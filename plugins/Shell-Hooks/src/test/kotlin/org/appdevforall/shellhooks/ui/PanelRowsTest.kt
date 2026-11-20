package org.appdevforall.shellhooks.ui

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import org.appdevforall.shellhooks.engine.Hook
import org.appdevforall.shellhooks.engine.HookLog
import org.appdevforall.shellhooks.engine.HookRun
import org.appdevforall.shellhooks.engine.Outcome
import org.appdevforall.shellhooks.engine.PendingTrust
import org.appdevforall.shellhooks.engine.ScriptSource
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PanelRowsTest {
    private val project = File("/projects/app")

    @Test
    fun theTabListsTheSwitchThenPendingScriptsThenRunsNewestFirst() {
        val log = HookLog()
        val older = run().also(log::record)
        val newer = run().also(log::record)
        val a = pending("a")
        val b = pending("b")

        assertEquals(
            listOf(
                PanelRow.Off,
                PanelRow.PendingTitle,
                PanelRow.Pending(a),
                PanelRow.Pending(b),
                PanelRow.RunsHeader,
                PanelRow.Run(newer),
                PanelRow.Run(older),
            ),
            PanelRow.of(isOn = false, runs = log.runs.value, pending = listOf(a, b)),
        )
    }

    @Test
    fun withNothingToShowOnlyTheRunsTitleAndTheEmptyNoteRemain() {
        assertEquals(listOf(PanelRow.RunsHeader, PanelRow.Empty), PanelRow.of(isOn = true, runs = emptyList(), pending = emptyList()))
    }

    @Test
    fun pendingScriptsPastTheCapAreCounted() {
        val all = (1..PanelRow.MAX_PENDING_ROWS + 3).map { pending("s$it") }

        val rows = PanelRow.of(isOn = true, runs = emptyList(), pending = all)

        assertEquals(PanelRow.MAX_PENDING_ROWS, rows.count { it is PanelRow.Pending })
        assertEquals(PanelRow.PendingMore(3), rows.single { it is PanelRow.PendingMore })
    }

    @Test
    fun aFinishedHookOnAFullLogInsertsOneRowRemovesTheOldestAndRebindsNoOther() {
        val log = HookLog()
        repeat(HookLog.DEFAULT_CAPACITY) { log.record(run()) }
        val pending = listOf(pending("a"))
        val before = PanelRow.of(isOn = true, runs = log.runs.value, pending = pending)

        log.record(run())
        val ops = diff(before, PanelRow.of(isOn = true, runs = log.runs.value, pending = pending))

        assertEquals(Ops(inserted = 1, removed = 1, changed = 0, moved = 0), ops)
    }

    @Test
    fun trustingAScriptRemovesOnlyItsRowAndTheLastOneTakesTheTitleWithIt() {
        val runs = listOf(run(), run())
        val a = pending("a")
        val b = pending("b")

        assertEquals(Ops(removed = 1), diff(PanelRow.of(true, runs, listOf(a, b)), PanelRow.of(true, runs, listOf(b))))
        assertEquals(Ops(removed = 2), diff(PanelRow.of(true, runs, listOf(b)), PanelRow.of(true, runs, emptyList())))
    }

    @Test
    fun aScriptSeenAgainWithNewContentRebindsItsRow() {
        val first = pending("a")
        val changed = first.copy(sha256 = "other")

        assertEquals(Ops(changed = 1), diff(PanelRow.of(true, emptyList(), listOf(first)), PanelRow.of(true, emptyList(), listOf(changed))))
    }

    @Test
    fun theSwitchTurningOffAddsOneRowAtTheTop() {
        val runs = listOf(run())

        assertEquals(Ops(inserted = 1), diff(PanelRow.of(true, runs, emptyList()), PanelRow.of(false, runs, emptyList())))
    }

    private fun run() =
        HookRun(
            startedAtMillis = 0,
            hook = Hook.FILE_SAVED,
            script = File("/hooks/file-saved"),
            source = ScriptSource.GLOBAL,
            target = "/projects/app/a.kt",
            outcome = Outcome.SUCCEEDED,
            exitCode = 0,
            durationMillis = 1,
            output = "ok",
        )

    private fun pending(path: String) = PendingTrust(project, path, File(project, path), "sha-$path")

    private data class Ops(
        var inserted: Int = 0,
        var removed: Int = 0,
        var changed: Int = 0,
        var moved: Int = 0,
    ) : ListUpdateCallback {
        override fun onInserted(
            position: Int,
            count: Int,
        ) {
            inserted += count
        }

        override fun onRemoved(
            position: Int,
            count: Int,
        ) {
            removed += count
        }

        override fun onMoved(
            fromPosition: Int,
            toPosition: Int,
        ) {
            moved++
        }

        override fun onChanged(
            position: Int,
            count: Int,
            payload: Any?,
        ) {
            changed += count
        }
    }

    // What ListAdapter's differ would tell the RecyclerView to do.
    private fun diff(
        old: List<PanelRow>,
        new: List<PanelRow>,
    ): Ops {
        val result =
            DiffUtil.calculateDiff(
                object : DiffUtil.Callback() {
                    override fun getOldListSize() = old.size

                    override fun getNewListSize() = new.size

                    override fun areItemsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ) = PanelRowDiff.areItemsTheSame(old[oldItemPosition], new[newItemPosition])

                    override fun areContentsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ) = PanelRowDiff.areContentsTheSame(old[oldItemPosition], new[newItemPosition])
                },
            )
        return Ops().also(result::dispatchUpdatesTo)
    }
}
