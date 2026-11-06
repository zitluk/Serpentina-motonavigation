package cz.motonavigace

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
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
        /** Střed mapy – pro rychlé hledání, když poloha ještě není známá. */
        const val EXTRA_CENTER_LAT = "center_lat"
        const val EXTRA_CENTER_LON = "center_lon"
    }

    private lateinit var b: ActivitySearchBinding
    private var places: List<Place> = emptyList()
    private var searchJob: Job? = null
    private var near: LatLong? = null
    private var center: LatLong? = null
    private val quickButtons by lazy {
        mapOf(b.quickFuel to PoiCategory.FUEL, b.quickParking to PoiCategory.PARKING, b.quickFood to PoiCategory.FOOD)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(b.root)
        setupEdgeToEdge(b.root)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        if (intent.hasExtra(EXTRA_NEAR_LAT)) {
            near = LatLong(intent.getDoubleExtra(EXTRA_NEAR_LAT, 0.0), intent.getDoubleExtra(EXTRA_NEAR_LON, 0.0))
        }

        if (intent.hasExtra(EXTRA_CENTER_LAT)) {
            center = LatLong(intent.getDoubleExtra(EXTRA_CENTER_LAT, 0.0), intent.getDoubleExtra(EXTRA_CENTER_LON, 0.0))
        }
        for ((button, category) in quickButtons) {
            button.setOnClickListener {
                // Vybrané je vždy nejvýš jedno; druhé ťuknutí na vybrané výsledky zavře
                if (button.isChecked) {
                    quickButtons.keys.filter { it != button }.forEach { it.isChecked = false }
                    searchCategory(category)
                } else {
                    showHistory()
                }
            }
        }

        b.results.layoutManager = LinearLayoutManager(this)
        b.results.adapter = Adapter()

        if (Storage.poiFiles().isEmpty()) {
            showMessage(getString(R.string.search_no_data))
        }
        b.historyClear.setOnClickListener { confirmClearHistory() }
        showHistory()
        b.query.doAfterTextChanged {
            // Psaní ruší rychlé hledání; prázdné pole po ťuknutí na čip výsledky čipu nesmaže
            if (!it.isNullOrBlank()) quickButtons.keys.forEach { button -> button.isChecked = false }
            if (quickButtons.keys.none(MaterialButton::isChecked)) scheduleSearch(debounceMs = 300)
        }
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
        if (query.isBlank()) {
            showHistory()
            return
        }
        searchJob = lifecycleScope.launch {
            delay(debounceMs)
            val result = PoiSearch.search(query, near)
            history = emptyList()
            b.historyHeader.isVisible = false
            places = result
            b.results.adapter?.notifyDataSetChanged()
            showMessage(when {
                Storage.poiFiles().isEmpty() -> getString(R.string.search_no_data)
                query.isNotBlank() && result.isEmpty() -> getString(R.string.search_nothing)
                else -> ""
            })
        }
    }

    private fun searchCategory(category: PoiCategory) {
        searchJob?.cancel()
        b.query.text?.clear()
        WindowCompat.getInsetsController(window, b.query).hide(WindowInsetsCompat.Type.ime())
        val around = near ?: center ?: return
        searchJob = lifecycleScope.launch {
            showResults(emptyList(), "")
            val result = PoiSearch.searchCategory(category, around)
            showResults(result, when {
                Storage.poiFiles().isEmpty() -> getString(R.string.search_no_data)
                result.isEmpty() -> getString(R.string.quick_nothing, PoiSearch.CATEGORY_MAX_RADIUS_KM)
                near == null -> getString(R.string.quick_from_map)
                else -> ""
            })
        }
    }

    private fun showResults(result: List<Place>, message: String) {
        history = emptyList()
        b.historyHeader.isVisible = false
        places = result
        b.results.adapter?.notifyDataSetChanged()
        showMessage(message)
    }

    /** Prázdná zpráva nezabírá místo nad výsledky. */
    private fun showMessage(message: String) {
        b.message.text = message
        b.message.isVisible = message.isNotEmpty()
    }

    // ---------------------------------------------------------------- historie cílů

    /** Položky historie v [places] (stejné pořadí); prázdné = zobrazují se výsledky hledání. */
    private var history: List<DestinationHistory.Entry> = emptyList()

    /** Nedávné cíle, dokud jezdec nic nehledá. */
    private fun showHistory() {
        history = DestinationHistory.load(this)
        places = history.map { e ->
            Place(e.name, getString(R.string.history_item), e.position, near?.let { e.position.sphericalDistance(it) })
        }
        b.historyHeader.isVisible = history.isNotEmpty()
        b.results.adapter?.notifyDataSetChanged()
        if (Storage.poiFiles().isNotEmpty()) showMessage("")
    }

    private fun confirmClearHistory() {
        GloveMenu.show(this, getString(R.string.history_clear_confirm), listOf(
            GloveMenu.Item(getString(R.string.history_clear)) {
                DestinationHistory.clear(this)
                showHistory()
            },
            GloveMenu.Item(getString(R.string.cancel)) {},
        ))
    }

    /** Dlouhý stisk na nedávný cíl – odebrat jen ten jeden. */
    private fun offerRemove(entry: DestinationHistory.Entry) {
        GloveMenu.show(this, entry.name, listOf(
            GloveMenu.Item(getString(R.string.history_remove)) {
                DestinationHistory.remove(this, entry)
                showHistory()
            },
            GloveMenu.Item(getString(R.string.cancel)) {},
        ))
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
            holder.b.detail.text = listOfNotNull(place.description.ifEmpty { null }, place.distance?.let(::formatDistance))
                .joinToString(" · ")
            holder.b.root.setOnClickListener { choose(place) }
            val entry = history.getOrNull(position)
            holder.b.root.setOnLongClickListener {
                entry?.let(::offerRemove)
                entry != null
            }
        }
    }
}
