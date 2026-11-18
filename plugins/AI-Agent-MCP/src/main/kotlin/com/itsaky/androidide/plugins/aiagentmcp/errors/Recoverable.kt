package com.itsaky.androidide.plugins.aiagentmcp.errors

/**
 * As [runCatching], but catching only what this plugin can recover from: an [Exception], or a
 * [LinkageError], which is how a host whose plugin-api differs from this plugin's fails a call
 * (AbstractMethodError, NoSuchMethodError, NoClassDefFoundError).
 *
 * Any other Error propagates, a [VirtualMachineError] (OutOfMemoryError, StackOverflowError) above
 * all: it means the process is failing, not one call, and the IDE's uncaught-exception handler has
 * to see it rather than this plugin quietly carrying on.
 *
 * @param block the work, usually one that reaches the host directly or through a listener.
 * @return its value, or the recoverable failure it threw.
 */
internal inline fun <T> runCatchingRecoverable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: Exception) {
    Result.failure(e)
} catch (e: LinkageError) {
    Result.failure(e)
}
