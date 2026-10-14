package com.itsaky.androidide.plugins.aicore.tool.handlers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [FilePager]: line counting, whole-line pages and the column continuation. */
class FilePagerTest {

    private fun page(content: String, request: PageRequest, room: Int): FilePage =
        (FilePager(content).page(request, room) as PageResult.Page).page

    @Test
    fun givenAFinalNewline_whenCounted_thenItStartsNoExtraLine() {
        assertEquals(2, FilePager("a\nb\n").lineCount)
        assertEquals(2, FilePager("a\nb").lineCount)
        assertEquals(0, FilePager("").lineCount)
    }

    @Test
    fun givenLinesThatDoNotAllFit_whenPaged_thenThePageEndsAfterTheLastWholeLine() {
        val result = page("aaa\nbbb\nccc\n", PageRequest(), room = 9)

        assertEquals("aaa\nbbb\n", result.text)
        assertEquals(2, result.endLine)
        assertEquals(PagePosition(3), result.next)
    }

    @Test
    fun givenCrlfLines_whenPaged_thenTheCarriageReturnsAreKept() {
        val result = page("a\r\nb\r\n", PageRequest(), room = 100)

        assertEquals("a\r\nb\r\n", result.text)
        assertNull(result.next)
    }

    @Test
    fun givenALineExactlyAsLongAsTheRoom_whenPaged_thenTheNextColumnIsItsNewline() {
        val first = page("abcd\nef", PageRequest(), room = 4)
        val second = page("abcd\nef", PageRequest(1, first.next!!.column), room = 4)

        assertEquals("abcd", first.text)
        assertEquals(PagePosition(1, 5), first.next)
        assertEquals("\nef", second.text)
    }

    @Test
    fun givenASurrogatePairAtTheCut_whenPaged_thenItIsNotSplit() {
        val result = page("ab😀cd", PageRequest(), room = 3)

        assertEquals("ab", result.text)
        assertEquals(PagePosition(1, 3), result.next)
    }

    @Test
    fun givenAColumnPastTheLine_whenPaged_thenItIsOutOfRange() {
        val result = FilePager("abc\ndef").page(PageRequest(1, startColumn = 9), room = 10)

        assertTrue(result is PageResult.OutOfRange)
    }

    @Test
    fun givenAnEndLineBeforeTheStart_whenPaged_thenItIsOutOfRange() {
        val result = FilePager("a\nb\nc").page(PageRequest(startLine = 3, endLine = 2), room = 10)

        assertTrue(result is PageResult.OutOfRange)
    }
}
