package org.appdevforall.maps

import org.appdevforall.maps.data.IiabArchiveResolver
import org.appdevforall.maps.data.RegionDownloader
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
 *  - **Project flow.** The user creates a project from the Offline OSM Map template, taps
 *    the **Maps** tab, downloads a region (bbox picker → background download →
 *    `/sdcard/CodeOnTheGo/maps/<id>/`), and applies it with the switch on its row, which
 *    copies the region's data files into the project. See [RegionManagerFragment.applyRegionToProject].
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

        /** The tab pill's label; the panel's own heading is R.string.maps_regions_title. */
        const val MAPS_TAB_TITLE = "Maps"

        /** Tooltip tag for the bottom-sheet "Maps" tab. */
        const val TOOLTIP_TAG_MAPS_TAB = "maps.bottom_sheet.maps_tab"

        /** Tooltip tag for the Offline OSM Map card on the New Project screen. */
        const val TOOLTIP_TAG_REGION_MAP_TEMPLATE = "maps.template.region_map"

        /** Tooltip tags for the region list and the four download-wizard screens. */
        const val TOOLTIP_TAG_REGION_LIST = "maps.regions.list"
        const val TOOLTIP_TAG_SOURCE_PICKER = "maps.wizard.source"
        const val TOOLTIP_TAG_BBOX_PICKER = "maps.wizard.bbox"
        const val TOOLTIP_TAG_SAVE_REGION = "maps.wizard.save"
        const val TOOLTIP_TAG_DOWNLOAD_PROGRESS = "maps.wizard.download"

        /** The host registers and looks up plugin tooltips under `plugin_<pluginId>`; any other value renders `n/a`. */
        const val TOOLTIP_CATEGORY = "plugin_$PLUGIN_ID"

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
        // The shared clients outlive this instance, so on unload cancel their queued and running
        // calls (a blocking execute() does not see the scope's cancellation) and close idle sockets.
        for (client in listOf(RegionDownloader.httpClient, IiabArchiveResolver.catalogClient)) {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
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
                // A literal, not a string resource: the host calls this on the main thread, and
                // the first plugin resource read there opens the plugin APK (StrictMode
                // DiskReadViolation in the host's PluginResourceContext).
                title = MAPS_TAB_TITLE,
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

    override fun getTooltipCategory(): String = TOOLTIP_CATEGORY

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        mapsTabEntry(),
        regionMapTemplateEntry(),
        regionListEntry(),
        sourcePickerEntry(),
        bboxPickerEntry(),
        saveRegionEntry(),
        downloadProgressEntry(),
    )

    /**
     * Link to the Tier 3 page, optionally at [anchor]. HTML, not Markdown: the host renders
     * Tier 3 in a WebView and picks the content type from the file extension.
     */
    private fun tutorialButton(anchor: String? = null) = PluginTooltipButton(
        description = "OSM + MapLibre tutorial",
        uri = if (anchor == null) "index.html" else "index.html#$anchor",
        order = 0,
    )

    private fun regionListEntry() = PluginTooltipEntry(
        tag = TOOLTIP_TAG_REGION_LIST,
        summary = "<b>Map regions</b><br>Regions saved on this phone. One can be in the open project.",
        detail = """
            <h3>Map regions</h3>
            <p>Each row is a region saved under <code>/sdcard/CodeOnTheGo/maps/</code>.</p>
            <p>The switch on a row copies that region into the open project and makes it the one
            the app shows; the row then reads <b>Active in this project</b>. Only one region is
            active at a time.</p>
            <p><b>Refresh</b> opens the download steps again with the region's name and area filled in.
            <b>Delete</b> removes it from the phone; projects that already use it keep their copy.</p>
        """.trimIndent(),
        buttons = listOf(tutorialButton("region-list")),
    )

    private fun sourcePickerEntry() = PluginTooltipEntry(
        tag = TOOLTIP_TAG_SOURCE_PICKER,
        summary = "<b>Map source</b><br>Choose the Internet in a Box server to download from.",
        detail = """
            <h3>Map source</h3>
            <p><b>Internet</b> downloads from the public server <code>iiab.switnet.org</code>.</p>
            <p><b>Local Network</b> downloads from an Internet in a Box on your Wi-Fi. Type its
            hostname; the screen shows whether the box answers.</p>
        """.trimIndent(),
        buttons = listOf(tutorialButton("source")),
    )

    private fun bboxPickerEntry() = PluginTooltipEntry(
        tag = TOOLTIP_TAG_BBOX_PICKER,
        summary = "<b>Choose region</b><br>Move and resize the box over the area you need.",
        detail = """
            <h3>Choose region</h3>
            <p>Drag the box to move it and drag a corner to resize it.</p>
            <p>The first line is the download size. Regions over 1 GB cannot be saved.</p>
            <p>The second line shows the box size and the most detailed zoom level the region
            will have. A bigger box gets a lower zoom limit to keep the download small.</p>
        """.trimIndent(),
        buttons = listOf(tutorialButton("choose-region")),
    )

    private fun saveRegionEntry() = PluginTooltipEntry(
        tag = TOOLTIP_TAG_SAVE_REGION,
        summary = "<b>Save region</b><br>Name the region, check the summary, then start the download.",
        detail = """
            <h3>Save region</h3>
            <p>Type a place name. It becomes the region's folder name, in lowercase with
            spaces and punctuation turned into dashes.</p>
            <p>A name another saved region already uses cannot be saved. Pick a new name, or use
            <b>Refresh</b> on that region to download it again.</p>
        """.trimIndent(),
        buttons = listOf(tutorialButton("save-region")),
    )

    private fun downloadProgressEntry() = PluginTooltipEntry(
        tag = TOOLTIP_TAG_DOWNLOAD_PROGRESS,
        summary = "<b>Downloading</b><br>The region is downloading. Cancel discards it.",
        detail = """
            <h3>Downloading</h3>
            <p>The map tiles for your region download from the source you picked. The
            low-zoom world basemap is copied from the plugin, so it needs no download.</p>
            <p><b>Cancel + discard</b> stops the download and deletes the partial region.</p>
        """.trimIndent(),
        buttons = listOf(tutorialButton("downloading")),
    )

    /**
     * Entry for the template card's tag. The host's New Project screen currently looks card
     * tags up under the IDE category (`TemplateListFragment` -> `showIdeCategoryTooltip`), so
     * the card does not find this entry until the host routes plugin templates to their category.
     */
    private fun regionMapTemplateEntry() = PluginTooltipEntry(
        tag = TOOLTIP_TAG_REGION_MAP_TEMPLATE,
        summary = "<b>Offline OSM Map</b><br>An app that shows an OpenStreetMap region with no internet.",
        detail = """
            <h3>Offline OSM Map</h3>
            <p>Creates a MapLibre map app in Kotlin or Java. It starts with no map data.</p>
            <p>After creating the project, open the <b>Maps</b> tab in the editor bottom
            sheet and download a region. The first region you download goes into the project;
            the switch on a region's row changes which one it uses. The region's tiles are
            copied into the app, which then builds and runs offline.</p>
        """.trimIndent(),
        buttons = listOf(tutorialButton()),
    )

    private fun mapsTabEntry() =
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_MAPS_TAB,
            summary = "<b>Maps</b><br>Add OSM tile data to the currently-open project.",
            detail = """
                <h3>Maps</h3>
                <p>Lists cached map regions stored under
                <code>/sdcard/CodeOnTheGo/maps/</code>. A region is reusable
                across multiple projects so you don't re-download the same
                tile pack twice.</p>
                <p>Per region you can turn on its switch (copies the
                region's data files into the open project's
                <code>app/src/main/assets/maps/</code>; the project must come
                from the Offline OSM Map template),
                <b>Refresh</b> (download it again, with its name and area
                filled in), or <b>Delete</b> (free disk space).</p>
                <p>Tap <b>+ Download new region</b> at the bottom to launch
                the bbox picker and create a new entry in the cache.</p>
            """.trimIndent(),
            buttons = listOf(tutorialButton()),
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
     * `PluginTooltipButton` with `uri = "index.html"` resolves to
     * `http://localhost:6174/plugin/org.appdevforall.maps/index.html`.
     * The asset is HTML (not Markdown) so the host WebView renders it formatted
     * rather than as raw text.
     */
    override fun getTier3DocsAssetPath(): String? = "docs"
}
