package com.itsaky.androidide.plugins.aicore.models

/**
 * What tapping a row in the chat history does, and which rows the user has ticked.
 *
 * Immutable: every change hands back a new value, so the screen holds one field and the list is
 * rebuilt from it rather than from views being toggled in place.
 *
 * @property isSelecting false while tapping a row opens that conversation; true once tapping a row
 *   ticks it instead, for an action over several at once.
 * @property ids the ticked conversations. May name a session that has since gone — nothing reads
 *   this as a count, and deleting by it ignores what is no longer there.
 */
data class SessionSelection(
    val isSelecting: Boolean,
    val ids: Set<String>,
) {
    companion object {
        /** The ordinary list: one tap, one conversation. */
        val BROWSING = SessionSelection(isSelecting = false, ids = emptySet())

        /** Picking several, nothing ticked yet. */
        val SELECTING = SessionSelection(isSelecting = true, ids = emptySet())
    }

    /**
     * @param id the conversation the user tapped.
     * @return this selection with [id] ticked, or unticked when it already was. Always selecting,
     *   so a tick can never be held by a list that is not picking.
     */
    fun toggle(id: String): SessionSelection = SessionSelection(
        isSelecting = true,
        ids = if (id in ids) ids - id else ids + id,
    )
}

/**
 * One row of the chat-history list, holding what the row draws and nothing else.
 *
 * A projection rather than the [ChatSession] itself: the list re-renders on every streamed token,
 * and a DiffUtil callback comparing whole sessions would deep-compare every transcript in the
 * project on each one. These fields are what the row shows, so comparing them is exactly what
 * "this row is unchanged" means.
 *
 * @property id the session this row stands for, which is all the actions need.
 * @property title already resolved, so the untitled fallback has one home rather than one per
 *   caller that renders a session's name.
 * @property isUntitled whether [title] is only that fallback. Kept apart from the title itself
 *   because the rename prompt seeds its field from the row: prefilling it with the fallback would
 *   turn "this chat names itself after its first message" into a fixed name on the next OK.
 * @property date when the conversation was started, formatted.
 * @property messageCount how much is in it.
 * @property isActive whether this is the conversation the chat is currently in.
 * @property isSelecting whether the list is picking rather than browsing. Carried per row, not
 *   held beside the list: the mode changes what every row draws, and a row that compares equal to
 *   its old value is a row DiffUtil never rebinds.
 * @property isSelected whether this row is ticked; always false while [isSelecting] is false.
 */
data class SessionRow(
    val id: String,
    val title: String,
    val isUntitled: Boolean,
    val date: String,
    val messageCount: Int,
    val isActive: Boolean,
    val isSelecting: Boolean,
    val isSelected: Boolean,
)

/**
 * Turns the live sessions into the chat-history list.
 *
 * Pure and free of Android types, so the ordering, the untitled fallback and the ticks unit-test on
 * their own rather than through a dialog.
 */
object ChatSessionRows {

    /**
     * @param sessions the project's sessions, in whatever order they are stored.
     * @param currentSessionId the conversation the chat is in, or null when there is none.
     * @param untitledTitle what to call a session that is neither renamed nor started; the caller
     *   resolves it from strings.xml, which this has no Context to reach.
     * @param selection what the list is doing and which rows are ticked.
     * @param limit how many rows to build, applied *after* the ordering so growing it appends to
     *   what is already on screen rather than reshuffling it. See [SessionPaging].
     * @param titlePending sessions whose title is still being written; they show [pendingTitle]
     *   and count as untitled, so a rename started meanwhile does not keep the placeholder.
     * @param pendingTitle the loading label, resolved from strings.xml by the caller.
     * @return one row per session in [newestFirst] order — a conversation started today is the one
     *   the user came to the list to find, and the order must not depend on which row was written
     *   last.
     */
    fun from(
        sessions: List<ChatSession>,
        currentSessionId: String?,
        untitledTitle: String,
        selection: SessionSelection = SessionSelection.BROWSING,
        limit: Int = Int.MAX_VALUE,
        titlePending: Set<String> = emptySet(),
        pendingTitle: String = untitledTitle,
    ): List<SessionRow> = sessions
        .newestFirst()
        .take(limit.coerceAtLeast(0))
        .map { session ->
            val pending = session.id in titlePending
            SessionRow(
                id = session.id,
                title = if (pending) pendingTitle else session.displayTitle ?: untitledTitle,
                isUntitled = pending || session.displayTitle == null,
                date = session.formattedDate,
                messageCount = session.messages.size,
                isActive = session.id == currentSessionId,
                isSelecting = selection.isSelecting,
                isSelected = selection.isSelecting && session.id in selection.ids,
            )
        }
}

/**
 * How much of the chat history is built into rows at a time.
 *
 * Every session of the open project is already in memory — the store loads them all — so this pages
 * the *rendering*, not the reading. A project with hundreds of conversations would otherwise inflate
 * hundreds of rows the moment the sidebar opens, on a device where that is the visible stall.
 *
 * Pure and free of Android types, so the growth rule unit-tests without a RecyclerView.
 */
object SessionPaging {

    /**
     * How many more rows to build once the list is scrolled near the end of what it has.
     *
     * @param built how many rows the list is showing now.
     * @param total how many conversations there are.
     * @param pageSize how many to add per step; a non-positive page would never grow the list, so
     *   it is floored at one.
     * @return the new row count, never past [total] and never below [built] — growth only ever
     *   appends, because shrinking it under a user mid-scroll would jump the list. Capped and
     *   floored in that order, and not as one `coerceIn`: [total] below [built] is an ordinary
     *   state — conversations deleted while the panel was open — and an inverted range throws.
     */
    fun grow(built: Int, total: Int, pageSize: Int): Int =
        (built + pageSize.coerceAtLeast(1)).coerceAtMost(total).coerceAtLeast(built)

    /**
     * Whether the list should grow now.
     *
     * @param lastVisible position of the last row on screen, or -1 when the list is empty.
     * @param built how many rows the list is showing now.
     * @param total how many conversations there are.
     * @param lookahead how many rows may still be ahead of [lastVisible] before the next page is
     *   appended, so the page lands before the user reaches the bottom rather than after.
     * @return true when there is more to show and the user is within [lookahead] of the end.
     */
    fun shouldGrow(lastVisible: Int, built: Int, total: Int, lookahead: Int): Boolean =
        built < total && lastVisible >= built - lookahead - 1
}
