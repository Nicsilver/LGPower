package com.nic.lgpower

/** The one info line logged when the shared command session dies. Carries no address or key. */
internal fun sessionDeathLine(cause: String, ageMs: Long, sinceRxMs: Long?, pointer: String): String =
    "cmd session died: cause=$cause age=${ageMs}ms sinceRx=${sinceRxMs?.let { "${it}ms" } ?: "never"} pointer=$pointer"
