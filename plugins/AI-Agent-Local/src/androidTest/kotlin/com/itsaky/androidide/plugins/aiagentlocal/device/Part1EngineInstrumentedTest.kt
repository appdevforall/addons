package com.itsaky.androidide.plugins.aiagentlocal.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineCommands
import com.itsaky.androidide.plugins.aiagentlocal.device.engine.EngineProgram
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
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the engine through the real runner and the real T0 sources on a device. Compiled in P1-04A1F, not run there (it needs
 * a phone). The two programs are packaged into the **test APK** (`build.gradle.kts`: the committed `jniLibs`, with
 * `useLegacyPackaging` for the androidTest component only), so the system extracts them to the test APK's
 * `nativeLibraryDir`. The tests **fail** with a clear message when they are not there; they do not skip. The foreground
 * tests that run a row start [TestHostActivity] (declared in the app under test, debug builds only, started with the target package name) and keep it resumed, so the process is in `/top-app`: exit 97 is then a **failure**, with the cpuset read
 * from `/proc/self/cpuset` in the message.
 */
@RunWith(AndroidJUnit4::class)
class Part1EngineInstrumentedTest {

    /** The test APK's own context: its `nativeLibraryDir` holds the extracted engine programs. */
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.context

    /**
     * The **target** (app) context: the tests run in the app's process under the app's uid, which can create files only in the app's
     * own data directory, not in the test package's. The work directory is therefore under the target's `filesDir`.
     */
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val work = File(target.filesDir, "part1/work")

    private fun runner(): EngineRunner {
        val locator = NativeLibEngineLocator(
            findLibrary = { name -> File(context.applicationInfo.nativeLibraryDir, "lib$name.so").takeIf { it.isFile }?.path },
            dataDir = File(context.applicationInfo.dataDir),
            pluginId = context.packageName,
        )
        for (program in EngineProgram.values()) {
            assertNotNull(
                "${program.fileName} is not in ${context.applicationInfo.nativeLibraryDir}: the test APK must package the engine programs " +
                    "(androidTest jniLibs with useLegacyPackaging) and the system must extract them",
                locator.locate(program),
            )
        }
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

    private fun ownCpuset(): String = try { File("/proc/self/cpuset").readText().trim() } catch (e: Exception) { "unreadable (${e.javaClass.simpleName})" }

    @Test
    fun givenTheRealRunnerAndAResumedActivity_whenOneT1RowRuns_thenTheAnswerIsARowOrAMemoryRefusalNeverTheForegroundRefusal() {
        val host = TestHostActivity.start(instrumentation)
        try {
            val r = runner().run(EngineCommands.c2Read(0, 0, 512))
            when (r) {
                is EngineResult.Ok -> assertEquals("C2-read-k0", r.json["tag"])
                is EngineResult.Refused -> {
                    // 97 with a resumed activity is a failure: the process should be in /top-app
                    assertTrue("exit 97 (not foreground) although the test activity is resumed; this process's cpuset is ${ownCpuset()}", r.exit != 97)
                    assertEquals("refusal ${r.exit}", 88, r.exit) // too little memory
                }
                else -> throw AssertionError("unexpected result $r")
            }
        } finally {
            TestHostActivity.finishAndWait(instrumentation, host)
        }
    }

    @Test
    fun givenTheRealSources_whenTheT0ReadRuns_thenItGivesAPart1StaticObjectWithinTheBudget() {
        val reader = StaticReader(
            runner(), JavaSysFiles(), AndroidBuildSource(), AndroidMemorySource(target),
            ConditionsReader(AndroidThermalSource(target), AndroidBatterySource(target), AndroidDisplaySource(target), AndroidScreenSource(target)),
            onMainThread = ::isMainThread,
        )
        val r = reader.read("test")
        assertEquals("part1-static-v1", r.static["format"])
        // the test must fail when a probe did not run: nothing of either probe may be recorded as unavailable
        val un = (r.static["unavailable"] as Map<*, *>).keys.map { it.toString() }
        // identity.platform may be legitimately unavailable: ro.board.platform can be empty on a phone (F28); the other leaves must be there
        for (k in listOf("features.hwcap", "features.hwcap2", "features.page_size", "vulkan")) {
            assertTrue("a probe did not run: $k is unavailable (${r.static["unavailable"]})", k !in un)
        }
        assertTrue("the static probe gave no feature word", (r.static["features"] as Map<*, *>)["hwcap"] is String)
        assertTrue("the GPU probe gave no object", r.static["vulkan"] is Map<*, *> && r.facts.containsKey("opencl_child"))
        assertTrue("T0 took ${r.elapsedMs} ms", r.elapsedMs < 2000)
        assertTrue((r.static["cpus"] as List<*>).isNotEmpty())
    }
}
