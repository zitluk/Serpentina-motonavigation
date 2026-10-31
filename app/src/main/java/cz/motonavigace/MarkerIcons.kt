package cz.motonavigace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import androidx.annotation.StringRes
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap

/** Druh části ikony – určuje barvu a způsob kreslení. */
enum class Part { TYRE, LINE, BODY, SHADE, SEAT, DARK, METAL, GLASS, SHINE, LIMB, LIMB_BACK }

/** Jedna část ikony: cesta ve viewportu 48 × 48, u čar ([Part.LINE], končetiny) s tloušťkou. */
class Layer(val part: Part, val d: String, val width: Float = 0f) {
    val stroked get() = part == Part.LINE || part == Part.LIMB || part == Part.LIMB_BACK
}

private fun tyre(d: String) = Layer(Part.TYRE, d)
private fun line(d: String, width: Float) = Layer(Part.LINE, d, width)
private fun body(d: String) = Layer(Part.BODY, d)
private fun shade(d: String) = Layer(Part.SHADE, d)
private fun seat(d: String) = Layer(Part.SEAT, d)
private fun dark(d: String) = Layer(Part.DARK, d)
private fun metal(d: String) = Layer(Part.METAL, d)
private fun glass(d: String) = Layer(Part.GLASS, d)
private fun shine(d: String) = Layer(Part.SHINE, d)
private fun limb(d: String, width: Float, back: Boolean = false) = Layer(if (back) Part.LIMB_BACK else Part.LIMB, d, width)

/** Chodec z boku – ikona polohy při navigaci pěšky k motorce. */
private val PEDESTRIAN = listOf(
    limb("M25.4,14.6L21.4,20.4L19.6,26", 3.6f, back = true),
    limb("M24,25.5L20.4,33.2L15.6,40.6", 4.4f, back = true),
    limb("M25.6,13.2L23.9,25.5", 5.6f),
    limb("M24,25.5L28.6,32.8L28.2,41.6L31.6,41.6", 4.4f),
    limb("M26.4,14.6L30.6,19.6L34.6,21.2", 3.6f),
    body("M27.2,2.6a3.9,3.9 0 1,0 0.01,0Z"),
)

/**
 * Tvar ikony polohy na mapě: silueta shora s předkem nahoře (viewport 48 × 48).
 * Části se kreslí v pořadí seznamu; [Part.BODY] dostane zvolenou barvu.
 */
