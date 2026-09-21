package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.handlers.PathGuard
import kotlinx.coroutines.delay
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
 * Unit tests for the [Executor] path-containment pre-guard, in particular the
 * [ToolHandler.resolvesPathsInternally] opt-out that lets read-only handlers rescue odd paths such as
 * a model-supplied "/.gitignore", while write tools stay guarded.
 */
class ExecutorTest {

    private lateinit var projectRoot: File

    /** A handler that records whether it was dispatched and always succeeds. */
    private class FakeHandler(
        override val toolName: String,
        override val resolvesPathsInternally: Boolean,
    ) : ToolHandler {
        override val description = "fake"
        override val requiresApproval = false
        override val pathArgs = listOf("file_path")
        var dispatched = false
            private set

        override suspend fun execute(args: Map<String, Any?>): ToolResult {
            dispatched = true
            return ToolResult.success("ran")
        }
    }

    /** Stands in for [handlers.EditFileHandler]: same tool name, aliases and path args. */
    private class AliasingHandler : ToolHandler {
        override val toolName = "edit_file"
        override val description = "fake edit"
        override val requiresApproval = false
        override val pathArgs = listOf("file_path")
        override val argAliases = mapOf("old" to "old_string", "new" to "new_string")
        var seenArgs: Map<String, Any?>? = null
            private set

        override suspend fun execute(args: Map<String, Any?>): ToolResult {
            seenArgs = args
            return ToolResult.success("edited")
        }
    }

    @Before
    fun setup() {
        projectRoot = Files.createTempDirectory("executor-project").toFile().canonicalFile
        PathGuard.setProjectRootForTesting(projectRoot.absolutePath)
    }

    @After
    fun tearDown() {
        PathGuard.setProjectRootForTesting(null)
    }

    private fun executorFor(handler: ToolHandler): Executor =
        Executor(ToolRouter(listOf(handler)), ToolApprovalManager())

    @Test
    fun givenAnInternallyResolvingHandler_whenExecutingAnEscapingPath_thenTheEscapePreGuardIsBypassed() = runBlocking {
        val handler = FakeHandler("fake_internal", resolvesPathsInternally = true)
        val executor = executorFor(handler)

        // "/escape.txt" is out of root, but an internally-resolving handler still gets dispatched.
        val results = executor.execute(listOf(ToolCall("fake_internal", mapOf("file_path" to "/escape.txt"))))

        assertTrue("handler should have been dispatched", handler.dispatched)
        assertTrue("result should be the handler's success", results.single().success)
    }

    @Test
    fun givenADefaultHandler_whenExecutingAPathThatEscapesTheProjectRoot_thenItIsRejected() = runBlocking {
        val handler = FakeHandler("fake_guarded", resolvesPathsInternally = false)
        val executor = executorFor(handler)

        val results = executor.execute(listOf(ToolCall("fake_guarded", mapOf("file_path" to "/escape.txt"))))

        assertFalse("handler must NOT run for an escaping write path", handler.dispatched)
        assertFalse(results.single().success)
        assertTrue(results.single().message.contains("outside the project directory"))
    }

    @Test
    fun givenOpenFileWithAPathAlias_whenExecuting_thenPathIsRemappedToFilePathAndItRuns() = runBlocking {
        // open_file requires file_path, so a model emitting {"path":...} is remapped, not rejected.
        val handler = object : ToolHandler {
            override val toolName = "open_file"
            override val description = "fake open"
            override val requiresApproval = false
            override val pathArgs = listOf("file_path")
            override val resolvesPathsInternally = true
            var seenArgs: Map<String, Any?>? = null
            override suspend fun execute(args: Map<String, Any?>): ToolResult {
                seenArgs = args
                return ToolResult.success("opened")
            }
        }
        val executor = executorFor(handler)

        val results = executor.execute(listOf(ToolCall("open_file", mapOf("path" to "MainActivity.java"))))

        assertTrue("open_file with a path alias should run", results.single().success)
        assertEquals("MainActivity.java", handler.seenArgs?.get("file_path"))
    }

