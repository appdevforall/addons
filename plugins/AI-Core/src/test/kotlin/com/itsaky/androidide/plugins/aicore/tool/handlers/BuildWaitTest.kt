package com.itsaky.androidide.plugins.aicore.tool.handlers

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** Unit tests for [awaitBuild], the wait shared by run_app, run_gradle_task and quick_build. */
class BuildWaitTest {

    @Test
    fun givenAFutureFailedWithoutACause_whenAwaited_thenTheFailureIsThrown() = runTest {
        val failure = CompletionException("host gave no cause", null)
        val future = CompletableFuture<String>().apply { completeExceptionally(failure) }

        val thrown = runCatching { awaitBuild("test_tool", future) {} }.exceptionOrNull()

        // Coroutines may rethrow a copy for its stack trace, so compare what the model would read.
        assertEquals(failure.toString(), thrown.toString())
    }

    @Test
    fun givenAWrappedFailure_whenAwaited_thenTheHostExceptionIsThrown() = runTest {
        val hostError = IllegalStateException("session gone")
        val future = CompletableFuture.failedFuture<String>(hostError).thenApply { it }

        val thrown = runCatching { awaitBuild("test_tool", future) {} }.exceptionOrNull()

        assertEquals(hostError.toString(), thrown.toString())
    }

}
