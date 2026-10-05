package com.itsaky.androidide.plugins.aicore.prompt.config

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigLoader
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigSource
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking

/**
 * Reads config from a directory, so JVM tests render the exact files the `.cgp` ships.
 *
 * @param root the directory holding the config files.
 * @param edits replaces one file's text before it is returned, as a device would see an edited file.
 */
class DirectoryPromptConfigSource(
    private val root: File,
    private val edits: Map<String, (String) -> String> = emptyMap(),
) : PromptConfigSource {

    override fun read(path: String): String {
        val file = File(root, path)
        if (!file.isFile) throw FileNotFoundException(path)
        return edits[path]?.invoke(file.readText()) ?: file.readText()
    }

    companion object {
        /** The shipped config files; unit tests run with the module directory as working dir. */
        val SHIPPED_ROOT = File("src/main/assets/prompts")

        /** The shipped config, loaded once for every test that renders a prompt. */
        val shippedConfig: AgentPromptConfig by lazy { load(DirectoryPromptConfigSource(SHIPPED_ROOT)) }

        /**
         * Loads the shipped config with one file rewritten by [edit].
         *
         * @param file the file to edit, e.g. `rules.yml`.
         * @param edit rewrites that file's text.
         * @return the config loaded from the edited files.
         */
        fun shippedWith(file: String, edit: (String) -> String): AgentPromptConfig =
            load(DirectoryPromptConfigSource(SHIPPED_ROOT, mapOf(file to edit)))

        private fun load(source: PromptConfigSource): AgentPromptConfig =
            runBlocking { PromptConfigLoader.load(source, AgentPromptConfigParser) }
    }
}
