package cz.motonavigace

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.sqrt

/** Maxima zaznamenaná měřákem náklonu; úhly ve stupních, rychlost v m/s, zrychlení v m/s². */
data class LeanStats(
    val maxLeft: Double = 0.0,
    val maxRight: Double = 0.0,
    val maxSpeed: Double = 0.0,
    val maxAccel: Double = 0.0,
    val maxBrake: Double = 0.0,
) {
    /** Zápis do souboru vedle GPX jízdy (řádky klíč=hodnota, nezávislé na jazyce). */
    fun encode(): String = listOf(
        "maxLeft" to maxLeft, "maxRight" to maxRight, "maxSpeed" to maxSpeed,
        "maxAccel" to maxAccel, "maxBrake" to maxBrake,
    ).joinToString("\n") { (k, v) -> "$k=${"%.3f".format(java.util.Locale.ROOT, v)}" }

    companion object {
        /** Opak [encode]; neznámé nebo poškozené řádky přeskočí, null = soubor bez údajů. */
        fun decode(text: String): LeanStats? {
            val v = text.lines().mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq > 0) line.substring(0, eq).trim() to line.substring(eq + 1).trim().toDoubleOrNull() else null
            }.filter { it.second != null }.associate { it.first to it.second!! }
            if (v.isEmpty()) return null
            return LeanStats(
                maxLeft = v["maxLeft"] ?: 0.0, maxRight = v["maxRight"] ?: 0.0, maxSpeed = v["maxSpeed"] ?: 0.0,
                maxAccel = v["maxAccel"] ?: 0.0, maxBrake = v["maxBrake"] ?: 0.0,
            )
        }
    }
}