    @Test
    fun givenEditFileWithAliasedSnippetArgs_whenExecuting_thenTheyAreRemappedToTheCanonicalKeys() = runBlocking {
        // Small models reach for "old"/"new" as often as the real names; rejecting costs a turn.
        val handler = AliasingHandler()
        val executor = executorFor(handler)

        val results = executor.execute(
            listOf(
                ToolCall(
                    "edit_file",
                    mapOf("file_path" to "Main.kt", "old" to "a = 1", "new" to "a = 2"),
                )
            )
        )

        assertTrue("aliased edit_file should run", results.single().success)
        assertEquals("a = 1", handler.seenArgs?.get("old_string"))
        assertEquals("a = 2", handler.seenArgs?.get("new_string"))
    }

    @Test
    fun givenBothAnAliasAndItsCanonicalKey_whenExecuting_thenTheCanonicalValueWins() = runBlocking {
        val handler = AliasingHandler()
        val executor = executorFor(handler)

        executor.execute(
            listOf(
                ToolCall(
                    "edit_file",
                    mapOf(
                        "file_path" to "Main.kt",
                        "old_string" to "canonical",
                        "old" to "alias",
                        "new_string" to "x",
                    ),
                )
            )
        )

        assertEquals("canonical", handler.seenArgs?.get("old_string"))
    }

    @Test
    fun givenEditFileMissingOldString_whenExecuting_thenItIsRejectedBeforeTheHandlerRuns() = runBlocking {
        val handler = AliasingHandler()
        val executor = executorFor(handler)

        val results = executor.execute(
            listOf(ToolCall("edit_file", mapOf("file_path" to "Main.kt", "new_string" to "x")))
        )

        assertFalse(results.single().success)
        assertTrue(results.single().message.contains("old_string"))
        assertEquals(null, handler.seenArgs)
    }

    @Test
    fun givenEditFileWithABlankNewString_whenExecuting_thenItStillRuns() = runBlocking {
        // A blank new_string is a deletion, so the empty-value check must not catch it.
        val handler = AliasingHandler()
        val executor = executorFor(handler)

        val results = executor.execute(
            listOf(
                ToolCall(
                    "edit_file",
                    mapOf("file_path" to "Main.kt", "old_string" to "gone", "new_string" to ""),
                )
            )
        )

        assertTrue("a deletion must reach the handler", results.single().success)
    }

    @Test
    fun givenAWhitespaceOnlyOldString_whenExecuting_thenItReachesTheHandler() = runBlocking {
        // Re-indenting is a legal edit whose old_string is whitespace, not a missing argument.
        val handler = AliasingHandler()
        val executor = executorFor(handler)

        val results = executor.execute(
            listOf(
                ToolCall(
                    "edit_file",
                    mapOf("file_path" to "Main.kt", "old_string" to "\n\n\n", "new_string" to "\n"),
                )
            )
        )

        assertTrue("whitespace is content, not a missing argument: ${results.single().message}", results.single().success)
        assertEquals("\n\n\n", handler.seenArgs?.get("old_string"))
    }

    @Test
    fun givenValidationThatThrows_whenExecuting_thenItBecomesAToolFailureRatherThanKillingTheRun() = runBlocking {
        // Unguarded, validate()'s SecurityException escaped execute() and aborted the whole run.
        var executed = false
        val handler = object : ToolHandler {
            override val toolName = "edit_file"
            override val description = "fake edit"
            override val requiresApproval = true
            override val pathArgs = listOf("file_path")
            override suspend fun validate(args: Map<String, Any?>): Validation =
                throw SecurityException("Plugin does not have access to file: Main.kt")

            override suspend fun execute(args: Map<String, Any?>): ToolResult {
                executed = true
                return ToolResult.success("edited")
            }
        }
        val approvalManager = ToolApprovalManager()
        val executor = Executor(ToolRouter(listOf(handler)), approvalManager)

        val results = executor.execute(
            listOf(
                ToolCall(
                    "edit_file",
                    mapOf("file_path" to "Main.kt", "old_string" to "a", "new_string" to "b"),
                )
            )
        )

        assertFalse(results.single().success)
        assertTrue(
            "the failure must name the cause; got: ${results.single().message}",
            results.single().message.contains("does not have access")
        )
        assertFalse("the handler must not run", executed)
        assertFalse("no approval may be requested", approvalManager.hasPendingApproval())
    }

