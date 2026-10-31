package cz.motonavigace

import android.util.Xml
import org.mapsforge.core.model.LatLong
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.max

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val ele: Double = Double.NaN,
    /** Čas v ms od epochy, 0 = neznámý. */
    val time: Long = 0,
) {
    val latLong get() = LatLong(lat, lon)
}

data class GpxTrack(
    val name: String?,
    val points: List<TrackPoint>,
    /** Body z <rte> – plánovaná trasa (průjezdní body), ne projetá stopa. */
    val isRoute: Boolean,
)

data class TrackStats(
    val distanceMeters: Double,
    /** Celková doba od prvního do posledního bodu (s). */
    val durationSeconds: Double,
    /** Doba v pohybu nad 5 km/h (s). */
    val movingSeconds: Double,
    val maxSpeedKmh: Double,
    val ascentMeters: Double,
) {
    val avgMovingSpeedKmh get() = if (movingSeconds > 0) distanceMeters / movingSeconds * 3.6 else 0.0
}

object Gpx {

    /**
     * Načte GPX 1.0/1.1. Upřednostní stopu (<trk>); když chybí, vezme trasu (<rte>),
     * a nakonec samotné body (<wpt>).
     */
    fun parse(input: InputStream): GpxTrack {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val trk = mutableListOf<TrackPoint>()
        val rte = mutableListOf<TrackPoint>()
        val wpt = mutableListOf<TrackPoint>()
        var name: String? = null
        val stack = ArrayDeque<String>()
        var lat = 0.0; var lon = 0.0; var ele = Double.NaN; var time = 0L

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.substringAfter(':')
                    stack.addLast(tag)
                    if (tag == "trkpt" || tag == "rtept" || tag == "wpt") {
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
                        ele = Double.NaN; time = 0
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text.trim()
                    if (text.isEmpty()) continue
                    val parent = stack.getOrNull(stack.size - 2)
                    when (stack.lastOrNull()) {
                        "ele" -> ele = text.toDoubleOrNull() ?: Double.NaN
                        "time" -> time = parseTime(text)
                        // Název souboru: první <name> v <trk>/<rte>, případně v <metadata>
                        "name" -> if (name == null && parent in setOf("trk", "rte", "metadata")) name = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = stack.removeLastOrNull()
                    if (!lat.isNaN() && !lon.isNaN()) {
                        val p = TrackPoint(lat, lon, ele, time)
                        when (tag) {
                            "trkpt" -> trk += p
                            "rtept" -> rte += p
                            "wpt" -> wpt += p
                        }
                    }
                }
            }
        }
        return when {
            trk.size >= 2 -> GpxTrack(name, trk, isRoute = false)
            rte.size >= 2 -> GpxTrack(name, rte, isRoute = true)
            wpt.size >= 2 -> GpxTrack(name, wpt, isRoute = true)
            else -> throw IllegalArgumentException(
                if (Settings.isEnglish()) "The file contains no route or track" else "Soubor neobsahuje žádnou trasu ani stopu")
        }
    }

    fun parse(file: File): GpxTrack = file.inputStream().buffered().use { parse(it) }

    fun write(file: File, name: String, points: List<TrackPoint>) {
        val tmp = File(file.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write("""<?xml version="1.0" encoding="UTF-8"?>""")
            w.write("\n<gpx version=\"1.1\" creator=\"Serpentina\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            w.write("  <trk>\n    <name>${escape(name)}</name>\n    <trkseg>\n")
            for (p in points) {
                w.write("      <trkpt lat=\"%.7f\" lon=\"%.7f\">".format(java.util.Locale.ROOT, p.lat, p.lon))
                if (!p.ele.isNaN()) w.write("<ele>%.1f</ele>".format(java.util.Locale.ROOT, p.ele))
                if (p.time > 0) w.write("<time>${Instant.ofEpochMilli(p.time)}</time>")
                w.write("</trkpt>\n")
            }
            w.write("    </trkseg>\n  </trk>\n</gpx>\n")
        }
        tmp.renameTo(file)
    }

    fun stats(points: List<TrackPoint>): TrackStats {
        val n = points.size
        // Kumulativní vzdálenost, ať jde rychlost spočítat přes libovolné okno bodů
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + points[i - 1].latLong.sphericalDistance(points[i].latLong)

        var moving = 0.0
        var maxSpeed = 0.0
        var ascent = 0.0
        var j = 0
        // Výška z GPS kolísá; stoupání počítáme až po změně o víc než 10 m
        var eleRef = points.firstOrNull { !it.ele.isNaN() }?.ele ?: Double.NaN
        for (i in 1 until n) {
            val a = points[i - 1]; val b = points[i]
            val dt = (b.time - a.time) / 1000.0
            if (a.time > 0 && b.time > 0 && dt > 0 && (cum[i] - cum[i - 1]) / dt > 1.4) moving += dt
            // Maximum z okna aspoň 5 s – rychlost z jednoho sekundového úseku je moc zašuměná
            if (b.time > 0) {
                while (j < i - 1 && points[j + 1].time > 0 && b.time - points[j + 1].time >= 5000) j++
                val window = (b.time - points[j].time) / 1000.0
                if (points[j].time > 0 && window >= 5) maxSpeed = max(maxSpeed, (cum[i] - cum[j]) / window * 3.6)
            }
            if (!b.ele.isNaN() && !eleRef.isNaN()) {
                if (b.ele - eleRef > 10) { ascent += b.ele - eleRef; eleRef = b.ele }
                else if (eleRef - b.ele > 10) eleRef = b.ele
            }
        }
        val t0 = points.firstOrNull()?.time ?: 0
        val t1 = points.lastOrNull()?.time ?: 0
        val duration = if (t0 > 0 && t1 > t0) (t1 - t0) / 1000.0 else 0.0
        return TrackStats(cum.lastOrNull() ?: 0.0, duration, moving, maxSpeed, ascent)
    }

    /**
     * Vybere z dlouhé stopy průjezdní body pro BRouter, aby výsledná trasa
     * kopírovala stopu po silnicích a přitom nebylo průjezdních bodů stovky.
     */
    fun shapingPoints(points: List<TrackPoint>, maxPoints: Int = 40): List<LatLong> {
        if (points.size <= maxPoints) return points.map { it.latLong }
        var total = 0.0
        for (i in 1 until points.size) total += points[i - 1].latLong.sphericalDistance(points[i].latLong)
        val step = max(total / (maxPoints - 1), 500.0)
        val result = mutableListOf(points.first().latLong)
        var acc = 0.0
        for (i in 1 until points.size) {
            acc += points[i - 1].latLong.sphericalDistance(points[i].latLong)
            if (acc >= step) { result += points[i].latLong; acc = 0.0 }
        }
        if (result.last() != points.last().latLong) result += points.last().latLong
        return result
    }

    private fun parseTime(s: String): Long =
        runCatching { Instant.parse(s).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .getOrDefault(0)

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
