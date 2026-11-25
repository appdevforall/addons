package org.appdevforall.customidebuilder.core

/** Decides whether the name and icon the user entered may go into a build, and says what is missing in words for the screen. */
object BrandingGate {
    const val CONFIRMATION = "This name and icon are my own, and not Code on the Go's"

    /** True when the user chose a name or an icon; with neither, the copy keeps Code on the Go's own and nothing here applies. */
    fun isCustom(name: String, hasIcon: Boolean): Boolean = name.isNotBlank() || hasIcon

    /** Everything that stops the build, empty when it may go ahead. [iconVerdict] is null while a chosen picture is still being checked. */
    fun problems(name: String, hasIcon: Boolean, iconVerdict: IconVerdict?, confirmed: Boolean): List<String> {
        if (!isCustom(name, hasIcon)) return emptyList()
        val out = ArrayList<String>()
        if (name.isNotBlank()) {
            val v = AppNameCheck.check(name)
            if (v is NameVerdict.Rejected) out.add("Name: ${v.reason}")
        }
        if (hasIcon) {
            when (iconVerdict) {
                null -> out.add("Icon: wait until the picture has been checked.")
                is IconVerdict.TooSimilar -> out.add("Icon: this picture is too close to Code on the Go's own icon. Choose a picture of your own.")
                is IconVerdict.Different, is IconVerdict.Unchecked -> Unit
            }
        }
        if (!confirmed) out.add("Tick \"$CONFIRMATION\" to build with your own name or icon.")
        return out
    }

    /** The name to write: the checked, trimmed text, or null when the field is blank (keep the host's). Call only when [problems] is empty. */
    fun nameToWrite(name: String): String? = if (name.isBlank()) null else (AppNameCheck.check(name) as NameVerdict.Ok).name
}
