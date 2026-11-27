package org.appdevforall.shellhooks.engine

import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * A one-use launcher for an action in the visible Terminal (ADFA-7334). The Terminal prints the
 * command line it runs, and its plugin API takes no environment, so the variables and paths go
 * into a file the plugin writes in its own folder, and the printed line is only `sh '<launcher>'`.
 *
 * The launcher deletes itself, prints one line naming the action and the file, then replaces
 * itself with the runner, which gets the arguments and environment it would get from a command
 * line. `exec` keeps the Terminal's stdin, so an action can still read what the user types.
 */
object ActionLauncher {
    /** The launchers' folder, under the plugin's data folder. Only launchers live there. */
    const val DIR = "run"

    private const val NAME_LIMIT = 32
    private const val SUFFIX_LENGTH = 5
    private const val ATTEMPTS = 20
    private const val SUFFIX_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val VARIABLE = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /**
     * Writes a launcher in [dir] that runs [runner] on [action] and [target] with [environment],
     * and returns it. The file is always new: an existing one is never written over.
     *
     * @throws IllegalArgumentException for a variable name the shell would not read as one, or a
     *   NUL in any value, before anything is written.
     * @throws IOException if the launcher cannot be written.
     */
    fun write(
        dir: File,
        runner: File,
        action: File,
        target: File,
        environment: Map<String, String>,
        random: Random = Random.Default,
    ): File {
        val text = script(runner, action, target, environment)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        repeat(ATTEMPTS) {
            val file = File(dir, fileName(action.name, random))
            if (file.createNewFile()) {
                try {
                    file.writeText(text)
                } catch (e: IOException) {
                    file.delete()
                    throw e
                }
                return file
            }
        }
        throw IOException("Cannot create a new launcher in $dir")
    }

    /** The launcher's text: POSIX sh, read by the Terminal's `sh` and never printed. */
    fun script(
        runner: File,
        action: File,
        target: File,
        environment: Map<String, String>,
    ): String =
        buildString {
            // When run as `sh FILE`, $0 is FILE. The shell already has it open, so it reads on.
            append("rm -f -- \"\$0\"\n")
            for ((name, value) in environment) {
                require(VARIABLE.matches(name)) { "Not an environment variable name: $name" }
                append("export ").append(name).append('=').append(ShellQuote.posix(value)).append('\n')
            }
            append("printf '%s\\n' ").append(ShellQuote.posix(label(action.name, target.name))).append('\n')
            append("exec sh ")
            append(listOf(runner.path, action.path, target.path).joinToString(" ") { ShellQuote.posix(it) })
            append('\n')
        }

    /**
     * The line the launcher prints: "Run <action> on <file>", the file by name only. Control
     * characters show as `?`, so a name cannot send the terminal an escape sequence.
     */
    fun label(
        actionName: String,
        fileName: String,
    ): String = "Run ${printable(actionName)} on ${printable(fileName)}"

    /** The command line for the Terminal: `sh` and the launcher's path, nothing from the action. */
    fun command(launcher: File): String = ShellQuote.join(listOf("sh", launcher.path))

    /**
     * The launcher's name: the action's name with anything outside `[A-Za-z0-9._-]` as `_`, at
     * most [NAME_LIMIT] characters, then `-` and a random suffix. The printed command line then
     * says which action ran, in plain ASCII.
     */
    fun fileName(
        actionName: String,
        random: Random = Random.Default,
    ): String {
        val base = actionName.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "._-") it else '_' }
            .joinToString("")
            .take(NAME_LIMIT)
            .ifEmpty { "action" }
        val suffix = (1..SUFFIX_LENGTH).map { SUFFIX_CHARS[random.nextInt(SUFFIX_CHARS.length)] }.joinToString("")
        return "$base-$suffix"
    }

    /**
     * Deletes the launchers left in [dir]: by a crash, or by a command the Terminal never took.
     * Called on activation, when none can be pending: the host stops a plugin's Terminal commands
     * when it disables or unloads it.
     */
    fun sweep(dir: File) {
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
    }

    private fun printable(name: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            if (ShellQuote.isControl(cp)) out.append('?') else out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString()
    }
}
