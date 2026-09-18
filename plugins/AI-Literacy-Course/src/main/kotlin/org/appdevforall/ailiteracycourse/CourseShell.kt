package org.appdevforall.ailiteracycourse

import java.io.File

/**
 * Generates the course navigation page (`index.html`) over the extracted tree.
 *
 * The bundle ships no web shell of its own — just numbered folders of `.mp4`
 * videos, `.pdf` resources, and HTML activities. This walks that regular
 * structure and emits a single video-forward page: lessons in playback order,
 * each video inline, activities as links the WebView opens in place, and PDFs
 * opened through the bundled PDF.js viewer (a bare WebView can't render PDFs).
 *
 * All links are relative to the page (served at `…/course/index.html`), so they
 * resolve under the same virtual https origin.
 */
object CourseShell {

    fun generate(root: File) {
        val sections = (root.listFiles { f -> f.isDirectory } ?: emptyArray())
            .sortedWith(compareBy({ sectionOrder(it.name) }, { it.name }))

        val body = StringBuilder()
        for (section in sections) {
            val items = itemsOf(root, section)
            if (items.isEmpty()) continue
            body.append("<section>\n<h2>").append(esc(sectionTitle(section.name))).append("</h2>\n")
            for (item in items) body.append(renderItem(item))
            body.append("</section>\n")
        }

        File(root, "index.html").writeText(page(body.toString()))
    }

    // --- model ---------------------------------------------------------------

    private enum class Kind { VIDEO, ACTIVITY, PDF }

    private data class Item(val order: Int, val title: String, val href: String, val kind: Kind)

    private fun itemsOf(root: File, section: File): List<Item> {
        val entries = section.listFiles() ?: return emptyList()
        return entries.mapNotNull { entry ->
            when {
                entry.isFile && entry.extension.equals("mp4", true) ->
                    Item(leadingNumber(entry.nameWithoutExtension), itemTitle(entry.nameWithoutExtension), rel(root, entry), Kind.VIDEO)

                entry.isFile && entry.extension.equals("pdf", true) ->
                    Item(leadingNumber(entry.nameWithoutExtension), itemTitle(entry.nameWithoutExtension), rel(root, entry), Kind.PDF)

                entry.isDirectory -> findIndexHtml(entry)?.let { index ->
                    Item(leadingNumber(entry.name), itemTitle(entry.name), rel(root, index), Kind.ACTIVITY)
                }

                else -> null
            }
        }.sortedWith(compareBy({ it.order }, { it.title }))
    }

    /** Shallowest index.html under [dir] (activities nest theirs differently). */
    private fun findIndexHtml(dir: File): File? =
        dir.walkTopDown()
            .filter { it.isFile && it.name.equals("index.html", true) }
            .minByOrNull { it.relativeTo(dir).path.count { c -> c == File.separatorChar } }

    // --- rendering -----------------------------------------------------------

    private fun renderItem(item: Item): String = when (item.kind) {
        Kind.VIDEO -> """
            <article class="item video">
              <h3>${esc(item.title)}</h3>
              <video controls preload="metadata" playsinline>
                <source src="${attr(item.href)}" type="video/mp4">
              </video>
            </article>
        """.trimIndent() + "\n"

        Kind.ACTIVITY -> link(item, "Activity")
        Kind.PDF -> link(item.copy(href = pdfViewerHref(item.href)), "PDF")
    }

    /**
     * Wrap a PDF's page-relative URL in the bundled PDF.js viewer. The viewer
     * lives at `pdfjs/web/viewer.html`, two levels below the page root, so the
     * PDF is reached back up via `../../`. Resolved against the viewer's URL,
     * this stays on the same origin — which PDF.js requires for `?file=`.
     * Course resource names are URL-safe kebab-case, so [rel] needs no further
     * encoding as a query value.
     */
    private fun pdfViewerHref(rel: String): String =
        "pdfjs/web/viewer.html?file=../../$rel"

    private fun link(item: Item, badge: String): String = """
        <a class="item link" href="${attr(item.href)}">
          <span class="badge">$badge</span>
          <span class="title">${esc(item.title)}</span>
          <span class="go">Open ›</span>
        </a>
    """.trimIndent() + "\n"

    // --- naming --------------------------------------------------------------

