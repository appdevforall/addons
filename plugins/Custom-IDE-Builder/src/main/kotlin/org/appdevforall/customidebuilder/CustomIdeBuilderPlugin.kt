package org.appdevforall.customidebuilder

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeTooltipService
import com.itsaky.androidide.plugins.services.IdeUIService

/**
 * Custom IDE Builder: makes a copy of the installed Code on the Go with a chosen set of
 * add-ons bundled, signed with the user's own key. The work is in `core`; this class only adds
 * the main-menu entry that opens the screen.
 */
class CustomIdeBuilderPlugin : IPlugin, UIExtension, DocumentationExtension {

    override fun initialize(context: PluginContext): Boolean {
        Companion.context = context
        // Anything a previous run left behind (a crash mid-build) is stale: no build is running yet.
        java.io.File(context.getPluginFilesDir(), "work").deleteRecursively()
        return true
    }

    override fun activate(): Boolean = true

    override fun deactivate(): Boolean = true

    override fun dispose() {
        Session.end()
        Session.clearItems()
        context = null
    }

    override fun getMainMenuItems(): List<MenuItem> = listOf(
        MenuItem(
            id = "custom_ide_builder",
            title = "Custom IDE Builder",
            isEnabled = true,
            isVisible = true,
            shortcut = null,
            subItems = emptyList(),
            action = { open() },
            tooltipTag = Help.MENU,
            icon = R.drawable.ic_custom_ide_builder,
        ),
    )

    private fun open() {
        val opened = context?.services?.get(IdeUIService::class.java)
            ?.openPluginScreen(PLUGIN_ID, BuilderFragment::class.java.name, "Custom IDE Builder") == true
        if (!opened) context?.logger?.warn("Unable to open the Custom IDE Builder screen")
    }

    override fun getTooltipCategory(): String = Help.CATEGORY

    override fun getTooltipEntries(): List<PluginTooltipEntry> = Help.entries

    override fun getTier3DocsAssetPath(): String = "docs"

    companion object {
        const val PLUGIN_ID = "org.appdevforall.customidebuilder"

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
