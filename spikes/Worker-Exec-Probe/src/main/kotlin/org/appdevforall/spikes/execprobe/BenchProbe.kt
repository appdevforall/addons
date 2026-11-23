package org.appdevforall.spikes.execprobe

import android.net.LocalServerSocket
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import dalvik.system.BaseDexClassLoader
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Called by bench_jni.cpp once per token; signature `([BJ)V`. */
interface TokenCallback {
    fun onToken(piece: ByteArray, tNs: Long)
}

/** The in-process half of spike 3: `libcogo_bench_jni.so`, loaded into CoGo's own process. */
object BenchJni {
    @Volatile private var loaded = false

    fun ensureLoaded() {
        if (!loaded) {
            System.loadLibrary("cogo_bench_jni")
            loaded = true
        }
    }

    @JvmStatic
    external fun run(path: String, nCtx: Int, nThreads: Int, prompt: String, nPredict: Int, callback: TokenCallback?): String
}

/**
 * ADFA-7281 spike 3: what does running llama.cpp in the worker cost, against running it
 * in-process as AI-Agent-Local does today?
 *
 * Both sides run the same bench_core.cpp against the same production llama.cpp libraries (copied
 * from AI-Agent-Local's AAR), with the same model, prompt, context, threads and greedy sampling.
 * Three modes, repeated [ROUNDS] times after one warm-up:
 *  - `worker+stream`: spawn the worker, send the model fd, stream every token back over the socket
 *  - `worker`:        the same, but no per-token traffic (isolates the IPC cost)
 *  - `in-process`:    bench_core over JNI with a per-token callback, as LLamaAndroid works today
 */
internal class BenchProbe(private val ctx: PluginContext) {

    private val out = StringBuilder()
    private fun line(s: String) { out.append(s).append('\n') }

    private class Run(
        val mode: String,
        val stats: Map<String, String>,
        val wallMs: Double,
        val firstTokenWallMs: Double?,
        val spawnToHelloMs: Double?,
        val ipcLatUs: List<Double>,
        val ideRssDuringKb: Long?,
        val spawnerMask: String? = null,
        val inheritedMask: String? = null,
    ) {
        var startTempC: Double? = null
    }

