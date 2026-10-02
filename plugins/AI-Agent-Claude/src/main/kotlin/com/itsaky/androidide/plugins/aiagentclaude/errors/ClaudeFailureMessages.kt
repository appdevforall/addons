package com.itsaky.androidide.plugins.aiagentclaude.errors

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentclaude.R

/**
 * The wording for a [ClaudeFailure].
 *
 * `context.androidContext` is plugin-scoped, so this plugin's string ids resolve here. Separate
 * from the backend, which decides *what* failed and has no business also owning how it is phrased.
 *
 * @param context this plugin's context, whose resources carry the strings
 */
internal class ClaudeFailureMessages(private val context: PluginContext) {

    /**
     * One user-facing sentence for [failure]. A failed lookup degrades to the generic message
     * rather than throwing out of an error handler.
     */
    fun of(failure: ClaudeFailure): String = try {
        val resources = context.androidContext
        when (failure) {
            is ClaudeFailure.ModelUnavailable ->
                resources.getString(R.string.claude_error_model_unavailable, failure.modelName)

            ClaudeFailure.QuotaExceeded ->
                resources.getString(R.string.claude_error_quota)

            ClaudeFailure.BillingRequired ->
                resources.getString(R.string.claude_error_billing)

            // Through CredentialFailure, which is also what the settings pane resolves, so the
            // transcript and the pane cannot describe the same refusal differently.
            ClaudeFailure.KeyRefused ->
                resources.getString(CredentialFailure.KeyRefused.messageRes)

            ClaudeFailure.KeyMissing ->
                resources.getString(CredentialFailure.KeyMissing.messageRes)

            ClaudeFailure.KeyForbidden ->
                resources.getString(CredentialFailure.KeyForbidden.messageRes)

            ClaudeFailure.KeyNeedsWorkspace ->
                resources.getString(CredentialFailure.KeyNeedsWorkspace.messageRes)

            ClaudeFailure.RequestTooLarge ->
                resources.getString(R.string.claude_error_too_large)

            is ClaudeFailure.RequestRejected -> failure.reason?.let {
                resources.getString(R.string.claude_error_request_rejected_reason, it)
            } ?: resources.getString(R.string.claude_error_request_rejected)

            is ClaudeFailure.ServiceUnavailable ->
                resources.getString(R.string.claude_error_service_unavailable, failure.httpStatus)

            is ClaudeFailure.Unexpected -> failure.reason?.let {
                resources.getString(R.string.claude_error_unexpected_reason, failure.httpStatus, it)
            } ?: resources.getString(R.string.claude_error_unexpected, failure.httpStatus)

            ClaudeFailure.Refused ->
                resources.getString(R.string.claude_error_refused)

            is ClaudeFailure.EmptyReply ->
                resources.getString(R.string.claude_error_empty_reply)

            ClaudeFailure.ReasoningOnly ->
                resources.getString(R.string.claude_error_reasoning_only)

            ClaudeFailure.TruncatedBeforeReply ->
                resources.getString(R.string.claude_error_truncated)

            ClaudeFailure.Unreachable ->
                resources.getString(R.string.claude_error_unreachable)

            is ClaudeFailure.Failed -> failure.reason?.let {
                resources.getString(R.string.claude_error_failed_reason, it)
            } ?: resources.getString(R.string.claude_error_failed)
        }
    } catch (e: Exception) {
        context.logger.error("ClaudeFailureMessages: could not resolve a string for $failure", e)
        "The request to Claude failed."
    }
}
