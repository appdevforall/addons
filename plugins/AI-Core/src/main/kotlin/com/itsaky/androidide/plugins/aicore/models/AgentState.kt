package com.itsaky.androidide.plugins.aicore.models

/**
 * Represents the current state of the AI agent.
 */
sealed class AgentState {
    /**
     * Agent is idle and ready to accept new messages.
     */
    object Idle : AgentState()

    /**
     * Agent is initializing (loading model, preparing context, etc.).
     */
    data class Initializing(val message: String) : AgentState()

    /**
     * Agent is thinking/reasoning about the user's request.
     */
    data class Thinking(val thought: String) : AgentState()

    /**
     * Agent is executing a tool or action.
     */
    data class Executing(
        val currentStepIndex: Int,
        val totalSteps: Int,
        val description: String,
        val startTime: Long = System.currentTimeMillis(),
        val elapsedMillis: Long = 0
    ) : AgentState() {
        /** Step number as the status line counts it, from 1. */
        val stepNumber: Int
            get() = currentStepIndex + 1

        /**
         * Projected duration of the whole run, from the average time per step so far. The words
         * around these figures are resources, so the rendering itself belongs to the UI layer.
         */
        val estimatedTotalMillis: Long
            get() {
                // A device clock change can put the elapsed time behind the start.
                val elapsed = elapsedMillis.coerceAtLeast(0)
                return (elapsed / stepNumber) * totalSteps
            }
    }

    /**
     * Agent is processing/generating a response.
     */
    data class Processing(val message: String) : AgentState()

    /**
     * A cancellation has been requested.
     */
    object Cancelling : AgentState()

    /**
     * Agent encountered an error.
     */
    data class Error(val message: String) : AgentState()
}

/**
 * Short name for the trace log: the state and what it is doing, without a data class's field dump
 * and without the error text, which is already in the transcript. Logcat only — nothing here
 * reaches the UI, which renders each state from string resources.
 */
val AgentState.traceLabel: String
    get() = when (this) {
        is AgentState.Idle -> "Idle"
        is AgentState.Initializing -> "Initializing"
        is AgentState.Thinking -> "Thinking"
        is AgentState.Executing -> "Executing(step ${currentStepIndex + 1}/$totalSteps $description)"
        is AgentState.Processing -> "Processing"
        is AgentState.Cancelling -> "Cancelling"
        is AgentState.Error -> "Error"
    }

/**
 * True while a run is in flight, which is what the composer keys its Stop control off. One
 * definition so the UI cannot drift from it a state at a time.
 */
val AgentState.isRunning: Boolean
    get() = this is AgentState.Executing || this is AgentState.Processing
