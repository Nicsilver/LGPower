package com.nic.lgpower

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * The TV is only reachable over the local network. When Android's default
 * network is cellular (it falls back to mobile data whenever the Wi-Fi fails
 * internet validation), unbound sockets route over mobile data and never reach
 * the TV — discovery finds nothing and commands time out. All LAN traffic is
 * therefore explicitly bound to the Wi-Fi/Ethernet network.
 */
object LanNetwork {

    @Suppress("DEPRECATION") // allNetworks: fine for a one-shot lookup, no callback lifecycle needed
    fun get(context: Context): Network? {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        }
    }
}
