"""Scorer: joins run.jsonl (what the runner injected), app.jsonl (what the app logged) and the TV truth (tv.jsonl from the
Blank listener, or faketv.timeline) into summary.md and anomalies.md.

    python score.py <run dir>

Clocks: the phone log and injector stamps are phone uptime; one offset per trial (min-RTT median of 12 probes, taken right
before the tap) moves them to PC time. Blank's own clock is moved to PC time with one offset for the whole run: the minimum
of (pc_recv - TV wall), i.e. the packet that crossed the LAN without queueing. Lost, duplicate and order results do not use
any offset; only latencies do.
"""
import collections, json, os, statistics, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from pl.truth import normalize_blank, parse_timeline_line
from pl.util import read_jsonl

LATE_MS = 500
DRIFT_MS = 50
GAP_WARN_MS = 150
TAP_LIKE = {"tap", "idle_gap", "lock", "bg", "cold", "wifi_blip"}
DRAG_LIKE = {"drag", "locked_pad"}


def pct(v, p):
    v = sorted(v)
    return v[min(len(v) - 1, int(p / 100 * len(v)))] if v else None


def med(v):
    return statistics.median(v) if v else None


def r0(x):
    return None if x is None else round(x)


def f(x, nd=0):
    return "-" if x is None else (f"{x:.{nd}f}" if isinstance(x, float) else str(x))


# ------------------------------------------------------------------ loading

