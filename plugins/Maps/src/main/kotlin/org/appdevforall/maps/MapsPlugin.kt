package org.appdevforall.maps

import org.appdevforall.maps.ui.RegionManagerFragment
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.UIExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Maps plugin entry point.
 *
 * **Hosting surface — editor bottom-sheet plugin tab.** Maps contributes a single
 * [TabItem] via [UIExtension.getEditorTabs] titled "Maps", alongside built-in tabs
 * (Logcat, Run) and sibling plugin tabs (Forms). The host's `EditorBottomSheet`
 * adds it to the tab rail and expands the sheet when the tab header is tapped.
 *
 *  - **No `EditorTabExtension`.** The main editor tab strip lives alongside file
 *    tabs, which is wrong for a tool opened and closed frequently. The bottom
 *    sheet is the canonical "tools while a project is open" surface.
 *
 *  - **No sidebar entry.** The plugin-api's `IdeEditorTabService` only routes to
 *    main-editor tabs, so a sidebar entry couldn't drive the bottom sheet, and it
 *    would duplicate the tab. If discovery becomes a problem, a sidebar entry
 *    could show a snackbar pointing at the bottom sheet.
 *
 *  - **No plugin Activities.** Plugin APKs load via `DexClassLoader`, so any
 *    `<activity>` declared in the plugin manifest never enters the host's merged
 *    manifest — `Intent(ctx, FooActivity::class.java)` throws
 *    `ActivityNotFoundException`. The bbox picker is therefore a Fragment swapped
 *    inside [RegionManagerFragment]'s child fragment manager, not its own Activity.
 *
 *  - **Project flow.** The user opens any CoGo project, taps the **Maps** tab,
 *    downloads a region (bbox picker → background download →
 *    `/sdcard/CodeOnTheGo/maps/<id>/`), and applies it via "Use in this project",
 *    which copies the region's PMTiles plus the minimal MapLibre
 *    boilerplate into the project. See [RegionManagerFragment.applyRegionToProject].
 */
class MapsPlugin : IPlugin, UIExtension, DocumentationExtension {

    /**
     * Static handle so plugin Fragments hosted under the IDE's Activity can
     * resolve the [com.itsaky.androidide.plugins.services.IdeProjectService]
     * without re-implementing service lookup. Set in [initialize], cleared
     * in [dispose] so a plugin reload doesn't leave a stale reference.
     *
     * One plugin instance at a time; the static is safe.
     */
    companion object {
        const val PLUGIN_ID = "org.appdevforall.maps"

        /**
         * Stable id for the bottom-sheet plugin tab. Used by the host's
         * `EditorBottomSheetTabAdapter` for tab persistence + tooltip-tag
         * lookup. Plugins must keep this id stable across releases — if it
         * ever changes, in-flight saved-state restoration breaks for users
         * who had the tab open at session-end.
         */
        const val MAPS_BOTTOM_SHEET_TAB_ID = "maps_maps_tab"

        /** Tooltip tag for the bottom-sheet "Maps" tab. */
        const val TOOLTIP_TAG_MAPS_TAB = "maps.bottom_sheet.maps_tab"

        @Volatile
        var pluginContext: PluginContext? = null
            internal set
    }

    private lateinit var context: PluginContext

