package org.appdevforall.buildanimation

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class BuildAnimationStateTest {

    /** Runs a delayed task only when [advance] is called with at least its delay since posting. */
    private class FakeScheduler : Scheduler {
        val pending = mutableListOf<Pair<Long, Runnable>>()

        override fun postDelayed(delayMs: Long, task: Runnable) {
            pending += delayMs to task
        }

        override fun cancel(task: Runnable) {
            pending.removeAll { it.second === task }
        }

        fun advance(byMs: Long) {
            val due = pending.filter { it.first <= byMs }
            pending.removeAll(due)
            due.forEach { it.second.run() }
        }
    }

    private lateinit var scheduler: FakeScheduler
    private lateinit var changes: MutableList<BuildPhase>
    private lateinit var state: BuildAnimationState

    @Before
    fun setUp() {
        scheduler = FakeScheduler()
        changes = mutableListOf()
        state = BuildAnimationState(scheduler) { changes += it }
    }

    @Test
    fun success_showsBuildingThenTheTick_thenHidesAfterTheDisplayTime() {
        state.onStarted()
        state.onFinished()
        assertEquals(BuildPhase.SUCCEEDED, state.phase)

        scheduler.advance(BuildAnimationState.RESULT_DISPLAY_MS - 1)
        assertEquals(BuildPhase.SUCCEEDED, state.phase)

        scheduler.advance(BuildAnimationState.RESULT_DISPLAY_MS)
        assertEquals(BuildPhase.IDLE, state.phase)
        assertEquals(listOf(BuildPhase.BUILDING, BuildPhase.SUCCEEDED, BuildPhase.IDLE), changes)
    }

    @Test
    fun failure_showsTheCross_thenHides() {
        state.onStarted()
        state.onFailed()
        assertEquals(BuildPhase.FAILED, state.phase)

        scheduler.advance(BuildAnimationState.RESULT_DISPLAY_MS)
        assertEquals(BuildPhase.IDLE, state.phase)
    }

    @Test
    fun aNewBuildDuringTheResult_isNotHiddenByTheOldRevert() {
        state.onStarted()
        state.onFinished()
        state.onStarted()

        scheduler.advance(BuildAnimationState.RESULT_DISPLAY_MS)

        assertEquals(BuildPhase.BUILDING, state.phase)
    }

    @Test
    fun repeatedStarts_notifyOnce() {
        state.onStarted()
        state.onStarted()

        assertEquals(listOf(BuildPhase.BUILDING), changes)
    }

    @Test
    fun reset_midBuild_showsBuilding_andIdleWhenNothingRuns() {
        state.reset(buildInProgress = true)
        assertEquals(BuildPhase.BUILDING, state.phase)

        state.reset(buildInProgress = false)
        assertEquals(BuildPhase.IDLE, state.phase)
    }

    @Test
    fun dispose_cancelsThePendingRevert() {
        state.onStarted()
        state.onFinished()
        state.dispose()

        scheduler.advance(BuildAnimationState.RESULT_DISPLAY_MS)

        assertEquals(BuildPhase.SUCCEEDED, state.phase)
        assertEquals(0, scheduler.pending.size)
    }
}
