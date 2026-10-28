package org.appdevforall.maps

import android.content.Context
import android.content.ContextWrapper
import com.itsaky.androidide.plugins.PluginContext
import org.appdevforall.maps.data.IiabArchiveResolver
import org.appdevforall.maps.data.RegionDownloader
import org.appdevforall.maps.testing.UnusedPluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.ResourceManager
import com.itsaky.androidide.plugins.ServiceRegistry
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

/**
 * Coverage for [MapsPlugin]'s host-facing surface using hand-written fakes of the
 * plugin-api host interfaces — there is no mock framework available in this module,
 * so the established pattern is to implement only the members the code under test
 * calls.
 *
 * What's covered on a plain JVM:
 *  - Companion constants ([MapsPlugin.PLUGIN_ID], tab id, tooltip tag).
 *  - The [com.itsaky.androidide.plugins.extensions.DocumentationExtension] surface:
 *    `getTooltipCategory`, `getTier3DocsAssetPath`, `getTooltipEntries` shape,
 *    `onDocumentationInstall` / `onDocumentationUninstall`.
 *  - The [com.itsaky.androidide.plugins.extensions.UIExtension] `getEditorTabs`
 *    TabItem (id / title / order / tooltipTag / enabled / visible), built with no
 *    resource read.
 *  - The lifecycle: `initialize` (sets the static `pluginContext`), `activate`
 *    (no template service → logs + returns true), `deactivate` (no service → no-op
 *    true), and `dispose` (cancels the scope + clears the static `pluginContext`).
 *
 * The `registerMapTemplates` background coroutine body (`MapTemplateBuilder.buildAndRegister`)
 * is NOT asserted: it needs a real `AssetManager` and the host's zip internals, and
 * it runs async on `Dispatchers.IO` so its outcome isn't deterministically observable
 * here. The null-service early-return branch IS exercised. See `skipped`.
 */
class MapsPluginCoverageTest {

    @After
    fun tearDown() {
        // Each test that calls initialize() sets the global static; reset between
        // tests so ordering can't leak a stale context.
        MapsPlugin.pluginContext = null
    }

    // ----------------------------------------------------------------------
    //  Hand-written host fakes
    // ----------------------------------------------------------------------

    /** Captures log calls so a test can assert a path logged (not just ran). */
    private class FakeLogger : PluginLogger {
        val messages = mutableListOf<String>()
        override val pluginId: String = "org.appdevforall.maps"
        override fun debug(message: String) { messages += message }
        override fun debug(message: String, throwable: Throwable) { messages += message }
        override fun info(message: String) { messages += message }
        override fun info(message: String, throwable: Throwable) { messages += message }
        override fun warn(message: String) { messages += message }
        override fun warn(message: String, throwable: Throwable) { messages += message }
        override fun error(message: String) { messages += message }
        override fun error(message: String, throwable: Throwable) { messages += message }
    }

    /**
     * Service registry that returns a configurable value for `get` — the plugin
     * only ever asks for `IdeTemplateService`. Returning null drives the
     * "service unavailable" early-return branches in `activate` / `deactivate`.
     */
    private class FakeServiceRegistry(private val service: Any? = null) : ServiceRegistry {
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> get(serviceClass: Class<T>): T? = service as T?
        override fun <T : Any?> register(serviceClass: Class<T>, service: T) =
            throw UnsupportedOperationException("not used")
        override fun <T : Any?> getAll(serviceClass: Class<T>): List<T> =
            throw UnsupportedOperationException("not used")
        override fun unregister(serviceClass: Class<*>) =
            throw UnsupportedOperationException("not used")
    }

    /** ResourceManager whose plugin dir is a real temp dir so any file use is safe. */
    private class FakeResourceManager(private val dir: File) : ResourceManager {
        override fun getPluginDirectory(): File = dir
        override fun getPluginFile(path: String): File = File(dir, path)
        override fun getPluginResource(path: String): ByteArray =
            throw UnsupportedOperationException("not used")
        override fun openPluginResource(path: String): InputStream =
            throw UnsupportedOperationException("not used")
        override fun openPluginAsset(path: String): InputStream =
            throw UnsupportedOperationException("not used")
    }

    /** A [Context] with no base, so any resource read through it fails the test. */
    private class FakeContext : ContextWrapper(null)

