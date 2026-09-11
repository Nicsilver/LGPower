package com.nic.lgpower

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15 draws every app edge to edge, so the status bar sits on top of whatever is at
 * the top of the layout (the gear and status dot on the remote). Padding the root by the
 * system-bar insets keeps the layout clear of the bars on every version, and painting the
 * root in the theme background keeps the bar areas the right colour.
 */
object SystemBars {
    fun applyTo(activity: Activity, root: View, background: Int) {
        // Below 35 the decor still keeps content clear of the bars itself; padding again
        // there would reserve the status bar height twice.
        if (Build.VERSION.SDK_INT < 35) return
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        root.setBackgroundColor(background)
        val l = root.paddingLeft; val t = root.paddingTop; val r = root.paddingRight; val b = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
            insets
        }
    }
}
