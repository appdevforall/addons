package com.itsaky.androidide.plugins.aiagentlocal.format

import java.util.Locale

/**
 * Formats byte counts for display.
 *
 * Deliberately binary (÷1024³) and US-locale rather than
 * `android.text.format.Formatter.formatShortFileSize()`, which reports decimal (SI) units: these
 * figures describe device RAM, which every Android surface (Settings, `ActivityManager`, spec
 * sheets) reports in binary units, so a decimal "8.6 GB" for 8 GiB of RAM would read as wrong.
 * Kept out of the backend so the engine has no formatting logic and this stays unit-testable.
 */
internal object ByteSize {

    private const val BYTES_PER_MB = 1024.0 * 1024.0
    private const val BYTES_PER_GB = BYTES_PER_MB * 1024.0

    /**
     * Formats [bytes] with the largest unit that keeps the figure meaningful. Sub-gigabyte values
     * fall back to MB: a device with 12 MB free must not be described as having "0.0 GB", which
     * reads as a broken string rather than as a memory shortage.
     *
     * @param bytes a byte count
     * @return the size as a one-decimal "X.X GB" or "X.X MB" string
     */
    fun format(bytes: Long): String =
        if (bytes >= BYTES_PER_GB) String.format(Locale.US, "%.1f GB", bytes / BYTES_PER_GB)
        else String.format(Locale.US, "%.1f MB", bytes / BYTES_PER_MB)
}
