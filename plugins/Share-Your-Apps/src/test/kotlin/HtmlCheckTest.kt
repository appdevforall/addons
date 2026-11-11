package htmltest
import org.appdevforall.shareyourapps.HtmlCheck
import org.appdevforall.shareyourapps.Product

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
    println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) failures++ }
fun p(h: String) = HtmlCheck.problems(h)

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    // Fine as written.
    check("plain text", p("frammis gloopy gloop").isEmpty())
    check("balanced tags", p("<p>Hi <b>there</b>, <a href=\"x\">link</a></p>").isEmpty())
    check("tags that never close", p("a<br>b<img src=x><hr>c<br/>").isEmpty())
    check("optional end tags left out", p("<ul><li>one<li>two</ul><p>a<p>b<table><tr><td>1<td>2</table>").isEmpty())
    check("a block tag ends an open <p>", p("<p>intro<ul><li>x</li></ul>").isEmpty())
    check("script and style contents are text", p("<style>b>i{color:red}</style><script>if (a<b) x='</b>'</script>").isEmpty())
    check("comments are skipped", p("<!-- <b> not a tag --><i>x</i>").isEmpty())
    check("'>' inside a quoted attribute", p("<a title=\"a > b\" href='x'>y</a>").isEmpty())
    check("'<' as plain text", p("if a < b and 3 <4").isEmpty())
    check("upper-case tags match", p("<B>bold</b>").isEmpty())
    check("self-closing SVG shapes", p("<svg viewBox=\"0 0 1 1\"><path d=\"M0 0\"/></svg>").isEmpty())
    check("the owner's store description", p("<span style='color:green;text-color:blue;background-color:pink'>Hi, mom!</span>").isEmpty())

    // Mistakes.
    val gs = p("frammis gloopy gloop <ol><li>zippy</li><li>zoppy\n</li><li>zoppy\n</li><li>zoppy\n</li><ol>")
    check("the Great Scott! description: two unclosed lists", gs == listOf("<ol> is opened 2 times but never closed"))
    check("opened but never closed", p("<b>bold") == listOf("<b> is opened but never closed"))
    check("closed but never opened", p("text</i>") == listOf("</i> closes a tag that was never opened"))
    check("closed in the wrong order", p("<ol><b>x</ol></b>") == listOf("</ol> closes before the <b> inside it", "</b> closes a tag that was never opened"))
    check("a closing tag for a tag that never closes", p("a<br></br>") == listOf("</br> isn't needed: <br> never takes a closing tag"))
    check("a tag never finished", p("<b class=\"x\" oops") == listOf("<b is never finished with >"))
    check("an unclosed comment", p("<!-- note") == listOf("A comment <!-- is never closed with -->"))
    check("an unclosed script", p("<script>x()") == listOf("<script> is opened but never closed"))
    check("at most five problems", p("</a></b></c></d></e></f></g>").size == 5)

    // Left-out products.
    fun prod(apk: String?, apkPub: Boolean, src: Boolean) = Product("P", "CodeOnTheGoProjects/P", apk, apkPub, src, emptyList(), null, null, 1)
    check("included: APK chosen and ticked", prod("a.apk", true, false).leftOutReason == null)
    check("included: source ticked", prod(null, true, true).leftOutReason == null)
    check("left out: no APK and source unticked", prod(null, true, false).leftOutReason == "has no APK chosen, and Publish the source isn't ticked")
    check("left out: both unticked", prod("a.apk", false, false).leftOutReason == "has Publish the APK and Publish the source both unticked")
    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED"); return failedNames.toList()
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class HtmlCheckChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
