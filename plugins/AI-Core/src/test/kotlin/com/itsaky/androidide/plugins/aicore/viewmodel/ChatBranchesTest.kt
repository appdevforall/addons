package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.models.ChatMessage
import com.itsaky.androidide.plugins.aicore.models.ChatSession
import com.itsaky.androidide.plugins.aicore.models.Sender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** How editing an older prompt forks a chat into versions, and how switching between them works. */
class ChatBranchesTest {

    private val session = ChatSession(
        id = "s1",
        messages = listOf(
            message("u1", Sender.USER, 1),
            message("a1", Sender.AGENT, 2),
            message("u2", Sender.USER, 3),
            message("t2", Sender.TOOL, 4),
            message("a2", Sender.AGENT, 5),
        ),
    )

    @Test
    fun givenAnUnforkedChat_whenAskingForVersions_thenThereAreNone() {
        assertTrue(ChatBranches.positions(session).isEmpty())
    }

    @Test
    fun givenAnOlderPrompt_whenForking_thenOnlyTheMessagesAboveItStayOnScreen() {
        val forked = ChatBranches.fork(session, "u2")!!

        assertEquals(listOf("u1", "a1"), forked.messages.map { it.id })
        assertEquals(listOf("u2", "t2", "a2"), forked.otherBranches!!.map { it.id })
        assertEquals(listOf("a1", "u2", "t2"), forked.otherBranches!!.map { it.parentId })
    }

    @Test
    fun givenAnAgentMessage_whenForking_thenNothingForks() {
        assertNull(ChatBranches.fork(session, "a1"))
    }

    @Test
    fun givenAForkAnsweredByANewPrompt_whenAskingForVersions_thenItIsTheSecondOfTwo() {
        val edited = withNewBranch(ChatBranches.fork(session, "u2")!!, "u2b", 10)

        assertEquals(mapOf("u2b" to ChatBranches.Position(1, 2)), ChatBranches.positions(edited))
        assertEquals("u2", ChatBranches.versionId(edited, "u2b", -1))
        assertNull(ChatBranches.versionId(edited, "u2b", 1))
    }

    @Test
    fun givenAFork_whenSwitchingBack_thenTheOriginalBranchReturnsWhole() {
        val edited = withNewBranch(ChatBranches.fork(session, "u2")!!, "u2b", 10)

        val switched = ChatBranches.switchTo(edited, "u2b", "u2")!!

        assertEquals(listOf("u1", "a1", "u2", "t2", "a2"), switched.messages.map { it.id })
        assertTrue(switched.messages.all { it.parentId == null })
        assertEquals(setOf("u2b", "a2b"), switched.otherBranches!!.map { it.id }.toSet())
        assertEquals(mapOf("u2" to ChatBranches.Position(0, 2)), ChatBranches.positions(switched))
    }

    @Test
    fun givenTheFirstPromptForked_whenAskingForVersions_thenTheChatStartHasTwo() {
        val edited = withNewBranch(ChatBranches.fork(session, "u1")!!, "u1b", 10)

        assertEquals(listOf("u1b", "a1b"), edited.messages.map { it.id })
        assertEquals(mapOf("u1b" to ChatBranches.Position(1, 2)), ChatBranches.positions(edited))
    }

    @Test
    fun givenANestedFork_whenSwitchingAwayAndBack_thenTheVersionLastOnScreenReturns() {
        // Fork at u2, answer it with u2b, then fork again at u1 and answer with u1b.
        val atU2 = withNewBranch(ChatBranches.fork(session, "u2")!!, "u2b", 10)
        val atU1 = withNewBranch(ChatBranches.fork(atU2, "u1")!!, "u1b", 20)

        val back = ChatBranches.switchTo(atU1, "u1b", "u1")!!

        // u2b was on screen under u1 when it was left, not the older u2.
        assertEquals(listOf("u1", "a1", "u2b", "a2b"), back.messages.map { it.id })
        assertEquals(
            mapOf("u1" to ChatBranches.Position(0, 2), "u2b" to ChatBranches.Position(1, 2)),
            ChatBranches.positions(back),
        )
    }

    @Test
    fun givenAMessageThatIsNotAVersion_whenSwitchingToIt_thenNothingChanges() {
        val edited = withNewBranch(ChatBranches.fork(session, "u2")!!, "u2b", 10)

        assertNull(ChatBranches.switchTo(edited, "u2b", "t2"))
    }

    @Test
    fun givenAVersionFollowingARemovedMessage_whenRemovingIt_thenTheVersionFollowsTheMessageAbove() {
        val withNotice = session.copy(
            messages = listOf(session.messages[0], session.messages[1], message("n1", Sender.SYSTEM, 3)) +
                session.messages.drop(2),
        )
        val edited = withNewBranch(ChatBranches.fork(withNotice, "u2")!!, "u2b", 10)

        val trimmed = ChatBranches.removeFromScreen(edited) { it.id == "n1" }

        assertEquals(listOf("u1", "a1", "u2b", "a2b"), trimmed.messages.map { it.id })
        assertEquals("a1", trimmed.otherBranches!!.single { it.id == "u2" }.parentId)
        assertEquals("u2", trimmed.selectedBranches!!["a1"])
        assertEquals(mapOf("u2b" to ChatBranches.Position(1, 2)), ChatBranches.positions(trimmed))
    }

    @Test
    fun givenNothingToRemove_whenRemovingFromScreen_thenTheSessionIsUnchanged() {
        assertSame(session, ChatBranches.removeFromScreen(session) { false })
    }

    @Test
    fun givenTwoStoredBranchesSentInTheSameMillisecond_whenSwitching_thenTheOrderIsStable() {
        // u2b and u2c share a timestamp, so the id alone must decide which counts as newer.
        val atB = withNewBranch(ChatBranches.fork(session, "u2")!!, "u2b", 10)
        val atC = withNewBranch(ChatBranches.fork(atB, "u2b")!!, "u2c", 10)

        assertEquals(ChatBranches.Position(2, 3), ChatBranches.positions(atC)["u2c"])
        assertEquals("u2b", ChatBranches.versionId(atC, "u2c", -1))
    }

    /** [forked] with a new prompt [promptId] and its reply appended on screen, as a run adds them. */
    private fun withNewBranch(forked: ChatSession, promptId: String, timestamp: Long) = forked.copy(
        messages = forked.messages +
            message(promptId, Sender.USER, timestamp) +
            message(promptId.replaceFirst('u', 'a'), Sender.AGENT, timestamp + 1),
    )

    private fun message(id: String, sender: Sender, timestamp: Long) =
        ChatMessage(id = id, text = "text of $id", sender = sender, timestamp = timestamp)
}