    /** PluginContext fake wiring the four sub-fakes together. */
    private class FakePluginContext(
        androidCtx: Context,
        services: ServiceRegistry,
        resources: ResourceManager,
        logger: PluginLogger,
    ) : PluginContext by UnusedPluginContext {
        override val androidContext: Context = androidCtx
        override val services: ServiceRegistry = services
        override val eventBus: Any get() = throw UnsupportedOperationException("not used")
        override val logger: PluginLogger = logger
        override val resources: ResourceManager = resources
        override val pluginId: String = "org.appdevforall.maps"
    }

    // ----------------------------------------------------------------------
    //  Helpers
    // ----------------------------------------------------------------------

    private val tmpDirs = mutableListOf<File>()

    private fun newContext(
        service: Any? = null,
        logger: FakeLogger = FakeLogger(),
    ): FakePluginContext {
        val dir = File.createTempFile("maps-plugin-test", "").let {
            it.delete(); it.mkdirs(); it
        }
        tmpDirs += dir
        return FakePluginContext(
            androidCtx = FakeContext(),
            services = FakeServiceRegistry(service),
            resources = FakeResourceManager(dir),
            logger = logger,
        )
    }

    @After
    fun cleanupTmp() {
        tmpDirs.forEach { it.deleteRecursively() }
        tmpDirs.clear()
    }

    // ----------------------------------------------------------------------
    //  Companion constants
    // ----------------------------------------------------------------------

    @Test
    fun `companion exposes the stable plugin id, tab id and tooltip tag`() {
        assertEquals("org.appdevforall.maps", MapsPlugin.PLUGIN_ID)
        assertEquals("maps_maps_tab", MapsPlugin.MAPS_BOTTOM_SHEET_TAB_ID)
        assertEquals("maps.bottom_sheet.maps_tab", MapsPlugin.TOOLTIP_TAG_MAPS_TAB)
    }

    // ----------------------------------------------------------------------
    //  DocumentationExtension surface
    // ----------------------------------------------------------------------

    @Test
    fun `tooltip category and tier3 docs path are the documented constants`() {
        val plugin = MapsPlugin()
        // The host derives "plugin_" + the full plugin.id on lookup; a short slug renders n/a.
        assertEquals("plugin_org.appdevforall.maps", plugin.getTooltipCategory())
        assertEquals("docs", plugin.getTier3DocsAssetPath())
    }

    @Test
    fun `tooltip entries expose the maps tab entry with an html tutorial button`() {
        val entries = MapsPlugin().getTooltipEntries()
        val entry = entries.single { it.tag == MapsPlugin.TOOLTIP_TAG_MAPS_TAB }
        // The entry tag must match the tab's tooltipTag so the host can resolve it.
        assertEquals(MapsPlugin.TOOLTIP_TAG_MAPS_TAB, entry.tag)
        assertTrue("summary should be the HTML <b>Maps</b> headline",
            entry.summary.contains("<b>Maps</b>"))
        assertTrue("detail should reference the on-device cache dir",
            entry.detail.contains("/sdcard/CodeOnTheGo/maps/"))

        assertEquals("one tutorial button", 1, entry.buttons.size)
        val button = entry.buttons[0]
        // Tier-3 docs render in a WebView; the asset MUST be .html, not .md.
        assertEquals("index.html", button.uri)
        assertEquals(0, button.order)
    }

    @Test
    fun `every tooltip tag the plugin attaches to UI has a registered entry`() {
        val registered = MapsPlugin().getTooltipEntries().map { it.tag }.toSet()
        // The tab, the template card and each screen carry a tag; an unregistered tag renders n/a.
        assertEquals(
            setOf(
                MapsPlugin.TOOLTIP_TAG_MAPS_TAB,
                MapsPlugin.TOOLTIP_TAG_REGION_MAP_TEMPLATE,
                MapsPlugin.TOOLTIP_TAG_REGION_LIST,
                MapsPlugin.TOOLTIP_TAG_SOURCE_PICKER,
                MapsPlugin.TOOLTIP_TAG_BBOX_PICKER,
                MapsPlugin.TOOLTIP_TAG_SAVE_REGION,
                MapsPlugin.TOOLTIP_TAG_DOWNLOAD_PROGRESS,
            ),
            registered,
        )
        val template = MapsPlugin().getTooltipEntries()
            .single { it.tag == MapsPlugin.TOOLTIP_TAG_REGION_MAP_TEMPLATE }
        assertTrue(template.summary.contains("Offline OSM Map"))
        assertEquals("index.html", template.buttons.single().uri)
    }

