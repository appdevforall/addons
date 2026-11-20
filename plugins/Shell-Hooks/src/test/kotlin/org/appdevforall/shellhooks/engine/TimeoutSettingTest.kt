package org.appdevforall.shellhooks.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeoutSettingTest {
    @Test
    fun aWholeNumberInRangeIsStoredAsTyped() {
        assertEquals(1, TimeoutSetting.parse("1"))
        assertEquals(90, TimeoutSetting.parse(" 90 "))
        assertEquals(3600, TimeoutSetting.parse("3600"))
    }

    @Test
    fun anythingElseIsRefusedRatherThanClamped() {
        listOf("", " ", "0", "-5", "3601", "5000", "6.5", "abc").forEach { assertNull(it, TimeoutSetting.parse(it)) }
    }
}
