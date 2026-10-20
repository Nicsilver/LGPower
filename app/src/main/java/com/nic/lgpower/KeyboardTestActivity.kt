package com.nic.lgpower

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Settings > About, long-press on the app header: probes the TV's typing support and builds a report to paste. */
class KeyboardTestActivity : AppCompatActivity() {

    private var report: KeyboardTest.Report? = null
    private var userSaw: String? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_keyboard_test)
        val root = findViewById<View>(R.id.kbt_root)
        SystemBars.applyTo(this, root, ThemeManager.getActiveTheme(this).windowBg)
        applyTheme()

        findViewById<Button>(R.id.kbt_start).setOnClickListener { start() }
        mapOf(
            R.id.kbt_saw_both to "both texts appeared",
            R.id.kbt_saw_some to "only one of the texts appeared",
            R.id.kbt_saw_none to "nothing appeared"
        ).forEach { (id, answer) ->
            findViewById<Button>(id).setOnClickListener { userSaw = answer; showReport() }
        }
        findViewById<Button>(R.id.kbt_copy).setOnClickListener {
            val text = report?.render(userSaw) ?: return@setOnClickListener
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("LG Power keyboard test", text))
            Toast.makeText(this, "Report copied", Toast.LENGTH_SHORT).show()
        }
    }

    private fun start() {
        if (running) return
        running = true
        report = null; userSaw = null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val start = findViewById<Button>(R.id.kbt_start)
        val progress = findViewById<TextView>(R.id.kbt_progress)
        start.isEnabled = false
        start.alpha = 0.5f
        progress.visibility = View.VISIBLE
        progress.text = "Starting"
        findViewById<View>(R.id.kbt_answer_block).visibility = View.GONE
        findViewById<View>(R.id.kbt_report_block).visibility = View.GONE

        val version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        Thread {
            val result = KeyboardTest(WebOsClient(this), version).run { line ->
                runOnUiThread { progress.text = line }
            }
            runOnUiThread {
                running = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                report = result
                start.isEnabled = true
                start.alpha = 1f
                start.text = "Run again"
                progress.text = "Finished"
                findViewById<View>(R.id.kbt_answer_block).visibility = View.VISIBLE
                showReport()
            }
        }.start()
    }

    private fun showReport() {
        val r = report ?: return
        findViewById<TextView>(R.id.kbt_report).text = r.render(userSaw)
        findViewById<View>(R.id.kbt_report_block).visibility = View.VISIBLE
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val theme = ThemeManager.getActiveTheme(this)
        ThemeManager.applyToRoot(findViewById(R.id.kbt_root), theme)
        window.statusBarColor = theme.windowBg
        window.navigationBarColor = theme.windowBg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val flags = window.decorView.systemUiVisibility
            window.decorView.systemUiVisibility = if (theme.statusBarLightIcons)
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        val dp = resources.displayMetrics.density
        listOf(R.id.kbt_start, R.id.kbt_copy).forEach { id ->
            findViewById<Button>(id).apply {
                background = GradientDrawable().apply { cornerRadius = 10f * dp; setColor(theme.btnAccentBg) }
                setTextColor(theme.btnAccentText)
            }
        }
        listOf(R.id.kbt_saw_both, R.id.kbt_saw_some, R.id.kbt_saw_none).forEach { id ->
            findViewById<Button>(id).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 10f * dp; setColor(0)
                    setStroke((1f * dp).toInt(), theme.btnGhostBorder)
                }
                setTextColor(theme.primaryText)
            }
        }
    }
}
