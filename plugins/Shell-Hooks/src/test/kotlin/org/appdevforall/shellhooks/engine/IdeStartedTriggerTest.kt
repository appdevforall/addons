package org.appdevforall.shellhooks.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class IdeStartedTriggerTest {
    private val store = MapStore()
    private var enabled = true
    private var runs = 0
    private val trigger = IdeStartedTrigger(LaunchGate(store), { enabled }) { runs++ }

    @Test
    fun runsOnceForEachLaunch() {
        trigger.onActivate("100:5000")
        // Re-enabling or updating the plugin activates it again in the same launch.
        trigger.onActivate("100:5000")

        assertEquals(1, runs)
    }

    @Test
    fun aRestartRunsItAgain() {
        trigger.onActivate("100:5000")
        trigger.onActivate("214:91000")

        assertEquals(2, runs)
    }

    @Test
    fun aStartWithHooksOffRecordsNothingSoALaterStartInTheSameLaunchRunsIt() {
        enabled = false
        trigger.onActivate("100:5000")
        enabled = true
        trigger.onActivate("100:5000")

        assertEquals(1, runs)
    }
}
