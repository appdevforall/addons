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
    private inner class CRig(val root: File = File(tmp.root, "c${System.nanoTime()}").also { it.mkdirs() }, ops: com.itsaky.androidide.plugins.aiagentlocal.device.store.FileOps = com.itsaky.androidide.plugins.aiagentlocal.device.store.FileOps.Real) {
        val rig = Rig(root, prepare = false, ops = ops)
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
        assertEquals(0, c.rig.ui.asked); assertTrue(c.rig.engine.commands.isEmpty()) // F26: not asked while the foreground gate fails
        c.rig.fg.fg = { true }
        runBlocking { ctl.beforeModelLoad() }
        assertEquals(1, c.rig.ui.asked); assertEquals(6, c.rig.engine.commands.size)
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

    // ---- P1-04A2F ---------------------------------------------------------------------------------------------------

    @Test
    fun givenTheLastT0ShowedThatTheEngineCannotRun_whenTheModelLoads_thenNoQuestionOneLogLineAndOneSkippedRecord() {
        // F05
        val cases = listOf<Pair<String, (CRig) -> Unit>>(
            "exec_blocked" to { c -> c.phone.engine.static = { EngineResult.ExecBlocked("error=13") } },
            "program_missing" to { c -> c.phone.engine.gpu = { EngineResult.ProgramMissing("x") } },
            "engine_mismatch" to { c -> c.phone.engine.static = { EngineResult.EngineMismatch("part1-engine/0.2.3") } },
        )
        for ((reason, setup) in cases) {
            val c = CRig(); setup(c)
            val ctl = c.controller(); ctl.onActivated()
            runBlocking { ctl.beforeModelLoad(); ctl.beforeModelLoad() }
            assertEquals(reason, 0, c.rig.ui.asked)
            assertTrue(reason, c.rig.engine.commands.isEmpty())
            assertEquals(reason, 1, c.logs.count { it.contains("not offered") })
            val rec = c.rig.store.readPassRecord()!!
            assertEquals("skipped", rec["status"]); assertEquals(reason, rec["reason"])
        }
    }

    @Test
    fun givenAWorkingEngineAndACompletedPass_whenAnotherLoadComes_thenTheCompletedRecordIsNotOverwritten() {
        val c = CRig(); val ctl = c.controller(); ctl.onActivated()
        runBlocking { ctl.beforeModelLoad() }
        assertEquals("completed", c.rig.store.readPassRecord()!!["status"])
        val next = CRig(c.root); next.phone.engine.static = { EngineResult.ExecBlocked("x") }
        val n = next.controller(); n.onActivated(); runBlocking { n.beforeModelLoad() }
        assertEquals("completed", next.rig.store.readPassRecord()!!["status"])
    }

    @Test
    fun givenTheCallingCoroutineIsCancelledWhileWaitingForT0_whenItWaits_thenItReturnsAtOnce() {
        // F07: the wait is interruptible (it used to block for the whole 15 s)
        val c = CRig(); c.runBackground = false
        val ctl = c.controller(t0WaitMs = 30_000); ctl.onActivated()
        var thrown: Throwable? = null
        val t0 = System.nanoTime()
        runBlocking {
            val job = launch(Dispatchers.Default) {
                try { ctl.beforeModelLoad() } catch (e: CancellationException) { thrown = e; throw e }
            }
            kotlinx.coroutines.delay(200)
            job.cancel(); job.join()
        }
        assertNotNull(thrown)
        assertTrue("took ${(System.nanoTime() - t0) / 1_000_000} ms", (System.nanoTime() - t0) < 10_000_000_000L)
    }

    @Test
    fun givenTheCallingCoroutineIsCancelledDuringAnInlineT0_whenAProbeRuns_thenTheProbeIsInterruptedAndTheCancellationIsPassedOn() {
        val c = CRig(); c.experiments = false
        val ctl = c.controller(); ctl.onActivated()
        c.experiments = true
        val inProbe = CountDownLatch(1)
        val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        c.phone.engine.static = {
            inProbe.countDown()
            try { Thread.sleep(30_000) } catch (e: InterruptedException) { interrupted.set(true); Thread.currentThread().interrupt() }
            EngineResult.Cancelled
        }
        var thrown: Throwable? = null
        runBlocking {
            val job = launch(Dispatchers.Default) {
                try { ctl.beforeModelLoad() } catch (e: CancellationException) { thrown = e; throw e }
            }
            assertTrue(inProbe.await(10, TimeUnit.SECONDS))
            job.cancel(); job.join()
        }
        assertNotNull(thrown); assertTrue(interrupted.get())
        assertEquals(0, c.rig.ui.asked)
    }

    @Test
    fun givenAnotherModelIsResident_whenTheHookRuns_thenT1IsDeferredAndTheNextLoadWithNoModelAsks() {
        // F10
        val c = CRig(); val ctl = c.controller(); ctl.onActivated()
        runBlocking { ctl.beforeModelLoad(otherModelResident = true) }
        assertEquals(0, c.rig.ui.asked)
        assertTrue(c.logs.any { it.contains("model_resident") })
        runBlocking { ctl.beforeModelLoad(otherModelResident = false) }
        assertEquals(1, c.rig.ui.asked)
    }

    @Test
    fun givenAnErrorFromAFake_whenTheEntryPointsRun_thenItIsALogLineWithTheClassNameOnly() {
        // F11
        val a = CRig(); a.factoryFails = null
        val boom = Part1Controller(Part1Switch { true }, { throw NoClassDefFoundError("secret/Path") }, { it.run() }, { a.logs.add(it) })
        boom.onActivated()
        assertTrue(a.logs.any { it.contains("T0 failed (NoClassDefFoundError)") })
        assertFalse(a.logs.any { it.contains("secret") })
        val b = CRig(); b.phone.engine.static = { throw ExceptionInInitializerError("x") }
        val ctl = b.controller(); ctl.onActivated()
        assertTrue(b.logs.any { it.contains("T0 failed (ExceptionInInitializerError)") })
        val c = CRig()
        val ctl2 = Part1Controller(
            Part1Switch { true },
            { Part1Runtime(c.phone.reader(), c.rig.store, { c.rig.index }, EngineRunner.EXPECTED_ENGINE_VERSION, "1", { throw LinkageError("tier") }) },
            { it.run() }, { c.logs.add(it) },
        )
        ctl2.onActivated()
        runBlocking { ctl2.beforeModelLoad() } // nothing reaches the caller
        assertTrue(c.logs.any { it.contains("T1 failed (LinkageError)") })
        val d = Part1Controller(Part1Switch { throw OutOfMemoryError() }, { throw AssertionError() }, { throw AssertionError() }, { })
        runCatching { d.onActivated() }.let { assertTrue("an Error from the switch reaches only the logger", it.isSuccess) }
    }

    @Test
    fun givenCloseDuringT0_whenTheT0ThreadGoesOn_thenItWritesNothingAfterTheMark() {
        // F12
        val c = CRig()
        val ctl = c.controller()
        val orig = c.phone.engine.static
        c.phone.engine.static = { ctl.close(); orig() } // the plugin is deactivated while T0 reads
        ctl.onActivated()
        assertFalse(File(c.rig.dir, "static.json").exists())
        assertFalse(File(c.rig.dir, "key.txt").exists())
        assertFalse(File(c.rig.dir, "device-descriptor-v0.2.json").exists())
    }

    @Test
    fun givenCloseBetweenTwoStoreWrites_whenT0GoesOn_thenTheNextWriteIsNotDone() {
        // F12: the mark is checked before each store write (close comes while the 1st, 2nd or 3rd file is being written)
        val expectedFiles = listOf(
            1 to setOf("key.txt"),
            2 to setOf("key.txt", "static.json"),
            3 to setOf("key.txt", "static.json", "probe-facts.json"),
        )
        for ((closeAt, files) in expectedFiles) {
            val c = CRig(File(tmp.root, "w$closeAt${System.nanoTime()}").also { it.mkdirs() })
            var writes = 0
            var ctl: Part1Controller? = null
            val ops = object : com.itsaky.androidide.plugins.aiagentlocal.device.store.FileOps {
                override fun writeAndSync(file: File, bytes: ByteArray) {
                    com.itsaky.androidide.plugins.aiagentlocal.device.store.FileOps.Real.writeAndSync(file, bytes)
                    if (!file.name.endsWith(".tmp")) return
                    if (++writes == closeAt) ctl!!.close()
                }
                override fun moveReplacing(from: File, to: File) = com.itsaky.androidide.plugins.aiagentlocal.device.store.FileOps.Real.moveReplacing(from, to)
                override fun delete(file: File) = com.itsaky.androidide.plugins.aiagentlocal.device.store.FileOps.Real.delete(file)
            }
            val store = com.itsaky.androidide.plugins.aiagentlocal.device.store.Part1Store(c.rig.dir, ops)
            val rt = Part1Runtime(c.phone.reader(), store, { c.rig.index }, EngineRunner.EXPECTED_ENGINE_VERSION, "1", { c.rig.tier() })
            ctl = Part1Controller(Part1Switch { true }, { rt }, { it.run() }, { c.logs.add(it) })
            ctl.onActivated()
            val present = (c.rig.dir.list() ?: emptyArray()).filter { !it.endsWith(".tmp") }.toSet()
            assertEquals("closed at write $closeAt", files, present)
        }
    }

    @Test
    fun givenAnOldT0ThatDoesNotEnd_whenAnActivationFollows_thenItBindsNothingAndDoesNoPart1Work() {
        val c = CRig()
        val gate = CountDownLatch(1)
        val orig = c.phone.engine.static
        val inProbe = CountDownLatch(1)
        c.phone.engine.static = { inProbe.countDown(); gate.await(20, TimeUnit.SECONDS); orig() }
        var old: Thread? = null
        val oldCtl = Part1Controller(Part1Switch { true }, runtimeOf(c), { r -> old = Thread(r).also { it.start() } }, { c.logs.add(it) })
        oldCtl.onActivated()
        assertTrue(inProbe.await(10, TimeUnit.SECONDS))
        oldCtl.close()
        val fresh = CRig(File(tmp.root, "fresh${System.nanoTime()}").also { it.mkdirs() })
        val newCtl = Part1Controller(Part1Switch { true }, { fresh.runtime() }, { it.run() }, { fresh.logs.add(it) }, previous = oldCtl, previousWaitMs = 100)
        newCtl.onActivated()
        assertEquals(0, fresh.factoryCalls)
        assertFalse(File(fresh.rig.dir, "key.txt").exists())
        assertTrue(fresh.logs.any { it.contains("earlier T0 is still running") })
        gate.countDown(); old?.join(10_000)
    }

    @Test
    fun givenAnOldT0ThatEndsInTime_whenAnActivationFollows_thenTheNewOneRunsItsT0() {
        val c = CRig()
        val gate = CountDownLatch(1)
        val orig = c.phone.engine.static
        val inProbe = CountDownLatch(1)
        c.phone.engine.static = { inProbe.countDown(); gate.await(20, TimeUnit.SECONDS); orig() }
        var old: Thread? = null
        val oldCtl = Part1Controller(Part1Switch { true }, runtimeOf(c), { r -> old = Thread(r).also { it.start() } }, { c.logs.add(it) })
        oldCtl.onActivated(); assertTrue(inProbe.await(10, TimeUnit.SECONDS)); oldCtl.close()
        val fresh = CRig(File(tmp.root, "fresh${System.nanoTime()}").also { it.mkdirs() })
        val newCtl = Part1Controller(Part1Switch { true }, { fresh.runtime() }, { it.run() }, { fresh.logs.add(it) }, previous = oldCtl, previousWaitMs = 10_000)
        Thread { Thread.sleep(200); gate.countDown() }.start()
        newCtl.onActivated()
        assertTrue(File(fresh.rig.dir, "static.json").exists())
        old?.join(10_000)
    }

    @Test
    fun givenTheHostHasNoBuildStatusService_whenTheModelLoads_thenT1IsNotOfferedAndTheLineIsLoggedOnce() {
        // F16
        val c = CRig()
        val ctl = Part1Controller(
            Part1Switch { true },
            { Part1Runtime(c.phone.reader(), c.rig.store, { c.rig.index }, EngineRunner.EXPECTED_ENGINE_VERSION, "1", { c.rig.tier() }, ideGateAvailable = false) },
            { it.run() }, { c.logs.add(it) },
        )
        ctl.onActivated()
        runBlocking { ctl.beforeModelLoad(); ctl.beforeModelLoad() }
        assertEquals(0, c.rig.ui.asked); assertTrue(c.rig.engine.commands.isEmpty())
        assertEquals(1, c.logs.count { it.contains("ide_gate_unavailable") })
    }

    @Test
    fun givenClosedDuringAPass_whenCloseRuns_thenThePassEndsCancelledWithPluginDeactivated() {
        val c = CRig(); val ctl = c.controller(); ctl.onActivated()
        c.rig.engine.beforeRun = { cmd -> if (c.rig.tagOf(cmd) == "C2-read-k0") ctl.close() }
        runBlocking { ctl.beforeModelLoad() }
        assertNull("a deactivated pass writes no pass record", c.rig.store.readPassRecord())
        assertEquals(1, c.rig.logs.count { it == "T1 stopped: plugin_deactivated" })
    }

    @Test
    fun givenCloseBetweenTheControllersCheckAndTheTier_whenTheTierIsBuilt_thenNoQuestionNoEngineCommandAndNoStoreWrite() {
        val ops = RecordingOps(); val c = CRig(ops = ops)
        val store = c.rig.store
        var ctl: Part1Controller? = null
        val rt = Part1Runtime(c.phone.reader(), store, { c.rig.index }, EngineRunner.EXPECTED_ENGINE_VERSION, "1", { ctl!!.close(); c.rig.tier() })
        ctl = Part1Controller(Part1Switch { true }, { rt }, { it.run() }, { c.logs.add(it) })
        ctl.onActivated()
        val writes = ops.replaced.size
        runBlocking { ctl.beforeModelLoad() }
        assertEquals(0, c.rig.ui.asked); assertTrue(c.rig.engine.commands.isEmpty())
        assertEquals(writes, ops.replaced.size)
        assertFalse(c.rig.dir.walkTopDown().any { it.name == "t1-pass.json" })
    }

    @Test
    fun givenCloseDuringARow_whenThePassEnds_thenNoStoreWriteHappensAfterTheCloseAndNoPassRecordIsWritten() {
        val ops = RecordingOps(); val c = CRig(ops = ops)
        val store = c.rig.store
        val rt = Part1Runtime(c.phone.reader(), store, { c.rig.index }, EngineRunner.EXPECTED_ENGINE_VERSION, "1", { c.rig.tier() })
        val ctl = Part1Controller(Part1Switch { true }, { rt }, { it.run() }, { c.logs.add(it) })
        ctl.onActivated()
        var writesAtClose = -1
        c.rig.engine.beforeRun = { cmd -> if (c.rig.tagOf(cmd) == "C2-read-k1") { writesAtClose = ops.replaced.size; ctl.close() } }
        runBlocking { ctl.beforeModelLoad() }
        assertTrue(writesAtClose > 0)
        assertEquals("a store write happened after close()", writesAtClose, ops.replaced.size)
        assertFalse(c.rig.dir.walkTopDown().any { it.name == "t1-pass.json" })
        assertTrue("rows stored before the close stay", File(c.rig.dir, "rows.jsonl").readText().contains("C2-read-k0"))
    }

    private fun runtimeOf(c: CRig): () -> Part1Runtime = { c.runtime() }
}
