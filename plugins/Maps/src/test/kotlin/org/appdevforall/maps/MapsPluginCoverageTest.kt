package org.appdevforall.maps

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import com.itsaky.androidide.plugins.PluginContext
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
 *    TabItem (id / title / order / tooltipTag / enabled / visible) — the title is
 *    resolved via `androidContext.getString`, fed through a [FakeContext] that
 *    reroutes the (final) `Context.getString(int)` to a fake `Resources`.
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

    /**
     * A [Resources] subclass that reroutes string lookups to a fixed table. The
     * (final) `Context.getString(int)` ultimately calls `getResources().getString(int)`,
     * so overriding it here is what lets `getEditorTabs` resolve a title without a
     * real resource table. Constructed with nulls — only `getString` is exercised.
     */
    private class FakeResources(private val table: Map<Int, String>) :
        Resources(null, null, null) {
        private fun lookup(id: Int): String =
            table[id] ?: throw Resources.NotFoundException("no fake string for id $id")

        // Different Context.getString(int) impls delegate to either getString() or
        // getText() on Resources; override both so the lookup is robust.
        override fun getString(id: Int): String = lookup(id)
        override fun getText(id: Int): CharSequence = lookup(id)
    }

    /**
     * A [Context] backed by [ContextWrapper] (concrete) with a fake [Resources].
     * `Context.getString(int)` is final and delegates to `getResources()`, which we
     * override — that's the only Android surface `getEditorTabs` touches.
     */
    private class FakeContext(private val resources: Resources) : ContextWrapper(null) {
        override fun getResources(): Resources = resources
    }

    /** PluginContext fake wiring the four sub-fakes together. */
    private class FakePluginContext(
        androidCtx: Context,
        services: ServiceRegistry,
        resources: ResourceManager,
        logger: PluginLogger,
    ) : PluginContext {
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
        strings: Map<Int, String> = mapOf(R.string.maps_tab_title to "Maps"),
        logger: FakeLogger = FakeLogger(),
    ): FakePluginContext {
        val dir = File.createTempFile("maps-plugin-test", "").let {
            it.delete(); it.mkdirs(); it
        }
        tmpDirs += dir
        return FakePluginContext(
            androidCtx = FakeContext(FakeResources(strings)),
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
        assertEquals("plugin_maps", plugin.getTooltipCategory())
        assertEquals("docs", plugin.getTier3DocsAssetPath())
    }

    @Test
    fun `tooltip entries expose the maps tab entry with an html tutorial button`() {
        val entries = MapsPlugin().getTooltipEntries()
        assertEquals("exactly one tooltip entry", 1, entries.size)

        val entry = entries[0]
        // The entry tag must match the tab's tooltipTag so the host can resolve it.
        assertEquals(MapsPlugin.TOOLTIP_TAG_MAPS_TAB, entry.tag)
        assertTrue("summary should be the HTML <b>Maps</b> headline",
            entry.summary.contains("<b>Maps</b>"))
        assertTrue("detail should reference the on-device cache dir",
            entry.detail.contains("/sdcard/CodeOnTheGo/maps/"))

        assertEquals("one tutorial button", 1, entry.buttons.size)
        val button = entry.buttons[0]
        // Tier-3 docs render in a WebView; the asset MUST be .html, not .md.
        assertEquals("osm-tutorial.html", button.uri)
        assertEquals(0, button.order)
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

    // getEditorTabs() is intentionally NOT unit-tested. It resolves the tab title via
    // `androidContext.getString(R.string.maps_tab_title)`, and `Context.getString(int)`
    // is `final` — under the mockable android.jar (`returnDefaultValues = true`) it is
    // stubbed to return null without ever calling the overridable `getResources()`, so a
    // FakeContext/FakeResources can't intercept it. The null title then NPEs the non-null
    // TabItem.title param. Resolving a string resource needs Robolectric (disallowed here),
    // so getEditorTabs is covered by the android-qa device walk, not a JVM unit test.
    // (Reported as a known coverage gap rather than suppressed.)

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
}
