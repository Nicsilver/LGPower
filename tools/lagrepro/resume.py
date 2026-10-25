"""First key after the app comes back from the background.

resume.py <apk> <name> <a|b|c> <background s> [cycles] [--stale silent|abort|silent-ptr|silent-rst] [--freeze] [--pkg PKG]

--pkg com.nic.lgpower.flutter drives the Flutter port instead: its prefs are seeded as root (a profile
build is not debuggable, so run-as is out), touch-down comes from the `LagT 0 <ms>` print that
lagt-flutter.patch adds to lib/main.dart, and the resume-keep/stale verdicts (debug-only log lines there)
are left at 0 -- read the registers and ptr_opens counts instead. --ok X,Y overrides the tap position.

Per cycle: HOME, wait, bring the app back, tap OK 0.5 s later, then 11 s for the key (and any
duplicate) to land. --stale makes faketv drop or silence every socket open 1 s into the
background, so the old sessions are dead when the app returns. --freeze holds the app in the
cached-app freezer for the background time, as a phone does after ~10 s (the emulator didn't on its own). Prints one JSON line per run;
needs the lagt.patch log line in the build.
"""
import sys, os, time, json, subprocess, socket, argparse

HERE = os.path.dirname(os.path.abspath(__file__))
ADB = r"C:\Android\android-sdk\platform-tools\adb.exe"
PY = r"C:\Python311\python.exe"
FAKETV = os.path.join(HERE, "..", "faketv", "faketv.py")
ENV = dict(os.environ, MSYS_NO_PATHCONV="1")
OK = (539, 1400)
FLUTTER_PREFS = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <string name="flutter.tv_ip">10.0.2.2</string>
  <string name="flutter.tv_mac">AA:BB:CC:DD:EE:FF</string>
  <string name="flutter.client_key">faketv-key-123</string>
  <long name="flutter.last_seen_version" value="%d" />
  <boolean name="flutter.tour_pending" value="false" />
</map>
"""
PREFS = """<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="tv_ip">10.0.2.2</string>
    <string name="tv_mac">AA:BB:CC:DD:EE:FF</string>
    <string name="client_key">faketv-key-123</string>
    <int name="last_seen_version" value="%d" />
