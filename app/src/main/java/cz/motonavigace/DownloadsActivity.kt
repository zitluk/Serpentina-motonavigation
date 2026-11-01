package cz.motonavigace

import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

class DownloadsActivity : AppCompatActivity() {

    private val adapter = RegionAdapter()
    /** Průběh stahování podle regionu – sdílené routovací dlaždice patří jen regionu, který je spustil. */
    private var progress: Map<String, List<Downloads.Progress>> = emptyMap()
    /** Datum mapy na serveru podle regionu (zjišťuje se jednou po otevření obrazovky). */
    private var remoteDates: Map<String, Long> = emptyMap()
    /** Datum dat ve stažené mapě; klíč obsahuje i čas změny souboru, ať se po aktualizaci načte znovu. */
    private val localDates = mutableMapOf<String, Long?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityDownloadsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(binding.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

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
                    progress = Downloads.refresh(this@DownloadsActivity)
                    adapter.notifyDataSetChanged()
                    delay(1000)
                }
            }
        }
    }

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

    private inner class RegionAdapter : RecyclerView.Adapter<RegionAdapter.Holder>() {
        inner class Holder(val b: ItemRegionBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = Region.ALL.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemRegionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val region = Region.ALL[position]
            val files = Downloads.regionFiles(region).map { it.second }
            val running = progress[region.name].orEmpty()
            val ctx = this@DownloadsActivity
            with(holder.b) {
                name.setText(region.label)
                when {
                    running.isNotEmpty() -> {
                        val done = running.sumOf { it.downloaded }
                        val total = running.sumOf { it.total.coerceAtLeast(0) }
                        status.text = getString(R.string.downloading, files.count { it.isFile }, files.size) + " · " +
                            Formatter.formatShortFileSize(ctx, done) +
                            if (total > 0) " / " + Formatter.formatShortFileSize(ctx, total) else ""
                        this.progress.visibility = android.view.View.VISIBLE
                        this.progress.isIndeterminate = total <= 0
                        update.visibility = android.view.View.GONE
                        if (total > 0) this.progress.progress = (done * 100 / total).toInt()
                        action.setText(R.string.cancel)
                        action.setOnClickListener { Downloads.cancel(ctx, region); notifyItemChanged(position) }
                    }
                    Downloads.isDownloaded(region) -> {
                        val size = files.sumOf { it.length() }
                        val local = localDate(files[0])
                        val remote = remoteDates[region.name]
                        val newer = local != null && remote != null && Downloads.isNewer(remote, local)
                        status.text = listOfNotNull(
                            getString(R.string.downloaded) + " · " + Formatter.formatShortFileSize(ctx, size),
                            local?.let { getString(R.string.map_version, formatDate(it)) },
                            if (newer) getString(R.string.map_update_available, formatDate(remote!!)) else null,
                        ).joinToString("\n")
                        this.progress.visibility = android.view.View.GONE
                        update.visibility = if (newer) android.view.View.VISIBLE else android.view.View.GONE
                        update.setOnClickListener { Downloads.update(ctx, region); warnWifiOnly(); notifyItemChanged(position) }
                        action.setText(R.string.delete)
                        action.setOnClickListener { Downloads.delete(region); notifyItemChanged(position) }
                    }
                    else -> {
                        val have = files.count { it.isFile }
                        update.visibility = android.view.View.GONE
                        // Routovací dlaždice sdílí víc zemí – bez vlastní mapy nebo POI region za rozstažený nepovažujeme
                        val started = files.take(2).any { it.isFile }
                        status.text = if (started) getString(R.string.partially_downloaded, have, files.size) else getString(R.string.not_downloaded)
                        this.progress.visibility = android.view.View.GONE
                        action.setText(R.string.download)
                        action.setOnClickListener { Downloads.start(ctx, region); warnWifiOnly(); notifyItemChanged(position) }
                    }
                }
            }
        }
    }
}
