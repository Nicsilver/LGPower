package com.nic.lgpower

/**
 * Newest first. `code` is the versionCode that shipped the release; everything before
 * 1.22.0 shipped as versionCode 1, which is fine because [since] only ever filters
 * against codes from 1.30.0 onwards (the first release that stored a marker).
 */
object ReleaseNotes {

    data class Release(val code: Int, val name: String, val date: String, val notes: List<String>)

    fun since(lastSeenCode: Int) = all.filter { it.code > lastSeenCode }

    val all = listOf(
        Release(32, "1.30.0", "2026-09-07", listOf(
            "Connection dot and power button now follow the TV's real power state. No more flickering, and the power button works when the TV is in standby",
            "The TV shows as off the moment you turn it off",
            "Volume and brightness sliders no longer flood the TV with writes",
            "This what's new popup, plus full release notes under Settings",
        )),
        Release(31, "1.29.1", "2026-09-07", listOf(
            "Brightness and picture mode work again on webOS 25 and 26",
        )),
        Release(30, "1.29.0", "2026-09-01", listOf(
            "Numpad mode behind the 123 button: digits, dash, list, guide, info, subtitles and channel keys",
        )),
        Release(29, "1.28.1", "2026-08-31", listOf(
            "Main remote fits on 360dp screens",
            "Pairing screen shows an error after 30 seconds instead of spinning forever",
        )),
        Release(28, "1.28.0", "2026-08-31", listOf(
            "Pairing fixed on webOS 25 and 26 TVs",
            "Netflix replaces Stremio as a default shortcut",
        )),
        Release(27, "1.27.0", "2026-08-31", listOf(
            "IR service remote for factory menus, under Settings > Advanced",
        )),
        Release(26, "1.26.0", "2026-08-31", listOf(
            "All TV traffic goes over Wi-Fi, even when the phone would rather use mobile data",
            "Fixed a crash, TV discovery matching other devices, and the IP being saved before pairing finished",
        )),
        Release(25, "1.25.0", "2026-08-31", listOf(
            "Color button row stays open for repeated presses. Tap outside it to close",
        )),
        Release(24, "1.24.0", "2026-07-22", listOf(
            "Under the hood only: release builds now publish to Google Play automatically",
        )),
        Release(23, "1.23.0", "2026-07-21", listOf(
            "Targets Android 16",
        )),
        Release(22, "1.22.0", "2026-07-14", listOf(
            "First Google Play release",
            "Theme editor, plus more built-in themes",
        )),
        Release(1, "1.21.0", "2026-05-11", listOf(
            "First-time setup: find your TV, pick it and pair in one flow",
        )),
        Release(1, "1.20.0", "2026-05-11", listOf(
            "One persistent connection to the TV, so commands feel snappier",
            "Faster on/off detection",
            "Redesigned picker sheets",
        )),
        Release(1, "1.19.0", "2026-05-10", listOf(
            "Keyboard no longer pushes the remote around when it opens",
        )),
        Release(1, "1.18.0", "2026-05-10", listOf(
            "Press animations on the d-pad, pills and buttons",
            "Monochrome icon for themed icons on Android 13 and up",
            "Light theme fixes for the mute and screen-off states",
        )),
        Release(1, "1.17.0", "2026-05-08", listOf(
            "Themes: light, dark and Darcula",
        )),
        Release(1, "1.16.0", "2026-05-08", listOf(
            "Redesigned settings screen",
            "IR power moved to a long-press on the power button",
            "Color buttons next to Sound",
            "Long-press Back sends Exit",
        )),
        Release(1, "1.15.0", "2026-05-05", listOf(
            "Sound mode picker",
        )),
        Release(1, "1.14.0", "2026-05-04", listOf(
            "Wake on LAN: power and app shortcuts turn the TV on when it is off",
            "Home button above the d-pad, with Mute and Input next to it",
            "Channel up/down as an alternative to the brightness pill",
            "Fixed a crash when no TV IP was set",
        )),
        Release(1, "1.13.0", "2026-04-13", listOf(
            "Picture mode picker with the current mode highlighted",
            "Back and Menu moved below the d-pad",
        )),
        Release(1, "1.12.0", "2026-04-13", listOf(
            "Two-finger scroll in locked touchpad mode",
        )),
        Release(1, "1.11.0", "2026-04-13", listOf(
            "New keyboard sheet replaces the stock dialog",
            "Screen-off and input icons match LG's own",
        )),
        Release(1, "1.10.0", "2026-04-12", listOf(
            "Long-press power turns the TV off over Wi-Fi",
            "More accurate connection dot",
            "Touchpad: tap to click, faster lock, smoother tracking",
        )),
        Release(1, "1.9.0", "2026-04-12", listOf(
            "Drag the volume and brightness pills like sliders, with haptic ticks",
            "Mute and screen-off buttons show their state",
            "Input source picker",
            "Last volume and brightness restored instantly on open",
        )),
        Release(1, "1.8.0", "2026-04-12", listOf(
            "Volume and brightness levels shown as fills in the pills",
        )),
        Release(1, "1.7.0", "2026-04-10", listOf(
            "Connection dot refreshes while the app is open",
        )),
        Release(1, "1.6.0", "2026-04-10", listOf(
            "Connection status dot",
            "TV found automatically on startup",
        )),
        Release(1, "1.5.0", "2026-04-10", listOf(
            "Fixed the icon in the app switcher",
        )),
        Release(1, "1.4.0", "2026-04-10", listOf(
            "Phone volume keys control the TV while the app is open",
            "New app icon",
        )),
        Release(1, "1.3.0", "2026-04-09", listOf(
            "Brightness up and down buttons",
        )),
        Release(1, "1.2.0", "2026-04-09", listOf(
            "Widget icons dimmed to match system icons",
        )),
        Release(1, "1.1.0", "2026-04-09", listOf(
            "Home screen widgets for app shortcuts",
        )),
        Release(1, "1.0.0", "2026-04-09", listOf(
            "First release: d-pad, volume, touchpad, keyboard and app shortcuts over Wi-Fi",
            "IR power, OK and screen-off home screen widgets",
            "Finds the TV on your network automatically",
        )),
    )
}
