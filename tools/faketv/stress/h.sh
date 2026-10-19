source "$(dirname "${BASH_SOURCE[0]}")/t.sh"
now() { date +%s.%N; }
seedapp() { if [ $APP = K ]; then seed_k "${1-faketv-key-123}"; else seed_f "${1-faketv-key-123}"; fi; }
# cold start: seed, launch, wait for first cmd connection attempt
coldstart() { # key
  other_stop; ash am force-stop $PKG; sleep 0.5; seedapp "${1-faketv-key-123}"; local b=$(ctot); launch; local i=0
  while [ "$(ctot)" = "$b" ] && [ $i -lt 300 ]; do sleep 0.1; i=$((i+1)); done
  [ $APP = F ] && sleep ${FEXTRA:-1.5}
}
settle() { sleep ${1:-8}; }
# poll for a toast/snackbar text; prints elapsed seconds since $T0
toastwait() { # maxsec
  local end=$(( $(date +%s) + $1 ))
  while [ $(date +%s) -lt $end ]; do
    if ash "uiautomator dump /sdcard/u.xml >/dev/null 2>&1; cat /sdcard/u.xml" | grep -q "Timeout. Is the TV"; then
      echo "toast seen at +$($PY -c "print(round($(now)-$T0,1))")s"; return; fi
  done; echo "no toast within $1 s"
}
act_a() { ash input tap $UPX $UPY; }
act_b() { hold $UPX $UPY 1200; }
act_c() { ash input swipe 537 $TPY 837 1000 800; }
act_d() { hold 537 $TPY 1400; sleep 0.4; ash input tap 540 1500; sleep 0.4; ash input swipe 400 1300 700 1500 500; sleep 0.4; ash input tap 540 1500; sleep 0.4; ash input keyevent 4; }
act_e() { ash input swipe 537 $TPY 837 1000 2500; }
open_state() { curl -s http://127.0.0.1:3029/status | grep -o '"open_cmd": [0-9]*, "open_ptr": [0-9]*'; }
sumwin() { # from-line  -> compact summary
  an $1 | sed -n 1,5p
}
runcase() { # name act mode(cold|warm) delay wait
  local name=$1 act=$2 mode=$3 d=${4:-2.5} w=${5:-10}
  ctl "reg_delay?s=0"
  if [ $mode = cold ]; then ctl "reg_delay?s=$d"; ctl "mark?t=$APP-$name"; local n=$(loglines); coldstart
  else coldstart; sleep 7; ctl "mark?t=$APP-$name"; local n=$(loglines); ctl "drop?what=all"; ctl "reg_delay?s=$d"; sleep 0.3; fi
  T0=$(now); $act; sleep $w
  echo "== $APP $name $mode d=$d"; an $n | sed -n '1,2p;5p' | cut -c1-150 ; echo "   $(an $n | sed -n 3p)"; echo "   $(open_state)"
  ctl "reg_delay?s=0"
}
ready() { ctl "reg_delay?s=0"; coldstart; sleep ${1:-8}; ash input tap $UPX $UPY; sleep 1.5; }
brief() { # from-line label
  local o=$(an $1); echo "== $2"; echo "$o" | grep "^frames\|^attempts\|key/click" | cut -c1-140 | tr '\n' '|'; echo; echo "   $(open_state)"
}
s3() { local g=$1; ready; ctl "mark?t=$APP-S3-$g"; local n=$(loglines); ctl "drop?what=ptr"; sleep $g; T0=$(now); ash input tap $UPX $UPY; sleep 7; brief $n "$APP S3 gap=$g"; since $n | grep "ptr p[0-9]*\] #" | cut -c1-60 | tr '\n' ' '; echo; }
s4() { ready; ctl "mark?t=$APP-S4"; local n=$(loglines); ctl "drop?what=cmd"; sleep 0.3; ash input tap $UPX $UPY; sleep 8; ash input tap $UPX $UPY; sleep 4; brief $n "$APP S4"; echo "   subscribes: $(since $n | grep -c 'uri=ssap://com.webos.service.tvpower/power/getPowerState')"; since $n | grep "\[cmd c[0-9]*\] \(connected\|closed\)" | cut -c1-70 | tr '\n' ' '; echo; }
s6() { local mode=$1; ready; ctl "mark?t=$APP-S6-$mode"; local n=$(loglines); local t00=$(now); ctl "off?mode=$mode"; sleep 1.5; T0=$(now); ash input tap $UPX $UPY; toastwait 12
  local el=$($PY -c "print(int($(now)-$t00))"); sleep $((22-el)); ctl "on"; local m=$(loglines); echo "   off window attempts:"; an $n | grep "^attempts\|^frames" | cut -c1-120 | sed 's/^/     /'
  sleep 6; ash input tap $UPX $UPY; sleep 3; brief $m "$APP S6 $mode after-on"; shot s6-$APP-$mode 30; }
s7() { ready; ctl "mark?t=$APP-S7"; local n=$(loglines); ctl "standby?on=1"; ctl "drop?what=all"; local end=$(( $(date +%s) + 20 )); while [ $(date +%s) -lt $end ]; do hold $UPX $UPY 600; ash input swipe 537 $TPY 800 900 300; sleep 0.5; done; echo "   standby window:"; an $n | grep "^attempts\|^frames" | cut -c1-120 | sed 's/^/     /'; local c=$(since $n | grep -c "STANDBY"); echo "   STANDBY refusals=$c"; ctl "standby?on=0"; local m=$(loglines); sleep 8; ash input tap $UPX $UPY; sleep 3; brief $m "$APP S7 after-standby"; }
s8() { ready; ctl "mark?t=$APP-S8"; ctl "drop?what=ptr"; sleep 1; ash input tap $UPX $UPY; sleep 3; ctl "drop?what=cmd"; sleep 4; ctl "off?mode=refuse"; sleep 6; ctl on; sleep 6
  ctl "standby?on=1"; ctl "drop?what=all"; sleep 8; ctl "standby?on=0"; sleep 6
  for i in 1 2 3; do ash input keyevent 3; sleep 2.5; launch; sleep 5; done
  for i in 1 2; do ash input keyevent 26; sleep 2.5; ash input keyevent 26; sleep 1; ash input keyevent 82 >/dev/null; sleep 5; done
  sleep 20; echo "== $APP S8 after workload+20s idle: $(open_state)"
  ash input keyevent 3; sleep 45; echo "   after 45s in background: $(open_state)"
  launch; sleep 5; echo "   resumed: $(open_state)"
  ash am force-stop $PKG; sleep 3; echo "   after force-stop: $(open_state)"; }
skew() { local a=$(now); local d=$(ash date +%s.%N); local b=$(now); $PY -c "print(($a+$b)/2-$d)"; }
# toast time relative to T0 for K via logcat (after the window)
ktoast() { local sk=$(skew); ash "logcat -d -v epoch" | grep "u0 Toast}: Setting back" | head -1 | awk '{print $1}' | { read e; [ -n "$e" ] && $PY -c "print('K toast at +%.1fs' % ($e+$sk-$T0))" || echo "no K toast"; }; }
extra8() { ready; ctl "mark?t=$APP-X8"; local n=$(loglines); ctl "drop?what=all"; ctl "reg_delay?s=8"; sleep 0.5; ash logcat -c; T0=$(now); ash input tap $UPX $UPY
  if [ $APP = F ]; then toastwait 12; sleep 4; else sleep 12; ktoast; fi
  echo "   frames in 16 s after tap:"; an $n | grep "^frames\|^attempts" | cut -c1-120 | sed 's/^/     /'; ctl "reg_delay?s=0"; local m=$(loglines); sleep 3; ash input tap $UPX $UPY; sleep 3; brief $m "$APP X8 next tap (delay 0)"; }
flap() { ready; ctl "mark?t=$APP-flap"; local n=$(loglines); bash "$SPD/flapper.sh" 42 & local pid=$!; hold $UPX $UPY 17000; wait $pid; sleep 2; echo "== $APP flapping"; an $n | grep "^cmd\|^frames\|^attempts\|key/click" | cut -c1-160; local m=$(loglines); sleep 1; ash input tap $UPX $UPY; sleep 3; brief $m "$APP flap recovery tap"; }
s2prompt() { ctl "pair?mode=prompt"; ctl "reg_delay?s=0"; other_stop; ash am force-stop $PKG; sleep 0.5; seedapp stalekey; ctl "mark?t=$APP-S2prompt"; local n=$(loglines); launch; sleep 9
  for i in 1 2 3; do ash input tap $UPX $UPY; sleep 1.5; ash input keyevent 3; sleep 2; launch; sleep 3; ash input tap $UPX $UPY; sleep 1; ash input tap $UPX $UPY; sleep 2; done
  echo "== $APP stale key + prompt, before accept"; an $n | sed -n '1,2p;5p' | cut -c1-140; echo "   $(open_state) prompts-in-log: $(since $n | grep -c 'PAIRING PROMPT')"; since $n | grep "REGISTER\|PROMPT\|connected\|closed" | cut -c1-100 | sed 's/^/     /' | head -30
  local m=$(loglines); ctl accept; sleep 3; ash input tap $UPX $UPY; sleep 3; brief $m "$APP after /accept + 1 tap"; since $m | grep "REGISTER\|PROMPT\|KEY" | cut -c1-100 | sed 's/^/     /' | head; ctl "pair?mode=off"; }
s2deny() { ctl "pair?mode=deny"; other_stop; ash am force-stop $PKG; sleep 0.5; seedapp stalekey; ctl "mark?t=$APP-S2deny"; local n=$(loglines); launch; sleep 9; local end=$(( $(date +%s) + 75 )); while [ $(date +%s) -lt $end ]; do ash input tap $UPX $UPY; sleep 3; done
  echo "== $APP stale key + deny 75 s"; an $n | sed -n '1,2p;5p' | cut -c1-140; since $n | grep "PAIRING PROMPT" | cut -c1-14 | tr '\n' ' '; echo; echo "   $(open_state)"; ctl "pair?mode=off"; }
vol() { return; }
s3arc() { local drop=$1; ctl "vol?v=20"; ready 8; sleep 2; ctl "mark?t=$APP-ARC-$drop"; local n=$(loglines)
  if [ $APP = K ]; then y0=1631; y1=1391; else y0=1622; y1=1379; fi
  if [ $drop = ptr ]; then ( sleep 0.7; curl -s "http://127.0.0.1:3029/drop?what=ptr" >/dev/null ) & fi; if [ $drop = abort ]; then ( sleep 0.5; curl -s "http://127.0.0.1:3029/drop?what=ptr&abort=1" >/dev/null; sleep 0.5; curl -s "http://127.0.0.1:3029/drop?what=ptr&abort=1" >/dev/null ) & fi
  ash input swipe 140 $y0 140 $y1 1500; sleep 6; wait
  echo "== $APP ARC drag 20->60 drop=$drop: $(an $n | grep '^frames' | cut -c1-90) ; TV vol $(curl -s http://127.0.0.1:3029/status | grep -o '"volume": [0-9]*')"
  echo "   $(an $n | grep '^key/click' | cut -c1-120) ; ptr conns used: $(since $n | grep -c 'ptr p[0-9]*\] connected')"
  ash "uiautomator dump /sdcard/u.xml >/dev/null 2>&1; cat /sdcard/u.xml" | grep -o 'text="[0-9]\{1,3\}"\|content-desc="[0-9]\{1,3\}"' | head -3 | tr '\n' ' '; shot arc-$APP-$drop 30; }
IDK=b0a70b21-8ca7-4248-9587-e7af695de027; IDF=a7863bda-1f45-434a-b4d1-50610dbe491e
wakextra() { # kind id label
  local j="{&quot;kind&quot;:&quot;$1&quot;,&quot;id&quot;:&quot;$2&quot;,&quot;label&quot;:&quot;$3&quot;}"
  if [ $APP = K ]; then EXTRA="<string name=\"wake_action_$IDK\">$j</string>"; else EXTRA="<string name=\"flutter.wake_action_$IDF\">$j</string>"; fi; export EXTRA; }
pwr() { if [ $APP = K ]; then ash input tap 274 674; else ash input tap 277 651; fi; }
wakecycle() { # label
  launch; sleep 2; ctl "mark?t=$APP-wake-$1"; local n=$(loglines); pwr; sleep 9    # TV off
  local m=$(loglines); T0=$(now); pwr; local seen=""; local end=$(( $(date +%s) + 24 ))
  while [ $(date +%s) -lt $end ]; do
    if [ -z "$seen" ] && ash "uiautomator dump /sdcard/u.xml >/dev/null 2>&1; cat /sdcard/u.xml" | grep -q "NO ANSWER FROM TV"; then seen="hint at +$($PY -c "print(round($(now)-$T0,1))")s"; fi; done
  echo "-- $APP $1: ${seen:-no hint within 24 s}"
  since $m | grep -i "wol\]\|launcher/launch\|switchInput\|getSystemInfo\|turnOnScreen\|turnOff\|\[power\]" | sed 's/payload=\(.\{0,60\}\).*/payload=\1/; s/<- type=request id=[a-z0-9]* //' | cut -c1-130 | sed 's/^/     /'
  sleep 1; }
w4() { # name kind id label
  wakextra "$2" "$3" "$4"; TVIP=10.0.2.2; export TVIP; ready 8; wakecycle "$1-3s"; wakecycle "$1-15s"; }
restart_ftv() { taskkill /PID $(cat $SPD/ftv.pid) /F >/dev/null; sleep 1; (cd /c/Programming/LGPowerWidget/tools/faketv/stress && nohup /c/Python311/python.exe faketv.py --port ${FPORT:-3001} --icon-port 3022 --ctl-port 3029 "$@" >> $SPD/ftv.log 2>&1 &); sleep 3
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='python.exe'\" | Where-Object {\$_.CommandLine -like '*3029*'} | Select -Expand ProcessId" > $SPD/ftv.pid; }
w4r() { restart_ftv --wake-delays 3,15; w4 "$@"; }
fsetup() { ctl "pair?mode=prompt"; ash am force-stop $FPKG; ash pm clear $FPKG >/dev/null; launch; sleep 14; }
fmanual() { ash input tap 540 1106; sleep 2.5; ash input text 127.0.0.1; sleep 0.8; ash input tap 737 940; }
fprefs() { ash "run-as $FPKG cat shared_prefs/FlutterSharedPreferences.xml" | grep -o 'client_key">[^<]*\|tv_ip">[^<]*\|tvs">[^<]\{0,40\}' | tr '\n' ' '; echo; }
wifi() { ready 8; ctl "mark?t=$APP-wifi"; local n=$(loglines); echo "== $APP wifi drop"; ash svc wifi disable; sleep 2.5; echo " tap during outage"; ash input tap $UPX $UPY; sleep 2.5; ash svc wifi enable; echo " enabled $(date +%T)"; sleep 1; ash input tap $UPX $UPY; echo " tap +1s"; sleep 12; ash input tap $UPX $UPY; echo " tap +13s"; sleep 4; echo " frames: $(an $n | grep '^frames\|^attempts' | cut -c1-100 | tr '\n' ' ')"; since $n | grep "KEY\|connected\|closed\|STANDBY" | grep -v "  \[open" | cut -c1-90 | sed 's/^/     /' | head -20; echo "   $(open_state)"; }
