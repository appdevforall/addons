package com.itsaky.androidide.plugins.aiagentlocal.device.engine

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.JsonException
import java.io.File
import java.io.InputStream

/** Runs engine commands; implemented by [EngineRunner], replaced by fakes in tests of the code that uses it. */
interface EngineExecutor {
    fun run(command: EngineCommand): EngineResult
}

/**
 * Runs the engine programs, one process per invocation, and turns the outcome into an [EngineResult]. Never throws.
 *
 * - The program is found by [locator]; the process is started with an argument list, working directory [workDir]
 *   (created when missing), an empty environment (the engine needs none, ENGINE.md "Starting from the plugin") and a
 *   closed stdin.
 * - stdout and stderr are read at the same time, each keeping its last [OUTPUT_CAP_BYTES] bytes. The result is the
 *   **last non-empty stdout line**, which must be one JSON object.
 * - The hard limit is the command's `--max-seconds` plus [GRACE_SECONDS]; then the process is destroyed
 *   (`destroy`, then `destroyForcibly` if it is still alive) and the result is [EngineResult.TimeoutKilled].
 * - The first call of a session verifies the engine: it runs `--static-probe --json`, and when its `engine_version` is not
 *   [expectedVersion] the result is [EngineResult.EngineMismatch] now and for every later call; nothing else is run and
 *   nothing is repaired (planner F179). When the probe itself gives no usable answer, that answer is returned and the check
 *   is repeated by the next call.
 * - "Never throws" covers every `Exception`. A `StackOverflowError` from parsing the last line (a deeply nested object) is turned
 *   into [EngineResult.NoJson]. Other `Error`s (`OutOfMemoryError`, `LinkageError`, ...) are deliberately **not** caught: the
 *   process is in a state the runner cannot repair, and they reach the caller.
 * - Blocking: call it from a background thread. [onMainThread] (the app passes a Looper check) makes a call on the
 *   main thread return [EngineResult.NotStarted] without starting anything.
 */
