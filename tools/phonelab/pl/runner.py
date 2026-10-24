"""Trial execution. The runner only records raw facts into run.jsonl (what was injected, when, which clock offsets were
current); every verdict is computed by score.py so a run can be re-scored after the scorer improves."""
import os, subprocess, sys, time, traceback

from .device import (InjectorDead, Paused, FallbackInjector, Injector, get_coords, parse_reply, ui_coords)
from .util import log, now_ms

EXPECT = {"right": "ArrowRight", "left": "ArrowLeft", "up": "ArrowUp", "down": "ArrowDown", "ok": "Enter"}


class Skip(Exception):
    """The trial cannot run in this setup; it is recorded as void with the reason."""


class AbortRun(Exception):
    pass


class Ctx:
    def __init__(self):
        self.cfg = {}
        self.n = 0
        self.last_borrow = time.time()
        self.paused_s = 0.0
        self.pin = None
        self.recovered = 0


# ------------------------------------------------------------------ housekeeping

def housekeep(ctx):
    t0 = time.time()
    while ctx.truth.alert() or os.path.exists(os.path.join(ctx.out, "PAUSE")):
        why = "ALERT" if ctx.truth.alert() else "PAUSE file"
        if time.time() - t0 < 6:
            log(f"paused by {why}; waiting (clear it with `tv.py close-popup --clear` / delete PAUSE)")
            ctx.run.write(dict(ev="pause", why=why, pc=round(now_ms(), 1)))
        if time.time() - t0 > ctx.cfg.get("alert_max_s", 1800):
            raise AbortRun(f"{why} not cleared after {ctx.cfg.get('alert_max_s', 1800)} s")
        time.sleep(3)
    if time.time() - t0 > 1:
        ctx.run.write(dict(ev="resume", after_s=round(time.time() - t0, 1), pc=round(now_ms(), 1)))
    if ctx.dev.phone and time.time() - ctx.last_borrow > ctx.cfg["borrow_every_s"]:
        rc = ctx.dev.borrow()
        ctx.last_borrow = time.time()
        ctx.run.write(dict(ev="borrow", rc=rc, pc=round(now_ms(), 1)))
    try:
        ctx.inj.cmd("N")
    except InjectorDead as e:
        log(f"injector died ({e}); restarting")
        ctx.run.write(dict(ev="injector-restart", why=str(e), pc=round(now_ms(), 1)))
        try:
            ctx.inj.close()
        except Exception:
            pass
        ctx.inj.start()
        ctx.recovered += 1
    for s in (ctx.logs_app, ctx.logs_sys):
        if not s.alive():
            log("logcat stream died; restarting")
            ctx.run.write(dict(ev="logcat-restart", pc=round(now_ms(), 1)))
            s.start()
    if ctx.truth.kind == "real" and not ctx.truth.guard_alive():
        ctx.run.write(dict(ev="guard-dead", pc=round(now_ms(), 1)))


def blur_wait(ctx, rec, maxs=120):
    """Blank blur or hidden means the TV page lost the screen (modal up): wait it out, then settle."""
    t0 = time.time()
    if not ctx.truth.blur():
        return 0
    while ctx.truth.blur() and time.time() - t0 < maxs:
        time.sleep(0.5)
    time.sleep(3.0)
    ms = round((time.time() - t0) * 1000)
    rec["blur_wait_ms"] = rec.get("blur_wait_ms", 0) + ms
    if ctx.truth.blur():
        raise Skip("blur persisted")
    return ms


def maybe_prime(ctx, wait=1.2):
    """The guard darkens the panel ~20 s after the last key and the first key after dark only wakes it."""
    if ctx.truth.kind == "real" and now_ms() - ctx.truth.last_recv() > 8000:
        ctx.mouse.prime(wait)
        return True
    return False


