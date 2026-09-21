package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.handlers.BuiltInToolHandlers
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The progress rules on their own, without a loop around them: what counts as the same action,
 * which guard wins when two could fire, and when a counter goes back to zero.
 */
class ToolCallProgressGuardTest {

    private fun guard(repeats: Int = 2, stale: Int = 3, mutating: Set<String> = setOf("edit_file")) =
        ToolCallProgressGuard(
            maxConsecutiveRepeats = repeats,
            maxTurnsWithoutProgress = stale,
            pathsOf = { call -> setOfNotNull(call.args["file_path"]?.toString()) },
            changesPaths = { call -> call.name in mutating },
        )

    /** The guard wired the way the chat wires it, from the handlers' own argument declarations. */
    private fun realGuard(stale: Int = 3): ToolCallProgressGuard {
        val builtIns = BuiltInToolHandlers.create(mockk<PluginContext>(relaxed = true))
            .associateBy { it.toolName }
        return ToolCallProgressGuard(
            maxConsecutiveRepeats = 2,
            maxTurnsWithoutProgress = stale,
            pathsOf = { call -> builtIns[call.name]?.pathsIn(call.args).orEmpty() },
            changesPaths = { call -> builtIns[call.name]?.mutatesProject == true },
        )
    }

    private fun call(name: String, path: String = "A.kt") =
        listOf(ToolCall(name, mapOf("file_path" to path)))

    private val ok = listOf(ToolResult.success("ok"))
    private val failed = listOf(ToolResult.failure("nope"))

    @Test
    fun givenABatchNeverSeenBefore_whenInspected_thenItProceeds() {
        val guard = guard()

        assertEquals(ToolCallProgressGuard.Verdict.PROCEED, guard.inspect(call("read_file")))
        assertEquals(ToolCallProgressGuard.Verdict.PROCEED, guard.inspect(call("list_files")))
    }

    @Test
    fun givenTheSameToolOnADifferentArgument_whenInspected_thenItIsADistinctAction() {
        val guard = guard()
        guard.inspect(call("read_file", "A.kt"))
        guard.recordResults(ok)

        assertEquals(ToolCallProgressGuard.Verdict.PROCEED, guard.inspect(call("read_file", "B.kt")))
    }

    @Test
    fun givenArgumentsInAnotherOrder_whenInspected_thenTheBatchIsStillTheSameAction() {
        val guard = guard()
        guard.inspect(listOf(ToolCall("read_file", linkedMapOf("file_path" to "A.kt", "limit" to 10))))
        guard.recordResults(ok)

        val reordered = listOf(ToolCall("read_file", linkedMapOf("limit" to 10, "file_path" to "A.kt")))
        assertEquals(ToolCallProgressGuard.Verdict.ASSUME_COMPLETE, guard.inspect(reordered))
    }

    @Test
    fun givenTheBatchThatJustSucceededReissued_whenInspected_thenTheRunReadsAsComplete() {
        val guard = guard()
        guard.inspect(call("read_file"))
        guard.recordResults(ok)

        assertEquals(ToolCallProgressGuard.Verdict.ASSUME_COMPLETE, guard.inspect(call("read_file")))
    }

    @Test
    fun givenOneFailingBatchRepeated_whenInspected_thenItStopsAtTheRepeatLimit() {
        val guard = guard(repeats = 2)

        assertEquals(ToolCallProgressGuard.Verdict.PROCEED, guard.inspect(call("list_files")))
        guard.recordResults(failed)
        assertEquals(ToolCallProgressGuard.Verdict.PROCEED, guard.inspect(call("list_files")))
        guard.recordResults(failed)
        assertEquals(ToolCallProgressGuard.Verdict.REPEATED, guard.inspect(call("list_files")))
    }

    @Test
    fun givenARotationBetweenSeenActions_whenInspected_thenItStopsAsCycling() {
        val guard = guard(stale = 3)
        // Three distinct actions, so no two consecutive turns ever match: only the seen-set sees it.
        val cycle = listOf(call("open_file"), call("read_build_output"), call("search_project"))
        cycle.forEach { guard.inspect(it); guard.recordResults(ok) }

        assertEquals(ToolCallProgressGuard.Verdict.PROCEED, guard.inspect(cycle[0]))
        guard.recordResults(ok)
        assertEquals(ToolCallProgressGuard.Verdict.PROCEED, guard.inspect(cycle[1]))
        guard.recordResults(ok)
        assertEquals(ToolCallProgressGuard.Verdict.CYCLING, guard.inspect(cycle[2]))
        assertEquals(3, guard.staleTurns)
    }

