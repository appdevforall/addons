package com.itsaky.androidide.plugins.vectorsearch

import java.io.File

/**
 * The search roots of one index build, as the single value the index stores and compares them by:
 * their absolute paths, sorted, joined by [SEPARATOR].
 */
object RootsKey {

    private const val SEPARATOR = "|"

    /** The key for [roots]; the same set in any order gives the same key. */
    fun of(roots: List<File>): String =
        roots.map { it.absolutePath }.sorted().joinToString(SEPARATOR)

    /**
     * Whether every root in [rootsKey] lies within [projectRoot], so that build belongs to the open
     * project. A sibling sharing a prefix (`app2` beside `app`) does not.
     */
    fun isUnder(rootsKey: String, projectRoot: File): Boolean {
        val dir = projectRoot.absolutePath.trimEnd(File.separatorChar)
        val prefix = dir + File.separatorChar
        return rootsKey.split(SEPARATOR).all { it == dir || it.startsWith(prefix) }
    }
}