def ensure_fg(ctx, rec, app, settle=None):
    top = ctx.dev.top()
    if app.pkg + "/" not in top:
        ctx.dev.adb("shell", "am", "start", "-W", "-n", f"{app.pkg}/{app.act}")
        time.sleep(ctx.cfg["settle_s"] if settle is None else settle)
        rec["fg_started"] = True
        top = ctx.dev.top()
    if app.pkg + "/" not in top:
        raise Skip(f"foreground is {top[:80]!r}, not {app.pkg}")
    rec["top"] = top[-60:]
    return top


def coords(ctx, rec, app, refresh=False):
    if refresh or not app.xy:
        get_coords(ctx.dev, app, ctx.applog)
    rec["coord_src"] = app.coord_src
    return app.xy


def need(app, *names):
    miss = [n for n in names if n not in app.xy]
    if miss:
        raise Skip(f"widgets not found: {', '.join(miss)} ({app.coord_src})")
    return [(app.xy[n]["x"], app.xy[n]["y"]) for n in names]


def do_sync(ctx, rec):
    off = ctx.inj.sync(12)
    off["tv"] = None if ctx.truth.offset_ms() is None else round(ctx.truth.offset_ms(), 2)
    off["tv_n"] = ctx.truth.count()
    rec["off"] = off
    return off


def mark(ctx, rec, app, broadcast=True):
    ctx.inj.cmd(f"L {rec['id']} {app.label}")
    if broadcast and ctx.cfg.get("app_marks", True):
        ctx.dev.sh(f"am broadcast -p {app.pkg} -a com.nic.lgpower.TEST_MARK --es id {rec['id']}")
    rec["pc_mark"] = round(now_ms(), 1)


def inject(ctx, rec, line, role):
    reply, pc0, pc1 = ctx.inj.cmd(line)
    if reply.startswith("ERR"):
        raise Skip(f"injector error: {reply[:120]}")
    pr = parse_reply(reply)
    e = dict(role=role, cmd=line, op=pr["op"], nums=pr["nums"], pc0=round(pc0, 1), pc1=round(pc1, 1))
    rec["inj"].append(e)
    return e


def wait_key(ctx, key, since_ms, wait_s):
    end = time.time() + wait_s
    while time.time() < end:
        hit = ctx.truth.keys_since(since_ms, key)
        if hit:
            return hit[0]
        time.sleep(0.04)
    return None


def arm(ctx, rec, app, prime=True, app_mark=True):
    """Everything that must be true right before an injected touch: no blur, TV awake, fresh clock offsets, mark."""
    blur_wait(ctx, rec)
    if prime:
        maybe_prime(ctx)
    mark(ctx, rec, app, broadcast=app_mark)
    do_sync(ctx, rec)
    blur_wait(ctx, rec)


def tap_and_watch(ctx, rec, app, name="right", retry=True, wait_s=None):
    wait_s = wait_s or ctx.cfg["tap_wait_s"]
    (x, y), = need(app, name)
    key = EXPECT.get(name)
    rec["expect"] = key
    rec["win_ms"] = int(wait_s * 1000)
    since = now_ms()
    inject(ctx, rec, f"T {x} {y}", "tap")
    hit = wait_key(ctx, key, since - 5, wait_s)
    if hit is not None:
        time.sleep(ctx.cfg["dup_watch_s"])   # a duplicate keydown can only be seen if the trial outlasts the first one
    if hit is None and retry:
        time.sleep(1.0)
        since2 = now_ms()
        inject(ctx, rec, f"T {x} {y}", "retry")
        wait_key(ctx, key, since2 - 5, 3.0)


def prep(ctx, rec, app):
    ensure_fg(ctx, rec, app)
    coords(ctx, rec, app)


# ------------------------------------------------------------------ scenarios

def sc_tap(ctx, rec, app, key="right", retry=True):
    prep(ctx, rec, app)
    arm(ctx, rec, app)
    tap_and_watch(ctx, rec, app, key, retry)


def sc_idle_gap(ctx, rec, app, s=120, key="right", retry=True):
    prep(ctx, rec, app)
    time.sleep(s)
    ensure_fg(ctx, rec, app, settle=0.5)
    arm(ctx, rec, app)
    tap_and_watch(ctx, rec, app, key, retry)


