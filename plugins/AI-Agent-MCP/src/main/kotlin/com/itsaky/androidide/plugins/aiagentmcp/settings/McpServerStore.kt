package com.itsaky.androidide.plugins.aiagentmcp.settings

import android.content.SharedPreferences
import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.security.UnavailableSecretException
import com.itsaky.androidide.plugins.aiagentmcp.security.UnreadableSecretException
import com.itsaky.androidide.plugins.aiagentmcp.security.secureTokenStore
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHeaders
import com.itsaky.androidide.plugins.security.KeystoreSecretStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "$LOG_PREFIX.McpServerStore"

/**
 * The configured servers, their per-tool toggles and their tokens.
 *
 * One JSON blob under one preference key rather than a key per field: the list is small, always
 * read whole, and a partial write is what leaves a server with a URL and no toggles.
 */
object McpServerStore {

    /** Preferences file holding this plugin's settings. */
    const val PREFERENCE_FILE = "McpSettings"

    private const val KEY_SERVERS = "servers"
    private const val KEY_TOKEN_PREFIX = "token_"
    private const val KEY_HEADERS_PREFIX = "headers_"

    private const val FIELD_ID = "id"
    private const val FIELD_NAME = "name"
    private const val FIELD_URL = "url"
    private const val FIELD_ENABLED = "enabled"
    private const val FIELD_KNOWN_TOOLS = "knownTools"
    private const val FIELD_ENABLED_TOOLS = "enabledTools"

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Serialises every read-modify-write of the server list.
     *
     * The list is one JSON blob, so each mutation reads it whole and puts it back whole: a refresh
     * on `McpPlugin`'s scope and a toggle on the screen's dispatcher interleave, and the loser's
     * write vanishes — the switch stays on while `enabledTools` on disk no longer holds it.
     */
    private val lock = Any()

