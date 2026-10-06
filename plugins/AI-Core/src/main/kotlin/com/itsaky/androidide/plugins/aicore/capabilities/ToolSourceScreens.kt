package com.itsaky.androidide.plugins.aicore.capabilities

import androidx.annotation.StringRes
import com.itsaky.androidide.plugins.aicore.R

/**
 * A screen another plugin owns, which the host mounts with that plugin's own class loader.
 *
 * @property pluginId the owning plugin's id.
 * @property fragmentClassName the screen's Fragment, as that plugin's settings entry names it.
 * @property titleRes the screen's title, from this plugin's resources.
 */
data class PluginScreen(
    val pluginId: String,
    val fragmentClassName: String,
    @StringRes val titleRes: Int,
)

/**
 * The settings screen of each tool source that has one.
 *
 * Kept here because `ToolSource` carries no settings hook; each entry must track the class its
 * plugin's `getSettingsEntries()` names, or the host shows a blank screen.
 */
object ToolSourceScreens {

    private const val MCP_PLUGIN_ID = "com.itsaky.androidide.plugins.aiagentmcp"

    private val screens = mapOf(
        MCP_PLUGIN_ID to PluginScreen(
            pluginId = MCP_PLUGIN_ID,
            fragmentClassName = "com.itsaky.androidide.plugins.aiagentmcp.settings.McpSettingsFragment",
            titleRes = R.string.pref_mcp_title,
        ),
    )

    /**
     * @param providerId the tool source's provider id, which is its plugin's id.
     * @return the source's settings screen, or null when it has none known here.
     */
    fun settingsFor(providerId: String): PluginScreen? = screens[providerId]
}