class Run:
    def __init__(self, d):
        self.d = d
        self.meta = json.load(open(os.path.join(d, "meta.json"))) if os.path.exists(os.path.join(d, "meta.json")) else {}
        rows = read_jsonl(os.path.join(d, "run.jsonl"))
        self.trials = [r for r in rows if r.get("ev") == "trial"]
        self.events = [r for r in rows if r.get("ev") != "trial"]
        self.app = read_jsonl(os.path.join(d, "app.jsonl"))
        self.kind = self.meta.get("tv") or ("fake" if os.path.exists(os.path.join(d, "faketv.timeline")) else "real")
        self.tv_raw_n = 0
        self.tv = []
        self.tv_off = None
        self.tv_off_windows = []
        self._load_tv()
        self.by_app = collections.defaultdict(list)
        for a in self.app:
            self.by_app[a.get("app")].append(a)
        self.guard = []
        gp = os.path.join(d, "guard.log")
        if os.path.exists(gp):
            self.guard = open(gp, encoding="utf-8", errors="replace").read().splitlines()

    def _load_tv(self):
        if self.kind == "fake":
            p = os.path.join(self.d, "faketv.timeline")
            if os.path.exists(p):
                for line in open(p, encoding="utf-8", errors="replace"):
                    e = parse_timeline_line(line)
                    if e:
                        e["pc"] = e["recv"]
                        self.tv.append(e)
            self.tv_off = 0.0
        else:
            raw = read_jsonl(os.path.join(self.d, "tv.jsonl"))
            diffs = []
            for r in raw:
                e = normalize_blank(r)
                if e["wall"] is not None and r.get("pc_recv"):
                    diffs.append((r["pc_recv"] * 1000.0, r["pc_recv"] * 1000.0 - e["wall"]))
                self.tv.append(e)
            if diffs:
                self.tv_off = min(d for _, d in diffs)
                span = 300_000
                t0 = diffs[0][0]
                buckets = collections.defaultdict(list)
                for t, d in diffs:
                    buckets[int((t - t0) // span)].append(d)
                self.tv_off_windows = [min(v) for _, v in sorted(buckets.items()) if len(v) >= 20]
                self.tv_off_p5_gap = pct([d - self.tv_off for _, d in diffs], 5)
            for e in self.tv:
                e["pc"] = (e["wall"] + self.tv_off) if (e["wall"] is not None and self.tv_off is not None) else e["recv"]
        self.tv_raw_n = len(self.tv)
        self.tv.sort(key=lambda e: e["pc"])
        self.blur = []
        cur = None
        for e in self.tv:
            if e["kind"] == "blur" and cur is None:
                cur = e["pc"]
            elif e["kind"] == "focus" and cur is not None:
                self.blur.append((cur, e["pc"]))
                cur = None
        if cur is not None:
            self.blur.append((cur, float("inf")))

    def tv_between(self, a, b, kinds=None):
        return [e for e in self.tv if a <= e["pc"] <= b and (kinds is None or e["kind"] in kinds)]


# ------------------------------------------------------------------ per-trial scoring

def cap(rec, t):
    """A trial's window never reaches into the next trial."""
    return min(t, rec.get("cap", float("inf")))


def to_pc(rec, up):
    return up + rec["off"]["phone_ms"]


def inj_by_role(rec, role):
    return [e for e in rec["inj"] if e["role"] == role]


def app_events(run, rec):
    lab = rec["app"].lower()
    off = rec["off"]["phone_ms"]
    lo, hi = rec["pc_start"] - 8000, rec["pc_end"] + 2000
    out = []
    for a in run.by_app.get(lab, []):
        if "t" in a:
            pc = a["t"] + off
            if lo <= pc <= hi:
                out.append(dict(a, pc=pc))
    return out


def app_counts(run, rec, a, b, ev):
    """How the app reports its own frames of one kind in [a, b]: sent / queued / dropped."""
    c = collections.Counter()
    for e in app_events(run, rec):
        if e.get("ev") == ev and a <= e["pc"] <= b:
            c[e.get("st", "?")] += 1
    return dict(c)


def sock_story(evs, t_pc, win_ms):
    """What the app's pointer session looked like when the tap arrived."""
    story = dict(session=None, ready_ms=None, fails=[], connects=0, key=None, key_st=None, touch_seen=None, dispatch_ms=None,
                 queued_flush=None)
    ptr = [e for e in evs if e.get("ev") == "sock" and e.get("role") in ("ptr", "reg") and e["pc"] <= t_pc]
    last_open = None
    for e in ptr:
        if e["role"] == "ptr":
            story["session"] = e.get("st")
            if e.get("st") == "open":
                last_open = e["pc"]
    if story["session"] == "open" and last_open is not None:
        story["ready_ms"] = round(t_pc - last_open)
    for e in evs:
        if e.get("ev") == "sock" and e.get("st") in ("fail", "closing", "dead", "prompt") and t_pc - 6000 <= e["pc"] <= t_pc + win_ms:
            story["fails"].append(f"{e.get('role')}:{e.get('st')}" + (f" {e['reason']}" if e.get("reason") else ""))
        if e.get("ev") == "sock" and e.get("role") == "ptr" and e.get("st") == "connect" and t_pc - 10000 <= e["pc"] <= t_pc + win_ms:
            story["connects"] += 1
    touches = [e for e in evs if e.get("ev") == "touch" and e.get("a") == "down" and abs(e["pc"] - t_pc) <= 400]
    if touches:
        story["touch_seen"] = True
        story["dispatch_ms"] = r0(touches[0]["t"] - touches[0].get("et", touches[0]["t"]))
    elif any(e.get("ev") == "touch" for e in evs):
        story["touch_seen"] = False
    keys = [e for e in evs if e.get("ev") == "key" and t_pc - 50 <= e["pc"] <= t_pc + win_ms]
    if keys:
        story["key"] = keys[0].get("name")
        story["key_st"] = keys[0].get("st")
        story["key_delay_ms"] = r0(keys[0]["pc"] - t_pc)
    story["fails"] = story["fails"][:4]
    return story


def window_events(run, a, b):
    return [e for e in run.tv if a <= e["pc"] <= b]


def blur_overlap(run, a, b):
    return any(s <= b and e >= a for s, e in run.blur)


def classify_lost(run, rec, t_pc, win, story, have_app_log):
    """Why a tap produced no key: swallowed by the rig, never produced by the app, or lost after the app sent it."""
    near = window_events(run, t_pc - 20, t_pc + win)
    if any(e["kind"] == "wake" for e in near):
        return "rig-void", "panel wake swallowed the key"
    if blur_overlap(run, t_pc - 1500, t_pc + win):
        return "rig-void", "Blank blur window overlaps the tap"
    if have_app_log and story["touch_seen"] is False:
        return "rig-void", "tap injected but the app saw no touch (swallowed by another window?)"
    if have_app_log and story["key_st"] is None and story["touch_seen"]:
        return "lost", "app got the touch but produced no key"
    if story["key_st"] == "dropped":
        return "lost", "app dropped the key (pointer session dead)"
    if story["key_st"] == "queued":
        return "lost", "app queued the key, never delivered"
    if story["key_st"] == "sent":
        return "lost", "app sent the key, TV never saw it"
    return "lost", "no key on the TV" + ("" if have_app_log else " (no app log to say why)")


def score_tap(run, rec):
    taps = inj_by_role(rec, "tap")
    retries = inj_by_role(rec, "retry")
    out = dict(kind="tap")
    if not taps:
        out["verdict"] = "void"
        return out
    key = rec.get("expect")
    win = rec.get("win_ms", 4000)
    up = taps[0]["nums"][0]
    t_pc = to_pc(rec, up)
    out["t_pc"] = t_pc
    stop = cap(rec, t_pc + win)
    if retries:
        stop = min(stop, to_pc(rec, retries[0]["nums"][0]) - 20)
    evs = [e for e in run.tv_between(t_pc - 20, stop, {"key"}) if e["key"] == key]
    evs_all = run.tv_between(t_pc - 20, cap(rec, t_pc + win + 3000))
    out["tv_events_any"] = len(evs_all)
    aev = app_events(run, rec)
    have_app = len(run.by_app.get(rec["app"].lower(), [])) > 0 and any(e.get("ev") == "touch" for e in aev)
    story = sock_story(aev, t_pc, win)
    out["story"] = story
    if retries:
        up2 = retries[0]["nums"][0]
        t2 = to_pc(rec, up2)
        e2 = [e for e in run.tv_between(t2 - 20, t2 + 3000, {"key"}) if e["key"] == key]
        out["retry_lat"] = r0(e2[0]["pc"] - t2) if e2 else None
        out["retry_after_ms"] = r0(t2 - t_pc)
    if evs:
        out["lat"] = evs[0]["pc"] - t_pc
        out["dups"] = len(evs) - 1
        v = "ok"
        if out["dups"]:
            v, out["why"] = "dup", f"{len(evs)} keydowns for one tap"
        if out["lat"] > LATE_MS:
            v, out["why"] = "late", f"{out['lat']:.0f} ms"
        out["verdict"] = v
    else:
        out["lat"] = None
        out["dups"] = 0
        out["verdict"], out["why"] = classify_lost(run, rec, t_pc, win, story, have_app)
    if blur_overlap(run, t_pc - 1500, t_pc + win) and out["verdict"] != "rig-void":
        out["flags"] = ["blur overlap"]
    return out


def score_hold(run, rec):
    h = inj_by_role(rec, "hold")
    if not h:
        return dict(kind="hold", verdict="void")
    d, u = h[0]["nums"][:2]
    t0, t1 = to_pc(rec, d), to_pc(rec, u)
    key = rec.get("expect")
    evs = [e for e in run.tv_between(t0 - 20, cap(rec, t1 + 1500), {"key"}) if e["key"] == key]
    ts = [e["t"] for e in evs]
    gaps = [round(b - a, 1) for a, b in zip(ts, ts[1:])]
    rep = gaps[1:]
    out = dict(kind="hold", n_down=len(evs), first_ms=r0(evs[0]["pc"] - t0) if evs else None, first_gap=gaps[0] if gaps else None,
               rep_med=med(rep), rep_sd=round(statistics.pstdev(rep), 1) if len(rep) > 1 else None, rep_max=max(rep) if rep else None,
               last_after_up=r0(evs[-1]["pc"] - t1) if evs else None, t_pc=t0, hold_ms=r0(t1 - t0))
    ac = app_counts(run, rec, t0 - 50, t1 + 50, "key")
    out["app_keys"] = ac or None
    out["verdict"], out["why"] = "ok", None
    if not evs:
        out["verdict"], out["why"] = "lost", "no keydown during the hold"
    elif ac and ac.get("sent", 0) > len(evs) + 1:
        out["verdict"], out["why"] = "lost", f"app sent {ac['sent']} presses, TV saw {len(evs)}"
    elif out["first_ms"] > LATE_MS:
        out["verdict"], out["why"] = "late", f"first press {out['first_ms']} ms"
    elif rep and out["rep_max"] > max(250, 3 * (out["rep_med"] or 1)):
        out["verdict"], out["why"] = "stall", f"repeat gap {out['rep_max']} ms vs median {out['rep_med']}"
    elif out["last_after_up"] is not None and out["last_after_up"] > 400:
        out["verdict"], out["why"] = "stall", f"repeats continued {out['last_after_up']} ms after release"
    return out


def score_fast(run, rec):
    f_ = inj_by_role(rec, "fast")
    if not f_:
        return dict(kind="fast", verdict="void")
    downs = f_[0]["nums"]
    seq = rec.get("seq", [])
    t0 = to_pc(rec, downs[0])
    t1 = to_pc(rec, downs[-1])
    evs = [e for e in run.tv_between(t0 - 20, cap(rec, t1 + 2000), {"key"}) if (e["key"] or "").startswith("Arrow")]
    got = [e["key"] for e in evs]
    lat = [e["pc"] - to_pc(rec, d) for e, d in zip(evs, downs)] if len(evs) == len(downs) else None
    out = dict(kind="fast", sent=len(seq), got=len(evs), in_order=got == seq, lat_max=r0(max(lat)) if lat else None,
               lat_med=r0(med(lat)) if lat else None, t_pc=t0)
    out["app_keys"] = app_counts(run, rec, t0 - 50, t1 + 300, "key") or None
    out["verdict"], out["why"] = "ok", None
    if len(evs) < len(seq):
        out["verdict"], out["why"] = "lost", f"{len(evs)}/{len(seq)} arrived"
    elif len(evs) > len(seq):
        out["verdict"], out["why"] = "dup", f"{len(evs)} keys for {len(seq)} taps"
    elif got != seq:
        out["verdict"], out["why"] = "order", "keys arrived out of order"
    elif lat and max(lat) > LATE_MS:
        out["verdict"], out["why"] = "late", f"worst {max(lat):.0f} ms"
    return out


def score_drag(run, rec):
    g = inj_by_role(rec, "drag")
    if not g:
        return dict(kind="drag", verdict="void")
    d, u, n_inj = g[0]["nums"][:3]
    t0, t1 = to_pc(rec, d), to_pc(rec, u)
    mm = run.tv_between(t0 - 20, cap(rec, t1 + 400), {"move"})
    ts = [e["t"] for e in mm]
    gaps = [round(b - a, 1) for a, b in zip(ts, ts[1:])]
    if mm and mm[0]["dx"] is not None:
        disp = (sum(e["dx"] or 0 for e in mm), sum(e["dy"] or 0 for e in mm))
    elif len(mm) > 1:
        disp = (mm[-1]["x"] - mm[0]["x"], mm[-1]["y"] - mm[0]["y"])
    else:
        disp = (0, 0)
    clicks = len(run.tv_between(t0 - 20, cap(rec, t1 + 400), {"click"}))
    out = dict(kind="drag", inj_moves=n_inj, n_move=len(mm), gap_med=med(gaps), gap_max=max(gaps) if gaps else None,
               gap_p95=pct(gaps, 95), first_move=r0(mm[0]["pc"] - t0) if mm else None,
               last_after_end=r0(mm[-1]["pc"] - t1) if mm else None, disp=[round(disp[0]), round(disp[1])], clicks=clicks, t_pc=t0)
    am = app_counts(run, rec, t0 - 50, t1 + 300, "move")
    out["app_moves"] = am or None
    out["verdict"], out["why"] = "ok", None
    if len(mm) < 3:
        out["verdict"], out["why"] = "lost", f"only {len(mm)} move frames"
    elif am and am.get("sent", 0) > len(mm) + 1:
        out["verdict"], out["why"] = "lost", f"app sent {am['sent']} move frames, TV saw {len(mm)}"
    elif out["gap_max"] > GAP_WARN_MS:
        out["verdict"], out["why"] = "stall", f"gap {out['gap_max']} ms between move frames"
    elif out["first_move"] > LATE_MS:
        out["verdict"], out["why"] = "late", f"first move {out['first_move']} ms"
    elif clicks:
        out["verdict"], out["why"] = "dup", f"{clicks} click(s) during a drag"
    return out


def score_click(run, rec):
    c = inj_by_role(rec, "click")
    if not c:
        return dict(kind="click", verdict="void")
    d, u = c[0]["nums"][:2]
    t0, t1 = to_pc(rec, d), to_pc(rec, u)
    cl = run.tv_between(t0 - 20, cap(rec, t1 + 1200), {"click"})
    out = dict(kind="click", clicks=len(cl), t_pc=t0, click_after_up=r0(cl[0]["pc"] - t1) if cl else None)
    out["verdict"], out["why"] = ("ok", None) if len(cl) == 1 else (("lost", "no click") if not cl else ("dup", f"{len(cl)} clicks"))
    return out


def score_kbd(run, rec):
    k = rec.get("kbd")
    if not k:
        return dict(kind="kbd", verdict="void")
    op = inj_by_role(rec, "open-sheet")
    out = dict(kind="kbd", **k, t_pc=to_pc(rec, op[0]["nums"][0]) if op else rec["pc_start"])
    out["verdict"], out["why"] = "ok", None
    if not k["focus"]:
        # informational: a fake that answers without currentWidget does not make the apps show the hint (only an explicit focus:false does)
        out["why"] = None
    elif k["hint_visible"]:
        out["verdict"], out["why"] = "bad", "no-field hint visible while the TV reports focus"
    elif k["delta"] != k["expect"]:
        out["verdict"], out["why"] = "lost", f"surroundingTextLength rose by {k['delta']}, expected {k['expect']}"
    return out


SCORERS = {"hold": score_hold, "fast": score_fast, "pad_click": score_click, "kbd": score_kbd}


def score_trial(run, rec):
    if rec.get("void") or "off" not in rec:
        return dict(kind="void", verdict="void", why=rec.get("void") or "no clock sync")
    sc = rec["sc"]
    try:
        if sc in TAP_LIKE:
            return score_tap(run, rec)
        if sc in DRAG_LIKE:
            return score_drag(run, rec)
        return SCORERS[sc](run, rec)
    except (KeyError, IndexError, TypeError) as e:
        return dict(kind="void", verdict="void", why=f"scorer could not read the trial ({e!r})")


def kind_of(sc):
    return "tap" if sc in TAP_LIKE else "drag" if sc in DRAG_LIKE else {"pad_click": "click"}.get(sc, sc)


def group_label(rec):
    p = rec.get("params") or {}
    sc = rec["sc"]
    bits = {"lock": f"lock {p.get('lock_s', 8)} s", "bg": f"bg {p.get('bg_s', 8)} s", "hold": f"hold {p.get('ms', 3000)} ms",
            "fast": f"fast {p.get('gap_ms', 110)} ms gap", "drag": f"drag {p.get('ms', 300)} ms", "locked_pad": f"locked pad {p.get('ms', 300)} ms",
            "idle_gap": f"idle {p.get('s', 120)} s", "wifi_blip": f"wifi blip {p.get('off_s', 5)} s"}
    return bits.get(sc, sc.replace("_", " "))


# ------------------------------------------------------------------ tables

def table(head, rows):
    if not rows:
        return []
    out = ["| " + " | ".join(head) + " |", "|" + "|".join("---" for _ in head) + "|"]
    out += ["| " + " | ".join(f(c) for c in r) + " |" for r in rows]
    return out + [""]


def build_tables(scored):
    groups = collections.OrderedDict()
    for r in scored:
        groups.setdefault((r["kind_group"], r["grp"], r["rec"]["app"]), []).append(r)
    sections = collections.OrderedDict(tap=[], hold=[], fast=[], drag=[], click=[], kbd=[])
    for (kind, grp, app), rs in groups.items():
        sc = [r["s"] for r in rs]
        real = [s for s in sc if s["verdict"] not in ("void", "rig-void")]
        n_void = sum(1 for s in sc if s["verdict"] == "void")
        n_rig = sum(1 for s in sc if s["verdict"] == "rig-void")
        if kind == "tap":
            lats = [s["lat"] for s in real if s.get("lat") is not None]
            lost = sum(1 for s in real if s["verdict"] == "lost")
            dup = sum(s.get("dups", 0) for s in real)
            late = sum(1 for s in real if s["verdict"] == "late")
            rt = [s.get("retry_lat") for s in real if "retry_lat" in s]
            ready = [s["story"]["ready_ms"] for s in real if s.get("story") and s["story"]["ready_ms"] is not None]
            sections["tap"].append([grp, app, len(sc), len(real) - lost - late - sum(1 for s in real if s["verdict"] == "dup"),
                                    lost, dup, late, n_rig, n_void, r0(med(lats)), r0(pct(lats, 95)), r0(max(lats)) if lats else None,
                                    f"{sum(1 for x in rt if x is not None)}/{len(rt)}" if rt else "-", r0(med(ready))])
        elif kind == "hold":
            sections["hold"].append([grp, app, len(sc), med([s["n_down"] for s in real]), med([s["first_ms"] for s in real if s["first_ms"] is not None]),
                                     med([s["first_gap"] for s in real if s["first_gap"] is not None]),
                                     med([s["rep_med"] for s in real if s["rep_med"] is not None]),
                                     med([s["rep_sd"] for s in real if s["rep_sd"] is not None]),
                                     max([s["rep_max"] for s in real if s["rep_max"] is not None], default=None),
                                     sum(1 for s in real if s["verdict"] != "ok"), n_void])
        elif kind == "fast":
            sections["fast"].append([grp, app, len(sc), f"{sum(s['got'] for s in real)}/{sum(s['sent'] for s in real)}",
                                     f"{sum(1 for s in real if s['in_order'])}/{len(real)}",
                                     med([s["lat_med"] for s in real if s["lat_med"] is not None]),
                                     max([s["lat_max"] for s in real if s["lat_max"] is not None], default=None), n_void])
        elif kind == "drag":
            sections["drag"].append([grp, app, len(sc), med([s["n_move"] for s in real]), min([s["n_move"] for s in real], default=None),
                                     med([abs(s["disp"][0]) + abs(s["disp"][1]) for s in real]),
                                     med([s["gap_med"] for s in real if s["gap_med"] is not None]),
                                     max([s["gap_max"] for s in real if s["gap_max"] is not None], default=None),
                                     med([s["first_move"] for s in real if s["first_move"] is not None]),
                                     sum(1 for s in real if s["n_move"] < 3), sum(s["clicks"] for s in real), n_void])
        elif kind == "click":
            sections["click"].append([grp, app, len(sc), f"{sum(s['clicks'] for s in real)}/{len(real)}", n_void])
        elif kind == "kbd":
            sections["kbd"].append([grp, app, len(sc), sum(1 for s in real if s["verdict"] == "ok"),
                                    sum(1 for s in real if s.get("hint_visible")), med([s["delta"] for s in real]), n_void])
    heads = dict(
        tap=["scenario", "app", "n", "ok", "lost", "dup", "late>500", "rig-void", "void", "lat med ms", "p95", "max", "retry ok", "ready ms med"],
        hold=["scenario", "app", "n", "presses med", "first press ms", "1st gap ms", "repeat med ms", "sd", "worst gap", "bad", "void"],
        fast=["scenario", "app", "n", "arrived", "in order", "lat med ms", "lat worst", "void"],
        drag=["scenario", "app", "n", "moves med", "moves min", "disp px med", "gap med ms", "gap worst", "first move ms", "<3 moves", "clicks", "void"],
        click=["scenario", "app", "n", "clicks/taps", "void"],
        kbd=["scenario", "app", "n", "pass", "hint shown", "delta med", "void"])
    titles = dict(tap="Single taps (lat = phone touch to TV keydown)", hold="Hold (repeat cadence)", fast="Fast taps (arrived / sent)",
                  drag="Drags (pointer move frames on the TV)", click="Touchpad click", kbd="Keyboard (TV surroundingTextLength delta)")
    out = []
    for k in sections:
        if sections[k]:
            out += [f"### {titles[k]}", ""] + table(heads[k], sections[k])
    return out


# ------------------------------------------------------------------ output

def hhmmss(pc):
    return time.strftime("%H:%M:%S", time.localtime(pc / 1000)) + f".{int(pc % 1000):03d}"


def excerpt(run, r):
    rec, s = r["rec"], r["s"]
    t = s.get("t_pc")
    L = []
    if t is None:
        return L
    L.append(f"- trial window {hhmmss(rec['pc_start'])} .. {hhmmss(rec['pc_end'])}; clock offsets: phone {rec['off']['phone_ms']} ms "
             f"(rtt {rec['off']['rtt_ms']}, spread {rec['off']['spread_ms']}), TV {'-' if rec['off'].get('tv') is None else rec['off']['tv']}")
    inj = "; ".join(f"{e['role']} {e['cmd']}" for e in rec["inj"][:6])
    L.append(f"- injected: {inj}")
    st = s.get("story")
    if st:
        L.append(f"- app story: session {st['session']}, ready {st['ready_ms']} ms before the tap, key {st['key']}/{st['key_st']}, "
                 f"reconnects {st['connects']}, touch seen {st['touch_seen']}, fails {st['fails'] or 'none'}")
    aev = app_events(run, rec)
    if aev:
        near = sorted(sorted(aev, key=lambda e: abs(e["pc"] - t))[:10], key=lambda e: e["pc"])
        L.append("- app log (10 nearest, ms relative to the tap):")
        for e in near:
            body = {k: v for k, v in e.items() if k not in ("app", "t", "ev", "pc", "r", "lc", "et")}
            L.append(f"    {e['pc'] - t:+8.0f}  {e.get('ev')} {json.dumps(body, separators=(',', ':')) if body else ''}")
    else:
        L.append("- app log: no lines near this trial")
    end = cap(rec, t + (rec.get("win_ms") or 4000))
    tvn = [e for e in run.tv_between(t - 1500, end) if e["kind"] not in ("move",)][:14]
    moves = run.tv_between(t - 200, end, {"move"})
    mv = len(moves)
    L.append(f"- TV events in the window ({len(tvn)} shown{', +' + str(mv) + ' move frames' if mv else ''}):")
    for e in tvn:
        L.append(f"    {e['pc'] - t:+8.0f}  {e['kind']} {e.get('key') or e.get('detail') or ''}")
    if not tvn:
        L.append("    none")
    if mv and s.get("kind") == "drag":
        rel = [round(e["pc"] - t) for e in moves]
        L.append(f"- move frames (ms after the touch-down): {rel[:60]}{' ...' if len(rel) > 60 else ''}")
    return L


def score_dir(d):
    run = Run(d)
    meta = run.meta
    scored = []
    order = sorted(run.trials, key=lambda r: r["pc_start"])
    for a, b in zip(order, order[1:]):
        a["cap"] = b["pc_start"] - 10
    for rec in run.trials:
        s = score_trial(run, rec)
        scored.append(dict(rec=rec, s=s, grp=group_label(rec), kind_group=kind_of(rec["sc"])))
    with open(os.path.join(d, "scored.jsonl"), "w", encoding="utf-8") as fh:
        for r in scored:
            fh.write(json.dumps(dict(id=r["rec"]["id"], app=r["rec"]["app"], sc=r["rec"]["sc"], grp=r["grp"],
                                     **{k: v for k, v in r["s"].items() if k != "story"}, story=r["s"].get("story")), default=str) + "\n")

    # ---- run-level health
    phone_offs = [r["rec"]["off"] for r in scored if "off" in r["rec"]]
    flags = []
    if run.kind == "real" and run.tv_off_windows and max(run.tv_off_windows) - min(run.tv_off_windows) > DRIFT_MS:
        flags.append(f"TV clock offset drifted {max(run.tv_off_windows) - min(run.tv_off_windows):.0f} ms across 5-min windows (> {DRIFT_MS}): latencies are biased")
    po = [o["phone_ms"] for o in phone_offs]
    if po and max(po) - min(po) > DRIFT_MS:
        flags.append(f"phone clock offset moved {max(po) - min(po):.0f} ms during the run (> {DRIFT_MS})")
    overl = [r["rec"]["id"] for r in scored if r["s"].get("t_pc") and blur_overlap(run, r["s"]["t_pc"] - 1500, r["s"]["t_pc"] + 4000)]
    if overl:
        flags.append(f"Blank blur overlapped {len(overl)} trial(s): {', '.join(overl[:8])}")
    pauses = [e for e in run.events if e.get("ev") == "pause"]
    if pauses:
        flags.append(f"{len(pauses)} pause event(s) (guard ALERT or PAUSE file)")
    for e in run.events:
        if e.get("ev") in ("injector-restart", "logcat-restart", "guard-dead"):
            flags.append(f"{e['ev']} at {hhmmss(e['pc'])}")
    zero = [r["rec"]["id"] for r in scored if r["s"].get("tv_events_any") == 0]
    if run.kind == "real" and zero and len(zero) >= 2:
        flags.append(f"{len(zero)} trials saw zero TV events of any kind (listener dead?): {', '.join(zero[:8])}")
    alerts = [l for l in run.guard if "ALERT" in l]
    polls = [l for l in run.guard if "poll failed" in l]
    if alerts:
        flags.append(f"guard raised {len(alerts)} ALERT line(s); first: {alerts[0][:110]}")
    if meta.get("injector") == "input":
        flags.append("injector fell back to `adb shell input`: phone-side stamps are estimated from the PC clock, latencies are good to about +-rtt/2 and fast sequences were skipped")
    if meta.get("abort"):
        flags.append(f"run aborted: {meta['abort'][:200]}")
    if meta.get("tv_off_failed"):
        flags.append("tv.py off did not confirm standby: CHECK THE TV")
    if not run.by_app:
        flags.append("no app log lines (builds without LgpTest): app-side columns are empty, lost trials cannot be split into rig vs app")

    skews, missing = [], 0
    if run.by_app:
        marks = {}
        for lab, evs in run.by_app.items():
            for e in evs:
                if e.get("ev") == "mark":
                    marks[(lab, e.get("id"))] = e
        for r in scored:
            rec = r["rec"]
            if rec.get("void") or "off" not in rec or not rec.get("pc_mark") or rec["sc"] == "cold":
                continue
            m = marks.get((rec["app"].lower(), rec["id"]))
            if m is None:
                missing += 1
            else:
                skews.append(m["t"] + rec["off"]["phone_ms"] - rec["pc_mark"])
        if skews and abs(med(skews)) > 150:
            flags.append(f"app mark lines sit {med(skews):.0f} ms from the runner's mark time: the phone clock join is suspect")
        if missing and len([1 for e in run.app if e.get("ev") == "mark"]):
            flags.append(f"{missing} trial(s) have no TEST_MARK line in the app log (broadcast failed or app log off)")

    n_tr = len(scored)
    bad = [r for r in scored if r["s"]["verdict"] not in ("ok",)]
    wall = (meta.get("pc_end", 0) - meta.get("pc_start", 0)) / 60000 if meta.get("pc_end") else None
    S = [f"# phonelab run {os.path.basename(d)}", ""]
    S.append(f"- matrix `{os.path.basename(meta.get('matrix', '?'))}`, tv **{run.kind}**, device {meta.get('serial')} ({meta.get('target')}), "
             f"injector **{meta.get('injector', '?')}**, wall {f(wall, 1)} min, commit {meta.get('git')}")
    for lab, info in (meta.get("apps_info") or {}).items():
        S.append(f"- {lab}: `{info['pkg']}` versionCode {info['version']}")
    st = meta.get("selftest") or {}
    if st:
        S.append("- self-test: " + "; ".join(f"{k} {v['got']}/{v['sent']} via {v['injector']}" for k, v in st.items()))
    S.append(f"- trials {n_tr}: ok {sum(1 for r in scored if r['s']['verdict'] == 'ok')}, "
             f"rig-void {sum(1 for r in scored if r['s']['verdict'] == 'rig-void')}, runner-void {sum(1 for r in scored if r['s']['verdict'] == 'void')}, "
             f"other findings {sum(1 for r in scored if r['s']['verdict'] not in ('ok', 'rig-void', 'void'))}")
    if phone_offs:
        S.append(f"- phone clock: rtt min-per-trial median {f(med([o['rtt_ms'] for o in phone_offs]), 1)} ms (p95 {f(pct([o['rtt_ms'] for o in phone_offs], 95), 1)}), "
                 f"offset range {f(max(po) - min(po), 1)} ms, worst probe spread {f(max(o['spread_ms'] for o in phone_offs), 1)} ms")
    if run.kind == "real":
        S.append(f"- TV clock: min-filter offset {f(run.tv_off, 1)} ms over {len(run.tv)} events, 5-min window range "
                 f"{f(max(run.tv_off_windows) - min(run.tv_off_windows), 1) if run.tv_off_windows else '-'} ms, "
                 f"5th-percentile packet delay above the minimum {f(getattr(run, 'tv_off_p5_gap', None), 1)} ms (latencies read about that much high on a busy LAN)")
        if run.guard:
            S.append(f"- guard: {len(polls)} poll failure(s), {len(alerts)} ALERT line(s) in this run")
    else:
        S.append(f"- faketv timeline: {len(run.tv)} events; latencies are phone touch to faketv receipt on the PC (no TV clock involved)")
    S.append(f"- app log lines: {len(run.app)}")
    S.append("")
    if flags:
        S += ["## Flags", ""] + [f"- {x}" for x in flags] + [""]
    S += ["## Results", ""] + build_tables(scored)
    # per-app roll-up of findings
    roll = collections.Counter((r["rec"]["app"], r["s"]["verdict"]) for r in scored if r["s"]["verdict"] != "ok")
    if roll:
        S += ["### Findings by app", ""] + [f"- {a}: {v} x {n}" for (a, v), n in sorted(roll.items())] + [""]
    S.append(f"Details for the {len(bad)} non-ok trial(s): `anomalies.md`; per-trial numbers: `scored.jsonl`.")
    open(os.path.join(d, "summary.md"), "w", encoding="utf-8").write("\n".join(S) + "\n")

    A = [f"# Anomalies in {os.path.basename(d)}", ""]
    if not bad:
        A.append("No anomalies.")
    for r in bad:
        rec, s = r["rec"], r["s"]
        title = f"## {rec['id']} {rec['app']} {r['grp']} (#{rec['i'] + 1}): {s['verdict'].upper()}"
        A += [title, "", f"- {s.get('why') or '-'}"]
        for k in ("lat", "retry_lat", "retry_after_ms", "dups", "n_down", "first_ms", "rep_max", "got", "sent", "n_move", "gap_max", "app_keys", "app_moves", "kbd"):
            if s.get(k) is not None and k in s:
                A.append(f"- {k}: {s[k]}")
        if s.get("flags"):
            A.append("- flags: " + ", ".join(s["flags"]))
        if s["verdict"] != "void":
            A += excerpt(run, r)
        A.append("")
    open(os.path.join(d, "anomalies.md"), "w", encoding="utf-8").write("\n".join(A) + "\n")
    return scored


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    score_dir(sys.argv[1])
    print(os.path.join(sys.argv[1], "summary.md"))
