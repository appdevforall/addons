package org.appdevforall.shellhooks.engine

import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class HookRunnerTest {
    private val log = HookLog()
    private val wrapper = File("/data/plugin/cogo-hook-run.sh")
    private val project = File("/projects/app")

    /** One script per event, named after the event's first argument. */
    private val oneScriptPerEvent =
        ScriptResolver { event ->
            val script = File("/hooks/${event.hook.id}")
            listOf(ResolvedScript(script, script, ScriptSource.GLOBAL, File("/hooks")))
        }

    private val success = CommandResult.Success(0, "ok\n", "", 1)

    // advanceUntilIdle() ignores backgroundScope, where the runner's worker lives.
    private fun TestScope.settle() {
        advanceTimeBy(1_000_000)
        runCurrent()
    }

    private fun TestScope.runner(
        commands: CommandRunner,
        resolver: ScriptResolver = oneScriptPerEvent,
        timeoutMs: Long = 60_000,
        capacity: Int = HookRunner.DEFAULT_QUEUE_CAPACITY,
        warnings: MutableList<String> = mutableListOf(),
    ) = HookRunner(
        scope = backgroundScope,
        resolver = resolver,
        commands = commands,
        log = log,
        wrapper = { wrapper },
        timeoutMs = { timeoutMs },
        clock = { testScheduler.currentTime },
        warn = { warnings += it },
        capacity = capacity,
    )

    @Test
    fun runsOneScriptAtATimeInEventOrder() =
        runTest {
            val running = AtomicInteger()
            var mostAtOnce = 0
            val order = mutableListOf<String>()
            val runner =
                runner(
                    CommandRunner { spec, _, _ ->
                        mostAtOnce = maxOf(mostAtOnce, running.incrementAndGet())
                        order += spec.arguments.drop(2).firstOrNull() ?: ""
                        delay(100)
                        running.decrementAndGet()
                        success
                    },
                )

            repeat(10) { runner.submit(HookEvent.fileSaved(File("/projects/app/F$it.kt"), project)) }
            settle()

            assertEquals("never more than one command at a time (host allows 3)", 1, mostAtOnce)
            assertEquals((0 until 10).map { "/projects/app/F$it.kt" }, order)
            assertEquals(10, log.runs.value.size)
        }

    @Test
    fun aFullQueueDropsTheOldestEvent() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val ran = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            val runner =
                runner(
                    CommandRunner { spec, _, _ ->
                        val file = spec.arguments[2]
                        if (file == "first") release.await()
                        ran += file
                        success
                    },
                    capacity = 3,
                    warnings = warnings,
                )

            runner.submit(HookEvent.fileSaved(File("first"), null))
            runCurrent() // the worker takes "first" and blocks on it
            listOf("a", "b", "c", "d", "e").forEach { runner.submit(HookEvent.fileSaved(File(it), null)) }
            release.complete(Unit)
            settle()

            assertEquals(listOf("first", "c", "d", "e"), ran)
            assertEquals(2, warnings.count { it.contains("queue full") })
        }

    @Test
    fun aScriptThatOutlivesItsTimeoutIsStoppedAndRecordedAsTimedOut() =
        runTest {
            var cancelled = false
            val runner =
                runner(
                    CommandRunner { _, _, _ ->
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    },
                    timeoutMs = 1_000,
                )

            runner.submit(HookEvent.buildStarted(project))
            advanceTimeBy(1_000 + HookRunner.WRAPPER_KILL_GRACE_MS + HookRunner.TIMEOUT_GRACE_MS + 1)
            runCurrent()

            assertTrue("the hung command was cancelled", cancelled)
            assertEquals(Outcome.TIMED_OUT, log.runs.value.single().outcome)
        }

    @Test
    fun aRunStoppedForItsTimeoutKeepsWhatItPrinted() =
        runTest {
            val runner =
                runner(
                    CommandRunner { _, _, output ->
                        output.append("step 1 done")
                        output.append("waiting for the server")
                        awaitCancellation()
                    },
                    timeoutMs = 1_000,
                )

            runner.submit(HookEvent.buildStarted(project))
            settle()

            val run = log.runs.value.single()
            assertEquals(Outcome.TIMED_OUT, run.outcome)
            assertEquals("step 1 done\nwaiting for the server\nStopped after 1 s", run.output)
        }

    @Test
    fun theWrappersTimeoutExitIsRecordedAsTimedOut() =
        runTest {
            val runner =
                runner(
                    CommandRunner { _, _, _ ->
                        delay(1_000)
                        CommandResult.Failure(HookRunner.TIMEOUT_EXIT, "partial", "", null, 1_000)
                    },
                    timeoutMs = 1_000,
                )

            runner.submit(HookEvent.buildStarted(project))
            settle()

            val run = log.runs.value.single()
            assertEquals(Outcome.TIMED_OUT, run.outcome)
            assertEquals("partial\nStopped after 1 s", run.output)
        }

    @Test
    fun aKillAfterTheLimitIsATimeoutButAnEarlyKillIsAFailure() =
        runTest {
            var elapsed = 0L
            val runner =
                runner(
                    CommandRunner { _, _, _ ->
                        delay(elapsed)
                        CommandResult.Failure(HookRunner.TIMEOUT_KILL_EXIT, "", "", null, elapsed)
                    },
                    timeoutMs = 1_000,
                )

            elapsed = 6_000
            runner.submit(HookEvent.buildStarted(project))
            settle()
            elapsed = 10
            runner.submit(HookEvent.buildSucceeded(project))
            settle()

            assertEquals(listOf(Outcome.TIMED_OUT, Outcome.FAILED), log.runs.value.reversed().map { it.outcome })
        }

    @Test
    fun anEarlyExit124IsAFailureWithItsCode() =
        runTest {
            val runner =
                runner(
                    // A lint hook passing on an inner timeout's 124 long before its own limit.
                    CommandRunner { _, _, _ ->
                        delay(6)
                        CommandResult.Failure(HookRunner.TIMEOUT_EXIT, "lint timed out\n", "", null, 6)
                    },
                    timeoutMs = 60_000,
                )

            runner.submit(HookEvent.buildStarted(project))
            settle()

            val run = log.runs.value.single()
            assertEquals(Outcome.FAILED, run.outcome)
            assertEquals(HookRunner.TIMEOUT_EXIT, run.exitCode)
            assertEquals("lint timed out", run.output.trimEnd())
        }

    @Test
    fun anErrorWhileListingScriptsDoesNotStopTheWorker() =
        runTest {
            var calls = 0
            val warnings = mutableListOf<String>()
            val runner =
                runner(
                    CommandRunner { _, _, _ -> success },
                    resolver =
                        ScriptResolver { event ->
                            if (calls++ == 0) throw OutOfMemoryError("a huge script")
                            oneScriptPerEvent.resolve(event)
                        },
                    warnings = warnings,
                )

            runner.submit(HookEvent.buildStarted(project))
            runner.submit(HookEvent.buildSucceeded(project))
            settle()

            assertEquals(listOf(Hook.BUILD_SUCCEEDED), log.runs.value.map { it.hook })
            assertTrue(warnings.single().contains("OutOfMemoryError"))
        }

    @Test
    fun theTimeoutSettingReachesTheWrapperAndTheHostGetsLonger() =
        runTest {
            var limit = 0L
            var env = emptyMap<String, String>()
            val runner =
                runner(
                    CommandRunner { spec, timeout, _ ->
                        limit = timeout
                        env = spec.environment
                        success
                    },
                    timeoutMs = 42_000,
                )

            runner.submit(HookEvent.buildStarted(project))
            settle()

            assertEquals("42", env[HookInvocation.TIMEOUT_VARIABLE])
            assertEquals(42_000L + HookRunner.WRAPPER_KILL_GRACE_MS, limit)
        }

    @Test
    fun theHostsOwnTimeoutIsRecordedAsTimedOut() =
        runTest {
            val runner =
                runner(CommandRunner { _, _, _ -> CommandResult.Failure(-1, "partial", "", "Command timed out after 60000ms: x", 60_000) })

            runner.submit(HookEvent.buildStarted(project))
            settle()

            assertEquals(Outcome.TIMED_OUT, log.runs.value.single().outcome)
        }

    @Test
    fun aRefusedStartIsRecordedAndTheQueueKeepsGoing() =
        runTest {
            var calls = 0
            val runner =
                runner(
                    CommandRunner { _, _, _ ->
                        if (calls++ == 0) throw IllegalStateException("maximum of 3 concurrent commands")
                        success
                    },
                )

            runner.submit(HookEvent.buildStarted(project))
            runner.submit(HookEvent.buildSucceeded(project))
            settle()

            val runs = log.runs.value.reversed()
            assertEquals(listOf(Outcome.NOT_STARTED, Outcome.SUCCEEDED), runs.map { it.outcome })
        }

    @Test
    fun aNonZeroExitIsAFailureWithItsCodeAndOutput() =
        runTest {
            val runner = runner(CommandRunner { _, _, _ -> CommandResult.Failure(3, "out", "err", null, 5) })

            runner.submit(HookEvent.buildFailed("BUILD_FAILED", project))
            settle()

            val run = log.runs.value.single()
            assertEquals(Outcome.FAILED, run.outcome)
            assertEquals(3, run.exitCode)
            assertEquals("out\nerr", run.output)
            assertEquals("BUILD_FAILED", run.target)
        }

    @Test
    fun aMissingWrapperRunsNothing() =
        runTest {
            var calls = 0
            val runner =
                HookRunner(
                    scope = backgroundScope,
                    resolver = oneScriptPerEvent,
                    commands = CommandRunner { _, _, _ -> calls++; success },
                    log = log,
                    wrapper = { null },
                    timeoutMs = { 1_000 },
                )

            runner.submit(HookEvent.buildStarted(project))
            settle()

            assertEquals(0, calls)
            assertEquals(Outcome.NOT_STARTED, log.runs.value.single().outcome)
        }

    @Test
    fun closingStopsTheRunningScriptAndRefusesNewEvents() =
        runTest {
            var cancelled = false
            val runner =
                runner(
                    CommandRunner { _, _, _ ->
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled = true
                        }
                    },
                )
            runner.submit(HookEvent.buildStarted(project))
            runCurrent()

            runner.close()
            runCurrent()

            assertTrue(cancelled)
            assertEquals(false, runner.submit(HookEvent.buildSucceeded(project)))
        }

    @Test
    fun coalescerDeliversOnlyTheLastOfABurst() =
        runTest {
            val emitted = mutableListOf<HookEvent>()
            val coalescer = Coalescer(backgroundScope, 500) { emitted += it }

            listOf("a", "b", "c").forEach {
                coalescer.offer(HookEvent.fileActivated(File(it), null))
                advanceTimeBy(200)
            }
            advanceTimeBy(501)

            assertEquals(listOf("c"), emitted.map { it.args.single() })
        }

    @Test
    fun coalescerDeliversEventsFartherApartThanItsDelay() =
        runTest {
            val emitted = mutableListOf<HookEvent>()
            val coalescer = Coalescer(backgroundScope, 500) { emitted += it }

            coalescer.offer(HookEvent.fileActivated(File("a"), null))
            advanceTimeBy(600)
            coalescer.offer(HookEvent.fileActivated(null, null))
            advanceTimeBy(600)

            assertEquals(listOf("a", ""), emitted.map { it.args.single() })
        }
}
