package org.appdevforall.shellhooks.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Runs action launchers under each POSIX shell this machine has, as the Terminal's `sh` would.
 * The launcher then execs `sh` from PATH for the runner, as in the Terminal.
 */
@RunWith(Parameterized::class)
class ActionLauncherTest(
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

        private val FILE_NAME = Regex("[A-Za-z0-9._-]{1,32}-[a-z0-9]{5}")
    }

    private val work = tempDir("launcher")
    private val dir = File(work, "data/run")

    /** Prints its two arguments and two variables, each ended by a NUL, so any byte survives. */
    private val dumper =
        work.script("dumper", "#!/bin/sh\nprintf '%s\\0' \"\$1\" \"\$2\" \"\${COGO_A-unset}\" \"\${COGO_B-unset}\"\n")
    private val quiet = work.script("quiet", "#!/bin/sh\n:\n")

    @After
    fun tearDown() {
        work.deleteRecursively()
    }

    private fun launcher(
        runner: File = dumper,
        action: File = File(work, "word-count"),
        target: File = File(work, "Main.kt"),
        environment: Map<String, String> = emptyMap(),
    ) = ActionLauncher.write(dir, runner, action, target, environment)

    private fun run(
        launcher: File,
        input: String = "",
    ): ByteArray {
        assumeTrue("needs $shell", File(shell).canExecute())
        val command = if (shell.endsWith("busybox")) listOf(shell, "sh") else listOf(shell)
        val process = ProcessBuilder(command + launcher.path).directory(work).redirectErrorStream(true).start()
        process.outputStream.use { it.write(input.toByteArray()) }
        val out = process.inputStream.readBytes()
        process.waitFor(10, TimeUnit.SECONDS)
        return out
    }

    /** The printed line, then the dumper's fields. */
    private fun split(out: ByteArray): Pair<String, List<String>> {
        val text = out.toString(Charsets.UTF_8)
        val line = text.substringBefore('\n')
        return line to text.substringAfter('\n').split('\u0000')
    }

    @Test
    fun everyHostileValueReachesTheRunnerByteForByte() {
        for (name in HOSTILE_NAMES + CONTROL_NAMES) {
            val action = File(work, "act $name")
            val target = File(work, name)
            val launcher =
                launcher(action = action, target = target, environment = linkedMapOf("COGO_A" to name, "COGO_B" to "x'$name'y"))

            val (_, fields) = split(run(launcher))

            assertEquals("name: <$name>", listOf(action.path, target.path, name, "x'$name'y", ""), fields)
        }
        assertFalse("a value ran a command", File(work, "pwned").exists())
    }

    @Test
    fun theLauncherDeletesItself() {
        val launcher = launcher()

        run(launcher)

        assertFalse(launcher.exists())
    }

    @Test
    fun theLauncherDeletesItselfWhenTheRunnerIsMissing() {
        val launcher = launcher(runner = File(work, "no such runner"))

        run(launcher)

        assertFalse(launcher.exists())
    }

    // The printed line is the only text the launcher adds; a control code in a name must not reach
    // the terminal raw, as the command line's quoting already ensures for the line the Terminal prints.
    @Test
    fun itPrintsOneLineNamingTheActionAndTheFile() {
        val launcher =
            launcher(runner = quiet, action = File(work, "x\u001b]52;c;ZWNobyBwd25lZA==\u0007"), target = File(work, "Main\u009b.kt"))

        val out = run(launcher).toString(Charsets.UTF_8)

        assertEquals("Run x?]52;c;ZWNobyBwd25lZA==? on Main?.kt\n", out)
    }

    @Test
    fun anActionCanReadWhatTheUserTypes() {
        val reader = work.script("reader", "#!/bin/sh\nIFS= read -r line\nprintf 'got:%s\\n' \"\$line\"\n")

        val out = run(launcher(runner = reader), input = "typed text\n").toString(Charsets.UTF_8)

        assertTrue(out, out.endsWith("got:typed text\n"))
    }

    // The point of the launcher: the line the Terminal prints names it, and nothing else.
    @Test
    fun theTerminalsCommandIsShAndTheLaunchersPathOnly() {
        val secret = "/storage/emulated/0/CodeOnTheGoProjects/My App/app/src/Main.kt"
        val launcher =
            launcher(
                action = File(work, "word-count"),
                target = File(secret),
                environment = linkedMapOf("COGO_A" to "value-a", "COGO_PROJECT_DIR" to "/storage/emulated/0/CodeOnTheGoProjects/My App"),
            )

        val command = ActionLauncher.command(launcher)

        assertEquals("'sh' '${launcher.path}'", command)
        assertEquals(dir.canonicalFile, launcher.canonicalFile.parentFile)
        assertTrue(launcher.name, FILE_NAME.matches(launcher.name))
        assertTrue(launcher.name, launcher.name.startsWith("word-count-"))
        for (leak in listOf("COGO_", "value-a", "Main.kt", "CodeOnTheGoProjects")) {
            assertFalse("$leak in: $command", leak in command)
        }
    }

    @Test
    fun fileNamesAreSafeAndBounded() {
        for (name in listOf("with space", "a/b", "x\u001b]52\u0007", "caf\u00e9", "a".repeat(300), "", "..", "-dash") + HOSTILE_NAMES) {
            val fileName = ActionLauncher.fileName(name)
            assertTrue("<$name> gave <$fileName>", FILE_NAME.matches(fileName))
        }
        assertEquals("action-", ActionLauncher.fileName("").dropLast(5))
    }

    @Test
    fun aNulOrABadVariableNameIsRefusedBeforeAnythingIsWritten() {
        for (environment in listOf(mapOf("COGO_A" to "a\u0000b"), mapOf("A;rm" to "1"), mapOf("" to "1"))) {
            try {
                launcher(environment = environment)
                throw AssertionError("accepted $environment")
            } catch (expected: IllegalArgumentException) {
            }
        }
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun aLauncherNeverReplacesAnExistingFile() {
        val first = ActionLauncher.write(dir, dumper, File(work, "word-count"), File(work, "A.kt"), emptyMap(), Random(7))
        val text = first.readText()

        val second = ActionLauncher.write(dir, dumper, File(work, "word-count"), File(work, "B.kt"), emptyMap(), Random(7))

        assertNotEquals(first, second)
        assertEquals(text, first.readText())
    }

    @Test
    fun sweepEmptiesTheLaunchersFolderAndNothingElse() {
        val left = listOf(launcher(), launcher())
        val sibling = File(dir.parentFile, "cogo-hook-run.sh").apply { writeText("keep") }

        ActionLauncher.sweep(dir)

        left.forEach { assertFalse(it.exists()) }
        assertTrue(dir.isDirectory)
        assertEquals("keep", sibling.readText())
    }
}
