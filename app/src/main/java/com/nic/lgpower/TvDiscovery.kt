package com.nic.lgpower

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object TvDiscovery {

    private const val SSDP_ADDR = "239.255.255.255"
    private const val SSDP_PORT = 1900
    private const val WEBOS_PORT = 3001
    private const val SCAN_MS   = 3000L

    private fun ssdpSearch(st: String) =
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.255:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"

    /** Blocking — call from a background thread. Returns deduplicated IPs. */
    fun discover(context: Context): List<String> {
        val results = Collections.synchronizedSet(LinkedHashSet<String>())
        val latch   = CountDownLatch(2)

        Thread { ssdpScan(context, results); latch.countDown() }.start()
        Thread { portScan(results);          latch.countDown() }.start()

        latch.await(SCAN_MS + 1000, TimeUnit.MILLISECONDS)
        return results.toList()
    }

    // ── SSDP ──────────────────────────────────────────────────────────────────

    private fun ssdpScan(context: Context, out: MutableSet<String>) {
        val wm   = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wm.createMulticastLock("lg_power_ssdp").also { it.acquire() }
        try {
            val socket = DatagramSocket().apply { soTimeout = 500 }
            val addr   = InetAddress.getByName(SSDP_ADDR)
            listOf(
                ssdpSearch("ssdp:all"),
                ssdpSearch("urn:lge-com:service:webos-second-screen:1"),
                ssdpSearch("urn:dial-multiscreen-org:service:dial:1"),
            ).forEach { msg ->
                val data = msg.toByteArray()
                socket.send(DatagramPacket(data, data.size, addr, SSDP_PORT))
            }
            val buf      = ByteArray(2048)
            val deadline = System.currentTimeMillis() + SCAN_MS
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    extractIp(String(pkt.data, 0, pkt.length))?.let { out.add(it) }
                } catch (_: SocketTimeoutException) { }
            }
            socket.close()
        } catch (_: Exception) {
        } finally {
            lock.release()
        }
    }

    private fun extractIp(response: String): String? {
        val line = response.lines().firstOrNull {
            it.startsWith("LOCATION", ignoreCase = true)
        } ?: return null
        return runCatching { URL(line.substringAfter(":").trim()).host }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }

    // ── Port scan ─────────────────────────────────────────────────────────────
    // Scan every host on the local /24 for port 3001 (WebOS WSS port).
    // With 50 threads and 300ms timeout this takes ~2s for 254 hosts.

    private fun portScan(out: MutableSet<String>) {
        val local = getLocalIp() ?: return
        val base  = local.address  // 4 bytes, e.g. [192, 168, 1, x]

        val executor = Executors.newFixedThreadPool(50)
        val latch    = CountDownLatch(254)

        for (i in 1..254) {
            val hostBytes = byteArrayOf(base[0], base[1], base[2], i.toByte())
            executor.submit {
                try {
                    Socket().use { it.connect(InetSocketAddress(InetAddress.getByAddress(hostBytes), WEBOS_PORT), 300) }
                    out.add(hostBytes.joinToString(".") { b -> (b.toInt() and 0xFF).toString() })
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await(SCAN_MS, TimeUnit.MILLISECONDS)
        executor.shutdownNow()
    }

    private fun getLocalIp(): Inet4Address? =
        NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull()
}
