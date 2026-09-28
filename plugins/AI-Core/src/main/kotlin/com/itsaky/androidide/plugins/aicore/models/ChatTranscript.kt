package com.itsaky.androidide.plugins.aicore.models

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * The plain-text form a chat is exported to and imported from (ADFA-6247).
 *
 * Readable without any tool — a short `#` preamble, then one block per message headed by who sent
 * it — and strict enough to read back: a file this did not write is refused rather than guessed at.
 *
 * ```
 * # Code on the Go AI Agent chat
 * # format: 1
 * # name: Build failure
 *
 * --- USER 2026-09-24T10:00:01Z
 * Why does the build fail?
 *
 * --- AGENT 2026-09-24T10:00:05Z duration=4210
 * The action failed.
 * --- MODEL WROTE
 * I'll read the build file first.
 * ```
 *
 * `--- MODEL WROTE` opens [ChatMessage.historyText], present only when it differs from the bubble.
 * A message line that would read as a header or that marker is escaped with one leading backslash,
 * and so is one already starting with backslashes before one — so every line reads back as written.
 */
object ChatTranscript {

    /** What the export is saved as; the system picker suggests `.txt` from it. */
    const val MIME_TYPE = "text/plain"

    /** A file larger than this is not a transcript this plugin wrote, and is not read in whole. */
    const val MAX_IMPORT_BYTES = 4L * 1024 * 1024

    private const val MAGIC = "# Code on the Go AI Agent chat"
    /** How far [read] looks for [MAGIC] before refusing the file, blank lines before it included. */
    private const val MAX_MAGIC_SEARCH_BYTES = 4 * 1024
    private const val BOM = "\uFEFF"
    private const val FORMAT_VERSION = "1"
    private const val HEADER_PREFIX = "--- "
    private const val HISTORY_MARKER = "--- MODEL WROTE"
    private const val KEY_FORMAT = "format"
    private const val KEY_NAME = "name"
    private const val TOKEN_DURATION = "duration="
    private const val TOKEN_STATUS = "status="
    private const val FILE_EXTENSION = ".txt"
    private const val FALLBACK_FILE_NAME = "chat"
    /** In code points, so 60 four-byte emoji plus `.txt` still fit a 255-byte file name. */
    private const val MAX_FILE_NAME_LENGTH = 60

    private val HEADER = Regex("""^--- (USER|AGENT|SYSTEM|TOOL) (\S+)((?: \S+)*)$""")
    private val UNSAFE_FILE_NAME_CHARS = Regex("""[\\/:*?"<>|\p{Cntrl}]+""")
    private val WHITESPACE = Regex("""\s+""")

    /** The file is not a transcript this plugin can read; [message] says why, for the log. */
    class InvalidTranscriptException(message: String) : Exception(message)

    /**
     * Writes [session] out as a transcript, every message in order and labeled by sender.
     *
     * @param session the chat to export.
     * @return the file's full text.
     */
    fun export(session: ChatSession): String = buildString {
        append(MAGIC).append('\n')
        append("# ").append(KEY_FORMAT).append(": ").append(FORMAT_VERSION).append('\n')
        // Only a name the user gave: an auto-titled chat stays auto-titled once imported.
        session.name?.takeIf { it.isNotBlank() }?.let {
            append("# ").append(KEY_NAME).append(": ").append(it.replace(WHITESPACE, " ").trim()).append('\n')
        }
        for (message in session.messages) {
            append('\n').append(header(message)).append('\n')
            appendBody(message.text)
            message.historyText?.let { append(HISTORY_MARKER).append('\n').appendBody(it) }
        }
    }

    /**
     * Writes [session] to [stream] as UTF-8, in the form [export] renders.
     *
     * @param session the chat to export.
     * @param stream where the file goes; left open for the caller to close.
     */
    fun write(session: ChatSession, stream: OutputStream) {
        stream.write(export(session).toByteArray(Charsets.UTF_8))
    }

    /**
     * Reads [stream] as UTF-8 text, refusing one past [limit] or not opening with [MAGIC] before
     * buffering the rest. By hand, not InputStream.readNBytes: that is API 33, the host runs on 28.
     *
     * @throws IOException when the stream holds more than [limit] bytes.
     * @throws InvalidTranscriptException when the first line is not the transcript header.
     */
    fun read(stream: InputStream, limit: Long = MAX_IMPORT_BYTES): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var headerSeen = false
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (out.size().toLong() + read > limit) throw IOException("file larger than $limit bytes")
            out.write(buffer, 0, read)
            // Copies only the few KB before the header; a picked log is refused after one chunk.
            if (!headerSeen) headerSeen = opensWithMagic(out.toByteArray())
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /**
     * Reads a transcript back as a brand-new chat: fresh ids throughout, so it can never stand in
     * for, or overwrite, the chat it was exported from.
     *
     * @param text the file's full text.
     * @param projectKey the project the new chat belongs to.
     * @param now when the chat is created, which puts it at the top of the newest-first list.
     * @return the chat, not yet added to any list.
     * @throws InvalidTranscriptException when [text] is not a transcript this plugin wrote.
     */
    fun parse(text: String, projectKey: String?, now: Long = System.currentTimeMillis()): ChatSession {
        val lines = text.removePrefix(BOM).replace("\r\n", "\n").split('\n')
        val firstLine = lines.indexOfFirst { it.isNotBlank() }
        if (firstLine < 0 || lines[firstLine].trim() != MAGIC) {
            throw InvalidTranscriptException("missing the transcript header")
        }

        var index = firstLine + 1
        var format: String? = null
        var name: String? = null
        while (index < lines.size && !isHeader(lines[index])) {
            val line = lines[index++]
            if (line.isBlank()) continue
            if (!line.startsWith("#")) throw InvalidTranscriptException("text before the first message")
            val key = line.removePrefix("#").substringBefore(':').trim()
            val value = line.substringAfter(':', "").trim()
            when (key) {
                KEY_FORMAT -> format = value
                KEY_NAME -> name = value.takeIf { it.isNotEmpty() }
            }
        }
        if (format != FORMAT_VERSION) throw InvalidTranscriptException("unsupported format $format")

        val messages = mutableListOf<ChatMessage>()
        while (index < lines.size) {
            val header = matchHeader(lines[index++])
                ?: throw InvalidTranscriptException("expected a message header")
            val body = mutableListOf<String>()
            while (index < lines.size && !isHeader(lines[index])) {
                body += lines[index++]
            }
            // Only the separator export writes (or the empty string after the file's last newline).
            if (body.lastOrNull() == "") body.removeAt(body.lastIndex)
            val marker = body.indexOfFirst(::isHistoryMarker)
            val text = if (marker < 0) body else body.subList(0, marker)
            val history = if (marker < 0) null else body.subList(marker + 1, body.size)
            messages += message(header, text.joinBody(), history?.joinBody())
        }
        return ChatSession(createdAt = now, messages = messages, projectKey = projectKey, name = name)
    }

