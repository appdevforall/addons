package soundlogtest

import java.net.Inet4Address
import java.net.InetAddress
import org.appdevforall.shareyourapps.server.SoundLog
import org.appdevforall.shareyourapps.server.SoundPayload

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
    println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) failures++ }

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    val address = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 49, 1)) as Inet4Address
    val secret = "s3cret-PASS-xyz"

    // The line as the log writes it.
    check("line has the [sound] tag", SoundLog.line("hello") == "[sound] hello")

    // Played.
    check("played: stopped early, other Wi-Fi",
        SoundLog.played(3, 36, stoppedEarly = true, volumeLow = false, ownHotspot = false, error = null) ==
            "played the code: 3 of 36 bursts, stopped early")
    val finished = SoundLog.played(4, 4, stoppedEarly = false, volumeLow = true, ownHotspot = true, error = null)
    check("played: counts the bursts played of those planned", finished.contains("4 of 4 bursts"))
    check("played: all bursts played", finished.contains("all bursts played"))
    check("played: low volume noted", finished.endsWith("; media volume was low"))
    check("played: own hotspot says name and password were sent", finished.contains("this phone's hotspot name and password"))
    check("played: stopped before the first burst ended", SoundLog.played(0, 36, true, false, false, null).contains("part of the first burst"))
    val failed = SoundLog.played(0, 36, stoppedEarly = false, volumeLow = false, ownHotspot = false, error = "boom")
    check("played: a failure gives the reason", failed.contains(", failed: boom") && !failed.contains("stopped early"))

    // Heard: the password never appears.
    val own = SoundPayload("ABCD2345", address, 6175, true, "DIRECT-LS-ABCD", secret)
    val heardOwn = SoundLog.heard(own, "repeat 2, start signal 0.81")
    check("heard: never the password", !heardOwn.contains(secret))
    check("heard: says a password came, not logged", heardOwn.contains("password received, not logged"))
    check("heard: the code as displayed", heardOwn.startsWith("heard code LS-ABCD-2345 (repeat 2, start signal 0.81)"))
    check("heard: the server's address and port", heardOwn.contains(", server 192.168.49.1:6175"))
    check("heard: the hotspot's name", heardOwn.contains("its own hotspot DIRECT-LS-ABCD"))
    check("heard: the payload's own text hides the password too", !own.toString().contains(secret))

    val wifi = SoundPayload("ABCD2345", address, 6175, false, "HomeNet", null)
    check("heard: other Wi-Fi names the network", SoundLog.heard(wifi, "repeat 1").contains(", on Wi-Fi HomeNet"))
    val ownNoName = SoundPayload("ABCD2345", address, 6175, true, null, null)
    check("heard: own hotspot without a name", SoundLog.heard(ownNoName, "repeat 1").endsWith(", its own hotspot"))
    val bare = SoundLog.heard(SoundPayload("ABCD2345"), "3 repeats combined")
    check("heard: no address, no network name", bare.endsWith(", no network name") && !bare.contains("server"))

    // The other lines.
    check("nothing heard, with the strongest signal", SoundLog.nothingHeard(30, 0.123) == "heard nothing in 30 s (strongest start signal 0.12)")
    check("microphone refused", SoundLog.microphoneRefused() == "can't listen: the microphone permission was refused")
    check("listening failed gives the reason", SoundLog.listenFailed("no mic") == "listening failed: no mic")
    check("self-test passed", SoundLog.selfTest(true, "repeat 1") == "self-test passed (repeat 1)")
    check("self-test failed", SoundLog.selfTest(false, "too quiet") == "self-test failed: too quiet")

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED"); return failedNames.toList()
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class SoundLogChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
