package cz.motonavigace

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Poloha slunce bez internetu – podle ní se mapa přepíná do nočních barev. */
object Sun {
    /**
     * Slunce pod obzorem víc než o tolik stupňů = noc. Mezi západem (−0,8°) a koncem
     * občanského soumraku (−6°), kdy už se bez světel jezdit nedá.
     */
    private const val NIGHT_BELOW_DEG = -4.0

    fun isNight(lat: Double, lon: Double, utcMillis: Long = System.currentTimeMillis()) =
        elevation(lat, lon, utcMillis) < NIGHT_BELOW_DEG

    /** Výška slunce nad obzorem ve stupních (přesnost kolem 1°, pro den a noc dostačuje). */
    fun elevation(lat: Double, lon: Double, utcMillis: Long): Double {
        val d = utcMillis / 86_400_000.0 - 10_957.5 // dny od J2000 (1. 1. 2000 12:00 UTC)
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360)
        val q = (280.459 + 0.98564736 * d) % 360
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val gmstHours = (18.697374558 + 24.06570982441908 * d) % 24
        val hourAngle = Math.toRadians(gmstHours * 15 + lon) - ra
        val phi = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(hourAngle)))
    }
}
