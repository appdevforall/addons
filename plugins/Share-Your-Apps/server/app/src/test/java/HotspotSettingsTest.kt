package hstest
import org.appdevforall.shareyourapps.server.HotspotSettings as H

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
    println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) failures++ }

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    check("a plain name is fine", H.nameProblem("Anna's Store") == null && H.name(" Anna ") == "DIRECT-LS-Anna")
    check("22 ASCII characters fit exactly", H.nameProblem("a".repeat(22)) == null && H.nameProblem("a".repeat(23)) != null)
    check("multi-byte letters count as bytes", H.nameProblem("é".repeat(11)) == null && H.nameProblem("é".repeat(12)) != null)
    check("emoji count as 4 bytes", H.nameProblem("😀".repeat(5)) == null && H.nameProblem("😀".repeat(6)) != null)
    check("empty name refused", H.nameProblem("   ") != null)
    check("control characters refused", H.nameProblem("a\u0007b") != null)
    check("every accepted name matches Android's rule", listOf("Anna", "x", "a".repeat(22)).all {
        val n = H.name(it); n.matches(Regex("^DIRECT-[a-zA-Z0-9]{2}.*")) && n.toByteArray().size <= 32 })
    check("password: 8 and 63 fit, 7 and 64 don't", H.passwordProblem("a".repeat(8)) == null && H.passwordProblem("a".repeat(63)) == null &&
        H.passwordProblem("a".repeat(7)) != null && H.passwordProblem("a".repeat(64)) != null)
    check("password: spaces and punctuation fine, accents and control characters not",
        H.passwordProblem("pass word; \"x\"") == null && H.passwordProblem("pässword1") != null && H.passwordProblem("pass\tword") != null)
    val r = H.randomPassword()
    check("random passwords are valid, 16 long, without look-alikes", H.passwordProblem(r) == null && r.length == 16 && r.none { it in "0O1lI" })
    check("random passwords differ", (1..20).map { H.randomPassword() }.toSet().size == 20)
    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED"); return failedNames.toList()
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class HotspotSettingsChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
