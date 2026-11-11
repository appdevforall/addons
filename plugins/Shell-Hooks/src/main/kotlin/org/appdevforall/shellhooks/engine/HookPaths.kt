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
     */
    fun discover(
        root: File,
        hookId: String,
    ): List<File> {
        val scripts = mutableListOf<File>()
        File(root, hookId).takeIf(::isRunnable)?.let(scripts::add)
        File(root, "$hookId.d").listFiles()
            ?.filter(::isRunnable)
            ?.sortedBy { it.name }
            ?.let(scripts::addAll)
        return scripts
    }

    /** The actions offered on a file's menu, sorted by name. */
    fun actions(actionsDir: File): List<File> =
        actionsDir.listFiles()?.filter(::isRunnable)?.sortedBy { it.name }.orEmpty()

    /**
     * A regular file with its executable bit set. Samples, dotfiles (editor swap files) and
     * `name~` backups are skipped, so editing a hook in place never runs a half-written copy.
     */
    fun isRunnable(file: File): Boolean {
        val name = file.name
        if (name.startsWith(".") || name.endsWith("~") || name.endsWith(SAMPLE_SUFFIX)) return false
        return file.isFile && file.canExecute()
    }
}