    /**
     * Background scope tied to the plugin's lifecycle. Cancelled in [dispose].
     * SupervisorJob so a single failed coroutine doesn't tear the rest down.
     */
    private val pluginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            pluginContext = context
            context.logger.info("MapsPlugin initialized")
            true
        } catch (e: Exception) {
            context.logger.error("MapsPlugin initialize() failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("MapsPlugin activated (bottom-sheet 'Maps' tab)")
        // Register the OpenStreetMap-backed project template so the user can start
        // a Maps app from CoGo's "Create new project" grid. Builds + registers the
        // .cgt into the plugin's working dir, then hands it to the host.
        registerMapTemplates()
        return true
    }

    private fun registerMapTemplates() {
        val templateService = context.services
            .get(com.itsaky.androidide.plugins.services.IdeTemplateService::class.java)
        if (templateService == null) {
            context.logger.warn("IdeTemplateService unavailable; Map templates not registered")
            return
        }
        // Run off the main thread — .cgt assembly does file I/O + zipping.
        pluginScope.launch {
            try {
                val outputDir = java.io.File(
                    context.resources.getPluginDirectory(),
                    "templates",
                )
                val count = org.appdevforall.maps.templates.MapTemplateBuilder.buildAndRegister(
                    ctx = context,
                    templateService = templateService,
                    outputDir = outputDir,
                )
                context.logger.info("Registered $count Maps project template(s) at $outputDir")
            } catch (t: Throwable) {
                context.logger.warn("Failed to register Maps templates: ${t.message}", t)
            }
        }
    }

    override fun deactivate(): Boolean {
        context.logger.info("MapsPlugin deactivated")
        // Unregister our project template so disabling/uninstalling the plugin doesn't
        // leave an orphaned .cgt lingering in CoGo's New-Project grid. (activate()
        // registers it; symmetric teardown here.)
        runCatching {
            context.services
                .get(com.itsaky.androidide.plugins.services.IdeTemplateService::class.java)
                ?.let { svc ->
                    val removed = org.appdevforall.maps.templates.MapTemplateBuilder.unregister(svc)
                    context.logger.info("Unregistered Maps project template: $removed")
                }
        }.onFailure {
            context.logger.warn("Failed to unregister Maps template: ${it.message}", it)
        }
        return true
    }

    override fun dispose() {
        pluginScope.cancel()
        // Clear the static plugin-context reference so a subsequent reload
        // doesn't leave Fragments resolving services from a defunct context.
        pluginContext = null
        context.logger.info("MapsPlugin disposed")
    }

    // ---------------------------------------------------------------------
    //  UIExtension — bottom-sheet plugin tab.
    // ---------------------------------------------------------------------

    /**
     * Contribute the single entry point: the bottom-sheet plugin tab "Maps"
     * hosting [RegionManagerFragment]. Tab order 200 sits after Forms (150) and
     * the host's built-in tabs (0–7).
     */
    override fun getEditorTabs(): List<TabItem> {
        return listOf(
            TabItem(
                id = MAPS_BOTTOM_SHEET_TAB_ID,
                // The panel content's larger heading uses R.string.maps_regions_title
                // ("Map Regions"); the tab pill itself is just "Maps".
                title = context.androidContext.getString(R.string.maps_tab_title),
                fragmentFactory = { RegionManagerFragment() },
                isEnabled = true,
                isVisible = true,
                order = 200,
                // tooltipTag must match the host's 7-arg TabItem ctor — a plugin
                // built against a 6-arg ABI hits NoSuchMethodError at runtime when
                // the host instantiates the TabItem.
                tooltipTag = TOOLTIP_TAG_MAPS_TAB,
            )
        )
    }

    // ---------------------------------------------------------------------
    //  DocumentationExtension — tooltips for the bottom-sheet tab.
    // ---------------------------------------------------------------------

    override fun getTooltipCategory(): String = "plugin_maps"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = "maps.bottom_sheet.maps_tab",
            summary = "<b>Maps</b><br>Add OSM tile data to the currently-open project.",
            detail = """
                <h3>Maps</h3>
                <p>Lists cached map regions stored under
                <code>/sdcard/CodeOnTheGo/maps/</code>. A region is reusable
                across multiple projects so you don't re-download the same
                tile pack twice.</p>
                <p>Per region you can <b>Use in this project</b> (copies the
                region's PMTiles plus the minimal MapLibre boilerplate into
                the currently-open project's
                <code>app/src/main/assets/maps/&lt;region&gt;/</code>),
                <b>Refresh</b> (re-download the region from source), or
                <b>Delete</b> (free disk space).</p>
                <p>Tap <b>+ Download new region</b> at the bottom to launch
                the bbox picker and create a new entry in the cache.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "OSM + MapLibre tutorial",
                    // HTML, not Markdown: CoGo renders tier-3 docs in a WebView,
                    // which displays Markdown as raw text. The host resolves the
                    // content type from the file extension (.html → text/html), so
                    // the asset MUST be .html to render as a formatted page.
                    uri = "osm-tutorial.html",
                    order = 0
                )
            )
        )
    )

    override fun onDocumentationInstall(): Boolean {
        context.logger.info("MapsPlugin documentation installed")
        return true
    }

    override fun onDocumentationUninstall() {
        context.logger.info("MapsPlugin documentation uninstalled")
    }

    /**
     * Tier 3 docs subdirectory under `src/main/assets/`. The IDE walks this
     * tree at install time and inserts every file into the documentation DB
     * under `plugin/<pluginId>/<relative-path>` (per `Tier3AssetWalker`); a
     * `PluginTooltipButton` with `uri = "osm-tutorial.html"` resolves to
     * `http://localhost:6174/plugin/org.appdevforall.maps/osm-tutorial.html`.
     * The asset is HTML (not Markdown) so the host WebView renders it formatted
     * rather than as raw text.
     */
    override fun getTier3DocsAssetPath(): String? = "docs"
}
