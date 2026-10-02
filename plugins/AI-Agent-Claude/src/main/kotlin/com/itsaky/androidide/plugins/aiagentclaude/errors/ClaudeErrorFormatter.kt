package com.itsaky.androidide.plugins.aiagentclaude.errors

import androidx.annotation.StringRes
import com.itsaky.androidide.plugins.aiagentclaude.R
import org.json.JSONObject
import java.io.IOException

/**
 * What the Claude API said went wrong, as far as it could be determined.
 *
 * Every field is nullable because the failure may not be an API response at all — a DNS failure,
 * a dropped connection, or a captive portal's HTML page reaches the same code path.
 */
data class ClaudeApiError(
    /** HTTP status lifted from the `... HTTP <code>: <body>` message, or null if there wasn't one. */
    val httpStatus: Int?,
    /** The API's `error.type`, e.g. `authentication_error`, or null. */
    val apiType: String?,
    /** The API's human-readable `error.message`, collapsed to one line, or null. */
    val apiMessage: String?,
)

/**
 * A Claude failure reduced to the thing the user needs to be told.
 *
 * Carries no text: the wording lives in `strings.xml`, which also lets every branch be unit-tested
 * without a Context. Any reason is already single-lined, length-capped, and never a JSON body.
 */
sealed interface ClaudeFailure {

    /** The model is unknown, or not available to this account (HTTP 404). */
    data class ModelUnavailable(val modelName: String) : ClaudeFailure

    /** Rate limit (HTTP 429). The key itself is fine. */
    data object QuotaExceeded : ClaudeFailure

    /** The account has no credit left (HTTP 402, or a 400 that says the balance is too low). */
    data object BillingRequired : ClaudeFailure

    /** The credential was refused (HTTP 401). */
    data object KeyRefused : ClaudeFailure

    /** No key was configured, so none was sent (HTTP 401 with nothing sent). */
    data object KeyMissing : ClaudeFailure

    /** The key is valid but not allowed to do this (HTTP 403). */
    data object KeyForbidden : ClaudeFailure

    /** The conversation is larger than the API accepts (HTTP 413). */
    data object RequestTooLarge : ClaudeFailure

    /** HTTP 400 about the request rather than the credential. */
    data class RequestRejected(val reason: String?) : ClaudeFailure

    /** API overloaded (529) or failing (5xx). Says nothing about the key or the model. */
    data class ServiceUnavailable(val httpStatus: Int) : ClaudeFailure

    /** An HTTP status with no specific handling. */
    data class Unexpected(val httpStatus: Int, val reason: String?) : ClaudeFailure

    /** No response at all — no network, DNS failure, or timeout. */
    data object Unreachable : ClaudeFailure

    /**
     * The model declined the request (`stop_reason: "refusal"`), and any fallback the API tried
     * declined it too. The request did not fail, so this is not reported as an error with it.
     */
    data object Refused : ClaudeFailure

    /**
     * The stream ended successfully but carried no reply text.
     *
     * Its own state because the request did **not** fail: reporting a network-shaped error here
     * sends the user hunting for a connection problem that does not exist.
     *
     * @param skippedChunks payloads the parser could not use, which is the diagnostic
     */
    data class EmptyReply(val skippedChunks: Int) : ClaudeFailure

    /** The model thought and never got to an answer before the turn ended. */
    data object ReasoningOnly : ClaudeFailure

    /** The token cap cut the turn off before any reply text arrived (`stop_reason: max_tokens`). */
    data object TruncatedBeforeReply : ClaudeFailure

    /** Everything else, including failures that never reached the network. */
    data class Failed(val reason: String?) : ClaudeFailure
}

/**
 * A credential failure in the form the settings pane can be handed: a tag stable enough to persist,
 * and the wording resolved wherever it is shown.
 *
 * The pane is handed one of these rather than a rendered sentence. A sentence recorded at the
 * moment of failure is frozen in the locale it was produced in — change the device language and
 * the banner reads half in each — and it outlives a later correction to the wording.
 */
internal enum class CredentialFailure(val tag: String, @get:StringRes val messageRes: Int) {
    KeyRefused("key_refused", R.string.claude_error_key_refused),
    KeyMissing("key_missing", R.string.claude_error_key_missing),
    KeyForbidden("key_forbidden", R.string.claude_error_key_forbidden);

    companion object {
        /**
         * The credential failure [failure] is, or null when it is about something else.
         *
         * The settings pane reports only these: a 500, a rate limit or an unreachable API says
         * nothing about the key, and reporting one as a credential problem would send the user off
         * to replace a key that works. [ClaudeFailure.QuotaExceeded] and
         * [ClaudeFailure.BillingRequired] are deliberately outside it — the key was accepted, the
         * account simply has nothing left to spend. Listed exhaustively so a failure added later
         * has to be classified here.
         */
        fun of(failure: ClaudeFailure): CredentialFailure? = when (failure) {
            ClaudeFailure.KeyRefused -> KeyRefused
            ClaudeFailure.KeyMissing -> KeyMissing
            ClaudeFailure.KeyForbidden -> KeyForbidden
            is ClaudeFailure.ModelUnavailable,
            ClaudeFailure.QuotaExceeded,
            ClaudeFailure.BillingRequired,
            ClaudeFailure.RequestTooLarge,
            is ClaudeFailure.RequestRejected,
            is ClaudeFailure.ServiceUnavailable,
            is ClaudeFailure.Unexpected,
            ClaudeFailure.Unreachable,
            ClaudeFailure.Refused,
            is ClaudeFailure.EmptyReply,
            ClaudeFailure.ReasoningOnly,
            ClaudeFailure.TruncatedBeforeReply,
            is ClaudeFailure.Failed -> null
        }

        /** The failure [tag] names, or null for a tag this release no longer knows. */
        fun ofTag(tag: String): CredentialFailure? = entries.firstOrNull { it.tag == tag }
    }
}

