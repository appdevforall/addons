package org.appdevforall.localstore.server

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * Finding the owner's server from a typed code (design, Appendix E.2). The seller's server
 * broadcasts "who has this code?" — as a hash of the code, so the broadcast doesn't reveal it — and
 * only the server showing that code answers, with its port. A QR code carries the address itself,
 * so scanning needs no search.
 */
object ShareDiscovery {
    const val PORT = 6176

    fun probe(code: String): String = "LSD1 " + ShareCrypto.sha256("localstore-discover v1".toByteArray(), code.toByteArray())
        .copyOf(16).joinToString("") { "%02x".format(it) }

    /** Answers probes for whichever share [host] returns, while it's open. */
    class Listener(private val host: () -> ShareHost?, private val httpPort: Int, port: Int = PORT) : Thread("share-discovery") {
        private val socket = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        val port: Int get() = socket.localPort
        init { isDaemon = true }

        override fun run() {
            val buf = ByteArray(512)
            while (!socket.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { socket.receive(p) } catch (e: Exception) { break }
                val h = host() ?: continue
                if (!h.isOpen()) continue
                if (String(p.data, 0, p.length) != probe(h.code)) continue
                val reply = "LSH1 $httpPort".toByteArray()
                try { socket.send(DatagramPacket(reply, reply.size, p.socketAddress)) } catch (e: Exception) { }
            }
        }

        fun close() = socket.close()
    }

    /** The owner's server's address, or null if no server answered within [timeoutMs]. */
    fun find(code: String, targets: List<InetAddress> = broadcastTargets(), port: Int = PORT, timeoutMs: Int = 3_000,
             bind: (DatagramSocket) -> Unit = {}): InetSocketAddress? {
        val normalized = ShareCodes.normalize(code) ?: return null
        DatagramSocket().use { s ->
            bind(s)   // on Android, to the Wi-Fi network
            s.broadcast = true; s.soTimeout = timeoutMs
            val msg = probe(normalized).toByteArray()
            for (t in targets) try { s.send(DatagramPacket(msg, msg.size, t, port)) } catch (e: Exception) { }
            val buf = ByteArray(64)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (e: SocketTimeoutException) { return null }
                val text = String(p.data, 0, p.length)
                val httpPort = text.removePrefix("LSH1 ").toIntOrNull()
                if (text.startsWith("LSH1 ") && httpPort != null && httpPort in 1..65535) return InetSocketAddress(p.address, httpPort)
            }
        }
        return null
    }

    /** Every IPv4 broadcast address of the phone's networks, and the general one. */
    fun broadcastTargets(): List<InetAddress> {
        val out = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                ni.interfaceAddresses.mapNotNullTo(out) { it.broadcast }
            }
        } catch (e: Exception) { }
        return out.distinct()
    }
}
