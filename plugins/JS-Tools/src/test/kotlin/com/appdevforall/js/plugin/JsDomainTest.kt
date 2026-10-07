package com.appdevforall.js.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JsDomainTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(path: String, text: String = ""): File =
        File(folder.root, path).apply {
            parentFile.mkdirs()
            writeText(text)
        }

    @Test
    fun `a package json, a tsconfig, or a source file in the root or src marks the project`() {
        assertFalse(JsDomain.isJsProject(folder.root))

        file("src/index.ts")
        assertTrue(JsDomain.isJsProject(folder.root))
    }

    @Test
    fun `a gradle project is never claimed, even with javascript in it`() {
        file("package.json", "{}")
        file("build.gradle.kts")

        assertFalse(JsDomain.isJsProject(folder.root))
    }

    @Test
    fun `run app follows a node start script to its file`() {
        file("package.json", """{ "scripts": { "start": "node src/server.ts" }, "main": "src/index.js" }""")
        val server = file("src/server.ts")
        file("src/index.js")

        assertEquals(RunTarget.Source(server), JsDomain.runTarget(folder.root))
    }

    @Test
    fun `any other start script runs through npm`() {
        file("package.json", """{ "scripts": { "start": "vite --host" } }""")

        assertEquals(RunTarget.Script("start"), JsDomain.runTarget(folder.root))
    }

    @Test
    fun `without a start script run app uses main, then an index file`() {
        file("package.json", """{ "main": "lib/app.mjs" }""")
        val app = file("lib/app.mjs")
        assertEquals(RunTarget.Source(app), JsDomain.runTarget(folder.root))

        file("package.json", "{}")
        val index = file("src/index.ts")
        assertEquals(RunTarget.Source(index), JsDomain.runTarget(folder.root))
    }

    @Test
    fun `a main pointing at a missing file falls through to the index file`() {
        file("package.json", """{ "main": "dist/index.js" }""")
        val index = file("index.js")

        assertEquals(RunTarget.Source(index), JsDomain.runTarget(folder.root))
    }

    @Test
    fun `nothing to run gives no target`() {
        file("tsconfig.json", "{}")
        file("src/util.ts")

        assertNull(JsDomain.runTarget(folder.root))
    }

    @Test
    fun `a broken package json is reported, not ignored`() {
        file("package.json", """{ "scripts": """)
        file("index.js")

        val target = JsDomain.runTarget(folder.root)
        assertTrue(target is RunTarget.Invalid)
        assertTrue((target as RunTarget.Invalid).reason.startsWith("package.json is not valid JSON"))
    }

    @Test
    fun `npm's placeholder test script does not count as a test script`() {
        file("package.json", """{ "scripts": { "test": "echo \"Error: no test specified\" && exit 1" } }""")
        assertNull(JsDomain.testScript(folder.root))

        file("package.json", """{ "scripts": { "test": "node --test" } }""")
        assertEquals("node --test", JsDomain.testScript(folder.root))
    }

    @Test
    fun `type check prefers tsconfig over jsconfig`() {
        assertNull(JsDomain.typeCheckConfig(folder.root))

        val jsconfig = file("jsconfig.json", "{}")
        assertEquals(jsconfig, JsDomain.typeCheckConfig(folder.root))

        val tsconfig = file("tsconfig.json", "{}")
        assertEquals(tsconfig, JsDomain.typeCheckConfig(folder.root))
    }

    @Test
    fun `only files node can load directly are runnable`() {
        assertTrue(JsDomain.isRunnable(File("a.ts")))
        assertTrue(JsDomain.isRunnable(File("a.cjs")))
        assertFalse(JsDomain.isRunnable(File("a.tsx")))
        assertFalse(JsDomain.isRunnable(File("a.json")))
    }
}
