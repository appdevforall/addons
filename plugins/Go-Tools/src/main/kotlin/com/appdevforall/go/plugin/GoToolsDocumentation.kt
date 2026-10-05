package com.appdevforall.go.plugin

import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

internal object GoToolsDocumentation {

    const val CATEGORY = "plugin_${GoToolsPlugin.PLUGIN_ID}"
    const val DOCS_ASSET_PATH = "docs"

    fun entries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = tagFor(GoToolsPlugin.ACTION_RUN_APP),
            summary = "Compiles the main package in the project root and runs the program.",
            detail = """
                <p>Runs <code>go build</code> on the project root, then executes the binary it
                produced. Building first and executing the result - rather than using
                <code>go run</code> - means the process the IDE tracks <i>is</i> your program, so
                <b>Cancel Run app</b> really stops it. With <code>go run</code> the compiler exits
                and leaves the program behind as an orphan.</p>
                <p>The binary is written to the IDE's temporary directory and replaced on every run.
                Output streams into <b>Build Output</b>. A run is stopped after 30 minutes.</p>
                <p>If the build fails because a module is missing, <code>go mod tidy</code> runs for
                you and you are asked to tap Run again.</p>
            """.trimIndent(),
            buttons = buttons("run-app"),
        ),
        PluginTooltipEntry(
            tag = tagFor(GoToolsPlugin.ACTION_RUN_CURRENT_FILE),
            summary = "Compiles and runs the single Go file open in the editor.",
            detail = """
                <p>Appears while a <code>.go</code> file that is not a test file is open, and builds
                exactly that file by its full path - useful for a scratch program that is not the
                module's entry point.</p>
                <p>The file must declare <code>package main</code> and a <code>main</code> function,
                and it must not depend on other files in the package: a single-file build sees only
                the file you point it at. A run is stopped after 30 minutes.</p>
            """.trimIndent(),
            buttons = buttons("run-current-file"),
        ),
        PluginTooltipEntry(
            tag = tagFor(GoToolsPlugin.ACTION_TIDY),
            summary = "Runs go mod tidy to add missing modules and drop unused ones.",
            detail = """
                <p>Runs <code>go mod tidy</code> in the project root: it reads every import in the
                module, adds what is missing to <code>go.mod</code>, removes what is no longer used,
                and refreshes <code>go.sum</code>.</p>
                <p><b>This step needs a network connection</b> - modules are downloaded from the Go
                module proxy. A project that imports only the standard library never needs it, which
                is why both bundled templates are standard-library only. The command is stopped
                after 10 minutes.</p>
            """.trimIndent(),
            buttons = buttons("tidy-modules"),
        ),
        PluginTooltipEntry(
            tag = tagFor(GoToolsPlugin.ACTION_TEST),
            summary = "Runs every test in the module with go test.",
            detail = """
                <p>Runs <code>go test ./...</code> from the project root, covering every package in
                the module. Go's test runner is part of the toolchain, so nothing extra is
                installed.</p>
                <p>Results stream into <b>Build Output</b>. The run is stopped after 15 minutes.</p>
            """.trimIndent(),
            buttons = buttons("run-tests"),
        ),
    )

    fun tagFor(actionId: String): String = "${GoToolsPlugin.PLUGIN_ID}.$actionId"

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
