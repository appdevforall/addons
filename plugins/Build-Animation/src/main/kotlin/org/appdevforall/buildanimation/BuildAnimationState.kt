package org.appdevforall.buildanimation

/** What the toolbar icon shows. IDLE is a greyed-out static wrench. */
enum class BuildPhase { IDLE, BUILDING, SUCCEEDED, FAILED }

/** Delayed-callback seam, so the state machine is testable without a Looper. */
interface Scheduler {
    fun postDelayed(delayMs: Long, task: Runnable)
    fun cancel(task: Runnable)
}

/**
 * IDLE -> BUILDING on start; BUILDING -> SUCCEEDED/FAILED on the result, which reverts to IDLE
 * after [RESULT_DISPLAY_MS]. [onChange] fires only on a real transition.
 */
class BuildAnimationState(
    private val scheduler: Scheduler,
    private val onChange: (BuildPhase) -> Unit,
) {
    var phase: BuildPhase = BuildPhase.IDLE
        private set

    private val revertToIdle = Runnable { moveTo(BuildPhase.IDLE) }

    fun onStarted() {
        // A build started while the previous result is still showing must not be hidden by
        // that result's pending revert.
        scheduler.cancel(revertToIdle)
        moveTo(BuildPhase.BUILDING)
    }

    fun onFinished() = showResult(BuildPhase.SUCCEEDED)

    fun onFailed() = showResult(BuildPhase.FAILED)

    /** Re-syncs with the IDE, e.g. on activation mid-build. */
    fun reset(buildInProgress: Boolean) {
        scheduler.cancel(revertToIdle)
        moveTo(if (buildInProgress) BuildPhase.BUILDING else BuildPhase.IDLE)
    }

    fun dispose() {
        scheduler.cancel(revertToIdle)
    }

    private fun showResult(result: BuildPhase) {
        scheduler.cancel(revertToIdle)
        moveTo(result)
        scheduler.postDelayed(RESULT_DISPLAY_MS, revertToIdle)
    }

    private fun moveTo(next: BuildPhase) {
        if (next == phase) return
        phase = next
        onChange(next)
    }

    companion object {
        // Outlasts the host's own "Build successful" flashbar (DURATION_SHORT, 2 s plus its
        // animations), which covers the toolbar, so the result is still visible once it clears.
        const val RESULT_DISPLAY_MS = 5_000L
    }
}
