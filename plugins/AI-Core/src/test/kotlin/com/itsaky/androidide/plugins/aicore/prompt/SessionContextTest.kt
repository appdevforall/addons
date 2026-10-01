package com.itsaky.androidide.plugins.aicore.prompt

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [SessionContext.current], the clock line every prompt carries. */
class SessionContextTest {

    @Test
    fun givenAZonedMoment_whenReadingTheSession_thenTheDayDateTimeZoneAndOffsetAreStated() {
        val now = ZonedDateTime.of(2026, 9, 25, 14, 3, 0, 0, ZoneId.of("America/Mexico_City"))

        val session = SessionContext.current(now = now)

        assertEquals("Friday, 25 September 2026, 14:03 (America/Mexico_City, UTC-06:00)", session.currentTime)
    }

    @Test
    fun givenUtc_whenReadingTheSession_thenTheOffsetIsWrittenOutRatherThanZ() {
        val now = ZonedDateTime.of(2029, 1, 1, 9, 0, 0, 0, ZoneId.of("UTC"))

        assertEquals("Monday, 1 January 2029, 09:00 (UTC, UTC+00:00)", SessionContext.current(now).currentTime)
    }
}
