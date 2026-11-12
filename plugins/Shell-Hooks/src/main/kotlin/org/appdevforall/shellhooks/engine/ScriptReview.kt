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

    /**
     * [text] as shown for review, how many characters in it were made visible, and where each
     * `<U+XXXX>` it put in sits in [text], so a view can mark them apart from the same six
     * characters typed in the script.
     */
    data class Revealed(
        val text: String,
        val hidden: Int,
        val escapes: List<IntRange> = emptyList(),
    )

    /**
     * Shows as `<U+XXXX>` every character that could make a script read differently from how it
     * runs:
     * - everything outside plain ASCII: bidirectional controls that make a command read as a
     *   comment ("Trojan Source"), zero-width characters, or a no-break space, which bash takes as
     *   part of a word, so `true<NBSP># cmd` defines or calls a function named `true<NBSP>#` while
     *   looking like a comment;
     * - every carriage return, Windows line endings included;
     * - every space, tab or carriage return at the end of a line. After a backslash these turn
     *   what reads as a line continuation (`: comment \`) into two commands: the shell escapes the
     *   invisible character, and the next line runs on its own.
     * Rather than list the dangerous characters, only printable ASCII, tab and newline are shown as
     * they are. Characters are taken one code point at a time, so a supplementary character such as
     * a tag (U+E0041) shows as one escape, and an unpaired surrogate is shown too.
     */
    fun reveal(text: String): Revealed {
        val trailing = trailingBlanks(text)
        val out = StringBuilder(text.length)
        val escapes = mutableListOf<IntRange>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (isPlain(cp) && !trailing[i]) {
                out.appendCodePoint(cp)
            } else {
                val start = out.length
                out.append("<U+").append(Integer.toHexString(cp).uppercase().padStart(4, '0')).append('>')
                escapes += start until out.length
            }
            i += Character.charCount(cp)
        }
        return Revealed(out.toString(), escapes.size, escapes)
    }

    // Marks each space, tab or carriage return in the run that ends a line (before a newline, or
    // at the end of the text). All of the run, not only its last character: after `\` the first
    // blank is escaped and the rest is just as invisible.
    private fun trailingBlanks(text: String): BooleanArray {
        val marks = BooleanArray(text.length)
        var inTrail = true
        for (j in text.indices.reversed()) {
            val c = text[j]
            when {
                c == '\n' -> inTrail = true
                inTrail && (c == ' ' || c == '\t' || c == '\r') -> marks[j] = true
                else -> inTrail = false
            }
        }
        return marks
    }

    /** [bytes] decoded as UTF-8 and revealed: what the review dialog shows. */
    fun revealBytes(bytes: ByteArray): Revealed = reveal(String(bytes, Charsets.UTF_8))

    private fun isPlain(cp: Int): Boolean = cp == '\t'.code || cp == '\n'.code || cp in 0x20..0x7E
}
