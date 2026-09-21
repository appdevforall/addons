package com.itsaky.androidide.plugins.aiagentopenai.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reasoning-model parameter rules. Getting these wrong 400s on exactly the models users most
 * want, which is why the retry path is covered as carefully as the initial choice.
 */
class RequestTuningTest {

    @Test
    fun givenAReasoningModel_whenAskedIfItReasons_thenItDoes() {
        listOf("gpt-5", "gpt-5.1", "gpt-5-mini", "o1", "o1-preview", "o3-mini", "o4-mini")
            .forEach { assertTrue("$it should be a reasoning model", RequestTuning.isReasoningModel(it)) }
    }

    @Test
    fun givenAnOrdinaryModel_whenAskedIfItReasons_thenItDoesNot() {
        listOf("gpt-4o", "gpt-4.1", "qwen2.5-coder", "llama3.2", "mistral-small")
            .forEach { assertFalse("$it should not reason", RequestTuning.isReasoningModel(it)) }
    }

    @Test
    fun givenAVendorPrefixedId_whenAskedIfItReasons_thenThePrefixIsIgnored() {
        // OpenRouter ids carry a vendor segment; the model name is what matters.
        assertTrue(RequestTuning.isReasoningModel("openai/gpt-5.1"))
        assertFalse(RequestTuning.isReasoningModel("meta-llama/llama-3.3-70b"))
    }

    @Test
    fun givenAnIdMerelyStartingWithThoseLetters_whenAskedIfItReasons_thenItDoesNot() {
        // A prefix must end the id or a segment, or `o1ntel` would be misread as `o1`.
        assertFalse(RequestTuning.isReasoningModel("o1ntel-chat"))
        assertFalse(RequestTuning.isReasoningModel("gpt-55-turbo"))
    }

    @Test
    fun givenMixedCase_whenAskedIfItReasons_thenTheCheckIsCaseInsensitive() {
        assertTrue(RequestTuning.isReasoningModel("GPT-5"))
    }

    @Test
    fun givenAReasoningModelOnOpenAi_whenTuned_thenTemperatureIsOmitted() {
        val tuning = RequestTuning.forModel("gpt-5", requiresApiKey = true)
        assertFalse(tuning.sendTemperature)
        assertEquals(RequestTuning.MAX_COMPLETION_TOKENS, tuning.tokenParam)
    }

    @Test
    fun givenAnOrdinaryModelOnOpenAi_whenTuned_thenTemperatureIsSent() {
        val tuning = RequestTuning.forModel("gpt-4o", requiresApiKey = true)
        assertTrue(tuning.sendTemperature)
        assertEquals(RequestTuning.MAX_COMPLETION_TOKENS, tuning.tokenParam)
    }

    @Test
    fun givenALocalServer_whenTuned_thenTheLegacyTokenParamIsUsed() {
        // Ollama, LM Studio and llama-server implement max_tokens, not max_completion_tokens.
        val tuning = RequestTuning.forModel("qwen2.5-coder", requiresApiKey = false)
        assertEquals(RequestTuning.MAX_TOKENS, tuning.tokenParam)
        assertTrue(tuning.sendTemperature)
    }

    @Test
    fun givenTemperatureIsSent_whenDroppingIt_thenItIsOmitted() {
        val tuning = RequestTuning(RequestTuning.MAX_TOKENS, sendTemperature = true)
        val adjusted = tuning.without(RequestTuning.TEMPERATURE)
        assertFalse(adjusted!!.sendTemperature)
    }

    @Test
    fun givenTemperatureIsAlreadyOmitted_whenDroppingIt_thenThereIsNothingToChange() {
        val tuning = RequestTuning(RequestTuning.MAX_TOKENS, sendTemperature = false)
        assertNull(tuning.without(RequestTuning.TEMPERATURE))
    }

    @Test
    fun givenTheModernTokenParam_whenTheServerRejectsIt_thenItDegradesToTheLegacyOne() {
        val tuning = RequestTuning(RequestTuning.MAX_COMPLETION_TOKENS, sendTemperature = true)
        val adjusted = tuning.without(RequestTuning.MAX_COMPLETION_TOKENS)
        assertEquals(RequestTuning.MAX_TOKENS, adjusted!!.tokenParam)
    }

    @Test
    fun givenTheLegacyTokenParam_whenTheServerRejectsIt_thenItUpgradesToTheModernOne() {
        val tuning = RequestTuning(RequestTuning.MAX_TOKENS, sendTemperature = true)
        val adjusted = tuning.without(RequestTuning.MAX_TOKENS)
        assertEquals(RequestTuning.MAX_COMPLETION_TOKENS, adjusted!!.tokenParam)
    }

