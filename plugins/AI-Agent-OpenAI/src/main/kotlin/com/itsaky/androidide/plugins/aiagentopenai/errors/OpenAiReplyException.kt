package com.itsaky.androidide.plugins.aiagentopenai.errors

/**
 * A 2xx reply whose body reports an `error` object instead of an answer.
 *
 * Carries the body in its message so [OpenAiErrorFormatter.classify] reads the server's `code` and
 * `message` from it exactly as it does for an [OpenAiHttpException]; never shown unfiltered.
 *
 * @param body the server's reply body
 */
class OpenAiReplyException(val body: String) : Exception("OpenAI reply error: $body")
