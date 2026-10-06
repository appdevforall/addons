package com.appdevforall.jvm.plugin

import java.io.File

internal object JvmDomain {

    private val GRADLE_MARKERS = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
    private const val SCAN_DEPTH = 4

    @Volatile
    private var cachedRoot: String? = null

    @Volatile
    private var cachedResult = false

    @Synchronized
    fun isJvmProject(root: File?): Boolean {
        if (root == null) return false
        val path = root.absolutePath
        if (path != cachedRoot) {
            cachedResult = GRADLE_MARKERS.none { File(root, it).exists() } && hasJvmSource(root, SCAN_DEPTH)
            cachedRoot = path
        }
        return cachedResult
    }

    private fun hasJvmSource(dir: File, depth: Int): Boolean {
        val children = dir.listFiles() ?: return false
        if (children.any { it.isFile && MainClassFinder.isSource(it) }) return true
        return depth > 0 && children.any { it.isDirectory && !MainClassFinder.isSkipped(it) && hasJvmSource(it, depth - 1) }
    }
}
