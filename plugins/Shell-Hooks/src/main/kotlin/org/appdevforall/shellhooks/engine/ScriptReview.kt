package org.appdevforall.shellhooks.engine

import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Whether a project script can be reviewed, and reading it to find out.
 *
 * The review dialog shows a project script so the user can see what will run, and only a plain
 * script can be shown so that what it displays is what the shell reads. Each review of this plugin
 * found another way to make a script read differently from how it runs: a no-break space bash
 * takes as part of a word, a backslash before an invisible carriage return or trailing blank that
 * reads as a line continuation, a run of spaces whose soft wrap looks like a new line, a tab that
 * looks like a space where `<<-` tells them apart. Rather than display each such trick, a project
 * script can be trusted only when it is plain:
 * - at most [MAX_BYTES] bytes;
 * - every byte printable ASCII (0x20-0x7E) or a newline: no tab, carriage return, other control
 *   byte or byte outside ASCII;
 * - no line longer than [MAX_LINE] characters, and none ending in a space (a last line without a
 *   newline is fine);
 * - a name inside the hooks folder of letters, digits, `.`, `_`, `-` and `/`, with no part starting
 *   with `.`.
 * Anything else is listed with the reason it cannot be reviewed, and never runs.
 *
 * Plain text shows the reader every byte; it does not show how the shell parses them. A plain
 * script can still read as inert and run a command. [ScriptHints] marks, as a best effort, lines that
 * use syntax worth a closer look.
 */
object ScriptReview {
    const val MAX_BYTES = 64 * 1024
    const val MAX_LINE = 100

    private val SAFE_NAME = Regex("[A-Za-z0-9._/-]+")

    /** Why a script cannot be reviewed; [message] completes "Can't be reviewed: ". */
    sealed interface Reason {
        val message: String

        data object TooLarge : Reason {
            override val message = "it is larger than ${MAX_BYTES / 1024} KB"
        }

        data object UnsafeName : Reason {
            override val message = "its name uses characters other than letters, digits, '.', '_', '-' and '/'"
        }

        data object HiddenName : Reason {
            override val message = "its name has a part that starts with '.'"
        }

        data class BadByte(
            val line: Int,
            val column: Int,
            val value: Int,
        ) : Reason {
            override val message: String
                get() {
                    val what =
                        when (value) {
                            0x09 -> "a tab"
                            0x0D -> "a carriage return"
                            in 0x00..0x1F, 0x7F -> "a control character (0x%02X)".format(value)
                            else -> "byte 0x%02X (not plain ASCII)".format(value)
                        }
                    return "line $line, column $column: $what"
                }
        }

        data class LongLine(
            val line: Int,
        ) : Reason {
            override val message = "line $line is longer than $MAX_LINE characters"
        }

        data class TrailingSpace(
            val line: Int,
        ) : Reason {
            override val message = "line $line ends in a space"
        }
    }

    /** What [assess] found. */
    sealed interface Assessment {
        /** A plain script: these [bytes] can be shown, as [lines], and trusted. */
        data class Reviewable(
            val bytes: ByteArray,
            val sha256: String,
        ) : Assessment {
            val lines: List<String> get() = lines(bytes)
        }

        data class NotReviewable(
            val reason: Reason,
        ) : Assessment

        /**
         * The file could not be read. [cause] is for the log only: an IOException's message starts
         * with the path, which the project chose, so it is never shown as it is.
         */
        data class Unreadable(
            val cause: String,
        ) : Assessment
    }

    /**
     * Whether [file], at [relativePath] inside its hooks folder, can be reviewed. The name is
     * checked before anything is read, and no more than [MAX_BYTES] + 1 bytes ever are.
     */
    fun assess(
        file: File,
        relativePath: String,
    ): Assessment {
        checkName(relativePath)?.let { return Assessment.NotReviewable(it) }
        val bytes =
            try {
                readAtMost(file, MAX_BYTES)
            } catch (e: IOException) {
                return Assessment.Unreadable(e.message ?: e.javaClass.simpleName)
            } ?: return Assessment.NotReviewable(Reason.TooLarge)
        checkBytes(bytes)?.let { return Assessment.NotReviewable(it) }
        return Assessment.Reviewable(bytes, TrustStore.sha256(bytes))
    }

    /** Why [relativePath] and [bytes] cannot be reviewed, or null when they can. */
    fun check(
        relativePath: String,
        bytes: ByteArray,
    ): Reason? = checkName(relativePath) ?: checkBytes(bytes)

    /** Why a script named [relativePath] inside its hooks folder cannot be reviewed, or null. */
    fun checkName(relativePath: String): Reason? {
        if (!SAFE_NAME.matches(relativePath)) return Reason.UnsafeName
        if (relativePath.split('/').any { it.startsWith(".") }) return Reason.HiddenName
        return null
    }

    /** The first reason [bytes] cannot be reviewed, by position, or null when they can. */
    fun checkBytes(bytes: ByteArray): Reason? {
        if (bytes.size > MAX_BYTES) return Reason.TooLarge
        var line = 1
        var column = 0
        var last = -1
        for (b in bytes) {
            val value = b.toInt() and 0xFF
            when {
                value == 0x0A -> {
                    if (last == 0x20) return Reason.TrailingSpace(line)
                    line++
                    column = 0
                }
                value in 0x20..0x7E -> {
                    column++
                    if (column > MAX_LINE) return Reason.LongLine(line)
                }
                else -> return Reason.BadByte(line, column + 1, value)
            }
            last = value
        }
        if (last == 0x20) return Reason.TrailingSpace(line)
        return null
    }

    /** The lines of a reviewable script, without their newlines; no empty last line for a final newline. */
    fun lines(bytes: ByteArray): List<String> {
        val text = String(bytes, Charsets.US_ASCII)
        val lines = text.split('\n')
        return if (text.endsWith('\n')) lines.dropLast(1) else lines
    }

    /**
     * [file]'s bytes, or null when it holds more than [max]. Checks the length first and never
     * reads past [max] + 1, so a file that grows while it is read is still caught.
     */
    @Throws(IOException::class)
    fun readAtMost(
        file: File,
        max: Int,
    ): ByteArray? = readAtMost(file.length(), { file.inputStream() }, max)

    /** [readAtMost] given the file's reported [length] and a way to [open] it. */
    @Throws(IOException::class)
    internal fun readAtMost(
        length: Long,
        open: () -> InputStream,
        max: Int,
    ): ByteArray? {
        if (length > max) return null
        open().use { input ->
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
     * A name as shown, how many characters in it were made visible, and where each `<U+XXXX>` it
     * put in sits in [text], so a view can mark it apart from the same characters typed in a name.
     */
    data class Revealed(
        val text: String,
        val hidden: Int,
        val escapes: List<IntRange> = emptyList(),
    )

    /**
     * Shows as `<U+XXXX>` every character of a name the project chose (a project folder, a file
     * the run was about, a script that cannot be reviewed) that could make it read differently
     * from what it is: everything outside plain ASCII (bidirectional controls, zero-width
     * characters, no-break spaces), every control character including tab and newline, and every
     * space at the end of the name or before a newline. Only printable ASCII is shown as it is: a
     * name is one line, so a newline in one must not add a line of text to a dialog. Characters
     * are taken one code point at a time, so a supplementary character shows as one escape and an
     * unpaired surrogate is shown too. Scripts themselves are not revealed: one that would need it
     * cannot be reviewed at all.
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
    // at the end of the text).
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

    private fun isPlain(cp: Int): Boolean = cp in 0x20..0x7E
}
