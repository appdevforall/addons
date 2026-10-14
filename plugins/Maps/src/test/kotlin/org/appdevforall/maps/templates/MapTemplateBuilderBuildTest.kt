package org.appdevforall.maps.templates

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.plugins.templates.CgtTemplateBuilder
import java.io.File
import java.io.FileNotFoundException
import java.util.zip.ZipFile
import org.appdevforall.maps.testing.TestAssetManagers
import org.appdevforall.maps.testing.UnusedPluginContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Runs [MapTemplateBuilder.buildAndRegister] through the host's real [CgtTemplateBuilder] against
 * the plugin's real template assets, and checks what lands in the `.cgt` and what gets registered.
 */
class MapTemplateBuilderBuildTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val assetsRoot = File("src/main/assets")

    private class AssetContext(private val assets: AssetManager) : ContextWrapper(null) {
        override fun getAssets(): AssetManager = assets
    }

    private class AssetPluginContext(assets: AssetManager) : PluginContext by UnusedPluginContext {
        override val androidContext: Context = AssetContext(assets)
    }

    /** Hands out real builders and records what the plugin registers. */
    private class RecordingTemplateService(private val accept: Boolean) : IdeTemplateService {
        val builtNames = mutableListOf<String>()
        val registered = mutableListOf<File>()

        override fun createTemplateBuilder(templateName: String): CgtTemplateBuilder {
            builtNames += templateName
            return CgtTemplateBuilder(templateName)
        }

        override fun registerTemplate(cgtFile: File): Boolean {
            registered += cgtFile
            return accept
        }

        override fun unregisterTemplate(templateName: String): Boolean = throw UnsupportedOperationException()
        override fun isTemplateRegistered(templateName: String): Boolean = throw UnsupportedOperationException()
        override fun getRegisteredTemplates(): List<String> = throw UnsupportedOperationException()
        override fun reloadTemplates(): Unit = throw UnsupportedOperationException()
    }

    private fun zipEntries(file: File): Set<String> =
        ZipFile(file).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }

    @Test
    fun `builds the template from the real assets and registers the cgt`() {
        val svc = RecordingTemplateService(accept = true)
        val outputDir = File(tmp.root, "out/templates")

        val count = MapTemplateBuilder.buildAndRegister(
            AssetPluginContext(TestAssetManagers.fromDirectory(assetsRoot)), svc, outputDir,
        )

        assertEquals(1, count)
        assertEquals(listOf(MapTemplateBuilder.REGION_MAP_TEMPLATE_NAME), svc.builtNames)
        val cgt = svc.registered.single()
        assertTrue("the .cgt is written under the output dir: $cgt", cgt.isFile && cgt.startsWith(outputDir))
        assertEquals(MapTemplateBuilder.REGION_MAP_TEMPLATE_FILE, cgt.name)

        val entries = zipEntries(cgt)
        val root = "OfflineOSMMap/"
        // Pebble files keep their .peb suffix inside the .cgt; static files are verbatim.
        for (peb in listOf(
            "settings.gradle.kts.peb",
            "app/build.gradle.kts.peb",
            "app/src/main/AndroidManifest.xml.peb",
            "app/src/main/java/PACKAGE_NAME/MapRegionActivity.kt.peb",
            "app/src/main/java/PACKAGE_NAME/MapRegionActivity.java.peb",
            "app/src/main/java/PACKAGE_NAME/PmtilesHttpServer.kt.peb",
            "app/src/main/java/PACKAGE_NAME/PmtilesHttpServer.java.peb",
            "app/src/main/res/xml/network_security_config.xml",
            "app/src/main/assets/maps/style.json",
            "app/src/main/assets/maps/fonts/notosans/OFL.txt",
        )) {
            assertTrue("missing $peb", "$root$peb" in entries)
        }
        // Every vendored Maven file listed in the manifest, plus the manifest itself.
        val manifest = File(assetsRoot, "templates/region-map/offline-maplibre-repo/repo-manifest.txt")
            .readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val repoEntries = entries.filter { it.startsWith("${root}offline-maplibre-repo/") }.toSet()
        val expectedRepo = manifest.map { "${root}offline-maplibre-repo/$it" }.toSet() +
            "${root}offline-maplibre-repo/repo-manifest.txt"
        assertEquals(expectedRepo, repoEntries)

        // Static files are copied byte for byte.
        val style = ZipFile(cgt).use { zip ->
            zip.getInputStream(zip.getEntry("${root}app/src/main/assets/maps/style.json")).readBytes()
        }
        assertArrayEquals(
            File(assetsRoot, "templates/region-map/app/src/main/assets/maps/style.json").readBytes(),
            style,
        )
    }

    @Test
    fun `returns zero when the host declines the registration`() {
        val svc = RecordingTemplateService(accept = false)

        val count = MapTemplateBuilder.buildAndRegister(
            AssetPluginContext(TestAssetManagers.fromDirectory(assetsRoot)), svc, File(tmp.root, "out"),
        )

        assertEquals(0, count)
        assertEquals("the built .cgt is still offered to the host", 1, svc.registered.size)
    }

    @Test
    fun `offline repo entries come from the manifest, skipping blank lines and trimming paths`() {
        val repo = "templates/region-map/offline-maplibre-repo"
        val synthetic = mapOf(
            "$repo/repo-manifest.txt" to "  org/x/x.pom  \n\n   \norg/y/y.jar\n".toByteArray(),
            "$repo/org/x/x.pom" to "pom".toByteArray(),
            "$repo/org/y/y.jar" to byteArrayOf(1, 2, 3),
        )
        val assets = TestAssetManagers.create { path ->
            synthetic[path]?.inputStream() ?: File(assetsRoot, path).takeIf { it.isFile }?.inputStream()
        }
        val svc = RecordingTemplateService(accept = true)

        MapTemplateBuilder.buildAndRegister(AssetPluginContext(assets), svc, File(tmp.root, "out"))

        val repoEntries = zipEntries(svc.registered.single())
            .filter { it.startsWith("OfflineOSMMap/offline-maplibre-repo/") }.toSet()
        assertEquals(
            setOf(
                "OfflineOSMMap/offline-maplibre-repo/org/x/x.pom",
                "OfflineOSMMap/offline-maplibre-repo/org/y/y.jar",
                "OfflineOSMMap/offline-maplibre-repo/repo-manifest.txt",
            ),
            repoEntries,
        )
    }

    @Test
    fun `a missing template asset fails the build instead of registering a partial template`() {
        val assets = TestAssetManagers.create { path ->
            if (path.endsWith("MapRegionActivity.kt.peb")) null
            else File(assetsRoot, path).takeIf { it.isFile }?.inputStream()
        }
        val svc = RecordingTemplateService(accept = true)

        val thrown = runCatching {
            MapTemplateBuilder.buildAndRegister(AssetPluginContext(assets), svc, File(tmp.root, "out"))
        }.exceptionOrNull()

        assertTrue("expected FileNotFoundException, got $thrown", thrown is FileNotFoundException)
        assertTrue("nothing may be registered", svc.registered.isEmpty())
    }

    @Test
    fun `an unreadable repo manifest fails the build`() {
        val assets = TestAssetManagers.create { path ->
            if (path.endsWith("repo-manifest.txt")) {
                object : java.io.InputStream() {
                    override fun read(): Int = throw java.io.IOException("manifest unreadable")
                }
            } else {
                File(assetsRoot, path).takeIf { it.isFile }?.inputStream()
            }
        }
        val svc = RecordingTemplateService(accept = true)

        val thrown = runCatching {
            MapTemplateBuilder.buildAndRegister(AssetPluginContext(assets), svc, File(tmp.root, "out"))
        }.exceptionOrNull()

        assertEquals("manifest unreadable", thrown?.message)
        assertTrue(svc.registered.isEmpty())
    }
}
