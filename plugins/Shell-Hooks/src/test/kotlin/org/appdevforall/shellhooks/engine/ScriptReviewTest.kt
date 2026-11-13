package org.appdevforall.shellhooks.engine

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class ScriptReviewTest {
    private val dir = tempDir("review")

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun reason(text: String): String? = ScriptReview.checkBytes(text.toByteArray(Charsets.ISO_8859_1))?.message

    private fun reasonUtf8(text: String): String? = ScriptReview.checkBytes(text.toByteArray(Charsets.UTF_8))?.message

    // The rule, one test per clause, each naming the first violation.

    @Test
    fun aPlainScriptIsReviewable() {
        assertNull(reason("#!/bin/sh\n# lint the file\nprintf '%s\\n' \"\$1\" | tr -d '~'\n"))
    }

    @Test
    fun aLastLineWithoutANewlineIsReviewable() {
        assertNull(reason("#!/bin/sh\necho done"))
    }

    @Test
    fun aTabIsNotReviewable() {
        assertEquals("line 3, column 10: a tab", reason("#!/bin/sh\necho one\nif true; \tthen echo; fi\n"))
    }

    @Test
    fun aCarriageReturnIsNotReviewable() {
        assertEquals("line 1, column 10: a carriage return", reason("#!/bin/sh\r\necho\n"))
    }

    @Test
    fun anotherControlCharacterIsNotReviewable() {
        assertEquals("line 2, column 5: a control character (0x1B)", reason("#!/bin/sh\necho\u001B[2J\n"))
        assertEquals("line 1, column 2: a control character (0x7F)", reason("e\u007Fcho\n"))
        assertEquals("line 1, column 1: a control character (0x00)", reason("\u0000"))
    }

    @Test
    fun aByteOutsideAsciiIsNotReviewable() {
        assertEquals("line 2, column 6: byte 0xC3 (not plain ASCII)", reasonUtf8("#!/bin/sh\n# café menu\n"))
    }

    @Test
    fun aLineOfExactlyTheLimitIsReviewableAndOneMoreIsNot() {
        val limit = "#" + "x".repeat(ScriptReview.MAX_LINE - 1)

        assertNull(reason("$limit\n$limit"))
        assertEquals("line 2 is longer than 100 characters", reason("echo\n${limit}x\n"))
    }

    @Test
    fun aLineEndingInASpaceIsNotReviewable() {
        assertEquals("line 2 ends in a space", reason("#!/bin/sh\necho one \necho two\n"))
    }

    @Test
    fun aLastLineEndingInASpaceIsNotReviewable() {
        assertEquals("line 2 ends in a space", reason("#!/bin/sh\necho one "))
    }

    @Test
    fun theFirstViolationIsReported() {
        assertEquals("line 1, column 2: a tab", reason("a\tb \n" + "x".repeat(200) + "\n"))
    }

    @Test
    fun aScriptOfExactlyTheLimitIsReviewableAndLargerIsNot() {
        val line = "#" + "x".repeat(62) + "\n" // 64 bytes
        val body = line.repeat(ScriptReview.MAX_BYTES / line.length)
        assertEquals(ScriptReview.MAX_BYTES, body.length)

        val exact = dir.script("exact", body)
        val over = dir.script("over", "$body#")

        assertTrue(ScriptReview.assess(exact, "exact") is ScriptReview.Assessment.Reviewable)
        assertEquals(ScriptReview.Assessment.NotReviewable(ScriptReview.Reason.TooLarge), ScriptReview.assess(over, "over"))
        assertEquals("it is larger than 64 KB", ScriptReview.Reason.TooLarge.message)
    }

    // The name.

    @Test
    fun aNameOfLettersDigitsDotsDashesUnderscoresAndSlashesIsReviewable() {
        assertNull(ScriptReview.checkName("file-saved"))
        assertNull(ScriptReview.checkName("file-saved.d/10_lint.sh"))
    }

    @Test
    fun aNameWithOtherCharactersIsNotReviewable() {
        for (name in listOf("with space", "café", "a‮b", "semi;colon", "tab\there", "new\nline", "")) {
            assertEquals(name, ScriptReview.Reason.UnsafeName, ScriptReview.checkName(name))
        }
        assertEquals(
            "its name uses characters other than letters, digits, '.', '_', '-' and '/'",
            ScriptReview.Reason.UnsafeName.message,
        )
    }

    @Test
    fun aNameWithAPartStartingWithADotIsNotReviewable() {
        for (name in listOf(".hidden", "file-saved.d/.lint", "../file-saved", "file-saved.d/./x")) {
            assertEquals(name, ScriptReview.Reason.HiddenName, ScriptReview.checkName(name))
        }
    }

    @Test
    fun anUnsafeNameIsReportedBeforeAnythingIsRead() {
        val file = File(dir, "missing")

        assertEquals(ScriptReview.Assessment.NotReviewable(ScriptReview.Reason.UnsafeName), ScriptReview.assess(file, "a b"))
    }

    // Every trick the reviews found is outside the rule.

    @Test
    fun theNoBreakSpaceFunctionNameTrickIsNotReviewable() {
        // Under bash these two lines run `touch`, though they read as a no-op and a comment.
        val script = "true #() { \"\$@\"; }   # placeholder\ntrue # touch \"\$COGO_PROJECT_DIR/pwned\"\n"

        assertEquals("line 1, column 5: byte 0xC2 (not plain ASCII)", reasonUtf8(script))
    }

    @Test
    fun aBackslashBeforeACarriageReturnIsNotReviewable() {
        // Reads as one inert `:` command continued onto the next line; the shell escapes the CR and
        // runs the second line on its own. Text only; never executed.
        val script = ": Disabled, kept for reference: \\\r\nrm -rf \"\$HOME\"\n"

        assertEquals("line 1, column 34: a carriage return", reason(script))
    }

    @Test
    fun aBackslashBeforeATrailingSpaceIsNotReviewable() {
        assertEquals("line 1 ends in a space", reason(": kept for reference: \\ \ntouch pwned\n"))
    }

    @Test
    fun aBackslashBeforeATrailingTabIsNotReviewable() {
        assertEquals("line 1, column 24: a tab", reason(": kept for reference: \\\t\ntouch pwned\n"))
    }

    @Test
    fun theThreeHundredSpaceWrapTrickIsOverTheLineLimit() {
        // In a wrapping view the payload looks like the first line of an inert notes block. Only the
        // line limit refuses it: a shorter version fits, and is marked for its << instead
        // (ScriptHintsTest.everyTrickFromTheReviewsIsMarkedOnTheLineItNeeds). The dialog's continuation
        // mark is what shows its wrap; that mark is Android UI and has no unit test.
        val script = ": <<'NOTES'" + " ".repeat(300) + "; touch marker\nThese notes are documentation only.\nNOTES\n"

        assertEquals("line 1 is longer than 100 characters", reason(script))
    }

    @Test
    fun aTabIndentedHeredocTerminatorIsNotReviewable() {
        // `<<-` ends the heredoc on a tab-indented NOTES, not a space-indented one, and the two
        // look alike on screen: the reader thinks `touch` is notes, the shell runs it.
        val script = ": <<-'NOTES'\n notes\n\tNOTES\n touch marker\nNOTES\n"

        assertEquals("line 3, column 1: a tab", reason(script))
    }

    @Test
    fun aHugeFileOfCarriageReturnsIsNeverReadPastTheLimit() {
        val crs = ByteArray(256 * 1024) { 0x0D }
        var opened = false
        var read = 0L
        val open = {
            opened = true
            object : ByteArrayInputStream(crs) {
                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int = super.read(b, off, len).also { if (it > 0) read += it }
            }
        }

        // Its length says it is too large: not even opened.
        assertNull(ScriptReview.readAtMost(crs.size.toLong(), open, ScriptReview.MAX_BYTES))
        assertFalse(opened)
        // A file that reports a small length (it grew, or it is a pipe) is read no further than one
        // byte past the limit, which is enough to know it is too large.
        assertNull(ScriptReview.readAtMost(0, open, ScriptReview.MAX_BYTES))
        assertEquals((ScriptReview.MAX_BYTES + 1).toLong(), read)

        val file = File(dir, "crs").apply { writeBytes(crs) }
        assertEquals(ScriptReview.Assessment.NotReviewable(ScriptReview.Reason.TooLarge), ScriptReview.assess(file, "crs"))
    }

    // Reading.

    @Test
    fun aReviewableScriptIsReadWithItsHashAndLines() {
        val body = "#!/bin/sh\n\necho one\n"
        val file = dir.script("hook", body)

        val assessed = ScriptReview.assess(file, "hook") as ScriptReview.Assessment.Reviewable

        assertArrayEquals(body.toByteArray(), assessed.bytes)
        assertEquals(TrustStore.sha256(body.toByteArray()), assessed.sha256)
        assertEquals(listOf("#!/bin/sh", "", "echo one"), assessed.lines)
    }

    @Test
    fun aLastLineWithoutANewlineIsItsOwnLine() {
        assertEquals(listOf("a", "b"), ScriptReview.lines("a\nb".toByteArray()))
    }

    @Test
    fun aMissingScriptIsUnreadableAndItsMessageIsKeptForTheLogOnly() {
        assertTrue(ScriptReview.assess(File(dir, "gone"), "gone") is ScriptReview.Assessment.Unreadable)
    }

    // Names the project chose are revealed before they are shown.

    @Test
    fun bidiOverridesAndZeroWidthCharactersInANameAreShown() {
        val revealed = ScriptReview.reveal("notes ‮txt.sh‬ x​y﻿")

        assertEquals("notes <U+202E>txt.sh<U+202C> x<U+200B>y<U+FEFF>", revealed.text)
        assertEquals(4, revealed.hidden)
    }

    @Test
    fun aNoBreakSpaceAndOtherSpacesInANameAreShown() {
        val revealed = ScriptReview.reveal("a b cㅤd­e")

        assertEquals("a<U+00A0>b<U+2028>c<U+3164>d<U+00AD>e", revealed.text)
        assertEquals(4, revealed.hidden)
    }

    @Test
    fun aSupplementaryCharacterIsOneEscapeAndAnUnpairedSurrogateIsShown() {
        assertEquals("x<U+E0041>y", ScriptReview.reveal("x${String(Character.toChars(0xE0041))}y").text)
        assertEquals("a<U+D800>b", ScriptReview.reveal("a\uD800b").text)
    }

    @Test
    fun controlCharactersNewlinesTabsAndBlanksEndingALineInANameAreShown() {
        // A name is one line: a newline in a folder name must not add a line of text to a dialog.
        val revealed = ScriptReview.reveal("a\u001Bb \nc\r\td")

        assertEquals("a<U+001B>b<U+0020><U+000A>c<U+000D><U+0009>d", revealed.text)
        assertEquals(5, revealed.hidden)
    }

    @Test
    fun eachEscapeIsLocatedInTheShownName() {
        val revealed = ScriptReview.reveal("a b ")

        assertEquals(listOf("<U+00A0>", "<U+0020>"), revealed.escapes.map { revealed.text.substring(it) })
    }

    @Test
    fun aPlainNameIsUnchanged() {
        assertEquals(ScriptReview.Revealed("My App/src/Main.kt", 0), ScriptReview.reveal("My App/src/Main.kt"))
    }
}