enum class MarkerShape(val id: Int, @StringRes val label: Int, val layers: List<Layer>) {
    ARROW(0, R.string.marker_arrow, listOf(body("M24,3L41,43L24,34L7,43Z"))),
    SPORT(1, R.string.bike_sport, listOf(
        tyre("M22.7,1.5h2.6v8h-2.6z"),
        tyre("M22.1,37.5h3.8v9h-3.8z"),
        line("M21,18.6L17.4,19.6", 1.1f), line("M27,18.6L30.6,19.6", 1.1f),
        metal("M26.2,32L27.6,32.4L27.2,38.6L26,38.6Z"),
        body("M24,6C26.2,6 28.6,9.5 29.4,14.5L29.6,17C29.6,18.6 28.4,19.6 27.6,20.6C28.4,22.5 28.6,25 28.2,27.5C27.8,30 26.8,31.5 26.4,33.5L25.2,41.5Q24,43 22.8,41.5L21.6,33.5C21.2,31.5 20.2,30 19.8,27.5C19.4,25 19.6,22.5 20.4,20.6C19.6,19.6 18.4,18.6 18.4,17L18.6,14.5C19.4,9.5 21.8,6 24,6Z"),
        glass("M24,8.6C25.6,8.6 27,11 27.3,14L24,15.2L20.7,14C21,11 22.4,8.6 24,8.6Z"),
        dark("M29.8,13.4a1.5,0.85 0 1,0 0.01,0Z"), dark("M18.2,13.4a1.5,0.85 0 1,0 0.01,0Z"),
        seat("M22.2,29.5Q24,28.6 25.8,29.5L25.4,35.5Q24,36.3 22.6,35.5Z"),
        shine("M23.4,20.5L24.6,20.5L24.4,27.6L23.6,27.6Z"),
    )),
    TOURING(2, R.string.bike_touring, listOf(
        tyre("M22.5,2h3v8h-3z"),
        tyre("M22,37h4v9h-4z"),
        line("M16,19.8L13.4,20.6", 1.4f), line("M32,19.8L34.6,20.6", 1.4f),
        // Boční kufry
        shade("M32,28.5h4.8a1.5,1.5 0 0 1 1.5,1.5v8a1.5,1.5 0 0 1 -1.5,1.5h-4.8z"),
        shade("M16,28.5h-4.8a1.5,1.5 0 0 0 -1.5,1.5v8a1.5,1.5 0 0 0 1.5,1.5h4.8z"),
        body("M24,5.5C28,5.5 31.5,8.5 32.5,13L33,17.5C33,19.5 31.5,20.5 29.5,21L29.5,30L28.5,38Q24,40 19.5,38L18.5,30L18.5,21C16.5,20.5 15,19.5 15,17.5L15.5,13C16.5,8.5 20,5.5 24,5.5Z"),
        // Topcase
        shade("M21.6,38.6h4.8a2,2 0 0 1 2,2v3.4a2,2 0 0 1 -2,2h-4.8a2,2 0 0 1 -2,-2v-3.4a2,2 0 0 1 2,-2z"),
        glass("M24,7.5C27,7.5 29.3,9.8 29.8,13L24,14.6L18.2,13C18.7,9.8 21,7.5 24,7.5Z"),
        dark("M33.4,14.2a1.6,1 0 1,0 0.01,0Z"), dark("M14.6,14.2a1.6,1 0 1,0 0.01,0Z"),
        seat("M21.5,22Q24,21 26.5,22L26.5,35Q24,36 21.5,35Z"),
        shine("M23.4,15.8L24.6,15.8L24.5,20.6L23.5,20.6Z"),
    )),
    NAKED(3, R.string.bike_naked, listOf(
        tyre("M22.6,1.5h2.8v8.5h-2.8z"),
        tyre("M22,37.5h4v9h-4z"),
        dark("M23,8h2v31h-2z"),
        line("M15.2,14.2L14.4,11", 0.8f), line("M32.8,14.2L33.6,11", 0.8f),
        line("M13,14.8Q24,12.8 35,14.8", 1.4f),
        dark("M14.3,9.6a1.7,1.1 0 1,0 0.01,0Z"), dark("M33.7,9.6a1.7,1.1 0 1,0 0.01,0Z"),
        metal("M26.8,30L28.4,30.4L28.2,38Q27.6,38.8 27,38Z"),
        // Kulaté světlo
        dark("M24,6.9a2.7,2.7 0 1,0 0.01,0Z"),
        glass("M24,7.9a1.7,1.7 0 1,0 0.01,0Z"),
        body("M22.2,33.5L25.8,33.5L25.3,40.2Q24,41.2 22.7,40.2Z"),
        body("M24,15C28.2,15 30.2,18.4 30,22.2C29.8,25.8 27.4,28 24,28C20.6,28 18.2,25.8 18,22.2C17.8,18.4 19.8,15 24,15Z"),
        seat("M21.5,27Q24,26 26.5,27L26.2,35Q24,36.2 21.8,35Z"),
        shine("M23.4,16.8L24.6,16.8L24.5,25L23.5,25Z"),
    )),
    ADVENTURE(4, R.string.bike_adventure, listOf(
        tyre("M22.7,0.8h2.6v9h-2.6z"),
        tyre("M22.2,37.5h3.6v9.2h-3.6z"),
        line("M11,16.2L37,16.2", 1.4f),
        // Hliníkové kufry a nosič
        metal("M29.4,27.5h4.4a0.8,0.8 0 0 1 0.8,0.8v8.4a0.8,0.8 0 0 1 -0.8,0.8h-4.4z"),
        metal("M18.6,27.5h-4.4a0.8,0.8 0 0 0 -0.8,0.8v8.4a0.8,0.8 0 0 0 0.8,0.8h4.4z"),
        metal("M21.8,34.5h4.4v4.2h-4.4z"),
        body("M24,6.5C26.4,6.5 28,9 28.4,12.5L28.6,16C28.8,18.5 27.8,19.5 27.4,20.5C28.8,22.5 29,25.5 28.2,28.5L26.8,40Q24,41.5 21.2,40L19.8,28.5C19,25.5 19.2,22.5 20.6,20.5C20.2,19.5 19.2,18.5 19.4,16L19.6,12.5C20,9 21.6,6.5 24,6.5Z"),
        body("M24,3.6L25.7,7.6L24,8.6L22.3,7.6Z"),
        // Chrániče rukou
        body("M9.6,14.4Q8.4,16.2 9.6,18L12.4,17.8L12.4,14.6Z"), body("M38.4,14.4Q39.6,16.2 38.4,18L35.6,17.8L35.6,14.6Z"),
        glass("M24,7.8C25.6,7.8 26.6,9.8 26.8,12.2L24,13.2L21.2,12.2C21.4,9.8 22.4,7.8 24,7.8Z"),
        seat("M22.2,22.5Q24,21.8 25.8,22.5L25.6,33Q24,33.8 22.4,33Z"),
        shine("M23.4,14.6L24.6,14.6L24.5,20.6L23.5,20.6Z"),
    )),
    CRUISER(5, R.string.bike_cruiser, listOf(
        tyre("M22.6,1h2.8v8.5h-2.8z"),
        tyre("M21.6,37h4.8v9.5h-4.8z"),
        dark("M23,8h2v28h-2z"),
        // Stupačky
        dark("M15.8,23.5h3.4v5h-3.4z"), dark("M28.8,23.5h3.4v5h-3.4z"),
        line("M16.2,9.6L16.4,8.4", 0.8f), line("M31.8,9.6L31.6,8.4", 0.8f),
        line("M14,9.6Q16,15.5 21,15.6L27,15.6Q32,15.5 34,9.6", 1.5f),
        metal("M16.4,6.6a1.3,1.3 0 1,0 0.01,0Z"), metal("M31.6,6.6a1.3,1.3 0 1,0 0.01,0Z"),
        // Válce V-twinu
        metal("M19.6,19.4a1.5,2.5 0 1,0 0.01,0Z"), metal("M28.4,19.4a1.5,2.5 0 1,0 0.01,0Z"),
        metal("M27,27.5L28.6,27.8L29,41Q28.4,42 27.8,41Z"),
        metal("M24,7a2.3,2.3 0 1,0 0.01,0Z"),
        glass("M24,7.9a1.4,1.4 0 1,0 0.01,0Z"),
        body("M20.8,31.5Q24,30.4 27.2,31.5L27.4,43Q24,46 20.6,43Z"),
        body("M24,13C27.2,13 28.6,16.4 28.4,20C28.2,23.6 26.6,25.6 24,25.6C21.4,25.6 19.8,23.6 19.6,20C19.4,16.4 20.8,13 24,13Z"),
        seat("M20.6,24.6Q24,23.4 27.4,24.6L27.8,31.2Q24,33.4 20.2,31.2Z"),
        shine("M23.4,14.8L24.6,14.8L24.5,23.4L23.5,23.4Z"),
    )),
    SCOOTER(6, R.string.bike_scooter, listOf(
        tyre("M22.5,2h3v6h-3z"),
        tyre("M22.3,40.5h3.4v6h-3.4z"),
        line("M16.6,11.6L15.9,9.6", 0.8f), line("M31.4,11.6L32.1,9.6", 0.8f),
        line("M15,11.8L33,11.8", 1.3f),
        dark("M15.7,7.8a1.3,1.3 0 1,0 0.01,0Z"), dark("M32.3,7.8a1.3,1.3 0 1,0 0.01,0Z"),
        body("M24,4.5C28.6,4.5 31,7.5 31,11.5L30.6,16.5C30.4,18.6 29,19.5 27.4,19.8L27.2,25.5C30,26.5 31.2,29 31,32.5L30.4,38C30,41.5 27.4,43 24,43C20.6,43 18,41.5 17.6,38L17,32.5C16.8,29 18,26.5 20.8,25.5L20.6,19.8C19,19.5 17.6,18.6 17.4,16.5L17,11.5C17,7.5 19.4,4.5 24,4.5Z"),
        // Podlaha pro nohy
        dark("M21.6,19.8L26.4,19.8L26.4,25.4L21.6,25.4Z"),
        shade("M21,9.5Q24,8 27,9.5L27,13Q24,14 21,13Z"),
        glass("M24,8.1a1.8,0.9 0 1,0 0.01,0Z"),
        seat("M21,27Q24,25.8 27,27L27,37Q24,38.6 21,37Z"),
        shine("M23.4,14.2L24.6,14.2L24.5,18.6L23.5,18.6Z"),
    ));

