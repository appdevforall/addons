package com.itsaky.androidide.plugins.aiagentgemini.backend

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where a Google Search grounding source really points. Gemini lists each source as a redirect
 * through its own grounding host, so citing the link as given names no page a reader can recognise,
 * and fetching it asks the user to approve a host that is not the page's.
 */
internal object GroundingRedirect {

    /** Per request: a source that does not answer quickly is cited as given rather than awaited. */
    private const val TIMEOUT_MS = 5_000

    /**
     * The page [uri] redirects to, read from one `HEAD` that does not follow it.
     *
     * @param uri the source link as the grounding metadata gave it.
     * @param open opens a connection to a URL, or null for a scheme that is not HTTP; a parameter
     *   so the answer is testable offline.
     * @return the redirect's absolute target, or [uri] itself when it does not redirect or the
     *   request fails.
     */
    fun target(
        uri: String,
        open: (URL) -> HttpURLConnection? = { it.openConnection() as? HttpURLConnection },
    ): String {
        val url = try {
            URL(uri)
        } catch (e: IOException) {
            return uri
        }
        val conn = try {
            open(url)
        } catch (e: IOException) {
            return uri
        } ?: return uri
        return try {
            conn.requestMethod = "HEAD"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            val location = conn.getHeaderField("Location")
            if (conn.responseCode in 300..399 && !location.isNullOrBlank()) {
                URL(url, location).toString()
            } else {
                uri
            }
        } catch (e: Exception) {
            // One source that fails oddly must not fail the search whose answer it is cited under.
            uri
        } finally {
            conn.disconnect()
        }
    }
}
