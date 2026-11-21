package com.itsaky.androidide.plugins.aiagentmcp.client

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticServers
import com.itsaky.androidide.plugins.aiagentmcp.debugger.McpServers
import com.itsaky.androidide.plugins.aiagentmcp.errors.runCatchingRecoverable
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
     * A server no longer configured gets a session that is not kept: the caller is a call or a
     * listing that outlived the server's removal, and a session stored for it would outlive the
     * server with nothing left to close it. The check is made under this object's lock, which the
     * removal of a SQLite debugger entry takes, to detach its session, only after the entry has
     * left the list; so either that removal takes the session out or this sees the entry gone. A
     * server of the user's is removed from the store after its session is ended, so its DELETE
     * still has the credentials; a lookup between the two still stores one.
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
            // A supplier, not the values: the session is kept for the life of the process, and a
            // token in one of its fields would be readable in a heap dump for just as long.
            val fresh = McpSession(server.url, { credentialsFor(server.id, server.url) })
            if (!McpServers.isConfigured(server.id)) return fresh
            stale = current?.second
            fresh.also { sessions[server.id] = key to it }
        }

        stale?.let {
            Log.i(TAG, "Server '${server.name}' changed; dropping its session")
            runCatchingRecoverable { it.close() }
        }
        return session
    }

    /** Drops the session for [serverId], ending it server-side when it had one. */
    fun invalidate(serverId: String) {
        detach(serverId)?.let { runCatchingRecoverable { it.close() } }
    }

    /**
     * Takes the session for [serverId] out without ending it, for a caller that must take it out
     * under a lock of its own and end it once that lock is let go, since `close()` sends a DELETE.
     * `AutomaticServers` does, for an entry that went away, in the same step that removes it.
     *
     * Taken under this object's lock, as [session] replaces a session under it. Safe to call under
     * another lock: under this one, [session] only reads the configured servers, which takes no
     * lock (`McpServers.all`), and it reads the credentials before it takes this one.
     *
     * @return the session taken out, or null when [serverId] had none.
     */
    fun detach(serverId: String): McpSession? = synchronized(this) { sessions.remove(serverId)?.second }

    /** Ends every session, for the plugin shutting down. */
    fun closeAll() {
        detachAll().forEach { session -> runCatchingRecoverable { session.close() } }
    }

    /**
     * Takes every session out without ending it, as [detach] does one: for a caller that empties
     * the map under a lock of its own and ends the sessions once that lock is let go, since each
     * `close()` sends a DELETE and can wait for a handshake in flight.
     *
     * @return the sessions taken out.
     */
    fun detachAll(): List<McpSession> = synchronized(this) {
        sessions.values.map { it.second }.also { sessions.clear() }
    }

    /**
     * Decrypts one server's credentials, for a request about to go out.
     *
     * Keystore work on every call rather than once per session; against a network round trip it
     * does not register, and it is what keeps the plaintext from outliving the request.
     *
     * @param serverId the server being called.
     * @param url the url the calling session was built for. An automatic entry's token is released
     *   only while the entry still points there; a session for a replaced endpoint gets none, so
     *   its `close()` and any call still in flight cannot hand the new process's token to the old port.
     * @return its token and headers; either may be empty.
     * @throws UnreadableSecretException when a stored credential cannot be decrypted here. Sending
     *   the request without it would earn a 401 and tell the user their token was refused.
     * @throws UnavailableSecretException when the keystore could not be reached to decrypt one, a
     *   failure to retry rather than to report as a lost credential.
     */
    private fun credentialsFor(serverId: String, url: String): McpCredentials {
        if (AutomaticServers.isAutomatic(serverId)) {
            // Read per request, so a token refreshed on the same port is used without a new session.
            val endpoint = AutomaticServers.endpoint(serverId)
            val token = if (endpoint != null && AutomaticServers.urlFor(endpoint) == url) endpoint.token else ""
            return McpCredentials(token, emptyMap())
        }
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
        val credentials = credentialsFor(server.id, server.url)
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
