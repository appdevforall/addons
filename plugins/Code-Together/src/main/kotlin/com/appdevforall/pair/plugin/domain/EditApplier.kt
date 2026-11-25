package com.appdevforall.pair.plugin.domain

import com.appdevforall.pair.plugin.data.EditOp
import com.appdevforall.pair.plugin.data.ProtocolMessage
import com.appdevforall.pair.plugin.util.PairLog
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.SelectionRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class EditApplier(
    private val editorService: IdeEditorService,
    private val scope: CoroutineScope,
    private val suppression: LoopbackSuppression,
    private val pathMapper: PathMapper,
    private val logger: PluginLogger,
    private val onOutOfSync: (wireFile: String?) -> Unit,
) {

    private val seqByFile: ConcurrentHashMap<String, AtomicLong> = ConcurrentHashMap()

    fun currentSeq(file: String): Long = seqByFile.getOrPut(file) { AtomicLong(0L) }.get()

    private fun flagOutOfSync(wireFile: String?) = onOutOfSync(wireFile)

    // After applying a remote change the editor moves the local caret to the edit site. Record it
    // so the cursor poll doesn't mistake that drag for a local cursor move and echo it back.
    private fun recordAutoCaret(file: File, wireFile: String) {
        if (editorService.getCurrentFile() != file) return
        val pos = editorService.getCurrentCursorPosition() ?: return
        suppression.recordAutoCaret(wireFile, pos.line, pos.column)
    }

    // replaceRange enqueues a DocumentChangeEvent (EventBus MAIN_ORDERED) that is delivered on a
    // LATER main-loop tick. Exiting suppression synchronously leaves it off when that echo arrives,
    // so the applied edit is re-observed and re-broadcast — an unbounded duplication loop. Posting
    // the exit makes it run after the echo (enqueued earlier), so the echo stays suppressed.
    private fun releaseSuppressionAfterEcho() {
        scope.launch(Dispatchers.Main) { suppression.exit() }
    }

    private fun resolveLocal(wirePath: String): File? {
        val file = File(pathMapper.toLocal(wirePath))
        if (file.exists()) return file
        logger.warn("PairPlugin: remote edit for unavailable file '$wirePath' — flagging out of sync")
        flagOutOfSync(null)
        return null
    }

    fun applyEdit(message: ProtocolMessage.Edit) {
        scope.launch {
            withContext(Dispatchers.Main) {
                val file = resolveLocal(message.file) ?: return@withContext
                PairLog.d("[APPLY] edit ${message.op} ${message.file} @${message.startLine}:${message.startColumn} seq=${message.seq} textLen=${message.text.length}")
                // INSERT's wire range spans the newly-inserted text on the sender; the receiver
                // doesn't have that text yet, so it must insert at the collapsed start point —
                // replacing [start..end) would clobber the receiver's own content there.
                val range = when (message.op) {
                    EditOp.INSERT -> SelectionRange(
                        message.startLine, message.startColumn, message.startLine, message.startColumn,
                    )
                    else -> SelectionRange(
                        message.startLine, message.startColumn, message.endLine, message.endColumn,
                    )
                }
                val text = when (message.op) {
                    EditOp.INSERT -> message.text
                    EditOp.DELETE -> ""
                    EditOp.REPLACE -> message.text
                }
                val inBounds = fits(file, message.startLine, message.startColumn) &&
                    (message.op == EditOp.INSERT || fits(file, message.endLine, message.endColumn))
                if (!inBounds) {
                    PairLog.w(
                        "[APPLY] out-of-bounds edit dropped for ${message.file} " +
                            "(${message.startLine}:${message.startColumn}..${message.endLine}:${message.endColumn}) " +
                            "— local content diverged; flagging out of sync",
                    )
                    logger.warn("PairPlugin: remote edit out of bounds for '${message.file}' — flagging out of sync")
                    flagOutOfSync(message.file)
                    return@withContext
                }
                suppression.enter()
                runCatching { editorService.replaceRange(file, range, text) }
                    .onSuccess {
                        seqByFile.getOrPut(message.file) { AtomicLong(0L) }.set(message.seq)
                        suppression.recordApplied(message.file, editorService.getFileContent(file).orEmpty())
                        recordAutoCaret(file, message.file)
                    }
                    .onFailure {
                        PairLog.e("[APPLY] replaceRange threw for ${message.file}: ${it.message}", it)
                        logger.warn("PairPlugin: replaceRange failed for '${message.file}' — flagging out of sync")
                        flagOutOfSync(message.file)
                    }
                releaseSuppressionAfterEcho()
            }
        }
    }

    private fun fits(file: File, line: Int, column: Int): Boolean {
        if (line < 0 || column < 0) return false
        if (line >= editorService.getLineCount(file)) return false
        val length = editorService.getLineText(file, line)?.length ?: return false
        return column <= length
    }

    fun applySnapshot(message: ProtocolMessage.SyncSnapshot) {
        scope.launch {
            withContext(Dispatchers.Main) {
                val file = resolveLocal(message.file) ?: return@withContext
                // Compute the replace range from the ACTUAL current content, not from
                // getLineCount/getLineText: those are unreliable for a file that isn't the active
                // editor buffer and collapse the range toward (0,0), which makes replaceRange INSERT
                // the snapshot without clearing the file — duplicating the whole document.
                val current = editorService.getFileContent(file)
                if (current == null) {
                    PairLog.w("[APPLY] snapshot skipped — ${message.file} not open/available in editor")
                    return@withContext
                }
                if (current == message.content) {
                    PairLog.d("[APPLY] snapshot no-op (already in sync) ${message.file} len=${current.length}")
                    seqByFile.getOrPut(message.file) { AtomicLong(0L) }.set(message.seq)
                    return@withContext
                }
                val lines = current.split('\n')
                val endLine = (lines.size - 1).coerceAtLeast(0)
                val endColumn = lines.last().length
                val fullRange = SelectionRange(0, 0, endLine, endColumn)
                PairLog.d("[APPLY] snapshot ${message.file}: ${current.length}->${message.content.length} chars over 0:0..$endLine:$endColumn")
                suppression.recordApplied(message.file, message.content)
                suppression.enter()
                runCatching { editorService.replaceRange(file, fullRange, message.content) }
                    .onSuccess {
                        seqByFile.getOrPut(message.file) { AtomicLong(0L) }.set(message.seq)
                        recordAutoCaret(file, message.file)
                    }
                    .onFailure {
                        PairLog.e("[APPLY] snapshot replaceRange threw for ${message.file}: ${it.message}", it)
                        logger.warn("PairPlugin: snapshot apply failed for '${message.file}' — flagging out of sync")
                        flagOutOfSync(message.file)
                    }
                releaseSuppressionAfterEcho()
            }
        }
    }

    fun resetSequences() {
        seqByFile.clear()
        suppression.clearApplied()
    }
}
