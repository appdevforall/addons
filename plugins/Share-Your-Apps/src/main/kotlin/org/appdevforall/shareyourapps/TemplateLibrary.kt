package org.appdevforall.shareyourapps

import io.pebbletemplates.pebble.error.LoaderException
import io.pebbletemplates.pebble.loader.Loader
import java.io.File
import java.io.Reader
import java.io.StringReader

/**
 * Where templates find each other (design, Appendix D.1), for `{% extends %}`, `{% include %}`,
 * `{% import %}` and `{% from … import %}`:
 *
 * - **"default"** is the built-in default template, split into blocks — styles, header,
 *   announcement, card, downloads and footer — so a template can `{% extends "default" %}` and
 *   replace only the blocks it wants.
 * - **"accessible"** is the built-in Clear Access template.
 * - **Any other name** is a file in the owner's template library, `CodeOnTheGoProjects/ShareYourAppsTemplates/`,
 *   with `.peb` added if the name has no extension. Names are relative to the library's top, never
 *   to the including template; a name that could leave the library — absolute, with a `..` or
 *   empty part, or a backslash — is refused.
 *
 * Only text: nothing here loads code.
 */
class TemplateLibrary(private val folder: File?) : Loader<String> {

    override fun getReader(name: String): Reader = when (name) {
        DEFAULT -> StringReader(Templates.DEFAULT_CATALOG)
        ACCESSIBLE -> StringReader(Templates.ACCESSIBLE)
        else -> {
            val f = file(name) ?: throw LoaderException(null, "\"$name\" isn't a template name: it must stay inside $FOLDER.")
            if (!f.isFile) throw LoaderException(null, "There's no template \"$name\" in $FOLDER.")
            f.reader(Charsets.UTF_8)
        }
    }

    override fun resourceExists(name: String): Boolean = name == DEFAULT || name == ACCESSIBLE || file(name)?.isFile == true
    override fun createCacheKey(name: String): String = name
    override fun resolveRelativePath(relativePath: String, anchorPath: String): String = relativePath
    override fun setCharset(charset: String) {}
    override fun setPrefix(prefix: String) {}
    override fun setSuffix(suffix: String) {}

    /** The library file a name stands for, or null if the name could leave the library, or there's no library. */
    fun file(name: String): File? {
        val dir = folder ?: return null
        if (!safe(name)) return null
        val withExt = if ('.' in name.substringAfterLast('/')) name else "$name.peb"
        val f = File(dir, withExt)
        val root = dir.canonicalPath + File.separator
        return f.takeIf { it.canonicalPath.startsWith(root) }
    }

    companion object {
        const val DEFAULT = "default"
        const val ACCESSIBLE = "accessible"
        /** The library folder, relative to shared storage's root. */
        const val FOLDER = "CodeOnTheGoProjects/ShareYourAppsTemplates"

        /** Relative, with no empty, "." or ".." parts, and no backslash or NUL. */
        fun safe(name: String): Boolean = name.isNotBlank() && !name.startsWith("/") && '\\' !in name && '\u0000' !in name &&
            name.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}
