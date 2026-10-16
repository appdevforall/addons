package com.itsaky.androidide.plugins.aiagentmcp.errors

import com.itsaky.androidide.plugins.aiagentmcp.transport.McpExchange
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpException
import com.itsaky.androidide.plugins.aiagentmcp.transport.describe

/**
 * Renders a failed attempt as what went over the wire, in `curl -v`'s shape: `>` sent, `<`
 * received, `*` what interrupted it. Pure: its few words come in through [Labels].
 */
object McpTranscript {

    /** The translated pieces of a transcript; the settings pane resolves them from `strings.xml`. */
    interface Labels {

        /** @return a redacted credential's placeholder, given how many characters it hides. */
        fun hidden(length: Int): String

        /** @return the note under a capped body, given how many characters were dropped. */
        fun omitted(count: Int): String

        /** The transcript of a failure that never reached the network. */
        val nothingSent: String
    }

    /**
     * A failed attempt, kept structured for the log view and rendered for the clipboard.
     * @property exchanges every exchange the attempt made, oldest first.
     * @property unexplained the error's own line when no exchange already says it; null otherwise.
     * @property text the whole attempt as [render] lays it out, to copy.
     */
    data class Log(val exchanges: List<McpExchange>, val unexplained: String?, val text: String)

    /**
     * The log of an attempt that ended in [error].
     * @param exchanges every exchange the attempt made, oldest first.
     * @param error what the attempt failed with.
     * @param labels the translated pieces.
     * @param redact masks credentials in the error's line, which the exchanges never saw.
     * @return the attempt, structured and as text.
     */
    fun log(
        exchanges: List<McpExchange>,
        error: Throwable,
        labels: Labels,
        redact: (String) -> String,
    ): Log = Log(
        exchanges = exchanges,
        unexplained = redact(error.describe()).takeIf { isUnexplained(exchanges, error) },
        text = render(exchanges, error, labels, redact),
    )

    /**
     * The transcript of an attempt that ended in [error].
     * @param exchanges every exchange the attempt made, oldest first.
     * @param error what the attempt failed with.
     * @param labels the translated pieces.
     * @param redact masks credentials in the error's line, which the exchanges never saw.
     * @return the text to show and to copy.
     */
    fun render(
        exchanges: List<McpExchange>,
        error: Throwable,
        labels: Labels,
        redact: (String) -> String,
    ): String {
        val blocks = buildList {
            if (exchanges.isEmpty()) add(labels.nothingSent)
            exchanges.forEach { add(render(it, labels)) }
            if (isUnexplained(exchanges, error)) add("* ${redact(error.describe())}")
        }
        return blocks.joinToString("\n\n")
    }

    /** Whether [error] still needs its own line: a failed exchange or an HTTP status already says it. */
    private fun isUnexplained(exchanges: List<McpExchange>, error: Throwable): Boolean =
        exchanges.lastOrNull()?.failure == null && error !is McpHttpException

    private fun render(exchange: McpExchange, labels: Labels): String = buildList {
        add("> ${exchange.method} ${exchange.url}")
        addAll(headers(">", exchange.requestHeaders, labels))
        addAll(body(">", exchange.requestBody, labels))
        exchange.status?.let { status ->
            add("< $status")
            addAll(headers("<", exchange.responseHeaders, labels))
            exchange.responseBody?.let { addAll(body("<", it, labels)) }
        }
        exchange.failure?.let { add("* $it") }
    }.joinToString("\n")

    private fun headers(marker: String, headers: List<McpExchange.Header>, labels: Labels): List<String> =
        headers.map { "$marker ${it.name}: ${value(it.value, labels)}" }

    /**
     * @param value a header value, possibly a redacted credential.
     * @return how it is shown: as sent, or as its scheme and a placeholder.
     */
    fun value(value: McpExchange.Value, labels: Labels): String = when (value) {
        is McpExchange.Value.Shown -> value.text
        is McpExchange.Value.Hidden ->
            listOfNotNull(value.scheme, labels.hidden(value.length)).joinToString(" ")
    }

    /** A blank marker line, then the body with every line marked; nothing for an empty body. */
    private fun body(marker: String, body: McpExchange.Body, labels: Labels): List<String> {
        if (body.text.isEmpty() && body.omitted == 0) return emptyList()
        val lines = body.text.trimEnd('\n').lines().map { "$marker $it".trimEnd() }
        val note = labels.omitted(body.omitted).takeIf { body.omitted > 0 }?.let { "$marker $it" }
        return listOf(marker) + lines + listOfNotNull(note)
    }
}
