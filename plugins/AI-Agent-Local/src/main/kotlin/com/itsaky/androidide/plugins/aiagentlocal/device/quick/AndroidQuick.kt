package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentlocal.R
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineProgram
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineRunner
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.JvmProcessLauncher
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.NativeLibEngineLocator
import com.itsaky.androidide.plugins.aiagentlocal.device.match.DeviceIndex
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.AndroidBatterySource
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.AndroidBuildSource
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.AndroidDisplaySource
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.AndroidMemorySource
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.AndroidScreenSource
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.AndroidThermalSource
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.ConditionsReader
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.JavaSysFiles
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.StaticReader
import com.itsaky.androidide.plugins.aiagentlocal.device.staticread.isMainThread
import com.itsaky.androidide.plugins.aiagentlocal.device.store.Part1Store
import com.itsaky.androidide.plugins.base.PluginWindows
import com.itsaky.androidide.plugins.services.BuildStatusListener
import com.itsaky.androidide.plugins.services.IdeBuildService
import com.itsaky.androidide.plugins.services.IdeFeatureFlagService
import com.itsaky.androidide.plugins.services.IdeUIService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/*
 * The real implementations of the seams of the quick tier. They need a device and are not run by the JVM tests; the instrumented test
 * `Part1QuickInstrumentedTest` builds the runtime and runs a whole pass through the real runner. Nothing here logs, shows or stores the build
 * fingerprint or the device key.
 */

object SystemClockImpl : Clock {
    override fun nowMs(): Long = System.nanoTime() / 1_000_000L
    override fun sleepMs(ms: Long) = Thread.sleep(ms)
}

/** A daemon thread that calls back once after the delay, unless closed first. */
class ThreadWatchdog : Watchdog {
    override fun start(ms: Long, onTimeout: () -> Unit): AutoCloseable {
        val t = Thread {
            try {
                Thread.sleep(ms)
                onTimeout()
            } catch (_: InterruptedException) {
                // closed before the limit
            }
        }
        t.isDaemon = true
        t.name = "part1-t1-watchdog"
        t.start()
        return AutoCloseable { t.interrupt() }
    }
}

/**
 * Gate 1: the app's current activity (the host's `IdeUIService`) exists, is not finishing, is at least `RESUMED` when it is a `LifecycleOwner` (the host's AppCompat
 * activities are), and the app's process is in the foreground (`ActivityManager.getMyMemoryState` importance FOREGROUND). The engine
 * checks its own cpuset by itself and refuses with exit 97; the two checks together are the gate.
 */
