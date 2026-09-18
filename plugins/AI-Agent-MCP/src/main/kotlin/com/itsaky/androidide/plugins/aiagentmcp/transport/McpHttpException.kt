package com.itsaky.androidide.plugins.aiagentmcp.transport

import java.io.IOException

/**
 * A non-2xx answer from an MCP server.
 *
 * The status and body are fields rather than something a reader digs back out of the message: the
 * settings pane's verdict and the session's re-initialize-on-404 recovery both need the status, and
 * parsing it out of formatted text would make the wording of a log line a cross-module contract.
 *
 * @param statusCode the HTTP status the server answered with.
 * @param body the server's error body; never shown to the user unfiltered.
 */
class McpHttpException(
    val statusCode: Int,
    val body: String,
) : IOException("MCP HTTP $statusCode: $body")
