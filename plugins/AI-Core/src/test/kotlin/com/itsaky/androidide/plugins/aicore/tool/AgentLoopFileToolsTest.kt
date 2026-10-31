package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.ServiceRegistry
import com.itsaky.androidide.plugins.aicore.tool.handlers.CreateFileHandler
import com.itsaky.androidide.plugins.aicore.tool.handlers.EditFileHandler
import com.itsaky.androidide.plugins.aicore.tool.handlers.PathGuard
import com.itsaky.androidide.plugins.aicore.tool.handlers.UpdateFileHandler
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage.Role
import com.itsaky.androidide.plugins.services.SelectionRange
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [AgentLoop]'s check for a change not made, driven with the real file-writing tools and keyed
 * through a real [ToolRouter] as ChatViewModel wires it: a repeat of an edit that worked is taken for
 * made only when nothing has written its file since, however each call spelled the path, and in the
 * editor as on disk.
 */
class AgentLoopFileToolsTest {

    private lateinit var root: File
    private lateinit var router: ToolRouter
    // The editor's open buffers by canonical path; a file is open only once it has one.
    private val buffers = mutableMapOf<String, String>()

    @Before
    fun setup() {
        root = Files.createTempDirectory("agentloop-files").toFile().canonicalFile
        PathGuard.setProjectRootForTesting(root.absolutePath)
        val context = mockk<PluginContext>()
        val services = mockk<ServiceRegistry>()
        val editor = mockk<IdeEditorService>(relaxed = true)
        every { editor.getFileContent(any()) } answers { buffers[firstArg<File>().canonicalPath] }
        every { editor.replaceRange(any(), any(), any()) } answers {
            val path = firstArg<File>().canonicalPath
            val range = secondArg<SelectionRange>()
            val text = buffers.getValue(path)
            fun offset(line: Int, column: Int): Int {
                var at = 0
                repeat(line) { at = text.indexOf('\n', at) + 1 }
                return at + column
            }
            buffers[path] = text.substring(0, offset(range.startLine, range.startColumn)) + thirdArg<String>() +
                text.substring(offset(range.endLine, range.endColumn))
            true
        }
        coEvery { editor.saveFile(any()) } answers {
            val file = firstArg<File>()
            file.writeText(buffers.getValue(file.canonicalPath))
            true
        }
        every { context.services } returns services
        every { context.logger } returns mockk(relaxed = true)
        every { services.get(IdeEditorService::class.java) } returns editor
        router = ToolRouter(listOf(EditFileHandler(context, Dispatchers.Unconfined), UpdateFileHandler(context), CreateFileHandler(context)))
    }

    @After
    fun tearDown() {
        PathGuard.setProjectRootForTesting(null)
        root.deleteRecursively()
    }

    /** Runs one batch of [script] per turn; returns the result and the loop's notices. */
    private suspend fun drive(vararg script: List<ToolCall>): Pair<AgentLoop.Result, List<String>> {
        var turn = 0
        val notices = mutableListOf<String>()
        val loop = AgentLoop(
            formatToolResults = { _, results -> results.joinToString("\n") { "${it.success}: ${it.message}" } },
            extractToolCalls = { text -> script[text.toInt()] },
            diagnoseUnparsedReply = { null },
            terminalTool = "respond",
            unfinishedTurn = { "finish" },
            unappliedWriteTurn = { "UNAPPLIED" },
        )
        val result = loop.run(
            history = mutableListOf(ChatMessage(Role.USER, "change the title")),
            generate = { AgentLoop.ModelReply((turn++).coerceAtMost(script.size - 1).toString()) },
            executeTools = { calls ->
                calls.map { call ->
                    val handler = router.getHandler(call.name)!!
                    when (val validation = handler.validate(normalizeToolArgs(handler, call.args))) {
                        is Validation.Accepted -> handler.execute(validation.args)
                        is Validation.Rejected -> validation.result
                    }
                }
            },
            pathsOf = { call -> router.getHandler(call.name)?.pathsIn(call.args).orEmpty() },
            changesPaths = { call -> router.getHandler(call.name)?.mutatesProject == true },
            writesFiles = { call -> router.getHandler(call.name)?.writesFiles == true },
            changeOf = router::changeOf,
            canonicalName = { name -> router.getHandler(name)?.toolName ?: name },
            events = object : AgentLoop.Events {
                override suspend fun onUnappliedWriteAnswer(turn: Int) { notices += "unapplied" }
                override suspend fun onChangeNotMade(turn: Int) { notices += "not-made" }
            },
        )
        return result to notices
    }

