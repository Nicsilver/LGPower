import sys, os, time, json, subprocess, socket, statistics

SP = os.path.dirname(os.path.abspath(__file__))
ADB = r"C:\Android\android-sdk\platform-tools\adb.exe"
SER = "emulator-5558"
PORT = 5558
FAKETV = r"C:\Programming\LGPowerWidget-lagrepro\tools\faketv\faketv.py"
PY = r"C:\Python311\python.exe"
APK = {"v1391": r"C:\Users\Nic\AppData\Local\Temp\lagapk\v1391.apk",
       "master": r"C:\Users\Nic\AppData\Local\Temp\lagapk\master.apk"}
ENV = dict(os.environ, MSYS_NO_PATHCONV="1")


def adb(*a, timeout=60, check=False):
    r = subprocess.run([ADB, "-s", SER, *a], capture_output=True, text=True, timeout=timeout, env=ENV)
    return r.stdout


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def console(*cmds):
    tok = open(os.path.expanduser("~/.emulator_console_auth_token")).read().strip()
    s = socket.create_connection(("127.0.0.1", PORT)); time.sleep(.3); s.recv(4096)
    for c in ("auth " + tok, *cmds):
        s.sendall((c + "\r\n").encode()); time.sleep(.3); s.recv(4096)
    s.close()


def clock_offset(n=7):
    best = None
    for _ in range(n):
        t0 = time.time()
        out = sh("cat /proc/uptime")
        t1 = time.time()
        up = float(out.split()[0])
        cand = ((t0 + t1) / 2 - up, t1 - t0)
        if best is None or cand[1] < best[1]:
            best = cand
    return best[0]


def ensure_fg():
    if "com.nic.lgpower" not in sh("dumpsys window | grep mCurrentFocus"):
        sh("am start -W -n com.nic.lgpower/.MainActivity")
        time.sleep(3)
        return True
    return False


def tap(x, y):
    sh(f"input tap {x} {y}")


def run(build, cond):
    cfg = {
        "a": dict(proxy=None, faketv=[], load=0),
        "b": dict(proxy=["--delay", "100"], faketv=["--reg-delay", "300"], load=0),
        "e": dict(proxy=["--delay", "40"], faketv=["--reg-delay", "150"], load=0),
        "c": dict(proxy=["--jitter", "50:300"], faketv=["--reg-delay", "300", "--hang-new-every", "5"], load=0),
        "d": dict(proxy=["--jitter", "50:300"], faketv=["--reg-delay", "300", "--hang-new-every", "5"], load=2),
    }[cond]
    out = os.path.join(SP, "runs", f"{build}_{cond}")
    os.makedirs(out, exist_ok=True)
    tlf = os.path.join(out, "timeline.txt")
    if os.path.exists(tlf):
        os.remove(tlf)
    adb("install", "-r", "-d", APK[build])
    adb("push", os.path.join(SP, "prefs.xml"), "/data/local/tmp/prefs.xml")
    sh("run-as com.nic.lgpower sh -c 'mkdir -p shared_prefs; cp /data/local/tmp/prefs.xml shared_prefs/webos.xml'")
    sh("am force-stop com.nic.lgpower")
    sh("input keyevent KEYCODE_HOME")
    flog = open(os.path.join(out, "faketv.log"), "w")
    port_args = ["--port", "3101"] if cfg["proxy"] else []
    proc = subprocess.Popen([PY, FAKETV, "--host", "10.0.2.2", "--timeline", tlf, *port_args, *cfg["faketv"]],
                            stdout=flog, stderr=subprocess.STDOUT)
    pproc = subprocess.Popen([PY, os.path.join(SP, "proxy.py"), *cfg["proxy"]],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL) if cfg["proxy"] else None
    loads = []
    try:
        time.sleep(3)
        sh("logcat -c")
        sh("am start -W -n com.nic.lgpower/.MainActivity")
        time.sleep(8)  # app warm-up, same for both builds
        if cfg["load"]:
            sh("echo 'while :; do :; done' > /data/local/tmp/busy.sh")
            for _ in range(cfg["load"]):
                loads.append(subprocess.Popen([ADB, "-s", SER, "shell", "sh /data/local/tmp/busy.sh"], env=ENV,
                                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
            time.sleep(2)
        ev = {"phases": [], "cfg": cfg}
        ev["offset"] = clock_offset()
        TP = (540, 644)

        # 1. short swipes on the Touchpad button (drag without lock)
        vecs = [(300, 0), (0, 300), (-300, 0), (0, -300)]
        for i in range(20):
            dx, dy = vecs[i % 4]
            dur = 300 if i < 10 else 900
            t0 = time.time()
            sh(f"input swipe {TP[0]} {TP[1]} {TP[0]+dx} {TP[1]+dy} {dur}")
            t1 = time.time()
            ev["phases"].append(dict(kind="swipe", i=i, t0=t0, t1=t1, dx=dx, dy=dy, dur=dur))
            time.sleep(2.5)

        # 2. long drags in locked mode: hold still to lock, then drag 5 s on the overlay, BACK to leave
        for i in range(3):
            sh(f"input swipe {TP[0]} {TP[1]} {TP[0]} {TP[1]} 1200")
            time.sleep(0.4)
            t0 = time.time()
            sh("input swipe 200 1200 800 1200 5000", timeout=30)
            t1 = time.time()
            ev["phases"].append(dict(kind="drag", i=i, t0=t0, t1=t1, dx=600, dy=0))
            time.sleep(0.5)
            sh("input keyevent KEYCODE_BACK")
            time.sleep(1.0)
            if ensure_fg():
                ev.setdefault("fg_restarts", []).append(["drag", i])
            time.sleep(1.5)

        # 3. d-pad taps
        keys = [(538, 1200), (538, 1600), (337, 1400), (740, 1400)]
        ev["offset2"] = clock_offset()
        tt0 = time.time()
        for i in range(20):
            x, y = keys[i % 4]
            t = time.time()
            tap(x, y)
            ev["phases"].append(dict(kind="tap", i=i, t0=t, t1=time.time()))
            time.sleep(0.6)

        # 4. held arrows
        time.sleep(1)
        for i in range(5):
            t0 = time.time()
            sh("input swipe 740 1400 740 1400 2000")
            t1 = time.time()
            ev["phases"].append(dict(kind="hold", i=i, t0=t0, t1=t1))
            time.sleep(1.5)

        # 5. background / foreground then first key
        ev["offset3"] = clock_offset()
        for i in range(3):
            sh("input keyevent KEYCODE_HOME")
            time.sleep(3)
            t0 = time.time()
            sh("am start -W -n com.nic.lgpower/.MainActivity")
            t_fg = time.time()
            time.sleep(0.5)
            tap(539, 1400)
            t1 = time.time()
            ev["phases"].append(dict(kind="resume", i=i, t0=t0, tfg=t_fg, t1=t1))
            time.sleep(6)
        ev["offset4"] = clock_offset()
        log = sh("logcat -d -v raw -s LagT:I")
        open(os.path.join(out, "lagt.txt"), "w").write(log)
        json.dump(ev, open(os.path.join(out, "events.json"), "w"))
    finally:
        for p in loads:
            p.terminate()
        if loads:
            sh("pkill -f 'busy[.]sh'")
        if pproc:
            pproc.terminate()
        proc.terminate()
        try:
            proc.wait(10)
        except Exception:
            pass
        sh("am force-stop com.nic.lgpower")


if __name__ == "__main__":
    run(sys.argv[1], sys.argv[2])
