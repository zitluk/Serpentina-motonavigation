package cz.motonavigace

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/** Radar z OpenStreetMap (highway=speed_camera). */
data class SpeedCamera(
    val position: LatLong,
    /** Povolená rychlost v km/h, null = v datech chybí. */
    val maxspeed: Int?,
    /** Směr jízdy, který radar měří (stupně od severu), null = oba nebo neznámý. */
    val direction: Float?,
    /** Úsekové měření. */
    val section: Boolean,
)

/** Radar na navigované trase. */
data class CameraOnRoute(val camera: SpeedCamera, /** Vzdálenost od startu trasy (m). */ val along: Double)

/**
 * Radary z bodů zájmu, které se stahují s mapou (kategorie „Speed cameras“).
 * Za navigace se přiřadí k trase a jezdec dostane upozornění s předstihem.
 */
object SpeedCameras {
    private const val CATEGORY = "Speed cameras"
    /** Radar dál od trasy leží na jiné silnici (souběžná, nadjezd). */
    private const val MAX_FROM_ROUTE = 30.0
    /** Radar měřící směr víc odchýlený od směru trasy je pro protisměr. */
    private const val MAX_DIRECTION_DIFF = 60f

    @Volatile private var cache: Pair<String, List<SpeedCamera>>? = null

    /** Všechny radary ze stažených bodů zájmu; po stažení nebo smazání mapy se načtou znovu. */
    suspend fun all(): List<SpeedCamera> = withContext(Dispatchers.IO) {
        val files = Storage.poiFiles()
        val key = files.joinToString { "${it.name}:${it.length()}:${it.lastModified()}" }
        cache?.takeIf { it.first == key }?.let { return@withContext it.second }
        val result = mutableListOf<SpeedCamera>()
        for (file in files) {
            runCatching {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                    db.rawQuery(
                        "SELECT i.lat, i.lon, d.data FROM poi_categories c " +
                            "JOIN poi_category_map m ON m.category = c.id " +
                            "JOIN poi_index i ON i.id = m.id JOIN poi_data d ON d.id = m.id WHERE c.name = ?",
                        arrayOf(CATEGORY),
                    ).use { c ->
                        while (c.moveToNext()) result += parse(LatLong(c.getDouble(0), c.getDouble(1)), c.getString(2))
                    }
                }
            }
        }
        cache = key to result
        result
    }

    /** Radar z tagů bodu zájmu (oddělené \r jako v databázi Mapsforge). */
    internal fun parse(position: LatLong, data: String): SpeedCamera {
        val tags = data.split('\r').mapNotNull {
            val eq = it.indexOf('=')
            if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else null
        }.toMap()
        // „50“, „50 km/h“, „CZ:urban“ – bereme jen číslo
        val max = tags["maxspeed"]?.takeWhile { it.isDigit() }?.toIntOrNull()?.takeIf { it in 5..200 }
        // Směr ve stupních nebo světovou stranou; forward/backward/both bez znalosti cesty nerozlišíme
        val direction = tags["direction"]?.let { d -> d.toFloatOrNull() ?: COMPASS[d.uppercase()] }?.let { (it % 360 + 360) % 360 }
        val note = (tags["note"].orEmpty() + " " + tags["description"].orEmpty()).lowercase()
        val section = tags["enforcement"] == "average_speed" || tags.containsKey("average_speed") ||
            "úsekové" in note || "usekove" in note
        return SpeedCamera(position, max, direction, section)
    }

    private val COMPASS = listOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        .mapIndexed { i, s -> s to i * 22.5f }.toMap()

    /**
     * Radary ležící na trase a měřící směr jízdy po ní, seřazené od startu.
     * Radar se přiřadí k nejbližšímu místu trasy, pokud je do [MAX_FROM_ROUTE].
     */
    fun onRoute(route: Route, cameras: List<SpeedCamera>): List<CameraOnRoute> {
        val pts = route.points
        if (pts.size < 2 || cameras.isEmpty()) return emptyList()
        val margin = MAX_FROM_ROUTE / 111_000.0 * 2
        val minLat = pts.minOf { it.latitude } - margin
        val maxLat = pts.maxOf { it.latitude } + margin
        val lonMargin = margin / cos(Math.toRadians(pts[0].latitude))
        val minLon = pts.minOf { it.longitude } - lonMargin
        val maxLon = pts.maxOf { it.longitude } + lonMargin
        val result = mutableListOf<CameraOnRoute>()
        for (cam in cameras) {
            val p = cam.position
            if (p.latitude !in minLat..maxLat || p.longitude !in minLon..maxLon) continue
            var bestD = Double.MAX_VALUE
            var bestI = -1
            var bestT = 0.0
            for (i in 0 until pts.lastIndex) {
                val (d, t) = distanceToSegment(p, pts[i], pts[i + 1])
                if (d < bestD) { bestD = d; bestI = i; bestT = t }
            }
            if (bestI < 0 || bestD > MAX_FROM_ROUTE) continue
            val dir = cam.direction
            if (dir != null && abs(angleDiff(bearing(pts[bestI], pts[bestI + 1]), dir)) > MAX_DIRECTION_DIFF) continue
            val segLen = route.distances[bestI + 1] - route.distances[bestI]
            result += CameraOnRoute(cam, route.distances[bestI] + bestT * segLen)
        }
        return result.sortedBy { it.along }
    }

    /** Kurz úseku ve stupních od severu (pro krátké úseky stačí rovinná aproximace). */
    internal fun bearing(a: LatLong, b: LatLong): Float {
        val dx = (b.longitude - a.longitude) * cos(Math.toRadians(a.latitude))
        val dy = b.latitude - a.latitude
        return ((Math.toDegrees(atan2(dx, dy)) + 360) % 360).toFloat()
    }

    private fun angleDiff(a: Float, b: Float): Float = ((b - a) % 360 + 540) % 360 - 180

    /** Vzdálenost bodu od úsečky (m) a poloha nejbližšího bodu na ní (0…1). */
    private fun distanceToSegment(p: LatLong, a: LatLong, b: LatLong): Pair<Double, Double> {
        val k = cos(Math.toRadians(p.latitude)) * 111_320.0
        val ax = (a.longitude - p.longitude) * k; val ay = (a.latitude - p.latitude) * 110_540.0
        val bx = (b.longitude - p.longitude) * k; val by = (b.latitude - p.latitude) * 110_540.0
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val x = ax + t * dx; val y = ay + t * dy
        return Math.sqrt(x * x + y * y) to t
    }
}
