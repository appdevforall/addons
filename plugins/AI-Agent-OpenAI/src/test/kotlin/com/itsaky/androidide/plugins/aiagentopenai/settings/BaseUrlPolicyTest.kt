package com.itsaky.androidide.plugins.aiagentopenai.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The URL rules that decide whether this backend can be configured at all. Every case here is a
 * setup mistake or a policy call that would otherwise only surface on a device.
 */
class BaseUrlPolicyTest {

    private fun accept(input: String): BaseUrlResult.Accepted {
        val result = BaseUrlPolicy.normalize(input)
        assertTrue("expected $input to be accepted, got $result", result is BaseUrlResult.Accepted)
        return result as BaseUrlResult.Accepted
    }

    private fun reject(input: String): BaseUrlResult.Reason {
        val result = BaseUrlPolicy.normalize(input)
        assertTrue("expected $input to be rejected, got $result", result is BaseUrlResult.Rejected)
        return (result as BaseUrlResult.Rejected).reason
    }

    @Test
    fun givenTheOpenAiDefault_whenNormalized_thenItIsUnchanged() {
        assertEquals(BaseUrlPolicy.DEFAULT_BASE_URL, accept(BaseUrlPolicy.DEFAULT_BASE_URL).url)
    }

    @Test
    fun givenSurroundingWhitespace_whenNormalized_thenItIsTrimmed() {
        assertEquals("https://api.openai.com/v1", accept("  https://api.openai.com/v1  ").url)
    }

    @Test
    fun givenATrailingSlash_whenNormalized_thenItIsDropped() {
        assertEquals("https://api.openai.com/v1", accept("https://api.openai.com/v1/").url)
    }

    @Test
    fun givenAPastedChatCompletionsUrl_whenNormalized_thenTheEndpointPathIsStripped() {
        assertEquals(
            "https://api.openai.com/v1",
            accept("https://api.openai.com/v1/chat/completions").url
        )
    }

    @Test
    fun givenAPastedModelsUrl_whenNormalized_thenTheEndpointPathIsStripped() {
        assertEquals("http://localhost:11434/v1", accept("http://localhost:11434/v1/models").url)
    }

    @Test
    fun givenAnUppercaseHost_whenNormalized_thenItIsLowercased() {
        assertEquals("https://api.openai.com/v1", accept("https://API.OpenAI.COM/v1").url)
    }

    @Test
    fun givenCleartextLoopback_whenNormalized_thenItIsAcceptedAsLoopback() {
        val result = accept("http://localhost:11434/v1")
        assertTrue(result.cleartext)
        assertTrue(result.loopback)
    }

    @Test
    fun givenCleartextOnAPrivateLan_whenNormalized_thenItIsAcceptedButNotLoopback() {
        val result = accept("http://192.168.1.50:11434/v1")
        assertTrue(result.cleartext)
        assertFalse(result.loopback)
    }

    @Test
    fun givenCleartextOnEveryPrivateRange_whenNormalized_thenAllAreAccepted() {
        listOf(
            "http://10.1.2.3:8080/v1",
            "http://172.16.0.9:8080/v1",
            "http://172.31.255.1:8080/v1",
            "http://169.254.1.1:8080/v1",
            "http://127.0.0.1:8080/v1",
            "http://10.0.2.2:11434/v1",
        ).forEach { accept(it) }
    }

    @Test
    fun givenABareLanHostname_whenNormalized_thenItIsAccepted() {
        // A dotless name such as `raspberrypi` only resolves on the local network.
        assertTrue(accept("http://raspberrypi:11434/v1").cleartext)
    }

    @Test
    fun givenCleartextOnAPublicHost_whenNormalized_thenItIsRejected() {
        assertEquals(BaseUrlResult.Reason.CLEARTEXT_PUBLIC, reject("http://api.openai.com/v1"))
    }

    @Test
    fun givenCleartextOnAPublicIp_whenNormalized_thenItIsRejected() {
        assertEquals(BaseUrlResult.Reason.CLEARTEXT_PUBLIC, reject("http://8.8.8.8:11434/v1"))
    }

    @Test
    fun given172OutsideThePrivateRange_whenNormalized_thenCleartextIsRejected() {
        // 172.15 and 172.32 are public; only 172.16-172.31 is RFC 1918.
        assertEquals(BaseUrlResult.Reason.CLEARTEXT_PUBLIC, reject("http://172.15.0.1/v1"))
        assertEquals(BaseUrlResult.Reason.CLEARTEXT_PUBLIC, reject("http://172.32.0.1/v1"))
    }

    @Test
    fun givenHttpsOnAPublicHost_whenNormalized_thenItIsAccepted() {
        val result = accept("https://openrouter.ai/api/v1")
        assertFalse(result.cleartext)
    }

    @Test
    fun givenBlankInput_whenNormalized_thenItIsRejectedAsBlank() {
        assertEquals(BaseUrlResult.Reason.BLANK, reject("   "))
        assertEquals(BaseUrlResult.Reason.BLANK, BaseUrlPolicy.normalize(null).let {
            (it as BaseUrlResult.Rejected).reason
        })
    }

    @Test
    fun givenANonHttpScheme_whenNormalized_thenItIsRejectedAsMalformed() {
        assertEquals(BaseUrlResult.Reason.MALFORMED, reject("ftp://api.openai.com/v1"))
        assertEquals(BaseUrlResult.Reason.MALFORMED, reject("api.openai.com/v1"))
    }