    /**
     * @param inProcess also run the in-process mode. False runs the worker modes alone, which
     *   keeps llama.cpp (and its OpenMP runtime) out of the IDE process entirely.
     */
    fun run(model: File, inProcess: Boolean = true): String {
        val nativeDir = (javaClass.classLoader as BaseDexClassLoader).findLibrary("cogo_bench_worker")?.let { File(it).parentFile }
            ?: return "FAIL  bench worker not extracted"
        val worker = File(nativeDir, "libcogo_bench_worker.so")
        line("model ${model.name} (${model.length() / 1_048_576} MiB), n_ctx $N_CTX, n_predict $N_PREDICT, greedy")
        line("IDE VmRSS before: ${vmRssKb()} kB")

        line("spawning thread affinity: ${threadMask()}")
        runWorker(worker, nativeDir, model, stream = true) // warm-up: page cache, CPU governor
        val runs = mutableListOf<Run>()
        // The phone throttles as it heats up, so a fixed order would favour whichever mode runs
        // first in each round. Rotate the order every round instead.
        val modes = mutableListOf<() -> Run>(
            { runWorker(worker, nativeDir, model, stream = false) },
            { runWorker(worker, nativeDir, model, stream = false, env = mapOf("OMP_WAIT_POLICY" to "active")) },
            { runWorker(worker, nativeDir, model, stream = false, env = mapOf("KMP_USE_YIELD" to "0")) },
        )
        if (inProcess) modes += { runInProcess(model) }
        repeat(ROUNDS) { round ->
            for (i in modes.indices) {
                val temp = maxThermalC()
                runs += modes[(i + round) % modes.size]().also { it.startTempC = temp }
            }
        }
        line("spawning thread affinity after: ${threadMask()}")
        line("IDE VmRSS after: ${vmRssKb()} kB")
        line("")

        for (r in runs) {
            val s = r.stats
            if (s["ok"] != "true") {
                line("%-14s FAIL %s".format(r.mode, s["error"] ?: s))
                continue
            }
            line(
                "%-32s load %6s ms  ctx %5s ms  prompt %3s tok %6s ms  first %6s ms  gen %3s tok %6s tok/s  wall %7.1f ms%s".format(
                    r.mode, s["load_ms"], s["ctx_ms"], s["prompt_tokens"], s["prompt_ms"], s["first_token_ms"],
                    s["gen_tokens"], s["tok_per_s"], r.wallMs,
                    (r.spawnToHelloMs?.let { "  spawn->hello %.1f ms".format(it) } ?: "") +
                        (r.firstTokenWallMs?.let { "  first token (wall) %.1f ms".format(it) } ?: "") +
                        (r.startTempC?.let { "  start %.1f C".format(it) } ?: "") +
                        "  csw vol ${s["gen_vcsw"]} invol ${s["gen_nvcsw"]}" + (s["exit_hang"]?.let { "  EXIT HANG: $it" } ?: ""),
                ),
            )
        }
        line("")
        for (mode in runs.map { it.mode }.distinct()) {
            val rs = runs.filter { it.mode == mode }
            val tps = rs.mapNotNull { it.stats["tok_per_s"]?.toDoubleOrNull() }.sorted()
            val load = rs.mapNotNull { it.stats["load_ms"]?.toDoubleOrNull() }.sorted()
            val wall = rs.map { it.wallMs }.sorted()
            if (rs.isEmpty()) continue
            val first = rs.mapNotNull { it.firstTokenWallMs }.sorted()
            val vcsw = rs.mapNotNull { it.stats["gen_vcsw"]?.toLongOrNull() }.sorted()
            line(
                "%-32s median of %d: load %.1f ms, gen %.2f tok/s (range %.1f-%.1f), wall %.1f ms%s".format(
                    mode, rs.size, load.median(), tps.median(), tps.first(), tps.last(), wall.median(),
                    (if (first.isEmpty()) "" else ", first token (wall) %.1f ms".format(first.median())) +
                        (if (vcsw.isEmpty()) "" else ", voluntary switches during gen %d".format(vcsw[vcsw.size / 2])),
                ),
            )
        }
        val lat = runs.flatMap { it.ipcLatUs }.sorted()
        if (lat.isNotEmpty()) {
            line(
                "token delivery latency (native timestamp -> plugin, all streamed runs, %d tokens): median %.0f us, p95 %.0f us, max %.0f us".format(
                    lat.size, lat.median(), lat[(lat.size * 95 / 100).coerceAtMost(lat.size - 1)], lat.last(),
                ),
            )
        }
        for (mode in listOf("worker+stream", "in-process")) {
            val l = runs.filter { it.mode == mode }.flatMap { it.ipcLatUs }.sorted()
            if (l.isNotEmpty()) line("  $mode: median %.0f us, p95 %.0f us".format(l.median(), l[(l.size * 95 / 100).coerceAtMost(l.size - 1)]))
        }
        runs.firstOrNull { it.mode == "worker" }?.stats?.get("vm_hwm_kb")?.let { line("worker peak RSS (VmHWM): $it kB, all of it outside the IDE process") }
        runs.firstOrNull { it.mode == "in-process" }?.ideRssDuringKb?.let { line("IDE VmRSS while generating in-process: $it kB") }
        val hashes = runs.mapNotNull { it.stats["text_hash"] }.toSet()
        line(if (hashes.size == 1) "PASS  all ${runs.size} runs produced identical text (hash ${hashes.single()})" else "FAIL  outputs differ: $hashes")
        return out.toString()
    }

