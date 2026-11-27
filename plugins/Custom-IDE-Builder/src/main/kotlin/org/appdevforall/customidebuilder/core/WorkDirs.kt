package org.appdevforall.customidebuilder.core

/**
 * The plugin's `work` folder holds `staged` (the picked add-ons) and one output folder per build, so a
 * build that is still cleaning up can never wipe the output of the next one.
 */
object WorkDirs {
    private val OUT = Regex("out(-\\d+)?")

    /** The output folder name for build number [id]. */
    fun outDirName(id: Int): String = "out-$id"

    /**
     * The names in [children] that are output folders of some other build, including the single `out`
     * earlier versions used: every `out` or `out-<n>` except [current]. Nothing else is ever named.
     */
    fun staleOutDirs(children: Collection<String>, current: String): List<String> =
        children.filter { it != current && OUT.matches(it) }
}
