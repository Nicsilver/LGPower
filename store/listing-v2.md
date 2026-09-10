# Play listing v2 (draft, 2026-09-10, revised after 13-agent review)

Current live listing: title "LGPower", 3 dark screenshots, feature list from July.

## Title (30 chars max)

Recommended:
`LG Power - webOS TV Remote`  (26)

Alternatives:
- `LG Power: webOS TV Remote`  (25)
- `LG TV Remote (webOS)`  (20)

Why: Play search is the only organic channel and nobody types "LGPower". People type "lg tv remote" or "webos remote". The main screen inside the app is already titled "LG TV Remote". "LG" appears once on purpose: Play's metadata policy flags repeated brand keywords, and a title that opens with a manufacturer name twice reads as impersonation.

## Short description (80 chars max)

Recommended:
`Fast remote for LG webOS TVs. Wi-Fi + IR power, touchpad, keyboard, app buttons`  (79)

Alternatives:
- `Open source remote for LG webOS TVs. No ads, no tracking, all on your own LAN`  (77)
- `Remote for LG webOS TVs. Wi-Fi control, IR power fallback, touchpad, keyboard`  (77)

"Free" and "no ads" were dropped from the recommended line: Play already labels the app free, and those are the words its metadata scanner looks for.

## Full description (4000 chars max)

LG Power turns your Android phone into a fast, clean remote for LG webOS TVs. No account, no ads, no tracking. Everything runs on your home network, so it stays quick and private.

Built by someone who kept losing the Magic Remote down the side of the couch.

WHAT IT DOES
- Power: turns the TV off over Wi-Fi and back on with Wake-on-LAN (the TV needs network standby enabled). If your phone has an IR blaster, a long press sends the IR power code instead, so power still works when the TV has dropped off the network
- Screen Off: switches the panel off while the TV keeps playing. Great for music and podcasts on an OLED
- D-pad with hold-to-repeat, plus OK, Home, Back and Menu. Long-press Back for Exit
- Volume and brightness: tap them as buttons or drag them like sliders, with haptic ticks. Your phone's volume keys work too
- Swap the brightness pill for channel up/down if you watch live TV
- Numpad with channel up/down, List and dash, plus Guide, Info, Exit and CC
- Colour buttons (red, green, yellow, blue) in a row that stays open until you tap outside it, so repeated presses are easy
- Touchpad: drag anywhere for the cursor, or hold to lock it full-screen with tap-to-click and two-finger scroll
- Keyboard: type straight into TV search fields with your phone keyboard
- Pickers for input source, picture mode and sound mode
- App shortcuts: up to four buttons for the apps installed on your TV
- Home screen widgets for Power, Screen Off, OK and app shortcuts
- Themes: Dark, Light, Nord, Dracula, Monokai, Catppuccin, Solarized Light and One Light, plus an editor to make your own

WORKS WITH THE NEWEST FIRMWARE
Pairing works on webOS 25 and 26, the software on the 2025 and 2026 TVs and on older sets that LG has updated to it.

FOR THE TINKERERS
An IR service remote under Settings > Advanced opens the factory menus (IN-START, EZ-ADJUST) from the phone's IR blaster. It is behind a warning for a reason: wrong values can ruin the picture and IN-STOP factory resets the TV on the spot.

REQUIREMENTS
- An LG webOS TV on the same local network as your phone (Wi-Fi or Ethernet)
- Android 8.0 or newer
- A phone with an IR blaster for the IR power fallback, the Power and OK home screen widgets, and the service remote. Everything else works without one
- On first launch, pick your TV from the list and accept the pairing prompt on the TV screen. Pairing is remembered after that

PRIVACY
LG Power collects no data and has no servers. Your TV's address, MAC and pairing key, your shortcuts and your theme are stored only on your phone. The app is open source (AGPL-3.0) at github.com/Nicsilver/LGPower

LG Power is an independent, unofficial app. It is not affiliated with, endorsed by or sponsored by LG Electronics. LG, webOS and Magic Remote are trademarks of LG Electronics. All other product names, logos and brands are property of their respective owners and are used for identification only.

## Screenshots (8, 1080x1920, same look as the video)

Pastel background, graphite phone, one phone per frame, caption with a red underline. All captured with the real shortcut icons and the dark keyboard. Composer: store/screenshots/_frame.py (imports tools/faketv/produce.py for the background and bezel), captions in _captions.json.

1. 01_remote: main remote, Dark. "The whole remote on one screen"
2. 02_keyboard: keyboard sheet, dark Gboard. "Type into TV search"
3. 03_touchpad: touchpad locked. "Hold Touchpad to lock a cursor pad"
4. 04_pickers: picture mode picker. "Picture, sound and input pickers"
5. 05_numpad: numpad. "Numpad, guide, info and subtitles"
6. 06_widgets: launcher with the Power, Screen Off and OK widgets. "Widgets for Power, Screen Off and OK"
7. 07_themes: main remote, Light. "Light, dark and six more themes"
8. 08_service: service remote, part number and password masked. "Service remote over IR"

Earlier sets are in old_v1 to old_v3 (gitignored).

## Release notes for the live 1.30.0 build (500 chars max)

Everything user-facing since the listing was last touched (1.25.0 to 1.30.0). Paste into the production release's notes on Play, or reuse for 1.31.0.

- Pairing works on webOS 25 and 26 TVs
- Numpad with guide, info, exit and CC
- Colour buttons stay open for repeated presses
- Brightness and picture mode fixed after the pairing change
- IR service remote under Settings > Advanced
- Connection dot follows the TV's real power state, power works from standby
- Main remote fits smaller phones, pairing screen stops after 30 seconds
- Release notes under Settings > About

## Follow-ups

Shipped in 1.31.0 (tag v1.31.0, 2026-09-10):
- Widget picker labels are model-neutral: "TV Power", "TV Screen Off", "TV OK".
- Service remote footer reads "IR ONLY · AIM THE PHONE AT THE TV"; no part number, no "replica".
- Home screen Power widget goes over Wi-Fi / Wake-on-LAN like the in-app button, IR only as a fallback.
- docs/privacy.html dated 10 September 2026, lists the MAC address, keyboard text handling and the network-state permission.

Still IR-only by design: the OK widget (OK is meaningless when the TV is off, and IR is instant).
