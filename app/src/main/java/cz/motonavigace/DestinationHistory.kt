package cz.motonavigace

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import org.mapsforge.core.model.LatLong

/**
 * Historie cílů, kam jezdec navigoval – nabízí se v „Kam jedeme?“, dokud nic nepíše.
 * Nejnovější první, stejný cíl (do [SAME_PLACE_METERS]) jen jednou.
 */
object DestinationHistory {
    data class Entry(val name: String, val position: LatLong)

    private const val PREFS = "history"
    private const val KEY = "destinations"
    private const val MAX = 20
    private const val SAME_PLACE_METERS = 100.0

    fun load(context: Context): List<Entry> {
        val json = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Entry(o.getString("name"), LatLong(o.getDouble("lat"), o.getDouble("lon")))
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, name: String, position: LatLong) {
        val rest = load(context).filterNot { it.position.sphericalDistance(position) < SAME_PLACE_METERS }
        save(context, (listOf(Entry(name, position)) + rest).take(MAX))
    }

    fun remove(context: Context, entry: Entry) = save(context, load(context) - entry)

    fun clear(context: Context) = prefs(context).edit { remove(KEY) }

    private fun save(context: Context, entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach {
            array.put(JSONObject().put("name", it.name).put("lat", it.position.latitude).put("lon", it.position.longitude))
        }
        prefs(context).edit { putString(KEY, array.toString()) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
