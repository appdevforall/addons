package com.itsaky.androidide.plugins.aiagentlocal.model

import android.app.ActivityManager
import android.content.Context

/**
 * Free RAM, as an interface so the pre-flight check is testable without a device.
 */
fun interface DeviceMemory {

    /**
     * Must be read at the moment of the check, never cached — the user may have just closed apps.
     *
     * @return free RAM in bytes, or null when it cannot be read. Null rather than a negative
     *   sentinel, so "unknown" cannot be confused with the reading of 0 a device under real
     *   pressure will genuinely report.
     */
    fun availableBytes(): Long?
}

/**
 * Reads free RAM from [ActivityManager], which needs no permission.
 *
 * @param contextProvider supplies the Android context, and null before the plugin is initialized
 * @param onReadError reports why a reading could not be taken. The pre-flight only learns that one
 *   could not be, so without this the reason behind a silently skipped check is lost.
 */
class SystemDeviceMemory(
    private val contextProvider: () -> Context?,
    private val onReadError: (Throwable) -> Unit = {},
) : DeviceMemory {

    /** Returns null rather than throwing; the gate treats "unknown" as "don't warn". */
    override fun availableBytes(): Long? = try {
        // applicationContext, so a provider that ever hands back an Activity can't be held here.
        val context = contextProvider()?.applicationContext ?: return null
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        ActivityManager.MemoryInfo().also(manager::getMemoryInfo).availMem
    } catch (e: Exception) {
        onReadError(e)
        null
    }
}
