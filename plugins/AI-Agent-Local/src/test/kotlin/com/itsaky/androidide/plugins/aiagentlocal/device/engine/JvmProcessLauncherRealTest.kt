package com.itsaky.androidide.plugins.aiagentlocal.device.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The real [JvmProcessLauncher] and the runner over it, on the Linux JVM, with small shell scripts as stand-ins for the engine
 * programs: environment cleared, working directory, stdin at end of file, a hung child killed, a non-executable file refused.
 * (Kills the mutants that drop `environment().clear()` or `directory(workDir)`.)
 */
class JvmProcessLauncherRealTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val engine = EngineRunner.EXPECTED_ENGINE_VERSION

    private fun script(name: String, body: String, executable: Boolean = true): File {
        val f = File(tmp.root, name)
        f.writeText("#!/bin/sh\n$body\n")
        f.setExecutable(executable)
        assumeTrue("needs a POSIX /bin/sh", File("/bin/sh").canExecute())
        assumeTrue("the file system must honour the execute bit", f.canExecute() == executable)
        return f
    }

    private fun locatorFor(f: File) = object : EngineLocator {
        override fun locate(program: EngineProgram): File = f
    }

    private fun work() = File(tmp.root, "part1/work")

    /** Prints one JSON line: the number of environment variables the shell did not set itself, the working directory, and the result of reading stdin. */
    private val probeBody = """
        n=${'$'}(env | grep -v '^PWD=' | grep -v '^OLDPWD=' | grep -v '^_=' | grep -v '^SHLVL=' | wc -l)
        read -r line
        rc=${'$'}?
        printf '{"format":"part1-staticprobe-v1","status":"ok","engine_version":"$engine","env":%s,"cwd":"%s","stdin_rc":%s}\n' "${'$'}n" "${'$'}(pwd -P)" "${'$'}rc"
    """.trimIndent()

    @Test
    fun givenTheRealLauncher_whenAChildRuns_thenItsEnvironmentIsEmptyItsWorkingDirectoryIsTheWorkDirAndStdinIsAtEndOfFile() {
        val exe = script("libpart1_membw.so", probeBody)
        val r = EngineRunner(locatorFor(exe), JvmProcessLauncher(), work()).run(EngineCommands.staticProbe())
        assertTrue("result: $r", r is EngineResult.Ok)
        val json = (r as EngineResult.Ok).json
        assertEquals("an inherited environment variable reached the child", 0L, json["env"])
        assertEquals(work().canonicalPath, json["cwd"])
        assertEquals("stdin must be at end of file (read returns 1)", 1L, json["stdin_rc"])
    }

    @Test
    fun givenTheRealLauncher_whenAnEnvironmentIsGiven_thenOnlyThatEnvironmentIsSet() {
        val exe = script("env.sh", "echo \"X=\$PART1_TEST_ONLY\"; env | grep -c '^HOME=' ")
        work().mkdirs()
        val p = JvmProcessLauncher().start(listOf(exe.path), work(), mapOf("PART1_TEST_ONLY" to "v"))
        p.closeStdin()
        assertTrue(p.waitFor(10_000))
        val out = p.stdout.readBytes().toString(Charsets.UTF_8).lines()
        assertEquals("X=v", out[0])
        assertEquals("0", out[1]) // HOME of the parent is not passed on
    }

    @Test
    fun givenAHungChild_whenTheHardLimitPasses_thenItIsKilledAndReportedAsTimeoutKilled() {
        val marker = File(tmp.root, "pid.txt")
        // the engine's own limit is 1 s, so the hard limit is 1 + 5 s; the first call is the version probe, so the script answers it first
        val gate = script("gate.sh", "case \"\$*\" in *--static-probe*) printf '{\"format\":\"part1-staticprobe-v1\",\"status\":\"ok\",\"engine_version\":\"$engine\"}\\n';; *) echo \$\$ > '${marker.path}'; exec sleep 60;; esac")
        val runner = EngineRunner(locatorFor(gate), JvmProcessLauncher(), work())
        val row = EngineCommands.c2Read(0, 0, 100).let { EngineCommand(it.kind, it.program, it.args, it.tag, 1) }
        val t0 = System.nanoTime()
        val r = runner.run(row)
        val seconds = (System.nanoTime() - t0) / 1e9
        assertEquals(EngineResult.TimeoutKilled, r)
        assertTrue("killed after $seconds s", seconds in 5.5..12.0)
        val pid = marker.readText().trim()
        Thread.sleep(200)
        assertFalse("the child (pid recorded) must be gone", File("/proc/$pid").exists() && File("/proc/$pid/stat").readText().substringAfter(") ").first() != 'Z')
    }

    @Test
    fun givenAFileThatIsNotExecutable_whenRun_thenTheStartThrowsAndTheResultIsExecBlocked() {
        val exe = script("libpart1_membw.so", probeBody, executable = false)
        val r = EngineRunner(locatorFor(exe), JvmProcessLauncher(), work()).run(EngineCommands.staticProbe())
        assertTrue("result: $r", r is EngineResult.ExecBlocked)
        assertTrue((r as EngineResult.ExecBlocked).message.startsWith("IOException"))
    }

    @Test
    fun givenAChildThatExitsWithAnErrorAndNoJson_whenRun_thenItIsNoJsonWithTheExitAndStderr() {
        val gate = script("fail.sh", "case \"\$*\" in *--static-probe*) printf '{\"format\":\"part1-staticprobe-v1\",\"status\":\"ok\",\"engine_version\":\"$engine\"}\\n';; *) echo 'boom' >&2; exit 7;; esac")
        val r = EngineRunner(locatorFor(gate), JvmProcessLauncher(), work()).run(EngineCommands.c2Read(0, 0, 100))
        r as EngineResult.NoJson
        assertEquals(7, r.exit)
        assertEquals("boom\n", r.stderrTail)
    }
}
