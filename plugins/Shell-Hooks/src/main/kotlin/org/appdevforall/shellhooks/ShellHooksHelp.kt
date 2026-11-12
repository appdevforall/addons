package org.appdevforall.shellhooks

import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry

/**
 * The plugin's in-IDE help. Every tag here is attached to a UI element; each entry's button opens
 * the full guide, `assets/docs/index.html`.
 */
object ShellHooksHelp {
    const val TAG_TAB = "shellhooks.tab"
    const val TAG_SETTINGS = "shellhooks.settings"
    const val TAG_FOLDER = "shellhooks.folder"
    const val TAG_HOOKS = "shellhooks.hooks"
    const val TAG_TIMEOUT = "shellhooks.timeout"
    const val TAG_PROJECT_HOOKS = "shellhooks.project_hooks"
    const val TAG_REVIEW = "shellhooks.review"
    const val TAG_ACTION = "shellhooks.action"
    const val TAG_BACK = "shellhooks.back"

    private val guide = listOf(PluginTooltipButton(description = "Shell Hooks guide", uri = "index.html", order = 0))

    fun entries(): List<PluginTooltipEntry> =
        listOf(
            PluginTooltipEntry(
                tag = TAG_TAB,
                summary = "What your hook scripts did: each run's exit code and output.",
                detail =
                    """
                    <p>Lists the last 100 hook runs, newest first. Tap a run to see what the script printed.</p>
                    <p>Scripts stored in a project wait here for your review before they run.</p>
                    """.trimIndent(),
                buttons = guide,
            ),
            PluginTooltipEntry(
                tag = TAG_SETTINGS,
                summary = "Runs your shell scripts when the IDE opens a project, saves a file, or builds.",
                detail =
                    """
                    <p>Off until you turn it on. Put an executable script named after a hook, such as
                    <code>file-saved</code>, in your hooks folder. It gets the file or project in <code>$1</code>.</p>
                    <p>Hooks only report what happened: a script cannot stop a save or a build.</p>
                    """.trimIndent(),
                buttons = guide,
            ),
            PluginTooltipEntry(
                tag = TAG_FOLDER,
                summary = "Your hooks live in ~/.config/codeonthego/hooks.",
                detail =
                    """
                    <p>For each hook, the script <code>&lt;hook&gt;</code> runs first, then every script in
                    <code>&lt;hook&gt;.d/</code> in name order. Only executable files run
                    (<code>chmod +x</code>); <code>.sample</code> files never do.</p>
                    <p>Scripts in <code>actions/</code> appear as <b>Run &lt;name&gt;</b> on a file's menu.</p>
                    """.trimIndent(),
                buttons = guide,
            ),
            PluginTooltipEntry(
                tag = TAG_HOOKS,
                summary = "Turn single hooks off without deleting their scripts.",
                detail =
                    """
                    <p>Each switch is one hook; under it is what the script gets in <code>$1</code>. A hook that
                    is off runs none of its scripts, yours or a project's. The scripts stay where they are.</p>
                    <p><code>ide-started</code> runs once in each launch of Code on the Go, the first time Shell
                    Hooks starts with hooks on.</p>
                    """.trimIndent(),
                buttons = guide,
            ),
            PluginTooltipEntry(
                tag = TAG_TIMEOUT,
                summary = "A script still running after this many seconds is stopped.",
                detail =
                    """
                    <p>Scripts run one at a time, so a slow one delays the next. With the Terminal's
                    <code>timeout</code> installed, the programs a script started are stopped with it.
                    Run long jobs in the background: <code>nohup long-job &gt;/dev/null 2&gt;&amp;1 &amp;</code>.
                    Without the redirect, the job keeps the script's output open and the script counts as
                    still running.</p>
                    """.trimIndent(),
                buttons = guide,
            ),
            PluginTooltipEntry(
                tag = TAG_PROJECT_HOOKS,
                summary = "Scripts stored in a project run only after you review and trust them.",
                detail =
                    """
                    <p>A project can carry hooks in <code>.codeonthego/hooks/</code>. A script can arrive with a
                    project, from a cloned repository or an AI agent's edit, so none runs until you read it in
                    the Hooks tab and trust it. Editing a trusted script makes it untrusted again.</p>
                    <p>Only plain scripts can be trusted: at most 64 KB of printable ASCII and newlines, no tabs,
                    no line over 100 characters or ending in a space, and a name with no part starting with a
                    dot. Anything else never runs.</p>
                    <p>Trust covers the script's own text, not project files or settings it uses, such as
                    <code>.git/config</code> or Gradle and npm files.</p>
                    <p>This does not protect you from a plugin that can run commands: such a plugin can already
                    change your own hooks folder. Install only plugins you trust.</p>
                    """.trimIndent(),
                buttons = guide,
            ),
            PluginTooltipEntry(
                tag = TAG_REVIEW,
                summary = "Read the script before you trust it: it runs with the Terminal's access.",
                detail =
                    """
                    <p><b>Trust</b> approves exactly the text shown, one numbered row per line; a row marked
                    &#x21B3; continues the line above it. If the file changes afterwards it waits for review
                    again.</p>
                    <p>Only a plain script can be reviewed: at most 64 KB of printable ASCII and newlines, with
                    no tabs, carriage returns or other characters, no line over 100 characters or ending in a
                    space, and a name with no part starting with a dot. Those could make a script run
                    differently from how it reads, so a script that has them never runs. It is listed with
                    the reason, except a name starting with a dot, which is skipped and not listed.</p>
                    <p>You see every character, but reviewing is reading code: read it as the shell will. A
                    backslash ending a line of code usually joins the next line to it, and a
                    <code>#</code> starting that line may then be no comment (a backslash that ends a
                    comment, or is escaped by another backslash, joins nothing); a here-document ends at a
                    line matching its delimiter, which may not be the one you expect; backticks, or
                    <code>$(</code> in the text of an unquoted here-document, can run commands.</p>
                    <p>A best-effort check, not a guarantee, marks <b>!</b> every line that contains
                    <code>&lt;&lt;</code>, a backtick or <code>$(</code>, or ends in a backslash, and lists
                    them above the script by kind. It judges each line on its own, in comments and strings
                    too, so it marks many harmless lines, and it misses every other kind of trick: a line
                    without a mark can still do anything. Read every line.</p>
                    <p>Trust covers only this script: not files it runs from the project, nor settings tools
                    read by themselves, such as hooks in <code>.git/config</code> or Gradle and npm files.</p>
                    """.trimIndent(),
                buttons = guide,
            ),
            PluginTooltipEntry(
                tag = TAG_ACTION,
                summary = "Runs this script from your actions folder on the file, in the Terminal.",
                detail =
                    """
                    <p>Every executable script in <code>~/.config/codeonthego/hooks/actions/</code> appears here,
                    for files only. It runs in the Terminal so you see what it prints, with the file in
                    <code>$1</code>. If it cannot start, the Hooks tab says why.</p>
                    """.trimIndent(),
                buttons = guide,
            ),
            PluginTooltipEntry(
                tag = TAG_BACK,
                summary = "Back to Preferences. Your changes are already saved.",
                buttons = guide,
            ),
        )
}
