package cz.motonavigace

import android.content.Context
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
import org.oscim.layers.LocationLayer
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
import org.oscim.theme.XmlRenderThemeMenuCallback
import org.oscim.theme.XmlRenderThemeStyleMenu
import org.oscim.theme.internal.VtmThemes
import org.oscim.theme.styles.LineStyle
import org.oscim.tiling.source.mapfile.MapFileTileSource
import org.oscim.tiling.source.mapfile.MultiMapFileTileSource
import java.io.File

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
    private val routeLayer = PathLayer(map, line(0xCCE53935.toInt(), 7f))
    private val recordedLayer = PathLayer(map, line(0xCC1E88E5.toInt(), 5f))
    private val pinStart = symbol(R.drawable.ic_pin_start)
    private val pinVia = symbol(R.drawable.ic_pin_via)
    private val pinEnd = symbol(R.drawable.ic_pin_end)
    private val pins = ItemizedLayer(map, pinEnd)
    private val location = LocationLayer(map)

    var mode: MapViewMode = MapViewMode.PERSPECTIVE_3D
        private set
    private var navigating = false

    init {
        map.viewport().setMinZoomLevel(4)
        map.viewport().setMaxZoomLevel(20)
        location.isEnabled = false

        val layers = map.layers()
        layers.add(LongPressLayer(onLongPress))
        layers.add(trackLayer)
        layers.add(routeLayer)
        layers.add(recordedLayer)
        layers.add(pins)
        layers.add(location)

        val scaleBar = DefaultMapScaleBar(map)
        val scaleLayer = MapScaleBarLayer(map, scaleBar)
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
        theme = map.setTheme(VtmThemes.MOTORIDER.apply { menuCallback = MotoThemeMenu })
        map.updateMap(true)
        return true
    }

    // ---------------------------------------------------------------- poloha mapy

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
        if (navigating) map.viewport().setMapViewCenter(0f, navPivotY())
        map.animator().animateTo(400, pos)
    }

    fun startFollowing() {
        navigating = true
        map.viewport().setMapViewCenter(0f, navPivotY())
    }

    fun stopFollowing() {
        navigating = false
        map.viewport().setMapViewCenter(0f, 0f)
        val pos = map.mapPosition
        pos.setTilt(if (mode == MapViewMode.PERSPECTIVE_3D) TILT_3D else 0f)
        map.animator().animateTo(300, pos)
    }

    /** Kamera při navigaci: jezdec ve spodní části, mapa natočená podle režimu. */
    fun follow(p: LatLong, headingDeg: Float?, speedMps: Float) {
        val pos = map.mapPosition
        pos.setPosition(p.latitude, p.longitude)
        val fast = speedMps > 22
        pos.setZoomLevel(if (mode == MapViewMode.PERSPECTIVE_3D) (if (fast) 14 else 15) else (if (fast) 15 else 16))
        when (mode) {
            MapViewMode.NORTH_UP -> { pos.setBearing(0f); pos.setTilt(0f) }
            MapViewMode.HEADING_2D -> { headingDeg?.let { pos.setBearing(-it) }; pos.setTilt(0f) }
            MapViewMode.PERSPECTIVE_3D -> { headingDeg?.let { pos.setBearing(-it) }; pos.setTilt(TILT_3D) }
        }
        map.animator().animateTo(900, pos)
    }

    /** Sever nahoře drží jezdce víc uprostřed, po směru jízdy víc dole (vidí dál dopředu). */
    private fun navPivotY() = if (mode == MapViewMode.NORTH_UP) 0.3f else 0.6f

    // ---------------------------------------------------------------- překryvy

    fun setLocation(p: LatLong, accuracy: Float) {
        location.isEnabled = true
        location.setPosition(p.latitude, p.longitude, accuracy)
        map.updateMap(true)
    }

    fun showRoute(points: List<LatLong>?) = setPath(routeLayer, points)
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