    /**
     * The file name the export picker suggests: the chat's title, stripped of what file systems
     * refuse, or a generic name when nothing usable is left.
     *
     * @param title the title the chat is listed under.
     * @return a name ending in `.txt`.
     */
    fun fileName(title: String): String {
        val base = title.replace(UNSAFE_FILE_NAME_CHARS, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .let(::capFileName)
            .trim()
            .trim('.')
            .ifEmpty { FALLBACK_FILE_NAME }
        return base + FILE_EXTENSION
    }

    /** [base] cut to [MAX_FILE_NAME_LENGTH] code points, so an emoji is never split in half. */
    private fun capFileName(base: String): String =
        if (base.codePointCount(0, base.length) <= MAX_FILE_NAME_LENGTH) base
        else base.substring(0, base.offsetByCodePoints(0, MAX_FILE_NAME_LENGTH))

    /**
     * Whether [prefix] holds the whole first non-blank line and it is [MAGIC]; false while that
     * line is still incomplete. The same test [parse] applies, run before the file is buffered.
     *
     * @throws InvalidTranscriptException once that line is complete and is not [MAGIC], or none
     *   has ended within [MAX_MAGIC_SEARCH_BYTES].
     */
    private fun opensWithMagic(prefix: ByteArray): Boolean {
        val text = String(prefix, Charsets.UTF_8).removePrefix(BOM)
        val start = text.indexOfFirst { !it.isWhitespace() }
        val end = if (start < 0) -1 else text.indexOf('\n', start)
        if (end < 0) {
            if (prefix.size > MAX_MAGIC_SEARCH_BYTES) throw InvalidTranscriptException("missing the transcript header")
            return false
        }
        if (text.substring(start, end).trim() != MAGIC) throw InvalidTranscriptException("missing the transcript header")
        return true
    }

    /** Trailing whitespace is ignored, since an editor may add it to a line this wrote without. */
    private fun matchHeader(line: String): MatchResult? = HEADER.matchEntire(line.trimEnd())

    private fun isHeader(line: String): Boolean = matchHeader(line) != null

    private fun isHistoryMarker(line: String): Boolean = line.trimEnd() == HISTORY_MARKER

    private fun isStructural(line: String): Boolean = isHeader(line) || isHistoryMarker(line)

    private fun StringBuilder.appendBody(text: String): StringBuilder = apply {
        text.split('\n').forEach { append(escape(it)).append('\n') }
    }

    private fun List<String>.joinBody(): String = joinToString("\n", transform = ::unescape)

    private fun header(message: ChatMessage): String = buildString {
        append(HEADER_PREFIX).append(message.sender.name).append(' ')
        append(Instant.ofEpochMilli(message.timestamp))
        // The duration is what marks an agent turn finished, which restoring history relies on.
        message.durationMs?.let { append(' ').append(TOKEN_DURATION).append(it) }
        // SENT is the default and a LOADING reply is written as it stands, so neither is spelled out.
        if (message.status == MessageStatus.ERROR || message.status == MessageStatus.COMPLETED) {
            append(' ').append(TOKEN_STATUS).append(message.status.name)
        }
    }

    private fun message(header: MatchResult, text: String, historyText: String?): ChatMessage {
        val (sender, stamp, tokens) = header.destructured
        val timestamp = try {
            Instant.parse(stamp).toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw InvalidTranscriptException("bad timestamp $stamp")
        }
        var durationMs: Long? = null
        var status = MessageStatus.SENT
        tokens.trim().split(' ').filter { it.isNotEmpty() }.forEach { token ->
            when {
                token.startsWith(TOKEN_DURATION) -> durationMs = token.removePrefix(TOKEN_DURATION)
                    .toLongOrNull() ?: throw InvalidTranscriptException("bad duration $token")
                token.startsWith(TOKEN_STATUS) -> status = MessageStatus.entries
                    .firstOrNull { it.name == token.removePrefix(TOKEN_STATUS) } ?: MessageStatus.SENT
            }
        }
        // A reply still streaming when it was exported must not spin forever once imported.
        if (status == MessageStatus.LOADING) status = MessageStatus.SENT
        return ChatMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            sender = Sender.valueOf(sender),
            status = status,
            timestamp = timestamp,
            durationMs = durationMs,
            historyText = historyText,
        )
    }

    private fun escape(line: String): String =
        if (isStructural(line.trimStart('\\'))) "\\" + line else line

    private fun unescape(line: String): String =
        if (line.startsWith('\\') && isStructural(line.trimStart('\\'))) line.substring(1) else line
}
