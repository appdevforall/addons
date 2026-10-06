package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.models.ChatMessage
import com.itsaky.androidide.plugins.aicore.models.ChatSession
import com.itsaky.androidide.plugins.aicore.models.Sender

/**
 * The versions a chat grows when an older prompt is edited: the edited prompt starts a new branch
 * from the messages above it, and the original is kept beside it rather than discarded.
 *
 * A session's `messages` is always the branch on screen; every other branch's messages sit in
 * `otherBranches`, each naming the message it follows. Kept free of Android and of the ViewModel,
 * so the tree rules are unit-testable on their own.
 */
internal object ChatBranches {

    /** The `selectedBranches` key for the chat's start, which has no message of its own. */
    const val ROOT = ""

    /**
     * Where a prompt sits among its versions.
     *
     * @property index zero-based, oldest version first.
     * @property count how many versions there are; always at least two.
     */
    data class Position(val index: Int, val count: Int)

    /** Oldest first; the id breaks a same-millisecond tie, so no order depends on what is on screen. */
    private val versionOrder = compareBy<ChatMessage>({ it.timestamp }, { it.id })

    /**
     * Every prompt on screen that has other versions, and where it sits among them.
     *
     * @param session the current chat.
     * @return message id to position; empty for a chat that was never forked.
     */
    fun positions(session: ChatSession): Map<String, Position> {
        val others = session.otherBranches.orEmpty()
        if (others.isEmpty()) return emptyMap()
        val path = session.messages
        val result = HashMap<String, Position>()
        path.forEachIndexed { index, message ->
            if (message.sender != Sender.USER) return@forEachIndexed
            val versions = versionsAt(path, others, index)
            if (versions.size > 1) {
                result[message.id] = Position(versions.indexOfFirst { it.id == message.id }, versions.size)
            }
        }
        return result
    }

    /**
     * Moves [messageId] and everything after it into `otherBranches`, leaving the messages above
     * it on screen for the edited prompt to follow. Nothing is lost: the original is now a version.
     *
     * @param session the current chat.
     * @param messageId a user prompt on screen.
     * @return the forked session, or null when [messageId] is not a prompt on screen.
     */
    fun fork(session: ChatSession, messageId: String): ChatSession? {
        val path = session.messages
        val index = path.indexOfFirst { it.id == messageId }
        if (index < 0 || path[index].sender != Sender.USER) return null
        val (others, selected) = archive(session, index)
        return session.copy(
            messages = path.subList(0, index).toList(),
            otherBranches = others,
            selectedBranches = selected,
        )
    }

    /**
     * Puts version [targetId] of the prompt [messageId] on screen, followed by whatever was last
     * on screen after it; the branch it replaces is kept for switching back.
     *
     * @param session the current chat.
     * @param messageId a user prompt on screen.
     * @param targetId another version of that prompt, from `otherBranches`.
     * @return the switched session, or null when [targetId] is not a version of [messageId].
     */
    fun switchTo(session: ChatSession, messageId: String, targetId: String): ChatSession? {
        val path = session.messages
        val index = path.indexOfFirst { it.id == messageId }
        if (index < 0 || path[index].sender != Sender.USER) return null
        val parent = parentIdAt(path, index)
        val target = session.otherBranches.orEmpty().firstOrNull {
            it.id == targetId && it.sender == Sender.USER && it.parentId == parent
        } ?: return null
        val (archived, selected) = archive(session, index)
        val tail = descend(target, archived, selected)
        val tailIds = tail.mapTo(HashSet()) { it.id }
        return session.copy(
            // Order says what follows what on screen, so the stored link is dropped there.
            messages = path.subList(0, index) + tail.map { it.copy(parentId = null) },
            otherBranches = archived.filterNot { it.id in tailIds },
            selectedBranches = selected + ((parent ?: ROOT) to target.id),
        )
    }

    /**
     * The version of [messageId] [step] places away from it, oldest first.
     *
     * @return that version's id, or null past either end or when the prompt has one version.
     */
    fun versionId(session: ChatSession, messageId: String, step: Int): String? {
        val path = session.messages
        val index = path.indexOfFirst { it.id == messageId }
        if (index < 0) return null
        val versions = versionsAt(path, session.otherBranches.orEmpty(), index)
        val target = versions.indexOfFirst { it.id == messageId } + step
        return versions.getOrNull(target)?.id?.takeUnless { it == messageId }
    }

    /**
     * Drops the on-screen messages [doomed] accepts, re-pointing every stored version that followed
     * one of them at the nearest message kept above it, so no version is left following nothing.
     *
     * @param session the current chat.
     * @param doomed picks the on-screen messages to remove; stored versions are never removed.
     * @return the session without them; [session] itself when nothing matched.
     */
    fun removeFromScreen(session: ChatSession, doomed: (ChatMessage) -> Boolean): ChatSession {
        val path = session.messages
        if (path.none(doomed)) return session
        // What each removed message is replaced by as a parent: the nearest survivor above it.
        val successor = HashMap<String, String?>()
        var keptAbove: String? = null
        for (message in path) {
            if (doomed(message)) successor[message.id] = keptAbove else keptAbove = message.id
        }
        fun repoint(id: String?): String? = if (id != null && id in successor) successor[id] else id
        return session.copy(
            messages = path.filterNot(doomed),
            otherBranches = session.otherBranches?.map { it.copy(parentId = repoint(it.parentId)) },
            selectedBranches = session.selectedBranches?.mapKeys { (key, _) ->
                if (key == ROOT) key else repoint(key) ?: ROOT
            },
        )
    }

    /** The prompt at [index] and every stored version of it, oldest first. */
    private fun versionsAt(
        path: List<ChatMessage>,
        others: List<ChatMessage>,
        index: Int,
    ): List<ChatMessage> {
        val parent = parentIdAt(path, index)
        val siblings = others.filter { it.sender == Sender.USER && it.parentId == parent }
        return (siblings + path[index]).sortedWith(versionOrder)
    }

    /** What the message at [index] follows: the one above it, or null at the chat's start. */
    private fun parentIdAt(path: List<ChatMessage>, index: Int): String? =
        if (index == 0) null else path[index - 1].id

    /**
     * Links the branch from [index] down into `otherBranches`, and records it as the one last on
     * screen at every step, so a later switch back lands on it and not on an older sibling.
     */
    private fun archive(session: ChatSession, index: Int): Pair<List<ChatMessage>, Map<String, String>> {
        val path = session.messages
        val selected = session.selectedBranches.orEmpty().toMutableMap()
        val tail = (index until path.size).map { i ->
            val parent = parentIdAt(path, i)
            selected[parent ?: ROOT] = path[i].id
            path[i].copy(parentId = parent)
        }
        return (session.otherBranches.orEmpty() + tail) to selected
    }

    /**
     * [start] and the branch below it that was last on screen, falling back on the newest where
     * nothing was recorded.
     */
    private fun descend(
        start: ChatMessage,
        others: List<ChatMessage>,
        selected: Map<String, String>,
    ): List<ChatMessage> {
        val children = others.groupBy { it.parentId }
        val branch = mutableListOf(start)
        while (true) {
            val next = children[branch.last().id] ?: break
            branch += next.firstOrNull { it.id == selected[branch.last().id] }
                ?: next.maxWith(versionOrder)
        }
        return branch
    }
}
