#!/bin/bash
# Records the app tour with scrcpy and writes section timestamps (marks.txt) and every
# tap/swipe (taps.txt), both relative to T0, for produce.py.
# Layout coordinates are for 1.35 on the 1080x2400 pixel_6 AVD with four shortcuts
# (two rows) and two saved TVs; re-seed with shots_v135.sh first.
set -u
ADB=/c/Android/android-sdk/platform-tools/adb.exe; S=${ANDROID_SERIAL:-emulator-5574}
OUT=${OUT:-$(dirname "$0")}; mkdir -p "$OUT"; cd "$OUT"
now()   { python -c "import time;print(round(time.time()-$T0,3))"; }
# A tap is a short press so the button's pressed state actually renders a few frames.
tap()   { echo "$(now) tap $1 $2 ${4:-170}" >> taps.txt; $ADB -s $S shell input swipe $1 $2 $1 $2 ${4:-170}; sleep ${3:-0.6}; }
swipe() { echo "$(now) swipe $1 $2 $3 $4 ${5:-300}" >> taps.txt; $ADB -s $S shell input swipe $1 $2 $3 $4 ${5:-300}; sleep ${6:-0.6}; }
key()   { $ADB -s $S shell input keyevent $1; sleep ${2:-0.6}; }
# ddrag pill from to ms [wait]: app-side eased slider animation (debug build only); pill x is
# 139 (volume) or 940 (brightness); level -> raw y on the 1080x2400 AVD is 1720 - 5.92*level
ddrag() { x=139; [ "$1" = brightness ] && x=940; y1=$(( 1720 - 592 * $2 / 100 )); y2=$(( 1720 - 592 * $3 / 100 ))
          echo "$(now) drag $x $y1 $x $y2 $4 ease" >> taps.txt
          $ADB -s $S shell am broadcast -a com.nic.lgpower.DEMO_DRAG --es pill $1 --ei from $2 --ei to $3 --ei ms $4 >/dev/null; sleep ${5:-0.6}; }
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
T0=0; $ADB -s $S shell input tap 539 1440; sleep 1.5; $ADB -s $S shell input tap 539 1440; sleep 1.0   # warm the pointer socket
rm -f tour_raw.mp4 marks.txt taps.txt
scrcpy -s $S --no-playback --record=tour_raw.mp4 --max-fps=60 --video-bit-rate=16M --time-limit=56 > scrcpy.log 2>&1 &
SP=$!
T0=$(python -c "import time;print(time.time())")
sleep 2.0

mark "D-pad and OK"
tap 738 1440 0.35; tap 539 1641 0.35; tap 342 1440 0.35; tap 539 1239 0.35; tap 539 1440 0.6

mark "Volume and brightness, drag or tap"
ddrag volume 18 92 700 0.95; ddrag volume 92 40 700 0.95; ddrag brightness 70 30 700 0.9

mark "Hold Touchpad to lock a cursor pad"
swipe 539 723 539 723 1250 0.15; swipe 300 1000 800 1300 380 0.1; swipe 800 1300 400 1500 380 0.15; tap 648 2054 0.4; tap 902 254 0.5

mark "Type with your keyboard"
tap 801 723 1.1; type_word "planet"; tap 540 2181 0.01 60; type_word "earth"; sleep 0.35; tap 978 1425 0.8

mark "Media keys"
tap 539 1821 0.7; tap 417 2085 0.35; tap 657 2085 0.35; tap 897 2085 0.5; $ADB -s $S shell input tap 980 640; sleep 0.4

mark "Numpad, guide, info and Live TV"
tap 539 2085 0.7; tap 288 715 0.25; tap 792 715 0.3; tap 540 1380 0.4; tap 883 1819 0.4; tap 540 2083 0.5

mark "Picture mode"
tap 348 2085 0.8; tap 540 1496 0.9

mark "App shortcuts, up to eight"
tap 294 333 0.5; tap 783 489 0.7

mark "Switch between your TVs"
tap 540 150 0.8; tap 540 2048 1.6

mark "Themes"
tap 1001 150 1.0; tap 540 2253 0.8; tap 540 1364 0.25; key BACK 1.4

mark "montage"
sleep 0.3

mark "Screen off, audio keeps playing"
tap 156 2085 1.8

mark "end"
sleep 0.4
wait $SP
$ADB -s $S shell cmd uimode night no >/dev/null
cat marks.txt
