package org.appdevforall.maps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DownloadCompleteMessage] — the pure
 * `FirstRegionAutoActivator.Result` → post-download Snackbar mapping extracted
 * from `RegionManagerFragment.onDownloadComplete`. Asserts the *exact*
 * user-facing strings: they're the contract the android-qa walks verify.
 */
class DownloadCompleteMessageTest {

    @Test
    fun `Activated - confirms the apply, no action`() {
        val spec = DownloadCompleteMessage.forResult(
            FirstRegionAutoActivator.Result.Activated(regionId = "lagos", displayName = "Lagos"),
            regionId = "lagos",
        )
        assertEquals("Region downloaded and applied to project: Lagos", spec.message)
        assertFalse(spec.showApplyAction)
    }

    @Test
    fun `NoOpAlreadyActive - active region untouched, offers the Apply action`() {
        val spec = DownloadCompleteMessage.forResult(
            FirstRegionAutoActivator.Result.NoOpAlreadyActive,
            regionId = "lagos",
        )
        assertEquals(
            "Region downloaded: lagos. Project's active region is unchanged.",
            spec.message,
        )
        assertTrue("the only branch with a one-tap Apply escape hatch", spec.showApplyAction)
    }

    @Test
    fun `NoOpRegionNotFound - surfaces the cache miss, no action`() {
        val spec = DownloadCompleteMessage.forResult(
            FirstRegionAutoActivator.Result.NoOpRegionNotFound,
            regionId = "lagos",
        )
        assertEquals("Region downloaded but couldn't be located in cache: lagos", spec.message)
        assertFalse(spec.showApplyAction)
    }

    @Test
    fun `ApplyFailed - surfaces the reason, no action`() {
        val spec = DownloadCompleteMessage.forResult(
            FirstRegionAutoActivator.Result.ApplyFailed(regionId = "lagos", reason = "disk full"),
            regionId = "lagos",
        )
        assertEquals("Region downloaded; apply failed: disk full", spec.message)
        assertFalse(spec.showApplyAction)
    }
}
