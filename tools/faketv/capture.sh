#!/bin/bash
# adb tap / swipe / key / text / screencap helper for driving the app on an emulator.
#
#   capture.sh <name>                     screenshot -> $OUT/<name>.png
#   capture.sh tap <x> <y> [wait_s]       tap, then wait (default 1 s)
#   capture.sh swipe <x1> <y1> <x2> <y2> [ms]   swipe (default 300 ms); same start and end = hold
#   capture.sh key <KEYEVENT>             e.g. BACK, HOME
#   capture.sh text "<string>"            types into the focused field (use %s for spaces)
#
# Env: ANDROID_SERIAL (emulator serial, e.g. emulator-5574), OUT (screenshot dir, default ./out),
#      ADB (path to adb; defaults to the Android SDK platform-tools on this machine).
ADB=${ADB:-/c/Android/android-sdk/platform-tools/adb.exe}
S=${ANDROID_SERIAL:?set ANDROID_SERIAL to the emulator serial from 'adb devices'}
OUT=${OUT:-$(dirname "$0")/out}
mkdir -p "$OUT"
case "$1" in
  tap) $ADB -s $S shell input tap $2 $3; sleep ${4:-1};;
  swipe) $ADB -s $S shell input swipe $2 $3 $4 $5 ${6:-300}; sleep 1;;
  key) $ADB -s $S shell input keyevent $2; sleep 1;;
  text) $ADB -s $S shell input text "$2"; sleep 1;;
  *) MSYS_NO_PATHCONV=1 $ADB -s $S exec-out screencap -p > "$OUT/$1.png"; echo "saved $OUT/$1.png";;
esac
