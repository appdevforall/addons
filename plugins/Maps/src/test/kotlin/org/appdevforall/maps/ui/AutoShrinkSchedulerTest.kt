package org.appdevforall.maps.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [AutoShrinkScheduler], with a fake clock standing in for the coroutine delay. */
class AutoShrinkSchedulerTest {

    private class FakeTimer {
        private class Pending(val dueAt: Long, val action: () -> Unit, var cancelled: Boolean = false)

        private val pending = mutableListOf<Pending>()
        var now = 0L
            private set

        fun postDelayed(delayMs: Long, action: () -> Unit): () -> Unit {
            val p = Pending(now + delayMs, action)
            pending += p
            return { p.cancelled = true }
        }

        fun advanceBy(ms: Long) {
            now += ms
            val due = pending.filter { !it.cancelled && it.dueAt <= now }
            pending.removeAll(due)
            due.forEach { it.action() }
        }
    }

    private val timer = FakeTimer()
    private var shrinks = 0
    private val scheduler =
        AutoShrinkScheduler(delayMs = 250L, postDelayed = timer::postDelayed, shrink = { shrinks++ })

    @Test
    fun `idle shrinks once the delay has passed`() {
        scheduler.onCameraIdle()
        timer.advanceBy(249L)
        assertEquals("shrink ran before the delay", 0, shrinks)
        timer.advanceBy(1L)
        assertEquals("shrink count after the delay", 1, shrinks)
    }

    @Test
    fun `a second idle restarts the wait so only the last one shrinks`() {
        scheduler.onCameraIdle()
        timer.advanceBy(200L)
        scheduler.onCameraIdle()
        timer.advanceBy(200L)
        assertEquals("shrink ran on the superseded idle", 0, shrinks)
        timer.advanceBy(50L)
        assertEquals("shrink count after the last idle", 1, shrinks)
    }

    @Test
    fun `a gesture starting during the wait cancels the pending shrink`() {
        scheduler.onCameraIdle()
        timer.advanceBy(100L)
        scheduler.onCameraMoveStarted()
        timer.advanceBy(1_000L)
        assertEquals("shrink ran during the user's next gesture", 0, shrinks)
    }

    @Test
    fun `cancel drops the pending shrink`() {
        scheduler.onCameraIdle()
        scheduler.cancel()
        timer.advanceBy(1_000L)
        assertEquals("shrink ran after cancel", 0, shrinks)
    }
}
