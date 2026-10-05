package com.itsaky.androidide.plugins.vectorsearch

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferencesIndexBuildLogTest {

    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val stored = mutableMapOf<String, Any?>()
    private val preferences = mockk<SharedPreferences> {
        every { all } answers { stored.toMap() }
        every { edit() } returns editor
    }
    private val log = PreferencesIndexBuildLog { preferences }

    @Test
    fun givenBuildsOfThisProjectAndAnother_whenAsked_thenTheLatestOfThisProjectIsReturned() {
        stored["built_at:/projects/app"] = 100L
        stored["built_at:/projects/app/core"] = 300L
        stored["built_at:/projects/app2"] = 900L

        assertEquals(300L, log.lastBuiltUnder(File("/projects/app")))
    }

    @Test
    fun givenNoRecordedBuild_whenAsked_thenThereIsNoTime() {
        // Rows indexed before this was recorded: the screen says "Not recorded", not a wrong date.
        assertNull(log.lastBuiltUnder(File("/projects/app")))
    }

    @Test
    fun givenABuild_whenRecorded_thenItIsStoredUnderItsRootsKey() {
        every { editor.putLong(any(), any()) } returns editor

        log.recordBuilt("/projects/app", 42L)

        verify { editor.putLong("built_at:/projects/app", 42L) }
    }
}