    companion object {
        fun byId(id: Int) = entries.firstOrNull { it.id == id } ?: ARROW
    }
}

/** Nabízené barvy ikony (ARGB). */
val MARKER_COLORS = listOf(
    0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFF212121.toInt(), 0xFFFAFAFA.toInt(),
)

object MarkerIcons {
    private const val TYRE = 0xFF1B1B1B.toInt()
    private const val SEAT = 0xFF262626.toInt()
    private const val DARK = 0xFF202020.toInt()
    private const val GLASS = 0xD92C3E50.toInt()
    private const val GLASS_EDGE = 0xFF9FB3C4.toInt()
    private const val METAL_EDGE = 0xFF5B6066.toInt()
    /** Tloušťka kontrastního obrysu (na každou stranu polovina). */
    private const val HALO = 2.4f

    /**
     * Ikona jako bitmapa [sizeDp] × [sizeDp]. Pod ní měkký stín a kolem tenký obrys
     * v kontrastní barvě, aby byla vidět na světlé i tmavé mapě.
     */
    fun bitmap(context: Context, shape: MarkerShape, color: Int, sizeDp: Float = 48f): Bitmap =
        draw(context, shape.layers, color, sizeDp)

    /** Chodec ve zvolené barvě; kreslený z boku, na mapě stojí a neotáčí se. */
    fun pedestrian(context: Context, color: Int, sizeDp: Float = 48f): Bitmap =
        draw(context, PEDESTRIAN, color, sizeDp)

