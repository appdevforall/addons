package org.appdevforall.shellhooks.engine

import com.itsaky.androidide.plugins.extensions.CommandOutput
import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.services.CommandExecution
import com.itsaky.androidide.plugins.services.IdeCommandService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdeCommandRunnerTest {
    private class FakeExecution : CommandExecution {
        private val lines = Channel<CommandOutput>(Channel.UNLIMITED)
        val result = CompletableDeferred<CommandResult>()
        var cancelled = false

        override val executionId = "fake"
        override val output: Flow<CommandOutput> = lines.receiveAsFlow()

        fun print(line: CommandOutput) {
            lines.trySend(line)
        }

        override suspend fun await(): CommandResult = result.await()

        override fun cancel() {
            cancelled = true
        }
    }

    private class FakeService(
        private val execution: FakeExecution,
    ) : IdeCommandService {
        override fun executeCommand(
            spec: CommandSpec,
            timeoutMs: Long,
        ): CommandExecution = execution

        override fun isCommandRunning(executionId: String) = !execution.result.isCompleted

        override fun cancelCommand(executionId: String) = true.also { execution.cancel() }

        override fun getRunningCommandCount() = 0
    }

    private val spec = CommandSpec.ShellCommand("sh", listOf("wrapper", "hook"))

    @Test
    fun linesPrintedBeforeACancelAreKeptAndTheCommandIsStopped() =
        runTest {
            val execution = FakeExecution()
            val tail = OutputTail()
            val run = launch { IdeCommandRunner { FakeService(execution) }.run(spec, 1_000, tail) }
            runCurrent()

            execution.print(CommandOutput.StdOut("step 1 done"))
            execution.print(CommandOutput.StdErr("waiting for the server"))
            runCurrent()
            run.cancel() // the runner's own timeout, or the plugin closing
            runCurrent()

            assertEquals("step 1 done\nwaiting for the server\n", tail.text())
            assertTrue("the host command was stopped", execution.cancelled)
        }

    @Test
    fun theTailKeepsTheLastLinesOfAChattyCommand() =
        runTest {
            val execution = FakeExecution()
            val tail = OutputTail(max = 16)
            val run = launch { IdeCommandRunner { FakeService(execution) }.run(spec, 1_000, tail) }
            runCurrent()

            (1..20).forEach { execution.print(CommandOutput.StdOut("line-%02d".format(it))) }
            runCurrent()
            run.cancel()
            runCurrent()

            assertTrue(tail.text(), tail.text().endsWith("line-19\nline-20\n"))
            assertFalse(tail.text(), tail.text().contains("line-01"))
        }

    @Test
    fun aFinishedCommandReturnsTheHostsResult() =
        runTest {
            val execution = FakeExecution()
            val tail = OutputTail()
            val result = async { IdeCommandRunner { FakeService(execution) }.run(spec, 1_000, tail) }
            runCurrent()

            execution.print(CommandOutput.StdOut("ok"))
            execution.print(CommandOutput.ExitCode(0))
            runCurrent()
            val done = CommandResult.Success(0, "ok\n", "", 5)
            execution.result.complete(done)
            runCurrent()

            assertEquals(done, result.await())
            assertEquals("ok\n", tail.text())
            assertFalse(execution.cancelled)
        }
}
