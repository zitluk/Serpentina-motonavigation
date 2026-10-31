package cz.motonavigace

import android.content.Context
import java.io.File

/**
 * Umístění offline dat v úložišti aplikace (nevyžaduje oprávnění k úložišti).
 *
 *  maps/      *.map soubory Mapsforge
 *  segments/  *.rd5 routovací dlaždice BRouteru (5×5°)
 *  pois/      *.poi databáze bodů zájmu Mapsforge (vyhledávání)
 *  tracks/    *.gpx zaznamenané jízdy a importované trasy
 *  profiles/  routovací profily *.brf + lookups.dat
 */
object Storage {
    lateinit var root: File
        private set

    val mapsDir get() = File(root, "maps")
    val segmentsDir get() = File(root, "segments")
    val profilesDir get() = File(root, "profiles")
    val poisDir get() = File(root, "pois")
    val tracksDir get() = File(root, "tracks")

    fun init(context: Context) {
        root = context.getExternalFilesDir(null) ?: context.filesDir
        listOf(mapsDir, segmentsDir, profilesDir, poisDir, tracksDir).forEach { it.mkdirs() }
        copyProfiles(context)
    }

    fun mapFiles(): List<File> =
        mapsDir.listFiles { f -> f.isFile && f.name.endsWith(".map") }?.sortedBy { it.name } ?: emptyList()

    fun poiFiles(): List<File> =
        poisDir.listFiles { f -> f.isFile && f.name.endsWith(".poi") }?.sortedBy { it.name } ?: emptyList()

    fun trackFiles(): List<File> =
        tracksDir.listFiles { f -> f.isFile && f.name.endsWith(".gpx") }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun hasSegment(name: String) = File(segmentsDir, name).let { it.isFile && it.length() > 0 }

    /** Profily z assets se přepíšou při každém spuštění, aby odpovídaly verzi aplikace. */
    private fun copyProfiles(context: Context) {
        for (name in context.assets.list("brouter").orEmpty()) {
            context.assets.open("brouter/$name").use { input ->
                File(profilesDir, name).outputStream().use { input.copyTo(it) }
            }
        }
    }
}
