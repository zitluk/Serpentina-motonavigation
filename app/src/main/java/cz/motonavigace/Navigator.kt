package cz.motonavigace

import org.mapsforge.core.model.LatLong
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/** Stav navigace po zpracování nové polohy. */
data class NavState(
    /** Nejbližší bod na trase (přichycená poloha). */
    val snapped: LatLong,
    /** Index segmentu trasy, na kterém jezdec je. */
    val segment: Int,
    val remainingMeters: Double,
    val remainingSeconds: Double,
    val next: Maneuver?,
    /** Vzdálenost k dalšímu manévru v metrech. */
    val toNext: Double,
    val offRoute: Boolean,
    val arrived: Boolean,
    /** Směr trasy v místě jezdce (stupně od severu) – stabilnější než kurz z GPS. */
    val routeBearing: Float,
)

/**
 * Sleduje polohu na trase a rozhoduje o hlasových pokynech.
 * Neřeší UI ani přepočet – ten dělá volající, když [NavState.offRoute].
 */
class Navigator(val route: Route, private val speak: (String) -> Unit) {

    private var segment = 0
    private var offRouteFixes = 0
    /** Index manévru → už ohlášené fáze (0 = předběžně, 1 = teď). */
    private val announced = mutableMapOf<Int, MutableSet<Int>>()
    private var arrivedAnnounced = false

    fun update(position: LatLong, accuracy: Float, speedMps: Float): NavState {
        val pts = route.points
        // Hledáme kousek dopředu i dozadu od posledního segmentu, ať nepřeskočíme na souběžnou část trasy
        val from = max(0, segment - 10)
        val to = minOf(pts.size - 2, segment + 400)
        var best = segment
        var bestDist = Double.MAX_VALUE
        var bestT = 0.0
        for (i in from..to) {
            val (d, t) = distanceToSegment(position, pts[i], pts[i + 1])
            if (d < bestDist) { bestDist = d; best = i; bestT = t }
        }
        segment = best
        val segLen = route.distances[best + 1] - route.distances[best]
        val along = route.distances[best] + bestT * segLen
        val total = route.distances.last()
        val remaining = max(0.0, total - along)
        val segTime = route.times[best + 1] - route.times[best]
        val remainingTime = max(0.0, route.times.last() - (route.times[best] + bestT * segTime))
        val snapped = LatLong(
            pts[best].latitude + (pts[best + 1].latitude - pts[best].latitude) * bestT,
            pts[best].longitude + (pts[best + 1].longitude - pts[best].longitude) * bestT,
        )

        val tolerance = max(40.0, accuracy * 1.5)
        offRouteFixes = if (bestDist > tolerance) offRouteFixes + 1 else 0
        val offRoute = offRouteFixes >= 3

        val nextIdx = route.maneuvers.indexOfFirst { it.distance > along + 5 && it.command != "C" }
        val next = route.maneuvers.getOrNull(nextIdx)
        val toNext = next?.let { it.distance - along } ?: remaining
        val arrived = remaining < 30

        if (arrived && !arrivedAnnounced) {
            arrivedAnnounced = true
            speak(if (Settings.isEnglish()) "You have arrived." else "Jste v cíli.")
        } else if (next != null && !offRoute) {
            announce(nextIdx, next, toNext, speedMps)
        }
        return NavState(snapped, best, remaining, remainingTime, next, toNext, offRoute, arrived, bearingAhead(best))
    }

    private fun announce(idx: Int, m: Maneuver, toNext: Double, speedMps: Float) {
        val fast = speedMps > 20 // ~70 km/h
        val early = if (fast) 1500.0 else 500.0
        val now = if (fast) 200.0 else 80.0
        val done = announced.getOrPut(idx) { mutableSetOf() }
        when {
            toNext <= now && 1 !in done -> {
                done += 1; done += 0
                speak(instruction(m).replaceFirstChar { it.uppercase() } + ".")
            }
            toNext <= early && toNext > now * 1.5 && 0 !in done -> {
                done += 0
                speak(if (Settings.isEnglish()) "In ${spokenDistanceEn(toNext)}, ${instruction(m)}."
                    else "Za ${spokenDistance(toNext)} ${instruction(m)}.")
            }
        }
    }

    /** Směr ~30 m dopředu po trase, ať se mapa netočí na každém krátkém úseku. */
    private fun bearingAhead(segment: Int): Float {
        val pts = route.points
        val from = pts[segment]
        var j = segment + 1
        while (j < pts.lastIndex && route.distances[j] - route.distances[segment] < 30) j++
        val to = pts[j]
        val dx = (to.longitude - from.longitude) * cos(Math.toRadians(from.latitude))
        val dy = to.latitude - from.latitude
        return ((Math.toDegrees(atan2(dx, dy)) + 360) % 360).toFloat()
    }

