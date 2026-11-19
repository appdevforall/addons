package org.appdevforall.maps.data

import com.itsaky.androidide.plugins.ResourceManager
import java.io.File
import org.appdevforall.maps.PluginLog

/**
 * Extracts bundled glyph PBFs from plugin assets into the app's filesystem cache.
 *
 * MapLibre's `AssetFileSource` reads only the host APK's assets, not the plugin's —
 * so the `asset://fonts/...` URL never resolves for a plugin-bundled font. The fix
 * is to copy each PBF once out of plugin assets (via [ResourceManager.openPluginAsset])
 * into a cache directory, then have `style.json` reference them via `file://` at the
 * cache path.
 *
 * Only Latin ranges are bundled. Every other range gets an empty file, because MapLibre
 * leaves blank every tile whose labels need a range that failed to load.
 */
object MapFontExtractor {

    /**
     * Glyph PBFs bundled in plugin assets under `assets/fonts/{stack}/{range}.pbf`.
     * Latin coverage only.
     */
    private val BUNDLED_FONT_STACKS: Map<String, List<String>> = mapOf(
        "Noto Sans Regular" to listOf("0-255", "256-511", "512-767", "7680-7935", "8192-8447"),
        "Noto Sans Italic" to listOf("0-255", "256-511", "512-767", "7680-7935", "8192-8447"),
    )

    /** Every range name MapLibre can request, "0-255" through "65280-65535". */
    private val ALL_RANGES: List<String> = (0 until 256).map { "${it * 256}-${it * 256 + 255}" }

    /**
     * Extract bundled glyph PBFs from plugin assets to filesystem cache on first
     * picker open. Copies each PBF once via [ResourceManager.openPluginAsset]; style.json
     * then references them via `file://` at the cache path.
     *
     * Writes an empty glyph file for every range not bundled, so a label outside the
     * bundled ranges drops those characters instead of blanking its tile.
     *
     * Idempotent — skips files that already exist with non-zero size. Must be called
     * off the main thread (it copies ~10 PBFs out of plugin assets on a cold cache);
     * the caller invokes it inside `withContext(Dispatchers.IO)`.
     *
     * @param resources the plugin's [ResourceManager] (from `PluginContext.resources`).
     * @param cacheDir the app cache directory the fonts are copied under.
     * @return the root directory the fonts were extracted into, or null if the cache
     *   directory couldn't be created.
     */
    fun extract(resources: ResourceManager, cacheDir: File): File? {
        val root = File(cacheDir, "maps-plugin-fonts")
        if (!root.exists() && !root.mkdirs()) {
            PluginLog.warn("Font cache: failed to mkdir $root")
            return null
        }
        var extracted = 0
        var skipped = 0
        for ((stack, ranges) in BUNDLED_FONT_STACKS) {
            val stackDir = File(root, stack)
            if (!stackDir.exists()) stackDir.mkdirs()
            for (range in ranges) {
                val dest = File(stackDir, "$range.pbf")
                if (dest.exists() && dest.length() > 0) { skipped++; continue }
                val input = resources.openPluginAsset("fonts/$stack/$range.pbf")
                if (input == null) {
                    PluginLog.warn("Font cache: missing asset fonts/$stack/$range.pbf")
                    continue
                }
                runCatching {
                    input.use { src ->
                        dest.outputStream().use { dst -> src.copyTo(dst) }
                    }
                    extracted++
                }.onFailure {
                    PluginLog.warn("Font cache: extract failed for $stack/$range.pbf", it)
                    dest.delete()
                }
            }
            for (range in ALL_RANGES - ranges.toSet()) {
                val stub = File(stackDir, "$range.pbf")
                if (stub.exists()) continue
                runCatching { stub.createNewFile() }
                    .onFailure { PluginLog.warn("Font cache: empty range failed for $stack/$range.pbf", it) }
            }
        }
        return root
    }
}
