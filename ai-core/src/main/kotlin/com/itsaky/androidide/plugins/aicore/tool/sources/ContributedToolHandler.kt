package com.itsaky.androidide.plugins.aicore.tool.sources

import android.util.Log
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.aicore.tool.Validation
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

private const val TAG = "$LOG_PREFIX.ContributedToolHandler"

/**
 * Adapts one [ContributedTool] onto the agent's own [ToolHandler].
 *
 * Nothing a provider does may escape this class: a source that throws, hangs its future or
 * completes it exceptionally costs the user that tool call, never the run — the same posture
 * `BackendRegistry.describe` takes towards a backend that cannot describe itself.
 *
 * @property toolName the namespaced name the model sees; the source is still called with its own.
 */
class ContributedToolHandler(
    private val source: ContributedToolSource,
    private val tool: ContributedTool,
    override val toolName: String,
) : ToolHandler {

    /** Flattened here, at the boundary, so the prompt and the approval dialog both get one line. */
    override val description: String =
        ContributedText.label(tool.description, ContributedText.MAX_DETAIL_CHARS)

    override val parametersSchema: Map<String, Any> = tool.parametersSchema

    /**
     * The tool's own name, never the qualified form a collision may have forced on it.
     *
     * Flattened and capped: this is a remote string on a consent dialog, where a newline plus a
     * copy of the dialog's own header is enough to forge structure the user then trusts.
     */
    override val displayName: String = ContributedText.label(tool.name)

    /**
     * The contributing plugin's own name, flattened and capped like the two strings above: it is
     * the third provider-supplied string the approval dialog renders, and it reaches the model too
     * through [providerFailure].
     */
    override val sourceLabel: String = ContributedText.label(source.displayName)

    /**
     * Always true, whatever the source declared.
     *
     * A source's own `requiresApproval = false` would return from
     * `ToolApprovalManager.ensureApproved` before [allowsSessionApproval] below is ever consulted,
     * so a provider could opt out of the only control there is by asking. The declaration is kept
     * on [ContributedTool] for a host-side allowlist to honour one day; nothing honours it now.
     */
    override val requiresApproval: Boolean = true

    /**
     * Never blanket-approved: a contributed tool runs outside the agent's path containment, so the
     * approval dialog is the only control there is.
     */
    override val allowsSessionApproval: Boolean = false

    /** The contributing plugin's id, for provenance in logs and the settings screen. */
    val providerId: String get() = tool.providerId

    /** Whether the provider declared the tool free of side effects. */
    val readOnly: Boolean get() = tool.readOnly

    /**
     * From the provider's own declaration, never [requiresApproval], which is forced true above and
     * would make every contributed tool, read-only ones included, look like a change to the run.
     */
    override val mutatesProject: Boolean get() = !readOnly

    /**
     * Checks the schema's required properties before an approval dialog is spent on a call that
     * cannot succeed.
     * @param args the normalized call arguments.
     * @return acceptance, or the failure to report to the model instead.
     */
    override suspend fun validate(args: Map<String, Any?>): Validation {
        val missing = requiredProperties().filter { key ->
            args[key]?.toString()?.trim().isNullOrEmpty()
        }
        return if (missing.isEmpty()) {
            Validation.Accepted(args)
        } else {
            Validation.Rejected(
                ToolResult.failure("Missing required argument(s): ${missing.joinToString(", ")}")
            )
        }
    }

    /**
     * Runs the tool through its provider and maps the outcome back.
     * @param args the call arguments.
     * @return the provider's outcome, or a failure describing why it could not be obtained.
     */
    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val callId = UUID.randomUUID().toString()

        val future = try {
            source.invoke(callId, tool.name, args)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            Log.e(TAG, "Source '${tool.providerId}' threw invoking ${tool.name}", e)
            return ToolResult.failure(providerFailure("could not start"), e.stackTraceToString())
        }

        return try {
            val outcome = await(callId, future)
            if (outcome.success) {
                ToolResult.success("$displayName completed", outcome.output)
            } else {
                ToolResult.failure(
                    outcome.errorMessage?.takeIf { it.isNotBlank() } ?: providerFailure("failed"),
                    outcome.output.takeIf { it.isNotBlank() },
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            Log.w(TAG, "Source '${tool.providerId}' failed running ${tool.name}", e)
            val cause = if (e is CompletionException) e.cause ?: e else e
            ToolResult.failure(
                "${providerFailure("failed")}: ${cause.message ?: cause.javaClass.simpleName}",
                cause.stackTraceToString(),
            )
        }
    }

    /**
     * Awaits [future] without blocking a thread, cancelling through to the provider when the run
     * is stopped.
     * @param callId this call's id, the key the provider cancels by.
     * @param future the provider's in-flight call.
     * @return the outcome it completes with.
     */
    private suspend fun await(
        callId: String,
        future: CompletableFuture<ContributedToolResult>,
    ): ContributedToolResult = suspendCancellableCoroutine { continuation ->
        future.whenComplete { outcome, error ->
            when {
                error != null -> continuation.resumeWithException(error)
                outcome == null ->
                    continuation.resumeWithException(
                        IllegalStateException("completed with no outcome")
                    )
                else -> continuation.resume(outcome)
            }
        }
        continuation.invokeOnCancellation {
            runCatching { source.cancel(callId) }
                .onFailure { Log.w(TAG, "Source '${tool.providerId}' threw cancelling ${tool.name}", it) }
            future.cancel(true)
        }
    }

    /**
     * The `required` property names declared by the tool's JSON Schema.
     * @return the required argument names; empty when the schema declares none or is malformed.
     */
    private fun requiredProperties(): List<String> =
        (tool.parametersSchema["required"] as? Collection<*>)
            ?.mapNotNull { it as? String }
            .orEmpty()

    /** One model-facing sentence naming the provider, so a failure is attributable. */
    private fun providerFailure(what: String): String =
        "Tool '$displayName' from $sourceLabel $what"
}
