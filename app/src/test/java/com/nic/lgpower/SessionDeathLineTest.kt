package com.nic.lgpower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDeathLineTest {
    @Test fun peerCloseWithTraffic() {
        assertEquals(
            "cmd session died: cause=peer closed code=1001 reason=Going Away age=42000ms sinceRx=1500ms pointer=kept",
            sessionDeathLine("peer closed code=1001 reason=Going Away", 42_000, 1_500, "kept"),
        )
    }

    @Test fun neverReceivedAnything() {
        assertEquals(
            "cmd session died: cause=replaced age=80ms sinceRx=never pointer=unshared",
            sessionDeathLine("replaced", 80, null, "unshared"),
        )
    }

    @Test fun addressesNeverSurviveScrubbing() {
        val out = scrubAddresses("Failed to connect to /192.168.1.157:3001 via fe80::1ff:fe23:4567:890a and ::1")
        assertEquals("Failed to connect to /<ip> via <ip> and <ip>", out)
        assertFalse(Regex("""\d+\.\d+\.\d+\.\d+""").containsMatchIn(out))
    }

    @Test fun scrubsEveryAddressShape() {
        listOf(
            "192.168.1.157:3001", "/192.168.1.50", "2001:db8:85a3:0:0:8a2e:370:7334", "fe80::1:2:3",
            "[fe80::1%wlan0]:3001", "::1", "::ffff:192.168.1.5", "fd00::abcd:1/64",
        ).forEach { shape ->
            val out = scrubAddresses("x $shape y")
            assertFalse("leaked in: $out", out.contains(Regex("""\d{2,}\.\d|fe80|fd00|2001|::""")))
            assertTrue("no marker in: $out", out.contains("<ip>"))
        }
    }

    @Test fun leavesLookalikesAlone() {
        listOf("12:34:56.789", "04.40.43", "4.9.0.12.5", "33.21.65", "WebOsClient::close").forEach {
            assertEquals(it, scrubAddresses(it))
        }
    }
}
