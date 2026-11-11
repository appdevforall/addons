package org.appdevforall.localstore.server

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** A network interface reduced to what address selection needs, so it can be tested off-device. */
data class Iface(val name: String, val up: Boolean, val loopback: Boolean, val addresses: List<InetAddress>)

/**
 * The external address to bind: the Wi-Fi interface's IPv4 address, preferring interfaces
 * named `wlan*`, otherwise any other up, non-loopback interface with a private IPv4 address.
 * (Hotspot interfaces come later; this is the LAN case.)
 */
fun pickExternalAddress(ifaces: List<Iface>): InetAddress? {
    fun v4(i: Iface) = i.addresses.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    val usable = ifaces.filter { it.up && !it.loopback }
    return usable.filter { it.name.startsWith("wlan") }.firstNotNullOfOrNull(::v4)
        ?: usable.firstNotNullOfOrNull(::v4)
}

fun systemInterfaces(): List<Iface> =
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map {
        Iface(it.name, it.isUp, it.isLoopback, it.inetAddresses.toList())
    }

/** Mobile data, VPN and tunnel interfaces: buyers can never reach the phone through these. */
private val UNREACHABLE = listOf("rmnet", "ccmni", "pdp", "tun", "v4-", "clat", "dummy", "ipsec", "lo")

private fun privateV4(i: Iface) = i.addresses.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }

/** By Android convention, a phone's own Wi-Fi connection is always this interface. */
const val WIFI_CLIENT = "wlan0"

/** The joined Wi-Fi network's address: a private IPv4 address on [WIFI_CLIENT]. */
fun pickWifiAddress(ifaces: List<Iface>): InetAddress? =
    ifaces.filter { it.up && !it.loopback && it.name == WIFI_CLIENT }.firstNotNullOfOrNull(::privateV4)

/**
 * Interfaces that phones use for their own Mobile hotspot: `swlan0` on Samsung, `ap0` or
 * `softap0` on many others, and `wlan1` and up where the hotspot shares Wi-Fi's naming. Android
 * has no public way for apps to ask whether the Mobile hotspot is on, so it's recognized by name.
 * USB (`rndis`, `usb`, `ncm`) and Bluetooth (`bt-pan`) tethering are left out: buyers aren't on those.
 */
private val PHONE_HOTSPOT = Regex("""^(swlan|softap|ap)\d+$|^wlan[1-9]\d*$""")

/** The address of the phone's own Mobile hotspot, turned on in Settings, if it's on. */
fun pickPhoneHotspotAddress(ifaces: List<Iface>): InetAddress? =
    ifaces.filter { it.up && !it.loopback && PHONE_HOTSPOT.matches(it.name) }.firstNotNullOfOrNull(::privateV4)

/** The address of a hotspot the phone is hosting: a private IPv4 address on any interface buyers could reach. */
fun pickHotspotAddress(ifaces: List<Iface>): InetAddress? =
    ifaces.filter { i -> i.up && !i.loopback && UNREACHABLE.none { i.name.startsWith(it) } }.firstNotNullOfOrNull(::privateV4)
