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

    // The real check: a shell reads each quoted word back unchanged, and nothing in it runs.
    @Test
    fun aShellReadsEveryHostileWordBackUnchanged() {
        val sh = File("/bin/sh")
        assumeTrue("needs /bin/sh", sh.canExecute())
        val dir = tempDir("quote")
        try {
            for (name in HOSTILE_NAMES) {
                // The assignment reaches the inner shell's environment; "$V" in the outer line would not see it.
                val line = ShellQuote.command(mapOf("V" to name), listOf("sh", "-c", "printf '%s|%s' \"\$1\" \"\$V\"", "_", name))
                val process = ProcessBuilder(sh.path, "-c", line).directory(dir).redirectErrorStream(true).start()
                val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
                process.waitFor(10, TimeUnit.SECONDS)
                assertEquals("word: <$name>", "$name|$name", out)
            }
            assertFalse("a quoted word ran a command", File(dir, "pwned").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
