package org.appdevforall.shellhooks.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicLong

enum class ScriptSource(
    val id: String,
) {
    GLOBAL("global"),
    PROJECT("project"),
}

enum class Outcome {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,

    /** The command never started: no command service, a refused start, or a missing wrapper. */
    NOT_STARTED,
}

/**
 * One script's run, as the Hooks tab shows it. [hook] is null for an action the user ran from a
 * file's menu. [output] is stdout then stderr, tail-capped. [id] is unique within the process.
 */
data class HookRun(
    val startedAtMillis: Long,
    val hook: Hook?,
    val script: File,
    val source: ScriptSource,
    val target: String?,
    val outcome: Outcome,
    val exitCode: Int?,
    val durationMillis: Long,
    val output: String,
    val id: Long = nextRunId.incrementAndGet(),
) {
    /** The hook's id, or `action` for an action. */
    val name: String get() = hook?.id ?: ACTION

    /**
     * What the run was about, for its one-line title: the file or project name, or for
     * build-failed the first line of the IDE's message, which is text rather than a path.
     */
    val targetLabel: String?
        get() {
            val value = target?.takeIf { it.isNotEmpty() } ?: return null
            if (hook == Hook.BUILD_FAILED) {
                val line = value.lineSequence().first().trim()
                return if (line.length <= MAX_TITLE_CHARS) line else line.take(MAX_TITLE_CHARS - 3) + "..."
            }
            return File(value).name.ifEmpty { value }
        }

    companion object {
        const val ACTION = "action"
        const val MAX_TITLE_CHARS = 80
        private val nextRunId = AtomicLong()
    }
}

/**
 * A project hook that was skipped because the user has not trusted this content of it, or because
 * it is larger than [ScriptReview.MAX_BYTES] ([tooLarge]), which never runs.
 */
data class PendingTrust(
    val projectDir: File,
    val relativePath: String,
    val file: File,
    val sha256: String,
    val tooLarge: Boolean = false,
)

/** The recent runs and the project hooks awaiting review, observed by the Hooks tab. */
class HookLog(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val _runs = MutableStateFlow<List<HookRun>>(emptyList())
    private val _pending = MutableStateFlow<List<PendingTrust>>(emptyList())

    /** Newest first. */
    val runs: StateFlow<List<HookRun>> = _runs.asStateFlow()
    val pending: StateFlow<List<PendingTrust>> = _pending.asStateFlow()

    fun record(run: HookRun) {
        _runs.update { (listOf(run) + it).take(capacity) }
    }

    /** Lists [entry] for review once; a later sighting of the same script replaces it. */
    fun addPending(entry: PendingTrust) {
        _pending.update { list -> list.filterNot { it.sameScript(entry) } + entry }
    }

    fun removePending(
        projectDir: File,
        relativePath: String,
    ) {
        _pending.update { list -> list.filterNot { it.projectDir == projectDir && it.relativePath == relativePath } }
    }

    fun clearRuns() {
        _runs.value = emptyList()
    }

    private fun PendingTrust.sameScript(other: PendingTrust) =
        projectDir == other.projectDir && relativePath == other.relativePath

    companion object {
        const val DEFAULT_CAPACITY = 100

        /** The most of one run's output kept: the end, where errors are. */
        const val MAX_OUTPUT_CHARS = 64 * 1024

        fun combineOutput(
            stdout: String,
            stderr: String,
        ): String {
            val combined =
                when {
                    stderr.isBlank() -> stdout
                    stdout.isBlank() -> stderr
                    else -> stdout.trimEnd() + "\n" + stderr
                }
            return capTail(combined, MAX_OUTPUT_CHARS)
        }

        fun capTail(
            text: String,
            max: Int,
        ): String = if (text.length <= max) text else "[...]\n" + text.takeLast(max)
    }
}

/**
 * The end of a command's output as its lines arrive, for a run that is stopped before the host
 * reports what it printed. Keeps at most [max] characters, from the end.
 */
class OutputTail(
    private val max: Int = HookLog.MAX_OUTPUT_CHARS,
) {
    private val buffer = StringBuilder()

    fun append(line: String) {
        synchronized(buffer) {
            buffer.append(line).append('\n')
            if (buffer.length > 2 * max) buffer.delete(0, buffer.length - max)
        }
    }

    fun text(): String = synchronized(buffer) { HookLog.capTail(buffer.toString(), max) }
}
