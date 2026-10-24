import sys, os, json, statistics

SP = os.path.dirname(os.path.abspath(__file__))


def pct(v, p):
    v = sorted(v)
    if not v:
        return float("nan")
    k = (len(v) - 1) * p
    f = int(k)
    c = min(f + 1, len(v) - 1)
    return v[f] + (v[c] - v[f]) * (k - f)


def load(build, cond):
    d = os.path.join(SP, "runs", f"{build}_{cond}")
    ev = json.load(open(os.path.join(d, "events.json")))
    frames = []  # (t, kind, a, b, port)
    extra = {"hang": 0, "ptr_open": 0, "reg": 0}
    for line in open(os.path.join(d, "timeline.txt")):
        p = line.split()
        if len(p) < 2:
            continue
        t = float(p[0])
        if p[1] == "ptr":
            frames.append((t, p[2], p[3] if len(p) > 3 else "", p[4] if len(p) > 4 else ""))
        elif p[1] == "conn-hang":
            extra["hang"] += 1
        elif p[1] == "ptr-open":
            extra["ptr_open"] += 1
        elif p[1] == "cmd" and p[2] == "register":
            extra["reg"] += 1
    downs, ups = [], []
    offs = [ev["offset"], ev["offset2"], ev["offset3"], ev["offset4"]]
    for line in open(os.path.join(d, "lagt.txt")):
        p = line.split()
        if len(p) == 2 and p[0] in ("0", "1"):
            (downs if p[0] == "0" else ups).append(int(p[1]) / 1000.0)
    return ev, frames, downs, ups, offs, extra


def to_host(dev_t, offs, phases_t):
    return dev_t + offs[phases_t]


def bunch(ts):
    """gaps > 100 ms and frames bunched (within 5 ms of previous) right after such a gap"""
    gaps = 0
    bunched = 0
    bursts = 0
    i = 1
    while i < len(ts):
        if ts[i] - ts[i - 1] > 0.100:
            gaps += 1
            j = i
            n = 0
            while j + 1 < len(ts) and ts[j + 1] - ts[j] <= 0.005:
                n += 1
                j += 1
            if n:
                bursts += 1
                bunched += n + 1
            i = j + 1
        else:
            i += 1
    return gaps, bursts, bunched


def score(build, cond):
    ev, frames, downs_d, ups_d, offs, extra = load(build, cond)
    off = statistics.median(offs)
    downs = [d + off for d in downs_d]
    ups = [u + off for u in ups_d]
    R = {"extra": extra, "offset_spread_ms": round((max(offs) - min(offs)) * 1000)}
    phases = ev["phases"]

    # which DOWN belongs to a phase: first DOWN at/after t0-0.2 (host), because the shell command start precedes the real touch
    def first_after(lst, t, hi):
        for x in lst:
            if t - 0.05 <= x <= hi:
                return x
        return None

    # swipes
    for dur in (300, 900):
        score_swipes(R, [p for p in phases if p["kind"] == "swipe" and p["dur"] == dur], dur, downs, frames, first_after)
    return score_rest(R, phases, downs, ups, frames)


def score_swipes(R, sw, dur, downs, frames, first_after):
    lat, loss, nofirst = [], [], 0
    for p in sw:
        hi = p["t1"] + 2.4
        td = first_after(downs, p["t0"], p["t1"] + 0.5)
        mv = [f for f in frames if f[1] == "move" and p["t0"] - 0.1 <= f[0] <= hi]
        if td is None:
            continue
        if not mv:
            nofirst += 1
            loss.append(100.0)
            continue
        lat.append((mv[0][0] - td) * 1000)
        sx = sum(int(f[2]) for f in mv)
        sy = sum(int(f[3]) for f in mv)
        exp = p["dx"] if p["dx"] else p["dy"]
        got = sx if p["dx"] else sy
        loss.append(max(0.0, 100.0 * (1 - got / exp)))
    R[f"swipe{dur}_first_frame_ms(med,p90,max)"] = (round(statistics.median(lat)) if lat else None, round(pct(lat, .9)) if lat else None, round(max(lat)) if lat else None)
    R[f"swipe{dur}_loss_pct(mean,max)"] = (round(statistics.mean(loss), 1), round(max(loss), 1))
    R[f"swipe{dur}_no_frame"] = nofirst


