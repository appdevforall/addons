package com.itsaky.androidide.plugins.aicore.tool.sources

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A tool source under test control: it can list tools, throw while listing, and hand back a future
 * the test completes when it chooses.
 */
class FakeToolSource(
    override val providerId: String,
    override val displayName: String = providerId,
    private val tools: List<ContributedTool> = emptyList(),
    private val listThrows: Throwable? = null,
) : ContributedToolSource {

    /** Futures handed out by [invoke], keyed by call id, so a test can complete or inspect them. */
    val calls = ConcurrentHashMap<String, CompletableFuture<ContributedToolResult>>()

    /** Call ids passed to [cancel]; written from the agent's thread, read from the test's. */
    val cancelled = CopyOnWriteArrayList<String>()

    /** Thrown by [invoke] instead of returning a future, when set. */
    var invokeThrows: Throwable? = null

    /** Completes every future immediately with this outcome, when set. */
    var immediateOutcome: ContributedToolResult? = null

    override fun listTools(): List<ContributedTool> {
        listThrows?.let { throw it }
        return tools
    }

    override fun invoke(
        callId: String,
        toolName: String,
        args: Map<String, Any?>,
    ): CompletableFuture<ContributedToolResult> {
        invokeThrows?.let { throw it }
        val future = CompletableFuture<ContributedToolResult>()
        calls[callId] = future
        immediateOutcome?.let { future.complete(it) }
        return future
    }

    override fun cancel(callId: String) {
        cancelled += callId
    }
}

/**
 * Builds a contributed tool for [providerId].
 * @param name the tool's source-local name.
 * @param description the description that would reach the prompt.
 * @param required the schema's required property names.
 * @return the tool.
 */
fun contributedTool(
    providerId: String,
    name: String,
    description: String = "does something",
    required: List<String> = emptyList(),
    requiresApproval: Boolean = true,
    readOnly: Boolean = false,
): ContributedTool = ContributedTool(
    providerId = providerId,
    name = name,
    description = description,
    parametersSchema = if (required.isEmpty()) emptyMap() else mapOf("required" to required),
    requiresApproval = requiresApproval,
    readOnly = readOnly,
)
