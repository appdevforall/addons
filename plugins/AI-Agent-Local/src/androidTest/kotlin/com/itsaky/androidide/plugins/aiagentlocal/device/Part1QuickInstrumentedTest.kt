package com.itsaky.androidide.plugins.aiagentlocal.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineProgram
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineRunner
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.JvmProcessLauncher
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.NativeLibEngineLocator
import com.itsaky.androidide.plugins.aiagentlocal.device.match.DeviceIndex
import com.itsaky.androidide.plugins.aiagentlocal.device.quick.AndroidForegroundGate
import com.itsaky.androidide.plugins.aiagentlocal.device.quick.Clock
import com.itsaky.androidide.plugins.aiagentlocal.device.quick.ConditionsProvider
import com.itsaky.androidide.plugins.aiagentlocal.device.quick.ProcMemoryProbe
import com.itsaky.androidide.plugins.aiagentlocal.device.quick.Progress
import com.itsaky.androidide.plugins.aiagentlocal.device.quick.QuickAnswer
import com.itsaky.androidide.plugins.aiagentlocal.device.quick.QuickStatus
import com.itsaky.androidide.plugins.aiagentlocal.device.quick.QuickTier
import com.itsaky.androidide.plugins.aiagentlocal.device.quick.QuickTierUi
import com.itsaky.androidide.plugins.aiagentlocal.device.quick.QuietIdeGate
import com.itsaky.androidide.plugins.aiagentlocal.device.quick.ScreenKeeper
import com.itsaky.androidide.plugins.aiagentlocal.device.quick.SystemClockImpl
import com.itsaky.androidide.plugins.aiagentlocal.device.quick.ThreadWatchdog
import com.itsaky.androidide.plugins.aiagentlocal.device.quick.WaitingFor
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
import com.itsaky.androidide.plugins.aiagentlocal.device.store.BindResult
import com.itsaky.androidide.plugins.aiagentlocal.device.store.Part1Store
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A whole T1 pass through the **real** runner, the real T0 sources and the real store, with a fake dialog that answers "Run now". Written in
 * P1-04A2 and compiled there; **not run** (it needs a phone, with the plugin's test APK installed and the app on screen). It fails, it does
 * not skip, when the programs are not extracted or the pass does not complete: the message names the result and the reason.
 *
 * The test starts a plain empty activity ([TestHostActivity], test APK only, `FLAG_KEEP_SCREEN_ON`), keeps it resumed for the whole test and
 * finishes it in `finally`, so the process is in `/top-app`. The foreground gate is the real [AndroidForegroundGate] on that activity (the
 * engine checks its own cpuset as well, exit 97).
 * Fakes: the dialog (answers Run now), the IDE gate (idle) and the screen flag. Everything else is real. The work directory is under the target context's files directory; the programs are those of the test APK.
 */
@RunWith(AndroidJUnit4::class)
class Part1QuickInstrumentedTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val test = instrumentation.context
    private val target = InstrumentationRegistry.getInstrumentation().targetContext

    private class RunNowUi : QuickTierUi {
        val steps = ArrayList<String>()
        override fun ask() = QuickAnswer.RUN_NOW
        override fun onCancel(handler: () -> Unit) {}
        override fun showProgress(progress: Progress) { steps.add("row ${progress.row} of ${progress.total}: ${progress.label}") }
        override fun showWaiting(what: WaitingFor) { steps.add("waiting: $what") }
        override fun close() {}
    }

    @Test
    fun givenTheRealRunnerAndAFakeDialogThatSaysRunNow_whenAWholeT1PassRuns_thenItCompletesAndTheDescriptorHasMeasuredLeaves() {
        val host = TestHostActivity.start(instrumentation)
        try {
            wholePass(host)
        } finally {
            TestHostActivity.finishAndWait(instrumentation, host)
        }
    }

    private fun wholePass(host: TestHostActivity) {
        val locator = NativeLibEngineLocator(
            findLibrary = { name -> File(test.applicationInfo.nativeLibraryDir, "lib$name.so").takeIf { it.isFile }?.path },
            dataDir = File(test.applicationInfo.dataDir), pluginId = test.packageName,
        )
        for (p in EngineProgram.values()) assertNotNull("${p.fileName} is not in ${test.applicationInfo.nativeLibraryDir}: the test APK must package and extract the engine programs", locator.locate(p))
        val runner = EngineRunner(locator, JvmProcessLauncher(), File(target.filesDir, "part1-test/work"), onMainThread = ::isMainThread)
        val store = Part1Store(File(target.filesDir, "part1-test/store").also { it.deleteRecursively() })
        val conditions = ConditionsReader(AndroidThermalSource(target), AndroidBatterySource(target), AndroidDisplaySource(target), AndroidScreenSource(target))
        val files = JavaSysFiles()
        val t0 = StaticReader(runner, files, AndroidBuildSource(), AndroidMemorySource(target), conditions, onMainThread = ::isMainThread).read("test")
        val fingerprint = (t0.static["identity"] as Map<*, *>)["build_fingerprint"] as? String
        assertTrue("the T0 read gave no build fingerprint", !fingerprint.isNullOrEmpty())
        assertTrue(store.bindDevice(fingerprint, EngineRunner.EXPECTED_ENGINE_VERSION) is BindResult.Fresh)
        assertTrue(store.writeStatic(t0.static))
        val ui = RunNowUi()
        val tier = QuickTier(
            engine = runner, store = store, index = { DeviceIndex.loadFromAssets { target.assets.open(it) } },
            foreground = AndroidForegroundGate { host }, ide = object : QuietIdeGate { override fun isBusy() = false; override fun startsSeen() = 0L },
            conditions = ConditionsProvider { conditions.read() }, memory = ProcMemoryProbe(files),
            screen = object : ScreenKeeper { override fun keepOn() {}; override fun release() {} },
            ui = ui, clock = SystemClockImpl as Clock, watchdog = ThreadWatchdog(), pluginVersion = "test",
        )
        val result = tier.run()
        assertEquals("the pass did not complete: $result; steps ${ui.steps}", QuickStatus.COMPLETED, result.status)
        assertTrue("rows stored: ${result.rowsStored}", result.rowsStored >= 3)
        assertNotNull(store.readDescriptor())
        assertTrue("T1 took ${result.elapsedMs} ms", result.elapsedMs < 180_000)
    }
}