    private fun runWorker(
        worker: File, nativeDir: File, model: File, stream: Boolean,
        resetAffinity: Boolean = false, env: Map<String, String> = emptyMap(),
    ): Run {
        val mode = (if (resetAffinity) "worker+reset" else if (stream) "worker+stream" else "worker") +
            env.entries.joinToString("") { " ${it.key}=${it.value}" }
        val spawnerMask = threadMask()
        var inheritedMask: String? = null
        val name = "cogo-bench-" + UUID.randomUUID()
        val server = LocalServerSocket(name)
        val pfd = ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY)
        val t0 = System.nanoTime()
        val pb = ProcessBuilder(worker.absolutePath, "--bench", name)
        pb.environment()["LD_LIBRARY_PATH"] = nativeDir.absolutePath
        if (resetAffinity) pb.environment()["COGO_RESET_AFFINITY"] = "1"
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        Log.i(ExecProbePlugin.TAG, "bench: run start: $mode")
        val proc = pb.start()
        // Hard deadline. A worker that stops answering (a wedged ggml, a spinning OpenMP team) is
        // SIGKILLed by the pid from its hello: Process.destroyForcibly() only sends SIGTERM on Android.
        // Killing it closes the socket, which ends the read loop below.
        val workerPidRef = java.util.concurrent.atomic.AtomicInteger(-1)
        val finished = java.util.concurrent.CountDownLatch(1)
        var timedOut = false
        Thread {
            if (!finished.await(RUN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                timedOut = true
                Log.w(ExecProbePlugin.TAG, "bench: run TIMEOUT after $RUN_TIMEOUT_MS ms: $mode (pid ${workerPidRef.get()})")
                workerPidRef.get().let { if (it > 0) runCatching { Os.kill(it, OsConstants.SIGKILL) } else proc.destroyForcibly() }
                runCatching { server.close() }
            }
        }.apply { isDaemon = true; start() }
        // llama.cpp logs to stderr, merged into stdout here; drain it so the pipe never fills.
        val log = StringBuffer()
        val drain = Thread {
            runCatching { proc.inputStream.bufferedReader().forEachLine { log.append(it).append('\n') } }
        }.apply { isDaemon = true; start() }
        // If the worker dies before it connects, accept() would block forever: unblock it.
        Thread {
            proc.waitFor()
            Thread.sleep(500)
            runCatching { server.close() }
        }.apply { isDaemon = true; start() }
        val lat = ArrayList<Double>(N_PREDICT)
        var firstTokenWall: Double? = null
        var spawnToHello: Double? = null
        var stats: Map<String, String> = emptyMap()
        try {
            server.accept().use { s ->
                val input = BufferedInputStream(s.inputStream, 1 shl 16)
                val hello = readLine(input)
                spawnToHello = (System.nanoTime() - t0) / 1e6
                check(hello?.startsWith("H ") == true) { "bad hello '$hello'" }
                workerPidRef.set(hello!!.split(' ')[1].toInt())
                inheritedMask = readLine(input)?.removePrefix("A ")

                val prompt = PROMPT.toByteArray()
                s.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                s.outputStream.write("$N_CTX 0 $N_PREDICT ${if (stream) 1 else 0} ${prompt.size}\n".toByteArray() + prompt)
                s.outputStream.flush()
                pfd.close()

                while (true) {
                    val l = readLine(input) ?: break
                    if (l.startsWith("T ")) {
                        val now = System.nanoTime()
                        val parts = l.split(' ')
                        val len = parts[2].toInt()
                        readExactly(input, len)
                        lat += (now - parts[1].toLong()) / 1e3
                        if (firstTokenWall == null) firstTokenWall = (now - t0) / 1e6
                    } else if (l.startsWith("S ")) {
                        stats = parseFlatJson(l.substring(2))
                        break
                    }
                }
            }
        } catch (e: Exception) {
            drain.join(2000)
            stats = mapOf("error" to "${e.javaClass.simpleName}: ${e.message}; worker exit ${runCatching { proc.exitValue() }.getOrNull()}; log tail: ${log.takeLast(600)}")
        } finally {
            runCatching { server.close() }
            runCatching { pfd.close() }
        }
        val wall = (System.nanoTime() - t0) / 1e6
        // The worker has nothing left to do once it has sent its stats. One that does not exit
        // promptly is itself a finding (an OpenMP team that keeps spinning at exit, say).
        var exitHangMs: Double? = null
        if (!proc.waitFor(EXIT_GRACE_MS, TimeUnit.MILLISECONDS)) {
            val t1 = System.nanoTime()
            workerPidRef.get().let { if (it > 0) runCatching { Os.kill(it, OsConstants.SIGKILL) } }
            proc.destroyForcibly()
            proc.waitFor(5, TimeUnit.SECONDS)
            exitHangMs = EXIT_GRACE_MS + (System.nanoTime() - t1) / 1e6
        }
        finished.countDown()
        drain.join(1000)
        if (timedOut) stats = mapOf("error" to "TIMEOUT after $RUN_TIMEOUT_MS ms, SIGKILLed; log tail: ${log.takeLast(400)}")
        else if (exitHangMs != null && stats["ok"] == "true") stats = stats + ("exit_hang" to "did not exit within $EXIT_GRACE_MS ms of its stats; SIGKILLed")
        Log.i(ExecProbePlugin.TAG, "bench: run end: $mode ${if (timedOut) "TIMEOUT" else stats["tok_per_s"] + " tok/s"}${stats["exit_hang"]?.let { ", $it" } ?: ""}")
        return Run(mode, stats, wall, firstTokenWall, spawnToHello, lat, null, spawnerMask, inheritedMask)
    }

