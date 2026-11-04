package cz.motonavigace

import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.TrackAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import java.io.File
import kotlin.math.floor
import kotlin.math.min

/** Typ trasy; [id] odpovídá parametru route_type v profilu moto.brf. */
enum class RouteType(val id: Int, @androidx.annotation.StringRes val label: Int) {
    FASTEST(0, R.string.route_fastest),
    SHORTEST(1, R.string.route_shortest),
    ECO(2, R.string.route_eco),
    CURVY(3, R.string.route_curvy),
    MAX_CURVY(4, R.string.route_max_curvy),
}

data class RouteOptions(
    val type: RouteType = RouteType.FASTEST,
    val avoidMotorways: Boolean = false,
    val avoidUnpaved: Boolean = true,
    val avoidFerries: Boolean = false,
)

/** Manévr na trase (odbočení, kruhový objezd, cíl…). */
data class Maneuver(
    /** Index bodu trasy, kde se manévr provádí. */
    val index: Int,
    /** Vzdálenost od startu trasy v metrech. */
    val distance: Double,
    /** Kód BRouteru: C, TL, TSLL, TSHL, TR, TSLR, TSHR, KL, KR, TU, RNDB, RNLB, EL, ER, END. */
    val command: String,
    /** Výjezd z kruhového objezdu, jinak 0. */
    val exit: Int,
    /** Tagy cesty, po které se k manévru přijíždí (pro navádění do pruhů). */
    val approachTags: String? = null,
) {
    val lanes: LaneHint? get() = LaneHint.forManeuver(this, approachTags)
}

class Route(
    val points: List<LatLong>,
    /** Kumulativní vzdálenost od startu v metrech pro každý bod. */
    val distances: DoubleArray,
    /** Kumulativní odhadovaný čas od startu v sekundách pro každý bod. */
    val times: DoubleArray,
    val maneuvers: List<Maneuver>,
    val lengthMeters: Int,
) {
    val durationSeconds get() = times.last().toInt()

    /** Bod trasy ve vzdálenosti [meters] od startu (mimo trasu se drží jejích konců). */
    fun pointAt(meters: Double): LatLong {
        if (points.size < 2 || meters <= 0) return points.first()
        if (meters >= distances.last()) return points.last()
        var i = distances.indexOfFirst { it > meters }.coerceAtLeast(1)
        val seg = distances[i] - distances[i - 1]
        val t = if (seg > 0) (meters - distances[i - 1]) / seg else 0.0
        val a = points[i - 1]
        val b = points[i]
        return LatLong(a.latitude + (b.latitude - a.latitude) * t, a.longitude + (b.longitude - a.longitude) * t)
    }
}

/** Chyba výpočtu trasy; text se skládá až v UI podle jazyka aplikace. */
class RoutingException(@androidx.annotation.StringRes val text: Int, val detail: String = "") : Exception(detail) {
    fun describe(context: android.content.Context): String = context.getString(text, detail)
}

/** Offline výpočet trasy přes BRouter nad staženými rd5 segmenty. */
object RoutePlanner {
    private const val PROFILE = "moto.brf"
    private const val MAX_RUNNING_MS = 60_000L
    /** Styl pokynů OsmAnd – jakýkoli pevný styl; 1 (auto) by pokyny vůbec negeneroval. */
    private const val TURN_INSTRUCTION_MODE = 3
    /** Okruh kolem místa sjetí z trasy, kterým nová trasa nemá vést zpátky (m). */
    private const val AVOID_RADIUS_METERS = 30.0
    /** Cena průjezdu tím okruhem – jen pokud jiná cesta není (slepá ulice), jinak se mu vyhne. */
    private const val AVOID_WEIGHT = 2000.0

    /** Segmenty, které musí být stažené, aby šlo trasu spočítat (aspoň pro všechny body trasy). */
    fun missingSegments(waypoints: List<LatLong>): List<String> = waypoints
        .map { Region.segmentName(floor5(it.longitude), floor5(it.latitude)) }
        .distinct()
        .filterNot(Storage::hasSegment)

    /**
     * @param looseWaypoints průjezdní body nemusí ležet u silnice (vygenerovaný okruh) –
     *   BRouter pak hledá nejbližší cestu i ve větší vzdálenosti.
     * @param avoidPoint místo, kde jezdec z trasy úmyslně sjel – nová trasa ho zpátky přes ně
     *   nevede, pokud to jde jinak (měkká zakázaná oblast BRouteru)
     */
    /**
     * Až [max] různých tras mezi stejnými body (BRouter alternativeIdx 0, 1, 2…).
     * První je vždy ta nejlepší; alternativy, které se s už nalezenými z velké části
     * kryjí, se zahodí. Chyba výpočtu alternativy nevadí, chyba hlavní trasy ano.
     */
    suspend fun calculateAlternatives(waypoints: List<LatLong>, options: RouteOptions, max: Int = 3): List<Route> {
        val result = mutableListOf(calculate(waypoints, options))
        for (idx in 1 until max) {
            val alt = try { calculate(waypoints, options, alternative = idx) } catch (_: RoutingException) { break }
            if (result.none { overlap(it, alt) > 0.85 }) result += alt
        }
        return result
    }

