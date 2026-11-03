package cz.motonavigace

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cz.motonavigace.databinding.ActivityDownloadsBinding
import cz.motonavigace.databinding.ItemRegionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Stažení map: katalog celého světa po zemích (velké země i po částech) s hledáním země.
 * Bez hledání nahoře stažené a stahované mapy, pod nimi všechny země po kontinentech.
 */
class DownloadsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDownloadsBinding
    private val adapter = RegionAdapter()
    /** Průběh stahování podle regionu – sdílené routovací dlaždice patří jen regionu, který je spustil. */
    private var progress: Map<String, List<Downloads.Progress>> = emptyMap()
    /** Datum mapy na serveru podle regionu (zjišťuje se jednou po otevření obrazovky). */
    private var remoteDates: Map<String, Long> = emptyMap()
    /** Datum dat ve stažené mapě; klíč obsahuje i čas změny souboru, ať se po aktualizaci načte znovu. */
    private val localDates = mutableMapOf<String, Long?>()
    private var query = ""

    /** Řádek seznamu: nadpis skupiny, nebo region. */
    private sealed interface Row {
        data class Header(val text: String) : Row
        data class Item(val region: Region) : Row
    }

    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.query.doAfterTextChanged {
            query = it?.toString().orEmpty()
            rebuild()
            binding.list.scrollToPosition(0)
        }
        rebuild()

        if (Settings.canUseNetwork(this)) lifecycleScope.launch {
            remoteDates = withContext(Dispatchers.IO) {
                Region.ALL.filter { File(Storage.mapsDir, it.mapFileName).isFile }
                    .mapNotNull { r -> Downloads.remoteDate(r)?.let { r.name to it } }.toMap()
            }
            adapter.notifyDataSetChanged()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    val before = progress.keys
                    progress = Downloads.refresh(this@DownloadsActivity)
                    // Dokončené nebo nové stahování přesouvá region mezi stažené
                    if (progress.keys != before) rebuild() else adapter.notifyDataSetChanged()
                    delay(1000)
                }
            }
        }
    }

    /** Mapa regionu je v telefonu (celá nebo z části) nebo se právě stahuje. */
    private fun isMine(r: Region) = r.name in progress ||
        File(Storage.mapsDir, r.mapFileName).isFile || File(Storage.poisDir, r.poiFileName).isFile

    private fun rebuild() {
        val result = mutableListOf<Row>()
        val found = Catalog.search(query)
        if (query.isBlank()) {
            val mine = found.filter(::isMine)
            if (mine.isNotEmpty()) {
                result += Row.Header(getString(R.string.downloads_mine))
                mine.mapTo(result) { Row.Item(it) }
            }
            for (continent in CONTINENTS) {
                val inContinent = found.filter { it.continent == continent.first }
                if (inContinent.isEmpty()) continue
                result += Row.Header(getString(continent.second))
                inContinent.mapTo(result) { Row.Item(it) }
            }
        } else {
            found.mapTo(result) { Row.Item(it) }
        }
        rows = result
        binding.empty.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun continentName(r: Region) = CONTINENTS.firstOrNull { it.first == r.continent }?.let { getString(it.second) }.orEmpty()

    private fun localDate(file: File): Long? =
        localDates.getOrPut("${file.path}@${file.lastModified()}") { Downloads.mapDate(file) }

    private val dateFormat = SimpleDateFormat("d. M. yyyy", Locale.ROOT)

    private fun formatDate(ms: Long): String = dateFormat.format(Date(ms))

    /** Bez povolených mobilních dat čeká stahování na Wi-Fi – ať to uživatel ví. */
    private fun warnWifiOnly() {
        if (!Settings.canUseNetwork(this)) Toast.makeText(this, R.string.download_waits_for_wifi, Toast.LENGTH_LONG).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private inner class RegionAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        inner class Holder(val b: ItemRegionBinding) : RecyclerView.ViewHolder(b.root)
        inner class HeaderHolder(val text: TextView) : RecyclerView.ViewHolder(text)

        override fun getItemCount() = rows.size

        override fun getItemViewType(position: Int) = if (rows[position] is Row.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) HeaderHolder(inflater.inflate(R.layout.item_downloads_header, parent, false) as TextView)
            else Holder(ItemRegionBinding.inflate(inflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderHolder).text.text = row.text
                is Row.Item -> bindRegion(holder as Holder, row.region)
            }
        }

        private fun bindRegion(holder: Holder, region: Region) {
            val files = Downloads.regionFiles(region).map { it.second }
            val running = progress[region.name].orEmpty()
            val ctx = this@DownloadsActivity
            // Ve výsledcích hledání chybí nadpisy kontinentů, kontinent je proto u názvu
            val where = if (query.isBlank()) "" else continentName(region) + " · "
            with(holder.b) {
                name.text = region.label()
                when {
                    running.isNotEmpty() -> {
                        val done = running.sumOf { it.downloaded }
                        val total = running.sumOf { it.total.coerceAtLeast(0) }
                        status.text = where + getString(R.string.downloading, files.count { it.isFile }, files.size) + " · " +
                            Formatter.formatShortFileSize(ctx, done) +
                            if (total > 0) " / " + Formatter.formatShortFileSize(ctx, total) else ""
                        progress.visibility = View.VISIBLE
                        progress.isIndeterminate = total <= 0
                        update.visibility = View.GONE
                        if (total > 0) progress.progress = (done * 100 / total).toInt()
                        action.setText(R.string.cancel)
                        action.setOnClickListener { Downloads.cancel(ctx, region); rebuild() }
                    }
                    Downloads.isDownloaded(region) -> {
                        val size = files.sumOf { it.length() }
                        val local = localDate(files[0])
                        val remote = remoteDates[region.name]
                        val newer = local != null && remote != null && Downloads.isNewer(remote, local)
                        status.text = listOfNotNull(
                            where + getString(R.string.downloaded) + " · " + Formatter.formatShortFileSize(ctx, size),
                            local?.let { getString(R.string.map_version, formatDate(it)) },
                            if (newer) getString(R.string.map_update_available, formatDate(remote!!)) else null,
                        ).joinToString("\n")
                        progress.visibility = View.GONE
                        update.visibility = if (newer) View.VISIBLE else View.GONE
                        update.setOnClickListener { Downloads.update(ctx, region); warnWifiOnly(); rebuild() }
                        action.setText(R.string.delete)
                        action.setOnClickListener { Downloads.delete(region); rebuild() }
                    }
                    else -> {
                        val have = files.count { it.isFile }
                        update.visibility = View.GONE
                        // Routovací dlaždice sdílí víc zemí – bez vlastní mapy nebo POI region za rozstažený nepovažujeme
                        val started = files.take(2).any { it.isFile }
                        val size = getString(R.string.download_size,
                            Formatter.formatShortFileSize(ctx, region.downloadSize(Storage::hasSegment)))
                        status.text = where + (if (started) getString(R.string.partially_downloaded, have, files.size)
                            else getString(R.string.not_downloaded)) + " · " + size
                        progress.visibility = View.GONE
                        action.setText(R.string.download)
                        action.setOnClickListener { Downloads.start(ctx, region); warnWifiOnly(); rebuild() }
                    }
                }
            }
        }
    }

    private companion object {
        /** Kontinenty v pořadí seznamu: Evropa první, Rusko je na serveru samostatně. */
        val CONTINENTS = listOf(
            "europe" to R.string.continent_europe,
            "russia" to R.string.continent_russia,
            "asia" to R.string.continent_asia,
            "africa" to R.string.continent_africa,
            "north-america" to R.string.continent_north_america,
            "central-america" to R.string.continent_central_america,
            "south-america" to R.string.continent_south_america,
            "australia-oceania" to R.string.continent_oceania,
        )
    }
}
