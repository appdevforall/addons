package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Shared by every handler that waits on a build, so run_app, run_gradle_task and quick_build give up together.

/** How long to wait for a build to finish before reporting it still running. */
internal const val BUILD_TIMEOUT_MS = 10 * 60 * 1000L

/**
 * How often to log that the wait is still alive. A build can hold the agent for ten minutes, and
 * without a heartbeat that stretch of logcat is indistinguishable from a hung agent.
 */
internal const val BUILD_PROGRESS_LOG_INTERVAL_MS = 30 * 1000L

/**
 * Waits up to [BUILD_TIMEOUT_MS] for the build behind [future], logging a heartbeat as [toolName].
 * Stop pressed while the build runs calls [cancel]: the agent started the build, so the agent ends it.
 * A [cancel] that throws is logged, never thrown over the cancellation.
 * @return the build's result, or null when it did not finish in time and may still be running.
 */
internal suspend fun <T> awaitBuild(toolName: String, future: CompletableFuture<T>, cancel: () -> Unit): T? {
    val startMs = System.currentTimeMillis()
    return try {
        withTimeoutOrNull(BUILD_TIMEOUT_MS) {
            coroutineScope {
                val heartbeat = launch { logProgressUntilCancelled(toolName, startMs) }
                try {
                    awaitResult(future)
                } finally {
                    heartbeat.cancel()
                }
            }
        }
    } catch (ce: CancellationException) {
        if (!future.isDone) {
            AgentTrace.stage("BUILD", "$toolName stopped; cancelling the build")
            // A throw here would replace the cancellation, and Stop would read as a build failure.
            runCatching(cancel).onFailure { AgentTrace.refusal("BUILD", "$toolName cancel failed", it.toString()) }
        }
        throw ce
    }
}

/** Logs one line every [BUILD_PROGRESS_LOG_INTERVAL_MS] while the wait lasts. */
private suspend fun logProgressUntilCancelled(toolName: String, startMs: Long) {
    while (true) {
        delay(BUILD_PROGRESS_LOG_INTERVAL_MS)
        val seconds = (System.currentTimeMillis() - startMs) / 1000
        AgentTrace.detail("BUILD", "$toolName still waiting elapsed=${seconds}s")
    }
}

/**
 * Suspends until [future] completes. Unlike `CompletableFuture.await`, a cancelled wait leaves
 * the future alone, so the caller can still tell whether the build was running when it stopped.
 */
private suspend fun <T> awaitResult(future: CompletableFuture<T>): T =
    suspendCancellableCoroutine { continuation ->
        future.whenComplete { result, error ->
            if (!continuation.isActive) return@whenComplete
            if (error == null) {
                continuation.resume(result)
            } else {
                // A dependent stage wraps the host's exception; the model should read the original.
                continuation.resumeWithException((error as? CompletionException)?.cause ?: error)
            }
        }
    }
