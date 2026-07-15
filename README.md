<p align="center">
  <img src="store/feature_graphic_1024x500.png" width="640" alt="LG Power. Remote for LG webOS TVs.">
</p>

<p align="center">
  <a href="../../releases/latest"><img src="https://img.shields.io/github/v/release/Nicsilver/LGPower?label=release&color=e63946" alt="Latest release"></a>
</p>

LG Power is an Android remote for LG webOS TVs. It talks to the TV over Wi-Fi for everything except power-on, which uses the phone's IR blaster, so the power button works even when the TV is off and unreachable over the network.

<p align="center">
  <img src="screenshots/app.png" width="260" alt="The remote">
  &nbsp;
  <img src="screenshots/settings_apps.png" width="260" alt="Choosing app shortcuts">
</p>

## Features

- **Power** over IR. Works when the TV is off; no wake-on-LAN flakiness.
- **D-pad, OK, volume and mute**, all with hold-to-repeat.
- **Screen Off** turns off the panel without putting the TV in full standby.
- **Touchpad**: full-screen cursor mode with drag-to-move and tap-to-click.
- **Keyboard**: type directly to the TV, handy for search fields.
- **App shortcuts**: two to four configurable buttons, picked from the apps installed on your TV.
- **Home screen widgets** for Power, Screen Off and OK.
- **Themes**: eight built-ins (Dark, Light, Nord, Dracula, Catppuccin, Monokai, One Light, Solarized Light) plus a custom theme editor.

## Download

Grab the latest signed APK from the [Releases](../../releases/latest) page and install it. You may need to allow installing from unknown sources.

## Setup

1. Connect your phone to the same Wi-Fi network as the TV.
2. Open the app. It searches the network and lists the TVs it finds.
3. Tap your TV, then accept the pairing prompt on the TV screen.

Pairing is remembered, so later commands connect instantly. You can also set the TV's IP address by hand in settings (gear icon, top right).

## Requirements

- An LG webOS TV (developed against a C4) and an Android phone on the same network.
- A phone with an IR blaster for the power button. Everything else works without one.

## Build from source

```bash
./gradlew assembleDebug
```

Requires JDK 17 or newer.

## Disclaimer

LG Power is an independent hobby project. It is not affiliated with, endorsed by, or connected to LG Electronics. LG and webOS are trademarks of LG Electronics Inc.
