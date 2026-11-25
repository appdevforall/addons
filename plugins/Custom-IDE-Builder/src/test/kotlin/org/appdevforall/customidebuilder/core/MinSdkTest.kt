package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MinSdkTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun readsANumberFromABinaryManifest() {
        assertEquals(26, (PluginMinSdk.fromManifest(ArscSupport.manifest(26)) as MinSdk.Known).api)
        assertEquals(34, (PluginMinSdk.fromManifest(ArscSupport.manifest(34)) as MinSdk.Known).api)
    }

    @Test
    fun aManifestWithoutAMinimumIsUnspecified() {
        assertTrue(PluginMinSdk.fromManifest(ArscSupport.manifest(null)) is MinSdk.Unspecified)
    }

    @Test
    fun aCodenameIsUnknownNotANumber() {
        val m = PluginMinSdk.fromManifest(ArscSupport.manifestWithCodename("VanillaIceCream"))
        assertTrue(m.toString(), m is MinSdk.Unknown)
        assertEquals("VanillaIceCream", (m as MinSdk.Unknown).raw)
    }

    @Test
    fun readsTheManifestOutOfAnArchive() {
        val f = tmp.newFile("p.cgp")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(ArscSupport.manifest(30)); z.closeEntry()
        }
        assertEquals(30, (PluginMinSdk.fromArchive(f) as MinSdk.Known).api)
    }

    @Test
    fun anArchiveWithoutAReadableManifestGivesNull() {
        val none = tmp.newFile("a.cgp")
        ZipOutputStream(none.outputStream()).use { z -> z.putNextEntry(ZipEntry("x")); z.write(1); z.closeEntry() }
        assertNull(PluginMinSdk.fromArchive(none))
        val bad = tmp.newFile("b.cgp")
        bad.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(PluginMinSdk.fromArchive(bad))
        val garbage = tmp.newFile("c.cgp")
        ZipOutputStream(garbage.outputStream()).use { z -> z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(ByteArray(40) { 9 }); z.closeEntry() }
        assertNull(PluginMinSdk.fromArchive(garbage))
    }

    private fun archiveWithManifest(name: String, size: Int, claimed: Int?): java.io.File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(ByteArray(size)); z.closeEntry()
        }
        if (claimed != null) {
            // Central directory entry: signature 0x02014b50, uncompressed size at offset 24.
            val b = f.readBytes()
            var i = b.size - 4
            while (!(b[i] == 0x50.toByte() && b[i + 1] == 0x4b.toByte() && b[i + 2] == 1.toByte() && b[i + 3] == 2.toByte())) i--
            for (k in 0..3) b[i + 24 + k] = (claimed ushr (8 * k)).toByte()
            f.writeBytes(b)
        }
        return f
    }

    @Test
    fun aManifestOverTheCapIsUnknown() {
        val m = PluginMinSdk.fromArchive(archiveWithManifest("big.cgp", PluginMinSdk.MAX_MANIFEST + 1, null))
        assertTrue(m.toString(), m is MinSdk.Unknown)
    }

    @Test
    fun aManifestThatClaimsASmallSizeButIsLargerIsUnknown() {
        val f = archiveWithManifest("liar.cgp", 3 * PluginMinSdk.MAX_MANIFEST, 100)
        java.util.zip.ZipFile(f).use { assertEquals(100L, it.getEntry("AndroidManifest.xml").size) }
        val m = PluginMinSdk.fromArchive(f)
        assertTrue(m.toString(), m is MinSdk.Unknown)
    }

    @Test
    fun theCapCountsBytesNotTheHeader() {
        assertEquals(10, PluginMinSdk.readCapped(java.io.ByteArrayInputStream(ByteArray(10)), 10)!!.size)
        assertNull(PluginMinSdk.readCapped(java.io.ByteArrayInputStream(ByteArray(11)), 10))
    }

    @Test
    fun warnsOnlyWhenThePluginNeedsANewerAndroidThanTheHost() {
        val w = MinSdkCheck.warnings(
            28,
            listOf(
                "Old" to MinSdk.Known(21),
                "Same" to MinSdk.Known(28),
                "New" to MinSdk.Known(30),
                "None" to MinSdk.Unspecified,
                "Unread" to null,
                "Preview" to MinSdk.Unknown("Zeta"),
            ),
        )
        assertEquals(listOf("New", "Preview"), w.map { it.label })
        val tooNew = w[0] as MinSdkWarning.TooNew
        assertEquals(30, tooNew.required)
        assertTrue(tooNew.message, tooNew.message.contains("Android 11 (API 30)") && tooNew.message.contains("Android 9 (API 28)"))
        val unknown = w[1] as MinSdkWarning.Unknown
        assertTrue(unknown.message, unknown.message.contains("could not read") && unknown.message.contains("Zeta"))
    }

    @Test
    fun namesAVersionBeyondTheTableByItsLevel() {
        assertEquals("Android API 99", AndroidVersions.name(99))
        assertEquals("Android 12L (API 32)", AndroidVersions.name(32))
    }
}
