package com.nic.lgpower

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** One saved TV: name, address, MAC, and the switch / remove actions. */
class TvDetailActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("webos", MODE_PRIVATE) }
    private lateinit var tvId: String
    private var removed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tvId = intent.getStringExtra(EXTRA_TV_ID) ?: run { finish(); return }
        val tv = TvStore.list(prefs).firstOrNull { it.id == tvId } ?: run { finish(); return }
        setContentView(R.layout.activity_tv_detail)
        SystemBars.applyTo(this, findViewById(R.id.tv_detail_root), ThemeManager.getActiveTheme(this).windowBg)
        applyTheme()

        val isActive = TvStore.activeId(prefs) == tvId
        findViewById<TextView>(R.id.tv_detail_title).text = tv.name
        findViewById<EditText>(R.id.edit_name).setText(tv.name)
        findViewById<EditText>(R.id.edit_tv_ip).setText(tv.ip)
        findViewById<EditText>(R.id.edit_tv_mac).setText(tv.mac)
        findViewById<Button>(R.id.btn_use_tv).visibility = if (isActive) View.GONE else View.VISIBLE

        findViewById<Button>(R.id.btn_use_tv).setOnClickListener {
            save()
            TvStore.switchTo(prefs, tvId)
            finish()
        }
        findViewById<Button>(R.id.btn_remove_tv).setOnClickListener {
            showWarningSheet(
                chipText = "REMOVE TV",
                title = "Remove ${tv.name}?",
                body = "The pairing with this TV is forgotten. Adding it again asks the TV to pair once more.",
                buttonText = "Remove",
                cancelClosesScreen = false,
                onAccept = { removed = true; TvStore.remove(prefs, tvId); finish() }
            )
        }
    }

    override fun onPause() {
        super.onPause()
        if (!removed) save()
    }

    private fun save() {
        TvStore.update(
            prefs, tvId,
            name = findViewById<EditText>(R.id.edit_name).text.toString(),
            ip = findViewById<EditText>(R.id.edit_tv_ip).text.toString().trim(),
            mac = findViewById<EditText>(R.id.edit_tv_mac).text.toString().trim()
        )
    }

    @Suppress("DEPRECATION")
    private fun applyTheme() {
        val theme = ThemeManager.getActiveTheme(this)
        ThemeManager.applyToRoot(findViewById(R.id.tv_detail_root), theme)
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
        fun ghost() = GradientDrawable().apply {
            cornerRadius = 10f * dp; setColor(0)
            setStroke((1f * dp).toInt(), theme.btnGhostBorder)
        }
        findViewById<Button>(R.id.btn_use_tv).apply {
            background = GradientDrawable().apply { cornerRadius = 10f * dp; setColor(theme.btnAccentBg) }
            setTextColor(theme.btnAccentText)
        }
        findViewById<Button>(R.id.btn_remove_tv).apply {
            background = ghost()
            setTextColor(0xFFE05555.toInt())
        }
        listOf(R.id.edit_name, R.id.edit_tv_ip, R.id.edit_tv_mac).forEach { id ->
            val et = findViewById<EditText>(id)
            et.setTextColor(theme.secondaryText)
            et.setHintTextColor(Color.argb(120, Color.red(theme.secondaryText), Color.green(theme.secondaryText), Color.blue(theme.secondaryText)))
        }
    }

    companion object {
        const val EXTRA_TV_ID = "tv_id"
    }
}
