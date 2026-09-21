package com.itsaky.androidide.plugins.aiagentmcp.client

/**
 * What one request to an MCP server authenticates with.
 *
 * Supplied per request rather than held in a field, because a session outlives the call that made
 * it: [McpConnections] keeps one per configured server for the life of the process, so a token
 * stored on the session is a plaintext credential readable in a heap dump until the IDE exits.
 * Nothing about a session needs it between calls, so nothing keeps it between calls.
 *
 * The last hop is still a `String` — [java.net.HttpURLConnection.setRequestProperty] takes one and
 * retains it for the connection — so this bounds how long the plaintext lives rather than
 * eliminating it. That is the part that was actually costing something.
 *
 * @property token bearer token, or blank for a server that needs none.
 * @property headers the user's own headers, sent on every request to this server.
 */
class McpCredentials(val token: String, val headers: Map<String, String>)
