package com.itsaky.androidide.plugins.aiagentclaude.errors

import java.io.IOException

/**
 * A failure the Claude API reported: a non-2xx answer, or an `error` event inside a stream that had
 * already started.
 *
 * The status and the body are fields rather than something a reader digs back out of the message:
 * the settings pane's verdict and the transient-failure retry both need the status, and reading it
 * out of formatted text made the wording of a log line a contract that a reword would silently
 * break.
 *
 * @param statusCode the HTTP status, or for a stream error the status its error type maps to
 * @param body the API's error body; never shown to the user unfiltered
 * @param retryAfterSeconds the `retry-after` header, when the API sent one
 */
class ClaudeHttpException(
    val statusCode: Int,
    val body: String,
    val retryAfterSeconds: Long? = null,
) : IOException("Claude HTTP $statusCode: $body") {

    companion object {

        /**
         * The status an in-stream `error` event stands for, so it classifies — and retries — like
         * the same failure arriving as an HTTP status.
         *
         * @param errorType the event's `error.type`, e.g. `overloaded_error`
         */
        fun statusForStreamError(errorType: String?): Int = when (errorType) {
            "overloaded_error" -> 529
            "rate_limit_error" -> 429
            "api_error" -> 500
            "authentication_error" -> 401
            "permission_error" -> 403
            "not_found_error" -> 404
            "request_too_large" -> 413
            "billing_error" -> 402
            "invalid_request_error" -> 400
            else -> 500
        }
    }
}

/**
 * The API accepted the request, then went silent for longer than the read timeout. Its own type
 * because the network was evidently up: reporting it as "check your internet connection" sends
 * the user after the wrong problem.
 */
class ClaudeStreamStalledException(cause: Throwable) : IOException("Claude stopped responding", cause)
