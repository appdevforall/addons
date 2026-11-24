package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NamingTest {
    @Test
    fun outputNameHasNoSpaces() {
        assertEquals("Code-on-the-Go-26.45-custom.apk", Naming.outputName("Code on the Go", "26.45"))
        assertEquals("CodeOnTheGo-v26.45-abc-release-custom.apk", Naming.outputName("CodeOnTheGo", "v26.45-abc-release"))
        assertEquals("App-custom.apk", Naming.outputName("App", null))
        assertEquals("custom.apk", Naming.outputName("///", ""))
    }
}
