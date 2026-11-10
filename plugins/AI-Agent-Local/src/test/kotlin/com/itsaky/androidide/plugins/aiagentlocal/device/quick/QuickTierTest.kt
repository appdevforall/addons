package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineResult
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.Recorded
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class QuickTierTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun rig(phone: String = "D5") = Rig(File(tmp.root, "r${System.nanoTime()}").also { it.mkdirs() }, phone)

    private val d5Tags = listOf("pinprobe", "C2-read-k0", "C2-read-k1", "C3-read-2fast", "C4-read-all", "C7-gpu-alone")

    /** The invariants of every exit path. */
    private fun Rig.assertClean() {
        assertFalse("a process is still running", engine.running)
        assertFalse("the keep-screen-on flag is still set", screen.on)
        assertEquals("the screen flag is released as often as it is set", screen.keepOnCalls, screen.releases)
        assertTrue("the dialog is closed", ui.closed >= 1)
        assertTrue("a half-written file", noTempFiles())
        assertEquals(wd.started, wd.closed)
    }

    // ---- the whole pass ------------------------------------------------------------------------------------------

    @Test
    fun givenAD5LikePhone_whenThePassRuns_thenItIsCompletedWithTheRowsInOrderAndTheRightCommandLines() {
        val r = rig()
        val res = r.run()
        assertEquals(QuickStatus.COMPLETED, res.status)
        assertEquals(StopReason.NONE, res.reason)
        assertEquals(d5Tags, r.tags())
        assertEquals(5, res.rowsStored)
        assertTrue(res.recorded)
        // typed builders only: every row carries the memory limit and --fit-buffer; L = min(55 % of 3817152 kB, 70 % of 2300000 kB) = 1572 MiB
        for (c in r.engine.commands.drop(1)) {
            assertEquals("1572", c.args[c.args.indexOf("--mem-limit-mib") + 1])
            assertTrue(c.args.contains("--fit-buffer"))
            assertTrue(c.args.contains("--tier") && c.args[c.args.indexOf("--tier") + 1] == "T1")
        }
        assertEquals(listOf("--cores", "0"), r.engine.commands[1].args.let { it.subList(it.indexOf("--cores"), it.indexOf("--cores") + 2) })
        assertEquals("4", r.engine.commands[2].args[r.engine.commands[2].args.indexOf("--cores") + 1])
        assertEquals("4+5", r.engine.commands[3].args[r.engine.commands[3].args.indexOf("--cores") + 1])
        assertEquals("0+1+2+3+4+5+6+7", r.engine.commands[4].args[r.engine.commands[4].args.indexOf("--cores") + 1])
        // progress: row i of 6, what it measures
        assertEquals(listOf(1, 2, 3, 4, 5, 6), r.ui.progress.map { it.row })
        assertEquals(setOf(6), r.ui.progress.map { it.total }.toSet())
        assertEquals(listOf(RowLabel.PIN_PROBE, RowLabel.CLUSTER_READ, RowLabel.CLUSTER_READ, RowLabel.TWO_FAST_READ, RowLabel.ALL_READ, RowLabel.GPU_READ), r.ui.progress.map { it.label })
        assertEquals(1, r.screen.keepOnCalls)
        assertEquals(QuickLimits.OVERALL_MS, r.wd.ms)
        r.assertClean()
        // records
        val p = r.pass()
        assertEquals("completed", p["status"]); assertEquals("none", p["reason"]); assertEquals(5L, p["rows_stored"])
        assertTrue((p["elapsed_ms"] as Long) > 0)
        assertNotNull(p["pin_probe"])
        assertNotNull(r.store.readDescriptor())
        assertEquals(5, r.store.readRows().lines.size)
        // the stored rows carry what the app knows
        val row = Json.parseObject(r.store.readRows().lines[0])
        val c = Json.obj(row["conditions"], "c")
        assertEquals(90L, (c["battery_pct"] as Number).toLong()); assertEquals(0L, (c["thermal_status"] as Number).toLong())
        assertEquals(true, c["screen_on"]); assertEquals("1280x800", c["display_px"]); assertEquals(60.0, (c["display_refresh_hz"] as Number).toDouble(), 0.0)
        assertTrue((c["note"] as String).contains("app: wall "))
        assertEquals("T1", row["tier"])
    }

    @Test
    fun givenThePassRuns_whenTheLogIsRead_thenItNamesRowsAndOutcomesOnly() {
        val r = rig(); r.run()
        val all = r.logs.joinToString("\n")
        assertTrue(all.contains("row C2-read-k0 stored"))
        assertFalse(all.contains(r.fingerprint)); assertFalse(all.contains(com.itsaky.androidide.plugins.aiagentlocal.device.store.DeviceKey.of(r.fingerprint, "part1-engine/0.2.4")))
        assertFalse(all.contains("/home")); assertFalse(all.contains("synthetic"))
    }

    // ---- the question ------------------------------------------------------------------------------------------

    @Test
    fun givenTheUserSkips_whenThePassRuns_thenNothingIsMeasuredAndNothingIsLeftOn() {
        val r = rig(); r.ui.answer = { QuickAnswer.SKIP }
        val res = r.run()
        assertEquals(QuickStatus.SKIPPED, res.status); assertEquals(StopReason.USER_SKIPPED, res.reason)
        assertTrue(r.engine.commands.isEmpty())
        assertEquals(0, r.screen.keepOnCalls); assertEquals(0, r.wd.started)
        assertNull(r.store.readPassRecord())
        assertEquals(1, r.ui.closed)
    }

    @Test
    fun givenNoActivity_whenThePassRuns_thenItIsDeferredWithoutMeasuring() {
        val r = rig(); r.ui.answer = { QuickAnswer.NO_ACTIVITY }
        val res = r.run()
        assertEquals(QuickStatus.DEFERRED, res.status); assertEquals(StopReason.NO_ACTIVITY, res.reason)
        assertTrue(r.engine.commands.isEmpty()); assertEquals(0, r.screen.keepOnCalls)
    }

    @Test
    fun givenNoStoredStaticInput_whenThePassRuns_thenItIsDeferredAndTheUserIsNotAsked() {
        val r = rig()
        java.io.File(r.dir, "static.json").delete()
        val res = r.run()
        assertEquals(QuickStatus.DEFERRED, res.status); assertEquals(StopReason.STATIC_MISSING, res.reason)
        assertEquals(0, r.ui.asked)
    }

    @Test
    fun givenABatteryBelow20OrUnreadableBeforeTheQuestion_whenThePassRuns_thenItIsDeferredWithoutAsking() {
        val low = rig(); low.cond.battery = { 19 }
        assertEquals(StopReason.BATTERY_LOW, low.run().reason); assertEquals(0, low.ui.asked)
        val unread = rig(); unread.cond.battery = { null }
        assertEquals(StopReason.BATTERY_UNREADABLE, unread.run().reason); assertEquals(0, unread.ui.asked)
        val exactly = rig(); exactly.cond.battery = { 20 }
        assertEquals(QuickStatus.COMPLETED, exactly.run().status) // 20 % passes
    }

    // ---- gates ---------------------------------------------------------------------------------------------------

    @Test
    fun givenNotInTheForegroundBeforeTheStart_whenThePassRuns_thenItIsDeferred() {
        val r = rig(); r.fg.fg = { false }
        val res = r.run()
        assertEquals(QuickStatus.DEFERRED, res.status); assertEquals(StopReason.NOT_FOREGROUND, res.reason)
        assertTrue(r.engine.commands.isEmpty()); r.assertClean()
    }

    @Test
    fun givenTheForegroundIsLostDuringARow_whenThePassRuns_thenThatRowIsVoidNotStoredAndThePassEnds() {
        val r = rig()
        var lost = false
        r.engine.beforeRun = { c -> if (c.tag == "C2-read-k1") lost = true }
        r.fg.fg = { !lost }
        val res = r.run()
        assertEquals(QuickStatus.ENDED_EARLY, res.status); assertEquals(StopReason.NOT_FOREGROUND, res.reason)
        assertEquals(1, res.rowsVoid); assertEquals(1, res.rowsStored) // only C2-read-k0
        assertEquals(listOf("pinprobe", "C2-read-k0", "C2-read-k1"), r.tags())
        assertEquals(1, r.store.readRows().lines.size)
        r.assertClean()
    }

    @Test
    fun givenAnEngineRefusalForTheForeground_whenARowRuns_thenThePassIsVoidedAndStopped() {
        val r = rig()
        r.overrides["C2-read-k1"] = { EngineResult.Refused(Lines.obj("d5-row-gate-97.out"), 97) }
        val res = r.run()
        assertEquals(QuickStatus.ENDED_EARLY, res.status); assertEquals(StopReason.NOT_FOREGROUND, res.reason)
        assertEquals(1, res.rowsVoid); assertEquals(1, res.rowsStored)
        assertEquals(listOf("pinprobe", "C2-read-k0", "C2-read-k1"), r.tags()) // stopped: nothing after the 97
        r.assertClean()
    }

    @Test
    fun givenABusyIdeBeforeTheStart_whenItBecomesQuietWithin60s_thenThePassWaitsAndRuns() {
        val r = rig()
        val t0 = r.clock.now
        r.ide.busy = { r.clock.now - t0 < 20_000 }
        val res = r.run()
        assertEquals(QuickStatus.COMPLETED, res.status)
        assertTrue(r.ui.waiting.contains(WaitingFor.IDE_QUIET))
        val waited = ((r.pass()["waits_ms"] as Map<*, *>)["ide"] as Number).toLong()
        assertTrue("waited $waited", waited in 20_000..21_000)
        // polled in steps of at most 500 ms
        assertTrue(r.clock.sleeps.all { it <= QuickLimits.POLL_MS })
    }

    @Test
    fun givenAnIdeBusyForMoreThan60sBeforeTheStart_whenThePassRuns_thenItIsDeferredAfter60s() {
        val r = rig(); r.ide.busy = { true }
        val t0 = r.clock.now
        val res = r.run()
        assertEquals(QuickStatus.DEFERRED, res.status); assertEquals(StopReason.IDE_BUSY, res.reason)
        assertEquals(QuickLimits.IDE_WAIT_MS, r.clock.now - t0)
        assertTrue(r.engine.commands.isEmpty()); r.assertClean()
        assertNull(r.store.readPassRecord())
    }

    @Test
    fun givenTheIdeBecomesBusyBeforeALaterRow_whenItStaysBusy_thenThePassEndsEarlyKeepingTheValidRows() {
        val r = rig()
        var busy = false
        r.engine.beforeRun = { c -> if (c.tag == "C2-read-k0") busy = true } // busy from the end of the first C2 row on
        r.overrides["C2-read-k0"] = { c -> Lines.ok("d5-row-c2-ok-v0.2.1.out").also { busy = true } }
        r.ide.busy = { busy }
        val res = r.run()
        // the first C2 row ended while the IDE was busy: it is void and, being the first void, repeated after waiting; the wait times out
        assertEquals(QuickStatus.ENDED_EARLY, res.status); assertEquals(StopReason.IDE_BUSY, res.reason)
        assertEquals(1, res.rowsVoid)
        assertEquals(0, res.rowsStored)
        r.assertClean()
    }

    @Test
    fun givenABuildStartsDuringARow_whenTheRowEnds_thenItIsVoidAndRepeatedOnce() {
        val r = rig()
        var first = true
        r.engine.beforeRun = { c -> if (c.tag == "C2-read-k1" && first) { first = false; r.ide.starts++ } }
        val res = r.run()
        assertEquals(QuickStatus.COMPLETED, res.status)
        assertEquals(1, res.rowsVoid); assertEquals(5, res.rowsStored)
        assertEquals(listOf("pinprobe", "C2-read-k0", "C2-read-k1", "C2-read-k1", "C3-read-2fast", "C4-read-all", "C7-gpu-alone"), r.tags())
        assertEquals(2, r.tags().count { it == "C2-read-k1" })
        val notes = (r.pass()["rows"] as List<*>).map { (it as Map<*, *>)["outcome"] }
        assertTrue(notes.contains("void_build_started"))
        r.assertClean()
    }

    @Test
    fun givenABuildStartsDuringTheRepeatToo_whenTheRowEnds_thenThePassEndsEarly() {
        val r = rig()
        r.engine.beforeRun = { c -> if (c.tag == "C2-read-k1") r.ide.starts++ }
        val res = r.run()
        assertEquals(QuickStatus.ENDED_EARLY, res.status); assertEquals(StopReason.IDE_BUSY, res.reason)
        assertEquals(2, res.rowsVoid); assertEquals(1, res.rowsStored)
        assertEquals(2, r.tags().count { it == "C2-read-k1" })
        r.assertClean()
    }

    @Test
    fun givenABuildStillRunningWhenARowEnds_whenChecked_thenItVoidsTheRowAndTheRepeatWaitsForQuiet() {
        val r = rig()
        var until = 0L
        r.engine.beforeRun = { c -> if (c.tag == "C4-read-all" && until == 0L) until = r.clock.now + 20_000 } // a build begins with the row and outlasts it
        r.ide.busy = { until != 0L && r.clock.now < until }
        val res = r.run()
        // the row ended while the build ran: void; the repeat waited for the build to end and then ran
        assertEquals(1, res.rowsVoid); assertEquals(QuickStatus.COMPLETED, res.status)
        assertEquals(2, r.tags().count { it == "C4-read-all" })
        assertTrue(r.ui.waiting.contains(WaitingFor.IDE_QUIET))
    }

    @Test
    fun givenABatteryDropOf5PointsSinceTheStart_whenARowIsDue_thenThePassStops_andOf4ItGoesOn() {
        val drop5 = rig()
        var level = 90
        drop5.cond.battery = { level }
        drop5.engine.beforeRun = { c -> if (c.tag == "C2-read-k1") level = 85 }
        val res = drop5.run()
        assertEquals(QuickStatus.ENDED_EARLY, res.status); assertEquals(StopReason.BATTERY_DROP, res.reason)
        assertEquals(listOf("pinprobe", "C2-read-k0", "C2-read-k1"), drop5.tags())
        drop5.assertClean()
        val drop4 = rig()
        var l4 = 90
        drop4.cond.battery = { l4 }
        drop4.engine.beforeRun = { c -> if (c.tag == "C2-read-k1") l4 = 86 }
        assertEquals(QuickStatus.COMPLETED, drop4.run().status)
    }

    @Test
    fun givenTheBatteryFallsBelow20DuringThePass_whenARowIsDue_thenThePassStops() {
        val r = rig()
        var level = 24
        r.cond.battery = { level }
        r.engine.beforeRun = { c -> if (c.tag == "C2-read-k0") level = 19 }
        val res = r.run()
        assertEquals(StopReason.BATTERY_LOW, res.reason); assertEquals(QuickStatus.ENDED_EARLY, res.status)
    }

    @Test
    fun givenAHotPhone_whenARowIsDue_thenItWaitsForNoneOrLightAndRecordsTheWait() {
        val r = rig()
        val t0 = r.clock.now
        r.cond.status = { if (r.clock.now - t0 < 12_000) 2 else 0 } // MODERATE for 12 s
        val res = r.run()
        assertEquals(QuickStatus.COMPLETED, res.status)
        assertTrue(r.ui.waiting.contains(WaitingFor.THERMAL))
        val w = ((r.pass()["waits_ms"] as Map<*, *>)["thermal"] as Number).toLong()
        assertTrue("thermal wait $w", w in 12_000..13_000)
    }

    @Test
    fun givenAPhoneThatStaysHot_whenARowIsDue_thenItWaitsUpTo60sAndTheDeferredPassEndsOrIsDeferred() {
        val before = rig(); before.cond.status = { 3 }
        val t0 = before.clock.now
        val res = before.run()
        assertEquals(StopReason.THERMAL, res.reason); assertEquals(QuickStatus.DEFERRED, res.status) // nothing had started yet
        assertEquals(QuickLimits.THERMAL_WAIT_MS, before.clock.now - t0)
        // hot only from the second row on: the pass ends early
        val later = rig()
        later.engine.beforeRun = { c -> if (c.tag == "C2-read-k0") later.cond.status = { 4 } }
        val res2 = later.run()
        assertEquals(QuickStatus.ENDED_EARLY, res2.status); assertEquals(StopReason.THERMAL, res2.reason)
        assertEquals(1, res2.rowsStored)
        later.assertClean()
    }

    @Test
    fun givenStatusLightOrNone_whenARowIsDue_thenItStartsAtOnce() {
        val r = rig(); r.cond.status = { 1 }
        val res = r.run()
        assertEquals(QuickStatus.COMPLETED, res.status)
        assertFalse(r.ui.waiting.contains(WaitingFor.THERMAL))
        assertEquals(0L, ((r.pass()["waits_ms"] as Map<*, *>)["thermal"] as Number).toLong())
        // status 2 does not start
        val hot = rig(); hot.cond.status = { 2 }
        assertEquals(StopReason.THERMAL, hot.run().reason)
    }

    @Test
    fun givenNeitherThermalStatusNorHeadroomCanBeRead_whenRowsFollowEachOther_thenAFixed10sRestSitsBetweenThemAndNotBeforeTheFirst() {
        val r = rig(); r.cond.status = { null }; r.cond.headroom = { null }
        val t0 = r.clock.now
        val res = r.run()
        assertEquals(QuickStatus.COMPLETED, res.status)
        val rest = ((r.pass()["waits_ms"] as Map<*, *>)["rest"] as Number).toLong()
        assertEquals(5 * QuickLimits.BLIND_REST_MS, rest) // six rows, five rests
        assertEquals(5, r.ui.waiting.count { it == WaitingFor.REST })
        assertTrue(r.clock.now - t0 >= rest)
    }

    @Test
    fun givenStatusUnreadableButHeadroomANumber_whenRowsRun_thenThereIsNoRestAndTheHeadroomIsRecorded() {
        val r = rig(); r.cond.status = { null }; r.cond.headroom = { 0.4 }
        assertEquals(QuickStatus.COMPLETED, r.run().status)
        assertEquals(0L, ((r.pass()["waits_ms"] as Map<*, *>)["rest"] as Number).toLong())
        val c = Json.obj(Json.parseObject(r.store.readRows().lines[0])["conditions"], "c")
        assertEquals(0.4, (c["thermal_headroom"] as Number).toDouble(), 0.0)
        assertFalse(c.containsKey("thermal_status") && c["thermal_status"] != null)
    }

    @Test
    fun givenTheMemoryCannotBeRead_whenARowIsDue_thenThePassEndsAfterThePinProbe() {
        val r = rig(); r.mem.info = { null }
        val res = r.run()
        assertEquals(StopReason.MEMORY_UNREADABLE, res.reason); assertEquals(QuickStatus.ENDED_EARLY, res.status)
        assertEquals(listOf("pinprobe"), r.tags())
        assertNotNull(r.store.readStatic()!!["pin_origin"]) // the pin probe was folded and kept
        r.assertClean()
    }

    @Test
    fun givenTheLimitIsReadBeforeEachRow_whenTheMemoryChanges_thenEachRowCarriesItsOwnLimit() {
        val r = rig()
        var avail = 2_300_000L
        r.mem.info = { MemInfo(3_817_152L, avail) }
        r.engine.beforeRun = { c -> if (c.tag == "C2-read-k0") avail = 1_000_000L }
        r.run()
        val limits = r.engine.commands.drop(1).map { it.args[it.args.indexOf("--mem-limit-mib") + 1].toLong() }
        assertEquals(1572L, limits[0])
        assertEquals(683L, limits[1]) // 70 % of 1000000 kB = 700000 kB = 683 MiB
    }

    // ---- cancellation and the limit --------------------------------------------------------------------------------

    @Test
    fun givenCancelIsPressedAtEveryRow_whenThePassRuns_thenNoProcessNoHalfWrittenFileAndTheScreenFlagIsCleared() {
        for (tag in d5Tags) {
            val r = rig()
            r.engine.beforeRun = { c -> if (r.tagOf(c) == tag) r.ui.cancelHandler() } // the Cancel button the pass registered
            val res = r.run()
            assertEquals(tag, QuickStatus.CANCELLED, res.status); assertEquals(StopReason.USER_CANCELLED, res.reason)
            val expectedStored = maxOf(0, d5Tags.indexOf(tag) - 1)
            assertEquals(tag, expectedStored, res.rowsStored)
            assertTrue(tag, r.engine.cancelCalls >= 1)
            assertEquals(tag, d5Tags.take(d5Tags.indexOf(tag) + 1), r.tags()) // nothing after the cancelled row
            r.assertClean()
            // a cancelled row is not stored as a measurement
            assertFalse(tag, r.store.readRows().lines.any { it.contains("\"$tag\"") })
        }
    }

    @Test
    fun givenCancelIsPressedWhileTheIdeIsBeingWaitedFor_whenThePassWaits_thenItIsCancelledAtOnce() {
        val r = rig(); r.ide.busy = { true }
        r.clock.onSleep = { r.ui.cancelHandler() }
        val res = r.run()
        assertEquals(QuickStatus.CANCELLED, res.status)
        assertEquals(1, r.clock.sleeps.size)
        r.assertClean()
    }

    @Test
    fun givenTheWatchdogFiresDuringARow_whenThePassRuns_thenItEndsEarlyWithTheOverallLimit() {
        val r = rig()
        r.engine.beforeRun = { c -> if (c.tag == "C3-read-2fast") r.wd.fire() }
        val res = r.run()
        assertEquals(QuickStatus.ENDED_EARLY, res.status); assertEquals(StopReason.OVERALL_LIMIT, res.reason)
        assertEquals(2, res.rowsStored)
        assertTrue(r.engine.cancelCalls >= 1)
        r.assertClean()
    }

    @Test
    fun givenMoreThan180sHavePassed_whenTheNextRowIsDue_thenThePassStopsWithTheOverallLimit() {
        val r = rig()
        r.engine.beforeRun = { c -> if (c.tag == "C2-read-k1") r.clock.now += 181_000 }
        val res = r.run()
        assertEquals(StopReason.OVERALL_LIMIT, res.reason); assertEquals(QuickStatus.ENDED_EARLY, res.status)
        assertEquals(listOf("pinprobe", "C2-read-k0", "C2-read-k1"), r.tags())
        assertEquals(2, res.rowsStored) // the row that ran is valid and kept
        assertTrue(res.elapsedMs >= 181_000)
    }

    @Test
    fun givenExactly180sHavePassedAtAGate_whenARowIsDue_thenThePassStops_andOneMillisecondLessItGoesOn() {
        fun nextRowStartedWhenElapsedAtTheNextGateIs(ms: Long): Boolean {
            val r = rig()
            val t0 = r.clock.now
            // the recorded C2-read-k0 row takes 10 974 ms of fake time (served after this hook), so the next gate sees exactly [ms]
            r.engine.beforeRun = { c -> if (c.tag == "C2-read-k0") r.clock.now = t0 + ms - 10_974 }
            r.run()
            return r.tags().contains("C2-read-k1")
        }
        assertFalse(nextRowStartedWhenElapsedAtTheNextGateIs(180_000))
        assertTrue(nextRowStartedWhenElapsedAtTheNextGateIs(179_999))
    }

    @Test
    fun givenTheThreadIsInterruptedWhileWaiting_whenThePassWaits_thenItCountsAsCancelled() {
        val r = rig(); r.ide.busy = { true }; r.clock.interruptOnSleep = true
        val res = r.run()
        assertEquals(QuickStatus.CANCELLED, res.status)
        r.assertClean()
        Thread.interrupted() // the pass keeps the flag for its caller; clear it for the next test
    }

    @Test
    fun givenAnExceptionFromAGate_whenThePassRuns_thenItIsAResultNotAThrow() {
        val r = rig()
        r.fg.fg = { throw IllegalStateException("boom") }
        val res = r.run()
        assertEquals(QuickStatus.FAILED, res.status); assertEquals(StopReason.UNEXPECTED, res.reason)
        r.assertClean()
        val r2 = rig(); r2.cond.status = { throw IllegalStateException("boom") }
        assertEquals(StopReason.UNEXPECTED, r2.run().reason); r2.assertClean()
    }

    // ---- every runner result class at every row position --------------------------------------------------------------

    private fun resultsToTry(): Map<String, () -> EngineResult> = linkedMapOf(
        "ExecBlocked" to { EngineResult.ExecBlocked("IOException: error=13") },
        "ProgramMissing" to { EngineResult.ProgramMissing("libpart1_membw.so was not found") },
        "EngineMismatch" to { EngineResult.EngineMismatch("part1-engine/0.2.3") },
        "NotStarted" to { EngineResult.NotStarted("the work directory cannot be created") },
        "TimeoutKilled" to { EngineResult.TimeoutKilled },
        "NoJson" to { EngineResult.NoJson(139, "") },
        "FailedNoObject" to { EngineResult.Failed(null, 1) },
        "Cancelled" to { EngineResult.Cancelled },
    )

    @Test
    fun givenAnyEngineProblemAtAnyRowPosition_whenThePassRuns_thenItIsARecordedReasonNeverAThrowAndNothingIsLeftBehind() {
        val expected = mapOf(
            "ExecBlocked" to StopReason.EXEC_BLOCKED, "ProgramMissing" to StopReason.PROGRAM_MISSING, "EngineMismatch" to StopReason.ENGINE_MISMATCH,
            "NotStarted" to StopReason.NOT_STARTED, "TimeoutKilled" to StopReason.ENGINE_TIMEOUT, "NoJson" to StopReason.ENGINE_CRASH,
            "FailedNoObject" to StopReason.ENGINE_CRASH, "Cancelled" to StopReason.USER_CANCELLED,
        )
        for ((name, make) in resultsToTry()) {
            for ((pos, tag) in d5Tags.withIndex()) {
                val r = rig()
                r.overrides[tag] = { make() }
                val res = r.run()
                val where = "$name at $tag"
                assertEquals(where, expected.getValue(name), res.reason)
                val stored = if (pos == 0) 0 else pos - 1 // the pin probe is not a stored row
                val wantStatus = when {
                    name == "Cancelled" -> QuickStatus.CANCELLED
                    stored == 0 -> QuickStatus.FAILED // nothing stored yet
                    else -> QuickStatus.ENDED_EARLY // earlier rows are kept
                }
                assertEquals(where, wantStatus, res.status)
                assertEquals(where, stored, res.rowsStored)
                assertEquals(where, d5Tags.take(pos + 1), r.tags())
                assertTrue(where, res.recorded)
                r.assertClean()
            }
        }
    }

    @Test
    fun givenTheEngineRefusesOrFailsWithItsOwnRecord_whenARowRuns_thenItIsStoredWithItsStatusAndThePassGoesOn() {
        val cases = mapOf(
            "memory 88" to { EngineResult.Refused(Lines.obj("d5-row-memory-88.out"), 88) },
            "timeout 124" to { EngineResult.Failed(Lines.obj("d5-row-timeout-124.out"), 124) },
        )
        for ((name, make) in cases) {
            for (tag in d5Tags.drop(1)) {
                val r = rig()
                // the engine's record is for another tag: serve it under the tag that was started
                r.overrides[tag] = { c ->
                    val base = make()
                    val j = (base as? EngineResult.Refused)?.json ?: (base as EngineResult.Failed).json!!
                    val retagged = LinkedHashMap(j); retagged["tag"] = c.tag
                    if (base is EngineResult.Refused) EngineResult.Refused(retagged, base.exit) else EngineResult.Failed(retagged, (base as EngineResult.Failed).exit)
                }
                val res = r.run()
                assertEquals("$name at $tag", QuickStatus.COMPLETED, res.status)
                assertEquals("$name at $tag", 5, res.rowsStored)
                val statuses = r.store.readRows().lines.map { Json.parseObject(it)["status"] }
                assertTrue("$name at $tag $statuses", statuses.any { it == "skipped" || it == "failed" })
                r.assertClean()
            }
        }
        // at the pin probe the same answers end the pass: there is no pin result to fold
        val r = rig()
        r.overrides["pinprobe"] = { EngineResult.Refused(mapOf("format" to "part1-pinprobe-v1", "status" to "skipped"), 88) }
        assertEquals(StopReason.PIN_PROBE_FAILED, r.run().reason)
    }

    @Test
    fun givenAnUnstorableEngineRecord_whenARowRuns_thenItIsNotForcedInAndThePassEndsEarlyAtTheEnd() {
        val r = rig()
        r.overrides["C3-read-2fast"] = { EngineResult.Failed(mapOf("format" to "part1-rows-v1", "status" to "failed"), 1) }
        val res = r.run()
        assertEquals(QuickStatus.ENDED_EARLY, res.status)
        assertEquals(4, res.rowsStored)
        assertEquals(d5Tags, r.tags())
        r.assertClean()
    }

    @Test
    fun givenTheStoreRefusesARow_whenARowIsStored_thenThePassEndsEarlyWithStoreFailed() {
        val r = rig()
        r.engine.beforeRun = { c -> if (c.tag == "C4-read-all") java.io.File(r.dir, "rows.jsonl").also { it.delete() }.mkdirs() } // the log cannot be written
        val res = r.run()
        assertEquals(StopReason.STORE_FAILED, res.reason)
        r.assertClean()
    }

    // ---- row planning inside the pass ---------------------------------------------------------------------------------

    @Test
    fun givenAFirstCpuThatDoesNotPin_whenTheClusterIsRead_thenASubstituteOfTheSameClusterIsUsedAndRecorded() {
        val r = rig()
        // the pin probe says CPU 4 failed: the second cluster is read on CPU 5
        r.overrides["pinprobe"] = { val j = LinkedHashMap(Lines.obj("d5-pinprobe-v0.2.3.out"))
            val cpus = (j["cpus"] as List<*>).map { c -> val m = LinkedHashMap(c as Map<String, Any?>); if ((m["index"] as Number).toLong() == 4L) m["result"] = "failed"; m }
            j["cpus"] = cpus; EngineResult.Ok(j) }
        val res = r.run()
        assertEquals(QuickStatus.COMPLETED, res.status)
        val k1 = r.engine.commands.first { it.tag == "C2-read-k1" }
        assertEquals("5", k1.args[k1.args.indexOf("--cores") + 1])
        val twoFast = r.engine.commands.first { it.tag == "C3-read-2fast" }
        assertEquals("5+6", twoFast.args[twoFast.args.indexOf("--cores") + 1]) // 4 is not pinnable: the next of the fastest cluster
        val all = r.engine.commands.first { it.tag == "C4-read-all" }
        assertEquals("0+1+2+3+5+6+7", all.args[all.args.indexOf("--cores") + 1])
        val row = r.store.readRows().lines.map { Json.parseObject(it) }.first { it["tag"] == "C2-read-k1" }
        val pin = Json.obj(row["pin"], "pin")
        assertEquals("substituted", pin["result"]); assertEquals(4L, (pin["substituted_for"] as Number).toLong())
    }

    @Test
    fun givenAClusterWithNoPinnableCpu_whenItIsRead_thenOneUnpinnedRowIsTakenAndLabelled() {
        val r = rig()
        r.overrides["pinprobe"] = { val j = LinkedHashMap(Lines.obj("d5-pinprobe-v0.2.3.out"))
            val cpus = (j["cpus"] as List<*>).map { c -> val m = LinkedHashMap(c as Map<String, Any?>); if ((m["index"] as Number).toLong() in 0L..3L) m["result"] = "failed"; m }
            j["cpus"] = cpus; EngineResult.Ok(j) }
        r.run()
        val k0 = r.engine.commands.first { it.tag == "C2-read-k0" }
        assertTrue(k0.args.contains("--allow-unpinned"))
        assertEquals("0", k0.args[k0.args.indexOf("--cores") + 1])
        val all = r.engine.commands.first { it.tag == "C4-read-all" }
        assertEquals("4+5+6+7", all.args[all.args.indexOf("--cores") + 1])
        assertFalse(r.engine.commands.first { it.tag == "C2-read-k1" }.args.contains("--allow-unpinned"))
    }

    @Test
    fun givenNoVulkanComputeDevice_whenThePassRuns_thenThereIsNoGpuRow() {
        val r = rig()
        val s = LinkedHashMap(r.store.readStatic()!!)
        s["vulkan"] = linkedMapOf("present" to false)
        r.store.writeStatic(s)
        val res = r.run()
        assertEquals(QuickStatus.COMPLETED, res.status)
        assertEquals(d5Tags.dropLast(1), r.tags())
        assertEquals(5, r.ui.progress.last().total)
    }

    @Test
    fun givenAFailingPinProbeStatus_whenThePassRuns_thenNothingElseRuns() {
        val r = rig()
        r.overrides["pinprobe"] = { val j = LinkedHashMap(Lines.obj("d5-pinprobe-v0.2.3.out")); j["status"] = "skipped"; EngineResult.Ok(j) }
        val res = r.run()
        assertEquals(StopReason.PIN_PROBE_FAILED, res.reason); assertEquals(QuickStatus.FAILED, res.status)
        assertEquals(listOf("pinprobe"), r.tags())
    }

    @Test
    fun givenAPassThatWasCompletedBefore_whenAnotherEndsEarly_thenThePinProbeOfTheFirstIsStillInTheRecord() {
        val r = rig(); r.run()
        val first = r.pass()["pin_probe"]
        assertNotNull(first)
        r.overrides["pinprobe"] = { EngineResult.NoJson(1, "") }
        val res = r.run()
        assertEquals(StopReason.ENGINE_CRASH, res.reason)
        assertEquals(first, r.pass()["pin_probe"])
    }
}
