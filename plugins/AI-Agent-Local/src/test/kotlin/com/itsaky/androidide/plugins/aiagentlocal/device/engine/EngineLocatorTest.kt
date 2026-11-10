package com.itsaky.androidide.plugins.aiagentlocal.device.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineLocatorTest {

    private val data = File("/data/user/0/example.app")

    @Test
    fun givenTheClassLoaderFindsTheLibrary_whenLocated_thenItsPathIsUsed() {
        val found = mutableListOf<String>()
        val loc = NativeLibEngineLocator({ found.add(it); "/native/lib${it}.so" }, data, "plug", isFile = { true })
        assertEquals(File("/native/libpart1_membw.so"), loc.locate(EngineProgram.MEMBW))
        assertEquals(File("/native/libpart1_gpubw.so"), loc.locate(EngineProgram.GPUBW))
        assertEquals(listOf("part1_membw", "part1_gpubw"), found)
    }

    @Test
    fun givenTheClassLoaderFindsNothing_whenLocated_thenTheHostExtractionDirectoryIsUsed() {
        val loc = NativeLibEngineLocator({ null }, data, "my.plugin", isFile = { it.path == "/data/user/0/example.app/app_plugin_native_libs/my.plugin/libpart1_gpubw.so" })
        assertEquals(File("/data/user/0/example.app/app_plugin_native_libs/my.plugin/libpart1_gpubw.so"), loc.locate(EngineProgram.GPUBW))
        assertNull(loc.locate(EngineProgram.MEMBW))
    }

    @Test
    fun givenTheLoaderPathIsNotAFileOrTheLookupThrows_whenLocated_thenTheFallbackIsTried() {
        val fallback = "/data/user/0/example.app/app_plugin_native_libs/p/libpart1_membw.so"
        val a = NativeLibEngineLocator({ "/gone/libpart1_membw.so" }, data, "p", isFile = { it.path == fallback })
        assertEquals(File(fallback), a.locate(EngineProgram.MEMBW))
        val b = NativeLibEngineLocator({ throw IllegalStateException("no loader") }, data, "p", isFile = { it.path == fallback })
        assertEquals(File(fallback), b.locate(EngineProgram.MEMBW))
        val c = NativeLibEngineLocator({ "" }, data, "p", isFile = { it.path == fallback })
        assertEquals(File(fallback), c.locate(EngineProgram.MEMBW))
    }
}
