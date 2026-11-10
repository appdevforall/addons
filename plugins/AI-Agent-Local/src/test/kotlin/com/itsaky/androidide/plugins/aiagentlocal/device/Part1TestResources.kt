package com.itsaky.androidide.plugins.aiagentlocal.device

import com.itsaky.androidide.plugins.aiagentlocal.device.match.DeviceIndex
import java.io.File
import java.io.FileInputStream

/** Reads the Part 1 test resources (`src/test/resources/part1/`) and the shipped index asset. */
internal object Part1TestResources {

    fun text(path: String): String = String(bytes(path), Charsets.UTF_8)

    fun bytes(path: String): ByteArray =
        (Part1TestResources::class.java.classLoader!!.getResourceAsStream(path) ?: error("missing test resource $path")).use { it.readBytes() }

    fun tree(path: String): Any? = Json.parse(text(path), path)

    fun obj(path: String): Map<String, Any?> = Json.obj(tree(path), path)

    /** The shipped index asset in the source tree (the Gradle test task passes its path). */
    fun indexAssetFile(): File = File(System.getProperty("part1IndexAssetPath") ?: error("system property part1IndexAssetPath is not set"))

    fun realIndex(): DeviceIndex = DeviceIndex.load(FileInputStream(indexAssetFile()))

    /** Directories of the golden set, sorted. */
    fun goldenCaseNames(): List<String> {
        val url = Part1TestResources::class.java.classLoader!!.getResource("part1/golden") ?: error("missing part1/golden")
        return File(url.toURI()).listFiles { f -> f.isDirectory }!!.map { it.name }.sorted()
    }
}
