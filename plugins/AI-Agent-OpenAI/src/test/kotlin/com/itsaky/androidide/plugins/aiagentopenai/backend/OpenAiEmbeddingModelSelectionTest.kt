package com.itsaky.androidide.plugins.aiagentopenai.backend

import android.content.SharedPreferences
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiagentopenai.preferences.OpenAiPreferences
import com.itsaky.androidide.plugins.aiagentopenai.settings.BaseUrlPolicy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertThrows
import org.junit.Test

class OpenAiEmbeddingModelSelectionTest {

    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private var stampedFor: String? = BaseUrlPolicy.DEFAULT_BASE_URL
    private val prefs = mockk<SharedPreferences>(relaxed = true) {
        every { edit() } returns editor
        every { getString(OpenAiPreferences.KEY_BASE_URL, any()) } returns null
        every { getString(OpenAiPreferences.KEY_EMBEDDING_MODEL, any()) } returns "text-embedding-3-small"
        every { getString(OpenAiPreferences.KEY_EMBEDDING_MODEL_URL, any()) } answers { stampedFor }
    }
    private val context = mockk<PluginContext>(relaxed = true) {
        every { getPluginSharedPreferences(any()) } returns prefs
    }
    private val backend = OpenAiBackend(context, promptConfig = { null })

    init {
        every { editor.putString(any(), any()) } returns editor
    }

    @Test
    fun givenANewModel_whenSet_thenItIsStoredWithTheServerItWasChosenFor() {
        backend.setEmbeddingModelId("text-embedding-3-large")

        verify { editor.putString(OpenAiPreferences.KEY_EMBEDDING_MODEL, "text-embedding-3-large") }
        verify {
            editor.putString(OpenAiPreferences.KEY_EMBEDDING_MODEL_URL, BaseUrlPolicy.DEFAULT_BASE_URL)
        }
    }

    @Test
    fun givenTheSameModelChosenForAnotherServer_whenSetAgain_thenTheServerIsStampedAgain() {
        // Left stamped for the old server, the settings pane would later retire the user's pick.
        stampedFor = "http://localhost:11434/v1"

        backend.setEmbeddingModelId("text-embedding-3-small")

        verify {
            editor.putString(OpenAiPreferences.KEY_EMBEDDING_MODEL_URL, BaseUrlPolicy.DEFAULT_BASE_URL)
        }
    }

    @Test
    fun givenTheCurrentModelForThisServer_whenSetAgain_thenNothingIsWritten() {
        backend.setEmbeddingModelId("text-embedding-3-small")

        verify(exactly = 0) { editor.putString(any(), any()) }
    }

    @Test
    fun givenABlankModel_whenSet_thenItIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { backend.setEmbeddingModelId("") }
    }
}
