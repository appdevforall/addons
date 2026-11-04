package com.appdevforall.jvm.plugin

import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

internal object JvmToolsDocumentation {

    const val CATEGORY = "plugin_${JvmToolsPlugin.PLUGIN_ID}"
    const val DOCS_ASSET_PATH = "docs"

    fun entries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = tagFor(JvmToolsPlugin.ACTION_RUN),
            summary = "Compiles every Java and Kotlin file in the project and runs the chosen main class.",
            detail = """
                <p>Compiles all <code>.java</code> and <code>.kt</code> files in the project on the device,
                then starts the main class chosen in <b>Run configuration</b> with its program arguments.
                With no choice saved, Run starts the main class of the file open in the editor, or the
                first one it finds.</p>
                <p>Compiler errors and the program's output stream into <b>Build Output</b>. While the
                program runs, the button becomes <b>Cancel Run</b>; tap it to stop the program. A run is
                stopped after 30 minutes.</p>
                <p>The program cannot read keyboard input: <code>Scanner</code> and
                <code>readLine()</code> see the end of input. Pass values as program arguments instead.</p>
            """.trimIndent(),
            buttons = buttons("run"),
        ),
        PluginTooltipEntry(
            tag = tagFor(JvmToolsPlugin.ACTION_RUN_CONFIGURATION),
            summary = "Chooses which main class Run starts and the arguments it passes.",
            detail = """
                <p>Lists every class in the project that has a <code>main</code> method: Java classes with
                <code>public static void main(String[] args)</code>, Kotlin files with a top-level
                <code>fun main()</code>, and Kotlin objects with a <code>@JvmStatic</code> main.</p>
                <p>Program arguments are split on spaces. Wrap an argument in quotes to keep its spaces,
                for example <code>--name "Ada Lovelace"</code>. The choice is saved for this project.</p>
            """.trimIndent(),
            buttons = buttons("run-configuration"),
        ),
    )

    fun tagFor(actionId: String): String = "${JvmToolsPlugin.PLUGIN_ID}.$actionId"

    private fun buttons(anchor: String): List<PluginTooltipButton> = listOf(
        PluginTooltipButton(
            description = "How this command works",
            uri = "index.html#$anchor",
            order = 0,
        ),
        PluginTooltipButton(
            description = "About Code on the Go plugins",
            uri = "i/plugins-adfa.html",
            order = 1,
            directPath = true,
        ),
    )
}
