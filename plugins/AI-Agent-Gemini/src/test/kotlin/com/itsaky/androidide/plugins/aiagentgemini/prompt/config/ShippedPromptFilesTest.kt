package com.itsaky.androidide.plugins.aiagentgemini.prompt.config

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigLoader
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigSource
import com.itsaky.androidide.plugins.aiagentgemini.prompt.config.DirectoryPromptConfigSource.Companion.SHIPPED_ROOT
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shipped `assets/prompts/` files: every one included once, in order, with no malformed tag. */
class ShippedPromptFilesTest {

    /** The shipped files, by name. */
    private val shipped: Map<String, String> =
        SHIPPED_ROOT.listFiles { f -> f.extension == "yml" }!!.associate { it.name to it.readText() }

    @Test
    fun givenTheShippedEntryFile_whenLoading_thenItAndEveryIncludeAreReadInOrder() {
        val paths = mutableListOf<String>()
        val source = PromptConfigSource { path -> paths += path; File(SHIPPED_ROOT, path).readText() }

        runBlocking { PromptConfigLoader.load(source, GeminiPromptConfigParser) }

        assertEquals(
            listOf("agent.yml", "scope.yml", "rules.yml", "workflow.yml", "tools.yml", "layout.yml"),
            paths,
        )
    }

    @Test
    fun givenEveryShippedFile_whenListed_thenEachIsIncludedExactlyOnce() {
        // A .yml nobody includes is dead wording that looks live to whoever edits it.
        val entry = shipped.getValue("agent.yml")
        val included = Regex("(?m)^  - (\\S+\\.yml)$").findAll(entry).map { it.groupValues[1] }

        assertEquals(shipped.keys - "agent.yml", included.toSet())
    }

    @Test
    fun givenTheShippedFiles_whenScanned_thenNoTagIsMalformed() {
        // A `{{name}}` or `{{ #X}}` typo would reach the model verbatim, since it is no tag.
        assertTrue(shipped.isNotEmpty())
        for ((name, text) in shipped) {
            assertFalse("$name has a malformed tag", MALFORMED_TAG.containsMatchIn(text))
        }
    }

    private companion object {
        /** A `{{` that opens none of `{{NAME}}`, `{{#NAME}}`, `{{^NAME}}` or `{{/NAME}}`. */
        val MALFORMED_TAG = Regex("""\{\{(?![#^/]?[A-Z])""")
    }
}
