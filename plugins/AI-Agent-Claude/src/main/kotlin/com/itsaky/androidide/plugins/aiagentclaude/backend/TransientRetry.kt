package com.itsaky.androidide.plugins.aiagentclaude.backend

import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeHttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * When a failed request is worth sending again, and after how long.
 *
 * The Claude API answers 529 when it is overloaded and 429 when the account's rate limit is hit;
 * both usually clear within seconds, and failing the turn on the first one makes a busy minute
 * look like a broken plugin. Retried only before a stream has delivered anything, so a retry can
 * never show the user the same tokens twice. Pure, so the schedule is unit-testable.
 */
internal object TransientRetry {

    /** Attempts after the first. Two matches what the official SDKs do by default. */
    const val MAX_RETRIES = 2

    /** Statuses that say "try again" rather than "this request is wrong". */
    private val RETRYABLE_STATUSES = setOf(408, 409, 429, 500, 502, 503, 504, 529)

    /** First backoff; doubled per attempt when the API names no delay of its own. */
    private const val BASE_DELAY_MS = 1_000L

    /**
     * Longest wait honoured, even when `retry-after` asks for more: past this the user is better
     * served by an error they can act on than by a turn that sits silent.
     */
    private const val MAX_DELAY_MS = 10_000L

    /**
     * Runs [attempt], retrying it per [delayMs] for as long as [delivered] says nothing has
     * reached the user. From the first delivered token a failure is final, since a retry would
     * repeat what is already on screen.
     *
     * @param onRetry told about each retry before its wait, for the log
     * @param sleep the wait; injectable so the schedule is testable without real time
     * @return what the successful attempt returned
     */
    suspend fun <T> run(
        delivered: () -> Boolean = { false },
        onRetry: (error: Exception, retry: Int, waitMs: Long) -> Unit = { _, _, _ -> },
        sleep: suspend (Long) -> Unit = { delay(it) },
        attempt: suspend () -> T,
    ): T {
        var retries = 0
        while (true) {
            try {
                return attempt()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val wait = if (delivered()) null else delayMs(e, retries)
                if (wait == null) throw e
                retries++
                onRetry(e, retries, wait)
                sleep(wait)
            }
        }
    }

    /**
     * How long to wait before retrying after [error], or null to give up.
     *
     * @param error the failure the last attempt ended with
     * @param retriesSoFar retries already made for this request
     */
    fun delayMs(error: Throwable, retriesSoFar: Int): Long? {
        if (retriesSoFar >= MAX_RETRIES) return null
        val http = error as? ClaudeHttpException ?: return null
        if (http.statusCode !in RETRYABLE_STATUSES) return null
        val asked = http.retryAfterSeconds?.takeIf { it >= 0 }?.times(1_000L)
        val backoff = BASE_DELAY_MS shl retriesSoFar
        return (asked ?: backoff).coerceAtMost(MAX_DELAY_MS)
    }
}
