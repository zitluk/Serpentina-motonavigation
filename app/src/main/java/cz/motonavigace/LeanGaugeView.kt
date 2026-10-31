package cz.motonavigace

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Půlkruhový ukazatel náklonu: stupnice ±[RANGE]°, ručička s aktuálním náklonem
 * a červené značky maxim na obou stranách.
 */
class LeanGaugeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private companion object {
        const val RANGE = 60f
    }

    var lean = 0.0
        set(value) { field = value; invalidate() }
    var maxLeft = 0.0
        set(value) { field = value; invalidate() }
    var maxRight = 0.0
        set(value) { field = value; invalidate() }

    private val density = resources.displayMetrics.density
    private val onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
    private val maxColor = 0xFFE53935.toInt()

    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = onSurface; alpha = 70; strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onSurface; strokeWidth = 2 * density }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = onSurface; textAlign = Paint.Align.CENTER }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary; strokeCap = Paint.Cap.ROUND }
    private val maxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = maxColor; strokeCap = Paint.Cap.ROUND }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onSurface; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val arc = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Půlkruh + místo pro číslo pod středem: výška ≈ 0,7 × šířka
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val wanted = (w * 0.7f).roundToInt()
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> h
            MeasureSpec.AT_MOST -> min(wanted, h)
            else -> wanted
        }
        setMeasuredDimension(w, height)
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 16 * density
        val radius = min((width - 2 * pad) / 2, (height - pad) / 1.35f)
        val cx = width / 2f
        val cy = pad + radius
        val stroke = radius * 0.09f

        scalePaint.strokeWidth = stroke
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arc, 270 - RANGE, 2 * RANGE, false, scalePaint)

        // Stupnice po 10°, popisky po 20°
        labelPaint.textSize = radius * 0.11f
        for (deg in -60..60 step 10) {
            val inner = if (deg % 20 == 0) radius - stroke * 1.6f else radius - stroke
            drawRadial(canvas, cx, cy, deg.toFloat(), inner, radius - stroke / 2, tickPaint)
            if (deg % 20 == 0) {
                val r = radius - stroke * 2.8f
                val a = Math.toRadians(deg.toDouble())
                canvas.drawText(abs(deg).toString(), cx + r * Math.sin(a).toFloat(),
                    cy - r * Math.cos(a).toFloat() + labelPaint.textSize / 3, labelPaint)
            }
        }

        // Maxima – tlustá červená značka na vnější hraně
        maxPaint.strokeWidth = stroke * 0.7f
        if (maxLeft > 0.5) drawRadial(canvas, cx, cy, -maxLeft.toFloat().coerceAtMost(RANGE), radius - stroke, radius + stroke * 0.6f, maxPaint)
        if (maxRight > 0.5) drawRadial(canvas, cx, cy, maxRight.toFloat().coerceAtMost(RANGE), radius - stroke, radius + stroke * 0.6f, maxPaint)

        // Ručička ze středu, natočená jako motorka
        needlePaint.strokeWidth = stroke * 0.55f
        drawRadial(canvas, cx, cy, lean.toFloat().coerceIn(-RANGE - 5, RANGE + 5), 0f, radius - stroke * 1.2f, needlePaint)
        canvas.drawCircle(cx, cy, stroke * 0.8f, needlePaint)

        valuePaint.textSize = radius * 0.32f
        val value = "${abs(lean).roundToInt()}° ${LeanMeter.side(lean)}".trim()
        canvas.drawText(value, cx, cy + valuePaint.textSize * 1.05f, valuePaint)
    }

    /** Úsečka na paprsku pod úhlem [deg] od svislice (kladně doprava). */
    private fun drawRadial(c: Canvas, cx: Float, cy: Float, deg: Float, r1: Float, r2: Float, p: Paint) {
        val a = Math.toRadians(deg.toDouble())
        val sx = Math.sin(a).toFloat(); val sy = -Math.cos(a).toFloat()
        c.drawLine(cx + r1 * sx, cy + r1 * sy, cx + r2 * sx, cy + r2 * sy, p)
    }
}