def unlock_phone(ctx, rec, app, t_after_wake=0.7):
    W, H = app.screen or ctx.dev.screen_size()
    inject(ctx, rec, "K 224", "wake")
    time.sleep(t_after_wake)
    inject(ctx, rec, f"G {W // 2} {int(H * 0.83)} {W // 2} {int(H * 0.29)} 200 8", "swipe")
    time.sleep(0.9)
    pin = ctx.pin
    if pin:
        for dg in pin[:-1]:
            inject(ctx, rec, f"K {7 + int(dg)}", "pin")
            time.sleep(0.07)
        e = inject(ctx, rec, f"K {7 + int(pin[-1])}", "pin")
        return e
    return rec["inj"][-1]


def sc_lock(ctx, rec, app, lock_s=8, delay_ms=0, retry=True):
    prep(ctx, rec, app)
    blur_wait(ctx, rec)
    mark(ctx, rec, app)
    inject(ctx, rec, f"K {ctx.cfg['lock_key']}", "lock")
    lead = max(0, lock_s - 4)
    time.sleep(lead)
    maybe_prime(ctx, wait=0)
    time.sleep(min(4, lock_s))
    do_sync(ctx, rec)
    t_wait = now_ms()
    last = unlock_phone(ctx, rec, app)
    end = time.time() + 5
    while time.time() < end:
        r = ctx.applog.focus_since(app.pkg, t_wait)
        if r:
            rec["focus_r"] = round(r, 1)
            break
        time.sleep(0.003)
    time.sleep(delay_ms / 1000)
    blur_wait(ctx, rec)
    rec["delay_ms"] = delay_ms
    tap_and_watch(ctx, rec, app, "right", retry)


def start_and_wait_focus(ctx, rec, app):
    """The first tap goes out as soon as the window can take input; before that Android drops it on the floor, which
    would score as a rig problem instead of the app's resume path."""
    t0 = now_ms()
    ctx.dev.adb("shell", "am", "start", "-W", "-n", f"{app.pkg}/{app.act}")
    rec["am_start_ms"] = round(now_ms() - t0)
    end = time.time() + 4
    while time.time() < end:
        r = ctx.applog.focus_since(app.pkg, t0 - 50)
        if r:
            rec["focus_r"] = round(r, 1)
            # a touch in the first milliseconds after input focus is dropped (the app log shows no touch at all); a person
            # cannot tap that fast either
            time.sleep(ctx.cfg["post_focus_ms"] / 1000)
            return
        time.sleep(0.003)
    rec["focus_r"] = None


def sc_bg(ctx, rec, app, bg_s=8, retry=True):
    prep(ctx, rec, app)
    blur_wait(ctx, rec)
    mark(ctx, rec, app)
    inject(ctx, rec, "K 3", "home")
    time.sleep(max(0, bg_s - 4))
    maybe_prime(ctx, wait=0)
    time.sleep(min(4, bg_s))
    do_sync(ctx, rec)
    blur_wait(ctx, rec)
    start_and_wait_focus(ctx, rec, app)
    tap_and_watch(ctx, rec, app, "right", retry)


def sc_cold(ctx, rec, app, retry=True):
    prep(ctx, rec, app)
    blur_wait(ctx, rec)
    mark(ctx, rec, app, broadcast=False)
    inject(ctx, rec, "K 3", "home")
    time.sleep(1.0)
    ctx.dev.adb("shell", "am", "force-stop", app.pkg)
    maybe_prime(ctx, wait=0)
    time.sleep(2.5)
    do_sync(ctx, rec)
    blur_wait(ctx, rec)
    start_and_wait_focus(ctx, rec, app)
    tap_and_watch(ctx, rec, app, "right", retry)


def sc_hold(ctx, rec, app, ms=3000, key="right"):
    prep(ctx, rec, app)
    arm(ctx, rec, app)
    (x, y), = need(app, key)
    rec["expect"] = EXPECT[key]
    rec["win_ms"] = ms + 1500
    inject(ctx, rec, f"H {x} {y} {ms}", "hold")
    time.sleep(1.5)


