package cz.motonavigace

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cz.motonavigace.databinding.ActivityLeanBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Ukazatel náklonu motorky s maximálním náklonem, rychlostí a zrychlením. */
class LeanActivity : AppCompatActivity(), LocationListener {

    private lateinit var b: ActivityLeanBinding
    private lateinit var meter: LeanMeter
    private lateinit var sensors: LeanSensors

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLeanBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupEdgeToEdge(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Maxima se měří vždy od otevření obrazovky; jízdy si je ukládají samy (Trasy a jízdy)
        meter = LeanMeter(LeanSensors.calibration(this))
        sensors = LeanSensors(this, meter, ::displayRotation)

        b.maxLeft.label.setText(R.string.lean_max_left)
        b.maxRight.label.setText(R.string.lean_max_right)
        b.speed.label.setText(R.string.lean_speed)
        b.maxSpeed.label.setText(R.string.lean_max_speed)
        b.accel.label.setText(R.string.lean_accel)
        b.maxAccel.label.setText(R.string.lean_max_accel)
        b.maxBrake.label.setText(R.string.lean_max_brake)
        if (!sensors.hasGyro) {
            b.hint.text = getString(R.string.lean_hint) + "\n" + getString(R.string.lean_no_gyro)
        }

        b.btnCalibrate.setOnClickListener {
            meter.calibrate()
            LeanSensors.saveCalibration(this, meter.calibration)
            Toast.makeText(this, R.string.lean_calibrated, Toast.LENGTH_SHORT).show()
        }
        b.btnReset.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.lean_reset_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.lean_reset) { _, _ -> meter.resetStats(); render() }
                .show()
        }
        adaptToScreen()
        // Po otočení známe novou výšku až po rozvržení
        b.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> b.root.post { fitGauge() } }

        // Displej obnovujeme 10× za sekundu, senzory chodí mnohem častěji
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { render(); delay(100) }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        adaptToScreen()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        sensors.start()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocation()
        } else {
            locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    override fun onPause() {
        super.onPause()
        sensors.stop()
        getSystemService(LocationManager::class.java).removeUpdates(this)
    }

    private fun startLocation() {
        try {
            getSystemService(LocationManager::class.java)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this)
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
            // Zařízení bez GPS – rychlost a zrychlení zůstanou nulové
        }
    }

    override fun onLocationChanged(location: Location) {
        if (location.hasSpeed()) meter.onSpeed(location.speed.toDouble(), location.elapsedRealtimeNanos)
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_0
        else windowManager.defaultDisplay.rotation

    private fun render() {
        val s = meter.stats
        b.gauge.lean = meter.lean
        b.gauge.maxLeft = s.maxLeft
        b.gauge.maxRight = s.maxRight
        b.maxLeft.value.text = degrees(s.maxLeft)
        b.maxRight.value.text = degrees(s.maxRight)
        b.speed.value.text = kmh(meter.speed)
        b.maxSpeed.value.text = kmh(s.maxSpeed)
        b.accel.value.text = gForce(meter.accel)
        b.maxAccel.value.text = gForce(s.maxAccel)
        b.maxBrake.value.text = gForce(s.maxBrake)
    }

    /** Na širokém displeji (na šířku, tablet) ukazatel vlevo a hodnoty vpravo. */
    private fun adaptToScreen() {
        val wide = resources.configuration.screenWidthDp > resources.configuration.screenHeightDp
        b.content.orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        // Vedle vyššího sloupce s hodnotami by se ukazatel vycentroval mimo obrazovku
        b.content.gravity = if (wide) Gravity.TOP else Gravity.CENTER
        for (v in listOf<View>(b.gauge, b.stats)) {
            v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply {
                width = if (wide) 0 else LinearLayout.LayoutParams.MATCH_PARENT
                weight = if (wide) 1f else 0f
            }
        }
        fitGauge()
    }

    /** Na šířku se ukazatel musí vejít na výšku obrazovky, jinak by číslo pod ním ujelo dolů. */
    private fun fitGauge() {
        val wide = b.content.orientation == LinearLayout.HORIZONTAL
        val h = if (wide && b.root.height > 0) b.root.height - b.content.paddingTop - b.content.paddingBottom
        else LinearLayout.LayoutParams.WRAP_CONTENT
        if (b.gauge.layoutParams.height != h) {
            b.gauge.layoutParams = b.gauge.layoutParams.apply { height = h }
        }
    }

    private fun degrees(v: Double) = "${v.roundToInt()}°"
    private fun kmh(mps: Double) = "${(mps * 3.6).roundToInt()} km/h"
    private fun gForce(a: Double) = "%.2f g".format(abs(a) / LeanMeter.G).let { if (a < -0.05) "−$it" else it }
}
