package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import io.mockk.mockk
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
 * Unit tests for [SearchProjectHandler], chiefly a single file given as `project_dir` (ADFA-6359).
 */
class SearchProjectHandlerTest {

    private lateinit var projectRoot: File
    private lateinit var handler: SearchProjectHandler

    @Before
    fun setup() {
        projectRoot = Files.createTempDirectory("searchproject-project").toFile().canonicalFile
        PathGuard.setProjectRootForTesting(projectRoot.absolutePath)
        handler = SearchProjectHandler(mockk<PluginContext>(relaxed = true))
    }

    @After
    fun tearDown() {
        PathGuard.setProjectRootForTesting(null)
        PathGuard.setProjectRootProvider(null)
        projectRoot.deleteRecursively()
    }

    @Test
    fun givenFileAsProjectDir_whenQueryInFile_thenReturnsMatchingLines() = runBlocking {
        File(projectRoot, "mcp-curl-tests.sh").writeText("#!/bin/sh\ncurl -s localhost\necho done\nCURL again\n")

        val result = handler.execute(mapOf("query" to "curl", "project_dir" to "mcp-curl-tests.sh"))

        assertTrue("Expected success, got: ${result.message}", result.success)
        assertEquals("Found 2 match(es) for 'curl' in mcp-curl-tests.sh", result.message)
        val data = result.data.orEmpty()
        assertTrue(data.contains("mcp-curl-tests.sh:2 → curl -s localhost"))
        assertTrue(data.contains("mcp-curl-tests.sh:4 → CURL again"))
        assertFalse(data.contains("[FILE]"))
    }

    @Test
    fun givenFileAsProjectDir_whenQueryAbsent_thenSucceedsWithNoMatches() = runBlocking {
        File(projectRoot, "notes.txt").writeText("nothing to see")

        val result = handler.execute(mapOf("query" to "curl", "project_dir" to "notes.txt"))

        assertTrue(result.success)
        assertEquals("No lines matching 'curl' in notes.txt", result.message)
        assertEquals("", result.data)
    }

