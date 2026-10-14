package org.appdevforall.maps.templates

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.IdeTemplateService
import com.itsaky.androidide.plugins.templates.CgtTemplateBuilder
import java.io.File

/**
 * Static project template that emits an offline region-map app which compiles and
 * runs out of the box. The emitted app IS the region map: it launches straight to
 * a MapLibre [MapRegionActivity] that renders ONE bundled OpenStreetMap region
 * from a fixed, flat asset layout (`app/src/main/assets/maps/`). The emitted files
 * are bundled as template assets under `src/main/assets/templates/region-map/` (the
 * team convention — see `ndk-installer-plugin`), rendered by the CGT framework
 * rather than emitted as inline Kotlin strings:
 *
 *  - `app/build.gradle.kts` — MapLibre Native + Material; `noCompress("pmtiles")`;
 *    minSdk pinned to 23 (MapLibre 13.x floor).
 *  - `app/src/main/AndroidManifest.xml` — `MapRegionActivity` as the exported
 *    launcher, INTERNET + location permissions, `networkSecurityConfig`.
 *  - `app/src/main/res/layout/activity_map_region.xml` — a MapLibre `MapView` in a
 *    CoordinatorLayout with an empty-state banner and a recenter button (lifted
 *    above the system nav bar at runtime).
 *  - `app/src/main/res/xml/network_security_config.xml` — cleartext to 127.0.0.1
 *    for the in-process loopback PMTiles HTTP server.
 *  - `app/src/main/assets/maps/{style.json, meta.json}` — the style
 *    (with `PMTILES_URL_*` runtime placeholders) and an empty bbox; the activity
 *    surfaces a "no region configured" banner until a region is applied.
 *  - `app/src/main/java/PACKAGE_NAME/MapRegionActivity.{kt,java}` — renders the
 *    bundled region via a loopback HTTP server, forwards lifecycle to `MapView`,
 *    drives the recenter button.
 *  - `app/src/main/java/PACKAGE_NAME/PmtilesHttpServer.{kt,java}` — the loopback
 *    HTTP server that serves the bundled PMTiles to MapLibre.
 *
 * Both Kotlin and Java source variants ship; the CGT `showLanguageOption()` picks
 * which compiles.
 *
 * Region content arrives via the Maps tab, not the template: the user downloads a
 * region (bbox picker → background download) and applies it via "Use in this
 * project", which copies the region's data files (`tiles.pmtiles`,
 * `basemap.pmtiles`, `meta.json`) into the fixed flat
 * `app/src/main/assets/maps/`, overwriting any previous region. Apply is a pure
 * data copy — no code/manifest/Gradle patching (see [ProjectMapEmitter]).
 *
 * Asset-file syntax: `.peb` files are Pebble-rendered at scaffold time and must
 * use the runtime delimiters (`${'$'}{{VAR}}`, `${'$'}{% if %}`); plain files
 * are copied verbatim. The MapLibre dependency version is pinned in
 * `app/build.gradle.kts.peb`.
 */
internal object MapTemplateBuilder {

    const val REGION_MAP_TEMPLATE_NAME = "Offline OSM Map"

    /**
     * The `.cgt` filename the host derives from [REGION_MAP_TEMPLATE_NAME] — it strips
     * every non-alphanumeric char (`CgtTemplateBuilder.DIR_NAME_REGEX`) and appends
     * `.cgt`. Kept in sync here so [unregister] can remove exactly what [buildAndRegister]
     * registered. ("Offline OSM Map" -> "OfflineOSMMap.cgt".)
     */
    val REGION_MAP_TEMPLATE_FILE: String =
        REGION_MAP_TEMPLATE_NAME.replace(Regex("[^\\p{L}\\p{N}]"), "") + ".cgt"

    private const val REGION_MAP_TOOLTIP_TAG = "maps.template.region_map"

    private const val ASSETS_REGION_MAP = "templates/region-map"

    /**
     * Project-local Maven repo bundled with the plugin and copied verbatim into
     * every generated project (as `offline-maplibre-repo/`). It vendors MapLibre
     * 13.1.0 + the androidx/material transitive closure that CoGo's bundled
     * offline `localMvnRepository` lacks, so the emitted app builds with ZERO
     * network on an offline device. The generated `settings.gradle.kts` declares
     * it as `maven { url = uri("${rootDir}/offline-maplibre-repo") }` (the bare
     * `${rootDir}` passes through Pebble untouched — only `${{...}}` is a print tag).
     *
     * The repo's files are enumerated from a baked `repo-manifest.txt` (one
     * repo-relative path per line) so the emitter doesn't have to walk the asset
     * tree recursively at scaffold time. (ADFA-2436 / E13)
     *
     * NOTE (prototype): this repo is pre-generated and committed as a plugin
     * asset. Productionizing it as a Gradle repo-gen task that resolves the
     * template's closure on every plugin build is a tracked followup — see the
     * E13 design doc.
     */
    private const val OFFLINE_REPO_DIR = "offline-maplibre-repo"
    private const val OFFLINE_REPO_MANIFEST = "$OFFLINE_REPO_DIR/repo-manifest.txt"

