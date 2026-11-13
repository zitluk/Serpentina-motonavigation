package cz.motonavigace

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.Surface
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
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.doOnPreDraw
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.slider.Slider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt
import cz.motonavigace.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import java.io.File
import java.text.DateFormat
import java.util.Date

class MainActivity : AppCompatActivity(), LocationListener {

    private lateinit var b: ActivityMainBinding
    private lateinit var mapCtl: MapController

    private var lastLocation: Location? = null
    /** Mapa se ještě má vycentrovat na polohu – až na dost přesnou (do [PRECISE_ACCURACY]). */
    private var waitingForFix = false
    private val stopWaitingForFix = Runnable { lastLocation?.takeIf(::isFresh)?.let { centerOnFix(it) } }
    /** Při spuštění aplikace se po první nové poloze zeptáme, zda navigovat k zaparkované motorce. */
    private var askForBike = false
    /** Okamžik spuštění – poslední známá poloha z doby před ním může být ještě u motorky. */
    private val launchedAtNanos = SystemClock.elapsedRealtimeNanos()

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
    /** Hlasový výstup se připojil k hlasovému modulu telefonu (jazyk jde nastavit až potom). */
    private var ttsInitialized = false

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

    /** Jen oprávnění pro notifikaci záznamu, bez spuštění (automatický záznam se spustí až za jízdy). */
    private val notificationPermissionOnly = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

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
        // Úvodní logo jen při spuštění, ne po otočení obrazovky
        if (savedInstanceState == null) showStartLogo()
        adaptToScreen(resources.configuration)
        onBackPressedDispatcher.addCallback(this, stopNavigationOnBack)

