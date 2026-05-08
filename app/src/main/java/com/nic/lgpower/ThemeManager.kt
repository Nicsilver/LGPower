package com.nic.lgpower

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.json.JSONObject

object ThemeManager {

    private const val PREF_KEY = "theme_id"
    private val cache = mutableMapOf<String, ThemeConfig>()

    fun getActiveThemeId(context: Context): String =
        prefs(context).getString(PREF_KEY, "dark") ?: "dark"

    fun setActiveThemeId(context: Context, id: String) =
        prefs(context).edit().putString(PREF_KEY, id).apply()

    fun getActiveTheme(context: Context): ThemeConfig =
        loadTheme(context, getActiveThemeId(context))

    fun listThemes(context: Context): List<ThemeConfig> =
        (context.assets.list("themes") ?: emptyArray())
            .filter { it.endsWith(".json") }
            .mapNotNull { runCatching { loadTheme(context, it.removeSuffix(".json")) }.getOrNull() }
            .sortedBy { it.name }

    private fun loadTheme(context: Context, id: String): ThemeConfig {
        cache[id]?.let { return it }
        val text = context.assets.open("themes/$id.json").bufferedReader().readText()
        return ThemeConfig.fromJson(JSONObject(text)).also { cache[id] = it }
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
