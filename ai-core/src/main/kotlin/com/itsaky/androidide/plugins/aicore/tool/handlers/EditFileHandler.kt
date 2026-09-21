package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.ToolSchema
import com.itsaky.androidide.plugins.aicore.tool.Validation
import com.itsaky.androidide.plugins.aicore.tool.handlers.edit.AtomicFileWriter
import com.itsaky.androidide.plugins.aicore.tool.handlers.edit.EditTargetResolver
import com.itsaky.androidide.plugins.aicore.tool.handlers.edit.EditorBufferApplier
import com.itsaky.androidide.plugins.aicore.tool.handlers.edit.FileTextMatcher
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.tool.parseToolBoolean
import com.itsaky.androidide.plugins.services.IdeEditorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Surgical find/replace edit of a project file: the model supplies the exact snippet ([ARG_OLD])
 * and its replacement ([ARG_NEW]), which survives a local model's reply budget where a whole-file
 * rewrite does not. Decides *what* the edit is; applying it belongs to `handlers/edit`.
 * @param pluginContext host services (editor access).
 * @param mainDispatcher dispatcher for editor-UI calls; overridden in unit tests.
 */
class EditFileHandler(
    private val pluginContext: PluginContext,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ToolHandler {

    override val toolName = TOOL_NAME
    override val parametersSchema = ToolSchema.objectOf(
        ARG_PATH to ToolSchema.string("Project-relative path of the file to edit."),
        ARG_OLD to ToolSchema.string(
            "The exact text to find, copied byte-for-byte from the file including indentation."
        ),
        ARG_NEW to ToolSchema.string("What to put in its place; empty deletes it."),
        ARG_REPLACE_ALL to ToolSchema.boolean(
            "Replace every occurrence. When false the text must match exactly once."
        ),
        required = listOf(ARG_PATH, ARG_OLD, ARG_NEW),
    )
    override val description =
        "Edit an existing file by replacing an exact snippet: give file_path, old_string " +
            "(text to find, copied exactly including indentation) and new_string (its " +
            "replacement; empty deletes it). old_string must match exactly once unless " +
            "replace_all is true. Prefer this over update_file for changing a file."
    override val requiresApproval = true
    override val mutatesProject = true
    override val pathArgs = listOf(ARG_PATH)
    override val argAliases = mapOf(
        "old" to ARG_OLD,
        "old_text" to ARG_OLD,
        "search" to ARG_OLD,
        "new" to ARG_NEW,
        "new_text" to ARG_NEW,
        "replace" to ARG_NEW,
        "content" to ARG_NEW,
    )

    companion object {
        const val TOOL_NAME = "edit_file"

        const val ARG_PATH = "file_path"
        const val ARG_OLD = "old_string"
        const val ARG_NEW = "new_string"
        const val ARG_REPLACE_ALL = "replace_all"

        /**
         * Fingerprint of the text [validate] matched, so [execute] can tell whether the file changed
         * while the approval dialog was open. Written by [validate] only; a call without it skips
         * the check. Underscored so it cannot collide with a model-supplied key.
         */
        const val ARG_REVIEWED_FINGERPRINT = "__reviewed_content"

        /**
         * Largest file this tool will read. Editing holds the file plus the edited copy in memory,
         * a project tree legitimately contains jars, `.gguf` models and APKs, and an
         * `OutOfMemoryError` on one of those is not catchable here — it kills the IDE process.
         */
        const val MAX_EDIT_BYTES = 1L * 1024 * 1024

        /** Cap on each model-supplied snippet; the tool-call grammar bounds neither. */
        const val MAX_ARG_CHARS = 64 * 1024

        /** Bytes sampled when deciding whether a file is binary. */
        private const val BINARY_SNIFF_BYTES = 8 * 1024

        /** Longest [ARG_OLD] still treated as a bare name rather than a code region. */
        private const val MAX_IDENTIFIER_CHARS = 64

        /**
         * A request phrased as an instruction ("_bind with _binding", "foo -> bar"), which small
         * models paste into [ARG_OLD] *and* [ARG_NEW] unchanged. Recognising it turns a rejection the
         * model just repeats verbatim — three times, then the agent loop gives up — into one that
         * hands it the two values it should have sent.
         */
        private val INSTRUCTION_PAIR =
            Regex("""^(\S+)\s+(?:with|to|into|by|for|->|=>)\s+(\S+)$""", RegexOption.IGNORE_CASE)
    }

    /**
     * Pre-approval check: everything [execute] would reject anyway, without touching a byte, so the
     * user is never shown a dialog for an edit that cannot apply. Small models produce those
     * constantly — an identical old/new pair, a hallucinated path — and each one cost a dialog.
     * @param args the normalized call arguments.
     * @return acceptance carrying the resolved path, or the failure to report instead.
     */
    override suspend fun validate(args: Map<String, Any?>): Validation =
        when (val analysis = analyze(args, tracing = false)) {
            is Analysis.Rejected -> Validation.Rejected(analysis.result)
            // The resolved path so the user reviews the real file, and the fingerprint for execute().
            is Analysis.Ready -> Validation.Accepted(
                args + mapOf(
                    ARG_PATH to analysis.filePath,
                    ARG_REVIEWED_FINGERPRINT to fingerprintOf(analysis.original),
                )
            )
        }

    /**
     * Change-detection fingerprint of the text an edit was reviewed against. Length plus
     * [String.hashCode] — specified exactly by the JVM, so stable across processes — catches a file
     * rewritten between review and application. Detects accidents, not a crafted collision.
     * @param text the matched content — the editor buffer when the file is open, else the disk copy.
     * @return an opaque fingerprint, comparable only against another value from this function.
     */
    private fun fingerprintOf(text: String): String = "${text.length}:${text.hashCode()}"

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        // Re-analyzed, not reused: the file may have changed while the dialog was open.
        val analysis = try {
            analyze(args, tracing = true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            pluginContext.logger.error("edit_file: failed to read ${args[ARG_PATH]} for editing", e)
            return ToolResult.failure("Error editing file: ${e.message}", e.stackTraceToString())
        }

        val ready = when (analysis) {
            is Analysis.Rejected -> return analysis.result
            is Analysis.Ready -> analysis
        }

        // A rewritten file can still match old_string once, in surroundings nobody reviewed.
        val reviewed = args[ARG_REVIEWED_FINGERPRINT]?.toString()
        if (reviewed != null && reviewed != fingerprintOf(ready.original)) {
            AgentTrace.refusal(
                "EDIT",
                "apply path=${ready.file.name}",
                "content changed after approval; not applying a diff the user never saw",
            )
            return ToolResult.failure(
                "${ready.filePath} changed after this edit was approved — read the file again " +
                    "and redo the edit against its current text"
            )
        }

        return try {
            // The snippets FileTextMatcher resolved, which may differ from the model's line endings.
            val updated = ready.original.replace(ready.oldString, ready.newString)

            // Nothing has been mutated yet; a Stop here must leave the file untouched.
            currentCoroutineContext().ensureActive()

            val summary = "replaced ${ready.occurrences} occurrence(s) of " +
                "${ready.oldString.length} chars with ${ready.newString.length} chars"

            if (ready.buffer != null && ready.editorService != null) {
                applyToEditor(ready, updated, summary)
            } else {
                applyToDisk(ready, updated, summary)
            }
        } catch (ce: CancellationException) {
            // Before catch(Exception): it is an Exception on the JVM, so a broad catch eats Stop.
            throw ce
        } catch (e: Exception) {
            pluginContext.logger.error("edit_file: failed to apply the edit to ${ready.filePath}", e)
            ToolResult.failure("Error editing file: ${e.message}", e.stackTraceToString())
        }
    }

    /** Outcome of [analyze]: an edit ready to apply, or the failure to report instead. */
    private sealed interface Analysis {
        /**
         * Everything needed to apply the edit, already validated.
         * @property original the text that was matched — the editor buffer when [buffer] is
         *   non-null, otherwise the on-disk contents.
         * @property oldString the snippet **as it appears in [original]**, which for a CRLF file
         *   is not necessarily the string the model supplied.
         */
        data class Ready(
            val file: File,
            val filePath: String,
            val oldString: String,
            val newString: String,
            val original: String,
            val buffer: String?,
            val editorService: IdeEditorService?,
            val occurrences: Int,
        ) : Analysis

        data class Rejected(val result: ToolResult) : Analysis
    }

    /**
     * Works out what the edit would do, without changing anything. Shared by [validate] (before
     * approval) and [execute] (after it) so the two can never disagree about whether an edit
     * applies; a check living only in `execute` would surprise a user who already approved.
     * @param args the normalized call arguments.
     * @param tracing whether to emit trace lines; off for the pre-approval pass, which would
     *   otherwise log every analysis twice.
     * @return the applicable edit, or the failure to return.
     */
    private suspend fun analyze(args: Map<String, Any?>, tracing: Boolean): Analysis {
        val filePath = args[ARG_PATH]?.toString()?.trim()
        if (filePath.isNullOrBlank()) {
            return reject("$ARG_PATH is required")
        }

        val oldString = args[ARG_OLD]?.toString()
        if (oldString.isNullOrEmpty()) {
            return reject("$ARG_OLD is required — the exact text to replace, copied from the file")
        }
        // Presence, not blankness: an empty new_string is a deletion, which is a legal edit.
        if (!args.containsKey(ARG_NEW)) {
            return reject("$ARG_NEW is required — use an empty string to delete $ARG_OLD")
        }
        val newString = args[ARG_NEW]?.toString() ?: ""
        val replaceAll = parseToolBoolean(args[ARG_REPLACE_ALL])

        if (oldString.length > MAX_ARG_CHARS || newString.length > MAX_ARG_CHARS) {
            return reject(
                "$ARG_OLD/$ARG_NEW must be under $MAX_ARG_CHARS characters — edit a smaller region"
            )
        }
        if (oldString == newString) {
            return reject(identicalArgsRejection(oldString))
        }

        val target = when (val resolution = EditTargetResolver.resolve(filePath)) {
            is EditTargetResolver.Target.Rejected -> {
                if (tracing) AgentTrace.refusal("EDIT", "target path=$filePath", resolution.reason)
                return reject(resolution.reason)
            }
            is EditTargetResolver.Target.Resolved -> resolution
        }
        if (tracing && target.correctedFrom != null) {
            AgentTrace.stage("EDIT", "path corrected", "${target.correctedFrom} → ${target.displayPath}")
        }
        val file = target.file
        val resolvedPath = target.displayPath

        // Explicitly nullable: a platform type, but genuinely absent with no editor host.
        val editorService: IdeEditorService? = pluginContext.services.get(IdeEditorService::class.java)
        // Non-null only when the file is open; then it, not the stale disk copy, is what to match.
        val buffer = editorService?.let { withContext(mainDispatcher) { it.getFileContent(file) } }

        // Capped like the disk path: the edited copy is a second allocation of the same size.
        if (buffer != null && buffer.length > MAX_EDIT_BYTES) {
            return reject(
                "File is too large to edit (${buffer.length} characters, limit $MAX_EDIT_BYTES): $resolvedPath"
            )
        }

        val original = buffer ?: when (val read = readFromDisk(file, resolvedPath)) {
            is DiskRead.Failed -> {
                if (tracing) AgentTrace.refusal("EDIT", "read path=${file.name}", read.result.message)
                return Analysis.Rejected(read.result)
            }
            is DiskRead.Text -> read.content
        }
        if (tracing) {
            // Which copy was matched is the first thing to check for an edit on the wrong version.
            AgentTrace.stage(
                "EDIT",
                "source=${if (buffer != null) "editor-buffer" else "disk"} " +
                    "path=${file.name} chars=${original.length} replaceAll=$replaceAll",
            )
        }

        val match = FileTextMatcher.match(original, oldString, newString)
        if (match is FileTextMatcher.Match.NotFound) {
            // The dominant local-model failure: whitespace or escaping drift from the real file.
            if (tracing) {
                AgentTrace.refusal(
                    "EDIT",
                    "match=0 path=${file.name} oldChars=${oldString.length}",
                    "old_string not found — ${AgentTrace.preview(oldString, 80)}",
                )
            }
            return reject(
                "$ARG_OLD not found in $resolvedPath — read the file first and copy the target " +
                    "text exactly, including indentation and line breaks"
            )
        }
        val found = match as FileTextMatcher.Match.Found
        if (found.occurrences > 1 && !replaceAll) {
            if (tracing) {
                AgentTrace.refusal(
                    "EDIT",
                    "match=${found.occurrences} path=${file.name}",
                    "ambiguous old_string — ${AgentTrace.preview(oldString, 80)}",
                )
            }
            return reject(ambiguousRejection(oldString, found.occurrences, resolvedPath))
        }
        if (tracing) {
            AgentTrace.detail(
                "EDIT",
                "match=${found.occurrences} path=${file.name} oldChars=${found.oldString.length} " +
                    "newChars=${found.newString.length} crlfAdapted=${found.lineEndingsAdapted}",
            )
        }

        return Analysis.Ready(
            file = file,
            filePath = resolvedPath,
            oldString = found.oldString,
            newString = found.newString,
            original = original,
            buffer = buffer,
            editorService = editorService,
            occurrences = found.occurrences,
        )
    }

    private fun reject(message: String): Analysis = Analysis.Rejected(ToolResult.failure(message))

    /**
     * Rejection for an edit whose old and new text are the same, naming the corrected pair when the
     * model pasted the user's instruction instead of the file's text.
     * @param text the value sent as both [ARG_OLD] and [ARG_NEW].
     * @return the message to return to the model.
     */
    private fun identicalArgsRejection(text: String): String {
        val base = "$ARG_NEW is identical to $ARG_OLD — nothing to change. Put the text as it " +
            "appears in the file in $ARG_OLD, and what it should become in $ARG_NEW."
        val (from, to) = INSTRUCTION_PAIR.find(text.trim())?.destructured ?: return base
        if (from == to) return base
        return "$base You sent the request itself, not the file's text: retry with " +
            "$ARG_OLD=\"$from\" and $ARG_NEW=\"$to\"."
    }

    /**
     * Rejection for an [ARG_OLD] matching more than once. Which fix leads matters: for a bare name
     * the user almost always meant every occurrence, and a model told to "add surrounding lines"
     * first answers with one call per line instead of a single [ARG_REPLACE_ALL] edit.
     * @param oldString the snippet that matched.
     * @param occurrences how many times it matched.
     * @param path the resolved file path, for the message.
     * @return the message to return to the model.
     */
    private fun ambiguousRejection(oldString: String, occurrences: Int, path: String): String {
        val head = "$ARG_OLD matched $occurrences times in $path — "
        val looksLikeAName =
            oldString.length <= MAX_IDENTIFIER_CHARS && oldString.none { it.isWhitespace() }
        return if (looksLikeAName) {
            head + "it looks like a name used throughout the file. Retry the SAME call with " +
                "\"$ARG_REPLACE_ALL\":\"true\" to change all $occurrences in ONE edit — do not make " +
                "one call per occurrence. Only if you meant a single one, add the surrounding lines " +
                "to $ARG_OLD instead."
        } else {
            head + "add surrounding lines to make it unique, or set $ARG_REPLACE_ALL to true to " +
                "change all of them"
        }
    }

    /** Outcome of reading the on-disk copy: either usable text or the failure to report. */
    private sealed interface DiskRead {
        data class Text(val content: String) : DiskRead
        data class Failed(val result: ToolResult) : DiskRead
    }

    /**
     * Reads [file] as UTF-8 text, refusing anything too large to hold in memory twice or
     * that isn't really text.
     * @param file the resolved target.
     * @param filePath the model-supplied path, for messages.
     * @return the decoded text, or the failure to return to the model.
     */
    private fun readFromDisk(file: File, filePath: String): DiskRead {
        if (!file.canRead()) {
            return DiskRead.Failed(ToolResult.failure("Cannot read file: $filePath"))
        }
        val length = file.length()
        if (length > MAX_EDIT_BYTES) {
            return DiskRead.Failed(
                ToolResult.failure(
                    "File is too large to edit ($length bytes, limit $MAX_EDIT_BYTES): $filePath"
                )
            )
        }

        val bytes = file.readBytes()
        if (looksBinary(bytes)) {
            return DiskRead.Failed(
                ToolResult.failure(
                    "$filePath is not a UTF-8 text file — editing it would corrupt its contents"
                )
            )
        }
        return DiskRead.Text(String(bytes, StandardCharsets.UTF_8))
    }

    /**
     * Hands the edit to the open editor's buffer.
     * @return the result to hand back to the model.
     */
    private suspend fun applyToEditor(
        ready: Analysis.Ready,
        updated: String,
        summary: String,
    ): ToolResult {
        val applier = EditorBufferApplier(requireNotNull(ready.editorService), mainDispatcher)
        return when (
            val outcome = applier.apply(
                file = ready.file,
                displayPath = ready.filePath,
                matched = ready.original,
                updated = updated,
                oldString = ready.oldString,
                newString = ready.newString,
                occurrences = ready.occurrences,
            )
        ) {
            is EditorBufferApplier.Outcome.Failed -> ToolResult.failure(outcome.reason)
            is EditorBufferApplier.Outcome.Applied -> {
                val savedNote =
                    if (outcome.saved) "saved" else "left unsaved in the editor — save it to persist"
                AgentTrace.stage(
                    "EDIT",
                    "apply=editor ok saved=${outcome.saved} path=${ready.file.name} | $summary",
                )
                ToolResult.success(
                    message = "Edited ${ready.filePath} in the editor ($summary); $savedNote. " +
                        "The change can be undone with Ctrl+Z.",
                    data = ready.filePath
                )
            }
        }
    }

    /**
     * Writes the edit straight to disk, for a file no editor tab holds.
     * @return the result to hand back to the model.
     */
    private fun applyToDisk(ready: Analysis.Ready, updated: String, summary: String): ToolResult {
        val bytes = updated.toByteArray(StandardCharsets.UTF_8)
        return when (val outcome = AtomicFileWriter.replace(ready.file, ready.filePath, bytes)) {
            is AtomicFileWriter.Outcome.Failed -> ToolResult.failure(outcome.reason)
            AtomicFileWriter.Outcome.Written -> {
                AgentTrace.stage(
                    "EDIT",
                    "apply=disk ok bytes=${bytes.size} path=${ready.file.name} | $summary",
                )
                ToolResult.success(
                    message = "Edited ${ready.filePath} ($summary)",
                    data = ready.filePath
                )
            }
        }
    }

    /**
     * Whether [bytes] should be treated as binary. A NUL byte is decisive; otherwise the sample is
     * decoded with errors REPORTed, not replaced — a lenient decode turns each bad byte into U+FFFD
     * and writes it back as real UTF-8, corrupting the file while reporting success.
     * @param bytes the file contents, of which only the leading sample is examined.
     * @return true when the file must not be treated as editable text.
     */
    private fun looksBinary(bytes: ByteArray): Boolean {
        val sampleSize = minOf(bytes.size, BINARY_SNIFF_BYTES)
        for (i in 0 until sampleSize) if (bytes[i] == 0.toByte()) return true
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            false
        } catch (e: CharacterCodingException) {
            true
        }
    }
}
