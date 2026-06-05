package org.appdevforall.maps.util

/**
 * Human-readable byte-size formatting shared by `RegionAdapter`
 * (per-row size) and `RegionManagerFragment` (cache + free footer).
 *
 * Picks the smallest unit that doesn't round to "0.0":
 *  - `< 1 KB` → bytes
 *  - `< 1 MB` → KB with one decimal
 *  - `< 1 GB` → MB with one decimal
 *  - `>= 1 GB` → GB with two decimals
 *
 * Internal to the plugin — only the two callers above consume it.
 */
internal fun formatByteSize(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}