</map>
"""
COND = {
    "a": dict(proxy=None, faketv=[]),
    "b": dict(proxy=["--delay", "100"], faketv=["--reg-delay", "300"]),
    "c": dict(proxy=["--jitter", "50:300"], faketv=["--reg-delay", "300", "--hang-new-every", "5"]),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk"); ap.add_argument("name"); ap.add_argument("cond"); ap.add_argument("bg", type=float)
    ap.add_argument("cycles", type=int, nargs="?", default=5)
    ap.add_argument("--stale", choices=["silent", "abort", "silent-ptr", "silent-rst"])
    ap.add_argument("--freeze", action="store_true")
    ap.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL", ""))
    ap.add_argument("--out", required=True)
    ap.add_argument("--version-code", type=int, default=None)
    ap.add_argument("--pkg", default="com.nic.lgpower")
    ap.add_argument("--ok", default="")
    a = ap.parse_args()
    ser = a.serial
    flutter = a.pkg.endswith(".flutter")
    pkg = a.pkg
    if a.version_code is None:
        a.version_code = 53 if flutter else 46
    ok = tuple(int(v) for v in a.ok.split(",")) if a.ok else OK

    def sh(cmd, timeout=60):
        return subprocess.run([ADB, "-s", ser, "shell", cmd], capture_output=True, text=True,
                              timeout=timeout, env=ENV).stdout

    def offset(n=7):
        best = None
        for _ in range(n):
            t0 = time.time(); up = float(sh("cat /proc/uptime").split()[0]); t1 = time.time()
            if best is None or t1 - t0 < best[1]:
                best = ((t0 + t1) / 2 - up, t1 - t0)
        return best[0]

    def main_activity():
        out = sh(f"cmd package resolve-activity --brief -c android.intent.category.LAUNCHER {pkg}").strip().splitlines()
        return out[-1].strip()

    cfg = COND[a.cond]
    out = os.path.join(a.out, f"{a.name}_{a.cond}_{int(a.bg)}{'_' + a.stale if a.stale else ''}{'_frozen' if a.freeze else ''}")
    os.makedirs(out, exist_ok=True)
    tlf = os.path.join(out, "timeline.txt")
    stale_file = os.path.join(out, "stale.flag")
    for f in (tlf, stale_file):
        if os.path.exists(f):
            os.remove(f)
    inst = subprocess.run([ADB, "-s", ser, "install", "-r", "-d", a.apk], capture_output=True, text=True, env=ENV)
    if "Success" not in inst.stdout:
        sys.exit("install failed: " + inst.stdout + inst.stderr)
    prefs = os.path.join(out, "webos.xml")
    open(prefs, "w").write(PREFS % a.version_code)
    subprocess.run([ADB, "-s", ser, "push", prefs, "/data/local/tmp/webos.xml"], capture_output=True, env=ENV)
    sh(f"am force-stop {pkg}")
    if flutter:
        open(prefs, "w").write(FLUTTER_PREFS % a.version_code)
        subprocess.run([ADB, "-s", ser, "push", prefs, "/data/local/tmp/webos.xml"], capture_output=True, env=ENV)
        subprocess.run([ADB, "-s", ser, "root"], capture_output=True, env=ENV)
        time.sleep(2)
        subprocess.run([ADB, "-s", ser, "wait-for-device"], capture_output=True, env=ENV)
        d = f"/data/data/{pkg}/shared_prefs"
        sh(f"mkdir -p {d}; cp /data/local/tmp/webos.xml {d}/FlutterSharedPreferences.xml; "
           f"chown -R $(stat -c %u /data/data/{pkg}):$(stat -c %g /data/data/{pkg}) {d}; restorecon -R {d}")
    else:
        sh(f"run-as {pkg} sh -c 'mkdir -p shared_prefs; cp /data/local/tmp/webos.xml shared_prefs/webos.xml'")
    sh("input keyevent KEYCODE_HOME")
    flog = open(os.path.join(out, "faketv.log"), "w")
    port_args = ["--port", "3101"] if cfg["proxy"] else []
    tv = subprocess.Popen([PY, FAKETV, "--host", "10.0.2.2", "--timeline", tlf, "--stale-file", stale_file,
                           *port_args, *cfg["faketv"]], stdout=flog, stderr=subprocess.STDOUT)
    px = subprocess.Popen([PY, os.path.join(HERE, "proxy.py"), *cfg["proxy"]],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) if cfg["proxy"] else None
    try:
        time.sleep(3)
        # The default 256 KiB ring drops the LagT lines under the app's own test log on a long run
        sh("logcat -G 16M")
        sh("logcat -c")
        sh(f"am start -W -n {main_activity()}")
        time.sleep(8)
        sh(f"input tap {ok[0]} {ok[1]}")  # warm-up key, not scored
        time.sleep(4)
        offs = [offset()]
        for i in range(a.cycles):
            sh("input keyevent KEYCODE_HOME")
            t_home = time.time()
            if a.stale:
                time.sleep(1)
                open(stale_file, "w").write(a.stale)
            if a.freeze:
                time.sleep(1)
                sh("am freeze --sticky com.nic.lgpower")
            time.sleep(max(0, a.bg - (time.time() - t_home)))
            if a.freeze:
                sh("am unfreeze --sticky com.nic.lgpower")
            if a.stale == "silent-rst":
                open(stale_file, "w").write("rst-arm")
                time.sleep(0.4)
            sh(f"am start -W -n {main_activity()}")
            time.sleep(0.5)
            sh(f"input tap {ok[0]} {ok[1]}")
            time.sleep(11)
        offs.append(offset())
        if flutter:
            lagt = "\n".join(l[5:] for l in sh("logcat -d -v raw -s flutter:I").splitlines() if l.startswith("LagT "))
            conn = ""
        else:
            lagt = sh("logcat -d -v raw -s LagT:I")
            conn = sh("logcat -d -v raw -s LgpTest:I | grep -E 'resume-|\"life\"'")
        open(os.path.join(out, "lagt.txt"), "w").write(lagt)
        open(os.path.join(out, "conn.txt"), "w").write(conn)
    finally:
        if px:
            px.terminate()
        tv.terminate()
        try:
            tv.wait(10)
        except Exception:
            pass
        sh(f"am force-stop {pkg}")

    off = sum(offs) / len(offs)
    downs = [int(p[1]) / 1000.0 + off for p in (l.split() for l in lagt.splitlines()) if len(p) == 2 and p[0] == "0"]
    frames, regs, ptr_open, hangs = [], 0, 0, 0
    rst_at, presence_ms = None, []
    for line in open(tlf):
        p = line.split()
        if len(p) >= 2 and p[1] == "stale-rst" and rst_at is None:
            rst_at = float(p[0])
        if len(p) >= 4 and p[1] == "cmd" and p[2] == "subscribe" and p[3].endswith("getPowerState") and rst_at is not None:
            presence_ms.append(round((float(p[0]) - rst_at) * 1000)); rst_at = None
        if len(p) >= 4 and p[1] == "ptr" and p[2] == "button":
            frames.append((float(p[0]), p[3]))
        regs += len(p) >= 3 and p[1] == "cmd" and p[2] == "register"
        ptr_open += len(p) >= 2 and p[1] == "ptr-open"
        hangs += len(p) >= 2 and p[1] == "conn-hang"
    taps = downs[1:]
    lat, counts = [], []
    for i, d in enumerate(taps):
        end = taps[i + 1] if i + 1 < len(taps) else d + 11
        got = [t for t, name in frames if d - 0.05 <= t < end and name == "ENTER"]
        counts.append(len(got))
        lat.append(round((got[0] - d) * 1000) if got and got[0] - d < 10 else None)
    res = dict(name=a.name, cond=a.cond, bg=a.bg, stale=a.stale, frozen=a.freeze, taps=len(taps), first_key_ms=lat, keys_per_tap=counts,
               lost=sum(1 for x in lat if x is None), dup=sum(max(0, c - 1) for c in counts),
               registers=regs, ptr_opens=ptr_open, hangs=hangs,
               presence_after_rst_ms=presence_ms, keep=conn.count("resume-keep"), stale_seen=conn.count("resume-stale"))
    json.dump(res, open(os.path.join(out, "result.json"), "w"))
    print(json.dumps(res), flush=True)


if __name__ == "__main__":
    main()
