package cz.motonavigace

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Uložené GPX soubory: zaznamenané jízdy a importované trasy. */
object TrackLibrary {

    /** Naposledy dokončený záznam – obrazovka na něj upozorní. */
    @Volatile
    var lastRecorded: File? = null

    data class Entry(val file: File, val track: GpxTrack, val stats: TrackStats) {
        val title get() = track.name ?: file.nameWithoutExtension
    }

    suspend fun load(file: File): Entry = withContext(Dispatchers.IO) {
        val track = Gpx.parse(file)
        Entry(file, track, Gpx.stats(track.points))
    }

    suspend fun loadAll(): List<Entry> = withContext(Dispatchers.IO) {
        Storage.trackFiles().mapNotNull { runCatching { load(it) }.getOrNull() }
    }

    /** Zkopíruje GPX z [uri] do knihovny; nejdřív ověří, že jde načíst. */
    suspend fun import(context: Context, uri: Uri): Entry = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException(if (Settings.isEnglish()) "Cannot open the file" else "Soubor nelze otevřít")
        val track = Gpx.parse(bytes.inputStream())
        val base = (displayName(context, uri)?.substringBeforeLast('.') ?: track.name ?: "trasa")
            .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(60)
        var file = File(Storage.tracksDir, "$base.gpx")
        var n = 2
        while (file.exists()) file = File(Storage.tracksDir, "$base ($n).gpx").also { n++ }
        file.writeBytes(bytes)
        Entry(file, track, Gpx.stats(track.points))
    }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    fun describe(e: Entry): String {
        val s = e.stats
        val parts = mutableListOf(formatDistance(s.distanceMeters))
        if (s.durationSeconds > 0) parts += formatDuration(s.durationSeconds)
        if (s.movingSeconds > 0) parts += "Ø %.0f km/h".format(s.avgMovingSpeedKmh)
        if (s.maxSpeedKmh > 0) parts += "max %.0f km/h".format(s.maxSpeedKmh)
        if (s.ascentMeters > 0) parts += "↑ %.0f m".format(s.ascentMeters)
        return parts.joinToString(" · ")
    }
}
