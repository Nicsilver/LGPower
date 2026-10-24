"""Trial execution. The runner only records raw facts into run.jsonl (what was injected, when, which clock offsets were
current); every verdict is computed by score.py so a run can be re-scored after the scorer improves."""
import os, subprocess, sys, time, traceback

from . import truth as T
from .device import (InjectorDead, Paused, FallbackInjector, Injector, get_coords, parse_reply, ui_coords)
from .matrix import REAL_TV_KEYS
from .util import AbortRun, log, now_ms

EXPECT = {"right": "ArrowRight", "left": "ArrowLeft", "up": "ArrowUp", "down": "ArrowDown", "ok": "Enter"}


class Skip(Exception):
    """The trial cannot run in this setup; it is recorded as void with the reason."""


class Ctx:
    def __init__(self):
        self.cfg = {}
        self.n = 0
        self.last_borrow = time.time()
        self.paused_s = 0.0
        self.pin = None
        self.recovered = 0
        self.dark = False          # the runner turned the panel off between trials, so the next first key only wakes it
        self.after_lock = set()    # packages whose touchpad-lock overlay may still be up from the previous trial
        self.unlock_fail = 0


# ------------------------------------------------------------------ housekeeping

PARK_AFTER_S = 60


def housekeep(ctx):
    t0 = time.time()
    parked = False
    while ctx.truth.alert() or os.path.exists(os.path.join(ctx.out, "PAUSE")):
        why = "ALERT" if ctx.truth.alert() else "PAUSE file"
        if time.time() - t0 < 6:
            log(f"paused by {why}; waiting (clear it with `tv.py close-popup --clear` / delete PAUSE)")
            ctx.run.write(dict(ev="pause", why=why, pc=round(now_ms(), 1)))
        if ctx.dev.phone and not parked and time.time() - t0 > PARK_AFTER_S:
            # an unlocked, dimmed phone must not sit idle through a long wait (OLED, and Nic may want it back)
            ctx.run.write(dict(ev="park", why=why, rc=ctx.dev.park(), pc=round(now_ms(), 1)))
            parked = True
        if time.time() - t0 > ctx.cfg.get("alert_max_s", 1800):
            raise AbortRun(f"{why} not cleared after {ctx.cfg.get('alert_max_s', 1800)} s")
        time.sleep(3)
    if parked:
        rc = ctx.dev.borrow()
        ctx.last_borrow = time.time()
        ctx.run.write(dict(ev="borrow", rc=rc, after_park=True, pc=round(now_ms(), 1)))
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
        raise AbortRun("the TV guard died; nothing keeps the panel dark or watches for the upgrade modal")


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
    if ctx.truth.kind == "real" and (ctx.dark or now_ms() - ctx.truth.last_recv() > 8000):
        ctx.mouse.prime(wait)
        ctx.dark = False
        return True
    return False


def dark_panel(ctx):
    """Real TV: panel off between trials (tv skill rule 3); the next trial's prime wakes it."""
    if ctx.truth.kind != "real":
        return
    if not ctx.mouse.screen_off():
        r = T.tv_py("screen-off", timeout=60)
        if r.returncode != 0:
            log(f"screen-off failed rc={r.returncode}: {r.stdout.strip()[-100:]}")
    ctx.dark = True


def center_pointer(ctx):
    ctx.mouse.center()
    ctx.dark = False   # 137 pointer moves wake a dark panel; only the first one is swallowed


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


def real_dirs_only(ctx, *names):
    """Backstop for matrix.real_tv_problems: the real TV only ever gets d-pad directions."""
    if ctx.truth.kind == "real":
        bad = [n for n in names if n not in REAL_TV_KEYS]
        if bad:
            raise AbortRun(f"refusing to press {bad} on the real TV")


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


def check_alert(ctx):
    if ctx.truth.alert():
        raise Skip("guard ALERT is set; no touch goes out while a popup may be on the TV")


