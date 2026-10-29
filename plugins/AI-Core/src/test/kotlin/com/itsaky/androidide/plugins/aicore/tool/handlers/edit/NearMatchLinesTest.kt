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
    fun givenALongLine_whenFound_thenItIsCut() {
        val text = "foo " + "x".repeat(1_000)

        assertEquals(200, NearMatchLines.find(text, "foo").single().text.length)
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
}
