"""TV-side truth: the real C4 through the Blank listener, or faketv's timeline. Both are reduced to one event shape
so the runner (online decisions) and score.py (offline joins) read them the same way.

Normalized event: dict(kind, recv, wall, t, key, x, y, dx, dy, raw)
  kind: key | keyup | move | click | down | up | wheel | blur | focus | wake | start | other
  recv: PC epoch ms when the PC saw it (Blank: listener's pc_recv; faketv: its own timestamp)
  wall/t: TV Date.now() and performance.now() in ms (Blank only)
"""
import asyncio, json, os, re, tempfile, threading, time

from .util import log, now_ms, pid_alive

SKILLS_TV = r"C:\Users\Nic\.claude\skills\tv"
STATE_DIR = os.environ.get("TV_GUARD_DIR") or os.path.join(tempfile.gettempdir(), "tv-guard")

KEYMAP = {"LEFT": "ArrowLeft", "RIGHT": "ArrowRight", "UP": "ArrowUp", "DOWN": "ArrowDown", "ENTER": "Enter",
          "BACK": "Escape", "HOME": "Home"}


def normalize_blank(e):
    """One Blank listener line -> normalized event."""
    ty = e.get("type")
    out = dict(kind="other", recv=e.get("pc_recv", 0) * 1000.0, wall=e.get("wall"), t=e.get("t"), key=None, x=e.get("x"), y=e.get("y"),
               dx=None, dy=None, raw=ty)
    if ty == "keydown":
        out["key"] = e.get("key")
        out["kind"] = "wake" if e.get("keyCode") == 1537 else "key"
        out["repeat"] = bool(e.get("repeat"))
    elif ty == "keyup":
        out["kind"], out["key"] = "keyup", e.get("key")
    elif ty == "mousemove":
        out["kind"] = "move"
    elif ty in ("click", "wheel", "blur", "focus", "start"):
        out["kind"] = ty
    elif ty == "mousedown":
        out["kind"] = "down"
    elif ty == "mouseup":
        out["kind"] = "up"
    elif ty == "visibility":
        out["kind"] = "blur" if e.get("state") == "hidden" else "focus"
    return out


def parse_timeline_line(line):
    """faketv --timeline line -> normalized event, or None. Format: '<unix s> <kind> <detail...>'."""
    parts = line.strip().split()
    if len(parts) < 2:
        return None
    try:
        ts = float(parts[0])
    except ValueError:
        return None
    kind, rest = parts[1], parts[2:]
    ms = ts * 1000.0
    ev = dict(kind="other", recv=ms, wall=None, t=ms, key=None, x=None, y=None, dx=None, dy=None, raw=kind)
    if kind == "ptr" and rest:
        ty = rest[0]
        if ty == "button" and len(rest) > 1:
            ev["kind"], ev["key"] = "key", KEYMAP.get(rest[1], rest[1])
        elif ty == "move" and len(rest) > 2:
            ev["kind"] = "move"
            try:
                ev["dx"], ev["dy"] = float(rest[1]), float(rest[2])
            except ValueError:
                pass
        elif ty == "click":
            ev["kind"] = "click"
        elif ty in ("scroll", "wheel"):
            ev["kind"] = "wheel"
    elif kind in ("ptr-open", "ptr-close", "cmd", "cmd-drop"):
        ev["kind"] = kind
        ev["detail"] = " ".join(rest)
    else:
        return None
    return ev


class _Tail:
    def __init__(self, path, from_end=True):
        self.path = path
        self.pos = os.path.getsize(path) if (from_end and os.path.exists(path)) else 0
        self.buf = b""

    def read_new(self):
        if not os.path.exists(self.path):
            return []
        size = os.path.getsize(self.path)
        if size < self.pos:
            self.pos = 0
        if size == self.pos:
            return []
        with open(self.path, "rb") as f:
            f.seek(self.pos)
            data = f.read()
        self.pos += len(data)
        self.buf += data
        *lines, self.buf = self.buf.split(b"\n")
        return [l.decode("utf-8", "replace") for l in lines if l.strip()]


class TruthBase:
    kind = "?"

    def __init__(self):
        self.ev = []
        self.lock = threading.Lock()
        self._stop = False
        self.n_raw = 0

    def start(self):
        threading.Thread(target=self._loop, daemon=True).start()

    def stop(self):
        self._stop = True

    def _loop(self):
        while not self._stop:
            try:
                for line in self.tail.read_new():
                    self._ingest(line)
            except Exception as e:  # a half-written line must not kill the tailer
                log(f"truth tail error: {e!r}")
            time.sleep(0.05)

    def since(self, recv_ms):
        with self.lock:
            return [e for e in self.ev if e["recv"] >= recv_ms]

    def count(self):
        with self.lock:
            return len(self.ev)

    def last_recv(self):
        with self.lock:
            return self.ev[-1]["recv"] if self.ev else 0

    def keys_since(self, recv_ms, key=None):
        return [e for e in self.since(recv_ms) if e["kind"] == "key" and (key is None or e["key"] == key)]

    def blur(self):
        return False

    def alert(self):
        return False