/** Whether this failure is about the credential rather than the request, the model or the network. */
internal val ClaudeFailure.isCredentialProblem: Boolean
    get() = CredentialFailure.of(this) != null

/**
 * Classifies a Claude failure so it can be reported as one translated sentence.
 *
 * The log keeps the full body; **no [ClaudeFailure] ever carries a JSON payload** — putting the raw
 * error body in the chat transcript is the bug this class exists to prevent.
 */
object ClaudeErrorFormatter {

    /**
     * Matches the status in a `Claude HTTP 404: {...}` message. A fallback: a status that arrived
     * as a [ClaudeHttpException] field is read from the field, never from text.
     */
    private val HTTP_STATUS = Regex("""HTTP (\d{3})""")

    /** Longest slice of the API's own wording carried onward; keeps a stray body out of the UI. */
    private const val MAX_ECHOED_REASON = 160

    /**
     * Pull the status code and, when the message carries a JSON error body, the API's own
     * `type`/`message` out of it. A non-JSON, truncated or absent body yields nulls rather than
     * throwing, because this runs while already handling a failure.
     *
     * @param rawMessage the throwable message, typically `Claude HTTP <code>: <body>`
     */
    fun parse(rawMessage: String?): ClaudeApiError {
        val raw = rawMessage.orEmpty()
        val error = errorObjectIn(raw)

        return ClaudeApiError(
            httpStatus = HTTP_STATUS.find(raw)?.groupValues?.get(1)?.toIntOrNull(),
            apiType = error?.optString("type")?.takeIf { it.isNotBlank() },
            apiMessage = error?.optString("message")?.takeIf { it.isNotBlank() }?.toSingleLine(),
        )
    }

    /**
     * Decide what to tell the user about [error].
     *
     * @param error the failure as thrown; its message is parsed, and its type distinguishes a
     *   transport problem from an API refusal when there is no status to read
     * @param modelName the model the request was for, so an unknown-model failure can name it
     * @param hasApiKey whether a key was actually sent, to tell "wrong key" from "no key"
     */
    fun classify(
        error: Throwable,
        modelName: String,
        hasApiKey: Boolean,
    ): ClaudeFailure {
        val parsed = parse(error.message)
        // The transport reports its status as a field; the pattern below only has to cover a
        // failure that reached here some other way.
        val status = (error as? ClaudeHttpException)?.statusCode ?: parsed.httpStatus

        return when {
            status == 404 || parsed.apiType == "not_found_error" ->
                ClaudeFailure.ModelUnavailable(modelName)

            status == 402 || parsed.apiType == "billing_error" || parsed.mentionsCredit() ->
                ClaudeFailure.BillingRequired

            status == 429 || parsed.apiType == "rate_limit_error" -> ClaudeFailure.QuotaExceeded

            status == 401 && !hasApiKey -> ClaudeFailure.KeyMissing

            status == 401 || parsed.apiType == "authentication_error" -> ClaudeFailure.KeyRefused

            status == 403 || parsed.apiType == "permission_error" -> ClaudeFailure.KeyForbidden

            status == 413 || parsed.apiType == "request_too_large" -> ClaudeFailure.RequestTooLarge

            status == 400 -> ClaudeFailure.RequestRejected(safeReason(parsed, error))

            // 529 is the API's "overloaded", which is the common one.
            status != null && status in 500..599 -> ClaudeFailure.ServiceUnavailable(status)

            status != null -> ClaudeFailure.Unexpected(status, safeReason(parsed, error))

            // No status at all: the request never got an answer.
            error is IOException -> ClaudeFailure.Unreachable

            else -> ClaudeFailure.Failed(safeReason(parsed, error))
        }
    }

    /**
     * True when the API is saying the account is out of money. It reports that as a 400
     * `invalid_request_error` whose message names the credit balance, not as a status of its own.
     */
    private fun ClaudeApiError.mentionsCredit(): Boolean =
        apiMessage?.lowercase()?.contains("credit balance") == true

    /**
     * The API's own explanation, but only when it is short and safe to show.
     *
     * Falls back to the throwable's message when there was no JSON body, and never when that
     * message contains one — carrying a `{` onward is the bug this class exists to prevent.
     *
     * @return the reason, or null when there is nothing showable
     */
    private fun safeReason(parsed: ClaudeApiError, error: Throwable): String? {
        val reason = parsed.apiMessage
            ?: error.message?.takeIf { !it.contains('{') }?.toSingleLine()
            ?: return null
        if (reason.isBlank() || reason.length > MAX_ECHOED_REASON) return null
        return reason
    }

    /**
     * The `error` object of an API error body, wherever it starts inside [raw]. Never throws: it
     * runs while a failure is already being handled.
     *
     * @param raw a throwable message or a raw response body
     */
    internal fun errorObjectIn(raw: String?): JSONObject? {
        val start = raw?.indexOf('{') ?: return null
        if (start < 0) return null
        return runCatching { JSONObject(raw.substring(start)).optJSONObject("error") }.getOrNull()
    }

    /** Collapse whitespace runs so a pretty-printed JSON string can't span lines in the UI. */
    private fun String.toSingleLine(): String = trim().replace(Regex("""\s+"""), " ")
}
