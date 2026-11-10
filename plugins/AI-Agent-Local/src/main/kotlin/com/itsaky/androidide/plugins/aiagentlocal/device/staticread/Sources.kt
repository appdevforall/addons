package com.itsaky.androidide.plugins.aiagentlocal.device.staticread

/** A value read from the system, or the reason it could not be read. Reads never guess and never throw. */
sealed class Reading<out T> {
    class Value<T>(val value: T) : Reading<T>()
    class Unavailable(val reason: String) : Reading<Nothing>()
}

/** Plain file reads (sysfs, procfs, `/vendor/etc`). */
interface SysFiles {
    /** The text of the file (at most 64 KiB), or the reason it cannot be read. */
    fun readText(path: String): Reading<String>
}

/** The `android.os.Build` fields the T0 read uses. A field the system does not give is null. Nothing identifying a person or a device beyond the build. */
interface BuildSource {
    val board: String?
    val device: String?
    val hardware: String?
    val socModel: String?

    /** `Build.FINGERPRINT`: stored only in the plugin's own files, never logged. */
    val fingerprint: String?
    val sdkInt: Int
    val supportedAbis: List<String>
}

/** `ActivityManager.MemoryInfo`. */
class MemoryFacts(val totalBytes: Long, val availBytes: Long, val lowMemory: Boolean, val thresholdBytes: Long)

interface MemorySource {
    fun read(): Reading<MemoryFacts>
}

/** `PowerManager` thermal status (0 NONE .. 6 SHUTDOWN) and headroom (NaN means the platform has no forecast). */
interface ThermalSource {
    fun status(): Reading<Int>
    fun headroom(): Reading<Double>
}

/** The battery state from the sticky battery broadcast. */
class BatteryFacts(val levelPct: Int?, val charging: Boolean?, val temperatureC: Double?)

interface BatterySource {
    fun read(): Reading<BatteryFacts>
}

/** The default display. */
class DisplayFacts(val refreshHz: Double?, val widthPx: Int?, val heightPx: Int?)

interface DisplaySource {
    fun read(): Reading<DisplayFacts>
}

interface ScreenSource {
    fun isScreenOn(): Reading<Boolean>
}
