package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** The whole build with a name and icon: the table is rewritten, stored and aligned, the files are in, and it still signs. */
class BrandingPipelineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val minSdk = 26
    private val oldTable = ArscSupport.table()

    private fun host(arsc: ByteArray = oldTable, method: Int = ZipEntry.DEFLATED): File {
        val unsigned = tmp.newFile("host-unsigned.apk")
        TestSupport.syntheticApk(unsigned, mapOf("resources.arsc" to (method to arsc)))
        val signed = tmp.newFile("host.apk")
        Signing.sign(unsigned, signed, TestSupport.signingKey("Host"), minSdk)
        return signed
    }

    private val icons = BrandIcons(
        launcher = Density.values().associateWith { ArscSupport.png("L-${it.qualifier}") },
        foreground = Density.values().associateWith { ArscSupport.png("F-${it.qualifier}") },
        notification = Density.values().associateWith { ArscSupport.png("N-${it.qualifier}") },
        mark = Density.values().associateWith { ArscSupport.png("M-${it.qualifier}") },
    )

    private fun run(branding: Branding?, hostFile: File = host()): BuildOutput {
        val key = TestSupport.signingKey("User")
        return Pipeline.run(BuildInput(hostFile, emptyList(), emptyList(), key, minSdk, tmp.newFolder(), "out.apk", branding))
    }

    @Test
    fun theTableIsStoredAlignedAndCarriesTheNameAndIconsAndTheBuildStillVerifies() {
        val out = run(Branding("My IDE", icons))
        assertTrue(out.verify.verified && out.verify.v2)
        assertEquals(emptyList<String>(), ZipAlignment.misaligned(out.apk))
        RawZipFile(out.apk).use { z ->
            val arsc = z.entries.first { it.name == "resources.arsc" }
            assertTrue("resources.arsc must be stored for Android 11+", arsc.isStored)
            assertEquals(0L, z.dataOffset(arsc) % 4)
            for (name in listOf("res/custom-ide-builder/launcher-xxxhdpi.png", "res/custom-ide-builder/notification-xxxhdpi.png")) {
                val e = z.entries.first { it.name == name }
                assertTrue(e.isStored)
                assertEquals(0L, z.dataOffset(e) % 4)
            }
        }
        ZipFile(out.apk).use { zip ->
            val table = ArscSupport.load(zip.getInputStream(zip.getEntry("resources.arsc")).readBytes())
            assertEquals("My IDE", ArscSupport.strings(table, "string")["string/app_name [fr]"])
            assertEquals("res/custom-ide-builder/launcher-xxxhdpi.png", ArscSupport.strings(table, "drawable")["drawable/ic_launcher []"])
            assertArrayEquals(ArscSupport.png("L-xhdpi"), zip.getInputStream(zip.getEntry("res/custom-ide-builder/launcher-xhdpi.png")).readBytes())
            assertArrayEquals(ArscSupport.png("N-xxxhdpi"), zip.getInputStream(zip.getEntry("res/custom-ide-builder/notification-xxxhdpi.png")).readBytes())
            // The in-app logo is the user's picture too, not Code on the Go's vector.
            assertEquals("res/custom-ide-builder/mark-xxxhdpi.png", ArscSupport.strings(table, "drawable")["drawable/cogo_brand_mark []"])
            assertArrayEquals(ArscSupport.png("M-xxxhdpi"), zip.getInputStream(zip.getEntry("res/custom-ide-builder/mark-xxxhdpi.png")).readBytes())
            assertArrayEquals("untouched entries come through", TestSupport.text(200_000), zip.getInputStream(zip.getEntry("classes.dex")).readBytes())
        }
        assertEquals(oldTable.size < out.apk.length(), true)
        assertEquals(4, out.rewrite.branding!!.appNameEntries)
    }

    @Test
    fun aCompressedHostTableComesOutStored() {
        val out = run(Branding("My IDE", null), host(method = ZipEntry.DEFLATED))
        RawZipFile(out.apk).use { z -> assertTrue(z.entries.first { it.name == "resources.arsc" }.isStored) }
    }

    @Test
    fun noBrandingLeavesTheTableByteForByte() {
        val out = run(null, host(method = ZipEntry.STORED))
        ZipFile(out.apk).use { zip -> assertArrayEquals(oldTable, zip.getInputStream(zip.getEntry("resources.arsc")).readBytes()) }
        assertEquals(null, out.rewrite.branding)
    }

    @Test
    fun aHostWithoutATableFailsWithAClearMessageAndLeavesNoFiles() {
        val unsigned = tmp.newFile("nores-unsigned.apk")
        TestSupport.syntheticApk(unsigned)
        // The synthetic table is noise, not an arsc.
        val signed = tmp.newFile("nores.apk")
        Signing.sign(unsigned, signed, TestSupport.signingKey("H"), minSdk)
        val work = tmp.newFolder("w")
        try {
            Pipeline.run(BuildInput(signed, emptyList(), emptyList(), TestSupport.signingKey("U"), minSdk, work, "x.apk", Branding("My IDE", null)))
            throw AssertionError("accepted")
        } catch (e: BuildException) {
            assertTrue(e.message, e.message!!.contains("name or icon"))
        }
        assertEquals(emptyList<String>(), work.list()!!.toList())
    }
}
