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
    fun commandPutsAssignmentsBeforeTheWords() {
        assertEquals("A='1' B='x y' 'sh' 'run'", ShellQuote.command(linkedMapOf("A" to "1", "B" to "x y"), listOf("sh", "run")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun commandRejectsAnUnsafeVariableName() {
        ShellQuote.command(mapOf("A;rm" to "1"), listOf("sh"))
    }

    @Test
    fun aControlCharacterIsWrittenAsItsBytes() {
        assertEquals("'a'\$'\\x1b''b'", ShellQuote.quote("a\u001bb"))
        assertEquals("'x'\$'\\x1b\\x07''y'", ShellQuote.quote("x\u001b\u0007y"))
        // C1 CSI as its UTF-8 bytes, so the escape does not depend on the shell's locale.
        assertEquals("''\$'\\xc2\\x9b'''", ShellQuote.quote("\u009b"))
    }

    // The Terminal prints the line before running it; a raw control code there would reach the terminal.
    @Test
    fun noControlCharacterReachesTheCommandLineRaw() {
        for (name in CONTROL_NAMES + HOSTILE_NAMES) {
            val line = ShellQuote.command(mapOf("V" to name), listOf("sh", name))
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
                // The assignment reaches the inner shell's environment; "$V" in the outer line would not see it.
                val line = ShellQuote.command(mapOf("V" to name), listOf("sh", "-c", "printf '%s|%s' \"\$1\" \"\$V\"", "_", name))
                val process = ProcessBuilder(bash!!.path, "-c", line).directory(dir).redirectErrorStream(true).start()
                val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
                process.waitFor(10, TimeUnit.SECONDS)
                assertEquals("word: <$name>", "$name|$name", out)
            }
            assertFalse("a quoted word ran a command", File(dir, "pwned").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    private companion object {
        /** Names with terminal control codes: an OSC 52 clipboard write, a CR, a tab, DEL, C1 CSI. */
        val CONTROL_NAMES =
            listOf(
                "x\u001b]52;c;ZWNobyBwd25lZA==\u0007.txt",
                "car\rriage.kt",
                "tab\t.kt",
                "del\u007f.kt",
                "csi\u009b31m.kt",
                "quote'\u001b'mixed.kt",
            )
    }
}