        mapCtl = MapController(this, b.mapView, ::onMapLongPress)
        // Kdo si mapu posunul sám, tomu ji poloha nepřetahuje
        mapCtl.onUserMove = { runOnUiThread { cancelWaitingForFix() } }
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
        b.btnNoMapsDownload.setOnClickListener { openDownloads() }
        b.btnLocation.setOnClickListener {
            // Za navigace po prohlížení mapy zpět k jezdci
            if (mapCtl.isFreeLook) return@setOnClickListener mapCtl.resumeFollow()
            val loc = lastLocation
            if (loc != null && isFresh(loc) && loc.accuracy <= PRECISE_ACCURACY) centerOnFix(loc)
            else {
                startWaitingForFix()
                requestLocation()
            }
        }
        b.btnNavigate.setOnClickListener { startNavigation() }
        b.btnStopNav.setOnClickListener { stopNavigation() }
        b.btnNavMenu.setOnClickListener { showMenu() }
        fabBackground = b.btnRecord.backgroundTintList
        b.btnRecord.setOnClickListener { showRecordingMenu() }
        // Výška horního pruhu s pokynem i spodního panelu se mění (pruhy, zalomený text) a po otočení
        // i rozměr mapy a poloha tlačítek – jezdec drží střed volné mapy
        listOf(b.navTop, b.navPanel, b.mapView, b.fabs).forEach { panel ->
            panel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                if (b.navPanel.visibility == View.VISIBLE) updateNavPivot()
            }
        }
        b.btnUpdateDownload.setOnClickListener { UpdateCheck.openDownload(this) }
        b.btnUpdateDismiss.setOnClickListener {
            UpdateCheck.availableUpdate(this)?.let { UpdateCheck.dismiss(this, it) }
            showUpdateCard()
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInitialized = true
                // Jazyk navigace a hlas zvolený v nastavení; bez hlasových dat se mlčí
                ttsReady = tts?.let(NavVoice::apply) == true
            }
        }

        b.recChip.setOnClickListener { confirmStopRecording() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Recording.state.collect(::onRecordingState)
            }
        }
        // Noční mapa: kontrola po minutě stačí, západ slunce se nepřehoupne náhle
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    applyNightMap()
                    delay(60_000)
                }
            }
        }
        RecordingService.recoverUnfinished()?.let { TrackLibrary.lastRecorded = it }

        askForBike = savedInstanceState == null && bikePosition() != null
        // Po spuštění mapu vycentrujeme na polohu, jen ne po otočení obrazovky
        if (savedInstanceState == null) startWaitingForFix()
        // Nová verze na GitHubu – nejvýš jednou za 12 h, bez internetu se nic neukáže
        lifecycleScope.launch {
            UpdateCheck.check(this@MainActivity)
            showUpdateCard()
        }
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
        // Volba z Nastavení – ikony bodů zájmu na mapě
        mapCtl.setPoiLayers(Settings.poiLayers)
        showRadarsOnMap()
        Downloads.refresh(this)
        reloadMapsIfChanged()
        applyRoadTypeDefaults()
        TrackLibrary.lastRecorded?.let {
            TrackLibrary.lastRecorded = null
            Toast.makeText(this, getString(R.string.recording_saved, it.nameWithoutExtension), Toast.LENGTH_LONG).show()
        }
        // Po návratu z Nastavení, kde se mohla aktualizace právě najít
        showUpdateCard()
        // …nebo změnit hlas navigace, případně doinstalovat hlasová data
        if (ttsInitialized) tts?.let { ttsReady = NavVoice.apply(it) }
        // Rejstřík jmen pro rychlé hledání – po stažení nebo aktualizaci mapy se na pozadí postaví znovu
        lifecycleScope.launch { SearchIndex.ensureAll() }
    }

    /**
     * Úvodní logo zvoleného výrobce (nebo Serpentiny) přes celou šířku displeje na [START_LOGO_MS].
     * Systémová úvodní obrazovka umí jen malou ikonu uprostřed, tohle ji vystřídá; mapa se mezitím
     * načítá pod ním. Logo a barvu pozadí bere ze stylu úvodní obrazovky výrobce.
     */
    private fun showStartLogo() {
        val attrs = intArrayOf(
            androidx.core.splashscreen.R.attr.windowSplashScreenAnimatedIcon,
            androidx.core.splashscreen.R.attr.windowSplashScreenIconBackgroundColor,
        )
        val a = obtainStyledAttributes(Settings.splashTheme, attrs)
        val logo = a.getDrawable(0) ?: ContextCompat.getDrawable(this, R.drawable.ic_launcher_foreground)
        val background = a.getColor(1, ContextCompat.getColor(this, R.color.launcher_bg))
        a.recycle()
        b.startLogo.setBackgroundColor(background)
        // Ikona má kolem loga průhledný okraj pro kulaté a čtvercové ikony – ořízneme ho,
        // ať logo vyplní šířku displeje
        logo?.let { b.startLogoImage.setImageBitmap(trimTransparent(it.toBitmap(START_LOGO_PX, START_LOGO_PX))) }
        val margin = (START_LOGO_MARGIN_DP * resources.displayMetrics.density).toInt()
        b.startLogoImage.setPadding(margin, margin, margin, margin)
        b.startLogo.visibility = View.VISIBLE
        // Pojistka, kdyby okno fokus hned nedostalo (třeba pod systémovým dialogem)
        b.startLogo.doOnPreDraw { b.startLogo.postDelayed(::startLogoCountdown, START_LOGO_FALLBACK_MS) }
    }

    /**
     * Systémovou úvodní obrazovku na Androidu 12+ kreslí spouštěcí aktivita výrobce a zmizí, až se
     * okno MainActivity ukáže – to pozná fokus okna. Teprve od té chvíle je logo vidět.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && b.startLogo.visibility == View.VISIBLE) startLogoCountdown()
    }

    private var logoCountdown = false

    /** Odpočet od chvíle, kdy je logo opravdu vidět; pak logo plynule zmizí. */
    private fun startLogoCountdown() {
        if (logoCountdown) return
        logoCountdown = true
        lifecycleScope.launch {
            delay(START_LOGO_MS)
            b.startLogo.animate().alpha(0f).setDuration(400).withEndAction {
                b.startLogo.visibility = View.GONE
            }.start()
        }
    }

    /** Výřez bitmapy bez průhledného okraje. */
    private fun trimTransparent(src: Bitmap): Bitmap {
        val w = src.width
        val px = IntArray(w * src.height).also { src.getPixels(it, 0, w, 0, 0, w, src.height) }
        var left = w; var top = src.height; var right = -1; var bottom = -1
        for (y in 0 until src.height) for (x in 0 until w) {
            if (Color.alpha(px[y * w + x]) > 24) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return src
        return Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
    }

    /** Mapa v nočních barvách podle volby v nastavení; bez polohy GPS se slunce počítá pro střed mapy. */
    private fun applyNightMap() {
        val night = when (Settings.nightMap) {
            NightMap.AUTO -> {
                val p = lastLocation?.let { LatLong(it.latitude, it.longitude) } ?: mapCtl.center
                Sun.isNight(p.latitude, p.longitude)
            }
            NightMap.APP_THEME ->
                resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            NightMap.ALWAYS -> true
            NightMap.NEVER -> false
        }
        mapCtl.setNight(night)
    }

    /**
     * Jezdec při navigaci do spodní třetiny viditelné mapy mezi horním pruhem s pokynem
     * a spodním panelem (doprostřed té třetiny) – před motorkou je vidět víc silnice.
     */
    private fun updateNavPivot() {
        val w = b.mapView.width.takeIf { it > 0 } ?: return
        val h = b.mapView.height.takeIf { it > 0 } ?: return
        val loc = IntArray(2)
        b.mapView.getLocationInWindow(loc)
        val mapLeft = loc[0]
        val mapTop = loc[1]
        b.navTop.getLocationInWindow(loc)
        val barTop = loc[1] - mapTop
        val barBottom = barTop + b.navTop.height
        val barRight = loc[0] - mapLeft + b.navTop.width
        b.navPanel.getLocationInWindow(loc)
        val panelTop = loc[1] - mapTop
        val panelBottom = panelTop + b.navPanel.height
        val panelRight = loc[0] - mapLeft + b.navPanel.width
        val fabsLeft = IntArray(2).also(b.fabs::getLocationInWindow)[0] - mapLeft
        // Na šířku a na tabletu leží horní pruh i spodní panel vlevo (adaptToScreen) a nad sebou
        // mezi nimi skoro nic nezbude – jezdec pak jede ve volném pruhu vpravo od nich, před tlačítky.
        // Bez toho by zůstal uprostřed šířky obrazovky, tedy pod panely.
        val left = maxOf(barRight, panelRight)
        val beside = b.navPanel.width < w * 0.7 && fabsLeft - left >= MIN_RIDER_STRIP_DP * resources.displayMetrics.density
        val (riderX, top, bottom) = if (beside) Triple((left + fabsLeft) / 2f, barTop, panelBottom)
            else Triple(w / 2f, barBottom, panelTop)
        if (bottom <= top) return
        val riderY = top + (bottom - top) * NAV_RIDER_POSITION
        mapCtl.setNavPivot((riderX - w / 2f) / (w / 2f), (riderY - h / 2f) / (h / 2f))
    }

    /** Upozornění na novou verzi – ne během navigace a ne u verze, kterou uživatel zavřel. */
    private fun showUpdateCard() {
        val newer = UpdateCheck.availableUpdate(this)
        val show = newer != null && !UpdateCheck.isDismissed(this, newer) && navigator == null
        if (show) b.updateText.text = getString(R.string.update_banner, newer)
        b.updateCard.visibility = if (show) View.VISIBLE else View.GONE
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
        rideSensors?.stop()
        b.root.removeCallbacks(stopWaitingForFix)
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
        // Menu + panel s manévrem; na tabletu stejně široké jako spodní panel a menu vedle něj
        b.navTop.updateLayoutParams<ViewGroup.LayoutParams> {
            width = if (wide) ((420 + 84) * density).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    private fun hideCoveredZoomButtons() {
        val loc = IntArray(2)
        b.btnZoomOut.getLocationOnScreen(loc)
        val zoomBottom = loc[1] + b.btnZoomOut.height
        fun coveredAbove(bottom: Int): Boolean =
            listOf(b.routePanel, b.navPanel).any { panel ->
                panel.visibility == View.VISIBLE && run { panel.getLocationOnScreen(loc); loc[1] < bottom }
            }
        // FAB se musí schovávat přes show()/hide(), přímá změna viditelnosti ho nevykreslí zpátky
        fun FloatingActionButton.showIf(visible: Boolean) {
            if (!visible && isOrWillBeShown) hide() else if (visible && isOrWillBeHidden) show()
        }
        val zoomCovered = coveredAbove(zoomBottom)
        b.btnZoomIn.showIf(!zoomCovered)
        b.btnZoomOut.showIf(!zoomCovered)
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

    /**
     * Záznam jízdy: ruční spuštění (za běhu zastavení) a pod ním zaškrtávací volby
     * automatického spuštění po rozjezdu a zastavení po navigaci.
     */
    private fun showRecordingMenu() {
        val manual = if (Recording.state.value.active) GloveMenu.Item(getString(R.string.recording_stop)) { confirmStopRecording() }
            else GloveMenu.Item(getString(R.string.recording_start_manual), highlighted = true) { requestRecording() }
        GloveMenu.show(this, getString(R.string.recording_title), listOf(
            manual,
            // Buď záznam sám po rozjezdu, nebo dotaz – obojí naráz nedává smysl
            GloveMenu.Item.checkbox(getString(R.string.recording_auto_start), Settings.autoStartRecording, group = "start") {
                Settings.autoStartRecording = it
                if (it) requestRecordingPermissions()
            },
            GloveMenu.Item.checkbox(getString(R.string.recording_ask_on_ride), Settings.askToRecord, group = "start") {
                Settings.askToRecord = it
                // Znovu zapnutý dotaz platí hned, i když jezdec dřív zvolil „Nezaznamenávat“
                if (it) askRecordingAllowed = true
            },
            GloveMenu.Item.checkbox(getString(R.string.recording_stop_after_nav), Settings.stopRecordingAfterNavigation) {
                Settings.stopRecordingAfterNavigation = it
            },
        ))
    }

    private fun showMenu() {
        fun item(text: Int, action: () -> Unit) = GloveMenu.Item(getString(text), action = action)
        val recording = item(R.string.recording_title) { showRecordingMenu() }
        if (navigator != null || recalculating) {
            // Za navigace jen to, co jízdu nepřeruší; nový cíl se rovnou naviguje
            GloveMenu.show(this, null, listOf(
                item(R.string.search_title) { openSearch() },
                recording,
                item(R.string.lean_title) { startActivity(Intent(this, LeanActivity::class.java)) },
                item(R.string.offline_data) { openDownloads() },
                item(R.string.settings_title) { startActivity(Intent(this, SettingsActivity::class.java)) },
                item(R.string.stop_navigation_full) { stopNavigation() },
            ))
            return
        }
        val items = mutableListOf(
            item(R.string.search_title) { openSearch() },
            item(R.string.round_trip) { askRoundTripLength() },
            recording,
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
        cancelWaitingForFix()
        // Až po rozvržení (těsně před vykreslením), ať známe skutečnou výšku panelu
        // i s právě přidanými tlačítky variant
        b.root.requestLayout()
        b.root.doOnPreDraw {
            val w = b.mapView.width
            val h = b.mapView.height
            // Spodní hrana vyhledávací lišty (nebo navigačního panelu); sloupec tlačítek
            // vpravo pod ní mapu nezakrývá, takže se do volné plochy nepočítá
            val bar = if (b.navTop.visibility == View.VISIBLE) b.navTop else b.searchBar
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
        val heading = headingFor(location, pos)
        autoRecording(location)
        // Za navigace ikonu posouvá sledování trasy – na stejné místo jako kameru
        navigator?.let { onNavigationFix(it, location, pos); return }
        mapCtl.setLocation(pos, heading)

        // Na starou uloženou ani nepřesnou polohu mapu necentrujeme – poskakovala by
        if (waitingForFix && isFresh(location)) {
            if (location.accuracy <= PRECISE_ACCURACY) centerOnFix(location)
            else b.gpsChip.text = getString(R.string.waiting_for_precise_location_accuracy, location.accuracy.roundToInt())
        } else if (!mapCtl.tracking && location.hasSpeed() && location.speed * 3.6f >= Recording.AUTO_START_KMH &&
            mapCtl.sinceUserMoveMs > TRACK_RESUME_MS && b.routePanel.visibility != View.VISIBLE &&
            location.accuracy <= PRECISE_ACCURACY
        ) {
            // Za jízdy se mapa po chvíli bez dotyku sama vrátí k jezdci a dál ho sleduje
            centerOnFix(location)
        }
        // Rozhodujeme až podle dost přesné polohy změřené po spuštění
        // Dotaz na motorku až po úvodním logu, jinak by se ukázal přes něj
        if (askForBike && b.startLogo.visibility != View.VISIBLE &&
            location.elapsedRealtimeNanos >= launchedAtNanos && location.accuracy <= BIKE_FIX_ACCURACY
        ) {
            askForBike = false
            offerNavigationToBike(location, pos)
        }
    }

    // ---------------------------------------------------------------- vycentrování na polohu

    private fun isFresh(location: Location) = System.currentTimeMillis() - location.time < 2 * 60_000

    /** Počká na přesnou polohu; když nepřijde do [PRECISE_TIMEOUT_MS], vezme nejlepší čerstvou. */
    private fun startWaitingForFix() {
        waitingForFix = true
        b.gpsChip.setText(R.string.waiting_for_precise_location)
        b.gpsWaiting.visibility = View.VISIBLE
        b.root.removeCallbacks(stopWaitingForFix)
        b.root.postDelayed(stopWaitingForFix, PRECISE_TIMEOUT_MS)
    }

    private fun cancelWaitingForFix() {
        if (!waitingForFix) return
        waitingForFix = false
        b.gpsWaiting.visibility = View.GONE
        b.root.removeCallbacks(stopWaitingForFix)
    }

    /** Poloha doprostřed mapy; s otevřeným panelem trasy doprostřed volné plochy nad ním. */
    private fun centerOnFix(location: Location) {
        cancelWaitingForFix()
        if (b.routePanel.visibility != View.VISIBLE) mapCtl.resetViewCenter()
        mapCtl.moveTo(LatLong(location.latitude, location.longitude), zoom = if (mapCtl.zoomLevel < 12) 14 else null)
        // Dál mapa drží polohu uprostřed, dokud s ní jezdec sám nepohne
        mapCtl.startTracking()
    }

    // ---------------------------------------------------------------- zaparkovaná motorka

    private fun bikePosition(): LatLong? {
        if (!prefs.contains("bike_lat")) return null
        return LatLong(
            java.lang.Double.longBitsToDouble(prefs.getLong("bike_lat", 0)),
            java.lang.Double.longBitsToDouble(prefs.getLong("bike_lon", 0)),
        )
    }

    /** Po ukončení navigace si pamatujeme, kde motorka zůstala. */
    private fun saveBikePosition() {
        val loc = lastLocation ?: return
        prefs.edit {
            putLong("bike_lat", java.lang.Double.doubleToRawLongBits(loc.latitude))
            putLong("bike_lon", java.lang.Double.doubleToRawLongBits(loc.longitude))
        }
    }

    /** Ikona chodce místo motorky; hned překreslí polohu na mapě. */
    private fun setWalking(on: Boolean) {
        if (mapCtl.walking == on) return
        mapCtl.walking = on
        lastLocation?.let { mapCtl.setLocation(LatLong(it.latitude, it.longitude), null) }
    }

    private fun offerNavigationToBike(location: Location, here: LatLong) {
        val bike = bikePosition() ?: return
        val distance = here.sphericalDistance(bike)
        // U motorky, nebo když GPS ještě neví přesně, kde jsme, se neptáme
        if (distance <= BIKE_NEAR_METERS || distance <= location.accuracy) return
        GloveMenu.show(this, getString(R.string.bike_title) + " " + getString(R.string.bike_message, formatDistance(distance)), listOf(
            GloveMenu.Item(getString(R.string.bike_navigate)) {
                onDestinationChosen(getString(R.string.bike_destination), bike, thenNavigate = true)
            },
            GloveMenu.Item(getString(R.string.bike_no)) {},
        ))
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
        b.btnNewLoop.setOnClickListener { roundTripKm?.let { generateRoundTrip(it) } }
        b.btnReverse.setOnClickListener {
            val s = start
            start = end
            end = s
            vias.reverse()
            if (roundTripKm == null) destinationName = null
            onWaypointsChanged()
        }
    }

    /** Po změně typu cest v nastavení přednastaví typ trasy a vyhýbání. */
    private fun applyRoadTypeDefaults() {
        val road = Settings.roadType
        val applied = prefs.getInt("bike_applied", -1)
        // Dřívější naked (id 2) je teď stejný typ cest jako sportovní – volby se nepřepisují
        if (applied == road.id || RoadType.byId(applied) == road) {
            if (applied != road.id) prefs.edit { putInt("bike_applied", road.id) }
            return
        }
        prefs.edit { putInt("bike_applied", road.id) }
        // Při prvním spuštění necháme volby, které si jezdec už nastavil
        if (applied == -1) return
        selectRouteType(road.routeType)
        b.chipMotorways.isChecked = road.avoidMotorways
        b.chipUnpaved.isChecked = road.avoidUnpaved
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
            // Bez zarovnání podle účaří: tlačítko se zalomeným textem by jinak sjelo níž.
            // Stejná výška celé řady, i když se text zalomí jen v jednom tlačítku.
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            rowItems.forEachIndexed { i, v ->
                row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
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

    private fun onDestinationChosen(name: String, position: LatLong, thenNavigate: Boolean = false) {
        end = position
        destinationName = name
        vias.clear()
        viasFromTrack = false
        roundTripKm = null
        // Cíl vybraný za jízdy z menu: navigace pokračuje rovnou k novému cíli
        val here = lastLocation
        if ((navigator != null || recalculating) && here != null) {
            b.destinationName.text = name
            setWalking(position == bikePosition())
            rememberDestination()
            b.navInstruction.setText(R.string.recalculating)
            recalculateFrom(LatLong(here.latitude, here.longitude))
            return
        }
        // Trasa z aktuální polohy, pokud ji známe
        lastLocation?.let { start = LatLong(it.latitude, it.longitude) }
        onWaypointsChanged(zoomWhenDone = !thenNavigate, onDone = { if (thenNavigate) startNavigation() })
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

    private fun onWaypointsChanged(zoomWhenDone: Boolean = false, onDone: (Route) -> Unit = {}) {
        // Kdo plánuje trasu, chce ji vidět – mapa už na polohu sama neskočí
        cancelWaitingForFix()
        refreshPins()
        b.destinationName.text = destinationName
        b.destinationName.visibility = if (destinationName != null) View.VISIBLE else View.GONE
        b.btnNewLoop.visibility = if (roundTripKm != null) View.VISIBLE else View.GONE
        // Přiblížit tak, aby byly vidět všechny varianty trasy
        calculateRoute(onDone = {
            if (zoomWhenDone) zoomToPoints(routes.flatMap(Route::points))
            onDone(it)
        })
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
        val km = roundTripKm
        if (km != null && vias.isEmpty() && s == e) {
            // Změna voleb zrušila ještě nedokončené hledání okruhu; bez průjezdních bodů by vyšla trasa start = cíl
            generateRoundTrip(km, from = s)
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
        // Okruh začíná i končí na stejném místě – šipky ukážou, kterým směrem vyjet
        mapCtl.showDirection(r?.points?.takeIf { roundTripKm != null })
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

    /** Vygeneruje okruh dané délky z [from], jinak z aktuální polohy (bez GPS ze středu mapy). */
    private fun generateRoundTrip(km: Int, from: LatLong? = null) {
        val from = from ?: lastLocation?.let { LatLong(it.latitude, it.longitude) } ?: run {
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

    /**
     * Při zapnutí automatického záznamu si oprávnění vyžádáme hned – za jízdy, kdy se záznam
     * spustí sám, by systémový dotaz překážel. Bez notifikací záznam funguje taky.
     */
    private fun requestRecordingPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestLocation()
        } else if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionOnly.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Automatika se po zastavení záznamu znovu chystá až po zastavení motorky (ne hned po ručním stopu). */
    private var autoStartArmed = true

    /** Na záznam jízdy bez navigace se ptáme jen jednou za spuštění aplikace – po „Ne“ už ne. */
    private var askRecordingAllowed = true
    /** Hned po navigaci se na záznam neptáme (přes souhrn jízdy) – až po zastavení a novém rozjezdu. */
    private var askRecordingPaused = false
    private var askRecordingDialog: android.app.Dialog? = null

    /**
     * Automatický start záznamu po rozjezdu a znovu-nachystání po zastavení motorky. Bez automatiky
     * se při jízdě bez navigace zeptáme, jestli jízdu zaznamenat.
     */
    private fun autoRecording(location: Location) {
        if (!location.hasSpeed()) return
        val kmh = location.speed * 3.6f
        if (Recording.state.value.active) {
            autoStartArmed = false
            askRecordingDialog?.dismiss()
            return
        }
        if (kmh < Recording.AUTO_REARM_KMH) { autoStartArmed = true; askRecordingPaused = false }
        if (kmh < Recording.AUTO_START_KMH ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return
        if (Settings.autoStartRecording) {
            if (!autoStartArmed) return
            autoStartArmed = false
            startRecording()
            Toast.makeText(this, R.string.recording_auto_started, Toast.LENGTH_SHORT).show()
        } else if (navigator == null && Settings.askToRecord && askRecordingAllowed && !askRecordingPaused && askRecordingDialog == null &&
            // Ne přes úvodní logo – zeptáme se až s viditelnou mapou
            b.startLogo.visibility != View.VISIBLE &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        ) {
            askRecording()
        }
    }

    /** Jedeme bez navigace a bez záznamu – nabídneme spuštění záznamu, případně i automatiku. */
    private fun askRecording() {
        // Za jízdy v rukavicích: velké položky přes celou šířku, ne malá tlačítka dialogu
        askRecordingDialog = GloveMenu.show(this, getString(R.string.recording_ask_message), listOf(
            GloveMenu.Item(getString(R.string.recording_ask_yes)) { requestRecording() },
            GloveMenu.Item(getString(R.string.recording_ask_always)) {
                Settings.autoStartRecording = true
                autoStartArmed = false
                requestRecording()
            },
            GloveMenu.Item(getString(R.string.recording_ask_no)) { askRecordingAllowed = false },
        )).apply {
            // Zavření stažením dolů nebo ťuknutím vedle bereme jako „Ne“ – jinak by se s další polohou ptal znovu
            setOnCancelListener { askRecordingAllowed = false }
            setOnDismissListener { askRecordingDialog = null }
        }
    }

    private fun confirmStopRecording() {
        // Za jízdy v rukavicích – velké položky přes celou šířku
        GloveMenu.show(this, getString(R.string.recording_stop_ask, formatDistance(Recording.state.value.distanceMeters)), listOf(
            GloveMenu.Item(getString(R.string.recording_stop)) { Recording.stop(this) },
            GloveMenu.Item(getString(R.string.recording_continue)) {},
        ))
    }

    private var drawnRecordedPoints = 0
    /** Výchozí barva tlačítka záznamu, když neběží (podle světlého/tmavého vzhledu). */
    private var fabBackground: ColorStateList? = null

    private fun onRecordingState(state: Recording.State) {
        b.recChip.visibility = if (state.active) View.VISIBLE else View.GONE
        // Během záznamu je tlačítko záznamu plně červené
        b.btnRecord.apply {
            val red = 0xFFC62828.toInt()
            backgroundTintList = if (state.active) ColorStateList.valueOf(red) else fabBackground
            imageTintList = ColorStateList.valueOf(if (state.active) Color.WHITE else red)
            // Čtečka obrazovky barvu nevidí – stav záznamu musí slyšet
            contentDescription = getString(if (state.active) R.string.recording_running else R.string.recording_title)
        }
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

    /** Cíl navigace do historie v „Kam jedeme?“ – ne okruh ani cesta pěšky k motorce. */
    private fun rememberDestination() {
        val e = end ?: return
        val name = destinationName ?: return
        if (roundTripKm == null && !mapCtl.walking) DestinationHistory.add(this, name, e)
    }

    private fun startNavigation() {
        // Kdo navigaci spustí, je u motorky – navigaci k ní už po ukončení nenabízíme
        askForBike = false
        val loc = lastLocation
        val r = route
        if (loc == null || r == null) {
            Toast.makeText(this, R.string.need_location, Toast.LENGTH_LONG).show()
            return
        }
        val here = LatLong(loc.latitude, loc.longitude)
        cancelWaitingForFix()
        setNavigationUi(true)
        // K zaparkované motorce se jde pěšky
        setWalking(end != null && end == bikePosition())
        // Náklon a souhrn jízdy se měří při každé navigaci na motorce
        if (!mapCtl.walking) startRide()
        rememberDestination()
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
        // Po navigaci pěšky k motorce neukládáme – chodec může skončit kdekoli cestou
        if (!mapCtl.walking) saveBikePosition()
        setWalking(false)
        navigator = null
        recalculating = false
        tts?.stop()
        camerasJob?.cancel()
        camerasFor = null
        routeCameras = emptyList()
        warnedCameras.clear()
        slowDownCameras.clear()
        b.radarCard.visibility = View.GONE
        // Po navigaci zpět na běžnou mapu s vyhledáváním – trasa i panel s jejím výběrem zmizí
        clearRoute()
        setNavigationUi(false)
        mapCtl.stopFollowing(lastLocation?.let { LatLong(it.latitude, it.longitude) })
        // Po navigaci mapa dál sleduje jezdce
        if (lastLocation != null) mapCtl.startTracking()
        // Záznam jízdy skončí s navigací, pokud si to jezdec zaškrtl (uloží se do Tras a jízd)
        if (Settings.stopRecordingAfterNavigation && Recording.state.value.active) Recording.stop(this)
        askRecordingPaused = true
        finishRide()?.let(::offerRideSummary)
    }

    // ---------------------------------------------------------------- náklon a souhrn jízdy

    private var rideMeter: LeanMeter? = null
    private var rideSensors: LeanSensors? = null
    private var rideTracker: RideTracker? = null

    private fun startRide() {
        if (rideMeter != null) return
        val meter = LeanMeter(LeanSensors.calibration(this))
        rideMeter = meter
        rideSensors = LeanSensors(this, meter, ::displayRotation).also { it.start() }
        rideTracker = RideTracker(System.currentTimeMillis())
    }

    private fun onRideFix(location: Location, pos: LatLong) {
        val meter = rideMeter ?: return
        if (location.hasSpeed()) meter.onSpeed(LeanMeter.gpsSpeed(location), location.elapsedRealtimeNanos)
        if (location.hasBearing()) meter.onCourse(location.bearing.toDouble(), location.elapsedRealtimeNanos)
        rideTracker?.onFix(pos, location.accuracy, location.speed, location.time)
    }

    /** Ukončí měření a vrátí souhrn jízdy (trvale se jízda ukládá jen se záznamem jízdy). */
    private fun finishRide(): RideSummary? {
        val meter = rideMeter ?: return null
        rideSensors?.stop()
        val summary = rideTracker?.summary(meter.stats, System.currentTimeMillis())
        rideMeter = null
        rideSensors = null
        rideTracker = null
        return summary?.takeIf { it.distanceMeters >= MIN_RIDE_SUMMARY_METERS }
    }

    private fun offerRideSummary(r: RideSummary) {
        GloveMenu.show(this, getString(R.string.ride_done_title) + ". " +
            getString(R.string.ride_done_message, formatDistance(r.distanceMeters), formatDuration(r.totalSeconds)), listOf(
            GloveMenu.Item(getString(R.string.ride_show)) { showRideSummary(r) },
            GloveMenu.Item(getString(R.string.ride_no)) {},
        ))
    }

    private fun showRideSummary(r: RideSummary) {
        fun kmh(mps: Double) = "${(mps * 3.6).roundToInt()} km/h"
        fun g(a: Double) = "%.2f g".format(a / LeanMeter.G)
        val l = r.lean
        val rows = listOf(
            R.string.ride_distance to formatDistance(r.distanceMeters),
            R.string.ride_time to formatDuration(r.totalSeconds) + " (" + getString(R.string.ride_moving, formatDuration(r.movingSeconds)) + ")",
            // Skok polohy (ztráta signálu) by průměr nafoukl nad maximum
            R.string.ride_avg_speed to kmh(if (l.maxSpeed > 0) minOf(r.avgSpeed, l.maxSpeed) else r.avgSpeed),
            R.string.lean_max_speed to kmh(l.maxSpeed),
            R.string.lean_max_left to "${l.maxLeft.roundToInt()}°",
            R.string.lean_max_right to "${l.maxRight.roundToInt()}°",
            R.string.lean_max_accel to g(l.maxAccel),
            R.string.lean_max_brake to g(l.maxBrake),
        )
        GloveMenu.show(this, getString(R.string.ride_summary_title) + "\n\n" +
            rows.joinToString("\n") { (label, value) -> "${getString(label)}: $value" }, listOf(
            GloveMenu.Item(getString(R.string.close)) {},
        ))
    }

    @Suppress("DEPRECATION")
    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_0
        else windowManager.defaultDisplay.rotation

    private fun setNavigationUi(on: Boolean) {
        b.searchBar.visibility = if (on) View.GONE else View.VISIBLE
        b.routePanel.visibility = if (!on && end != null) View.VISIBLE else View.GONE
        b.navTop.visibility = if (on) View.VISIBLE else View.GONE
        b.navPanel.visibility = if (on) View.VISIBLE else View.GONE
        mapCtl.setRouteProgress(on)
        if (on) b.updateCard.visibility = View.GONE else showUpdateCard()
        stopNavigationOnBack.isEnabled = on
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            b.navArrow.text = ""
            b.navArrow.setCompoundDrawables(null, null, null, null)
            b.navDistance.text = ""
            b.navInstruction.setText(R.string.calculating)
            b.navEta.text = ""
            b.navRemaining.text = ""
            b.navSpeed.setText(R.string.speed_unknown)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Úsek trasy, na kterém leží bod ve vzdálenosti [meters] od startu. */
    private fun segmentAt(r: Route, meters: Double): Int =
        (r.distances.indexOfFirst { it > meters } - 1).coerceIn(0, r.points.size - 2)

    private fun onNavigationFix(nav: Navigator, location: Location, pos: LatLong) {
        val state = nav.update(pos, location.accuracy, location.speed)
        onRideFix(location, pos)

        // Mapa sleduje jezdce; natočení a naklonění podle režimu zobrazení.
        // Na trase podle směru silnice, mimo ni podle kurzu z GPS (jen v pohybu, jinak je nespolehlivý).
        val gpsHeading = location.bearing.takeIf { location.hasBearing() && location.speed > 1.5f }
        // Kousek od trasy (jezdec odbočil) už ikonu ani mapu po staré trase netáhneme
        val onRoute = state.nearRoute && !state.offRoute
        val heading = if (onRoute) state.routeBearing else gpsHeading
        // Ikona k další poloze z GPS plynule dojíždí, takže by o sekundu zaostávala – míří proto
        // na místo, kam jezdec po trase za tu dobu dojede (poloha z GPS je navíc o chvilku stará)
        val r = nav.route
        val along = r.distances[state.segment] + r.points[state.segment].sphericalDistance(state.snapped)
        val ahead = (location.speed * PREDICT_SECONDS).toDouble().coerceIn(0.0, PREDICT_MAX_METERS)
        val shown = r.pointAt(along + ahead)
        mapCtl.follow(shown.takeIf { onRoute } ?: pos, heading, location.speed)
        // Trasa před jezdcem tmavě a široce, za ním světle a úzce – mimo trasu dělení nedává smysl
        if (onRoute) mapCtl.showProgress(r.points, segmentAt(r, along + ahead), shown)

        showLanes(state.next?.takeIf { state.toNext < LANES_DISTANCE && !state.offRoute })
        showManeuverArrow(state.next)
        b.navDistance.text = formatDistance(state.toNext)
        b.navInstruction.text = state.next?.let { Navigator.instruction(it).replaceFirstChar { c -> c.uppercase() } }
        val eta = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(System.currentTimeMillis() + state.remainingSeconds.toLong() * 1000))
        b.navEta.text = getString(R.string.nav_eta, eta)
        b.navRemaining.text = "${formatDistance(state.remainingMeters)} · ${formatDuration(state.remainingSeconds)}"
        b.navSpeed.text = if (location.hasSpeed()) (location.speed * 3.6f).roundToInt().toString() else getString(R.string.speed_unknown)
        showSpeedCamera(nav.route, state, location)

        when {
            state.arrived -> {
                showManeuverArrow(Maneuver(0, 0.0, "END", 0))
                b.navDistance.text = ""
                b.navInstruction.text = getString(R.string.arrived)
            }
            state.offRoute && !recalculating -> {
                b.navInstruction.setText(R.string.recalculating)
                speak(getString(R.string.recalculating).removeSuffix("…"))
                // Jezdec odbočil úmyslně – nová trasa ho nemá vracet na místo, kde z trasy sjel
                recalculateFrom(pos, passedSegment = state.segment, avoid = state.snapped)
            }
        }
    }

    /**
     * Symbol dalšího manévru. Kruhový objezd se kreslí jako kruh se šipkou ve směru objíždění
     * (u nás proti směru hodinových ručiček) a číslem výjezdu uprostřed, ostatní jako znak šipky.
     */
    private fun showManeuverArrow(m: Maneuver?) {
        val roundabout = m?.command == "RNDB" || m?.command == "RNLB"
        if (roundabout) {
            val px = (56 * resources.displayMetrics.density).toInt()
            val icon = RoundaboutIcon(m!!.exit, clockwise = m.command == "RNLB", color = b.navArrow.currentTextColor)
                .apply { setBounds(0, 0, px, px) }
            b.navArrow.text = ""
            b.navArrow.setCompoundDrawables(null, icon, null, null)
        } else {
            b.navArrow.setCompoundDrawables(null, null, null, null)
            b.navArrow.text = Navigator.arrow(m)
        }
    }

    // ---------------------------------------------------------------- radary

    /** Radary na navigované trase (přiřazují se na pozadí po každém výpočtu trasy). */
    private var routeCameras: List<CameraOnRoute> = emptyList()
    private var camerasFor: Route? = null
    private var camerasJob: Job? = null
    /** Radary, na které už jezdec byl upozorněn (a kde už slyšel „zpomalte“). */
    private val warnedCameras = mutableSetOf<LatLong>()
    private val slowDownCameras = mutableSetOf<LatLong>()

    /** Radary na mapě podle volby v bodech zájmu (z dat stažených zemí). */
    private fun showRadarsOnMap() {
        if (!Settings.showRadars) {
            mapCtl.setRadars(emptyList())
            return
        }
        lifecycleScope.launch { mapCtl.setRadars(SpeedCameras.all().map { it.position }) }
    }

    private fun loadCameras(r: Route) {
        camerasJob?.cancel()
        routeCameras = emptyList()
        camerasFor = r
        camerasJob = lifecycleScope.launch {
            val all = SpeedCameras.all()
            routeCameras = withContext(Dispatchers.Default) { SpeedCameras.onRoute(r, all) }
        }
    }

    /**
     * Štítek s nejbližším radarem před jezdcem; hlas upozorní jednou s předstihem
     * a ještě jednou, když jezdec u radaru jede rychleji, než se tam smí.
     */
    private fun showSpeedCamera(r: Route, state: NavState, location: Location) {
        // Radary vypnuté v bodech zájmu – ani štítek, ani hlas
        if (!Settings.showRadars) {
            b.radarCard.visibility = View.GONE
            return
        }
        if (camerasFor !== r) loadCameras(r)
        val along = r.distances[state.segment] + r.points[state.segment].sphericalDistance(state.snapped)
        val fast = location.speed > 20 // ~70 km/h
        val range = if (fast) CAMERA_WARN_FAST else CAMERA_WARN
        val next = routeCameras.takeUnless { state.offRoute }
            ?.firstOrNull { it.along - along in -CAMERA_PASSED..range }
        if (next == null) {
            b.radarCard.visibility = View.GONE
            return
        }
        val cam = next.camera
        val ahead = (next.along - along).coerceAtLeast(0.0)
        val kmh = if (location.hasSpeed()) location.speed * 3.6f else null
        val tooFast = cam.maxspeed != null && kmh != null && kmh > cam.maxspeed + CAMERA_TOLERANCE_KMH
        val label = getString(if (cam.section) R.string.radar_section else R.string.radar)
        b.radarText.text = listOfNotNull(label, cam.maxspeed?.toString(), formatDistance(ahead)).joinToString(" · ")
        b.radarCard.setCardBackgroundColor(if (tooFast) 0xFFC62828.toInt() else 0xFF263238.toInt())
        b.radarCard.visibility = View.VISIBLE
        if (warnedCameras.add(cam.position)) {
            speak(cam.maxspeed?.let { getString(R.string.radar_speak_limit, label, it) } ?: getString(R.string.radar_speak, label), queue = true)
        }
        if (tooFast && ahead < CAMERA_WARN && slowDownCameras.add(cam.position)) {
            speak(getString(R.string.radar_slow_down), queue = true)
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
    private fun recalculateFrom(here: LatLong, passedSegment: Int = -1, avoid: LatLong? = null) {
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
                val r = RoutePlanner.calculate(
                    listOf(here) + vias + e, options(), looseWaypoints = roundTripKm != null, avoidPoint = avoid,
                )
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

    /** @param queue zařadit za právě mluvený pokyn místo jeho přerušení (upozornění na radar) */
    private fun speak(text: String, queue: Boolean = false) {
        if (ttsReady && NavVoice.enabled) tts?.speak(text, if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null, text)
    }

    private companion object {
        /** Odkud před manévrem ukazovat pruhy (m). */
        const val LANES_DISTANCE = 1500.0
        /** Do této vzdálenosti od motorky se na navigaci k ní neptáme (m). */
        const val BIKE_NEAR_METERS = 20.0
        /** Nejhorší přesnost polohy (m), podle které se ještě rozhodujeme o dotazu na motorku. */
        const val BIKE_FIX_ACCURACY = 30f
        /** Přesnost polohy (m), na kterou se mapa vycentruje. */
        const val PRECISE_ACCURACY = 30f
        /** Kde stojí jezdec při navigaci: podíl výšky volné mapy od horního pruhu (5/6 = střed spodní třetiny). */
        const val NAV_RIDER_POSITION = 5f / 6f
        /** Nejužší volný pruh vedle panelů (dp), do kterého má smysl jezdce posadit. */
        const val MIN_RIDER_STRIP_DP = 96
        /** Odkud upozornit na radar (m), nad ~70 km/h dřív. */
        const val CAMERA_WARN = 400.0
        const val CAMERA_WARN_FAST = 700.0
        /** Štítek ještě chvíli po minutí radaru (nepřesnost polohy). */
        const val CAMERA_PASSED = 15.0
        /** O kolik km/h nad povolenou rychlost je štítek červený a zazní „zpomalte“. */
        const val CAMERA_TOLERANCE_KMH = 3
        /** O kolik sekund jízdy po trase ikona míří dopředu (vyrovná dojíždění a stáří polohy z GPS). */
        const val PREDICT_SECONDS = 1.0f
        /** Víc dopředu nikdy – při skoku rychlosti z GPS by ikona utekla. */
        const val PREDICT_MAX_METERS = 40.0
        /** Jak dlouho zůstane úvodní logo (ms). */
        const val START_LOGO_MS = 5_000L
        /** Kdyby nepřišel konec systémové úvodní obrazovky, odpočet začne tolik po prvním vykreslení. */
        const val START_LOGO_FALLBACK_MS = 3_000L
        /** Rozlišení, ve kterém se logo vykreslí a ořízne (loga výrobců mají 432 px). */
        const val START_LOGO_PX = 1080
        /** Okraj kolem úvodního loga. */
        const val START_LOGO_MARGIN_DP = 24
        /** Po kratší jízdě (navigace hned zrušená) se souhrn nenabízí (m). */
        const val MIN_RIDE_SUMMARY_METERS = 300.0
        /** Jak dlouho nejvýš čekat na přesnou polohu, než se vezme nejlepší dostupná. */
        const val PRECISE_TIMEOUT_MS = 20_000L
        /** Za jízdy bez navigace se mapa vrátí k jezdci po této době od posledního dotyku. */
        const val TRACK_RESUME_MS = 15_000L
    }
}
