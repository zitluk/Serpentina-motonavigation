package cz.motonavigace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.annotation.StringRes
import androidx.core.graphics.PathParser
import androidx.core.graphics.createBitmap

/**
 * Tvar ikony polohy na mapě, kreslený shora s předkem nahoře (viewport 48 × 48).
 * [body] se vyplní zvolenou barvou, [dark] jsou tmavé části (kola, helma),
 * [bars] řídítka jako čára.
 */
enum class MarkerShape(
    val id: Int,
    @StringRes val label: Int,
    val body: String,
    val dark: List<String> = emptyList(),
    val bars: String? = null,
) {
    ARROW(0, R.string.marker_arrow, "M24,3L41,43L24,34L7,43Z"),
    SPORT(
        1, R.string.bike_sport,
        "M24,7C28,7 30,12 30,18L29,31C29,37 27,42 24,42C21,42 19,37 19,31L18,18C18,12 20,7 24,7Z",
        dark = listOf(WHEEL_FRONT, WHEEL_REAR, helmet(24f)),
        bars = "M16,17L32,17",
    ),
    TOURING(
        2, R.string.bike_touring,
        "M24,6C30,6 33,11 33,17L32,28L37,30L37,40L11,40L11,30L16,28L15,17C15,11 18,6 24,6Z",
        dark = listOf(WHEEL_FRONT, WHEEL_REAR, helmet(24f)),
        bars = "M12,15L36,15",
    ),
    NAKED(
        3, R.string.bike_naked,
        "M24,12C27,12 29,16 29,21L28,32C28,38 26,42 24,42C22,42 20,38 20,32L19,21C19,16 21,12 24,12ZM24,5.5A3.5,3.5 0,1 1,23.99,5.5Z",
        dark = listOf(WHEEL_FRONT, WHEEL_REAR, helmet(25f)),
        bars = "M10,14L38,14",
    ),
    ADVENTURE(
        4, R.string.bike_adventure,
        "M24,4L27,9L28,20L28,33C28,39 26,44 24,44C22,44 20,39 20,33L20,20L21,9Z",
        dark = listOf(WHEEL_FRONT, WHEEL_REAR, helmet(24f)),
        bars = "M8,15L40,15M8,12L8,18M40,12L40,18",
    ),
    CRUISER(
        5, R.string.bike_cruiser,
        "M24,9C27,9 28,13 28,18L28,26L31,28L31,34L28,36L28,42C28,44 26,45 24,45C22,45 20,44 20,42L20,36L17,34L17,28L20,26L20,18C20,13 21,9 24,9Z",
        dark = listOf(WHEEL_FRONT, WHEEL_REAR, helmet(27f)),
        bars = "M11,8Q15,15 21,14L27,14Q33,15 37,8",
    ),
    SCOOTER(
        6, R.string.bike_scooter,
        "M24,6C31,6 33,14 33,24C33,36 30,43 24,43C18,43 15,36 15,24C15,14 17,6 24,6Z",
        dark = listOf("M22,2H26V7H22Z", "M22,41H26V46H22Z", helmet(27f)),
        bars = "M14,11L34,11",
    );

    companion object {
        fun byId(id: Int) = entries.firstOrNull { it.id == id } ?: ARROW
    }
}

private const val WHEEL_FRONT = "M22,1H26V10H22Z"
private const val WHEEL_REAR = "M22,38H26V47H22Z"
private fun helmet(cy: Float) = "M24,${cy - 4}A4,4 0,1 1,23.99,${cy - 4}Z"

/** Nabízené barvy ikony (ARGB). */
val MARKER_COLORS = listOf(
    0xFFE53935.toInt(), 0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF43A047.toInt(),
    0xFF1E88E5.toInt(), 0xFF8E24AA.toInt(), 0xFF212121.toInt(), 0xFFFAFAFA.toInt(),
)

object MarkerIcons {
    /**
     * Ikona jako bitmapa [sizeDp] × [sizeDp]. Kolem je obrys v kontrastní barvě,
     * aby byla vidět na světlé i tmavé mapě.
     */
    fun bitmap(context: Context, shape: MarkerShape, color: Int, sizeDp: Float = 48f): Bitmap {
        val px = (sizeDp * context.resources.displayMetrics.density).toInt()
        val bmp = createBitmap(px, px)
        val c = Canvas(bmp)
        c.scale(px / 48f, px / 48f)
        val light = Color.luminance(color) > 0.6f
        val outline = if (light) 0xFF212121.toInt() else Color.WHITE
        val darkColor = if (light) 0xFF424242.toInt() else 0xFF212121.toInt()

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val body = path(shape.body)
        val darks = shape.dark.map(::path)
        val bars = shape.bars?.let(::path)

        // 1) obrys všeho
        stroke.color = outline; stroke.strokeWidth = 5f
        (darks + body).forEach { c.drawPath(it, stroke) }
        bars?.let { stroke.strokeWidth = 7f; c.drawPath(it, stroke) }
        // 2) kola a řídítka pod tělem, 3) tělo, 4) helma nad tělem
        fill.color = darkColor
        darks.dropLast(1).forEach { c.drawPath(it, fill) }
        bars?.let { stroke.color = darkColor; stroke.strokeWidth = 3f; c.drawPath(it, stroke) }
        fill.color = color
        c.drawPath(body, fill)
        darks.lastOrNull()?.let {
            fill.color = darkColor; c.drawPath(it, fill)
            stroke.color = outline; stroke.strokeWidth = 1.2f; c.drawPath(it, stroke)
        }
        return bmp
    }

    private fun path(d: String): Path = PathParser.createPathFromPathData(d)
}
