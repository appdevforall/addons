package com.itsaky.androidide.plugins.aicore.tool.sources

import java.util.concurrent.CompletableFuture

/**
 * A registered contributor of agent tools, as this plugin's own type.
 *
 * Mirrors the host's `ToolSourceRegistry.ToolSource`; the bridge adapts one onto the other so this
 * package compiles and its tests run without the host contract.
 */
interface ContributedToolSource {

    /** Stable identity, conventionally the contributing plugin's `plugin.id`. */
    val providerId: String

    /** Human-readable source name, shown wherever tool provenance is surfaced. */
    val displayName: String

    /**
     * The tools currently offered. Read on registration and after a change notification; the store
     * treats a throwing source as absent, so it must be cheap and must not block on the network.
     * @return this source's tools.
     */
    fun listTools(): List<ContributedTool>

    /**
     * Runs one tool, off the caller's thread.
     * @param callId identifies this call for the lifetime of the run; the key for [cancel].
     * @param toolName the tool's own name, without the agent's namespace prefix.
     * @param args arguments keyed by schema property name.
     * @return a future completing with the outcome.
     */
    fun invoke(
        callId: String,
        toolName: String,
        args: Map<String, Any?>,
    ): CompletableFuture<ContributedToolResult>

    /** Best-effort cancellation of an in-flight [invoke], matched by `callId`. */
    fun cancel(callId: String) {}
}
