package org.appdevforall.maps.templates

import org.appdevforall.maps.util.AtomicFiles
import java.io.File

/**
 * Apply-path engine: bundles ONE downloaded region into an open Maps project as a
 * pure **data copy**.
 *
 * The emitted Maps project (scaffolded by [MapTemplateBuilder]) already ships the
 * `MapRegionActivity`, the loopback `PmtilesHttpServer`, the layout, the style,
 * the manifest, and the Gradle wiring. So applying a region is just copying its
 * three data files into the project's fixed, flat asset location, overwriting any
 * previously-applied region:
 *
 *   app/src/main/assets/maps/tiles.pmtiles
 *   app/src/main/assets/maps/basemap.pmtiles
 *   app/src/main/assets/maps/meta.json
 *
 * **No code injection, no manifest/Gradle patching, no style.json write, no
 * active/region-id markers, no per-region subdir, no pruning.** Those all live in
 * the template now.
 *
 * **Require a Maps project.** Apply fails if the project has no `MapRegionActivity`
 * (`.kt` or `.java`) under `app/src/main` — there's nothing to feed the data to.
 * The caller surfaces the "create a Maps project first" reason.
 *
 * **Safety.** Canonical-path containment guards keep the copy under the project's
 * assets dir and the source under the cache root. The copy is atomic per file and
 * cleans up a partial write on failure.
 *
 * **Threading.** Pure / blocking I/O. Call from `Dispatchers.IO`. No coroutines,
 * no Android dependencies — JVM-testable without Robolectric.
 */
internal object ProjectMapEmitter {

    /** The fixed, flat directory inside the project where region data lives. */
    private const val MAPS_ASSETS_SUBPATH = "src/main/assets/maps"

    /**
     * The region data files copied on apply. `tiles`/`basemap`/`meta` are the
     * canonical three.
     */
    private val REQUIRED_DATA_FILES = listOf(
        "tiles.pmtiles",
        "basemap.pmtiles",
        "meta.json",
    )

    /**
     * Copy [srcRegionDir]'s data files into [projectDir]'s fixed flat maps assets,
     * overwriting any previous region.
     *
     * @param projectDir    the open project root (contains `app/`).
     * @param srcRegionDir  the cached region directory (under the cache root).
     * @param cacheRoot     the canonical cache root, for source-containment checks.
     * @param onChunk       cooperative-cancellation seam invoked between copy
     *                      chunks; a coroutine caller passes
     *                      `{ coroutineContext.ensureActive() }` so a cancelled
     *                      apply aborts the large `tiles.pmtiles` copy promptly.
     *                      Defaults to a no-op (JVM tests, non-coroutine callers).
     * @return an [EmitResult]; `success=false` with a human-readable [EmitResult.reason]
     *         when the project isn't a Maps project, paths escape their roots, or
     *         the copy fails (partial writes are cleaned up).
     */
    fun apply(
        projectDir: File,
        srcRegionDir: File,
        cacheRoot: File,
        onChunk: () -> Unit = {},
    ): EmitResult {
        val appDir = File(projectDir, "app")
        if (!appDir.isDirectory) {
            return EmitResult(success = false, reason = "no app/ dir in $projectDir")
        }

        if (!hasMapRegionActivity(appDir)) {
            return EmitResult(
                success = false,
                reason = "Create a Maps project first — this project has no map activity.",
            )
        }

        val canonicalSrc = srcRegionDir.canonicalFile
        val canonicalCacheRoot = cacheRoot.canonicalFile
        if (!canonicalSrc.toPath().startsWith(canonicalCacheRoot.toPath())) {
            return EmitResult(success = false, reason = "source escapes cache root: $canonicalSrc")
        }

        val mapsDir = File(appDir, MAPS_ASSETS_SUBPATH).apply { mkdirs() }
        val canonicalMaps = mapsDir.canonicalFile
        val canonicalApp = appDir.canonicalFile
        if (!canonicalMaps.toPath().startsWith(canonicalApp.toPath())) {
            return EmitResult(success = false, reason = "maps dir escapes project: $canonicalMaps")
        }

        // Build the copy list: the required files that exist. A region missing
        // tiles.pmtiles is unusable, so require it.
        val pairs = REQUIRED_DATA_FILES
            .map { name -> File(canonicalSrc, name) to File(canonicalMaps, name) }
            .filter { (src, _) -> src.exists() }

        if (pairs.none { (src, _) -> src.name == "tiles.pmtiles" }) {
            return EmitResult(success = false, reason = "region has no tiles.pmtiles: $canonicalSrc")
        }

        val written = mutableListOf<File>()
        return try {
            for ((src, dest) in pairs) {
                AtomicFiles.copy(src, dest, onChunk)
                written += dest
            }
            EmitResult(success = true)
        } catch (e: Exception) {
            // Clean up the files this apply wrote so a mid-copy failure doesn't
            // leave the project bundling a half-applied region.
            written.forEach { runCatching { it.delete() } }
            EmitResult(success = false, reason = "copy failed: ${e.message}")
        }
    }

    /**
     * True if the project declares a `MapRegionActivity` source file (`.kt` or
     * `.java`) anywhere under `app/src/main`. This is the gate for the
     * "require a Maps project" rule — apply refuses to dump region data into a
     * project that can't render it.
     */
    internal fun hasMapRegionActivity(appDir: File): Boolean {
        val srcMain = File(appDir, "src/main")
        if (!srcMain.isDirectory) return false
        return srcMain.walkTopDown().any { f ->
            f.isFile && (f.name == "MapRegionActivity.kt" || f.name == "MapRegionActivity.java")
        }
    }
}

/**
 * Result of [ProjectMapEmitter.apply]. Carries a human-readable [reason] on
 * failure for the caller to surface (e.g. a Snackbar / Toast).
 */
internal data class EmitResult(
    val success: Boolean,
    val reason: String? = null,
)
