package com.itsaky.androidide.plugins.aiagentlocal.device.staticread

/**
 * The conditions a row or the static read records: thermal status and headroom, battery level, charging and temperature,
 * display refresh rate and resolution, screen state. A value that could not be read is null and its reason is in
 * [unavailable] under `environment.<field>`.
 */
class ConditionsSnapshot(
    val thermalStatus: Int?,
    val thermalHeadroom: Double?,
    val batteryPct: Int?,
    val charging: Boolean?,
    val batteryTempC: Double?,
    val displayRefreshHz: Double?,
    val displayPx: String?,
    val screenOn: Boolean?,
    val unavailable: Map<String, String>,
)

/** Reads [ConditionsSnapshot]; every Android call sits behind a source interface and a failure becomes an `unavailable` entry. */
class ConditionsReader(
    private val thermal: ThermalSource,
    private val battery: BatterySource,
    private val display: DisplaySource,
    private val screen: ScreenSource,
) {
    fun read(): ConditionsSnapshot {
        val un = LinkedHashMap<String, String>()
        fun <T> take(key: String, call: () -> Reading<T>): T? = when (val r = guard(call)) {
            is Reading.Value -> r.value
            is Reading.Unavailable -> { un[key] = r.reason; null }
        }

        val status = take("environment.thermal_status") { thermal.status() }
        val headroom = take("environment.thermal_headroom") { thermal.headroom() }?.let {
            if (it.isNaN() || it.isInfinite()) {
                un["environment.thermal_headroom"] = "the thermal headroom API returned NaN (no forecast on this device)"
                null
            } else {
                it
            }
        }
        val bat = take("environment.battery") { battery.read() }
        if (bat != null) {
            if (bat.levelPct == null) un["environment.battery_pct"] = "the battery broadcast has no level"
            if (bat.charging == null) un["environment.charging"] = "the battery broadcast has no charging state"
            if (bat.temperatureC == null) un["environment.battery_temp_c"] = "the battery broadcast has no temperature"
        } else {
            un["environment.battery"]?.let { reason ->
                un["environment.battery_pct"] = reason
                un["environment.charging"] = reason
                un.remove("environment.battery")
            }
        }
        val disp = take("environment.display") { display.read() }
        var px: String? = null
        if (disp != null) {
            if (disp.refreshHz == null) un["environment.display_refresh_hz"] = "the display gave no refresh rate"
            if (disp.widthPx == null || disp.heightPx == null) {
                un["environment.display_px"] = "the display gave no resolution"
            } else {
                px = "${disp.widthPx}x${disp.heightPx}"
            }
        } else {
            un["environment.display"]?.let { reason ->
                un["environment.display_refresh_hz"] = reason
                un["environment.display_px"] = reason
                un.remove("environment.display")
            }
        }
        val screenOn = take("environment.screen_on") { screen.isScreenOn() }
        return ConditionsSnapshot(
            thermalStatus = status,
            thermalHeadroom = headroom,
            batteryPct = bat?.levelPct,
            charging = bat?.charging,
            batteryTempC = bat?.temperatureC,
            displayRefreshHz = disp?.refreshHz,
            displayPx = px,
            screenOn = screenOn,
            unavailable = un,
        )
    }

    private fun <T> guard(call: () -> Reading<T>): Reading<T> = try {
        call()
    } catch (e: Exception) {
        Reading.Unavailable("the read failed (${e.javaClass.simpleName})")
    }
}
