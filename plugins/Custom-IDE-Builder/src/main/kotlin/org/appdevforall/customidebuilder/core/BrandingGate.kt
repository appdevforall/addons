package org.appdevforall.customidebuilder.core

/**
 * Decides whether the name and icon the user entered may go into a build, and says what is missing in words
 * for the screen. Every copy needs its own name, its own icon and the ownership confirmation.
 */
object BrandingGate {
    const val CONFIRMATION = "This name and icon are my own, and not Code on the Go's"

    /** Everything that stops the build, empty when it may go ahead. [iconVerdict] is null while a chosen picture is still being checked. */
    fun problems(name: String, hasIcon: Boolean, iconVerdict: IconVerdict?, confirmed: Boolean): List<String> {
        val out = ArrayList<String>()
        if (name.isBlank()) {
            out.add("Name: enter a name of your own for the copy.")
        } else {
            val v = AppNameCheck.check(name)
            if (v is NameVerdict.Rejected) out.add("Name: ${v.reason}")
        }
        if (!hasIcon) {
            out.add("Icon: choose a picture of your own for the copy.")
        } else {
            when (iconVerdict) {
                null -> out.add("Icon: wait until the picture has been checked.")
                is IconVerdict.TooSimilar -> out.add("Icon: this picture is too close to Code on the Go's own icon. Choose a picture of your own.")
                is IconVerdict.Different, is IconVerdict.Unchecked -> Unit
            }
        }
        if (!confirmed) out.add("Tick \"$CONFIRMATION\" to build.")
        return out
    }

    /** The name to write: the checked, trimmed text. Call only when [problems] is empty. */
    fun nameToWrite(name: String): String = (AppNameCheck.check(name) as NameVerdict.Ok).name
}
