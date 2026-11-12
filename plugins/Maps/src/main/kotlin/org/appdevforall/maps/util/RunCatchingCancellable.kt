package org.appdevforall.maps.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for blocks that suspend: it rethrows [CancellationException], so a cancelled
 * caller stops instead of reading the cancellation as an ordinary failure.
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
