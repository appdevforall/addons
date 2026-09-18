package com.itsaky.androidide.plugins.aiagentmcp.transport

import java.net.MalformedURLException
import java.net.URL

/**
 * Where a redirect may be repeated, given that the request carries credentials.
 *
 * Pure and separate from the socket work: every request this plugin sends carries the bearer token
 * and the user's own headers, so the rule deciding whether they may be sent somewhere else is the
 * one piece of the transport worth exercising without a server.
 */
object McpRedirects {

    /** The statuses that name a new location; 304 and the rest of 3xx are not redirects. */
    private val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

    /** What to do with a redirect. */
    sealed interface Verdict {

        /** The answer was not a redirect at all. */
        data object NotARedirect : Verdict

        /** Repeat the request at [url], which is the same origin as the one it was sent to. */
        data class Follow(val url: String) : Verdict

        /** The destination is another origin, so the credentials must not go there. */
        data object OtherOrigin : Verdict

        /** The destination is missing or cannot be read as a URL. */
        data object Unusable : Verdict
    }

    /**
     * Reads a redirect.
     *
     * Only the same origin is followed: a redirect to another host would hand it the bearer token
     * this plugin encrypts at rest, plus every custom header — as often a credential as the token
     * is — in one hop and with nothing left to see afterwards. An https → http downgrade is a
     * change of scheme and is therefore refused by the same rule.
     *
     * @param status the status the server answered with.
     * @param current the URL the request was sent to, for resolving a relative `Location`.
     * @param location the `Location` header, or null when the server sent none.
     * @return the verdict.
     */
    fun verdict(status: Int, current: String, location: String?): Verdict {
        if (status !in REDIRECT_STATUSES) return Verdict.NotARedirect
        val destination = location?.takeIf { it.isNotBlank() } ?: return Verdict.Unusable

        val from = try {
            URL(current)
        } catch (e: MalformedURLException) {
            return Verdict.Unusable
        }
        val to = try {
            URL(from, destination)
        } catch (e: MalformedURLException) {
            return Verdict.Unusable
        }

        return if (sameOrigin(from, to)) Verdict.Follow(to.toString()) else Verdict.OtherOrigin
    }

    /** Whether two URLs address the same origin, i.e. scheme, host and effective port all match. */
    private fun sameOrigin(from: URL, to: URL): Boolean =
        from.protocol.equals(to.protocol, ignoreCase = true) &&
            from.host.equals(to.host, ignoreCase = true) &&
            from.effectivePort() == to.effectivePort()

    /** This URL's port, with the scheme's default filled in so `https://h` matches `https://h:443`. */
    private fun URL.effectivePort(): Int = if (port == -1) defaultPort else port
}
