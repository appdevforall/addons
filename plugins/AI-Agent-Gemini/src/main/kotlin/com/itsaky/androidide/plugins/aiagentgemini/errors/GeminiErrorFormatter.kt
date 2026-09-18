package com.itsaky.androidide.plugins.aiagentgemini.errors

import androidx.annotation.StringRes
import com.itsaky.androidide.plugins.aiagentgemini.R
import org.json.JSONObject
import java.io.IOException

/**
 * What the Generative Language API said went wrong, as far as it could be determined.
 *
 * Every field is nullable because the failure may not be an API response at all — a DNS failure or
 * a proxy's HTML error page reaches the same code path.
 */
data class GeminiApiError(
    /** HTTP status lifted from the `… HTTP <code>: <body>` message, or null if there wasn't one. */
    val httpStatus: Int?,
    /** Google's machine-readable `error.status`, e.g. `NOT_FOUND`, or null. */
    val apiStatus: String?,
    /** Google's human-readable `error.message`, collapsed to one line, or null. */
    val apiMessage: String?
)

/**
 * A Gemini failure reduced to the thing the user needs to be told.
 *
 * Carries no text: the wording lives in `strings.xml`, which also lets every branch be unit-tested
 * without a Context. Any reason is already single-lined, length-capped, and never a JSON body.
 */
sealed interface GeminiFailure {

    /** The selected model is gone or was never available to this key (HTTP 404 / `NOT_FOUND`). */
    data class ModelUnavailable(val modelName: String) : GeminiFailure

    /** Rate limit or quota (HTTP 429 / `RESOURCE_EXHAUSTED`). The key itself is fine. */
    data object QuotaExceeded : GeminiFailure

    /** The credential was refused (HTTP 401/403, `UNAUTHENTICATED`, `PERMISSION_DENIED`). */
    data object KeyRefused : GeminiFailure

    /** The credential is malformed or wrong (HTTP 400 whose message names the API key). */
    data object KeyInvalid : GeminiFailure

    /** HTTP 400 about the request rather than the credential. */
    data class RequestRejected(val reason: String?) : GeminiFailure

    /** Google-side outage (HTTP 5xx). Says nothing about the key or the model. */
    data class ServiceUnavailable(val httpStatus: Int) : GeminiFailure

    /** An HTTP status with no specific handling. */
    data class Unexpected(val httpStatus: Int, val reason: String?) : GeminiFailure

    /** No response at all — no network, DNS failure, timeout. */
    data object Unreachable : GeminiFailure

    /**
     * Generation stopped at the output cap (`finishReason: MAX_TOKENS`) with nothing runnable.
     * Not an API error — the request succeeded — so it is never produced by [GeminiErrorFormatter].
     */
    data object ReplyTruncated : GeminiFailure

    /** Everything else, including failures that never reached the network. */
    data class Failed(val reason: String?) : GeminiFailure
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
    KeyRefused("key_refused", R.string.gemini_error_key_refused),
    KeyInvalid("key_invalid", R.string.gemini_error_key_invalid);

    companion object {
        /**
         * The credential failure [failure] is, or null when it is about something else.
         *
         * The settings pane reports only these: a 500 or an unreachable network says nothing about
         * the key, and reporting one as a credential problem would send the user off to re-enter a
         * key that works. Listed exhaustively so a failure added later has to be classified here.
         */
        fun of(failure: GeminiFailure): CredentialFailure? = when (failure) {
            GeminiFailure.KeyRefused -> KeyRefused
            GeminiFailure.KeyInvalid -> KeyInvalid
            is GeminiFailure.ModelUnavailable,
            GeminiFailure.QuotaExceeded,
            is GeminiFailure.RequestRejected,
            is GeminiFailure.ServiceUnavailable,
            is GeminiFailure.Unexpected,
            GeminiFailure.Unreachable,
            GeminiFailure.ReplyTruncated,
            is GeminiFailure.Failed -> null
        }

        /** The failure [tag] names, or null for a tag this release no longer knows. */
        fun ofTag(tag: String): CredentialFailure? = entries.firstOrNull { it.tag == tag }
    }
}

/** Whether this failure is about the credential rather than the request, the model or the network. */
internal val GeminiFailure.isCredentialProblem: Boolean
    get() = CredentialFailure.of(this) != null

