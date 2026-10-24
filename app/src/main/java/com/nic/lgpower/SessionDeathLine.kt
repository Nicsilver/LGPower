package com.nic.lgpower

/** The one info line logged when the shared command session dies. Carries no address or key. */
internal fun sessionDeathLine(cause: String, ageMs: Long, sinceRxMs: Long?, pointer: String): String =
    "cmd session died: cause=$cause age=${ageMs}ms sinceRx=${sinceRxMs?.let { "${it}ms" } ?: "never"} pointer=$pointer"

private val ipv4 = Regex("""\d{1,3}(?:\.\d{1,3}){3}(?::\d+)?""")
private val ipv6 = Regex("""(?:[0-9a-fA-F]{0,4}:){2,}[0-9a-fA-F]{0,4}""")

/** Exception texts name the host they failed to reach, and the TV's address must not reach logs. */
internal fun scrubAddresses(text: String?): String =
    (text ?: "none").replace(ipv4, "<ip>").replace(ipv6, "<ip>")
