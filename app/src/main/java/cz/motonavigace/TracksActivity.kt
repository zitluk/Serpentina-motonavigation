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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

        /** „3. 11. 2026 13:45“ podle jazyka a formátu času v telefonu. */
        fun rideDate(context: android.content.Context, e: TrackLibrary.Entry): String {
            val d = Date(e.startTime)
            return DateFormat.getMediumDateFormat(context).format(d) + " " + DateFormat.getTimeFormat(context).format(d)
        }
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

    /** Detail jízdy; zobrazení nebo navigaci po trase předá dál do MainActivity, po smazání obnoví seznam. */
    private val detail = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data?.getStringExtra(EXTRA_ACTION) != null) {
            setResult(RESULT_OK, data)
            finish()
        } else {
            reload()
        }
    }

    private fun openDetail(e: TrackLibrary.Entry) =
        detail.launch(Intent(this, TrackDetailActivity::class.java).putExtra(EXTRA_PATH, e.file.path))

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val b: ItemTrackBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemTrackBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val e = entries[position]
            // Jízda jen datem a časem, importovaná trasa svým názvem; údaje jsou v detailu
            holder.b.title.text = if (e.isRide) rideDate(this@TracksActivity, e) else e.title
            holder.b.icon.setImageResource(if (e.isRide) R.drawable.ic_record else R.drawable.ic_route)
            holder.b.root.setOnClickListener { openDetail(e) }
        }
    }
}
