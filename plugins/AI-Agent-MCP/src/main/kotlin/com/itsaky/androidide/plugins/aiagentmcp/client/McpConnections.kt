package com.itsaky.androidide.plugins.aiagentmcp.client

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentmcp.security.UnavailableSecretException
import com.itsaky.androidide.plugins.aiagentmcp.security.UnreadableSecretException
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "$LOG_PREFIX.McpConnections"

/**
 * The live [McpSession] per configured server.
 *
 * Sessions are reused so the handshake is paid once rather than per tool call, and keyed by url
 * *and* token: editing either has to produce a new session, or the old credentials keep working
 * until the IDE restarts, which reads as "my change did nothing".
 */
object McpConnections {

    /**
     * What a session was built from.
     *
     * The credentials are held as SHA-256 digests rather than as `hashCode`: a 32-bit hash of two
     * distinct tokens can coincide, and the cost of that coincidence is one server's credential
     * being reused after the user replaced it.
     */
    private data class Key(
        val serverId: String,
        val url: String,
        val tokenFingerprint: String,
        val headerFingerprint: String,
    )

    private val sessions = ConcurrentHashMap<String, Pair<Key, McpSession>>()

    /**
     * The session for [server], created if needed.
     *
     * Reads the token and the headers, so call this off the main thread.
     *
     * @param server the server to connect to.
     * @return its session, not yet initialized.
     */
    fun session(server: McpServer): McpSession {
        val key = keyFor(server)

        // Locked, so two tool calls arriving together cannot each build a session and leave one of
        // them unreachable and unclosed. The stale one is closed outside, where its DELETE cannot
        // hold the next caller behind a socket.
        var stale: McpSession? = null
        val session = synchronized(this) {
            val current = sessions[server.id]
            if (current != null && current.first == key) return current.second
            stale = current?.second
            // A supplier, not the values: the session is kept for the life of the process, and a
            // token in one of its fields would be readable in a heap dump for just as long.
            McpSession(server.url, { credentialsFor(server.id) })
                .also { sessions[server.id] = key to it }
        }

        stale?.let {
            Log.i(TAG, "Server '${server.name}' changed; dropping its session")
            runCatching { it.close() }
        }
        return session
    }

    /** Drops the session for [serverId], ending it server-side when it had one. */
    fun invalidate(serverId: String) {
        sessions.remove(serverId)?.second?.let { runCatching { it.close() } }
    }

    /** Ends every session, for the plugin shutting down. */
    fun closeAll() {
        sessions.values.forEach { (_, session) -> runCatching { session.close() } }
        sessions.clear()
    }

    /**
     * Decrypts one server's credentials, for a request about to go out.
     *
     * Keystore work on every call rather than once per session; against a network round trip it
     * does not register, and it is what keeps the plaintext from outliving the request.
     *
     * @param serverId the server being called.
     * @return its token and headers; either may be empty.
     * @throws UnreadableSecretException when a stored credential cannot be decrypted here. Sending
     *   the request without it would earn a 401 and tell the user their token was refused.
     * @throws UnavailableSecretException when the keystore could not be reached to decrypt one, a
     *   failure to retry rather than to report as a lost credential.
     */
    private fun credentialsFor(serverId: String): McpCredentials {
        val token = when (val stored = McpServerStore.token(serverId)) {
            // Trimmed here, not by the store: the host's readAndMigrate migrates verbatim, and a
            // legacy token saved with a stray newline is one `setRequestProperty` refuses to send.
            is KeystoreSecretStore.Stored.Value -> stored.plain.trim()
            KeystoreSecretStore.Stored.Absent -> ""
            KeystoreSecretStore.Stored.Unreadable ->
                throw UnreadableSecretException("The stored token for '$serverId' cannot be decrypted.")
            // Not Unreadable: the token is very likely intact and the call is worth repeating, so
            // the user is told to retry rather than to enter the token again.
            KeystoreSecretStore.Stored.Unavailable ->
                throw UnavailableSecretException(
                    "The stored token for '$serverId' could not be read just now."
                )
        }
        return McpCredentials(token, McpServerStore.headers(serverId))
    }

    /**
     * What [server]'s session was built from, credentials included as digests.
     *
     * Kept to its own function so the decrypted values are unreachable the moment it returns; the
     * session that follows re-reads them per request rather than holding these.
     *
     * @param server the server to key.
     * @return the key to compare against the cached one.
     */
    private fun keyFor(server: McpServer): Key {
        // Headers join the key for the same reason the token does: edit one and the old session
        // would keep sending the old value until the IDE restarts.
        val credentials = credentialsFor(server.id)
        return Key(
            serverId = server.id,
            url = server.url,
            tokenFingerprint = fingerprint(credentials.token),
            headerFingerprint = fingerprint(
                credentials.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            ),
        )
    }

    /**
     * A digest of a credential, for comparing one against another without keeping it here.
     * @param value the credential, or empty when there is none.
     * @return its hex SHA-256.
     */
    private fun fingerprint(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        return try {
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte -> "%02x".format(byte) }
        } finally {
            // The digest is kept, the credential it was taken from is not.
            bytes.fill(0)
        }
    }
}
