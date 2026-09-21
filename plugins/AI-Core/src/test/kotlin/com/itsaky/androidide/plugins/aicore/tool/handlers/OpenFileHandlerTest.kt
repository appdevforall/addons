package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.services.IdeEditorService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [OpenFileHandler] — the tool that opens a project file in the
 * IDE editor. Covers every branch, including the path-containment guard that
 * regressed on `open_file .gitignore`.
 */
class OpenFileHandlerTest {

    private lateinit var projectRoot: File
    private lateinit var context: PluginContext
    private lateinit var services: ServiceRegistry
    private lateinit var editorService: IdeEditorService
    private lateinit var handler: OpenFileHandler

    @Before
    fun setup() {
        projectRoot = Files.createTempDirectory("openfile-project").toFile().canonicalFile
        PathGuard.setProjectRootForTesting(projectRoot.absolutePath)

        editorService = mockk(relaxed = true)
        services = mockk()
        context = mockk()
        every { context.services } returns services
        every { services.get(IdeEditorService::class.java) } returns editorService

        // Unconfined: no Android main looper in a JVM test.
        handler = OpenFileHandler(context, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        PathGuard.setProjectRootForTesting(null)
        PathGuard.setProjectRootProvider(null)
        projectRoot.deleteRecursively()
    }

    private fun createFile(relative: String): File =
        File(projectRoot, relative).apply {
            parentFile?.mkdirs()
            writeText("content")
        }

    @Test
    fun givenAnExistingProjectFile_whenOpened_thenItSucceeds() = runBlocking {
        createFile(".gitignore")
        every { editorService.openFile(any()) } returns true

        val result = handler.execute(mapOf("file_path" to ".gitignore"))

        assertTrue("Expected success, got: ${result.message}", result.success)
        assertEquals(".gitignore", result.data)
        verify { editorService.openFile(File(projectRoot, ".gitignore")) }
    }

    @Test
    fun givenALeadingSlashPath_whenOpened_thenItFallsBackToBasenameSearchAndOpens() = runBlocking {
        // The model sometimes prepends a slash ("/.gitignore"), which resolves
        // outside the project root; the basename fallback must still find it.
        createFile(".gitignore")
        every { editorService.openFile(any()) } returns true

        val result = handler.execute(mapOf("file_path" to "/.gitignore"))

        assertTrue("Expected success, got: ${result.message}", result.success)
        verify { editorService.openFile(File(projectRoot, ".gitignore")) }
    }

    @Test
    fun givenALeadingSlashDirectoryQualifiedPath_whenOpened_thenItResolvesAsRelativeAndOpens() = runBlocking {
        // With two .gitignore files, a bare basename is ambiguous; the model
        // disambiguates with "app/.gitignore" but sends it slash-prefixed. It
        // must resolve to the exact file, not collapse back to a basename search.
        createFile(".gitignore")
        createFile("app/.gitignore")
        every { editorService.openFile(any()) } returns true

        val result = handler.execute(mapOf("file_path" to "/app/.gitignore"))

        assertTrue("Expected success, got: ${result.message}", result.success)
        verify { editorService.openFile(File(projectRoot, "app/.gitignore")) }
    }

    @Test
    fun givenAMissingFilePath_whenOpened_thenItFails() = runBlocking {
        val result = handler.execute(emptyMap())

        assertFalse(result.success)
        assertTrue(result.message.contains("file_path is required"))
        verify(exactly = 0) { editorService.openFile(any()) }
    }

    @Test
    fun givenABlankFilePath_whenOpened_thenItFails() = runBlocking {
        val result = handler.execute(mapOf("file_path" to "   "))

        assertFalse(result.success)
        verify(exactly = 0) { editorService.openFile(any()) }
    }

    @Test
    fun givenAPathEscapingTheProjectRoot_whenOpened_thenItIsRejected() = runBlocking {
        val result = handler.execute(mapOf("file_path" to "../outside.txt"))

        assertFalse(result.success)
        assertTrue(result.message.contains("within project directory"))
        verify(exactly = 0) { editorService.openFile(any()) }
    }

    @Test
    fun givenANonexistentFile_whenOpened_thenItFailsWithNotFound() = runBlocking {
        val result = handler.execute(mapOf("file_path" to "does/not/exist.kt"))

        assertFalse(result.success)
        assertEquals("File not found", result.message)
        verify(exactly = 0) { editorService.openFile(any()) }
    }

    @Test
    fun givenALeadingSlashNonexistentPath_whenOpened_thenItReportsNotFoundRatherThanAnEscape() = runBlocking {
        // "/does/not/exist.kt" has an in-root reading ("does/not/exist.kt") that
        // simply doesn't exist — so the user sees "File not found", not the
        // misleading "must be within project directory" (which is reserved for a
        // real containment escape like "../outside.txt").
        val result = handler.execute(mapOf("file_path" to "/does/not/exist.kt"))

        assertFalse(result.success)
        assertEquals("File not found", result.message)
        verify(exactly = 0) { editorService.openFile(any()) }
    }

    @Test
    fun givenADirectoryPath_whenOpened_thenItIsRejectedAsNotAFile() = runBlocking {
        File(projectRoot, "somedir").mkdirs()

        val result = handler.execute(mapOf("file_path" to "somedir"))

        assertFalse(result.success)
        assertEquals("Not a file", result.message)
        verify(exactly = 0) { editorService.openFile(any()) }
    }

    @Test
    fun givenNoEditorService_whenOpened_thenItFailsGracefully() = runBlocking {
        createFile("Main.kt")
        every { services.get(IdeEditorService::class.java) } returns null

        val result = handler.execute(mapOf("file_path" to "Main.kt"))

        assertFalse(result.success)
        assertEquals("Editor service not available", result.message)
    }

    @Test
    fun givenABareFilename_whenOpened_thenItResolvesToItsRealNestedPath() = runBlocking {
        createFile("app/src/main/java/com/example/MainActivity.java")
        every { editorService.openFile(any()) } returns true

        val result = handler.execute(mapOf("file_path" to "MainActivity.java"))

        assertTrue("Expected success, got: ${result.message}", result.success)
        verify {
            editorService.openFile(File(projectRoot, "app/src/main/java/com/example/MainActivity.java"))
        }
    }

    @Test
    fun givenAnAmbiguousBareFilename_whenOpened_thenItReturnsTheCandidatesInsteadOfOpening() = runBlocking {
        createFile("app/src/main/java/A/Strings.kt")
        createFile("app/src/main/java/B/Strings.kt")

        val result = handler.execute(mapOf("file_path" to "Strings.kt"))

        assertFalse(result.success)
        assertTrue(result.message.contains("Multiple files"))
        verify(exactly = 0) { editorService.openFile(any()) }
    }

    @Test
    fun givenABareFilenameWithNoMatch_whenOpened_thenItReportsNotFound() = runBlocking {
        val result = handler.execute(mapOf("file_path" to "Nope.java"))

        assertFalse(result.success)
        assertEquals("File not found", result.message)
    }

    @Test
    fun givenTheEditorReportingFailure_whenOpened_thenItSurfacesAFailureResult() = runBlocking {
        createFile("Main.kt")
        every { editorService.openFile(any()) } returns false

        val result = handler.execute(mapOf("file_path" to "Main.kt"))

        assertFalse(result.success)
        assertEquals("Failed to open file", result.message)
    }
}
