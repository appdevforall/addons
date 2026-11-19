package org.appdevforall.maps.ui

/**
 * Times the bbox picker's auto-shrink: runs [shrink] [delayMs] after the camera goes idle, and
 * drops the pending run as soon as the camera starts moving again.
 *
 * @param postDelayed runs an action after a delay and returns a function that cancels it
 */
internal class AutoShrinkScheduler(
    private val delayMs: Long,
    private val postDelayed: (delayMs: Long, action: () -> Unit) -> () -> Unit,
    private val shrink: () -> Unit,
) {

    private var cancelPending: (() -> Unit)? = null

    /** Restarts the wait, so only the last idle of a pan-and-zoom sequence shrinks. */
    fun onCameraIdle() {
        cancel()
        cancelPending =
            postDelayed(delayMs) {
                cancelPending = null
                shrink()
            }
    }

    /** A new gesture means the user is not done, so a shrink now would jump the box mid-drag. */
    fun onCameraMoveStarted() = cancel()

    /** Drops any pending shrink. */
    fun cancel() {
        cancelPending?.invoke()
        cancelPending = null
    }
}