    @Test
    fun givenAReadAndAWriteInOneBatch_whenExecuting_thenTheReadFinishesFirst() = runBlocking {
        // search_project + edit_file states a dependency the old parallel-writes schedule broke.
        val order = mutableListOf<String>()
        val read = object : ToolHandler {
            override val toolName = "search_project"
            override val description = "fake search"
            override val pathArgs = emptyList<String>()
            override suspend fun execute(args: Map<String, Any?>): ToolResult {
                delay(50)
                synchronized(order) { order.add("read-done") }
                return ToolResult.success("found")
            }
        }
        val write = object : ToolHandler {
            override val toolName = "edit_file"
            override val description = "fake edit"
            override val pathArgs = emptyList<String>()
            override suspend fun execute(args: Map<String, Any?>): ToolResult {
                synchronized(order) { order.add("write-start") }
                return ToolResult.success("edited")
            }
        }
        val executor = Executor(ToolRouter(listOf(read, write)), ToolApprovalManager())

        executor.execute(
            listOf(
                ToolCall("search_project", mapOf("query" to "MainActivity")),
                ToolCall("edit_file", mapOf("file_path" to "Main.kt", "old_string" to "a", "new_string" to "b")),
            )
        )

        assertEquals(listOf("read-done", "write-start"), order)
    }

    @Test
    fun givenAWriteThenARead_whenExecuting_thenTheWriteFinishesFirst() = runBlocking {
        // The mirror image: hoisting the read of create_file + read_file failed as "not found".
        val order = mutableListOf<String>()
        val write = object : ToolHandler {
            override val toolName = "create_file"
            override val description = "fake create"
            override val pathArgs = emptyList<String>()
            override suspend fun execute(args: Map<String, Any?>): ToolResult {
                delay(50)
                synchronized(order) { order.add("write-done") }
                return ToolResult.success("created")
            }
        }
        val read = object : ToolHandler {
            override val toolName = "read_file"
            override val description = "fake read"
            override val pathArgs = emptyList<String>()
            override suspend fun execute(args: Map<String, Any?>): ToolResult {
                synchronized(order) { order.add("read-start") }
                return ToolResult.success("contents")
            }
        }
        val executor = Executor(ToolRouter(listOf(write, read)), ToolApprovalManager())

        executor.execute(
            listOf(
                ToolCall("create_file", mapOf("file_path" to "Foo.kt", "content" to "x")),
                ToolCall("read_file", mapOf("file_path" to "Foo.kt")),
            )
        )

        assertEquals(listOf("write-done", "read-start"), order)
    }

    @Test
    fun givenConsecutiveReads_whenExecuting_thenTheyStillRunConcurrently() = runBlocking {
        // Segmenting must not cost concurrency: two adjacent reads have no dependency.
        val started = mutableListOf<String>()
        val handler = object : ToolHandler {
            override val toolName = "read_file"
            override val description = "fake read"
            override val pathArgs = emptyList<String>()
            override suspend fun execute(args: Map<String, Any?>): ToolResult {
                synchronized(started) { started.add(args["file_path"].toString()) }
                // Both calls must sit inside this delay at once; serialised, the second would not.
                delay(100)
                synchronized(started) { started.add("done:${args["file_path"]}") }
                return ToolResult.success("contents")
            }
        }
        val executor = Executor(ToolRouter(listOf(handler)), ToolApprovalManager())

        executor.execute(
            listOf(
                ToolCall("read_file", mapOf("file_path" to "A.kt")),
                ToolCall("read_file", mapOf("file_path" to "B.kt")),
            )
        )

        assertEquals(listOf("A.kt", "B.kt", "done:A.kt", "done:B.kt"), started)
    }

