package org.appdevforall.maps.templates

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLClassLoader
import java.nio.file.Files
import javax.tools.ToolProvider
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun range(spec: String?, total: Long): List<Long>? =
        (invoke(resolveRange, spec, total) as LongArray?)?.toList()

    @Test
    fun `resolveRange serves the last n bytes for a suffix range`() {
        // Ignoring "-500" would answer 200 with the whole file.
        assertEquals(listOf(500L, 999L), range("-500", 1000))
        assertEquals("a suffix longer than the file is the whole file", listOf(0L, 999L), range("-5000", 1000))
        assertNull("a zero-length suffix cannot be satisfied", range("-0", 1000))
    }

    @Test
    fun `resolveRange handles closed, open and out-of-range starts`() {
        assertEquals(listOf(0L, 999L), range(null, 1000))
        assertEquals(listOf(10L, 19L), range("10-19", 1000))
        assertEquals(listOf(10L, 999L), range("10-", 1000))
        assertEquals("an end past the file is clamped", listOf(10L, 999L), range("10-5000", 1000))
        assertNull("a start past the file cannot be satisfied", range("1000-", 1000))
        assertNull("an empty file cannot be served", range(null, 0))
    }

    @Test
    fun `resolveRange treats malformed and multi-range values as the whole file`() {
        for (spec in listOf("abc", "5-x", "1-2,4-5", "20-10", "")) {
            assertEquals("spec '$spec'", listOf(0L, 999L), range(spec, 1000))
        }
    }

    @Test
    fun `a client that sends nothing is dropped after the read timeout`() {
        val server = serverClass.getDeclaredConstructor(android.content.res.AssetManager::class.java, String::class.java)
            .newInstance(null, "maps")
        val handle = serverClass.getDeclaredMethod("handle", Socket::class.java).apply { isAccessible = true }
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            Socket(listener.inetAddress, listener.localPort).use { silent ->
                val accepted = listener.accept()
                val worker = Thread { invokeOn(handle, server, accepted) }.apply { isDaemon = true; start() }
                val timeoutMs = serverClass.getDeclaredField("READ_TIMEOUT_MS").apply { isAccessible = true }.getInt(null)
                worker.join(timeoutMs + 5_000L)
                // Without the timeout the handler blocks in readLine for as long as the client stays.
                assertTrue("handler still blocked on a silent client", !worker.isAlive)
                silent.soTimeout = 1_000
                assertEquals("the server closes the connection", -1, silent.getInputStream().read())
            }
        }
    }

    private fun invokeOn(method: Method, target: Any, vararg args: Any?): Any? =
        try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    companion object {

        private lateinit var outDir: File
        private lateinit var serverClass: Class<*>
        private lateinit var skipFully: Method
        private lateinit var resolveRange: Method

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
            resolveRange = serverClass.getDeclaredMethod("resolveRange", String::class.java, Long::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }

        @AfterClass
        @JvmStatic
        fun cleanup() {
            outDir.deleteRecursively()
        }
    }
}
