package com.itsaky.androidide.plugins.aiagentmcp.transport

/**
 * One HTTP request to an MCP server and what came back, so a failure can show what really happened.
 * Pure data: [McpRedactor] has already removed credentials by the time one is built.
 *
 * @property method the HTTP method.
 * @property url the URL the request went to, after any redirect.
 * @property requestHeaders the headers this client set, in the order it set them.
 * @property requestBody what was POSTed.
 * @property status the server's status line, or null when no answer arrived.
 * @property responseHeaders the server's headers, in the order it sent them.
 * @property responseBody what the server sent back, or null when none was read.
 * @property failure what interrupted the exchange, or null when it ran to the end.
 */
data class McpExchange(
    val method: String,
    val url: String,
    val requestHeaders: List<Header>,
    val requestBody: Body,
    val status: String?,
    val responseHeaders: List<Header>,
    val responseBody: Body?,
    val failure: String?,
) {

    /** One header line. */
    data class Header(val name: String, val value: Value)

    /** A header value as it may be shown. */
    sealed interface Value {

        /** Shown as it was on the wire. */
        data class Shown(val text: String) : Value

        /**
         * A credential, reduced to what diagnoses it without leaking it.
         * @property scheme the auth scheme in front of it, such as `Bearer`, or null when none.
         * @property length the characters hidden, so a truncated paste still shows.
         */
        data class Hidden(val scheme: String?, val length: Int) : Value
    }

    /**
     * A body, capped so a server answering with a megabyte of HTML cannot stall the screen.
     * @property text what is kept.
     * @property omitted how many characters past [text] were dropped.
     */
    data class Body(val text: String, val omitted: Int = 0) {

        companion object {

            /** Characters kept per body: an error or handshake fits whole, a big tool list does not. */
            const val MAX_CHARS = 16 * 1024

            /**
             * Keeps [text], capped at [MAX_CHARS].
             * @param text the body as read.
             * @return the body to keep.
             */
            fun capped(text: String): Body =
                if (text.length <= MAX_CHARS) Body(text)
                else Body(text.take(MAX_CHARS), text.length - MAX_CHARS)
        }
    }
}

/** One line naming what went wrong, the way a transcript prints it. */
fun Throwable.describe(): String = "${javaClass.name}: ${message.orEmpty()}"
