package com.itsaky.androidide.plugins.aiagentmcp.transport

import java.net.HttpURLConnection

/**
 * Builds one [McpExchange] from a live [HttpURLConnection] as the request runs.
 * Everything passes through [redactor] on the way in, so no credential is kept for display.
 *
 * @param headers the headers as set on the connection, credentials included.
 * @param contentLength the body's size in bytes, as the stack sends it.
 */
internal class McpExchangeTrace(
    private val method: String,
    private val url: String,
    private val headers: List<Pair<String, String>>,
    private val payload: String,
    private val contentLength: Int,
    private val redactor: McpRedactor,
) {

    private var status: String? = null
    private var responseHeaders: List<McpExchange.Header> = emptyList()
    private var responseBody: McpExchange.Body? = null

    /** Records the status line and headers, which is where the server is first heard from. */
    fun answered(conn: HttpURLConnection) {
        val code = conn.responseCode
        val fields = conn.headerFields.orEmpty().entries
        status = fields.firstOrNull { it.key == null }?.value?.firstOrNull()
            ?: "HTTP $code ${conn.responseMessage.orEmpty()}".trim()
        responseHeaders = fields
            // `X-Android-*` is the platform's own bookkeeping, not something the server sent.
            .filter { (name, _) -> name != null && !name.startsWith("X-Android-", ignoreCase = true) }
            .flatMap { (name, values) -> values.map { redactor.responseHeader(name, it) } }
    }

    /** Records the body as read, before any parsing can fail on it. */
    fun received(body: String) {
        responseBody = McpExchange.Body.capped(redactor.scrub(body))
    }

    /**
     * The finished record.
     * @param failure what ended the request early, or null when it ran to the end.
     */
    fun toExchange(failure: Throwable?): McpExchange = McpExchange(
        method = method,
        url = url,
        requestHeaders = headers.map { (name, value) -> redactor.requestHeader(name, value) } +
            redactor.requestHeader("Content-Length", contentLength.toString()),
        requestBody = McpExchange.Body.capped(payload),
        status = status,
        responseHeaders = responseHeaders,
        responseBody = responseBody,
        // The status line and body already say what an HTTP error was.
        failure = failure?.takeUnless { it is McpHttpException }?.let { redactor.scrub(it.describe()) },
    )
}
