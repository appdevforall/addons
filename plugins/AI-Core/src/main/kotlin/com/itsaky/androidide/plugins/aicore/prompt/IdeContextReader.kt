package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.tool.handlers.PathGuard
import com.itsaky.androidide.plugins.services.IdeEditorService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads what the IDE has open and how the project is laid out into an [IdeContext].
 *
 * The only part of prompt assembly that touches a service or the disk, so everything downstream of
 * it stays pure.
 *
 * @param getContext supplies the plugin context, or null before the plugin is initialized.
 */
class IdeContextReader(private val getContext: () -> PluginContext?) : IdeContextSource {

    /**
     * Reads the open-file state and the module layout.
     *
     * @return the context; the modules alone when there is no editor service or the read fails,
     *   since those are worth stating even when nothing is known to be open.
     */
    override suspend fun read(): IdeContext {
        val root = File(PathGuard.projectRoot())
        val modules = withContext(Dispatchers.IO) { ProjectLayout.describe(root) }
        // Paths, not a count: an empty or wrong one here is what sends the agent walking the tree,
        // and these are project-relative directory names rather than the user's content.
        AgentTrace.stage(
            "LAYOUT",
            "modules=${modules.size}" + modules.joinToString("") {
                " ${it.name}[src=${it.sourceDir} layout=${it.layoutDir} manifest=${it.manifest}]"
            },
        )

        val editor = getContext()?.services?.get(IdeEditorService::class.java)
            ?: return IdeContext(null, emptyList(), modules)

        // Editor state is read on the main thread, like every other editor-service call.
        val (current, open) = withContext(Dispatchers.Main) {
            runCatching { editor.getCurrentFile() to editor.getOpenFiles() }
                .getOrDefault(null to emptyList())
        }

        return IdeContext(
            currentFile = current?.let { relativePath(it, root) },
            otherFiles = open.orEmpty()
                .filter { it != current }
                .mapNotNull { relativePath(it, root) }
                .take(MAX_OPEN_FILES),
            modules = modules,
        )
    }

    companion object {
        /** Max open files named in the prompt's IDE-context block. */
        private const val MAX_OPEN_FILES = 8

        /**
         * [file]'s path relative to [root], or its name when no relative path exists.
         *
         * @return the path, or null when it is blank (the root itself), which names no file.
         */
        internal fun relativePath(file: File, root: File): String? =
            runCatching { file.relativeToOrSelf(root).path }
                .getOrDefault(file.name)
                .takeUnless { it.isBlank() }
    }
}
