package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.prompt.ToolResultsPrompt
import com.itsaky.androidide.plugins.aicore.prompt.config.DirectoryPromptConfigSource.Companion.shippedConfig
import com.itsaky.androidide.plugins.aicore.tool.ToolCall
import com.itsaky.androidide.plugins.aicore.tool.ToolOutputBudget
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for [ReadFileHandler]: a file larger than the loop's per-result budget is read in
 * pages that follow the `Next:` hint, never meet the loop's own cut, and join back to the exact file.
 */
class ReadFileHandlerTest {

    private lateinit var projectRoot: File
    private val context: PluginContext = mockk(relaxed = true)
    private val handler = ReadFileHandler(context)

    @Before
    fun setup() {
        projectRoot = Files.createTempDirectory("readfile-project").toFile().canonicalFile
        PathGuard.setProjectRootForTesting(projectRoot.absolutePath)
    }

    @After
    fun tearDown() {
        PathGuard.setProjectRootForTesting(null)
        projectRoot.deleteRecursively()
    }

    private fun createFile(relative: String, content: String): File =
        File(projectRoot, relative).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    /** About 37 KB of mixed line lengths, CRLF and LF, tabs and non-ASCII, with no final newline. */
    private fun mixedContent(): String = buildString {
        var line = 1
        while (length < 37_000) {
            val ending = if (line % 3 == 0) "\r\n" else "\n"
            append("\t".repeat(line % 4)).append("line $line ").append("é😀ß".repeat(line % 7))
            append("x".repeat((line * 37) % 300)).append(ending)
            line++
        }
        append("last line without a newline")
    }

    /** Follows each page's `Next:` hint from the first call until a page has none. */
    private fun readAllPages(path: String): List<ToolResult> = runBlocking {
        val pages = mutableListOf<ToolResult>()
        var args: Map<String, Any?>? = mapOf("file_path" to path)
        while (args != null) {
            val result = handler.execute(args)
            assertTrue("page failed: ${result.message}", result.success)
            pages += result
            args = nextArgs(result.message)
            assertTrue("runaway paging", pages.size < 200)
        }
        pages
    }

    private fun nextArgs(message: String): Map<String, Any?>? {
        val hint = NEXT_HINT.find(message) ?: return null
        val (path, line, column) = hint.destructured
        return buildMap {
            put("file_path", path)
            put("start_line", line)
            if (column.isNotEmpty()) put("start_column", column)
        }
    }

    /** The turn the agent loop would feed back for [result], at the default budget. */
    private fun fedBack(result: ToolResult): String = ToolResultsPrompt.render(
        shippedConfig, "respond", ToolOutputBudget.DEFAULT_CHARS,
        listOf(ToolCall("read_file", emptyMap())), listOf(result),
    )

    @Test
    fun givenA37KbMixedFile_whenReadByFollowingNext_thenThePagesJoinToTheExactFile() {
        val content = mixedContent()
        createFile("app/src/Main.kt", content)

        val pages = readAllPages("app/src/Main.kt")

        assertTrue("expected several pages, got ${pages.size}", pages.size > 5)
        assertEquals(content, pages.joinToString("") { it.data.orEmpty() })
        pages.forEach { assertFalse("the loop's cut fired", fedBack(it).contains("…[truncated")) }
        assertTrue(pages.last().message.endsWith("End of file."))
    }

    @Test
    fun givenA37KbSingleLineFile_whenReadByFollowingNext_thenTheColumnPagesJoinToTheExactFile() {
        val content = buildString { while (length < 37_000) append("abc😀") }
        createFile("data.json", content)

        val pages = readAllPages("data.json")

        assertEquals(content, pages.joinToString("") { it.data.orEmpty() })
        assertTrue(pages[1].message.startsWith("From line 1 column "))
        pages.forEach { assertFalse("the loop's cut fired", fedBack(it).contains("…[truncated")) }
    }

    @Test
    fun givenASmallFile_whenRead_thenItIsReturnedWholeInOnePage() = runBlocking {
        createFile("a.txt", "one\ntwo\n")

        val result = handler.execute(mapOf("file_path" to "a.txt"))

        assertEquals("one\ntwo\n", result.data)
        assertEquals("Lines 1-2 of 2 in a.txt (8 characters). End of file.", result.message)
    }

    @Test
    fun givenALineRange_whenRead_thenOnlyThoseLinesComeBackWithNoNextHint() = runBlocking {
        createFile("a.txt", (1..10).joinToString("\n") { "line$it" })

        val result = handler.execute(mapOf("file_path" to "a.txt", "start_line" to "3", "end_line" to 4))

        assertEquals("line3\nline4\n", result.data)
        assertEquals("Lines 3-4 of 10 in a.txt (60 characters). End of the requested range (end_line=4).", result.message)
    }

    @Test
    fun givenALineRangeTooLongForOnePage_whenRead_thenTheNextHintKeepsTheEndLine() = runBlocking {
        createFile("a.txt", (1..10).joinToString("\n") { "x".repeat(30) })
        val small = ReadFileHandler(context, ToolOutputBudget { 160 })

        val result = small.execute(mapOf("file_path" to "a.txt", "start_line" to 2, "end_line" to 9))

        assertTrue(result.message, result.message.endsWith("end_line=9"))
        assertTrue(result.message.length + 1 + result.data!!.length <= 160)
    }

    @Test
    fun givenAStartLinePastTheEnd_whenRead_thenItFailsNamingTheLineCount() = runBlocking {
        createFile("a.txt", "one\ntwo")

        val result = handler.execute(mapOf("file_path" to "a.txt", "start_line" to 5))

        assertFalse(result.success)
        assertTrue(result.message.contains("(2 lines)"))
    }

    @Test
    fun givenANonNumericStartLine_whenRead_thenItFailsNamingTheArgument() = runBlocking {
        createFile("a.txt", "one")

        val result = handler.execute(mapOf("file_path" to "a.txt", "start_line" to "top"))

        assertFalse(result.success)
        assertTrue(result.message.contains("start_line"))
    }

    @Test
    fun givenAnEmptyFile_whenRead_thenItSaysSoWithoutData() = runBlocking {
        createFile("empty.txt", "")

        val result = handler.execute(mapOf("file_path" to "empty.txt"))

        assertTrue(result.success)
        assertNull(result.data)
        assertTrue(result.message.contains("empty"))
    }

    private companion object {
        val NEXT_HINT = Regex("""Next: read_file file_path=(\S+) start_line=(\d+)(?: start_column=(\d+))?""")
    }

    @Test
    fun givenAStringifiedFloatLineNumber_whenRead_thenItReadsLikeTheWholeNumber() = runBlocking {
        createFile("a.txt", (1..5).joinToString("\n") { "line$it" })

        val result = handler.execute(mapOf("file_path" to "a.txt", "start_line" to "2.0", "end_line" to 2.0))

        assertEquals("line2\n", result.data)
    }

    @Test
    fun givenANonNumericColumn_whenRead_thenItFailsNamingTheArgument() = runBlocking {
        createFile("a.txt", "one")

        val result = handler.execute(mapOf("file_path" to "a.txt", "start_column" to "left"))

        assertFalse(result.success)
        assertEquals("start_column must be a whole number, was 'left'", result.message)
    }

    @Test
    fun givenAnEndLinePastTheEnd_whenRead_thenItSaysEndOfFileNotEndOfRange() = runBlocking {
        createFile("a.txt", "one\ntwo")

        val result = handler.execute(mapOf("file_path" to "a.txt", "end_line" to 9))

        assertTrue(result.message, result.message.endsWith("End of file."))
    }
}
