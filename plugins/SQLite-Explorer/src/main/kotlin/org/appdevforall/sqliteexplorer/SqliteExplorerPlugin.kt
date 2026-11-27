package org.appdevforall.sqliteexplorer

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeTooltipService
import com.itsaky.androidide.plugins.services.IdeUIService

/**
 * SQLite Explorer: browses and queries the databases of the open project's app through its database debugger
 * (ADFA-7131). The protocol and parsing are in `core`; this class adds the main-menu entry that opens the screen.
 */
class SqliteExplorerPlugin : IPlugin, UIExtension, DocumentationExtension {

    override fun initialize(context: PluginContext): Boolean {
        Companion.context = context
        return true
    }

    override fun activate(): Boolean {
        Connection.active = true
        return true
    }

    // The host's disable calls only this, not dispose, so the connection is dropped here.
    override fun deactivate(): Boolean {
        Connection.active = false
        Connection.forget()
        return true
    }

    override fun dispose() {
        Connection.active = false
        Connection.forget()
        context = null
    }

    override fun getMainMenuItems(): List<MenuItem> = listOf(
        MenuItem(
            id = "sqlite_explorer",
            title = "SQLite Explorer",
            isEnabled = true,
            isVisible = true,
            shortcut = null,
            subItems = emptyList(),
            action = { open() },
            tooltipTag = Help.MENU,
            icon = R.drawable.ic_sqlite_explorer,
        ),
    )

    private fun open() {
        val opened = context?.services?.get(IdeUIService::class.java)
            ?.openPluginScreen(PLUGIN_ID, ExplorerFragment::class.java.name, "SQLite Explorer") == true
        if (!opened) context?.logger?.warn("Unable to open the SQLite Explorer screen")
    }

    override fun getTooltipCategory(): String = Help.CATEGORY

    override fun getTooltipEntries(): List<PluginTooltipEntry> = Help.entries

    override fun getTier3DocsAssetPath(): String = "docs"

    companion object {
        const val PLUGIN_ID = "org.appdevforall.sqliteexplorer"

        /** The host creates the screen by class name and gives it no context, so it reads this one. */
        @Volatile
        var context: PluginContext? = null
            private set

        fun tooltips(): IdeTooltipService? = try {
            context?.services?.get(IdeTooltipService::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
