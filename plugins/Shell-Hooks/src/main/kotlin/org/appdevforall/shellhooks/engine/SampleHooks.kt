package org.appdevforall.shellhooks.engine

import java.io.File

/**
 * Writes one `<hook>.sample` per hook, and a sample action, into the user's hooks folder. As in
 * git, a sample never runs: it is not executable and its name ends in `.sample`. Renaming it and
 * marking it executable turns it on.
 */
object SampleHooks {
    const val ACTION_SAMPLE = "word-count.sample"

    /** Writes the samples and returns how many files it wrote. */
    fun write(paths: HookPaths): Int {
        paths.globalHooks.mkdirs()
        paths.actions.mkdirs()
        var written = 0
        for (hook in Hook.entries) {
            sample(File(paths.globalHooks, hook.id + HookDiscovery.SAMPLE_SUFFIX), hookSample(hook))
            written++
        }
        sample(File(paths.actions, ACTION_SAMPLE), ACTION_BODY)
        return written + 1
    }

    private fun sample(
        file: File,
        body: String,
    ) {
        file.writeText(body)
        file.setExecutable(false, false)
    }

    fun hookSample(hook: Hook): String =
        """
        |#!/usr/bin/env bash
        |# Sample Shell Hooks script for "${hook.id}" (${hook.title}).
        |# To use it, drop ".sample" from its name and make it executable:
        |#   cd ~/.config/codeonthego/hooks && mv ${hook.id}.sample ${hook.id} && chmod +x ${hook.id}
        |# For several scripts on one hook, put them in ${hook.id}.d/ instead; they run in name order.
        |#
        |# Arguments: ${hook.argsDoc}
        |# Environment:
        |#   COGO_HOOK          ${hook.id}
        |#   COGO_PROJECT_DIR   the project's folder, or empty when none is open and for ide-started
        |#   COGO_HOOKS_DIR     the hooks folder this script is in
        |#   COGO_HOOK_SCRIPT   this script's path
        |#   COGO_HOOK_SOURCE   global, or project for a script stored in a project
        |#   COGO_IDE_VERSION   the IDE's version, when known
        |# The script runs in the project's folder. Its output appears in the Hooks tab.
        |# Hooks only report what happened; a script cannot stop a save or a build.
        |
        |printf '%s %s %s\n' "${'$'}(date '+%F %T')" "${'$'}COGO_HOOK" "${'$'}*" >> "${'$'}HOME/codeonthego-hooks.log"
        |
        """.trimMargin()

    private val ACTION_BODY =
        """
        |#!/usr/bin/env bash
        |# Sample Shell Hooks action. Actions appear as "Run <name>" when you long-press a file in
        |# the file tree or an editor tab, and run in the Terminal so you see their output.
        |# To use it: cd ~/.config/codeonthego/hooks/actions && mv $ACTION_SAMPLE word-count && chmod +x word-count
        |#
        |# Arguments: ${'$'}1 = the file
        |
        |wc -l -w -c "${'$'}1"
        |
        """.trimMargin()
}
