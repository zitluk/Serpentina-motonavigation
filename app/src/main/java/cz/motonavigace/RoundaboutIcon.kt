package cz.motonavigace

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Kruhový objezd v pruhu s pokynem: kruh se šipkou ve směru objíždění a uprostřed číslo výjezdu.
 * Při pravostranném provozu (Česko) se objíždí proti směru hodinových ručiček.
 * @param exit číslo výjezdu, 0 = neznámé (jen kruh)
 * @param clockwise levostranný provoz (BRouter RNLB)
 */
class RoundaboutIcon(private val exit: Int, private val clockwise: Boolean, color: Int) : Drawable() {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = min(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val stroke = size * 0.09f
        val r = size / 2 - stroke * 1.6f
        ring.strokeWidth = stroke
        // Vjezd zespodu, kruh skoro dokola a šipka na konci ve směru jízdy
        val start = 110f
        val sweep = if (clockwise) 320f else -320f
        val mirrorStart = if (clockwise) 70f else start
        canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), mirrorStart, sweep, false, ring)
        val end = Math.toRadians((mirrorStart + sweep).toDouble())
        val px = cx + r * cos(end).toFloat()
        val py = cy + r * sin(end).toFloat()
        // Tečna ve směru objíždění (osa y obrazovky míří dolů)
        val dir = if (clockwise) 1f else -1f
        val tx = -sin(end).toFloat() * dir
        val ty = cos(end).toFloat() * dir
        val head = stroke * 2.2f
        canvas.drawPath(Path().apply {
            moveTo(px + tx * head, py + ty * head)
            lineTo(px - ty * head * 0.8f, py + tx * head * 0.8f)
            lineTo(px + ty * head * 0.8f, py - tx * head * 0.8f)
            close()
        }, fill)
        if (exit > 0) {
            text.textSize = r * 1.15f
            canvas.drawText(exit.toString(), cx, cy - (text.descent() + text.ascent()) / 2, text)
        }
    }

    override fun setAlpha(alpha: Int) {
        ring.alpha = alpha; fill.alpha = alpha; text.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        ring.colorFilter = colorFilter; fill.colorFilter = colorFilter; text.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
