package com.nic.lgpower

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Apple-style custom theme editor. The user picks a handful of seed colors and
 * an appearance; the full theme is derived live into the preview at the top.
 * Launched either to create a new theme (seeded from a base) or edit an
 * existing custom one.
 */
class ThemeEditorActivity : AppCompatActivity() {

    companion object {
        /** Theme id to copy seed colors from when creating a new theme. */
        const val EXTRA_BASE_ID = "base_id"
        /** Id of an existing custom theme to edit in place (optional). */
        const val EXTRA_EDIT_ID = "edit_id"
    }

    private val d get() = resources.displayMetrics.density
    private fun dp(v: Float) = (v * d).toInt()

    private lateinit var active: ThemeConfig   // theme used to style the editor chrome
    private var editingId: String? = null      // non-null when editing an existing custom theme

    // Working seed state
    private var name = "My Theme"
    private var light = false
    private var seedBg = 0
    private var seedSurface = 0
    private var seedText = 0
    private var seedSecondary = 0
    private var seedAccent = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_editor)
        SystemBars.applyTo(this, findViewById(R.id.editor_root), ThemeManager.getActiveTheme(this).windowBg)
        active = ThemeManager.getActiveTheme(this)

        // Seed from either the theme being edited or a base theme to clone.
        editingId = intent.getStringExtra(EXTRA_EDIT_ID)
        val baseId = editingId ?: intent.getStringExtra(EXTRA_BASE_ID) ?: ThemeManager.getActiveThemeId(this)
        val base = runCatching { ThemeManager.listThemes(this).first { it.id == baseId } }
            .getOrElse { active }

        seedBg = base.seedBg
        seedSurface = base.seedSurface
        seedText = base.seedText
        seedSecondary = base.seedSecondary
        seedAccent = base.seedAccent
        light = base.statusBarLightIcons
        name = if (editingId != null) base.name else "${base.name} Copy"

        styleChrome()
        findViewById<TextView>(R.id.editor_title).text = if (editingId != null) "Edit Theme" else "New Theme"

        findViewById<EditText>(R.id.edit_theme_name).apply {
            setText(name)
            setSelection(text.length)
            addTextChangedListener(SimpleWatcher { name = it })
        }

        buildSegment()
        buildColorRows()
        refreshPreview()

        findViewById<Button>(R.id.btn_save_theme).setOnClickListener { save() }
        findViewById<Button>(R.id.btn_cancel_theme).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_delete_theme).apply {
            visibility = if (editingId != null) View.VISIBLE else View.GONE
            setOnClickListener { confirmDelete() }
        }
    }

    // ── Editor chrome (styled with the active app theme) ───────────────────────

    @Suppress("DEPRECATION")
    private fun styleChrome() {
        val root = findViewById<View>(R.id.editor_root)
        root.setBackgroundColor(active.windowBg)
        ThemeManager.applyToRoot(root, active)
        window.statusBarColor = active.windowBg
        window.navigationBarColor = active.windowBg
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val flags = window.decorView.systemUiVisibility
            window.decorView.systemUiVisibility = if (active.statusBarLightIcons)
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
        findViewById<EditText>(R.id.edit_theme_name).apply {
            setTextColor(active.primaryText)
            setHintTextColor(ColorUtil.withAlpha(active.secondaryText, 0x66))
        }
        findViewById<Button>(R.id.btn_save_theme).apply {
            background = roundRect(active.btnAccentBg, 12f)
            setTextColor(active.btnAccentText)
        }
        findViewById<Button>(R.id.btn_cancel_theme).setTextColor(active.secondaryText)
    }

    private fun buildSegment() {
        val seg = findViewById<LinearLayout>(R.id.appearance_segment)
        seg.removeAllViews()
        seg.background = roundRect(ColorUtil.mix(active.surfaceBg, active.primaryText, 0.08f), 10f)
        seg.setPadding(dp(3f), dp(3f), dp(3f), dp(3f))

        fun half(label: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
            if (selected) {
                background = roundRect(active.btnAccentBg, 8f)
                setTextColor(active.btnAccentText)
            } else {
                setTextColor(active.secondaryText)
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        seg.addView(half("Dark", !light) { if (light) setAppearance(light = false) })
        seg.addView(half("Light", light) { if (!light) setAppearance(light = true) })
    }

    // Appearance is a starting point: it swaps the neutral seeds for the built-in Dark
    // or Light palette and keeps the accent, so the choice is visible straight away
    private fun setAppearance(light: Boolean) {
        this.light = light
        val base = runCatching { ThemeManager.listThemes(this).first { it.id == if (light) "light" else "dark" } }.getOrNull()
        if (base != null) {
            seedBg = base.seedBg
            seedSurface = base.seedSurface
            seedText = base.seedText
            seedSecondary = base.seedSecondary
        }
        buildSegment()
        buildColorRows()
        refreshPreview()
    }

    private data class ColorRow(val label: String, val get: () -> Int, val set: (Int) -> Unit)

    private fun buildColorRows() {
        val group = findViewById<LinearLayout>(R.id.colors_group)
        group.removeAllViews()
        val rows = listOf(
            ColorRow("Background",     { seedBg },        { seedBg = it }),
            ColorRow("Surface",        { seedSurface },   { seedSurface = it }),
            ColorRow("Text",           { seedText },      { seedText = it }),
            ColorRow("Secondary Text", { seedSecondary }, { seedSecondary = it }),
            ColorRow("Accent",         { seedAccent },    { seedAccent = it })
        )
        rows.forEachIndexed { i, row ->
            if (i > 0) group.addView(View(this).apply {
                setBackgroundColor(active.divider)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    .also { it.marginStart = dp(16f) }
            })
            group.addView(colorRowView(row))
        }
    }

    private fun colorRowView(row: ColorRow): View {
        val swatch = View(this).apply {
            background = swatchDrawable(row.get())
            layoutParams = LinearLayout.LayoutParams(dp(26f), dp(26f))
        }
        val hex = TextView(this).apply {
            text = ColorUtil.toHex(row.get())
            textSize = 14f
            setTextColor(active.secondaryText)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .also { it.marginEnd = dp(10f) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            setPadding(dp(16f), 0, dp(16f), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52f))
            addView(TextView(context).apply {
                text = row.label
                textSize = 15f
                setTextColor(active.primaryText)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(hex)
            addView(swatch)
            setOnClickListener {
                showColorPicker(row.label, row.get()) { picked ->
                    row.set(picked or 0xFF000000.toInt())
                    swatch.background = swatchDrawable(row.get())
                    hex.text = ColorUtil.toHex(row.get())
                    refreshPreview()
                }
            }
        }
    }

    // ── Live preview (styled with the theme being edited) ──────────────────────

    private fun currentTheme(): ThemeConfig = ThemeConfig.derived(
        id = editingId ?: "preview", name = name, light = light,
        bg = seedBg, surface = seedSurface, text = seedText,
        secondary = seedSecondary, accent = seedAccent
    )

    private fun refreshPreview() {
        val t = currentTheme()
        val frame = findViewById<View>(R.id.preview_frame)
        frame.background = roundRect(t.windowBg, 18f)
        frame.clipToOutline = true
        ThemeManager.applyToRoot(frame, t)

        findViewById<Button>(R.id.preview_accent).apply {
            background = android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(ColorUtil.withAlpha(t.btnAccentText, 0x33)),
                roundRect(t.btnAccentBg, 12f), null)
            setTextColor(t.btnAccentText)
            setOnClickListener { this@ThemeEditorActivity.findViewById<Switch>(R.id.preview_switch).toggle() }
        }
        wirePreviewPill(t)
        findViewById<ImageView>(R.id.preview_icon_power).setColorFilter(t.circleBtnIconTint)
        findViewById<ImageView>(R.id.preview_icon_home).setColorFilter(t.circleBtnIconTint)
        findViewById<Switch>(R.id.preview_switch).apply {
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(t.switchTrackOn, t.switchTrackOff)
            )
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(t.switchThumbOn, t.switchThumbOff)
            )
        }
    }

    // The preview pill behaves like the volume pill on the remote: drag to set a level
    private var previewLevel = 24
    private fun wirePreviewPill(t: ThemeConfig) {
        val pill = findViewById<View>(R.id.preview_pill)
        val fill = findViewById<View>(R.id.preview_pill_fill)
        val value = findViewById<TextView>(R.id.preview_pill_value)
        // Plain rectangle clipped by the pill's own rounded outline, like the real slider
        pill.clipToOutline = true
        fill.setBackgroundColor(ColorUtil.withAlpha(t.pillLabelText, 0x33))
        fun render() {
            value.text = previewLevel.toString()
            fill.post {
                fill.layoutParams = fill.layoutParams.also { it.width = (pill.width * previewLevel / 100f).toInt() }
                fill.requestLayout()
            }
        }
        render()
        pill.setOnTouchListener { v, e ->
            when (e.action) {
                android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    previewLevel = (e.x / v.width * 100).toInt().coerceIn(0, 100)
                    render(); true
                }
                android.view.MotionEvent.ACTION_UP -> { v.performClick(); true }
                else -> false
            }
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private fun save() {
        val finalName = name.trim().ifEmpty { "My Theme" }
        val id = editingId ?: ThemeManager.newCustomId(this)
        val theme = ThemeConfig.derived(
            id = id, name = finalName, light = light,
            bg = seedBg, surface = seedSurface, text = seedText,
            secondary = seedSecondary, accent = seedAccent
        )
        ThemeManager.saveCustom(this, theme)
        ThemeManager.setActiveThemeId(this, id)
        Toast.makeText(this, "\"$finalName\" saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete \"$name\"?")
            .setMessage("This custom theme will be removed.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                editingId?.let { ThemeManager.deleteCustom(this, it) }
                finish()
            }
            .show()
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius * d
        setColor(color)
    }

    private fun swatchDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1f), ColorUtil.withAlpha(active.primaryText, 0x33))
    }

    private class SimpleWatcher(val onChange: (String) -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { onChange(s?.toString() ?: "") }
    }
}
