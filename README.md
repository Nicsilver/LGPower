# LG C4 Power Widget

1×1 home screen widget for OnePlus 12 that fires the LG NEC IR power code via the built-in IR blaster.

## Build

```bash
# From project root
./gradlew assembleRelease        # or assembleDebug for sideloading
```

APK lands at: `app/build/outputs/apk/debug/app-debug.apk`

## Sideload to OnePlus 12

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to the phone and open it (enable "Install unknown apps" for your file manager).

## Add the widget

Long-press home screen → Widgets → search "LG" → drag **LG C4 Power** to your screen.

## How it works

- `ConsumerIrManager.transmit(38000, pattern)` — 38kHz carrier, standard NEC timing
- IR code: `0x20DF10EF` (LG universal power toggle, works on C2/C3/C4)
- No background service, no wakelock — the broadcast receiver wakes for ~50ms on each tap
- `updatePeriodMillis=0` means zero battery drain at idle

## If the code doesn't work

LG occasionally uses `0x20DF08F7` as an alternate power code. Edit `LGPowerWidget.kt`:

```kotlin
private val LG_POWER_PATTERN: IntArray = buildNecPattern(0x20DF08F7)
```

You can also try codes from LIRC's LG database or capture your remote with an IR receiver app.
