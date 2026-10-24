package com.nic.lgpower

/** One LgpTest line. Kept free of Android classes so it runs in plain JVM unit tests. */
object TestLogJson {
    /** [kv] alternates field name and value (String, Int, Long, Boolean or null, which is left out). */
    fun line(app: String, t: Long, ev: String, kv: Array<out Any?>): String {
        val sb = StringBuilder(96)
        sb.append("{\"app\":\"").append(app).append("\",\"t\":").append(t).append(",\"ev\":\"")
        escape(sb, ev)
        sb.append('"')
        var i = 0
        while (i + 1 < kv.size) {
            val v = kv[i + 1]
            if (v != null) {
                sb.append(",\"")
                escape(sb, kv[i] as String)
                sb.append("\":")
                if (v is String) { sb.append('"'); escape(sb, v); sb.append('"') } else sb.append(v)
            }
            i += 2
        }
        return sb.append('}').toString()
    }

    fun escape(sb: StringBuilder, s: String) {
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
            else -> sb.append(c)
        }
    }

    fun short(s: String?, max: Int = 80): String? =
        s?.replace('\n', ' ')?.let { if (it.length > max) it.substring(0, max) else it }
}
