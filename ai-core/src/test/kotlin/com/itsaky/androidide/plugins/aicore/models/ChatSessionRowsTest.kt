package com.itsaky.androidide.plugins.aicore.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val UNTITLED = "New chat"

/**
 * The chat-history list's ordering, titling and active marker, away from the dialog that shows it.
 */
class ChatSessionRowsTest {

    @Test
    fun givenSessionsInStorageOrder_whenBuildingRows_thenTheNewestComesFirst() {
        val rows = ChatSessionRows.from(
            listOf(session("older", createdAt = 1_000), session("newer", createdAt = 2_000)),
            currentSessionId = null,
            untitledTitle = UNTITLED,
        )

        assertEquals(listOf("newer", "older"), rows.map { it.id })
    }

    @Test
    fun givenAStartedSession_whenBuildingRows_thenItIsTitledAfterItsFirstUserMessage() {
        val rows = ChatSessionRows.from(
            listOf(session("s1", userText = "why does the build fail")),
            currentSessionId = null,
            untitledTitle = UNTITLED,
        )

        assertEquals("why does the build fail", rows.single().title)
    }

    @Test
    fun givenARenamedSession_whenBuildingRows_thenTheNameWins() {
        val rows = ChatSessionRows.from(
            listOf(session("s1", userText = "why does the build fail", name = "Build failure")),
            currentSessionId = null,
            untitledTitle = UNTITLED,
        )

        assertEquals("Build failure", rows.single().title)
    }

    @Test
    fun givenASessionThatIsNeitherNamedNorStarted_whenBuildingRows_thenTheFallbackIsUsed() {
        val rows = ChatSessionRows.from(
            listOf(session("s1")),
            currentSessionId = null,
            untitledTitle = UNTITLED,
        )

        assertEquals(UNTITLED, rows.single().title)
        // The rename prompt reads this to know not to seed its field with the fallback.
        assertTrue(rows.single().isUntitled)
    }

    @Test
    fun givenASessionWithATitleOfItsOwn_whenBuildingRows_thenItIsNotMarkedUntitled() {
        val rows = ChatSessionRows.from(
            listOf(session("s1", userText = "why does the build fail")),
            currentSessionId = null,
            untitledTitle = UNTITLED,
        )

        assertFalse(rows.single().isUntitled)
    }

    @Test
    fun givenACurrentSession_whenBuildingRows_thenOnlyItIsMarkedActive() {
        val rows = ChatSessionRows.from(
            listOf(session("s1"), session("s2")),
            currentSessionId = "s2",
            untitledTitle = UNTITLED,
        )

        assertTrue(rows.single { it.id == "s2" }.isActive)
        assertFalse(rows.single { it.id == "s1" }.isActive)
    }

    @Test
    fun givenNoCurrentSession_whenBuildingRows_thenNoRowIsMarkedActive() {
        val rows = ChatSessionRows.from(
            listOf(session("s1"), session("s2")),
            currentSessionId = null,
            untitledTitle = UNTITLED,
        )

        assertTrue(rows.none { it.isActive })
    }

    @Test
    fun givenASessionWithATranscript_whenBuildingRows_thenTheRowCountsEveryMessage() {
        val session = ChatSession(
            id = "s1",
            messages = listOf(
                ChatMessage(text = "ask", sender = Sender.USER),
                ChatMessage(text = "answer", sender = Sender.AGENT),
                ChatMessage(text = "a notice", sender = Sender.SYSTEM),
            ),
        )

        val rows = ChatSessionRows.from(listOf(session), "s1", UNTITLED)

        assertEquals(3, rows.single().messageCount)
    }

    @Test
    fun givenTheListIsBrowsing_whenBuildingRows_thenNoRowIsPickingOrTicked() {
        val rows = ChatSessionRows.from(listOf(session("s1")), "s1", UNTITLED)

        assertFalse(rows.single().isSelecting)
        assertFalse(rows.single().isSelected)
    }

    @Test
    fun givenSomeSessionsTicked_whenBuildingRows_thenOnlyThoseAreSelected() {
        val rows = ChatSessionRows.from(
            listOf(session("s1"), session("s2"), session("s3")),
            currentSessionId = null,
            untitledTitle = UNTITLED,
            selection = SessionSelection(isSelecting = true, ids = setOf("s1", "s3")),
        )

        assertEquals(listOf("s1", "s3"), rows.filter { it.isSelected }.map { it.id })
        // Every row has to carry the mode, or DiffUtil never rebinds the untouched ones.
        assertTrue(rows.all { it.isSelecting })
    }