    /**
     * The screens are not JVM-testable, so this reads their source: each of the five must wire a
     * tag, and every tag referenced anywhere in the plugin must have an entry.
     */
    @Test
    fun `every tooltip tag a screen wires has a registered entry`() {
        val sourceRoot = File("src/main/kotlin/org/appdevforall/maps")
        val tagRef = Regex("""MapsPlugin\.(TOOLTIP_TAG_[A-Z_]+)""")
        fun tagsIn(file: File) = tagRef.findAll(file.readText()).map { it.groupValues[1] }.toSet()

        val screens = listOf(
            "RegionManagerFragment", "SourcePickerFragment", "BboxPickerFragment",
            "Step3SaveFragment", "DownloadProgressFragment",
        )
        for (screen in screens) {
            val file = File(sourceRoot, "ui/$screen.kt")
            assertTrue("$file must exist", file.isFile)
            assertTrue("$screen wires no tooltip tag", tagsIn(file).isNotEmpty())
        }

        val sources = sourceRoot.walk().filter { it.extension == "kt" }.toList()
        assertTrue("scanned ${sources.size} files", sources.size > 20)
        val referenced = sources.flatMap(::tagsIn).toSet()
            .associateWith { MapsPlugin::class.java.getField(it).get(null) as String }
        val registered = MapsPlugin().getTooltipEntries().map { it.tag }.toSet()
        assertEquals(
            "tags with no entry",
            emptyMap<String, String>(),
            referenced.filterValues { it !in registered },
        )
    }

    @Test
    fun `every tutorial link opens a page and anchor that ship in the docs folder`() {
        val docsDir = File("src/main/assets/docs")
        val buttons = MapsPlugin().getTooltipEntries().flatMap { it.buttons }
        assertTrue("entries carry tutorial buttons", buttons.size >= 7)
        for (button in buttons) {
            val page = button.uri.substringBefore('#')
            val file = File(docsDir, page)
            assertTrue("${button.uri}: $file must exist", file.isFile)
            val anchor = button.uri.substringAfter('#', "")
            if (anchor.isNotEmpty()) {
                assertTrue("${button.uri}: no id=\"$anchor\" in $page", file.readText().contains("id=\"$anchor\""))
            }
        }
    }