class BlankTruth(TruthBase):
    kind = "real"

    def __init__(self, out_path, src=None):
        super().__init__()
        self.src = src or os.path.join(STATE_DIR, "blank-events.jsonl")
        self.tail = _Tail(self.src)
        self.out = open(out_path, "a", encoding="utf-8")
        self._blur = False
        self._min_off = None

    def _ingest(self, line):
        try:
            raw = json.loads(line)
        except ValueError:
            return
        self.out.write(line + "\n")
        self.out.flush()
        e = normalize_blank(raw)
        with self.lock:
            self.ev.append(e)
            if e["wall"] is not None and raw.get("pc_recv"):
                off = raw["pc_recv"] * 1000.0 - e["wall"]
                if self._min_off is None or off < self._min_off:
                    self._min_off = off
            if e["kind"] == "blur":
                self._blur = True
            elif e["kind"] == "focus":
                self._blur = False

    def blur(self):
        return self._blur

    def offset_ms(self):
        return self._min_off

    def alert(self):
        return os.path.exists(os.path.join(STATE_DIR, "ALERT"))

    def guard_alive(self):
        try:
            pid = int(open(os.path.join(STATE_DIR, "guard.pid")).read().strip())
        except (OSError, ValueError):
            return False
        return pid_alive(pid)


class FakeTruth(TruthBase):
    kind = "fake"

    def __init__(self, timeline_path):
        super().__init__()
        self.path = timeline_path
        open(timeline_path, "a").close()
        self.tail = _Tail(timeline_path, from_end=False)

    def _ingest(self, line):
        e = parse_timeline_line(line)
        if e:
            with self.lock:
                self.ev.append(e)

    def offset_ms(self):
        return 0.0


# ------------------------------------------------------------------ helpers that drive the real TV

def tv_py(*args, timeout=120):
    import subprocess, sys
    return subprocess.run([sys.executable, os.path.join(SKILLS_TV, "tv.py"), *args], capture_output=True, text=True, timeout=timeout)


class NullMouse:
    def prime(self, wait=0.0):
        pass

    def screen_off(self):
        return False

    def center(self):
        pass

    def corner(self):
        pass

    def close(self):
        pass


class Mouse:
    """Persistent helper pointer socket on the PC. It wakes the dark panel before a trial (the first key after dark
    is consumed by the wake) and re-centres the TV cursor between drags so a drag is never clamped at an edge."""

    def __init__(self):
        import sys
        sys.path.insert(0, SKILLS_TV)
        import tv as TVM
        import websockets
        self.TVM, self.ws = TVM, websockets
        self.loop = asyncio.new_event_loop()
        threading.Thread(target=self.loop.run_forever, daemon=True).start()
        self.run(self._open())

    def run(self, co, t=40):
        return asyncio.run_coroutine_threadsafe(co, self.loop).result(t)

    async def _open(self):
        self.sess = self.TVM.Session(self.TVM.HELPER_KEY, self.TVM.HELPER_PERMS, "PIN")
        await self.sess.__aenter__()
        r = await self.sess.call("ssap://com.webos.service.networkinput/getPointerInputSocket")
        self.p = await self.ws.connect(r["payload"]["socketPath"], ssl=self.TVM.ctx(), open_timeout=8)

    async def _move(self, dx, dy, steps):
        for _ in range(steps):
            await self.p.send(f"type:move\ndx:{dx}\ndy:{dy}\ndown:0\n\n")
            await asyncio.sleep(0.012)

    def move(self, dx, dy, steps=1):
        self.run(self._move(dx, dy, steps))

    async def _key(self, name):
        await self.p.send(f"type:button\nname:{name}\n\n")

    def prime(self, wait=1.2):
        self.run(self._key("RIGHT"))
        time.sleep(wait)

    async def _screen_off(self):
        r = await self.sess.call(self.TVM.POWER + "turnOffScreen")
        return r.get("type") != "error"

    def screen_off(self):
        """Panel dark between trials (tv skill rule 3); the next trial's prime wakes it. False when the call failed."""
        try:
            return self.run(self._screen_off(), 15)
        except Exception as ex:
            log(f"screen-off over the helper socket failed ({ex!r:.80})")
            return False

    def corner(self):
        self.move(-30, -30, 120)

    def center(self):
        self.move(-30, -30, 120)   # clamp to the top-left corner
        self.move(20, 10, 17)      # about the middle of the 1920x1080 page; 20 units is the per-message cap

    def close(self):
        try:
            self.run(self.p.close(), 5)
            self.run(self.sess.__aexit__(None, None, None), 5)
        except Exception:
            pass


def open_mouse(tries=12):
    for i in range(tries):
        try:
            return Mouse()
        except Exception as ex:
            log(f"helper pointer socket failed ({ex!r:.80}), retry")
            time.sleep(3)
    return None
