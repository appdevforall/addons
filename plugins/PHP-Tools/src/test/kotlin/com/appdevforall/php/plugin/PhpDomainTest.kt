package com.appdevforall.php.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhpDomainTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun file(path: String, text: String = ""): File =
        File(folder.root, path).apply {
            parentFile.mkdirs()
            writeText(text)
        }

    @Test
    fun `a composer json or a php file in the root, src, or public marks the project`() {
        assertFalse(PhpDomain.isPhpProject(folder.root))

        file("public/index.php")
        assertTrue(PhpDomain.isPhpProject(folder.root))
    }

    @Test
    fun `a gradle project is never claimed, even with php in it`() {
        file("composer.json", "{}")
        file("settings.gradle")

        assertFalse(PhpDomain.isPhpProject(folder.root))
    }

    @Test
    fun `a php start script runs php directly`() {
        file("composer.json", """{ "scripts": { "start": "php -S localhost:9000 -t public" } }""")

        assertEquals(RunTarget.Php(listOf("-S", "localhost:9000", "-t", "public")), PhpDomain.runTarget(folder.root))
    }

    @Test
    fun `any other start script runs through composer`() {
        file("composer.json", """{ "scripts": { "start": "php artisan serve && echo done" } }""")

        assertEquals(RunTarget.Script("start"), PhpDomain.runTarget(folder.root))
    }

    @Test
    fun `without a start script a public index starts the web server`() {
        file("composer.json", """{ "name": "a/b" }""")
        file("public/index.php")

        assertEquals(RunTarget.Php(listOf("-S", "localhost:8080", "-t", "public")), PhpDomain.runTarget(folder.root))
    }

    @Test
    fun `otherwise the first entry file runs as a program`() {
        file("src/main.php")
        file("main.php")

        assertEquals(RunTarget.Php(listOf("main.php")), PhpDomain.runTarget(folder.root))
    }

    @Test
    fun `nothing to run gives no target`() {
        file("src/Helper.php")

        assertNull(PhpDomain.runTarget(folder.root))
    }

    @Test
    fun `a broken composer json is reported, not ignored`() {
        file("composer.json", """{ "scripts": """)
        file("index.php")

        val target = PhpDomain.runTarget(folder.root)
        assertTrue(target is RunTarget.Invalid)
        assertTrue((target as RunTarget.Invalid).reason.startsWith("composer.json is not valid JSON"))
    }

    @Test
    fun `tests prefer phpunit, then a test script`() {
        assertFalse(PhpDomain.hasPhpUnit(folder.root))
        assertFalse(PhpDomain.hasTestScript(folder.root))

        file("composer.json", """{ "scripts": { "test": "phpunit" } }""")
        assertTrue(PhpDomain.hasTestScript(folder.root))

        file("vendor/bin/phpunit")
        assertTrue(PhpDomain.hasPhpUnit(folder.root))
    }

    @Test
    fun `only php files are runnable`() {
        assertTrue(PhpDomain.isRunnable(File("a.php")))
        assertFalse(PhpDomain.isRunnable(File("a.phtml")))
        assertFalse(PhpDomain.isRunnable(File("a.js")))
    }
}
