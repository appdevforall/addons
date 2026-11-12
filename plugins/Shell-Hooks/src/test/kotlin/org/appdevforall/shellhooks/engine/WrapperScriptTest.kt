package org.appdevforall.shellhooks.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Runs the bundled wrapper under each POSIX shell this machine has. Android runs it with mksh
 * (/system/bin/sh), which is not here; dash and busybox ash are the strictest stand-ins.
 */
@RunWith(Parameterized::class)
class WrapperScriptTest(
    private val shell: String,
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun shells(): List<Array<String>> =
            listOf("/bin/sh", "/usr/bin/dash", "/usr/bin/bash", "/usr/bin/busybox")
                .filter { File(it).canExecute() }
                .map { arrayOf(it) }
                .ifEmpty { listOf(arrayOf("/bin/sh")) }
    }

    private val wrapper = File("src/main/assets/cogo-hook-run.sh")
    private val work = tempDir("wrapper")
    private val prefix = File(work, "usr")
    private val project = File(work, "my project").apply { mkdirs() }

    @After
    fun tearDown() {
        work.deleteRecursively()
    }

    /** A stand-in interpreter in $PREFIX/bin that reports how it was called. */
    private fun interpreter(name: String) =
        prefix.script("bin/$name", "#!/bin/sh\nprintf '%s\\n' \"interp=$name\"; for a in \"\$@\"; do printf '[%s]\\n' \"\$a\"; done\n")

    private fun start(
        script: File,
        args: List<String>,
        env: Map<String, String>,
    ): Process {
        assumeTrue("needs $shell", File(shell).canExecute())
        val command = if (shell.endsWith("busybox")) listOf(shell, "sh") else listOf(shell)
        return ProcessBuilder(command + listOf(wrapper.absolutePath, script.path) + args)
            .directory(work)
            .redirectErrorStream(true)
            .apply {
                environment()["PREFIX"] = prefix.path
                environment().putAll(env)
            }.start()
    }

    private fun run(
        script: File,
        vararg args: String,
        env: Map<String, String> = emptyMap(),
    ): String {
        val process = start(script, args.toList(), env)
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor(10, TimeUnit.SECONDS)
        return out
    }

    /** Alive and not a zombie, read from /proc. */
    private fun isRunning(pid: Long): Boolean {
        val stat = File("/proc/$pid/stat").takeIf { it.exists() }?.readText() ?: return false
        return stat.substringAfterLast(')').trim().firstOrNull() != 'Z'
    }

    @Test
    fun anEnvShebangRunsTheNamedProgramFromPrefix() {
        interpreter("python3")
        val script = work.script("hook", "#!/usr/bin/env python3\nprint(1)\n")

        assertEquals("interp=python3\n[${script.path}]\n[a b]\n", run(script, "a b"))
    }

    @Test
    fun anAbsoluteShebangWithAnArgumentIsMappedOntoPrefix() {
        interpreter("bash")
        val script = work.script("hook", "#!/bin/bash -e\necho\n")

        assertEquals("interp=bash\n[-e]\n[${script.path}]\n[x]\n", run(script, "x"))
    }

    @Test
    fun aWindowsLineEndingOnTheShebangIsIgnored() {
        interpreter("bash")
        val script = work.script("hook", "#!/usr/bin/env bash\r\necho\r\n")

        assertEquals("interp=bash\n[${script.path}]\n", run(script))
    }

    @Test
    fun noShebangRunsWithPrefixBash() {
        interpreter("bash")
        val script = work.script("hook", "echo plain\n")

        assertEquals("interp=bash\n[${script.path}]\n", run(script))
    }

    @Test
    fun noShebangAndNoBashFallsBackToSh() {
        val script = work.script("hook", "printf 'sh:%s\\n' \"\$1\"\n")

        assertEquals("sh:v\n", run(script, "v"))
    }

    @Test
    fun aRemappablePathMissingFromPrefixIsLeftToTheKernel() {
        val script = work.script("hook", "#!/bin/sh\nprintf 'direct:%s\\n' \"\$1\"\n")

        assertEquals("direct:v\n", run(script, "v"))
    }

    @Test
    fun aBinShebangUsesPrefixWhenItHasTheProgram() {
        interpreter("sh")
        val script = work.script("hook", "#!/bin/sh\necho\n")

        assertEquals("interp=sh\n[${script.path}]\n[v]\n", run(script, "v"))
    }

    @Test
    fun anInterpreterOutsideTheTermuxExecPathsRunsAsWritten() {
        // termux-exec remaps only /bin, /usr/bin and /usr/local/bin. A virtualenv's python or
        // /system/bin/sh must not be swapped for the Terminal's program of the same name.
        interpreter("sh")
        val foreign = work.script("venv/bin/sh", "#!/bin/sh\nprintf 'foreign\\n'; for a in \"\$@\"; do printf '[%s]\\n' \"\$a\"; done\n")
        val script = work.script("hook", "#!${foreign.path}\necho\n")

        assertEquals("foreign\n[${script.path}]\n[v]\n", run(script, "v"))
    }

    /** Links a coreutils `timeout` into $PREFIX/bin, as the Terminal's bootstrap ships it. */
    private fun linkTimeout() {
        val timeout = listOf("/usr/bin/gnutimeout", "/usr/bin/timeout", "/bin/timeout").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("needs a coreutils timeout", timeout != null)
        Files.createDirectories(File(prefix, "bin").toPath())
        Files.createSymbolicLink(File(prefix, "bin/timeout").toPath(), timeout!!.toPath())
    }

    /** Processes still running with [mark] in their environment: what a run left behind. */
    private fun leftBehind(mark: String): List<Long> =
        File("/proc").listFiles().orEmpty().mapNotNull { it.name.toLongOrNull() }.filter { pid ->
            runCatching { File("/proc/$pid/environ").readBytes().toString(Charsets.ISO_8859_1) }
                .getOrDefault("")
                .split('\u0000')
                .contains("COGO_TEST_MARK=$mark") && isRunning(pid)
        }

    private fun waitUntil(
        seconds: Long,
        done: () -> Boolean,
    ): Boolean = waitUntilMillis(TimeUnit.SECONDS.toMillis(seconds), done)

    private fun waitUntilMillis(
        millis: Long,
        done: () -> Boolean,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        while (!done() && System.nanoTime() < deadline) Thread.sleep(20)
        return done()
    }

    @Test
    fun killingTheWrapperStopsTheScriptAndWhatItStarted() {
        // The IDE stops a hook on disable, unload or update by SIGKILLing the process it started.
        assumeTrue("needs /proc", File("/proc/self/stat").exists())
        linkTimeout()
        val mark = java.util.UUID.randomUUID().toString()
        val scriptPid = File(work, "script.pid")
        val childPid = File(work, "child.pid")
        val script = work.script("hook", "#!/bin/sh\necho \$\$ > \"\$1\"\nsleep 30 &\necho \$! > \"\$2\"\nwait\n")

        val process = start(script, listOf(scriptPid.path, childPid.path), mapOf("COGO_TIMEOUT_SECONDS" to "60", "COGO_TEST_MARK" to mark))
        try {
            assertTrue("the script started", waitUntil(10) { childPid.length() > 0 && scriptPid.length() > 0 })
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            val pids = listOf(scriptPid, childPid).map { it.readText().trim().toLong() }

            assertTrue("the script and its child outlived the wrapper: ${leftBehind(mark)}", waitUntil(4) { pids.none(::isRunning) })
            assertTrue("left behind: ${leftBehind(mark)}", waitUntil(2) { leftBehind(mark).isEmpty() })
        } finally {
            leftBehind(mark).forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    @Test
    fun aRunUnderTimeoutReturnsTheScriptsStatusAndLeavesNothingRunning() {
        assumeTrue("needs /proc", File("/proc/self/stat").exists())
        linkTimeout()
        val mark = java.util.UUID.randomUUID().toString()
        // Ends 0.2 s in. The watchdog checks on the wrapper once a second, so one the wrapper did
        // not kill would live about 0.8 s longer; the wrapper's own kill ends it at once.
        val script = work.script("hook", "#!/bin/sh\necho done\nsleep 0.2\nexit 3\n")

        val process = start(script, emptyList(), mapOf("COGO_TIMEOUT_SECONDS" to "60", "COGO_TEST_MARK" to mark))
        try {
            // Read on the side: waiting for end of output first would also wait for anything that
            // holds the pipe, and hide a watchdog that outlives the wrapper.
            val out = CompletableFuture.supplyAsync { process.inputStream.readBytes().toString(Charsets.UTF_8) }
            val exited = process.waitFor(10, TimeUnit.SECONDS)

            assertTrue(exited)
            assertTrue("left behind: ${leftBehind(mark)}", waitUntilMillis(300) { leftBehind(mark).isEmpty() })
            assertEquals("done\n", out.get(5, TimeUnit.SECONDS))
            assertEquals(3, process.exitValue())
        } finally {
            leftBehind(mark).forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    @Test
    fun killingTheWrapperStopsAScriptThatIgnoresTerm() {
        // TERM is ignored by the script and, inherited, by its child: only timeout's KILL, 5 s
        // after the watchdog's TERM (`-k 5`), stops them.
        assumeTrue("needs /proc", File("/proc/self/stat").exists())
        linkTimeout()
        val mark = java.util.UUID.randomUUID().toString()
        val scriptPid = File(work, "script.pid")
        val childPid = File(work, "child.pid")
        val script = work.script("hook", "#!/bin/sh\ntrap '' TERM\necho \$\$ > \"\$1\"\nsleep 30 &\necho \$! > \"\$2\"\nwait\n")

        val process = start(script, listOf(scriptPid.path, childPid.path), mapOf("COGO_TIMEOUT_SECONDS" to "60", "COGO_TEST_MARK" to mark))
        try {
            assertTrue("the script started", waitUntil(10) { childPid.length() > 0 && scriptPid.length() > 0 })
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            val pids = listOf(scriptPid, childPid).map { it.readText().trim().toLong() }

            assertTrue("a TERM-ignoring script outlived the wrapper: ${leftBehind(mark)}", waitUntil(9) { pids.none(::isRunning) })
            assertTrue("left behind: ${leftBehind(mark)}", waitUntil(2) { leftBehind(mark).isEmpty() })
        } finally {
            leftBehind(mark).forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    @Test
    fun aTimeoutStopsTheScriptAndEveryProcessItStarted() {
        linkTimeout()
        val pidFile = File(work, "child.pid")
        // The child runs in the foreground: a plain kill of the script's own process misses it.
        val script = work.script("hook", "#!/bin/sh\nsh -c 'echo \$\$ > \"\$1\"; exec sleep 60' child \"\$1\"\n")

        val process = start(script, listOf(pidFile.path), mapOf("COGO_TIMEOUT_SECONDS" to "1"))
        val exited = process.waitFor(15, TimeUnit.SECONDS)
        if (!exited) process.destroyForcibly() // what the IDE would do
        val child = pidFile.readText().trim().toLong()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (isRunning(child) && System.nanoTime() < deadline) Thread.sleep(50)

            assertTrue("the wrapper's timeout ended the run", exited)
            assertTrue("exit ${process.exitValue()}", process.exitValue() == 124 || process.exitValue() == 137)
            assertFalse("the script's child outlived the timeout", isRunning(child))
        } finally {
            ProcessHandle.of(child).ifPresent { it.destroyForcibly() }
        }
    }

    @Test
    fun withoutATimeoutVariableTheScriptIsNotWrapped() {
        interpreter("timeout")
        val script = work.script("hook", "#!/bin/sh\necho plain\n")

        assertEquals("plain\n", run(script))
    }

    @Test
    fun hostileArgumentsArriveUnchangedAndRunNothing() {
        val script = work.script("hook", "#!/bin/sh\nfor a in \"\$@\"; do printf '[%s]\\n' \"\$a\"; done\n")

        val out = run(script, *HOSTILE_NAMES.toTypedArray())

        assertEquals(HOSTILE_NAMES.joinToString("") { "[$it]\n" }, out)
        assertFalse(File(work, "pwned").exists())
        assertFalse(File(project, "pwned").exists())
    }

    @Test
    fun theScriptRunsInTheEventsProjectFolder() {
        val script = work.script("hook", "#!/bin/sh\npwd\n")

        val pwd = run(script, env = mapOf("COGO_PROJECT_DIR" to project.path)).trim()

        assertEquals(project.canonicalPath, File(pwd).canonicalPath)
    }

    @Test
    fun aMissingProjectFolderLeavesTheDirectoryAlone() {
        val script = work.script("hook", "#!/bin/sh\npwd\n")

        val pwd = run(script, env = mapOf("COGO_PROJECT_DIR" to File(work, "gone").path)).trim()

        assertEquals(work.canonicalPath, File(pwd).canonicalPath)
    }

    @Test
    fun theExitCodeIsTheScripts() {
        assumeTrue(File(shell).canExecute())
        val script = work.script("hook", "#!/bin/sh\nexit 7\n")
        val command = if (shell.endsWith("busybox")) listOf(shell, "sh") else listOf(shell)
        val process = ProcessBuilder(command + listOf(wrapper.absolutePath, script.path)).start()
        process.waitFor(10, TimeUnit.SECONDS)

        assertEquals(7, process.exitValue())
    }
}
