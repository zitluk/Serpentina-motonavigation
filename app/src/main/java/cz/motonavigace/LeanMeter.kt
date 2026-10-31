package cz.motonavigace

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.exp
import kotlin.math.sqrt

/** Maxima zaznamenaná měřákem náklonu; úhly ve stupních, rychlost v m/s, zrychlení v m/s². */
data class LeanStats(
    val maxLeft: Double = 0.0,
    val maxRight: Double = 0.0,
    val maxSpeed: Double = 0.0,
    val maxAccel: Double = 0.0,
    val maxBrake: Double = 0.0,
)

/**
 * Výpočet náklonu motorky z telefonu upevněného na řídítkách na výšku (displejem k jezdci).
 *
 * Akcelerometr sám náklon v zatáčce neukáže – při ustálené zatáčce míří zdánlivá
 * tíže pořád podél osy motorky. Za jízdy proto počítáme náklon z fyziky zatáčky:
 * sin(φ) = v · ω / g, kde ω je rychlost otáčení kolem svislé osy motorky (gyroskop
 * promítnutý do směru zdánlivé tíže) a v rychlost z GPS. Při stání (nebo bez gyroskopu)
 * se použije prostý náklon telefonu vůči tíži, opravený o kalibraci.
 *
 * Kladný úhel = náklon doprava.
 */
class LeanMeter(var calibration: Double = 0.0, var stats: LeanStats = LeanStats()) {

    /** Vyfiltrovaný náklon ve stupních. */
    var lean = 0.0
        private set
    var speed = 0.0
        private set
    /** Vyhlazené podélné zrychlení z GPS v m/s² (záporné = brzdění). */
    var accel = 0.0
        private set

    private val gravity = DoubleArray(3)
    private var hasGravity = false
    private var lastGyroNs = 0L
    private var lastSpeedNs = 0L

    /** Vektor tíže v souřadnicích telefonu (TYPE_GRAVITY nebo vyhlazený akcelerometr). */
    fun onGravity(x: Float, y: Float, z: Float) {
        gravity[0] = x.toDouble(); gravity[1] = y.toDouble(); gravity[2] = z.toDouble()
        hasGravity = true
        // Bez gyroskopu jsou jediným zdrojem náklonu data o tíži
        if (lastGyroNs == 0L) filterLean(staticLean(), 0.1)
    }

    /** Úhlová rychlost z gyroskopu v rad/s, [timestampNs] z SensorEvent. */
    fun onGyro(x: Float, y: Float, z: Float, timestampNs: Long) {
        if (!hasGravity) return
        val dt = if (lastGyroNs == 0L) 0.0 else (timestampNs - lastGyroNs) / 1e9
        lastGyroNs = timestampNs
        if (dt <= 0.0 || dt > 1.0) return

        val g = norm(gravity)
        // Rychlost otáčení kolem svislé osy motorky (kladně = doleva)
        val yaw = (x * gravity[0] + y * gravity[1] + z * gravity[2]) / g
        val dynamic = Math.toDegrees(asin((-speed * yaw / G).coerceIn(-1.0, 1.0)))
        // Mezi 2 a 4 m/s plynule přecházíme ze statického na dynamický výpočet
        val w = ((speed - 2.0) / 2.0).coerceIn(0.0, 1.0)
        filterLean(w * dynamic + (1 - w) * staticLean(), dt)
    }

    /** Rychlost z GPS v m/s, [elapsedNs] = Location.elapsedRealtimeNanos. */
    fun onSpeed(speedMps: Double, elapsedNs: Long) {
        val dt = (elapsedNs - lastSpeedNs) / 1e9
        if (lastSpeedNs != 0L && dt in 0.3..5.0) {
            val a = (speedMps - speed) / dt
            accel += (a - accel) * 0.5
        } else {
            accel = 0.0
        }
        lastSpeedNs = elapsedNs
        speed = speedMps
        record()
    }

    /** Současný náklon telefonu se bere jako svislá poloha motorky. */
    fun calibrate() {
        calibration += staticLean()
        lean = 0.0
    }

    fun resetStats() {
        stats = LeanStats()
    }

    /** Náklon telefonu vůči tíži bez ohledu na jízdu (osa x telefonu míří doprava). */
    private fun staticLean(): Double {
        val g = norm(gravity)
        if (g < 1e-3) return 0.0
        return Math.toDegrees(asin((-gravity[0] / g).coerceIn(-1.0, 1.0))) - calibration
    }

    private fun filterLean(target: Double, dt: Double) {
        // Časová konstanta 0,25 s – potlačí vibrace motoru, ale náklon nezpozdí
        lean += (target - lean) * (1 - exp(-dt / 0.25))
        record()
    }

    private fun record() {
        val s = stats
        // Náklon při stání (stojánek, tlačení) se do maxim nepočítá
        val moving = speed > MIN_RECORD_SPEED
        val l = lean.coerceIn(-MAX_LEAN, MAX_LEAN)
        val updated = s.copy(
            maxLeft = if (moving && -l > s.maxLeft) -l else s.maxLeft,
            maxRight = if (moving && l > s.maxRight) l else s.maxRight,
            maxSpeed = maxOf(s.maxSpeed, speed),
            maxAccel = if (moving && accel > s.maxAccel) accel else s.maxAccel,
            maxBrake = if (moving && -accel > s.maxBrake) -accel else s.maxBrake,
        )
        if (updated != s) stats = updated
    }

    private fun norm(v: DoubleArray) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    companion object {
        const val G = 9.81
        /** Víc už motorka na silnici nezvládne, vyšší hodnoty jsou chyba měření. */
        const val MAX_LEAN = 65.0
        const val MIN_RECORD_SPEED = 3.0

        fun side(angle: Double) = if (abs(angle) < 0.5) "" else if (angle < 0) "L" else "P"
    }
}
