package com.itsaky.androidide.plugins.aiagentmcp.transport

/**
 * The rules for the extra headers a user may attach to a server.
 *
 * Some MCP servers need more than a bearer token to route a request — an API key, an environment
 * selector, a client id — so the settings screen lets the user add their own. That text goes
 * straight onto the wire, which makes two checks mandatory rather than cosmetic: a name or value
 * carrying CR or LF would let one field forge another (request splitting), and a name this plugin
 * sets itself would silently break the protocol.
 *
 * Pure and free of Android types, so every rule is unit-testable without a device.
 */
object McpHeaders {

    /**
     * Headers the transport owns. A user-supplied `Accept` or `Mcp-Session-Id` would break the
     * session or the response negotiation, and the failure would look like a broken server.
     * `Authorization` is deliberately absent: it has its own field, but a server wanting a scheme
     * other than `Bearer` has no other way to say so.
     */
    val RESERVED = setOf(
        "content-type",
        "accept",
        "mcp-session-id",
        "mcp-protocol-version",
    )

    /** Max characters kept from a name; longer is a paste accident, not a header. */
    const val MAX_NAME_LENGTH = 128

    /** Max characters kept from a value. Generous: some gateways take a signed blob here. */
    const val MAX_VALUE_LENGTH = 2048

    /** RFC 7230 token characters — what a header name is actually allowed to contain. */
    private val NAME_PATTERN = Regex("""^[A-Za-z0-9!#$%&'*+.^_`|~-]+$""")

    /**
     * Whether [name] is a usable header name this plugin does not already own.
     * @param name the name as typed.
     * @return true when it can be sent.
     */
    fun isValidName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() &&
            trimmed.length <= MAX_NAME_LENGTH &&
            NAME_PATTERN.matches(trimmed) &&
            trimmed.lowercase() !in RESERVED
    }

    /**
     * Whether [value] can be sent as typed.
     *
     * Empty is allowed — an empty header is legal and occasionally meaningful — but a line break
     * or any other control character is not.
     *
     * @param value the value as typed.
     * @return true when it can be sent.
     */
    fun isValidValue(value: String): Boolean =
        value.length <= MAX_VALUE_LENGTH && value.none { it.isISOControl() }

    /**
     * Whether [token] can travel in an `Authorization` header.
     *
     * The same rule as a header value, because that is what it becomes: a token pasted with a
     * trailing line break would forge a header of its own at the socket, exactly as a header value
     * would.
     *
     * @param token the bearer token as stored or typed.
     * @return true when it can be sent.
     */
    fun isSendableToken(token: String): Boolean = isValidValue(token)

    /**
     * Keeps only the pairs that can be sent, in the order given.
     *
     * A row the user left half-filled is dropped rather than reported: the settings screen already
     * marks a bad name as it is typed, and a silent drop here is the last line of defence, not the
     * first.
     *
     * @param headers the pairs as entered.
     * @return the pairs safe to put on the wire, later duplicates of a name discarded.
     */
    fun sanitize(headers: Map<String, String>): Map<String, String> {
        val clean = LinkedHashMap<String, String>()
        for ((name, value) in headers) {
            val trimmed = name.trim()
            if (!isValidName(trimmed) || !isValidValue(value)) continue
            if (clean.keys.none { it.equals(trimmed, ignoreCase = true) }) {
                clean[trimmed] = value
            }
        }
        return clean
    }

    /**
     * Why [name] cannot be used, for the settings screen to show against the offending row.
     * @param name the name as typed.
     * @return a reason key, or null when the name is fine.
     */
    fun nameProblem(name: String): Problem? {
        val trimmed = name.trim()
        return when {
            trimmed.isEmpty() -> Problem.EMPTY
            trimmed.lowercase() in RESERVED -> Problem.RESERVED
            trimmed.length > MAX_NAME_LENGTH -> Problem.TOO_LONG
            !NAME_PATTERN.matches(trimmed) -> Problem.ILLEGAL_CHARACTERS
            else -> null
        }
    }

    /**
     * Why one row of the settings screen cannot be sent, name and value judged together.
     *
     * The duplicate check belongs here rather than in the screen: [sanitize] keeps the first of two
     * rows naming the same header, so a duplicate the screen accepted would vanish on the way to
     * the wire without anyone being told.
     *
     * @param name the header name as typed.
     * @param value the header value as typed.
     * @param taken the names the rows above this one already claimed.
     * @return the problem to show against this row, or null when it can be sent.
     */
    fun rowProblem(name: String, value: String, taken: Collection<String>): Problem? {
        nameProblem(name)?.let { return it }
        if (!isValidValue(value)) return Problem.ILLEGAL_VALUE
        val trimmed = name.trim()
        return Problem.DUPLICATE.takeIf { taken.any { other -> other.equals(trimmed, true) } }
    }

    /** What is wrong with a header row the user typed. */
    enum class Problem { EMPTY, RESERVED, ILLEGAL_CHARACTERS, TOO_LONG, ILLEGAL_VALUE, DUPLICATE }
}
