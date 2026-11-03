package cz.motonavigace

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.view.Choreographer
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import org.mapsforge.core.model.LatLong
import org.oscim.android.MapView
import org.oscim.android.canvas.AndroidGraphics
import org.oscim.backend.CanvasAdapter
import org.oscim.core.BoundingBox
import org.oscim.core.GeoPoint
import org.oscim.core.MapPosition
import org.oscim.event.Gesture
import org.oscim.event.GestureListener
import org.oscim.event.MotionEvent
import org.oscim.layers.Layer
import org.oscim.layers.PathLayer
import org.oscim.layers.marker.ItemizedLayer
import org.oscim.layers.marker.MarkerInterface
import org.oscim.layers.marker.MarkerItem
import org.oscim.layers.marker.MarkerSymbol
import org.oscim.layers.tile.buildings.BuildingLayer
import org.oscim.layers.tile.vector.VectorTileLayer
import org.oscim.layers.tile.vector.labeling.LabelLayer
import org.oscim.renderer.GLViewport
import org.oscim.scalebar.DefaultMapScaleBar
import org.oscim.scalebar.MapScaleBarLayer
import org.oscim.theme.IRenderTheme
import org.oscim.theme.ThemeCallback
import org.oscim.theme.ThemeLoader
import org.oscim.theme.XmlRenderThemeMenuCallback
import org.oscim.theme.XmlRenderThemeStyleMenu
import org.oscim.theme.internal.VtmThemes
import org.oscim.theme.styles.CircleStyle
import org.oscim.theme.styles.LineStyle
import org.oscim.theme.styles.RenderStyle
import org.oscim.theme.styles.TextStyle
import org.oscim.tiling.source.mapfile.MapFileTileSource
import org.oscim.tiling.source.mapfile.MultiMapFileTileSource
import java.io.File
import kotlin.math.abs

/** Jak se mapa natáčí a naklání. */
enum class MapViewMode(val id: Int, val short: String, @androidx.annotation.StringRes val title: Int) {
    PERSPECTIVE_3D(0, "3D", R.string.view_3d),
    HEADING_2D(1, "2D", R.string.view_2d),
    NORTH_UP(2, "N", R.string.view_north);

    companion object {
        fun byId(id: Int) = entries.firstOrNull { it.id == id } ?: PERSPECTIVE_3D
    }
}

/**
 * Obal nad mapou VTM (OpenGL): podkladová mapa s 3D budovami, trasa,
 * stopy, piny, poloha a režimy zobrazení 3D / 2D / sever nahoře.
 */
