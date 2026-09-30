package com.itsaky.androidide.plugins.aicore.prompt

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The run's own facts, stated on every prompt whether or not the IDE has anything open: no model
 * knows today's date, and one never told whether it can reach the web answers "I have no access to
 * real-time information" even to a question it has a tool for (ADFA-6223).
 *
 * @property currentTime the device's date, time and time zone, already worded for the prompt.
 */
data class SessionContext(
    val currentTime: String,
) {

    companion object {

        /** English whatever the device locale, like the rest of the prompt. */
        private val FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm", Locale.US)

        /**
         * Reads the clock now.
         *
         * @param now the moment to state; the device clock in its own zone unless a test pins it.
         * @return the session, e.g. "Friday, 25 September 2026, 14:03 (America/Mexico_City, UTC-06:00)".
         */
        fun current(now: ZonedDateTime = ZonedDateTime.now()): SessionContext {
            // The zone id and the offset both: "what time is it in Tokyo" needs the offset to work from.
            val offset = now.offset.id.let { if (it == "Z") "+00:00" else it }
            return SessionContext("${now.format(FORMAT)} (${now.zone.id}, UTC$offset)")
        }
    }
}
