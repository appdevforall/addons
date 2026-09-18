package com.itsaky.androidide.plugins.aiagentopenai.errors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the tags [CredentialFailure] persists.
 *
 * A refusal is recorded as a tag and read back by a later run of the plugin — after an update as
 * well as after a restart — so the tags are pinned here: renaming one turns a recorded refusal
 * into nothing the settings pane can report, silently and only on a device that had one stored.
 */
class CredentialFailureTagTest {

    @Test
    fun givenARecordedTag_whenReadBack_thenItNamesTheSameFailure() {
        CredentialFailure.entries.forEach { failure ->
            assertEquals(failure, CredentialFailure.ofTag(failure.tag))
        }
    }

    @Test
    fun givenTheTags_whenCompared_thenTheyAreTheValuesWrittenToDisk() {
        assertEquals("key_refused", CredentialFailure.KeyRefused.tag)
        assertEquals("key_missing", CredentialFailure.KeyMissing.tag)
        assertEquals("key_forbidden", CredentialFailure.KeyForbidden.tag)
    }

    @Test
    fun givenATagThisReleaseDoesNotKnow_whenReadBack_thenThereIsNothingToReport() {
        assertNull(CredentialFailure.ofTag("key_from_another_release"))
    }

    @Test
    fun givenACredentialFailure_whenClassified_thenItCarriesWordingToResolve() {
        // Recorded as a failure rather than a sentence, so the banner follows the device language;
        // a zero id would mean nothing to show where the pane expects a string.
        CredentialFailure.entries.forEach { failure ->
            assertEquals(false, failure.messageRes == 0)
        }
    }
}
