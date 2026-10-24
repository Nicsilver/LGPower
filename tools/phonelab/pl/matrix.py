"""Matrix file -> flat trial list. Rows run in file order; inside a row the apps are interleaved per trial (K, F, K, F)
so a TV hang episode hits both apps equally instead of whichever ran first."""
import itertools, re

from .util import load_matrix

ALIASES = {"background": "bg", "cold_start": "cold", "idle": "idle_gap", "padclick": "pad_click", "keyboard": "kbd"}
SCENARIOS = {"tap", "hold", "fast", "drag", "lock", "bg", "cold", "locked_pad", "pad_click", "wifi_blip", "idle_gap", "kbd"}
# list-valued params that mean "one group of n trials per value" (the plan's `ms: [300, 1500]`)
CROSS = ("ms", "gap_ms", "lock_s", "bg_s", "s", "off_s")

DEFAULTS = dict(gap_s=1.5, settle_s=4.0, tap_wait_s=4.0, dup_watch_s=1.2, post_focus_ms=250, lock_key=223, app_marks=True, borrow_every_s=600)


def expand(path_or_dict, apps):
    m = path_or_dict if isinstance(path_or_dict, dict) else load_matrix(path_or_dict)
    settings = {**DEFAULTS, **(m.get("settings") or {})}
    trials = []
    for ri, row in enumerate(m["rows"]):
        row = dict(row)
        raw_sc = row.pop("sc")
        sc = ALIASES.get(raw_sc, raw_sc)
        if sc not in SCENARIOS:
            raise SystemExit(f"matrix row {ri}: unknown sc {raw_sc!r} (known: {', '.join(sorted(SCENARIOS))})")
        n = int(row.pop("n", 1))
        row_apps = row.pop("apps", None)
        row_apps = [a.strip().upper() for a in (row_apps.split(",") if isinstance(row_apps, str) else row_apps)] if row_apps else apps
        keys = [k for k in CROSS if isinstance(row.get(k), list)]
        combos = itertools.product(*[row[k] for k in keys]) if keys else [()]
        for combo in combos:
            params = {**row, **dict(zip(keys, combo))}
            for i in range(n):
                for a in row_apps:
                    trials.append(dict(row=ri, i=i, sc=sc, app=a, params=params))
    return settings, trials


# The real C4 may only ever see these (tv skill rule 1: ENTER on an unnoticed upgrade modal hits "Check now").
REAL_TV_KEYS = {"up", "down", "left", "right"}
_OK_WORDS = {"ok", "enter", "btn_ok", "select"}
REAL_TV_REFUSED_SC = {"pad_click": "a click can land on an unnoticed modal", "kbd": "typing needs a focused field and ENTER (fake TV only)"}


def _tokens(v):
    return [t for t in re.split(r"[^a-z0-9_]+", str(v).lower()) if t]


def real_tv_problems(trials):
    """Reasons the trial list must never run against the real TV; empty when it is safe. Checked at startup, before any
    device is touched."""
    out = []
    seen = set()
    for t in trials:
        sc, params = t["sc"], t["params"]
        msg = None
        if sc in REAL_TV_REFUSED_SC:
            msg = f"row {t['row']}: {sc} is refused on the real TV ({REAL_TV_REFUSED_SC[sc]})"
        else:
            for k, v in params.items():
                toks = _tokens(v) if isinstance(v, (str, list)) else []
                if k in ("key", "pattern"):
                    bad = [x for x in toks if x not in REAL_TV_KEYS]
                elif isinstance(v, str):
                    bad = [x for x in toks if x in _OK_WORDS]
                else:
                    bad = []
                if bad:
                    msg = f"row {t['row']}: {sc} {k}={v!r} would send {', '.join(sorted(set(bad)))} to the real TV (only up/down/left/right are allowed)"
                    break
        if msg and msg not in seen:
            seen.add(msg)
            out.append(msg)
    return out


def phone_problems(trials):
    """Scenarios that must never run with a real phone as the device."""
    return sorted({f"row {t['row']}: {t['sc']} is refused on a real phone (Wi-Fi off drops wireless adb)" for t in trials if t["sc"] == "wifi_blip"})
