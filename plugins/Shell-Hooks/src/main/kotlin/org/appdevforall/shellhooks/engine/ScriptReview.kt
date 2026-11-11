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
     * Makes characters that change how a script looks without looking like anything visible as
     * `<U+XXXX>`: bidirectional controls (the "Trojan Source" trick, which can make a command read
     * as a comment), zero-width characters, the byte-order mark, the replacement character that
     * stands in for bytes that are not UTF-8, and control characters other than tab and newline.
     * A carriage return directly before a newline is a Windows line ending and stays as it is.
     */
    fun reveal(text: String): Revealed {
        val out = StringBuilder(text.length)
        var hidden = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val crlf = c == '\r' && i + 1 < text.length && text[i + 1] == '\n'
            if (!crlf && isDeceptive(c)) {
                out.append("<U+").append("%04X".format(c.code)).append('>')
                hidden++
            } else {
                out.append(c)
            }
            i++
        }
        return Revealed(out.toString(), hidden)
    }

    private fun isDeceptive(c: Char): Boolean {
        val code = c.code
        return when {
            c == '\t' || c == '\n' -> false
            code < 0x20 || code in 0x7F..0x9F -> true
            code in 0x202A..0x202E || code in 0x2066..0x2069 -> true
            code == 0x200E || code == 0x200F || code == 0x061C -> true
            code in 0x200B..0x200D || code == 0x2060 || code == 0xFEFF -> true
            code == 0xFFFD -> true
            else -> false
        }
    }
}
