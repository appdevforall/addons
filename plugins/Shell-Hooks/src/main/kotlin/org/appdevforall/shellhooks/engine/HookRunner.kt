package org.appdevforall.shellhooks.engine

import com.itsaky.androidide.plugins.extensions.CommandResult
import com.itsaky.androidide.plugins.extensions.CommandSpec
import com.itsaky.androidide.plugins.services.IdeCommandService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Runs one command and suspends until it ends; cancelling the caller stops the command. */
fun interface CommandRunner {
    suspend fun run(
        spec: CommandSpec.ShellCommand,
        timeoutMs: Long,
    ): CommandResult
}

/** [CommandRunner] over the IDE's headless command service. */
class IdeCommandRunner(
    private val service: () -> IdeCommandService?,
) : CommandRunner {
    override suspend fun run(
        spec: CommandSpec.ShellCommand,
        timeoutMs: Long,
    ): CommandResult {
        val commands = service() ?: return CommandResult.Failure(-1, "", "", "The IDE's command service is unavailable", 0)
        val execution = commands.executeCommand(spec, timeoutMs)
        return try {
            coroutineScope {
                // The host buffers every output line until someone reads them; drain them so a
                // chatty script does not hold its whole output twice.
                val drain = launch { execution.output.collect { } }
                execution.await().also { drain.cancel() }
            }
        } catch (e: CancellationException) {
            execution.cancel()
            throw e
        }
    }
}

/** How each script is launched: the bundled wrapper, the script, then the event's arguments. */
object HookInvocation {
    fun spec(
        wrapper: File,
        script: ResolvedScript,
        event: HookEvent,
        ideVersion: String?,
    ): CommandSpec.ShellCommand =
        CommandSpec.ShellCommand(
            executable = "sh",
            arguments = listOf(wrapper.path, script.executable.path) + event.args,
            // Null: the host's project root, or no directory with no project open. The wrapper
            // then changes to COGO_PROJECT_DIR, which for project-closed the host would refuse.
            workingDirectory = null,
            environment = environment(script, event, ideVersion),
        )

    fun environment(
        script: ResolvedScript,
        event: HookEvent,
        ideVersion: String?,
    ): Map<String, String> =
        buildMap {
            put("COGO_HOOK", event.hook.id)
            put("COGO_PROJECT_DIR", event.projectDir?.path ?: "")
            ideVersion?.let { put("COGO_IDE_VERSION", it) }
            put("COGO_HOOKS_DIR", script.hooksDir.path)
            put("COGO_HOOK_SCRIPT", script.original.path)
            put("COGO_HOOK_SOURCE", script.source.id)
        }
}

/**
 * Runs hook scripts one at a time, in the order their events happened. Events wait in a bounded
 * queue; when it is full the oldest is dropped. One at a time keeps the plugin far under the
 * host's limit of three concurrent commands, and keeps a burst of saves from starting a burst
 * of processes.
 */
class HookRunner(
    scope: CoroutineScope,
    private val resolver: ScriptResolver,
    private val commands: CommandRunner,
    private val log: HookLog,
    private val wrapper: () -> File?,
    private val timeoutMs: () -> Long,
    private val ideVersion: String? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val warn: (String) -> Unit = {},
    capacity: Int = DEFAULT_QUEUE_CAPACITY,
) {
    private val queue =
        Channel<HookEvent>(capacity, BufferOverflow.DROP_OLDEST) { dropped ->
            warn("Shell Hooks: queue full, dropped ${dropped.hook.id}")
        }

    private val worker: Job =
        scope.launch {
            for (event in queue) process(event)
        }

    /** Queues [event]; false once the runner is closed. */
    fun submit(event: HookEvent): Boolean = queue.trySend(event).isSuccess

    /** Stops taking events and cancels the script running now. */
    fun close() {
        queue.close()
        worker.cancel()
    }

    private suspend fun process(event: HookEvent) {
        val scripts =
            try {
                resolver.resolve(event)
            } catch (e: Exception) {
                warn("Shell Hooks: cannot list scripts for ${event.hook.id}: ${e.message}")
                return
            }
        for (script in scripts) runOne(event, script)
    }

    private suspend fun runOne(
        event: HookEvent,
        script: ResolvedScript,
    ) {
        val started = clock()
        val limit = timeoutMs()
        val wrapperFile = wrapper()
        val (outcome, exitCode, output) =
            if (wrapperFile == null) {
                Triple(Outcome.NOT_STARTED, null, "The hook runner script is missing")
            } else {
                val spec = HookInvocation.spec(wrapperFile, script, event, ideVersion)
                try {
                    // The host enforces the limit; the grace period only catches a host that does not.
                    withTimeoutOrNull(limit + TIMEOUT_GRACE_MS) { commands.run(spec, limit) }
                        ?.let(::describe)
                        ?: Triple(Outcome.TIMED_OUT, null, "Stopped after ${limit / 1000} s")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A refused start: the concurrency limit, or a permission the plugin lacks.
                    Triple(Outcome.NOT_STARTED, null, e.message ?: e.javaClass.simpleName)
                }
            }
        log.record(
            HookRun(
                startedAtMillis = started,
                hook = event.hook,
                script = script.original,
                source = script.source,
                target = event.target,
                outcome = outcome,
                exitCode = exitCode,
                durationMillis = clock() - started,
                output = output,
            ),
        )
    }

    private fun describe(result: CommandResult): Triple<Outcome, Int?, String> =
        when (result) {
            is CommandResult.Success -> Triple(Outcome.SUCCEEDED, result.exitCode, HookLog.combineOutput(result.stdout, result.stderr))
            is CommandResult.Failure -> {
                val output = HookLog.combineOutput(result.stdout, result.stderr)
                val error = result.error
                when {
                    error != null && error.contains("timed out", ignoreCase = true) ->
                        Triple(Outcome.TIMED_OUT, null, listOf(output, error).filter { it.isNotBlank() }.joinToString("\n"))
                    error != null && result.exitCode == -1 ->
                        Triple(Outcome.NOT_STARTED, null, error)
                    else -> Triple(Outcome.FAILED, result.exitCode, output)
                }
            }
            is CommandResult.Cancelled -> Triple(Outcome.CANCELLED, null, HookLog.combineOutput(result.partialStdout, result.partialStderr))
        }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 64
        const val TIMEOUT_GRACE_MS = 5_000L
    }
}

/**
 * Collapses a burst of events into the last one, delivered [delayMs] after the burst ends.
 * Switching through five tabs runs file-activated once, for the tab the user stopped on.
 */
class Coalescer(
    private val scope: CoroutineScope,
    private val delayMs: Long,
    private val emit: (HookEvent) -> Unit,
) {
    private var pending: Job? = null

    fun offer(event: HookEvent) {
        synchronized(this) {
            pending?.cancel()
            pending =
                scope.launch {
                    delay(delayMs)
                    emit(event)
                }
        }
    }

    fun cancel() {
        synchronized(this) { pending?.cancel() }
    }
}
