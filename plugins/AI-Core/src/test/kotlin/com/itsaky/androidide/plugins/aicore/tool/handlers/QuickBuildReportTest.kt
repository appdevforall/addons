package com.itsaky.androidide.plugins.aicore.tool.handlers

import com.itsaky.androidide.plugins.services.QuickBuildAvailability
import com.itsaky.androidide.plugins.services.QuickBuildResult
import com.itsaky.androidide.plugins.services.QuickBuildUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [QuickBuildReport]: every Quick Build outcome the host can report reads back to
 * the model as what happened and what to call next (ADFA-6300).
 */
class QuickBuildReportTest {

    private fun unavailable(reason: QuickBuildUnavailableReason, message: String = "host says no") =
        QuickBuildReport.unavailable(QuickBuildAvailability.Unavailable(reason, message))

    @Test
    fun givenAnOlderHost_whenUnavailable_thenItSaysThisIdeVersionHasNoQuickBuild() {
        val result = unavailable(QuickBuildUnavailableReason.NOT_SUPPORTED, "Quick Build is not supported")

        assertFalse(result.success)
        assertEquals("Quick Build is not available: Quick Build is not supported", result.message)
        assertTrue(result.error_details!!.contains("This IDE version does not support Quick Build"))
        assertTrue(result.error_details!!.contains("run_app"))
    }

    @Test
    fun givenEveryUnavailableReason_whenReported_thenTheHostMessageAndAdviceAreBothThere() {
        for (reason in QuickBuildUnavailableReason.entries) {
            val result = unavailable(reason, "reason-$reason")

            assertTrue("$reason", result.message.endsWith("reason-$reason"))
            assertTrue("$reason", result.error_details!!.startsWith("Quick Build did not start. "))
        }
    }

    @Test
    fun givenAReleaseVariant_whenUnavailable_thenItPointsToADebugVariantOrRunApp() {
        val details = unavailable(QuickBuildUnavailableReason.RELEASE_VARIANT).error_details!!

        assertTrue(details.contains("debug"))
        assertTrue(details.contains("run_app"))
    }

    @Test
    fun givenARestart_whenReported_thenItSaysRestartedAndThatStateWasReset() {
        val result = QuickBuildReport.resultFor(QuickBuildResult.Reloaded(durationMillis = 12_060, restarted = true))

        assertTrue(result.success)
        assertEquals("Quick Build updated the app: restarted in 12.1s", result.message)
        assertTrue(result.data!!.contains("state was reset"))
    }

    @Test
    fun givenNothingChanged_whenReported_thenItSucceedsAsUpToDate() {
        val result = QuickBuildReport.resultFor(QuickBuildResult.UpToDate)

        assertTrue(result.success)
        assertTrue(result.message.contains("nothing to update"))
    }

    @Test
    fun givenTheAppIsNotRunning_whenReported_thenItSaysTheChangeWasNotAppliedAndHowToLaunch() {
        val result = QuickBuildReport.resultFor(QuickBuildResult.AppNotRunning("The app is not running."))

        assertTrue(result.success)
        assertTrue(result.message.contains("not running"))
        assertTrue(result.data!!.startsWith("The app is not running."))
        assertTrue(result.data!!.contains("call quick_build again", ignoreCase = true))
    }

    @Test
    fun givenACompileFailure_whenReported_thenTheErrorsAndTheOutputWindowAreBothThere() {
        val reason = "The change did not compile:\nMainActivity.kt:12:5: Unresolved reference: foo"
        val output = "> Task :app:compileDebugKotlin\ne: MainActivity.kt:12:5 Unresolved reference: foo"

        val result = QuickBuildReport.resultFor(QuickBuildResult.Failed(reason, output))

        assertFalse(result.success)
        assertEquals("Quick Build failed: The change did not compile:", result.message)
        assertTrue(result.error_details!!.startsWith(reason))
        assertTrue(result.error_details!!.contains("e: MainActivity.kt:12:5"))
    }

    @Test
    fun givenAFailureReasonOpeningWithBlankLines_whenReported_thenTheHeaderIsTheFirstLineWithText() {
        val reason = "\n  \nThe change did not compile:\ne: MainActivity.kt:12:5 Unresolved reference"

        val result = QuickBuildReport.resultFor(QuickBuildResult.Failed(reason, null))

        assertEquals("Quick Build failed: The change did not compile:", result.message)
    }

    @Test
    fun givenAFailureWithoutOutput_whenReported_thenItPointsToReadBuildOutput() {
        val result = QuickBuildReport.resultFor(QuickBuildResult.Failed("Quick Build could not start.", null))

        assertFalse(result.success)
        assertTrue(result.error_details!!.contains("read_build_output"))
    }

    @Test
    fun givenARefusal_whenReported_thenItSaysNoBuildRanAndWhy() {
        val result = QuickBuildReport.resultFor(QuickBuildResult.Refused("a Quick Build is already running"))

        assertFalse(result.success)
        assertEquals("Quick Build did not start: a Quick Build is already running", result.message)
        assertTrue(result.error_details!!.contains("No build ran"))
    }

    @Test
    fun givenTheUserDeclinedTheReplace_whenReported_thenTheAgentIsToldNotToRetryUnasked() {
        val result = QuickBuildReport.resultFor(QuickBuildResult.Declined)

        assertFalse(result.success)
        assertTrue(result.message.contains("declined"))
        assertTrue(result.error_details!!.contains("Do not call quick_build again"))
    }

    @Test
    fun givenACancellation_whenReported_thenItSaysCancelled() {
        val result = QuickBuildReport.resultFor(QuickBuildResult.Cancelled)

        assertFalse(result.success)
        assertEquals("Quick Build was cancelled", result.message)
    }
}
