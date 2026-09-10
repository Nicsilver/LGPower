#!/bin/bash
# Records the app tour with scrcpy and writes section timestamps (relative to recording start)
# to marks.txt so the post-production script can place captions.
set -u
ADB=/c/Android/android-sdk/platform-tools/adb.exe; S=${ANDROID_SERIAL:-emulator-5574}
OUT=${OUT:-$(dirname "$0")/out}; mkdir -p "$OUT"; cd "$OUT"
now()   { python -c "import time;print(round(time.time()-$T0,3))"; }
tap()   { echo "$(now) tap $1 $2 0" >> taps.txt; $ADB -s $S shell input tap $1 $2; sleep ${3:-0.6}; }
swipe() { echo "$(now) swipe $1 $2 $3 $4 ${5:-300}" >> taps.txt; $ADB -s $S shell input swipe $1 $2 $3 $4 ${5:-300}; sleep ${6:-0.6}; }
key()   { $ADB -s $S shell input keyevent $1; sleep ${2:-0.6}; }
mark()  { echo "$(python -c "import time;print(round(time.time()-$T0,2))") $1" >> marks.txt; }

$ADB -s $S shell settings put system show_touches 1
$ADB -s $S shell am start -n com.nic.lgpower/.MainActivity >/dev/null; sleep 3
T0=0; $ADB -s $S shell input tap 539 1394; sleep 1.5; $ADB -s $S shell input tap 539 1394; sleep 1.0   # warm the pointer socket
rm -f tour_raw.mp4 marks.txt taps.txt
scrcpy -s $S --no-playback --record=tour_raw.mp4 --max-fps=30 --video-bit-rate=12M --time-limit=48 > scrcpy.log 2>&1 &
SP=$!
T0=$(python -c "import time;print(time.time())")
sleep 2.0

mark "D-pad, hold to repeat"
tap 737 1394 0.45; tap 539 1592 0.45; tap 337 1394 0.45; swipe 737 1394 737 1394 900 0.4; tap 539 1394 0.8

mark "Volume and brightness sliders"
swipe 139 1500 139 1150 700 0.3; swipe 139 1150 139 1420 700 0.3; swipe 940 1150 940 1450 700 0.6

mark "Touchpad, hold to lock"
swipe 539 632 539 632 1300 0.2; swipe 300 1000 800 1300 350 0.2; swipe 800 1300 400 1500 350 0.2; tap 648 2052 0.5; tap 900 252 0.7

mark "Type with your keyboard"
tap 802 632 1.2; $ADB -s $S shell input text "planet%searth"; sleep 0.8; tap 978 1425 1.0

mark "Numpad, guide, info and CC"
tap 539 2086 0.9; tap 540 951 0.35; tap 828 951 0.35; tap 540 2086 0.8

mark "Colour buttons that stay open"
tap 730 2086 0.7; tap 420 2085 0.3; tap 420 2085 0.3; tap 420 2085 0.3; tap 540 200 0.6

mark "Picture and sound modes"
tap 348 2086 1.1; tap 540 1500 1.0; tap 920 2086 1.1; tap 540 1911 1.0

mark "Input source"
tap 773 961 1.1; tap 540 1911 1.0

mark "App shortcuts"
tap 294 474 0.6; tap 828 474 0.9

mark "Eight themes and an editor"
tap 1001 142 1.2; swipe 540 2000 540 800 400 0.8; tap 141 1431 1.2; tap 540 1911 1.2; key BACK 1.6

mark "Screen off, audio keeps playing"
tap 156 2085 2.2

mark "end"
sleep 0.5
wait $SP
$ADB -s $S shell settings put system show_touches 0
cat marks.txt
