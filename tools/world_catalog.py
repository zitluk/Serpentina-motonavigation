"""
Katalog map celého světa pro obrazovku Stažení map.

Projde server map Mapsforge (maps/v5) a bodů zájmu (pois), ke každé mapě zjistí z hlavičky
souboru ohraničení a přesnou velikost a přiřadí routovací dlaždice BRouteru, které ji pokrývají
(jen ty, které na serveru existují – nad mořem žádné nejsou). Výsledek uloží do
app/src/main/assets/world.json; země dostanou kód ISO, podle kterého aplikace ukáže
český nebo anglický název.

Spuštění (z kořene repozitáře):
    uv run --with pycountry python tools/world_catalog.py
"""
import concurrent.futures
import json
import math
import re
import struct
import sys
import urllib.request
from pathlib import Path

import pycountry

MAPS = "https://download.mapsforge.org/maps/v5/"
POIS = "https://download.mapsforge.org/pois/"
SEGMENTS = "https://brouter.de/brouter/segments4/"
OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/world.json"
UA = {"User-Agent": "Serpentina-catalog/1.0"}

# Názvy souborů, které pycountry sám nenajde (nebo najde špatně)
ISO_OVERRIDES = {
    "great-britain": "GB", "united-kingdom": "GB", "ireland-and-northern-ireland": "IE",
    "czech-republic": "CZ", "kosovo": "XK", "macedonia": "MK", "russia": "RU", "turkey": "TR",
    "bosnia-herzegovina": "BA", "moldova": "MD", "us": "US", "usa": "US", "south-korea": "KR",
    "north-korea": "KP", "laos": "LA", "vietnam": "VN", "iran": "IR", "syria": "SY",
    "tanzania": "TZ", "congo-democratic-republic": "CD", "congo-brazzaville": "CG",
    "ivory-coast": "CI", "cape-verde": "CV", "bolivia": "BO", "venezuela": "VE",
    "taiwan": "TW", "palestine": "PS", "brunei": "BN", "east-timor": "TL", "micronesia": "FM",
    "saint-helena-ascension-and-tristan-da-cunha": "SH", "swaziland": "SZ", "burma": "MM",
    "myanmar": "MM", "the-bahamas": "BS", "bahamas": "BS", "gcc-states": None,
    "israel-and-palestine": "IL", "senegal-and-gambia": "SN", "haiti-and-domrep": "HT",
    "guernsey-jersey": "GG", "malaysia-singapore-brunei": "MY", "guinea-bissau": "GW", "comores": "KM",
    "south-africa-and-lesotho": "ZA", "wallis-et-futuna": "WF", "pitcairn-islands": "PN",
    "kiribati": "KI", "tuvalu": "TV", "fiji": "FJ", "new-zealand": "NZ", "polynesie-francaise": "PF",
    "gcc-states": None, "ile-de-clipperton": None, "american-oceania": None,
}

# Soubor, který je částí země, i když neleží v její složce (ostrovy, části USA a Ruska)
PART_OF = {"azores": "PT", "canary-islands": "ES", "ile-de-clipperton": "FR"}

# Další slova pro hledání u částí zemí, jejichž název na serveru oblast nepojmenuje (česky i anglicky)
KEYWORDS = {
    "europe/italy/isole": "Sardinie Sardinia Sardegna Sicílie Sicily Sicilia ostrovy islands",
    "europe/italy/nord-est": "Alpy Alps Dolomity Dolomites Benátky Venice Jižní Tyrolsko South Tyrol Gardské jezero Garda",
    "europe/italy/nord-ovest": "Alpy Alps Piemont Piedmont Lombardie Lombardy Ligurie Liguria Aosta",
    "europe/italy/centro": "Toskánsko Tuscany Řím Rome Umbrie Umbria",
    "europe/italy/sud": "Neapol Naples Amalfi Kalábrie Calabria Apulie Puglia",
    "europe/spain/islas-baleares": "Baleárské ostrovy Balearic Islands Mallorca Ibiza Menorca",
    "europe/canary-islands": "Kanárské ostrovy Tenerife Gran Canaria",
    "europe/azores": "Azory",
}

# Ohraničení ostrovů zabírá i moře a cizí pevninu – routovací dlaždice jen tam, kde je souš regionu
SEGMENT_OVERRIDES = {
    # Sardinie (E5_N35, E5_N40) a Sicílie (E10_N35, E15_N35)
    "europe/italy/isole": ["E5_N35.rd5", "E5_N40.rd5", "E10_N35.rd5", "E15_N35.rd5"],
}


