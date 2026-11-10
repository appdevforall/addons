package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.Reading
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.SysFiles

/** Reads `MemTotal` and `MemAvailable` from `/proc/meminfo` ([MEMINFO]) through [files]; null when either is missing. */
class ProcMemoryProbe(private val files: SysFiles) : MemoryProbe {
    override fun read(): MemInfo? {
        val text = (files.readText(MEMINFO) as? Reading.Value)?.value ?: return null
        val total = kb(text, "MemTotal") ?: return null
        val avail = kb(text, "MemAvailable") ?: return null
        return MemInfo(total, avail)
    }

    private fun kb(text: String, key: String): Long? =
        text.lines().firstNotNullOfOrNull { Regex("$key:\\s+(\\d{1,15})\\s*kB").matchEntire(it.trim())?.groupValues?.get(1)?.toLong() }

    companion object {
        const val MEMINFO = "/proc/meminfo"
    }
}

/** The memory guard of plan 3.5: `L = min(55 % of MemTotal, 70 % of MemAvailable)`, in MiB, read just before each row. */
object MemoryGuard {
    /** [info] in kB to the limit in MiB (rounded down); at least 1 so that a typed builder accepts it. */
    fun limitMib(info: MemInfo): Long {
        val byTotal = info.totalKb * QuickLimits.MEM_TOTAL_PERCENT / 100
        val byAvail = info.availKb * QuickLimits.MEM_AVAIL_PERCENT / 100
        return maxOf(1L, minOf(byTotal, byAvail) / 1024)
    }
}
