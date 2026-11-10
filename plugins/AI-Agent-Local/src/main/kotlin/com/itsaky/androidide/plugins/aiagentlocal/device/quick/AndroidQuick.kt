package com.itsaky.androidide.plugins.aiagentlocal.device.quick

import android.app.Activity
import android.app.ActivityManager
import android.app.Dialog
import android.content.Context
import android.view.WindowManager
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
 * Gate 1: the app's current activity (the host's `IdeUIService`) exists, is not finishing, and the app's process is in the foreground
 * (`ActivityManager.getMyMemoryState` importance FOREGROUND, which holds while an activity is resumed and visible). The engine
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
            info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * Gate 2 with the host's build-status service (`IdeBuildService`: `isBuildInProgress` is true from `prepareBuild`, which a project sync
 * also goes through, until the build finishes or fails). A listener counts the builds that start. The service does not see indexing; see
 * [ProcQuietIdeGate] for what the fallback adds when the service is absent.
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

/** `FLAG_KEEP_SCREEN_ON` on the activity's window for the duration of a pass; removed again in [release] only when this class set it. */
class AndroidScreenKeeper(private val activity: () -> Activity?) : ScreenKeeper {
    private val held = AtomicReference<Activity?>()

    override fun keepOn() {
        val a = activity() ?: return
        a.runOnUiThread {
            val w = a.window ?: return@runOnUiThread
            if (w.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0) {
                w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                held.set(a)
            }
        }
    }

    override fun release() {
        val a = held.getAndSet(null) ?: return
        a.runOnUiThread { a.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/**
 * The question and the progress, as dialogs on the current activity in the plugin's existing dialog style (`MaterialAlertDialogBuilder`,
 * shown through the host's `PluginWindows.showDialog`). All text comes from string resources. The question returns Skip by itself when the
 * user does not answer within [QuickLimits.ASK_TIMEOUT_MS] or the dialog goes away; with no activity it returns `NO_ACTIVITY`.
 */
class AndroidQuickTierUi(private val strings: Context, private val activity: () -> Activity?) : QuickTierUi {
    private val question = AtomicReference<Dialog?>()
    private val progress = AtomicReference<Dialog?>()
    private val progressHost = AtomicReference<Activity?>()
    @Volatile private var cancelHandler: () -> Unit = {}

    override fun ask(): QuickAnswer {
        val a = activity()
        if (a == null || a.isFinishing || a.isDestroyed) return QuickAnswer.NO_ACTIVITY
        val answer = AtomicReference<QuickAnswer?>()
        val done = CountDownLatch(1)
        a.runOnUiThread {
            try {
                val d = MaterialAlertDialogBuilder(a)
                    .setTitle(strings.getString(R.string.part1_dialog_title))
                    .setMessage(strings.getString(R.string.part1_dialog_message))
                    .setPositiveButton(strings.getString(R.string.part1_dialog_run)) { _, _ -> answer.compareAndSet(null, QuickAnswer.RUN_NOW) }
                    .setNegativeButton(strings.getString(R.string.part1_dialog_skip)) { _, _ -> answer.compareAndSet(null, QuickAnswer.SKIP) }
                    .setOnDismissListener {
                        answer.compareAndSet(null, QuickAnswer.SKIP) // back press, outside tap, activity gone
                        done.countDown()
                    }
                    .create()
                question.set(d)
                if (!PluginWindows.showDialog(d)) {
                    answer.compareAndSet(null, QuickAnswer.NO_ACTIVITY)
                    done.countDown()
                }
            } catch (e: Exception) {
                answer.compareAndSet(null, QuickAnswer.NO_ACTIVITY)
                done.countDown()
            }
        }
        if (!done.await(QuickLimits.ASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            answer.compareAndSet(null, QuickAnswer.SKIP)
            a.runOnUiThread { runCatching { question.get()?.dismiss() } }
        }
        return answer.get() ?: QuickAnswer.SKIP
    }

    override fun onCancel(handler: () -> Unit) {
        cancelHandler = handler
    }

    override fun showProgress(progress: Progress) {
        val what = when (progress.label) {
            RowLabel.PIN_PROBE -> strings.getString(R.string.part1_row_pin_probe)
            RowLabel.CLUSTER_READ -> strings.getString(R.string.part1_row_cluster_read, (progress.cluster ?: 0) + 1)
            RowLabel.TWO_FAST_READ -> strings.getString(R.string.part1_row_two_fast)
            RowLabel.ALL_READ -> strings.getString(R.string.part1_row_all)
            RowLabel.GPU_READ -> strings.getString(R.string.part1_row_gpu)
        }
        setProgressText(strings.getString(R.string.part1_progress_row, progress.row, progress.total, what))
    }

    override fun showWaiting(what: WaitingFor) {
        setProgressText(
            strings.getString(
                when (what) {
                    WaitingFor.IDE_QUIET -> R.string.part1_waiting_ide
                    WaitingFor.THERMAL -> R.string.part1_waiting_thermal
                    WaitingFor.REST -> R.string.part1_waiting_rest
                },
            ),
        )
    }

    private fun setProgressText(text: String) {
        val a = progressHost.get() ?: activity()?.also { progressHost.set(it) } ?: return
        a.runOnUiThread {
            try {
                val existing = progress.get()
                if (existing != null) {
                    (existing as? androidx.appcompat.app.AlertDialog)?.setMessage(text)
                    return@runOnUiThread
                }
                val d = MaterialAlertDialogBuilder(a)
                    .setTitle(strings.getString(R.string.part1_progress_title))
                    .setMessage(text)
                    .setCancelable(false)
                    .setNegativeButton(strings.getString(R.string.part1_cancel)) { _, _ -> cancelHandler() }
                    .create()
                progress.set(d)
                PluginWindows.showDialog(d)
            } catch (e: Exception) {
                // a dialog that cannot be shown does not stop the measurement; the Cancel button is then absent, the 180 s limit remains
            }
        }
    }

    override fun close() {
        val host = progressHost.getAndSet(null) ?: activity()
        val dialogs = listOfNotNull(question.getAndSet(null), progress.getAndSet(null))
        if (dialogs.isEmpty()) return
        val dismiss = Runnable { dialogs.forEach { runCatching { it.dismiss() } } }
        if (host != null) host.runOnUiThread(dismiss) else dismiss.run()
    }
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
        val ide: QuietIdeGate = if (buildService != null) {
            HostQuietIdeGate(buildService).also { closers.add(it) }
        } else {
            ProcQuietIdeGate(JavaProcFs(), android.os.Process.myUid())
        }
        val newTier = {
            QuickTier(
                engine = runner, store = store, index = { DeviceIndex.loadFromAssets { app.assets.open(it) } },
                foreground = AndroidForegroundGate(activity), ide = ide, conditions = { conditions.read() },
                memory = ProcMemoryProbe(files), screen = AndroidScreenKeeper(activity), ui = AndroidQuickTierUi(app, activity),
                clock = SystemClockImpl, watchdog = ThreadWatchdog(), pluginVersion = version,
                log = { context.logger.info("Part1: $it") },
            )
        }
        return Part1Runtime(
            reader = reader, store = store, index = { DeviceIndex.loadFromAssets { app.assets.open(it) } },
            engineId = EngineRunner.EXPECTED_ENGINE_VERSION, pluginVersion = version, newQuickTier = newTier, engine = runner,
            close = { closers.forEach { runCatching { it.close() } } },
        )
    }
}
