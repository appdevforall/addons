package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingTest {
    @Test
    fun outputNameHasNoSpaces() {
        assertEquals("Code-on-the-Go-26.45-custom.apk", Naming.outputName("Code on the Go", "26.45"))
        assertEquals("CodeOnTheGo-v26.45-abc-release-custom.apk", Naming.outputName("CodeOnTheGo", "v26.45-abc-release"))
        assertEquals("App-custom.apk", Naming.outputName("App", null))
        assertEquals("custom.apk", Naming.outputName("///", ""))
    }

    @Test
    fun enteredNameIsCleanedAndEndsInApk() {
        assertEquals("My-copy.apk", Naming.fromEntry("My copy"))
        assertEquals("My-copy.apk", Naming.fromEntry("  My copy.apk "))
        assertEquals("My-copy.apk", Naming.fromEntry("My copy.APK"))
        assertEquals("a-b.apk", Naming.fromEntry("a/b"))
        assertEquals("v1.2.apk", Naming.fromEntry("v1.2"))
        assertEquals(120 + 4, Naming.fromEntry("x".repeat(300))!!.length)
    }

    @Test
    fun enteredNameWithNothingUsableIsRefused() {
        assertNull(Naming.fromEntry(""))
        assertNull(Naming.fromEntry("   "))
        assertNull(Naming.fromEntry("///"))
        assertNull(Naming.fromEntry(".apk"))
        assertNull(Naming.fromEntry(" .. "))
    }

    @Test
    fun takenNamesIgnoreCase() {
        assertTrue(Naming.isTaken("X-custom.apk", listOf("a.txt", "x-CUSTOM.apk")))
        assertFalse(Naming.isTaken("X-custom.apk", listOf("X-custom.apk.bak", "X-custom-2.apk")))
        assertFalse(Naming.isTaken("X-custom.apk", emptyList()))
    }

    @Test
    fun freeNameIsKeptWhenNothingHoldsIt() {
        assertEquals("X-custom.apk", Naming.freeName("X-custom.apk", listOf("other.apk")))
    }

    @Test
    fun freeNameSkipsEveryTakenNumber() {
        assertEquals("X-custom-2.apk", Naming.freeName("X-custom.apk", listOf("X-custom.apk")))
        assertEquals("X-custom-4.apk", Naming.freeName("X-custom.apk", listOf("X-custom.apk", "X-custom-2.apk", "x-custom-3.APK")))
    }

    @Test
    fun freeNameStaysWithinTheLengthLimit() {
        val long = "y".repeat(120) + ".apk"
        val free = Naming.freeName(long, listOf(long))
        assertEquals(120 + 4, free.length)
        assertTrue(free.endsWith("-2.apk"))
    }

    @Test
    fun savedNoteIsEmptyOnlyWhenTheNamesMatch() {
        assertEquals("", Naming.savedNote("X-custom.apk", "X-custom.apk"))
        val renamed = Naming.savedNote("X-custom.apk", "X-custom (1).apk")
        assertTrue(renamed, "X-custom.apk" in renamed && "X-custom (1).apk" in renamed)
        assertTrue(Naming.savedNote("X-custom.apk", null).isNotEmpty())
    }
}
