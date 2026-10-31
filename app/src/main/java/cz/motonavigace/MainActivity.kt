package cz.motonavigace

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import cz.motonavigace.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.mapsforge.core.model.LatLong
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var b: ActivityMainBinding
    private lateinit var mapCtl: MapController

    private var lastLocation: Location? = null
    private var followOnFirstFix = true

    // Body trasy
    private var start: LatLong? = null
    private var end: LatLong? = null
    private var destinationName: String? = null
    private val vias = mutableListOf<LatLong>()
    /** Průjezdní body vybrané z GPX stopy – je jich hodně, takže je nekreslíme jako piny. */
    private var viasFromTrack = false
    /** Délka vygenerované projížďky po okolí; null = běžná trasa do cíle. */
    private var roundTripKm: Int? = null
    private var routeJob: Job? = null
    private var route: Route? = null
    private var routeTypeButtons: List<MaterialButton> = emptyList()
    /** Nalezené varianty trasy; [route] je ta vybraná. */
    private var routes: List<Route> = emptyList()

    // GPX stopa zobrazená z knihovny
    private var shownTrack: TrackLibrary.Entry? = null

    // Navigace
    private var navigator: Navigator? = null
    private var recalculating = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val prefs by lazy { getSharedPreferences("main", MODE_PRIVATE) }

    private val locationPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) startLocationUpdates()
        else Toast.makeText(this, R.string.location_denied, Toast.LENGTH_LONG).show()
    }

    private val searchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        onDestinationChosen(
            data.getStringExtra(SearchActivity.EXTRA_NAME).orEmpty(),
            LatLong(data.getDoubleExtra(SearchActivity.EXTRA_LAT, 0.0), data.getDoubleExtra(SearchActivity.EXTRA_LON, 0.0)),
        )
    }

    private val tracksLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        val path = data.getStringExtra(TracksActivity.EXTRA_PATH) ?: return@registerForActivityResult
        val navigate = data.getStringExtra(TracksActivity.EXTRA_ACTION) == TracksActivity.ACTION_NAVIGATE
        lifecycleScope.launch {
            runCatching { TrackLibrary.load(File(path)) }
                .onSuccess { if (navigate) navigateAlongTrack(it) else showTrack(it) }
                .onFailure { Toast.makeText(this@MainActivity, it.message, Toast.LENGTH_LONG).show() }
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(::importAndNavigate)
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startRecording() } // bez notifikací záznam funguje taky, jen ho není vidět v liště

    private val stopNavigationOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = stopNavigation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+ ukáže ikonu spouštěcího aliasu sám, starší verze kreslí úvodní obrazovku podle stylu
        if (Build.VERSION.SDK_INT < 31) setTheme(Settings.splashTheme)
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Mapa pod systémovými lištami na všech verzích, odsazení řeší applyWindowInsets()
        enableEdgeToEdge()
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        adaptToScreen(resources.configuration)
        onBackPressedDispatcher.addCallback(this, stopNavigationOnBack)

        mapCtl = MapController(this, b.mapView, ::onMapLongPress)
        applyWindowInsets()
        restoreMapPosition()
        // Naklonění jde nastavit, až mapa zná svou velikost
        b.mapView.post { setViewMode(MapViewMode.byId(prefs.getInt("view_mode", MapViewMode.PERSPECTIVE_3D.id))) }
        b.btnViewMode.setOnClickListener { showViewModeMenu() }
        b.btnZoomIn.setOnClickListener { mapCtl.zoomBy(1) }
        b.btnZoomOut.setOnClickListener { mapCtl.zoomBy(-1) }
        // Tlačítka přiblížení schováme, když by je zakryl panel trasy – napůl schované se špatně trefují
        b.root.viewTreeObserver.addOnPreDrawListener { hideCoveredZoomButtons(); true }
        setupRoutePanel()

        b.searchText.setOnClickListener { openSearch() }
        b.btnMenu.setOnClickListener { showMenu() }
        b.btnDownloads.setOnClickListener { openDownloads() }
        b.btnNoMapsDownload.setOnClickListener { openDownloads() }
        b.btnLocation.setOnClickListener {
            val loc = lastLocation
            if (loc != null) mapCtl.moveTo(LatLong(loc.latitude, loc.longitude))
            else {
                followOnFirstFix = true
                Toast.makeText(this, R.string.waiting_for_gps, Toast.LENGTH_SHORT).show()
                requestLocation()
            }
        }
        b.btnNavigate.setOnClickListener { startNavigation() }
        b.btnStopNav.setOnClickListener { stopNavigation() }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag(if (Settings.isEnglish()) "en-GB" else "cs-CZ")
                ttsReady = true
            }
        }

        b.recChip.setOnClickListener { confirmStopRecording() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Recording.state.collect(::onRecordingState)
            }
        }
        RecordingService.recoverUnfinished()?.let { TrackLibrary.lastRecorded = it }

        requestLocation()
        if (savedInstanceState != null) restoreWaypoints(savedInstanceState)
        handleIntent(intent)
    }

    /** Trasa přežije znovuvytvoření obrazovky (tmavý režim, velikost písma, hustota displeje…). */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        fun put(key: String, p: LatLong?) = p?.let { outState.putDoubleArray(key, doubleArrayOf(it.latitude, it.longitude)) }
        put("start", start)
        put("end", end)
        outState.putDoubleArray("vias", vias.flatMap { listOf(it.latitude, it.longitude) }.toDoubleArray())
        outState.putBoolean("viasFromTrack", viasFromTrack)
        outState.putString("destinationName", destinationName)
        roundTripKm?.let { outState.putInt("roundTripKm", it) }
    }

    private fun restoreWaypoints(state: Bundle) {
        fun get(key: String) = state.getDoubleArray(key)?.let { LatLong(it[0], it[1]) }
        start = get("start")
        end = get("end") ?: return
        vias.clear()
        state.getDoubleArray("vias")?.toList()?.chunked(2)?.forEach { vias += LatLong(it[0], it[1]) }
        viasFromTrack = state.getBoolean("viasFromTrack")
        destinationName = state.getString("destinationName")
        roundTripKm = state.getInt("roundTripKm", 0).takeIf { it > 0 }
        onWaypointsChanged()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** GPX otevřené z jiné aplikace (správce souborů, e-mail…). */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.data?.let(::importAndNavigate)
        setIntent(Intent(this, MainActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        mapCtl.onResume()
        Downloads.refresh(this)
        reloadMapsIfChanged()
        applyBikeDefaults()
        TrackLibrary.lastRecorded?.let {
            TrackLibrary.lastRecorded = null
            Toast.makeText(this, getString(R.string.recording_saved, it.nameWithoutExtension), Toast.LENGTH_LONG).show()
        }
    }

    override fun onPause() {
        val center = mapCtl.center
        prefs.edit {
            putFloat("lat", center.latitude.toFloat())
            putFloat("lon", center.longitude.toFloat())
            putInt("zoom", mapCtl.zoomLevel)
        }
        mapCtl.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        getSystemService(LocationManager::class.java).removeUpdates(this)
        tts?.shutdown()
        mapCtl.onDestroy()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        adaptToScreen(newConfig)
        // Po otočení se změnila volná plocha mapy – trasu znovu vejdeme (při navigaci ne, tam mapa sleduje jezdce)
        if (navigator == null) route?.let(::zoomToRoute)
    }

    /**
     * Na úzkých telefonech zabírají spodní panely celou šířku. Na šířku a na
     * tabletech mají pevnou šířku vlevo dole, aby zbytek mapy zůstal vidět.
     */
    private fun adaptToScreen(config: Configuration) {
        val density = resources.displayMetrics.density
        val wide = config.screenWidthDp >= 600
        for (panel in listOf(b.routePanel, b.navPanel)) {
            panel.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                width = if (wide) (420 * density).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
                gravity = if (wide) Gravity.BOTTOM or Gravity.START else Gravity.BOTTOM
            }
        }
        b.navBanner.updateLayoutParams<ViewGroup.LayoutParams> {
            width = if (wide) (420 * density).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    private fun hideCoveredZoomButtons() {
        val loc = IntArray(2)
        b.btnZoomOut.getLocationOnScreen(loc)
        val zoomBottom = loc[1] + b.btnZoomOut.height
        val covered = listOf(b.routePanel, b.navPanel).any { panel ->
            panel.visibility == View.VISIBLE && run { panel.getLocationOnScreen(loc); loc[1] < zoomBottom }
        }
        // FAB se musí schovávat přes show()/hide(), přímá změna viditelnosti ho nevykreslí zpátky
        for (fab in listOf(b.btnZoomIn, b.btnZoomOut)) {
            if (covered && fab.isOrWillBeShown) fab.hide()
            else if (!covered && fab.isOrWillBeHidden) fab.show()
        }
    }

    /** Mapa se kreslí pod systémové lišty, ovládací prvky se od nich odsadí. */
    private fun applyWindowInsets() {
        val pad = (12 * resources.displayMetrics.density).toInt()
        val panelMargin = (8 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            b.topArea.updatePadding(top = bars.top + pad, left = bars.left + pad, right = bars.right + pad)
            for (panel in listOf(b.routePanel, b.navPanel)) {
                panel.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = bars.bottom + panelMargin
                    leftMargin = bars.left + panelMargin
                    rightMargin = bars.right + panelMargin
                }
            }
            if (::mapCtl.isInitialized) mapCtl.setBottomInset(bars.left, bars.bottom)
            insets
        }
    }

    private fun openDownloads() = startActivity(Intent(this, DownloadsActivity::class.java))

    private fun openSearch() {
        val intent = Intent(this, SearchActivity::class.java)
        lastLocation?.let {
            intent.putExtra(SearchActivity.EXTRA_NEAR_LAT, it.latitude)
            intent.putExtra(SearchActivity.EXTRA_NEAR_LON, it.longitude)
        }
        mapCtl.center.let {
            intent.putExtra(SearchActivity.EXTRA_CENTER_LAT, it.latitude)
            intent.putExtra(SearchActivity.EXTRA_CENTER_LON, it.longitude)
        }
        searchLauncher.launch(intent)
    }

    private fun showMenu() {
        fun item(text: Int, action: () -> Unit) = GloveMenu.Item(getString(text), action = action)
        val items = mutableListOf(
            item(R.string.search_title) { openSearch() },
            item(R.string.round_trip) { askRoundTripLength() },
            if (Recording.state.value.active) item(R.string.recording_stop) { confirmStopRecording() }
            else item(R.string.recording_start) { requestRecording() },
            item(R.string.tracks_title) { tracksLauncher.launch(Intent(this, TracksActivity::class.java)) },
            item(R.string.lean_title) { startActivity(Intent(this, LeanActivity::class.java)) },
            item(R.string.import_gpx) { importLauncher.launch(arrayOf("*/*")) },
            item(R.string.offline_data) { openDownloads() },
        )
        if (start != null || end != null) items += item(R.string.clear_route) { clearRoute() }
        if (shownTrack != null) items += item(R.string.track_hide) { hideTrack() }
        items += item(R.string.settings_title) { startActivity(Intent(this, SettingsActivity::class.java)) }
        GloveMenu.show(this, null, items)
    }

    // ---------------------------------------------------------------- mapa

    private fun restoreMapPosition() {
        // Výchozí pohled: střed Česka
        val lat = prefs.getFloat("lat", 49.8f).toDouble()
        val lon = prefs.getFloat("lon", 15.5f).toDouble()
        mapCtl.moveTo(LatLong(lat, lon), prefs.getInt("zoom", 7), animate = false)
    }

    /** Načte všechny stažené *.map soubory jako jednu podkladovou mapu. */
    private fun reloadMapsIfChanged() {
        val hasMaps = mapCtl.setMaps(Storage.mapFiles())
        b.noMapsCard.visibility = if (hasMaps) View.GONE else View.VISIBLE
    }

    private fun setViewMode(mode: MapViewMode) {
        mapCtl.setMode(mode)
        b.btnViewMode.text = mode.short
        prefs.edit { putInt("view_mode", mode.id) }
    }

    private fun showViewModeMenu() {
        GloveMenu.show(this, getString(R.string.view_mode), MapViewMode.entries.map { m ->
            GloveMenu.Item(getString(m.title), checked = m == mapCtl.mode) { setViewMode(m) }
        })
    }

    /** Zobrazí celou trasu nad panelem trasy. */
    private fun zoomToRoute(r: Route) = zoomToPoints(r.points)

    /** Vejde body do části mapy, kterou nezakrývá horní lišta ani spodní panel. */
    private fun zoomToPoints(points: List<LatLong>) {
        if (points.size < 2) return
        // Až po rozvržení (těsně před vykreslením), ať známe skutečnou výšku panelu
        // i s právě přidanými tlačítky variant
        b.root.requestLayout()
        b.root.doOnPreDraw {
            val w = b.mapView.width
            val h = b.mapView.height
            // Spodní hrana vyhledávací lišty (nebo navigačního panelu); sloupec tlačítek
            // vpravo pod ní mapu nezakrývá, takže se do volné plochy nepočítá
            val bar = if (b.navBanner.visibility == View.VISIBLE) b.navBanner else b.searchBar
            val top = IntArray(2).also(bar::getLocationInWindow)[1] + bar.height -
                IntArray(2).also(b.mapView::getLocationInWindow)[1]
            val panel = b.routePanel.takeIf { it.visibility == View.VISIBLE }
            // Panel vlevo (široké obrazovky) zabírá šířku, ne výšku
            val panelBeside = panel != null && panel.width < b.root.width * 0.7
            when {
                panel == null -> mapCtl.fit(points, 0, top, w, h)
                panelBeside -> mapCtl.fit(points, panel.right, top, w, h)
                else -> mapCtl.fit(points, 0, top, w, panel.top)
            }
        }
    }

    // ---------------------------------------------------------------- poloha

    private fun requestLocation() {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (perms.any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startLocationUpdates()
        } else {
            locationPermission.launch(perms)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val lm = getSystemService(LocationManager::class.java)
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!lm.allProviders.contains(provider)) continue
            runCatching {
                lm.getLastKnownLocation(provider)?.let { if (lastLocation == null) onLocationChanged(it) }
                lm.requestLocationUpdates(provider, 1000L, 2f, this)
            }
        }
    }

    private var headingFrom: LatLong? = null
    private var lastHeading: Float? = null

    /**
     * Směr ikony: kurz z GPS v pohybu, jinak směr z posunu od posledního místa
     * (některé přijímače kurz nehlásí). Při stání drží poslední známý směr.
     */
    private fun headingFor(location: Location, pos: LatLong): Float? {
        if (location.hasBearing() && location.speed > 1.5f) {
            lastHeading = location.bearing
            headingFrom = pos
            return lastHeading
        }
        val from = headingFrom
        if (from == null) { headingFrom = pos; return lastHeading }
        if (from.sphericalDistance(pos) > 8) {
            val dLon = Math.toRadians(pos.longitude - from.longitude)
            val lat1 = Math.toRadians(from.latitude); val lat2 = Math.toRadians(pos.latitude)
            val y = Math.sin(dLon) * Math.cos(lat2)
            val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
            lastHeading = ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).toFloat()
            headingFrom = pos
        }
        return lastHeading
    }

    override fun onLocationChanged(location: Location) {
        // Síťová poloha nesmí přebít čerstvou a přesnější GPS
        val prev = lastLocation
        if (prev != null && location.provider != LocationManager.GPS_PROVIDER &&
            prev.provider == LocationManager.GPS_PROVIDER && location.time - prev.time < 10_000
        ) return

        lastLocation = location
        val pos = LatLong(location.latitude, location.longitude)
        mapCtl.setLocation(pos, headingFor(location, pos))

        navigator?.let { onNavigationFix(it, location, pos); return }

        // Na starou uloženou polohu (třeba z minulého dne) mapu necentrujeme
        val fresh = System.currentTimeMillis() - location.time < 2 * 60_000
        if (followOnFirstFix && fresh) {
            followOnFirstFix = false
            mapCtl.moveTo(pos, zoom = if (mapCtl.zoomLevel < 12) 14 else null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    // ---------------------------------------------------------------- trasa

    private fun setupRoutePanel() {
        val saved = prefs.getInt("route_type", RouteType.FASTEST.id)
        routeTypeButtons = RouteType.entries.map { type ->
            toggleButton(type.label).apply {
                tag = type
                isChecked = type.id == saved
                // Vždy právě jedna vybraná volba – i opakované klepnutí ji nechá zapnutou
                setOnClickListener { selectRouteType(type); onOptionsChanged() }
            }
        }
        fillGrid(b.routeTypes, routeTypeButtons, columns = 2)
        b.chipMotorways.isChecked = prefs.getBoolean("avoid_motorways", false)
        b.chipUnpaved.isChecked = prefs.getBoolean("avoid_unpaved", true)
        b.chipFerries.isChecked = prefs.getBoolean("avoid_ferries", false)
        listOf(b.chipMotorways, b.chipUnpaved, b.chipFerries).forEach { chip ->
            chip.addOnCheckedChangeListener { _, _ -> onOptionsChanged() }
        }
        b.btnClear.setOnClickListener { clearRoute() }
        b.btnNewLoop.setOnClickListener { roundTripKm?.let(::generateRoundTrip) }
        b.btnReverse.setOnClickListener {
            val s = start
            start = end
            end = s
            vias.reverse()
            if (roundTripKm == null) destinationName = null
            onWaypointsChanged()
        }
    }

    /** Po změně typu motocyklu v nastavení přednastaví typ trasy a vyhýbání. */
    private fun applyBikeDefaults() {
        val bike = Settings.bike
        val applied = prefs.getInt("bike_applied", -1)
        if (applied == bike.id) return
        prefs.edit { putInt("bike_applied", bike.id) }
        // Při prvním spuštění necháme volby, které si jezdec už nastavil
        if (applied == -1) return
        selectRouteType(bike.routeType)
        b.chipMotorways.isChecked = bike.avoidMotorways
        b.chipUnpaved.isChecked = bike.avoidUnpaved
    }

    private fun selectedRouteType(): RouteType =
        routeTypeButtons.firstOrNull { it.isChecked }?.tag as? RouteType ?: RouteType.FASTEST

    private fun selectRouteType(type: RouteType) = routeTypeButtons.forEach { it.isChecked = it.tag == type }

    private fun toggleButton(text: Int): MaterialButton =
        (layoutInflater.inflate(R.layout.item_toggle, null, false) as MaterialButton).apply { setText(text) }

    /** Tlačítka v mřížce se stejně širokými sloupci – zarovnaná do bloku, poslední řádek se roztáhne. */
    private fun fillGrid(grid: LinearLayout, buttons: List<View>, columns: Int) {
        val gap = (8 * resources.displayMetrics.density).toInt()
        grid.removeAllViews()
        buttons.chunked(columns).forEachIndexed { r, rowItems ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowItems.forEachIndexed { i, v ->
                row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = gap
                })
            }
            grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (r > 0) topMargin = gap / 2
            })
        }
    }

    private fun options() = RouteOptions(
        type = selectedRouteType(),
        avoidMotorways = b.chipMotorways.isChecked,
        avoidUnpaved = b.chipUnpaved.isChecked,
        avoidFerries = b.chipFerries.isChecked,
    )

    private fun onOptionsChanged() {
        val o = options()
        prefs.edit {
            putInt("route_type", o.type.id)
            putBoolean("avoid_motorways", o.avoidMotorways)
            putBoolean("avoid_unpaved", o.avoidUnpaved)
            putBoolean("avoid_ferries", o.avoidFerries)
        }
        calculateRoute()
    }

    private fun onDestinationChosen(name: String, position: LatLong) {
        end = position
        destinationName = name
        vias.clear()
        viasFromTrack = false
        roundTripKm = null
        // Trasa z aktuální polohy, pokud ji známe
        lastLocation?.let { start = LatLong(it.latitude, it.longitude) }
        onWaypointsChanged(zoomWhenDone = true)
    }

    private fun onMapLongPress(p: LatLong) = runOnUiThread {
        if (navigator != null) return@runOnUiThread
        fun choose(which: Int) {
            when (which) {
                0 -> start = p
                1 -> {
                    if (viasFromTrack) vias.clear()
                    viasFromTrack = false
                    roundTripKm = null
                    vias += p
                }
                2 -> {
                    end = p
                    roundTripKm = null
                    destinationName = null
                    // Bez startu vyjedeme z aktuální polohy
                    if (start == null) lastLocation?.let { start = LatLong(it.latitude, it.longitude) }
                }
            }
            onWaypointsChanged()
        }
        GloveMenu.show(this, "%.5f, %.5f".format(p.latitude, p.longitude), listOf(
            GloveMenu.Item(getString(R.string.set_start)) { choose(0) },
            GloveMenu.Item(getString(R.string.add_via)) { choose(1) },
            GloveMenu.Item(getString(R.string.set_end)) { choose(2) },
        ))
    }

    private fun clearRoute() {
        hideTrack()
        mapCtl.resetViewCenter()
        start = null
        end = null
        destinationName = null
        vias.clear()
        viasFromTrack = false
        roundTripKm = null
        onWaypointsChanged()
    }

    private fun onWaypointsChanged(zoomWhenDone: Boolean = false) {
        refreshPins()
        b.destinationName.text = destinationName
        b.destinationName.visibility = if (destinationName != null) View.VISIBLE else View.GONE
        b.btnNewLoop.visibility = if (roundTripKm != null) View.VISIBLE else View.GONE
        // Přiblížit tak, aby byly vidět všechny varianty trasy
        calculateRoute(onDone = { if (zoomWhenDone) zoomToPoints(routes.flatMap(Route::points)) })
    }

    private fun calculateRoute(onDone: (Route) -> Unit = {}) {
        routeJob?.cancel()
        val s = start
        val e = end
        val hasAny = s != null || e != null || vias.isNotEmpty()
        b.routeButtons.visibility = if (hasAny) View.VISIBLE else View.GONE
        // Panel trasy jen s vybraným cílem, jinak nechává mapu volnou
        if (navigator == null && !recalculating) b.routePanel.visibility = if (e != null) View.VISIBLE else View.GONE

        if (s == null || e == null) {
            showRoute(null)
            b.routeStatus.setText(
                when {
                    !hasAny -> R.string.hint_long_press
                    s == null -> R.string.hint_need_start
                    else -> R.string.hint_need_end
                }
            )
            return
        }

        b.routeProgress.visibility = View.VISIBLE
        b.routeStatus.setText(R.string.calculating)
        b.btnNavigate.isEnabled = false
        routeJob = lifecycleScope.launch {
            try {
                val points = listOf(s) + vias + e
                // Okruh má vlastní hledání variant („Jiný okruh“), alternativy jen u běžné trasy
                val found = if (roundTripKm != null) listOf(RoutePlanner.calculate(points, options(), looseWaypoints = true))
                    else RoutePlanner.calculateAlternatives(points, options())
                showRoutes(found, 0)
                onDone(found[0])
            } catch (ex: RoutingException) {
                showRoute(null)
                b.routeStatus.text = ex.describe(this@MainActivity)
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Exception) {
                showRoute(null)
                b.routeStatus.text = getString(R.string.error_routing, ex.message.orEmpty())
            } finally {
                b.routeProgress.visibility = View.GONE
            }
        }
    }

    /** Zobrazí varianty trasy, [selected] červeně, ostatní šedě, a tlačítka pro výběr. */
    private fun showRoutes(found: List<Route>, selected: Int) {
        routes = found
        val r = found[selected]
        showRoute(r, keepAlternatives = true)
        b.routeStatus.text = formatSummary(r)
        mapCtl.showAlternatives(found.filterIndexed { i, _ -> i != selected }.map { it.points })
        b.routeAlternatives.visibility = if (found.size > 1) View.VISIBLE else View.GONE
        if (found.size > 1) {
            fillGrid(b.routeAlternatives, found.mapIndexed { i, alt ->
                toggleButton(R.string.app_name).apply {
                    text = getString(R.string.route_variant, formatDistance(alt.lengthMeters.toDouble()),
                        formatDuration(alt.durationSeconds.toDouble()))
                    isChecked = i == selected
                    setOnClickListener { showRoutes(routes, i) }
                }
            }, columns = found.size)
        }
    }

    private fun showRoute(r: Route?, keepAlternatives: Boolean = false) {
        if (!keepAlternatives) {
            routes = listOfNotNull(r)
            mapCtl.showAlternatives(emptyList())
            b.routeAlternatives.visibility = View.GONE
        }
        route = r
        mapCtl.showRoute(r?.points)
        b.btnNavigate.isEnabled = r != null
        // Volby trasy ukazujeme i bez trasy, aby šly změnit před výpočtem
        b.routeDetails.visibility = if (start != null && end != null) View.VISIBLE else View.GONE
    }

    private fun formatSummary(r: Route) =
        "${formatDistance(r.lengthMeters.toDouble())} · ${formatDuration(r.durationSeconds.toDouble())}"

    // ---------------------------------------------------------------- projížďka po okolí

    private fun askRoundTripLength() {
        val density = resources.displayMetrics.density
        val saved = prefs.getInt("round_trip_km", 100)
        val label = TextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
        }
        val slider = Slider(this).apply {
            valueFrom = 20f
            valueTo = 300f
            stepSize = 10f
            value = saved.toFloat().coerceIn(20f, 300f)
            setLabelFormatter { "${it.toInt()} km" }
        }
        fun update() {
            val km = slider.value.toInt()
            // Hrubý odhad času podle průměru ~55 km/h na okreskách
            label.text = getString(R.string.round_trip_length, km, formatDuration(km / 55.0 * 3600))
        }
        update()
        slider.addOnChangeListener { _, _, _ -> update() }
        val presets = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fillGrid(presets, listOf(30, 50, 100, 150, 200, 300).map { km ->
            toggleButton(R.string.app_name).apply {
                text = "$km km"
                isCheckable = false
                setOnClickListener { slider.value = km.toFloat() }
            }
        }, columns = 3)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * density).toInt()
            setPadding(pad, (8 * density).toInt(), pad, 0)
            addView(label)
            addView(slider)
            addView(presets)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.round_trip)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.round_trip_create) { _, _ ->
                val km = slider.value.toInt()
                prefs.edit { putInt("round_trip_km", km) }
                generateRoundTrip(km)
            }
            .show()
    }

    /** Vygeneruje okruh dané délky z aktuální polohy (bez GPS ze středu mapy). */
    private fun generateRoundTrip(km: Int) {
        val from = lastLocation?.let { LatLong(it.latitude, it.longitude) } ?: run {
            Toast.makeText(this, R.string.round_trip_from_map_center, Toast.LENGTH_LONG).show()
            mapCtl.center
        }
        hideTrack()
        routeJob?.cancel()
        roundTripKm = km
        start = from
        end = from
        vias.clear()
        viasFromTrack = true
        destinationName = getString(R.string.round_trip_name, km)
        refreshPins()
        showRoute(null)
        b.destinationName.text = destinationName
        b.destinationName.visibility = View.VISIBLE
        b.btnNewLoop.visibility = View.VISIBLE
        b.routeButtons.visibility = View.VISIBLE
        b.routePanel.visibility = View.VISIBLE
        b.routeProgress.visibility = View.VISIBLE
        b.btnNewLoop.isEnabled = false
        b.routeStatus.setText(R.string.round_trip_searching)
        routeJob = lifecycleScope.launch {
            try {
                val trip = RoundTripPlanner.generate(from, km, options())
                vias += trip.shaping
                showRoute(trip.route)
                b.routeStatus.text = formatSummary(trip.route)
                zoomToRoute(trip.route)
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Exception) {
                showRoute(null)
                b.routeStatus.text = (ex as? RoutingException)?.describe(this@MainActivity) ?: ex.message
            } finally {
                b.routeProgress.visibility = View.GONE
                b.btnNewLoop.isEnabled = true
            }
        }
    }

    // ---------------------------------------------------------------- GPX a záznam jízdy

    private fun showTrack(e: TrackLibrary.Entry, zoom: Boolean = true) {
        shownTrack = e
        mapCtl.showTrack(e.track.points.map { it.latLong })
        if (zoom) zoomToPoints(e.track.points.map { it.latLong })
        Toast.makeText(this, "${e.title}\n${TrackLibrary.describe(e)}", Toast.LENGTH_LONG).show()
    }

    private fun hideTrack() {
        shownTrack = null
        mapCtl.showTrack(null)
    }

    /** Naplánuje trasu po silnicích tak, aby kopírovala GPX (přes průjezdní body). */
    private fun navigateAlongTrack(e: TrackLibrary.Entry) {
        showTrack(e, zoom = false)
        val shaping = Gpx.shapingPoints(e.track.points)
        start = lastLocation?.let { LatLong(it.latitude, it.longitude) } ?: shaping.first()
        vias.clear()
        vias += shaping.drop(1).dropLast(1)
        viasFromTrack = true
        roundTripKm = null
        end = shaping.last()
        destinationName = e.title
        onWaypointsChanged(zoomWhenDone = true)
    }

    private fun importAndNavigate(uri: Uri) {
        lifecycleScope.launch {
            try {
                val e = TrackLibrary.import(this@MainActivity, uri)
                Toast.makeText(this@MainActivity, getString(R.string.imported, e.title), Toast.LENGTH_SHORT).show()
                navigateAlongTrack(e)
            } catch (ex: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.import_failed, ex.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestRecording() {
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            requestLocation()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startRecording()
        }
    }

    private fun startRecording() = Recording.start(this)

    private fun confirmStopRecording() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.recording_stop)
            .setMessage(formatDistance(Recording.state.value.distanceMeters))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.recording_stop) { _, _ -> Recording.stop(this) }
            .show()
    }

    private var drawnRecordedPoints = 0

    private fun onRecordingState(state: Recording.State) {
        b.recChip.visibility = if (state.active) View.VISIBLE else View.GONE
        if (!state.active) {
            if (drawnRecordedPoints > 0) {
                mapCtl.showRecorded(null)
                drawnRecordedPoints = 0
                // Služba mezitím jízdu uložila
                TrackLibrary.lastRecorded?.let {
                    TrackLibrary.lastRecorded = null
                    Toast.makeText(this, getString(R.string.recording_saved, it.nameWithoutExtension), Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        val minutes = (System.currentTimeMillis() - state.startTime) / 60_000
        b.recChip.text = "REC  ${formatDistance(state.distanceMeters)} · ${minutes / 60}:%02d".format(minutes % 60)
        if (state.pointCount != drawnRecordedPoints) {
            val pts = Recording.points()
            mapCtl.showRecorded(pts.map { it.latLong })
            drawnRecordedPoints = pts.size
        }
    }

    // ---------------------------------------------------------------- navigace

    private fun startNavigation() {
        val loc = lastLocation
        val r = route
        if (loc == null || r == null) {
            Toast.makeText(this, R.string.need_location, Toast.LENGTH_LONG).show()
            return
        }
        val here = LatLong(loc.latitude, loc.longitude)
        setNavigationUi(true)
        // Během navigace jen vybraná trasa, šedé varianty by mátly
        mapCtl.showAlternatives(emptyList())
        mapCtl.startFollowing()
        if (here.sphericalDistance(r.points.first()) > 100) {
            // Trasa nezačíná u jezdce – spočítáme ji z aktuální polohy
            recalculateFrom(here)
        } else {
            beginGuidance(r)
        }
    }

    private fun beginGuidance(r: Route) {
        navigator = Navigator(r, ::speak)
        lastLocation?.let { onNavigationFix(navigator!!, it, LatLong(it.latitude, it.longitude)) }
    }

    private fun stopNavigation() {
        navigator = null
        recalculating = false
        tts?.stop()
        setNavigationUi(false)
        mapCtl.stopFollowing()
        route?.let { zoomToRoute(it) }
    }

    private fun setNavigationUi(on: Boolean) {
        b.searchBar.visibility = if (on) View.GONE else View.VISIBLE
        b.btnDownloads.visibility = if (on) View.GONE else View.VISIBLE
        b.routePanel.visibility = if (!on && end != null) View.VISIBLE else View.GONE
        b.navBanner.visibility = if (on) View.VISIBLE else View.GONE
        b.navPanel.visibility = if (on) View.VISIBLE else View.GONE
        stopNavigationOnBack.isEnabled = on
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            b.navArrow.text = ""
            b.navDistance.text = ""
            b.navInstruction.setText(R.string.calculating)
            b.navEta.text = ""
            b.navRemaining.text = ""
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun onNavigationFix(nav: Navigator, location: Location, pos: LatLong) {
        val state = nav.update(pos, location.accuracy, location.speed)

        // Mapa sleduje jezdce; natočení a naklonění podle režimu zobrazení.
        // Na trase podle směru silnice, mimo ni podle kurzu z GPS (jen v pohybu, jinak je nespolehlivý).
        val heading = if (!state.offRoute) state.routeBearing
            else location.bearing.takeIf { location.hasBearing() && location.speed > 1.5f }
        mapCtl.follow(state.snapped.takeUnless { state.offRoute } ?: pos, heading, location.speed)

        showLanes(state.next?.takeIf { state.toNext < LANES_DISTANCE && !state.offRoute })
        b.navArrow.text = Navigator.arrow(state.next)
        b.navDistance.text = formatDistance(state.toNext)
        b.navInstruction.text = state.next?.let { Navigator.instruction(it).replaceFirstChar { c -> c.uppercase() } }
        val eta = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + state.remainingSeconds.toLong() * 1000))
        b.navEta.text = getString(R.string.nav_eta, eta)
        b.navRemaining.text = "${formatDistance(state.remainingMeters)} · ${formatDuration(state.remainingSeconds)}"

        when {
            state.arrived -> {
                b.navArrow.text = Navigator.arrow(Maneuver(0, 0.0, "END", 0))
                b.navDistance.text = ""
                b.navInstruction.text = getString(R.string.arrived)
            }
            state.offRoute && !recalculating -> {
                b.navInstruction.setText(R.string.recalculating)
                speak(getString(R.string.recalculating).removeSuffix("…"))
                recalculateFrom(pos, passedSegment = state.segment)
            }
        }
    }

    /**
     * Řada pruhů v horním panelu: doporučené bíle se šipkou manévru, ostatní šedě rovně.
     * Ukazuje se jen na vícepruhových silnicích a v posledních [LANES_DISTANCE] metrech.
     */
    private fun showLanes(m: Maneuver?) {
        val hint = m?.lanes
        b.navLanes.visibility = if (hint == null) View.GONE else View.VISIBLE
        if (hint == null) return
        val arrow = Navigator.arrow(m)
        b.navLanes.removeAllViews()
        for (i in 0 until hint.count) {
            val on = i in hint.recommended
            b.navLanes.addView(TextView(this).apply {
                text = if (on) arrow else "↑"
                textSize = 30f
                setTextColor(if (on) 0xFFFFFFFF.toInt() else 0x66FFFFFF)
                gravity = Gravity.CENTER
                minWidth = (40 * resources.displayMetrics.density).toInt()
                if (on) setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            // Dělicí čára mezi pruhy
            if (i < hint.count - 1) b.navLanes.addView(View(this).apply { setBackgroundColor(0x55FFFFFF) },
                LinearLayout.LayoutParams((2 * resources.displayMetrics.density).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /** Nová trasa z [here] přes dosud neprojeté průjezdní body do cíle. */
    private fun recalculateFrom(here: LatLong, passedSegment: Int = -1) {
        val e = end ?: return
        val old = route
        if (old != null && passedSegment >= 0) {
            // Průjezdní body, které už jsou za námi, vynecháme
            vias.removeAll { via -> nearestIndex(old, via) <= passedSegment }
        }
        recalculating = true
        start = here
        routeJob?.cancel()
        routeJob = lifecycleScope.launch {
            try {
                val r = RoutePlanner.calculate(listOf(here) + vias + e, options(), looseWaypoints = roundTripKm != null)
                showRoute(r)
                b.routeStatus.text = formatSummary(r)
                refreshPins()
                if (b.navPanel.visibility == View.VISIBLE) beginGuidance(r)
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Exception) {
                b.navInstruction.text = (ex as? RoutingException)?.describe(this@MainActivity) ?: ex.message
            } finally {
                recalculating = false
            }
        }
    }

    private fun refreshPins() = mapCtl.setPins(start, if (viasFromTrack) emptyList() else vias, end)

    private fun nearestIndex(r: Route, p: LatLong): Int =
        r.points.indices.minByOrNull { r.points[it].sphericalDistance(p) } ?: 0

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text)
    }

    private companion object {
        /** Odkud před manévrem ukazovat pruhy (m). */
        const val LANES_DISTANCE = 1500.0
    }
}