    /** The page is served from documentation.db offline, so an image must ship beside it. */
    @Test
    fun `every image the tutorial shows ships in the docs folder and stays small`() {
        val docsDir = File("src/main/assets/docs")
        val sources = Regex("""<img src="([^"]+)"""").findAll(File(docsDir, "index.html").readText())
            .map { it.groupValues[1] }.toList()
        assertTrue("tutorial embeds screenshots", sources.size >= 4)
        for (src in sources) {
            val file = File(docsDir, src)
            assertTrue("$src: $file must exist", file.isFile)
            assertTrue("$src is ${file.length()} bytes, over 150 KB", file.length() <= 150L * 1024)
        }
    }

    @Test
    fun `documentation install returns true and uninstall is a no-op that runs`() {
        val plugin = MapsPlugin()
        val logger = FakeLogger()
        plugin.initialize(newContext(logger = logger))

        assertTrue("install hook should report success", plugin.onDocumentationInstall())
        plugin.onDocumentationUninstall() // must not throw
        assertTrue(
            "both doc hooks should have logged",
            logger.messages.any { it.contains("documentation installed") } &&
                logger.messages.any { it.contains("documentation uninstalled") },
        )
    }

    // ----------------------------------------------------------------------
    //  UIExtension — getEditorTabs
    // ----------------------------------------------------------------------

    @Test
    fun `getEditorTabs builds the Maps tab without reading a plugin resource`() {
        val plugin = MapsPlugin()
        // FakeContext has no resources: a getString call returns null here and fails the TabItem.
        plugin.initialize(newContext())

        val tab = plugin.getEditorTabs().single()

        assertEquals("Maps", tab.title)
        assertEquals(MapsPlugin.MAPS_BOTTOM_SHEET_TAB_ID, tab.id)
        assertEquals(MapsPlugin.TOOLTIP_TAG_MAPS_TAB, tab.tooltipTag)
        assertEquals(200, tab.order)
        assertTrue(tab.isEnabled && tab.isVisible)
    }

    // ----------------------------------------------------------------------
    //  Lifecycle
    // ----------------------------------------------------------------------

    @Test
    fun `initialize sets the static plugin context and returns true`() {
        val plugin = MapsPlugin()
        val ctx = newContext()

        assertNull("static should be clear before initialize", MapsPlugin.pluginContext)
        val ok = plugin.initialize(ctx)

        assertTrue("initialize should succeed", ok)
        assertSame("the static must point at the supplied context", ctx, MapsPlugin.pluginContext)
    }

    @Test
    fun `activate without a template service logs a warning and still returns true`() {
        val plugin = MapsPlugin()
        val logger = FakeLogger()
        plugin.initialize(newContext(service = null, logger = logger))

        val ok = plugin.activate()

        assertTrue("activate should succeed even if templates can't register", ok)
        assertTrue(
            "the missing-service branch should warn",
            logger.messages.any { it.contains("IdeTemplateService unavailable") },
        )
    }

    @Test
    fun `deactivate without a template service is a no-op that returns true`() {
        val plugin = MapsPlugin()
        plugin.initialize(newContext(service = null))

        assertTrue("deactivate should report success", plugin.deactivate())
    }

    @Test
    fun `dispose clears the static plugin context`() {
        val plugin = MapsPlugin()
        plugin.initialize(newContext())
        assertNotNull("initialize should have set the static", MapsPlugin.pluginContext)

        plugin.dispose()

        assertNull("dispose must clear the static so a reload starts clean", MapsPlugin.pluginContext)
    }

    @Test
    fun `dispose after initialize does not throw and leaves a reusable plugin`() {
        val plugin = MapsPlugin()
        plugin.initialize(newContext())
        plugin.dispose()
        // A second initialize must re-arm the static — proves dispose() didn't
        // wedge any non-recreatable state.
        val ctx2 = newContext()
        assertTrue(plugin.initialize(ctx2))
        assertSame(ctx2, MapsPlugin.pluginContext)
    }

    @Test
    fun `dispose closes the idle connections of both shared http clients`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        repeat(2) { server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("ok")) }
        server.start()
        try {
            val clients = listOf(RegionDownloader.httpClient, IiabArchiveResolver.catalogClient)
            // The pools are process-wide, so drop what earlier tests left before counting.
            clients.forEach { it.connectionPool.evictAll() }
            for (client in clients) {
                client.newCall(okhttp3.Request.Builder().url(server.url("/")).build()).execute().use { it.body!!.string() }
            }
            assertEquals("each client keeps its connection alive", listOf(1, 1), clients.map { it.connectionPool.idleConnectionCount() })

            val plugin = MapsPlugin()
            plugin.initialize(newContext())
            plugin.dispose()

            assertEquals(listOf(0, 0), clients.map { it.connectionPool.idleConnectionCount() })
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `dispose cancels calls still in flight on both shared http clients`() {
        val server = okhttp3.mockwebserver.MockWebServer()
        // The server never answers, so each call is still running when dispose() runs.
        repeat(2) {
            server.enqueue(
                okhttp3.mockwebserver.MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE)
            )
        }
        server.start()
        try {
            val clients = listOf(RegionDownloader.httpClient, IiabArchiveResolver.catalogClient)
            val failures = java.util.concurrent.ConcurrentLinkedQueue<String>()
            val done = java.util.concurrent.CountDownLatch(clients.size)
            val calls = clients.map { client ->
                client.newCall(okhttp3.Request.Builder().url(server.url("/")).build()).also { call ->
                    call.enqueue(object : okhttp3.Callback {
                        override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                            failures += e.message.orEmpty()
                            done.countDown()
                        }

                        override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                            response.close()
                            done.countDown()
                        }
                    })
                }
            }
            repeat(2) { server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS) }

            val plugin = MapsPlugin()
            plugin.initialize(newContext())
            plugin.dispose()

            assertTrue("both calls end well before the 5 s timeouts", done.await(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(listOf(true, true), calls.map { it.isCanceled() })
            assertEquals(listOf("Canceled", "Canceled"), failures.toList())
        } finally {
            server.shutdown()
        }
    }
}
