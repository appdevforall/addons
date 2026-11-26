package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WorkDirsTest {
    @Test
    fun everyBuildGetsItsOwnFolder() {
        assertNotEquals(WorkDirs.outDirName(1), WorkDirs.outDirName(2))
        assertEquals("out-5", WorkDirs.outDirName(5))
    }

    @Test
    fun theStaleFoldersAreTheOtherOutputFolders() {
        val stale = WorkDirs.staleOutDirs(listOf("out-3", "out-4", "out-12", "out"), current = "out-4")
        assertEquals(listOf("out-3", "out-12", "out"), stale)
    }

    @Test
    fun theCurrentBuildsFolderIsNeverStale() {
        assertEquals(emptyList<String>(), WorkDirs.staleOutDirs(listOf("out-7"), current = "out-7"))
        assertEquals(listOf("out-70"), WorkDirs.staleOutDirs(listOf("out-7", "out-70"), current = "out-7"))
    }

    @Test
    fun nothingButOutputFoldersIsNamed() {
        val others = listOf("staged", "out-", "out-x", "out-1.bak", "outside", "Out-1", "out--1", "x-out-1", "../out-1", "")
        assertEquals(emptyList<String>(), WorkDirs.staleOutDirs(others, current = "out-9"))
    }
}
