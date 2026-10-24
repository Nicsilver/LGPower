"""phonelab runner: one process, no agent between steps.

    python run.py --matrix matrices/smoke.yaml --apps K,F --tv real
    python run.py --matrix matrices/emu-smoke.yaml --apps K,F --tv fake --serial emulator-5574

Writes <out>/{meta.json,run.jsonl,app.jsonl,sys.jsonl,tv.jsonl|faketv.timeline,kbd.jsonl,runner.log} and then runs score.py
on the folder, which writes summary.md and anomalies.md. Read those two files, not the raw logs.
"""
import argparse, json, os, socket, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from pl import device as D
from pl import runner as R
from pl import truth as T
from pl.kbd import KbdWatch
from pl.matrix import expand
from pl.util import Jsonl, log, now_ms, set_log_file, pid_alive

TV_IP = os.environ.get("PHONELAB_TV_IP", "192.168.1.157")
TV_MAC = os.environ.get("PHONELAB_TV_MAC", "3c:f0:83:55:ad:5e")
TV_KEY = os.environ.get("PHONELAB_TV_KEY", r"C:\Programming\LGPowerWidget\.plans\tv\client-key.txt")
FAKETV = os.path.join(HERE, "..", "faketv", "faketv.py")
DEFAULT_PKG = {"K": "com.nic.lgpower", "F": "com.nic.lgpower.flutter"}


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]
    finally:
        s.close()


def port_open(host, port):
    s = socket.socket()
    s.settimeout(0.5)
    try:
        s.connect((host, port))
        return True
    except OSError:
        return False
    finally:
        s.close()


def get_pin():
    out = subprocess.run(["pwsh", "-NoProfile", "-Command",
                          "[Net.NetworkCredential]::new('', (Get-Content (Join-Path $HOME '.phone-pin') | ConvertTo-SecureString)).Password"],
                         capture_output=True, text=True).stdout.strip()
    return out or None


