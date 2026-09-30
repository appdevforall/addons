package com.itsaky.androidide.plugins.aicore.services

import com.itsaky.androidide.plugins.services.LlmInferenceService.BackendChangeListener
import com.itsaky.androidide.plugins.services.LlmInferenceService.LlmBackend
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests the backend-change listeners the chat's capability tags depend on: every register,
 * unregister and reported change reaches them, and nothing else does.
 */
class LlmInferenceServiceListenerTest {

    private lateinit var service: LlmInferenceServiceImpl
    private val heard = mutableListOf<String>()
    private val listener = BackendChangeListener { heard += it }

    @Before
    fun setUp() {
        service = LlmInferenceServiceImpl()
        service.addBackendChangeListener(listener)
    }

    @Test
    fun givenAListener_whenABackendRegistersAndUnregisters_thenItHearsBoth() {
        service.registerBackend(backend("gemini"))
        service.unregisterBackend("gemini")

        assertEquals(listOf("gemini", "gemini"), heard)
    }

    @Test
    fun givenAListener_whenAnUnknownBackendUnregisters_thenItHearsNothing() {
        service.unregisterBackend("nobody")

        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun givenARegisteredBackend_whenItReportsAChange_thenTheListenerHearsItsId() {
        service.registerBackend(backend("local"))
        heard.clear()

        service.notifyBackendChanged("local")

        assertEquals(listOf("local"), heard)
    }

    @Test
    fun givenAnUnregisteredId_whenAChangeIsReported_thenItIsIgnored() {
        service.notifyBackendChanged("nobody")

        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun givenARemovedListener_whenABackendRegisters_thenItIsNotCalled() {
        service.removeBackendChangeListener(listener)

        service.registerBackend(backend("gemini"))

        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun givenTheSameListenerAddedTwice_whenABackendRegisters_thenItIsCalledOnce() {
        service.addBackendChangeListener(listener)

        service.registerBackend(backend("gemini"))

        assertEquals(listOf("gemini"), heard)
    }

    @Test
    fun givenAListenerThatThrows_whenABackendRegisters_thenTheOthersStillHearAndNothingEscapes() {
        val throwing = BackendChangeListener { throw IllegalStateException("boom") }
        service.removeBackendChangeListener(listener)
        service.addBackendChangeListener(throwing)
        service.addBackendChangeListener(listener)

        service.registerBackend(backend("gemini"))

        assertEquals(listOf("gemini"), heard)
    }

    private fun backend(id: String) = mockk<LlmBackend>(relaxed = true) {
        every { getId() } returns id
        every { getName() } returns id
    }
}
