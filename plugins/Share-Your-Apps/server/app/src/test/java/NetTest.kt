package nettest
import org.appdevforall.shareyourapps.server.*
import java.net.InetAddress

var failures = 0
val failedNames = mutableListOf<String>()
fun check(name: String, cond: Boolean) {
    if (!cond) failedNames += name
    println((if (cond) "PASS  " else "FAIL  ") + name); if (!cond) failures++ }
fun a(s: String) = InetAddress.getByName(s)
fun i(name: String, vararg addrs: String, up: Boolean = true) = Iface(name, up, name == "lo", addrs.map(::a))

/** Runs every check; returns the names of those that failed. */
fun runChecks(): List<String> {
    failures = 0; failedNames.clear()
    val lo = i("lo", "127.0.0.1")
    val cell = i("rmnet_data0", "10.144.3.7")          // mobile data: private-looking, unreachable for buyers
    val wlan = i("wlan0", "fe80::1", "192.168.3.97")
    val p2p = i("p2p-wlan0-0", "192.168.49.1")         // a Wi-Fi Direct group
    val sap = i("swlan0", "192.168.237.18")            // Samsung's local-only hotspot
    check("Wi-Fi: wlan0's IPv4", pickWifiAddress(listOf(lo, cell, wlan))?.hostAddress == "192.168.3.97")
    check("Wi-Fi: none when only mobile data is up", pickWifiAddress(listOf(lo, cell)) == null)
    check("Wi-Fi: none when wlan0 has only IPv6", pickWifiAddress(listOf(lo, i("wlan0", "fe80::1"))) == null)
    check("Wi-Fi: a down wlan0 doesn't count", pickWifiAddress(listOf(i("wlan0", "192.168.3.97", up = false))) == null)
    check("hotspot: finds the Wi-Fi Direct group, not mobile data", pickHotspotAddress(listOf(lo, cell, p2p))?.hostAddress == "192.168.49.1")
    check("hotspot: finds swlan0, not mobile data", pickHotspotAddress(listOf(lo, cell, sap))?.hostAddress == "192.168.237.18")
    check("hotspot: none when only mobile data and loopback", pickHotspotAddress(listOf(lo, cell, i("ccmni1", "10.0.0.2"), i("tun0", "10.8.0.1"))) == null)
    // The phone's own Mobile hotspot, turned on in Settings.
    val samsungAp = i("swlan0", "192.168.43.1")
    check("Mobile hotspot: Samsung's swlan0", pickPhoneHotspotAddress(listOf(lo, cell, samsungAp))?.hostAddress == "192.168.43.1")
    check("Mobile hotspot: ap0, softap0 and wlan1, as other brands name it", listOf("ap0", "softap0", "wlan1").all { n ->
        pickPhoneHotspotAddress(listOf(lo, cell, i(n, "192.168.43.1")))?.hostAddress == "192.168.43.1" })
    check("Mobile hotspot: never Wi-Fi, mobile data, Wi-Fi Direct, or USB and Bluetooth tethering",
        pickPhoneHotspotAddress(listOf(lo, wlan, cell, p2p, i("rndis0", "192.168.42.129"), i("bt-pan", "192.168.44.1"), i("usb0", "192.168.42.2"))) == null)
    check("Mobile hotspot: a switched-off one doesn't count", pickPhoneHotspotAddress(listOf(i("swlan0", "192.168.43.1", up = false))) == null)
    check("Wi-Fi is only wlan0, so a wlan1 hotspot isn't mistaken for Wi-Fi", pickWifiAddress(listOf(lo, i("wlan1", "192.168.43.1"))) == null)
    check("with both on, Wi-Fi and the Mobile hotspot are told apart",
        pickWifiAddress(listOf(wlan, samsungAp))?.hostAddress == "192.168.3.97" && pickPhoneHotspotAddress(listOf(wlan, samsungAp))?.hostAddress == "192.168.43.1")

    println(if (failures == 0) "\nALL PASSED" else "\n$failures FAILED"); return failedNames.toList()
}

/** The JUnit test: runs the checks above, and fails naming them if any fail. */
class NetChecks {
    @org.junit.Test
    fun everyCheckPasses() {
        val failed = runChecks()
        org.junit.Assert.assertTrue("Failed checks: " + failed.joinToString("; "), failed.isEmpty())
    }
}
