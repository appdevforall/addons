package com.itsaky.androidide.plugins.aicore.tool.sources

/**
 * One tool a plugin contributes to the agent, as this plugin's own type.
 *
 * Mirrors the host's `ToolSourceRegistry.ToolSpec` field for field so the bridge in
 * `services/ToolSourceRegistryImpl` is a copy and nothing else here names the host type.
 *
 * @property providerId the contributing plugin's id, carried so provenance survives flattening.
 * @property name the tool's own name, unique only within its source.
 * @property description what the tool does; this text reaches the model's prompt.
 * @property parametersSchema JSON Schema for the arguments; empty means untyped string arguments.
 * @property requiresApproval what the source declared; carried for a host-side allowlist to honour
 *   one day, but not honoured today — see [ContributedToolHandler.requiresApproval].
 * @property readOnly whether the tool is free of side effects.
 */
data class ContributedTool(
    val providerId: String,
    val name: String,
    val description: String,
    val parametersSchema: Map<String, Any> = emptyMap(),
    val requiresApproval: Boolean = true,
    val readOnly: Boolean = false,
)

/**
 * The result of one contributed-tool call.
 *
 * @property success whether the tool did what was asked.
 * @property output the result as text for the model.
 * @property errorMessage one user-facing sentence explaining a failure; null on success.
 */
data class ContributedToolResult(
    val success: Boolean,
    val output: String,
    val errorMessage: String? = null,
)
