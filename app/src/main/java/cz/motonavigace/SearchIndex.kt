package cz.motonavigace

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Rejstřík jmen pro rychlé hledání: databáze POI nemá index podle jména, takže každé hledání
 * by procházelo celý soubor (ČR: 772 tisíc záznamů, na starším telefonu i desítky sekund).
 * Rejstřík (fulltext SQLite FTS4) se postaví jednou na pozadí po stažení mapy a hledání
 * v něm trvá milisekundy. Dokud není hotový, hledá se postaru.
 */
object SearchIndex {
    private val mutex = Mutex()
    private lateinit var dir: File

    fun init(context: Context) {
        dir = File(context.noBackupFilesDir, "search").apply { mkdirs() }
    }

    private fun indexFile(poi: File) = File(dir, poi.name + ".fts")

    /** Rejstřík k [poi] je hotový a patří k aktuální verzi souboru (po aktualizaci mapy se staví znovu). */
    fun isReady(poi: File): Boolean {
        val f = indexFile(poi)
        if (!f.isFile) return false
        return runCatching {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT size, modified FROM meta", null).use { c ->
                    c.moveToFirst() && c.getLong(0) == poi.length() && c.getLong(1) == poi.lastModified()
                }
            }
        }.getOrDefault(false)
    }

    /** Postaví chybějící rejstříky a smaže ty, jejichž mapa už není stažená. */
    suspend fun ensureAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pois = Storage.poiFiles()
            val wanted = pois.map { indexFile(it).name }.toSet()
            dir.listFiles()?.filter { it.name !in wanted }?.forEach { it.delete() }
            for (poi in pois) if (!isReady(poi)) runCatching { build(poi) }
        }
    }

    private fun build(poi: File) {
        val tmp = File(dir, indexFile(poi).name + ".tmp").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(tmp, null).use { idx ->
            // Výchozí tokenizér stačí: normalized_name je malými písmeny bez diakritiky
            idx.execSQL("CREATE VIRTUAL TABLE names USING fts4(norm)")
            idx.execSQL("CREATE TABLE meta(size INTEGER, modified INTEGER)")
            val insert = idx.compileStatement("INSERT INTO names(docid, norm) VALUES (?, ?)")
            SQLiteDatabase.openDatabase(poi.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { src ->
                idx.beginTransaction()
                try {
                    // Hodnota tagu normalized_name: od „=“ po nejbližší \r (tagy jsou oddělené \r)
                    src.rawQuery(
                        "SELECT id, substr(data, instr(data, 'normalized_name=') + 16, " +
                            "CASE WHEN instr(substr(data, instr(data, 'normalized_name=') + 16), char(13)) > 0 " +
                            "THEN instr(substr(data, instr(data, 'normalized_name=') + 16), char(13)) - 1 ELSE 1000 END) " +
                            "FROM poi_data WHERE instr(data, 'normalized_name=') > 0",
                        null,
                    ).use { c ->
                        while (c.moveToNext()) {
                            insert.bindLong(1, c.getLong(0))
                            insert.bindString(2, c.getString(1))
                            insert.executeInsert()
                        }
                    }
                    idx.execSQL("INSERT INTO meta VALUES (${poi.length()}, ${poi.lastModified()})")
                    idx.setTransactionSuccessful()
                } finally {
                    idx.endTransaction()
                }
            }
            idx.execSQL("INSERT INTO names(names) VALUES('optimize')")
        }
        File(tmp.path + "-journal").delete()
        if (!tmp.renameTo(indexFile(poi))) tmp.delete()
    }

    /**
     * Id bodů z [poi], jejichž jméno obsahuje slova začínající všemi slovy dotazu
     * („nova ves“, „ceske bud“). Null = rejstřík není hotový.
     */
    fun find(poi: File, normalizedQuery: String, limit: Int): List<Long>? {
        if (!isReady(poi)) return null
        val match = tokens(normalizedQuery).joinToString(" ") { "$it*" }
        if (match.isEmpty()) return emptyList()
        return runCatching {
            SQLiteDatabase.openDatabase(indexFile(poi).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT docid FROM names WHERE norm MATCH ? LIMIT $limit", arrayOf(match)).use { c ->
                    buildList { while (c.moveToNext()) add(c.getLong(0)) }
                }
            }
        }.getOrNull()
    }

    /**
     * Slova dotazu pro MATCH – jen písmena a číslice malými písmeny, ať uživatel nemůže napsat
     * syntaxi FTS (operátory OR, NOT, NEAR platí jen velkými písmeny).
     */
    internal fun tokens(normalizedQuery: String): List<String> =
        normalizedQuery.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
}
