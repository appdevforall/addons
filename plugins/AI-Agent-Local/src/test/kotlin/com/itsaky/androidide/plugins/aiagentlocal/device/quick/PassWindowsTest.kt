package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A UI executor that queues: nothing runs until the test says so, and then in posting order (F02, F14). */
class QueueExecutor {
    private val queue = ArrayDeque<Runnable>()
    val post: (Runnable) -> Unit = { r -> synchronized(queue) { queue.addLast(r) } }
    fun pending(): Int = synchronized(queue) { queue.size }
    fun runNext() { val r = synchronized(queue) { queue.removeFirst() }; r.run() }
    fun runAll() { while (pending() > 0) runNext() }
}

class FakeHost(val name: String, var usable: Boolean = true, var flag: Boolean = false) {
    var progressText: String? = null
    var progressShown = 0
    var progressDismissed = 0
    var questionShown = 0
    var questionDismissed = 0
    var flagSets = 0
    var cancel: () -> Unit = {}
    var onRun: () -> Unit = {}
    var onSkip: () -> Unit = {}
    var onDismissed: () -> Unit = {}
    val progressVisible get() = progressShown - progressDismissed
    val questionVisible get() = questionShown - questionDismissed
}

class FakeWindowOps : WindowOps<FakeHost> {
    var failProgress = false
    override fun isUsable(host: FakeHost) = host.usable
    override fun flagIsSet(host: FakeHost) = host.flag
    override fun setFlag(host: FakeHost, on: Boolean) { host.flag = on; if (on) host.flagSets++ }
    override fun showQuestion(host: FakeHost, onRun: () -> Unit, onSkip: () -> Unit, onDismissed: () -> Unit): DialogHandle? {
        host.questionShown++
        host.onRun = onRun; host.onSkip = onSkip; host.onDismissed = onDismissed
        return object : DialogHandle {
            override fun setText(text: String) {}
            override fun dismiss() { host.questionDismissed++; onDismissed() }
        }
    }
    override fun showProgress(host: FakeHost, text: String, onCancel: () -> Unit): DialogHandle? {
        if (failProgress) return null
        host.progressShown++
        host.progressText = text
        host.cancel = onCancel
        return object : DialogHandle {
            override fun setText(text: String) { host.progressText = text }
            override fun dismiss() { host.progressDismissed++ }
        }
    }
}

class PassWindowsTest {
    private val a = FakeHost("A")
    private val b = FakeHost("B")
    private var current: FakeHost? = a
    private val ui = QueueExecutor()
    private val ops = FakeWindowOps()
    private fun windows(askTimeoutMs: Long = 5_000) = PassWindows({ current }, ui.post, ops, askTimeoutMs)

    @Test
    fun givenReleaseComesBeforeTheQueuedKeepOnRuns_whenTheQueueRuns_thenTheFlagIsNeverSet() {
        val w = windows(); w.open()
        w.keepOn() // queued, not yet run
        w.close() // release: sets closed first, then queues the clean-up
        ui.runAll()
        assertFalse(a.flag)
        assertEquals(0, a.flagSets)
    }

    @Test
    fun givenCloseComesBeforeTheQueuedProgressTextRuns_whenTheQueueRuns_thenNoDialogIsCreatedAfterIt() {
        val w = windows(); w.open()
        w.progress("Step 1 of 6")
        w.close()
        ui.runAll()
        assertEquals(0, a.progressShown)
        // and a dialog that is up is dismissed, not updated, by a close that queued before the update ran
        val w2 = windows(); w2.open()
        w2.progress("one"); ui.runAll()
        assertEquals(1, a.progressVisible)
        w2.progress("two"); w2.close()
        ui.runAll()
        assertEquals("one", a.progressText)
        assertEquals(0, a.progressVisible)
    }

    @Test
    fun givenTheActivityIsReplacedMidPass_whenTheNextProgressUpdateRuns_thenTheFlagAndTheDialogMoveToTheNewOne() {
        val w = windows(); w.open()
        w.keepOn(); w.progress("one"); ui.runAll()
        assertTrue(a.flag); assertEquals(1, a.progressVisible)
        current = b // recreated or replaced
        w.progress("two"); ui.runAll()
        assertFalse(a.flag); assertEquals(0, a.progressVisible)
        assertTrue(b.flag); assertEquals(1, b.progressVisible); assertEquals("two", b.progressText)
        w.close(); ui.runAll()
        assertFalse(b.flag); assertEquals(0, b.progressVisible)
        assertFalse(a.flag)
    }

    @Test
    fun givenNoCurrentActivityAtAnUpdate_whenTheNextOneHasAnActivity_thenThePassCarriedOnWithoutADialogAndThenMoves() {
        val w = windows(); w.open()
        current = null
        w.keepOn(); w.progress("one"); ui.runAll()
        assertEquals(0, a.progressShown); assertFalse(a.flag)
        current = b
        w.progress("two"); ui.runAll()
        assertEquals(1, b.progressVisible); assertTrue(b.flag)
        w.close(); ui.runAll()
        assertFalse(b.flag)
    }

    @Test
    fun givenAnActivityThatAlreadyHoldsTheFlag_whenThePassEnds_thenTheFlagIsLeftAsItWas() {
        a.flag = true
        val w = windows(); w.open()
        w.keepOn(); ui.runAll()
        assertEquals(0, a.flagSets)
        w.close(); ui.runAll()
        assertTrue(a.flag)
    }

    @Test
    fun givenAFinishingActivity_whenAnUpdateRuns_thenNothingIsShownOnIt() {
        a.usable = false
        val w = windows(); w.open()
        w.progress("x"); ui.runAll()
        assertEquals(0, a.progressShown)
    }

    @Test
    fun givenTheQuestionIsAskedAndTheUserAnswers_whenTheUiThreadRuns_thenTheAnswerComesBack() {
        val w = windows()
        val out = AtomicReference<QuickAnswer>()
        val t = Thread { out.set(w.ask()) }; t.start()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (ui.pending() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
        ui.runAll()
        assertEquals(1, a.questionShown)
        a.onRun(); a.onDismissed()
        t.join(5_000)
        assertEquals(QuickAnswer.RUN_NOW, out.get())
    }

    @Test
    fun givenTheUiThreadIsTooSlowForTheQuestion_whenTheQueueRunsLater_thenTheAnswerWasSkipAndNothingIsShown() {
        val w = windows(askTimeoutMs = 50)
        assertEquals(QuickAnswer.SKIP, w.ask())
        ui.runAll()
        assertEquals(0, a.questionShown)
    }

    @Test
    fun givenCloseBeforeTheQuestionIsCreated_whenTheQueueRuns_thenNothingIsShownAndAskReturnsSkip() {
        val w = windows()
        val out = AtomicReference<QuickAnswer>()
        val t = Thread { out.set(w.ask()) }; t.start()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (ui.pending() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
        w.close()
        ui.runAll()
        t.join(5_000)
        assertEquals(0, a.questionShown)
        assertEquals(QuickAnswer.SKIP, out.get())
    }

    @Test
    fun givenNoActivity_whenAsked_thenNoActivityAndTheCancelButtonCallsTheHandler() {
        current = null
        assertEquals(QuickAnswer.NO_ACTIVITY, windows().ask())
        current = a
        val w = windows(); w.open()
        var cancelled = 0
        w.onCancel { cancelled++ }
        w.progress("x"); ui.runAll()
        a.cancel()
        assertEquals(1, cancelled)
        assertNotNull(a.progressText)
    }
}