def sc_fast(ctx, rec, app, gap_ms=110, taps=10, hold_ms=25, pattern="up,down,left,right"):
    if ctx.inj.kind != "inj":
        raise Skip("fast sequences need the real injector (self-test fell back to input)")
    prep(ctx, rec, app)
    seq = (pattern.split(",") * taps)[:taps]
    pts = need(app, *seq)
    arm(ctx, rec, app)
    rec["seq"] = [EXPECT[k] for k in seq]
    rec["win_ms"] = int(gap_ms * taps + 2000)
    inject(ctx, rec, f"S {gap_ms} {hold_ms} " + " ".join(f"{x},{y}" for x, y in pts), "fast")
    time.sleep(2.0)


def sc_drag(ctx, rec, app, ms=300, step_ms=8, dx=150, dy=90, **_):
    prep(ctx, rec, app)
    (tx, ty), = need(app, "touchpad")
    ctx.mouse.center()
    time.sleep(0.6)
    arm(ctx, rec, app, prime=False)
    sgn = 1 if rec["i"] % 2 == 0 else -1
    rec["win_ms"] = ms + 1200
    inject(ctx, rec, f"G {tx} {ty} {tx + sgn * dx} {ty + sgn * dy} {ms} {step_ms}", "drag")
    time.sleep(1.2)


def engage_lock(ctx, rec, app):
    (tx, ty), = need(app, "touchpad")
    inject(ctx, rec, f"H {tx} {ty} 1400", "lock-hold")
    time.sleep(1.0)
    coords(ctx, rec, app, refresh=True)


def leave_lock(ctx, rec, app):
    if "pad_exit" in app.xy:
        inject(ctx, rec, f"T {app.xy['pad_exit']['x']} {app.xy['pad_exit']['y']}", "pad-exit")
    else:
        inject(ctx, rec, "K 3", "home")
        time.sleep(0.8)
        ctx.dev.adb("shell", "am", "force-stop", app.pkg)
    app.xy = {}


def pad_area(app, screen):
    p = app.xy.get("pad")
    if p:
        return p["l"], p["t"], p["r"], p["b"]
    W, H = screen
    return int(W * 0.1), int(H * 0.45), int(W * 0.9), int(H * 0.8)


def sc_locked_pad(ctx, rec, app, ms=300, step_ms=8, dxy=200, **_):
    prep(ctx, rec, app)
    ctx.mouse.center()
    engage_lock(ctx, rec, app)
    l, t, r, b = pad_area(app, app.screen or ctx.dev.screen_size())
    sgn = 1 if rec["i"] % 2 == 0 else -1
    cx, cy = (l + r) // 2, (t + b) // 2
    x0, y0 = cx - sgn * dxy // 2, cy - sgn * dxy // 4
    time.sleep(0.6)
    arm(ctx, rec, app, prime=False, app_mark=False)
    rec["win_ms"] = ms + 1200
    inject(ctx, rec, f"G {x0} {y0} {x0 + sgn * dxy} {y0 + sgn * dxy // 2} {ms} {step_ms}", "drag")
    time.sleep(1.2)
    leave_lock(ctx, rec, app)


def sc_pad_click(ctx, rec, app, **_):
    if ctx.truth.kind == "real" and not ctx.cfg.get("allow_click"):
        raise Skip("clicks on the real TV need --allow-click (a click on an unnoticed modal could hit Check now)")
    prep(ctx, rec, app)
    ctx.mouse.center()
    engage_lock(ctx, rec, app)
    l, t, r, b = pad_area(app, app.screen or ctx.dev.screen_size())
    time.sleep(0.5)
    arm(ctx, rec, app, prime=False, app_mark=False)
    rec["win_ms"] = 1500
    inject(ctx, rec, f"T {(l + r) // 2} {(t + b) // 2} 60", "click")
    time.sleep(1.2)
    leave_lock(ctx, rec, app)


