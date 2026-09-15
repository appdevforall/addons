package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.aicore.models.ToolResult

/**
 * Judges whether a run is still getting somewhere, from the tool-call batches it issues.
 * [AgentLoop] builds one per run and asks it about every batch before running it, so the loop
 * orchestrates turns and this decides what counts as progress.
 *
 * @param maxConsecutiveRepeats identical unsuccessful batches tolerated back to back.
 * @param maxTurnsWithoutProgress turns tolerated introducing no batch the run has not already run.
 */
internal class ToolCallProgressGuard(
    private val maxConsecutiveRepeats: Int,
    private val maxTurnsWithoutProgress: Int,
) {

    /** What [AgentLoop] should do with the batch just inspected. */
    enum class Verdict {
        /** Nothing seen so far rules this batch out; run it. */
        PROCEED,

        /** The batch that just succeeded was re-issued, which reads as the work being finished. */
        ASSUME_COMPLETE,

        /** The same unsuccessful batch, issued once too often. */
        REPEATED,

        /** Turn after turn with no action the run had not already taken. */
        CYCLING,
    }

    // Every signature this run has issued, so a rotation is visible and not just a repeat.
    private val seenSignatures = mutableSetOf<String>()
    private var previousSignature: String? = null
    private var consecutiveRepeats = 0
    private var turnsWithoutNewSignature = 0

    // Null until a batch has run: "no tools yet" and "the tools failed" end a run differently.
    private var previousBatchSucceeded: Boolean? = null

    /** Turns running that have introduced no batch the run had not already issued. */
    val staleTurns: Int get() = turnsWithoutNewSignature

    /** True once a batch has run and failed, which makes a prose reply an abandonment. */
    val lastBatchFailed: Boolean get() = previousBatchSucceeded == false

    /**
     * Inspects the batch a turn wants to run, before it runs.
     * @param calls the batch.
     * @return what the loop should do with it.
     */
    fun inspect(calls: List<ToolCall>): Verdict {
        val signature = signatureOf(calls)
        val verdict = verdictFor(signature)
        if (verdict == Verdict.PROCEED) previousSignature = signature
        return verdict
    }

    /**
     * Records how the batch just run turned out.
     * @param results the batch's results, as the loop received them.
     */
    fun recordResults(results: List<ToolResult>) {
        previousBatchSucceeded = results.isNotEmpty() && results.all { it.success }
    }

    private fun verdictFor(signature: String): Verdict {
        if (seenSignatures.add(signature)) {
            turnsWithoutNewSignature = 0
        } else {
            turnsWithoutNewSignature++
            // Ahead of the repeat checks: a run that has circled this long has not finished.
            if (turnsWithoutNewSignature >= maxTurnsWithoutProgress) return Verdict.CYCLING
        }
        if (signature != previousSignature) {
            consecutiveRepeats = 0
            return Verdict.PROCEED
        }
        if (previousBatchSucceeded == true) return Verdict.ASSUME_COMPLETE
        consecutiveRepeats++
        return if (consecutiveRepeats >= maxConsecutiveRepeats) Verdict.REPEATED else Verdict.PROCEED
    }

    /**
     * Builds a stable, order-sensitive fingerprint of a batch, so two turns are the same action
     * exactly when they call the same tools with the same arguments.
     * @param calls the batch to fingerprint.
     * @return the fingerprint string.
     */
    private fun signatureOf(calls: List<ToolCall>): String =
        calls.joinToString("|") { call ->
            call.name + "(" + call.args.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" } + ")"
        }
}
