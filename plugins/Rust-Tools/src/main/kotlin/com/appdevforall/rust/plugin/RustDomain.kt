package com.appdevforall.rust.plugin

import java.io.File

internal object RustDomain {

    private const val CARGO_TOML = "Cargo.toml"

    private val GRADLE_MARKERS = listOf(
        "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts"
    )

    fun isRustProject(root: File?): Boolean {
        if (root == null) return false
        if (GRADLE_MARKERS.any { File(root, it).exists() }) return false
        if (File(root, CARGO_TOML).exists()) return true
        val rustFiles = root.listFiles { f -> f.isFile && f.name.endsWith(".rs") }
        return rustFiles != null && rustFiles.isNotEmpty()
    }
}