def sc_wifi_blip(ctx, rec, app, off_s=5, retry=True):
    if ctx.dev.phone and not ctx.cfg.get("wifi_blip_ok"):
        raise Skip("wireless adb to the phone would drop with Wi-Fi (set settings.wifi_blip_ok for a USB-connected phone)")
    prep(ctx, rec, app)
    ctx.dev.sh("svc wifi disable")
    time.sleep(off_s)
    ctx.dev.sh("svc wifi enable")
    t0 = time.time()
    while time.time() - t0 < 40:
        if "inet " in ctx.dev.sh("ip -4 addr show wlan0"):
            break
        time.sleep(0.5)
    rec["reconnect_s"] = round(time.time() - t0, 1)
    time.sleep(1.0)
    arm(ctx, rec, app)
    tap_and_watch(ctx, rec, app, "right", retry)


def sc_kbd(ctx, rec, app, text="hello", wait_s=6.0):
    if ctx.kbd is None:
        raise Skip("no keyboard watcher (registerRemoteKeyboard subscription failed at start)")
    prep(ctx, rec, app)
    (kx, ky), = need(app, "keyboard")
    arm(ctx, rec, app, prime=False)
    inject(ctx, rec, f"T {kx} {ky}", "open-sheet")
    time.sleep(1.8)
    before = ctx.kbd.last()
    if before is None or not before[1]:
        raise Skip("TV reports no focused text field (a real TV needs the URL bar focused first)")
    n0 = before[2]
    _, xml = ui_coords(ctx.dev, app)
    hint = "Select a text field" in xml
    t0 = now_ms()
    ctx.dev.sh(f"input text {text}")
    time.sleep(0.5)
    ctx.dev.sh("input keyevent 66")
    end = time.time() + wait_s
    n1 = n0
    while time.time() < end:
        fr = ctx.kbd.since(t0)
        lens = [f[2] for f in fr if f[1] and f[2] is not None]
        if lens:
            n1 = lens[-1]
            if n1 - n0 >= len(text):
                break
        time.sleep(0.1)
    rec["kbd"] = dict(text=text, len_before=n0, len_after=n1, delta=n1 - n0, expect=len(text), focus=True, hint_visible=hint,
                      frames=len(ctx.kbd.since(t0)))
    ctx.dev.adb("shell", "am", "force-stop", app.pkg)
    app.xy = {}


SCENARIOS = {"tap": sc_tap, "idle_gap": sc_idle_gap, "lock": sc_lock, "bg": sc_bg, "cold": sc_cold, "hold": sc_hold, "fast": sc_fast,
             "drag": sc_drag, "locked_pad": sc_locked_pad, "pad_click": sc_pad_click, "wifi_blip": sc_wifi_blip, "kbd": sc_kbd}


def run_trial(ctx, spec):
    app = ctx.apps[spec["app"]]
    ctx.n += 1
    rec = dict(ev="trial", id=f"t{ctx.n:04d}", row=spec["row"], i=spec["i"], app=app.label, pkg=app.pkg, sc=spec["sc"],
               params=spec["params"], inj=[], void=None, pc_start=round(now_ms(), 1))
    try:
        housekeep(ctx)
        SCENARIOS[spec["sc"]](ctx, rec, app, **spec["params"])
    except Skip as e:
        rec["void"] = f"rig: {e}"
    except Paused as e:
        rec["void"] = f"rig: owner paused the phone ({e})"
        ctx.last_borrow = 0
    except InjectorDead as e:
        rec["void"] = f"rig: injector {e}"
    except subprocess.TimeoutExpired as e:
        rec["void"] = f"rig: adb timeout {str(e)[:80]}"
    except AbortRun:
        raise
    except Exception as e:
        rec["void"] = f"rig: exception {e!r:.150}"
        log(traceback.format_exc())
    rec["pc_end"] = round(now_ms(), 1)
    if rec["void"]:
        log(f"{rec['id']} {rec['app']} {rec['sc']} VOID {rec['void']}")
    ctx.run.write(rec)
    return rec
