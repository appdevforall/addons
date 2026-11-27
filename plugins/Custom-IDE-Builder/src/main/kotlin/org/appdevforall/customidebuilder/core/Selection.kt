package org.appdevforall.customidebuilder.core

import java.io.File

/**
 * An add-on the user chose, before it is checked. [label] is what the screen calls it (the
 * picked file's name, or the catalog name); [pluginId] is the `plugin.id` read from a .cgp's
 * manifest and is null for a template or a .cgp whose manifest could not be read.
 */
class PickedAddon(val label: String, val fileName: String, val file: File, val pluginId: String?)

sealed class SelectionResult {
    class Valid(val staged: List<StagedAddon>) : SelectionResult()

    class Invalid(val problems: List<String>) : SelectionResult()
}

object AddonSelection {
    /** The kind a file name asks for, by extension in any case, or null for anything else. */
    fun kindOf(fileName: String): AddonKind? =
        AddonKind.values().firstOrNull { fileName.endsWith(".${it.extension}", ignoreCase = true) }

    /**
     * The host refuses a plugin ID that is a path, since it names the plugin's folder
     * (PluginIdTest in Code on the Go). Applying the same rule here catches it before a build.
     */
    fun isUsablePluginId(id: String): Boolean =
        id.isNotEmpty() && id != "." && id != ".." && id.none { it == '/' || it == '\\' || it == '\u0000' } && id.trim() == id

    /** A zip entry name that is safe on every file system and acceptable to the host's own name rule. */
    fun entryNameFor(base: String): String {
        val cleaned = base.map { if (it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '_' || it == '-') it else '_' }.joinToString("")
        return cleaned.trimStart('.').ifEmpty { "addon" }.take(100)
    }

    /**
     * Checks the whole selection and assigns entry names, or lists every problem found:
     * a file that is neither .cgp nor .cgt, `core.cgt`, a plugin without a usable ID, two plugins
     * with one ID, two templates with one file name. Nothing is dropped silently.
     */
    fun validate(picked: List<PickedAddon>): SelectionResult {
        val problems = ArrayList<String>()
        val staged = ArrayList<StagedAddon>()
        val pluginIds = HashMap<String, PickedAddon>()
        val templateNames = HashMap<String, PickedAddon>()
        for (p in picked) {
            val kind = kindOf(p.fileName)
            if (kind == null) {
                problems.add("${p.label}: not a .cgp or .cgt file")
                continue
            }
            val stem = p.fileName.substring(0, p.fileName.length - kind.extension.length - 1)
            when (kind) {
                AddonKind.PLUGIN -> {
                    val id = p.pluginId
                    if (id == null) {
                        problems.add("${p.label}: could not read the plugin ID from its manifest, so it is not a plugin Code on the Go can install")
                        continue
                    }
                    if (!isUsablePluginId(id)) {
                        problems.add("${p.label}: the plugin ID \"$id\" is not a valid name")
                        continue
                    }
                    val earlier = pluginIds.put(id, p)
                    if (earlier != null) {
                        problems.add("${p.label} and ${earlier.label} are both the plugin $id; include one")
                        continue
                    }
                    staged.add(StagedAddon(kind, entryNameFor(id) + ".cgp", p.file))
                }
                AddonKind.TEMPLATE -> {
                    val name = entryNameFor(stem) + ".cgt"
                    if (name.equals(CORE_TEMPLATE, ignoreCase = true)) {
                        problems.add("${p.label}: core.cgt is installed by Code on the Go itself and is never bundled")
                        continue
                    }
                    val earlier = templateNames.put(name.lowercase(), p)
                    if (earlier != null) {
                        problems.add("${p.label} and ${earlier.label} have the same file name; include one")
                        continue
                    }
                    staged.add(StagedAddon(kind, name, p.file))
                }
            }
        }
        // Two different plugin IDs can still clean up to one entry name.
        staged.groupBy { it.kind to it.fileName.lowercase() }.filterValues { it.size > 1 }.forEach { (key, _) ->
            problems.add("two ${key.first.dir} would be stored as ${key.second}; their names differ only in characters a file name cannot hold")
        }
        return if (problems.isEmpty()) SelectionResult.Valid(staged) else SelectionResult.Invalid(problems)
    }
}