    private val ACRONYMS = mapOf(
        "ai" to "AI", "ml" to "ML", "ui" to "UI", "ux" to "UX",
        "neuropocket" to "NeuroPocket"
    )

    private fun sectionTitle(name: String): String {
        if (name.equals("introduction", true)) return "Introduction"
        if (name.equals("resources", true)) return "Resources"
        val m = Regex("^lesson-(\\d+)-(.+)$", RegexOption.IGNORE_CASE).find(name)
        if (m != null) return "Lesson ${m.groupValues[1]} · ${titleCase(m.groupValues[2])}"
        return titleCase(name)
    }

    private fun sectionOrder(name: String): Int = when {
        name.equals("introduction", true) -> 0
        name.equals("resources", true) -> 1000
        else -> Regex("^lesson-(\\d+)", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 500
    }

    /** Strip a leading "12-" ordering prefix, then title-case the rest. */
    private fun itemTitle(raw: String): String =
        titleCase(raw.replace(Regex("^\\d+-"), ""))

    /**
     * The leading "12-" ordering prefix of a raw file/dir name, as an int.
     * Anchored to the start so a number later in the name (e.g. the "1" in
     * "5-lesson-1-recap-quiz") can't be mistaken for the ordering key.
     * Unnumbered entries sort last.
     */
    private fun leadingNumber(rawName: String): Int =
        Regex("^(\\d+)").find(rawName)?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE

    private fun titleCase(raw: String): String =
        raw.split('-', ' ', '_').filter { it.isNotBlank() }.joinToString(" ") { w ->
            ACRONYMS[w.lowercase()] ?: w.replaceFirstChar { it.uppercase() }
        }

    // --- encoding ------------------------------------------------------------

    /** Relative URL from the page root, with forward slashes and spaces encoded. */
    private fun rel(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/').replace(" ", "%20")

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun attr(s: String): String = esc(s).replace("\"", "&quot;")

    // --- page template -------------------------------------------------------

    private fun page(body: String): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>AI Literacy Course</title>
<style>
  :root {
    --bg: #f5f6fa; --card: #ffffff; --text: #1b1b1f; --muted: #5b5e6b;
    --accent: #485d92; --accent-fg: #ffffff; --border: #e2e4ee;
  }
  @media (prefers-color-scheme: dark) {
    :root {
      --bg: #0c1018; --card: #161b27; --text: #e7e9f2; --muted: #9aa0b4;
      --accent: #b9c9ff; --accent-fg: #0c1018; --border: #232a3a;
    }
  }
  * { box-sizing: border-box; }
  body {
    margin: 0; background: var(--bg); color: var(--text);
    font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
    line-height: 1.5;
  }
  .wrap { max-width: 880px; margin: 0 auto; padding: 20px 16px 64px; }
  header { padding: 8px 0 16px; }
  header h1 { font-size: 1.6rem; margin: 0 0 4px; }
  header p { margin: 0; color: var(--muted); font-size: .95rem; }
  section { margin-top: 28px; }
  h2 {
    font-size: 1.15rem; margin: 0 0 12px; padding-bottom: 6px;
    border-bottom: 2px solid var(--border);
  }
  .item {
    display: block; background: var(--card); border: 1px solid var(--border);
    border-radius: 14px; padding: 14px 16px; margin: 12px 0;
  }
  .item h3 { font-size: 1.02rem; margin: 0 0 10px; }
  video { width: 100%; border-radius: 10px; background: #000; display: block; }
  a.item.link {
    display: flex; align-items: center; gap: 12px; text-decoration: none;
    color: var(--text);
  }
  a.item.link .title { flex: 1; font-weight: 600; }
  a.item.link .go { color: var(--accent); font-weight: 600; white-space: nowrap; }
  .badge {
    background: var(--accent); color: var(--accent-fg); font-size: .72rem;
    font-weight: 700; letter-spacing: .03em; text-transform: uppercase;
    padding: 4px 8px; border-radius: 999px;
  }
  footer { margin-top: 40px; color: var(--muted); font-size: .82rem; text-align: center; }
</style>
</head>
<body>
<div class="wrap">
<header>
  <h1>Introduction to AI</h1>
  <p>Learn AI Anywhere · an offline AI literacy course</p>
</header>
$body
<footer>Course © Learn AI Anywhere · learnaianywhere.org</footer>
</div>
</body>
</html>
""".trimIndent()
}
