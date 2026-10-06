package com.itsaky.androidide.plugins.aicore.capabilities

import com.itsaky.androidide.plugins.services.CapabilityStatus

/** Why the backend tag reads the way it does; each case is a different fix for the user. */
enum class BackendState {
    /** The selected backend is installed and reports itself configured. */
    READY,

    /** The selected backend is installed but not set up yet — no key, no model. */
    NOT_CONFIGURED,

    /** A selection is stored, but the plugin that provided it is gone. */
    NOT_INSTALLED,

    /** No backend plugin is installed at all. */
    NONE_INSTALLED,
}

/**
 * One tag in the row under the chat input: something the agent is connected to.
 *
 * Carries data only, never display text, so the row is built and tested without resources; the
 * one exception is [Tools.statusMessage], which the provider wrote for the user in its own words.
 */
sealed interface CapabilityTag {

    /** Stable identity, so the row can tell an updated tag from a new one. */
    val key: String

    val status: CapabilityStatus

    /**
     * The backend that will answer.
     *
     * @property name the backend's own label, or null when none is installed to name.
     * @property modelName the model it will answer with, or null when it does not say.
     * @property health whether a [BackendState.READY] backend's server answers, as it reports it.
     * @property statusMessage the backend's own sentence about a [health] other than available.
     */
    data class Backend(
        val name: String?,
        val modelName: String?,
        val state: BackendState,
        val health: CapabilityStatus = CapabilityStatus.AVAILABLE,
        val statusMessage: String? = null,
    ) : CapabilityTag {
        override val key: String get() = KEY
        override val status: CapabilityStatus
            get() = if (state == BackendState.READY) health else CapabilityStatus.DEGRADED

        companion object {
            const val KEY = "backend"
        }
    }

    /**
     * Whether the device can reach the internet, which every network backend and MCP server needs.
     *
     * @property online true when the default network is validated for internet access.
     */
    data class Web(val online: Boolean) : CapabilityTag {
        override val key: String get() = KEY
        override val status: CapabilityStatus
            get() = if (online) CapabilityStatus.AVAILABLE else CapabilityStatus.DEGRADED

        companion object {
            const val KEY = "web"
        }
    }

    /**
     * One source of plugin-contributed tools, or one group of a source that reports groups — one
     * MCP server, say.
     *
     * @property providerId the contributing source's provider id.
     * @property groupId the group's id within that source, or null when the source is one unit.
     * @property name the group's name, or the source's when it reports no groups.
     * @property sourceName the contributing source's display name.
     * @property toolCount how many tools this tag stands for; zero is legitimate for a broken one.
     * @property statusMessage the provider's own sentence about a non-available status, if any.
     */
    data class Tools(
        val providerId: String,
        val groupId: String?,
        val name: String,
        val sourceName: String,
        val toolCount: Int,
        override val status: CapabilityStatus,
        val statusMessage: String?,
    ) : CapabilityTag {
        override val key: String get() = "tools:$providerId:${groupId.orEmpty()}"
    }
}

/**
 * The row's order: backend first since it answers every prompt, then web access, then tool
 * sources in registration order.
 *
 * @param backend the backend tag.
 * @param web the web access tag.
 * @param tools the tool-source tags, already in presentation order.
 * @return the tags as the row shows them.
 */
fun capabilityRow(
    backend: CapabilityTag.Backend,
    web: CapabilityTag.Web,
    tools: List<CapabilityTag.Tools>,
): List<CapabilityTag> = listOf(backend, web) + tools
