package com.itsaky.androidide.plugins.aiagentmcp.debugger

import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpException
import java.net.ConnectException

/**
 * Retries a call on an automatic entry once, with the endpoint read again.
 *
 * A relaunched app listens on a new port with a new token, so a refused connection or a 401/403
 * usually means the stored values are stale. The retry happens only when this entry's URL or
 * token now differ from the ones the failing call used: asking the same server the same thing
 * again helps nobody. A read timeout is not retried: the server may still be running the call,
 * and repeating it would run a slow query twice.
 *
 * @param refresh re-reads the endpoints.
 * @param current the entry now stored for an id, or null when its app is gone.
 * @param token the token now stored for an id, or null when it has no entry.
 */
class EndpointRetry(
    private val refresh: () -> Boolean,
    private val current: (String) -> McpServer?,
    private val token: (String) -> String?,
) {
    fun <T> call(server: McpServer, block: (McpServer) -> T): T {
        // What the call is about to use: the credential is read per request, so a refresh by the
        // probe that lands first is what this call sends, and is what the comparison must see.
        val usedToken = token(server.id)
        try {
            return block(server)
        } catch (e: Exception) {
            if (!server.automatic || !isStale(e)) throw e
            refresh()
            val fresh = current(server.id) ?: throw e
            // Judged for this id alone, from the values used: a probe refresh that already
            // replaced them still earns the retry, and another app's change does not.
            if (fresh.url == server.url && token(server.id) == usedToken) throw e
            return block(fresh)
        }
    }

    companion object {
        fun isStale(e: Throwable): Boolean = when (e) {
            is ConnectException -> true
            is McpHttpException -> e.statusCode == 401 || e.statusCode == 403
            else -> false
        }
    }
}
