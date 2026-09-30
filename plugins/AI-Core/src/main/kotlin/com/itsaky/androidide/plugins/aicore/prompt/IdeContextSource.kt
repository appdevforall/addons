package com.itsaky.androidide.plugins.aicore.prompt

/**
 * Supplies the [IdeContext] a prompt is built around.
 *
 * An interface so [SystemPromptFactory] depends on the answer rather than on the editor service
 * that produces it, which is what lets the assembly be tested without a device.
 */
fun interface IdeContextSource {

    /**
     * Reads the context.
     *
     * @return what the IDE has open, with whatever parts of it could be determined.
     */
    suspend fun read(): IdeContext
}
