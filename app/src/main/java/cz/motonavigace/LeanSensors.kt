package cz.motonavigace

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.edit

/**
 * Napojí měřák náklonu na senzory tíže a gyroskop. Používá ho obrazovka Náklon
 * i navigace, která náklon měří na pozadí celou jízdu.
 * @param rotation otočení displeje (Surface.ROTATION_*) – osy senzorů se přepočítají na osy obrazovky
 */
class LeanSensors(context: Context, private val meter: LeanMeter, private val rotation: () -> Int) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    /** Bez senzoru tíže si ji odvodíme z akcelerometru dolní propustí. */
    private val lowPass = FloatArray(3)
    private var running = false

    val hasGyro: Boolean get() = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null

    fun start() {
        if (running) return
        running = true
        val g = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        g?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y) = toScreenAxes(event.values[0], event.values[1])
        val z = event.values[2]
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY -> meter.onGravity(x, y, z, event.timestamp)
            Sensor.TYPE_ACCELEROMETER -> {
                lowPass[0] += (x - lowPass[0]) * 0.1f
                lowPass[1] += (y - lowPass[1]) * 0.1f
                lowPass[2] += (z - lowPass[2]) * 0.1f
                meter.onGravity(lowPass[0], lowPass[1], lowPass[2], event.timestamp)
            }
            Sensor.TYPE_GYROSCOPE -> meter.onGyro(x, y, z, event.timestamp)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /**
     * Senzory hlásí osy podle přirozené orientace telefonu; výpočet počítá s osou x
     * vodorovně přes displej, takže při otočené obrazovce osy přemapujeme.
     */
    private fun toScreenAxes(x: Float, y: Float): Pair<Float, Float> =
        when (rotation()) {
            android.view.Surface.ROTATION_90 -> -y to x
            android.view.Surface.ROTATION_180 -> -x to -y
            android.view.Surface.ROTATION_270 -> y to -x
            else -> x to y
        }

    companion object {
        private const val PREFS = "lean"

        /** Kalibrace náklonu (maxima se neukládají – měří se vždy od otevření obrazovky nebo jízdy). */
        fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun calibration(context: Context) = prefs(context).getFloat("calibration", 0f).toDouble()

        fun saveCalibration(context: Context, value: Double) = prefs(context).edit { putFloat("calibration", value.toFloat()) }

        /** Otočení výchozího displeje – pro službu záznamu, která vlastní okno nemá. */
        fun displayRotation(context: Context): Int =
            context.getSystemService(android.hardware.display.DisplayManager::class.java)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: android.view.Surface.ROTATION_0
    }
}
