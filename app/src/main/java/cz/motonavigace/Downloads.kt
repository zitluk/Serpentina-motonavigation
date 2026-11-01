package cz.motonavigace

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import java.io.DataInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stahování offline dat přes systémový DownloadManager (běží i na pozadí).
 * Soubor se stahuje jako *.part a přejmenuje se až po úspěšném dokončení,
 * takže rozpracovaná mapa se nikdy nenačte.
 */
object Downloads {
    private const val PREFS = "downloads"

    data class Progress(val downloaded: Long, val total: Long)

    private fun dm(context: Context) = context.getSystemService(DownloadManager::class.java)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Rozpracované stahování a region, který o něj požádal. */
    private data class Job(val target: File, val region: String)

    /** Rozpracovaná stahování: id → úloha (uloženo jako „region\tcesta“). */
    private fun pending(context: Context): Map<Long, Job> =
        prefs(context).all.mapNotNull { (k, v) ->
            val id = k.toLongOrNull() ?: return@mapNotNull null
            val value = v as String
            val tab = value.indexOf('\t')
            id to if (tab < 0) Job(File(value), "") else Job(File(value.substring(tab + 1)), value.substring(0, tab))
        }.toMap()

    fun regionFiles(region: Region): List<Pair<String, File>> =
        listOf(
            region.mapUrl to File(Storage.mapsDir, region.mapFileName),
            region.poiUrl to File(Storage.poisDir, region.poiFileName),
        ) +
            region.segments.map { Region.segmentUrl(it) to File(Storage.segmentsDir, it) }

    fun isDownloaded(region: Region) = regionFiles(region).all { it.second.isFile }

    fun start(context: Context, region: Region) = enqueue(context, region, regionFiles(region).filterNot { it.second.isFile })

    /** Stáhne znovu mapu a body zájmu; stará verze zůstane, dokud nová nedorazí celá. */
    fun update(context: Context, region: Region) = enqueue(context, region, regionFiles(region).take(2))

    private fun enqueue(context: Context, region: Region, files: List<Pair<String, File>>) {
        val busy = pending(context).values.map { it.target }.toSet()
        for ((url, target) in files) {
            if (target in busy) continue
            val part = File(target.path + ".part").apply { delete() }
            val request = DownloadManager.Request(Uri.parse(url))
                .setTitle("${region.name}: ${target.name}")
                .setDestinationUri(Uri.fromFile(part))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                // Při vypnutých mobilních datech počká DownloadManager na Wi-Fi
                .setAllowedOverMetered(Settings.mobileData)
            val id = dm(context).enqueue(request)
            prefs(context).edit { putString(id.toString(), "${region.name}\t${target.path}") }
        }
    }

    fun cancel(context: Context, region: Region) {
        pending(context).filterValues { it.region == region.name }.forEach { (id, job) ->
            val target = job.target
            dm(context).remove(id)
            File(target.path + ".part").delete()
            prefs(context).edit { remove(id.toString()) }
        }
    }

    /** Smaže mapu a body zájmu regionu a segmenty, které nepotřebuje žádný jiný stažený region. */
    fun delete(region: Region) {
        File(Storage.mapsDir, region.mapFileName).delete()
        File(Storage.poisDir, region.poiFileName).delete()
        val stillNeeded = Region.ALL.filter { it != region && File(Storage.mapsDir, it.mapFileName).isFile }
            .flatMap { it.segments }.toSet()
        region.segments.filterNot { it in stillNeeded }.forEach { File(Storage.segmentsDir, it).delete() }
    }

    /**
     * Zkontroluje stav stahování, dokončené soubory přejmenuje a vrátí průběh
     * rozpracovaných souborů podle regionu, který je stahuje.
     */
    fun refresh(context: Context): Map<String, List<Progress>> {
        val all = pending(context)
        if (all.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, MutableList<Progress>>()
        val seen = mutableSetOf<Long>()
        dm(context).query(DownloadManager.Query().setFilterById(*all.keys.toLongArray())).use { c ->
            val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
            val doneCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val job = all[id] ?: continue
                val target = job.target
                seen += id
                val part = File(target.path + ".part")
                when (c.getInt(statusCol)) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        // Při aktualizaci nahradí starou verzi až kompletní nový soubor
                        target.delete()
                        part.renameTo(target)
                        prefs(context).edit { remove(id.toString()) }
                    }
                    DownloadManager.STATUS_FAILED -> {
                        part.delete()
                        prefs(context).edit { remove(id.toString()) }
                    }
                    else -> result.getOrPut(job.region) { mutableListOf() } += Progress(c.getLong(doneCol), c.getLong(totalCol))
                }
            }
        }
        // Stahování zrušené uživatelem z notifikace
        (all.keys - seen).forEach { prefs(context).edit { remove(it.toString()) } }
        return result
    }

    /** Datum dat v mapě z hlavičky souboru Mapsforge (ms), nebo null u neplatného souboru. */
    fun mapDate(file: File): Long? = try {
        DataInputStream(file.inputStream().buffered()).use { input ->
            val magic = ByteArray(20).also { input.readFully(it) }
            if (String(magic, Charsets.US_ASCII) != "mapsforge binary OSM") return null
            input.readInt() // velikost hlavičky
            input.readInt() // verze formátu
            input.readLong() // velikost souboru
            input.readLong()
        }
    } catch (_: Exception) {
        null
    }

    /** Kdy byla mapa naposledy nahraná na server (ms), null bez připojení. */
    fun remoteDate(region: Region): Long? = try {
        (URL(region.mapUrl).openConnection() as HttpURLConnection).run {
            requestMethod = "HEAD"
            connectTimeout = 5000
            readTimeout = 5000
            try { lastModified.takeIf { responseCode == 200 && it > 0 } } finally { disconnect() }
        }
    } catch (_: Exception) {
        null
    }

    /** Server publikuje mapu do hodin od data v ní; výrazně novější soubor = nová verze. */
    fun isNewer(remote: Long, local: Long) = remote - local > 2 * 24 * 3600 * 1000L
}
