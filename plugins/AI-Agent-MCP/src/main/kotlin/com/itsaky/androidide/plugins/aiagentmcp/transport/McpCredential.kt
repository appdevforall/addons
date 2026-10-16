package com.itsaky.androidide.plugins.aiagentmcp.transport

/**
 * Which credential a request carried, so a refusal names the field the user has to fix.
 * An `Authorization` extra header replaces the access token on the wire, so it is the one blamed.
 */
sealed interface McpCredential {

    /** Nothing that could authenticate was sent. */
    data object None : McpCredential

    /** The access token, as `Authorization: Bearer`. */
    data object Token : McpCredential

    /**
     * An extra header that set `Authorization` itself, replacing the access token if there was one.
     * @property name the header's name as the user typed it.
     */
    data class AuthorizationHeader(val name: String) : McpCredential

    /** No `Authorization` was sent, but extra headers were, and one may carry an API key. */
    data object ExtraHeaders : McpCredential

    companion object {

        private const val AUTHORIZATION = "Authorization"

        /**
         * @param token the access token, blank when none is stored.
         * @param userHeaders the user's extra headers, as sent.
         * @return the credential those amount to.
         */
        fun of(token: String, userHeaders: Map<String, String>): McpCredential {
            val header = userHeaders.keys.firstOrNull { it.equals(AUTHORIZATION, ignoreCase = true) }
            return when {
                header != null -> AuthorizationHeader(header)
                token.isNotBlank() -> Token
                userHeaders.isNotEmpty() -> ExtraHeaders
                else -> None
            }
        }
    }
}
