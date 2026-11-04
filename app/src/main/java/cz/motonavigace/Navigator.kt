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
    /** Jezdec je u trasy – ikonu jde přichytit na ni; jinak se ukazuje skutečná poloha z GPS. */
    val nearRoute: Boolean,
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
    /** Odpuštěná vzdálenost od začátku trasy (m), záporná = zatím neznámá; 0 po najetí na trasu. */
    private var startSlack = -1.0
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

        // Jezdec, který úmyslně odbočí, má novou trasu dostat hned: stačí dvě polohy mimo trasu,
        // nebo jedna, když je od ní už daleko
        // Nová trasa začíná na nejbližší silnici – jezdec na parkovišti nebo mimo cestu je od ní
        // hned na začátku dál; tuhle vzdálenost (s rezervou) odpustíme, dokud na trasu nenajede,
        // jinak by se trasa přepočítávala pořád dokola
        if (startSlack < 0) startSlack = bestDist + START_SLACK_METERS
        val tolerance = max(OFF_ROUTE_METERS, accuracy * 1.5)
        val nearRoute = bestDist <= tolerance
        if (nearRoute) startSlack = 0.0
        offRouteFixes = if (bestDist <= max(tolerance, startSlack)) 0 else offRouteFixes + 1
        val offRoute = offRouteFixes >= OFF_ROUTE_FIXES || bestDist > maxOf(FAR_OFF_ROUTE_METERS, accuracy * 3.0, startSlack)

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
        return NavState(snapped, best, remaining, remainingTime, next, toNext, offRoute, nearRoute, arrived, bearingAhead(best))
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
                speak(if (Settings.isEnglish()) "In ${spokenDistanceEn(toNext)}, ${instruction(m)}${laneAdvice(m)}."
                    else "Za ${spokenDistance(toNext)} ${instruction(m)}${laneAdvice(m)}.")
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
        /** Dál od trasy (m) už jezdec po trase nejede. */
        private const val OFF_ROUTE_METERS = 35.0
        /** Kolik poloh za sebou mimo trasu stačí na přepočet. */
        private const val OFF_ROUTE_FIXES = 2
        /** Takhle daleko od trasy (m) se přepočítá hned při první poloze. */
        private const val FAR_OFF_ROUTE_METERS = 80.0
        /** Rezerva k počáteční vzdálenosti od trasy (m), než se začne znovu přepočítávat. */
        private const val START_SLACK_METERS = 30.0

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
            // Pravostranný provoz objíždí proti směru hodinových ručiček, levostranný po směru
            "RNDB" -> "⟲"
            "RNLB" -> "⟳"
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

        /** Doplněk k předběžnému hlášení: „, držte se v levém pruhu“. */
        private fun laneAdvice(m: Maneuver): String {
            val hint = m.lanes ?: return ""
            val single = hint.recommended.size == 1
            return when (hint.side) {
                LaneHint.Side.LEFT -> if (Settings.isEnglish()) ", use the left lane${if (single) "" else "s"}"
                    else if (single) ", zařaďte se do levého pruhu" else ", držte se v levých pruzích"
                LaneHint.Side.RIGHT -> if (Settings.isEnglish()) ", use the right lane${if (single) "" else "s"}"
                    else if (single) ", zařaďte se do pravého pruhu" else ", držte se v pravých pruzích"
                LaneHint.Side.ANY -> ""
            }
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
