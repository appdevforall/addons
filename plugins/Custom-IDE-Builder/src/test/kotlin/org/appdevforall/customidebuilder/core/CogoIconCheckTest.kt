package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * The look-alike check against Code on the Go's real launcher icon (F51: on device, the app's own
 * mipmap-xxxhdpi/ic_launcher.png and a crop of it were accepted).
 *
 * Fixtures: `cogo-icons/ic_launcher-<density>.png` are copies of
 * resources/src/main/res/mipmap-<density>/ic_launcher.png in appdevforall/CodeOnTheGo (GPL-3.0, as this
 * repository; unchanged on stage at 8302d5a5ef). They show the phone outline with padding on white.
 *
 * The device's references are drawn from vectors, which a JVM cannot render, so they are rebuilt from the
 * xxxhdpi PNG at the geometry the vectors give: `drawable/ic_launcher` puts the 413-unit-tall phone in a
 * 413-unit square (it fills the height), and the adaptive icon draws `ic_launcher_foreground` (the phone at
 * 2.35 x 413 = 970 of 1600 units) with the outer 18 of 108 dp cut off, so the phone is 970 / 1600 x 1.5 = 0.91
 * of the masked icon's height.
 */
class CogoIconCheckTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val densities = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")

    private fun png(name: String): BufferedImage = javaClass.getResourceAsStream("/cogo-icons/$name").use { ImageIO.read(it!!) }

    private fun launcher(density: String) = png("ic_launcher-$density.png")

    private fun pixels(image: BufferedImage): PixelImage =
        PixelImage(image.getRGB(0, 0, image.width, image.height, null, 0, image.width), image.width, image.height)

    /** [src] drawn into a [w] x [h] rectangle at ([x], [y]) of a transparent [n] square, bilinear as Android's filtered scaling. */
    private fun draw(src: BufferedImage, n: Int, x: Int = 0, y: Int = 0, w: Int = n, h: Int = n, fill: Int? = null): BufferedImage {
        val out = BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        if (fill != null) {
            g.color = java.awt.Color(fill, true)
            g.fillRect(0, 0, n, n)
        }
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, x, y, w, h, null)
        g.dispose()
        return out
    }

    /** What IconImages.prepare hashes: the centred square of the picture, scaled to 512 and then to 128. */
    private fun prepared(picture: BufferedImage): PixelImage {
        val side = minOf(picture.width, picture.height)
        val square = picture.getSubimage((picture.width - side) / 2, (picture.height - side) / 2, side, side)
        return pixels(draw(draw(square, 512), 128))
    }

    /** The phone's bounds in the xxxhdpi PNG: every pixel darker than near-white. */
    private fun phone(): BufferedImage {
        val src = launcher("xxxhdpi")
        var top = src.height; var bottom = -1; var left = src.width; var right = -1
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val c = src.getRGB(x, y)
            val bright = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3
            if (bright < 200) { top = minOf(top, y); bottom = maxOf(bottom, y); left = minOf(left, x); right = maxOf(right, x) }
        }
        return src.getSubimage(left, top, right - left + 1, bottom - top + 1)
    }

    /** The phone at [share] of the height of a white disc on a transparent 128 square, centred. */
    private fun onDisc(share: Double): PixelImage {
        val n = 128
        val p = phone()
        val h = (n * share).toInt()
        val w = h * p.width / p.height
        val out = draw(p, n, (n - w) / 2, (n - h) / 2, w, h, fill = 0xFFFFFFFF.toInt())
        for (y in 0 until n) for (x in 0 until n) {
            val dx = x - n / 2 + 0.5; val dy = y - n / 2 + 0.5
            if (dx * dx + dy * dy > (n / 2.0) * (n / 2.0)) out.setRGB(x, y, 0)
        }
        return pixels(out)
    }

    /** `drawable/ic_launcher` as IconImages draws it: the phone filling the height of a white circle. */
    private fun fullBleed() = onDisc(1.0)

    /** `mipmap/ic_launcher` on Android 8 and later: the adaptive icon under a circular mask. */
    private fun adaptiveMasked() = onDisc(0.91)

    /** Every reference this test can rebuild, as IconImages.hostIcons collects them on a device. */
    private fun deviceReferences(): List<PixelImage> =
        listOf(fullBleed(), adaptiveMasked()) + densities.map { pixels(draw(launcher(it), 128)) }

    @Test
    fun theLauncherPngAtEveryDensityMatchesTheIconDrawnFullBleed() {
        // The one reference the device had: before trimming, every density came out about 45 bits away.
        for (d in densities) {
            val v = IconCheck.compare(prepared(launcher(d)), listOf(fullBleed()))
            assertTrue("$d: $v", v is IconVerdict.TooSimilar)
        }
    }

    @Test
    fun theLauncherPngAtEveryDensityMatchesTheMaskedAdaptiveIcon() {
        for (d in densities) {
            val v = IconCheck.compare(prepared(launcher(d)), listOf(adaptiveMasked()))
            assertTrue("$d: $v", v is IconVerdict.TooSimilar)
        }
    }

    @Test
    fun cropsAndRescalesOfTheLauncherPngAreRefused() {
        val src = launcher("xxxhdpi")
        val n = src.width
        val variants = mapOf(
            "centred crop 10" to src.getSubimage(10, 10, n - 20, n - 20),
            "centred crop 25" to src.getSubimage(25, 25, n - 50, n - 50),
            "square crop tight on the phone" to src.getSubimage(27, 28, 136, 136),
            "off-centre crop" to src.getSubimage(0, 0, n - 22, n - 22),
            "wide crop" to src.getSubimage(0, 20, n, n - 40),
            "shrunk and blown up" to draw(draw(src, 40), n),
            "on a coloured backing" to draw(src, n, fill = 0xFFFFF0C8.toInt()).also { img ->
                for (y in 0 until n) for (x in 0 until n) {
                    val c = img.getRGB(x, y)
                    if ((c and 0xFFFFFF) == 0xFFFFFF) img.setRGB(x, y, 0xFFFFF0C8.toInt())
                }
            },
            "the phone alone on a transparent square" to phone().let { p -> draw(p, p.height, (p.height - p.width) / 2, 0, p.width, p.height) },
        )
        val refs = listOf(fullBleed(), adaptiveMasked())
        val accepted = variants.mapNotNull { (what, picture) ->
            val v = IconCheck.compare(prepared(picture), refs)
            if (v is IconVerdict.Different) "$what (${v.distance})" else null
        }
        assertEquals(emptyList<String>(), accepted)
    }

    @Test
    fun aCopyIsRefusedWhenAnyOneReferenceMatches() {
        val unrelated = PixelImage(IntArray(128 * 128) { i -> if ((i / 128 / 16 + i % 128 / 16) % 2 == 0) 0xFF202020.toInt() else 0xFFE0E0E0.toInt() }, 128, 128)
        val cogo = prepared(launcher("xxxhdpi"))
        assertTrue(IconCheck.compare(cogo, listOf(unrelated)) is IconVerdict.Different)
        val v = IconCheck.compare(cogo, listOf(unrelated, pixels(draw(launcher("hdpi"), 128))))
        assertTrue(v.toString(), v is IconVerdict.TooSimilar)
    }

    /** A picture drawn from a seed: a few coloured blocks and discs on a background, like an icon. */
    private fun picture(seed: Long, n: Int = 128): PixelImage {
        val rnd = Random(seed)
        val px = IntArray(n * n)
        px.fill(rnd.nextInt(0xFFFFFF) or (0xFF shl 24))
        repeat(3 + rnd.nextInt(4)) {
            val colour = rnd.nextInt(0xFFFFFF) or (0xFF shl 24)
            val cx = rnd.nextInt(n); val cy = rnd.nextInt(n); val r = n / 8 + rnd.nextInt(n / 3)
            val disc = rnd.nextBoolean()
            for (y in 0 until n) for (x in 0 until n) {
                val inside = if (disc) (x - cx) * (x - cx) + (y - cy) * (y - cy) < r * r else Math.abs(x - cx) < r && Math.abs(y - cy) < r
                if (inside) px[y * n + x] = colour
            }
        }
        return PixelImage(px, n, n)
    }

    /** An icon-like picture on white: a dark outline shape, as Code on the Go's is, but not a phone. */
    private fun outlined(seed: Long, n: Int = 128): PixelImage {
        val rnd = Random(seed)
        val px = IntArray(n * n) { 0xFFFFFFFF.toInt() }
        val colour = rnd.nextInt(0xFFFFFF) and 0x7F7F7F or (0xFF shl 24)
        repeat(1 + rnd.nextInt(3)) {
            val cx = n / 4 + rnd.nextInt(n / 2); val cy = n / 4 + rnd.nextInt(n / 2); val r = n / 8 + rnd.nextInt(n / 4)
            val disc = rnd.nextBoolean()
            for (y in 0 until n) for (x in 0 until n) {
                val dx = x - cx; val dy = y - cy
                val edge = if (disc) Math.abs(Math.sqrt((dx * dx + dy * dy).toDouble()) - r) < 3 else maxOf(Math.abs(dx), Math.abs(dy)) in r - 3..r
                if (edge) px[y * n + x] = colour
            }
        }
        return PixelImage(px, n, n)
    }

    @Test
    fun clearlyDifferentPicturesAreAcceptedAgainstCodeOnTheGosIcon() {
        val refs = deviceReferences()
        val chosen = (1L..150L).map { picture(5000 + it) } + (1L..150L).map { outlined(9000 + it) }
        val refused = chosen.indices.filter { IconCheck.compare(chosen[it], refs) is IconVerdict.TooSimilar }
        assertTrue("${refused.size} of ${chosen.size} unrelated pictures refused: $refused", refused.size.toDouble() / chosen.size < 0.02)
    }

    @Test
    fun unrelatedPicturesRarelyMatchEachOtherWithTrimming() {
        val pictures = (1L..80L).map { picture(1000 + it) }
        var within = 0
        var pairs = 0
        for (i in pictures.indices) for (j in i + 1 until pictures.size) {
            if (IconCheck.compare(pictures[i], listOf(pictures[j])) is IconVerdict.TooSimilar) within++
            pairs++
        }
        assertTrue("$within of $pairs unrelated pairs refused", within.toDouble() / pairs < 0.02)
    }

    @Test
    fun trimmingCutsTheMarginAndKeepsThePictureCentred() {
        val n = 40
        val px = IntArray(n * n) { 0xFFFFFFFF.toInt() }
        for (y in 10 until 30) for (x in 15 until 25) px[y * n + x] = 0xFF000080.toInt()
        val t = IconTrim.of(PixelImage(px, n, n))
        assertEquals(20, t.width)
        assertEquals(20, t.height)
        assertEquals("the margin left and right of a tall picture is the background", 0xFFFFFFFF.toInt(), t.argb[10 * 20 + 2])
        assertEquals(0xFF000080.toInt(), t.argb[10 * 20 + 10])
        // Transparency is a white background, as the hash sees it.
        val clear = IntArray(n * n)
        for (y in 10 until 30) for (x in 15 until 25) clear[y * n + x] = 0xFF000080.toInt()
        assertEquals(IconHash.of(t), IconHash.of(IconTrim.of(PixelImage(clear, n, n))))
    }

    @Test
    fun aPictureWithoutABackgroundIsNotTrimmed() {
        val corners = PixelImage(IntArray(16) { if (it == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }, 4, 4)
        assertTrue(IconTrim.of(corners) === corners)
        val flat = PixelImage(IntArray(64) { 0xFF336699.toInt() }, 8, 8)
        assertTrue(IconTrim.of(flat) === flat)
    }

    @Test
    fun theHostsRasterIconsAreFoundThroughItsResourceTable() {
        val table = com.reandroid.arsc.chunk.TableBlock.load(java.io.ByteArrayInputStream(ArscSupport.table()))
        val p = table.iterator().next()
        p.getOrCreate("hdpi", "drawable", "ic_launcher_round").setValueAsString("res/drawable-hdpi/ic_launcher_round.9.png")
        p.getOrCreate("xxhdpi", "drawable", "cogo_brand_mark").setValueAsString("res/a1.webp")
        p.getOrCreate("xxhdpi", "drawable", "ic_other").setValueAsString("res/other.png")
        table.refreshFull()
        val paths = HostIcons.rasterPaths(table.bytes)
        val expected = densities.flatMap { d ->
            listOf("ic_launcher", "ic_launcher_round", "ic_launcher_foreground").map { "res/mipmap-$d-v4/$it.png" }
        } + "res/a1.webp"
        assertEquals(expected.toSet(), paths.toSet())
        assertEquals("each path once", paths.size, paths.toSet().size)

        val apk = File(tmp.root, "host.apk")
        ZipOutputStream(apk.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("resources.arsc")); z.write(table.bytes); z.closeEntry()
            z.putNextEntry(ZipEntry("res/mipmap-xxxhdpi-v4/ic_launcher.png")); z.write(byteArrayOf(1, 2, 3)); z.closeEntry()
            z.putNextEntry(ZipEntry("res/a1.webp")); z.write(byteArrayOf(4)); z.closeEntry()
        }
        // Entries the table names but the APK lacks are skipped.
        assertEquals(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4)).toSet(), HostIcons.rasterFiles(apk).map { it.toList() }.toSet())
        assertEquals(emptyList<ByteArray>(), HostIcons.rasterFiles(File(tmp.root, "missing.apk")))
    }
}
