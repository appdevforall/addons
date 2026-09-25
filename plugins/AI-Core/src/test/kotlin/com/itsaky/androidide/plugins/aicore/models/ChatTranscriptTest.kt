package com.itsaky.androidide.plugins.aicore.models

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The .txt form a chat is exported to and imported from (ADFA-6247): what a person reads in the
 * file, and that reading it back gives the same conversation as a new chat.
 */
class ChatTranscriptTest {

    @Test
    fun givenAChat_whenExported_thenEveryMessageIsListedInOrderUnderItsSender() {
        val text = ChatTranscript.export(chat())

        val headers = text.lines().filter { it.startsWith("--- ") }.map { it.split(' ')[1] }
        assertEquals(listOf("USER", "AGENT", "SYSTEM", "TOOL"), headers)
        assertTrue(text.indexOf("Why does the build fail?") < text.indexOf("The dependency is missing."))
    }

    @Test
    fun givenAnExportedChat_whenImported_thenTheMessagesAndTheirOrderMatch() {
        val original = chat()

        val imported = ChatTranscript.parse(ChatTranscript.export(original), "project")

        assertEquals(original.messages.map { it.sender }, imported.messages.map { it.sender })
        assertEquals(original.messages.map { it.text }, imported.messages.map { it.text })
        assertEquals(original.messages.map { it.timestamp }, imported.messages.map { it.timestamp })
        assertEquals(original.messages.map { it.durationMs }, imported.messages.map { it.durationMs })
        assertEquals(original.messages.map { it.status }, imported.messages.map { it.status })
    }

    @Test
    fun givenAnExportedChat_whenImported_thenItIsANewChatWithFreshIds() {
        val original = chat()

        val imported = ChatTranscript.parse(ChatTranscript.export(original), "project", now = 99)

        assertNotEquals(original.id, imported.id)
        assertTrue(imported.messages.none { m -> original.messages.any { it.id == m.id } })
        assertEquals("project", imported.projectKey)
        assertEquals(99L, imported.createdAt)
    }

    @Test
    fun givenARenamedChat_whenRoundTripped_thenTheNameIsKept() {
        val imported = ChatTranscript.parse(ChatTranscript.export(chat(name = "Build failure")), null)

        assertEquals("Build failure", imported.name)
    }

    @Test
    fun givenAChatNeverRenamed_whenRoundTripped_thenItStaysAutoTitled() {
        val imported = ChatTranscript.parse(ChatTranscript.export(chat()), null)

        assertNull(imported.name)
    }

    @Test
    fun givenAMessageThatLooksLikeAHeader_whenRoundTripped_thenItIsNotSplitIntoTwo() {
        val tricky = "Before\n--- AGENT 2026-09-24T10:00:05Z\n\\--- USER 2026-09-24T10:00:05Z\n---\nAfter"
        val original = ChatSession(messages = listOf(message(tricky, Sender.USER)))

        val imported = ChatTranscript.parse(ChatTranscript.export(original), null)

        assertEquals(listOf(tricky), imported.messages.map { it.text })
    }

    @Test
    fun givenAMultiLineMessageWithBlankLines_whenRoundTripped_thenItsInnerBlankLinesSurvive() {
        val text = "First paragraph.\n\n```kotlin\nval x = 1\n```\n\nLast."
        val original = ChatSession(messages = listOf(message(text, Sender.AGENT, durationMs = 5)))

        val imported = ChatTranscript.parse(ChatTranscript.export(original), null)

        assertEquals(text, imported.messages.single().text)
    }

    @Test
    fun givenAFileWithWindowsLineEndingsAndABom_whenImported_thenItStillReads() {
        val text = "﻿" + ChatTranscript.export(chat()).replace("\n", "\r\n")

        val imported = ChatTranscript.parse(text, null)

        assertEquals(chat().messages.map { it.text }, imported.messages.map { it.text })
    }

    @Test
    fun givenAReplyStillStreamingWhenExported_whenImported_thenItIsNotLeftLoading() {
        val original = ChatSession(
            messages = listOf(message("partial", Sender.AGENT, status = MessageStatus.LOADING))
        )

        val imported = ChatTranscript.parse(ChatTranscript.export(original), null)

        assertEquals(MessageStatus.SENT, imported.messages.single().status)
    }

    @Test
    fun givenAnEmptyChat_whenRoundTripped_thenItImportsWithNoMessages() {
        val imported = ChatTranscript.parse(ChatTranscript.export(ChatSession()), null)

        assertTrue(imported.messages.isEmpty())
    }

