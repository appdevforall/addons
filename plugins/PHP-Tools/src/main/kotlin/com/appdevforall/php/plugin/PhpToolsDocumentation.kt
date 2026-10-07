package com.appdevforall.php.plugin

import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

internal object PhpToolsDocumentation {

    const val CATEGORY = "plugin_${PhpToolsPlugin.PLUGIN_ID}"
    const val DOCS_ASSET_PATH = "docs"

    fun entries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = tagFor(PhpToolsPlugin.ACTION_RUN_APP),
            summary = "Runs the project with the bundled PHP, or starts its website.",
            detail = """
                <p>Runs the <code>start</code> script from <code>composer.json</code>. A script of the
                form <code>php ...</code>, such as <code>php -S localhost:8080 -t public</code>, runs
                PHP directly; any other script runs through Composer. Without a start script, a
                <code>public/index.php</code> starts PHP's built-in web server on port 8080, and
                otherwise <code>index.php</code> or <code>main.php</code> runs as a program.</p>
                <p>Output streams into <b>Build Output</b>, and <b>Cancel Run app</b> stops the program
                or the web server. A run is stopped after 30 minutes.</p>
            """.trimIndent(),
            buttons = buttons("run-app"),
        ),
        PluginTooltipEntry(
            tag = tagFor(PhpToolsPlugin.ACTION_RUN_CURRENT_FILE),
            summary = "Runs the PHP file open in the editor.",
            detail = """
                <p>Appears while a <code>.php</code> file is open, and runs exactly that file with
                PHP. Useful for a scratch script that is not the project's entry point. A run is
                stopped after 30 minutes.</p>
            """.trimIndent(),
            buttons = buttons("run-current-file"),
        ),
        PluginTooltipEntry(
            tag = tagFor(PhpToolsPlugin.ACTION_TEST),
            summary = "Runs the project's tests.",
            detail = """
                <p>Runs PHPUnit when Composer has installed it in <code>vendor/bin</code>, or the
                <code>test</code> script from <code>composer.json</code>. Otherwise it runs every
                <code>tests/*Test.php</code> file with assertions turned on, so a failed
                <code>assert()</code> fails the test. That needs no packages, so tests run with no
                network connection.</p>
                <p>Results stream into <b>Build Output</b>. The run is stopped after 15 minutes.</p>
            """.trimIndent(),
            buttons = buttons("run-tests"),
        ),
        PluginTooltipEntry(
            tag = tagFor(PhpToolsPlugin.ACTION_CHECK_SYNTAX),
            summary = "Checks every PHP file in the project for syntax errors.",
            detail = """
                <p>Runs <code>php -l</code> on every <code>.php</code> file outside
                <code>vendor/</code> and lists each file with its result in <b>Build Output</b>.
                Nothing is run and nothing is written to disk.</p>
            """.trimIndent(),
            buttons = buttons("check-syntax"),
        ),
        PluginTooltipEntry(
            tag = tagFor(PhpToolsPlugin.ACTION_INSTALL),
            summary = "Runs composer install for the packages listed in composer.json.",
            detail = """
                <p>Appears when the project has a <code>composer.json</code>, and runs
                <code>composer install</code> in the project root. <b>Downloading packages needs a
                network connection.</b> A project that uses only PHP's built-in functions never needs
                this step, which is why the bundled templates have no dependencies.</p>
            """.trimIndent(),
            buttons = buttons("install-dependencies"),
        ),
    )

    fun tagFor(actionId: String): String = "${PhpToolsPlugin.PLUGIN_ID}.$actionId"

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
