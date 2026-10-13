package com.itsaky.androidide.plugins.aicore.fragments

import com.itsaky.androidide.plugins.aicore.tool.handlers.EditFileHandler
import com.itsaky.androidide.plugins.aicore.tool.handlers.RunShellCommandHandler
import com.itsaky.androidide.plugins.aicore.tool.handlers.StopTerminalCommandHandler
import com.itsaky.androidide.plugins.aicore.tool.handlers.TerminalCommandHandler
import java.text.Bidi
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

    /** [text] with each line held left to right, as the shell previews show it. */
    private fun ltr(text: String) = text.lineSequence().joinToString("\n") { "\u2066$it\u2069" }

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

    @Test
    fun givenALongMultiLineScript_whenFormatted_thenEveryLineIsShownAsWritten() {
        val script = (1..50).joinToString("\n") { "echo \"line $it\"" }

        val text = ApprovalTextFormatter.formatShellCommand(
            mapOf(RunShellCommandHandler.ARG_COMMAND to script),
            directoryLabel = { "in $it" },
        )

        assertEquals(ltr(script), text)
    }

    @Test
    fun givenAWorkingDirectory_whenFormatted_thenItIsLabelledAboveTheCommandAsWritten() {
        val text = ApprovalTextFormatter.formatShellCommand(
            mapOf(
                RunShellCommandHandler.ARG_COMMAND to "ls",
                RunShellCommandHandler.ARG_WORKING_DIRECTORY to " -L dir ",
            ),
            directoryLabel = { "Runs in: $it" },
        )

        assertEquals("Runs in: ${ltr("-L dir")}\n\n${ltr("ls")}", text)
        assertFalse(text.contains("cd "))
    }

    @Test
    fun givenACarriageReturnHidingAPrefix_whenFormatted_thenItIsSpelledOutAndWarnedAbout() {
        val text = ApprovalTextFormatter.formatShellCommand(
            mapOf(RunShellCommandHandler.ARG_COMMAND to "rm -rf app\rls"),
            directoryLabel = { "in $it" },
        )

        assertTrue(text.startsWith(ltr("rm -rf app<U+000D>ls")))
        assertTrue(text.contains("invisible or control characters"))
    }

    @Test
    fun givenBidiOverridesZeroWidthAndEscapes_whenFormatted_thenEachIsSpelledOut() {
        val command = "echo \u202Egnp.exe\u202C \u200B\u2066x\u2069 \u001B[2K"

        val text = ApprovalTextFormatter.revealHidden(command)

        assertEquals("echo <U+202E>gnp.exe<U+202C> <U+200B><U+2066>x<U+2069> <U+001B>[2K", text)
    }

    @Test
    fun givenAHiddenCharacterInTheWorkingDirectory_whenFormatted_thenItIsSpelledOutToo() {
        val text = ApprovalTextFormatter.formatShellCommand(
            mapOf(
                RunShellCommandHandler.ARG_COMMAND to "ls",
                RunShellCommandHandler.ARG_WORKING_DIRECTORY to "app\u202Etset",
            ),
            directoryLabel = { "Runs in: $it" },
        )

        assertTrue(text.startsWith("Runs in: ${ltr("app<U+202E>tset")}\n\n${ltr("ls")}"))
    }

    @Test
    fun givenBlankGlyphLettersAndDefaultIgnorables_whenRevealed_thenEachIsSpelledOut() {
        val command = "cat configㅤ.txt ᅟᅠﾠ ⠀ ok️ ͏"

        val text = ApprovalTextFormatter.revealHidden(command)

        assertEquals(
            "cat config<U+3164>.txt <U+115F><U+1160><U+FFA0> <U+2800> ok<U+FE0F> <U+034F>",
            text,
        )
    }

    @Test
    fun givenAHangulFillerInAFileName_whenFormatted_thenTheHiddenCharacterWarningIsShown() {
        val text = ApprovalTextFormatter.formatShellCommand(
            mapOf(RunShellCommandHandler.ARG_COMMAND to "cat configㅤ.txt"),
            directoryLabel = { "in $it" },
        )

        assertTrue(text.contains("invisible or control characters"))
    }

    @Test
    fun givenALongRunOfBlankLines_whenFormatted_thenItIsCountedAndWarnedAbout() {
        val command = "ls" + "\n".repeat(201) + "rm -rf app/src"

        val text = ApprovalTextFormatter.formatShellCommand(
            mapOf(RunShellCommandHandler.ARG_COMMAND to command),
            directoryLabel = { "in $it" },
        )

        assertTrue(text.startsWith(ltr("ls\n[200 blank lines]\nrm -rf app/src")))
        assertTrue(text.contains("long runs of blank lines"))
        assertFalse(text.contains("invisible or control characters"))
    }

    @Test
    fun givenAFewBlankLinesInAScript_whenFormatted_thenTheyStayAsWrittenWithoutAWarning() {
        val script = "echo a\n\n  \necho b"

        val text = ApprovalTextFormatter.formatShellCommand(
            mapOf(RunShellCommandHandler.ARG_COMMAND to script),
            directoryLabel = { "in $it" },
        )

        assertEquals(ltr(script), text)
    }

    @Test
    fun givenLeadingAndTrailingBlankRuns_whenCollapsed_thenEachRunIsCounted() {
        val text = ApprovalTextFormatter.collapseBlankLines("\n\n\nls\n \n\t\n\n")

        assertEquals("[3 blank lines]\nls\n[4 blank lines]", text)
    }

    @Test
    fun givenNewlinesAndTabs_whenRevealed_thenTheyStayAsWritten() {
        assertEquals("a\n\tb", ApprovalTextFormatter.revealHidden("a\n\tb"))
    }

    @Test
    fun givenACommandStartingWithAnRtlLetter_whenFormatted_thenItIsLaidOutLeftToRight() {
        val command = "\u05D0; rm -rf app/src; echo \u05D1"

        val text = ApprovalTextFormatter.formatShellCommand(
            mapOf(RunShellCommandHandler.ARG_COMMAND to command),
            directoryLabel = { "in $it" },
        )

        // Bare, the paragraph takes its direction from the Hebrew letter and shows the parts reversed.
        assertEquals(1, Bidi(command, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseLevel)
        assertEquals(ltr(command), text)
        assertEquals(0, Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT).baseLevel)
    }

    @Test
    fun givenAStopOfACommandThisChatStarted_whenFormatted_thenItShowsTheCommandUnderItsSession() {
        val text = ApprovalTextFormatter.formatStopCommand(
            mapOf(
                TerminalCommandHandler.ARG_COMMAND_ID to "3f9a1c2e",
                StopTerminalCommandHandler.DETAIL_COMMAND to "npm run dev",
                StopTerminalCommandHandler.DETAIL_SESSION to "AI Core 2",
            ),
            sessionLabel = { "In: $it" },
            unknownCommand = { "Unknown $it" },
        )

        assertEquals("In: AI Core 2\n\n${ltr("npm run dev")}", text)
    }

    @Test
    fun givenAStopOfAnUnknownCommand_whenFormatted_thenItShowsTheIdAlone() {
        val text = ApprovalTextFormatter.formatStopCommand(
            mapOf(TerminalCommandHandler.ARG_COMMAND_ID to "3f9a1c2e"),
            sessionLabel = { "In: $it" },
            unknownCommand = { "Unknown $it" },
        )

        assertEquals("Unknown 3f9a1c2e", text)
    }
}
