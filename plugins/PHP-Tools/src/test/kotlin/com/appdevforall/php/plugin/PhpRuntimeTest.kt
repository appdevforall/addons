package com.appdevforall.php.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PhpRuntimeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val lock = listOf(
        "arm64-v8a\tdeb\tphp\t8.5.1\thttps://packages.termux.dev/apt/termux-main/pool/main/p/php/php_8.5.1_aarch64.deb\taaaa",
        "all\tdeb\tcomposer\t2.10.3\thttps://packages.termux.dev/apt/termux-main/pool/main/c/composer/composer_2.10.3_all.deb\tbbbb",
        "armeabi-v7a\tdeb\tphp\t8.5.1\thttps://packages.termux.dev/apt/termux-main/pool/main/p/php/php_8.5.1_arm.deb\tcccc",
        "all\tphar\tphpactor\t2026.10.07.0\thttps://github.com/phpactor/phpactor/releases/download/2026.10.07.0/phpactor.phar\tdddd",
    ).joinToString("\n")

    private val entries = BundleEntry.parseLock(lock)
    private val deviceEntries = BundleEntry.forDevice(entries, "arm64-v8a")

    private fun runtime() = PhpRuntime(File(folder.root, "plugin"), File(folder.root, "usr/tmp"))

    @Test
    fun `a device installs its own abi and the portable packages`() {
        assertEquals(listOf("php.deb", "composer.deb", "phpactor.phar"), deviceEntries.map { it.fileName })
    }

    @Test
    fun `a malformed lock line fails`() {
        assertThrows(IllegalArgumentException::class.java) { BundleEntry.parseLock("all\tphar\tphpactor") }
    }

    @Test
    fun `the install script verifies every archive and never downloads`() {
        val script = runtime().installScript(deviceEntries)

        assertFalse(script.contains("curl"))
        assertFalse(script.contains("https://"))
        assertEquals(3, script.lines().count { it.contains("| sha256sum -c -") })
        assertEquals(2, script.lines().count { it.startsWith("dpkg-deb -x ") })
        assertEquals("set -e", script.lines().first())
    }

    @Test
    fun `the install script moves php out of the termux prefix, drops what is unused, and places the phar`() {
        val runtime = runtime()
        val script = runtime.installScript(deviceEntries)

        assertTrue(script.contains("root/data/data/com.termux/files/usr' '${runtime.home.absolutePath}/php'"))
        assertTrue(script.contains("'${runtime.home.absolutePath}/php/bin/php-cgi'"))
        assertTrue(script.contains("archives/phpactor.phar' '${runtime.home.absolutePath}/phpactor.phar'"))
        assertTrue(script.indexOf("rm -rf '${runtime.home.absolutePath}'\n") < script.indexOf("phpactor.phar' '"))
    }

    @Test
    fun `launchers load the bundled libraries and the plugin's php ini`() {
        val runtime = runtime()
        assertFalse(runtime.isInstalled(deviceEntries))

        runtime.writeLaunchers(deviceEntries)

        val php = runtime.php.readText()
        assertTrue(php.startsWith("#!/system/bin/sh\n"))
        assertTrue(php.contains("export LD_LIBRARY_PATH='${runtime.home.absolutePath}/php/lib'"))
        assertTrue(php.contains("export PHPRC='${runtime.home.absolutePath}/etc'"))
        assertTrue(php.contains("export PHP_INI_SCAN_DIR="))
        assertTrue(runtime.composer.readText().contains("'${runtime.home.absolutePath}/php/bin/composer'"))
        assertTrue(runtime.phpactor.readText().contains("'${runtime.home.absolutePath}/phpactor.phar'"))
        assertTrue(runtime.isInstalled(deviceEntries))
    }

    @Test
    fun `the ini moves the paths termux compiled in`() {
        val runtime = runtime()
        runtime.writeLaunchers(deviceEntries)

        val ini = runtime.ini.readText()
        val tmp = File(folder.root, "usr/tmp").absolutePath
        assertTrue(ini.contains("opcache.lockfile_path=\"$tmp\""))
        assertTrue(ini.contains("sys_temp_dir=\"$tmp\""))
        assertTrue(ini.contains("openssl.cafile=\"${File(folder.root, "usr/etc/tls/cert.pem").absolutePath}\""))
    }

    @Test
    fun `the shell termux compiled into php is relocated to the system shell, and only whole strings are`() {
        val runtime = runtime()
        val shell = "/data/data/com.termux/files/usr/bin/sh".toByteArray()
        val binary = File(runtime.home, "php/bin/php").apply { parentFile.mkdirs() }
        binary.writeBytes(byteArrayOf(1, 0) + shell + byteArrayOf(0, 7) + "x".toByteArray() + shell + byteArrayOf(0))

        assertEquals(listOf(2), PhpRuntime.shellPathOffsets(binary.readBytes()))

        runtime.relocateShell()

        val patched = binary.readBytes()
        val relocated = "/system/bin/sh".toByteArray()
        assertEquals(relocated.toList(), patched.slice(2 until 2 + relocated.size))
        assertTrue(patched.slice(2 + relocated.size..2 + shell.size).all { it == 0.toByte() })
        assertEquals(shell.toList(), patched.slice(2 + shell.size + 3 until 2 + shell.size + 3 + shell.size))
        assertEquals(binary.length(), (2 + shell.size + 3 + shell.size + 1).toLong())
    }

    @Test
    fun `a php build without the termux shell fails the install`() {
        val runtime = runtime()
        File(runtime.home, "php/bin/php").apply {
            parentFile.mkdirs()
            writeBytes("no shell here".toByteArray())
        }

        assertThrows(java.io.IOException::class.java) { runtime.relocateShell() }
    }

    @Test
    fun `a new bundle makes the old install stale`() {
        val runtime = runtime()
        runtime.writeLaunchers(deviceEntries)

        assertFalse(runtime.isInstalled(deviceEntries.map { if (it.name == "php") it.copy(sha256 = "eeee") else it }))
    }

    @Test
    fun `the environment puts the launchers on the path and keeps composer inside the plugin`() {
        val runtime = runtime()
        val environment = runtime.environment()

        assertEquals("${runtime.bin.absolutePath}:/system/bin", environment.getValue("PATH"))
        assertEquals("0", environment.getValue("COMPOSER_PROCESS_TIMEOUT"))
        assertTrue(environment.getValue("COMPOSER_HOME").startsWith(File(folder.root, "plugin").absolutePath))
        assertEquals(listOf(runtime.phpactor.absolutePath, "language-server"), runtime.languageServerCommand())
    }
}
