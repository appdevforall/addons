package org.appdevforall.customidebuilder.core

object Naming {
    /**
     * `<host label>-<version name>-custom.apk`, with every run of characters outside letters,
     * digits, dot, underscore and dash turned into one dash, so the name has no spaces.
     */
    fun outputName(hostLabel: String, versionName: String?): String {
        val parts = listOf(hostLabel, versionName.orEmpty(), "custom").map { clean(it) }.filter { it.isNotEmpty() }
        return parts.joinToString("-").take(120) + ".apk"
    }

    private fun clean(text: String): String =
        text.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.')
}
