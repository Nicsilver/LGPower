#!/bin/bash
# Raw 1080x2400 captures for the 1.35 store set, against faketv on the project emulator.
# Usage: ANDROID_SERIAL=emulator-5574 ./shots_v135.sh <out_dir>
set -e
ADB=${ADB:-/c/Android/android-sdk/platform-tools/adb.exe}
S=${ANDROID_SERIAL:?}
OUT=${1:?out dir}
mkdir -p "$OUT"
shot() { sleep ${2:-1}; MSYS_NO_PATHCONV=1 $ADB -s $S exec-out screencap -p > "$OUT/$1.png"; echo "shot $1"; }
tap() { $ADB -s $S shell input tap $1 $2; sleep ${3:-1}; }
key() { $ADB -s $S shell input keyevent $1; sleep 1; }

# Two saved TVs on the fake, four shortcuts, no what's-new, no tour
ID1=11111111-1111-1111-1111-111111111111
ID2=22222222-2222-2222-2222-222222222222
SC='[{"id":"youtube.leanback.v4","title":"YouTube","iconUrl":"http://10.0.2.2:3002/icon/youtube.leanback.v4.png"},{"id":"netflix","title":"Netflix","iconUrl":"http://10.0.2.2:3002/icon/netflix.png"},{"id":"amazon","title":"Prime Video","iconUrl":"http://10.0.2.2:3002/icon/amazon.png"},{"id":"com.disney.disneyplus-prod","title":"Disney+","iconUrl":"http://10.0.2.2:3002/icon/com.disney.disneyplus-prod.png"}]'
SC_ESC=$(printf '%s' "$SC" | sed 's/"/\&quot;/g')
TVS="[{\"id\":\"$ID1\",\"name\":\"Living room\",\"ip\":\"10.0.2.2\",\"mac\":\"AA:BB:CC:DD:EE:FF\",\"key\":\"faketv-key-123\",\"udn\":\"\"},{\"id\":\"$ID2\",\"name\":\"Bedroom\",\"ip\":\"10.0.2.2\",\"mac\":\"AA:BB:CC:DD:EE:FF\",\"key\":\"faketv-key-123\",\"udn\":\"\"}]"
TVS_ESC=$(printf '%s' "$TVS" | sed 's/"/\&quot;/g')
$ADB -s $S shell am force-stop com.nic.lgpower
$ADB -s $S shell "run-as com.nic.lgpower sh -c 'mkdir -p shared_prefs && cat > shared_prefs/webos.xml'" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="tv_ip">10.0.2.2</string>
    <string name="tv_mac">AA:BB:CC:DD:EE:FF</string>
    <string name="client_key">faketv-key-123</string>
    <int name="last_seen_version" value="39" />
    <string name="tvs">$TVS_ESC</string>
    <string name="active_tv">$ID1</string>
    <string name="app_shortcuts_$ID1">$SC_ESC</string>
    <string name="app_shortcuts_$ID2">$SC_ESC</string>
</map>
EOF

# Clean status bar
$ADB -s $S shell settings put global sysui_demo_allowed 1
$ADB -s $S shell am broadcast -a com.android.systemui.demo -e command enter >/dev/null
$ADB -s $S shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200 >/dev/null
$ADB -s $S shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false >/dev/null
$ADB -s $S shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true >/dev/null
$ADB -s $S shell am broadcast -a com.android.systemui.demo -e command network -e mobile hide >/dev/null
$ADB -s $S shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false >/dev/null

$ADB -s $S shell am start -n com.nic.lgpower/.MainActivity >/dev/null; sleep 6
shot main_dark 0
tap 540 142 1.5;            shot tvs 0;          key BACK
tap 540 1780 1;             shot media 0;        tap 980 560 0.6
tap 539 2086 1;             shot numpad 0;       key BACK
tap 802 632 2;              $ADB -s $S shell input text "planet%searth"; shot keyboard 1; key BACK; key BACK
tap 348 2086 1.5;           shot picture 0;      key BACK
# touchpad: hold on the button locks it open
$ADB -s $S shell input swipe 539 632 539 632 1400; shot touchpad 1.2; key BACK
# tour: first card
$ADB -s $S shell am force-stop com.nic.lgpower
$ADB -s $S shell "run-as com.nic.lgpower sh -c 'sed -i \"s#</map>#    <boolean name=\\\"tour_pending\\\" value=\\\"true\\\" />\\n</map>#\" shared_prefs/webos.xml'"
$ADB -s $S shell am start -n com.nic.lgpower/.MainActivity >/dev/null; sleep 5
tap 1000 1400 1; tap 1000 1400 1; tap 1000 1400 1.2; shot tour 0; key BACK
# settings
tap 1001 142 2;             shot settings 0;     key BACK
# light theme
$ADB -s $S shell am force-stop com.nic.lgpower
$ADB -s $S shell "run-as com.nic.lgpower sh -c 'sed -i \"s#</map>#    <string name=\\\"theme_id\\\">light</string>\\n</map>#\" shared_prefs/webos.xml'"
$ADB -s $S shell am start -n com.nic.lgpower/.MainActivity >/dev/null; sleep 6
shot main_light 0
$ADB -s $S shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
echo done
