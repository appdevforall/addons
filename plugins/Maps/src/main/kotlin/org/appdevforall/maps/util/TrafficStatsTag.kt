package org.appdevforall.maps.util

/**
 * Socket traffic-stats tag for every request the plugin makes. Tagging the thread first keeps
 * StrictMode's UntaggedSocketViolation quiet and attributes the traffic to Maps ("MAPS" in ASCII).
 */
internal const val MAPS_TRAFFIC_STATS_TAG = 0x4D41_5053
