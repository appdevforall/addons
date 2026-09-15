package com.itsaky.androidide.plugins.aicore.managers

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ChatSession

private const val TAG = "$LOG_PREFIX.ChatStorageManager"

/**
 * Keeps the chat history across restarts, as whole sessions written to SharedPreferences as JSON.
 * Every read is total and every write replaces the stored list, so it holds no session state of its
 * own; `ChatViewModel` owns the live list and is the only caller.
 *
 * History is per project: both keys carry [projectKey], so one preferences file holds a separate
 * namespace per project and a conversation never surfaces in a codebase it was not written in.
 *
 * **Threading.** Constructed on the main thread, because the migration and first read have to
 * settle before anything can mutate the sessions they produce. Reads may run on any thread;
 * [persist] blocks until the write lands, so it belongs on a background one. It also serializes
 * the list it is handed, so the caller must hand it a snapshot no other thread is still mutating —
 * a session's `messages` list grows on the main thread for the whole of a streamed reply.
 *
 * **Failure.** Nothing here throws. Losing the history must not stop the Agent from opening, and a
 * write that fails must not take the IDE down mid-conversation, so every failure is logged and
 * degrades to "nothing stored".
 *
 * @param context any Android context; only its SharedPreferences are used
 * @param projectKey the open project's namespace, from [ProjectKey]
 */
class ChatStorageManager(context: Context, private val projectKey: String) {

