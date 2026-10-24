import json, os, sys, threading, time


class AbortRun(Exception):
    """The run must stop now; the teardown still parks the phone and turns the TV off."""


def now_ms():
    return time.time() * 1000.0


_log_lock = threading.Lock()
_log_file = None


def set_log_file(path):
    global _log_file
    _log_file = open(path, "a", encoding="utf-8")


def log(msg):
    line = time.strftime("%H:%M:%S") + " " + msg
    with _log_lock:
        print(line, file=sys.stderr, flush=True)
        if _log_file:
            _log_file.write(line + "\n")
            _log_file.flush()


class Jsonl:
    """Append-only JSON lines file, flushed per line so a crashed run still leaves readable data."""

    def __init__(self, path):
        self.path = path
        self.f = open(path, "a", encoding="utf-8")
        self.lock = threading.Lock()

    def write(self, obj):
        line = json.dumps(obj, separators=(",", ":"))
        with self.lock:
            self.f.write(line + "\n")
            self.f.flush()

    def close(self):
        with self.lock:
            self.f.close()


def read_jsonl(path):
    out = []
    if not os.path.exists(path):
        return out
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except ValueError:
                pass
    return out


def load_matrix(path):
    text = open(path, encoding="utf-8").read()
    if path.lower().endswith(".json"):
        return json.loads(text)
    import yaml
    return yaml.safe_load(text)


def pid_alive(pid):
    """os.kill(pid, 0) would send CTRL_C_EVENT on Windows, so ask the process table instead."""
    if os.name != "nt":
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False
    import ctypes
    k = ctypes.windll.kernel32
    h = k.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return False
    code = ctypes.c_ulong()
    ok = k.GetExitCodeProcess(h, ctypes.byref(code))
    k.CloseHandle(h)
    return bool(ok) and code.value == 259  # STILL_ACTIVE
