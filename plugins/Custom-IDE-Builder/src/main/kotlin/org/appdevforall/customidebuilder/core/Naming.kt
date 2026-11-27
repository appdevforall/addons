package org.appdevforall.customidebuilder.core

object Naming {
    /** The longest name stem kept, before the `.apk`. */
    private const val MAX_STEM = 120

    private const val EXTENSION = ".apk"

    /**
     * `<host label>-<version name>-custom.apk`, with every run of characters outside letters,
     * digits, dot, underscore and dash turned into one dash, so the name has no spaces.
     */
    fun outputName(hostLabel: String, versionName: String?): String {
        val parts = listOf(hostLabel, versionName.orEmpty(), "custom").map { clean(it) }.filter { it.isNotEmpty() }
        return parts.joinToString("-").take(MAX_STEM) + EXTENSION
    }

    /**
     * A name the user typed, cleaned the way [outputName] cleans its parts and ending in `.apk`
     * (a typed `.apk`, in any case, is not doubled). Null when nothing usable is left.
     */
    fun fromEntry(text: String): String? {
        val typed = text.trim()
        val stem = clean(if (typed.endsWith(EXTENSION, ignoreCase = true)) typed.dropLast(EXTENSION.length) else typed).take(MAX_STEM).trim('-', '.')
        return if (stem.isEmpty()) null else stem + EXTENSION
    }

    /** Whether [name] is among [existing]. File systems behind the folder picker may ignore case, so this does too. */
    fun isTaken(name: String, existing: Collection<String>): Boolean = existing.any { it.equals(name, ignoreCase = true) }

    /**
     * [wanted] if it is free, otherwise `<stem>-2.apk`, `<stem>-3.apk` and so on, the first that is
     * free in [existing]. A dash and a number rather than the provider's own " (1)", so the
     * suggestion is never mistaken for a name the provider chose.
     */
    fun freeName(wanted: String, existing: Collection<String>): String {
        if (!isTaken(wanted, existing)) return wanted
        val stem = (if (wanted.endsWith(EXTENSION, ignoreCase = true)) wanted.dropLast(EXTENSION.length) else wanted).ifEmpty { "custom" }
        var n = 2
        while (true) {
            val suffix = "-$n"
            val candidate = stem.take(MAX_STEM - suffix.length).trimEnd('-', '.') + suffix + EXTENSION
            if (!isTaken(candidate, existing)) return candidate
            n++
        }
    }

    /**
     * What to add to the Done message when the folder saved the file under another name than
     * [requested] ([actual], null when the folder would not say). Empty when they match.
     */
    fun savedNote(requested: String, actual: String?): String =
        when {
            actual == null -> "The folder did not say what it named the file, so look for $requested in it."
            actual == requested -> ""
            else -> "You asked for $requested, but the folder saved the file as $actual, " +
                "because a file with that name appeared in it at the same moment."
        }

    /** What to add to the final message when a cancelled or failed save left the partial file [name] in [folder] and the provider would not delete it. */
    fun partialLeftNote(name: String, folder: String): String =
        "A partial file named $name remains in $folder and should be deleted by hand."

    private fun clean(text: String): String =
        text.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.')
}
