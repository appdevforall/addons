package org.appdevforall.customidebuilder.core

import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import com.reandroid.arsc.value.ValueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * F50: on Android 9 a plugin with minSdk 33 was refused as "could not read the plugin ID", because
 * PackageManager.getPackageArchiveInfo will not parse an APK for a newer Android. The ID is now read from the
 * binary manifest, which does not depend on the device.
 */
class PluginManifestTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val nameId = 0x01010003
    private val valueId = 0x01010024

    /** A binary manifest with [meta] as application meta-data, in order; a null value is written as a number. */
    private fun manifest(packageName: String? = "org.example.plugin", minSdk: Int? = 33, vararg meta: Pair<String, String?>): ByteArray {
        val m = AndroidManifestBlock()
        if (packageName != null) m.packageName = packageName
        if (minSdk != null) m.setMinSdkVersion(minSdk)
        val application = m.getOrCreateApplicationElement()
        for ((name, value) in meta) {
            val e = application.newElement("meta-data")
            e.getOrCreateAndroidAttribute("name", nameId).setValueAsString(name)
            val v = e.getOrCreateAndroidAttribute("value", valueId)
            if (value != null) {
                v.setValueAsString(value)
            } else {
                v.valueType = ValueType.DEC
                v.data = 7
            }
        }
        m.refreshFull()
        return m.bytes
    }

    private fun cgp(manifest: ByteArray?, name: String = "p.cgp"): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            if (manifest != null) { z.putNextEntry(ZipEntry("AndroidManifest.xml")); z.write(manifest); z.closeEntry() }
            z.putNextEntry(ZipEntry("classes.dex")); z.write(ByteArray(10)); z.closeEntry()
        }
        return f
    }

    @Test
    fun aPluginForANewerAndroidIsIdentifiedAndWarnedAbout() {
        val file = cgp(manifest(minSdk = 33, meta = arrayOf("plugin.id" to "org.appdevforall.aicore", "plugin.main_class" to "org.appdevforall.aicore.Main")))
        val identity = requireNotNull(PluginManifest.fromArchive(file)) { "the plugin ID was not read" }
        assertEquals("org.appdevforall.aicore", identity.pluginId)
        assertEquals("org.appdevforall.aicore.Main", identity.mainClass)
        assertEquals("org.example.plugin", identity.packageName)
        // So the selection accepts it, and the screen shows the warning a device-dependent read never reached.
        val picked = PickedAddon("AI Core", "AI-Core.cgp", file, identity.pluginId)
        assertTrue(AddonSelection.validate(listOf(picked)) is SelectionResult.Valid)
        val w = MinSdkCheck.warnings(28, listOf("AI Core" to PluginMinSdk.fromArchive(file)), deviceApi = 28)
        assertEquals(1, w.size)
        assertEquals(33, (w[0] as MinSdkWarning.TooNew).required)
    }

    @Test
    fun withoutAPluginIdThePackageNameIsTheId() {
        val id = PluginManifest.identity(manifest(meta = arrayOf("plugin.main_class" to "a.B")))!!
        assertEquals("org.example.plugin", id.pluginId)
        // A plugin.id that is not text is what Bundle.getString returns null for: the package name again.
        assertEquals("org.example.plugin", PluginManifest.identity(manifest(meta = arrayOf("plugin.id" to null, "plugin.main_class" to "a.B")))!!.pluginId)
    }

    @Test
    fun aManifestWithoutAMainClassIsNotAPlugin() {
        assertNull(PluginManifest.identity(manifest(meta = arrayOf("plugin.id" to "x"))))
        assertNull(PluginManifest.identity(manifest(meta = arrayOf("plugin.id" to "x", "plugin.main_class" to ""))))
        assertNull(PluginManifest.identity(manifest(meta = arrayOf("plugin.id" to "x", "plugin.main_class" to null))))
        assertNull("no package name and no plugin.id", PluginManifest.identity(manifest(packageName = null, meta = arrayOf("plugin.main_class" to "a.B"))))
    }

    @Test
    fun theLastEntryOfANameWinsAsInTheBundle() {
        val id = PluginManifest.identity(manifest(meta = arrayOf("plugin.id" to "first", "plugin.main_class" to "a.B", "plugin.id" to "second")))!!
        assertEquals("second", id.pluginId)
    }

    @Test
    fun aFileThatIsNotAReadablePluginGivesNull() {
        assertNull(PluginManifest.fromArchive(cgp(null, "none.cgp")))
        assertNull(PluginManifest.fromArchive(cgp(ByteArray(40) { 9 }, "garbage.cgp")))
        assertNull(PluginManifest.fromArchive(cgp(ByteArray(PluginMinSdk.MAX_MANIFEST + 1), "huge.cgp")))
        val notZip = tmp.newFile("x.cgp").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        assertNull(PluginManifest.fromArchive(notZip))
    }

    @Test
    fun theWarningNamesThisDeviceWhenItIsTooOld() {
        val old = MinSdkCheck.warnings(28, listOf("AI Core" to MinSdk.Known(33)), deviceApi = 28).single()
        assertTrue(old.message, old.message.contains("on a device below Android 13 (API 33) AI Core will not work."))
        assertTrue(old.message, old.message.endsWith("This device has Android 9 (API 28)."))
        val fine = MinSdkCheck.warnings(28, listOf("AI Core" to MinSdk.Known(33)), deviceApi = 34).single()
        assertFalse(fine.message, fine.message.contains("This device"))
    }
}
