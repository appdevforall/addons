package org.appdevforall.shellhooks.engine

import java.io.File

/**
 * Where hooks live. [appFilesDir] is the IDE's files directory, the root its command service
 * gives every process: `HOME` is `<appFilesDir>/home` and `PREFIX` is `<appFilesDir>/usr`, the
 * same folders the Terminal uses, so `~/.config/codeonthego/hooks` here is the one a user edits
 * there.
 */
class HookPaths(
    appFilesDir: File,
) {
    val home = File(appFilesDir, "home")
    val prefix = File(appFilesDir, "usr")

    /** The user's own hooks: `~/.config/codeonthego/hooks`. */
    val globalHooks = File(home, ".config/codeonthego/hooks")

    /** Scripts offered as "Run <name>" on a file's menu. */
    val actions = File(globalHooks, ACTIONS_DIR)

    companion object {
        const val ACTIONS_DIR = "actions"
        const val PROJECT_HOOKS = ".codeonthego/hooks"

        /** A project's own hooks, which run only once trusted. */
        fun projectHooks(projectDir: File) = File(projectDir, PROJECT_HOOKS)
    }
}

/** Finds the scripts a hook runs, git-hooks style. */
object HookDiscovery {
    const val SAMPLE_SUFFIX = ".sample"

    /**
     * The scripts in [root] for [hookId]: the file `<hookId>`, then every script in
     * `<hookId>.d/` in byte order of their names (as `LC_ALL=C ls` sorts them).
     *
     * [requireExecutable] is false for a project's hooks: projects usually live on shared storage,
     * which is mounted noexec and keeps no executable bits, and a trusted project script runs from
     * a private copy the plugin marks executable itself.
     */
    fun discover(
        root: File,
        hookId: String,
        requireExecutable: Boolean = true,
    ): List<File> {
        val scripts = mutableListOf<File>()
        File(root, hookId).takeIf { isRunnable(it, requireExecutable) }?.let(scripts::add)
        File(root, "$hookId.d").listFiles()
            ?.filter { isRunnable(it, requireExecutable) }
            ?.sortedBy { it.name }
            ?.let(scripts::addAll)
        return scripts
    }

    /** The actions offered on a file's menu, sorted by name. */
    fun actions(actionsDir: File): List<File> =
        actionsDir.listFiles()?.filter { isRunnable(it) }?.sortedBy { it.name }.orEmpty()

    /**
     * The actions to offer for [target]: none for a folder, which the file tree's long-press menu
     * also asks about, since an action's `$1` is a file.
     */
    fun actionsFor(
        target: File,
        actionsDir: File,
    ): List<File> = if (target.isFile) actions(actionsDir) else emptyList()

    /**
     * A regular file, with its executable bit set when [requireExecutable]. Samples, dotfiles
     * (editor swap files) and `name~` backups are skipped, so editing a hook in place never runs a
     * half-written copy.
     */
    fun isRunnable(
        file: File,
        requireExecutable: Boolean = true,
    ): Boolean {
        val name = file.name
        if (name.startsWith(".") || name.endsWith("~") || name.endsWith(SAMPLE_SUFFIX)) return false
        return file.isFile && (!requireExecutable || file.canExecute())
    }
}
