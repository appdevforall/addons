package com.appdevforall.js.plugin

import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

internal object JsToolsDocumentation {

    const val CATEGORY = "plugin_${JsToolsPlugin.PLUGIN_ID}"
    const val DOCS_ASSET_PATH = "docs"

    fun entries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = tagFor(JsToolsPlugin.ACTION_RUN_APP),
            summary = "Runs the project's entry point with the bundled Node.js.",
            detail = """
                <p>Runs the file named by a <code>start</code> script of the form
                <code>node &lt;file&gt;</code>, or by the <code>main</code> field of
                <code>package.json</code>, or else <code>index</code> or <code>main</code> with a
                <code>.js</code> or <code>.ts</code> extension in the project root or <code>src/</code>.
                Any other <code>start</code> script runs through <code>npm start</code>.</p>
                <p>TypeScript files run directly: Node.js removes the type annotations as it loads
                them, so there is no build step. Output streams into <b>Build Output</b>, and
                <b>Cancel Run app</b> stops the program. A run is stopped after 30 minutes.</p>
            """.trimIndent(),
            buttons = buttons("run-app"),
        ),
        PluginTooltipEntry(
            tag = tagFor(JsToolsPlugin.ACTION_RUN_CURRENT_FILE),
            summary = "Runs the JavaScript or TypeScript file open in the editor.",
            detail = """
                <p>Appears while a <code>.js</code>, <code>.mjs</code>, <code>.cjs</code>,
                <code>.ts</code>, <code>.mts</code> or <code>.cts</code> file is open, and runs
                exactly that file with Node.js. Useful for a scratch script that is not the project's
                entry point. A run is stopped after 30 minutes.</p>
            """.trimIndent(),
            buttons = buttons("run-current-file"),
        ),
        PluginTooltipEntry(
            tag = tagFor(JsToolsPlugin.ACTION_TEST),
            summary = "Runs the project's tests.",
            detail = """
                <p>Runs <code>npm test</code> when <code>package.json</code> defines a test script,
                and otherwise Node.js's built-in test runner, <code>node --test</code>, which finds
                files such as <code>*.test.js</code> and <code>*.test.ts</code>. The built-in runner
                needs no packages, so tests run with no network connection.</p>
                <p>Results stream into <b>Build Output</b>. The run is stopped after 15 minutes.</p>
            """.trimIndent(),
            buttons = buttons("run-tests"),
        ),
        PluginTooltipEntry(
            tag = tagFor(JsToolsPlugin.ACTION_TYPE_CHECK),
            summary = "Checks the project's types with the bundled TypeScript compiler.",
            detail = """
                <p>Appears when the project has a <code>tsconfig.json</code> or
                <code>jsconfig.json</code>, and runs <code>tsc --noEmit</code> with it. Every type
                error in the project is listed in <b>Build Output</b>; nothing is written to disk.</p>
                <p>It is the same TypeScript compiler the language server uses for completion and
                the squiggles in the editor, so both report the same errors.</p>
            """.trimIndent(),
            buttons = buttons("type-check"),
        ),
        PluginTooltipEntry(
            tag = tagFor(JsToolsPlugin.ACTION_INSTALL),
            summary = "Runs npm install for the packages listed in package.json.",
            detail = """
                <p>Runs <code>npm install</code> in the project root. <b>Downloading packages needs a
                network connection.</b> A project that uses only Node.js's built-in modules never
                needs this step, which is why the bundled templates have no dependencies to
                download.</p>
            """.trimIndent(),
            buttons = buttons("install-packages"),
        ),
    )

    fun tagFor(actionId: String): String = "${JsToolsPlugin.PLUGIN_ID}.$actionId"

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
