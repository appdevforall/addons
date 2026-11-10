package com.itsaky.androidide.plugins.aiagentlocal.device.engine

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EngineRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun work() = File(tmp.root, "part1/work")

    private fun recorded(name: String, exit: Int = Recorded.exit(name)) = FakeProcess(stdout = Recorded.bytes(name), exit = exit)

    private fun runC2(name: String, exit: Int = Recorded.exit(name)): Pair<EngineResult, FakeLauncher> {
        val (runner, launcher) = runnerFor(work()) { recorded(name, exit) }
        return runner.run(EngineCommands.c2Read(0, 0, 1694)) to launcher
    }

    @Test
    fun givenARecordedOkRow_whenRun_thenTheResultIsOkAndTheProcessWasStartedAsTheRulesSay() {
        val (r, launcher) = runC2("d5-row-c2-ok-v0.2.1.out")
        r as EngineResult.Ok
        assertEquals("C2-read-k0", r.json["tag"]) // the recorded row has its own tag
        assertEquals("ok", r.json["status"])
        assertEquals(2, launcher.starts.size) // the version probe, then the row
        val row = launcher.starts[1]
        assertEquals(File("/plugin/native/libpart1_membw.so").path, row.argv[0])
        assertEquals(EngineCommands.c2Read(0, 0, 1694).args, row.argv.drop(1))
        assertEquals(work(), row.workDir)
        assertTrue(work().isDirectory) // created
        assertEquals(emptyMap<String, String>(), row.env) // a cleared environment
        assertTrue(row.process.stdinClosed)
        assertEquals(listOf((120 + 5) * 1000L), row.process.waits.take(1)) // hard limit: --max-seconds + 5 s
    }

    @Test
    fun givenARecordedGpuRowWithManyHumanLines_whenRun_thenTheLastLineIsTheResult() {
        val (runner, launcher) = runnerFor(work()) { recorded("d5-gpu-row-ok.out") }
        val r = runner.run(EngineCommands.c7GpuAlone(1694)) as EngineResult.Ok
        assertEquals("gpu", r.json["kind"])
        assertEquals(File("/plugin/native/libpart1_gpubw.so").path, launcher.starts[1].argv[0])
        assertEquals((180 + 5) * 1000L, launcher.starts[1].process.waits[0])
    }

    @Test
    fun givenRecordedRefusals_whenRun_thenTheyAreRefusedWithTheirExitCodes() {
        for ((name, exit) in listOf("d5-row-memory-88.out" to 88, "d5-row-gate-97.out" to 97)) {
            val (r, _) = runC2(name)
            r as EngineResult.Refused
            assertEquals(exit, r.exit)
            assertEquals("skipped", r.json["status"])
        }
        // exit 3 (unsupported) is a refusal too
        val (runner, _) = runnerFor(work()) { FakeProcess(stdout = """{"format":"part1-rows-v1","tag":"C7-gpu-alone","status":"unsupported","reason":"no Vulkan"}""".toByteArray(), exit = 3) }
        val r = runner.run(EngineCommands.c7GpuAlone(100)) as EngineResult.Refused
        assertEquals(3, r.exit)
    }

    @Test
    fun givenTheRecordedTimeoutRowAndErrorExits_whenRun_thenTheyAreFailedWithTheEnginesObject() {
        val (r, _) = runC2("d5-row-timeout-124.out")
        r as EngineResult.Failed
        assertEquals(124, r.exit)
        assertEquals("failed", r.json!!["status"])
        for (exit in listOf(1, 2, 87)) {
            val (rr, _) = runC2("d5-row-timeout-124.out", exit)
            assertEquals(exit, (rr as EngineResult.Failed).exit)
        }
        // exit 0 with a status other than ok is not Ok
        val (runner, _) = runnerFor(work()) { FakeProcess(stdout = """{"format":"part1-rows-v1","tag":"x","status":"failed"}""".toByteArray(), exit = 0) }
        assertEquals(0, (runner.run(EngineCommands.c2Read(0, 0, 100)) as EngineResult.Failed).exit)
    }

    @Test
    fun givenAnExitWithoutAnyJson_whenRun_thenItIsNoJsonWithTheExitAndTheEndOfStderr() {
        for (exit in listOf(126, 127, 137, 139)) {
            val (runner, _) = runnerFor(work()) { FakeProcess(stdout = ByteArray(0), stderr = "linker: library libvulkan.so not found\n".toByteArray(), exit = exit) }
            val r = runner.run(EngineCommands.c2Read(0, 0, 100)) as EngineResult.NoJson
            assertEquals(exit, r.exit)
            assertEquals("linker: library libvulkan.so not found\n", r.stderrTail)
        }
        // only the last 1 KiB of stderr
        val long = "x".repeat(5000) + "END"
        val (runner, _) = runnerFor(work()) { FakeProcess(stderr = long.toByteArray(), exit = 1) }
        val tail = (runner.run(EngineCommands.c2Read(0, 0, 100)) as EngineResult.NoJson).stderrTail
        assertEquals(EngineRunner.STDERR_TAIL_BYTES, tail.length)
        assertTrue(tail.endsWith("END"))
    }

    @Test
    fun givenAGarbageLastLine_whenRun_thenItIsNoJson() {
        val outputs = listOf(
            "{\"format\":\"part1-rows-v1\",\"status\":\"ok\"}\n# pin who=membw-t0 cpu=0 attempts=1 time_to_pin_ms=0.1 result=pinned\n",
            "{\"format\":\"part1-rows-v1\"\n", // truncated object
            "[1,2,3]\n", // not an object
            "{\"a\":1} trailing\n",
            "\n\n  \n",
        )
        for (o in outputs) {
            val (runner, _) = runnerFor(work()) { FakeProcess(stdout = o.toByteArray(), exit = 0) }
            assertTrue(o, runner.run(EngineCommands.c2Read(0, 0, 100)) is EngineResult.NoJson)
        }
    }

    @Test
    fun givenTrailingBlankLinesAndCarriageReturns_whenRun_thenTheLastNonEmptyLineIsRead() {
        val line = Recorded.lastLine("d5-row-c2-ok-v0.2.1.out")
        val out = "noise\r\n$line\r\n\r\n   \n"
        val (runner, _) = runnerFor(work()) { FakeProcess(stdout = out.toByteArray(), exit = 0) }
        assertTrue(runner.run(EngineCommands.c2Read(0, 0, 100)) is EngineResult.Ok)
    }

    @Test
    fun givenAStartThatThrows_whenRun_thenItIsExecBlockedAndNothingIsThrown() {
        val launcher = FakeLauncher { FakeProcess() }
        launcher.failWith = IOException("Cannot run program: error=13, Permission denied")
        val runner = EngineRunner(FakeLocator(), launcher, work())
        val r = runner.run(EngineCommands.staticProbe()) as EngineResult.ExecBlocked
        assertTrue(r.message.startsWith("IOException: Cannot run program"))
        launcher.failWith = IllegalStateException("denied")
        assertTrue(runner.run(EngineCommands.staticProbe()) is EngineResult.ExecBlocked)
        launcher.failWith = SecurityException("no")
        assertTrue(runner.run(EngineCommands.staticProbe()) is EngineResult.ExecBlocked)
    }

    @Test
    fun givenAMissingProgramOrWorkDir_whenRun_thenItIsNotExecBlocked() {
        val launcher = FakeLauncher { FakeProcess(stdout = Recorded.staticProbe024()) }
        val none = EngineRunner(FakeLocator(missing = setOf(EngineProgram.MEMBW)), launcher, work())
        val r = none.run(EngineCommands.staticProbe())
        // F14: a missing file is ProgramMissing, never ExecBlocked ("the phone forbids starting a child")
        assertEquals("libpart1_membw.so was not found", (r as EngineResult.ProgramMissing).message)
        assertEquals(0, launcher.starts.size)
        val blocker = tmp.newFile("file-not-dir")
        val bad = EngineRunner(FakeLocator(), launcher, File(blocker, "sub"))
        val b = bad.run(EngineCommands.staticProbe())
        assertEquals("the work directory cannot be created", (b as EngineResult.NotStarted).message)
        assertEquals(0, launcher.starts.size)
    }

    @Test
    fun givenAProgramThatExistsAndWhoseStartThrows_whenRun_thenOnlyThatIsExecBlocked() {
        val launcher = FakeLauncher { FakeProcess() }
        launcher.failWith = IOException("error=13")
        val r = EngineRunner(FakeLocator(), launcher, work()).run(EngineCommands.gpuProbe())
        assertTrue(r is EngineResult.ExecBlocked)
        assertTrue(r !is EngineResult.ProgramMissing)
    }

    @Test
    fun givenAnOkRecordWithANonZeroExit_whenRun_thenItIsNotOk() {
        // R06: Ok needs exit 0 as well as status ok
        val ok = """{"format":"part1-rows-v1","tag":"C2-read-k0","status":"ok"}"""
        for (exit in listOf(1, 2, 124)) {
            val (runner, _) = runnerFor(work()) { FakeProcess(stdout = ok.toByteArray(), exit = exit) }
            val r = runner.run(EngineCommands.c2Read(0, 0, 100)) as EngineResult.Failed
            assertEquals(exit, r.exit)
        }
        for (exit in listOf(88, 97, 3)) {
            val (runner, _) = runnerFor(work()) { FakeProcess(stdout = ok.toByteArray(), exit = exit) }
            assertEquals(exit, (runner.run(EngineCommands.c2Read(0, 0, 100)) as EngineResult.Refused).exit)
        }
    }

    @Test
    fun givenARecordWithAnotherTag_whenRun_thenItKeepsItsOwnTag() {
        // R07: only a record without a tag gets the started row's tag
        val other = """{"format":"part1-rows-v1","tag":"C9-engine-own","status":"failed","exit_code":2}"""
        val (runner, _) = runnerFor(work()) { FakeProcess(stdout = other.toByteArray(), exit = 2) }
        val r = runner.run(EngineCommands.c2Read(1, 4, 100)) as EngineResult.Failed
        assertEquals("C9-engine-own", r.json!!["tag"])
        // a record that is not a row is never tagged
        val probe = """{"format":"part1-staticprobe-v1","status":"failed"}"""
        val (runner2, _) = runnerFor(work()) { FakeProcess(stdout = probe.toByteArray(), exit = 1) }
        assertNull((runner2.run(EngineCommands.c2Read(1, 4, 100)) as EngineResult.Failed).json!!["tag"])
    }

    @Test
    fun givenAThreadInterruptedWhileWaiting_whenRun_thenTheFlagIsRestoredTheChildIsKilledAndAResultIsReturned() {
        // R20, R21
        var destroyed = false
        var forced = false
        val proc = object : RunningProcess {
            override val stdout = ByteArrayInputStream(ByteArray(0))
            override val stderr = ByteArrayInputStream(ByteArray(0))
            override fun closeStdin() {}
            private var calls = 0
            override fun waitFor(timeoutMs: Long): Boolean {
                calls++
                if (calls == 1) throw InterruptedException("cancelled")
                return destroyed // later waits (inside the kill) see the process die
            }
            override fun exitValue() = 143
            override fun destroy() { destroyed = true }
            override fun destroyForcibly() { forced = true }
            override fun isAlive() = !destroyed
        }
        val launcher = object : ProcessLauncher {
            override fun start(argv: List<String>, workDir: File, env: Map<String, String>) = proc
        }
        val runner = EngineRunner(FakeLocator(), launcher, work())
        try {
            val r = runner.run(EngineCommands.staticProbe())
            assertTrue(r is EngineResult.Failed && r.json == null && r.exit == -1)
            assertTrue("the interrupt flag must be restored", Thread.currentThread().isInterrupted)
            assertTrue("the child must be killed", destroyed)
            assertTrue(!forced) // it died on destroy
        } finally {
            Thread.interrupted() // clear the flag for the next test on this thread
        }
    }

    @Test
    fun givenALastLineNestedTooDeeply_whenRun_thenItIsNoJsonAndOtherErrorsAreNotCaught() {
        // the JVM's org.json limits nesting; Android's tokener recurses, so the overflow is injected here
        val line = Recorded.lastLine("d5-row-c2-ok-v0.2.1.out").toByteArray()
        val overflow = EngineRunner(FakeLocator(), FakeLauncher { FakeProcess(stdout = line) }, work(), parseObject = { _, _ -> throw StackOverflowError() })
        val r1 = overflow.run(EngineCommands.staticProbe())
        assertTrue("result: $r1", r1 is EngineResult.NoJson)
        val deep = "{\"a\":" + "[".repeat(200_000) + "]".repeat(200_000) + "}\n"
        val (runner, _) = runnerFor(work()) { FakeProcess(stdout = deep.toByteArray(), exit = 0) }
        assertTrue(runner.run(EngineCommands.c2Read(0, 0, 100)) is EngineResult.NoJson) // whatever the parser does, never an exception
        val oom = EngineRunner(FakeLocator(), FakeLauncher { FakeProcess(stdout = line) }, work(), parseObject = { _, _ -> throw OutOfMemoryError("test") })
        try {
            oom.run(EngineCommands.staticProbe())
            org.junit.Assert.fail("an OutOfMemoryError from the parser must not be swallowed")
        } catch (e: OutOfMemoryError) {
            // expected
        }
        // an Error that is not a StackOverflowError reaches the caller (documented in the class comment)
        val launcher = object : ProcessLauncher {
            override fun start(argv: List<String>, workDir: File, env: Map<String, String>): RunningProcess = throw OutOfMemoryError("test")
        }
        try {
            EngineRunner(FakeLocator(), launcher, work()).run(EngineCommands.staticProbe())
            org.junit.Assert.fail("an OutOfMemoryError must not be swallowed")
        } catch (e: OutOfMemoryError) {
            // expected
        }
    }

    @Test
    fun givenAProcessThatNeverEnds_whenRun_thenItIsDestroyedAndReportedAsTimeoutKilled() {
        var proc: FakeProcess? = null
        val (runner, _) = runnerFor(work()) { FakeProcess(hangs = true).also { proc = it } }
        val r = runner.run(EngineCommands.c2Read(0, 0, 100))
        assertEquals(EngineResult.TimeoutKilled, r)
        assertTrue(proc!!.destroyed)
        assertTrue(!proc!!.forced) // it died on destroy
        assertEquals((120 + 5) * 1000L, proc!!.waits[0])
    }

    @Test
    fun givenTheGpuProbe_whenItHangs_thenTheHardKillIsItsMaxSecondsPlusFive() {
        var proc: FakeProcess? = null
        val (runner, _) = runnerFor(work()) { FakeProcess(hangs = true).also { proc = it } }
        assertEquals(EngineResult.TimeoutKilled, runner.run(EngineCommands.gpuProbe()))
        assertEquals((5 + 5) * 1000L, proc!!.waits[0])
    }

    @Test
    fun givenAProcessThatIgnoresDestroy_whenRun_thenItIsKilledForcibly() {
        var proc: FakeProcess? = null
        val (runner, _) = runnerFor(work()) { FakeProcess(hangs = true, ignoresDestroy = true).also { proc = it } }
        assertEquals(EngineResult.TimeoutKilled, runner.run(EngineCommands.c7GpuAlone(100)))
        assertTrue(proc!!.destroyed)
        assertTrue(proc!!.forced)
        assertEquals((180 + 5) * 1000L, proc!!.waits[0])
    }

    @Test
    fun givenMoreOutputThanTheCap_whenRun_thenOnlyTheEndIsKeptAndTheLastLineStillCounts() {
        val line = Recorded.lastLine("d5-row-c2-ok-v0.2.1.out")
        val noise = "# a line of noise that fills the pipe\n".repeat(100_000) // 3.8 MB
        val (runner, _) = runnerFor(work()) { FakeProcess(stdout = (noise + line + "\n").toByteArray(), stderr = noise.toByteArray(), exit = 0) }
        assertTrue(runner.run(EngineCommands.c2Read(0, 0, 100)) is EngineResult.Ok)
        // a last line that is itself larger than the cap is cut and therefore not JSON
        // the cap is pinned as a literal number: 1 MiB (R17)
        assertEquals(1_048_576, EngineRunner.OUTPUT_CAP_BYTES)
        val huge = "{\"x\":\"" + "y".repeat(1_048_576 + 100) + "\"}\n"
        val (runner2, _) = runnerFor(work()) { FakeProcess(stdout = huge.toByteArray(), exit = 0) }
        assertTrue(runner2.run(EngineCommands.c2Read(0, 0, 100)) is EngineResult.NoJson)
        // a last line just under the cap is kept whole (it is an object, so not NoJson)
        val big = "{\"x\":\"" + "y".repeat(1_048_576 - 100) + "\"}\n"
        val (runner3, _) = runnerFor(work()) { FakeProcess(stdout = big.toByteArray(), exit = 0) }
        assertTrue(runner3.run(EngineCommands.c2Read(0, 0, 100)) is EngineResult.Failed)
    }

    @Test
    fun givenStdoutThatOnlyEndsAfterStderrWasRead_whenRun_thenBothAreReadAtTheSameTime() {
        val line = Recorded.lastLine("d5-row-c2-ok-v0.2.1.out") + "\n"
        val errRead = CountDownLatch(1)
        val outRead = CountDownLatch(1)
        val (runner, _) = runnerFor(work()) {
            FakeProcess(
                exit = 0,
                stdoutStream = GatedStream(line.toByteArray(), waitFor = errRead, release = outRead), // stdout waits for stderr's first read
                stderrStream = GatedStream("warn\n".toByteArray(), waitFor = null, release = errRead),
            )
        }
        assertTrue(runner.run(EngineCommands.c2Read(0, 0, 100)) is EngineResult.Ok)
    }

    @Test
    fun givenARecordWithoutTag_whenRun_thenItGetsTheTagOfTheRowThatWasStarted() {
        val noTag = """{"format":"part1-rows-v1","kind":"read","status":"failed","exit_code":2,"reason":"bad option"}"""
        val (runner, _) = runnerFor(work()) { FakeProcess(stdout = noTag.toByteArray(), exit = 2) }
        val r = runner.run(EngineCommands.c2Read(1, 4, 100)) as EngineResult.Failed
        assertEquals("C2-read-k1", r.json!!["tag"])
        // a record that has its own tag keeps it; a probe object is not given one
        val (runner2, _) = runnerFor(work()) { FakeProcess(stdout = Recorded.bytes("d5-pinprobe-v0.2.3.out"), exit = 0) }
        val p = runner2.run(EngineCommands.pinProbe(listOf(0, 1, 2, 3, 4, 5, 6, 7))) as EngineResult.Ok
        assertNull(p.json["tag"])
    }

    @Test
    fun givenTheFirstCall_whenTheStaticProbeIsOfAnotherVersion_thenItIsAMismatchAndNothingElseRuns() {
        val launcher = FakeLauncher { FakeProcess(stdout = Recorded.bytes("d5-staticprobe-v0.2.3.out")) } // 0.2.3 is not 0.2.4
        val runner = EngineRunner(FakeLocator(), launcher, work())
        val r = runner.run(EngineCommands.c2Read(0, 0, 100)) as EngineResult.EngineMismatch
        assertEquals("part1-engine/0.2.3", r.found)
        assertEquals(1, launcher.starts.size)
        assertTrue("--static-probe" in launcher.starts[0].argv)
        // every later call reports it without starting anything
        assertEquals("part1-engine/0.2.3", (runner.run(EngineCommands.gpuProbe()) as EngineResult.EngineMismatch).found)
        assertEquals("part1-engine/0.2.3", (runner.run(EngineCommands.staticProbe()) as EngineResult.EngineMismatch).found)
        assertEquals(1, launcher.starts.size)
    }

    @Test
    fun givenAStaticProbeWithoutEngineVersion_whenTheFirstCallRuns_thenFoundIsNull() {
        val launcher = FakeLauncher { FakeProcess(stdout = """{"format":"part1-staticprobe-v1","status":"ok","exit_code":0}""".toByteArray()) }
        val r = EngineRunner(FakeLocator(), launcher, work()).run(EngineCommands.staticProbe()) as EngineResult.EngineMismatch
        assertNull(r.found)
    }

    @Test
    fun givenTheMatchingEngine_whenTheFirstCallIsTheStaticProbe_thenItRunsOnceAndTheVersionIsVerifiedForTheSession() {
        val (runner, launcher) = runnerFor(work()) { recorded("d5-gpu-row-ok.out") }
        val r = runner.run(EngineCommands.staticProbe()) as EngineResult.Ok
        assertEquals(EngineRunner.EXPECTED_ENGINE_VERSION, r.json["engine_version"])
        assertEquals(1, launcher.starts.size)
        runner.run(EngineCommands.c7GpuAlone(100))
        runner.run(EngineCommands.c7GpuAlone(100))
        assertEquals(3, launcher.starts.size) // one probe, two rows: no second check
    }

    @Test
    fun givenAProbeThatGivesNoAnswer_whenTheFirstCallRuns_thenItsResultIsReturnedAndTheCheckIsRepeatedNextTime() {
        var n = 0
        val launcher = FakeLauncher { argv ->
            if ("--static-probe" in argv) { n++; if (n == 1) FakeProcess(exit = 139) else FakeProcess(stdout = Recorded.staticProbe024()) } else FakeProcess(stdout = Recorded.bytes("d5-row-c2-ok-v0.2.1.out"))
        }
        val runner = EngineRunner(FakeLocator(), launcher, work())
        assertTrue(runner.run(EngineCommands.c2Read(0, 0, 100)) is EngineResult.NoJson)
        assertEquals(1, launcher.starts.size) // the row was not run
        assertTrue(runner.run(EngineCommands.c2Read(0, 0, 100)) is EngineResult.Ok)
        assertEquals(3, launcher.starts.size)
    }

    @Test
    fun givenACallOnTheMainThread_whenRun_thenNothingIsStarted() {
        val (runner, launcher) = runnerFor(work(), onMain = true) { FakeProcess() }
        assertTrue(runner.run(EngineCommands.staticProbe()) is EngineResult.NotStarted)
        assertEquals(0, launcher.starts.size)
    }

    @Test
    fun givenAProcessWhoseWaitThrows_whenRun_thenTheCallerGetsAResultNotAnException() {
        val boom = object : RunningProcess {
            override val stdout = ByteArrayInputStream(ByteArray(0))
            override val stderr = ByteArrayInputStream(ByteArray(0))
            override fun closeStdin() {}
            override fun waitFor(timeoutMs: Long): Boolean = throw IllegalStateException("broken")
            override fun exitValue() = 0
            override fun destroy() {}
            override fun destroyForcibly() {}
            override fun isAlive() = true
        }
        val launcher = object : ProcessLauncher {
            override fun start(argv: List<String>, workDir: File, env: Map<String, String>) = boom
        }
        val r = EngineRunner(FakeLocator(), launcher, work()).run(EngineCommands.staticProbe())
        assertTrue(r is EngineResult.Failed && r.json == null && r.exit == -1)
    }

    @Test
    fun givenEveryRecordedFile_whenReplayedAsItsCommand_thenTheClassMatchesTheRecordedExit() {
        val rows = mapOf(
            "d5-row-c3-ok.out" to EngineCommands.c3Read2Fast(0, 1, 100), "d5-row-c4-ok.out" to EngineCommands.c4ReadAll(listOf(0, 1), 100),
        )
        for ((name, cmd) in rows) {
            val (runner, _) = runnerFor(work()) { recorded(name) }
            assertTrue(name, runner.run(cmd) is EngineResult.Ok)
        }
        assertTrue(Recorded.names().size >= 11)
        for (n in Recorded.names()) assertTrue(n, Recorded.bytes(n).isNotEmpty())
    }
}
