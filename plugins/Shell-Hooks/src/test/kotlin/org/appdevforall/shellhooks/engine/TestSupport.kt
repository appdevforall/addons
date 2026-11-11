package org.appdevforall.shellhooks.engine

import java.io.File
import java.nio.file.Files

class MapStore : KeyValueStore {
    val map = linkedMapOf<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun putString(
        key: String,
        value: String?,
    ) {
        if (value == null) map.remove(key) else map[key] = value
    }

    override fun keys(): Set<String> = map.keys.toSet()
}

fun tempDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()

fun File.script(
    path: String,
    body: String = "#!/bin/sh\necho hi\n",
    executable: Boolean = true,
): File =
    File(this, path).apply {
        parentFile.mkdirs()
        writeText(body)
        setExecutable(executable, false)
    }

/** Arguments a hostile file name could smuggle into a shell, if anything ever parsed them. */
val HOSTILE_NAMES =
    listOf(
        "with space.kt",
        "\$(touch pwned).kt",
        "`touch pwned`.kt",
        "quote'd.kt",
        "double\"quote.kt",
        "new\nline.kt",
        "semi;colon && rm -rf x.kt",
        "-dash-first.kt",
        "glob*?[x].kt",
        "",
    )
