package org.appdevforall.localstore.server

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt

/**
 * Draws its text inside a thin, rounded box, in the text's own colour, so it follows light and dark mode. Used for
 * a button's name in running text, such as "Start" in "Start will serve…", so it reads as the button above.
 */
class BoxSpan(private val density: Float) : ReplacementSpan() {
    private val padX get() = 4 * density

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.let { paint.getFontMetricsInt(it) }        // the line keeps its normal height
        return (paint.measureText(text, start, end) + 2 * padX).roundToInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val w = paint.measureText(text, start, end); val fm = paint.fontMetrics
        val style = paint.style; val stroke = paint.strokeWidth
        paint.style = Paint.Style.STROKE; paint.strokeWidth = maxOf(1f, 0.75f * density)   // a hairline
        canvas.drawRoundRect(RectF(x + 0.5f, y + fm.ascent - density, x + w + 2 * padX - 0.5f, y + fm.descent + density), 3 * density, 3 * density, paint)
        paint.style = style; paint.strokeWidth = stroke
        canvas.drawText(text, start, end, x + padX, y.toFloat(), paint)
    }
}
