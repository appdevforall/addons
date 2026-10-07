package com.itsaky.androidide.plugins.vectorsearch

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.BackendChangeListener
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendWatchTest {

    private var selected: String? = "gemini"
    private val listener = slot<BackendChangeListener>()
    private val service = mockk<LlmInferenceService>(relaxed = true) {
        every { preferredBackendId } answers { selected }
        every { addBackendChangeListener(capture(listener)) } just runs
    }
    private var changes = 0
    private val watch = BackendWatch(mockk<PluginContext>(relaxed = true), { service }) { changes++ }

    @Test
    fun givenAnotherBackendsStatusChange_whenReported_thenTheScreenIsNotTold() {
        // Local reports its model loading; the screen describes Gemini and has nothing to re-read.
        watch.start()

        listener.captured.onBackendChanged("local")

        assertEquals(0, changes)
    }

    @Test
    fun givenTheSelectedBackendsChange_whenReported_thenTheScreenIsTold() {
        watch.start()

        listener.captured.onBackendChanged("gemini")

        assertEquals(1, changes)
    }

    @Test
    fun givenANewSelection_whenReported_thenTheScreenIsTold() {
        watch.start()
        selected = "openai"

        listener.captured.onBackendChanged("openai")

        assertEquals(1, changes)
    }

    @Test
    fun givenAWatchingPlugin_whenStopped_thenItsListenerIsRemoved() {
        // AC6: deactivate() removes the listener.
        watch.start()

        watch.stop()

        verify { service.removeBackendChangeListener(listener.captured) }
    }
}
