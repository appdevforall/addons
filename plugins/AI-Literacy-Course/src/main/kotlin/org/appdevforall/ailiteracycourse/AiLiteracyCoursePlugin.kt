package org.appdevforall.ailiteracycourse

import org.appdevforall.ailiteracycourse.fragments.CourseFragment
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeUIService

/**
 * AI Literacy Course — packages Learn AI Anywhere's offline "Introduction to AI"
 * course (26 videos + interactive activities + the NeuroPocket app + teacher
 * resources) as a Code On The Go plugin that runs entirely offline.
 *
 * Shape:
 *   - this file: lifecycle + a side-menu launcher that opens the course full-screen
 *   - [CourseInstaller]: one-time extraction of the bundled ZIP into plugin storage
 *   - [CourseShell]: generates the navigation index.html over the extracted tree
 *   - [CourseFragment]: the full-screen WebView host (video, activities, PDFs)
 *
 * The ~110 MB course ZIP is fetched at build time by the `downloadAssets` Gradle
 * task and packed into the .cgp; nothing is downloaded at runtime.
 */
class AiLiteracyCoursePlugin : IPlugin, UIExtension, DocumentationExtension {

    private lateinit var context: PluginContext

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            // The host instantiates CourseFragment by class name (it has no
            // PluginContext of its own), so hand the context to it via a holder.
            pluginContext = context
            context.logger.info("AiLiteracyCoursePlugin initialized")
            true
        } catch (t: Throwable) {
            context.logger.error("AiLiteracyCoursePlugin initialization failed", t)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("AiLiteracyCoursePlugin activated")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("AiLiteracyCoursePlugin deactivated")
        return true
    }

    override fun dispose() {
        pluginContext = null
        context.logger.info("AiLiteracyCoursePlugin disposed")
    }

    // --- UIExtension: side-menu launcher -------------------------------------

    override fun getSideMenuItems(): List<NavigationItem> = listOf(
        NavigationItem(
            id = "open_course",
            title = "AI Literacy Course",
            icon = R.drawable.ic_course,
            group = "learn",
            order = 100,
            tooltipTag = TOOLTIP_TAG,
            action = { openCourse() }
        )
    )

    private fun openCourse() {
        val opened = context.services
            .get(IdeUIService::class.java)
            ?.openPluginScreen(
                pluginId = PLUGIN_ID,
                fragmentClassName = CourseFragment::class.java.name,
                title = "AI Literacy Course"
            ) == true

        if (!opened) {
            context.logger.warn("Unable to open the AI Literacy Course screen")
        }
    }

    // --- DocumentationExtension: tooltip + Tier-3 docs -----------------------

    override fun getTooltipCategory(): String = "plugin_ailiteracy"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG,
            summary = "Open the offline AI Literacy Course — 26 videos plus interactive activities.",
            detail = """
                <p>Opens the <b>Introduction to AI</b> course from
                <b>Learn AI Anywhere</b>, full-screen and fully offline.</p>
                <ul>
                  <li><b>Videos</b> — an introduction plus four lessons, in playback order.</li>
                  <li><b>Activities</b> — self-check quizzes, worksheets, and the NeuroPocket app.</li>
                  <li><b>Resources</b> — teacher guide, slides, and worksheets (PDF).</li>
                </ul>
                <p>The first time you open it, the course unpacks once; after that it
                opens instantly with no internet needed.</p>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "About this plugin",
                    uri = "index.html",  // -> assets/docs/index.html (Tier 3)
                    order = 0
                )
            )
        )
    )

    override fun getTier3DocsAssetPath(): String? = "docs"

    companion object {
        const val PLUGIN_ID = "org.appdevforall.ailiteracycourse"
        const val TOOLTIP_TAG = "ailiteracy.open_course"

        /**
         * Set in [initialize], read by [CourseFragment]. The fragment is created
         * by the host with no reference to our [PluginContext]; this holder bridges
         * the gap. Lives for the IDE session — cleared in [dispose].
         */
        @Volatile
        var pluginContext: PluginContext? = null
            private set
    }
}
