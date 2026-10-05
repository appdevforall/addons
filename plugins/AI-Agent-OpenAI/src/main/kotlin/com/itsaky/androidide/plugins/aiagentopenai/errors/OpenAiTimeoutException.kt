package com.itsaky.androidide.plugins.aiagentopenai.errors

import java.io.IOException

/**
 * The request reached the server, which then sent nothing for [timeoutMs].
 *
 * Its own type because the connection worked: a reasoning model, or one running a web search, was
 * still thinking, and reporting that as "could not reach" sends the user to check a network that is fine.
 *
 * @param timeoutMs how long the read waited
 * @param cause the socket's own timeout
 */
class OpenAiTimeoutException(
    val timeoutMs: Int,
    cause: Throwable,
) : IOException("OpenAI sent nothing for ${timeoutMs}ms", cause)
