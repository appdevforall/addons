package org.appdevforall.maps.data

import org.json.JSONException
import org.json.JSONObject

/**
 * Reads IIAB's `maps-catalog.json`, the upstream list of the current map archives on the
 * `iiab.switnet.org` mirror. IIAB's own region extractor (`tile-extract.py.j2`) picks its files
 * from the same catalog.
 */
internal object IiabCatalog {

    /** The catalog IIAB's `maps_catalog_url` default points at. */
    const val CATALOG_URL =
        "https://raw.githubusercontent.com/iiab/iiab/refs/heads/master/roles/maps/maps-catalog.json"

    /** The mirror directory every archive in the catalog lives under. */
    const val MIRROR_BASE = "https://iiab.switnet.org/maps/2/"

    /**
     * Used when the catalog cannot be read, and always for an IIAB LAN box. The catalog's z00-z14
     * archive as of 2026-10-27.
     */
    const val FALLBACK_VECTOR_ARCHIVE = "openstreetmap-openmaptiles.2026-10-01.z00-z14.pmtiles"

    /**
     * The archive names a LAN box may hold, newest first. A box keeps the build it was installed
     * with, and boxes installed before IIAB's 2026-10-06 update hold the 2026-04-01 one.
     */
    val LAN_VECTOR_ARCHIVES =
        listOf(FALLBACK_VECTOR_ARCHIVE, "openstreetmap-openmaptiles.2026-04-01.z00-z14.pmtiles")

    private val VECTOR_ARCHIVE =
        Regex("""openstreetmap-openmaptiles\.(\d{4}-\d{2}-\d{2})\.z00-z14\.pmtiles""")

    /**
     * The zoom 0-14 vector archive's file name in [catalogJson], or null when the entry is missing,
     * is not on [MIRROR_BASE], or is not a dated `z00-z14` OpenMapTiles name.
     */
    fun vectorArchive(catalogJson: String): String? {
        val url =
            try {
                JSONObject(catalogJson).getJSONObject("data").getJSONObject("vector").getString("14")
            } catch (e: JSONException) {
                return null
            }
        if (!url.startsWith(MIRROR_BASE)) return null
        return url.removePrefix(MIRROR_BASE).takeIf { VECTOR_ARCHIVE.matches(it) }
    }

    /** The `yyyy-mm-dd` date in a vector archive name, or null if [archive] is not one. */
    fun dateOf(archive: String): String? = VECTOR_ARCHIVE.matchEntire(archive)?.groupValues?.get(1)
}
