package com.itsaky.androidide.plugins.aiagentlocal.device.engine

/** What a command asks of the engine; the runner needs it to decide how to read the result. */
enum class CommandKind { STATIC_PROBE, GPU_PROBE, PIN_PROBE, ROW }

/**
 * One command line of the engine: a program and an argument **list** (never a shell string). Instances are made only by
 * [EngineCommands]; callers cannot pass free-form arguments.
 *
 * [tag] is the row tag the process is started with (null for the three probes). [maxSeconds] is the value of the
 * engine's own `--max-seconds`; the runner kills the process [EngineRunner.GRACE_SECONDS] seconds after it.
 */
class EngineCommand internal constructor(
    val kind: CommandKind,
    val program: EngineProgram,
    val args: List<String>,
    val tag: String?,
    val maxSeconds: Int,
) {
    override fun toString(): String = "EngineCommand(${program.fileName} ${args.joinToString(" ")})"
}

/**
 * Typed builders for the command lines of `part1-engine/ENGINE.md`: the three probes and the T1 rows. Arguments are
 * validated (non-negative CPU numbers, no duplicate CPU, positive memory limit) and the text of every option is fixed
 * here, in the order the engine document writes it. `L` of the document is [memLimitMib].
 */
object EngineCommands {

    /** `--max-seconds` of a T1 CPU row (the kit's timeout, ENGINE.md "T1 rows"). */
    const val CPU_ROW_MAX_SECONDS = 120

    /** `--max-seconds` of the T1 GPU row. */
    const val GPU_ROW_MAX_SECONDS = 180

    /** `--max-seconds` of the static probe (it takes under 0.1 s on the measured phones). */
    const val STATIC_PROBE_MAX_SECONDS = 2

    /** `--max-seconds` of the GPU probe (the engine finishes within 3 s even when four OpenCL candidates hang). */
    const val GPU_PROBE_MAX_SECONDS = 3

    /** `libpart1_membw.so --static-probe --json` (no gate, nothing measured). */
    fun staticProbe(): EngineCommand = EngineCommand(
        CommandKind.STATIC_PROBE, EngineProgram.MEMBW,
        listOf("--static-probe", "--json", "--max-seconds", STATIC_PROBE_MAX_SECONDS.toString()), null, STATIC_PROBE_MAX_SECONDS,
    )

    /** `libpart1_gpubw.so --probe --json` (Vulkan facts and the OpenCL child result). */
    fun gpuProbe(): EngineCommand = EngineCommand(
        CommandKind.GPU_PROBE, EngineProgram.GPUBW,
        listOf("--probe", "--json", "--max-seconds", GPU_PROBE_MAX_SECONDS.toString()), null, GPU_PROBE_MAX_SECONDS,
    )

    /**
     * `libpart1_membw.so --pin-probe --cores <list> --spin-ms <spinMs> --json`. The spin budget is shared by the CPUs; a CPU that
     * does not pin costs up to 5 s, so the time limit is `12 + 5 * CPUs` seconds.
     */
    fun pinProbe(cpus: List<Int>, spinMs: Int = 2000): EngineCommand {
        val list = cpuList(cpus, 1)
        require(spinMs in 1..60_000) { "spinMs must be 1..60000" }
        val limit = 12 + 5 * cpus.size
        return EngineCommand(
            CommandKind.PIN_PROBE, EngineProgram.MEMBW,
            listOf("--pin-probe", "--cores", list.joinToString(","), "--spin-ms", spinMs.toString(), "--json", "--max-seconds", limit.toString()),
            null, limit,
        )
    }

    /** `C2-read-k<cluster>`: one thread on [cpu], the single-core read of cluster [cluster] (tier T1). */
    fun c2Read(cluster: Int, cpu: Int, memLimitMib: Long): EngineCommand {
        require(cluster >= 0) { "cluster must not be negative" }
        val tag = "C2-read-k$cluster"
        return cpuRow(tag, listOf("--kernel", "K02", "--threads", "1", "--cores", cpuList(listOf(cpu), 1).joinToString("+"),
            "--warmup-s", "3", "--window-s", "6", "--runs", "1", "--tag", tag), memLimitMib)
    }

    /** `C3-read-2fast`: two threads on the CPUs [a] and [b]. */
    fun c3Read2Fast(a: Int, b: Int, memLimitMib: Long): EngineCommand {
        val tag = "C3-read-2fast"
        return cpuRow(tag, listOf("--kernel", "K02", "--threads", "2", "--cores", cpuList(listOf(a, b), 2).joinToString("+"),
            "--warmup-s", "3", "--window-s", "6", "--runs", "1", "--tag", tag), memLimitMib)
    }

    /** `C4-read-all`: one thread on each of the pinnable [cpus]. */
    fun c4ReadAll(cpus: List<Int>, memLimitMib: Long): EngineCommand {
        val tag = "C4-read-all"
        val list = cpuList(cpus, 1)
        return cpuRow(tag, listOf("--kernel", "K02", "--threads", list.size.toString(), "--cores", list.joinToString("+"),
            "--warmup-s", "3", "--window-s", "6", "--runs", "1", "--tag", tag), memLimitMib)
    }

    /** `C7-gpu-alone`: the GPU read with no CPU stream (tier T1). */
    fun c7GpuAlone(memLimitMib: Long): EngineCommand {
        require(memLimitMib > 0) { "memLimitMib must be positive" }
        val tag = "C7-gpu-alone"
        return EngineCommand(
            CommandKind.ROW, EngineProgram.GPUBW,
            listOf("--modes", "0", "--per-wg", "4096", "--tag", tag) + tail(memLimitMib, GPU_ROW_MAX_SECONDS), tag, GPU_ROW_MAX_SECONDS,
        )
    }

    private fun cpuRow(tag: String, head: List<String>, memLimitMib: Long): EngineCommand {
        require(memLimitMib > 0) { "memLimitMib must be positive" }
        return EngineCommand(CommandKind.ROW, EngineProgram.MEMBW, head + tail(memLimitMib, CPU_ROW_MAX_SECONDS), tag, CPU_ROW_MAX_SECONDS)
    }

    /** The common tail of a T1 row: `--json --tier T1 --mem-limit-mib L --fit-buffer --max-seconds S`. */
    private fun tail(memLimitMib: Long, maxSeconds: Int): List<String> =
        listOf("--json", "--tier", "T1", "--mem-limit-mib", memLimitMib.toString(), "--fit-buffer", "--max-seconds", maxSeconds.toString())

    private fun cpuList(cpus: List<Int>, min: Int): List<Int> {
        require(cpus.size >= min) { "at least $min CPU" }
        require(cpus.all { it in 0..1023 }) { "CPU numbers are 0..1023" }
        require(cpus.toSet().size == cpus.size) { "a CPU twice in the list" }
        return cpus
    }
}