    @Test
    fun givenATickedSessionThatIsGone_whenBuildingRows_thenNothingClaimsItStillExists() {
        val rows = ChatSessionRows.from(
            listOf(session("s1")),
            currentSessionId = null,
            untitledTitle = UNTITLED,
            selection = SessionSelection(isSelecting = true, ids = setOf("s1", "deleted")),
        )

        assertEquals(1, rows.count { it.isSelected })
    }

    @Test
    fun givenTicksHeldWhileBrowsing_whenBuildingRows_thenTheyAreNotDrawn() {
        val rows = ChatSessionRows.from(
            listOf(session("s1")),
            currentSessionId = null,
            untitledTitle = UNTITLED,
            selection = SessionSelection(isSelecting = false, ids = setOf("s1")),
        )

        assertFalse(rows.single().isSelected)
    }

    @Test
    fun givenAPickingList_whenARowIsToggledTwice_thenItEndsUnticked() {
        val once = SessionSelection.SELECTING.toggle("s1")
        val twice = once.toggle("s1")

        assertEquals(setOf("s1"), once.ids)
        assertTrue(twice.ids.isEmpty())
        // Still picking: unticking the last row must not drop the user back into browsing.
        assertTrue(twice.isSelecting)
    }

    @Test
    fun givenABrowsingList_whenARowIsToggled_thenItStartsPicking() {
        val selection = SessionSelection.BROWSING.toggle("s1")

        assertTrue(selection.isSelecting)
        assertEquals(setOf("s1"), selection.ids)
    }

    @Test
    fun givenMoreSessionsThanThePage_whenBuildingRows_thenOnlyTheNewestPageIsBuilt() {
        val sessions = (1..10).map { session("s$it", createdAt = it * 1_000L) }

        val rows = ChatSessionRows.from(
            sessions,
            currentSessionId = null,
            untitledTitle = UNTITLED,
            limit = 3,
        )

        // The limit applies after the ordering, so growing it appends older chats to these three
        // rather than reshuffling what the user is already looking at.
        assertEquals(listOf("s10", "s9", "s8"), rows.map { it.id })
    }

    @Test
    fun givenFewerSessionsThanThePage_whenBuildingRows_thenEveryOneIsBuilt() {
        val rows = ChatSessionRows.from(
            listOf(session("s1"), session("s2")),
            currentSessionId = null,
            untitledTitle = UNTITLED,
            limit = 20,
        )

        assertEquals(2, rows.size)
    }

    @Test
    fun givenAPartlyBuiltList_whenItGrows_thenItStopsAtTheSessionCount() {
        assertEquals(20, SessionPaging.grow(built = 10, total = 40, pageSize = 10))
        assertEquals(12, SessionPaging.grow(built = 10, total = 12, pageSize = 10))
        // Never backwards: a page that arrived while the list already showed everything must not
        // shrink it under a user mid-scroll.
        assertEquals(10, SessionPaging.grow(built = 10, total = 4, pageSize = 10))
    }

    @Test
    fun givenAMisconfiguredPageSize_whenTheListGrows_thenItStillAdvances() {
        // A page of zero would leave the scroll listener asking for more forever and never getting
        // it, which reads as a list that simply stops partway down.
        assertEquals(11, SessionPaging.grow(built = 10, total = 40, pageSize = 0))
    }

    @Test
    fun givenTheEndOfTheBuiltRowsIsNear_whenScrolling_thenTheListGrows() {
        assertTrue(SessionPaging.shouldGrow(lastVisible = 15, built = 20, total = 40, lookahead = 8))
        assertFalse(SessionPaging.shouldGrow(lastVisible = 5, built = 20, total = 40, lookahead = 8))
        // Nothing left to append, however far down the user is.
        assertFalse(SessionPaging.shouldGrow(lastVisible = 19, built = 20, total = 20, lookahead = 8))
    }

    /**
     * @param userText the session's first user turn, or null for a session never started.
     * @param name the name the user gave it, or null for one never renamed.
     */
    private fun session(
        id: String,
        createdAt: Long = 1_000,
        userText: String? = null,
        name: String? = null,
    ) = ChatSession(
        id = id,
        createdAt = createdAt,
        messages = userText?.let { listOf(ChatMessage(text = it, sender = Sender.USER)) }.orEmpty(),
        name = name,
    )
}
