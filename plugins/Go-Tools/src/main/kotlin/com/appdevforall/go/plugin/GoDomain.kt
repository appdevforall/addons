package com.appdevforall.go.plugin

import java.io.File

internal object GoDomain {

    private const val GO_MOD = "go.mod"

    private val GRADLE_MARKERS = listOf(
        "settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts"
    )

    fun isGoProject(root: File?): Boolean {
        if (root == null) return false
        if (GRADLE_MARKERS.any { File(root, it).exists() }) return false
        if (File(root, GO_MOD).exists()) return true
        val goFiles = root.listFiles { f -> f.isFile && f.name.endsWith(".go") }
        return goFiles != null && goFiles.isNotEmpty()
    }
}
