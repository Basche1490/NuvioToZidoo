package io.github.basche1490.nuviotozidoo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** A ring that drains as the countdown runs, with the seconds left in the middle. */
class CountdownRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        color = 0x33FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 30f * density
        isFakeBoldText = true
    }
    private val bounds = RectF()

    /** Fraction of the countdown left, 0 to 1. */
    var fraction: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    var seconds: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = arc.strokeWidth / 2f + 1f
        bounds.set(inset, inset, width - inset, height - inset)
        canvas.drawOval(bounds, track)
        canvas.drawArc(bounds, -90f, 360f * fraction, false, arc)
        val y = height / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(seconds.toString(), width / 2f, y, text)
    }
}
