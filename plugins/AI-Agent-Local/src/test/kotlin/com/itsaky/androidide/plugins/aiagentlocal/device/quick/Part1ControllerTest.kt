package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineResult
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineRunner
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.D5Like
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Part1ControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** A controller on fakes. [factoryCalls] counts how often the runtime was built. */
    private inner class CRig(val root: File = File(tmp.root, "c${System.nanoTime()}").also { it.mkdirs() }) {
        val rig = Rig(root, prepare = false)
        val phone = D5Like()
        var experiments = true
        var preference = true
        var factoryCalls = 0
        var backgroundCalls = 0
        val logs = ArrayList<String>()
        var runBackground = true
        val queued = ArrayList<Runnable>()
        val engineCommandsForT0 = ArrayList<String>()
        var factoryFails: Exception? = null

        fun runtime(): Part1Runtime {
            factoryCalls++
            factoryFails?.let { throw it }
            return Part1Runtime(
                reader = phone.reader(), store = rig.store, index = { rig.index }, engineId = EngineRunner.EXPECTED_ENGINE_VERSION,
                pluginVersion = "1.1.0", newQuickTier = { rig.tier() }, engine = rig.engine, close = { closed++ },
            )
        }
        var closed = 0

        fun controller(t0WaitMs: Long = 2_000) = Part1Controller(
            switch = Part1Switch.of({ experiments }, { preference }),
            runtimeFactory = ::runtime,
            background = { r -> backgroundCalls++; if (runBackground) r.run() else queued.add(r) },
            log = { logs.add(it) },
            t0WaitMs = t0WaitMs,
        )
    }

    private fun countOrigin(x: Any?, origin: String): Int = when (x) {
        is Map<*, *> -> (if (x["origin"] == origin && x.containsKey("value")) 1 else 0) + x.values.sumOf { countOrigin(it, origin) }
        is List<*> -> x.sumOf { countOrigin(it, origin) }
        else -> 0
    }

    // ---- the switch ------------------------------------------------------------------------------------------------

    @Test
    fun givenEveryCombinationOfTheTwoSwitches_whenTheEntryPointsRun_thenPart1RunsOnlyWhenBothAreOn() {
        for (exp in listOf(false, true)) for (pref in listOf(false, true)) {
            val c = CRig(); c.experiments = exp; c.preference = pref
            val ctl = c.controller()
            ctl.onActivated()
            runBlocking { ctl.beforeModelLoad() }
            val on = exp && pref
            assertEquals("exp=$exp pref=$pref factory", if (on) 1 else 0, c.factoryCalls)
            assertEquals("exp=$exp pref=$pref background", if (on) 1 else 0, c.backgroundCalls)
            assertEquals("exp=$exp pref=$pref dialog", if (on) 1 else 0, c.rig.ui.asked)
            assertEquals("exp=$exp pref=$pref files", on, File(c.rig.dir, "static.json").exists())
        }
    }

    @Test
    fun givenEitherSwitchOff_whenEveryFakeFailsOnAnyCall_thenNothingIsTouched() {
        // the factory, the executor, the preference (while Experiments is off) and the dialog all throw an Error if called
        val boom = { throw AssertionError("Part 1 touched something while it is off") }
        val offExperiments = Part1Controller(
            switch = Part1Switch.of({ false }, { boom() }), runtimeFactory = { boom() }, background = { boom() }, log = { boom() },
        )
        offExperiments.onActivated()
        runBlocking { offExperiments.beforeModelLoad() }
        val offPreference = Part1Controller(
            switch = Part1Switch.of({ true }, { false }), runtimeFactory = { boom() }, background = { boom() }, log = { boom() },
        )
        offPreference.onActivated()
        runBlocking { offPreference.beforeModelLoad() }
        offPreference.close(); offExperiments.close()
    }

    @Test
    fun givenAServiceThatFails_whenTheSwitchIsAsked_thenItCountsAsOff() {
        assertFalse(Part1Switch.of({ throw IllegalStateException("no service") }, { true }).isOn())
        assertFalse(Part1Switch.of({ true }, { throw IllegalStateException("no prefs") }).isOn())
        assertTrue(Part1Switch.of({ true }, { true }).isOn())
    }

    // ---- tier T0 ---------------------------------------------------------------------------------------------------

    @Test
    fun givenTheSwitchIsOn_whenActivated_thenT0StoresAValidDescriptorWithNoMeasuredLeaves() {
        val c = CRig()
        c.controller().onActivated()
        assertEquals(1, c.backgroundCalls)
        val d = c.rig.store.readDescriptor()
        assertNotNull(d)
        assertEquals(0, countOrigin(d, "measured_app"))
        assertNotNull(c.rig.store.readStatic()); assertNotNull(c.rig.store.readDbRecord()); assertNotNull(c.rig.store.readProbeFacts())
        assertEquals(0, c.rig.store.readRows().lines.size)
        assertTrue(File(c.rig.dir, "key.txt").isFile)
        assertTrue(c.logs.any { it.contains("T0 done") })
    }

    @Test
    fun givenAFailureInT0_whenActivated_thenNothingIsThrownAndTheFailureIsALogLine() {
        val a = CRig(); a.factoryFails = IllegalStateException("boom")
        a.controller().onActivated()
        assertTrue(a.logs.any { it.contains("T0 failed") })
        val b = CRig()
        b.phone.engine.static = { EngineResult.ExecBlocked("error=13") }; b.phone.engine.gpu = { EngineResult.ProgramMissing("x") }
        b.controller().onActivated() // the probes fail: the read still gives an object with the reasons, and it is stored
        assertNotNull(b.rig.store.readStatic())
        val c = CRig(); c.phone.engine.static = { throw IllegalStateException("runner bug") }
        c.controller().onActivated()
        assertTrue(c.logs.any { it.contains("T0 failed") })
        val d = CRig(); d.runBackground = false
        d.controller().onActivated() // queued only; nothing ran, nothing blocked
        assertEquals(1, d.queued.size); assertEquals(0, d.factoryCalls)
    }

    @Test
    fun givenAnExecutorThatRefuses_whenActivated_thenNothingIsThrown() {
        val c = Part1Controller(Part1Switch { true }, { throw AssertionError("not reached") }, { throw java.util.concurrent.RejectedExecutionException() }, { })
        c.onActivated()
    }

    @Test
    fun givenNoFingerprint_whenT0Runs_thenNothingIsStoredAndNoLogLineShowsAnything() {
        val c = CRig()
        val noFp = D5Like()
        val rt = Part1Runtime(
            reader = com.itsaky.androidide.plugins.aiagentlocal.device.staticread.StaticReader(
                noFp.engine, noFp.files, com.itsaky.androidide.plugins.aiagentlocal.device.staticread.FakeBuild(fingerprint = ""), noFp.memory,
                com.itsaky.androidide.plugins.aiagentlocal.device.staticread.ConditionsReader(noFp.thermal, noFp.battery, noFp.display, noFp.screen),
            ),
            store = c.rig.store, index = { c.rig.index }, engineId = EngineRunner.EXPECTED_ENGINE_VERSION, pluginVersion = null, newQuickTier = { c.rig.tier() },
        )
        val ctl = Part1Controller(Part1Switch { true }, { rt }, { it.run() }, { c.logs.add(it) })
        ctl.onActivated()
        assertFalse(File(c.rig.dir, "static.json").exists())
        assertTrue(c.logs.any { it.contains("no build fingerprint") })
    }

    @Test
    fun givenStoredT1RowsAndTheSameKey_whenT0RunsAgain_thenTheDescriptorKeepsTheMeasuredLeavesAndThePin() {
        val c = CRig()
        val ctl = c.controller()
        ctl.onActivated()
        runBlocking { ctl.beforeModelLoad() } // T1 pass on the fakes
        val measured = countOrigin(c.rig.store.readDescriptor(), "measured_app")
        assertTrue("measured leaves after T1: $measured", measured >= 15)
        // a later activation (a new controller, new store object on the same files)
        val again = CRig(c.root)
        again.controller().onActivated()
        val after = again.rig.store.readDescriptor()
        assertEquals(measured, countOrigin(after, "measured_app"))
        assertNotNull(again.rig.store.readStatic()!!["pin_origin"]) // the pin probe was folded in again
        assertEquals(5, again.rig.store.readRows().lines.size)
    }

    // ---- tier T1 trigger -------------------------------------------------------------------------------------------

    @Test
    fun givenNoValidT1_whenTheModelLoads_thenThePassRunsOnceAndIsNotAskedAgainAfterItIsCompleted() {
        val c = CRig(); val ctl = c.controller()
        ctl.onActivated()
        runBlocking { ctl.beforeModelLoad() }
        assertEquals(1, c.rig.ui.asked); assertEquals(6, c.rig.engine.commands.size)
        runBlocking { ctl.beforeModelLoad() }
        assertEquals(1, c.rig.ui.asked) // the session is done
        // a new session finds the completed pass record for this key and does not ask either
        val next = CRig(c.root); next.controller().also { it.onActivated() }.let { n -> runBlocking { n.beforeModelLoad() } }
        assertEquals(0, next.rig.ui.asked)
    }

    @Test
    fun givenTheUserSkips_whenTheModelLoadsAgainInTheSession_thenItIsNotAskedAgainButTheNextSessionAsks() {
        val c = CRig(); c.rig.ui.answer = { QuickAnswer.SKIP }
        val ctl = c.controller(); ctl.onActivated()
        runBlocking { ctl.beforeModelLoad(); ctl.beforeModelLoad() }
        assertEquals(1, c.rig.ui.asked)
        assertTrue(c.rig.engine.commands.isEmpty())
        val next = CRig(c.root); val n = next.controller(); n.onActivated()
        runBlocking { n.beforeModelLoad() }
        assertEquals(1, next.rig.ui.asked) // asked again at the next session's first load
    }

    @Test
    fun givenACancelledPass_whenTheNextSessionLoads_thenItIsAskedAgainButNotInTheSameSession() {
        val c = CRig()
        c.rig.engine.beforeRun = { cmd -> if (c.rig.tagOf(cmd) == "C2-read-k0") c.rig.ui.cancelHandler() }
        val ctl = c.controller(); ctl.onActivated()
        runBlocking { ctl.beforeModelLoad(); ctl.beforeModelLoad() }
        assertEquals(1, c.rig.ui.asked)
        val next = CRig(c.root); val n = next.controller(); n.onActivated()
        runBlocking { n.beforeModelLoad() }
        assertEquals(1, next.rig.ui.asked)
    }

    @Test
    fun givenADeferredPass_whenTheModelLoadsAgain_thenItTriesAgainInTheSameSession() {
        val c = CRig(); c.rig.fg.fg = { false } // not in the foreground: deferred
        val ctl = c.controller(); ctl.onActivated()
        runBlocking { ctl.beforeModelLoad() }
        assertEquals(1, c.rig.ui.asked); assertTrue(c.rig.engine.commands.isEmpty())
        c.rig.fg.fg = { true }
        runBlocking { ctl.beforeModelLoad() }
        assertEquals(2, c.rig.ui.asked); assertEquals(6, c.rig.engine.commands.size)
    }

    @Test
    fun givenAPassThatEndedEarly_whenTheNextSessionLoads_thenItIsAskedAgainSinceNoCompletedPassIsRecorded() {
        val c = CRig()
        val ctl = c.controller(); ctl.onActivated()
        c.rig.engine.beforeRun = { cmd -> if (c.rig.tagOf(cmd) == "C2-read-k0") c.rig.cond.status = { 3 } }
        runBlocking { ctl.beforeModelLoad() }
        val rec = c.rig.store.readPassRecord()!!
        assertEquals("ended_early", rec["status"])
        val next = CRig(c.root); val n = next.controller(); n.onActivated()
        runBlocking { n.beforeModelLoad() }
        assertEquals(1, next.rig.ui.asked)
    }

    @Test
    fun givenT0IsNotDoneInTime_whenTheModelLoads_thenT1WaitsForTheNextLoad() {
        val c = CRig(); c.runBackground = false
        val ctl = c.controller(t0WaitMs = 50)
        ctl.onActivated()
        runBlocking { ctl.beforeModelLoad() }
        assertEquals(0, c.rig.ui.asked)
        assertTrue(c.logs.any { it.contains("T0 is not done") })
        // the queued T0 finishes later; the next load then runs the pass
        c.queued.forEach { it.run() }
        runBlocking { ctl.beforeModelLoad() }
        assertEquals(1, c.rig.ui.asked)
    }

    @Test
    fun givenTheSwitchWasTurnedOnAfterTheActivation_whenTheModelLoads_thenT0RunsFirstAndThenT1() {
        val c = CRig(); c.experiments = false
        val ctl = c.controller(); ctl.onActivated()
        assertEquals(0, c.factoryCalls)
        c.experiments = true
        runBlocking { ctl.beforeModelLoad() }
        assertNotNull(c.rig.store.readDescriptor()); assertEquals(1, c.rig.ui.asked)
        assertEquals(0, c.backgroundCalls) // T0 ran inline in the load
    }

    @Test
    fun givenThePassThrows_whenTheModelLoads_thenTheLoadSeesNoException() {
        val c = CRig(); c.rig.ui.answer = { throw IllegalStateException("dialog bug") }
        val ctl = c.controller(); ctl.onActivated()
        runBlocking { ctl.beforeModelLoad() } // the tier turns it into a result; nothing reaches the caller
        assertEquals(1, c.rig.ui.asked)
    }

    @Test
    fun givenTheCallingCoroutineIsCancelled_whenAPassIsRunning_thenThePassIsInterruptedItsProcessIsGoneAndTheCancellationIsPassedOn() {
        val c = CRig(); val ctl = c.controller(); ctl.onActivated()
        val inRow = CountDownLatch(1)
        c.rig.engine.beforeRun = { cmd -> if (c.rig.tagOf(cmd) == "C2-read-k0") { inRow.countDown(); Thread.sleep(30_000) } }
        var thrown: Throwable? = null
        runBlocking {
            val job = launch(Dispatchers.Default) {
                try { ctl.beforeModelLoad() } catch (e: CancellationException) { thrown = e; throw e }
            }
            assertTrue(inRow.await(10, TimeUnit.SECONDS))
            job.cancel()
            job.join()
        }
        assertNotNull(thrown)
        assertFalse(c.rig.engine.running)
        assertFalse(c.rig.screen.on)
        assertEquals("cancelled", c.rig.store.readPassRecord()!!["status"])
        assertTrue(c.rig.noTempFiles())
    }

    @Test
    fun givenClose_whenCalled_thenTheEngineIsCancelledTheRuntimeClosedAndLaterLoadsDoNothing() {
        val c = CRig(); val ctl = c.controller(); ctl.onActivated()
        ctl.close(); ctl.close()
        assertTrue(c.rig.engine.cancelCalls >= 1)
        assertTrue(c.closed >= 1)
        runBlocking { ctl.beforeModelLoad() }
        assertEquals(0, c.rig.ui.asked)
    }
}
