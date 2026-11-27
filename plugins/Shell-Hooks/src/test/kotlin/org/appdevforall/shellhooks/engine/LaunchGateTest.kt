package org.appdevforall.shellhooks.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchGateTest {
    private val store = MapStore()

    @Test
    fun ideStartedRunsOncePerLaunch() {
        val gate = LaunchGate(store)

        assertTrue(gate.isNewLaunch("100:5000"))
        gate.record("100:5000")
        // Re-enabling or updating the plugin activates it again in the same process.
        assertFalse(gate.isNewLaunch("100:5000"))
    }

    @Test
    fun aRestartIsANewLaunch() {
        val gate = LaunchGate(store)
        gate.record("100:5000")

        assertTrue(LaunchGate(store).isNewLaunch("214:91000"))
    }

    @Test
    fun aLaunchNotRecordedStaysNew() {
        // Hooks were off when the plugin activated, so ide-started did not run.
        val gate = LaunchGate(store)

        assertTrue(gate.isNewLaunch("100:5000"))
        assertTrue(gate.isNewLaunch("100:5000"))
    }
}
