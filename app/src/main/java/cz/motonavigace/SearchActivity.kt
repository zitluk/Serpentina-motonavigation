package cz.motonavigace

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cz.motonavigace.databinding.ActivitySearchBinding
import cz.motonavigace.databinding.ItemPlaceBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mapsforge.core.model.LatLong

/** Offline vyhledání cíle. Výsledek vrací jako EXTRA_NAME/EXTRA_LAT/EXTRA_LON. */
class SearchActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        /** Poloha uživatele pro řazení podle vzdálenosti. */
        const val EXTRA_NEAR_LAT = "near_lat"
        const val EXTRA_NEAR_LON = "near_lon"
    }

    private lateinit var b: ActivitySearchBinding
    private var places: List<Place> = emptyList()
    private var searchJob: Job? = null
    private var near: LatLong? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (intent.hasExtra(EXTRA_NEAR_LAT)) {
            near = LatLong(intent.getDoubleExtra(EXTRA_NEAR_LAT, 0.0), intent.getDoubleExtra(EXTRA_NEAR_LON, 0.0))
        }

        b.results.layoutManager = LinearLayoutManager(this)
        b.results.adapter = Adapter()

        if (Storage.poiFiles().isEmpty()) {
            b.message.setText(R.string.search_no_data)
        }
        b.query.doAfterTextChanged { scheduleSearch(debounceMs = 300) }
        b.query.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) { scheduleSearch(0); true } else false
        }
        b.query.requestFocus()
        // stateVisible z manifestu na novějších Androidech nestačí, klávesnici si řekneme sami
        b.query.post {
            WindowCompat.getInsetsController(window, b.query).show(WindowInsetsCompat.Type.ime())
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun scheduleSearch(debounceMs: Long) {
        searchJob?.cancel()
        val query = b.query.text?.toString().orEmpty()
        searchJob = lifecycleScope.launch {
            delay(debounceMs)
            val result = PoiSearch.search(query, near)
            places = result
            b.results.adapter?.notifyDataSetChanged()
            b.message.text = when {
                Storage.poiFiles().isEmpty() -> getString(R.string.search_no_data)
                query.isNotBlank() && result.isEmpty() -> getString(R.string.search_nothing)
                else -> ""
            }
        }
    }

    private fun choose(place: Place) {
        setResult(RESULT_OK, Intent()
            .putExtra(EXTRA_NAME, place.name)
            .putExtra(EXTRA_LAT, place.position.latitude)
            .putExtra(EXTRA_LON, place.position.longitude))
        finish()
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {
        inner class Holder(val b: ItemPlaceBinding) : RecyclerView.ViewHolder(b.root)

        override fun getItemCount() = places.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemPlaceBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val place = places[position]
            holder.b.name.text = place.name
            holder.b.detail.text = listOfNotNull(place.description, place.distance?.let(::formatDistance))
                .joinToString(" · ")
            holder.b.root.setOnClickListener { choose(place) }
        }
    }
}