    private fun draw(context: Context, parts: List<Layer>, color: Int, sizeDp: Float): Bitmap {
        val px = (sizeDp * context.resources.displayMetrics.density).toInt()
        val bmp = createBitmap(px, px)
        val c = Canvas(bmp)
        c.scale(px / 48f, px / 48f)
        val light = Color.luminance(color) > 0.6f
        val outline = if (light) 0xFF202020.toInt() else Color.WHITE
        val layers = parts.map { it to path(it.d) }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }

        // 1) měkký stín, 2) obrys celé siluety
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = 0x59000000; maskFilter = BlurMaskFilter(1.6f, BlurMaskFilter.Blur.NORMAL)
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        c.save(); c.translate(0f, 0.9f)
        silhouette(c, layers, shadow, extra = 0f)
        c.restore()
        shadow.maskFilter = null; shadow.color = outline
        silhouette(c, layers, shadow, extra = HALO)

        // 3) části zezadu dopředu
        val bodyShader = LinearGradient(13f, 0f, 35f, 0f,
            intArrayOf(tint(color, 0.62f), tint(color, 1.18f), tint(color, 0.62f)), null, Shader.TileMode.CLAMP)
        val metalShader = LinearGradient(10f, 0f, 38f, 0f,
            intArrayOf(0xFF7D8389.toInt(), 0xFFE3E6E8.toInt(), 0xFF7D8389.toInt()), null, Shader.TileMode.CLAMP)
        stroke.strokeWidth = 0.5f
        for ((layer, p) in layers) {
            fill.shader = null
            when (layer.part) {
                Part.TYRE -> { fill.color = TYRE; c.drawPath(p, fill) }
                Part.SEAT -> { fill.color = SEAT; c.drawPath(p, fill) }
                Part.DARK -> { fill.color = DARK; c.drawPath(p, fill) }
                Part.LINE -> {
                    stroke.color = TYRE; stroke.strokeWidth = layer.width; c.drawPath(p, stroke); stroke.strokeWidth = 0.5f
                }
                Part.BODY -> {
                    fill.shader = bodyShader; c.drawPath(p, fill)
                    stroke.color = tint(color, 0.5f); c.drawPath(p, stroke)
                }
                Part.SHADE -> {
                    fill.color = tint(color, 0.72f); c.drawPath(p, fill)
                    stroke.color = tint(color, 0.45f); c.drawPath(p, stroke)
                }
                Part.METAL -> {
                    fill.shader = metalShader; c.drawPath(p, fill)
                    stroke.color = METAL_EDGE; c.drawPath(p, stroke)
                }
                Part.GLASS -> {
                    fill.color = GLASS; c.drawPath(p, fill)
                    stroke.color = GLASS_EDGE; stroke.strokeWidth = 0.35f; c.drawPath(p, stroke); stroke.strokeWidth = 0.5f
                }
                Part.SHINE -> { fill.color = Color.argb(if (light) 140 else 97, 255, 255, 255); c.drawPath(p, fill) }
                Part.LIMB, Part.LIMB_BACK -> {
                    if (layer.part == Part.LIMB) stroke.shader = bodyShader else stroke.color = tint(color, 0.72f)
                    stroke.strokeWidth = layer.width; c.drawPath(p, stroke)
                    stroke.shader = null; stroke.strokeWidth = 0.5f
                }
            }
        }
        return bmp
    }

    /** Silueta všech částí; čáry o [extra] tlustší, plochy s obrysem [extra]. */
    private fun silhouette(c: Canvas, layers: List<Pair<Layer, Path>>, paint: Paint, extra: Float) {
        for ((layer, p) in layers) {
            if (layer.stroked) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = layer.width + extra
            } else {
                paint.style = if (extra > 0) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
                paint.strokeWidth = extra
            }
            c.drawPath(p, paint)
        }
    }

    /** Ztmavení (f < 1) nebo zesvětlení k bílé (f > 1). */
    private fun tint(color: Int, f: Float): Int {
        fun ch(v: Int) = (if (f >= 1) v + (255 - v) * (f - 1) else v * f).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
    }

    private fun path(d: String): Path = PathParser.createPathFromPathData(d)
}
