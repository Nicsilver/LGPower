#!/bin/bash
TVIP=${TVIP:-127.0.0.1}
# helpers: source this. Needs ADB_SERIAL, the faketv log at $SPD/ftv.log (faketv.py ... > ftv.log) and control port 3029 (--ctl-port 3029).
export MSYS_NO_PATHCONV=1
SPD=${SPD:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}
ADB=/c/Android/android-sdk/platform-tools/adb.exe
S=${ADB_SERIAL:?set ADB_SERIAL to the emulator serial}
a() { $ADB -s $S "$@"; }
ash() { $ADB -s $S shell "$@"; }
LOG=$SPD/ftv.log
ctl() { curl -s "http://127.0.0.1:3029/$1" > /dev/null; }
status() { curl -s "http://127.0.0.1:3029/status"; echo; }
mark() { ctl "mark?t=$1"; }
# line number marker so we can read the log from here
loglines() { wc -l < $LOG; }
since() { tail -n +$(( $1 + 1 )) $LOG; }
# kotlin pkg
KPKG=com.nic.lgpower
FPKG=com.nic.lgpower.flutter
seed_k() { # $1 = client_key ("" for none)
  local key="$1"
  local keyline=""
  [ -n "$key" ] && keyline="<string name=\"client_key\">$key</string>"
  ash am force-stop $KPKG
  ash "run-as $KPKG sh -c 'cat > shared_prefs/webos.xml'" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="tvs">[{&quot;id&quot;:&quot;b0a70b21-8ca7-4248-9587-e7af695de027&quot;,&quot;name&quot;:&quot;LG TV&quot;,&quot;ip&quot;:&quot;$TVIP&quot;,&quot;mac&quot;:&quot;AA:BB:CC:DD:EE:FF, 20:3D:BD:DE:49:12&quot;,&quot;key&quot;:&quot;$key&quot;,&quot;udn&quot;:&quot;&quot;}]</string>
    <string name="active_tv">b0a70b21-8ca7-4248-9587-e7af695de027</string>
    <string name="tv_ip">$TVIP</string>
    <string name="tv_mac">AA:BB:CC:DD:EE:FF, 20:3D:BD:DE:49:12</string>
    $keyline
    $EXTRA
    <int name="last_seen_version" value="46" />
</map>
EOF
}
seed_f() {
  local key="$1"
  local keyline=""
  [ -n "$key" ] && keyline="<string name=\"flutter.client_key\">$key</string>"
  ash am force-stop $FPKG
  ash "run-as $FPKG sh -c 'cat > shared_prefs/FlutterSharedPreferences.xml'" <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="flutter.tvs">[{&quot;id&quot;:&quot;a7863bda-1f45-434a-b4d1-50610dbe491e&quot;,&quot;name&quot;:&quot;TV&quot;,&quot;ip&quot;:&quot;$TVIP&quot;,&quot;mac&quot;:&quot;AA:BB:CC:DD:EE:FF, 20:3D:BD:DE:49:12&quot;,&quot;key&quot;:&quot;$key&quot;,&quot;udn&quot;:&quot;&quot;}]</string>
    <string name="flutter.active_tv">a7863bda-1f45-434a-b4d1-50610dbe491e</string>
    <string name="flutter.tv_ip">$TVIP</string>
    <string name="flutter.tv_mac">AA:BB:CC:DD:EE:FF, 20:3D:BD:DE:49:12</string>
    $keyline
    $EXTRA
    <long name="flutter.last_seen_version" value="53" />
</map>
EOF
}
shot() {
  local o=$(cygpath -m $SPD/shots/$1.png)
  $ADB -s $S exec-out screencap -p | magick png:- -resize 35% "$o"
}
PY=/c/Python311/python.exe
an() { $PY -I $(cygpath -m $SPD/an.py) $(cygpath -m $LOG) "$@"; }
UPX=537; UPY=1210; OKY=1410; TPY=665; HOMEX=303; HOMEY=975; VUX=140; VUY=1295; VDY=1600
setapp() {
  APP=$1
  if [ $1 = K ]; then PKG=$KPKG; LAUNCH="am start -n $KPKG/.MainActivity"; else PKG=$FPKG; LAUNCH="monkey -p $FPKG -c android.intent.category.LAUNCHER 1"; fi
}
launch() { ash $LAUNCH > /dev/null 2>&1; }
fresh() { if [ $APP = K ]; then seed_k "${1-faketv-key-123}"; else seed_f "${1-faketv-key-123}"; fi; launch; }
other_stop() { if [ $APP = K ]; then ash am force-stop $FPKG; else ash am force-stop $KPKG; fi; }
dtap() { # N taps on d-pad UP in one on-device loop
  ash "i=0; while [ \$i -lt $1 ]; do input tap $UPX $UPY; i=\$((i+1)); done"
}
hold() { ash input swipe $1 $2 $1 $2 $3; }   # x y ms
s1() { # app, wait
  setapp $1; other_stop; ash am force-stop $PKG; sleep 1; mark $1-S1; local n=$(loglines)
  fresh; sleep ${2:-8}
  echo "--- 20 taps"; local a=$(loglines); dtap 20; sleep 1; an $a | sed -n 2,3p
  a=$(loglines); echo "--- hold 3s"; hold $UPX $UPY 3000; sleep 1; an $a | sed -n 2,4p
  a=$(loglines); echo "--- drag"; ash input swipe 537 $TPY 837 1000 800; sleep 1; an $a | sed -n 2,3p
  a=$(loglines); echo "--- click"; hold 537 $TPY 1400; sleep 0.5; ash input tap 540 1500; sleep 0.5; ash input tap 540 1500; sleep 0.5; ash input swipe 400 1300 700 1500 500; sleep 0.5; ash input keyevent 4; sleep 1; an $a | sed -n 2,3p
  a=$(loglines); echo "--- vol"; ash input tap $VUX $VUY; sleep 0.4; ash input tap $VUX $VDY; sleep 0.4;  ash input keyevent 24; sleep 0.4; ash input keyevent 25; sleep 1; an $a | sed -n 2,3p
  echo "--- total"; an $n | sed -n 1,6p; status
}
v() { an $1 - v | grep -v "<- type" | grep -v "move [-0-9]*,[-0-9]*" | cut -c1-110; }
vm() { an $1 - v | grep -v "<- type" | cut -c1-110 | awk '/move/ {c++; if (c==1) print; last=$0; next} {if (c>0) {print "   ... moves: " c " (last " substr(last,1,40) ")"; c=0} print} END {if (c>0) print "   ... moves: " c}'; }
ctot() { curl -s http://127.0.0.1:3029/status | grep -o '"cmd_total": [0-9]*' | grep -o '[0-9]*$'; }
ptot() { curl -s http://127.0.0.1:3029/status | grep -o '"ptr_total": [0-9]*' | grep -o '[0-9]*$'; }
launch_wait() { local b=$(ctot); launch; local i=0; while [ "$(ctot)" = "$b" ] && [ $i -lt 100 ]; do sleep 0.1; i=$((i+1)); done; }
ready() { # fresh launch, wait connected, prime one UP
  other_stop; ash am force-stop $PKG; sleep 1; fresh ${1-faketv-key-123}; sleep ${2:-6}; ash input tap $UPX $UPY; sleep 1.5
}
ready2() { other_stop; ash am force-stop $PKG; sleep 1; fresh; local b=$(ctot); local i=0; while [ "$(ctot)" = "$b" ] && [ $i -lt 200 ]; do sleep 0.1; i=$((i+1)); done; sleep 8; }
trig() { case $1 in
  P) ash "su 0 am start -n $KPKG/.PowerActivity" >/dev/null 2>&1;;
  S) ash "su 0 am start -n $KPKG/.ScreenOffActivity" >/dev/null 2>&1;;
  A) ash "su 0 am start -n $KPKG/.AppShortcutActivity --es app_id netflix --es app_title Netflix" >/dev/null 2>&1;;
esac; }
rq() { since $1 | grep -v "REGISTER\|open cmd\|getVolume\|getSystemSettings\|getExternalInputList\|MARK" | grep -v "type=subscribe id=s1 uri=ssap://com.webos.service.tvpower" | sed 's/payload=.*//; s/<- type=//' | cut -c1-100; }
