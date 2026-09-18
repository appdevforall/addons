package com.itsaky.androidide.plugins.aiagentgemini.backend

import android.net.TrafficStats
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * [TrafficStats] is stubbed rather than letting `isReturnDefaultValues` mock every Android API,
 * which would silently change behaviour for the rest of the suite.
 */
class NetworkTagsTest {

    private var currentTag = UNTAGGED

    @Before
    fun setup() {
        mockkStatic(TrafficStats::class)
        every { TrafficStats.getThreadStatsTag() } answers { currentTag }
        every { TrafficStats.setThreadStatsTag(any()) } answers { currentTag = firstArg() }
    }

    @After
    fun tearDown() {
        unmockkStatic(TrafficStats::class)
    }

    @Test
    fun givenATag_whenRunningABlock_thenTheTagIsSetForTheBlockAndItsValueReturned() {
        var tagDuringBlock = UNTAGGED

        val result = withTrafficTag(NetworkTags.INFERENCE) {
            tagDuringBlock = TrafficStats.getThreadStatsTag()
            "done"
        }

        assertEquals(NetworkTags.INFERENCE, tagDuringBlock)
        assertEquals("done", result)
    }

    @Test
    fun givenABlockThatCompletes_whenItReturns_thenThePreviousThreadStatsTagIsRestored() {
        // A tag left behind would be charged to whatever this shared Dispatchers.IO thread
        // does next, which is the very defect this helper exists to fix.
        withTrafficTag(NetworkTags.CATALOG) {}

        assertEquals(UNTAGGED, currentTag)
    }

    @Test
    fun givenABlockThatThrows_whenRunningIt_thenThePreviousThreadStatsTagIsStillRestored() {
        assertThrows(IllegalStateException::class.java) {
            withTrafficTag(NetworkTags.INFERENCE) { throw IllegalStateException("boom") }
        }

        assertEquals(UNTAGGED, currentTag)
    }

    @Test
    fun givenAnEnclosingTag_whenANestedBlockReturns_thenTheEnclosingTagIsRestored() {
        withTrafficTag(NetworkTags.INFERENCE) {
            withTrafficTag(NetworkTags.CATALOG) {}

            assertEquals(NetworkTags.INFERENCE, currentTag)
        }

        assertEquals(UNTAGGED, currentTag)
    }

    private companion object {
        /** The platform's "no tag" value, which an untouched thread reports. */
        const val UNTAGGED = -1
    }
}
