package com.itsaky.androidide.plugins.aiagentlocal.model

/**
 * Decides whether a selected model is worth warning about, from its estimate and the free RAM.
 *
 * Pure, so every boundary is unit-testable. It never refuses a model: the caller asks the user,
 * who may proceed anyway (ADFA-1798).
 */
object ModelMemoryGate {

    /** How badly the device is short, which is the difference between "may" and "will" fail. */
    enum class Severity {
        /** The runtime allocations fit, but not alongside the weights: expect thrashing. */
        TIGHT,

        /** Not even the KV cache and compute buffers fit: expect a quick, hard failure. */
        INSUFFICIENT,
    }

    sealed interface Verdict {

        /** Everything fits; load without interrupting the user. */
        data object Safe : Verdict

        /** Nothing to judge — the size or the free RAM was unreadable. Fails open: no warning. */
        data object Unknown : Verdict

        /**
         * @param estimate what the model is expected to need
         * @param availableBytes free RAM at the moment of the check
         * @param severity how short the device is
         */
        data class Risky(
            val estimate: MemoryEstimate,
            val availableBytes: Long,
            val severity: Severity,
        ) : Verdict
    }

    /**
     * @param estimate the model's expected cost, or null when it could not be estimated
     * @param availableBytes free RAM, or null when it could not be read
     * @return the verdict for this model on this device, right now
     */
    fun evaluate(estimate: MemoryEstimate?, availableBytes: Long?): Verdict {
        if (estimate == null || availableBytes == null) return Verdict.Unknown
        return when {
            availableBytes >= estimate.totalBytes -> Verdict.Safe
            availableBytes >= estimate.runBytes -> Verdict.Risky(estimate, availableBytes, Severity.TIGHT)
            else -> Verdict.Risky(estimate, availableBytes, Severity.INSUFFICIENT)
        }
    }
}
