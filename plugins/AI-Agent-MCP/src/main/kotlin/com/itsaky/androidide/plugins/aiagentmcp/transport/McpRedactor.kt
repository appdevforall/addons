package com.itsaky.androidide.plugins.aiagentmcp.transport

/**
 * Decides what of one request may be shown: credentials hidden, any echo of them masked.
 * Built per request from the credentials it carries, and dropped with it.
 *
 * @param credentials the token and the user's header values, as sent.
 * @param userHeaderNames the headers the user configured; their values are stored encrypted.
 */
class McpRedactor(
    credentials: Collection<String>,
    private val userHeaderNames: Collection<String>,
) {

    companion object {

        /** What a credential echoed back by the server is replaced with. */
        const val MASK = "••••••••"

        /** Shorter values are masked only as a whole token, so `abc` does not eat "abcdef". */
        private const val MIN_SUBSTRING_LENGTH = 6

        /** Headers that carry a credential whoever set them. */
        private val CREDENTIAL_HEADERS = setOf("authorization", "proxy-authorization", "cookie")

        /** An RFC 7235 auth scheme followed by its credential, e.g. `Bearer abc`. */
        private val SCHEMED = Regex("""^([A-Za-z][A-Za-z0-9!#$%&'*+.^_`|~-]{0,31}) +(.*)$""")
    }

    /** Longest first, so no secret is left half-masked by a shorter one inside it. */
    private val secrets: List<Regex> = credentials
        .flatMap { listOfNotNull(it, SCHEMED.matchEntire(it)?.groupValues?.get(2)) }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
        .map { if (it.length >= MIN_SUBSTRING_LENGTH) Regex(Regex.escape(it)) else wholeToken(it) }

    /**
     * A header this client sent, its value hidden when it is a credential.
     * @return the header as it may be shown.
     */
    fun requestHeader(name: String, value: String): McpExchange.Header =
        McpExchange.Header(name, if (isCredential(name)) hide(value) else McpExchange.Value.Shown(value))

    /**
     * A header the server sent, with any credential it echoes masked.
     * @return the header as it may be shown.
     */
    fun responseHeader(name: String, value: String): McpExchange.Header =
        McpExchange.Header(name, McpExchange.Value.Shown(scrub(value)))

    /**
     * Replaces every occurrence of a credential in [text] with [MASK].
     * @param text anything that came back from the server or the stack.
     * @return the text, safe to show.
     */
    fun scrub(text: String): String = secrets.fold(text) { acc, secret -> acc.replace(secret, MASK) }

    private fun wholeToken(secret: String): Regex =
        Regex("""(?<![A-Za-z0-9])${Regex.escape(secret)}(?![A-Za-z0-9])""")

    private fun isCredential(name: String): Boolean =
        name.lowercase() in CREDENTIAL_HEADERS ||
            userHeaderNames.any { it.equals(name, ignoreCase = true) }

    private fun hide(value: String): McpExchange.Value.Hidden =
        SCHEMED.matchEntire(value)
            ?.let { McpExchange.Value.Hidden(it.groupValues[1], it.groupValues[2].length) }
            ?: McpExchange.Value.Hidden(null, value.length)
}
