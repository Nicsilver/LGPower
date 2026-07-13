package com.nic.lgpower

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject
import java.io.File

object ThemeManager {

    private const val PREF_KEY = "theme_id"
    private val cache = mutableMapOf<String, ThemeConfig>()

    // Dark and Light are pinned to the top of the picker, in that order; the rest
    // (built-in then custom) follow alphabetically by name.
    private val pinnedOrder = listOf("dark", "light")

    fun getActiveThemeId(context: Context): String =
        prefs(context).getString(PREF_KEY, "dark") ?: "dark"

    fun setActiveThemeId(context: Context, id: String) =
        prefs(context).edit().putString(PREF_KEY, id).apply()

    fun getActiveTheme(context: Context): ThemeConfig =
        runCatching { loadTheme(context, getActiveThemeId(context)) }
            .getOrElse { loadTheme(context, "dark") }

    fun listThemes(context: Context): List<ThemeConfig> {
        val builtIn = (context.assets.list("themes") ?: emptyArray())
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
        val custom = customDir(context).listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension } ?: emptyList()
        return (builtIn + custom).distinct()
            .mapNotNull { runCatching { loadTheme(context, it) }.getOrNull() }
            .sortedWith(compareBy(
                { pinnedOrder.indexOf(it.id).let { i -> if (i == -1) Int.MAX_VALUE else i } },
                { it.name.lowercase() }
            ))
    }

    // ── Custom theme persistence ───────────────────────────────────────────────

    private fun customDir(context: Context): File =
        File(context.filesDir, "themes").apply { if (!exists()) mkdirs() }

    private fun customFile(context: Context, id: String) = File(customDir(context), "$id.json")

    fun isCustom(context: Context, id: String): Boolean = customFile(context, id).exists()

    /** Persist a user theme (by its seed colors) and refresh the cache. */
    fun saveCustom(context: Context, theme: ThemeConfig) {
        customFile(context, theme.id).writeText(theme.toSeedJson().toString(2))
        cache[theme.id] = theme
    }

    fun deleteCustom(context: Context, id: String) {
        customFile(context, id).delete()
        cache.remove(id)
        if (getActiveThemeId(context) == id) setActiveThemeId(context, "dark")
    }

    /** A fresh, unused id for a new custom theme. */
    fun newCustomId(context: Context): String {
        var n = 1
        while (customFile(context, "custom_$n").exists()) n++
        return "custom_$n"
    }

    private fun loadTheme(context: Context, id: String): ThemeConfig {
        cache[id]?.let { return it }
        val custom = customFile(context, id)
        val cfg = if (custom.exists()) {
            ThemeConfig.fromSeedJson(JSONObject(custom.readText()))
        } else {
            val text = context.assets.open("themes/$id.json").bufferedReader().readText()
            ThemeConfig.fromJson(JSONObject(text))
        }
        return cfg.also { cache[id] = it }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("webos", Context.MODE_PRIVATE)

    /** Walk the view tree applying colors to any view that carries a theme tag. */
    fun applyToRoot(root: View, theme: ThemeConfig) = walkViews(root, theme)

    private fun walkViews(view: View, theme: ThemeConfig) {
        applyTag(view, theme)
        if (view is ViewGroup) repeat(view.childCount) { walkViews(view.getChildAt(it), theme) }
    }

    private fun applyTag(view: View, theme: ThemeConfig) {
        val density = view.context.resources.displayMetrics.density
        when (view.tag as? String) {
            "window_bg"     -> view.setBackgroundColor(theme.windowBg)
            "surface_bg"    -> view.background = roundRect(theme.surfaceBg, 14f * density)
            "btn_label"     -> (view as? TextView)?.setTextColor(theme.secondaryText)
            "section_label" -> (view as? TextView)?.setTextColor(theme.sectionLabel)
            "row_label"     -> (view as? TextView)?.setTextColor(theme.primaryText)
            "row_value"     -> (view as? TextView)?.setTextColor(theme.secondaryText)
            "divider"       -> view.setBackgroundColor(theme.divider)
            "circle_btn"    -> view.background = oval(theme.circleBtnBg)
            "dpad_ring"     -> view.background = oval(theme.dpadRingBg)
            "pill"          -> view.background = roundRect(theme.pillBg, 28f * density)
            "pill_divider"  -> view.setBackgroundColor(theme.pillDivider)
            "pill_label"    -> (view as? TextView)?.setTextColor(theme.pillLabelText)
            "title"         -> (view as? TextView)?.setTextColor(theme.primaryText)
        }
    }

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }
}