def git_rev():
    try:
        return subprocess.run(["git", "-C", HERE, "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
    except Exception:
        return None


def tap_burst(ctx, app, n, gap=0.35):
    (x, y), = R.need(app, "right")
    since = now_ms()
    errs = 0
    for _ in range(n):
        try:
            r, _, _ = ctx.inj.cmd(f"T {x} {y}")
            errs += r.startswith("ERR")
        except D.InjectorDead:
            errs += 1
        time.sleep(gap)
    time.sleep(0.8)
    return len(ctx.truth.keys_since(since - 5, "ArrowRight")), errs


def self_test(ctx, apps):
    """10 injected taps per app must land on the TV before the matrix starts; otherwise fall back to `input`, and if that
    fails too abort instead of recording a night of empty trials."""
    out = {}
    for app in apps:
        rec = dict(id="selftest", inj=[])
        R.ensure_fg(ctx, rec, app, settle=2.0)
        R.coords(ctx, rec, app)
        R.need(app, "right")
        R.maybe_prime(ctx)
        ctx.inj.sync(8)
        warm = False
        (x, y), = R.need(app, "right")
        t_end = time.time() + 12
        while time.time() < t_end:   # the first connection of a fresh app takes a moment
            since = now_ms()
            ctx.inj.cmd(f"T {x} {y}")
            if R.wait_key(ctx, "ArrowRight", since - 5, 1.5):
                warm = True
                break
        got, errs = tap_burst(ctx, app, 10)
        res = dict(app=app.label, injector=ctx.inj.kind, warm=warm, sent=10, got=got, inj_errors=errs, coord_src=app.coord_src)
        if got < 8 and ctx.inj.kind == "inj":
            log(f"self-test {app.label}: injector landed {got}/10; trying the input fallback")
            ctx.inj.close()
            ctx.inj = D.FallbackInjector(ctx.dev)
            ctx.inj.sync(6)
            got2, errs2 = tap_burst(ctx, app, 5, gap=0.6)
            res.update(fallback_got=got2, fallback_sent=5)
            if got2 >= 4:
                res["injector"] = "input"
            else:
                res["fail"] = True
        elif got < 8:
            res["fail"] = True
        out[app.label] = res
        log(f"self-test {app.label}: {res}")
        ctx.inj.cmd("K 3")
        time.sleep(0.5)
    ctx.run.write(dict(ev="selftest", results=out))
    return out


def start_faketv(args, out):
    """Starts faketv as a child. Another session may already hold 0.0.0.0:3001 (the apps always dial 3001), so in that case
    this copy binds the PC's LAN address instead and the device is pointed at that address."""
    bind = "0.0.0.0"
    if port_open("127.0.0.1", 3001):
        bind = lan_ip()
        args.tv_host = bind
        log(f"port 3001 is taken on this PC (another faketv?); binding {bind} instead and pointing the device at it")
    tl = os.path.join(out, "faketv.timeline")
    cmd = [sys.executable, FAKETV, "--host", args.tv_host, "--bind", bind, "--timeline", tl, *args.faketv_args.split()]
    logf = open(os.path.join(out, "faketv.log"), "w")
    p = subprocess.Popen(cmd, stdout=logf, stderr=subprocess.STDOUT, cwd=os.path.dirname(FAKETV))
    probe = "127.0.0.1" if bind == "0.0.0.0" else bind
    for _ in range(60):
        if port_open(probe, 3001) and p.poll() is None:
            return p, tl, probe
        if p.poll() is not None:
            raise SystemExit("faketv exited at start, see faketv.log")
        time.sleep(0.5)
    p.terminate()
    raise SystemExit("faketv did not open port 3001")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--matrix", required=True)
    ap.add_argument("--apps", default="K,F")
    ap.add_argument("--tv", choices=["real", "fake"], default="fake")
    ap.add_argument("--serial", help="adb serial; omit for the phone, required (emulator-NNNN) for an emulator")
    ap.add_argument("--out")
    ap.add_argument("--tv-host", help="address the DEVICE uses to reach faketv (default 10.0.2.2 on an emulator, this PC's LAN IP otherwise)")
    ap.add_argument("--faketv-args", default="", help="extra faketv flags, e.g. '--drop-cmd-every 30'")
    ap.add_argument("--pkg-k", default=DEFAULT_PKG["K"])
    ap.add_argument("--pkg-f", default=DEFAULT_PKG["F"])
    ap.add_argument("--install-k")
    ap.add_argument("--install-f")
    ap.add_argument("--tv-prep", action="store_true", help="real TV: run tv.py wake and tv.py blank first")
    ap.add_argument("--keep-tv", action="store_true", help="real TV: do not turn it off at the end")
    ap.add_argument("--no-park", action="store_true")
    ap.add_argument("--allow-click", action="store_true", help="real TV: allow pad_click trials")
    ap.add_argument("--no-selftest", action="store_true")
    ap.add_argument("--force-input", action="store_true", help="skip the injector and use `adb shell input` (slower, no fast taps)")
    ap.add_argument("--limit", type=int, default=0, help="run only the first N trials (debug)")
    args = ap.parse_args()

    emulator = bool(args.serial and args.serial.startswith("emulator-"))
    labels = [a.strip().upper() for a in args.apps.split(",") if a.strip()]
    settings, trials = expand(args.matrix, labels)
    if args.limit:
        trials = trials[:args.limit]
    name = os.path.splitext(os.path.basename(args.matrix))[0]
    out = os.path.abspath(args.out or os.path.join(HERE, "runs", time.strftime("%Y%m%d-%H%M%S") + "-" + name))
    os.makedirs(out, exist_ok=True)
    set_log_file(os.path.join(out, "runner.log"))
    if args.tv == "real" and emulator:
        log("note: emulator with the real TV; fine for a dry run, the C4 sees only RIGHT/UP/LEFT/DOWN")
    args.tv_host = args.tv_host or ("10.0.2.2" if emulator else lan_ip())

    serial = args.serial or D.find_phone_serial()
    dev = D.Dev(serial, phone=not emulator)
    ctx = R.Ctx()
    ctx.cfg = {**settings, "allow_click": args.allow_click}
    ctx.dev, ctx.out = dev, out
    ctx.run = Jsonl(os.path.join(out, "run.jsonl"))
    sys_sink = Jsonl(os.path.join(out, "sys.jsonl"))
    app_sink = Jsonl(os.path.join(out, "app.jsonl"))
    kbd_sink = Jsonl(os.path.join(out, "kbd.jsonl"))
    ctx.applog = D.AppLog(app_sink, sys_sink)
    ctx.kbd = ctx.mouse = ctx.faketv = None
    fake_probe = "127.0.0.1"
    ctx.pin = get_pin() if (dev.phone and any(t["sc"] == "lock" for t in trials)) else None
    exit_code = 0
    guard_log, guard_pos = None, 0
    meta = dict(matrix=os.path.abspath(args.matrix), apps=labels, tv=args.tv, serial=serial, target="emulator" if emulator else "phone",
                trials=len(trials), started=time.strftime("%Y-%m-%d %H:%M:%S"), pc_start=now_ms(), git=git_rev(), settings=ctx.cfg,
                tv_host=args.tv_host)
    try:
        # ---- apps
        ctx.apps = {}
        for lab in labels:
            app = D.App(lab, getattr(args, "pkg_" + lab.lower()), lab.lower())
            apk = getattr(args, "install_" + lab.lower())
            if apk:
                log(dev.adb("install", "-r", "-t", apk, timeout=240).strip().splitlines()[-1])
            if not dev.installed(app.pkg):
                raise SystemExit(f"{app.pkg} is not installed on {serial}")
            app.version = dev.version_code(app.pkg)
            D.resolve_activity(dev, app)
            ctx.apps[lab] = app
        meta["apps_info"] = {l: dict(pkg=a.pkg, version=a.version, activity=a.act) for l, a in ctx.apps.items()}

        # ---- TV side
        if args.tv == "real":
            if args.tv_prep:
                for sub in ("wake", "blank"):
                    r = T.tv_py(sub, timeout=200)
                    log(f"tv.py {sub}: rc={r.returncode} {r.stdout.strip()[-120:]}")
            ctx.truth = T.BlankTruth(os.path.join(out, "tv.jsonl"))
            if not ctx.truth.guard_alive():
                raise SystemExit("the TV guard is not running (python tv.py wake starts it); refusing to run unattended without it")
            if ctx.truth.alert():
                raise SystemExit("guard ALERT is set; clear it with tv.py close-popup first")
            ctx.truth.start()
            ctx.mouse = T.open_mouse() or T.NullMouse()
            tv_ip, tv_mac, key = TV_IP, TV_MAC, open(TV_KEY).read().strip()
            guard_log = os.path.join(T.STATE_DIR, "guard.log")
            guard_pos = os.path.getsize(guard_log) if os.path.exists(guard_log) else 0
        else:
            ctx.faketv, tl, fake_probe = start_faketv(args, out)
            ctx.truth = T.FakeTruth(tl)
            ctx.truth.start()
            ctx.mouse = T.NullMouse()
            tv_ip, tv_mac, key = args.tv_host, "AA:BB:CC:DD:EE:FF", "phonelab-fake-key"
        if dev.phone:
            dev.borrow()
            ctx.last_borrow = time.time()

        # ---- device side
        for app in ctx.apps.values():
            D.seed_prefs(dev, app, tv_ip, tv_mac, key)
        ctx.logs_app, ctx.logs_sys = D.start_logs(dev, ctx.applog)
        ctx.inj = D.FallbackInjector(dev) if args.force_input else D.Injector(dev)
        try:
            log("injector: " + ctx.inj.start())
        except D.InjectorDead as e:
            log(f"injector failed to start ({e}); using the input fallback")
            ctx.inj = D.FallbackInjector(dev)
            ctx.inj.start()
        if any(t["sc"] == "kbd" for t in trials):
            if args.tv == "fake":
                ctx.kbd = KbdWatch(fake_probe, kbd_sink)
            else:
                sys.path.insert(0, T.SKILLS_TV)
                import tv as TVM
                ctx.kbd = KbdWatch(TVM.IP, kbd_sink, key=open(TVM.HELPER_KEY).read().strip(), perms=TVM.HELPER_PERMS, pairing="PIN")
            if not ctx.kbd.start():
                log(f"keyboard watcher failed: {ctx.kbd.error}")
                ctx.kbd = None
        if not args.no_selftest:
            meta["selftest"] = self_test(ctx, list(ctx.apps.values()))
            if any(r.get("fail") for r in meta["selftest"].values()):
                bad = {k: v for k, v in meta["selftest"].items() if v.get("fail")}
                raise R.AbortRun(f"self-test failed: {bad}; app log lines seen {ctx.applog.n}, TV events {ctx.truth.count()}")
        meta["injector"] = ctx.inj.kind

        # ---- matrix
        t_run = time.time()
        voids = 0
        for k, spec in enumerate(trials, 1):
            rec = R.run_trial(ctx, spec)
            voids += bool(rec["void"])
            done = k
            el = time.time() - t_run
            eta = el / done * (len(trials) - done)
            json.dump(dict(done=done, total=len(trials), void=voids, elapsed_s=round(el), eta_s=round(eta), last=rec["id"]),
                      open(os.path.join(out, "progress.json"), "w"))
            log(f"[{done}/{len(trials)}] {rec['id']} {rec['app']} {rec['sc']} {'VOID' if rec['void'] else 'ok'}  eta {eta / 60:.1f} min")
            time.sleep(ctx.cfg["gap_s"])
    except R.AbortRun as e:
        log(f"ABORT: {e}")
        meta["abort"] = str(e)
        exit_code = 5
    except KeyboardInterrupt:
        meta["abort"] = "interrupted"
        exit_code = 130
    except BaseException as e:
        log(f"FATAL {e!r}")
        meta["abort"] = repr(e)
        exit_code = 1
    finally:
        log("teardown")
        for fn in (lambda: ctx.kbd and ctx.kbd.stop(), lambda: getattr(ctx, "inj", None) and ctx.inj.close(),
                   lambda: ctx.mouse and ctx.mouse.close()):
            try:
                fn()
            except Exception:
                pass
        if dev.phone and not args.no_park:
            try:
                dev.park()
            except Exception as e:
                log(f"park failed {e!r}")
        if args.tv == "real" and not args.keep_tv:
            for attempt in range(6):
                r = T.tv_py("off", timeout=200)
                log(f"tv.py off attempt {attempt + 1}: rc={r.returncode}")
                if r.returncode == 0:
                    break
                time.sleep(10)
            else:
                meta["tv_off_failed"] = True
        time.sleep(1.0)
        for s in (getattr(ctx, "logs_app", None), getattr(ctx, "logs_sys", None)):
            if s:
                s.stop()
        if getattr(ctx, "truth", None):
            ctx.truth.stop()
        if guard_log and os.path.exists(guard_log):
            with open(guard_log, "rb") as f:
                f.seek(guard_pos)
                open(os.path.join(out, "guard.log"), "wb").write(f.read())
        if ctx.faketv:
            ctx.faketv.terminate()
            try:
                ctx.faketv.wait(10)
            except Exception:
                pass
        meta["ended"] = time.strftime("%Y-%m-%d %H:%M:%S")
        meta["pc_end"] = now_ms()
        meta["exit_code"] = exit_code
        json.dump(meta, open(os.path.join(out, "meta.json"), "w"), indent=1, default=str)
        for j in (ctx.run, app_sink, sys_sink, kbd_sink):
            j.close()
    try:
        import score
        score.score_dir(out)
        print(os.path.join(out, "summary.md"))
        print(os.path.join(out, "anomalies.md"))
    except Exception as e:
        log(f"scoring failed: {e!r}")
    sys.exit(exit_code)


if __name__ == "__main__":
    main()
