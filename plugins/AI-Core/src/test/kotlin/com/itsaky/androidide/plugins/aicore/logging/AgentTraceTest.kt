package com.itsaky.androidide.plugins.aicore.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AgentTrace]'s previewing rules — the part that decides how much of a
 * user's prompt and source code ends up in logcat. A regression here leaks file contents
 * into a log rather than merely formatting something oddly.
 */
class AgentTraceTest {

    @Test
    fun givenAShortValue_whenPreviewed_thenItIsQuotedInFull() {
        assertEquals("\"hello\"", AgentTrace.preview("hello"))
    }

    @Test
    fun givenALongValue_whenPreviewed_thenItIsCutAndTheRealLengthIsReported() {
        val preview = AgentTrace.preview("x".repeat(500))

        assertTrue("should be truncated: $preview", preview.contains("…"))
        assertTrue("should report the true size: $preview", preview.contains("(500 chars)"))
        assertTrue(
            "must not carry the whole value",
            preview.length < 500
        )
    }

    @Test
    fun givenMultiLineCode_whenPreviewed_thenItCollapsesToOneLogLine() {
        val preview = AgentTrace.preview("fun a() {\n    body()\n}")

        assertFalse("a log line must not contain raw newlines", preview.contains("\n"))
        assertTrue(preview.contains("⏎"))
    }

    @Test
    fun givenCarriageReturns_whenPreviewed_thenTheyAreStripped() {
        assertFalse(AgentTrace.preview("a\r\nb").contains("\r"))
    }

    @Test
    fun givenNull_whenPreviewed_thenItIsRenderedWithoutQuotes() {
        assertEquals("null", AgentTrace.preview(null))
    }

    @Test
    fun givenEditArguments_whenPreviewed_thenEachValueIsCappedIndependently() {
        val rendered = AgentTrace.previewArgs(
            mapOf(
                "file_path" to "app/src/main/java/Main.java",
                "old_string" to "y".repeat(300),
                "new_string" to "short",
            )
        )

        assertTrue(rendered.contains("file_path="))
        assertTrue("the long snippet must be capped: $rendered", rendered.contains("(300 chars)"))
        assertTrue("short values stay intact: $rendered", rendered.contains("\"short\""))
    }

    @Test
    fun givenNoArguments_whenPreviewed_thenItRendersEmpty() {
        assertEquals("", AgentTrace.previewArgs(emptyMap()))
    }
}
