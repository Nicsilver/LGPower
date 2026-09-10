#!/bin/bash
# Records the app tour with scrcpy and writes section timestamps (marks.txt) and every
# tap/swipe (taps.txt), both relative to T0, for produce.py.
set -u
ADB=/c/Android/android-sdk/platform-tools/adb.exe; S=${ANDROID_SERIAL:-emulator-5574}
OUT=${OUT:-$(dirname "$0")}; mkdir -p "$OUT"; cd "$OUT"
now()   { python -c "import time;print(round(time.time()-$T0,3))"; }
# A tap is a 130 ms press so the button's pressed state actually renders a few frames.
tap()   { echo "$(now) tap $1 $2 130" >> taps.txt; $ADB -s $S shell input swipe $1 $2 $1 $2 130; sleep ${3:-0.6}; }
swipe() { echo "$(now) swipe $1 $2 $3 $4 ${5:-300}" >> taps.txt; $ADB -s $S shell input swipe $1 $2 $3 $4 ${5:-300}; sleep ${6:-0.6}; }
key()   { $ADB -s $S shell input keyevent $1; sleep ${2:-0.6}; }
mark()  { echo "$(now) $1" >> marks.txt; }

$ADB -s $S shell settings put global animator_duration_scale 1.0
$ADB -s $S shell settings put global transition_animation_scale 1.0
$ADB -s $S shell settings put global window_animation_scale 1.0
$ADB -s $S shell am start -n com.nic.lgpower/.MainActivity >/dev/null; sleep 3
T0=0; $ADB -s $S shell input tap 539 1394; sleep 1.5; $ADB -s $S shell input tap 539 1394; sleep 1.0   # warm the pointer socket
rm -f tour_raw.mp4 marks.txt taps.txt
scrcpy -s $S --no-playback --record=tour_raw.mp4 --max-fps=60 --video-bit-rate=16M --time-limit=52 > scrcpy.log 2>&1 &
SP=$!
T0=$(python -c "import time;print(time.time())")
sleep 2.0

mark "D-pad, hold to repeat"
tap 737 1394 0.5; tap 539 1592 0.5; tap 337 1394 0.5; swipe 737 1394 737 1394 900 0.4; tap 539 1394 0.9

mark "Volume and brightness sliders"
swipe 139 1500 139 1150 700 0.3; swipe 139 1150 139 1420 700 0.3; swipe 940 1150 940 1450 700 0.7

mark "Touchpad, hold to lock"
swipe 539 632 539 632 1300 0.2; swipe 300 1000 800 1300 400 0.2; swipe 800 1300 400 1500 400 0.2; tap 648 2052 0.5; tap 900 252 0.7

mark "Type with your keyboard"
tap 802 632 1.2; $ADB -s $S shell input text "planet%searth"; sleep 0.8; tap 978 1425 1.0

mark "Numpad, guide, info and CC"
tap 539 2086 0.9; tap 540 951 0.4; tap 828 951 0.4; tap 288 783 0.4; tap 540 2086 0.8

mark "Picture mode"
tap 348 2086 1.1; tap 540 1500 1.1

mark "Input source"
tap 773 961 1.1; tap 540 1911 1.1

mark "App shortcuts"
tap 294 474 0.7; tap 828 474 1.0

mark "Themes: Light"
tap 1001 142 1.0; swipe 540 2000 540 800 400 0.7; tap 141 1431 1.0; tap 540 1365 1.0; key BACK 1.8

mark "Themes: Nord"
tap 1001 142 1.0; swipe 540 2000 540 800 400 0.7; tap 141 1431 1.0; tap 540 1911 1.0; key BACK 1.8

mark "Screen off, audio keeps playing"
tap 156 2085 2.4

mark "end"
sleep 0.5
wait $SP
cat marks.txt
