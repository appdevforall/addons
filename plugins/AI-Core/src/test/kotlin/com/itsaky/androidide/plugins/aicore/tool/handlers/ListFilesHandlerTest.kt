package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [ListFilesHandler] after it was refactored to resolve and
 * containment-check paths through the shared [PathGuard].
 */
class ListFilesHandlerTest {

    private lateinit var projectRoot: File
    private lateinit var handler: ListFilesHandler

    @Before
    fun setup() {
        projectRoot = Files.createTempDirectory("listfiles-project").toFile().canonicalFile
        PathGuard.setProjectRootForTesting(projectRoot.absolutePath)
        handler = ListFilesHandler(mockk<PluginContext>(relaxed = true))
    }

    @After
    fun tearDown() {
        PathGuard.setProjectRootForTesting(null)
        PathGuard.setProjectRootProvider(null)
        projectRoot.deleteRecursively()
    }

    @Test
    fun givenNoDirectoryArg_whenListing_thenItListsTheProjectRoot() = runBlocking {
        File(projectRoot, "README.md").writeText("hi")
        File(projectRoot, "app").mkdirs()

        val result = handler.execute(emptyMap())

        assertTrue("Expected success, got: ${result.message}", result.success)
        val data = result.data.orEmpty()
        assertTrue(data.contains("README.md"))
        assertTrue(data.contains("app"))
    }

    @Test
    fun givenABlankDirectoryArg_whenListing_thenItListsTheProjectRoot() = runBlocking {
        File(projectRoot, "build.gradle.kts").writeText("x")

        val result = handler.execute(mapOf("directory" to "   "))

        assertTrue(result.success)
        assertTrue(result.data.orEmpty().contains("build.gradle.kts"))
    }

    @Test
    fun givenASubdirectoryArg_whenListing_thenItListsThatSubdirectory() = runBlocking {
        File(projectRoot, "src/main").mkdirs()
        File(projectRoot, "src/main/Main.kt").writeText("fun main() {}")

        val result = handler.execute(mapOf("directory" to "src/main"))

        assertTrue(result.success)
        assertTrue(result.data.orEmpty().contains("Main.kt"))
    }

    @Test
    fun givenASlashPrefixedRelativeDirectory_whenListing_thenItResolvesAsRelative() = runBlocking {
        // A slash-prefixed relative dir must fall back to relative-to-root.
        File(projectRoot, "src/main").mkdirs()
        File(projectRoot, "src/main/Main.kt").writeText("fun main() {}")

        val result = handler.execute(mapOf("directory" to "/src/main"))

        assertTrue("Expected success, got: ${result.message}", result.success)
        assertTrue(result.data.orEmpty().contains("Main.kt"))
    }

    @Test
    fun givenASlashPrefixedEscapingDirectory_whenListing_thenItIsStillRejected() = runBlocking {
        // The fallback must not become a containment bypass.
        val result = handler.execute(mapOf("directory" to "/../etc"))

        assertFalse(result.success)
        assertTrue(result.message.contains("within project directory"))
    }

    @Test
    fun givenADirectoryEscapingTheProjectRoot_whenListing_thenItIsRejected() = runBlocking {
        val result = handler.execute(mapOf("directory" to "../"))

        assertFalse(result.success)
        assertTrue(result.message.contains("within project directory"))
    }

    @Test
    fun givenANonexistentDirectory_whenListing_thenItFails() = runBlocking {
        val result = handler.execute(mapOf("directory" to "nope"))

        assertFalse(result.success)
        assertTrue(result.message.contains("does not exist"))
    }

    @Test
    fun givenAFilePath_whenListing_thenItIsRejectedAsNotADirectory() = runBlocking {
        File(projectRoot, "file.txt").writeText("x")

        val result = handler.execute(mapOf("directory" to "file.txt"))

        assertFalse(result.success)
        assertTrue(result.message.contains("not a directory"))
    }

    @Test
    fun givenAnEmptyDirectory_whenListing_thenItReportsSuccessWithNoEntries() = runBlocking {
        File(projectRoot, "empty").mkdirs()

        val result = handler.execute(mapOf("directory" to "empty"))

        assertTrue(result.success)
        assertTrue(result.data.orEmpty().contains("no files or directories"))
    }
}
