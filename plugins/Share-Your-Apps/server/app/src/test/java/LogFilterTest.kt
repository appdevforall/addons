package logtest
import org.appdevforall.shareyourapps.server.LogFilter
import org.appdevforall.shareyourapps.server.LogFilter.Kind

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
    println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) failures++ }

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    // Status lines as StoreLog.status writes them: a timestamp in brackets, then the tag.
    val log = listOf(
        "[23/Sep/2026:10:00:00 +0200] [server] serving Store_1 on 192.168.49.1:6175",
        "192.168.49.23 - - [23/Sep/2026:10:00:01 +0200] \"GET / HTTP/1.1\" 200 1280 \"-\" \"Chrome\" 90B/s",
        "[23/Sep/2026:10:00:02 +0200] [gzip] done: index.html 2458 -> 1280 bytes in 0 s",
        "192.168.49.23 - - [23/Sep/2026:10:00:05 +0200] \"GET /f/products/a/app.apk HTTP/1.1\" 200 6147028 \"-\" \"Chrome\" 2000B/s",
        "",
        "[23/Sep/2026:10:00:09 +0200] [hotspot] Wi-Fi Direct group failed: Wi-Fi is busy or switched off",
    )
    check("a timestamped status line is a status line", LogFilter.isStatus(log[0]) && LogFilter.isStatus(log[5]))
    check("a request line isn't, though it holds a bracketed date", !LogFilter.isStatus(log[1]) && !LogFilter.isStatus(log[3]))
    val all = LogFilter.apply(log, Kind.ALL, "", 100)
    check("all lines, newest first, blank lines dropped", all.lines.first().contains("] [hotspot]") && all.lines.last().contains("] [server]") && all.matches == 5)
    check("requests only", LogFilter.apply(log, Kind.REQUESTS, "", 100).lines.all { it.startsWith("192.") } && LogFilter.apply(log, Kind.REQUESTS, "", 100).matches == 2)
    check("status only", LogFilter.apply(log, Kind.STATUS, "", 100).matches == 3)
    check("search is case-insensitive", LogFilter.apply(log, Kind.ALL, "APP.APK", 100).matches == 1)
    check("search and filter combine", LogFilter.apply(log, Kind.STATUS, "failed", 100).lines.single().contains("Wi-Fi Direct group failed"))
    val big = (1..5000).map { "10.0.0.$it - - x" }
    val capped = LogFilter.apply(big, Kind.ALL, "", 2000)
    check("capped at 2,000 but counts every match", capped.lines.size == 2000 && capped.matches == 5000 && capped.lines.first() == "10.0.0.5000 - - x")
    val now = 1_000_000_000_000L
    check("age labels", LogFilter.ageLabel(0, now, now) == "Current" && LogFilter.ageLabel(1, now - 5 * 60_000, now) == "5m ago" &&
        LogFilter.ageLabel(2, now - 3 * 3_600_000, now) == "3h ago" && LogFilter.ageLabel(3, now - 50 * 3_600_000, now) == "2d ago")
    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED"); return failedNames.toList()
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class LogFilterChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