def score_rest(R, phases, downs, ups, frames):
    def first_after(lst, t, hi):
        for x in lst:
            if t - 0.05 <= x <= hi:
                return x
        return None

    # drags (locked)
    dr = [p for p in phases if p["kind"] == "drag"]
    mg, gp, bu, bf, tot, lat_d = [], [], [], [], [], []
    for p in dr:
        td = first_after(downs, p["t0"], p["t1"] + 0.5)
        up = first_after(ups, p["t1"] - 0.6, p["t1"] + 1.0)
        if td is None:
            continue
        end = up if up else p["t0"] + 5.3
        mv = [f for f in frames if f[1] == "move" and td - 0.05 <= f[0] <= end + 1.0]
        ts = [f[0] for f in mv]
        if len(ts) < 2:
            mg.append(5000); continue
        gaps_ms = [(b - a) * 1000 for a, b in zip(ts, ts[1:])]
        mg.append(max(gaps_ms))
        lat_d.append((ts[0] - td) * 1000)
        g, b, f = bunch(ts)
        gp.append(g); bu.append(b); bf.append(f)
        tot.append(sum(int(x[2]) for x in mv))
    R["drag_max_gap_ms"] = [round(x) for x in mg]
    R["drag_gaps_gt100"] = gp
    R["drag_bunch_bursts"] = bu
    R["drag_bunched_frames"] = bf
    R["drag_dx_total(exp600)"] = tot
    R["drag_first_frame_ms"] = [round(x) for x in lat_d]

    # taps
    tp = [p for p in phases if p["kind"] == "tap"]
    btn = [f for f in frames if f[1] == "button" or f[1] == "click"]
    used = set()
    lats, lost = [], 0
    w0 = tp[0]["t0"] - 0.1
    w1 = tp[-1]["t1"] + 1.0
    for p in tp:
        td = first_after(downs, p["t0"], p["t1"] + 0.5)
        if td is None:
            lost += 1; continue
        hit = None
        for k, f in enumerate(btn):
            if k not in used and f[0] >= td - 0.02 and f[0] <= td + 8:
                hit = k; break
        if hit is None:
            lost += 1
        else:
            used.add(hit)
            lats.append((btn[hit][0] - td) * 1000)
    allb = [f for f in btn if w0 <= f[0] <= w1]
    dup = max(0, len(allb) - len(tp)) if lost == 0 else max(0, len(allb) - len(used))
    R["tap_latency_ms(med,p90,max)"] = (round(statistics.median(lats)) if lats else None, round(pct(lats, .9)) if lats else None, round(max(lats)) if lats else None)
    R["taps_keys_lost"] = lost
    R["taps_keys_dup"] = len(allb) - len(used)

    # holds
    hd = [p for p in phases if p["kind"] == "hold"]
    cnt, mgap, hb = [], [], []
    for p in hd:
        td = first_after(downs, p["t0"], p["t1"] + 0.5)
        if td is None:
            continue
        fr = [f[0] for f in btn if td - 0.02 <= f[0] <= td + 2.6]
        cnt.append(len(fr))
        if len(fr) > 1:
            g2 = [(b - a) * 1000 for a, b in zip(fr[1:], fr[2:])] or [0]
            mgap.append(max(g2))
            hb.append(bunch(fr[1:])[2])
        else:
            mgap.append(2000)
        # first frame latency
    R["hold_frames_each(exp~14)"] = cnt
    R["hold_max_gap_ms"] = [round(x) for x in mgap]
    R["hold_bunched_frames"] = hb

    # resume
    rs = [p for p in phases if p["kind"] == "resume"]
    rl, rlost = [], 0
    for p in rs:
        td = first_after(downs, p["tfg"] + 0.3, p["t1"] + 0.5)
        if td is None:
            rlost += 1; continue
        hit = [f[0] for f in btn if td - 0.02 <= f[0] <= td + 10]
        if not hit:
            rlost += 1
        else:
            rl.append((hit[0] - td) * 1000)
    R["resume_first_key_ms"] = [round(x) for x in rl]
    R["resume_keys_lost"] = rlost
    return R


if __name__ == "__main__":
    for arg in sys.argv[1:]:
        b, c = arg.split(":")
        print(b, c, json.dumps(score(b, c), indent=None))
