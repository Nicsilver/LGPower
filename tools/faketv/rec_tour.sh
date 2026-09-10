#!/bin/bash
# Records the app tour with scrcpy and writes section timestamps (marks.txt) and every
# tap/swipe (taps.txt), both relative to T0, for produce.py.
set -u
ADB=/c/Android/android-sdk/platform-tools/adb.exe; S=${ANDROID_SERIAL:-emulator-5574}
OUT=${OUT:-$(dirname "$0")}; mkdir -p "$OUT"; cd "$OUT"
now()   { python -c "import time;print(round(time.time()-$T0,3))"; }
# A tap is a short press so the button's pressed state actually renders a few frames.
tap()   { echo "$(now) tap $1 $2 ${4:-130}" >> taps.txt; $ADB -s $S shell input swipe $1 $2 $1 $2 ${4:-130}; sleep ${3:-0.6}; }
swipe() { echo "$(now) swipe $1 $2 $3 $4 ${5:-300}" >> taps.txt; $ADB -s $S shell input swipe $1 $2 $3 $4 ${5:-300}; sleep ${6:-0.6}; }
key()   { $ADB -s $S shell input keyevent $1; sleep ${2:-0.6}; }
mark()  { echo "$(now) $1" >> marks.txt; }
# Gboard key centres on the 1080x2400 AVD (portrait, English layout)
kx() { case $1 in q)echo 60;; w)echo 165;; e)echo 273;; r)echo 381;; t)echo 486;; y)echo 594;; u)echo 702;; i)echo 810;; o)echo 918;; p)echo 1026;;
              a)echo 114;; s)echo 216;; d)echo 324;; f)echo 432;; g)echo 540;; h)echo 648;; j)echo 756;; k)echo 861;; l)echo 966;;
              z)echo 219;; x)echo 324;; c)echo 432;; v)echo 540;; b)echo 648;; n)echo 753;; m)echo 861;; _)echo 540;; esac; }
ky() { case $1 in q|w|e|r|t|y|u|i|o|p)echo 1713;; a|s|d|f|g|h|j|k|l)echo 1866;; z|x|c|v|b|n|m)echo 2022;; _)echo 2181;; esac; }
type_word() { for ((i=0;i<${#1};i++)); do c=${1:$i:1}; tap $(kx $c) $(ky $c) 0.01 60; done; }

$ADB -s $S shell settings put global animator_duration_scale 1.0
$ADB -s $S shell cmd uimode night yes >/dev/null      # dark Gboard
$ADB -s $S shell am start -n com.nic.lgpower/.MainActivity >/dev/null; sleep 3
T0=0; $ADB -s $S shell input tap 539 1394; sleep 1.5; $ADB -s $S shell input tap 539 1394; sleep 1.0   # warm the pointer socket
rm -f tour_raw.mp4 marks.txt taps.txt
scrcpy -s $S --no-playback --record=tour_raw.mp4 --max-fps=60 --video-bit-rate=16M --time-limit=42 > scrcpy.log 2>&1 &
SP=$!
T0=$(python -c "import time;print(time.time())")
sleep 2.0

mark "D-pad, hold to repeat"
tap 737 1394 0.35; tap 539 1592 0.35; tap 337 1394 0.35; swipe 737 1394 737 1394 800 0.3; tap 539 1394 0.6

mark "Volume and brightness sliders"
swipe 139 1560 139 1200 600 0.7; swipe 139 1200 139 1500 600 0.7; swipe 940 1200 940 1550 600 0.6

mark "Touchpad, hold to lock"
swipe 539 632 539 632 1250 0.15; swipe 300 1000 800 1300 380 0.1; swipe 800 1300 400 1500 380 0.15; tap 648 2052 0.4; tap 900 252 0.5

mark "Type with your keyboard"
tap 802 632 1.1; type_word "planet"; tap 540 2181 0.01 60; type_word "earth"; sleep 0.35; tap 978 1425 0.8

mark "Numpad, guide, info and CC"
tap 539 2086 0.7; tap 540 951 0.25; tap 828 951 0.25; tap 288 783 0.3; tap 540 2086 0.5

mark "Picture mode"
tap 348 2086 0.8; tap 540 1500 0.8

mark "Input source"
tap 773 961 0.8; tap 540 1911 0.8

mark "App shortcuts"
tap 294 474 0.5; tap 828 474 0.7

mark "Themes"
tap 1001 142 0.7; swipe 540 2000 540 800 350 0.5; tap 141 1431 0.7; tap 540 1365 0.7; key BACK 1.3

mark "montage"
sleep 0.3

mark "Screen off, audio keeps playing"
tap 156 2085 1.8

mark "end"
sleep 0.4
wait $SP
$ADB -s $S shell cmd uimode night no >/dev/null
cat marks.txt
