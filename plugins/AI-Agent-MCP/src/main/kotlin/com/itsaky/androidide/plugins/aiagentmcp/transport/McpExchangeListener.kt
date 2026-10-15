package com.itsaky.androidide.plugins.aiagentmcp.transport

/** Receives each [McpExchange] as it ends; called on the thread that made the request. */
fun interface McpExchangeListener {

    /**
     * @param exchange the request and its answer, credentials already redacted.
     * @param redactor the one that redacted it, for an error raised after the exchange ended.
     */
    fun onExchange(exchange: McpExchange, redactor: McpRedactor)

    companion object {

        /** Listens to nothing, for callers that do not record. */
        val NONE = McpExchangeListener { _, _ -> }
    }
}
