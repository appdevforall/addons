package com.appdevforall.rust.plugin

import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

internal object RustToolsDocumentation {

    const val CATEGORY = "plugin_${RustToolsPlugin.PLUGIN_ID}"
    const val DOCS_ASSET_PATH = "docs"

    fun entries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = tagFor(RustToolsPlugin.ACTION_RUN_APP),
            summary = "Builds the crate with cargo and runs the program.",
            detail = """
                <p>Runs <code>cargo run</code> in the project root. Cargo compiles the crate and then
                replaces itself with your program, so the process the IDE tracks <i>is</i> your
                program and <b>Cancel Run app</b> really stops it.</p>
                <p>Build output goes to the plugin's private directory rather than the project
                folder, because programs cannot be started from the device's shared storage. Output
                streams into <b>Build Output</b>. A run is stopped after 30 minutes.</p>
            """.trimIndent(),
            buttons = buttons("run-app"),
        ),
        PluginTooltipEntry(
            tag = tagFor(RustToolsPlugin.ACTION_RUN_CURRENT_FILE),
            summary = "Compiles and runs the single Rust file open in the editor.",
            detail = """
                <p>Compiles exactly the <code>.rs</code> file open in the editor with
                <code>rustc</code> and runs it - useful for a scratch program outside the crate.
                Open a <code>.rs</code> file before tapping it.</p>
                <p>The file needs a <code>main</code> function and cannot use other modules or
                crates: a single-file build sees only the file you point it at. For anything larger,
                use <b>Run app</b>. A run is stopped after 30 minutes.</p>
            """.trimIndent(),
            buttons = buttons("run-current-file"),
        ),
        PluginTooltipEntry(
            tag = tagFor(RustToolsPlugin.ACTION_FORMAT),
            summary = "Formats every file in the crate with rustfmt.",
            detail = """
                <p>Runs <code>cargo fmt</code> in the project root, rewriting every <code>.rs</code>
                file in the crate to the standard Rust style. Save open files first: the formatter
                works on the files on disk.</p>
                <p>A <code>rustfmt.toml</code> in the project root changes the style. The command is
                stopped after 5 minutes.</p>
            """.trimIndent(),
            buttons = buttons("format-code"),
        ),
        PluginTooltipEntry(
            tag = tagFor(RustToolsPlugin.ACTION_TEST),
            summary = "Runs every test in the crate with cargo test.",
            detail = """
                <p>Runs <code>cargo test</code> in the project root: unit tests, integration tests in
                <code>tests/</code>, and documentation examples. Rust's test runner is part of the
                toolchain, so nothing extra is installed.</p>
                <p>Results stream into <b>Build Output</b>. The run is stopped after 15 minutes.</p>
            """.trimIndent(),
            buttons = buttons("run-tests"),
        ),
    )

    fun tagFor(actionId: String): String = "${RustToolsPlugin.PLUGIN_ID}.$actionId"

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
