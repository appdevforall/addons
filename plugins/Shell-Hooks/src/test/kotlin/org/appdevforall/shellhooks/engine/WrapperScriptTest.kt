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
 * (/system/bin/sh), which is not tested; dash and busybox ash are the strictest stand-ins.
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

    /** The fields of /proc/<pid>/stat after the command name: state, ppid, pgrp, session, ... */
    private fun stat(pid: Long): List<String>? =
        runCatching { File("/proc/$pid/stat").readText() }.getOrNull()?.substringAfterLast(") ")?.split(' ')

    private fun childrenOf(pid: Long): List<Long> =
        File("/proc").listFiles().orEmpty().mapNotNull { it.name.toLongOrNull() }.filter { stat(it)?.getOrNull(1)?.toLongOrNull() == pid }

    private fun comm(pid: Long): String = runCatching { File("/proc/$pid/comm").readText().trim() }.getOrDefault("")

    /** This test's own process: the JVM that started the wrapper, as the IDE does. */
    private val jvmPid: Long = File("/proc/self").canonicalFile.name.toLong()

    /** The wrapper this test started with [mark]: the JVM's child carrying it. */
    private fun wrapperPid(mark: String): Long? = leftBehind(mark).firstOrNull { stat(it)?.getOrNull(1)?.toLongOrNull() == jvmPid }

    /** Where each of [pid]'s file descriptors points, e.g. `pipe:[123]`. */
    private fun fdTargets(pid: Long): List<String> =
        File("/proc/$pid/fd").listFiles().orEmpty().mapNotNull { runCatching { Files.readSymbolicLink(it.toPath()).toString() }.getOrNull() }

    @Test
    fun theWatchdogKeepsNoCopyOfTheIdesOutput() {
        // A watchdog holding the IDE's output open would make the host wait for it after the script
        // ends. dash and busybox keep a saved copy of stdout for `watch ... >/dev/null &`.
        assumeTrue("needs /proc", File("/proc/self/fd").isDirectory)
        linkTimeout()
        val mark = java.util.UUID.randomUUID().toString()
        val script = work.script("hook", "#!/bin/sh\nsleep 5\n")

        val process = start(script, emptyList(), mapOf("COGO_TIMEOUT_SECONDS" to "60", "COGO_TEST_MARK" to mark))
        try {
            var wrapper = -1L
            assertTrue("the wrapper started", waitUntil(10) { wrapperPid(mark)?.also { wrapper = it } != null })
            val output = Files.readSymbolicLink(File("/proc/$wrapper/fd/1").toPath()).toString()
            var watchdog = -1L
            // Its sleep running means it is past its redirections and in its loop.
            val started =
                waitUntil(10) {
                    childrenOf(wrapper).firstOrNull { comm(it) != "timeout" }?.also { watchdog = it } != null &&
                        childrenOf(watchdog).isNotEmpty()
                }
            assertTrue("the watchdog started", started)

            val holders = (listOf(watchdog) + childrenOf(watchdog)).filter { output in fdTargets(it) }
            assertTrue("the watchdog holds the IDE's output $output: $holders", holders.isEmpty())
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            waitUntil(10) { leftBehind(mark).isEmpty() }
            leftBehind(mark).forEach { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
        }
    }

    /** Links util-linux `setsid` into $PREFIX/bin, as the Terminal's bootstrap ships it. */
    private fun linkSetsid(): File {
        val setsid = File("/usr/bin/setsid")
        assumeTrue("needs util-linux setsid", setsid.canExecute())
        Files.createDirectories(File(prefix, "bin").toPath())
        Files.createSymbolicLink(File(prefix, "bin/setsid").toPath(), setsid.toPath())
        return setsid
    }

    @Test
    fun withoutTimeoutTheScriptKeepsTheWrappersProcessIdInASessionOfItsOwn() {
        // The IDE stops a hook by its process ID, so setsid must not fork; and the script must lead
        // a session and group of its own rather than share the IDE's.
        assumeTrue("needs /proc", File("/proc/self/stat").exists())
        linkSetsid()
        val script = work.script("hook", "#!/bin/sh\nset -- \$(sed 's/.*) //' /proc/\$\$/stat)\necho \"\$\$ \$2 \$3 \$4\"\n")

        val (pid, parent, group, session) = run(script).trim().split(' ')

        // Its parent is still this JVM: it is the very process started, not a fork of it.
        assertEquals("parent", jvmPid.toString(), parent)
        assertEquals("process group", pid, group)
        assertEquals("session", pid, session)
    }

    @Test
    fun withoutTimeoutAScriptThatSignalsItsGroupDoesNotSignalItsCaller() {
        // Without timeout the script would share its caller's group, which in the IDE is Code on the
        // Go's, and `trap 'kill 0' EXIT` would signal it. Here the caller is a stand-in shell in a
        // session of its own (never the test's), with a sentinel in its group.
        assumeTrue("needs /proc", File("/proc/self/stat").exists())
        val setsid = linkSetsid()
        val sentinel = File(work, "sentinel.pid")
        val status = File(work, "status")
        val script = work.script("hook", "#!/bin/sh\ntrap 'kill 0' EXIT\necho ran\n")
        val sh = if (shell.endsWith("busybox")) "'$shell' sh" else "'$shell'"
        val caller =
            "sleep 30 & echo \$! > '${sentinel.path}'; " +
                "$sh '${wrapper.absolutePath}' '${script.path}' > '${work.path}/out' 2>&1; echo \$? > '${status.path}'"

        val process =
            ProcessBuilder(setsid.path, "/bin/sh", "-c", caller)
                .directory(work)
                .redirectErrorStream(true)
                .apply { environment()["PREFIX"] = prefix.path }
                .start()
        val sentinelPid = { sentinel.readText().trim().toLongOrNull() }
        try {
            assertTrue("the caller finished", process.waitFor(15, TimeUnit.SECONDS))

            // The script's `kill 0` ends the script itself (143); its caller must live on to record that.
            assertTrue("the caller was signalled and never finished", status.exists())
            assertTrue("the sentinel in the caller's group was signalled", sentinelPid()?.let(::isRunning) == true)
            assertTrue("the script ran", File(work, "out").readText().startsWith("ran\n"))
        } finally {
            sentinelPid()?.let { pid -> ProcessHandle.of(pid).ifPresent { it.destroyForcibly() } }
            process.destroyForcibly()
        }
    }

    /**
     * Runs the wrapper's own `watch` function on its own, with `kill` replaced by a function that
     * logs each call to [log] before making it, so a test can see every signal the watchdog sends.
     */
    private fun startWatch(
        watched: Long,
        target: Long,
        log: File,
    ): Child {
        val text = wrapper.readText()
        val from = text.indexOf("\nwatch() {\n") + 1
        val to = text.indexOf("\n}\n", from) + 3
        assertTrue("watch() is in the wrapper", from > 0 && to > from)
        val harness =
            work.script(
                "watch-harness",
                "echo \$\$\n" + text.substring(from, to) +
                    "kill() { printf '%s\\n' \"\$*\" >> '${log.path}'; command kill \"\$@\"; }\n" +
                    "watch \"\$1\" \"\$2\"\n",
            )
        val command = if (shell.endsWith("busybox")) listOf(shell, "sh") else listOf(shell)
        return Child(
            ProcessBuilder(command + listOf(harness.path, watched.toString(), target.toString()))
                .directory(work)
                .redirectErrorStream(true)
                .start(),
        )
    }

    /** A process this test started, with the process ID it printed first (unit tests run on an API without Process.pid()). */
    private class Child(
        val process: Process,
    ) {
        val pid: Long = process.inputStream.bufferedReader().readLine().trim().toLong()
    }

    /** `[program] 30` in a process of its own, named after [program]. */
    private fun sleeper(program: String = "sleep"): Child =
        Child(ProcessBuilder("/bin/sh", "-c", "echo \$\$; exec \"\$0\" 30", program).start())

    @Test
    fun theWatchdogNeverSignalsASleepThatWasKilledFromOutside() {
        // Killed from outside, the watchdog's sleep is reaped by its `wait`, which returns over 128
        // just as when TERM interrupts it. Only the TERM case leaves a sleep to stop; signalling the
        // reaped one could hit another process that took its ID.
        assumeTrue("needs /proc", File("/proc/self/stat").exists())
        val log = File(work, "kills").apply { createNewFile() }
        val watched = sleeper()
        val target = sleeper()
        val watch = startWatch(watched.pid, target.pid, log)
        try {
            var nap = -1L
            assertTrue("the watchdog napped", waitUntil(10) { childrenOf(watch.pid).firstOrNull()?.also { nap = it } != null })
            ProcessHandle.of(nap).ifPresent { it.destroyForcibly() }
            // The next nap starting means the loop went round, past where it would signal the old one.
            assertTrue("the watchdog napped again", waitUntil(10) { childrenOf(watch.pid).any { it != nap } })

            assertFalse("signalled its reaped sleep $nap: ${log.readLines()}", log.readLines().any { it.trim() == nap.toString() })
        } finally {
            watched.process.destroyForcibly()
            watch.process.waitFor(5, TimeUnit.SECONDS)
            watch.process.destroyForcibly()
            target.process.destroyForcibly()
        }
    }

    @Test
    fun theWatchdogSignalsOnlyAProcessThatIsStillTimeout() {
        // Once the wrapper is gone the watchdog sends TERM to timeout's process ID. If timeout ended
        // first, that ID may be another process's: here, a sleep, which must be left alone.
        assumeTrue("needs /proc", File("/proc/self/comm").exists())
        val log = File(work, "kills").apply { createNewFile() }
        val stranger = sleeper()
        val watched = sleeper()
        val watch = startWatch(watched.pid, stranger.pid, log)
        try {
            assertTrue("the watchdog napped", waitUntil(10) { childrenOf(watch.pid).isNotEmpty() })
            watched.process.destroyForcibly()

            assertTrue("the watchdog ended", watch.process.waitFor(10, TimeUnit.SECONDS))
            assertTrue("a process that is not timeout was stopped", stranger.process.isAlive)
            val signals = log.readLines().filter { it.endsWith(" ${stranger.pid}") && !it.startsWith("-0") }
            assertTrue("signalled ${stranger.pid}: $signals", signals.isEmpty())
        } finally {
            watch.process.destroyForcibly()
            stranger.process.destroyForcibly()
        }
    }

    @Test
    fun theWatchdogStopsAProcessThatIsStillTimeout() {
        // The same, with a stand-in whose name is timeout: it gets the TERM. A script, as a process
        // started from one is named after it (a copied sleep may be a multi-call binary that then
        // acts as timeout).
        assumeTrue("needs /proc", File("/proc/self/comm").exists())
        val timeout = work.script("bin/timeout", "#!/bin/sh\nwhile :; do sleep 1; done\n")
        val log = File(work, "kills").apply { createNewFile() }
        val standIn = sleeper(timeout.path)
        val watched = sleeper()
        val watch = startWatch(watched.pid, standIn.pid, log)
        try {
            assertTrue("the stand-in is named timeout", waitUntil(5) { comm(standIn.pid) == "timeout" })
            assertTrue("the watchdog napped", waitUntil(10) { childrenOf(watch.pid).isNotEmpty() })
            watched.process.destroyForcibly()

            assertTrue("the watchdog ended", watch.process.waitFor(10, TimeUnit.SECONDS))
            assertTrue("timeout was not stopped: ${log.readLines()}", standIn.process.waitFor(5, TimeUnit.SECONDS))
            assertTrue(log.readLines().toString(), log.readLines().contains("-s TERM ${standIn.pid}"))
        } finally {
            watch.process.destroyForcibly()
            standIn.process.destroyForcibly()
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
