#!/bin/bash
# usage: cap.sh <name>   -> raw/<name>.png ; cap.sh tap x y ; cap.sh key KEY ; cap.sh text "..."
ADB=/c/Android/android-sdk/platform-tools/adb.exe; S=emulator-5574
SC=/c/Users/Nic/AppData/Local/Temp/claude/C--Programming/25986979-6da0-4ce5-a573-fcd54c5ca78d/scratchpad
case "$1" in
  tap) $ADB -s $S shell input tap $2 $3; sleep ${4:-1};;
  swipe) $ADB -s $S shell input swipe $2 $3 $4 $5 ${6:-300}; sleep 1;;
  key) $ADB -s $S shell input keyevent $2; sleep 1;;
  text) $ADB -s $S shell input text "$2"; sleep 1;;
  *) MSYS_NO_PATHCONV=1 $ADB -s $S exec-out screencap -p > $SC/raw/$1.png; echo "saved $1";;
esac
