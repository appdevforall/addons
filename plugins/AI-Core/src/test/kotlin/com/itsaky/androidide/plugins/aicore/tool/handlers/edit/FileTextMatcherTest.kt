package com.itsaky.androidide.plugins.aicore.tool.handlers.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FileTextMatcher]. The line-ending cases are why this class exists: a model emits
 * `\n`, so a CRLF file used to be uneditable while the advice to "copy the text exactly" was already
 * followed. Every adapted case asserts the direction too: the snippet converts, never the file.
 */
class FileTextMatcherTest {

    private fun found(match: FileTextMatcher.Match) =
        match as? FileTextMatcher.Match.Found ?: error("expected a match, got $match")

    @Test
    fun givenAnLfFileAndAnLfSnippet_whenMatching_thenItMatchesVerbatim() {
        val match = found(FileTextMatcher.match("a\nold\nb\n", "old", "new"))

        assertEquals(1, match.occurrences)
        assertEquals("old", match.oldString)
        assertEquals("new", match.newString)
        assertFalse("no adaptation was needed", match.lineEndingsAdapted)
    }

    @Test
    fun givenACrlfFileAndAnLfSnippet_whenMatching_thenTheSnippetIsAdaptedToCrlf() {
        val text = "fun a() {\r\n    old()\r\n}\r\n"

        val match = found(FileTextMatcher.match(text, "fun a() {\n    old()", "fun a() {\n    new()"))

        assertEquals(1, match.occurrences)
        assertTrue("adaptation must be reported", match.lineEndingsAdapted)
        assertEquals("fun a() {\r\n    old()", match.oldString)
        // The replacement is adapted the same way, or the edit would leave mixed endings.
        assertEquals("fun a() {\r\n    new()", match.newString)
    }

    @Test
    fun givenACrlfFileAndAnLfSnippet_whenReplacing_thenTheFilesCrlfEndingsSurvive() {
        // Normalising the *file* to LF for a two-line edit rewrites every untouched line.
        val text = "one\r\ntwo\r\nthree\r\n"

        val match = found(FileTextMatcher.match(text, "one\ntwo", "one\nTWO"))
        val updated = text.replace(match.oldString, match.newString)

        assertEquals("one\r\nTWO\r\nthree\r\n", updated)
    }

    @Test
    fun givenAnLfFileAndACrlfSnippet_whenMatching_thenTheSnippetIsAdaptedToLf() {
        val match = found(FileTextMatcher.match("one\ntwo\n", "one\r\ntwo", "one\r\nTWO"))

        assertEquals(1, match.occurrences)
        assertTrue(match.lineEndingsAdapted)
        assertEquals("one\ntwo", match.oldString)
        assertEquals("one\nTWO", match.newString)
    }

    @Test
    fun givenAMixedEndingFile_whenMatchingAnLfSnippet_thenItRefusesToGuess() {
        // Half CRLF, half LF: there is no single convention, so either choice rewrites lines.
        val text = "one\r\ntwo\nthree\r\n"

        val match = FileTextMatcher.match(text, "one\ntwo", "x\ny")

        assertTrue("must not guess on mixed endings", match is FileTextMatcher.Match.NotFound)
    }

    @Test
    fun givenASingleLineSnippetThatIsAbsent_whenMatching_thenNoAdaptationIsAttempted() {
        val match = FileTextMatcher.match("a\r\nb\r\n", "zzz", "x")

        assertTrue(match is FileTextMatcher.Match.NotFound)
    }

    @Test
    fun givenACrlfFileAndARepeatedLfSnippet_whenMatching_thenEveryOccurrenceIsCounted() {
        val text = "x\r\ny\r\nx\r\ny\r\n"

        val match = found(FileTextMatcher.match(text, "x\ny", "z\nw"))

        assertEquals(2, match.occurrences)
    }

    @Test
    fun givenAnEmptyNeedle_whenCounting_thenItIsZeroRatherThanUnbounded() {
        assertEquals(0, FileTextMatcher.countOccurrences("abc", ""))
    }

    @Test
    fun givenOverlappingCandidates_whenCounting_thenMatchesDoNotOverlap() {
        assertEquals(2, FileTextMatcher.countOccurrences("aaaa", "aa"))
    }
}
