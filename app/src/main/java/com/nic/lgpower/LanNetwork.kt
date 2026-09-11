package com.nic.lgpower

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The TV is only reachable over the local network. When Android's default
 * network is cellular (it falls back to mobile data whenever the Wi-Fi fails
 * internet validation), unbound sockets route over mobile data and never reach
 * the TV — discovery finds nothing and commands time out. All LAN traffic is
 * therefore explicitly bound to the Wi-Fi/Ethernet network.
 */
object LanNetwork {

    @Suppress("DEPRECATION") // allNetworks: fine for a one-shot lookup, no callback lifecycle needed
    fun get(context: Context): Network? = try {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }
    } catch (_: Exception) {
        // Null falls back to unbound sockets (pre-bind behavior) — degraded, never fatal
        null
    }

    /**
     * IPv4 addresses of the phone's own hotspot interfaces. A hotspot is not a
     * ConnectivityManager network, so it never shows up in [get]; it is an up interface
     * with a private address that no known network owns. Android routes tethered
     * subnets for unbound sockets, so traffic to them must NOT be bound to [get].
     */
    @Suppress("DEPRECATION")
    fun tetheredAddresses(context: Context): List<Inet4Address> = try {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val owned = cm.allNetworks
            .flatMap { n -> cm.getLinkProperties(n)?.linkAddresses?.map { it.address } ?: emptyList() }
            .toSet()
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress && it !in owned }
    } catch (_: Exception) {
        emptyList()
    }

    fun isTethered(context: Context, ip: String): Boolean {
        val prefix = ip.substringBeforeLast('.', "") + "."
        return prefix.length > 1 && tetheredAddresses(context).any { it.hostAddress?.startsWith(prefix) == true }
    }
}
