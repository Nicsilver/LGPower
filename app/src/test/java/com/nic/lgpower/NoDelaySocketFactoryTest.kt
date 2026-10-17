package com.nic.lgpower

import java.net.ServerSocket
import java.net.Socket
import javax.net.SocketFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoDelaySocketFactoryTest {
    private val plain = object : SocketFactory() {
        override fun createSocket() = Socket()
        override fun createSocket(h: String?, p: Int) = Socket(h, p)
        override fun createSocket(h: String?, p: Int, la: java.net.InetAddress?, lp: Int) = Socket(h, p, la, lp)
        override fun createSocket(h: java.net.InetAddress?, p: Int) = Socket(h, p)
        override fun createSocket(a: java.net.InetAddress?, p: Int, la: java.net.InetAddress?, lp: Int) = Socket(a, p, la, lp)
    }

    @Test fun unconnectedSocketGetsNoDelay() {
        assertFalse(plain.createSocket().tcpNoDelay)
        assertTrue(NoDelaySocketFactory(plain).createSocket().tcpNoDelay)
    }

    @Test fun connectedSocketGetsNoDelay() {
        ServerSocket(0).use { server ->
            NoDelaySocketFactory(plain).createSocket("127.0.0.1", server.localPort).use {
                assertTrue(it.tcpNoDelay)
            }
        }
    }
}
