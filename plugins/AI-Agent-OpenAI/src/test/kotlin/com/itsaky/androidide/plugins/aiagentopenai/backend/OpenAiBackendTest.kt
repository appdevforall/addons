package com.itsaky.androidide.plugins.aiagentopenai.backend

import com.itsaky.androidide.plugins.services.LlmInferenceService.CancellableBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.HistoryCapableBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmBackend
import com.itsaky.androidide.plugins.services.LlmInferenceService.ToolCallingBackend
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this backend declares to the caller. Each interface is optional, so dropping one compiles
 * and degrades silently — which is the only way these regress.
 */
class OpenAiBackendTest {

    private val backend = OpenAiBackend(mockk(relaxed = true))

    @Test
    fun givenTheBackend_whenAskedForItsIdentity_thenItRegistersAsOpenAi() {
        assertEquals("openai", backend.getId())
    }

    @Test
    fun givenTheBackend_whenAskedForItsCapabilities_thenItDeclaresToolCallingToo() {
        // Dropping ToolCallingBackend drops the agent back to parsing calls out of the reply text,
        // and takes the prompt's envelope instructions with it (ADFA-5410).
        val declared: LlmBackend = backend

        assertTrue(declared is HistoryCapableBackend)
        assertTrue(declared is CancellableBackend)
        assertTrue(declared is ToolCallingBackend)
    }
}