    @Test
    fun givenLooselyNamedReads_whenExecuting_thenTheyStillRunConcurrently() = runBlocking {
        // The router resolves `Read_File` and `  read_file ` to the same read-only handler, so the
        // batch must be scheduled on the registered name, not on the string the model emitted.
        val started = mutableListOf<String>()
        val handler = object : ToolHandler {
            override val toolName = "read_file"
            override val description = "fake read"
            override val pathArgs = emptyList<String>()
            override suspend fun execute(args: Map<String, Any?>): ToolResult {
                synchronized(started) { started.add(args["file_path"].toString()) }
                delay(100)
                synchronized(started) { started.add("done:${args["file_path"]}") }
                return ToolResult.success("contents")
            }
        }
        val executor = Executor(ToolRouter(listOf(handler)), ToolApprovalManager())

        executor.execute(
            listOf(
                ToolCall("Read_File", mapOf("file_path" to "A.kt")),
                ToolCall("  read_file ", mapOf("file_path" to "B.kt")),
            )
        )

        assertEquals(listOf("A.kt", "B.kt", "done:A.kt", "done:B.kt"), started)
    }

    @Test
    fun givenAReadWriteReadBatch_whenExecuting_thenResultsStayInInputOrder() = runBlocking {
        // Whatever the schedule, result[i] belongs to toolCalls[i]; the loop pairs them positionally.
        val read = object : ToolHandler {
            override val toolName = "read_file"
            override val description = "fake read"
            override val pathArgs = emptyList<String>()
            override suspend fun execute(args: Map<String, Any?>) =
                ToolResult.success("read:${args["file_path"]}")
        }
        val write = object : ToolHandler {
            override val toolName = "create_file"
            override val description = "fake create"
            override val pathArgs = emptyList<String>()
            override suspend fun execute(args: Map<String, Any?>) =
                ToolResult.success("wrote:${args["file_path"]}")
        }
        val executor = Executor(ToolRouter(listOf(read, write)), ToolApprovalManager())

        val results = executor.execute(
            listOf(
                ToolCall("read_file", mapOf("file_path" to "A.kt")),
                ToolCall("create_file", mapOf("file_path" to "B.kt", "content" to "x")),
                ToolCall("read_file", mapOf("file_path" to "C.kt")),
            )
        )

        assertEquals(
            listOf("read:A.kt", "wrote:B.kt", "read:C.kt"),
            results.map { it.message },
        )
    }

    @Test
    fun givenAHandlerThatRejectsInValidation_whenExecuting_thenItNeverReachesApprovalOrExecution() = runBlocking {
        // The whole point of validate(): a doomed call must not cost the user a dialog.
        var executed = false
        val handler = object : ToolHandler {
            override val toolName = "edit_file"
            override val description = "fake edit"
            override val requiresApproval = true
            override val pathArgs = listOf("file_path")
            override suspend fun validate(args: Map<String, Any?>): Validation =
                Validation.Rejected(
                    ToolResult.failure("new_string is identical to old_string — nothing to change")
                )

            override suspend fun execute(args: Map<String, Any?>): ToolResult {
                executed = true
                return ToolResult.success("edited")
            }
        }
        val approvalManager = ToolApprovalManager()
        val executor = Executor(ToolRouter(listOf(handler)), approvalManager)

        val results = executor.execute(
            listOf(
                ToolCall(
                    "edit_file",
                    mapOf("file_path" to "Main.kt", "old_string" to "x", "new_string" to "x"),
                )
            )
        )

        assertFalse(results.single().success)
        assertTrue(results.single().message.contains("identical"))
        assertFalse("the handler must not run", executed)
        assertFalse("no approval may be requested", approvalManager.hasPendingApproval())
    }

    @Test
    fun givenADefaultHandler_whenExecutingAnInProjectPath_thenItRuns() = runBlocking {
        val handler = FakeHandler("fake_guarded", resolvesPathsInternally = false)
        val executor = executorFor(handler)

        val results = executor.execute(listOf(ToolCall("fake_guarded", mapOf("file_path" to "notes.txt"))))

        assertTrue("in-project path should be allowed through", handler.dispatched)
        assertTrue(results.single().success)
    }
}
