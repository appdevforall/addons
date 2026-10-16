package com.itsaky.androidide.plugins.aicore.tool

/**
 * How many characters one tool result may hand the model. The loop cuts a longer result, so a
 * handler that windows or pages its own output sizes it from here rather than its own constant.
 */
fun interface ToolOutputBudget {

    /** The per-result cap in characters, message and data together. */
    fun chars(): Int

    /** The room left for a result's data once its one-line message is set beside it. */
    fun dataChars(): Int = chars() - MESSAGE_ROOM

    companion object {
        /** Sized for a local model's context: AI Agent Local defaults to 4,096 tokens. */
        const val DEFAULT_CHARS = 4000

        /** What a handler sets aside for its message when it windows its data. */
        const val MESSAGE_ROOM = 100

        /** The budget every backend gets until one can report its context size. */
        val DEFAULT = ToolOutputBudget { DEFAULT_CHARS }
    }
}
