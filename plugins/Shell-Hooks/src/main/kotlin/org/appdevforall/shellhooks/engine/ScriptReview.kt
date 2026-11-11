package org.appdevforall.shellhooks.engine

import java.io.File
import java.io.IOException

/**
 * Reading a project script for the trust check and for the user's review. A project can ship a
 * script of any size, so neither ever reads more than [MAX_BYTES]: a larger script is never read,
 * never run, and cannot be reviewed in the IDE.
 */
object ScriptReview {
    const val MAX_BYTES = 256 * 1024

    /** What [load] found. */
    sealed interface Loaded {
        data class Script(
            val bytes: ByteArray,
            val sha256: String,
        ) : Loaded

        data object TooLarge : Loaded

        data class Unreadable(
            val reason: String,
        ) : Loaded
    }

    fun load(file: File): Loaded =
        try {
            val bytes = readAtMost(file, MAX_BYTES)
            if (bytes == null) Loaded.TooLarge else Loaded.Script(bytes, TrustStore.sha256(bytes))
        } catch (e: IOException) {
            Loaded.Unreadable(e.message ?: e.javaClass.simpleName)
        }

    /**
     * [file]'s bytes, or null when it holds more than [max]. Checks the length first and never
     * reads past [max] + 1, so a file that grows while it is read is still caught.
     */
    @Throws(IOException::class)
    fun readAtMost(
        file: File,
        max: Int,
    ): ByteArray? {
        if (file.length() > max) return null
        file.inputStream().use { input ->
            val buffer = ByteArray(max + 1)
            var read = 0
            while (read < buffer.size) {
                val n = input.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            return if (read > max) null else buffer.copyOf(read)
        }
    }

    /** [text] as shown for review, and how many characters in it were made visible. */
    data class Revealed(
        val text: String,
        val hidden: Int,
    )

    /**
     * Shows everything outside plain ASCII as `<U+XXXX>`. A script can use such characters to read
     * differently from how it runs: bidirectional controls that make a command read as a comment
     * ("Trojan Source"), zero-width characters, or a no-break space, which bash takes as part of a
     * word, so `true<NBSP># cmd` defines or calls a function named `true<NBSP>#` while looking
     * like a comment. Rather than list the dangerous ones, only printable ASCII, tab and newline
     * are shown as they are, plus a carriage return directly before a newline (a Windows line
     * ending). Characters are taken one code point at a time, so a supplementary character such
     * as a tag (U+E0041) shows as one escape, and an unpaired surrogate is shown too.
     */
    fun reveal(text: String): Revealed {
        val out = StringBuilder(text.length)
        var hidden = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val crlf = cp == '\r'.code && i + 1 < text.length && text[i + 1] == '\n'
            if (crlf || isPlain(cp)) {
                out.appendCodePoint(cp)
            } else {
                out.append("<U+").append(Integer.toHexString(cp).uppercase().padStart(4, '0')).append('>')
                hidden++
            }
            i += Character.charCount(cp)
        }
        return Revealed(out.toString(), hidden)
    }

    /** [bytes] decoded as UTF-8 and revealed: what the review dialog shows. */
    fun revealBytes(bytes: ByteArray): Revealed = reveal(String(bytes, Charsets.UTF_8))

    private fun isPlain(cp: Int): Boolean = cp == '\t'.code || cp == '\n'.code || cp in 0x20..0x7E
}
