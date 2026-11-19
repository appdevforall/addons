package org.appdevforall.maps.util

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.appdevforall.maps.data.RegionCache
import org.appdevforall.maps.data.RegionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RunCatchingCancellableTest {

    @Test
    fun `a value and an ordinary failure come back as a Result`() {
        assertEquals(Result.success(3), runCatchingCancellable { 3 })
        val failure = runCatchingCancellable { throw IOException("disk") }
        assertEquals("disk", failure.exceptionOrNull()?.message)
    }

    @Test
    fun `a cancellation is rethrown, not returned`() {
        val thrown = runCatching { runCatchingCancellable { throw CancellationException("stop") } }
            .exceptionOrNull()
        assertTrue("got $thrown", thrown is CancellationException)
    }

    /** Runs [block] in a job that is already cancelled and returns what it produced. */
    private fun <T> inCancelledJob(block: suspend () -> T): Result<T> {
        var outcome: Result<T>? = null
        runBlocking {
            launch {
                coroutineContext.job.cancel()
                outcome = runCatching { block() }
            }.join()
        }
        return outcome!!
    }

    @Test
    fun `listing the cache in a cancelled job cancels instead of reporting no regions`() {
        val outcome = inCancelledJob { RegionCache.list() }
        assertTrue("got $outcome", outcome.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `deleting a region in a cancelled job cancels instead of reporting failure`() {
        val info = RegionInfo(
            regionId = "dhaka", displayName = "Dhaka", sizeBytes = 0L, downloadedAt = null,
            lastUsedAt = null, source = "internet", directory = File("dhaka"),
        )
        val outcome = inCancelledJob { RegionCache.delete(info) }
        assertTrue("got $outcome", outcome.exceptionOrNull() is CancellationException)
    }
}
