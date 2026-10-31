import json, sys, time, urllib.request, urllib.parse
UA = {"User-Agent": "Serpentina-icon-builder/0.1 (personal app; it@m2c.eu)"}
FILES = {"aprilia":"File:Aprilia-logo.svg","bmw":"File:BMW logo (gray).svg","cfmoto":"File:CFMOTO Logo.svg",
 "ducati":"File:Ducati red logo.svg","harley_davidson":"File:Harley-Davidson logo.svg","honda":"File:Honda Logo.svg",
 "husqvarna":"File:Husqvarna logo ArtWW.svg","indian":"File:Indian Motorcycle logo.svg","kawasaki":"File:Kawasaki-logo.svg",
 "ktm":"File:KTM-Logo.svg","mv_agusta":"File:MV Agusta Logo.svg","piaggio":"File:Piaggio-logo.svg",
 "royal_enfield":"File:Royal Enfield logo.svg","suzuki":"File:Suzuki logo 2025 (symbol).svg",
 "triumph":"File:Triumph Motorcycles logo and claim 2015.svg","vespa":"File:Vespa-logo.svg","yamaha":"File:Yamaha Motor logo.svg"}
out = sys.argv[1]; meta = {}
for key, title in FILES.items():
    q = urllib.parse.urlencode(dict(action="query", prop="imageinfo", titles=title, iiprop="url|extmetadata", iiurlwidth=800, format="json"))
    page = next(iter(json.load(urllib.request.urlopen(urllib.request.Request("https://commons.wikimedia.org/w/api.php?" + q, headers=UA), timeout=30))["query"]["pages"].values()))
    ii = page["imageinfo"][0]; md = ii["extmetadata"]
    data = urllib.request.urlopen(urllib.request.Request(ii["thumburl"], headers=UA), timeout=60).read()
    open(f"{out}/{key}.png", "wb").write(data)
    meta[key] = {"title": title, "page": ii["descriptionurl"], "license": md.get("LicenseShortName", {}).get("value"),
                 "author": md.get("Artist", {}).get("value", "")}
    print(key, len(data), meta[key]["license"]); time.sleep(0.5)
json.dump(meta, open(f"{out}/meta.json", "w"), ensure_ascii=False, indent=1)
