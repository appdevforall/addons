package org.appdevforall.customidebuilder.core

import java.io.File

/**
 * Where a picked file is copied before it is checked. The name a document provider reports is
 * untrusted (it can be "../../x.cgp"), so it never becomes a path: the file on disk is always
 * `<index>/addon.<ext>`, and the reported name stays only as the label and the source of the
 * bundled entry name (which [AddonSelection.entryNameFor] cleans).
 */
object Staging {
    /** `addon.cgp` or `addon.cgt` for a reported name with one of those extensions, else null. */
    fun stagedFileName(reportedName: String): String? = AddonSelection.kindOf(reportedName)?.let { "addon.${it.extension}" }

    /** The reported name as shown on screen: control characters removed, at most 100 characters. */
    fun label(reportedName: String): String = reportedName.filter { !it.isISOControl() }.take(100).ifBlank { "add-on" }

    /** The file to copy into for the [index]th add-on, or null for a name that is not an add-on or a path outside [root]. */
    fun stagedFile(root: File, index: Int, reportedName: String): File? {
        val name = stagedFileName(reportedName) ?: return null
        return File(File(root, index.toString()), name).takeIf { isInside(root, it) }
    }

    /** True if [file] resolves, symbolic links included, to a place strictly under [root]. */
    fun isInside(root: File, file: File): Boolean =
        try {
            val base = root.canonicalFile.path
            file.canonicalFile.path.startsWith(base + File.separator)
        } catch (e: java.io.IOException) {
            false
        }
}