    /** Registers [listener], called after any change to the servers or their toggles. */
    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    /** Removes a listener added by [addChangeListener]. */
    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Every configured server, in the order they were added.
     * @return the servers; empty before any is configured, or when the stored blob is unreadable.
     */
    fun servers(): List<McpServer> {
        val raw = prefs()?.getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::toServer)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stored server list is unreadable; treating it as empty", e)
            emptyList()
        }
    }

    /**
     * Stores the two fields the add/edit dialog owns, leaving every other field as stored.
     *
     * The dialog holds a snapshot taken when it opened, while the tool switches write straight
     * through as they are tapped. Saving that snapshot whole is what silently reverted them, so
     * the merge happens here rather than in any caller that might forget it.
     *
     * @param server the candidate, carrying the id plus the edited name and URL.
     * @return the record as it now stands, for a caller holding a snapshot to refresh from.
     */
    fun saveDetails(server: McpServer): McpServer {
        val merged = synchronized(lock) {
            mergeDetails(servers().firstOrNull { it.id == server.id }, server)
                .also(::upsertLocked)
        }
        fireChanged()
        return merged
    }

    /**
     * Merges a dialog's edits onto the stored record.
     *
     * Pure and separate from the write, so the rule this screen kept getting wrong — everything but
     * the name and the URL comes from the store, never from the caller — is testable without a
     * device.
     *
     * @param stored the record as it stands, or null for a server being added.
     * @param edited the candidate the dialog built from its snapshot.
     * @return the record to write.
     */
    internal fun mergeDetails(stored: McpServer?, edited: McpServer): McpServer =
        stored?.copy(name = edited.name, url = edited.url) ?: edited

    /**
     * Switches a whole server on or off, by id, so a stale snapshot cannot carry its toggles back.
     * @param id the server.
     * @param enabled whether it may contribute tools.
     */
    fun setEnabled(id: String, enabled: Boolean) {
        synchronized(lock) {
            val server = servers().firstOrNull { it.id == id } ?: return
            upsertLocked(server.copy(enabled = enabled))
        }
        fireChanged()
    }

    /**
     * The stored record for [id].
     * @param id the server.
     * @return the record, or null when it was never saved or has since been removed.
     */
    fun server(id: String): McpServer? = servers().firstOrNull { it.id == id }

    /**
     * Adds or replaces [server], matched by [McpServer.id]. Call holding [lock].
     *
     * Private on purpose: every field of the record is written, so a caller passing anything but a
     * record it just read back would revert whatever changed in between.
     *
     * @param server the server to store.
     */
    private fun upsertLocked(server: McpServer) {
        val current = servers().toMutableList()
        val index = current.indexOfFirst { it.id == server.id }
        if (index >= 0) current[index] = server else current += server
        writeLocked(current)
    }

    /**
     * Removes a server and forgets its token.
     * @param id the server to remove.
     */
    fun remove(id: String) {
        synchronized(lock) {
            prefs()?.edit()
                ?.remove(KEY_TOKEN_PREFIX + id)
                ?.remove(KEY_HEADERS_PREFIX + id)
                ?.apply()
            writeLocked(servers().filterNot { it.id == id })
        }
        fireChanged()
    }

    /**
     * Stores a server's bearer token, encrypted; blank forgets it.
     *
     * Keystore work, so call this off the main thread.
     *
     * @param id the server the token belongs to.
     * @param token the token, or blank to remove it.
     * @return true once it is on disk, or removed for a blank one; false when encrypting it or the
     *   write itself failed, which is what the pane must not report as saved.
     */
    fun setToken(id: String, token: String): Boolean {
        val stored = secureTokenStore.write(prefs(), KEY_TOKEN_PREFIX + id, token)
        // Like every other mutator: a new credential has to reach the agent, or it keeps calling
        // with the old one until something else happens to touch the store.
        fireChanged()
        return stored
    }

    /**
     * Reads a server's bearer token.
     *
     * Keystore work, so call this off the main thread.
     *
     * @param id the server.
     * @return what is stored: nothing, the token, a token this device can no longer read, or one
     *   the keystore would not open just now.
     */
    fun token(id: String): KeystoreSecretStore.Stored =
        secureTokenStore.readAndMigrate(prefs(), KEY_TOKEN_PREFIX + id)

    /** True when a token is stored for [id], without decrypting it. */
    fun hasToken(id: String): Boolean = prefs()?.contains(KEY_TOKEN_PREFIX + id) == true

    /**
     * The extra headers configured for a server.
     *
     * Encrypted with the token, not stored beside the URL: a header is as often a credential as the
     * token is — an API key, a signed claim — and the store cannot tell which is which.
     *
     * Keystore work, so call this off the main thread.
     *
     * @param id the server.
     * @return the headers in the order they were entered; empty when there are none.
     */
    fun headers(id: String): Map<String, String> {
        val stored = secureTokenStore.readAndMigrate(prefs(), KEY_HEADERS_PREFIX + id)
        if (stored is KeystoreSecretStore.Stored.Unreadable) {
            // Same failure as an unreadable token, and reported the same way: sending the request
            // without them would look like the server refusing a credential that is still correct.
            throw UnreadableSecretException("The stored headers for '$id' cannot be decrypted.")
        }
        if (stored is KeystoreSecretStore.Stored.Unavailable) {
            // Kept apart from the above: these headers are very likely intact, so the caller is
            // told to retry instead of asking the user to enter them again.
            throw UnavailableSecretException("The stored headers for '$id' could not be read just now.")
        }
        val raw = (stored as? KeystoreSecretStore.Stored.Value)?.plain ?: return emptyMap()
        return try {
            val json = JSONObject(raw)
            val parsed = LinkedHashMap<String, String>()
            json.keys().forEach { name -> parsed[name] = json.optString(name) }
            McpHeaders.sanitize(parsed)
        } catch (e: Exception) {
            Log.e(TAG, "Stored headers for '$id' are unreadable; treating them as absent", e)
            emptyMap()
        }
    }

    /**
     * Stores a server's extra headers, encrypted; an empty map forgets them.
     *
     * Keystore work, so call this off the main thread.
     *
     * @param id the server the headers belong to.
     * @param headers the headers to store; unusable pairs are dropped.
     * @return true once they are on disk, or removed for an empty map; false when encrypting them
     *   or the write itself failed. Forgetting them on a store that was never opened is success:
     *   there is nothing on disk to remove.
     */
    fun setHeaders(id: String, headers: Map<String, String>): Boolean {
        val clean = McpHeaders.sanitize(headers)
        val key = KEY_HEADERS_PREFIX + id
        // The empty map goes through write() as a blank, which removes the entry, rather than
        // through a bare remove/apply: the token's clear is synchronous and says whether it landed,
        // and headers left on disk for a credential the user just forgot are the same leak.
        val payload = if (clean.isEmpty()) {
            ""
        } else {
            val json = JSONObject()
            clean.forEach { (name, value) -> json.put(name, value) }
            json.toString()
        }
        // A clear with no preferences behind it has nothing to remove, so it succeeded; only a
        // write with something to store has genuinely failed.
        val prefs = prefs() ?: return clean.isEmpty()
        val stored = secureTokenStore.write(prefs, key, payload)
        fireChanged()
        return stored
    }

    /** Whether any extra header is configured for [id], without decrypting them. */
    fun hasHeaders(id: String): Boolean = prefs()?.contains(KEY_HEADERS_PREFIX + id) == true

    /**
     * Records the tools a server advertised, dropping toggles for tools it no longer has.
     * @param id the server.
     * @param toolNames the tools it just listed.
     */
    fun setKnownTools(id: String, toolNames: List<String>) {
        synchronized(lock) {
            val server = servers().firstOrNull { it.id == id } ?: return
            upsertLocked(
                server.copy(
                    knownTools = toolNames,
                    enabledTools = server.enabledTools.filterTo(mutableSetOf()) { it in toolNames },
                )
            )
        }
        fireChanged()
    }

    /**
     * Switches one tool on or off for a server.
     * @param id the server.
     * @param toolName the tool.
     * @param enabled whether the agent may see it.
     */
    fun setToolEnabled(id: String, toolName: String, enabled: Boolean) {
        synchronized(lock) {
            val server = servers().firstOrNull { it.id == id } ?: return
            val tools = server.enabledTools.toMutableSet()
            if (enabled) tools += toolName else tools -= toolName
            upsertLocked(server.copy(enabledTools = tools))
        }
        fireChanged()
    }

    /** A server with a fresh id, ready to be edited and stored. */
    fun newServer(name: String, url: String): McpServer =
        McpServer(id = UUID.randomUUID().toString(), name = name, url = url)

    /** Puts the whole list back. Call holding [lock]; the caller fires the listeners. */
    private fun writeLocked(servers: List<McpServer>) {
        val array = JSONArray()
        servers.forEach { array.put(toJson(it)) }
        prefs()?.edit()?.putString(KEY_SERVERS, array.toString())?.apply()
    }

    private fun toJson(server: McpServer): JSONObject = JSONObject().apply {
        put(FIELD_ID, server.id)
        put(FIELD_NAME, server.name)
        put(FIELD_URL, server.url)
        put(FIELD_ENABLED, server.enabled)
        put(FIELD_KNOWN_TOOLS, JSONArray(server.knownTools))
        put(FIELD_ENABLED_TOOLS, JSONArray(server.enabledTools.toList()))
    }

    private fun toServer(json: JSONObject): McpServer? {
        val id = json.optString(FIELD_ID).takeIf { it.isNotBlank() } ?: return null
        return McpServer(
            id = id,
            name = json.optString(FIELD_NAME).ifBlank { json.optString(FIELD_URL) },
            url = json.optString(FIELD_URL),
            enabled = json.optBoolean(FIELD_ENABLED, true),
            knownTools = json.optJSONArray(FIELD_KNOWN_TOOLS).toStringList(),
            enabledTools = json.optJSONArray(FIELD_ENABLED_TOOLS).toStringList().toSet(),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { name -> name.isNotBlank() } }
    }

    private fun prefs(): SharedPreferences? =
        McpPlugin.getContext()?.getPluginSharedPreferences(PREFERENCE_FILE)

    private fun fireChanged() {
        for (listener in listeners) {
            try {
                listener()
            } catch (e: Throwable) {
                Log.e(TAG, "A settings change listener threw", e)
            }
        }
    }
}