    @Test
    fun givenAnUnrelatedParam_whenDroppingIt_thenThereIsNothingToChange() {
        val tuning = RequestTuning(RequestTuning.MAX_TOKENS, sendTemperature = true)
        assertNull(tuning.without("top_p"))
    }
}

/**
 * Reading the offending parameter out of a 400 body. A false positive here costs a wasted retry; a
 * false negative surfaces the raw server error to the user.
 */
class UnsupportedParameterTest {

    @Test
    fun givenOpenAisReasoningModelError_whenParsed_thenMaxTokensIsNamed() {
        // The real wording names the offender first and the replacement second, so a
        // longest-match rule would retry without the wrong one and 400 again.
        val body = """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported""" +
            """ with this model. Use 'max_completion_tokens' instead.","type":""" +
            """"invalid_request_error","param":"max_tokens","code":"unsupported_parameter"}}"""
        assertEquals(RequestTuning.MAX_TOKENS, UnsupportedParameter.nameIn(body))
    }

    @Test
    fun givenThatErrorWithAnUnparseableBody_whenParsed_thenTheEarliestMentionWins() {
        // A proxy can truncate the body, leaving no readable `param` field to trust.
        val body = "Unsupported parameter: 'max_tokens' is not supported with this model." +
            " Use 'max_completion_tokens' inste"
        assertEquals(RequestTuning.MAX_TOKENS, UnsupportedParameter.nameIn(body))
    }

    @Test
    fun givenATemperatureRejection_whenParsed_thenTemperatureIsNamed() {
        val body = """{"error":{"message":"Unsupported value: 'temperature' does not support""" +
            """ 0.7 with this model.","param":"temperature","code":"unsupported_value"}}"""
        assertEquals(RequestTuning.TEMPERATURE, UnsupportedParameter.nameIn(body))
    }

    @Test
    fun givenAModernParamRejection_whenParsed_thenThatParamIsNamed() {
        // The legacy direction: a server that only knows max_tokens refuses the modern name.
        val body =
            """{"error":{"message":"unrecognized request argument supplied: max_completion_tokens"}}"""
        assertEquals(RequestTuning.MAX_COMPLETION_TOKENS, UnsupportedParameter.nameIn(body))
    }

    @Test
    fun givenAThirdPartyServerWording_whenParsed_thenTheParamIsStillFound() {
        val body = """{"error":{"message":"Extra inputs are not permitted: max_tokens"}}"""
        assertEquals(RequestTuning.MAX_TOKENS, UnsupportedParameter.nameIn(body))
    }

    @Test
    fun givenAValueErrorRatherThanAnUnsupportedOne_whenParsed_thenNothingIsNamed() {
        // The parameter is accepted; its value is bad. Retrying without it would not help.
        val body =
            """{"error":{"message":"max_tokens must be greater than 0","param":"max_tokens"}}"""
        assertNull(UnsupportedParameter.nameIn(body))
    }

    @Test
    fun givenAnUnrelatedError_whenParsed_thenNothingIsNamed() {
        assertNull(UnsupportedParameter.nameIn("""{"error":{"message":"model not found"}}"""))
    }

    @Test
    fun givenNoBody_whenParsed_thenNothingIsNamed() {
        assertNull(UnsupportedParameter.nameIn(null))
        assertNull(UnsupportedParameter.nameIn(""))
    }

    @Test
    fun givenAServerThatRefusesToolDeclarations_whenClassified_thenTheRetryDropsThem() {
        val body = """{"error":{"message":"Unsupported parameter: 'tools'","param":"tools"}}"""
        assertTrue(UnsupportedTools.rejectedIn(400, body))
        // Some compatible servers answer an unknown field with 422 instead.
        assertTrue(UnsupportedTools.rejectedIn(422, """{"error":"unknown field: functions"}"""))
    }

    @Test
    fun givenAFailureAboutSomethingElse_whenClassified_thenToolsAreKept() {
        // Dropping tools changes which protocol the run uses, so only a refusal of the
        // declaration itself may trigger it.
        assertFalse(UnsupportedTools.rejectedIn(400, """{"error":{"message":"model not found"}}"""))
        assertFalse(
            UnsupportedTools.rejectedIn(
                400,
                """{"error":{"message":"Unsupported parameter: 'temperature'"}}"""
            )
        )
        assertFalse(UnsupportedTools.rejectedIn(401, """{"error":"unsupported tools"}"""))
        assertFalse(UnsupportedTools.rejectedIn(400, null))
    }
}
