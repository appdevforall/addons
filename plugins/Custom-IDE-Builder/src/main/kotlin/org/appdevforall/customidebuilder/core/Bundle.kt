package org.appdevforall.customidebuilder.core

/**
 * The two kinds of add-on a build can bundle, each in a folder of its own in the APK assets.
 * Mirrors `BundledAddonKind` in Code on the Go (BundledAddons.kt); [dir] and [extension] must
 * stay equal to it, or the host does not find what this plugin bundled.
 */
enum class AddonKind(val dir: String, val extension: String) {
    PLUGIN("plugins", "cgp"),
    TEMPLATE("templates", "cgt"),
}

/** An add-on ready to go into the APK: [fileName] is the name inside its folder, [file] the bytes. */
class StagedAddon(val kind: AddonKind, val fileName: String, val file: java.io.File) {
    /** `assets/plugins/<fileName>`: the APK entry the host reads it from. */
    val apkEntryName: String get() = "$ASSETS/${kind.dir}/$fileName"
}

/** One line of the bundle index. */
data class IndexLine(val kind: AddonKind, val fileName: String, val size: Long, val sha256: String)

const val ASSETS = "assets"

/** The bundle index sits beside `plugins/` and `templates/`, so its APK entry is `assets/bundled-addons.tsv`. */
const val INDEX_FILE = "bundled-addons.tsv"
const val INDEX_APK_ENTRY = "$ASSETS/$INDEX_FILE"

/** The template the host installs by its own entry; it is never a bundled add-on. */
const val CORE_TEMPLATE = "core.cgt"

object BundleIndex {
    /**
     * The index text exactly as Code on the Go's build writes it (StageBundledAddons in
     * app/build.gradle.kts): plugins by file name, then templates by file name, one
     * `<dir>/<name>\t<size>\t<sha256>\n` line each, nothing at all for a thin build. The host
     * ignores an index it cannot parse and falls back to listing folders by name and size, so a
     * deviation here costs nothing visible; it is kept exact anyway.
     */
    fun render(lines: List<IndexLine>): String {
        val sb = StringBuilder()
        for (kind in AddonKind.values()) {
            lines.filter { it.kind == kind }.sortedBy { it.fileName }.forEach {
                sb.append("${kind.dir}/${it.fileName}\t${it.size}\t${it.sha256}\n")
            }
        }
        return sb.toString()
    }
}
