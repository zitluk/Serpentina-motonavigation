package cz.motonavigace

import org.mapsforge.core.model.LatLong

/** Souhrn jedné navigované jízdy: ujeto, čas, rychlosti a maxima z měřáku náklonu. */
data class RideSummary(
    val distanceMeters: Double,
    /** Celková doba od startu navigace (s). */
    val totalSeconds: Double,
    /** Doba v pohybu bez stání na křižovatkách a přestávek (s). */
    val movingSeconds: Double,
    val lean: LeanStats,
) {
    /** Průměrná rychlost v pohybu (m/s). */
    val avgSpeed: Double get() = if (movingSeconds > 0) distanceMeters / movingSeconds else 0.0
}

/** Sbírá vzdálenost a čas jízdy z poloh GPS (náklon a maxima měří [LeanMeter]). */
class RideTracker(private val startMillis: Long) {
    private var last: LatLong? = null
    private var lastMillis = startMillis
    private var distance = 0.0
    private var moving = 0.0

    fun onFix(p: LatLong, accuracy: Float, speedMps: Float, timeMillis: Long) {
        // Nepřesná poloha by do ujeté vzdálenosti přidala skoky
        if (accuracy > MAX_ACCURACY) return
        val prev = last
        val dt = (timeMillis - lastMillis) / 1000.0
        if (prev != null && dt in 0.0..MAX_GAP_S) {
            val d = prev.sphericalDistance(p)
            // Chvění polohy při stání se nepočítá
            if (speedMps > MIN_MOVING_SPEED || d > dt * MIN_MOVING_SPEED * 2) {
                distance += d
                moving += dt
            }
        }
        last = p
        lastMillis = timeMillis
    }

    fun summary(lean: LeanStats, nowMillis: Long) =
        RideSummary(distance, (nowMillis - startMillis) / 1000.0, moving, lean)

    private companion object {
        const val MAX_ACCURACY = 40f
        /** Po delší mezeře (tunel, ztráta signálu) úsek nepočítáme, cesta neznámá. */
        const val MAX_GAP_S = 30.0
        const val MIN_MOVING_SPEED = 1.5f
    }
}
