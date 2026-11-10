package com.itsaky.androidide.plugins.aiagentlocal.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineCommands
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineResult
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineRunner
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.JvmProcessLauncher
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.NativeLibEngineLocator
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
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the engine through the real runner and the real T0 sources on a device. Compiled in P1-04A1, not run there (it needs
 * a phone). The programs are executed from the app's native library directory: they are found there only when the
 * installed APK extracted its native libraries; otherwise the tests skip (`assumeTrue`) and say why. The foreground
 * gate (exit 97) is a legitimate answer for a row when the test activity is not on top.
 */
@RunWith(AndroidJUnit4::class)
class Part1EngineInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(context.filesDir, "part1/work")

    private fun runner(): EngineRunner {
        val locator = NativeLibEngineLocator(
            findLibrary = { name -> File(context.applicationInfo.nativeLibraryDir, "lib$name.so").takeIf { it.isFile }?.path },
            dataDir = File(context.applicationInfo.dataDir),
            pluginId = context.packageName,
        )
        assumeTrue("the engine programs are not extracted next to the app", locator.locate(com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineProgram.MEMBW) != null)
        return EngineRunner(locator, JvmProcessLauncher(), work, onMainThread = ::isMainThread)
    }

    @Test
    fun givenTheRealRunner_whenTheStaticProbeRuns_thenItIsOkWithTheExpectedEngineVersion() {
        val r = runner().run(EngineCommands.staticProbe())
        assertTrue("static probe: $r", r is EngineResult.Ok)
        val json = (r as EngineResult.Ok).json
        assertEquals("part1-staticprobe-v1", json["format"])
        assertEquals(EngineRunner.EXPECTED_ENGINE_VERSION, json["engine_version"])
    }

    @Test
    fun givenTheRealRunner_whenOneT1RowRuns_thenTheAnswerIsARowOrARecordedRefusal() {
        val r = runner().run(EngineCommands.c2Read(0, 0, 512))
        when (r) {
            is EngineResult.Ok -> assertEquals("C2-read-k0", r.json["tag"])
            is EngineResult.Refused -> assertTrue("refusal ${r.exit}", r.exit == 97 || r.exit == 88) // not foreground, or too little memory
            else -> throw AssertionError("unexpected result $r")
        }
    }

    @Test
    fun givenTheRealSources_whenTheT0ReadRuns_thenItGivesAPart1StaticObjectWithinTheBudget() {
        val reader = StaticReader(
            runner(), JavaSysFiles(), AndroidBuildSource(), AndroidMemorySource(context),
            ConditionsReader(AndroidThermalSource(context), AndroidBatterySource(context), AndroidDisplaySource(context), AndroidScreenSource(context)),
            onMainThread = ::isMainThread,
        )
        val r = reader.read("test")
        assertEquals("part1-static-v1", r.static["format"])
        assertTrue("T0 took ${r.elapsedMs} ms", r.elapsedMs < 2000)
        assertTrue((r.static["cpus"] as List<*>).isNotEmpty())
    }
}
