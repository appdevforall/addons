package com.itsaky.androidide.plugins.aiagentopenai.errors

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentopenai.R

/**
 * The wording for an [OpenAiFailure].
 *
 * `context.androidContext` is plugin-scoped, so this plugin's string ids resolve here. Separate
 * from the backend, which decides *what* failed and has no business also owning how it is phrased.
 *
 * @param context this plugin's context, whose resources carry the strings
 * @param baseUrl the configured server, named in the "server not running" advice
 */
internal class OpenAiFailureMessages(
    private val context: PluginContext,
    private val baseUrl: () -> String,
) {

    /**
     * One user-facing sentence for [failure]. A failed lookup degrades to the generic message
     * rather than throwing out of an error handler.
     */
    fun of(failure: OpenAiFailure): String = try {
        val resources = context.androidContext
        when (failure) {
            is OpenAiFailure.ModelUnavailable ->
                resources.getString(R.string.openai_error_model_unavailable, failure.modelName)

            OpenAiFailure.QuotaExceeded ->
                resources.getString(R.string.openai_error_quota)

            OpenAiFailure.BillingRequired ->
                resources.getString(R.string.openai_error_billing)

            // Through CredentialFailure, which is also what the settings pane resolves, so the
            // transcript and the pane cannot describe the same refusal differently.
            OpenAiFailure.KeyRefused ->
                resources.getString(CredentialFailure.KeyRefused.messageRes)

            OpenAiFailure.KeyMissing ->
                resources.getString(CredentialFailure.KeyMissing.messageRes)

            OpenAiFailure.KeyForbidden ->
                resources.getString(CredentialFailure.KeyForbidden.messageRes)

            is OpenAiFailure.RequestRejected -> failure.reason?.let {
                resources.getString(R.string.openai_error_request_rejected_reason, it)
            } ?: resources.getString(R.string.openai_error_request_rejected)

            is OpenAiFailure.ServiceUnavailable ->
                resources.getString(R.string.openai_error_service_unavailable, failure.httpStatus)

            is OpenAiFailure.Unexpected -> failure.reason?.let {
                resources.getString(R.string.openai_error_unexpected_reason, failure.httpStatus, it)
            } ?: resources.getString(R.string.openai_error_unexpected, failure.httpStatus)

            is OpenAiFailure.EmptyReply ->
                resources.getString(R.string.openai_error_empty_reply)

            OpenAiFailure.ReasoningOnly ->
                resources.getString(R.string.openai_error_reasoning_only)

            OpenAiFailure.TruncatedBeforeReply ->
                resources.getString(R.string.openai_error_truncated)

            OpenAiFailure.ServerNotRunning ->
                resources.getString(R.string.openai_error_server_not_running, baseUrl())

            OpenAiFailure.Unreachable ->
                resources.getString(R.string.openai_error_unreachable)

            is OpenAiFailure.Failed -> failure.reason?.let {
                resources.getString(R.string.openai_error_failed_reason, it)
            } ?: resources.getString(R.string.openai_error_failed)
        }
    } catch (e: Exception) {
        context.logger.error("OpenAiFailureMessages: could not resolve a string for $failure", e)
        "The request to the AI server failed."
    }
}