/**
 * Výpočet náklonu motorky z telefonu upevněného na řídítkách na výšku (displejem k jezdci).
 *
 * Akcelerometr sám náklon v zatáčce neukáže – při ustálené zatáčce míří zdánlivá
 * tíže pořád podél osy motorky. Za jízdy proto počítáme náklon z fyziky zatáčky:
 * sin(φ) = v · ω / g, kde ω je rychlost otáčení kolem svislé osy motorky (gyroskop
 * promítnutý do směru zdánlivé tíže) a v rychlost z GPS. Telefon na řídítkách se ale otáčí
 * i s každým pohybem řídítek a třese se s motorem – rychlost otáčení proto vyhlazujeme.
 * Telefon bez gyroskopu bere rychlost otáčení ze změny směru jízdy podle GPS.
 * Při stání se použije prostý náklon telefonu vůči tíži, opravený o kalibraci.
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
    /** Vyhlazená rychlost otáčení z gyroskopu (rad/s, kladně = doleva). */
    private var gyroYaw = 0.0
    private var lastCourse = Double.NaN
    private var lastCourseNs = 0L

    /**
     * Vektor tíže v souřadnicích telefonu (TYPE_GRAVITY nebo vyhlazený akcelerometr),
     * [timestampNs] z SensorEvent.
     */
    fun onGravity(x: Float, y: Float, z: Float, timestampNs: Long = lastSpeedNs) {
        gravity[0] = x.toDouble(); gravity[1] = y.toDouble(); gravity[2] = z.toDouble()
        hasGravity = true
        // Bez gyroskopu ukazuje tíže náklon jen při stání; za jízdy ho počítá změna směru z GPS
        if (lastGyroNs == 0L && dynamicWeight(currentSpeed(timestampNs)) == 0.0) filterLean(staticLean(), 0.1)
    }

    /** Úhlová rychlost z gyroskopu v rad/s, [timestampNs] z SensorEvent. */
    fun onGyro(x: Float, y: Float, z: Float, timestampNs: Long) {
        if (!hasGravity) return
        val dt = if (lastGyroNs == 0L) 0.0 else (timestampNs - lastGyroNs) / 1e9
        lastGyroNs = timestampNs
        if (dt <= 0.0 || dt > 1.0) return

        val g = norm(gravity)
        // Rychlost otáčení kolem svislé osy motorky (kladně = doleva); škubnutí řídítky
        // a vibrace trvají zlomek sekundy, zatáčka déle – vyhlazení je odfiltruje
        val yaw = (x * gravity[0] + y * gravity[1] + z * gravity[2]) / g
        gyroYaw += (yaw - gyroYaw) * (1 - exp(-dt / YAW_SMOOTHING_S))
        val v = currentSpeed(timestampNs)
        filterLean(blend(dynamicLean(v, gyroYaw), v), dt)
    }

    /**
     * Směr jízdy z GPS (stupně od severu) – telefonu bez gyroskopu dá rychlost otáčení
     * v zatáčce. S gyroskopem se nepoužije, ten je rychlejší a přesnější.
     */
    fun onCourse(bearingDeg: Double, elapsedNs: Long) {
        val dt = (elapsedNs - lastCourseNs) / 1e9
        val prev = lastCourse
        lastCourse = bearingDeg
        lastCourseNs = elapsedNs
        if (lastGyroNs != 0L || prev.isNaN() || dt !in 0.3..3.0) return
        // Doprava roste kurz, rychlost otáčení je kladná doleva
        val turn = -(((bearingDeg - prev) % 360 + 540) % 360 - 180)
        val yaw = Math.toRadians(turn) / dt
        val v = currentSpeed(elapsedNs)
        // Skutečná rychlost otáčení (ne promítnutá do osy nakloněného telefonu): tg(φ) = v · ω / g
        if (dynamicWeight(v) > 0) filterLean(blend(Math.toDegrees(atan(-v * yaw / G)), v), dt)
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

    /**
     * Bez čerstvé rychlosti z GPS (signál ztracený v budově, garáži) bereme, že motorka stojí –
     * jinak by poslední rychlost z jízdy držela výpočet ze zatáčení a náklon telefonu by se neukázal.
     */
    private fun currentSpeed(nowNs: Long) = if (nowNs - lastSpeedNs > SPEED_STALE_NS) 0.0 else speed

    /** Náklon z fyziky zatáčky: sin(φ) = v · ω / g (ω z gyroskopu promítnutá do osy nakloněné motorky). */
    private fun dynamicLean(v: Double, yaw: Double) = Math.toDegrees(asin((-v * yaw / G).coerceIn(-1.0, 1.0)))

    /**
     * Mezi 3 a 6 m/s (asi 11–22 km/h) plynule přecházíme ze statického na dynamický výpočet;
     * pod tím je rychlost z GPS při stání jen šum.
     */
    private fun dynamicWeight(v: Double) = ((v - 3.0) / 3.0).coerceIn(0.0, 1.0)

    private fun blend(dynamic: Double, v: Double): Double {
        val w = dynamicWeight(v)
        return w * dynamic + (1 - w) * staticLean()
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
        // Časová konstanta 0,3 s – potlačí zbylé chvění, ale náklon výrazně nezpozdí
        lean += (target - lean) * (1 - exp(-dt / 0.3))
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
        /** Rychlost z GPS starší než tohle (ns) už neplatí. */
        private const val SPEED_STALE_NS = 5_000_000_000L
        /** Vyhlazení rychlosti otáčení z gyroskopu (s) – pohyby řídítek a vibrace jsou kratší. */
        private const val YAW_SMOOTHING_S = 0.4

        /**
         * Rychlost z GPS pro měřák náklonu: když je menší než její vlastní nejistota (stání,
         * odražený signál v budově), bereme nulu – telefon v ruce by jinak ukazoval „zatáčení“.
         */
        fun gpsSpeed(location: android.location.Location): Double {
            val v = location.speed.toDouble()
            return if (location.hasSpeedAccuracy() && v < 2 * location.speedAccuracyMetersPerSecond) 0.0 else v
        }

        fun side(angle: Double) = if (abs(angle) < 0.5) "" else if (angle < 0) "L" else "P"
    }
}