    @Test
    fun givenEditsFollowedByReReads_whenInspected_thenVerifyingItsOwnWorkIsNotCycling() {
        val guard = guard(stale = 3)
        val turns = listOf(
            call("search_project", "foo"), call("read_file", "A.kt"), call("edit_file", "A.kt"),
            call("read_file", "B.kt"), call("edit_file", "B.kt"),
        )
        turns.forEach { guard.inspect(it); guard.recordResults(ok) }

        // The two edits changed what a re-read returns, so none of these three is a repeat.
        assertEquals(ToolCallProgressGuard.Verdict.PROCEED, guard.inspect(call("read_file", "A.kt")))
        guard.recordResults(ok)
        assertEquals(ToolCallProgressGuard.Verdict.PROCEED, guard.inspect(call("read_file", "B.kt")))
        guard.recordResults(ok)
        assertEquals(
            ToolCallProgressGuard.Verdict.PROCEED,
            guard.inspect(call("search_project", "foo")),
        )
    }

    @Test
    fun givenOneFileRewrittenBackAndForth_whenInspected_thenItStopsAsCycling() {
        val guard = guard(stale = 3)
        val x = listOf(ToolCall("edit_file", mapOf("file_path" to "A.kt", "content" to "X")))
        val y = listOf(ToolCall("edit_file", mapOf("file_path" to "A.kt", "content" to "Y")))
        listOf(x, y, x, y).forEach { guard.inspect(it); guard.recordResults(ok) }

        // Each edit invalidates reads of A.kt, but never the other edit of it.
        assertEquals(ToolCallProgressGuard.Verdict.CYCLING, guard.inspect(x))
    }

    @Test
    fun givenAnEditReissuedBetweenReReads_whenInspected_thenItStopsAsCycling() {
        val guard = guard(stale = 3)
        val turns = listOf(call("edit_file"), call("read_file"), call("edit_file"), call("read_file"))
        turns.forEach { guard.inspect(it); guard.recordResults(ok) }

        // Only an edit the run had not already made invalidates the read that follows it.
        assertEquals(ToolCallProgressGuard.Verdict.CYCLING, guard.inspect(call("edit_file")))
    }

    @Test
    fun givenANewActionAfterStaleTurns_whenInspected_thenTheStaleCounterResets() {
        val guard = guard(stale = 3)
        guard.inspect(call("read_file", "A.kt"))
        guard.recordResults(ok)
        guard.inspect(call("read_file", "B.kt"))
        guard.recordResults(failed)
        guard.inspect(call("read_file", "A.kt"))
        guard.recordResults(failed)
        assertEquals(1, guard.staleTurns)

        assertEquals(ToolCallProgressGuard.Verdict.PROCEED, guard.inspect(call("read_file", "C.kt")))
        assertEquals(0, guard.staleTurns)
    }

    @Test
    fun givenNoBatchHasRunYet_whenAsked_thenTheLastBatchHasNotFailed() {
        val guard = guard()

        assertFalse(guard.lastBatchFailed)
    }

    @Test
    fun givenAnEmptyResultList_whenRecorded_thenTheBatchCountsAsFailed() {
        val guard = guard()
        guard.inspect(call("read_file"))

        guard.recordResults(emptyList())

        assertTrue(guard.lastBatchFailed)
    }

    @Test
    fun givenOneFailureAmongSuccesses_whenRecorded_thenTheBatchCountsAsFailed() {
        val guard = guard()
        guard.inspect(call("read_file"))

        guard.recordResults(listOf(ToolResult.success("ok"), ToolResult.failure("nope")))

        assertTrue(guard.lastBatchFailed)
    }

    @Test
    fun givenAnEditSpellingThePathArgPath_whenInspected_thenItStillInvalidatesTheReadOfThatFile() {
        val guard = realGuard()
        val read = listOf(ToolCall("read_file", mapOf("file_path" to "A.kt")))
        guard.inspect(read)
        guard.recordResults(ok)
        // The near-miss key the executor remaps: the guard has to read the call the same way.
        val edit = mapOf("path" to "A.kt", "old_string" to "a", "new_string" to "b")
        guard.inspect(listOf(ToolCall("edit_file", edit)))
        guard.recordResults(ok)

        assertEquals(ToolCallProgressGuard.Verdict.PROCEED, guard.inspect(read))
    }

    @Test
    fun givenADependencyAddedWithNoBuildFile_whenInspected_thenItInvalidatesTheReadOfTheDefault() {
        val guard = realGuard()
        val read = listOf(ToolCall("read_file", mapOf("file_path" to "app/build.gradle.kts")))
        guard.inspect(read)
        guard.recordResults(ok)
        // build_file is optional; the handler defaults it, so the write still names a path.
        guard.inspect(listOf(ToolCall("add_dependency", mapOf("dependency" to "a:b:1"))))
        guard.recordResults(ok)

        assertEquals(ToolCallProgressGuard.Verdict.PROCEED, guard.inspect(read))
    }
}
