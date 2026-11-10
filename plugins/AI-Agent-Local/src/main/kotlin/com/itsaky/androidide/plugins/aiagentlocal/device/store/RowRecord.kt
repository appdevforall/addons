package com.itsaky.androidide.plugins.aiagentlocal.device.store

import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.ConditionsSnapshot

/**
 * Completes the row record the engine wrote with the conditions only the app knows (ENGINE.md, "Omitted (only the app
 * knows)": thermal status and headroom, battery level and charging, display, screen state, start temperature), read before the row
 * started. Values the engine already wrote are kept; a value that was not read stays absent (the generator then records
 * `null`). The record is not changed: a new tree is returned.
 */
object RowRecord {
    fun withConditions(row: Map<String, Any?>, c: ConditionsSnapshot): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val existing = (row["conditions"] as? Map<String, Any?>) ?: emptyMap()
        val merged = LinkedHashMap(existing)
        fun put(key: String, value: Any?) {
            if (value != null && merged[key] == null) merged[key] = if (value is Int) value.toLong() else value
        }
        put("thermal_status", c.thermalStatus)
        put("thermal_headroom", c.thermalHeadroom)
        put("battery_pct", c.batteryPct)
        put("charging", c.charging)
        put("display_refresh_hz", c.displayRefreshHz)
        put("display_px", c.displayPx)
        put("screen_on", c.screenOn)
        put("start_temp_c", c.batteryTempC)
        return LinkedHashMap(row).also { it["conditions"] = merged }
    }
}
