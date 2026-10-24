package com.nic.lgpower

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
