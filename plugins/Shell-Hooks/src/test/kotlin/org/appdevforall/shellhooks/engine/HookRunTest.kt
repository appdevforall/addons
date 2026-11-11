package org.appdevforall.shellhooks.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class HookRunTest {
    private fun run(
        hook: Hook?,
        target: String?,
    ) = HookRun(0, hook, File("/hooks/x"), ScriptSource.GLOBAL, target, Outcome.SUCCEEDED, 0, 1, "")

    @Test
    fun aPathTargetIsTitledByItsName() {
        assertEquals("Main.kt", run(Hook.FILE_SAVED, "/projects/app/src/Main.kt").targetLabel)
        assertEquals("app", run(Hook.PROJECT_OPENED, "/projects/app").targetLabel)
        assertEquals("Main.kt", run(null, "/projects/app/Main.kt").targetLabel)
    }

    @Test
    fun aBuildFailureIsTitledByItsFirstLineNotAPath() {
        val message = "Could not resolve x. See /data/app/build/report.txt\nmore detail"

        assertEquals("Could not resolve x. See /data/app/build/report.txt", run(Hook.BUILD_FAILED, message).targetLabel)
    }

    @Test
    fun aLongBuildFailureIsShortened() {
        val label = run(Hook.BUILD_FAILED, "e".repeat(200)).targetLabel!!

        assertEquals(HookRun.MAX_TITLE_CHARS, label.length)
        assertEquals("...", label.takeLast(3))
    }

    @Test
    fun noTargetHasNoLabelAndAnActionIsNamedAction() {
        assertNull(run(Hook.BUILD_STARTED, null).targetLabel)
        assertNull(run(Hook.FILE_ACTIVATED, "").targetLabel)
        assertEquals("action", run(null, "/a").name)
        assertEquals("file-saved", run(Hook.FILE_SAVED, "/a").name)
    }

    @Test
    fun runsStartedTheSameMillisecondStillHaveDistinctIds() {
        assertNotEquals(run(Hook.FILE_SAVED, "/a").id, run(Hook.FILE_SAVED, "/a").id)
    }
}