    private fun runInProcess(model: File): Run {
        Log.i(ExecProbePlugin.TAG, "bench: run start: in-process")
        BenchJni.ensureLoaded()
        val lat = ArrayList<Double>(N_PREDICT)
        var rssDuring: Long? = null
        val t0 = System.nanoTime()
        var firstTokenWall: Double? = null
        val json = BenchJni.run(model.absolutePath, N_CTX, 0, PROMPT, N_PREDICT, object : TokenCallback {
            override fun onToken(piece: ByteArray, tNs: Long) {
                val now = System.nanoTime()
                lat += (now - tNs) / 1e3
                if (firstTokenWall == null) {
                    firstTokenWall = (now - t0) / 1e6
                    rssDuring = vmRssKb()
                }
            }
        })
        val wall = (System.nanoTime() - t0) / 1e6
        return Run("in-process", parseFlatJson(json), wall, firstTokenWall, null, lat, rssDuring)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            sb.append(c.toChar())
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) error("socket closed mid-token")
            off += r
        }
        return buf
    }

    /** bench_core's stats are one flat JSON object of numbers, booleans and simple strings. */
    private fun parseFlatJson(s: String): Map<String, String> =
        Regex("\"(\\w+)\":(\"[^\"]*\"|[^,}]+)").findAll(s).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    /** The hottest thermal zone, in degrees C. */
    private fun maxThermalC(): Double? = runCatching {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }.orEmpty()
            .mapNotNull { runCatching { File(it, "temp").readText().trim().toDouble() / 1000.0 }.getOrNull() }
            .filter { it in 1.0..150.0 }.maxOrNull()
    }.getOrNull()

    /** The calling thread's Cpus_allowed_list. */
    private fun threadMask(): String = runCatching {
        File("/proc/self/task/${android.os.Process.myTid()}/status").readLines()
            .first { it.startsWith("Cpus_allowed_list:") }.substringAfter(':').trim()
    }.getOrElse { "?" }

    private fun vmRssKb(): Long? = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong()
    }.getOrNull()

    private fun List<Double>.median(): Double = if (isEmpty()) Double.NaN else this[size / 2]

    companion object {
        const val ROUNDS = 4
        const val RUN_TIMEOUT_MS = 90_000L
        const val EXIT_GRACE_MS = 5_000L
        const val N_CTX = 1024
        const val N_PREDICT = 128
        const val PROMPT =
            "// Kotlin: a function that returns the n-th Fibonacci number iteratively, with a KDoc comment\n" +
                "/**\n * Returns the n-th Fibonacci number.\n *\n * @param n the index, starting at 0\n */\nfun fibonacci(n: Int): Long {\n"
    }
}
