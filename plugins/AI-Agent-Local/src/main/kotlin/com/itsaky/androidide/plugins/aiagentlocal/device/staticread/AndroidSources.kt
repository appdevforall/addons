package com.itsaky.androidide.plugins.aiagentlocal.device.staticread

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.view.Display
import java.io.File
import java.io.FileInputStream

/*
 * The real implementations of the source interfaces. They are not covered by JVM tests (they need a device); the
 * instrumented test `Part1DeviceInstrumentedTest` builds them. Each call is wrapped by the reader, so an exception here becomes an
 * `unavailable` entry. None of them reads a serial number, Android ID, IMEI, account, IP address or MAC address.
 */

/** Reads plain files with a 64 KiB cap. The reason of a failure names the exception class only. */
class JavaSysFiles : SysFiles {
    override fun readText(path: String): Reading<String> = try {
        val f = File(path)
        FileInputStream(f).use { input ->
            val buf = ByteArray(MAX_BYTES + 1)
            var n = 0
            while (n < buf.size) {
                val r = input.read(buf, n, buf.size - n)
                if (r < 0) break
                n += r
            }
            if (n > MAX_BYTES) Reading.Unavailable("the file is larger than $MAX_BYTES bytes") else Reading.Value(String(buf, 0, n, Charsets.UTF_8))
        }
    } catch (e: Exception) {
        Reading.Unavailable("not readable (${e.javaClass.simpleName})")
    }

    private companion object {
        const val MAX_BYTES = 64 * 1024
    }
}

/** `android.os.Build`. */
class AndroidBuildSource : BuildSource {
    override val board: String? get() = Build.BOARD
    override val device: String? get() = Build.DEVICE
    override val hardware: String? get() = Build.HARDWARE
    override val socModel: String? get() = Build.SOC_MODEL
    override val socManufacturer: String? get() = Build.SOC_MANUFACTURER
    override val fingerprint: String? get() = Build.FINGERPRINT
    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    override val supportedAbis: List<String> get() = Build.SUPPORTED_ABIS.toList()
}

class AndroidMemorySource(private val context: Context) : MemorySource {
    override fun read(): Reading<MemoryFacts> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return Reading.Unavailable("no ActivityManager")
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return Reading.Value(MemoryFacts(info.totalMem, info.availMem, info.lowMemory, info.threshold))
    }
}

class AndroidThermalSource(private val context: Context) : ThermalSource {
    private fun pm(): PowerManager? = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    override fun status(): Reading<Int> = pm()?.let { Reading.Value(it.currentThermalStatus) } ?: Reading.Unavailable("no PowerManager")

    /** `getThermalHeadroom(0)`: the forecast for now; NaN when the device has none. */
    override fun headroom(): Reading<Double> = pm()?.let { Reading.Value(it.getThermalHeadroom(0).toDouble()) } ?: Reading.Unavailable("no PowerManager")
}

class AndroidBatterySource(private val context: Context) : BatterySource {
    override fun read(): Reading<BatteryFacts> {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return Reading.Unavailable("no battery broadcast")
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return Reading.Value(
            BatteryFacts(
                levelPct = if (level >= 0 && scale > 0) (level * 100) / scale else null,
                charging = if (status < 0) null else status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
                temperatureC = if (temp == Int.MIN_VALUE) null else temp / 10.0,
            ),
        )
    }
}

class AndroidDisplaySource(private val context: Context) : DisplaySource {
    override fun read(): Reading<DisplayFacts> {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return Reading.Unavailable("no DisplayManager")
        val d = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return Reading.Unavailable("no default display")
        val mode = d.mode
        return Reading.Value(DisplayFacts(d.refreshRate.toDouble(), mode.physicalWidth, mode.physicalHeight))
    }
}

class AndroidScreenSource(private val context: Context) : ScreenSource {
    override fun isScreenOn(): Reading<Boolean> =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.let { Reading.Value(it.isInteractive) } ?: Reading.Unavailable("no PowerManager")
}

/** True on the main thread: the argument of the `onMainThread` parameters of the runner and the reader. */
fun isMainThread(): Boolean = Looper.getMainLooper()?.thread === Thread.currentThread()
