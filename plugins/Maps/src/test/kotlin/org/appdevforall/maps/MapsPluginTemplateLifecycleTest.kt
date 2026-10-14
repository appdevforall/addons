package org.appdevforall.maps

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.ResourceManager
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.plugins.templates.CgtTemplateBuilder
import java.io.File
import java.io.InputStream
import java.util.Collections
import org.appdevforall.maps.templates.MapTemplateBuilder
import org.appdevforall.maps.testing.TestAssetManagers
import org.appdevforall.maps.testing.UnusedPluginContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers [MapsPlugin]'s template lifecycle with a template service present: the background
 * registration on activate (success and failure), unregistration on deactivate, and the failure
 * paths of initialize and of lifecycle calls made before initialize.
 */
class MapsPluginTemplateLifecycleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun tearDown() {
        MapsPlugin.pluginContext = null
    }

    private class RecordingLogger(private val failOnInfo: Boolean = false) : PluginLogger {
        val messages: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override val pluginId: String = MapsPlugin.PLUGIN_ID
        override fun debug(message: String) { messages += message }
        override fun debug(message: String, throwable: Throwable) { messages += message }
        override fun info(message: String) {
            if (failOnInfo) throw IllegalStateException("logger offline")
            messages += message
        }
        override fun info(message: String, throwable: Throwable) { messages += message }
        override fun warn(message: String) { messages += "WARN $message" }
        override fun warn(message: String, throwable: Throwable) { messages += "WARN $message" }
        override fun error(message: String) { messages += "ERROR $message" }
        override fun error(message: String, throwable: Throwable) { messages += "ERROR $message" }
    }

    private class Services(private val lookup: () -> Any?) : ServiceRegistry {
        override fun <T : Any?> get(serviceClass: Class<T>): T? = serviceClass.cast(lookup())
        override fun <T : Any?> register(serviceClass: Class<T>, service: T) = throw UnsupportedOperationException()
        override fun <T : Any?> getAll(serviceClass: Class<T>): List<T> = throw UnsupportedOperationException()
        override fun unregister(serviceClass: Class<*>) = throw UnsupportedOperationException()
    }

    private class PluginDir(private val dir: File) : ResourceManager {
        override fun getPluginDirectory(): File = dir
        override fun getPluginFile(path: String): File = File(dir, path)
        override fun getPluginResource(path: String): ByteArray = throw UnsupportedOperationException()
        override fun openPluginResource(path: String): InputStream = throw UnsupportedOperationException()
        override fun openPluginAsset(path: String): InputStream = throw UnsupportedOperationException()
    }

    private class AssetContext(private val assets: AssetManager) : ContextWrapper(null) {
        override fun getAssets(): AssetManager = assets
    }

    private class Ctx(
        override val logger: PluginLogger,
        override val services: ServiceRegistry,
        override val resources: ResourceManager,
        override val androidContext: Context,
    ) : PluginContext by UnusedPluginContext

    private class TemplateService(
        private val failBuild: Boolean = false,
        private val unregisterResult: Boolean = true,
    ) : IdeTemplateService {
        val registered: MutableList<File> = Collections.synchronizedList(mutableListOf())
        val unregistered = mutableListOf<String>()

        override fun createTemplateBuilder(templateName: String): CgtTemplateBuilder {
            if (failBuild) throw IllegalStateException("builder unavailable")
            return CgtTemplateBuilder(templateName)
        }
        override fun registerTemplate(cgtFile: File): Boolean { registered += cgtFile; return true }
        override fun unregisterTemplate(templateName: String): Boolean {
            unregistered += templateName
            return unregisterResult
        }
        override fun isTemplateRegistered(templateName: String): Boolean = throw UnsupportedOperationException()
        override fun getRegisteredTemplates(): List<String> = throw UnsupportedOperationException()
        override fun reloadTemplates(): Unit = throw UnsupportedOperationException()
    }

    private fun context(logger: PluginLogger, lookup: () -> Any?): Ctx = Ctx(
        logger = logger,
        services = Services(lookup),
        resources = PluginDir(tmp.root),
        androidContext = AssetContext(TestAssetManagers.fromDirectory(File("src/main/assets"))),
    )

    /** Bounded poll: registration runs on the plugin's IO scope, so its log line lands asynchronously. */
    private fun awaitMessage(logger: RecordingLogger, needle: String) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            if (logger.messages.toList().any { needle in it }) return
            Thread.sleep(20)
        }
        fail("no log line containing '$needle'; got ${logger.messages}")
    }

    @Test
    fun `activate builds and registers the template into the plugin directory`() {
        val logger = RecordingLogger()
        val svc = TemplateService()
        val plugin = MapsPlugin()
        plugin.initialize(context(logger) { svc })

        assertTrue(plugin.activate())
        awaitMessage(logger, "Registered 1 Maps project template(s)")

        val cgt = svc.registered.single()
        assertEquals(File(tmp.root, "templates/${MapTemplateBuilder.REGION_MAP_TEMPLATE_FILE}"), cgt)
        assertTrue(cgt.isFile)
        plugin.dispose()
    }

    @Test
    fun `a failed template build is logged as a warning, not thrown`() {
        val logger = RecordingLogger()
        val svc = TemplateService(failBuild = true)
        val plugin = MapsPlugin()
        plugin.initialize(context(logger) { svc })

        assertTrue(plugin.activate())
        awaitMessage(logger, "WARN Failed to register Maps templates: builder unavailable")

        assertTrue(svc.registered.isEmpty())
        plugin.dispose()
    }

    @Test
    fun `deactivate unregisters the template file from the host`() {
        val logger = RecordingLogger()
        val svc = TemplateService(unregisterResult = false)
        val plugin = MapsPlugin()
        plugin.initialize(context(logger) { svc })

        assertTrue(plugin.deactivate())

        assertEquals(listOf(MapTemplateBuilder.REGION_MAP_TEMPLATE_FILE), svc.unregistered)
        assertTrue(logger.messages.contains("Unregistered Maps project template: false"))
    }

    @Test
    fun `deactivate still succeeds when the service lookup throws`() {
        val logger = RecordingLogger()
        val plugin = MapsPlugin()
        plugin.initialize(context(logger) { throw IllegalStateException("registry gone") })

        assertTrue(plugin.deactivate())

        assertTrue(
            "got ${logger.messages}",
            logger.messages.contains("WARN Failed to unregister Maps template: registry gone"),
        )
    }

    @Test
    fun `initialize reports failure when the logger throws`() {
        val logger = RecordingLogger(failOnInfo = true)
        val plugin = MapsPlugin()

        assertFalse(plugin.initialize(context(logger) { null }))
        assertEquals(listOf("ERROR MapsPlugin initialize() failed"), logger.messages.toList())
    }

    @Test
    fun `lifecycle and documentation hooks fail loudly before initialize`() {
        val calls: List<Pair<String, (MapsPlugin) -> Unit>> = listOf(
            "activate" to { p -> p.activate() },
            "deactivate" to { p -> p.deactivate() },
            "dispose" to { p -> p.dispose() },
            "onDocumentationInstall" to { p -> p.onDocumentationInstall() },
            "onDocumentationUninstall" to { p -> p.onDocumentationUninstall() },
        )
        for ((name, call) in calls) {
            val thrown = runCatching { call(MapsPlugin()) }.exceptionOrNull()
            assertTrue("$name before initialize threw $thrown", thrown is UninitializedPropertyAccessException)
        }
    }
}
