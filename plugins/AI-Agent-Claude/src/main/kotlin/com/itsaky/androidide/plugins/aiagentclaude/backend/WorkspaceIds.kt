package com.itsaky.androidide.plugins.aiagentclaude.backend

/**
 * The workspace a key that belongs to no workspace must name on every request.
 *
 * Such a key is refused with a 400 until each request carries an `anthropic-workspace-id` header,
 * and the plugin cannot look the id up itself: listing workspaces takes an admin key, not the
 * user's API key. So the user types it, and what they type becomes a header value. Pure, so the
 * rule that keeps a pasted value from forging a second header is unit-testable.
 */
internal object WorkspaceIds {

    /** The header the API reads the id from. */
    const val HEADER = "anthropic-workspace-id"

    /**
     * Longest id accepted. Real ids are far shorter; the cap only bounds what a paste can put in
     * a header.
     */
    private const val MAX_LENGTH = 128

    /** What [normalize] concluded about a typed id. */
    sealed interface Result {
        /** Nothing was typed: requests carry no workspace header. */
        data object None : Result

        /** A usable id, trimmed. */
        data class Valid(val id: String) : Result

        /**
         * Not usable as a header value: it has whitespace or a control character inside it, or is
         * too long. A line break in particular would let a pasted value add a header of its own.
         */
        data object Invalid : Result
    }

    /**
     * Checks a typed workspace id.
     *
     * The `wrkspc_` prefix real ids carry is not required: the API is the judge of what a
     * workspace id looks like, and an id it rejects is reported like any other refusal.
     *
     * @param input the id as typed or stored, possibly null
     */
    fun normalize(input: String?): Result {
        val id = input?.trim().orEmpty()
        if (id.isEmpty()) return Result.None
        if (id.length > MAX_LENGTH) return Result.Invalid
        if (id.any { it.isWhitespace() || it.isISOControl() || it.code > 0x7E }) return Result.Invalid
        return Result.Valid(id)
    }

    /** The id to send for [input], or null when none should be: blank or unusable. */
    fun headerValue(input: String?): String? = (normalize(input) as? Result.Valid)?.id
}
