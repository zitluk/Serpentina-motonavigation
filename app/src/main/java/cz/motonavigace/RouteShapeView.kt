package cz.motonavigace

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import org.mapsforge.core.model.LatLong
import kotlin.math.cos
import kotlin.math.max

/** Obrys projeté trasy bez mapy (detail jízdy a obrázek ke sdílení): start zeleně, cíl červeně. */
class RouteShapeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var points: List<LatLong> = emptyList()
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = MaterialColors.getColor(this@RouteShapeView, androidx.appcompat.R.attr.colorPrimary)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        if (points.size < 2) return
        val pad = 14 * density
        // Rovinná projekce: zeměpisná délka zkrácená podle šířky, ať okruh není šišatý
        val k = cos(Math.toRadians(points.sumOf { it.latitude } / points.size))
        val xs = points.map { it.longitude * k }
        val ys = points.map { it.latitude }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val spanX = max(maxX - minX, 1e-9); val spanY = max(maxY - minY, 1e-9)
        val scale = minOf((width - 2 * pad) / spanX, (height - 2 * pad) / spanY)
        val offX = (width - spanX * scale) / 2
        val offY = (height - spanY * scale) / 2
        fun sx(i: Int) = (offX + (xs[i] - minX) * scale).toFloat()
        fun sy(i: Int) = (height - offY - (ys[i] - minY) * scale).toFloat()
        val path = Path().apply {
            moveTo(sx(0), sy(0))
            for (i in 1 until points.size) lineTo(sx(i), sy(i))
        }
        canvas.drawPath(path, line)
        dot.color = 0xFF43A047.toInt()
        canvas.drawCircle(sx(0), sy(0), 6 * density, dot)
        dot.color = 0xFFE53935.toInt()
        canvas.drawCircle(sx(points.lastIndex), sy(points.lastIndex), 6 * density, dot)
    }
}
