package com.itsaky.androidide.plugins.aicore.services

import android.util.Log
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.tool.handlers.PathGuard
import com.itsaky.androidide.plugins.aicore.tool.sources.ContributedTool
import com.itsaky.androidide.plugins.aicore.tool.sources.ContributedToolResult
import com.itsaky.androidide.plugins.aicore.tool.sources.ContributedToolSource
import com.itsaky.androidide.plugins.aicore.tool.sources.ToolSourceStore
import com.itsaky.androidide.plugins.services.ToolSourceRegistry
import java.io.File
import java.util.concurrent.CompletableFuture

private const val TAG = "$LOG_PREFIX.ToolSourceRegistry"

/**
 * The agent's implementation of the host's [ToolSourceRegistry].
 *
 * The only file in this plugin that names the host contract: everything past this boundary works in
 * [ContributedToolSource] and [ContributedTool], so the tool set can be built and tested without it.
 * Values crossing the boundary are JDK types, because each plugin has its own class loader and only
 * the host's types are common ground.
 *
 * @param store where registrations land; the process-wide store by default.
 */
class ToolSourceRegistryImpl(
    private val store: ToolSourceStore = ToolSourceStore.shared,
) : ToolSourceRegistry {

    private val lock = Any()

    /** The host-side sources, kept in registration order so [getToolSources] can return them. */
    private val hostSources = LinkedHashMap<String, ToolSourceRegistry.ToolSource>()

    override fun registerToolSource(source: ToolSourceRegistry.ToolSource) {
        val providerId = try {
            source.providerId.orEmpty().trim()
        } catch (e: Throwable) {
            Log.e(TAG, "A tool source threw reading its providerId; ignoring it", e)
            return
        }
        if (providerId.isEmpty()) {
            Log.w(TAG, "Ignoring a tool source with a blank providerId")
            return
        }

        val displayName = try {
            source.displayName.takeIf { it.isNotBlank() } ?: providerId
        } catch (e: Throwable) {
            Log.w(TAG, "Tool source '$providerId' threw reading its displayName", e)
            providerId
        }

        synchronized(lock) { hostSources[providerId] = source }
        store.register(HostToolSource(source, providerId, displayName))
    }

    override fun unregisterToolSource(source: ToolSourceRegistry.ToolSource) {
        val id = synchronized(lock) {
            val key = hostSources.entries.firstOrNull { it.value === source }?.key
                ?: return@synchronized null
            hostSources.remove(key)
            key
        } ?: run {
            Log.w(TAG, "Ignoring a tool source that was never registered")
            return
        }
        store.unregister(id)
    }

    override fun getToolSources(): List<ToolSourceRegistry.ToolSource> =
        synchronized(lock) { hostSources.values.toList() }

    override fun notifyToolsChanged(providerId: String) {
        store.toolsChanged(providerId.trim())
    }

    /**
     * Adapts one host [ToolSourceRegistry.ToolSource] onto this plugin's own source type.
     *
     * Field for field, with two jobs of its own: dropping a malformed spec rather than registering
     * a tool the model can name but never call, and turning a future that completes with nothing
     * into a legible failure.
     */
    private class HostToolSource(
        private val delegate: ToolSourceRegistry.ToolSource,
        override val providerId: String,
        override val displayName: String,
    ) : ContributedToolSource {

        override fun listTools(): List<ContributedTool> =
            delegate.listTools().orEmpty().mapNotNull(::toContributedTool)

        override fun invoke(
            callId: String,
            toolName: String,
            args: Map<String, Any?>,
        ): CompletableFuture<ContributedToolResult> {
            val invocation = Invocation(callId, toolName, args, openProjectRoot())
            return delegate.invoke(invocation).thenApply(::toContributedResult)
        }

        override fun cancel(callId: String) {
            delegate.cancel(callId)
        }

        /**
         * Copies a host spec across, or drops it.
         * @param spec the provider's tool spec.
         * @return the internal tool, or null when the spec is unusable.
         */
        private fun toContributedTool(spec: ToolSourceRegistry.ToolSpec?): ContributedTool? {
            val name = spec?.name?.trim().orEmpty()
            if (spec == null || name.isEmpty()) {
                Log.w(TAG, "Source '$providerId' offered a tool with no name; dropping it")
                return null
            }
            return ContributedTool(
                providerId = providerId,
                name = name,
                description = spec.description.orEmpty(),
                parametersSchema = spec.parametersSchema.orEmpty(),
                requiresApproval = spec.requiresApproval(),
                readOnly = spec.isReadOnly,
            )
        }

        /**
         * Copies a host outcome across.
         * @param outcome the provider's outcome, possibly null.
         * @return the internal result.
         */
        private fun toContributedResult(outcome: ToolSourceRegistry.ToolOutcome?): ContributedToolResult =
            if (outcome == null) {
                ContributedToolResult(false, "", "$displayName completed with no outcome.")
            } else {
                ContributedToolResult(
                    success = outcome.isSuccess,
                    output = outcome.output.orEmpty(),
                    errorMessage = outcome.errorMessage?.takeIf { it.isNotBlank() },
                )
            }

        /** The open project's root, or null when there is none to hand over. */
        private fun openProjectRoot(): String? =
            PathGuard.projectRoot().takeIf { File(it).isDirectory }
    }

    /**
     * One call, as the host contract describes it.
     *
     * The contract is a Java interface, so its accessors are implemented as functions: Kotlin
     * synthesises properties for *reading* a Java getter, never for overriding one.
     */
    private class Invocation(
        private val callId: String,
        private val toolName: String,
        private val arguments: Map<String, Any?>,
        private val projectRoot: String?,
    ) : ToolSourceRegistry.ToolInvocation {
        override fun getCallId(): String = callId
        override fun getToolName(): String = toolName
        override fun getArguments(): Map<String, Any?> = arguments
        override fun getProjectRoot(): String? = projectRoot
    }
}
