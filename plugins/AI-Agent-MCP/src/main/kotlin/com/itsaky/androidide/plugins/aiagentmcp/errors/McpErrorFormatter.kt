package com.itsaky.androidide.plugins.aiagentmcp.errors

import android.content.Context
import com.itsaky.androidide.plugins.aiagentmcp.R
import com.itsaky.androidide.plugins.aiagentmcp.client.McpProtocolException
import com.itsaky.androidide.plugins.aiagentmcp.security.UnavailableSecretException
import com.itsaky.androidide.plugins.aiagentmcp.security.UnreadableSecretException
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpException
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpRedirectException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * An MCP failure reduced to the thing the user needs to be told.
 *
 * Carries no text: the wording lives in `strings.xml`, which also lets every branch be unit-tested
 * without a Context.
 */
sealed interface McpFailure {

    /** The server answered a status with no more specific handling. */
    data class Http(val status: Int) : McpFailure

    /** The request was malformed as far as the server is concerned (HTTP 400). */
    data object BadRequest : McpFailure

    /** The token was refused or missing (HTTP 401). */
    data object TokenRefused : McpFailure

    /** The token is valid but not allowed here (HTTP 403). */
    data object Forbidden : McpFailure

    /** Nothing MCP answers at that URL (HTTP 404). */
    data object NoEndpoint : McpFailure

    /** The server does not accept this request shape (HTTP 405), i.e. not Streamable HTTP. */
    data object WrongTransport : McpFailure

    /** No response format both sides accept (HTTP 406). */
    data object NoCommonFormat : McpFailure

    /** Rate limited (HTTP 429). */
    data object RateLimited : McpFailure

    /** The server failed on its own side (HTTP 5xx). */
    data class ServerError(val status: Int) : McpFailure

    /** The server answered a JSON-RPC error, i.e. it understood and refused. */
    data class Rejected(val detail: String) : McpFailure

    /** The host does not resolve. */
    data object UnknownHost : McpFailure

    /** TLS failed — wrong scheme, or a certificate this device will not accept. */
    data object TlsFailed : McpFailure

    /** The server took longer than the read timeout. */
    data object TimedOut : McpFailure

    /** The connection was dropped deliberately, to stop an agent run. */
    data object Cancelled : McpFailure

    /** A stored credential cannot be decrypted on this device, so nothing was sent. */
    data object SecretUnreadable : McpFailure

    /** The keystore would not open a stored credential just now, so nothing was sent — retry. */
    data object SecretUnavailable : McpFailure

    /** The server redirected somewhere the request cannot be repeated with its credentials. */
    data object RedirectRefused : McpFailure

    /** Everything else, including failures that never reached the network. */
    data class Failed(val reason: String?) : McpFailure
}

/**
 * Classifies an MCP failure so it can be reported as one translated sentence.
 *
 * The server's raw body never reaches the transcript: it can be a stack trace, an HTML error page
 * or a token echoed back, and all three are worse than useless on a phone screen. The body stays in
 * logcat, where it belongs.
 */
object McpErrorFormatter {

    /**
     * Reduces [error] to what the user needs to know.
     * @param error the failure, from any layer.
     * @return the classification.
     */
    fun classify(error: Throwable): McpFailure = when (error) {
        // Before the IOException branches below, which it is one of.
        is UnreadableSecretException -> McpFailure.SecretUnreadable
        is UnavailableSecretException -> McpFailure.SecretUnavailable
        is McpRedirectException -> McpFailure.RedirectRefused
        is McpHttpException -> forStatus(error.statusCode)
        is McpProtocolException -> McpFailure.Rejected(error.message.orEmpty())
        is UnknownHostException -> McpFailure.UnknownHost
        is SSLException -> McpFailure.TlsFailed
        is SocketTimeoutException -> McpFailure.TimedOut
        // Thrown when the connection is dropped to cancel a run, which is not a fault to report.
        is InterruptedIOException -> McpFailure.Cancelled
        else -> McpFailure.Failed(error.message)
    }

    /**
     * The sentence to show for [error].
     * @param context this plugin's context, for its own `strings.xml`; null falls back to the
     *   untranslated shape, which beats showing the user nothing.
     * @param serverName the server's label, so a user with several knows which one failed.
     * @param error the failure.
     * @return one sentence.
     */
    fun format(context: Context?, serverName: String, error: Throwable): String {
        val failure = classify(error)
        if (context == null) return "$serverName: ${error.message ?: error.javaClass.simpleName}"

        return when (failure) {
            McpFailure.BadRequest -> context.getString(R.string.mcp_error_bad_request, serverName)
            McpFailure.TokenRefused -> context.getString(R.string.mcp_error_token_refused, serverName)
            McpFailure.Forbidden -> context.getString(R.string.mcp_error_forbidden, serverName)
            McpFailure.NoEndpoint -> context.getString(R.string.mcp_error_no_endpoint, serverName)
            McpFailure.WrongTransport -> context.getString(R.string.mcp_error_wrong_transport, serverName)
            McpFailure.NoCommonFormat -> context.getString(R.string.mcp_error_no_common_format, serverName)
            McpFailure.RateLimited -> context.getString(R.string.mcp_error_rate_limited, serverName)
            McpFailure.UnknownHost -> context.getString(R.string.mcp_error_unknown_host, serverName)
            McpFailure.TlsFailed -> context.getString(R.string.mcp_error_tls, serverName)
            McpFailure.TimedOut -> context.getString(R.string.mcp_error_timeout, serverName)
            McpFailure.Cancelled -> context.getString(R.string.mcp_error_cancelled, serverName)
            McpFailure.SecretUnreadable ->
                context.getString(R.string.mcp_error_secret_unreadable, serverName)
            McpFailure.SecretUnavailable ->
                context.getString(R.string.mcp_error_secret_unavailable, serverName)
            McpFailure.RedirectRefused ->
                context.getString(R.string.mcp_error_redirect_refused, serverName)
            is McpFailure.ServerError ->
                context.getString(R.string.mcp_error_server_error, serverName, failure.status)
            is McpFailure.Http -> context.getString(R.string.mcp_error_http, serverName, failure.status)
            is McpFailure.Rejected ->
                context.getString(R.string.mcp_error_rejected, serverName, failure.detail)
            is McpFailure.Failed -> failure.reason
                ?.let { context.getString(R.string.mcp_error_failed_reason, serverName, it) }
                ?: context.getString(R.string.mcp_error_failed, serverName)
        }
    }

    private fun forStatus(status: Int): McpFailure = when (status) {
        400 -> McpFailure.BadRequest
        401 -> McpFailure.TokenRefused
        403 -> McpFailure.Forbidden
        404 -> McpFailure.NoEndpoint
        405 -> McpFailure.WrongTransport
        406 -> McpFailure.NoCommonFormat
        408, 504 -> McpFailure.TimedOut
        429 -> McpFailure.RateLimited
        in 500..599 -> McpFailure.ServerError(status)
        else -> McpFailure.Http(status)
    }
}
