package com.itsaky.androidide.plugins.aiagentmcp.debugger

import org.junit.Assert.assertEquals
import org.junit.Test

class RepeatedFailureLogTest {
    @Test
    fun `the first failure is reported in full and the repeats briefly`() {
        val full = mutableListOf<Throwable>()
        val brief = mutableListOf<Throwable>()
        val log = RepeatedFailureLog(first = { full += it }, later = { brief += it })
        val a = SecurityException("a")
        val b = SecurityException("b")
        val c = IllegalStateException("c")

        log.report(a)
        log.report(b)
        log.report(c)

        assertEquals(listOf<Throwable>(a), full)
        assertEquals(listOf<Throwable>(b, c), brief)
    }
}
