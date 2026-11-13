package org.appdevforall.buildanimation

import android.os.Handler
import android.os.Looper
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.base.PluginWindows
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.ShowAsAction
import com.itsaky.androidide.plugins.extensions.ToolbarAction
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.BuildStatusListener
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.services.IdeUIService

/**
 * Shows an animated toolbar icon while a Gradle build runs, then a short success or failure
 * animation. When no build is running the icon is a greyed-out static wrench.
 *
 * Uses the host's dynamic toolbar icon ([ToolbarAction.iconProvider] plus
 * [IdeUIService.refreshToolbarActions]); the host starts an animated drawable itself. Build
 * callbacks arrive on the main thread.
 */
class BuildAnimationPlugin : IPlugin, UIExtension, DocumentationExtension, BuildStatusListener {

    private lateinit var context: PluginContext
    private var buildService: IdeBuildService? = null
    private val handler = Handler(Looper.getMainLooper())

    private val state = BuildAnimationState(
        scheduler = object : Scheduler {
            override fun postDelayed(delayMs: Long, task: Runnable) {
                handler.postDelayed(task, delayMs)
            }

            override fun cancel(task: Runnable) {
                handler.removeCallbacks(task)
            }
        },
        onChange = { refreshToolbar() },
    )

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        return true
    }

    override fun activate(): Boolean {
        buildService = context.services.get(IdeBuildService::class.java)
        buildService?.addBuildStatusListener(this)
        // Installing or enabling mid-build would otherwise show nothing until the next build.
        state.reset(buildService?.isBuildInProgress() == true)
        return true
    }

    override fun deactivate(): Boolean {
        tearDown()
        return true
    }

    // Also run here: the host may dispose without deactivating first.
    override fun dispose() = tearDown()

    /** Idempotent, so running it from both deactivate() and dispose() is safe. */
    private fun tearDown() {
        buildService?.removeBuildStatusListener(this)
        buildService = null
        state.dispose()
    }

    override fun onBuildStarted() = state.onStarted()

    override fun onBuildFinished() = state.onFinished()

    // Cancelled builds also arrive here; the plugin API doesn't distinguish them.
    override fun onBuildFailed(error: String?) = state.onFailed()

    override fun getToolbarActions(): List<ToolbarAction> = listOf(
        ToolbarAction(
            id = TOOLBAR_ACTION_ID,
            title = str(R.string.build_animation_action_title),
            icon = R.drawable.ic_build_animation,
            showAsAction = ShowAsAction.ALWAYS,
            order = 1000,
            action = { showStatus() },
        ).apply {
            // Greyed out when idle rather than hidden: the toolbar is end-aligned, so adding or
            // removing an icon shifts every built-in button, and Stop would move out from under
            // the finger that just tapped Run.
            isEnabledProvider = { state.phase != BuildPhase.IDLE }
            iconProvider = { iconFor(state.phase) }
        }
    )

    @DrawableRes
    private fun iconFor(phase: BuildPhase): Int = when (phase) {
        BuildPhase.IDLE -> R.drawable.ic_build_animation
        BuildPhase.BUILDING -> R.drawable.avd_build_running
        BuildPhase.SUCCEEDED -> R.drawable.avd_build_succeeded
        BuildPhase.FAILED -> R.drawable.avd_build_failed
    }

    private fun showStatus() {
        val message = when (state.phase) {
            BuildPhase.IDLE -> return
            BuildPhase.BUILDING -> R.string.build_animation_status_building
            BuildPhase.SUCCEEDED -> R.string.build_animation_status_succeeded
            BuildPhase.FAILED -> R.string.build_animation_status_failed
        }
        PluginWindows.showToast(context.androidContext, str(message))
    }

    private fun refreshToolbar() {
        context.services.get(IdeUIService::class.java)?.refreshToolbarActions()
    }

    private fun str(@StringRes id: Int): String = context.androidContext.getString(id)

    override fun getTooltipCategory(): String = "plugin_$PLUGIN_ID"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            // The host looks a toolbar action's tooltip up as "<pluginId>.<actionId>"
            // (PluginToolbarActionItem.retrieveTooltipTag), not by the bare action id.
            tag = "$PLUGIN_ID.$TOOLBAR_ACTION_ID",
            summary = "Animates while a build runs, then shows whether it passed. Tap for the status.",
            detail = """
                <p>This icon is greyed out until Gradle starts working. It animates during
                the build, then shows a tick or a cross for a few seconds.</p>
                <ul>
                  <li><b>Tap</b> the icon to see the current status.</li>
                  <li>A project sync animates too, because Gradle runs then as well.</li>
                  <li>Quick Build does not report to plugins yet, so it does not animate.</li>
                </ul>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(description = "About Build Animation", uri = "index.html", order = 0),
            ),
        )
    )

    override fun getTier3DocsAssetPath(): String = "docs"

    companion object {
        const val PLUGIN_ID = "org.appdevforall.buildanimation"
        private const val TOOLBAR_ACTION_ID = "build_animation_status"
    }
}
