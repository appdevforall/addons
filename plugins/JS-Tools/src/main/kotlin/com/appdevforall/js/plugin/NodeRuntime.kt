package com.appdevforall.js.plugin

import java.io.File
import java.io.IOException

internal data class BundleEntry(
    val abi: String,
    val kind: String,
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
) {
    val fileName: String = name.removePrefix("@").replace('/', '-') + if (kind == KIND_DEB) ".deb" else ".tgz"

    companion object {
        const val ALL_ABIS = "all"
        const val KIND_DEB = "deb"
        const val KIND_NPM = "npm"
        const val KIND_TYPES = "types"

        fun parseLock(text: String): List<BundleEntry> =
            text.lines().filter { it.isNotBlank() }.map { line ->
                val fields = line.split('\t')
                require(fields.size == 6) { "Malformed node-bundle.lock line: $line" }
                BundleEntry(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5])
            }

        fun forDevice(entries: List<BundleEntry>, abi: String): List<BundleEntry> =
            entries.filter { (it.abi == abi || it.abi == ALL_ABIS) && it.kind != KIND_TYPES }
    }
}

internal class NodeRuntime(
    private val pluginDir: File,
    private val prefix: File,
) {
    val home = File(pluginDir, "runtime")
    val bin = File(home, "bin")
    val node = File(bin, "node")
    val npm = File(bin, "npm")
    val tsc = File(bin, "tsc")
    val languageServer = File(bin, "typescript-language-server")
    val staging = File(pluginDir, "runtime-staging")
    val archives = File(staging, "archives")

    private val nodeHome = File(home, "node")
    private val packages = File(home, "node_modules")
    private val marker = File(home, "installed")

    val tsserver = File(packages, "typescript/lib/tsserver.js")

    fun isInstalled(entries: List<BundleEntry>): Boolean =
        marker.isFile && marker.readText() == fingerprint(entries) && node.canExecute() && languageServer.canExecute()

    fun installScript(entries: List<BundleEntry>): String {
        val root = File(staging, "root")
        val unpacked = File(staging, "packages")
        val lines = mutableListOf("set -e", "mkdir -p ${q(root)} ${q(unpacked)}")
        val moves = mutableListOf<String>()
        entries.forEach { entry ->
            val archive = File(archives, entry.fileName)
            lines += "echo ${q("${entry.sha256}  ${archive.absolutePath}")} | sha256sum -c - > /dev/null"
            when (entry.kind) {
                BundleEntry.KIND_DEB -> lines += "dpkg-deb -x ${q(archive)} ${q(root)}"
                BundleEntry.KIND_NPM -> {
                    val target = File(unpacked, entry.fileName.removeSuffix(".tgz"))
                    lines += "mkdir -p ${q(target)}"
                    lines += "tar -xzf ${q(archive)} -C ${q(target)}"
                    moves += "mv ${q(File(target, "package"))} ${q(File(packages, entry.name))}"
                }
                else -> throw IllegalArgumentException("${entry.name} has kind ${entry.kind}, which the device does not install")
            }
        }
        lines += "rm -rf ${q(home)}"
        lines += "mkdir -p ${q(packages)}"
        lines += "mv ${q(File(root, TERMUX_PREFIX.removePrefix("/")))} ${q(nodeHome)}"
        lines += moves
        lines += "rm -rf ${q(staging)}"
        return lines.joinToString("\n")
    }

    fun writeLaunchers(entries: List<BundleEntry>) {
        bin.mkdirs()
        launcher(
            node,
            "export LD_LIBRARY_PATH=${q(File(nodeHome, "lib"))}\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}",
            "exec ${q(File(nodeHome, "bin/node"))} \"\$@\"",
        )
        script(npm, File(nodeHome, "lib/node_modules/npm/bin/npm-cli.js"))
        script(File(bin, "npx"), File(nodeHome, "lib/node_modules/npm/bin/npx-cli.js"))
        script(tsc, File(packages, "typescript/bin/tsc"))
        script(languageServer, File(packages, "typescript-language-server/lib/cli.mjs"))
        marker.writeText(fingerprint(entries))
    }

    fun environment(tmp: File): Map<String, String> =
        mapOf(
            "PATH" to "${bin.absolutePath}:$SYSTEM_BIN",
            "TMPDIR" to tmp.absolutePath,
            "SSL_CERT_FILE" to File(prefix, "etc/tls/cert.pem").absolutePath,
            "npm_config_cache" to File(pluginDir, "npm-cache").absolutePath,
            "npm_config_prefix" to File(pluginDir, "npm-global").absolutePath,
            "npm_config_script_shell" to File(prefix, "bin/sh").absolutePath,
            "npm_config_update_notifier" to "false",
            "npm_config_fund" to "false",
            "npm_config_audit" to "false",
        )

    fun languageServerCommand(): List<String> = listOf(languageServer.absolutePath, "--stdio")

    private fun script(
        file: File,
        entryPoint: File,
    ) = launcher(file, "exec ${q(node)} ${q(entryPoint)} \"\$@\"")

    private fun launcher(
        file: File,
        vararg lines: String,
    ) {
        file.writeText((listOf("#!$SYSTEM_BIN/sh") + lines).joinToString("\n", postfix = "\n"))
        if (!file.setExecutable(true, true)) throw IOException("Could not make $file executable")
    }

    companion object {
        private const val SYSTEM_BIN = "/system/bin"
        private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

        fun fingerprint(entries: List<BundleEntry>): String = entries.joinToString("\n") { "${it.fileName} ${it.sha256}" }

        fun q(file: File): String = q(file.absolutePath)

        fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
