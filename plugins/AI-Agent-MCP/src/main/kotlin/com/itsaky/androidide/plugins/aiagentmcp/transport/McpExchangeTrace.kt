package com.itsaky.androidide.plugins.aiagentmcp.transport

import java.net.HttpURLConnection

/**
 * Builds one [McpExchange] from a live [HttpURLConnection] as the request runs.
 * Everything passes through [redactor] on the way in, so no credential is kept for display.
 *
 * @param headers the headers as set on the connection, credentials included.
 * @param contentLength the body's size in bytes, as the stack sends it.
 * @param redactor the request's own, built from the credentials it carries.
 */
internal class McpExchangeTrace(
    private val method: String,
    private val url: String,
    private val headers: List<Pair<String, String>>,
    private val payload: String,
    private val contentLength: Int,
    redactor: McpRedactor,
) {

    /**
     * What everything passes through: the request's redactor until [answered], and from there one
     * that also masks the session id the answer assigned. A reply to `initialize` assigns one the
     * request could not carry, and its body may repeat it.
     */
    var redactor: McpRedactor = redactor
        private set

    private var status: String? = null
    private var responseHeaders: List<McpExchange.Header> = emptyList()
    private var responseBody: McpExchange.Body? = null

    /** Records the status line and headers, which is where the server is first heard from. */
    fun answered(conn: HttpURLConnection) {
        val code = conn.responseCode
        // Before anything of the answer is kept: its other headers and its body may echo the id.
        conn.getHeaderField(McpHttpClient.HEADER_SESSION_ID)?.takeIf { it.isNotBlank() }
            ?.let { redactor = redactor.with(it) }
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
     * As [received], for a body kept only in part: [dropped] more characters came after [body]
     * and were read and thrown away; the record counts them with what its own cap leaves out.
     *
     * When anything was dropped, [body] was cut at a fixed length and may end partway through a
     * credential, which [McpRedactor.scrub] would leave showing; [McpRedactor.scrubCut] masks it.
     */
    fun received(body: String, dropped: Long) {
        val scrubbed = if (dropped > 0) redactor.scrubCut(body) else redactor.scrub(body)
        val kept = McpExchange.Body.capped(scrubbed)
        responseBody = kept.copy(omitted = (kept.omitted + dropped).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /**
     * The finished record.
     * @param failure what ended the request early, or null when it ran to the end.
     */
    fun toExchange(failure: Throwable?): McpExchange = McpExchange(
        method = method,
        url = redactor.url(url),
        requestHeaders = headers.map { (name, value) -> redactor.requestHeader(name, value) } +
            redactor.requestHeader("Content-Length", contentLength.toString()),
        requestBody = McpExchange.Body.capped(redactor.scrub(payload)),
        status = status,
        responseHeaders = responseHeaders,
        responseBody = responseBody,
        // The status line and body already say what an HTTP error was.
        failure = failure?.takeUnless { it is McpHttpException }?.let { redactor.message(it.describe()) },
    )
}
