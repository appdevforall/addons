package com.itsaky.androidide.plugins.aiagentmcp.client

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticEndpoint
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
     * Runs [block] with [server]'s session, created if needed, and ends the session once [block] is
     * done when it was not kept. Nothing else would end that one, so the server would keep the
     * session its `initialize` assigned. Its DELETE reads the credentials as each of its requests
     * did, under the same rule: only for the URL it was built for, while the server still has it,
     * so a server that is gone gets none, as the requests did.
     *
     * A server no longer configured gets a session that is not kept: the caller is a call or a
     * listing that outlived the server's removal, and a session stored for it would outlive the
     * server with nothing left to close it. So does a server of the user's whose URL has been
     * edited since the caller read it ([McpServers.isConfigured]): its session, for the old URL,
     * would replace the one for the new. The check is made under this object's lock, which the
     * removal of a SQLite debugger entry takes, to detach its session, only after the entry has
     * left the list; so either that removal takes the session out or this sees the entry gone. A
     * server of the user's is removed from the store after its session is ended, so its DELETE
     * still has the credentials; the settings pane ends whatever a lookup stored between the two
     * once the server is gone.
     *
     * Reads the token and the headers, and is blocking, like [block]: ending the session sends a
     * DELETE. Call it off the main thread.
     *
     * @param server the server to connect to.
     * @param block the calls to make; the session is not yet initialized.
     * @return what [block] returned.
     */
    fun <T> withSession(server: McpServer, block: (McpSession) -> T): T {
        val found = lookUp(server)
        try {
            return block(found.session)
        } finally {
            if (!found.kept) found.session.close()
        }
    }

    /**
     * What [lookUp] found.
     * @property kept whether [session] is the one stored for its server; false for a server no
     *   longer configured, or no longer at the URL the caller had.
     */
    private class Found(val session: McpSession, val kept: Boolean)

    /** Finds or builds [server]'s session for [withSession], telling one it kept from one it did not. */
    private fun lookUp(server: McpServer): Found {
        val key = keyFor(server)

        // Locked, so two tool calls arriving together cannot each build a session and leave one of
        // them unreachable and unclosed. The stale one is closed outside, where its DELETE cannot
        // hold the next caller behind a socket.
        var stale: McpSession? = null
        val session = synchronized(this) {
            val current = sessions[server.id]
            if (current != null && current.first == key) return Found(current.second, kept = true)
            // A supplier, not the values: the session is kept for the life of the process, and a
            // token in one of its fields would be readable in a heap dump for just as long.
            val fresh = McpSession(server.url, { credentialsFor(server.id, server.url) })
            if (!McpServers.isConfigured(server)) return Found(fresh, kept = false)
            stale = current?.second
            fresh.also { sessions[server.id] = key to it }
        }

        stale?.let {
            Log.i(TAG, "Server '${server.name}' changed; dropping its session")
            runCatchingRecoverable { it.close() }
        }
        return Found(session, kept = true)
    }

    /**
     * A session taken out of use, with the credentials its DELETE is to carry, read as it was taken
     * out. It is ended later, outside the caller's lock, and by then the credentials may have gone
     * with the server: a SQLite debugger entry's token leaves with its entry, a deleted server's
     * with the server. Read at the DELETE they would be empty, and the server would refuse it and
     * keep its session open.
     *
     * @property url the URL the session was built for.
     * @property credentials what the DELETE carries; null when they could not be read, when
     *   [close] reads them again.
     */
    class Released internal constructor(
        private val session: McpSession,
        private val url: String,
        private val credentials: McpCredentials?,
    ) {
        /**
         * Ends the session, best effort: sends a DELETE when it has a server-side id, so never under
         * a lock. An Exception or a LinkageError is survived; any other Error propagates.
         *
         * @param saved credentials the caller read before they could go, carried in place of the
         *   ones saved here when they were read for this session's URL, and ignored for any other:
         *   they do not belong there.
         */
        fun close(saved: SavedCredentials? = null) {
            val carried = saved?.takeIf { it.url == url }?.credentials ?: credentials
            runCatchingRecoverable { if (carried != null) session.close(carried) else session.close() }
        }
    }

    /**
     * A server's credentials as read for one URL, for a DELETE sent once they may have gone with
     * the server; see [saveCredentials].
     */
    class SavedCredentials internal constructor(internal val url: String, internal val credentials: McpCredentials)

    /**
     * [serverId]'s credentials, read now for the URL it has now: for a caller about to delete one of
     * the user's servers, which may find a session to end once the deletion has taken them away.
     * [Released.close] carries them only to a session built for that URL.
     *
     * @return them, or null for a server not stored or credentials that could not be read.
     */
    fun saveCredentials(serverId: String): SavedCredentials? = McpServerStore.readTogether {
        // The URL and the credentials read together, so they are the pair the server has.
        val url = McpServerStore.server(serverId)?.url ?: return@readTogether null
        runCatchingRecoverable { SavedCredentials(url, credentialsFor(serverId, url)) }
            .onFailure { e -> Log.d(TAG, "Could not read the credentials to end '$serverId' with: $e") }
            .getOrNull()
    }

    /** Drops the session for [serverId], ending it server-side when it had one. */
    fun invalidate(serverId: String) {
        detach(serverId)?.close()
    }

    /**
     * Takes the session for [serverId] out without ending it, with its credentials read now, for a
     * caller that must take it out under a lock of its own and end it once that lock is let go,
     * since ending it sends a DELETE.
     *
     * Taken under this object's lock, as [withSession] replaces a session under it. The credentials
     * are read once that is let go, still under the caller's lock: for a server of the user's that is
     * a Keystore read, never network. Safe to call under another lock: under this one, [withSession]
     * only checks the server is configured, which takes no lock (`McpServers.isConfigured`), and it
     * reads the credentials before it takes this one.
     *
     * @return the session taken out, or null when [serverId] had none.
     */
    fun detach(serverId: String): Released? = release(serverId) { url -> credentialsFor(serverId, url) }

    /**
     * As [detach], for a SQLite debugger entry that has already left the list, its app gone: its
     * token can no longer be read, so it is taken from [endpoint], the one the entry had. As for a
     * request, it goes only to the URL it belongs to. `AutomaticServers` does this, in the same step
     * that removes the entry.
     *
     * @param endpoint the endpoint the entry had when it was removed.
     */
    fun detachRemoved(serverId: String, endpoint: AutomaticEndpoint): Released? =
        release(serverId) { url -> automaticCredentials(endpoint, url) }

    /**
     * Ends every session, for the plugin shutting down. Every one taken out is ended, even when
     * reading one's credentials threw an Error; that Error is thrown once they have all been ended.
     */
    fun closeAll() {
        val released = ArrayList<Released>()
        var failure: Error? = null
        try {
            detachAll(released)
        } catch (e: Error) {
            failure = e
        }
        try {
            endAll(released)
        } catch (e: Error) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
        failure?.let { throw it }
    }

    /**
     * Ends each of [released], best effort, as [Released.close] does one. An Error from one, which
     * [Released.close] lets through, does not stop the rest: each is ended, and the first Error is
     * thrown once they all have been, the others added to it as suppressed.
     */
    fun endAll(released: List<Released>) {
        var first: Error? = null
        for (session in released) {
            try {
                session.close()
            } catch (e: Error) {
                first?.addSuppressed(e) ?: run { first = e }
            }
        }
        first?.let { throw it }
    }

    /**
     * Takes every session out without ending it, as [detach] does one: for a caller that empties
     * the map under a lock of its own and ends the sessions once that lock is let go, since each
     * close sends a DELETE and can wait for a handshake in flight.
     *
     * Every session that leaves the map is added to [into] as it leaves, so a caller that ends
     * [into] in a `finally` loses none. An Error from reading one session's credentials, which
     * [runCatchingRecoverable] lets through, does not stop the others: that session goes into
     * [into] with no credentials, so it is ended with none, the rest are taken out, and the first
     * Error is thrown after the last, the others added to it as suppressed.
     *
     * @param into receives the sessions taken out, each with its credentials.
     * @return [into].
     */
    fun detachAll(into: MutableList<Released> = ArrayList()): List<Released> {
        val taken = synchronized(this) { sessions.toMap().also { sessions.clear() } }
        var first: Error? = null
        for ((id, entry) in taken) {
            try {
                into += released(entry, id) { url -> credentialsFor(id, url) }
            } catch (e: Error) {
                // Explicit empty credentials: a close with none given would read them again, and throw.
                into += Released(entry.second, entry.first.url, McpCredentials("", emptyMap()))
                first?.addSuppressed(e) ?: run { first = e }
            }
        }
        first?.let { throw it }
        return into
    }

    /** Takes [serverId]'s session out; see [detach]. */
    private fun release(serverId: String, credentials: (url: String) -> McpCredentials): Released? {
        val entry = synchronized(this) { sessions.remove(serverId) } ?: return null
        return released(entry, serverId, credentials)
    }

    /**
     * [entry]'s session with its credentials, read for the URL it was built for. A failed read
     * leaves them unread: the close tries again, which beats not ending the session.
     */
    private fun released(
        entry: Pair<Key, McpSession>,
        serverId: String,
        credentials: (url: String) -> McpCredentials,
    ): Released {
        val read = runCatchingRecoverable { credentials(entry.first.url) }
            .onFailure { e -> Log.d(TAG, "Could not read the credentials to end '$serverId' with: $e") }
        return Released(entry.second, entry.first.url, read.getOrNull())
    }

    /**
     * Decrypts one server's credentials, for a request about to go out.
     *
     * Keystore work on every call rather than once per session; against a network round trip it
     * does not register, and it is what keeps the plaintext from outliving the request.
     *
     * @param serverId the server being called.
     * @param url the url the calling session was built for. A server's credentials are released
     *   only while it still points there; a session for a URL it has left gets none, so its
     *   `close()` and any call still in flight cannot hand the credentials meant for the new URL
     *   to the old one. That holds for an automatic entry, whose replaced endpoint would get the
     *   new process's token, and for a server of the user's, whose old URL would get the token
     *   and headers the user entered for the new one. A user's server's URL is read with its
     *   credentials, under the store's credentials lock, which a save holds while it writes all
     *   three: a read between its writes would pair the new URL with the old token and headers.
     * @return its token and headers; either may be empty.
     * @throws UnreadableSecretException when a stored credential cannot be decrypted here. Sending
     *   the request without it would earn a 401 and tell the user their token was refused.
     * @throws UnavailableSecretException when the keystore could not be reached to decrypt one, a
     *   failure to retry rather than to report as a lost credential.
     */
    private fun credentialsFor(serverId: String, url: String): McpCredentials {
        if (AutomaticServers.isAutomatic(serverId)) {
            // Read per request, so a token refreshed on the same port is used without a new session.
            return automaticCredentials(AutomaticServers.endpoint(serverId), url)
        }
        return McpServerStore.readTogether { storedCredentials(serverId, url) }
    }

    /** [credentialsFor] for a server of the user's. Call under `McpServerStore.readTogether`. */
    private fun storedCredentials(serverId: String, url: String): McpCredentials {
        // A server removed since, or moved to another URL, gets nothing; checked before the
        // Keystore is read, so its secrets are not even decrypted.
        if (McpServerStore.server(serverId)?.url != url) return McpCredentials("", emptyMap())
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
     * A SQLite debugger entry's credentials from [endpoint]: its token, but only while [url] is
     * that endpoint's own, so a session for a replaced endpoint cannot hand the new process's token
     * to the old port.
     * @param endpoint the entry's endpoint, or null when it has none.
     * @param url the URL the session was built for.
     */
    private fun automaticCredentials(endpoint: AutomaticEndpoint?, url: String): McpCredentials {
        val token = if (endpoint != null && AutomaticServers.urlFor(endpoint) == url) endpoint.token else ""
        return McpCredentials(token, emptyMap())
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
