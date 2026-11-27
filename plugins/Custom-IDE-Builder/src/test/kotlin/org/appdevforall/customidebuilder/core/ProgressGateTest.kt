package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class ProgressGateTest {
    @Test
    fun onlyTheFirstReportAsksForAPostUntilThePostRuns() {
        val gate = ProgressGate()
        assertTrue(gate.offer("Copying", 1))
        assertFalse(gate.offer("Copying", 2))
        assertFalse(gate.offer("Copying", 3))
        assertEquals(Progress("Copying", 3), gate.take())
        assertTrue(gate.offer("Copying", 4))
    }

    @Test
    fun aReportThatChangesNothingAsksForNothing() {
        val gate = ProgressGate()
        assertTrue(gate.offer("Copying", 5))
        gate.take()
        assertFalse(gate.offer("Copying", 5))
        assertTrue(gate.offer("Signing", 5))
    }

    @Test
    fun aNewStageAtTheSamePermilleIsShown() {
        val gate = ProgressGate()
        gate.reset("Copying", 1000)
        assertTrue(gate.offer("Signing", 1000))
        assertEquals(Progress("Signing", 1000), gate.take())
    }

    @Test
    fun resetForgetsADroppedPost() {
        val gate = ProgressGate()
        assertTrue(gate.offer("Copying", 10))
        // The queued post was removed with the plugin, so take() never runs.
        gate.reset("", 0)
        assertTrue(gate.offer("Copying", 10))
    }

    @Test
    fun resetValuesAreWhatAReportIsComparedWith() {
        val gate = ProgressGate()
        gate.reset("Building", 0)
        assertFalse(gate.offer("Building", 0))
    }

    @Test
    fun manyWorkerReportsNeverHaveTwoPostsPending() {
        val gate = ProgressGate()
        val pending = AtomicInteger()
        val maxPending = AtomicInteger()
        val posts = AtomicInteger()
        val threads = 4
        val done = CountDownLatch(threads)
        repeat(threads) { t ->
            Thread {
                for (i in 1..20_000) {
                    if (gate.offer("Copying $t", i)) {
                        posts.incrementAndGet()
                        maxPending.accumulateAndGet(pending.incrementAndGet(), ::maxOf)
                        // The "main thread" runs the post straight away here.
                        pending.decrementAndGet()
                        gate.take()
                    }
                }
                done.countDown()
            }.start()
        }
        done.await()
        assertEquals(1, maxPending.get())
        assertTrue(posts.get() > 0)
    }
}
