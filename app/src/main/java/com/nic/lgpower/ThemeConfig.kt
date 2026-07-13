package com.nic.lgpower

import android.graphics.Color
import org.json.JSONObject

data class ThemeConfig(
    val id: String,
    val name: String,
    val windowBg: Int,
    val surfaceBg: Int,
    val primaryText: Int,
    val secondaryText: Int,
    val pillLabelText: Int,
    val sectionLabel: Int,
    val divider: Int,
    val pillBg: Int,
    val pillDivider: Int,
    val circleBtnBg: Int,
    val circleBtnIconTint: Int,
    val dpadRingBg: Int,
    val switchTrackOn: Int,
    val switchTrackOff: Int,
    val switchThumbOn: Int,
    val switchThumbOff: Int,
    val btnAccentBg: Int,
    val btnAccentText: Int,
    val btnGhostBorder: Int,
    val btnGhostBorderPressed: Int,
    val btnGhostBgPressed: Int,
    val dpadOkBg: Int,
    val statusBarLightIcons: Boolean,
    // ── Editor metadata ──────────────────────────────────────────────────────
    // The small set of "seed" colors a user actually picks; everything above is
    // derived from these. Built-in themes back-fill seeds from their own values
    // so any theme can be used as a starting point for a custom one.
    val editable: Boolean = false,
    val seedBg: Int = windowBg,
    val seedSurface: Int = surfaceBg,
    val seedText: Int = primaryText,
    val seedSecondary: Int = secondaryText,
    val seedAccent: Int = btnAccentBg
) {
    companion object {
        fun fromJson(json: JSONObject): ThemeConfig {
            val c = json.getJSONObject("colors")
            fun p(key: String) = Color.parseColor(c.getString(key))
            return ThemeConfig(
                id                    = json.getString("id"),
                name                  = json.getString("name"),
                windowBg              = p("window_bg"),
                surfaceBg             = p("surface_bg"),
                primaryText           = p("primary_text"),
                secondaryText         = p("secondary_text"),
                pillLabelText         = p("pill_label_text"),
                sectionLabel          = p("section_label"),
                divider               = p("divider"),
                pillBg                = p("pill_bg"),
                pillDivider           = p("pill_divider"),
                circleBtnBg           = p("circle_btn_bg"),
                circleBtnIconTint     = p("circle_btn_icon_tint"),
                dpadRingBg            = p("dpad_ring_bg"),
                switchTrackOn         = p("switch_track_on"),
                switchTrackOff        = p("switch_track_off"),
                switchThumbOn         = p("switch_thumb_on"),
                switchThumbOff        = p("switch_thumb_off"),
                btnAccentBg           = p("btn_accent_bg"),
                btnAccentText         = p("btn_accent_text"),
                btnGhostBorder        = p("btn_ghost_border"),
                btnGhostBorderPressed = p("btn_ghost_border_pressed"),
                btnGhostBgPressed     = p("btn_ghost_bg_pressed"),
                dpadOkBg              = p("dpad_ok_bg"),
                statusBarLightIcons   = json.getBoolean("status_bar_light_icons")
            )
        }

        /**
         * Build a full theme from a handful of seed colors. This is the engine
         * behind the custom-theme editor: the user chooses background, surface,
         * text, secondary text and accent, and everything else is computed to
         * stay visually consistent.
         */
        fun derived(
            id: String,
            name: String,
            light: Boolean,
            bg: Int,
            surface: Int,
            text: Int,
            secondary: Int,
            accent: Int
        ): ThemeConfig {
            val m = ColorUtil::mix
            return ThemeConfig(
                id                    = id,
                name                  = name,
                windowBg              = bg,
                surfaceBg             = surface,
                primaryText           = text,
                secondaryText         = secondary,
                pillLabelText         = ColorUtil.withAlpha(text, 0xCC),
                sectionLabel          = secondary,
                divider               = m(surface, text, 0.10f),
                pillBg                = surface,
                pillDivider           = bg,
                circleBtnBg           = m(surface, text, 0.06f),
                circleBtnIconTint     = text,
                dpadRingBg            = m(surface, text, 0.06f),
                switchTrackOn         = accent,
                switchTrackOff        = m(surface, text, 0.22f),
                switchThumbOn         = if (light) 0xFFFFFFFF.toInt() else text,
                switchThumbOff        = if (light) 0xFFFFFFFF.toInt() else m(surface, text, 0.35f),
                btnAccentBg           = accent,
                btnAccentText         = ColorUtil.contrastText(accent),
                btnGhostBorder        = m(surface, text, 0.16f),
                btnGhostBorderPressed = m(surface, text, 0.30f),
                btnGhostBgPressed     = m(surface, text, 0.06f),
                dpadOkBg              = m(bg, surface, 0.5f),
                statusBarLightIcons   = light,
                editable              = true,
                seedBg                = bg,
                seedSurface           = surface,
                seedText              = text,
                seedSecondary         = secondary,
                seedAccent            = accent
            )
        }

        /** Load a user-created theme stored as seed colors. */
        fun fromSeedJson(json: JSONObject): ThemeConfig {
            val s = json.getJSONObject("seed")
            fun p(key: String) = Color.parseColor(s.getString(key))
            return derived(
                id        = json.getString("id"),
                name      = json.getString("name"),
                light     = json.optBoolean("light", false),
                bg        = p("background"),
                surface   = p("surface"),
                text      = p("text"),
                secondary = p("secondary"),
                accent    = p("accent")
            )
        }
    }

    /** Serialize a custom theme as its seed colors (re-derived on load). */
    fun toSeedJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("custom", true)
        put("light", statusBarLightIcons)
        put("seed", JSONObject().apply {
            put("background", ColorUtil.toHexArgb(seedBg))
            put("surface",    ColorUtil.toHexArgb(seedSurface))
            put("text",       ColorUtil.toHexArgb(seedText))
            put("secondary",  ColorUtil.toHexArgb(seedSecondary))
            put("accent",     ColorUtil.toHexArgb(seedAccent))
        })
    }
}
