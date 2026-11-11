package org.appdevforall.shareyourapps

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeEnvironmentService
import com.itsaky.androidide.plugins.services.IdeTooltipService
import java.io.File

class ShareYourAppsPlugin : IPlugin, UIExtension, DocumentationExtension {

    companion object {
        @Volatile var context: PluginContext? = null
            private set

        /** The plugin's private data directory, where catalog.json lives. */
        fun dataDir(): File? = try {
            context?.services?.get(IdeEnvironmentService::class.java)?.getPluginDataDirectory()
        } catch (e: Exception) {
            null
        }

        fun tooltips(): IdeTooltipService? = try {
            context?.services?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            null
        }
    }

    override fun initialize(context: PluginContext): Boolean {
        ShareYourAppsPlugin.context = context
        return true
    }

    override fun activate(): Boolean = true
    override fun deactivate(): Boolean = true
    override fun dispose() {}

    override fun getEditorTabs(): List<TabItem> = listOf(
        TabItem(
            id = "org_appdevforall_shareyourapps_tab",
            title = "Share Your Apps",
            fragmentFactory = { ShareYourAppsFragment() },
            isEnabled = true,
            isVisible = true,
            order = 0,
        )
    )

    // CodeOnTheGo files these under plugin_<pluginId> itself; this value is informational.
    override fun getTooltipCategory(): String = "plugin_org.appdevforall.shareyourapps"
    override fun getTooltipEntries(): List<PluginTooltipEntry> = Help.entries
    override fun onDocumentationInstall(): Boolean = true
    override fun onDocumentationUninstall() {}
    /** Tier 3: the offline pages in src/main/assets/docs/, which CodeOnTheGo indexes and serves. */
    override fun getTier3DocsAssetPath(): String = "docs"

    override fun getMainMenuItems(): List<MenuItem> = emptyList()
    override fun getSideMenuItems(): List<NavigationItem> = emptyList()
}
