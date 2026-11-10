package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A dialog on screen: its text can change and it can be dismissed. Used on the UI thread only. */
interface DialogHandle {
    fun setText(text: String)
    fun dismiss()
}

/**
 * What [PassWindows] needs of a window host [A] (an activity on the phone, a fake in the tests). Every call is made on the UI thread.
 * [showQuestion] and [showProgress] return null when the dialog could not be shown. The callbacks of the question may be called by the
 * dialog at any time on the UI thread.
 */
interface WindowOps<A : Any> {
    /** False when the host is finishing or destroyed. */
    fun isUsable(host: A): Boolean
    fun flagIsSet(host: A): Boolean
    fun setFlag(host: A, on: Boolean)
    fun showQuestion(host: A, onRun: () -> Unit, onSkip: () -> Unit, onDismissed: () -> Unit): DialogHandle?
    fun showProgress(host: A, text: String, onCancel: () -> Unit): DialogHandle?
}

/**
 * Everything one pass puts on a window: the keep-screen-on flag, the question and the progress dialog with its Cancel button.
 *
 * **One thread, one order.** Every change of window state (set or clear the flag, create, update or dismiss a dialog) is posted through
 * [post] and so happens on the UI thread in posting order. [close] sets the `closed` state first, on the calling thread, and then posts
 * the clean-up; a runnable queued before the close checks that state and does nothing after it, so nothing can be shown or set after
 * the pass is over. [open] starts a new cycle (a later cycle's runnables are never cut off by an earlier close, and an earlier cycle's
 * are dropped).
 *
 * **Recreated or replaced activity.** At every progress update the current host is read again; when it is not the one holding the flag or
 * the progress dialog, both move to the current host (the old flag is cleared and the old dialog dismissed). With no current host the
 * pass carries on without a dialog.
 *
 * The flag is cleared only where this class set it (the host may already hold it). Logs nothing.
 */
class PassWindows<A : Any>(
    private val current: () -> A?,
    private val post: (Runnable) -> Unit,
    private val ops: WindowOps<A>,
    private val askTimeoutMs: Long = QuickLimits.ASK_TIMEOUT_MS,
) {
    @Volatile private var closed = true
    @Volatile private var epoch = 0L
    @Volatile private var cancelHandler: () -> Unit = {}

    // UI-thread state only
    private var flagWanted = false
    private var flagAt: A? = null
    private var flagOwned = false
    private var progress: DialogHandle? = null
    private var progressAt: A? = null
    private var question: DialogHandle? = null

    /** Starts a cycle: runnables posted from now on are live until [close]. */
    @Synchronized
    fun open() {
        epoch++
        closed = false
    }

    fun onCancel(handler: () -> Unit) {
        cancelHandler = handler
    }

    /** Posts [task] for the current cycle; it does nothing when [close] (or a new [open]) came before it runs. */
    private fun postLive(onDropped: () -> Unit = {}, task: () -> Unit) {
        val e = epoch
        post(
            Runnable {
                if (closed || epoch != e) {
                    onDropped()
                    return@Runnable
                }
                try {
                    task()
                } catch (_: Exception) {
                    // a window that cannot be changed does not stop the measurement
                }
            },
        )
    }

    /** Asks the one question. Blocks; returns Skip by itself after [askTimeoutMs] or when the dialog goes away. */
    fun ask(): QuickAnswer {
        open()
        val first = current()
        if (first == null || !ops.isUsable(first)) return QuickAnswer.NO_ACTIVITY
        val answer = java.util.concurrent.atomic.AtomicReference<QuickAnswer?>()
        val done = CountDownLatch(1)
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        postLive(onDropped = { answer.compareAndSet(null, QuickAnswer.SKIP); done.countDown() }) {
            if (expired.get()) { // the UI thread was too slow: the answer is already Skip, nothing is shown
                done.countDown()
                return@postLive
            }
            val host = current()?.takeIf { ops.isUsable(it) }
            if (host == null) {
                answer.compareAndSet(null, QuickAnswer.NO_ACTIVITY)
                done.countDown()
                return@postLive
            }
            val d = ops.showQuestion(
                host,
                onRun = { answer.compareAndSet(null, QuickAnswer.RUN_NOW) },
                onSkip = { answer.compareAndSet(null, QuickAnswer.SKIP) },
                onDismissed = { answer.compareAndSet(null, QuickAnswer.SKIP); done.countDown() }, // back press, outside tap, host gone
            )
            if (d == null) {
                answer.compareAndSet(null, QuickAnswer.NO_ACTIVITY)
                done.countDown()
            } else {
                question = d
            }
        }
        if (!done.await(askTimeoutMs, TimeUnit.MILLISECONDS)) {
            answer.compareAndSet(null, QuickAnswer.SKIP)
            expired.set(true)
            post(Runnable { dismissQuestion() }) // after the creation above, in posting order
        }
        return answer.get() ?: QuickAnswer.SKIP
    }

    /** Sets the keep-screen-on flag on the current host (and, at later progress updates, on whichever host is current then). */
    fun keepOn() {
        postLive {
            flagWanted = true
            current()?.takeIf { ops.isUsable(it) }?.let { syncFlag(it) }
        }
    }

    /** Shows or updates the progress dialog on the current host; the flag and the dialog move there when the host changed. */
    fun progress(text: String) {
        postLive {
            val host = current()?.takeIf { ops.isUsable(it) } ?: return@postLive // none: carry on without a dialog
            if (flagWanted) syncFlag(host)
            syncProgress(host, text)
        }
    }

    /**
     * Ends the cycle: sets `closed` first, then posts the clean-up (clear the flag where it was set, dismiss both dialogs). Idempotent;
     * the clean-up runs even when nothing was shown. Safe to call from any thread.
     */
    fun close() {
        closed = true
        post(
            Runnable {
                try {
                    dismissQuestion()
                    progress?.let { runCatching { it.dismiss() } }
                    progress = null
                    progressAt = null
                    if (flagOwned) flagAt?.let { runCatching { ops.setFlag(it, false) } }
                    flagAt = null
                    flagOwned = false
                    flagWanted = false
                } catch (_: Exception) {
                }
            },
        )
    }

    // ---- UI thread -----------------------------------------------------------------------------------------------

    private fun dismissQuestion() {
        question?.let { runCatching { it.dismiss() } }
        question = null
    }

    private fun syncFlag(host: A) {
        if (flagAt === host) return
        if (flagOwned) flagAt?.let { runCatching { ops.setFlag(it, false) } }
        flagAt = host
        flagOwned = !ops.flagIsSet(host)
        if (flagOwned) ops.setFlag(host, true)
    }

    private fun syncProgress(host: A, text: String) {
        val d = progress
        if (d != null && progressAt === host) {
            d.setText(text)
            return
        }
        d?.let { runCatching { it.dismiss() } }
        progress = null
        progressAt = null
        val created = ops.showProgress(host, text) { cancelHandler() }
        if (created != null) {
            progress = created
            progressAt = host
        }
    }
}
