package cz.motonavigace

import org.mapsforge.core.model.LatLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Vygenerovaná projížďka: okruh ze startu zpět na start. */
class RoundTrip(
    val route: Route,
    /** Průjezdní body okruhu (bez startu a cíle). */
    val shaping: List<LatLong>,
)

/**
 * Projížďka po okolí: průjezdní body leží na kružnici procházející startem,
 * směr se losuje. Silnice trasu prodlužují oproti kružnici zhruba 1,4×;
 * když výsledná délka nesedí, kružnici přepočítáme podle skutečné délky.
 */
object RoundTripPlanner {
    private const val ROAD_FACTOR = 1.4
    private const val MAX_ATTEMPTS = 3
    /** Tolerance délky, se kterou jsme spokojeni (±12 %). */
    private const val TOLERANCE = 0.12

    suspend fun generate(
        start: LatLong,
        lengthKm: Int,
        options: RouteOptions,
        random: Random = Random.Default,
    ): RoundTrip {
        val target = lengthKm * 1000.0
        val direction = random.nextDouble(360.0)
        // Víc bodů u delších okruhů, ať trasa drží tvar a nevrací se stejnou cestou
        val points = (lengthKm / 25).coerceIn(4, 8)
        var radius = target / (2 * PI * ROAD_FACTOR)

        var best: RoundTrip? = null
        repeat(MAX_ATTEMPTS) {
            val shaping = circle(start, radius, direction, points)
            val route = RoutePlanner.calculate(listOf(start) + shaping + start, options, looseWaypoints = true)
            val candidate = RoundTrip(route, shaping)
            if (best == null || abs(route.lengthMeters - target) < abs(best!!.route.lengthMeters - target)) best = candidate
            val ratio = route.lengthMeters / target
            if (abs(ratio - 1) <= TOLERANCE) return candidate
            radius /= ratio
        }
        return best!!
    }

    /**
     * [count] bodů na kružnici o poloměru [radius] m, jejíž střed leží od startu
     * ve směru [direction] (stupně od severu); start je tedy na kružnici.
     */
    fun circle(start: LatLong, radius: Double, direction: Double, count: Int): List<LatLong> {
        val center = destination(start, radius, direction)
        return (1..count).map { i -> destination(center, radius, direction + 180 + i * 360.0 / (count + 1)) }
    }

    private fun destination(from: LatLong, meters: Double, bearingDeg: Double): LatLong {
        val b = Math.toRadians(bearingDeg)
        val lat = from.latitude + meters * cos(b) / 111_320.0
        val lon = from.longitude + meters * sin(b) / (111_320.0 * cos(Math.toRadians(from.latitude)))
        return LatLong(lat, lon)
    }
}