class AndroidForegroundGate(private val activity: () -> Activity?) : ForegroundGate {
    override fun isForeground(): Boolean = try {
        val a = activity()
        if (a == null || a.isFinishing || a.isDestroyed) {
            false
        } else {
            val info = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(info)
            val resumed = (a as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) ?: true
            resumed && info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * Gate 2 with the host's build-status service (`IdeBuildService`: `isBuildInProgress` is true from `prepareBuild`, which a project sync
 * also goes through, until the build finishes or fails). A listener counts the builds that start. The service does not see indexing. There is no
 * fallback: without the service T1 is not offered ([UnavailableIdeGate]).
 */
class HostQuietIdeGate(private val service: IdeBuildService) : QuietIdeGate, AutoCloseable {
    @Volatile private var starts = 0L
    private val listener = object : BuildStatusListener {
        override fun onBuildStarted() { starts++ }
        override fun onBuildFinished() {}
        override fun onBuildFailed(error: String?) {}
    }

    init {
        service.addBuildStatusListener(listener)
    }

    override fun isBusy(): Boolean = try { service.isBuildInProgress() } catch (e: Exception) { true }
    override fun startsSeen(): Long = starts
    override fun close() = service.removeBuildStatusListener(listener)
}

/** The gate used when the host has no build-status service; the controller never offers T1 then, so it is never asked. Reports busy. */
object UnavailableIdeGate : QuietIdeGate {
    override fun isBusy() = true
    override fun startsSeen() = 0L
}

/**
 * The real [WindowOps] on the current activity: `FLAG_KEEP_SCREEN_ON` on its window, and the question and the progress as dialogs in the
 * plugin's existing dialog style (`MaterialAlertDialogBuilder`, shown through the host's `PluginWindows.showDialog`). All text comes from
 * string resources. Every call is made on the UI thread by [PassWindows].
 */
class AndroidWindowOps(private val strings: Context) : WindowOps<Activity> {
    override fun isUsable(host: Activity) = !host.isFinishing && !host.isDestroyed

    override fun flagIsSet(host: Activity): Boolean =
        host.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0

    override fun setFlag(host: Activity, on: Boolean) {
        val w = host.window ?: return
        if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private class Handle(val dialog: Dialog) : DialogHandle {
        override fun setText(text: String) {
            (dialog as? androidx.appcompat.app.AlertDialog)?.setMessage(text)
        }

        override fun dismiss() = dialog.dismiss()
    }

    override fun showQuestion(host: Activity, onRun: () -> Unit, onSkip: () -> Unit, onDismissed: () -> Unit): DialogHandle? {
        val d = MaterialAlertDialogBuilder(host)
            .setTitle(strings.getString(R.string.part1_dialog_title))
            .setMessage(strings.getString(R.string.part1_dialog_message))
            .setPositiveButton(strings.getString(R.string.part1_dialog_run)) { _, _ -> onRun() }
            .setNegativeButton(strings.getString(R.string.part1_dialog_skip)) { _, _ -> onSkip() }
            .setOnDismissListener { onDismissed() }
            .create()
        return if (PluginWindows.showDialog(d)) Handle(d) else null
    }

    override fun showProgress(host: Activity, text: String, onCancel: () -> Unit): DialogHandle? {
        val d = MaterialAlertDialogBuilder(host)
            .setTitle(strings.getString(R.string.part1_progress_title))
            .setMessage(text)
            .setCancelable(false)
            .setNegativeButton(strings.getString(R.string.part1_cancel)) { _, _ -> onCancel() }
            .create()
        return if (PluginWindows.showDialog(d)) Handle(d) else null
    }
}

/** Posts to the main thread, always through the looper, so that the order of posting is the order of running. */
fun mainThreadPoster(): (Runnable) -> Unit {
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    return { r -> handler.post(r) }
}

/** `FLAG_KEEP_SCREEN_ON` for the duration of a pass, through the shared [PassWindows]; [release] ends the whole cycle. */
class AndroidScreenKeeper(private val windows: PassWindows<Activity>) : ScreenKeeper {
    override fun keepOn() = windows.keepOn()
    override fun release() = windows.close()
}

/**
 * The question and the progress, through the shared [PassWindows] (see there for the order of changes and for a recreated activity). The
 * question returns Skip by itself when the user does not answer within [QuickLimits.ASK_TIMEOUT_MS] or the dialog goes away; with no
 * activity it returns `NO_ACTIVITY`.
 */
class AndroidQuickTierUi(private val strings: Context, private val windows: PassWindows<Activity>) : QuickTierUi {
    override fun ask(): QuickAnswer = windows.ask()

    override fun onCancel(handler: () -> Unit) = windows.onCancel(handler)

    override fun showProgress(progress: Progress) {
        val what = when (progress.label) {
            RowLabel.PIN_PROBE -> strings.getString(R.string.part1_row_pin_probe)
            RowLabel.CLUSTER_READ -> strings.getString(R.string.part1_row_cluster_read, (progress.cluster ?: 0) + 1)
            RowLabel.TWO_FAST_READ -> strings.getString(R.string.part1_row_two_fast)
            RowLabel.ALL_READ -> strings.getString(R.string.part1_row_all)
            RowLabel.GPU_READ -> strings.getString(R.string.part1_row_gpu)
        }
        windows.progress(strings.getString(R.string.part1_progress_row, progress.row, progress.total, what))
    }

    override fun showWaiting(what: WaitingFor) {
        windows.progress(
            strings.getString(
                when (what) {
                    WaitingFor.IDE_QUIET -> R.string.part1_waiting_ide
                    WaitingFor.THERMAL -> R.string.part1_waiting_thermal
                    WaitingFor.REST -> R.string.part1_waiting_rest
                },
            ),
        )
    }

    override fun close() = windows.close()
}

/** Builds the real Part 1 runtime from the plugin context. Called by [Part1Controller] only when the switch is on. */
object AndroidPart1Runtime {

    /** The switch: the host's Experiments flag, then the plugin preference. */
    fun switchOf(context: PluginContext, preference: () -> Boolean): Part1Switch = Part1Switch.of(
        experiments = { context.services.get(IdeFeatureFlagService::class.java)?.isExperimentsEnabled() == true },
        preference = preference,
    )

    fun create(context: PluginContext): Part1Runtime {
        val app = context.androidContext
        val pluginData = context.getPluginDataDir()
        val store = Part1Store(File(pluginData, "part1"))
        val locator = NativeLibEngineLocator.forClassLoader(AndroidPart1Runtime::class.java.classLoader!!, File(app.applicationInfo.dataDir), context.pluginId)
        val runner = EngineRunner(locator, JvmProcessLauncher(), File(pluginData, "part1-work"), onMainThread = ::isMainThread)
        val files = JavaSysFiles()
        val conditions = ConditionsReader(AndroidThermalSource(app), AndroidBatterySource(app), AndroidDisplaySource(app), AndroidScreenSource(app))
        val reader = StaticReader(runner, files, AndroidBuildSource(), AndroidMemorySource(app), conditions, onMainThread = ::isMainThread)
        val activity: () -> Activity? = { context.services.get(IdeUIService::class.java)?.getCurrentActivity() }
        val version = runCatching { context.getPluginVersion(context.pluginId) }.getOrNull()
        val closers = ArrayList<AutoCloseable>()

        val buildService = context.services.get(IdeBuildService::class.java)
        // Without the host's build-status service a quiet IDE cannot be known: T1 is then not offered (deferred, ide_gate_unavailable)
        val ide: QuietIdeGate = if (buildService != null) HostQuietIdeGate(buildService).also { closers.add(it) } else UnavailableIdeGate
        val poster = mainThreadPoster()
        val newTier = {
            val windows = PassWindows(activity, poster, AndroidWindowOps(app))
            QuickTier(
                engine = runner, store = store, index = { DeviceIndex.loadFromAssets { app.assets.open(it) } },
                foreground = AndroidForegroundGate(activity), ide = ide, conditions = { conditions.read() },
                memory = ProcMemoryProbe(files), screen = AndroidScreenKeeper(windows), ui = AndroidQuickTierUi(app, windows),
                clock = SystemClockImpl, watchdog = ThreadWatchdog(), pluginVersion = version,
                log = { context.logger.info("Part1: $it") },
            )
        }
        return Part1Runtime(
            reader = reader, store = store, index = { DeviceIndex.loadFromAssets { app.assets.open(it) } },
            engineId = EngineRunner.EXPECTED_ENGINE_VERSION, pluginVersion = version, newQuickTier = newTier, engine = runner, ideGateAvailable = buildService != null,
            close = { closers.forEach { runCatching { it.close() } } },
        )
    }
}
