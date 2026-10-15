package com.itsaky.androidide.plugins.aiagentmcp.transport

/**
 * The exchanges of one attempt, in the order they ended. Filled and read on the attempt's own
 * thread: [com.itsaky.androidide.plugins.aiagentmcp.client.McpSession.recording] sees no other.
 */
class McpExchangeLog : McpExchangeListener {

    private val exchanges = mutableListOf<McpExchange>()

    private var redactor: McpRedactor? = null

    override fun onExchange(exchange: McpExchange, redactor: McpRedactor) {
        exchanges += exchange
        this.redactor = redactor
    }

    /** @return a copy of what has been recorded so far. */
    fun snapshot(): List<McpExchange> = exchanges.toList()

    /**
     * @param text an error raised after the last exchange, such as a JSON-RPC error echoing a key.
     * @return [text] redacted as the last exchange was; unchanged when nothing was sent.
     */
    fun redact(text: String): String = redactor?.message(text) ?: text
}
