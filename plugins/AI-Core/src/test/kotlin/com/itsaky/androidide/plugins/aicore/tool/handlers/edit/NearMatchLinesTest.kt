package com.itsaky.androidide.plugins.aicore.tool.handlers.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearMatchLinesTest {

    private val strings = "<resources>\n" +
        "    <string name=\"app_name\">Build Apps Anywhere </string>\n" +
        "    <string name=\"hello\">Hello</string>\n" +
        "</resources>\n"

    @Test
    fun givenAGuessSharingAWordWithOneLine_whenFound_thenThatLineComesBackExactly() {
        val lines = NearMatchLines.find(strings, "app_name = \"Block Stacker\"")

        assertEquals(
            NearMatchLines.Line(2, "    <string name=\"app_name\">Build Apps Anywhere </string>"),
            lines.first(),
        )
    }

    @Test
    fun givenSeveralLines_whenFound_thenTheOnesSharingMostWordsComeFirstInFileOrder() {
        val text = "alpha\nalpha beta\nbeta\nalpha beta\n"

        val lines = NearMatchLines.find(text, "alpha beta")

        assertEquals(listOf(2, 4, 1, 3), lines.map { it.number })
    }

    @Test
    fun givenManyMatchingLines_whenFound_thenTheListIsCapped() {
        val text = (1..50).joinToString("\n") { "val name$it = foo" }

        assertEquals(5, NearMatchLines.find(text, "foo").size)
    }

    @Test
    fun givenALongLine_whenFoundAndRendered_thenItIsCutAndSaysSo() {
        // Copied as old_string with a whole new line as new_string, an unmarked cut line leaves its tail behind.
        val line = NearMatchLines.find("foo " + "x".repeat(1_000), "foo").single()

        assertEquals(200, line.text.length)
        assertTrue(line.cut)
        assertTrue(NearMatchLines.render(listOf(line)).contains("only the first 200 characters; the line goes on"))
    }

    @Test
    fun givenALongLineWithACharacterAcrossTheCut_whenFound_thenTheCharacterIsDroppedWhole() {
        // U+1F600 is two chars; cutting between them would put a lone surrogate in the prompt.
        val line = NearMatchLines.find("foo " + "x".repeat(195) + "😀" + "tail", "foo").single()

        assertEquals(199, line.text.length)
        assertTrue(line.cut)
        assertTrue(!line.text.last().isSurrogate())
    }

    @Test
    fun givenAWordPastWhereALineIsCut_whenFound_thenTheLineStillMatches() {
        val text = "x".repeat(300) + " target\nother\n"

        assertEquals(listOf(1), NearMatchLines.find(text, "target").map { it.number })
    }

    @Test
    fun givenATabIndentedLine_whenRendered_thenTheTabIsShownApartFromSpaces() {
        val rendered = NearMatchLines.render(NearMatchLines.find("\tfoo()\n", "foo"))

        assertTrue(rendered.contains("line 1: \"\\tfoo()\""))
        assertTrue(rendered.contains("a tab is shown as \\t"))
    }

    @Test
    fun givenAGuessInANonLatinScript_whenFound_thenTheLineHoldingItComesBack() {
        val text = "<string name=\"app_name\">मेरा ऐप</string>\n<string name=\"title\">我的应用</string>\n"

        assertEquals(1, NearMatchLines.find(text, "मेरा").single().number)
        assertEquals(2, NearMatchLines.find(text, "我的应用").single().number)
    }

    @Test
    fun givenAGuessOfManyWords_whenFound_thenOnlyItsFirstWordsAreMatched() {
        // The cap bounds the cost of a huge old_string, which is words × file size.
        val snippet = (1..32).joinToString(" ") { "filler$it" } + " target"

        assertTrue(NearMatchLines.find("val target = 1\n", snippet).isEmpty())
    }

    @Test
    fun givenCrlfText_whenFound_thenNoCarriageReturnIsKept() {
        assertEquals("foo bar", NearMatchLines.find("foo bar\r\nbaz\r\n", "foo").single().text)
    }

    @Test
    fun givenNoSharedWordOrNoWordAtAll_whenFound_thenNothingComesBack() {
        assertTrue(NearMatchLines.find(strings, "zzz_nothing").isEmpty())
        assertTrue(NearMatchLines.find(strings, "= \"\"").isEmpty())
        assertEquals("", NearMatchLines.render(emptyList()))
    }

    @Test
    fun givenLines_whenRendered_thenTrailingSpacesStayVisibleInsideTheQuotes() {
        val rendered = NearMatchLines.render(listOf(NearMatchLines.Line(7, "a ")))

        assertTrue(rendered.contains("line 7: \"a \""))
    }

    @Test
    fun givenAWordInsideLongerWords_whenFound_thenOnlyAWholeWordMatches() {
        // `val` sits inside `evaluate` and `substring`, which say nothing about the line.
        val text = "evaluate(substring)\nval target = 1\n"

        assertEquals(listOf(2), NearMatchLines.find(text, "val").map { it.number })
    }

    @Test
    fun givenAPartOfAnIdentifier_whenFound_thenTheLinesHoldingItComeBackWholeIdentifierFirst() {
        val text = "my_name = 1\nother = 2\nappName = 3\n"

        assertEquals(listOf(1, 3), NearMatchLines.find(text, "name").map { it.number })
        assertEquals(listOf(3, 1), NearMatchLines.find(text, "appName").map { it.number })
    }

    @Test
    fun givenAPartOfAPhraseInAScriptWithoutSpaces_whenFound_thenItsLineComesBack() {
        // Chinese runs a sentence together, so the guess is a part of the line's one long run.
        val text = "<string name=\"title\">我的应用程序</string>\n<string name=\"other\">你好</string>\n"

        assertEquals(listOf(1), NearMatchLines.find(text, "我的应用").map { it.number })
    }
}
