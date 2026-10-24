package com.nic.lgpower

/** The one info line logged when the shared command session dies. Carries no address or key. */
internal fun sessionDeathLine(cause: String, ageMs: Long, sinceRxMs: Long?, pointer: String): String =
    "cmd session died: cause=$cause age=${ageMs}ms sinceRx=${sinceRxMs?.let { "${it}ms" } ?: "never"} pointer=$pointer"

private const val OCTET = """(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)"""
private const val V4 = """$OCTET(?:\.$OCTET){3}"""
private const val HEX = "[0-9A-Fa-f]{1,4}"

// Lookarounds keep clock times, firmware versions and "Class::method" from matching
private val ipv6 = Regex(
    """(?<![\w:.])(?:(?:$HEX:){7}$HEX|(?:$HEX:){6}$V4|(?:$HEX(?::$HEX)*)?::(?:(?:$HEX:)*$V4|$HEX(?::$HEX)*)?)(?:%[\w.~-]+)?(?![\w:])"""
)
private val ipv4 = Regex("""(?<![\w.])$V4(?::\d{1,5})?(?![\w]|\.\d)""")

/** Exception texts name the host they failed to reach, and the TV's address must not reach logs. */
internal fun scrubAddresses(text: String?): String =
    (text ?: "none").replace(ipv6, "<ip>").replace(ipv4, "<ip>")
