package org.appdevforall.localstore.server

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** The usual copy symbol, two overlapping rounded sheets, drawn in code: Android has no public copy icon. */
class CopyIcon(private val color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }

    override fun draw(canvas: Canvas) {
        val b = bounds; val s = minOf(b.width(), b.height()).toFloat()
        val left = b.left + (b.width() - s) / 2; val top = b.top + (b.height() - s) / 2
        paint.color = color; paint.strokeWidth = s * 0.09f
        val r = s * 0.08f
        canvas.drawRoundRect(RectF(left + s * 0.34f, top + s * 0.14f, left + s * 0.86f, top + s * 0.70f), r, r, paint)   // the sheet behind
        canvas.drawRoundRect(RectF(left + s * 0.14f, top + s * 0.30f, left + s * 0.66f, top + s * 0.86f), r, r, paint)   // the sheet in front
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