    /** Null when the preferences file could not be opened; every method below then does nothing. */
    private val prefs: SharedPreferences? = try {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (e: Exception) {
        Log.e(TAG, "Could not open $PREFS_NAME; chat history is disabled this session", e)
        null
    }

    private val gson = Gson()

    private val sessionsKey = KEY_SESSIONS_PREFIX + projectKey
    private val currentSessionIdKey = KEY_CURRENT_SESSION_ID_PREFIX + projectKey

    companion object {
        private const val PREFS_NAME = "ai_assistant_chats"

        private const val KEY_SESSIONS_PREFIX = "chat_sessions_"
        private const val KEY_CURRENT_SESSION_ID_PREFIX = "current_session_id_"

        /** The single global keys every release before per-project history wrote to. */
        private const val LEGACY_KEY_SESSIONS = "chat_sessions"
        private const val LEGACY_KEY_CURRENT_SESSION_ID = "current_session_id"
    }

    init {
        migrateLegacyGlobalHistory()
    }

    /**
     * Replaces this project's stored history and selection, in one edit.
     *
     * Both keys move together on purpose: written back-to-back, a process death between them
     * leaves a selection naming a session the stored list has never heard of. `commit()` rather
     * than `apply()` for the same reason — the write is done when this returns, instead of being
     * queued for whichever later main-thread pause flushes it. It blocks, so call it off the main
     * thread.
     *
     * @param sessions a snapshot of every session to keep, owned by the caller and not being
     *   mutated elsewhere; an empty list clears the history
     * @param currentSessionId the session the user is looking at, or null to leave none selected
     */
    fun persist(sessions: List<ChatSession>, currentSessionId: String?) {
        val prefs = prefs ?: return
        try {
            prefs.edit()
                .putString(sessionsKey, gson.toJson(sessions))
                .putString(currentSessionIdKey, currentSessionId)
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Could not save ${sessions.size} session(s) for project $projectKey", e)
        }
    }

    /**
     * Reads this project's history back. Unreadable JSON yields an empty list rather than throwing:
     * losing the history must not stop the Agent from opening, and a [ChatSession] field added in a
     * later release arrives here as exactly that case.
     *
     * Sessions stamped with another project are dropped, as a second line of defence behind the
     * namespaced key. Unstamped ones predate the field and are adopted by this project.
     *
     * @return the stored sessions, or an empty list when nothing is stored or the blob is unusable
     */
    fun loadSessions(): List<ChatSession> {
        val json = readString(sessionsKey) ?: return emptyList()
        return parseSessions(json)
            .filter { it.projectKey == null || it.projectKey == projectKey }
            .map { if (it.projectKey == null) it.copy(projectKey = projectKey) else it }
    }

    /**
     * The session the user was last looking at in this project. Not validated against
     * [loadSessions] — the id may name a session since deleted, so the caller resolves it and falls
     * back on its own.
     *
     * @return the stored session id, or null when none was recorded
     */
    fun loadCurrentSessionId(): String? = readString(currentSessionIdKey)

    /**
     * Moves the one pre-namespace history blob into this project's namespace, once.
     *
     * The legacy blob has no project of its own, so it can only be attributed to whichever project
     * is open when the upgraded build first reads it. Removing the legacy keys is what makes this
     * run once; a second project opened later finds nothing left to claim.
     *
     * Skipped entirely while no project is open, so the history is not stranded in the
     * [ProjectKey.NO_PROJECT] namespace before the user has opened anything. A failure here leaves
     * the legacy keys in place, so the next construction tries again.
     */
    private fun migrateLegacyGlobalHistory() {
        val prefs = prefs ?: return
        if (projectKey == ProjectKey.NO_PROJECT) return
        try {
            val legacyJson = prefs.getString(LEGACY_KEY_SESSIONS, null)
            val legacyCurrentId = prefs.getString(LEGACY_KEY_CURRENT_SESSION_ID, null)
            if (legacyJson == null && legacyCurrentId == null) return

            val editor = prefs.edit()
            // Anything already written under this namespace is newer than the legacy blob and wins.
            if (legacyJson != null && prefs.getString(sessionsKey, null) == null) {
                val adopted = parseSessions(legacyJson).map { it.copy(projectKey = projectKey) }
                editor.putString(sessionsKey, gson.toJson(adopted))
                Log.i(TAG, "Adopted ${adopted.size} legacy session(s) into project $projectKey")
            }
            // Migrated on its own key rather than only alongside the blob: nesting the two left
            // the last-open conversation behind whenever the blob did not move, and an id naming
            // no session is harmless — loadSessions already falls back.
            if (legacyCurrentId != null && prefs.getString(currentSessionIdKey, null) == null) {
                editor.putString(currentSessionIdKey, legacyCurrentId)
            }
            editor.remove(LEGACY_KEY_SESSIONS)
            editor.remove(LEGACY_KEY_CURRENT_SESSION_ID)
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Could not migrate legacy chat history into project $projectKey", e)
        }
    }

    /**
     * @param key the preference to read.
     * @return the stored string, or null when it is absent or unreadable.
     */
    private fun readString(key: String): String? = try {
        prefs?.getString(key, null)
    } catch (e: Exception) {
        Log.e(TAG, "Could not read '$key'", e)
        null
    }

    /**
     * @param json a stored sessions blob.
     * @return the sessions it holds, or an empty list when it cannot be read.
     */
    private fun parseSessions(json: String): List<ChatSession> {
        val type = object : TypeToken<List<ChatSession>>() {}.type
        return try {
            gson.fromJson<List<ChatSession>?>(json, type)
                ?.filterNotNull()
                ?.map(::withMessages)
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "Discarding an unreadable chat history blob for project $projectKey", e)
            emptyList()
        }
    }

    /**
     * Repairs a session whose transcript did not survive the round trip. Gson builds instances
     * through Unsafe, so a blob truncated mid-write — or one written before the field existed —
     * deserializes `messages` as null however Kotlin declares it, and the first caller to read the
     * transcript throws rather than showing an empty chat.
     *
     * @param session a session straight out of Gson.
     * @return [session], or a copy with an empty transcript when it arrived without one.
     */
    @Suppress("SENSELESS_COMPARISON")
    private fun withMessages(session: ChatSession): ChatSession =
        if (session.messages == null) session.copy(messages = emptyList()) else session
}