def inject(ctx, rec, line, role, force=False):
    """`force` is for cleanup only (leaving a locked touchpad): it skips the ALERT and pause checks."""
    if not force:
        check_alert(ctx)
        ctx.dev.gate_if_idle()   # the owner may have paused the phone during the sleep before this touch
    reply, pc0, pc1 = ctx.inj.cmd(line)
    ctx.dev.last_cmd = time.time()
    if reply.startswith("ERR"):
        raise Skip(f"injector error: {reply[:120]}")
    pr = parse_reply(reply)
    # the PIN must never reach disk: run.jsonl and the scorer's excerpts print this field
    e = dict(role=role, cmd="K *" if role == "pin" else line, op=pr["op"], nums=pr["nums"], pc0=round(pc0, 1), pc1=round(pc1, 1))
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
    check_alert(ctx)
    blur_wait(ctx, rec)
    if prime:
        maybe_prime(ctx)
    mark(ctx, rec, app, broadcast=app_mark)
    do_sync(ctx, rec)
    blur_wait(ctx, rec)
    check_alert(ctx)
    ctx.dev.gate()


def tap_and_watch(ctx, rec, app, name="right", retry=True, wait_s=None):
    wait_s = wait_s or ctx.cfg["tap_wait_s"]
    real_dirs_only(ctx, name)
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
    ctx.dev.gate()   # the lock sleep was long; the PIN must not go into a phone the owner picked up meanwhile
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


def ensure_unlocked(ctx, rec):
    if not ctx.dev.keyguard_showing():
        ctx.unlock_fail = 0
        return
    ctx.unlock_fail += 1
    rc = ctx.dev.unlock() if ctx.dev.phone else None
    rec["unlock_retry_rc"] = rc
    if ctx.unlock_fail >= 2:
        raise AbortRun("the keyguard stayed up after the PIN twice in a row")
    raise Skip("the keyguard was still up after the unlock; phone-adb.ps1 unlock was run and the trial is void")


def sc_lock(ctx, rec, app, lock_s=8, delay_ms=0, retry=True):
    if ctx.dev.phone and not ctx.pin:
        raise Skip("no phone PIN (~/.phone-pin) to unlock with")
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
    if rec.get("focus_r") is None:
        # no input focus for the app: the keyguard may still be up, and a tap on the PIN pad counts as a wrong PIN
        ensure_unlocked(ctx, rec)
    else:
        ctx.unlock_fail = 0
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
    real_dirs_only(ctx, key)
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
    real_dirs_only(ctx, *seq)
    pts = need(app, *seq)
    arm(ctx, rec, app)
    rec["seq"] = [EXPECT[k] for k in seq]
    rec["win_ms"] = int(gap_ms * taps + 2000)
    inject(ctx, rec, f"S {gap_ms} {hold_ms} " + " ".join(f"{x},{y}" for x, y in pts), "fast")
    time.sleep(2.0)


def sc_drag(ctx, rec, app, ms=300, step_ms=8, dx=150, dy=90, **_):
    prep(ctx, rec, app)
    (tx, ty), = need(app, "touchpad")
    center_pointer(ctx)
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
    """Leave the locked touchpad without touching the screen: a tap on the exit button that moved would be a TV click.
    HOME plus force-stop always works, and every step is best effort so the force-stop runs even if HOME failed."""
    ctx.after_lock.add(app.pkg)
    app.xy = {}
    for step in (lambda: inject(ctx, rec, "K 3", "home", force=True),
                 lambda: time.sleep(0.8),
                 lambda: ctx.dev.adb("shell", "am", "force-stop", app.pkg)):
        try:
            step()
        except AbortRun:
            raise
        except Exception as e:
            log(f"leave_lock step failed: {e!r:.120}")


def reset_after_lock(ctx, app):
    """The previous trial may have left an overlay up (a failed trial, or one cut short): the next touch would become a TV
    click. Start the next trial from a stopped app."""
    pkgs = ctx.after_lock | {app.pkg}
    try:
        ctx.inj.cmd("K 3")
    except Exception as e:
        log(f"home before the post-lock reset failed: {e!r:.100}")
    time.sleep(0.8)
    for pkg in sorted(pkgs):
        ctx.dev.adb("shell", "am", "force-stop", pkg)
    ctx.after_lock = set()
    for a in ctx.apps.values():
        a.xy = {}


def pad_area(app, screen):
    p = app.xy.get("pad")
    if p:
        return p["l"], p["t"], p["r"], p["b"]
    W, H = screen
    return int(W * 0.1), int(H * 0.45), int(W * 0.9), int(H * 0.8)


def sc_locked_pad(ctx, rec, app, ms=300, step_ms=8, dxy=200, **_):
    prep(ctx, rec, app)
    center_pointer(ctx)
    try:
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
    finally:
        leave_lock(ctx, rec, app)


