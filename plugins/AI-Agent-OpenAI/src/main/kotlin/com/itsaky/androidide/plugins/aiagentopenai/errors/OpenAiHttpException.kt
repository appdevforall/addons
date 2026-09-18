package com.itsaky.androidide.plugins.aiagentopenai.errors

import java.io.IOException

/**
 * A non-2xx answer from an OpenAI-compatible server.
 *
 * The status and the body are fields rather than something a reader digs back out of the message:
 * the settings pane's verdict and the retry-without-a-rejected-parameter recovery both need the
 * status, and reading it out of formatted text made the wording of a log line a cross-module
 * contract that a reword would silently break.
 *
 * @param statusCode the HTTP status the server answered with
 * @param body the server's error body; never shown to the user unfiltered
 */
class OpenAiHttpException(
    val statusCode: Int,
    val body: String,
) : IOException("OpenAI HTTP $statusCode: $body")