    private fun distanceToSegment(p: LatLong, a: LatLong, b: LatLong): Pair<Double, Double> {
        // Lokální rovinná aproximace v metrech
        val k = cos(Math.toRadians(p.latitude)) * 111_320.0
        val ax = (a.longitude - p.longitude) * k; val ay = (a.latitude - p.latitude) * 110_540.0
        val bx = (b.longitude - p.longitude) * k; val by = (b.latitude - p.latitude) * 110_540.0
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val x = ax + t * dx; val y = ay + t * dy
        return sqrt(x * x + y * y) to t
    }

    companion object {
        /** Pokyn v podobě „odbočte vpravo“ (malým písmenem, bez tečky). */
        fun instruction(m: Maneuver): String = if (Settings.isEnglish()) instructionEn(m) else when (m.command) {
            "TL" -> "odbočte vlevo"
            "TR" -> "odbočte vpravo"
            "TSLL" -> "odbočte mírně vlevo"
            "TSLR" -> "odbočte mírně vpravo"
            "TSHL" -> "odbočte ostře vlevo"
            "TSHR" -> "odbočte ostře vpravo"
            "KL" -> "držte se vlevo"
            "KR" -> "držte se vpravo"
            "EL" -> "sjeďte vlevo"
            "ER" -> "sjeďte vpravo"
            "TU" -> "otočte se"
            "RNDB", "RNLB" -> if (m.exit > 0) "na kruhovém objezdu vyjeďte ${ordinal(m.exit)} výjezdem" else "vjeďte na kruhový objezd"
            "END" -> "jste v cíli"
            else -> "pokračujte rovně"
        }

        /** Šipka pro panel s dalším manévrem. */
        fun arrow(m: Maneuver?): String = when (m?.command) {
            "TL", "TSHL" -> "↰"
            "TR", "TSHR" -> "↱"
            "TSLL", "KL", "EL" -> "↖"
            "TSLR", "KR", "ER" -> "↗"
            "TU" -> "↶"
            "RNDB", "RNLB" -> "⟳"
            "END" -> "⚑"
            else -> "↑"
        }

        private fun instructionEn(m: Maneuver): String = when (m.command) {
            "TL" -> "turn left"
            "TR" -> "turn right"
            "TSLL" -> "turn slightly left"
            "TSLR" -> "turn slightly right"
            "TSHL" -> "turn sharp left"
            "TSHR" -> "turn sharp right"
            "KL" -> "keep left"
            "KR" -> "keep right"
            "EL" -> "take the exit on the left"
            "ER" -> "take the exit on the right"
            "TU" -> "make a U-turn"
            "RNDB", "RNLB" -> if (m.exit > 0) "at the roundabout, take the ${ordinalEn(m.exit)} exit" else "enter the roundabout"
            "END" -> "you have arrived"
            else -> "continue straight"
        }

        private fun ordinalEn(n: Int) = when (n) {
            1 -> "first"; 2 -> "second"; 3 -> "third"; 4 -> "fourth"
            5 -> "fifth"; 6 -> "sixth"; else -> "$n."
        }

        private fun spokenDistanceEn(m: Double): String = when {
            m >= 950 -> {
                val km = Math.round(m / 100) / 10.0
                if (km == km.toInt().toDouble()) {
                    val k = km.toInt()
                    if (k == 1) "1 kilometer" else "$k kilometers"
                } else "$km kilometers"
            }
            m >= 100 -> "${(Math.round(m / 50) * 50)} meters"
            else -> "${(Math.round(m / 10) * 10)} meters"
        }

        private fun ordinal(n: Int) = when (n) {
            1 -> "prvním"; 2 -> "druhým"; 3 -> "třetím"; 4 -> "čtvrtým"
            5 -> "pátým"; 6 -> "šestým"; else -> "$n."
        }

        private fun spokenDistance(m: Double): String = when {
            m >= 950 -> {
                val km = Math.round(m / 100) / 10.0
                if (km == km.toInt().toDouble()) {
                    val k = km.toInt()
                    if (k == 1) "1 kilometr" else if (k in 2..4) "$k kilometry" else "$k kilometrů"
                } else "${km.toString().replace('.', ',')} kilometru"
            }
            m >= 100 -> "${(Math.round(m / 50) * 50)} metrů"
            else -> "${(Math.round(m / 10) * 10)} metrů"
        }
    }
}
