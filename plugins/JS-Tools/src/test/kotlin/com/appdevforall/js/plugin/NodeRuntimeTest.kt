package com.appdevforall.js.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NodeRuntimeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val lock = """
        arm64-v8a	deb	nodejs-lts	24.18.0-1	https://packages.termux.dev/apt/termux-main/pool/main/n/nodejs-lts/nodejs-lts_24.18.0-1_aarch64.deb	aaaa
        arm64-v8a	deb	openssl	1:3.6.5	https://packages.termux.dev/apt/termux-main/pool/main/o/openssl/openssl_1%3A3.6.5_aarch64.deb	bbbb
        armeabi-v7a	deb	nodejs-lts	24.18.0-1	https://packages.termux.dev/apt/termux-main/pool/main/n/nodejs-lts/nodejs-lts_24.18.0-1_arm.deb	ffff
        all	npm	typescript	6.0.3	https://registry.npmjs.org/typescript/-/typescript-6.0.3.tgz	cccc
        all	types	@types/node	24.19.1	https://registry.npmjs.org/@types/node/-/node-24.19.1.tgz	dddd
    """.trimIndent()

    private val entries = BundleEntry.parseLock(lock)
    private val deviceEntries = BundleEntry.forDevice(entries, "arm64-v8a")

    private fun runtime() = NodeRuntime(File(folder.root, "plugin"), File(folder.root, "usr"))

    @Test
    fun `the lock parses into entries with asset file names`() {
        assertEquals(5, entries.size)
        assertEquals(listOf("nodejs-lts.deb", "openssl.deb", "nodejs-lts.deb", "typescript.tgz", "types-node.tgz"), entries.map { it.fileName })
        assertEquals("1:3.6.5", entries[1].version)
    }

    @Test
    fun `a device installs its own abi and the abi-independent packages, never the template types`() {
        assertEquals(listOf("nodejs-lts" to "arm64-v8a", "openssl" to "arm64-v8a", "typescript" to "all"), deviceEntries.map { it.name to it.abi })
    }

    @Test
    fun `a malformed lock line fails`() {
        assertThrows(IllegalArgumentException::class.java) { BundleEntry.parseLock("arm64-v8a\tdeb\tnodejs") }
    }

    @Test
    fun `the install script never downloads and verifies every bundled archive`() {
        val script = runtime().installScript(deviceEntries)

        assertFalse(script.contains("curl"))
        assertFalse(script.contains("https://"))
        assertEquals(3, script.lines().count { it.contains("| sha256sum -c -") })
        assertEquals(2, script.lines().count { it.startsWith("dpkg-deb -x ") })
        assertEquals(1, script.lines().count { it.startsWith("tar -xzf ") })
        assertTrue(script.lines().first() == "set -e")
    }

    @Test
    fun `the install script moves node out of the termux prefix and npm packages into node_modules`() {
        val runtime = runtime()
        val script = runtime.installScript(deviceEntries)

        assertTrue(script.contains("root/data/data/com.termux/files/usr' '${runtime.home.absolutePath}/node'"))
        assertTrue(script.contains("packages/typescript/package' '${runtime.home.absolutePath}/node_modules/typescript'"))
        assertTrue(script.indexOf("rm -rf '${runtime.home.absolutePath}'") < script.indexOf("node_modules/typescript'"))
        assertTrue(script.lines().last().startsWith("rm -rf "))
    }

    @Test
    fun `type declarations are not something the device installs`() {
        assertThrows(IllegalArgumentException::class.java) { runtime().installScript(entries) }
    }

    @Test
    fun `launchers put the bundled libraries first and mark the install`() {
        val runtime = runtime()
        assertFalse(runtime.isInstalled(deviceEntries))

        runtime.writeLaunchers(deviceEntries)

        val node = runtime.node.readText()
        assertTrue(node.startsWith("#!/system/bin/sh\n"))
        assertTrue(node.contains("export LD_LIBRARY_PATH='${runtime.home.absolutePath}/node/lib'\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"))
        assertTrue(node.contains("exec '${runtime.home.absolutePath}/node/bin/node' \"\$@\""))
        assertTrue(runtime.npm.readText().contains("npm/bin/npm-cli.js"))
        assertTrue(runtime.tsc.readText().contains("'${runtime.home.absolutePath}/node_modules/typescript/bin/tsc'"))
        assertTrue(runtime.languageServer.readText().contains("'${runtime.home.absolutePath}/node_modules/typescript-language-server/lib/cli.mjs'"))
        assertEquals(listOf(runtime.languageServer.absolutePath, "--stdio"), runtime.languageServerCommand())
        assertTrue(runtime.node.canExecute())
        assertTrue(runtime.isInstalled(deviceEntries))
    }

    @Test
    fun `a new bundle makes the old install stale`() {
        val runtime = runtime()
        runtime.writeLaunchers(deviceEntries)

        assertFalse(runtime.isInstalled(deviceEntries.map { if (it.name == "nodejs-lts") it.copy(sha256 = "eeee") else it }))
    }

    @Test
    fun `the environment puts the launchers on the path and keeps npm inside the plugin`() {
        val runtime = runtime()
        val environment = runtime.environment(File("/tmp/x"))

        assertEquals("${runtime.bin.absolutePath}:/system/bin", environment.getValue("PATH"))
        assertEquals("/tmp/x", environment.getValue("TMPDIR"))
        assertTrue(environment.getValue("npm_config_cache").startsWith(File(folder.root, "plugin").absolutePath))
        assertEquals(File(folder.root, "usr/bin/sh").absolutePath, environment.getValue("npm_config_script_shell"))
        assertEquals(File(folder.root, "usr/etc/tls/cert.pem").absolutePath, environment.getValue("SSL_CERT_FILE"))
    }

    @Test
    fun `shell quoting survives a single quote`() {
        assertEquals("'it'\\''s'", NodeRuntime.q("it's"))
    }
}
