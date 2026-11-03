package cz.motonavigace

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stav probíhajícího záznamu, sdílený mezi službou a obrazovkou. */
object Recording {
    data class State(
        val active: Boolean = false,
        val startTime: Long = 0,
        val distanceMeters: Double = 0.0,
        /** Počet bodů – podle něj obrazovka pozná, že má překreslit stopu. */
        val pointCount: Int = 0,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private val points = mutableListOf<TrackPoint>()

    @Synchronized
    fun points(): List<TrackPoint> = points.toList()

    @Synchronized
    internal fun reset(startTime: Long, initial: List<TrackPoint> = emptyList()) {
        points.clear()
        points += initial
        _state.value = State(true, startTime, Gpx.stats(initial).distanceMeters, initial.size)
    }

    @Synchronized
    internal fun add(p: TrackPoint, segmentMeters: Double) {
        points += p
        val s = _state.value
        _state.value = s.copy(distanceMeters = s.distanceMeters + segmentMeters, pointCount = points.size)
    }

    @Synchronized
    internal fun finish() {
        _state.value = State()
    }

    /** Od této rychlosti se záznam spustí sám (volba v menu). */
    const val AUTO_START_KMH = 10f
    /** Po zastavení záznamu se automatika znovu chystá, až motorka zpomalí pod tuto rychlost. */
    const val AUTO_REARM_KMH = 3f

    fun start(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
    }

    fun stop(context: Context) {
        context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }
}

/**
 * Služba na popředí, která zaznamenává jízdu i se zhasnutou obrazovkou.
 * Body se průběžně připisují do tracks/.recording.csv, aby se jízda neztratila
 * při pádu; po zastavení se převede na GPX.
 */
class RecordingService : Service(), LocationListener {

    companion object {
        const val ACTION_STOP = "stop"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 1
        private const val MAX_ACCURACY_M = 30f
        private const val MIN_DISTANCE_M = 5.0

        private val journal get() = File(Storage.tracksDir, ".recording.csv")
        /** Maxima náklonu rozpracované jízdy (průběžně přepisovaná, ať se neztratí při pádu). */
        private val leanJournal get() = File(Storage.tracksDir, ".recording.lean")
        /** Jak často se maxima náklonu zapíšou (počet poloh z GPS). */
        private const val LEAN_SAVE_EVERY = 10

        /** Dokončí záznam, který zůstal po pádu nebo ukončení aplikace. */
        fun recoverUnfinished(): File? {
            if (!journal.isFile || Recording.state.value.active) return null
            return finishJournal()
        }

        private fun finishJournal(): File? {
            val points = readJournal()
            journal.delete()
            if (points.size < 2) {
                leanJournal.delete()
                return null
            }
            val start = Date(points.first().time)
            val name = (if (Settings.isEnglish()) "Ride " else "Jízda ") +
                SimpleDateFormat("d. M. yyyy HH:mm", Locale.ROOT).format(start)
            val file = File(Storage.tracksDir, "jizda-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(start) + ".gpx")
            Gpx.write(file, name, points)
            // Náklon, rychlosti a zrychlení jízdy vedle GPX (GPX je sdílí bez nich)
            if (leanJournal.isFile) {
                val lean = TrackLibrary.leanFile(file)
                lean.delete()
                if (!leanJournal.renameTo(lean)) leanJournal.delete()
            }
            return file
        }

        private fun readJournal(): List<TrackPoint> = runCatching {
            journal.readLines().mapNotNull { line ->
                val f = line.split(',')
                if (f.size < 4) null
                else TrackPoint(f[0].toDouble(), f[1].toDouble(), f[2].toDoubleOrNull() ?: Double.NaN, f[3].toLong())
            }
        }.getOrDefault(emptyList())
    }

    private var last: TrackPoint? = null
    /** Náklon se měří celou jízdu i se zhasnutou obrazovkou. */
    private var leanMeter: LeanMeter? = null
    private var leanSensors: LeanSensors? = null
    private var fixesSinceLeanSave = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        if (!Recording.state.value.active) startRecording()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        createChannel()
        // ServiceCompat typ služby na Androidu 8–9 (bez typů) sám vynechá
        @SuppressLint("InlinedApi")
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        // Navážeme na rozpracovaný záznam (služba mohla být systémem restartována)
        val resumed = readJournal()
        last = resumed.lastOrNull()
        Recording.reset(resumed.firstOrNull()?.time ?: System.currentTimeMillis(), resumed)
        // Maxima z přerušené části jízdy navazují
        val previous = if (resumed.isNotEmpty() && leanJournal.isFile) LeanStats.decode(leanJournal.readText()) else null
        if (resumed.isEmpty()) leanJournal.delete()
        val meter = LeanMeter(LeanSensors.calibration(this), previous ?: LeanStats())
        leanMeter = meter
        leanSensors = LeanSensors(this, meter) { LeanSensors.displayRotation(this) }.also { it.start() }
        runCatching {
            getSystemService(LocationManager::class.java)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        }.onFailure { stopRecording() }
    }

    private fun stopRecording() {
        getSystemService(LocationManager::class.java).removeUpdates(this)
        leanSensors?.stop()
        leanSensors = null
        saveLean()
        leanMeter = null
        val file = finishJournal()
        Recording.finish()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        file?.let { TrackLibrary.lastRecorded = it }
    }

    private fun saveLean() {
        leanMeter?.let { runCatching { leanJournal.writeText(it.stats.encode()) } }
    }

    override fun onDestroy() {
        leanSensors?.stop()
        super.onDestroy()
    }

    override fun onLocationChanged(location: Location) {
        // Rychlost pro výpočet náklonu a zrychlení i z méně přesných poloh
        leanMeter?.let { m ->
            if (location.hasSpeed()) m.onSpeed(location.speed.toDouble(), location.elapsedRealtimeNanos)
            if (++fixesSinceLeanSave >= LEAN_SAVE_EVERY) { fixesSinceLeanSave = 0; saveLean() }
        }
        if (location.hasAccuracy() && location.accuracy > MAX_ACCURACY_M) return
        val p = TrackPoint(
            location.latitude, location.longitude,
            // Výška přesně 0 bývá u prvních fixů jen „zatím nevím“, ne hladina moře
            if (location.hasAltitude() && location.altitude != 0.0) location.altitude else Double.NaN,
            location.time,
        )
        val prev = last
        val d = prev?.latLong?.sphericalDistance(p.latLong) ?: 0.0
        if (prev != null && d < MIN_DISTANCE_M) return
        last = p
        journal.appendText("${p.lat},${p.lon},${if (p.ele.isNaN()) "" else "%.1f".format(Locale.ROOT, p.ele)},${p.time}\n")
        Recording.add(p, d)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_record)
        .setContentTitle(getString(R.string.recording_active))
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .addAction(
            0, getString(R.string.recording_stop),
            PendingIntent.getService(
                this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()
}
