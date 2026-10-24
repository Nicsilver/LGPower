"""Everything that talks to the Android device: adb wrapper, the persistent injector, log capture, app setup."""
import json, os, queue, re, statistics, subprocess, sys, threading, time
import xml.etree.ElementTree as ET

from .util import log, now_ms

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEX = os.path.join(HERE, "inj", "inj.dex")
ADB = os.environ.get("PHONELAB_ADB", "adb")
PADB = r"C:\Users\Nic\.claude\skills\adb\padb.py"
PHONE_ADB_PS1 = r"C:\Users\Nic\.claude\skills\adb\phone-adb.ps1"
ACT = "com.nic.lgpower"


class Paused(Exception):
    """padb waited out the owner's pause tile (exit 75) or gave up (76): the step did not run."""


class InjectorDead(Exception):
    pass


def find_phone_serial():
    out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout
    for l in out.splitlines():
        if "_adb-tls-connect" in l and l.endswith("device"):
            return l.split()[0]
    for l in out.splitlines():
        if re.match(r"^192\.168\.\S+\s+device$", l):
            return l.split()[0]
    raise RuntimeError("phone not in adb devices")


class Dev:
    def __init__(self, serial, phone):
        self.phone = phone
        if phone and serial.startswith("emulator-"):
            raise SystemExit("refusing: --target phone but the serial is an emulator")
        if not phone and not serial.startswith("emulator-"):
            raise SystemExit("refusing: an emulator target needs an emulator-NNNN serial, never a real phone")
        self.serial = serial

    def adb(self, *args, input=None, timeout=60, text=True):
        # the phone goes through padb so the owner's pause tile is honoured; an emulator gets plain adb
        base = [sys.executable, PADB, "-s", self.serial] if self.phone else [ADB, "-s", self.serial]
        r = subprocess.run([*base, *args], input=input, capture_output=True, timeout=timeout)
        if r.returncode in (75, 76):
            raise Paused(f"padb exit {r.returncode}")
        return r.stdout.decode("utf-8", "replace") if text else r.stdout

    def sh(self, cmd, **kw):
        return self.adb("shell", cmd, **kw)

    def top(self):
        return self.sh("dumpsys activity activities | grep -m1 topResumedActivity").strip()

    def version_code(self, pkg):
        m = re.search(r"versionCode=(\d+)", self.sh(f"dumpsys package {pkg} | grep -m1 versionCode"))
        return int(m.group(1)) if m else None

    def installed(self, pkg):
        return f"package:{pkg}" in self.sh(f"pm list packages {pkg}").split()

    def screen_size(self):
        out = self.sh("wm size").strip().splitlines()
        m = re.search(r"(\d+)x(\d+)", out[-1]) if out else None
        return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)

    def borrow(self):
        """Refresh the phone borrow (unlock, dim) so the note never goes stale mid-run."""
        r = subprocess.run(["pwsh", "-NoProfile", "-File", PHONE_ADB_PS1, "borrow"], capture_output=True, text=True, timeout=90)
        return r.returncode

    def park(self):
        r = subprocess.run(["pwsh", "-NoProfile", "-File", PHONE_ADB_PS1, "park"], capture_output=True, text=True, timeout=90)
        return r.returncode


# ------------------------------------------------------------------ injector

