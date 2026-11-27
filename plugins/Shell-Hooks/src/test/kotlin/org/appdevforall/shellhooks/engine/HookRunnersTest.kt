package org.appdevforall.shellhooks.engine

import com.itsaky.androidide.plugins.extensions.CommandResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The runner the plugin builds: wired to know whether the Terminal has `timeout`. */
@OptIn(ExperimentalCoroutinesApi::class)
class HookRunnersTest {
    private val appFiles = tempDir("runners")
    private val paths = HookPaths(appFiles)
    private val project = tempDir("runners-project")
    private val log = HookLog()

    private val switches =
        object : HookSwitches {
            override val enabled = true
            override val projectHooksEnabled = false

            override fun isHookEnabled(hook: Hook) = true
        }

    @After
    fun tearDown() {
        listOf(appFiles, project).forEach { it.deleteRecursively() }
    }

    private fun TestScope.settle() {
        advanceTimeBy(1_000_000)
        runCurrent()
    }

    /** A 124 once the run reached its 1 s limit, as GNU timeout reports a TERM'd script. */
    private fun TestScope.runUntil124(): HookRun {
        paths.globalHooks.script("file-saved")
        val runner =
            HookRunners.create(
                scope = backgroundScope,
                paths = paths,
                switches = switches,
                trust = TrustStore(MapStore()),
                log = log,
                verifiedDir = File(appFiles, "verified"),
                commands =
                    CommandRunner { _, _, _ ->
                        delay(1_000)
                        CommandResult.Failure(124, "", "", null, 1_000)
                    },
                wrapper = { File(appFiles, "cogo-hook-run.sh") },
                timeoutMs = { 1_000 },
                ideVersion = null,
                warn = {},
                elapsedClock = { testScheduler.currentTime },
            )
        runner.submit(HookEvent.fileSaved(File(project, "Main.kt"), project))
        settle()
        return log.runs.value.single()
    }

    @Test
    fun withTimeoutInstalledA124AtTheLimitIsATimeout() =
        runTest {
            paths.prefix.script("bin/timeout")

            assertEquals(Outcome.TIMED_OUT, runUntil124().outcome)
        }

    @Test
    fun withoutTimeoutInstalledA124IsTheScriptsOwnFailure() =
        runTest {
            val run = runUntil124()

            assertEquals(Outcome.FAILED, run.outcome)
            assertEquals(124, run.exitCode)
        }

    @Test
    fun hasTimeoutWhenThePrefixHasAnExecutableTimeout() {
        paths.prefix.script("bin/timeout")

        assertTrue(paths.hasTimeout())
    }

    @Test
    fun noTimeoutWhenItIsMissingNotExecutableOrAFolder() {
        assertFalse(paths.hasTimeout())

        val file = paths.prefix.script("bin/timeout", executable = false)
        assertFalse(paths.hasTimeout())

        file.delete()
        file.mkdirs()
        assertFalse(paths.hasTimeout())
    }
}
