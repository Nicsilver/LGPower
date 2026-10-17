package com.nic.lgpower

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Pointer moves and key presses are a few dozen bytes each; with Nagle's algorithm on, the
 * stack holds a small write back until the previous one is acknowledged, so bursts arrive
 * clumped. OkHttp layers TLS over the plain socket this factory hands out.
 */
class NoDelaySocketFactory(private val delegate: SocketFactory) : SocketFactory() {

    private fun Socket.tuned() = apply { tcpNoDelay = true }

    override fun createSocket(): Socket = delegate.createSocket().tuned()

    override fun createSocket(host: String?, port: Int): Socket =
        delegate.createSocket(host, port).tuned()

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(host, port, localHost, localPort).tuned()

    override fun createSocket(host: InetAddress?, port: Int): Socket =
        delegate.createSocket(host, port).tuned()

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        delegate.createSocket(address, port, localAddress, localPort).tuned()
}
