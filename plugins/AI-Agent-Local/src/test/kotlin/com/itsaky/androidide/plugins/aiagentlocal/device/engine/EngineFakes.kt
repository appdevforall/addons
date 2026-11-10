package com.itsaky.androidide.plugins.aiagentlocal.device.engine

import com.itsaky.androidide.plugins.aiagentlocal.device.Json
import com.itsaky.androidide.plugins.aiagentlocal.device.Part1TestResources
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Recorded engine output (`src/test/resources/part1/engine-out/`) and the exit code it was recorded with. */
object Recorded {
    private val exits: Map<String, Any?> by lazy { Part1TestResources.obj("part1/engine-out/exits.json") }

    fun bytes(name: String): ByteArray = Part1TestResources.bytes("part1/engine-out/$name")
    fun text(name: String): String = Part1TestResources.text("part1/engine-out/$name")
    fun exit(name: String): Int = (exits[name] as Long).toInt()
    fun names(): List<String> = exits.keys.filter { it.endsWith(".out") }.sorted()

    /** The last non-empty line, as the runner reads it. */
    fun lastLine(name: String): String = text(name).lines().last { it.isNotBlank() }

    /** The recorded static probe of the phone, with the engine version of the packaged engine (the only edit: 0.2.3 becomes 0.2.4). */
    fun staticProbe024(): ByteArray = text("d5-staticprobe-v0.2.3.out").replace("part1-engine/0.2.3", EngineRunner.EXPECTED_ENGINE_VERSION).toByteArray()

    fun gpuProbe024(): ByteArray = text("d5-gpuprobe-v0.2.3.out").replace("part1-engine/0.2.3", EngineRunner.EXPECTED_ENGINE_VERSION).toByteArray()
}

/** A process that replays given bytes. */
class FakeProcess(
    stdout: ByteArray = ByteArray(0),
    stderr: ByteArray = ByteArray(0),
    private val exit: Int = 0,
    /** The process never exits by itself. */
    private val hangs: Boolean = false,
    /** `destroy()` does not stop it; only `destroyForcibly()` does. */
    private val ignoresDestroy: Boolean = false,
    stdoutStream: InputStream? = null,
    stderrStream: InputStream? = null,
) : RunningProcess {
    override val stdout: InputStream = stdoutStream ?: ByteArrayInputStream(stdout)
    override val stderr: InputStream = stderrStream ?: ByteArrayInputStream(stderr)
    var stdinClosed = false
    var destroyed = false
    var forced = false
    val waits = ArrayList<Long>()
    private var dead = !hangs

    override fun closeStdin() { stdinClosed = true }
    override fun waitFor(timeoutMs: Long): Boolean { waits.add(timeoutMs); return dead }
    override fun exitValue(): Int = if (hangs && !dead) throw IllegalThreadStateException() else if (hangs) 143 else exit
    override fun destroy() { destroyed = true; if (!ignoresDestroy) dead = true }
    override fun destroyForcibly() { forced = true; dead = true }
    override fun isAlive() = !dead
}

/** Replays one process per start; remembers what it was started with. */
class FakeLauncher(private val make: (List<String>) -> FakeProcess) : ProcessLauncher {
    class Start(val argv: List<String>, val workDir: File, val env: Map<String, String>, val process: FakeProcess)

    val starts = ArrayList<Start>()
    var failWith: Exception? = null

    override fun start(argv: List<String>, workDir: File, env: Map<String, String>): RunningProcess {
        failWith?.let { throw it }
        val p = make(argv)
        starts.add(Start(argv, workDir, env, p))
        return p
    }
}

class FakeLocator(private val dir: File = File("/plugin/native"), private val missing: Set<EngineProgram> = emptySet()) : EngineLocator {
    override fun locate(program: EngineProgram): File? = if (program in missing) null else File(dir, program.fileName)
}

/** A stream that waits for another stream to be read first: reading both in sequence, stdout first, would hang. */
class GatedStream(private val data: ByteArray, private val waitFor: CountDownLatch?, private val release: CountDownLatch?) : InputStream() {
    private val inner = ByteArrayInputStream(data)
    private var gated = waitFor != null

    override fun read(): Int = throw UnsupportedOperationException()

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        release?.countDown()
        if (gated) {
            if (!waitFor!!.await(1, TimeUnit.SECONDS)) throw java.io.IOException("the other stream was not read at the same time")
            gated = false
        }
        return inner.read(b, off, len)
    }
}

/** Builds a runner over a launcher that serves the version probe with the recorded probe (version 0.2.4) and every other command with [serve]. */
fun runnerFor(
    work: File,
    onMain: Boolean = false,
    serve: (List<String>) -> FakeProcess,
): Pair<EngineRunner, FakeLauncher> {
    val launcher = FakeLauncher { argv ->
        if ("--static-probe" in argv) FakeProcess(stdout = Recorded.staticProbe024()) else serve(argv)
    }
    return EngineRunner(FakeLocator(), launcher, work, onMainThread = { onMain }) to launcher
}

internal fun jsonOf(line: String): Map<String, Any?> = Json.parseObject(line)