/**
 * Classifies a Gemini failure so it can be reported as one translated sentence.
 *
 * Replaces `"Gemini API error: ${e.message}"`, which put the entire HTTP error body in the chat.
 * The log keeps the full body; **no [GeminiFailure] ever carries a JSON payload**.
 */
object GeminiErrorFormatter {

    /**
     * Matches the status in the `Gemini HTTP 404: {…}` and `ListModels HTTP 403: {…}` messages
     * built by [GeminiBackend]. Kept loose (no prefix) so both forms are covered.
     */
    private val HTTP_STATUS = Regex("""HTTP (\d{3})""")

    /** Longest slice of Google's own wording carried onward; keeps a stray body out of the UI. */
    private const val MAX_ECHOED_REASON = 160

    /**
     * Pull the status code and, when the message carries a JSON error body, Google's own
     * `status`/`message` out of it. A non-JSON, truncated or absent body yields nulls rather than
     * throwing, because this runs while already handling a failure.
     *
     * @param rawMessage the throwable message, typically `Gemini HTTP <code>: <body>`
     */
    fun parse(rawMessage: String?): GeminiApiError {
        val raw = rawMessage.orEmpty()
        val error = runCatching {
            val bodyStart = raw.indexOf('{')
            if (bodyStart < 0) null else JSONObject(raw.substring(bodyStart)).optJSONObject("error")
        }.getOrNull()

        return GeminiApiError(
            httpStatus = HTTP_STATUS.find(raw)?.groupValues?.get(1)?.toIntOrNull(),
            apiStatus = error?.optString("status")?.takeIf { it.isNotBlank() },
            apiMessage = error?.optString("message")?.takeIf { it.isNotBlank() }?.toSingleLine()
        )
    }

    /**
     * Decide what to tell the user about [error].
     *
     * @param error the failure as thrown; its message is parsed, and its type distinguishes a
     *   transport problem from an API refusal when there is no status to read
     * @param modelName the model the request was for, so a retired-model failure can name it
     */
    fun classify(error: Throwable, modelName: String): GeminiFailure {
        val parsed = parse(error.message)
        val status = parsed.httpStatus

        return when {
            // ListModels still advertises the model, but generateContent refuses it on new keys.
            status == 404 || parsed.apiStatus == "NOT_FOUND" ->
                GeminiFailure.ModelUnavailable(modelName)

            status == 429 || parsed.apiStatus == "RESOURCE_EXHAUSTED" ->
                GeminiFailure.QuotaExceeded

            status == 401 || status == 403 ||
                parsed.apiStatus == "UNAUTHENTICATED" || parsed.apiStatus == "PERMISSION_DENIED" ->
                GeminiFailure.KeyRefused

            status == 400 && parsed.mentionsApiKey() -> GeminiFailure.KeyInvalid

            status == 400 -> GeminiFailure.RequestRejected(safeReason(parsed, error))

            status != null && status in 500..599 -> GeminiFailure.ServiceUnavailable(status)

            status != null -> GeminiFailure.Unexpected(status, safeReason(parsed, error))

            // No status at all: the request never got an answer.
            error is IOException -> GeminiFailure.Unreachable

            else -> GeminiFailure.Failed(safeReason(parsed, error))
        }
    }

    /** True when Google's wording points at the credential rather than the request shape. */
    private fun GeminiApiError.mentionsApiKey(): Boolean =
        apiMessage?.contains("api key", ignoreCase = true) == true

    /**
     * Google's own explanation, but only when it is short and safe to show.
     *
     * Falls back to the throwable's message when there was no JSON body, and never when that
     * message contains one — carrying a `{` onward is the bug this class exists to prevent.
     *
     * @return the reason, or null when there is nothing showable
     */
    private fun safeReason(parsed: GeminiApiError, error: Throwable): String? {
        val reason = parsed.apiMessage
            ?: error.message?.takeIf { !it.contains('{') }?.toSingleLine()
            ?: return null
        if (reason.isBlank() || reason.length > MAX_ECHOED_REASON) return null
        return reason
    }

    /** Collapse whitespace runs so a pretty-printed JSON string can't span lines in the UI. */
    private fun String.toSingleLine(): String = trim().replace(Regex("""\s+"""), " ")
}
