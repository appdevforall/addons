package org.appdevforall.shellhooks.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.view.View
import android.widget.TextView

/**
 * The number column beside one line of a script under review. The first row of the line shows its
 * number, `!` in front when a check above names the line; every row the text wraps onto shows
 * [CONTINUED], so a wrap never passes for a new line.
 *
 * It draws at [text]'s own baselines rather than laying out text of its own, so each mark sits on
 * its row whatever font the mark falls back to, and the column never changes a row's height. It
 * reads [text]'s layout only when drawing, so it never asks for another layout pass.
 */
internal class LineGutter(
    context: Context,
    private val text: TextView,
    private val digits: Int,
) : View(context) {
    private val paint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            textSize = text.textSize
        }
    private val gap = text.textSize * 0.6f
    private var label = ""

    init {
        // A new layout of the text (rebinding, a width change) needs the marks drawn again.
        text.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> invalidate() }
    }

    /** Shows line [number]; [marked] when a check above names it. */
    fun bind(
        number: Int,
        marked: Boolean,
        color: Int,
        description: String,
    ) {
        label = (if (marked) "!" else "") + number
        paint.color = color
        contentDescription = description
        invalidate()
    }

    override fun onMeasure(
        widthMeasureSpec: Int,
        heightMeasureSpec: Int,
    ) {
        val widest = maxOf(paint.measureText("!" + "8".repeat(digits)), paint.measureText(CONTINUED))
        val width = (widest + gap).toInt() + 1 + paddingLeft + paddingRight
        // As tall as the row: the parent gives this column the text's height.
        setMeasuredDimension(width, MeasureSpec.getSize(heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val layout = text.layout ?: return
        // Both sit in the same row, so the text's offset within it is the difference of their tops.
        val dy = (text.top - top + text.totalPaddingTop).toFloat()
        val right = width - paddingRight - gap
        for (row in 0 until layout.lineCount) {
            val shown = if (row == 0) label else CONTINUED
            canvas.drawText(shown, right - paint.measureText(shown), dy + layout.getLineBaseline(row), paint)
        }
    }

    companion object {
        /** Marks a row that continues the line above it. */
        const val CONTINUED = "↳"
    }
}
