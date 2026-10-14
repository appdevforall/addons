package org.appdevforall.maps.data

import com.itsaky.androidide.plugins.ResourceManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers [MapFontExtractor.extract]: copying every bundled glyph range out of plugin assets,
 * skipping files already cached, and leaving no truncated file behind when an asset is
 * missing or a copy fails.
 */
class MapFontExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val stacks = listOf("Noto Sans Regular", "Noto Sans Italic")
    private val ranges = listOf("0-255", "256-511", "512-767", "7680-7935", "8192-8447")
    private val allAssets = stacks.flatMap { s -> ranges.map { r -> "fonts/$s/$r.pbf" } }

    /** Serves each asset path as its own bytes, unless [streamFor] overrides it; records every open. */
    private class FakeAssets(
        private val streamFor: (String) -> InputStream? = { ByteArrayInputStream(it.toByteArray()) },
    ) : ResourceManager {
        val opened = mutableListOf<String>()
        override fun openPluginAsset(path: String): InputStream? {
            opened += path
            return streamFor(path)
        }
        override fun getPluginDirectory(): File = throw UnsupportedOperationException()
        override fun getPluginFile(path: String): File = throw UnsupportedOperationException()
        override fun getPluginResource(path: String): ByteArray = throw UnsupportedOperationException()
        override fun openPluginResource(path: String): InputStream = throw UnsupportedOperationException()
    }

    private fun fontFile(root: File, asset: String) = File(root, asset.removePrefix("fonts/"))

    @Test
    fun `extracts every bundled range byte for byte under the cache dir`() {
        val assets = FakeAssets()

        val root = MapFontExtractor.extract(assets, tmp.root)

        assertEquals(File(tmp.root, "maps-plugin-fonts"), root)
        assertEquals(allAssets.toSet(), assets.opened.toSet())
        for (asset in allAssets) {
            assertArrayEquals(asset.toByteArray(), fontFile(root!!, asset).readBytes())
        }
    }

    @Test
    fun `a second run reuses cached files but re-extracts an empty one`() {
        val root = MapFontExtractor.extract(FakeAssets(), tmp.root)!!
        val emptied = fontFile(root, allAssets[3])
        emptied.writeBytes(ByteArray(0))
        val again = FakeAssets()

        assertEquals(root, MapFontExtractor.extract(again, tmp.root))

        assertEquals(listOf(allAssets[3]), again.opened)
        assertArrayEquals(allAssets[3].toByteArray(), emptied.readBytes())
    }

    @Test
    fun `a missing asset is skipped and the other ranges still extract`() {
        val missing = "fonts/Noto Sans Italic/512-767.pbf"
        val assets = FakeAssets { if (it == missing) null else ByteArrayInputStream(byteArrayOf(7)) }

        val root = MapFontExtractor.extract(assets, tmp.root)!!

        assertFalse(fontFile(root, missing).exists())
        assertEquals(allAssets.size - 1, allAssets.count { fontFile(root, it).isFile })
    }

    @Test
    fun `a failed copy deletes the partial file`() {
        val broken = "fonts/Noto Sans Regular/0-255.pbf"
        val assets = FakeAssets {
            if (it != broken) ByteArrayInputStream(byteArrayOf(1))
            else object : InputStream() {
                private var sent = false
                override fun read(): Int = throw IOException("asset truncated")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (sent) throw IOException("asset truncated")
                    sent = true
                    b[off] = 9
                    return 1
                }
            }
        }

        val root = MapFontExtractor.extract(assets, tmp.root)!!

        assertFalse("no truncated glyph file may remain", fontFile(root, broken).exists())
        assertTrue(fontFile(root, "fonts/Noto Sans Regular/256-511.pbf").isFile)
    }

    @Test
    fun `returns null when the font root cannot be created`() {
        val notADir = tmp.newFile("cache-is-a-file")

        assertNull(MapFontExtractor.extract(FakeAssets(), notADir))
    }
}
