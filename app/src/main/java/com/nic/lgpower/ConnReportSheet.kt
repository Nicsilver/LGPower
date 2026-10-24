package com.nic.lgpower

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
private fun wifiSnapshot(activity: Activity): WifiSnapshot? {
    val info = runCatching {
        activity.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
    }.getOrNull() ?: return null
    // A phone off Wi-Fi reports a zero frequency and the "no signal" RSSI sentinel
    if (info.frequency <= 0 || info.rssi <= -127) return null
    return WifiSnapshot(info.frequency, info.rssi, info.linkSpeed)
}

private val running = AtomicBoolean(false)

private fun collectReport(activity: Activity, client: WebOsClient, progress: (String) -> Unit): String {
    // The remote is not in front while Settings is, so take the lock for the test to measure the same radio mode
    val held = LowLatencyWifi.acquire(activity, "report")
    val tv = try {
        client.probeConnection(progress)
    } catch (e: Exception) {
        TvProbe(failure = "${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
    } finally {
        LowLatencyWifi.release("report")
    }
    return ConnReport.finish(ReportInputs(
        appName = "LG Power (Kotlin)",
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        phone = "${Build.MANUFACTURER} ${Build.MODEL}",
        os = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        wifi = wifiSnapshot(activity),
        lockLine = if (held) "${LowLatencyWifi.modeName}, held during the test"
        else "${LowLatencyWifi.modeName} requested, not granted",
        tv = tv,
        deaths = DeathLog.shared.recent(),
        nowMs = System.currentTimeMillis(),
    ))
}

/** Settings > About > Copy connection report: runs the probe behind a progress sheet, then offers the text to copy. */
fun Activity.showConnReportSheet(client: WebOsClient) {
    // A second tap would close the sessions the first probe is timing
    if (!running.compareAndSet(false, true)) return
    val theme = ThemeManager.getActiveTheme(this)
    val d = resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()

    val dialog = Dialog(this)
    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(10), dp(20), dp(20))
        background = GradientDrawable().apply {
            setColor(theme.surfaceBg)
            val r = 20 * d
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
    }
    content.addView(View(this).apply {
        background = GradientDrawable().apply { cornerRadius = 2 * d; setColor(theme.divider) }
        layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14)
        }
    })
    content.addView(TextView(this).apply {
        text = "Connection report"
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(theme.primaryText)
    })

    val status = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, dp(6))
    }
    status.addView(ProgressBar(this).apply {
        isIndeterminate = true
        indeterminateTintList = android.content.res.ColorStateList.valueOf(theme.btnAccentBg)
        layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) }
    })
    val progressText = TextView(this).apply {
        text = "Starting"
        textSize = 13f
        setTextColor(theme.secondaryText)
    }
    status.addView(progressText)
    content.addView(status)

    val reportText = TextView(this).apply {
        textSize = 11.5f
        typeface = Typeface.MONOSPACE
        setTextColor(theme.primaryText)
        setTextIsSelectable(true)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    val reportScroll = ScrollView(this).apply {
        visibility = View.GONE
        background = GradientDrawable().apply { cornerRadius = 10 * d; setColor(theme.windowBg) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10) }
        addView(reportText)
    }
    content.addView(reportScroll)

    val copy = TextView(this).apply {
        text = "Copy"
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(theme.btnAccentText)
        background = GradientDrawable().apply { cornerRadius = 11 * d; setColor(theme.btnAccentBg) }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(14) }
        isClickable = true; isFocusable = true
        visibility = View.GONE
    }
    content.addView(copy)

    dialog.setContentView(content)
    dialog.window?.apply {
        setBackgroundDrawableResource(android.R.color.transparent)
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.82f).toInt())
        setGravity(Gravity.BOTTOM)
        addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        setDimAmount(0.55f)
        attributes = attributes.also { it.windowAnimations = android.R.style.Animation_InputMethod }
    }
    dialog.show()

    // A dialog outliving its activity (rotation) leaks the window
    (this as? LifecycleOwner)?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) { if (dialog.isShowing) dialog.dismiss() }
    })

    Thread {
        val text = try {
            collectReport(this, client) { line -> runOnUiThread { progressText.text = line } }
        } finally {
            running.set(false)
        }
        runOnUiThread {
            if (!dialog.isShowing) return@runOnUiThread
            status.visibility = View.GONE
            reportText.text = text
            reportScroll.visibility = View.VISIBLE
            copy.visibility = View.VISIBLE
            copy.setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("LG Power connection report", text))
                copy.text = "Copied"
            }
        }
    }.start()
}
