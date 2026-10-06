package com.appdevforall.jvm.plugin

import java.io.File

internal data class MainClass(val name: String, val source: File)

internal object MainClassFinder {

    private const val MAX_SOURCES = 2000

    private val COMMENT = Regex("""/\*[\s\S]*?\*/|//[^\n]*""")
    private val JAVA_PACKAGE = Regex("""^\s*package\s+([\w.]+)\s*;""", RegexOption.MULTILINE)
    private val JAVA_TYPE = Regex("""\b(?:class|interface|enum|record)\s+(\w+)""")
    private val JAVA_MAIN = Regex(
        """\bstatic\s+(?:(?:public|final|synchronized|strictfp)\s+)*void\s+main\s*\(\s*(?:final\s+)?String\s*(?:\[\s*]|\.\.\.|\s\w+\s*\[\s*])"""
    )
    private val KOTLIN_PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
    private val KOTLIN_JVM_NAME = Regex("""@file:JvmName\(\s*"(\w+)"\s*\)""")
    private val KOTLIN_TOP_LEVEL_MAIN = Regex("""^(?:(?:public|suspend)\s+)*fun\s+main\s*\(""", RegexOption.MULTILINE)
    private val KOTLIN_JVM_STATIC_MAIN = Regex("""@JvmStatic\s+(?:(?:public|suspend)\s+)*fun\s+main\s*\(""")
    private val KOTLIN_OWNER = Regex("""\bobject\s+(\w+)|\bcompanion\s+object\b""")
    private val KOTLIN_CLASS = Regex("""\bclass\s+(\w+)""")

    fun isSource(file: File): Boolean = file.extension == "java" || file.extension == "kt"

    fun isSkipped(dir: File): Boolean = dir.name == "build" || dir.name.startsWith(".")

    fun find(root: File): List<MainClass> =
        sources(root)
            .flatMap { file -> mainClassNames(file.name, file.readText()).map { MainClass(it, file) } }
            .distinctBy { it.name }
            .sortedBy { it.name }

    fun sources(root: File): List<File> =
        root.walkTopDown()
            .onEnter { it == root || !isSkipped(it) }
            .filter { it.isFile && isSource(it) }
            .take(MAX_SOURCES)
            .toList()

    fun mainClassNames(fileName: String, text: String): List<String> {
        val code = COMMENT.replace(text, "")
        return when {
            fileName.endsWith(".java") -> javaMainClasses(code)
            fileName.endsWith(".kt") -> kotlinMainClasses(fileName, code)
            else -> emptyList()
        }
    }

    private fun javaMainClasses(code: String): List<String> {
        val pkg = JAVA_PACKAGE.find(code)?.groupValues?.get(1)
        return JAVA_MAIN.findAll(code)
            .mapNotNull { main -> JAVA_TYPE.findAll(code.substring(0, main.range.first)).lastOrNull()?.groupValues?.get(1) }
            .map { qualify(pkg, it) }
            .toList()
    }

    private fun kotlinMainClasses(fileName: String, code: String): List<String> {
        val pkg = KOTLIN_PACKAGE.find(code)?.groupValues?.get(1)
        val names = mutableListOf<String>()
        if (KOTLIN_TOP_LEVEL_MAIN.containsMatchIn(code)) {
            names += KOTLIN_JVM_NAME.find(code)?.groupValues?.get(1) ?: facadeClassName(fileName)
        }
        KOTLIN_JVM_STATIC_MAIN.findAll(code).forEach { main ->
            ownerOf(code.substring(0, main.range.first))?.let { names += it }
        }
        return names.map { qualify(pkg, it) }
    }

    private fun ownerOf(before: String): String? {
        val owner = KOTLIN_OWNER.findAll(before).lastOrNull() ?: return null
        return owner.groups[1]?.value
            ?: KOTLIN_CLASS.findAll(before.substring(0, owner.range.first)).lastOrNull()?.groupValues?.get(1)
    }

    private fun facadeClassName(fileName: String): String {
        val base = fileName.removeSuffix(".kt").map { if (it.isJavaIdentifierPart()) it else '_' }.joinToString("")
        val start = if (base.firstOrNull()?.isJavaIdentifierStart() == true) base else "_$base"
        return start.replaceFirstChar { it.uppercaseChar() } + "Kt"
    }

    private fun qualify(pkg: String?, name: String): String = if (pkg.isNullOrEmpty()) name else "$pkg.$name"
}
