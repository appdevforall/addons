package org.appdevforall.customidebuilder.core

/** The densities the launcher, foreground, notification and logo pictures are made in, with their sizes. */
enum class Density(val qualifier: String, val dpi: Int) {
    MDPI("mdpi", 160),
    HDPI("hdpi", 240),
    XHDPI("xhdpi", 320),
    XXHDPI("xxhdpi", 480),
    XXXHDPI("xxxhdpi", 640),
    ;

    /** Pixels for a square [dp] wide at this density. */
    fun px(dp: Int): Int = dp * dpi / 160

    companion object {
        /** The generated density nearest to [dpiValue] (a ResTable_config density); a tie goes to the larger. */
        fun nearest(dpiValue: Int, available: Collection<Density>): Density? {
            if (available.isEmpty()) return null
            // 0 (no density), 0xFFFE (anydpi) and 0xFFFF (nodpi) say nothing about size: take the sharpest.
            if (dpiValue <= 0 || dpiValue >= 0xFFFE) return available.maxByOrNull { it.dpi }
            return available.sortedByDescending { it.dpi }.minByOrNull { Math.abs(it.dpi - dpiValue) }
        }
    }
}

/** Sizes in dp of the pictures made from the user's image. */
object IconSizes {
    /** Launcher icon: the whole square. */
    const val LAUNCHER_DP = 48

    /** Adaptive-icon foreground canvas; the picture sits inside the central [FOREGROUND_SAFE_DP]. */
    const val FOREGROUND_DP = 108
    const val FOREGROUND_SAFE_DP = 66

    /** Status-bar icon. */
    const val NOTIFICATION_DP = 24

    /**
     * The in-app logo (`cogo_brand_mark`). Code on the Go shows it at 44 to 200 dp from one entry with no density,
     * which Android reads as mdpi and scales by the screen density. The xxxhdpi file, 320 px, is the one that
     * entry gets: sharp at 150 dp on an xxhdpi screen, and 6.5 MB as a bitmap at most (1280 px on xxxhdpi).
     */
    const val MARK_DP = 80
}

/** Pure pixel work for the icons; the Android layer only decodes and scales bitmaps. */
object Silhouette {
    /**
     * White where the picture is, transparent elsewhere, for the status bar (which tints the icon and ignores colour).
     * A picture with transparency uses its alpha. An opaque one would become a white square, so the area that
     * differs from the colour of its corners is taken as the picture instead. [coverage] is the share of pixels
     * that ended up white, so a caller can tell a useless result (nothing, or everything) from a good one.
     */
    class Result(val argb: IntArray, val coverage: Double, val usedColourDifference: Boolean)

    const val ALPHA_THRESHOLD = 128
    private const val COLOUR_DIFFERENCE = 96
    private const val OPAQUE_SHARE = 0.95

    fun of(image: PixelImage): Result {
        val px = image.argb
        val opaque = px.count { (it ushr 24) >= ALPHA_THRESHOLD }
        val out = IntArray(px.size)
        val byColour = opaque.toDouble() / px.size >= OPAQUE_SHARE
        if (!byColour) {
            for (i in px.indices) if ((px[i] ushr 24) >= ALPHA_THRESHOLD) out[i] = WHITE
        } else {
            val bg = cornerColour(image)
            for (i in px.indices) {
                val c = px[i]
                val diff = Math.abs(((c shr 16) and 0xFF) - bg[0]) + Math.abs(((c shr 8) and 0xFF) - bg[1]) + Math.abs((c and 0xFF) - bg[2])
                if (diff >= COLOUR_DIFFERENCE) out[i] = WHITE
            }
        }
        return Result(out, out.count { it != 0 }.toDouble() / out.size, byColour)
    }

    private const val WHITE = 0xFFFFFFFF.toInt()

    private fun cornerColour(image: PixelImage): IntArray {
        val w = image.width
        val h = image.height
        val corners = intArrayOf(image.argb[0], image.argb[w - 1], image.argb[(h - 1) * w], image.argb[h * w - 1])
        return intArrayOf(
            corners.sumOf { (it shr 16) and 0xFF } / 4,
            corners.sumOf { (it shr 8) and 0xFF } / 4,
            corners.sumOf { it and 0xFF } / 4,
        )
    }
}
