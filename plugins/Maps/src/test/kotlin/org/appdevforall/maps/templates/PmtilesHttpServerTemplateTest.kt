package org.appdevforall.maps.templates

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Behaviour of the generated app's loopback tile server. Compiles the shipped Java template
 * (`PmtilesHttpServer.java.peb`) against the unit-test classpath and calls its helpers, so the
 * test exercises the code students get rather than a copy of it.
 */
class PmtilesHttpServerTemplateTest {

    /** A stream whose `skip` always returns 0 although bytes remain, which the API allows. */
    private class NoSkipStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        override fun skip(n: Long): Long = 0
    }

    private fun invoke(method: Method, vararg args: Any?): Any? =
        try {
            method.invoke(null, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    @Test
    fun `skipFully reaches the requested offset when skip returns 0 before the end`() {
        val input: InputStream = NoSkipStream("0123456789".toByteArray())
        invoke(skipFully, input, 4L)
        // A server that stopped at the short skip would stream from offset 0 under a 206 header.
        assertEquals('4'.code, input.read())
    }

    @Test
    fun `skipFully throws at the end of the stream instead of stopping short`() {
        try {
            invoke(skipFully, NoSkipStream("01".toByteArray()), 4L)
            throw AssertionError("expected EOFException")
        } catch (e: EOFException) {
            assertTrue(e.message!!.contains("2 bytes left"))
        }
    }

    @Test
    fun `skipFully with a normal stream and a zero offset leaves the position alone`() {
        val input = ByteArrayInputStream("abc".toByteArray())
        invoke(skipFully, input, 0L)
        assertEquals('a'.code, input.read())
        invoke(skipFully, input, 1L)
        assertEquals('c'.code, input.read())
    }

    companion object {
        private lateinit var outDir: File
        private lateinit var serverClass: Class<*>
        private lateinit var skipFully: Method

        private val template =
            File("src/main/assets/templates/region-map/app/src/main/java/PACKAGE_NAME/PmtilesHttpServer.java.peb")

        @BeforeClass
        @JvmStatic
        fun compileTemplate() {
            outDir = Files.createTempDirectory("pmtiles-server-template").toFile()
            val src = File(outDir, "gen/test/PmtilesHttpServer.java")
            src.parentFile.mkdirs()
            src.writeText(template.readText().replace("\${{PACKAGE_NAME}}", "gen.test"))
            val compiler = ToolProvider.getSystemJavaCompiler()
                ?: error("no system Java compiler; run the tests on a JDK")
            val rc = compiler.run(
                null, null, System.err,
                "-classpath", System.getProperty("java.class.path"),
                "-d", outDir.absolutePath,
                src.absolutePath,
            )
            check(rc == 0) { "PmtilesHttpServer.java.peb did not compile (javac exit $rc)" }
            val loader = URLClassLoader(arrayOf(outDir.toURI().toURL()), PmtilesHttpServerTemplateTest::class.java.classLoader)
            serverClass = loader.loadClass("gen.test.PmtilesHttpServer")
            skipFully = serverClass.getDeclaredMethod("skipFully", InputStream::class.java, Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }

        @AfterClass
        @JvmStatic
        fun cleanup() {
            outDir.deleteRecursively()
        }
    }
}
