package com.itsaky.androidide.plugins.aiagentmcp.errors

import android.content.Context
import com.itsaky.androidide.plugins.aiagentmcp.R
import com.itsaky.androidide.plugins.aiagentmcp.client.McpProtocolException
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticServers
import com.itsaky.androidide.plugins.aiagentmcp.security.UnavailableSecretException
import com.itsaky.androidide.plugins.aiagentmcp.security.UnreadableSecretException
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpCleartextException
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpCredential
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpException
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpRedirectException
import java.io.InterruptedIOException
import java.net.ConnectException
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

    /** The credential sent was refused or missing (HTTP 401). */
    data class TokenRefused(val credential: McpCredential) : McpFailure

    /** The credential sent is valid but not allowed here (HTTP 403). */
    data class Forbidden(val credential: McpCredential) : McpFailure

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

    /** An `http://` URL off this device, refused before anything was sent. */
    data object CleartextRefused : McpFailure

    /** The server redirected somewhere the request cannot be repeated with its credentials. */
    data object RedirectRefused : McpFailure

    /** Everything else, including failures that never reached the network. */
    data class Failed(val reason: String?) : McpFailure

    /**
     * The SQLite debugger of the app [applicationId] did not answer before a socket timeout. Android
     * may have paused the app in the background, and a paused app answers nothing.
     */
    data class AppNotAnswering(val applicationId: String) : McpFailure

    /**
     * Nothing listens on the port stored for the app [applicationId]: its process ended, or it was
     * rebuilt without the SQLite debugger.
     */
    data class AppNotListening(val applicationId: String) : McpFailure
}

/**
 * Classifies an MCP failure so it can be reported as one translated sentence.
 *
 * The server's raw body never reaches this sentence: it can be a stack trace or an HTML page. The
 * settings pane shows it on request instead, credentials redacted, through [McpTranscript].
 */
object McpErrorFormatter {

    /** How far down a cause chain [classify] looks, which also ends a chain that loops. */
    private const val MAX_CAUSES = 8

    /**
     * Reduces [error] to what the user needs to know.
     * @param error the failure, from any layer.
     * @return the classification.
     */
    fun classify(error: Throwable): McpFailure = when (error) {
        // Before the IOException branches below, which it is one of.
        is UnreadableSecretException -> McpFailure.SecretUnreadable
        is UnavailableSecretException -> McpFailure.SecretUnavailable
        is McpCleartextException -> McpFailure.CleartextRefused
        is McpRedirectException -> McpFailure.RedirectRefused
        is McpHttpException -> forStatus(error.statusCode, error.credential)
        is McpProtocolException -> McpFailure.Rejected(error.message.orEmpty())
        is UnknownHostException -> McpFailure.UnknownHost
        is SSLException -> McpFailure.TlsFailed
        is SocketTimeoutException -> McpFailure.TimedOut
        // Thrown when the connection is dropped to cancel a run, which is not a fault to report.
        is InterruptedIOException -> McpFailure.Cancelled
        else -> McpFailure.Failed(error.message)
    }

    /**
     * Reduces [error] on [server] to what the user needs to know. As [classify], except on an
     * entry built from an app's stored SQLite debugger endpoint, where a socket timeout or a
     * refused connection says what to do with that app.
     *
     * Told apart by exception type, the cause chain included, never by message: Android's
     * `HttpURLConnection` writes "failed to connect to ... after 15000ms" for both.
     *
     * @param error the failure, from any layer.
     * @param server the server it came from.
     * @return the classification.
     */
    fun classify(error: Throwable, server: McpServer): McpFailure {
        val failure = classify(error)
        if (!server.automatic) return failure
        // An HTTP status or a JSON-RPC error is an answer, so the app is running and listening.
        if (failure != McpFailure.TimedOut && failure !is McpFailure.Failed) return failure
        val applicationId = AutomaticServers.applicationIdOf(server.id)
        val cause = generateSequence(error) { it.cause }.take(MAX_CAUSES)
            .firstOrNull { it is SocketTimeoutException || it is ConnectException }
        return when (cause) {
            is SocketTimeoutException -> McpFailure.AppNotAnswering(applicationId)
            is ConnectException -> McpFailure.AppNotListening(applicationId)
            else -> failure
        }
    }

    /**
     * The sentence to show for [error].
     * @param context this plugin's context, for its own `strings.xml`; null falls back to the
     *   untranslated shape, which beats showing the user nothing.
     * @param server the server that failed. Its label names it, so a user with several knows
     *   which one; the two reasons that say what to do with an app name its application ID.
     * @param error the failure.
     * @return one sentence.
     */
    fun format(context: Context?, server: McpServer, error: Throwable): String {
        val serverName = server.name
        if (context == null) return "$serverName: ${error.message ?: error.javaClass.simpleName}"

        return when (val failure = classify(error, server)) {
            McpFailure.BadRequest -> context.getString(R.string.mcp_error_bad_request, serverName)
            is McpFailure.TokenRefused -> refused(context, serverName, failure.credential)
            is McpFailure.Forbidden -> forbidden(context, serverName, failure.credential)
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
            McpFailure.CleartextRefused -> context.getString(R.string.mcp_error_cleartext, serverName)
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
            is McpFailure.AppNotAnswering ->
                context.getString(R.string.mcp_error_app_not_answering, failure.applicationId)
            is McpFailure.AppNotListening ->
                context.getString(R.string.mcp_error_app_not_listening, failure.applicationId)
        }
    }

    /** A 401, naming the field that was sent: blaming the token misleads when a header replaced it. */
    private fun refused(context: Context, serverName: String, credential: McpCredential): String =
        when (credential) {
            McpCredential.Token -> context.getString(R.string.mcp_error_token_refused, serverName)
            is McpCredential.AuthorizationHeader ->
                context.getString(R.string.mcp_error_header_refused, serverName, credential.name)
            McpCredential.ExtraHeaders -> context.getString(R.string.mcp_error_headers_refused, serverName)
            McpCredential.None -> context.getString(R.string.mcp_error_no_credentials, serverName)
        }

    private fun forbidden(context: Context, serverName: String, credential: McpCredential): String =
        when (credential) {
            McpCredential.Token -> context.getString(R.string.mcp_error_forbidden, serverName)
            is McpCredential.AuthorizationHeader ->
                context.getString(R.string.mcp_error_forbidden_header, serverName, credential.name)
            McpCredential.ExtraHeaders -> context.getString(R.string.mcp_error_forbidden_headers, serverName)
            McpCredential.None -> context.getString(R.string.mcp_error_forbidden_anonymous, serverName)
        }

    private fun forStatus(status: Int, credential: McpCredential): McpFailure = when (status) {
        400 -> McpFailure.BadRequest
        401 -> McpFailure.TokenRefused(credential)
        403 -> McpFailure.Forbidden(credential)
        404 -> McpFailure.NoEndpoint
        405 -> McpFailure.WrongTransport
        406 -> McpFailure.NoCommonFormat
        408, 504 -> McpFailure.TimedOut
        429 -> McpFailure.RateLimited
        in 500..599 -> McpFailure.ServerError(status)
        else -> McpFailure.Http(status)
    }
}
