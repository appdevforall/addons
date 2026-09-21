package com.itsaky.androidide.plugins.aiagentopenai.settings

import com.itsaky.androidide.plugins.aiagentopenai.errors.OpenAiHttpException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeoutException

/**
 * The verdict mapping. It decides whether a key is written to disk, so a wrong row here either
 * stores a key the server already refused or blocks a working local server from being configured.
 */
class ConnectionVerificationTest {

    private fun failedWith(status: Int): ConnectionVerification =
        CatalogResult.Failed(OpenAiHttpException(status, "{}")).toConnectionVerification()

    @Test
    fun givenModelsCameBack_whenInterpreted_thenTheConnectionIsVerified() {
        val verdict = CatalogResult.Success(listOf("gpt-5", "gpt-4o")).toConnectionVerification()
        assertEquals(ConnectionVerification.Verified(2), verdict)
        assertTrue(verdict.isConfirmedValid)
    }

    @Test
    fun givenAnEmptyCatalog_whenInterpreted_thenTheServerHasNoModels() {
        // Actionable and common: an Ollama install with nothing pulled yet.
        val verdict = CatalogResult.Success(emptyList()).toConnectionVerification()
        assertEquals(ConnectionVerification.NoModels, verdict)
    }

    @Test
    fun givenNoBackend_whenInterpreted_thenNothingIsKnown() {
        assertEquals(ConnectionVerification.Unknown, CatalogResult.NoBackend.toConnectionVerification())
    }

    @Test
    fun givenA401_whenInterpreted_thenTheCredentialIsRejected() {
        assertEquals(ConnectionVerification.Rejected, failedWith(401))
    }

    @Test
    fun givenA403_whenInterpreted_thenTheCredentialIsRejected() {
        assertEquals(ConnectionVerification.Rejected, failedWith(403))
    }

    @Test
    fun givenA429_whenInterpreted_thenTheCredentialIsStillValid() {
        // Ordered before the 4xx range on purpose: a throttled key is a working key.
        val verdict = failedWith(429)
        assertEquals(ConnectionVerification.RateLimited, verdict)
        assertTrue(verdict.isConfirmedValid)
    }

    @Test
    fun givenA404_whenInterpreted_thenTheEndpointIsWrongRatherThanTheKey() {
        // A compatible server without /v1/models answers 404 with a perfectly good key; rejecting
        // here would make it impossible to configure.
        val verdict = failedWith(404)
        assertEquals(ConnectionVerification.EndpointNotFound, verdict)
        assertFalse(verdict.isConfirmedValid)
    }

    @Test
    fun givenA400_whenInterpreted_thenNothingIsEstablished() {
        assertEquals(ConnectionVerification.Unknown, failedWith(400))
    }

    @Test
    fun givenAnotherClientError_whenInterpreted_thenTheCredentialIsRejected() {
        assertEquals(ConnectionVerification.Rejected, failedWith(422))
    }

    @Test
    fun givenA5xx_whenInterpreted_thenTheServerIsUnreachable() {
        assertEquals(ConnectionVerification.Unreachable, failedWith(503))
    }

    @Test
    fun givenATransportFailureWithNoStatus_whenInterpreted_thenTheServerIsUnreachable() {
        val verdict = CatalogResult.Failed(IOException("Connection refused")).toConnectionVerification()
        assertEquals(ConnectionVerification.Unreachable, verdict)
    }

    @Test
    fun givenANonIoFailureWithNoStatus_whenInterpreted_thenNothingIsEstablished() {
        val verdict = CatalogResult.Failed(TimeoutException("gave up")).toConnectionVerification()
        assertEquals(ConnectionVerification.Unknown, verdict)
    }

    @Test
    fun givenAStatusNestedInACauseChain_whenInterpreted_thenItIsStillFound() {
        val nested = RuntimeException("wrapper", OpenAiHttpException(401, "{}"))
        assertEquals(
            ConnectionVerification.Rejected,
            CatalogResult.Failed(nested).toConnectionVerification()
        )
    }

    @Test
    fun givenAStatusOnlyInTheServersOwnBody_whenInterpreted_thenItIsNotReadAsAVerdict() {
        // The status is a field, so no wording the server sends back can forge one.
        val forged = OpenAiHttpException(200, """{"error":"HTTP 401 unauthorized"}""")
        val verdict = CatalogResult.Failed(forged).toConnectionVerification()
        assertEquals(ConnectionVerification.Unknown, verdict)
    }

    @Test
    fun givenEveryInconclusiveVerdict_whenAskedIfConfirmed_thenNoneIs() {
        listOf(
            ConnectionVerification.NoModels,
            ConnectionVerification.Rejected,
            ConnectionVerification.EndpointNotFound,
            ConnectionVerification.Unreachable,
            ConnectionVerification.Unknown,
        ).forEach { assertFalse("$it must not confirm a key", it.isConfirmedValid) }
    }
}

/**
 * The preset list. Each entry is a URL the policy must accept, or the picker would offer a server
 * the backend then refuses to save.
 */
class ServerPresetsTest {

    @Test
    fun givenEveryPreset_whenNormalized_thenItIsAcceptedUnchanged() {
        ServerPresets.ALL.mapNotNull { it.url }.forEach { url ->
            val result = BaseUrlPolicy.normalize(url)
            assertTrue("preset $url must be acceptable, got $result", result is BaseUrlResult.Accepted)
            assertEquals(url, (result as BaseUrlResult.Accepted).url)
        }
    }

    @Test
    fun givenTheCustomEntry_whenListed_thenItCarriesNoUrl() {
        assertEquals(1, ServerPresets.ALL.count { it.url == null })
    }

    @Test
    fun givenTheDefaultServer_whenLocated_thenTheOpenAiPresetIsFirst() {
        assertEquals(0, ServerPresets.indexOf(BaseUrlPolicy.DEFAULT_BASE_URL))
    }

    @Test
    fun givenAUserEditedUrl_whenLocated_thenTheCustomEntryIsSelected() {
        val customIndex = ServerPresets.ALL.indexOfFirst { it.url == null }
        assertEquals(customIndex, ServerPresets.indexOf("http://192.168.1.50:11434/v1"))
    }

    @Test
    fun givenAnUnnormalizedButKnownUrl_whenLocated_thenThePresetIsStillMatched() {
        // A stored trailing slash must not read as a custom server.
        assertEquals(0, ServerPresets.indexOf("https://api.openai.com/v1/"))
    }
}