def sc_pad_click(ctx, rec, app, **_):
    if ctx.truth.kind == "real":
        raise Skip("pad_click is never run on the real TV (a click on an unnoticed modal could hit Check now)")
    prep(ctx, rec, app)
    center_pointer(ctx)
    try:
        engage_lock(ctx, rec, app)
        l, t, r, b = pad_area(app, app.screen or ctx.dev.screen_size())
        time.sleep(0.5)
        arm(ctx, rec, app, prime=False, app_mark=False)
        rec["win_ms"] = 1500
        inject(ctx, rec, f"T {(l + r) // 2} {(t + b) // 2} 60", "click")
        time.sleep(1.2)
    finally:
        leave_lock(ctx, rec, app)


def sc_wifi_blip(ctx, rec, app, off_s=5, retry=True):
    if ctx.dev.phone:
        raise Skip("wifi_blip never runs on a real phone: Wi-Fi off drops wireless adb and padb cannot reconnect it")
    prep(ctx, rec, app)
    try:
        ctx.dev.sh("svc wifi disable")
        time.sleep(off_s)
    finally:
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


def sc_kbd(ctx, rec, app, text="hello", wait_s=6.0, expect_focus=True):
    if ctx.truth.kind == "real":
        raise Skip("kbd trials are fake-TV only (they need a focused field and an ENTER on the real TV)")
    if ctx.kbd is None:
        raise Skip("no keyboard watcher (registerRemoteKeyboard subscription failed at start)")
    prep(ctx, rec, app)
    (kx, ky), = need(app, "keyboard")
    arm(ctx, rec, app, prime=False)
    inject(ctx, rec, f"T {kx} {ky}", "open-sheet")
    xml = ""
    end = time.time() + 10
    while time.time() < end:   # the first open of the sheet can take seconds on a slow device
        time.sleep(0.8)
        _, xml = ui_coords(ctx.dev, app)
        if "EditText" in xml:
            break
    else:
        raise Skip("the keyboard sheet never opened")
    before = ctx.kbd.last()
    if before is None:
        raise Skip("the TV sent no registerRemoteKeyboard frame")
    if bool(before[1]) != bool(expect_focus):
        raise Skip(f"TV reports focus={bool(before[1])} but the trial expects {bool(expect_focus)} )")
    n0 = before[2]
    if expect_focus:
        time.sleep(1.5)   # the no-field hint arrives with the first focus frame; give a wrong one time to show
        _, xml = ui_coords(ctx.dev, app)
    else:
        hint_end = time.time() + 5
        while "Select a text field" not in xml and time.time() < hint_end:
            time.sleep(0.7)
            _, xml = ui_coords(ctx.dev, app)
    hint = "Select a text field" in xml
    if not expect_focus:
        rec["kbd"] = dict(text="", len_before=None, len_after=None, delta=0, expect=0, focus=False, hint_visible=hint, frames=0)
        ctx.dev.adb("shell", "am", "force-stop", app.pkg)
        app.xy = {}
        return
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
        if ctx.after_lock:
            reset_after_lock(ctx, app)
        SCENARIOS[spec["sc"]](ctx, rec, app, **spec["params"])
        if ctx.truth.kind == "real" and ctx.truth.keys_since(rec["pc_start"], "Enter"):
            raise AbortRun("an ENTER reached the real TV during a trial; stopping before anything else is sent")
    except Skip as e:
        rec["void"] = f"rig: {e}"
    except Paused as e:
        rec["void"] = f"rig: owner paused the phone ({e})"
        ctx.last_borrow = 0
    except InjectorDead as e:
        rec["void"] = f"rig: injector {e}"
    except subprocess.TimeoutExpired as e:
        rec["void"] = f"rig: adb timeout {str(e)[:80]}"
    except AbortRun as e:
        rec["void"] = f"rig: run aborted ({e})"
        rec["pc_end"] = round(now_ms(), 1)
        ctx.run.write(rec)
        raise
    except Exception as e:
        rec["void"] = f"rig: exception {e!r:.150}"
        log(traceback.format_exc())
    rec["pc_end"] = round(now_ms(), 1)
    if rec["void"]:
        log(f"{rec['id']} {rec['app']} {rec['sc']} VOID {rec['void']}")
    ctx.run.write(rec)
    return rec
