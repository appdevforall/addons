package com.itsaky.androidide.plugins.aiagentopenai.settings

import com.itsaky.androidide.plugins.aiagentopenai.settings.ContextWindowSetting.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The context-window field: blank is automatic, and only a sane whole number is ever stored. */
class ContextWindowSettingTest {

    @Test
    fun givenBlankInput_whenParsed_thenItIsAutomatic() {
        assertEquals(ContextWindowSetting.Automatic, ContextWindowSetting.parse(""))
        assertEquals(ContextWindowSetting.Automatic, ContextWindowSetting.parse("   "))
        assertEquals(ContextWindowSetting.Automatic, ContextWindowSetting.parse(null))
    }

    @Test
    fun givenAWholeNumber_whenParsed_thenItIsTheTokenCount() {
        assertEquals(ContextWindowSetting.Tokens(8_192), ContextWindowSetting.parse(" 8192 "))
    }

    @Test
    fun givenGroupingSeparators_whenParsed_thenTheyAreIgnored() {
        assertEquals(ContextWindowSetting.Tokens(128_000), ContextWindowSetting.parse("128,000"))
        assertEquals(ContextWindowSetting.Tokens(128_000), ContextWindowSetting.parse("128 000"))
        assertEquals(ContextWindowSetting.Tokens(128_000), ContextWindowSetting.parse("128_000"))
    }

    @Test
    fun givenText_whenParsed_thenItIsRejectedAsNotANumber() {
        assertEquals(ContextWindowSetting.Rejected(Reason.NOT_A_NUMBER), ContextWindowSetting.parse("8k"))
        assertEquals(ContextWindowSetting.Rejected(Reason.NOT_A_NUMBER), ContextWindowSetting.parse("-4096"))
        assertEquals(ContextWindowSetting.Rejected(Reason.NOT_A_NUMBER), ContextWindowSetting.parse("4096.5"))
    }

    @Test
    fun givenTooFewTokens_whenParsed_thenItIsRejectedAsTooSmall() {
        assertEquals(ContextWindowSetting.Rejected(Reason.TOO_SMALL), ContextWindowSetting.parse("0"))
        assertEquals(ContextWindowSetting.Rejected(Reason.TOO_SMALL), ContextWindowSetting.parse("511"))
    }

    @Test
    fun givenTheLimits_whenParsed_thenBothAreAccepted() {
        assertEquals(ContextWindowSetting.Tokens(512), ContextWindowSetting.parse("512"))
        assertEquals(ContextWindowSetting.Tokens(10_000_000), ContextWindowSetting.parse("10000000"))
    }

    @Test
    fun givenTooManyTokens_whenParsed_thenItIsRejectedAsTooLarge() {
        assertEquals(ContextWindowSetting.Rejected(Reason.TOO_LARGE), ContextWindowSetting.parse("10000001"))
        assertEquals(ContextWindowSetting.Rejected(Reason.TOO_LARGE), ContextWindowSetting.parse("99999999999"))
    }

    @Test
    fun givenAnOutOfRangeStoredValue_whenRead_thenItCountsAsAutomatic() {
        assertNull(ContextWindowSetting.storedOrNull(0))
        assertNull(ContextWindowSetting.storedOrNull(20_000_000))
        assertEquals(4_096, ContextWindowSetting.storedOrNull(4_096))
    }
}
