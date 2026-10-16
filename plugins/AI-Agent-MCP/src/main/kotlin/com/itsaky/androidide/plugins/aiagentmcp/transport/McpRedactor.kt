package com.itsaky.androidide.plugins.aiagentmcp.transport

import java.net.URLDecoder

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

        /** Headers that carry a credential whoever set them; a session id works as one until it expires. */
        private val CREDENTIAL_HEADERS =
            setOf("authorization", "proxy-authorization", "cookie", "set-cookie", "mcp-session-id")

        /** An RFC 7235 auth scheme followed by its credential, e.g. `Bearer abc`. */
        private val SCHEMED = Regex("""^([A-Za-z][A-Za-z0-9!#$%&'*+.^_`|~-]{0,31}) +(.*)$""")

        /** A URL's scheme and its `user:pass@` part, which some servers take a secret in. */
        private val USERINFO = Regex("""^([A-Za-z][A-Za-z0-9+.-]*://)([^/?#@]*)@""")

        /** A URL inside free text, such as an exception message. */
        private val URL_IN_TEXT = Regex("""[A-Za-z][A-Za-z0-9+.-]*://[^\s"'<>]+""")

        /** Characters JSON may escape with a backslash alone. */
        private const val JSON_BACKSLASHED = "\"\\/"

        /**
         * A redactor for one request to [url], which also masks what the URL itself carries.
         * @param credentials the token, session and the user's header values, as sent.
         * @param userHeaderNames the headers the user configured.
         */
        fun forRequest(url: String, credentials: Collection<String>, userHeaderNames: Collection<String>) =
            McpRedactor(credentials + urlSecrets(url), userHeaderNames)

        /** The userinfo and query values of [url], long enough to be masked wherever echoed. */
        private fun urlSecrets(url: String): List<String> {
            val userInfo = USERINFO.find(url)?.groupValues?.get(2)
            val values = url.substringAfter('?', "").substringBefore('#')
                .split('&')
                .map { it.substringAfter('=', "") }
            return (listOfNotNull(userInfo, userInfo?.substringAfter(':', "")) + values)
                .flatMap { listOf(it, runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)) }
                .filter { it.length >= MIN_SUBSTRING_LENGTH }
        }
    }

    /** Longest first, so no secret is left half-masked by a shorter one inside it. */
    private val secrets: List<Regex> = credentials
        .flatMap { listOfNotNull(it, SCHEMED.matchEntire(it)?.groupValues?.get(2)) }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }
        .map { if (it.length >= MIN_SUBSTRING_LENGTH) Regex(encodedForms(it)) else wholeToken(it) }

    /**
     * A header this client sent, its value hidden when it is a credential.
     * @return the header as it may be shown.
     */
    fun requestHeader(name: String, value: String): McpExchange.Header =
        McpExchange.Header(name, if (isCredential(name)) hide(value) else McpExchange.Value.Shown(value))

    /**
     * A header the server sent: hidden when it issues a credential, otherwise any echo masked.
     * @return the header as it may be shown.
     */
    fun responseHeader(name: String, value: String): McpExchange.Header = McpExchange.Header(
        name,
        if (isCredential(name)) hide(value) else McpExchange.Value.Shown(scrub(value)),
    )

    /**
     * Replaces every occurrence of a credential in [text] with [MASK], JSON- or URL-escaped ones too.
     * @param text anything that came back from the server or the stack.
     * @return the text, safe to show.
     */
    fun scrub(text: String): String = secrets.fold(text) { acc, secret -> acc.replace(secret, MASK) }

    /**
     * A URL as it may be shown: its userinfo and every query value masked, then scrubbed.
     * A secret carried in a path segment cannot be told apart from the path, so it stays.
     */
    fun url(url: String): String = scrub(maskQuery(USERINFO.replace(url) { "${it.groupValues[1]}$MASK@" }))

    /**
     * A one-line error as it may be shown: scrubbed, and any URL in it masked as [url] does.
     * @param text an exception's description.
     */
    fun message(text: String): String = URL_IN_TEXT.replace(scrub(text)) { url(it.value) }

    private fun maskQuery(url: String): String {
        val start = url.indexOf('?').takeIf { it >= 0 } ?: return url
        val end = url.indexOf('#', start).takeIf { it >= 0 } ?: url.length
        val query = url.substring(start + 1, end).split('&').joinToString("&") { param ->
            val eq = param.indexOf('=')
            if (eq < 0 || eq == param.lastIndex) param else param.substring(0, eq + 1) + MASK
        }
        return url.substring(0, start + 1) + query + url.substring(end)
    }

    /** [secret] as a pattern matching each character as sent, `\uXXXX`-, `\`- or `%XX`-escaped. */
    private fun encodedForms(secret: String): String = secret.asIterable().joinToString("") { char ->
        val code = char.code
        val forms = buildList {
            add(Regex.escape(char.toString()))
            add("(?i:\\\\u%04x)".format(code))
            if (char in JSON_BACKSLASHED) add(Regex.escape("\\$char"))
            if (code < 0x80) add("(?i:%%%02x)".format(code))
        }
        forms.joinToString("|", "(?:", ")")
    }

    private fun wholeToken(secret: String): Regex =
        Regex("""(?<![A-Za-z0-9])${encodedForms(secret)}(?![A-Za-z0-9])""")

    private fun isCredential(name: String): Boolean =
        name.lowercase() in CREDENTIAL_HEADERS ||
            userHeaderNames.any { it.equals(name, ignoreCase = true) }

    private fun hide(value: String): McpExchange.Value.Hidden =
        SCHEMED.matchEntire(value)
            ?.let { McpExchange.Value.Hidden(it.groupValues[1], it.groupValues[2].length) }
            ?: McpExchange.Value.Hidden(null, value.length)
}
