package org.appdevforall.customidebuilder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import org.appdevforall.customidebuilder.core.BrandIcons
import org.appdevforall.customidebuilder.core.Density
import org.appdevforall.customidebuilder.core.HostIcons
import org.appdevforall.customidebuilder.core.IconCheck
import org.appdevforall.customidebuilder.core.IconSizes
import org.appdevforall.customidebuilder.core.PixelImage
import org.appdevforall.customidebuilder.core.Silhouette
import java.io.ByteArrayOutputStream
import java.io.File
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
    /** Side of the pictures the look-alike check hashes: room enough to trim a padded icon to its content. */
    private const val HASH_PX = 128
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

    /**
     * Code on the Go's own icon drawn every way it appears, for the look-alike check ([IconCheck.compare] keeps the
     * closest): the `drawable/ic_launcher` vector (full-bleed), `mipmap/ic_launcher` and `ic_launcher_round` as
     * this device resolves them (the adaptive icon with this launcher's mask on Android 8 and later, and its two
     * layers again without the mask), the in-app logo `cogo_brand_mark`, and the legacy launcher PNGs read out of
     * [hostApk]. Each is [HASH_PX] square; a drawing that fails is left out, so the list can be empty.
     */
    fun hostIcons(context: Context, hostPackage: String, hostApk: File): List<PixelImage> {
        // Reading the table takes a moment on an old phone, and the host does not change while the plugin runs.
        val key = "$hostPackage|${hostApk.path}|${hostApk.lastModified()}"
        cachedHostIcons?.let { if (it.first == key) return it.second }
        val out = ArrayList<PixelImage>()
        val resources =
            try {
                context.packageManager.getResourcesForApplication(hostPackage)
            } catch (e: Exception) {
                null
            }
        if (resources != null) {
            for ((type, name) in DRAWN) {
                val id = resources.getIdentifier(name, type, hostPackage)
                if (id == 0) continue
                val drawable =
                    try {
                        @Suppress("DEPRECATION")
                        resources.getDrawable(id, null)
                    } catch (e: Exception) {
                        null
                    } ?: continue
                drawn(drawable)?.let { out.add(it) }
                // The plugin's minSdk is 26, so AdaptiveIconDrawable is always there.
                if (drawable is AdaptiveIconDrawable) {
                    drawn(drawable.background, drawable.foreground)?.let { out.add(it) }
                }
            }
        }
        for (bytes in HostIcons.rasterFiles(hostApk)) decodedForHash(bytes)?.let { out.add(it) }
        if (out.isNotEmpty()) cachedHostIcons = key to out
        return out
    }

    @Volatile
    private var cachedHostIcons: Pair<String, List<PixelImage>>? = null

    /** Resources drawn through the host's Resources, as type to name. */
    private val DRAWN = listOf("drawable" to "ic_launcher", "mipmap" to "ic_launcher", "mipmap" to "ic_launcher_round", "drawable" to "cogo_brand_mark")

    /** [layers] drawn one over another on a transparent [HASH_PX] square, each fitted to it with its aspect kept. */
    private fun drawn(vararg layers: Drawable?): PixelImage? {
        var bitmap: Bitmap? = null
        return try {
            val b = Bitmap.createBitmap(HASH_PX, HASH_PX, Bitmap.Config.ARGB_8888)
            bitmap = b
            val canvas = Canvas(b)
            for (layer in layers) {
                if (layer == null) continue
                layer.bounds = fitted(layer.intrinsicWidth, layer.intrinsicHeight)
                layer.draw(canvas)
            }
            pixelsOf(b)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            bitmap?.recycle()
        }
    }

    /** A PNG, WebP or JPEG from the host APK, fitted to a transparent [HASH_PX] square. */
    private fun decodedForHash(bytes: ByteArray): PixelImage? {
        var decoded: Bitmap? = null
        var square: Bitmap? = null
        return try {
            val d = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            decoded = d
            val s = Bitmap.createBitmap(HASH_PX, HASH_PX, Bitmap.Config.ARGB_8888)
            square = s
            Canvas(s).drawBitmap(d, null, fitted(d.width, d.height), Paint(Paint.FILTER_BITMAP_FLAG))
            pixelsOf(s)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            decoded?.recycle()
            square?.recycle()
        }
    }

    /** The largest rectangle of [width] x [height]'s shape centred in the [HASH_PX] square; the whole square when either is unknown. */
    private fun fitted(width: Int, height: Int): Rect {
        if (width <= 0 || height <= 0) return Rect(0, 0, HASH_PX, HASH_PX)
        val w = if (width >= height) HASH_PX else maxOf(1, HASH_PX * width / height)
        val h = if (height >= width) HASH_PX else maxOf(1, HASH_PX * height / width)
        val left = (HASH_PX - w) / 2
        val top = (HASH_PX - h) / 2
        return Rect(left, top, left + w, top + h)
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
