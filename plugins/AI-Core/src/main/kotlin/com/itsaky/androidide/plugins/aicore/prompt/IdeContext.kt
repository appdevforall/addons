package com.itsaky.androidide.plugins.aicore.prompt

/**
 * What the IDE has open and how the project is laid out, read once per prompt.
 *
 * A value with no behaviour beyond deriving [exampleFilePath] from itself: [IdeContextReader]
 * fills it and `ide_context.yml` words it, so neither the services nor the wording is in here.
 *
 * @property currentFile the focused file, project-relative, or null when nothing is open.
 * @property otherFiles other open tabs, project-relative, already capped by the reader.
 * @property modules the project's modules, so the agent spends no turns rediscovering them.
 */
data class IdeContext(
    val currentFile: String?,
    val otherFiles: List<String>,
    val modules: List<ProjectLayout.Module>,
) {

    /** Whether there is nothing here worth telling the model. */
    val isEmpty: Boolean
        get() = currentFile == null && otherFiles.isEmpty() && modules.isEmpty()

    /**
     * The path the tool-call examples should use: a file the IDE really has open, so the examples
     * carry this project's own language and layout instead of teaching an Android/Java one. Falls
     * back to [FALLBACK_EXAMPLE_PATH] only when nothing is open.
     */
    val exampleFilePath: String
        get() = currentFile ?: otherFiles.firstOrNull() ?: FALLBACK_EXAMPLE_PATH

    companion object {
        /** Nothing open and no modules found; the prompt then carries no context block. */
        val EMPTY = IdeContext(null, emptyList(), emptyList())

        /**
         * Path used in the tool-call examples when the IDE has nothing open, so there is no real one
         * to show. A concrete path is what a small model needs to copy the *shape* from — a
         * placeholder like "path/to/File.ext" measurably degrades its calls — so this is the
         * dominant CoGo project layout rather than a language-neutral token. Whenever a file *is*
         * open, [exampleFilePath] uses that instead and this is never seen.
         */
        const val FALLBACK_EXAMPLE_PATH = "app/src/main/java/com/example/MainActivity.kt"
    }
}
