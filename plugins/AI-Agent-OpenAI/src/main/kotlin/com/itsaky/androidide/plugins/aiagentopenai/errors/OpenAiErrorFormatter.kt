package com.itsaky.androidide.plugins.aiagentopenai.errors

import androidx.annotation.StringRes
import com.itsaky.androidide.plugins.aiagentopenai.R
import org.json.JSONObject
import java.io.IOException

/**
 * What an OpenAI-compatible server said went wrong, as far as it could be determined.
 *
 * Every field is nullable because the failure may not be an API response at all — a DNS failure, a
 * refused TCP connection to a LAN box, or a proxy's HTML error page reaches the same code path.
 */
data class OpenAiApiError(
    /** HTTP status lifted from the `… HTTP <code>: <body>` message, or null if there wasn't one. */
    val httpStatus: Int?,
    /** The server's machine-readable `error.code`, e.g. `invalid_api_key`, or null. */
    val apiCode: String?,
    /** The server's `error.type`, e.g. `invalid_request_error`, or null. */
    val apiType: String?,
    /** The server's human-readable `error.message`, collapsed to one line, or null. */
    val apiMessage: String?,
)

/**
 * An OpenAI-compatible failure reduced to the thing the user needs to be told.
 *
 * Carries no text: the wording lives in `strings.xml`, which also lets every branch be unit-tested
 * without a Context. Any reason is already single-lined, length-capped, and never a JSON body.
 */
sealed interface OpenAiFailure {

    /** The model is unknown to this server (HTTP 404, or `model_not_found`). */
    data class ModelUnavailable(val modelName: String) : OpenAiFailure

    /** Rate limit or spent quota (HTTP 429). The key itself is fine. */
    data object QuotaExceeded : OpenAiFailure

    /** The account has no credit left (HTTP 429 whose body names billing or credit). */
    data object BillingRequired : OpenAiFailure

    /** The credential was refused (HTTP 401, or `invalid_api_key`). */
    data object KeyRefused : OpenAiFailure

    /** The server needs a key and none was configured (HTTP 401 with nothing sent). */
    data object KeyMissing : OpenAiFailure

    /** The key is valid but not allowed to use this model or endpoint (HTTP 403). */
    data object KeyForbidden : OpenAiFailure

    /** HTTP 400 about the request rather than the credential. */
    data class RequestRejected(val reason: String?) : OpenAiFailure

    /** Server-side outage (HTTP 5xx). Says nothing about the key or the model. */
    data class ServiceUnavailable(val httpStatus: Int) : OpenAiFailure

    /** An HTTP status with no specific handling. */
    data class Unexpected(val httpStatus: Int, val reason: String?) : OpenAiFailure

    /**
     * No response at all — no network, DNS failure, timeout, or nothing listening.
     *
     * Distinguished from [Unreachable] because a custom LAN server that is simply not running is
     * the single most likely failure for an ADFA-3452 user, and "check the server is running" is
     * better advice than "check your internet connection".
     */
    data object ServerNotRunning : OpenAiFailure

    /** No response and the server was OpenAI itself, i.e. the device has no route out. */
    data object Unreachable : OpenAiFailure

    /**
     * The server streamed successfully but produced no reply text.
     *
     * Its own state because the request did **not** fail: reporting a network-shaped error here
     * sends the user hunting for a connection problem that does not exist.
     *
     * @param skippedChunks payloads the parser could not use, which is the diagnostic
     */
    data class EmptyReply(val skippedChunks: Int) : OpenAiFailure

    /**
     * The model produced only thinking text and never got to an answer — almost always the token
     * cap being consumed by reasoning.
     */
    data object ReasoningOnly : OpenAiFailure

    /** The token cap cut the turn off before any reply text arrived (`finish_reason: length`). */
    data object TruncatedBeforeReply : OpenAiFailure

    /** Everything else, including failures that never reached the network. */
    data class Failed(val reason: String?) : OpenAiFailure
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
    KeyRefused("key_refused", R.string.openai_error_key_refused),
    KeyMissing("key_missing", R.string.openai_error_key_missing),
    KeyForbidden("key_forbidden", R.string.openai_error_key_forbidden);

    companion object {
        /**
         * The credential failure [failure] is, or null when it is about something else.
         *
         * The settings pane reports only these: a 500, a spent quota or an unreachable server says
         * nothing about the key, and reporting one as a credential problem would send the user off
         * to replace a key that works. [OpenAiFailure.QuotaExceeded] and
         * [OpenAiFailure.BillingRequired] are deliberately outside it — the key was accepted, the
         * account simply has nothing left to spend. Listed exhaustively so a failure added later
         * has to be classified here.
         */
        fun of(failure: OpenAiFailure): CredentialFailure? = when (failure) {
            OpenAiFailure.KeyRefused -> KeyRefused
            OpenAiFailure.KeyMissing -> KeyMissing
            OpenAiFailure.KeyForbidden -> KeyForbidden
            is OpenAiFailure.ModelUnavailable,
            OpenAiFailure.QuotaExceeded,
            OpenAiFailure.BillingRequired,
            is OpenAiFailure.RequestRejected,
            is OpenAiFailure.ServiceUnavailable,
            is OpenAiFailure.Unexpected,
            OpenAiFailure.ServerNotRunning,
            OpenAiFailure.Unreachable,
            is OpenAiFailure.EmptyReply,
            OpenAiFailure.ReasoningOnly,
            OpenAiFailure.TruncatedBeforeReply,
            is OpenAiFailure.Failed -> null
        }

        /** The failure [tag] names, or null for a tag this release no longer knows. */
        fun ofTag(tag: String): CredentialFailure? = entries.firstOrNull { it.tag == tag }
    }
}

