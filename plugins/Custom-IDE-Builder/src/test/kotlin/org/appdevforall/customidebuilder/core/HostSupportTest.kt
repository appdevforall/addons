package org.appdevforall.customidebuilder.core

import com.reandroid.arsc.chunk.TableBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

class HostSupportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun aTableWithTheWebsiteStringIsSupported() {
        assertTrue(HostSupport.hasContactStrings(ArscSupport.tableWithContacts()))
    }

    @Test
    fun aTableWithoutTheWebsiteStringIsNot() {
        assertFalse(HostSupport.hasContactStrings(ArscSupport.table()))
        // The other contact strings are in every Code on the Go; only the website link marks one that can carry them.
        assertFalse(HostSupport.hasContactStrings(ArscSupport.tableWithContacts(without = setOf(HostSupport.CONTACT_MARKER))))
        // A resource of that name but another type does not count.
        val t = TableBlock()
        t.newPackage(0x7f, "x").getOrCreate("", "bool", HostSupport.CONTACT_MARKER).setValueAsBoolean(true)
        t.refreshFull()
        assertFalse(HostSupport.hasContactStrings(t.bytes))
    }

    @Test
    fun somethingThatIsNotATableIsAnErrorNotAVerdict() {
        try {
            HostSupport.hasContactStrings(ByteArray(64) { 7 })
            fail("read garbage as a table")
        } catch (e: BrandingException) {
            assertTrue(e.message, e.message!!.contains("resource table"))
        }
    }

    @Test
    fun theRefusalNamesTheVersionNeededAndTheOneInstalled() {
        assertEquals(
            "This needs Code on the Go ${HostSupport.MIN_IDE_VERSION} or newer, which lets a copy replace App Dev For All's contact details. " +
                "The installed Code on the Go (26.40) is too old.",
            HostSupport.tooOldMessage("26.40"),
        )
        assertTrue(HostSupport.tooOldMessage(null).contains("(version unknown)"))
    }

    private fun apk(arsc: ByteArray?): File {
        val f = tmp.newFile()
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("classes.dex")); z.write(ByteArray(10)); z.closeEntry()
            if (arsc != null) { z.putNextEntry(ZipEntry("resources.arsc")); z.write(arsc); z.closeEntry() }
        }
        return f
    }

    @Test
    fun theApkIsJudgedByItsTable() {
        assertNull(HostSupport.problem(apk(ArscSupport.tableWithContacts()), "27.1"))
        assertEquals(HostSupport.tooOldMessage("26.48"), HostSupport.problem(apk(ArscSupport.table()), "26.48"))
        assertTrue(HostSupport.problem(apk(null), "26.48")!!.contains("resources.arsc"))
        assertTrue(HostSupport.problem(apk(ByteArray(64) { 7 }), "26.48")!!.contains("resource table"))
        val notZip = tmp.newFile().also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(HostSupport.problem(notZip, "26.48")!!.contains("could not be read"))
    }

    @Test
    fun theMinimumVersionIsTheManifests() {
        // Unit tests run in the module folder, where the manifest is.
        val manifest = File("src/main/AndroidManifest.xml")
        assertTrue("${manifest.absolutePath} is missing", manifest.isFile)
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(manifest)
        val android = "http://schemas.android.com/apk/res/android"
        val metas = doc.getElementsByTagName("meta-data")
        val values = (0 until metas.length).map { metas.item(it) as org.w3c.dom.Element }
            .filter { it.getAttributeNS(android, "name") == "plugin.min_ide_version" }
            .map { it.getAttributeNS(android, "value") }
        assertEquals("one plugin.min_ide_version", 1, values.size)
        assertEquals("HostSupport.MIN_IDE_VERSION must equal plugin.min_ide_version", values.single(), HostSupport.MIN_IDE_VERSION)
    }
}
