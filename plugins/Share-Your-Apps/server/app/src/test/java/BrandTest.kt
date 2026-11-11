package brandtest
import org.appdevforall.localstore.server.Brand
import java.util.Base64

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
    println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) failures++ }

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    val page = Brand.page("Anna &amp; Co", "<p>Not found.</p>")
    check("a complete page with the awning and the card", page.startsWith("<!doctype html>") && page.contains("<div class=aw></div>") && page.contains("<div class=k>") && page.endsWith("</html>"))
    check("the title is used as given (already escaped)", page.contains("<title>Anna &amp; Co</title>"))
    check("the octopus is an <img>, with empty alt text", page.contains("<img src=\"data:image/svg+xml;base64,") && page.contains("alt=\"\">"))
    val b64 = Brand.octopusDataUri.removePrefix("data:image/svg+xml;base64,")
    check("the embedded image decodes to the exact SVG", String(Base64.getDecoder().decode(b64)) == Brand.OCTOPUS_SVG)
    check("the SVG is animated, and still for reduced motion", Brand.OCTOPUS_SVG.contains("@keyframes") && Brand.OCTOPUS_SVG.contains("prefers-reduced-motion"))
    check("the SVG stays small", Brand.OCTOPUS_SVG.toByteArray().size < 3000)
    check("no web fonts: the store works offline", !page.contains("fonts.googleapis") && !page.contains("@import"))
    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED"); return failedNames.toList()
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class BrandChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
