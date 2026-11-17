package templatesync

import org.appdevforall.shareyourapps.Templates
import java.io.File

/**
 * templates/default-annotated.peb is the readable copy of Templates.DEFAULT_CATALOG, the compact
 * template the plugin ships. Nothing makes them agree but this check: it drops comments and
 * layout from both, so what's left is the tags, expressions, CSS and script that make the page.
 */
fun normalizeTemplate(s: String): String =
    s.replace(Regex("\\{#.*?#\\}", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("\\s*([{};,>:])\\s*"), "$1")
        .replace(";}", "}")
        .trim()

/** The first place two normalized templates differ, with a little context, or null if they match. */
fun firstDifference(expected: String, actual: String): String? {
    if (expected == actual) return null
    var i = 0
    while (i < expected.length && i < actual.length && expected[i] == actual[i]) i++
    fun around(s: String) = s.substring(maxOf(0, i - 40), minOf(s.length, i + 80))
    return "at character $i: shipped ...${around(expected)}... but annotated ...${around(actual)}..."
}

/** The annotated template: Gradle runs unit tests from the project's folder; fall back for other runners. */
fun annotatedTemplate(): File =
    listOf("templates/default-annotated.peb", "../templates/default-annotated.peb")
        .map { File(it) }.firstOrNull { it.isFile }
        ?: error("templates/default-annotated.peb not found from ${File(".").absoluteFile}")

class AnnotatedTemplateTest {
    @org.junit.Test
    fun annotatedTemplateMatchesShippedDefault() {
        val diff = firstDifference(
            normalizeTemplate(Templates.DEFAULT_CATALOG),
            normalizeTemplate(annotatedTemplate().readText(Charsets.UTF_8)),
        )
        org.junit.Assert.assertNull(
            "templates/default-annotated.peb has drifted from Templates.DEFAULT_CATALOG; update whichever is stale " +
                "(comments and layout don't count) $diff",
            diff,
        )
    }

    @org.junit.Test
    fun normalizingIgnoresCommentsAndLayoutOnly() {
        val compact = "<style>a{color:red;margin:0}</style><p>{{x}}</p>"
        val readable = "{# note #}<style>\n/* why */\na {\n\tcolor:red;\n\tmargin:0;\n}\n</style>\n<p>{{x}}</p>"
        org.junit.Assert.assertNull(firstDifference(normalizeTemplate(compact), normalizeTemplate(readable)))
        org.junit.Assert.assertNotNull(firstDifference(normalizeTemplate(compact), normalizeTemplate(readable.replace("red", "blue"))))
        org.junit.Assert.assertNotNull(firstDifference(normalizeTemplate(compact), normalizeTemplate(readable.replace("{{x}}", "{{y}}"))))
    }
}
