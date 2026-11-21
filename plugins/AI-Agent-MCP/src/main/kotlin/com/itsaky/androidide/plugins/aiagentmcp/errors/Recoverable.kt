package com.itsaky.androidide.plugins.aiagentmcp.errors

import kotlin.coroutines.cancellation.CancellationException

/**
 * As [runCatching], but catching only what this plugin can recover from: an [Exception], or a
 * [LinkageError], which is how a host whose plugin-api differs from this plugin's fails a call
 * (AbstractMethodError, NoSuchMethodError, NoClassDefFoundError).
 *
 * Any other Error propagates, a [VirtualMachineError] (OutOfMemoryError, StackOverflowError) above
 * all: it means the process is failing, not one call, and the IDE's uncaught-exception handler has
 * to see it rather than this plugin quietly carrying on.
 *
 * So does a [CancellationException], although it is an Exception: it is how a coroutine learns it
 * was cancelled, and a coroutine that kept it as one more failure would carry on with work nobody
 * wants. No block passed here suspends today; this keeps the first one that does honest.
 *
 * @param block the work, usually one that reaches the host directly or through a listener.
 * @return its value, or the recoverable failure it threw.
 */
internal inline fun <T> runCatchingRecoverable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
} catch (e: LinkageError) {
    Result.failure(e)
}
