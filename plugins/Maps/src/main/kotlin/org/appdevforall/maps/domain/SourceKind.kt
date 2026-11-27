package org.appdevforall.maps.domain

/**
 * Source kind written into `meta.json.source.kind`. A pure value type so the
 * source picker fragment + bbox picker can pass it through their listener
 * interfaces without leaking the internal data-layer downloader.
 */
enum class SourceKind(val wireValue: String) {
    IIAB_LAN("iiab-lan"),
    INTERNET("internet"),
    UNKNOWN("unknown"),
}
