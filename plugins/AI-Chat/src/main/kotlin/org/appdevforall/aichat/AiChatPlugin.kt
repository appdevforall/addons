package org.appdevforall.aichat

import androidx.fragment.app.Fragment
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.ContextMenuContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.EditorTabExtension
import com.itsaky.androidide.plugins.extensions.EditorTabItem
import com.itsaky.androidide.plugins.extensions.FabAction
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.ToolbarAction
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeEditorTabService

/**
 * AI Chat: a chat tab for Code on the Go whose front end is adapted from Jan's web UI
 * (https://github.com/janhq/jan, Apache-2.0; see web/NOTICE). It runs no model itself: every
 * message goes through AI Core's router to whichever backend the user picks in the composer.
 *
 * Shape:
 *   - this file: lifecycle, the editor tab, the side-menu launcher and in-app help
 *   - [ChatFragment]: the WebView that shows the bundled web app (web/, built into assets/web/)
 *   - [ChatBridge]: `window.CoGoChat`, the web app's only way into the IDE
 */
class AiChatPlugin : IPlugin, UIExtension, EditorTabExtension, DocumentationExtension {

    private lateinit var context: PluginContext

    override fun initialize(context: PluginContext): Boolean = try {
        this.context = context
        // The host creates ChatFragment itself, with no PluginContext to hand: pass it this way.
        pluginContext = context
        true
    } catch (t: Throwable) {
        context.logger.error("AiChatPlugin initialization failed", t)
        false
    }

    override fun activate(): Boolean = true

    override fun deactivate(): Boolean = true

    override fun dispose() {
        pluginContext = null
    }

    // --- EditorTabExtension: the chat is a main editor tab ------------------------------------

    override fun getMainEditorTabs(): List<EditorTabItem> = listOf(
        EditorTabItem(
            id = TAB_ID,
            title = "AI Chat",
            fragmentFactory = { ChatFragment() },
            isCloseable = true,
            isPersistent = false,
            order = 0,
            tooltip = "Chat with the AI backend selected in AI Core",
        ),
    )

    override fun onEditorTabSelected(tabId: String, fragment: Fragment) {}

    override fun onEditorTabClosed(tabId: String) {}

    override fun canCloseEditorTab(tabId: String): Boolean = true

    // --- UIExtension: a side-menu entry that opens the tab ------------------------------------
    // Every method is overridden: a plugin built against a newer plugin-api that leaves one to
    // the interface default throws AbstractMethodError on an older Code on the Go.

    override fun getSideMenuItems(): List<NavigationItem> = listOf(
        NavigationItem(
            id = "open_ai_chat",
            title = "AI Chat",
            icon = android.R.drawable.ic_dialog_email,
            group = "ai",
            order = 50,
            tooltipTag = TOOLTIP_TAG,
            action = { openChat() },
        ),
    )

    override fun getMainMenuItems(): List<MenuItem> = emptyList()
    override fun getContextMenuItems(context: ContextMenuContext): List<MenuItem> = emptyList()
    override fun getEditorTabs(): List<TabItem> = emptyList()
    override fun getToolbarActions(): List<ToolbarAction> = emptyList()
    override fun getFabActions(): List<FabAction> = emptyList()
    override fun getHiddenToolbarActionIds(): Set<String> = emptySet()

    private fun openChat() {
        val tabs = context.services.get(IdeEditorTabService::class.java)
        if (tabs == null || !tabs.isTabSystemAvailable()) {
            context.logger.warn("AI Chat: the editor tab system is not available")
            return
        }
        runCatching { tabs.selectPluginTab(TAB_ID) }
            .onFailure { context.logger.error("AI Chat: could not open the chat tab", it) }
    }

    // --- DocumentationExtension ---------------------------------------------------------------

    override fun getTooltipCategory(): String = "plugin_$PLUGIN_ID"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG,
            summary = "Chat with your AI backend in an editor tab.",
            detail = """
                <p>Opens a chat tab. Messages go to the AI backend you pick in the composer:
                <b>AI Agent Local</b> keeps everything on the device; a cloud backend sends
                your messages to that provider.</p>
                <p>Needs <b>AI Core</b> and at least one AI backend from the Addons Manager.
                Chats are saved on the device, in this plugin's storage.</p>
            """.trimIndent(),
            buttons = listOf(PluginTooltipButton(description = "About AI Chat", uri = "index.html", order = 0)),
        ),
    )

    override fun getTier3DocsAssetPath(): String = "docs"

    companion object {
        const val PLUGIN_ID = "org.appdevforall.aichat"
        const val TAB_ID = "ai_chat_main"
        const val TOOLTIP_TAG = "aichat.open"

        @Volatile
        var pluginContext: PluginContext? = null
            private set
    }
}