    @Test
    fun givenASchemeWithNoHost_whenNormalized_thenItIsRejectedAsNoHost() {
        assertEquals(BaseUrlResult.Reason.NO_HOST, reject("https://"))
    }

    @Test
    fun givenTheOpenAiHost_whenAskedIfAKeyIsRequired_thenItIs() {
        assertTrue(BaseUrlPolicy.requiresApiKey("https://api.openai.com/v1"))
        assertTrue(BaseUrlPolicy.requiresApiKey("https://API.OPENAI.COM/v1"))
    }

    @Test
    fun givenALocalServer_whenAskedIfAKeyIsRequired_thenItIsNot() {
        // The ADFA-3452 regression: demanding a key here makes the backend unusable.
        assertFalse(BaseUrlPolicy.requiresApiKey("http://localhost:11434/v1"))
        assertFalse(BaseUrlPolicy.requiresApiKey("http://192.168.1.50:1234/v1"))
    }

    @Test
    fun givenAnotherCloudProvider_whenAskedIfAKeyIsRequired_thenItIsNotForcedByPolicy() {
        // OpenRouter needs a key, but the server says so with a 401 — policy does not presume it.
        assertFalse(BaseUrlPolicy.requiresApiKey("https://openrouter.ai/api/v1"))
    }

    @Test
    fun givenAnUnusableUrl_whenAskedIfAKeyIsRequired_thenItDefaultsToRequiring() {
        // Failing closed: an unparseable URL must not read as "no credential needed".
        assertTrue(BaseUrlPolicy.requiresApiKey("not a url"))
        assertTrue(BaseUrlPolicy.requiresApiKey(null))
    }

    @Test
    fun givenOpenAi_whenAskingHowToPresentTheKeyField_thenItIsRequired() {
        assertEquals(
            KeyRequirement.REQUIRED,
            BaseUrlPolicy.keyRequirement("https://api.openai.com/v1")
        )
    }

    @Test
    fun givenALocalOrLanServer_whenAskingHowToPresentTheKeyField_thenNoKeyIsNeeded() {
        // Drives collapsing the key block: an empty mandatory-looking field for a server that
        // wants no credential is the pane's most confusing state.
        listOf(
            "http://localhost:11434/v1",
            "http://127.0.0.1:8080/v1",
            "http://192.168.1.50:1234/v1",
            "http://10.0.2.2:11434/v1",
            "http://raspberrypi:11434/v1",
        ).forEach {
            assertEquals("$it needs no key", KeyRequirement.NOT_NEEDED, BaseUrlPolicy.keyRequirement(it))
        }
    }

    @Test
    fun givenAnotherCloudProvider_whenAskingHowToPresentTheKeyField_thenAKeyIsExpected() {
        // OpenRouter needs one, but only it can say so — hence "expected", not "required".
        assertEquals(
            KeyRequirement.EXPECTED,
            BaseUrlPolicy.keyRequirement("https://openrouter.ai/api/v1")
        )
    }

    @Test
    fun givenAnUnusableUrl_whenAskingHowToPresentTheKeyField_thenItFailsClosed() {
        assertEquals(KeyRequirement.REQUIRED, BaseUrlPolicy.keyRequirement(null))
        assertEquals(KeyRequirement.REQUIRED, BaseUrlPolicy.keyRequirement("nonsense"))
    }

    @Test
    fun givenAnyUrl_whenComparedWithRequiresApiKey_thenTheTwoAgree() {
        // requiresApiKey is now derived, so the boolean and the tri-state cannot drift.
        listOf(
            "https://api.openai.com/v1", "http://localhost:11434/v1",
            "https://openrouter.ai/api/v1", null, "nonsense",
        ).forEach { url ->
            assertEquals(
                "disagreement for $url",
                BaseUrlPolicy.keyRequirement(url) == KeyRequirement.REQUIRED,
                BaseUrlPolicy.requiresApiKey(url)
            )
        }
    }

    @Test
    fun givenTheSameServerWrittenTwoWays_whenComparingOrigins_thenTheyMatch() {
        // The path is not part of an origin: a key issued by a host works on any path there.
        assertTrue(
            BaseUrlPolicy.sameOrigin("https://api.openai.com/v1", "https://api.openai.com/v1/")
        )
        assertTrue(BaseUrlPolicy.sameOrigin("https://API.OpenAI.com/v1", "https://api.openai.com"))
    }

    @Test
    fun givenADifferentHostSchemeOrPort_whenComparingOrigins_thenTheyDiffer() {
        assertFalse(
            BaseUrlPolicy.sameOrigin("https://api.openai.com/v1", "http://localhost:11434/v1")
        )
        assertFalse(
            BaseUrlPolicy.sameOrigin("http://localhost:11434/v1", "http://localhost:1234/v1")
        )
        assertFalse(
            BaseUrlPolicy.sameOrigin("https://api.openai.com/v1", "http://api.openai.com/v1")
        )
    }

    @Test
    fun givenAnUnusableUrl_whenComparingOrigins_thenItDoesNotMatch() {
        // Fails closed: an unparseable value must never authorise sending a key.
        assertFalse(BaseUrlPolicy.sameOrigin(null, "https://api.openai.com/v1"))
        assertFalse(BaseUrlPolicy.sameOrigin("nonsense", "nonsense"))
    }
}
