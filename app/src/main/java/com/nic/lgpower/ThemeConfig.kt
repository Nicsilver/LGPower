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
    val statusBarLightIcons: Boolean
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
    }
}
