package cz.motonavigace

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cz.motonavigace.databinding.ActivityTracksBinding
import cz.motonavigace.databinding.ItemTrackBinding
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Seznam zaznamenaných jízd a importovaných tras. Vybranou akci vrací
 * MainActivity jako EXTRA_ACTION (show/navigate) + EXTRA_PATH.
 */
class TracksActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_PATH = "path"
        const val ACTION_SHOW = "show"
        const val ACTION_NAVIGATE = "navigate"
    }

    private lateinit var b: ActivityTracksBinding
    private var entries: List<TrackLibrary.Entry> = emptyList()

    private val importGpx = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val e = TrackLibrary.import(this@TracksActivity, uri)
                Toast.makeText(this@TracksActivity, getString(R.string.imported, e.title), Toast.LENGTH_SHORT).show()
                reload()
            } catch (ex: Exception) {
                Toast.makeText(this@TracksActivity, getString(R.string.import_failed, ex.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTracksBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupEdgeToEdge(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = Adapter()
        b.btnImport.setOnClickListener { importGpx.launch(arrayOf("*/*")) }
        reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun reload() {
        lifecycleScope.launch {
            entries = TrackLibrary.loadAll()
            b.list.adapter?.notifyDataSetChanged()
            b.empty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun finishWith(action: String, e: TrackLibrary.Entry) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, action).putExtra(EXTRA_PATH, e.file.path))
        finish()
    }

    private fun showActions(e: TrackLibrary.Entry) {
        GloveMenu.show(this, e.title, listOf(
            GloveMenu.Item(getString(R.string.track_show)) { finishWith(ACTION_SHOW, e) },
            GloveMenu.Item(getString(R.string.track_navigate)) { finishWith(ACTION_NAVIGATE, e) },
            GloveMenu.Item(getString(R.string.track_share)) { share(e) },
            GloveMenu.Item(getString(R.string.delete)) { confirmDelete(e) },
        ))
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

    private fun confirmDelete(e: TrackLibrary.Entry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(e.title)
            .setMessage(R.string.track_delete_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                e.file.delete()
                reload()
            }
            .show()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val b: ItemTrackBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = entries[position]
            val ctx = this@TracksActivity
            val time = e.track.points.first().time.takeIf { it > 0 } ?: e.file.lastModified()
            val date = DateFormat.getMediumDateFormat(ctx).format(Date(time)) + " " +
                DateFormat.getTimeFormat(ctx).format(Date(time))
            val kind = getString(
                when {
                    e.file.name.startsWith("jizda-") -> R.string.kind_recorded
                    e.track.isRoute -> R.string.kind_route
                    else -> R.string.kind_track
                }
            )
            holder.b.title.text = e.title
            holder.b.kind.text = "$kind · $date"
            holder.b.stats.text = TrackLibrary.describe(e)
            holder.b.root.setOnClickListener { showActions(e) }
        }
    }
}