    // Pebble-rendered files: registered without the `.peb` suffix (the framework
    // appends it); the on-disk asset carries the suffix. Both the Kotlin and Java
    // source variants are registered; showLanguageOption() selects which compiles.
    private val TEMPLATE_FILES = listOf(
        "settings.gradle.kts",
        "build.gradle.kts",
        "README.md",
        "app/build.gradle.kts",
        "app/src/main/AndroidManifest.xml",
        "app/src/main/res/values/strings.xml",
        "app/src/main/java/PACKAGE_NAME/MapRegionActivity.kt",
        "app/src/main/java/PACKAGE_NAME/MapRegionActivity.java",
        "app/src/main/java/PACKAGE_NAME/PmtilesHttpServer.kt",
        "app/src/main/java/PACKAGE_NAME/PmtilesHttpServer.java",
    )

    // Verbatim files (no template substitution).
    private val STATIC_FILES = listOf(
        "gradle.properties",
        "app/src/main/res/values/themes.xml",
        "app/src/main/res/values/colors.xml",
        "app/src/main/res/layout/activity_map_region.xml",
        "app/src/main/res/xml/network_security_config.xml",
        "app/src/main/assets/maps/style.json",
        "app/src/main/assets/maps/meta.json",
        // Glyph fonts for place/street labels — served by the loopback HTTP
        // server (style `glyphs` points at it). Latin-covering Noto Sans ranges;
        // the dir name `notosans` (no space) is the symbol layers' text-font and
        // avoids URL-encoding in the glyph path.
        "app/src/main/assets/maps/fonts/notosans/0-255.pbf",
        "app/src/main/assets/maps/fonts/notosans/256-511.pbf",
        "app/src/main/assets/maps/fonts/notosans/512-767.pbf",
        "app/src/main/assets/maps/fonts/notosans/7680-7935.pbf",
        "app/src/main/assets/maps/fonts/notosans/8192-8447.pbf",
        // SIL OFL 1.1 requires the license + copyright to ship WITH the fonts;
        // emit it into the generated APK alongside the .pbf glyphs (OFL §2).
        "app/src/main/assets/maps/fonts/notosans/OFL.txt",
    )

    /**
     * Build the template into [outputDir] and register it via the supplied
     * [templateService]. Returns the count of successful registrations.
     * Idempotent: building the same template twice overwrites the previous
     * .cgt.
     */
    fun buildAndRegister(
        ctx: PluginContext,
        templateService: IdeTemplateService,
        outputDir: File
    ): Int {
        outputDir.mkdirs()

        val regionMap = templateService.createTemplateBuilder(REGION_MAP_TEMPLATE_NAME)
            .description(
                "Offline OpenStreetMap region map (MapLibre). Create the project, then " +
                    "download a region from the Maps tab and apply it to bundle one region " +
                    "offline. Available in Kotlin and Java."
            )
            .tooltipTag(REGION_MAP_TOOLTIP_TAG)
            .version("0.5.0")
            // Template tile icon in CoGo's New-Project grid (512x512). Without this the
            // tile renders blank. Lives beside the template dir; not emitted into projects.
            .thumbnailFromAssets("$ASSETS_REGION_MAP/thumb.png", ctx)
            .showLanguageOption()
            .let { populateScaffold(it, ctx) }
            .build(outputDir)

        var registered = 0
        if (templateService.registerTemplate(regionMap)) registered++
        return registered
    }

    /**
     * Unregister the Maps project template from the host. Call on plugin
     * `deactivate()` so uninstalling/disabling the plugin doesn't leave an orphaned
     * `.cgt` lingering in CoGo's New-Project grid. Returns true if the host removed it.
     */
    fun unregister(templateService: IdeTemplateService): Boolean =
        templateService.unregisterTemplate(REGION_MAP_TEMPLATE_FILE)

    private fun populateScaffold(
        builder: CgtTemplateBuilder,
        ctx: PluginContext
    ): CgtTemplateBuilder {
        for (dest in TEMPLATE_FILES) {
            builder.addTemplateFromAssets(dest, "$ASSETS_REGION_MAP/$dest.peb", ctx)
        }
        for (dest in STATIC_FILES) {
            builder.addStaticFromAssets(dest, "$ASSETS_REGION_MAP/$dest", ctx)
        }
        addOfflineRepo(builder, ctx)
        return builder
    }

    /**
     * Copy the bundled project-local Maven repo into the generated project as
     * `offline-maplibre-repo/`. Each repo file path is read from the baked
     * `repo-manifest.txt` and added verbatim (binary) at the same relative path.
     * The manifest itself is also emitted, so the generated project carries a
     * self-describing record of what was vendored.
     */
    private fun addOfflineRepo(builder: CgtTemplateBuilder, ctx: PluginContext) {
        val manifest = ctx.androidContext.assets
            .open("$ASSETS_REGION_MAP/$OFFLINE_REPO_MANIFEST")
            .bufferedReader()
            .use { it.readLines() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (rel in manifest) {
            val dest = "$OFFLINE_REPO_DIR/$rel"
            builder.addStaticFromAssets(dest, "$ASSETS_REGION_MAP/$dest", ctx)
        }
        // Emit the manifest too (self-describing vendored set).
        builder.addStaticFromAssets(
            OFFLINE_REPO_MANIFEST,
            "$ASSETS_REGION_MAP/$OFFLINE_REPO_MANIFEST",
            ctx,
        )
    }
}
