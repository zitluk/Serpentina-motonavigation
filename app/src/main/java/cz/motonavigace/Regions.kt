package cz.motonavigace

import android.content.Context
import org.json.JSONObject
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Region ke stažení: mapa a body zájmu Mapsforge + routovací segmenty BRouteru, které ho pokrývají.
 * Katalog celého světa je v assets/world.json (vytváří ho tools/world_catalog.py).
 */
data class Region(
    /** Cesta na serveru bez přípony (europe/italy/isole) – zároveň stálý klíč stahování. */
    val path: String,
    /** Kód země ISO 3166 (název se ukáže v jazyce aplikace), null = jen anglický [englishName]. */
    val country: String?,
    val englishName: String?,
    /** Část země (Bayern, Isole…), null = celá země. */
    val part: String?,
    val mapSize: Long,
    val poiSize: Long,
    /** Názvy rd5 souborů (např. E15_N45.rd5) pokrývajících region. */
    val segments: List<String>,
    /** Další slova pro hledání („Sardinie“ u italských ostrovů). */
    val keywords: String = "",
    /** Název souborů v telefonu, když se stejně jmenuje víc map (asia/georgia × us/georgia). */
    private val fileStem: String = path.substringAfterLast('/'),
) {
    val name get() = path
    val mapFileName get() = "$fileStem.map"
    val mapUrl get() = "$MAPS_BASE/$path.map"
    val poiFileName get() = "$fileStem.poi"
    val poiUrl get() = "$POIS_BASE/$path.poi"
    val continent get() = path.substringBefore('/')

    /** Název podle jazyka aplikace, u části země „Země – část“. */
    fun label(locale: Locale = appLocale()): String {
        val countryName = country?.let { Locale("", it).getDisplayCountry(locale) }?.takeIf { it.isNotBlank() } ?: englishName.orEmpty()
        return if (part != null) "$countryName – $part" else countryName
    }

    /** Přibližná velikost ke stažení: mapa, body zájmu a dosud nestažené routovací dlaždice. */
    fun downloadSize(hasSegment: (String) -> Boolean): Long =
        mapSize + poiSize + segments.filterNot(hasSegment).sumOf { Catalog.segmentSize(it) }

    companion object {
        const val MAPS_BASE = "https://download.mapsforge.org/maps/v5"
        const val POIS_BASE = "https://download.mapsforge.org/pois"
        const val SEGMENTS_BASE = "https://brouter.de/brouter/segments4"

        val ALL: List<Region> get() = Catalog.all

        fun segmentName(lon: Int, lat: Int): String {
            val slon = if (lon < 0) "W${-lon}" else "E$lon"
            val slat = if (lat < 0) "S${-lat}" else "N$lat"
            return "${slon}_$slat.rd5"
        }

        fun segmentUrl(name: String) = "$SEGMENTS_BASE/$name"

        fun appLocale(): Locale = if (Settings.isEnglish()) Locale.ENGLISH else Locale.forLanguageTag("cs")
    }
}

/** Katalog map celého světa z assets/world.json. */
object Catalog {
    @Volatile private var loaded: List<Region>? = null
    private var segmentSizes: Map<String, Long> = emptyMap()
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    val all: List<Region>
        get() = loaded ?: synchronized(this) {
            loaded ?: parse(appContext.assets.open("world.json").bufferedReader().use { it.readText() }).also { loaded = it }
        }

    fun segmentSize(name: String): Long = segmentSizes[name] ?: 0L

    /** Rozbor katalogu (oddělený kvůli testům bez Androidu). */
    internal fun parse(json: String): List<Region> {
        val root = JSONObject(json)
        val segs = root.getJSONObject("segments")
        segmentSizes = segs.keys().asSequence().associateWith { segs.getLong(it) }
        val maps = root.getJSONArray("maps")
        return List(maps.length()) { i ->
            val m = maps.getJSONObject(i)
            val g = m.getJSONArray("g")
            Region(
                path = m.getString("p"),
                country = m.optString("c").ifEmpty { null },
                englishName = m.optString("n").ifEmpty { null },
                part = m.optString("r").ifEmpty { null },
                mapSize = m.getLong("s"),
                poiSize = m.optLong("ps"),
                segments = List(g.length()) { g.getString(it) },
                keywords = m.optString("k"),
                fileStem = m.optString("f").ifEmpty { m.getString("p").substringAfterLast('/') },
            )
        }
    }

    /** Text bez diakritiky malými písmeny – „cesko“ najde Česko, „ital“ Itálii. */
    fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

    /**
     * Regiony odpovídající dotazu (název v jazyce aplikace i anglicky, část země, cesta na serveru),
     * celé země před jejich částmi, abecedně podle jazyka.
     */
    fun search(query: String, regions: List<Region> = all, locale: Locale = Region.appLocale()): List<Region> {
        val words = normalize(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val collator = Collator.getInstance(locale)
        return regions
            .map { it to normalize(it.label(locale) + " " + it.label(Locale.ENGLISH) + " " + it.path.replace('-', ' ') + " " + it.keywords) }
            .filter { (_, text) -> words.all { it in text } }
            .map { it.first }
            .sortedWith(compareBy<Region, String>(collator) { r -> r.label(locale).substringBefore(" – ") }.thenBy { it.part != null }.thenBy(collator) { it.label(locale) })
    }
}
