package com.nic.lgpower

/**
 * Newest first. `code` is the versionCode that shipped the release; everything before
 * 1.22.0 shipped as versionCode 1, which is fine because [since] only ever filters
 * against codes from 1.30.0 onwards (the first release that stored a marker).
 * Keep notes to one line each; the sheet shows them as single rows.
 */
object ReleaseNotes {

    data class Release(val code: Int, val name: String, val date: String, val notes: List<String>)

    fun since(lastSeenCode: Int) = all.filter { it.code > lastSeenCode }

    val all = listOf(
        Release(39, "1.35.0", "2026-09-12", listOf(
            "Several TVs: tap the name at the top to switch or add; Settings › TVs to rename or remove",
            "Media keys: rewind, play, pause and forward behind a new Media button under the d-pad",
            "Up to 8 app shortcuts in two rows",
            "OK and Live TV keys on the numpad page",
            "Channel buttons can replace the brightness slider (Settings › Controls)",
            "Guided tour of the remote after setup, replayable from Settings › About",
            "Theme editor: Light/Dark swaps the base palette, the preview is interactive",
            "Finds a TV connected to the phone's own hotspot",
            "Wake-on-LAN also sent directly to the TV's address, for TVs on another VLAN",
        )),
        Release(38, "1.34.0", "2026-09-11", listOf(
            "Settings gear and status dot no longer hide under the status bar on Android 15",
        )),
        Release(37, "1.33.0", "2026-09-11", listOf(
            "Keep screen on toggle in Settings, for a phone used as a dedicated remote",
        )),
        Release(36, "1.32.0", "2026-09-11", listOf(
            "Enter the TV's IP address by hand in setup, for TVs on another VLAN or wired through a dongle",
        )),
        Release(35, "1.31.2", "2026-09-11", listOf(
            "Touchpad lock grows out of the button, 0.6 s hold",
            "Touchpad overlay follows light themes",
        )),
        Release(34, "1.31.1", "2026-09-11", listOf(
            "Locked to portrait",
            "Picker rows light up when tapped",
            "No flash of the old theme when switching themes",
        )),
        Release(33, "1.31.0", "2026-09-10", listOf(
            "Power widget works over Wi-Fi and Wake-on-LAN, IR only as a fallback",
            "Widgets no longer say LG C4 in the widget picker",
        )),
        Release(32, "1.30.0", "2026-09-07", listOf(
            "Connection dot follows the TV's real power state, no more flicker",
            "Power button works when the TV is in standby",
            "TV shows as off the instant you turn it off",
            "Release notes in Settings › About",
        )),
        Release(31, "1.29.1", "2026-09-07", listOf(
            "Brightness and picture mode work again on webOS 25 and 26",
        )),
        Release(30, "1.29.0", "2026-09-01", listOf(
            "Numpad mode behind the 123 button",
        )),
        Release(29, "1.28.1", "2026-08-31", listOf(
            "Main remote fits 360dp screens",
            "Pairing screen gives up after 30 seconds instead of spinning forever",
        )),
        Release(28, "1.28.0", "2026-08-31", listOf(
            "Pairing fixed on webOS 25 and 26",
            "Netflix replaces Stremio as a default shortcut",
        )),
        Release(27, "1.27.0", "2026-08-31", listOf(
            "IR service remote for factory menus, under Settings › Advanced",
        )),
        Release(26, "1.26.0", "2026-08-31", listOf(
            "TV traffic always goes over Wi-Fi, even if the phone prefers mobile data",
            "Fixed a crash, discovery matching other devices, and the IP saving too early",
        )),
        Release(25, "1.25.0", "2026-08-31", listOf(
            "Color button row stays open for repeated presses",
        )),
        Release(24, "1.24.0", "2026-07-22", listOf(
            "Under the hood only: automatic Play Store publishing",
        )),
        Release(23, "1.23.0", "2026-07-21", listOf(
            "Targets Android 16",
        )),
        Release(22, "1.22.0", "2026-07-14", listOf(
            "First Google Play release",
            "Theme editor and more built-in themes",
        )),
        Release(1, "1.21.0", "2026-05-11", listOf(
            "First-time setup: find, pick and pair your TV",
        )),
        Release(1, "1.20.0", "2026-05-11", listOf(
            "One persistent connection, snappier commands",
            "Faster on/off detection",
            "Redesigned picker sheets",
        )),
        Release(1, "1.19.0", "2026-05-10", listOf(
            "Keyboard no longer pushes the remote around",
        )),
        Release(1, "1.18.0", "2026-05-10", listOf(
            "Press animations on the d-pad, pills and buttons",
            "Monochrome icon for themed icons on Android 13 and up",
            "Light theme fixes for mute and screen-off",
        )),
        Release(1, "1.17.0", "2026-05-08", listOf(
            "Themes: light, dark and Darcula",
        )),
        Release(1, "1.16.0", "2026-05-08", listOf(
            "Redesigned settings screen",
            "IR power moved to a long-press on Power",
            "Color buttons",
            "Long-press Back sends Exit",
        )),
        Release(1, "1.15.0", "2026-05-05", listOf(
            "Sound mode picker",
        )),
        Release(1, "1.14.0", "2026-05-04", listOf(
            "Wake on LAN: Power and shortcuts turn the TV on",
            "Home button above the d-pad",
            "Channel up/down as an alternative to brightness",
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
            "New keyboard sheet",
            "Screen-off and input icons match LG's",
        )),
        Release(1, "1.10.0", "2026-04-12", listOf(
            "Long-press Power turns the TV off over Wi-Fi",
            "More accurate connection dot",
            "Touchpad: tap to click, faster lock, smoother tracking",
        )),
        Release(1, "1.9.0", "2026-04-12", listOf(
            "Volume and brightness pills drag like sliders, with haptic ticks",
            "Mute and screen-off buttons show their state",
            "Input source picker",
            "Last volume and brightness restored on open",
        )),
        Release(1, "1.8.0", "2026-04-12", listOf(
            "Volume and brightness levels shown in the pills",
        )),
        Release(1, "1.7.0", "2026-04-10", listOf(
            "Connection dot refreshes while the app is open",
        )),
        Release(1, "1.6.0", "2026-04-10", listOf(
            "Connection status dot",
            "TV found automatically on startup",
        )),
        Release(1, "1.5.0", "2026-04-10", listOf(
            "Fixed the app switcher icon",
        )),
        Release(1, "1.4.0", "2026-04-10", listOf(
            "Phone volume keys control the TV",
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
            "IR power, OK and screen-off widgets",
            "Finds the TV on your network automatically",
        )),
    )
}
