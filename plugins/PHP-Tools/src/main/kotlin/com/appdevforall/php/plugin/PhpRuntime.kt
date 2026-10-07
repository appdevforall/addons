package com.appdevforall.php.plugin

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

internal data class BundleEntry(
    val abi: String,
    val kind: String,
    val name: String,
    val version: String,
    val url: String,
    val sha256: String,
) {
    val fileName: String = "$name.$kind"

    companion object {
        const val ALL_ABIS = "all"
        const val KIND_DEB = "deb"
        const val KIND_PHAR = "phar"

        fun parseLock(text: String): List<BundleEntry> =
            text.lines().filter { it.isNotBlank() }.map { line ->
                val fields = line.split('\t')
                require(fields.size == 6) { "Malformed php-bundle.lock line: $line" }
                BundleEntry(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5])
            }

        fun forDevice(entries: List<BundleEntry>, abi: String): List<BundleEntry> =
            entries.filter { it.abi == abi || it.abi == ALL_ABIS }
    }
}

internal class PhpRuntime(
    private val pluginDir: File,
    private val tmp: File,
) {
    val home = File(pluginDir, "runtime")
    val bin = File(home, "bin")
    val php = File(bin, "php")
    val composer = File(bin, "composer")
    val phpactor = File(bin, "phpactor")
    val staging = File(pluginDir, "runtime-staging")
    val archives = File(staging, "archives")
    val ini = File(home, "etc/php.ini")

    private val prefix = tmp.parentFile!!
    private val phpHome = File(home, "php")
    private val phpactorPhar = File(home, "phpactor.phar")
    private val marker = File(home, "installed")

    fun isInstalled(entries: List<BundleEntry>): Boolean =
        marker.isFile && marker.readText() == fingerprint(entries) && php.canExecute() && phpactor.canExecute()

    fun installScript(entries: List<BundleEntry>): String {
        val root = File(staging, "root")
        val lines = mutableListOf("set -e", "mkdir -p ${q(root)}")
        val moves = mutableListOf<String>()
        entries.forEach { entry ->
            val archive = File(archives, entry.fileName)
            lines += "echo ${q("${entry.sha256}  ${archive.absolutePath}")} | sha256sum -c - > /dev/null"
            when (entry.kind) {
                BundleEntry.KIND_DEB -> lines += "dpkg-deb -x ${q(archive)} ${q(root)}"
                BundleEntry.KIND_PHAR -> moves += "mv ${q(archive)} ${q(File(home, entry.fileName))}"
                else -> throw IllegalArgumentException("${entry.name} has kind ${entry.kind}, which the device does not install")
            }
        }
        lines += "rm -rf ${q(home)}"
        lines += "mkdir -p ${q(home)}"
        lines += "mv ${q(File(root, TERMUX_PREFIX.removePrefix("/")))} ${q(phpHome)}"
        lines += "rm -rf " + UNUSED.joinToString(" ") { q(File(phpHome, it)) }
        lines += moves
        lines += "rm -rf ${q(staging)}"
        return lines.joinToString("\n")
    }

    fun relocateShell() {
        val binary = File(phpHome, "bin/php")
        val offsets = shellPathOffsets(binary.readBytes())
        if (offsets.isEmpty()) throw IOException("$binary does not name $TERMUX_SHELL; the bundled PHP build has changed")
        RandomAccessFile(binary, "rw").use { file ->
            offsets.forEach { offset ->
                file.seek(offset.toLong())
                file.write(RELOCATED_SHELL)
            }
        }
    }

    fun writeLaunchers(entries: List<BundleEntry>) {
        bin.mkdirs()
        ini.parentFile!!.mkdirs()
        val certificates = File(prefix, "etc/tls/cert.pem").absolutePath
        ini.writeText(
            listOf(
                "opcache.lockfile_path" to tmp.absolutePath,
                "sys_temp_dir" to tmp.absolutePath,
                "upload_tmp_dir" to tmp.absolutePath,
                "session.save_path" to tmp.absolutePath,
                "openssl.cafile" to certificates,
                "curl.cainfo" to certificates,
                "memory_limit" to "512M",
            ).joinToString("\n", postfix = "\n") { (key, value) -> "$key=\"$value\"" },
        )
        launcher(
            php,
            "export LD_LIBRARY_PATH=${q(File(phpHome, "lib"))}\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}",
            "export PHPRC=${q(ini.parentFile!!)}",
            "export PHP_INI_SCAN_DIR=",
            "exec ${q(File(phpHome, "bin/php"))} \"\$@\"",
        )
        launcher(composer, "exec ${q(php)} ${q(File(phpHome, "bin/composer"))} \"\$@\"")
        launcher(phpactor, "exec ${q(php)} ${q(phpactorPhar)} \"\$@\"")
        marker.writeText(fingerprint(entries))
    }

    fun environment(): Map<String, String> =
        mapOf(
            "PATH" to "${bin.absolutePath}:$SYSTEM_BIN",
            "TMPDIR" to tmp.absolutePath,
            "COMPOSER_HOME" to File(pluginDir, "composer").absolutePath,
            "COMPOSER_PROCESS_TIMEOUT" to "0",
            "XDG_CACHE_HOME" to File(pluginDir, "cache").absolutePath,
            "XDG_CONFIG_HOME" to File(pluginDir, "config").absolutePath,
            "XDG_DATA_HOME" to File(pluginDir, "data").absolutePath,
        )

    fun languageServerCommand(): List<String> = listOf(phpactor.absolutePath, "language-server")

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
        private const val TERMUX_SHELL = "$TERMUX_PREFIX/bin/sh"
        private const val INSTALL_LAYOUT = 2
        private val UNUSED = listOf("include", "share", "bin/php-cgi", "bin/phpdbg", "bin/cstool")
        private val TERMUX_SHELL_STRING = TERMUX_SHELL.toByteArray() + 0
        private val RELOCATED_SHELL = "$SYSTEM_BIN/sh".toByteArray().copyOf(TERMUX_SHELL_STRING.size)

        fun shellPathOffsets(bytes: ByteArray): List<Int> {
            val offsets = mutableListOf<Int>()
            for (start in 0..bytes.size - TERMUX_SHELL_STRING.size) {
                if (start > 0 && bytes[start - 1] != 0.toByte()) continue
                if (TERMUX_SHELL_STRING.indices.all { bytes[start + it] == TERMUX_SHELL_STRING[it] }) offsets += start
            }
            return offsets
        }

        fun fingerprint(entries: List<BundleEntry>): String =
            (listOf("layout $INSTALL_LAYOUT") + entries.map { "${it.fileName} ${it.sha256}" }).joinToString("\n")

        fun q(file: File): String = q(file.absolutePath)

        fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
