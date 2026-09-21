package com.itsaky.androidide.plugins.aiagentmcp.transport

/**
 * Reads one line of an MCP server's server-sent-events response.
 *
 * MCP's Streamable HTTP transport lets a server answer a POST either with one JSON document or
 * with an SSE stream carrying the same document, so a client that only understands the first
 * silently sees "no reply" from half the servers in the wild. Pure, so the framing is testable
 * without a server.
 */
object SseChunk {

    private const val DATA_FIELD = "data:"
    private const val EVENT_FIELD = "event:"
    private const val COMMENT_PREFIX = ":"

    /** What one SSE line means to the reader loop. */
    sealed interface Event {

        /** A payload line; [payload] is one JSON-RPC document, or a fragment to accumulate. */
        data class Data(val payload: String) : Event

        /** A named event, e.g. `message`. Kept so an unusual name can be logged, not guessed at. */
        data class Named(val name: String) : Event

        /** A blank line: the end of one event, so whatever was accumulated is now complete. */
        data object Dispatch : Event

        /** A comment, a keep-alive, or a field this client has no use for. */
        data object Ignored : Event
    }

    /**
     * Classifies [line].
     * @param line one raw line from the response body, without its terminator.
     * @return what the reader loop should do with it.
     */
    fun parse(line: String): Event {
        if (line.isEmpty()) return Event.Dispatch

        // A leading colon is a comment; servers send them as keep-alives on idle streams.
        if (line.startsWith(COMMENT_PREFIX)) return Event.Ignored

        return when {
            line.startsWith(DATA_FIELD) -> Event.Data(line.removePrefix(DATA_FIELD).removePrefix(" "))
            line.startsWith(EVENT_FIELD) -> Event.Named(line.removePrefix(EVENT_FIELD).trim())
            else -> Event.Ignored
        }
    }
}
