package com.nic.lgpower

import android.graphics.Color

/** Small color-math helpers shared by the theme engine and the editor. */
object ColorUtil {

    /** Linear blend of two colors in RGB space. t=0 → a, t=1 → b. */
    fun mix(a: Int, b: Int, t: Float): Int {
        val s = t.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a)   + (Color.red(b)   - Color.red(a))   * s).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * s).toInt(),
            (Color.blue(a)  + (Color.blue(b)  - Color.blue(a))  * s).toInt()
        )
    }

    /** Same opaque RGB, new alpha (0..255). */
    fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /** Perceived luminance, 0 (black) .. 1 (white). */
    fun luminance(color: Int): Float =
        (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f

    /** Black or white, whichever reads better on top of [color]. */
    fun contrastText(color: Int): Int =
        if (luminance(color) > 0.55f) 0xFF1A1A1A.toInt() else 0xFFFFFFFF.toInt()

    /** "#RRGGBB" (alpha dropped) for display in the editor. */
    fun toHex(color: Int): String =
        String.format("#%06X", 0xFFFFFF and color)

    /** "#AARRGGBB", used when persisting so semi-transparent values survive. */
    fun toHexArgb(color: Int): String =
        String.format("#%08X", color)

    /** Parse "#RGB", "#RRGGBB" or "#AARRGGBB"; returns null on malformed input. */
    fun parseOrNull(text: String): Int? = runCatching {
        var s = text.trim()
        if (!s.startsWith("#")) s = "#$s"
        if (s.length == 4) // #RGB → #RRGGBB
            s = "#" + s.substring(1).map { "$it$it" }.joinToString("")
        Color.parseColor(s)
    }.getOrNull()
}
