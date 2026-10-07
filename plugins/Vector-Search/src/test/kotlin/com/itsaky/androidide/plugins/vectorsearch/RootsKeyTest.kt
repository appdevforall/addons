package com.itsaky.androidide.plugins.vectorsearch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootsKeyTest {

    private val project = File("/projects/app")

    @Test
    fun givenTheSameRootsInAnyOrder_whenKeyed_thenTheKeyIsTheSame() {
        val a = File("/projects/app/a")
        val b = File("/projects/app/b")

        assertEquals(RootsKey.of(listOf(a, b)), RootsKey.of(listOf(b, a)))
    }

    @Test
    fun givenRootsWithinTheProject_whenChecked_thenTheyAreUnderIt() {
        assertTrue(RootsKey.isUnder(RootsKey.of(listOf(project)), project))
        assertTrue(RootsKey.isUnder(RootsKey.of(listOf(File("/projects/app/core"))), project))
    }

    @Test
    fun givenASiblingSharingThePrefix_whenChecked_thenItIsNotUnderTheProject() {
        // Another project's build must not show as progress on this one's screen.
        assertFalse(RootsKey.isUnder(RootsKey.of(listOf(File("/projects/app2"))), project))
        assertFalse(
            RootsKey.isUnder(RootsKey.of(listOf(project, File("/projects/other"))), project)
        )
    }
}
