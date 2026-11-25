package org.appdevforall.customidebuilder.core

/** How big one add-on may be, and how much free space copying or downloading one needs. */
object AddonLimits {
    /**
     * The most one add-on file may hold: 512 MiB. The largest add-on published today is a course
     * archive of about 110 MB, so this leaves room to grow, while a wrong file, or a server that
     * promises more, cannot fill the phone before the build's own space check runs. A build then
     * needs about twice the app plus every add-on (see [Pipeline.spaceNeeded]), which is why the
     * ceiling is per add-on and not larger.
     */
    const val MAX_BYTES = 512L * 1024 * 1024

    /** Free space kept beyond the file being copied or downloaded. */
    const val SPACE_MARGIN = 64L * 1024 * 1024

    private fun megabytes(bytes: Long): Long = (bytes + 1024 * 1024 - 1) / (1024 * 1024)

    /** A user message when an add-on of [size] bytes is over the ceiling, else null. */
    fun sizeProblem(label: String, size: Long): String? =
        if (size > MAX_BYTES) "$label: it is ${megabytes(size)} MB, and this plugin takes add-ons of at most ${megabytes(MAX_BYTES)} MB" else null

    /**
     * A user message when [usableSpace] cannot hold a file of [size] bytes plus [SPACE_MARGIN], else null.
     * A [size] of null (a provider that does not say) is checked against the margin alone, and the
     * copy itself is still stopped at [MAX_BYTES].
     */
    fun spaceProblem(label: String, size: Long?, usableSpace: Long): String? {
        val needed = (size ?: 0L) + SPACE_MARGIN
        if (usableSpace >= needed) return null
        return "$label: not enough free space on this device: about ${megabytes(needed)} MB is needed and ${usableSpace / (1024 * 1024)} MB is free"
    }
}
