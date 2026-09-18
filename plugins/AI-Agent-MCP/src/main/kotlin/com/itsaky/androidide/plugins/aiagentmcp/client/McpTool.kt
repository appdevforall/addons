package com.itsaky.androidide.plugins.aiagentmcp.client

/**
 * One tool advertised by an MCP server.
 *
 * @property name the tool's own name, unique within its server.
 * @property description what it does, as the server describes it — untrusted remote text.
 * @property inputSchema JSON Schema for the arguments, empty when the server sends none.
 */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any> = emptyMap(),
)

/**
 * The outcome of one `tools/call`.
 *
 * @property success whether the server reported the call as succeeding.
 * @property text the call's content, flattened to text for the model.
 * @property errorMessage one user-facing sentence when it failed, else null.
 */
data class McpCallResult(
    val success: Boolean,
    val text: String,
    val errorMessage: String? = null,
)