/** Whether this failure is about the credential rather than the request, the model or the network. */
internal val OpenAiFailure.isCredentialProblem: Boolean
    get() = CredentialFailure.of(this) != null

/**
 * Classifies an OpenAI-compatible failure so it can be reported as one translated sentence.
 *
 * The log keeps the full body; **no [OpenAiFailure] ever carries a JSON payload** — putting the raw
 * error body in the chat transcript is the bug this class exists to prevent.
 */
object OpenAiErrorFormatter {

    /**
     * Matches the status in an `OpenAI HTTP 404: {…}` message. A fallback: a status that arrived as
     * an [OpenAiHttpException] field is read from the field, never from text.
     */
    private val HTTP_STATUS = Regex("""HTTP (\d{3})""")

    /** Longest slice of the server's own wording carried onward; keeps a stray body out of the UI. */
    private const val MAX_ECHOED_REASON = 160

    /**
     * Pull the status code and, when the message carries a JSON error body, the server's own
     * `code`/`type`/`message` out of it. A non-JSON, truncated or absent body yields nulls rather
     * than throwing, because this runs while already handling a failure.
     *
     * @param rawMessage the throwable message, typically `OpenAI HTTP <code>: <body>`
     */
    fun parse(rawMessage: String?): OpenAiApiError {
        val raw = rawMessage.orEmpty()
        val error = errorObjectIn(raw)

        return OpenAiApiError(
            httpStatus = HTTP_STATUS.find(raw)?.groupValues?.get(1)?.toIntOrNull(),
            apiCode = error?.optString("code")?.takeIf { it.isNotBlank() },
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
     * @param isOpenAiHost whether the target was OpenAI itself, which changes the no-answer advice
     */
    fun classify(
        error: Throwable,
        modelName: String,
        hasApiKey: Boolean,
        isOpenAiHost: Boolean,
    ): OpenAiFailure {
        val parsed = parse(error.message)
        // The transport reports its status as a field; the pattern below only has to cover a
        // failure that reached here some other way.
        val status = (error as? OpenAiHttpException)?.statusCode ?: parsed.httpStatus

        return when {
            status == 404 || parsed.apiCode == "model_not_found" ->
                OpenAiFailure.ModelUnavailable(modelName)

            status == 429 && parsed.mentionsBilling() -> OpenAiFailure.BillingRequired

            status == 429 || parsed.apiCode == "rate_limit_exceeded" ->
                OpenAiFailure.QuotaExceeded

            status == 401 && !hasApiKey -> OpenAiFailure.KeyMissing

            status == 401 || parsed.apiCode == "invalid_api_key" -> OpenAiFailure.KeyRefused

            status == 403 -> OpenAiFailure.KeyForbidden

            status == 400 -> OpenAiFailure.RequestRejected(safeReason(parsed, error))

            status != null && status in 500..599 -> OpenAiFailure.ServiceUnavailable(status)

            status != null -> OpenAiFailure.Unexpected(status, safeReason(parsed, error))

            // No status at all: the request never got an answer.
            error is IOException ->
                if (isOpenAiHost) OpenAiFailure.Unreachable else OpenAiFailure.ServerNotRunning

            else -> OpenAiFailure.Failed(safeReason(parsed, error))
        }
    }

    /** True when a 429 is about money rather than request rate. */
    private fun OpenAiApiError.mentionsBilling(): Boolean {
        val text = "${apiCode.orEmpty()} ${apiType.orEmpty()} ${apiMessage.orEmpty()}".lowercase()
        return listOf("billing", "credit", "quota", "insufficient_quota", "payment")
            .any { text.contains(it) }
    }

    /**
     * The server's own explanation, but only when it is short and safe to show.
     *
     * Falls back to the throwable's message when there was no JSON body, and never when that
     * message contains one — carrying a `{` onward is the bug this class exists to prevent.
     *
     * @return the reason, or null when there is nothing showable
     */
    private fun safeReason(parsed: OpenAiApiError, error: Throwable): String? {
        val reason = parsed.apiMessage
            ?: error.message?.takeIf { !it.contains('{') }?.toSingleLine()
            ?: return null
        if (reason.isBlank() || reason.length > MAX_ECHOED_REASON) return null
        return reason
    }

    /**
     * The `error` object of a server error body, wherever it starts inside [raw].
     *
     * Shared with the unsupported-parameter recovery, which reads a field of the same object out of
     * the same kind of body; two hand-rolled copies of "find the brace, hope it parses" is one too
     * many. Never throws: it runs while a failure is already being handled.
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