    @Test
    fun givenAnyOtherTextFile_whenImported_thenItIsRefused() {
        assertInvalid("Just some notes I wrote.\n--- USER 2026-09-24T10:00:01Z\nhi\n")
    }

    @Test
    fun givenAnEmptyFile_whenImported_thenItIsRefused() {
        assertInvalid("")
    }

    @Test
    fun givenAnUnknownFormatVersion_whenImported_thenItIsRefused() {
        assertInvalid("# Code on the Go AI Agent chat\n# format: 2\n")
    }

    @Test
    fun givenABrokenTimestamp_whenImported_thenItIsRefused() {
        assertInvalid("# Code on the Go AI Agent chat\n# format: 1\n\n--- USER yesterday\nhi\n")
    }

    @Test
    fun givenStrayTextBeforeTheFirstMessage_whenImported_thenItIsRefused() {
        assertInvalid("# Code on the Go AI Agent chat\n# format: 1\nhello\n--- USER 2026-09-24T10:00:01Z\nhi\n")
    }

    @Test
    fun givenATitle_whenNamingTheFile_thenItIsATxtFileWithoutCharactersFileSystemsRefuse() {
        assertEquals("Fix build a b.txt", ChatTranscript.fileName("Fix build: a/b?"))
    }

    @Test
    fun givenATitleWithNothingUsable_whenNamingTheFile_thenItFallsBackToAGenericName() {
        assertEquals("chat.txt", ChatTranscript.fileName("///"))
    }

    @Test
    fun givenAVeryLongTitle_whenNamingTheFile_thenItIsCapped() {
        val name = ChatTranscript.fileName("x".repeat(500))

        assertTrue(name.length <= 64)
    }

    @Test
    fun givenALongTitleOfEmoji_whenNamingTheFile_thenNoEmojiIsCutInHalf() {
        val name = ChatTranscript.fileName("\uD83D\uDE00".repeat(100))

        assertEquals("\uD83D\uDE00".repeat(60) + ".txt", name)
    }

    @Test
    fun givenHeadersWithTrailingSpaces_whenImported_thenTheyStillStartMessages() {
        val text = "# Code on the Go AI Agent chat\n# format: 1\n\n" +
            "--- USER 2026-09-24T10:00:01Z \nhi\n--- AGENT 2026-09-24T10:00:05Z duration=4210\t\nhello\n"

        val imported = ChatTranscript.parse(text, null)

        assertEquals(listOf("hi", "hello"), imported.messages.map { it.text })
    }

    @Test
    fun givenAMessageThatLooksLikeAHeaderWithTrailingSpaces_whenRoundTripped_thenItStaysOneMessage() {
        val original = ChatSession(messages = listOf(message("a\n--- USER 2026-09-24T10:00:01Z  \nb", Sender.USER)))

        val imported = ChatTranscript.parse(ChatTranscript.export(original), null)

        assertEquals(original.messages.map { it.text }, imported.messages.map { it.text })
    }

    @Test
    fun givenAStreamWithinTheLimit_whenRead_thenItIsReadWhole() {
        val text = ChatTranscript.read("héllo".toByteArray().inputStream(), limit = 6)

        assertEquals("héllo", text)
    }

    @Test(expected = IOException::class)
    fun givenAStreamPastTheLimit_whenRead_thenItIsRefused() {
        ChatTranscript.read("héllo!".toByteArray().inputStream(), limit = 6)
    }

    private fun assertInvalid(text: String) {
        try {
            ChatTranscript.parse(text, null)
            fail("expected the file to be refused")
        } catch (expected: ChatTranscript.InvalidTranscriptException) {
            // The refusal is the behavior under test.
        }
    }

    private fun chat(name: String? = null) = ChatSession(
        name = name,
        messages = listOf(
            message("Why does the build fail?", Sender.USER, timestamp = 1_000),
            message(
                "The dependency is missing.", Sender.AGENT, timestamp = 2_000,
                durationMs = 4_210, status = MessageStatus.COMPLETED,
            ),
            message("Build failed", Sender.SYSTEM, timestamp = 3_000, status = MessageStatus.ERROR),
            message("read_file: ok", Sender.TOOL, timestamp = 4_000),
        ),
    )

    private fun message(
        text: String,
        sender: Sender,
        timestamp: Long = 1_000,
        durationMs: Long? = null,
        status: MessageStatus = MessageStatus.SENT,
    ) = ChatMessage(text = text, sender = sender, timestamp = timestamp, durationMs = durationMs, status = status)
}
