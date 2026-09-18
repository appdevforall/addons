package com.itsaky.androidide.plugins.aicore.viewmodel

import java.io.File

/**
 * Where a project keeps its sources, layouts and manifest, for the prompt's IDE-context block.
 *
 * The agent otherwise finds this out one `list_files` at a time: a reported run spent seven of its
 * sixteen turns walking `app` → `src` → `main` → `java` → `com` → `example`, four of those steps
 * returning a single entry, and hit the step limit with the manifest edit still pending. Stating
 * the paths costs no turns at all.
 *
 * Pure and free of Android types, so it unit-tests against a temp directory.
 */
object ProjectLayout {

    /** Modules described, so a many-module project cannot crowd out the rest of the prompt. */
    private const val MAX_MODULES = 4

    /** Package directories are a few levels deep; the cap only stops a pathological tree. */
    private const val MAX_DEPTH = 12

    /** Source roots in preference order; the first that holds anything wins. */
    private val SOURCE_ROOTS = listOf("src/main/java", "src/main/kotlin")

    /**
     * One module's interesting directories, project-relative with `/` separators.
     *
     * @property name the module's directory name.
     * @property sourceDir the deepest package directory under its source root, or null when absent.
     * @property layoutDir its `res/layout`, or null when absent.
     * @property manifest its `AndroidManifest.xml`, or null when absent.
     */
    data class Module(
        val name: String,
        val sourceDir: String?,
        val layoutDir: String?,
        val manifest: String?,
    )

    /**
     * Describes the modules under [root].
     *
     * @param root the project root.
     * @return one entry per module that has a `src/main`, capped at [MAX_MODULES]; empty when the
     *   root is unreadable or holds no module, which is the "say nothing" case for the prompt.
     */
    fun describe(root: File): List<Module> = runCatching {
        root.listFiles()
            .orEmpty()
            .filter { it.isDirectory && File(it, "src/main").isDirectory }
            // `app` first: it is the module a request is about unless the user says otherwise.
            .sortedBy { if (it.name == "app") "" else it.name }
            .take(MAX_MODULES)
            .map { module ->
                Module(
                    name = module.name,
                    sourceDir = packageDirOf(module)?.let { relativeTo(root, it) },
                    layoutDir = File(module, "src/main/res/layout")
                        .takeIf { it.isDirectory }?.let { relativeTo(root, it) },
                    manifest = File(module, "src/main/AndroidManifest.xml")
                        .takeIf { it.isFile }?.let { relativeTo(root, it) },
                )
            }
            .filterNot { it.sourceDir == null && it.layoutDir == null && it.manifest == null }
    }.getOrDefault(emptyList())

    /**
     * The directory a new class in [module] belongs in.
     *
     * Descends while a directory holds exactly one subdirectory and no files, which is the
     * `com/example/myapplication` chain the agent was walking a turn at a time.
     *
     * @return the deepest such directory, or null when the module has no source root.
     */
    private fun packageDirOf(module: File): File? {
        val roots = SOURCE_ROOTS.map { File(module, it) }.filter { it.isDirectory }
        // The populated root, not merely the first: an AGP project keeps an empty `java` beside
        // the `kotlin` tree its code is in, and naming the empty one sends new classes there.
        var dir = roots.firstOrNull { holdsAnyFile(it) } ?: roots.firstOrNull() ?: return null
        var depth = 0
        while (depth++ < MAX_DEPTH) {
            val children = dir.listFiles().orEmpty()
            val onlyChild = children.singleOrNull()?.takeIf { it.isDirectory } ?: return dir
            dir = onlyChild
        }
        return dir
    }

    /**
     * Whether [dir] holds a file anywhere beneath it, i.e. is a source root in use.
     *
     * @param dir the candidate source root.
     * @return true when it contains at least one file within [MAX_DEPTH].
     */
    private fun holdsAnyFile(dir: File): Boolean =
        dir.walkTopDown().maxDepth(MAX_DEPTH).any { it.isFile }

    /**
     * [file] as a path relative to [root], with `/` separators whatever the platform uses.
     *
     * @return the relative path, or the file's name when it lies outside [root].
     */
    private fun relativeTo(root: File, file: File): String =
        runCatching { file.relativeToOrSelf(root).path.replace(File.separatorChar, '/') }
            .getOrDefault(file.name)
}