    @Test
    fun givenBinaryFileAsProjectDir_whenSearching_thenFailsAsNotText() = runBlocking {
        File(projectRoot, "libs").mkdirs()
        File(projectRoot, "libs/thing.jar").writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))

        val result = handler.execute(mapOf("query" to "curl", "project_dir" to "libs/thing.jar"))

        assertFalse(result.success)
        assertEquals("thing.jar is not a text file", result.message)
    }

    @Test
    fun givenMissingProjectDir_whenSearching_thenFailsNamingThePath() = runBlocking {
        val result = handler.execute(mapOf("query" to "curl", "project_dir" to "scripts/missing.sh"))

        assertFalse(result.success)
        assertEquals("No such file or directory: scripts/missing.sh", result.message)
    }

    @Test
    fun givenDirectory_whenSearchingNames_thenBehaviourUnchanged() = runBlocking {
        File(projectRoot, "app/src").mkdirs()
        File(projectRoot, "app/src/MainActivity.kt").writeText("class MainActivity")
        File(projectRoot, "app/src/Other.kt").writeText("MainActivity is referenced here")

        val result = handler.execute(mapOf("query" to "MainActivity", "project_dir" to "app"))

        assertTrue(result.success)
        assertEquals("Found 1 match(es) for 'MainActivity'", result.message)
        assertTrue(result.data.orEmpty().contains("📄 [FILE] "))
        assertTrue(result.data.orEmpty().contains("MainActivity.kt"))
    }

    @Test
    fun givenDirectory_whenSearchingContents_thenReturnsMatchingLines() = runBlocking {
        File(projectRoot, "app").mkdirs()
        File(projectRoot, "app/Other.kt").writeText("val x = 1\n// MainActivity starts here\n")

        val result = handler.execute(
            mapOf("query" to "MainActivity", "project_dir" to "app", "search_in_contents" to true)
        )

        assertTrue(result.success)
        assertTrue(result.data.orEmpty().contains("Other.kt:2 → // MainActivity starts here"))
    }

    @Test
    fun givenProjectDirOutsideRoot_whenSearching_thenRejected() = runBlocking {
        val outside = Files.createTempFile("searchproject-outside", ".sh").toFile()
        try {
            outside.writeText("curl")

            val result = handler.execute(mapOf("query" to "curl", "project_dir" to outside.absolutePath))

            assertFalse(result.success)
            assertEquals("Search directory must be within the project directory", result.message)
        } finally {
            outside.delete()
        }
    }

    @Test
    fun givenPngAsProjectDir_whenSearching_thenFailsAsNotText() = runBlocking {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x00, 0x00, 0x0d)
        File(projectRoot, "icon.png").writeBytes(png + "curl".toByteArray())

        val result = handler.execute(mapOf("query" to "curl", "project_dir" to "icon.png"))

        assertFalse(result.success)
        assertEquals("icon.png is not a text file", result.message)
    }

    @Test
    fun givenExtensionlessBinaryAsProjectDir_whenSearching_thenFailsAsNotText() = runBlocking {
        File(projectRoot, "blob").writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x00, 0x01))

        val result = handler.execute(mapOf("query" to "curl", "project_dir" to "blob"))

        assertFalse(result.success)
        assertEquals("blob is not a text file", result.message)
    }

    @Test
    fun givenBinaryInTree_whenSearchingContents_thenSkipped() = runBlocking {
        File(projectRoot, "app").mkdirs()
        File(projectRoot, "app/data.db").writeBytes(byteArrayOf(0x00) + "curl".toByteArray())

        val result = handler.execute(mapOf("query" to "curl", "project_dir" to "app", "search_in_contents" to true))

        assertTrue(result.success)
        assertEquals("No content found matching 'curl'", result.message)
    }

    @Test
    fun givenCrlfFile_whenSearching_thenLinesHaveNoCarriageReturn() = runBlocking {
        File(projectRoot, "win.sh").writeText("echo\r\ncurl -s localhost\r\n")

        val result = handler.execute(mapOf("query" to "curl", "project_dir" to "win.sh"))

        assertEquals("📄 win.sh:2 → curl -s localhost", result.data)
    }

    @Test
    fun givenMatchPastSnippetLength_whenSearching_thenSnippetKeepsMatch() = runBlocking {
        File(projectRoot, "min.js").writeText("a".repeat(200) + "curl" + "b".repeat(200))

        val result = handler.execute(mapOf("query" to "curl", "project_dir" to "min.js"))

        val snippet = result.data.orEmpty().substringAfter("→ ")
        assertTrue("Snippet lost the match: $snippet", snippet.contains("curl"))
        assertTrue(snippet.startsWith("…") && snippet.endsWith("…"))
    }

    @Test
    fun givenMatchLongerThanSnippetWindow_whenSearching_thenSnippetKeepsWholeMatch() = runBlocking {
        val query = "implementation(\"androidx.compose.material3:material3:1.2.0\") // pinned for M3 theming"
        File(projectRoot, "build.gradle.kts").writeText("    " + query + " and a trailing note that runs on")

        val result = handler.execute(mapOf("query" to query, "project_dir" to "build.gradle.kts"))

        val snippet = result.data.orEmpty().substringAfter("→ ")
        assertTrue("Snippet cut the match: $snippet", snippet.contains(query))
    }

    @Test
    fun givenUnreadableFileInTree_whenSearchingContents_thenSkippedAndOthersFound() = runBlocking {
        File(projectRoot, "locked.txt").apply { writeText("curl"); setReadable(false) }
        File(projectRoot, "open.txt").writeText("curl")

        val result = handler.execute(mapOf("query" to "curl", "search_in_contents" to true))

        assertTrue("Expected success, got: ${result.message}", result.success)
        assertTrue(result.data.orEmpty().contains("open.txt:1 → curl"))
    }

    @Test
    fun givenDirectory_whenSearching_thenPathsAreProjectRelative() = runBlocking {
        File(projectRoot, "app").mkdirs()
        File(projectRoot, "app/Curl.kt").writeText("x")
        File(projectRoot, "app/Other.kt").writeText("curl")

        val result = handler.execute(mapOf("query" to "curl", "project_dir" to "app", "search_in_contents" to true))

        val data = result.data.orEmpty()
        assertFalse("Leaked absolute path: $data", data.contains(projectRoot.absolutePath))
        assertTrue(data.contains("📄 [FILE] app${File.separator}Curl.kt"))
        assertTrue(data.contains("📄 app${File.separator}Other.kt:1 → curl"))
    }
}
