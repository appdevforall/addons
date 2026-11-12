package org.appdevforall.maps.data

import org.appdevforall.maps.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [DownloadCompleteMessage] — the pure
 * `FirstRegionAutoActivator.Result` → post-download Snackbar mapping extracted
 * from `RegionManagerFragment.onDownloadComplete`. Asserts the string resource and its argument
 * for each branch; the text itself lives in `strings.xml`.
 */
class DownloadCompleteMessageTest {

    @Test
    fun `Activated - confirms the apply, no action`() {
        val spec = DownloadCompleteMessage.forResult(
            FirstRegionAutoActivator.Result.Activated(regionId = "lagos", displayName = "Lagos"),
            displayName = "Lagos",
        )
        assertEquals(R.string.maps_download_done_applied, spec.messageRes)
        assertEquals("Lagos", spec.messageArg)
        assertFalse(spec.showApplyAction)
    }

    @Test
    fun `Updated - says the project's map is updated, no Apply action`() {
        val spec = DownloadCompleteMessage.forResult(
            FirstRegionAutoActivator.Result.Updated(regionId = "lagos", displayName = "Lagos"),
            displayName = "lagos",
        )
        assertFalse("the copy is already updated, so there is nothing to apply", spec.showApplyAction)
        assertEquals(R.string.maps_download_done_updated, spec.messageRes)
        assertEquals("Lagos", spec.messageArg)
    }

    @Test
    fun `NoOpAlreadyActive - active region untouched, offers the Apply action`() {
        val spec = DownloadCompleteMessage.forResult(
            FirstRegionAutoActivator.Result.NoOpAlreadyActive,
            displayName = "Lagos",
        )
        assertEquals(R.string.maps_download_done_unchanged, spec.messageRes)
        assertEquals("Lagos", spec.messageArg)
        assertTrue("the only branch with a one-tap Apply escape hatch", spec.showApplyAction)
    }

    @Test
    fun `NoOpRegionNotFound - surfaces the cache miss, no action`() {
        val spec = DownloadCompleteMessage.forResult(
            FirstRegionAutoActivator.Result.NoOpRegionNotFound,
            displayName = "Lagos",
        )
        assertEquals(R.string.maps_download_done_not_found, spec.messageRes)
        assertEquals("Lagos", spec.messageArg)
        assertFalse(spec.showApplyAction)
    }

    @Test
    fun `ApplyFailed - surfaces the reason, no action`() {
        val spec = DownloadCompleteMessage.forResult(
            FirstRegionAutoActivator.Result.ApplyFailed(regionId = "lagos", reason = "disk full"),
            displayName = "Lagos",
        )
        assertEquals(R.string.maps_download_done_apply_failed, spec.messageRes)
        assertEquals("disk full", spec.messageArg)
        assertFalse(spec.showApplyAction)
    }
}