    private val respond = listOf(ToolCall("respond", mapOf("message" to "Done.")))
    private val original = "class A {\n    val title = \"Hello\"\n}\n"
    private val titleEdit = ToolCall(
        "edit_file",
        mapOf("file_path" to "app/A.kt", "old_string" to "    val title = \"Hello\"", "new_string" to "    val title = \"Hi\""),
    )
    /** A whole-file rewrite from a read taken before the edit: Hello back, re-indented, so a repeat finds neither text. */
    private fun staleRewrite(path: String) =
        ToolCall("update_file", mapOf("file_path" to path, "content" to "class A {\n  val title = \"Hello\"\n}\n"))

    private fun file(path: String, text: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }

    @Test
    fun givenAStaleRewriteSpellingThePathAnotherWay_whenTheEditIsRepeated_thenTheChangeIsReportedNotMade() = runTest {
        val file = file("app/A.kt", original)

        val (result, notices) = drive(listOf(titleEdit), listOf(staleRewrite("app/../app/./A.kt")), listOf(titleEdit), respond, respond)

        assertTrue(file.readText().contains("\"Hello\""))
        assertEquals(AgentLoop.StopReason.ABANDONED, result.reason)
        assertEquals(listOf("unapplied", "not-made"), notices)
    }

    @Test
    fun givenAnEditInTheEditorThenARewriteOnDisk_whenTheEditIsRepeated_thenTheChangeIsReportedNotMade() = runTest {
        val file = file("app/A.kt", original)
        buffers[file.canonicalPath] = original
        val inEditor = titleEdit.copy(args = titleEdit.args + ("file_path" to "./app/A.kt"))

        val (result, notices) = drive(listOf(inEditor), listOf(staleRewrite("app/A.kt")), listOf(inEditor), respond, respond)

        assertEquals(AgentLoop.StopReason.ABANDONED, result.reason)
        assertEquals(listOf("unapplied", "not-made"), notices)
    }

    @Test
    fun givenAStaleRewriteInTheSameBatchAfterTheEdit_whenTheEditIsRepeated_thenTheChangeIsReportedNotMade() = runTest {
        file("app/A.kt", original)

        val (result, notices) = drive(listOf(titleEdit, staleRewrite("app/A.kt")), listOf(titleEdit), respond, respond)

        assertEquals(AgentLoop.StopReason.ABANDONED, result.reason)
        assertEquals(listOf("unapplied", "not-made"), notices)
    }

    @Test
    fun givenAnUpdateToAnotherFile_whenTheEditIsRepeatedAfterIt_thenTheChangeIsTakenForMade() = runTest {
        val file = file("app/A.kt", original)
        file("app/B.kt", "class B\n")
        val updateOther = ToolCall("update_file", mapOf("file_path" to "./app/B.kt", "content" to "class B {}\n"))

        val (result, notices) = drive(listOf(titleEdit), listOf(updateOther), listOf(titleEdit), respond)

        assertTrue(file.readText().contains("\"Hi\""))
        assertEquals(AgentLoop.StopReason.COMPLETED, result.reason)
        assertFalse("not-made" in notices)
    }

    @Test
    fun givenAnEditThatWorked_whenRepeatedInTheSameBatch_thenTheChangeIsTakenForMade() = runTest {
        val file = file("app/A.kt", original)

        val (result, notices) = drive(listOf(titleEdit, titleEdit), respond)

        assertTrue(file.readText().contains("\"Hi\""))
        assertEquals(AgentLoop.StopReason.COMPLETED, result.reason)
        assertFalse("not-made" in notices)
    }

    @Test
    fun givenACreateThatWorked_whenTheSameCreateOfAnotherExistingFileFails_thenItIsReportedNotMade() = runTest {
        // The same change, but to another file, which already held something else.
        val content = "<resources/>\n"
        val other = file("app/res/values-es/strings.xml", "<resources><string name=\"a\">b</string></resources>\n")
        fun create(path: String) = ToolCall("create_file", mapOf("file_path" to path, "content" to content))

        val (result, notices) = drive(
            listOf(create("app/res/values/strings.xml")), listOf(create("app/res/values-es/strings.xml")), respond, respond,
        )

        assertFalse(other.readText() == content)
        assertEquals(AgentLoop.StopReason.ABANDONED, result.reason)
        assertEquals(listOf("unapplied", "not-made"), notices)
    }
}