    /** Podíl bodů [b], které leží i na trase [a] (s přesností ~10 m). */
    private fun overlap(a: Route, b: Route): Double {
        fun key(p: LatLong) = "${Math.round(p.latitude * 1e4)}:${Math.round(p.longitude * 1e4)}"
        val keys = a.points.mapTo(HashSet(), ::key)
        return b.points.count { key(it) in keys }.toDouble() / b.points.size
    }

    suspend fun calculate(
        waypoints: List<LatLong>, options: RouteOptions,
        looseWaypoints: Boolean = false, alternative: Int = 0, avoidPoint: LatLong? = null,
    ): Route =
        withContext(Dispatchers.Default) {
            require(waypoints.size >= 2) { "Trasa potřebuje start a cíl" }
            val missing = missingSegments(waypoints)
            if (missing.isNotEmpty()) {
                throw RoutingException(R.string.error_missing_segments, missing.joinToString())
            }

            val rc = RoutingContext().apply {
                localFunction = File(Storage.profilesDir, PROFILE).absolutePath
                keyValues = mapOf(
                    "route_type" to options.type.id.toString(),
                    "avoid_motorways" to options.avoidMotorways.flag(),
                    "avoid_unpaved" to options.avoidUnpaved.flag(),
                    "avoid_ferries" to options.avoidFerries.flag(),
                    // Okruh vede jen po silnicích – bez účelových komunikací a obytných zón
                    "round_trip" to looseWaypoints.flag(),
                )
                turnInstructionMode = TURN_INSTRUCTION_MODE
                useDynamicDistance = looseWaypoints
                alternativeIdx = alternative
                avoidPoint?.let {
                    val nogo = OsmNodeNamed().apply {
                        name = "back"
                        isNogo = true
                        radius = AVOID_RADIUS_METERS
                        nogoWeight = AVOID_WEIGHT
                        ilon = ((it.longitude + 180.0) * 1_000_000 + 0.5).toInt()
                        ilat = ((it.latitude + 90.0) * 1_000_000 + 0.5).toInt()
                    }
                    nogopoints = mutableListOf(nogo).also(RoutingContext::prepareNogoPoints)
                }
            }
            val nodes = waypoints.mapIndexed { i, p ->
                OsmNodeNamed().apply {
                    name = if (i == 0) "from" else if (i == waypoints.lastIndex) "to" else "via$i"
                    ilon = ((p.longitude + 180.0) * 1_000_000 + 0.5).toInt()
                    ilat = ((p.latitude + 90.0) * 1_000_000 + 0.5).toInt()
                }
            }

            val engine = RoutingEngine(null, null, Storage.segmentsDir, nodes, rc)
            engine.quite = true
            engine.doRun(MAX_RUNNING_MS)

            engine.errorMessage?.let { throw RoutingException(R.string.error_routing, it) }
            val track = engine.foundTrack ?: throw RoutingException(R.string.error_no_route)

            val n = track.nodes.size
            // Trasa z jediného bodu (start = cíl) nemá délku a navigace by na ní spadla
            if (n < 2) throw RoutingException(R.string.error_no_route)
            val pts = ArrayList<LatLong>(n)
            val dist = DoubleArray(n)
            val time = DoubleArray(n)
            track.nodes.forEachIndexed { i, node ->
                pts += LatLong(node.iLat / 1_000_000.0 - 90.0, node.iLon / 1_000_000.0 - 180.0)
                if (i > 0) {
                    val d = node.calcDistance(track.nodes[i - 1]).toDouble()
                    dist[i] = dist[i - 1] + d
                    time[i] = time[i - 1] + d / (speedKmh(TrackAccess.wayTags(node)) / 3.6)
                }
            }
            val maneuvers = TrackAccess.hints(track)
                .filter { it.index in 0 until n }
                .map { Maneuver(it.index, dist[it.index], it.command, it.exit, it.approachTags) }
                .plus(Maneuver(n - 1, dist[n - 1], "END", 0))
                .distinctBy { it.index to it.command }
            Route(pts, dist, time, maneuvers, track.distance)
        }

    /**
     * Odhad cestovní rychlosti na motorce podle třídy silnice, omezený
     * rychlostním limitem z OSM. BRouter u nákladového profilu vrací
     * nepoužitelný čas, proto ho počítáme sami.
     */
    private fun speedKmh(wayTags: String?): Double {
        val tags = wayTags.orEmpty().split(' ').mapNotNull {
            val eq = it.indexOf('=')
            if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else null
        }.toMap()
        val typical = when (tags["highway"]) {
            "motorway" -> 120.0
            "trunk" -> 90.0
            "primary" -> 75.0
            "secondary" -> 65.0
            "tertiary" -> 55.0
            "unclassified" -> 45.0
            "motorway_link", "trunk_link" -> 55.0
            "primary_link", "secondary_link", "tertiary_link" -> 40.0
            "residential" -> 30.0
            "living_street" -> 15.0
            "service", "track" -> 20.0
            null -> if (tags["route"] == "ferry") 15.0 else 30.0
            else -> 30.0
        }
        val limit = tags["maxspeed"]?.toDoubleOrNull()
        val speed = if (limit != null && limit > 0) min(typical, limit) else typical
        return min(speed, Settings.bike.maxKmh)
    }

    private fun floor5(v: Double) = (floor(v / 5) * 5).toInt()

    /** BRouter čte hodnoty z keyValues jako čísla, booleany tedy jako 1/0. */
    private fun Boolean.flag() = if (this) "1" else "0"
}
