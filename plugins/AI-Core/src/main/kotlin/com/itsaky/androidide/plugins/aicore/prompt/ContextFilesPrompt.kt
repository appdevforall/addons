package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigProvider
import com.itsaky.androidide.plugins.ai.prompt.PromptTemplateEngine
import com.itsaky.androidide.plugins.aicore.prompt.config.AgentPromptConfig
import java.io.File

/**
 * Renders the files the user attached as the block appended to their message, worded by
 * `context_files.yml` and arranged by `layout.context_files`.
 *
 * Belongs to the user turn rather than the system prompt, so it is built per message; the files'
 * contents are inserted verbatim, never rendered as a template.
 *
 * @param config supplies the cached prompt config.
 * @param logWarn records a file that could not be read.
 */
class ContextFilesPrompt(
    private val config: PromptConfigProvider<AgentPromptConfig>,
    private val logWarn: (String, Throwable?) -> Unit,
) {

    /**
     * Renders the block.
     *
     * A file that cannot be read is skipped rather than failing the send: the message is still
     * worth answering without it, and the heading is written only once something is under it.
     *
     * @param files the files the user attached.
     * @return the block to append, or empty when nothing was attached or none could be read.
     */
    suspend fun render(files: List<File>): String {
        val entries = files.mapNotNull(::entryFor)
        if (entries.isEmpty()) return ""

        return render(config.config(), entries)
    }

    /**
     * Reads one file.
     *
     * @param file the attachment to read.
     * @return its name and contents, or null when it could not be read.
     */
    private fun entryFor(file: File): Pair<String, String>? {
        if (!file.exists() || !file.isFile) {
            // Silence here once let a deleted attachment look like one the model had ignored.
            logWarn("context file ${file.name} is gone; sending the message without it", null)
            return null
        }
        return try {
            file.name to file.readText()
        } catch (e: Exception) {
            logWarn("could not read context file ${file.name}", e)
            null
        }
    }

    companion object {
        /** Keeps the block off the end of the user's own words. */
        private const val SEPARATOR = "\n\n"

        /**
         * Renders the block for files already read. Pure and thread-safe.
         *
         * @param config the loaded prompt config.
         * @param entries each file's name and contents; not empty.
         * @return the block to append to the user's message.
         */
        fun render(config: AgentPromptConfig, entries: List<Pair<String, String>>): String =
            SEPARATOR + PromptTemplateEngine.render(
                config.layout.contextFiles,
                PromptVariables.contextFiles(config, entries),
            )

        /**
         * Renders the block against two files, to catch a name typo.
         *
         * @param config the loaded prompt config.
         * @return the failure's message; empty when it renders.
         */
        fun problems(config: AgentPromptConfig): List<String> = try {
            render(config, listOf("A.kt" to "a", "B.kt" to "b"))
            emptyList()
        } catch (e: IllegalArgumentException) {
            listOfNotNull(e.message)
        }
    }
}
