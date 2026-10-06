package com.itsaky.androidide.plugins.aiagentopenai.backend

import com.itsaky.androidide.plugins.services.CapabilityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests what a server check reports, and when the Agent's tag is told about it. */
class ServerHealthTest {

    private companion object {
        const val LM_STUDIO = "http://localhost:1234/v1"
        const val OLLAMA = "http://localhost:11434/v1"
    }

    @Test
    fun givenNoCheckYet_whenRead_thenItIsConnecting() {
        val health = ServerHealth {}

        assertEquals(CapabilityStatus.CONNECTING, health.readingFor(LM_STUDIO).status)
    }

    @Test
    fun givenAReadingForAnotherServer_whenRead_thenItDoesNotApply() {
        val health = ServerHealth {}
        health.record(HealthReading.unreachable(OLLAMA))

        assertEquals(CapabilityStatus.CONNECTING, health.readingFor(LM_STUDIO).status)
    }

    @Test
    fun givenTheSameReadingTwice_whenRecorded_thenTheTagIsToldOnce() {
        var told = 0
        val health = ServerHealth { told++ }

        health.record(HealthReading.unreachable(LM_STUDIO))
        health.record(HealthReading.unreachable(LM_STUDIO))
        health.record(HealthReading.available(LM_STUDIO))

        assertEquals(2, told)
    }

    @Test
    fun givenAServerWithoutACatalog_whenItAnswers404_thenItIsAvailable() {
        val reading = HealthReading.ofHttpStatus(LM_STUDIO, 404)

        assertEquals(CapabilityStatus.AVAILABLE, reading.status)
        assertNull(reading.problem)
    }

    @Test
    fun givenARefusedKey_whenItAnswers401_thenItIsDegradedForTheKey() {
        val reading = HealthReading.ofHttpStatus(LM_STUDIO, 401)

        assertEquals(CapabilityStatus.DEGRADED, reading.status)
        assertEquals(ServerProblem.KEY_REFUSED, reading.problem)
    }

    @Test
    fun givenAFailingServer_whenItAnswers503_thenItIsDegradedWithTheStatus() {
        val reading = HealthReading.ofHttpStatus(LM_STUDIO, 503)

        assertEquals(ServerProblem.SERVER_ERROR, reading.problem)
        assertEquals(503, reading.httpStatus)
    }
}
