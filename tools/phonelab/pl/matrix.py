"""Matrix file -> flat trial list. Rows run in file order; inside a row the apps are interleaved per trial (K, F, K, F)
so a TV hang episode hits both apps equally instead of whichever ran first."""
import itertools

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
