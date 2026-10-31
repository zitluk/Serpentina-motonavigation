package cz.motonavigace

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import java.text.Normalizer
import kotlin.coroutines.coroutineContext

data class Place(
    val name: String,
    val description: String,
    val position: LatLong,
    /** Vzdálenost od referenčního bodu v metrech (null, když není poloha). */
    val distance: Double?,
)

/**
 * Offline vyhledávání v databázích bodů zájmu Mapsforge (*.poi, SQLite).
 * Tabulka poi_data má v `data` tagy oddělené \r včetně normalized_name
 * (malá písmena bez diakritiky), poi_index souřadnice.
 */
object PoiSearch {
    private const val CANDIDATES_PER_FILE = 400
    private const val MAX_RESULTS = 50

    private val coordinates = Regex("""^\s*(-?\d{1,2}(?:[.,]\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:[.,]\d+)?)\s*$""")

    fun normalize(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").trim()

    suspend fun search(query: String, near: LatLong?): List<Place> = withContext(Dispatchers.IO) {
        parseCoordinates(query)?.let { return@withContext listOf(it) }
        val q = normalize(query)
        if (q.length < 2) return@withContext emptyList()

        val found = mutableListOf<Pair<Int, Place>>()
        for (file in Storage.poiFiles()) {
            coroutineContext.ensureActive()
            val db = runCatching {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            }.getOrNull() ?: continue
            val pattern = "%normalized_name=%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            // Obce zvlášť, aby je v LIMITu nepřebily tisíce zastávek a obchodů se stejným jménem
            val queries = listOf(
                "d.data LIKE ? ESCAPE '\\' AND d.data LIKE '%place=%'",
                "d.data LIKE ? ESCAPE '\\'",
            )
            db.use { for (where in queries) {
                it.rawQuery(
                    "SELECT i.lat, i.lon, d.data FROM poi_data d JOIN poi_index i ON i.id = d.id " +
                        "WHERE $where LIMIT $CANDIDATES_PER_FILE",
                    arrayOf(pattern),
                ).use { c ->
                    while (c.moveToNext()) {
                        val tags = parseTags(c.getString(2))
                        val normalized = tags["normalized_name"] ?: continue
                        if (!normalized.contains(q)) continue
                        val pos = LatLong(c.getDouble(0), c.getDouble(1))
                        val place = Place(
                            name = tags["name"] ?: continue,
                            description = describe(tags),
                            position = pos,
                            distance = near?.sphericalDistance(pos),
                        )
                        found += score(tags, normalized, q) to place
                    }
                }
            } }
        }
        found
            .sortedWith(compareBy({ it.first }, { it.second.distance ?: 0.0 }))
            .map { it.second }
            .distinctBy { it.name to it.description }
            .take(MAX_RESULTS)
    }

    private fun parseTags(data: String): Map<String, String> = data.split('\r').mapNotNull {
        val eq = it.indexOf('=')
        if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else null
    }.toMap()

    /** Nižší = lepší. Obce mají přednost před ostatními body, přesná shoda před částečnou. */
    private fun score(tags: Map<String, String>, normalized: String, q: String): Int {
        val match = when {
            normalized == q -> 0
            normalized.startsWith(q) -> 10
            else -> 20
        }
        val kind = when (tags["place"]) {
            "city" -> 0
            "town" -> 1
            "village" -> 2
            "suburb", "hamlet", "quarter", "neighbourhood", "locality", "isolated_dwelling" -> 3
            else -> 5
        }
        return match + kind
    }

    private fun describe(tags: Map<String, String>): String {
        val en = Settings.isEnglish()
        fun t(cs: String, english: String) = if (en) english else cs
        fun label(map: Map<String, Pair<String, String>>, key: String) = map[key]?.let { if (en) it.second else it.first }
        tags["place"]?.let { return label(PLACES, it) ?: t("Místo", "Place") }
        tags["amenity"]?.let { a -> label(AMENITIES, a)?.let { return it } }
        tags["tourism"]?.let { x -> label(TOURISM, x)?.let { return it } }
        if (tags["natural"] == "peak") return listOfNotNull(t("Vrchol", "Peak"), tags["ele"]?.let { "$it m" }).joinToString(" · ")
        if (tags["mountain_pass"] == "yes") return t("Horský průsmyk", "Mountain pass")
        if (tags["railway"] == "station") return t("Nádraží", "Railway station")
        if (tags["shop"] != null) return t("Obchod", "Shop")
        return tags["amenity"] ?: tags["tourism"] ?: tags["historic"] ?: tags["leisure"] ?: t("Bod zájmu", "Point of interest")
    }

    private fun parseCoordinates(query: String): Place? {
        val m = coordinates.matchEntire(query) ?: return null
        val lat = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Place("%.5f, %.5f".format(lat, lon), if (Settings.isEnglish()) "Coordinates" else "Souřadnice", LatLong(lat, lon), null)
    }

    /** OSM hodnota → (česky, anglicky). */
    private val PLACES = mapOf(
        "city" to ("Město" to "City"), "town" to ("Město" to "Town"), "village" to ("Obec" to "Village"),
        "hamlet" to ("Osada" to "Hamlet"), "suburb" to ("Část obce" to "Suburb"), "quarter" to ("Čtvrť" to "Quarter"),
        "neighbourhood" to ("Čtvrť" to "Neighbourhood"), "locality" to ("Lokalita" to "Locality"),
        "isolated_dwelling" to ("Samota" to "Isolated dwelling"),
    )
    private val AMENITIES = mapOf(
        "fuel" to ("Čerpací stanice" to "Fuel station"), "restaurant" to ("Restaurace" to "Restaurant"),
        "cafe" to ("Kavárna" to "Café"), "pub" to ("Hospoda" to "Pub"), "fast_food" to ("Rychlé občerstvení" to "Fast food"),
        "parking" to ("Parkoviště" to "Parking"), "hospital" to ("Nemocnice" to "Hospital"),
        "pharmacy" to ("Lékárna" to "Pharmacy"), "police" to ("Policie" to "Police"),
        "post_office" to ("Pošta" to "Post office"), "bank" to ("Banka" to "Bank"), "atm" to ("Bankomat" to "ATM"),
        "motorcycle_parking" to ("Parkování pro motorky" to "Motorcycle parking"),
        "charging_station" to ("Nabíjecí stanice" to "Charging station"),
    )
    private val TOURISM = mapOf(
        "hotel" to ("Hotel" to "Hotel"), "motel" to ("Motel" to "Motel"), "guest_house" to ("Penzion" to "Guest house"),
        "camp_site" to ("Kemp" to "Campsite"), "attraction" to ("Turistická atrakce" to "Attraction"),
        "viewpoint" to ("Vyhlídka" to "Viewpoint"), "museum" to ("Muzeum" to "Museum"),
        "information" to ("Turistické informace" to "Tourist information"), "hostel" to ("Hostel" to "Hostel"),
    )
}
