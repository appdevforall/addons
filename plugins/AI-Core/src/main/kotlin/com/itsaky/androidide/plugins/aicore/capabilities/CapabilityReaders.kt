package com.itsaky.androidide.plugins.aicore.capabilities

import android.util.Log
import com.itsaky.androidide.plugins.aicore.backends.SelectedBackend
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.services.CapabilityStatus
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.ToolSourceRegistry

private const val TAG = "$LOG_PREFIX.CapabilityReaders"

/**
 * Reads the host contracts into [CapabilityTag]s.
 *
 * Every call here reaches into another plugin's code, so each is guarded on its own: a source or
 * backend that throws costs its own tag the detail it failed to give, never the whole row.
 */
object CapabilityReaders {

    /**
     * The tag for the backend the user selected.
     *
     * @param selected the selection, resolved as the settings screen resolves it.
     * @param service the router, to reach the backend object behind the selection.
     * @return the backend tag; a backend that cannot be asked reads as not configured.
     */
    fun backend(selected: SelectedBackend, service: LlmInferenceService?): CapabilityTag.Backend =
        when (selected) {
            SelectedBackend.None -> CapabilityTag.Backend(null, null, BackendState.NONE_INSTALLED)
            SelectedBackend.Missing -> CapabilityTag.Backend(null, null, BackendState.NOT_INSTALLED)
            is SelectedBackend.Installed -> {
                val option = selected.option
                val backend = guard("backend '${option.id}'") { service?.getBackend(option.id) }
                val available = backend != null &&
                    guard("backend '${option.id}' availability") { backend.isAvailable } == true
                // Asked only of a configured backend; one that does not report status is taken at
                // its isAvailable word.
                val reporting = backend as? LlmInferenceService.StatusReportingBackend
                val health = if (available && reporting != null) {
                    guard("backend '${option.id}' status") { reporting.status }
                        ?.let(::status) ?: CapabilityStatus.AVAILABLE
                } else {
                    CapabilityStatus.AVAILABLE
                }
                val message = if (health != CapabilityStatus.AVAILABLE) {
                    message(guard("backend '${option.id}' message") { reporting?.statusMessage })
                } else {
                    null
                }
                CapabilityTag.Backend(
                    name = option.displayName,
                    modelName = backend?.let { modelName(option.id, it) },
                    state = if (available) BackendState.READY else BackendState.NOT_CONFIGURED,
                    health = health,
                    statusMessage = message,
                )
            }
        }

    /**
     * One tag per group of every source that reports groups, and one per source that does not.
     *
     * @param sources the registered sources, in registration order.
     * @return the tags, in that order; a source that cannot even name itself is left out.
     */
    fun tools(sources: List<ToolSourceRegistry.ToolSource>): List<CapabilityTag.Tools> =
        sources.flatMap(::toolsOf)

    /**
     * Normalizes a provider's status for display. Null from a misbehaving provider, or a constant
     * added to the contract after this build, reads as degraded, which the contract calls safest.
     *
     * @param status the provider's status.
     * @return the status to show.
     */
    fun status(status: CapabilityStatus?): CapabilityStatus = when (status) {
        CapabilityStatus.AVAILABLE -> CapabilityStatus.AVAILABLE
        CapabilityStatus.CONNECTING -> CapabilityStatus.CONNECTING
        else -> CapabilityStatus.DEGRADED
    }

    private fun toolsOf(source: ToolSourceRegistry.ToolSource): List<CapabilityTag.Tools> {
        val providerId = guard("a tool source's providerId") { source.providerId }
            ?.trim()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val sourceName = guard("source '$providerId' displayName") { source.displayName }
            ?.takeIf { it.isNotBlank() } ?: providerId

        val groups = (source as? ToolSourceRegistry.GroupedToolSource)
            ?.let { guard("source '$providerId' groups") { it.toolGroups } }.orEmpty()
        if (groups.isNotEmpty()) return groups.mapNotNull { group -> groupTag(providerId, sourceName, group) }

        val tag = CapabilityTag.Tools(
            providerId = providerId,
            groupId = null,
            name = sourceName,
            sourceName = sourceName,
            toolCount = guard("source '$providerId' tools") { source.listTools() }?.size ?: 0,
            status = sourceStatus(providerId, source),
            statusMessage = (source as? ToolSourceRegistry.StatusReportingToolSource)
                ?.let { message(guard("source '$providerId' message") { it.statusMessage }) },
        )
        // Offering nothing and not broken — MCP with no server switched on — is not connected to
        // anything; a broken one with no tools is exactly what must stay visible.
        val idle = tag.toolCount == 0 && tag.status == CapabilityStatus.AVAILABLE
        return if (idle) emptyList() else listOf(tag)
    }

    /** A source that does not report status is taken as available. */
    private fun sourceStatus(providerId: String, source: ToolSourceRegistry.ToolSource): CapabilityStatus =
        (source as? ToolSourceRegistry.StatusReportingToolSource)
            ?.let { status(guard("source '$providerId' status") { it.status }) }
            ?: CapabilityStatus.AVAILABLE

    private fun groupTag(
        providerId: String,
        sourceName: String,
        group: ToolSourceRegistry.ToolGroup?,
    ): CapabilityTag.Tools? {
        group ?: return null
        val groupId = guard("a group of '$providerId'") { group.id }
            ?.takeIf { it.isNotBlank() } ?: return null
        val label = "group '$groupId' of '$providerId'"
        return CapabilityTag.Tools(
            providerId = providerId,
            groupId = groupId,
            name = guard("$label displayName") { group.displayName }?.takeIf { it.isNotBlank() } ?: groupId,
            sourceName = sourceName,
            toolCount = guard("$label tools") { group.toolNames }?.size ?: 0,
            status = status(guard("$label status") { group.status }),
            statusMessage = message(guard("$label message") { group.statusMessage }),
        )
    }

    private fun modelName(id: String, backend: LlmInferenceService.LlmBackend): String? =
        (backend as? LlmInferenceService.ActiveModelReportingBackend)
            ?.let { guard("backend '$id' model name") { it.activeModelName } }
            ?.trim()?.takeIf { it.isNotEmpty() }

    private fun message(text: String?): String? = text?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Runs one call into another plugin, logging and swallowing whatever it throws.
     *
     * @param what names the call for the log.
     * @return the call's answer, or null when it threw.
     */
    private inline fun <T> guard(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        Log.w(TAG, "Reading $what failed", e)
        null
    }
}
