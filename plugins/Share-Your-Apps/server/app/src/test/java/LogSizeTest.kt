package logsizetest
import org.appdevforall.shareyourapps.server.LogSize as L

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
    println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) failures++ }

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    check("0, 10 and 100 are accepted", L.mb("0") == 0 && L.mb("10") == 10 && L.mb("100") == 100 &&
        listOf("0", "10", "100").all { L.problem(it) == null })
    check("spaces around a number are fine", L.mb(" 25 ") == 25 && L.problem(" 25 ") == null)
    check("101 is refused as more than 100", L.mb("101") == null && L.problem("101")!!.startsWith("101 is more than 100."))
    check("-1 is refused as less than 0", L.mb("-1") == null && L.problem("-1")!!.startsWith("-1 is less than 0."))
    check("a number too long for an Int is still more than 100",
        L.mb("99999999999999999999") == null && L.problem("99999999999999999999")!!.contains("is more than 100."))
    check("empty is refused", L.mb("") == null && L.problem("  ") != null)
    check("1.5 and letters are refused as not whole numbers",
        L.mb("1.5") == null && L.problem("1.5")!!.contains("isn't a whole number") && L.problem("ten")!!.contains("isn't a whole number"))
    check("every refusal names the valid range", listOf("", "-1", "101", "1.5", "ten", "99999999999999999999").all {
        L.problem(it)!!.endsWith("Enter a whole number from 0 to 100.") })
    check("mb and problem always agree", listOf("", "0", "100", "101", "-1", "1.5", " 7 ", "1e3").all { (L.mb(it) == null) == (L.problem(it) != null) })
    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED"); return failedNames.toList()
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class LogSizeChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