def get(url: str, headers: dict | None = None) -> tuple[bytes, dict]:
    req = urllib.request.Request(url, headers={**UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read(), dict(r.headers)


def listing(url: str) -> list[tuple[str, str]]:
    """(název, velikost z výpisu) položek adresáře; adresáře končí lomítkem."""
    html = get(url)[0].decode("utf-8", "replace")
    rows = re.findall(r'<a href="([^"?/][^"]*)">[^<]*</a>\s*</td><td[^>]*>[^<]*</td><td[^>]*>\s*([^<]*?)\s*</td>', html)
    if rows:
        return rows
    return [(h, "") for h in re.findall(r'<a href="([^"?/][^"]*)"', html)]


def human_size(s: str) -> int:
    m = re.match(r"([\d.]+)([KMG]?)", s.strip())
    if not m:
        return 0
    return int(float(m.group(1)) * {"": 1, "K": 1 << 10, "M": 1 << 20, "G": 1 << 30}[m.group(2)])


def crawl(base: str, suffix: str, path: str = "") -> dict[str, int]:
    """Soubory s příponou [suffix] pod [base] → velikost podle výpisu."""
    found = {}
    for name, size in listing(base + path):
        if name.endswith("/"):
            if path == "" and name == "world/":
                continue
            found.update(crawl(base, suffix, path + name))
        elif name.endswith(suffix):
            found[path + name[: -len(suffix)]] = human_size(size)
    return found


def map_header(path: str) -> tuple[int, list[float]]:
    """Přesná velikost a ohraničení (minLat, minLon, maxLat, maxLon) z hlavičky mapy Mapsforge."""
    data, headers = get(MAPS + path + ".map", {"Range": "bytes=0-63"})
    if data[:20] != b"mapsforge binary OSM":
        raise ValueError(path)
    total = int(headers.get("Content-Range", "/0").split("/")[-1])
    min_lat, min_lon, max_lat, max_lon = struct.unpack(">iiii", data[44:60])
    return total, [round(v / 1e6, 4) for v in (min_lat, min_lon, max_lat, max_lon)]


def segment_sizes() -> dict[str, int]:
    text = get(SEGMENTS)[0].decode()
    return {m.group(1): int(m.group(2)) for m in re.finditer(r'href="(\w+\.rd5)">.*?\s(\d+)\s*$', text, re.M)}


def segments_for(bbox: list[float], available: dict[str, int]) -> list[str]:
    min_lat, min_lon, max_lat, max_lon = bbox
    result = []
    lat = math.floor(min_lat / 5) * 5
    while lat < max_lat:
        lon = math.floor(min_lon / 5) * 5
        while lon < max_lon:
            name = f"{'W' if lon < 0 else 'E'}{abs(lon)}_{'S' if lat < 0 else 'N'}{abs(lat)}.rd5"
            if name in available:
                result.append(name)
            lon += 5
        lat += 5
    return result


def iso_for(slug: str) -> str | None:
    if slug in ISO_OVERRIDES:
        return ISO_OVERRIDES[slug]
    name = slug.replace("-", " ")
    try:
        return pycountry.countries.lookup(name).alpha_2
    except LookupError:
        try:
            return pycountry.countries.search_fuzzy(name)[0].alpha_2
        except LookupError:
            return None


def pretty(slug: str) -> str:
    return " ".join(w.capitalize() if w not in ("and", "of", "the", "da", "di") else w for w in slug.split("-"))


def main() -> None:
    print("Mapy…", file=sys.stderr)
    maps = crawl(MAPS, ".map")
    print(f"  {len(maps)} map, body zájmu…", file=sys.stderr)
    pois = crawl(POIS, ".poi")
    segs = segment_sizes()
    print(f"  {len(pois)} souborů bodů zájmu, {len(segs)} dlaždic BRouteru; hlavičky map…", file=sys.stderr)
    with concurrent.futures.ThreadPoolExecutor(8) as pool:
        headers = dict(zip(maps, pool.map(map_header, maps)))

    entries = []
    names = [p.rsplit("/", 1)[-1] for p in maps]
    for path in sorted(maps):
        size, bbox = headers[path]
        parts = path.split("/")
        entry = {"p": path, "b": bbox, "s": size, "g": SEGMENT_OVERRIDES.get(path) or segments_for(bbox, segs)}
        # Bez routovacích dat (odlehlé ostrovy) se nedá navigovat – takovou mapu nenabízíme
        if not entry["g"]:
            continue
        # Stejný název souboru v různých složkách (asia/georgia × us/georgia) – mapy leží v telefonu vedle sebe
        if names.count(path.rsplit("/", 1)[-1]) > 1:
            entry["f"] = path.replace("/", "_")
        if path in pois:
            entry["ps"] = pois[path]
        if path in KEYWORDS:
            entry["k"] = KEYWORDS[path]
        # continent/country nebo continent/country/region; Rusko je na serveru samo „kontinentem“
        country_slug, region = parts[1], (pretty(parts[-1]) if len(parts) > 2 else None)
        if parts[0] == "russia":
            iso, region = "RU", pretty(parts[1])
        elif country_slug.startswith("us-"):
            iso, region = "US", pretty(country_slug[3:])
        elif country_slug in PART_OF:
            iso, region = PART_OF[country_slug], pretty(country_slug)
        else:
            # Velké ostrovní státy jsou rozdělené na fiji-1, fiji-2…
            numbered = re.fullmatch(r"(.+)-(\d)", country_slug)
            if numbered:
                country_slug, region = numbered.group(1), numbered.group(2)
            iso = iso_for(country_slug)
        if iso:
            entry["c"] = iso
        else:
            entry["n"] = pretty(country_slug)
        if region:
            entry["r"] = region
        entries.append(entry)
    OUT.write_text(json.dumps({"segments": segs, "maps": entries}, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    no_iso = sorted({e["p"] for e in entries if "c" not in e})
    print(f"Hotovo: {len(entries)} map -> {OUT.name} ({OUT.stat().st_size // 1024} kB)", file=sys.stderr)
    print("Bez kódu ISO (ukážou se anglicky):", ", ".join(no_iso), file=sys.stderr)
    # Kontrola přiřazení: složka země -> kód
    pairs = sorted({(e["p"].split("/")[1], e.get("c", "-")) for e in entries})
    print(" ".join(f"{s}={c}" for s, c in pairs), file=sys.stderr)


if __name__ == "__main__":
    main()
