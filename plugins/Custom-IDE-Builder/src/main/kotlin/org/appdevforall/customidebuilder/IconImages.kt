package org.appdevforall.customidebuilder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import org.appdevforall.customidebuilder.core.BrandIcons
import org.appdevforall.customidebuilder.core.Density
import org.appdevforall.customidebuilder.core.IconSizes
import org.appdevforall.customidebuilder.core.PixelImage
import org.appdevforall.customidebuilder.core.Silhouette
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * What the screen keeps of a chosen picture: a small preview, the pixels the look-alike check hashes, and the PNGs
 * for every density of the icons and the in-app logo (from [IconSizes]). Bitmaps are decoded and scaled here because that needs
 * Android; the pixel logic (silhouette, hashing) is in `core` and tested on a JVM.
 */
class PreparedIcon(
    val preview: Bitmap,
    val hashPixels: PixelImage,
    val icons: BrandIcons,
    /** Share of the notification silhouette that is white: near 0 or near 1 means the picture makes a poor status-bar icon. */
    val silhouetteCoverage: Double,
)

object IconImages {
    private const val MAX_FILE_BYTES = 25 * 1024 * 1024
    private const val WORK_PX = 512
    private const val HASH_PX = 64
    private const val PREVIEW_PX = 192

    /** Decodes [uri], crops it to a centred square and builds everything in [PreparedIcon]. Throws [IOException] with a message for the user. */
    fun prepare(context: Context, uri: Uri): PreparedIcon {
        val source = decodeSquare(context, uri)
        try {
            val big = Bitmap.createScaledBitmap(source, Density.XXXHDPI.px(IconSizes.LAUNCHER_DP), Density.XXXHDPI.px(IconSizes.LAUNCHER_DP), true)
            val launcher = Density.values().associateWith { png(scaled(big, it.px(IconSizes.LAUNCHER_DP))) }
            val foreground = Density.values().associateWith { png(foregroundCanvas(source, it)) }

            val silhouette = Silhouette.of(pixelsOf(scaled(big, Density.XXXHDPI.px(IconSizes.NOTIFICATION_DP))))
            val mask = Bitmap.createBitmap(Density.XXXHDPI.px(IconSizes.NOTIFICATION_DP), Density.XXXHDPI.px(IconSizes.NOTIFICATION_DP), Bitmap.Config.ARGB_8888)
            mask.setPixels(silhouette.argb, 0, mask.width, 0, 0, mask.width, mask.height)
            val notification = Density.values().associateWith { png(scaled(mask, it.px(IconSizes.NOTIFICATION_DP))) }
            // From the 512 px source, not the 192 px launcher: the logo is shown larger than the launcher icon.
            val mark = Density.values().associateWith { png(scaled(source, it.px(IconSizes.MARK_DP))) }

            return PreparedIcon(
                preview = scaled(source, PREVIEW_PX),
                hashPixels = pixelsOf(scaled(source, HASH_PX)),
                icons = BrandIcons(launcher, foreground, notification, mark),
                silhouetteCoverage = silhouette.coverage,
            )
        } finally {
            source.recycle()
        }
    }

    /** Code on the Go's own launcher icon as the user sees it, for the look-alike check; null if it cannot be drawn. */
    fun hostIcon(context: Context, hostPackage: String): PixelImage? =
        try {
            val resources = context.packageManager.getResourcesForApplication(hostPackage)
            val id = resources.getIdentifier("ic_launcher", "drawable", hostPackage)
            if (id == 0) {
                null
            } else {
                @Suppress("DEPRECATION")
                val drawable = resources.getDrawable(id, null)
                val bitmap = Bitmap.createBitmap(WORK_PX / 4, WORK_PX / 4, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                drawable.draw(Canvas(bitmap))
                pixelsOf(scaled(bitmap, HASH_PX)).also { bitmap.recycle() }
            }
        } catch (e: Exception) {
            null
        }

    private fun decodeSquare(context: Context, uri: Uri): Bitmap {
        val bytes = AndroidBits.readBytes(context, uri, MAX_FILE_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("That file is not a picture Android can read.")
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= WORK_PX) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("That file is not a picture Android can read.")
        val side = minOf(decoded.width, decoded.height)
        val cropped = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val square = if (side == WORK_PX) cropped else Bitmap.createScaledBitmap(cropped, WORK_PX, WORK_PX, true)
        if (cropped !== square && cropped !== decoded) cropped.recycle()
        if (decoded !== square) decoded.recycle()
        return square
    }

    /** The adaptive-icon foreground: the picture in the central safe zone of a transparent 108 dp canvas. */
    private fun foregroundCanvas(source: Bitmap, density: Density): Bitmap {
        val canvasPx = density.px(IconSizes.FOREGROUND_DP)
        val picturePx = density.px(IconSizes.FOREGROUND_SAFE_DP)
        val out = Bitmap.createBitmap(canvasPx, canvasPx, Bitmap.Config.ARGB_8888)
        val left = (canvasPx - picturePx) / 2
        Canvas(out).drawBitmap(source, null, Rect(left, left, left + picturePx, left + picturePx), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun scaled(bitmap: Bitmap, px: Int): Bitmap = if (bitmap.width == px) bitmap else Bitmap.createScaledBitmap(bitmap, px, px, true)

    private fun pixelsOf(bitmap: Bitmap): PixelImage {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return PixelImage(px, bitmap.width, bitmap.height)
    }

    private fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
