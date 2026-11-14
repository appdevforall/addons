package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.tool.changeOf
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Unit tests for [CreateFileHandler] on a file that already exists. */
class CreateFileHandlerTest {

    private lateinit var projectRoot: File
    private lateinit var handler: CreateFileHandler

    @Before
    fun setup() {
        projectRoot = Files.createTempDirectory("createfile-project").toFile().canonicalFile
        PathGuard.setProjectRootForTesting(projectRoot.absolutePath)
        val context = mockk<PluginContext>()
        every { context.logger } returns mockk(relaxed = true)
        handler = CreateFileHandler(context)
    }

    @After
    fun tearDown() {
        PathGuard.setProjectRootForTesting(null)
        PathGuard.setProjectRootProvider(null)
        projectRoot.deleteRecursively()
    }

    private fun create(path: String, content: String) = runBlocking {
        handler.execute(mapOf("file_path" to path, "content" to content))
    }

    @Test
    fun givenAFileEditedSinceItWasCreated_whenCreatedAgain_thenItFailsAsAlreadyMadeAndTheFileIsUntouched() {
        // No write tool deletes a file, so a failed repeat of a create that worked shows the create
        // made; the edit since is the model's own, and must not be overwritten with the old content.
        val file = File(projectRoot, "Notes.md").apply { writeText("# Edit\n") }

        val result = create("./Notes.md", "# Note\n")

        assertFalse(result.success)
        assertTrue(result.alreadyMade)
        assertEquals(file.canonicalPath, result.file)
        assertEquals("# Edit\n", file.readText())
    }

    @Test
    fun givenTwoCreates_whenTheirChangesAreKeyed_thenTheContentDecidesAndTheSpellingDoesNot() {
        val note = handler.changeOf(mapOf("file_path" to "Notes.md", "content" to "# One\n"))

        assertEquals(note, handler.changeOf(mapOf("path" to "./Notes.md", "content" to "# One\n")))
        assertNotEquals(note, handler.changeOf(mapOf("file_path" to "Notes.md", "content" to "# Two\n")))
    }

    @Test
    fun givenADirectoryAtThePath_whenCreated_thenItFailsAndIsNotTakenForMade() {
        File(projectRoot, "notes").mkdirs()

        val result = create("notes", "# Notes\n")

        assertFalse(result.success)
        assertFalse(result.alreadyMade)
    }

    @Test
    fun givenNoFile_whenCreated_thenTheResultNamesTheFileItResolvedTo() {
        val result = create("./docs/Notes.md", "# Notes\n")

        assertTrue(result.success)
        assertEquals(File(projectRoot, "docs/Notes.md").canonicalPath, result.file)
    }
}
