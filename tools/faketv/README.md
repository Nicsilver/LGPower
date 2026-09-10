# faketv: a fake LG webOS TV for the emulator

A stand-in for a real webOS TV so LG Power can be driven end to end on an Android
emulator with no TV on the network. Used for the Play Store screenshots and the app-tour
video, and handy for poking at UI without walking to the living room.

`faketv.py` speaks enough SSAP (the webOS websocket protocol) that the app pairs, shows
a green connection dot, reads volume, brightness, picture mode, sound mode, inputs and
the app list, and sees its own commands change that state. Every handler carries a
comment pointing at the exact `WebOsClient.kt` line that parses the reply, so if the app
changes, the fake can be kept honest.

## What it serves

| Port | What | Notes |
|---|---|---|
| 3001 | `wss://` SSAP command socket | self-signed cert, generated on first run (the app trusts any cert) |
| 3001 `/pointer` | pointer input socket | d-pad, OK, volume keys arrive here as `type:button` frames and are logged |
| 3002 | `http://` app icons | 128x128 PNGs with two-letter labels, generated on first run |

Handled URIs: register (any client key is accepted, no pairing prompt), `getPowerState`
subscription, `getVolume` subscription and `setVolume` / `volumeUp` / `volumeDown` /
`setMute`, `getSystemSettings` / `setSystemSettings` for picture and sound, the
`createAlert` / `closeAlert` luna trick the app uses to write settings, `getExternalInputList`
/ `switchInput`, `system.launcher/launch`, `insertText`, `listLaunchPoints` (12 apps),
`turnOffScreen` / `turnOnScreen` / `system/turnOff` (with the real Active to Active
Standby transition timing), and `connectionmanager/getinfo`. Anything else gets
`returnValue: true` and an `UNHANDLED URI` log line.

## Run it

```
cd tools/faketv
python faketv.py --host 10.0.2.2
```

Needs Python 3.11 with `websockets` and `Pillow`, and `openssl` on the PATH (Git for
Windows ships one). `--host` only controls the hostname baked into icon URLs; the emulator
reaches the host machine at `10.0.2.2`. `--port` and `--icon-port` exist if 3001 or 3002
are taken.

If it fails to bind, a previous copy is still running. Find the PID with
`netstat -ano | findstr :3001` and stop that PID. Never kill by image name.

`python selftest.py` spawns its own server, runs register, getVolume, listLaunchPoints,
the pointer socket and an icon fetch, then stops that child by PID. Run it after touching
`faketv.py`. If a stray server is already on 3001 the selftest talks to that one instead
and the icon fetch fails, which is the hint.

## Point the app at it

Skip discovery by seeding the app's `webos` preferences on a debug build. The values only
have to be non-empty; the fake accepts any client key.

```
adb -s emulator-5574 shell "run-as com.nic.lgpower sh -c 'cat > shared_prefs/webos.xml'" <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="tv_ip">10.0.2.2</string>
    <string name="tv_mac">AA:BB:CC:DD:EE:FF</string>
    <string name="client_key">faketv-key-123</string>
    <int name="last_seen_version" value="33" />
</map>
EOF
adb -s emulator-5574 shell am force-stop com.nic.lgpower
adb -s emulator-5574 shell am start -n com.nic.lgpower/.MainActivity
```

Set `last_seen_version` to the current versionCode so the what's-new sheet does not pop
up over the first screenshot. Force-stop before relaunching if the app was already
running, otherwise it keeps the dead socket from before the server started.

Wake-on-LAN cannot reach the host from the emulator, so the "TV off" path in the app
still works (the fake answers `launch` while in standby) but nothing is actually woken.

## Screenshots

`capture.sh` wraps the adb input and screencap calls. Set the serial and an output dir:

```
export ANDROID_SERIAL=emulator-5574 OUT=/tmp/shots
./capture.sh tap 802 632 2          # keyboard button, wait 2 s
./capture.sh text "planet%searth"
./capture.sh 04_keyboard            # -> /tmp/shots/04_keyboard.png
```

Coordinates are for the 1080x2400 pixel_6 AVD the emulator skill creates. If the AVD has
a `wm size` override, reset it first (`adb shell wm size reset; adb shell wm density reset`).

For a clean status bar (12:00, Wi-Fi, battery, nothing else) put SystemUI in demo mode:

```
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true
adb shell am broadcast -a com.android.systemui.demo -e command network -e mobile hide
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
```

`store/screenshots/_frame.py` turns the raw 1080x2400 captures into the 1080x1920 Play
frames, driven by `store/screenshots/_captions.json` (single phone, three-phone fan, or
three-phone row). `python _frame.py <raw_dir> <out_dir>`.

## Things that bit

- The touchpad locks when the Touchpad button is held still for 1 s. A swipe with the
  same start and end point and a 1400 ms duration does it. Moving cancels the lock and the
  overlay closes on release.
- The keyboard's send button and a picker selection close their own sheets. An extra
  BACK after either one quits the app.
- The numpad and colour row close with the same button that opened them, or a tap
  outside; give them 2 s.
- Record video with `scrcpy -s <serial> --no-playback --record=out.mp4 --time-limit=34`.
  `adb shell screenrecord` stops early when the app is backgrounded.
- After reinstalling the APK, existing home screen widgets keep their old click target
  until the launcher refreshes them. Re-add the widget rather than tapping the stale one.
- Trampoline activities that finish after async work must not use `Theme.NoDisplay`
  (crash on resume). `Theme.Translucent.NoTitleBar` is the one that works.

## App-tour video

`rec_tour.sh` drives a ~40 s scripted tour (d-pad, sliders, touchpad, keyboard, numpad,
colour row, pickers, shortcuts, theme switch, screen off) while scrcpy records, and logs
every tap and swipe with a timestamp. `produce.py` turns that into the 1080x1920 clip in
`store/video/`: title card, phone in a bezel on a dark glow, a caption per section, an
white pulse on every tap and a ring that travels along drags (injected taps are invisible
to Android's own "show touches", so they are drawn in post from the tap log), a theme
montage cut in at the "montage" mark from the stills in `store/video/themes/`, and an end
card. Taps are sent as 130 ms presses so the app's pressed state renders; typing taps the
Gboard keys so each letter gets a pulse; recording and output are 60 fps.

```
export ANDROID_SERIAL=emulator-5574 OUT=/tmp/tour
bash rec_tour.sh                       # writes tour_raw.mp4, marks.txt, taps.txt in $OUT
python produce.py /tmp/tour/tour_raw.mp4 /tmp/tour/marks.txt /tmp/tour/tour_produced.mp4 ../../store/video/themes
```

Before recording: theme on Dark, `last_seen_version` in the prefs equal to the current
versionCode (or the what's-new sheet sits over the first section), and a freshly started
faketv so the power state is Active. `LAG` in `produce.py` is the delay between the script's
clock and scrcpy actually capturing; 0.72 s measured here (sample the d-pad wedge
brightness in the raw video and compare with the hold's time in taps.txt), re-measure if
captions or pulses drift.

Slider drags in the video are not touch input at all: debug builds of the app listen for
`am broadcast -a com.nic.lgpower.DEMO_DRAG --es pill volume --ei from 18 --ei to 90 --ei ms 700`
(`pill` is `volume` or `brightness`) and animate the pill with a decelerate curve, sending
the real setVolume / brightness calls as they go. `adb input swipe` emits too few move
events to look smooth, and raw `sendevent` needs root and is too slow per event.
`qr_play.png` is the Play-listing QR used on the end card.
