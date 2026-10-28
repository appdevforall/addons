package com.itsaky.androidide.plugins.aiagentlocal.backend

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * How many threads the native engine runs on.
 *
 * @property generation threads for writing the reply, which is bound by memory bandwidth and slows
 *   once the slow cores join.
 * @property batch threads for reading the prompt, which is compute-bound and gains from them.
 */
internal data class ThreadPlan(val generation: Int, val batch: Int)

/**
 * Chooses the engine's thread counts from the phone's core layout (ADFA-6757).
 *
 * Left alone, the engine runs both phases on `min(8, cores - 2)` threads. On a phone with slow
 * efficiency cores that puts generation threads on them, and the whole step waits for the slowest:
 * a Pixel 7 wrote 14.9 tokens/s on 6 threads and 18.3 on 4, but read a long prompt faster on 6.
 * So generation gets as many threads as there are fast cores (the count only; where the scheduler puts
 * them is its call) and prompt reading keeps the engine's own count.
 *
 * Everything it cannot tell stays as the engine would have chosen: a phone whose cores are alike,
 * or whose capacities cannot be read, gets [ThreadPlan.generation] equal to [ThreadPlan.batch].
 */
internal object InferenceThreads {

    /** The engine's own ceiling, which [plan] reproduces for [ThreadPlan.batch]. */
    private const val MAX_THREADS = 8

    /** Cores the engine leaves free for the UI and the OS. */
    private const val RESERVED_CORES = 2

    /**
     * A core below this share of the fastest core's capacity is an efficiency core. Measured on a
     * Pixel 6 Pro (2 X1, 2 A76, 4 A55): the A55s sit at 0.16 and the A76s at 0.49, so a cut-off of
     * one half would drop the mid cores too. Phones whose small cores sit above this keep all of them.
     */
    private const val SLOW_CORE_SHARE = 0.3

    private val CPU_DIR = Regex("cpu\\d+")

    /**
     * @param capacities each core's `cpu_capacity`; empty when unreadable.
     * @param onlineCores the cores the OS is running, which is what the engine sizes itself from.
     * @return the counts to give the engine; generation never exceeds batch and neither is below 1.
     */
    fun plan(capacities: List<Int>, onlineCores: Int): ThreadPlan {
        val batch = (onlineCores - RESERVED_CORES).coerceIn(1, MAX_THREADS)
        val fastest = capacities.maxOrNull() ?: return ThreadPlan(batch, batch)
        if (fastest <= 0) return ThreadPlan(batch, batch)
        val fast = capacities.count { it >= fastest * SLOW_CORE_SHARE }
        return ThreadPlan(generation = fast.coerceIn(1, batch), batch = batch)
    }

    /**
     * Reads the layout and plans for it. Never throws: a kernel that hides the files, or a reader
     * the sandbox refuses, leaves the engine's own choice.
     *
     * @param cpuRoot where the kernel lists its cores; a parameter so a test can lay one out.
     * @param onlineCores the cores the OS is running.
     */
    fun read(
        cpuRoot: File = File("/sys/devices/system/cpu"),
        onlineCores: Int = onlineCores(),
    ): ThreadPlan = plan(capacitiesIn(cpuRoot), onlineCores)

    /**
     * The cores the OS is running now, counted as the engine counts them (`_SC_NPROCESSORS_ONLN`).
     * Not `availableProcessors()`, which on Android reports the configured cores, offline ones too.
     */
    private fun onlineCores(): Int = try {
        Os.sysconf(OsConstants._SC_NPROCESSORS_ONLN).toInt()
    } catch (_: Throwable) {
        Runtime.getRuntime().availableProcessors()
    }

    /**
     * `cpu_capacity` is 1024 for the fastest core class and lower for the rest. Offline cores are
     * listed too, which is right: this is the phone's layout, not what is awake at this moment.
     */
    private fun capacitiesIn(cpuRoot: File): List<Int> = try {
        cpuRoot.listFiles { file -> CPU_DIR.matches(file.name) }.orEmpty().mapNotNull { core ->
            // Per core, so one unreadable file leaves that core out instead of the whole layout.
            try {
                File(core, "cpu_capacity").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            } catch (_: Exception) {
                null
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