class EngineRunner(
    private val locator: EngineLocator,
    private val launcher: ProcessLauncher,
    private val workDir: File,
    private val expectedVersion: String = EXPECTED_ENGINE_VERSION,
    private val onMainThread: () -> Boolean = { false },
) : EngineExecutor {
    companion object {
        /** The engine this plugin ships (`part1-engine/VERSION`); a static probe that says otherwise is a mismatch. */
        const val EXPECTED_ENGINE_VERSION = "part1-engine/0.2.4"

        /** Seconds after the engine's own `--max-seconds` at which the process is destroyed. */
        const val GRACE_SECONDS = 5

        /** Bytes kept of each of stdout and stderr. */
        const val OUTPUT_CAP_BYTES = 1024 * 1024

        /** Bytes of stderr reported in [EngineResult.NoJson]. */
        const val STDERR_TAIL_BYTES = 1024

        /** Exit codes that are recorded refusals (memory guard, not foreground, unsupported). */
        val REFUSAL_EXITS = setOf(88, 97, 3)

        private const val DESTROY_WAIT_MS = 1000L
        private const val FORCE_WAIT_MS = 2000L
        private const val JOIN_MS = 2000L
    }

    private var verified = false
    private var mismatch: EngineResult.EngineMismatch? = null

    /** Runs [command] (after the one-time version check) and reports the outcome. */
    @Synchronized
    override fun run(command: EngineCommand): EngineResult {
        try {
            if (onMainThread()) return EngineResult.NotStarted("called on the main thread; nothing was started")
            mismatch?.let { return it }
            if (!verified) {
                val probe = if (command.kind == CommandKind.STATIC_PROBE) command else EngineCommands.staticProbe()
                val r = execute(probe)
                val gate = gate(r)
                if (gate != null) return gate
                if (probe === command) return r
            }
            return execute(command)
        } catch (e: Exception) { // never into the caller; an Error (OutOfMemoryError, ...) is not caught: see the class comment
            return EngineResult.Failed(null, -1)
        }
    }

    /** null when the probe verified the engine; otherwise the result that stops the call. */
    private fun gate(r: EngineResult): EngineResult? {
        if (r !is EngineResult.Ok) return r
        val found = r.json["engine_version"] as? String
        if (found == expectedVersion) {
            verified = true
            return null
        }
        return EngineResult.EngineMismatch(found).also { mismatch = it }
    }

    private fun execute(command: EngineCommand): EngineResult {
        val exe = locator.locate(command.program)
            ?: return EngineResult.ProgramMissing("${command.program.fileName} was not found")
        if (!workDir.isDirectory && !workDir.mkdirs() && !workDir.isDirectory) {
            return EngineResult.NotStarted("the work directory cannot be created")
        }
        val proc = try {
            launcher.start(listOf(exe.path) + command.args, workDir, emptyMap())
        } catch (e: Exception) {
            return EngineResult.ExecBlocked("${e.javaClass.simpleName}: ${e.message.orEmpty().take(200)}")
        }
        val out = TailCapture(OUTPUT_CAP_BYTES)
        val err = TailCapture(OUTPUT_CAP_BYTES)
        val readers = try {
            runCatching { proc.closeStdin() }
            listOf(pump(proc.stdout, out), pump(proc.stderr, err))
        } catch (e: Exception) {
            kill(proc)
            return EngineResult.Failed(null, -1)
        }
        try {
            val limitMs = (command.maxSeconds + GRACE_SECONDS) * 1000L
            if (!proc.waitFor(limitMs)) {
                kill(proc)
                closeQuietly(proc.stdout); closeQuietly(proc.stderr)
                readers.forEach { it.join(JOIN_MS) }
                return EngineResult.TimeoutKilled
            }
            readers.forEach { it.join(JOIN_MS) }
            return classify(proc.exitValue(), out, err, command)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            kill(proc)
            return EngineResult.Failed(null, -1)
        }
    }

    private fun pump(input: InputStream, into: TailCapture): Thread {
        val t = Thread {
            try {
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    into.write(buf, 0, n)
                }
            } catch (_: Exception) {
                // a closed or broken pipe ends the capture; what was read stays
            }
        }
        t.isDaemon = true
        t.start()
        return t
    }

    private fun kill(proc: RunningProcess) {
        runCatching { proc.destroy() }
        if (runCatching { proc.waitFor(DESTROY_WAIT_MS) }.getOrDefault(false)) return
        runCatching { proc.destroyForcibly() }
        runCatching { proc.waitFor(FORCE_WAIT_MS) }
    }

    private fun closeQuietly(s: InputStream) {
        runCatching { s.close() }
    }

    private fun classify(exit: Int, out: TailCapture, err: TailCapture, command: EngineCommand): EngineResult {
        val json = lastJsonObject(out.bytes())
            ?: return EngineResult.NoJson(exit, String(err.bytes(), Charsets.UTF_8).takeLast(STDERR_TAIL_BYTES))
        val record = withTag(json, command)
        return when {
            exit == 0 && record["status"] == "ok" -> EngineResult.Ok(record)
            exit in REFUSAL_EXITS -> EngineResult.Refused(record, exit)
            else -> EngineResult.Failed(record, exit)
        }
    }

    /** The row log record of a row the engine reported without `tag` (a usage error before `--tag`) gets the tag the row was started with. */
    private fun withTag(json: Map<String, Any?>, command: EngineCommand): Map<String, Any?> =
        if (command.tag != null && json["format"] == "part1-rows-v1" && json["tag"] == null) json + ("tag" to command.tag) else json

    /** The last non-empty line of [stdout] as a JSON object, or null when there is none or it is not an object. */
    internal fun lastJsonObject(stdout: ByteArray): Map<String, Any?>? {
        val line = String(stdout, Charsets.UTF_8).split('\n').map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
        if (!line.startsWith("{") || !line.endsWith("}")) return null
        return try {
            Json.parseObject(line, "engine output")
        } catch (e: JsonException) {
            null
        } catch (e: StackOverflowError) {
            null // a recursive parser on a deeply nested line: no usable object
        }
    }
}
