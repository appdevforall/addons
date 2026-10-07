package com.appdevforall.jvm.plugin

import java.io.File

internal data class MainClass(val name: String, val source: File)

internal object MainClassFinder {

    private const val MAX_SOURCES = 2000

    private val COMMENT_OR_LITERAL = Regex(""""{3}[\s\S]*?"{3}|"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|/\*[\s\S]*?\*/|//[^\n]*""")
    private val JAVA_PACKAGE = Regex("""^\s*package\s+([\w.]+)\s*;""", RegexOption.MULTILINE)
    private val JAVA_TYPE = Regex("""(?<!\.)\b(?:class|interface|enum|record)\s+(\w+)""")
    private val JAVA_MAIN = Regex(
        """\bstatic\s+(?:(?:public|final|synchronized|strictfp)\s+)*void\s+main\s*\(\s*(?:final\s+)?String\s*(?:\[\s*]|\.\.\.|\s\w+\s*\[\s*])"""
    )
    private val KOTLIN_PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
    private val KOTLIN_JVM_NAME = Regex("""@file:JvmName\(\s*"(\w+)"\s*\)""")
    private val KOTLIN_TOP_LEVEL_MAIN = Regex("""^(?:(?:public|suspend)\s+)*fun\s+main\s*\(""", RegexOption.MULTILINE)
    private val KOTLIN_JVM_STATIC_MAIN = Regex("""@JvmStatic\s+(?:(?:public|suspend)\s+)*fun\s+main\s*\(""")
    private val KOTLIN_TYPE = Regex("""\b(companion\s+object)\b|\bobject\s+(\w+)|(?<!:)\b(?:class|interface)\s+(\w+)""")

    private enum class Kind { TYPE, OBJECT, COMPANION }

    private class Scope(val name: String, val kind: Kind)

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
        val code = COMMENT_OR_LITERAL.replace(text) { if (it.value.startsWith("/")) " " else it.value }
        val structure = COMMENT_OR_LITERAL.replace(code, "\"\"")
        return when {
            fileName.endsWith(".java") -> javaMainClasses(structure)
            fileName.endsWith(".kt") -> kotlinMainClasses(fileName, code, structure)
            else -> emptyList()
        }
    }

    private fun javaMainClasses(structure: String): List<String> {
        val pkg = JAVA_PACKAGE.find(structure)?.groupValues?.get(1)
        val bodies = bodies(structure, JAVA_TYPE) { Scope(it.groupValues[1], Kind.TYPE) }
        return JAVA_MAIN.findAll(structure)
            .mapNotNull { main -> binaryName(scopesAt(structure, bodies, main.range.first)) }
            .map { qualify(pkg, it) }
            .toList()
    }

    private fun kotlinMainClasses(fileName: String, code: String, structure: String): List<String> {
        val pkg = KOTLIN_PACKAGE.find(structure)?.groupValues?.get(1)
        val names = mutableListOf<String>()
        if (KOTLIN_TOP_LEVEL_MAIN.containsMatchIn(structure)) {
            names += KOTLIN_JVM_NAME.find(code)?.groupValues?.get(1) ?: facadeClassName(fileName)
        }
        val bodies = bodies(structure, KOTLIN_TYPE) { match ->
            when {
                match.groups[1] != null -> Scope("", Kind.COMPANION)
                match.groups[2] != null -> Scope(match.groupValues[2], Kind.OBJECT)
                else -> Scope(match.groupValues[3], Kind.TYPE)
            }
        }
        KOTLIN_JVM_STATIC_MAIN.findAll(structure).forEach { main ->
            val scopes = scopesAt(structure, bodies, main.range.first)
            when (scopes.lastOrNull()?.kind) {
                Kind.OBJECT -> binaryName(scopes)
                Kind.COMPANION -> binaryName(scopes.dropLast(1))
                else -> null
            }?.let { names += it }
        }
        return names.map { qualify(pkg, it) }
    }

    private fun bodies(structure: String, declaration: Regex, scope: (MatchResult) -> Scope): Map<Int, Scope> =
        buildMap {
            declaration.findAll(structure).forEach { match ->
                val brace = structure.indexOf('{', match.range.last + 1)
                if (brace >= 0) put(brace, scope(match))
            }
        }

    private fun scopesAt(structure: String, bodies: Map<Int, Scope>, position: Int): List<Scope?> {
        val scopes = ArrayList<Scope?>()
        for (index in 0 until position) {
            when (structure[index]) {
                '{' -> scopes += bodies[index]
                '}' -> scopes.removeLastOrNull()
            }
        }
        return scopes
    }

    private fun binaryName(scopes: List<Scope?>): String? {
        val types = scopes.filterNotNull()
        if (types.isEmpty() || types.size < scopes.size || types.any { it.kind == Kind.COMPANION }) return null
        return types.joinToString("$") { it.name }
    }

    private fun facadeClassName(fileName: String): String {
        val base = fileName.removeSuffix(".kt").map { if (it.isJavaIdentifierPart()) it else '_' }.joinToString("")
        val start = if (base.firstOrNull()?.isJavaIdentifierStart() == true) base else "_$base"
        return start.replaceFirstChar { it.uppercaseChar() } + "Kt"
    }

    private fun qualify(pkg: String?, name: String): String = if (pkg.isNullOrEmpty()) name else "$pkg.$name"
}
