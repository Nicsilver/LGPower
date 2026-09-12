#!/bin/bash
# One main-screen still per built-in theme, for the montage in produce.py.
# Usage: ANDROID_SERIAL=emulator-5574 ./theme_stills.sh <out_dir>   (app prefs already seeded)
set -e
ADB=/c/Android/android-sdk/platform-tools/adb.exe; S=${ANDROID_SERIAL:?}; OUT=${1:?}
mkdir -p "$OUT"
i=1
for t in onelight solarized_light catppuccin dracula monokai nord dark light; do
  $ADB -s $S shell am force-stop com.nic.lgpower
  $ADB -s $S shell "run-as com.nic.lgpower sh -c 'grep -q theme_id shared_prefs/webos.xml && sed -i \"s#<string name=\\\"theme_id\\\">[a-z_]*</string>#<string name=\\\"theme_id\\\">$t</string>#\" shared_prefs/webos.xml || sed -i \"s#</map>#    <string name=\\\"theme_id\\\">$t</string>\n</map>#\" shared_prefs/webos.xml'"
  $ADB -s $S shell am start -n com.nic.lgpower/.MainActivity >/dev/null; sleep 4
  MSYS_NO_PATHCONV=1 $ADB -s $S exec-out screencap -p > "$OUT/$(printf %02d $i)_$t.png"; echo "still $t"
  i=$((i+1))
done