class MapController(
    private val context: Context,
    private val view: MapView,
    onLongPress: (LatLong) -> Unit,
) {
    val map = view.map()

    private var baseLayers: List<Layer> = emptyList()
    private var theme: IRenderTheme? = null
    private var loadedMaps: List<File> = emptyList()

    private val trackLayer = PathLayer(map, line(0xB08E24AA.toInt(), 5f))
    // Trasa tmavě modrá – červená splývala s červenými a oranžovými silnicemi, řeky jsou světle modré
    private val routeLayer = PathLayer(map, line(ROUTE_COLOR, 7f))
    /** Projetá část trasy za navigace (pod zbývající částí). */
    private val passedLayer = PathLayer(map, line(ROUTE_PASSED, ROUTE_PASSED_WIDTH))
    /** Šipky směru jízdy na okruhu (start = cíl, jinak by nebylo poznat, kudy vyjet). */
    private var arrowBitmap = makeArrowBitmap()
    private var shownDirection: List<LatLong>? = null
    private val arrows = ItemizedLayer(map, arrowSymbol())

    private fun makeArrowBitmap() = AndroidGraphics.drawableToBitmap(BitmapDrawable(context.resources,
        MarkerIcons.directionArrow(context, routeColor())))

    // Ne-billboard: šipka leží na mapě a otáčí se s ní. VTM drží natočení v symbolu,
    // proto má každá šipka vlastní (obrázek sdílejí)
    private fun arrowSymbol() = MarkerSymbol(arrowBitmap, MarkerSymbol.HotspotPlace.CENTER, false)
    private val altLayers = List(2) { PathLayer(map, line(0xAA78909C.toInt(), 6f)) }
    // Záznam jízdy tyrkysově, ať se neplete s modrou trasou
    private val recordedLayer = PathLayer(map, line(0xCC00897B.toInt(), 5f))
    private val pinStart = symbol(R.drawable.ic_pin_start)
    private val pinVia = symbol(R.drawable.ic_pin_via)
    private val pinEnd = symbol(R.drawable.ic_pin_end)
    private val pins = ItemizedLayer(map, pinEnd)
    /** Poloha jezdce: zvolená ikona ležící na mapě, natočená po směru jízdy. */
    private val location = ItemizedLayer(map, pinEnd)
    private var locationItem: MarkerItem? = null
    private var markerKey = ""

    var mode: MapViewMode = MapViewMode.PERSPECTIVE_3D
        private set
    private var navigating = false

    private lateinit var scaleLayer: MapScaleBarLayer

    /** Uživatel mapu posunul, přiblížil nebo otočil prstem (ne animace z kódu). */
    var onUserMove: (() -> Unit)? = null

    init {
        map.viewport().setMinZoomLevel(4)
        map.viewport().setMaxZoomLevel(20)
        map.events.bind(org.oscim.map.Map.UpdateListener { e, _ ->
            if (e == org.oscim.map.Map.MOVE_EVENT || e == org.oscim.map.Map.SCALE_EVENT ||
                e == org.oscim.map.Map.ROTATE_EVENT || e == org.oscim.map.Map.TILT_EVENT
            ) onUserMove?.invoke()
            if (e == org.oscim.map.Map.POSITION_EVENT) view.post { refreshLocationView() }
        })

        val layers = map.layers()
        layers.add(LongPressLayer(onLongPress))
        layers.add(trackLayer)
        altLayers.forEach(layers::add)
        layers.add(passedLayer)
        layers.add(routeLayer)
        layers.add(recordedLayer)
        layers.add(arrows)
        layers.add(pins)
        layers.add(location)

        val scaleBar = DefaultMapScaleBar(map)
        scaleLayer = MapScaleBarLayer(map, scaleBar)
        scaleLayer.renderer.setPosition(GLViewport.Position.BOTTOM_LEFT)
        scaleLayer.renderer.setOffset(5 * CanvasAdapter.getScale(), 0f)
        layers.add(scaleLayer)
    }

    fun onResume() = view.onResume()
    fun onPause() = view.onPause()
    fun onDestroy() {
        theme?.dispose()
        view.onDestroy()
    }

    // ---------------------------------------------------------------- podkladová mapa

    /** Načte všechny *.map soubory jako jednu podkladovou mapu. Vrací false, když žádná není. */
    fun setMaps(files: List<File>): Boolean {
        if (files == loadedMaps) return files.isNotEmpty()
        loadedMaps = files
        val layers = map.layers()
        baseLayers.forEach { layers.remove(it) }
        baseLayers = emptyList()
        theme?.dispose()
        theme = null
        if (files.isEmpty()) {
            map.updateMap(true)
            return false
        }

        val source = MultiMapFileTileSource()
        files.forEach { f -> source.add(MapFileTileSource().apply { setMapFile(f.path) }) }
        val tiles: VectorTileLayer = map.setBaseMap(source) // vloží se hned nad vrstvu gest
        val buildings = BuildingLayer(map, tiles)
        val labels = LabelLayer(map, tiles)
        layers.add(2, buildings)
        layers.add(3, labels)
        baseLayers = listOf(tiles, buildings, labels)
        loadTheme()
        return true
    }

    // ---------------------------------------------------------------- noční mapa

    /** Mapa v nočních barvách (tmavé plochy, světlejší silnice, světlé popisky). */
    var night = false
        private set

    fun setNight(on: Boolean) {
        if (on == night) return
        night = on
        // Tmavě modrá trasa by na tmavé mapě zanikla, v noci je světlejší
        applyRouteStyle()
        arrowBitmap = makeArrowBitmap()
        shownDirection?.let(::showDirection)
        if (baseLayers.isNotEmpty()) loadTheme() else map.updateMap(true)
    }

    private fun routeColor() = if (night) ROUTE_COLOR_NIGHT else ROUTE_COLOR

    // ---------------------------------------------------------------- průběh navigace

    /** Za navigace: trasa před jezdcem tmavě modrá a široká, projetá část za ním světle modrá a užší. */
    private var routeProgress = false

    fun setRouteProgress(on: Boolean) {
        routeProgress = on
        if (!on) passedLayer.clearPath()
        applyRouteStyle()
        map.updateMap(true)
    }

    private fun applyRouteStyle() {
        routeLayer.setStyle(if (routeProgress) line(ROUTE_AHEAD, ROUTE_AHEAD_WIDTH) else line(routeColor(), 7f))
    }

    /** Rozdělí trasu v místě jezdce ([at] na úseku [segment]) na projetou a zbývající část. */
    fun showProgress(points: List<LatLong>, segment: Int, at: LatLong) {
        if (!routeProgress || points.size < 2) return
        val i = segment.coerceIn(0, points.size - 2)
        setPath(passedLayer, points.subList(0, i + 1) + at)
        setPath(routeLayer, listOf(at) + points.subList(i + 1, points.size))
    }

    private fun loadTheme() {
        val old = theme
        val file = VtmThemes.MOTORIDER.apply { menuCallback = MotoThemeMenu }
        val loaded = if (night) ThemeLoader.load(file, NightThemeCallback) else ThemeLoader.load(file)
        map.setTheme(loaded)
        theme = loaded
        old?.dispose()
        map.updateMap(true)
    }

    /** Přebarví styl mapy při načtení; ikony (pumpy, vyhlídky…) zůstávají. */
    private object NightThemeCallback : ThemeCallback {
        override fun getBitmap(bitmap: org.oscim.backend.canvas.Bitmap?) = bitmap
        override fun getColor(origin: RenderStyle<*>?, color: Int): Int = NightColors.transform(
            when (origin) {
                is LineStyle, is CircleStyle -> NightColors.Kind.LINE
                is TextStyle -> NightColors.Kind.TEXT
                // Plochy, 3D budovy a pozadí mapy (origin null)
                else -> NightColors.Kind.AREA
            },
            color,
        )
    }

    // ---------------------------------------------------------------- poloha mapy

    /** Měřítko nad systémovými lištami (mapa se kreslí až k okraji displeje). */
    fun setBottomInset(left: Int, bottom: Int) {
        scaleLayer.renderer.setOffset(5 * CanvasAdapter.getScale() + left, bottom.toFloat())
        map.updateMap(true)
    }

    val center: LatLong get() = map.mapPosition.let { LatLong(it.latitude, it.longitude) }
    val zoomLevel: Int get() = map.mapPosition.zoomLevel

    fun moveTo(p: LatLong, zoom: Int? = null, animate: Boolean = true) {
        val pos = map.mapPosition
        pos.setPosition(p.latitude, p.longitude)
        zoom?.let { pos.setZoomLevel(it) }
        if (animate) map.animator().animateTo(400, pos) else map.setMapPosition(pos)
    }

    /**
     * Vejde body do volné části mapy (obdélník v pixelech mapy), která
     * nezasahuje pod lišty a panely. Přehled je vždy rovně a severem nahoru.
     */
    fun fit(points: List<LatLong>, freeLeft: Int, freeTop: Int, freeRight: Int, freeBottom: Int) {
        if (points.size < 2 || view.width == 0) return
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        val freeW = (freeRight - freeLeft).coerceAtLeast((w / 4).toInt())
        val freeH = (freeBottom - freeTop).coerceAtLeast((h / 4).toInt())
        // Střed mapy vykreslíme doprostřed volné části
        map.viewport().setMapViewCenter(
            ((freeLeft + freeRight) / 2f - w / 2) / (w / 2),
            ((freeTop + freeBottom) / 2f - h / 2) / (h / 2),
        )
        val pos = MapPosition()
        pos.setByBoundingBox(BoundingBox(points.map { GeoPoint(it.latitude, it.longitude) }), (freeW * 0.9).toInt(), (freeH * 0.9).toInt())
        pos.setZoomLevel(pos.zoomLevel.coerceIn(4, 18))
        pos.setBearing(0f)
        pos.setTilt(0f)
        map.animator().animateTo(500, pos)
    }

    /** Střed mapy zpět doprostřed obrazovky (po zavření panelu trasy). */
    fun resetViewCenter() = map.viewport().setMapViewCenter(0f, 0f)

    // ---------------------------------------------------------------- režim zobrazení

    fun setMode(newMode: MapViewMode) {
        mode = newMode
        val events = map.eventLayer
        events.enableRotation(newMode != MapViewMode.NORTH_UP)
        events.enableTilt(newMode == MapViewMode.PERSPECTIVE_3D)
        val pos = map.mapPosition
        pos.setTilt(if (newMode == MapViewMode.PERSPECTIVE_3D) TILT_3D else 0f)
        if (newMode == MapViewMode.NORTH_UP) pos.setBearing(0f)
        if (navigating) {
            // Za navigace kameru vede plynulý pohyb, náklon a natočení k cíli dojedou samy
            map.viewport().setMapViewCenter(0f, navPivotY())
            camTilt = pos.tilt
            if (newMode == MapViewMode.NORTH_UP) camBearing = 0f
            kickGlide()
            return
        }
        map.animator().animateTo(400, pos)
    }

    /** Ruční přiblížení tlačítky během navigace se přičítá k automatickému zoomu. */
    private var navZoomOffset = 0

    fun zoomBy(delta: Int) {
        if (navigating) {
            navZoomOffset = (navZoomOffset + delta).coerceIn(-4, 3)
            camZoom = (camZoom + delta).coerceIn(3.0, 20.0)
            kickGlide()
            return
        }
        val pos = map.mapPosition
        pos.setZoomLevel((pos.zoomLevel + delta).coerceIn(3, 20))
        map.animator().animateTo(250, pos)
    }

    fun startFollowing() {
        navZoomOffset = 0
        zoomSpeed = -1.0
        navigating = true
        map.viewport().setMapViewCenter(0f, navPivotY())
    }

    /** Konec navigace; mapa se vrátí na [center] (poloha jezdce), pokud ho známe. */
    fun stopFollowing(center: LatLong? = null) {
        navigating = false
        camera = false
        map.viewport().setMapViewCenter(0f, 0f)
        val pos = map.mapPosition
        center?.let { pos.setPosition(it.latitude, it.longitude) }
        pos.setTilt(if (mode == MapViewMode.PERSPECTIVE_3D) TILT_3D else 0f)
        map.animator().animateTo(300, pos)
    }

    /** Kamera při navigaci: jezdec ve spodní části, mapa natočená podle režimu. */
    fun follow(p: LatLong, headingDeg: Float?, speedMps: Float) {
        // Rychlost z GPS kolísá – zoom se řídí vyhlazenou, ať mapa nepumpuje
        val kmh = speedMps * 3.6
        zoomSpeed = if (zoomSpeed < 0) kmh else zoomSpeed + (kmh - zoomSpeed) * ZOOM_SPEED_SMOOTHING
        val auto = speedZoom(zoomSpeed) + if (mode == MapViewMode.PERSPECTIVE_3D) 0.0 else ZOOM_2D_EXTRA
        if (!camera) {
            // Kamera navazuje na to, co je právě vidět
            map.animator().cancel()
            map.mapPosition.let { camBearing = it.bearing; camTilt = it.tilt }
            camera = true
        }
        camZoom = (auto + navZoomOffset).coerceIn(3.0, 20.0)
        camTilt = if (mode == MapViewMode.PERSPECTIVE_3D) TILT_3D else 0f
        camBearing = if (mode == MapViewMode.NORTH_UP) 0f else headingDeg?.let { -it } ?: camBearing
        // Ikona jede na stejné místo jako kamera, jinak by se vůči sobě chvěly
        setLocation(p, headingDeg)
    }

    /**
     * Svislá poloha jezdce při navigaci (−1 nahoře, 0 uprostřed, 1 dole). Nastavuje ji obrazovka
     * do spodní třetiny viditelné mapy mezi horním pruhem s pokynem a spodním panelem.
     */
    private var navPivot = 0f

    fun setNavPivot(pivotY: Float) {
        navPivot = pivotY.coerceIn(-0.9f, 0.9f)
        if (navigating) {
            map.viewport().setMapViewCenter(0f, navPivot)
            map.updateMap(true)
        }
    }

    private fun navPivotY() = navPivot

    // ---------------------------------------------------------------- překryvy

    /** Jezdec jde pěšky (navigace k motorce) – místo motorky se ukazuje chodec. */
    var walking = false

    private var lastLocation: LatLong? = null
    private var lastHeading: Float? = null

    /**
     * 3D motorka se při otáčení mapy musí ukázat z jiného úhlu i bez nové polohy
     * a po přepnutí 3D ↔ 2D se vyměnit za plochou ikonu (a zpět).
     */
    private fun refreshLocationView() {
        if (lastLocation == null) return
        val frame = if (!walking && mode == MapViewMode.PERSPECTIVE_3D && MarkerIcons.has3d(Settings.markerShape))
            MarkerIcons.frame3d((shownHeading ?: 0f) + map.mapPosition.bearing) else -1
        if (!markerKey.endsWith("/$frame")) placeMarker()
    }

    /**
     * Nová poloha jezdce. Ikona na ni nepřeskočí, ale do příští polohy z GPS (zhruba za sekundu)
     * k ní plynule dojede.
     * @param headingDeg směr jízdy od severu, null = neznámý (ponechá poslední směr)
     */
    fun setLocation(p: LatLong, headingDeg: Float?) {
        val first = lastLocation == null
        lastLocation = p
        headingDeg?.let { lastHeading = it }
        val now = System.nanoTime()
        if (first || walkingShown != walking || LatLong(shownLat, shownLon).sphericalDistance(p) > JUMP_METERS) {
            // Na začátku, po přepnutí na chodce nebo po velkém skoku (ztráta signálu) rovnou přeskočí
            shownLat = p.latitude
            shownLon = p.longitude
            shownHeading = lastHeading
            walkingShown = walking
        }
        glideFromLat = shownLat
        glideFromLon = shownLon
        glideDurationNs = (now - lastFixNs).coerceIn(MIN_GLIDE_NS, MAX_GLIDE_NS)
        glideStartNs = now
        lastFixNs = now
        placeMarker()
        kickGlide()
    }

    // ---------------------------------------------------------------- plynulý pohyb

    /** Zobrazená poloha a směr ikony – mezi polohami z GPS se posouvají po snímcích. */
    private var shownLat = 0.0
    private var shownLon = 0.0
    private var shownHeading: Float? = null
    private var walkingShown = false
    private var glideFromLat = 0.0
    private var glideFromLon = 0.0
    private var glideStartNs = 0L
    private var glideDurationNs = MIN_GLIDE_NS
    private var lastFixNs = 0L

    /** Za navigace jede kamera s ikonou; zoom, natočení a náklon plynule dobíhají k cíli. */
    private var camera = false
    /** Úroveň přiblížení kamery, i neceločíselná – zoom se mění plynule s rychlostí. */
    private var camZoom = 17.0
    /** Vyhlazená rychlost pro zoom (km/h), záporná = zatím žádná. */
    private var zoomSpeed = -1.0
    private var camBearing = 0f
    private var camTilt = 0f

    private var glideRunning = false
    private val glide = Choreographer.FrameCallback(::glideFrame)

    private fun kickGlide() {
        if (glideRunning) return
        glideRunning = true
        Choreographer.getInstance().postFrameCallback(glide)
    }

    private fun glideFrame(frameNs: Long) {
        val p = lastLocation ?: run { glideRunning = false; return }
        val t = ((frameNs - glideStartNs).toDouble() / glideDurationNs).coerceIn(0.0, 1.0)
        shownLat = glideFromLat + (p.latitude - glideFromLat) * t
        shownLon = glideFromLon + (p.longitude - glideFromLon) * t
        var settled = t >= 1.0
        lastHeading?.let { target ->
            val next = approachAngle(shownHeading ?: target, target, HEADING_SMOOTHING)
            shownHeading = next
            if (abs(angleDiff(next, target)) > 0.5f) settled = false
        }
        placeMarker()
        if (camera) {
            val pos = map.mapPosition
            pos.setPosition(shownLat, shownLon)
            val targetScale = Math.pow(2.0, camZoom)
            pos.setScale(pos.scale + (targetScale - pos.scale) * CAMERA_SMOOTHING)
            pos.setBearing(approachAngle(pos.bearing, camBearing, CAMERA_SMOOTHING))
            pos.setTilt(pos.tilt + (camTilt - pos.tilt) * CAMERA_SMOOTHING)
            map.setMapPosition(pos)
            if (abs(pos.scale / targetScale - 1) > 0.01 || abs(angleDiff(pos.bearing, camBearing)) > 0.5f ||
                abs(pos.tilt - camTilt) > 0.5f
            ) settled = false
        }
        if (settled) glideRunning = false else Choreographer.getInstance().postFrameCallback(glide)
    }

    /** Rozdíl úhlů v rozsahu −180…180°. */
    private fun angleDiff(from: Float, to: Float): Float = ((to - from) % 360 + 540) % 360 - 180

    /** Úhel posunutý o [factor] zbývajícího rozdílu k cíli, nejkratší stranou. */
    private fun approachAngle(from: Float, to: Float, factor: Float): Float =
        ((from + angleDiff(from, to) * factor) % 360 + 360) % 360

    /** Vykreslí ikonu na zobrazené poloze a ve zobrazeném směru. */
    private fun placeMarker() {
        val headingDeg = shownHeading
        val shape = Settings.markerShape
        // Ve 3D stojí motorka svisle a je vidět z úhlu kamery; plochá by na nakloněné mapě ležela
        val frame3d = if (!walking && mode == MapViewMode.PERSPECTIVE_3D && MarkerIcons.has3d(shape))
            MarkerIcons.frame3d((headingDeg ?: 0f) + map.mapPosition.bearing) else -1
        val key = "${shape.id}/${Settings.markerColor}/$walking/$frame3d"
        if (key != markerKey) {
            markerKey = key
            locationItem = null
        }
        val item = locationItem ?: MarkerItem("", "", GeoPoint(shownLat, shownLon)).also {
            it.marker = if (walking) {
                // Chodec je kreslený z boku: stojí na místě nohama a neotáčí se
                MarkerSymbol(
                    AndroidGraphics.drawableToBitmap(BitmapDrawable(context.resources,
                        MarkerIcons.pedestrian(context, Settings.markerColor, 40f))),
                    MarkerSymbol.HotspotPlace.BOTTOM_CENTER, true,
                )
            } else if (frame3d >= 0) {
                // Poloha jezdce je pod předním kolem, ne uprostřed (zvětšené) motorky
                val (ax, ay) = MarkerIcons.anchor3d(frame3d)
                MarkerSymbol(
                    AndroidGraphics.drawableToBitmap(BitmapDrawable(context.resources,
                        MarkerIcons.bike3d(context, shape, Settings.markerColor, frame3d, 72f))),
                    ax, ay, true,
                )
            } else {
                // Ne-billboard: ikona leží na mapě, otáčí se a ve 3D se naklání s ní;
                // poloha je u přídě, ikona se otáčí kolem ní
                MarkerSymbol(
                    AndroidGraphics.drawableToBitmap(BitmapDrawable(context.resources,
                        MarkerIcons.bitmap(context, Settings.markerShape, Settings.markerColor, 40f))),
                    0.5f, MarkerIcons.FLAT_NOSE_Y, false,
                )
            }
            location.removeAllItems()
            location.addItem(it)
            locationItem = it
        }
        item.geoPoint = GeoPoint(shownLat, shownLon)
        // VTM otáčí ikonu ležící na mapě po směru hodinových ručiček, stejně jako kurz
        if (!walking && frame3d < 0) headingDeg?.let { item.setRotation(it) }
        location.populate()
        map.updateMap(true)
    }

    fun showRoute(points: List<LatLong>?) = setPath(routeLayer, points)

    /** Šipky ve směru jízdy podél trasy (u okruhu); null je smaže. */
    fun showDirection(points: List<LatLong>?) {
        shownDirection = points
        arrows.removeAllItems()
        if (points != null && points.size >= 2) {
            val cumulative = DoubleArray(points.size)
            for (i in 1 until points.size) cumulative[i] = cumulative[i - 1] + points[i - 1].sphericalDistance(points[i])
            val length = cumulative.last()
            val step = maxOf(1000.0, length / ARROWS_ALONG)
            val at = (ARROWS_NEAR_START + generateSequence(step) { it + step }.takeWhile { it < length - 300 })
                .filter { it < length - 300 }
            val items = mutableListOf<MarkerInterface>()
            var i = 1
            for (d in at.sorted()) {
                while (i < points.lastIndex && cumulative[i] < d) i++
                val a = points[i - 1]
                val b = points[i]
                val f = if (cumulative[i] > cumulative[i - 1]) (d - cumulative[i - 1]) / (cumulative[i] - cumulative[i - 1]) else 0.0
                val lat = a.latitude + (b.latitude - a.latitude) * f.coerceIn(0.0, 1.0)
                val lon = a.longitude + (b.longitude - a.longitude) * f.coerceIn(0.0, 1.0)
                items += MarkerItem("", "", GeoPoint(lat, lon)).apply {
                    marker = arrowSymbol()
                    // VTM otáčí ikonu ležící na mapě po směru hodinových ručiček, stejně jako kurz
                    setRotation(bearing(a, b))
                }
            }
            arrows.addItems(items)
        }
        map.updateMap(true)
    }

    /** Kurz z [a] do [b] ve stupních od severu. */
    private fun bearing(a: LatLong, b: LatLong): Float {
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val y = Math.sin(dLon) * Math.cos(lat2)
        val x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon)
        return ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).toFloat()
    }

    /** Nevybrané alternativy šedě pod hlavní trasou. */
    fun showAlternatives(routes: List<List<LatLong>>) =
        altLayers.forEachIndexed { i, layer -> setPath(layer, routes.getOrNull(i)) }
    fun showTrack(points: List<LatLong>?) = setPath(trackLayer, points)
    fun showRecorded(points: List<LatLong>?) = setPath(recordedLayer, points)

    private fun setPath(layer: PathLayer, points: List<LatLong>?) {
        if (points.isNullOrEmpty()) layer.clearPath()
        else layer.setPoints(points.map { GeoPoint(it.latitude, it.longitude) })
        map.updateMap(true)
    }

    fun setPins(start: LatLong?, vias: List<LatLong>, end: LatLong?) {
        pins.removeAllItems()
        val items = mutableListOf<MarkerInterface>()
        fun add(p: LatLong, s: MarkerSymbol) {
            items += MarkerItem("", "", GeoPoint(p.latitude, p.longitude)).apply { marker = s }
        }
        vias.forEach { add(it, pinVia) }
        end?.let { add(it, pinEnd) }
        start?.let { add(it, pinStart) } // start navrch, u okruhu leží na cíli
        pins.addItems(items)
        map.updateMap(true)
    }

    // Konstruktor místo builderu – builder bez dalších voleb vyrobí čáru, která se nevykreslí
    private fun line(@ColorInt color: Int, widthDp: Float): LineStyle =
        // VTM šířku sám násobí hustotou displeje, proto se předává v dp
        LineStyle(color, widthDp, org.oscim.backend.canvas.Paint.Cap.ROUND)

    private fun symbol(@DrawableRes res: Int): MarkerSymbol =
        MarkerSymbol(
            AndroidGraphics.drawableToBitmap(ContextCompat.getDrawable(context, res)!!),
            MarkerSymbol.HotspotPlace.BOTTOM_CENTER,
        )

    /** Neviditelná vrstva, která zachytává dlouhý stisk do mapy. */
    private inner class LongPressLayer(private val callback: (LatLong) -> Unit) : Layer(map), GestureListener {
        override fun onGesture(g: Gesture, e: MotionEvent): Boolean {
            if (g != Gesture.LONG_PRESS) return false
            val p = map.viewport().fromScreenPoint(e.x, e.y)
            callback(LatLong(p.latitude, p.longitude))
            return true
        }
    }

    companion object {
        private const val TILT_3D = 55f

        /**
         * Přiblížení podle rychlosti (km/h): ve městě zblízka, ať jsou vidět odbočky,
         * na okresce a dálnici oddálené, ať je vidět daleko dopředu. Mezi body plynule.
         */
        private val SPEED_ZOOM = listOf(0.0 to 18.0, 30.0 to 17.5, 50.0 to 17.0, 70.0 to 16.4, 90.0 to 15.8, 130.0 to 14.8)
        /** Ve 2D je vidět méně dopředu než na nakloněné mapě, proto o kousek blíž. */
        private const val ZOOM_2D_EXTRA = 0.5
        /** Podíl změny rychlosti, o který se vyhlazená rychlost posune s každou polohou z GPS. */
        private const val ZOOM_SPEED_SMOOTHING = 0.25

        internal fun speedZoom(kmh: Double): Double {
            val pts = SPEED_ZOOM
            if (kmh <= pts.first().first) return pts.first().second
            if (kmh >= pts.last().first) return pts.last().second
            val i = pts.indexOfFirst { it.first > kmh }
            val (v0, z0) = pts[i - 1]
            val (v1, z1) = pts[i]
            return z0 + (z1 - z0) * (kmh - v0) / (v1 - v0)
        }
        private const val ROUTE_COLOR = 0xDD1565C0.toInt()
        private const val ROUTE_COLOR_NIGHT = 0xEE42A5F5.toInt()
        /** Za navigace: zbývající trasa tmavě modrá a širší, projetá světle modrá a užší. */
        private const val ROUTE_AHEAD = 0xEE0D47A1.toInt()
        private const val ROUTE_PASSED = 0xDD4FC3F7.toInt()
        private const val ROUTE_AHEAD_WIDTH = 10f
        private const val ROUTE_PASSED_WIDTH = 5f
        /** Šipky hned za startem, ať je směr vidět i na přiblížené mapě (m od startu). */
        private val ARROWS_NEAR_START = listOf(120.0, 350.0, 700.0)
        /** Dál po okruhu zhruba tolik šipek, ale ne hustěji než po kilometru. */
        private const val ARROWS_ALONG = 14
        /** Skok polohy, po kterém ikona nedojíždí, ale rovnou přeskočí (ztráta signálu). */
        private const val JUMP_METERS = 300.0
        /** Ikona dojíždí k nové poloze za dobu od předchozí polohy z GPS, ale v těchto mezích. */
        private const val MIN_GLIDE_NS = 300_000_000L
        private const val MAX_GLIDE_NS = 1_500_000_000L
        /** Podíl zbývajícího otočení ikony, zoomu, natočení a náklonu kamery za jeden snímek. */
        private const val HEADING_SMOOTHING = 0.15f
        private const val CAMERA_SMOOTHING = 0.08f
    }

    /**
     * Ze stylu MOTORIDER vypne vrstvy, které motorkáři mapu jen zahlcují
     * (parkoviště, nabíječky aut, obchody, MHD, čísla domů, restaurace…).
     * Pumpy, vyhlídky, průsmyky, vrcholy a ubytování zůstávají.
     */
    private object MotoThemeMenu : XmlRenderThemeMenuCallback {
        private val hidden = setOf("parking", "charging_station", "shop", "public_transport", "housenumber", "money", "entertainment", "food")

        override fun getCategories(style: XmlRenderThemeStyleMenu): Set<String> {
            val base = style.getLayer(style.defaultValue)
            val categories = HashSet(base.categories)
            // Vrstvy v MOTORIDER nemají atribut enabled (výchozí false), proto bereme všechny kromě skrytých
            for (overlay in base.overlays) {
                if (overlay.id !in hidden) categories += overlay.categories
            }
            return categories
        }
    }
}
