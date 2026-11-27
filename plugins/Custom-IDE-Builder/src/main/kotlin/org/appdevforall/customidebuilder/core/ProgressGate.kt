package org.appdevforall.customidebuilder.core

/** A step's label and how far along it is, in thousandths. */
data class Progress(val stage: String, val permille: Int)

/**
 * Coalesces progress reported from a worker thread into at most one pending main-thread update.
 *
 * A copy reports every chunk and a rewrite every zip entry, far faster than a phone can redraw, and
 * a post per report queues behind itself until the screen runs minutes late and a tap waits for the
 * build to end. Here the worker calls [offer] with each report and posts only when it returns true;
 * the post calls [take] when it runs and shows the latest values. A report equal to the latest one
 * asks for nothing, and while a post is pending later reports only replace the values it will show.
 *
 * Safe to call from any thread.
 */
class ProgressGate {
    private val lock = Any()
    private var stage = ""
    private var permille = 0
    private var pending = false

    /** Records the latest progress. True means no post is pending and the caller must post one that calls [take]. */
    fun offer(stage: String, permille: Int): Boolean =
        synchronized(lock) {
            if (stage == this.stage && permille == this.permille) return false
            this.stage = stage
            this.permille = permille
            if (pending) return false
            pending = true
            true
        }

    /** The latest progress, for the post to show. The next changed [offer] asks for a new post. */
    fun take(): Progress =
        synchronized(lock) {
            pending = false
            Progress(stage, permille)
        }

    /**
     * Says the screen now shows [stage] and [permille], set on the main thread, and forgets a pending
     * post: one already queued shows these values, and one dropped (the plugin ended) no longer
     * blocks the next.
     */
    fun reset(stage: String, permille: Int) =
        synchronized(lock) {
            this.stage = stage
            this.permille = permille
            pending = false
        }
}
