package com.itsaky.androidide.plugins.aicore.prompt

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for [IdeContextReader]'s path handling. */
class IdeContextReaderTest {

    private val root = File("/project")

    @Test
    fun givenAFileUnderTheRoot_whenMadeRelative_thenThePathIsProjectRelative() {
        assertEquals("app/Main.kt", IdeContextReader.relativePath(File("/project/app/Main.kt"), root))
    }

    @Test
    fun givenTheRootItself_whenMadeRelative_thenNoPathIsReturned() {
        // A blank path would otherwise become the example path and an empty "current file".
        assertNull(IdeContextReader.relativePath(root, root))
    }
}
