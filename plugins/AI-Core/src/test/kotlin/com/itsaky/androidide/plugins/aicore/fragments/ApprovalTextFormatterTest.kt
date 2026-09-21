package com.itsaky.androidide.plugins.aicore.fragments

import com.itsaky.androidide.plugins.aicore.tool.handlers.EditFileHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ApprovalTextFormatter]. This is the text a user reads before authorising a change
 * to their own source, so the tests are about *informed* consent: the path is named, both sides are
 * shown, and an edit that hits every occurrence says so.
 */
class ApprovalTextFormatterTest {

    private fun editArgs(vararg pairs: Pair<String, Any?>) = mapOf(*pairs)

    @Test
    fun givenAnEdit_whenFormatted_thenItNamesThePathAndBothSidesOfTheChange() {
        val text = ApprovalTextFormatter.formatEdit(
            editArgs(
                EditFileHandler.ARG_PATH to "app/src/Main.kt",
                EditFileHandler.ARG_OLD to "val a = 1",
                EditFileHandler.ARG_NEW to "val a = 2",
            )
        )

        assertTrue(text.contains("app/src/Main.kt"))
        assertTrue(text.contains("- val a = 1"))
        assertTrue(text.contains("+ val a = 2"))
    }

    @Test
    fun givenAnEmptyNewString_whenFormatted_thenItReadsAsADeletionRatherThanAnEmptyAddition() {
        val text = ApprovalTextFormatter.formatEdit(
            editArgs(
                EditFileHandler.ARG_PATH to "Main.kt",
                EditFileHandler.ARG_OLD to "val unused = 1",
                EditFileHandler.ARG_NEW to "",
            )
        )

        assertTrue(text.contains("(deleted)"))
    }

    @Test
    fun givenReplaceAllAsARealBoolean_whenFormatted_thenTheWarningIsShown() {
        val text = ApprovalTextFormatter.formatEdit(
            editArgs(
                EditFileHandler.ARG_PATH to "Main.kt",
                EditFileHandler.ARG_OLD to "a",
                EditFileHandler.ARG_NEW to "b",
                EditFileHandler.ARG_REPLACE_ALL to true,
            )
        )

        assertTrue("a whole-file edit must announce itself", text.contains("every occurrence"))
    }

    @Test
    fun givenReplaceAllAsAModelSuppliedString_whenFormatted_thenTheWarningIsStillShown() {
        // Parsed differently by the dialog, an every-occurrence edit was approved without saying so.
        listOf("true", " TRUE ", "yes", "1").forEach { raw ->
            val text = ApprovalTextFormatter.formatEdit(
                editArgs(
                    EditFileHandler.ARG_PATH to "Main.kt",
                    EditFileHandler.ARG_OLD to "a",
                    EditFileHandler.ARG_NEW to "b",
                    EditFileHandler.ARG_REPLACE_ALL to raw,
                )
            )

            assertTrue("'$raw' must warn", text.contains("every occurrence"))
        }
    }

    @Test
    fun givenNoReplaceAll_whenFormatted_thenNoWarningIsShown() {
        val text = ApprovalTextFormatter.formatEdit(
            editArgs(
                EditFileHandler.ARG_PATH to "Main.kt",
                EditFileHandler.ARG_OLD to "a",
                EditFileHandler.ARG_NEW to "b",
            )
        )

        assertFalse(text.contains("every occurrence"))
    }

    @Test
    fun givenAHugeSnippet_whenFormatted_thenItIsTruncatedWithTheOmissionStated() {
        // A wall of unreadable code is not consent, but nor is pretending it showed everything.
        val text = ApprovalTextFormatter.formatEdit(
            editArgs(
                EditFileHandler.ARG_PATH to "Main.kt",
                EditFileHandler.ARG_OLD to "x".repeat(5_000),
                EditFileHandler.ARG_NEW to "y",
            )
        )

        assertTrue(text.length < 2_000)
        assertTrue(text.contains("more characters"))
    }

    @Test
    fun givenATruncatedSnippet_whenFormatted_thenTheOmissionIsNotDisguisedAsRemovedCode() {
        // Prefixed the notice reads as changed code, and it must say the hidden text is written.
        val text = ApprovalTextFormatter.formatEdit(
            editArgs(
                EditFileHandler.ARG_PATH to "Main.kt",
                EditFileHandler.ARG_OLD to "x".repeat(5_000),
                EditFileHandler.ARG_NEW to "y",
            )
        )

        val notice = text.lines().first { it.contains("more characters") }
        assertFalse("the omission notice must not look like a diff line: $notice", notice.startsWith("- "))
        assertFalse("the omission notice must not look like a diff line: $notice", notice.startsWith("+ "))
        assertTrue("it must say the hidden text is still applied: $notice", notice.contains("WILL be written"))
    }

    @Test
    fun givenNoArguments_whenFormatted_thenItRendersAsAnEmptyObject() {
        assertEquals("{}", ApprovalTextFormatter.formatArgs(emptyMap()))
    }

    @Test
    fun givenAGenericToolCall_whenFormatted_thenEachArgumentIsNamedAndCapped() {
        val text = ApprovalTextFormatter.formatArgs(
            mapOf("file_path" to "Main.kt", "content" to "z".repeat(1_000))
        )

        assertTrue(text.contains("file_path"))
        assertTrue(text.contains("Main.kt"))
        assertTrue(text.contains("more characters"))
    }

    @Test
    fun givenANullArgumentValue_whenFormatted_thenItDoesNotBlowUp() {
        val text = ApprovalTextFormatter.formatArgs(mapOf("directory" to null))

        assertTrue(text.contains("directory"))
    }
}
