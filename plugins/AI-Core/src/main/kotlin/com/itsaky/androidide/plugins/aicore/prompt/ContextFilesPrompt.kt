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
     * @param maxFileChars the longest a file is sent whole; see [capped].
     * @param maxTotalChars the most all files together are sent with; see [allotted].
     * @return the block to append, or empty when nothing was attached or none could be read.
     */
    suspend fun render(
        files: List<File>,
        maxFileChars: Int = MAX_FILE_CHARS,
        maxTotalChars: Int = Int.MAX_VALUE,
    ): String {
        val entries = files.mapNotNull(::entryFor)
        if (entries.isEmpty()) return ""

        return render(config.config(), entries, maxFileChars, maxTotalChars)
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

        /** Longest attachment sent whole on any backend: about 17K tokens, re-sent on every later turn. */
        const val MAX_FILE_CHARS = 50_000

        /**
         * Renders the block for files already read. Pure and thread-safe.
         *
         * @param config the loaded prompt config.
         * @param entries each file's name and contents; not empty.
         * @param maxFileChars the longest a file is sent whole; see [capped].
         * @param maxTotalChars the most all files together are sent with; see [allotted].
         * @return the block to append to the user's message.
         */
        fun render(
            config: AgentPromptConfig,
            entries: List<Pair<String, String>>,
            maxFileChars: Int = MAX_FILE_CHARS,
            maxTotalChars: Int = Int.MAX_VALUE,
        ): String {
            val limits = allotted(entries.map { it.second.length }, maxFileChars, maxTotalChars)
            val capped = entries.mapIndexed { i, (name, text) -> name to capped(config, text, limits[i]) }
            return SEPARATOR + PromptTemplateEngine.render(
                config.layout.contextFiles,
                PromptVariables.contextFiles(config, capped),
            )
        }

        /**
         * Shares [maxTotalChars] out among files of [lengths], shortest first, so a small file is
         * sent whole and the rest split what it leaves; no file gets more than [maxFileChars].
         *
         * @return each file's limit, in the order of [lengths].
         */
        fun allotted(lengths: List<Int>, maxFileChars: Int, maxTotalChars: Int): List<Int> {
            val limits = IntArray(lengths.size)
            var left = maxTotalChars.coerceAtLeast(0)
            lengths.indices.sortedBy { lengths[it] }.forEachIndexed { k, i ->
                limits[i] = minOf(lengths[i], maxFileChars, left / (lengths.size - k))
                left -= limits[i]
            }
            return limits.toList()
        }

        /**
         * Cuts [text] to [maxChars], keeping its start and end, since an attachment is re-sent on
         * every later turn and an uncapped one filled the context window (ADFA-6345).
         *
         * @param config the loaded prompt config.
         * @param text an attached file's contents.
         * @param maxChars the longest [text] is sent whole; at most [MAX_FILE_CHARS].
         * @return [text] itself when it fits, otherwise its start and end around the cut notice.
         */
        fun capped(config: AgentPromptConfig, text: String, maxChars: Int = MAX_FILE_CHARS): String {
            if (text.length <= maxChars) return text
            val half = maxChars / 2
            val values = mapOf(
                PromptVariables.HEAD to text.take(half),
                PromptVariables.TAIL to text.takeLast(half),
                PromptVariables.COUNT to (text.length - 2 * half).toString(),
            )
            return PromptTemplateEngine.render(config.contextFiles.truncated, values)
        }

        /**
         * Renders the block against two files, one cut to the size limit, to catch a name typo.
         *
         * @param config the loaded prompt config.
         * @return the failure's message; empty when it renders.
         */
        fun problems(config: AgentPromptConfig): List<String> = try {
            render(config, listOf("A.kt" to "a", "B.kt" to "b".repeat(MAX_FILE_CHARS + 1)))
            emptyList()
        } catch (e: IllegalArgumentException) {
            listOfNotNull(e.message)
        }
    }
}