class Injector:
    kind = "inj"

    def __init__(self, dev):
        self.dev = dev
        self.p = None
        self.q = queue.Queue()
        self.off_ms = 0.0        # pc_ms = phone_uptime_ms + off_ms
        self.wall_off = 0.0      # phone wall ms minus phone uptime ms
        self.rtt_ms = None
        self.spread_ms = None

    def start(self):
        self.dev.adb("push", DEX, "/data/local/tmp/inj.dex", timeout=60)
        self.q = queue.Queue()
        self.p = subprocess.Popen(
            [ADB, "-s", self.dev.serial, "shell", "CLASSPATH=/data/local/tmp/inj.dex app_process /system/bin Inj"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        threading.Thread(target=self._reader, args=(self.p, self.q), daemon=True).start()
        first = self._get(20)
        if not first.startswith("READY"):
            raise InjectorDead("injector did not start: " + first[:200])
        return first

    @staticmethod
    def _reader(p, q):
        for line in p.stdout:
            q.put(line.rstrip("\r\n"))
        q.put(None)

    def _get(self, timeout):
        try:
            line = self.q.get(timeout=timeout)
        except queue.Empty:
            raise InjectorDead(f"no reply in {timeout} s")
        if line is None:
            raise InjectorDead("injector process ended")
        return line

    def cmd(self, line, timeout=20):
        pc0 = now_ms()
        try:
            self.p.stdin.write(line + "\n")
            self.p.stdin.flush()
        except OSError as e:
            raise InjectorDead(str(e))
        while True:
            r = self._get(timeout)
            if r[:1] == line[:1] or r.startswith("ERR"):  # skip stray shell noise
                break
        pc1 = now_ms()
        return r.strip(), pc0, pc1

    def sync(self, n=12):
        best = []
        for _ in range(n):
            r, pc0, pc1 = self.cmd("N")
            parts = r.split()
            up, wall = int(parts[1]), int(parts[2])
            best.append((pc1 - pc0, (pc0 + pc1) / 2 - up, wall - up))
        best.sort()
        top = best[:max(3, n * 2 // 3)]
        self.off_ms = statistics.median(o for _, o, _ in top)
        self.wall_off = statistics.median(w for _, _, w in top)
        self.rtt_ms = best[0][0]
        self.spread_ms = max(o for _, o, _ in top) - min(o for _, o, _ in top)
        return dict(phone_ms=round(self.off_ms, 2), rtt_ms=round(self.rtt_ms, 2), spread_ms=round(self.spread_ms, 2),
                    wall_off=round(self.wall_off))

    def close(self):
        try:
            self.p.stdin.write("Q\n")
            self.p.stdin.flush()
            self.p.wait(5)
        except Exception:
            try:
                self.p.kill()
            except Exception:
                pass


class FallbackInjector:
    """Same command set through `adb shell input`: slower (about 100 ms per call) and with phone-time stamps
    estimated from the PC clock, but it needs no hidden API. Fast sequences are not possible with it."""
    kind = "input"

    def __init__(self, dev):
        self.dev = dev
        self.off_ms = 0.0
        self.wall_off = 0.0
        self.rtt_ms = None
        self.spread_ms = None

    def start(self):
        return "READY input-fallback"

    def _up(self):
        t0 = now_ms()
        out = self.dev.sh("cat /proc/uptime")
        t1 = now_ms()
        return float(out.split()[0]) * 1000, t0, t1

    def sync(self, n=6):
        best = []
        for _ in range(n):
            up, t0, t1 = self._up()
            best.append((t1 - t0, (t0 + t1) / 2 - up))
        best.sort()
        self.off_ms = statistics.median(o for _, o in best[:max(2, n // 2)])
        self.rtt_ms = best[0][0]
        self.spread_ms = abs(best[min(len(best) - 1, 2)][1] - best[0][1])
        return dict(phone_ms=round(self.off_ms, 2), rtt_ms=round(self.rtt_ms, 2), spread_ms=round(self.spread_ms, 2), wall_off=0)

    def _stamp(self, pc0, pc1):
        return int((pc0 + pc1) / 2 - self.off_ms)

    def cmd(self, line, timeout=30):
        p = line.split()
        c = p[0]
        pc0 = now_ms()
        if c == "N":
            up, t0, t1 = self._up()
            return f"N {int(up)} 0", t0, t1
        if c == "T":
            self.dev.sh(f"input tap {p[1]} {p[2]}")
            pc1 = now_ms()
            d = self._stamp(pc0, pc1)
            return f"T {d} {d + 40}", pc0, pc1
        if c == "H":
            self.dev.sh(f"input swipe {p[1]} {p[2]} {p[1]} {p[2]} {p[3]}")
            pc1 = now_ms()
            return f"H {int(pc0 + 60 - self.off_ms)} {int(pc1 - 60 - self.off_ms)}", pc0, pc1
        if c == "G":
            self.dev.sh(f"input swipe {p[1]} {p[2]} {p[3]} {p[4]} {p[5]}")
            pc1 = now_ms()
            return f"G {int(pc0 + 60 - self.off_ms)} {int(pc1 - 60 - self.off_ms)} 0", pc0, pc1
        if c == "K":
            self.dev.sh(f"input keyevent {p[1]}")
            pc1 = now_ms()
            return f"K {self._stamp(pc0, pc1)}", pc0, pc1
        if c == "L":
            label = line.strip()[1:].strip().replace('"', "'")
            self.dev.sh("log -t LgpRig '{\"ev\":\"mark\",\"label\":\"" + label + "\"}'")
            return "L 0", pc0, now_ms()
        if c == "S":
            raise InjectorDead("fast sequences need the real injector")
        raise InjectorDead("unsupported in fallback: " + line)

    def close(self):
        pass


def parse_reply(reply):
    """'T 100 140' -> dict(op='T', nums=[100, 140]); G also carries per-move offsets in nums[3:]."""
    p = reply.split()
    return dict(op=p[0], nums=[int(x) for x in p[1:] if re.fullmatch(r"-?\d+", x)])


# ------------------------------------------------------------------ log capture

LC_RE = re.compile(r"^\s*(\d+)\.(\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(\S+?)\s*:\s?(.*)$")
SYS_RE = re.compile(r"Focus entering|Focus leaving|wm_on_resume_called|wm_on_paused_called|screen_toggled|wm_set_resumed_activity|input_focus")


class LogStream:
    """One `adb logcat` child streaming into a callback. Plain adb: a logcat read never needs the pause check."""

    def __init__(self, dev, args, on_line):
        self.dev, self.args, self.on_line = dev, args, on_line
        self.p = None

    def start(self):
        self.p = subprocess.Popen([ADB, "-s", self.dev.serial, "logcat", *self.args], stdout=subprocess.PIPE,
                                  stderr=subprocess.DEVNULL, text=True, encoding="utf-8", errors="replace")
        threading.Thread(target=self._rd, args=(self.p,), daemon=True).start()

    def _rd(self, p):
        for l in p.stdout:
            m = LC_RE.match(l)
            if m:
                self.on_line(int(m.group(1)) * 1000 + int(m.group(2).ljust(3, "0")[:3]), m.group(6), m.group(7), now_ms())

    def alive(self):
        return self.p is not None and self.p.poll() is None

    def stop(self):
        if self.p and self.p.poll() is None:
            self.p.terminate()


class AppLog:
    """LgpTest/LgpRig lines to app.jsonl, newest widgets reply per app kept for coordinate lookups."""

    def __init__(self, sink, sys_sink):
        self.sink, self.sys_sink = sink, sys_sink
        self.widgets = {}
        self.sys_recent = []
        self.n = 0

    def on_app(self, lc_ms, tag, msg, recv):
        try:
            d = json.loads(msg)
            if not isinstance(d, dict):
                raise ValueError
        except ValueError:
            d = {"ev": "raw", "msg": msg[:300]}
        d.setdefault("app", "rig")
        d["r"] = round(recv, 1)
        d["lc"] = lc_ms
        self.n += 1
        if d.get("ev") == "widgets":
            self.widgets[d.get("app")] = (recv, d)
        self.sink.write(d)

    def on_sys(self, lc_ms, tag, msg, recv):
        line = f"{tag}: {msg}"
        if SYS_RE.search(line):
            self.sys_sink.write({"lc": lc_ms, "r": round(recv, 1), "line": line[:300]})
            self.sys_recent.append((recv, line))
            del self.sys_recent[:-400]

    def focus_since(self, pkg, recv_ms):
        for r, line in self.sys_recent:
            if r >= recv_ms and "Focus entering" in line and pkg + "/" in line:
                return r
        return None


def start_logs(dev, applog):
    dev.adb("logcat", "-c")
    a = LogStream(dev, ["-v", "epoch", "-T", "1", "LgpTest:I", "LgpRig:I", "*:S"], applog.on_app)
    s = LogStream(dev, ["-b", "events", "-v", "epoch", "-T", "1"], applog.on_sys)
    a.start()
    s.start()
    return a, s


# ------------------------------------------------------------------ apps

# canonical TEST_WIDGETS names -> what the UI dump exposes when the app has no TEST_WIDGETS reply
UI_NAMES = {
    "k": dict(right="btn_right", up="btn_up", down="btn_down", left="btn_left", touchpad="btn_touchpad", keyboard="btn_keyboard", ok="btn_ok"),
    "f": dict(right="Right", up="Up", down="Down", left="Left", touchpad="Touchpad", keyboard="Keyboard", ok="OK"),
}


class App:
    def __init__(self, label, pkg, kind):
        self.label = label          # 'K' or 'F'
        self.kind = kind            # 'k' or 'f'
        self.pkg = pkg
        self.act = None
        self.version = None
        self.xy = {}
        self.screen = None
        self.coord_src = None


def resolve_activity(dev, app):
    out = dev.sh(f"cmd package resolve-activity --brief -c android.intent.category.LAUNCHER {app.pkg}")
    lines = [l.strip() for l in out.splitlines() if "/" in l]
    comp = lines[-1] if lines else f"{app.pkg}/{ACT}.MainActivity"
    app.act = comp.split("/", 1)[1]
    if app.act.startswith("."):
        app.act = app.pkg + app.act
    return comp


def seed_prefs(dev, app, tv_ip, tv_mac, client_key):
    """Point the app at the TV (or faketv) without a pairing flow; debug builds only (run-as)."""
    ver = app.version or 99
    dev.sh(f"am force-stop {app.pkg}")
    if app.kind == "k":
        xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
               f"  <string name=\"tv_ip\">{tv_ip}</string>\n  <string name=\"tv_mac\">{tv_mac}</string>\n"
               f"  <string name=\"client_key\">{client_key}</string>\n  <int name=\"last_seen_version\" value=\"{ver}\" />\n"
               "  <boolean name=\"tour_pending\" value=\"false\" />\n</map>\n")
        target = "webos.xml"
    else:
        xml = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n<map>\n"
               f"  <string name=\"flutter.tv_ip\">{tv_ip}</string>\n  <string name=\"flutter.tv_mac\">{tv_mac}</string>\n"
               f"  <string name=\"flutter.client_key\">{client_key}</string>\n  <long name=\"flutter.last_seen_version\" value=\"{ver}\" />\n"
               "  <boolean name=\"flutter.tour_pending\" value=\"false\" />\n</map>\n")
        target = "FlutterSharedPreferences.xml"
    out = dev.adb("shell", f"run-as {app.pkg} sh -c 'mkdir -p shared_prefs; cat > shared_prefs/{target}'", input=xml.encode())
    if "not debuggable" in out or "Unknown package" in out:
        raise SystemExit(f"cannot seed {app.pkg}: {out.strip()[:120]} (needs a debuggable build)")


def ui_coords(dev, app):
    dev.sh("uiautomator dump /sdcard/phonelab-ui.xml > /dev/null")
    x = dev.sh("cat /sdcard/phonelab-ui.xml")
    root = ET.fromstring(x[x.index("<?xml"):] if "<?xml" in x else x)
    found = {}
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if not m:
            continue
        b = tuple(map(int, m.groups()))
        for k in (n.get("resource-id"), n.get("content-desc"), n.get("text")):
            if k:
                found.setdefault(k.split("/")[-1], b)
    out = {}
    for canon, key in UI_NAMES[app.kind].items():
        b = found.get(key)
        if b:
            out[canon] = dict(x=(b[0] + b[2]) // 2, y=(b[1] + b[3]) // 2, l=b[0], t=b[1], r=b[2], b=b[3])
    return out, x


def broadcast_widgets(dev, app, applog, timeout=4.0):
    """TEST_WIDGETS: the ordered-broadcast result first, the widgets line in the log as the fallback."""
    t0 = now_ms()
    out = dev.sh(f"am broadcast -p {app.pkg} -a {ACT}.TEST_WIDGETS")
    m = re.search(r'data="(.*)"\s*$', out, re.M)
    if m:
        try:
            d = json.loads(m.group(1).replace('\\"', '"'))
            if d.get("ok"):
                return d
        except ValueError:
            pass
    end = time.time() + timeout
    while time.time() < end:
        got = applog.widgets.get(app.kind)
        if got and got[0] >= t0 and got[1].get("ok"):
            return got[1]
        time.sleep(0.1)
    return None


def get_coords(dev, app, applog):
    d = broadcast_widgets(dev, app, applog)
    if d and d.get("w"):
        app.xy = d["w"]
        app.screen = d.get("screen")
        app.coord_src = "TEST_WIDGETS"
        return app.xy
    app.xy, _ = ui_coords(dev, app)
    app.coord_src = "uiautomator"
    if not app.screen:
        app.screen = dev.screen_size()
    return app.xy
