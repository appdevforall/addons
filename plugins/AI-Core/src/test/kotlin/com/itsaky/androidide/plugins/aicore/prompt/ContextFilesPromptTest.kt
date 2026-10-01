package com.itsaky.androidide.plugins.aicore.prompt

import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedWith
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Unit tests for [ContextFilesPrompt], the block carrying the files the user attached. */
class ContextFilesPromptTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val warnings = mutableListOf<String>()
    private val prompt = ContextFilesPrompt({ shippedConfig }) { message, _ -> warnings.add(message) }

    @Test
    fun givenTheShippedConfig_whenChecked_thenTheBlockRenders() {
        assertEquals(emptyList<String>(), ContextFilesPrompt.problems(shippedConfig))
    }

    @Test
    fun givenTwoAttachedFiles_whenRendering_thenEachIsFramedUnderOneHeading() {
        val first = folder.newFile("A.kt").apply { writeText("val a = 1") }
        val second = folder.newFile("B.kt").apply { writeText("val b = 2") }

        val block = render(listOf(first, second))

        assertEquals("\n\nCONTEXT FILES:\n\n=== A.kt ===\nval a = 1\n\n=== B.kt ===\nval b = 2\n\n", block)
    }

    @Test
    fun givenAFileWhoseTextLooksLikeATemplate_whenRendering_thenItIsInsertedVerbatim() {
        // The user's own content is data; rendering it would throw on a stray tag or rewrite it.
        val file = folder.newFile("T.peb").apply { writeText("{{APP_NAME}} {{#X}}") }

        assertTrue(render(listOf(file)).contains("{{APP_NAME}} {{#X}}"))
    }

    @Test
    fun givenANameTypoInTheLayout_whenChecked_thenTheProblemNamesTheText() {
        val config = shippedWith("layout.yml") { it.replace("{{CONTEXT_FILES_HEADING}}", "{{CONTEXT_FILE_HEADING}}") }

        val problems = ContextFilesPrompt.problems(config)

        assertEquals(listOf("layout.yml: layout.context_files: unknown name {{CONTEXT_FILE_HEADING}}"), problems)
    }

    @Test
    fun givenNoAttachments_whenRendering_thenNothingIsAppended() {
        assertEquals("", render(emptyList()))
    }

    @Test
    fun givenAnAttachedFile_whenRendering_thenItsNameAndContentsAreIncluded() {
        val file = folder.newFile("Notes.kt").apply { writeText("val x = 1") }

        val block = render(listOf(file))

        assertTrue(block.contains("=== Notes.kt ==="))
        assertTrue(block.contains("val x = 1"))
    }

    @Test
    fun givenOnlyFilesThatCannotBeRead_whenRendering_thenNoEmptyHeadingIsAppended() {
        // A heading with nothing under it is prompt the model reads and cannot use.
        assertEquals("", render(listOf(File(folder.root, "gone.kt"))))
    }

    @Test
    fun givenAFileThatNoLongerExists_whenRendering_thenItIsReported() {
        // Silence here once let a deleted attachment look like one the model had ignored.
        render(listOf(File(folder.root, "gone.kt")))

        assertTrue(warnings.any { it.contains("gone.kt") })
    }

    @Test
    fun givenAFileThatNoLongerExists_whenRendering_thenTheOthersStillRender() {
        // A stale attachment must not cost the user the send; the message is worth answering.
        val present = folder.newFile("Present.kt").apply { writeText("kept") }

        val block = render(listOf(File(folder.root, "gone.kt"), present))

        assertTrue(block.contains("kept"))
    }

    private fun render(files: List<File>): String = runBlocking { prompt.render(files) }
}
