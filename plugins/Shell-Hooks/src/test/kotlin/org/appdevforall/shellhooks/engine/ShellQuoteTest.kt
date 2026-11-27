package org.appdevforall.shellhooks.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ShellQuoteTest {
    @Test
    fun quotesAWordInSingleQuotes() {
        assertEquals("'a b'", ShellQuote.quote("a b"))
    }

    @Test
    fun aSingleQuoteClosesEscapesAndReopens() {
        assertEquals("'it'\\''s'", ShellQuote.quote("it's"))
    }

    @Test
    fun theEmptyStringIsStillOneWord() {
        assertEquals("''", ShellQuote.quote(""))
    }

    @Test
    fun aControlCharacterIsWrittenAsItsBytes() {
        assertEquals("'a'\$'\\x1b''b'", ShellQuote.quote("a\u001bb"))
        assertEquals("'x'\$'\\x1b\\x07''y'", ShellQuote.quote("x\u001b\u0007y"))
        // C1 CSI as its UTF-8 bytes, so the escape does not depend on the shell's locale.
        assertEquals("''\$'\\xc2\\x9b'''", ShellQuote.quote("\u009b"))
    }

    // Bash ends a $'...' string at \x00, so a NUL would silently cut the word short.
    @Test(expected = IllegalArgumentException::class)
    fun aNulIsRefusedRatherThanCutShort() {
        ShellQuote.quote("a\u0001\u0000b")
    }

    // The Terminal prints the line before running it; a raw control code there would reach the terminal.
    @Test
    fun noControlCharacterReachesTheCommandLineRaw() {
        for (name in CONTROL_NAMES + HOSTILE_NAMES) {
            val line = ShellQuote.join(listOf("sh", name))
            assertFalse("raw control code in: $line", line.any { it < ' ' || it in '\u007f'..'\u009f' })
        }
    }

    // The real check: bash, which runs the Terminal's command line, reads each quoted word back
    // unchanged, and nothing in it runs.
    @Test
    fun bashReadsEveryHostileWordBackUnchanged() {
        val bash = listOf("/bin/bash", "/usr/bin/bash").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("needs bash", bash != null)
        val dir = tempDir("quote")
        try {
            for (name in HOSTILE_NAMES + CONTROL_NAMES) {
                val line = ShellQuote.join(listOf("sh", "-c", "printf '%s' \"\$1\"", "_", name))
                val process = ProcessBuilder(bash!!.path, "-c", line).directory(dir).redirectErrorStream(true).start()
                val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
                process.waitFor(10, TimeUnit.SECONDS)
                assertEquals("word: <$name>", name, out)
            }
            assertFalse("a quoted word ran a command", File(dir, "pwned").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun posixQuotesASingleQuoteAndKeepsControlCharacters() {
        assertEquals("'it'\\''s'", ShellQuote.posix("it's"))
        assertEquals("'a\u001bb\nc'", ShellQuote.posix("a\u001bb\nc"))
        assertEquals("''", ShellQuote.posix(""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun posixRefusesANul() {
        ShellQuote.posix("a\u0000b")
    }
}
