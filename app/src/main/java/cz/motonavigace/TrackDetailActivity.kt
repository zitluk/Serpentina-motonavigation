package cz.motonavigace

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import com.google.android.material.color.MaterialColors
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cz.motonavigace.databinding.ActivityTrackDetailBinding
import cz.motonavigace.databinding.ItemStatBinding
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

/**
 * Detail jízdy nebo importované trasy ve vzhledu obrazovky Náklon motocyklu: maximální náklon
 * na ukazateli, dlaždice s ujetou vzdáleností, časy, rychlostmi a zrychlením. Zobrazení na mapě
 * a navigaci vrací TracksActivity (EXTRA_ACTION + EXTRA_PATH).
 */
class TrackDetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityTrackDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTrackDetailBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupEdgeToEdge(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val path = intent.getStringExtra(TracksActivity.EXTRA_PATH) ?: return finish()
        lifecycleScope.launch {
            runCatching { TrackLibrary.load(File(path)) }
                .onSuccess(::show)
                .onFailure { finish() }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun show(e: TrackLibrary.Entry) {
        val s = e.stats
        val lean = e.lean
        b.title.text = if (e.isRide) TracksActivity.rideDate(this, e) else e.title
        b.kind.text = getString(
            when {
                e.isRide -> R.string.kind_recorded
                e.track.isRoute -> R.string.kind_route
                else -> R.string.kind_track
            }
        )

        // Ukazatel jen s maximy – současný náklon tu nedává smysl
        b.gauge.showValue = false
        b.gauge.maxLeft = lean?.maxLeft ?: 0.0
        b.gauge.maxRight = lean?.maxRight ?: 0.0
        val hasLean = lean != null
        b.gauge.visibility = if (hasLean) View.VISIBLE else View.GONE
        b.leanRow.visibility = if (hasLean) View.VISIBLE else View.GONE
        b.accelRow.visibility = if (hasLean) View.VISIBLE else View.GONE
        b.noLean.visibility = if (!hasLean && e.isRide) View.VISIBLE else View.GONE

        fun tile(t: ItemStatBinding, label: Int, value: String) {
            t.label.setText(label)
            t.value.text = value
        }
        lean?.let {
            tile(b.maxLeft, R.string.lean_max_left, "${it.maxLeft.roundToInt()}°")
            tile(b.maxRight, R.string.lean_max_right, "${it.maxRight.roundToInt()}°")
            tile(b.maxAccel, R.string.lean_max_accel, gForce(it.maxAccel))
            tile(b.maxBrake, R.string.lean_max_brake, gForce(it.maxBrake))
        }
        tile(b.distance, R.string.ride_distance, formatDistance(s.distanceMeters))
        tile(b.duration, R.string.ride_time, if (s.durationSeconds > 0) formatDuration(s.durationSeconds) else "–")
        tile(b.avgSpeed, R.string.ride_avg_speed, if (s.movingSeconds > 0) "%.0f km/h".format(s.avgMovingSpeedKmh) else "–")
        // Nejvyšší rychlost z GPS při jízdě, u starších záznamů z bodů stopy
        val max = maxOf(s.maxSpeedKmh, (lean?.maxSpeed ?: 0.0) * 3.6)
        tile(b.maxSpeed, R.string.lean_max_speed, if (max > 0) "%.0f km/h".format(max) else "–")
        tile(b.moving, R.string.track_moving, if (s.movingSeconds > 0) formatDuration(s.movingSeconds) else "–")
        tile(b.ascent, R.string.track_ascent, if (s.ascentMeters > 0) "%.0f m".format(s.ascentMeters) else "–")

        b.shape.points = e.track.points.map { it.latLong }
        b.btnShareImage.setOnClickListener { shareImage(e) }
        b.btnShow.setOnClickListener { finishWith(TracksActivity.ACTION_SHOW, e) }
        b.btnNavigate.setOnClickListener { finishWith(TracksActivity.ACTION_NAVIGATE, e) }
        b.btnShare.setOnClickListener { share(e) }
        b.btnDelete.setOnClickListener { confirmDelete(e) }
    }

    private fun gForce(a: Double) = "%.2f g".format(a / LeanMeter.G)

    private fun finishWith(action: String, e: TrackLibrary.Entry) {
        setResult(RESULT_OK, Intent().putExtra(TracksActivity.EXTRA_ACTION, action).putExtra(TracksActivity.EXTRA_PATH, e.file.path))
        finish()
    }

    private fun share(e: TrackLibrary.Entry) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", e.file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, e.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, getString(R.string.track_share)))
    }

    /**
     * Celý detail jízdy (i část mimo obrazovku) jako obrázek – bez tlačítek, s podpisem aplikace –
     * a sdílení přes systémovou nabídku (WhatsApp, Messenger, galerie…).
     */
    private fun shareImage(e: TrackLibrary.Entry) {
        val content = b.content
        b.actions.visibility = View.GONE
        b.brand.visibility = View.VISIBLE
        content.post {
            // Obsah přeměříme na plnou výšku, ať se vejde i to, co je pod okrajem obrazovky
            content.measure(
                View.MeasureSpec.makeMeasureSpec(content.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val bmp = createBitmap(content.width, content.measuredHeight)
            val canvas = Canvas(bmp)
            canvas.drawColor(MaterialColors.getColor(content, com.google.android.material.R.attr.colorSurface))
            content.layout(content.left, content.top, content.right, content.top + content.measuredHeight)
            content.draw(canvas)
            b.actions.visibility = View.VISIBLE
            b.brand.visibility = View.GONE
            val file = File(cacheDir, "share/jizda.png").apply { parentFile?.mkdirs() }
            runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                .onFailure { return@post }
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, getString(R.string.share_image_text, b.title.text, formatDistance(e.stats.distanceMeters)))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, getString(R.string.share_image)))
        }
    }

    private fun confirmDelete(e: TrackLibrary.Entry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(b.title.text)
            .setMessage(R.string.track_delete_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                TrackLibrary.delete(e)
                finish()
            }
            .show()
    }
}
