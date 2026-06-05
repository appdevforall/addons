package org.appdevforall.maps.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [RegionInstaller].
 *
 * The happy-path copy reaches `RegionCache.rootDir()`, which calls
 * `Environment.getExternalStorageDirectory()` — unavailable in plain JVM unit
 * tests. So this test exercises the failure contract: when the cache root can't be
 * resolved, [RegionInstaller.apply] must fail gracefully (return false) and route
 * the exception to the injected logger rather than throwing. Flat-copy + the
 * require-a-Maps-project gate are covered by
 * [org.appdevforall.maps.templates.ProjectMapEmitterTest]; full apply coverage
 * lives in the android-qa device walk.
 */
class RegionInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun regionInfo(dir: File) = RegionInfo(
        regionId = "test-region",
        displayName = "Test Region",
        sizeBytes = 0L,
        downloadedAt = null,
        lastUsedAt = null,
        source = "internet",
        directory = dir,
    )

    @Test
    fun `apply fails gracefully and reports the error when the cache root is unavailable`() {
        val project = tmp.newFolder("project")
        val regionDir = tmp.newFolder("cache", "test-region")
        var reported: Throwable? = null

        val result = RegionInstaller.apply(
            info = regionInfo(regionDir),
            projectDir = project,
            logError = { _, t -> reported = t },
        )

        assertFalse("must not throw; must return false when cache root is unavailable", result)
        assertTrue("the caught exception should be routed to logError", reported != null)
    }
}
