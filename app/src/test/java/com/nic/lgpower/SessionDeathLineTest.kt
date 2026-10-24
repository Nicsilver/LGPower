package com.nic.lgpower

import org.junit.Assert.assertEquals
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
}
