package com.itsaky.androidide.plugins.aiagentclaude.backend

import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeFailure
import com.itsaky.androidide.plugins.aiagentclaude.errors.ClaudeHttpException
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolCallRequest
import org.json.JSONObject

/**
 * Folds one stream's events into a turn, and decides how the turn ends.
 *
 * Kept apart from the socket so every rule that decides what reaches the caller is unit-testable
 * from captured event lines: a refusal is reported before any call runs, a declined model's calls
 * are dropped at a fallback, a call cut off by `max_tokens` is not run, and an empty turn says why.
 *
 * Not thread-safe: it belongs to the one reader loop consuming a single response body. Use a new
 * one per attempt, so a retried turn cannot report a call twice.
 *
 * @param onText receives each piece of reply text as it arrives, for the live transcript
 */
internal class TurnAssembler(private val onText: (String) -> Unit) {

    /** What ended the turn, or why it produced nothing to show. */
    sealed interface Result {

        /**
         * The model declined. Any partial output, and any tool call inside it, is not an answer
         * the model stands behind.
         */
        data object Refused : Result

        /** A turn to report: [text], [calls], or both. */
        data class Reply(val text: String, val calls: List<ToolCallRequest>) : Result

        /** The stream ended cleanly with neither text nor a call; [failure] says which case. */
        data class Empty(val failure: ClaudeFailure) : Result
    }

    private val text = StringBuilder()
    private val calls = ClaudeToolProtocol.CallAccumulator()

    /** The content block opened last, which is the one a `max_tokens` stop cut off. */
    private var lastBlockIndex = -1

    /** Reply text delivered so far, in pieces; a retry is only safe while this is zero. */
    var chunks = 0
        private set

    /** Payloads the parser could not use. */
    var skippedLines = 0
        private set

    /** Thinking blocks seen, which are never part of the reply. */
    var thinkingBlocks = 0
        private set

    /** The `stop_reason` the API reported, if any. */
    var stopReason: String? = null
        private set

    /** The model `message_start` named, which a fallback can make another one. */
    var servedBy: String? = null
        private set

    /** Calls dropped because the model that began them was declined mid-turn. */
    var discardedAtFallback = 0
        private set

    /** Calls dropped in [finish] as incomplete or cut off; set once [finish] runs. */
    var droppedCalls = 0
        private set

    /**
     * Applies one event.
     *
     * @return false once the stream is over and the reader should stop
     * @throws ClaudeHttpException for an `error` event, as the HTTP failure it stands for, so it
     *   classifies and retries like the same failure on the status line
     */
    fun accept(event: ClaudeStreamEvent): Boolean {
        when (event) {
            is ClaudeStreamEvent.Text -> deliver(event.text)
            is ClaudeStreamEvent.ToolStart -> {
                lastBlockIndex = event.index
                calls.start(event.index, event.id, event.name)
            }
            is ClaudeStreamEvent.ToolInput -> calls.appendInput(event.index, event.partialJson)
            is ClaudeStreamEvent.BlockOpened -> {
                lastBlockIndex = event.index
                if (event.type == "thinking" || event.type == "redacted_thinking") thinkingBlocks++
                if (event.type == "text" && event.text.isNotEmpty()) deliver(event.text)
            }
            is ClaudeStreamEvent.FallbackSwitch -> {
                lastBlockIndex = event.index
                servedBy = event.toModel ?: servedBy
                discardedAtFallback += calls.discardAll()
            }
            is ClaudeStreamEvent.Started -> servedBy = event.model
            is ClaudeStreamEvent.Stop -> stopReason = event.reason
            is ClaudeStreamEvent.Failure -> throw ClaudeHttpException(
                ClaudeHttpException.statusForStreamError(event.errorType),
                JSONObject().put(
                    "error",
                    JSONObject().put("type", event.errorType).put("message", event.message)
                ).toString(),
            )
            ClaudeStreamEvent.Done -> return false
            ClaudeStreamEvent.Ignored -> Unit
            // One bad line must not abort a stream that is otherwise producing text.
            is ClaudeStreamEvent.Malformed -> skippedLines++
        }
        return true
    }

    private fun deliver(piece: String) {
        chunks++
        text.append(piece)
        onText(piece)
    }

    /** Ends the turn: what the caller should be told, once the stream is over. */
    fun finish(): Result {
        // Checked before the calls: a declined turn's partial output is not an answer, and a tool
        // call inside it is not one the model stands behind.
        if (stopReason == "refusal") return Result.Refused
        // The block open at a max_tokens stop was cut off; a tool call there may parse, as an
        // empty input does, yet is not the call the model meant.
        if (stopReason == "max_tokens") calls.dropAt(lastBlockIndex)
        val requests = calls.requests()
        droppedCalls = calls.droppedCalls
        val reply = text.toString()
        // A turn that called a tool and said nothing is the normal agent turn, so only a reply
        // with neither text nor a call is empty.
        if (requests.isEmpty() && reply.isBlank()) return Result.Empty(emptyReplyFailure())
        return Result.Reply(reply, requests)
    }

    /**
     * Which empty-reply case this turn is. Ordered by how actionable the advice is: a cap that
     * cut the turn off has a cause the user can see, while an unrecognised shape only has a log.
     */
    private fun emptyReplyFailure(): ClaudeFailure = when {
        stopReason == "max_tokens" -> ClaudeFailure.TruncatedBeforeReply
        // Input that stops mid-JSON is a cut-off reply, whatever the API said stopped it.
        droppedCalls > 0 -> ClaudeFailure.TruncatedBeforeReply
        thinkingBlocks > 0 -> ClaudeFailure.ReasoningOnly
        else -> ClaudeFailure.EmptyReply(skippedLines)
    }
}
