package com.itsaky.androidide.plugins.aiagentmcp.transport

/**
 * The exchanges of one attempt, in the order they ended.
 * Synchronised: a session is shared, so a tool call on another thread can land here too.
 */
class McpExchangeLog : McpExchangeListener {

    private val exchanges = mutableListOf<McpExchange>()

    @Synchronized
    override fun onExchange(exchange: McpExchange) {
        exchanges += exchange
    }

    /** @return a copy of what has been recorded so far. */
    @Synchronized
    fun snapshot(): List<McpExchange> = exchanges.toList()
}
