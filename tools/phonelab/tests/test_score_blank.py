"""Scorer against a synthetic Blank recording: the TV clock is 842 s off, packets are batched (up to 500 ms) and one is lost.
The min-filter clock offset must recover the true latencies to within a few ms. Run: python -m unittest tests.test_score_blank"""
import json, os, random, sys, tempfile, unittest

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)
import score

TV_BEHIND_MS = 842_000.0
PHONE_OFF = 1_700_000_000_000.0   # pc_ms = uptime + PHONE_OFF


def blank(ev_type, pc_true, delay, **kw):
    return dict(type=ev_type, t=round(pc_true - 5e11, 2), wall=pc_true - TV_BEHIND_MS, pc_recv=(pc_true + delay) / 1000.0, **kw)


class BlankScore(unittest.TestCase):
    def test_latency_and_loss(self):
        random.seed(3)
        d = tempfile.mkdtemp()
        trials, tv, truth = [], [], []
        t0 = PHONE_OFF + 5_000_000
        for i in range(30):
            up = 5_000_000 + i * 6000
            tap_pc = up + PHONE_OFF
            trial = dict(ev="trial", id=f"t{i:04d}", row=0, i=i, app="K", pkg="p", sc="tap", params={}, void=None, pc_start=tap_pc - 800,
                         pc_end=tap_pc + 5000, off=dict(phone_ms=PHONE_OFF, rtt_ms=3.0, spread_ms=1.0, wall_off=0, tv=None),
                         expect="ArrowRight", win_ms=4000,
                         inj=[dict(role="tap", cmd="T 1 1", op="T", nums=[up, up + 40], pc0=tap_pc, pc1=tap_pc + 5)])
            trials.append(trial)
            if i == 7:
                continue   # lost tap
            lat = random.uniform(5, 40) if i != 9 else 900.0
            tv.append(blank("keydown", tap_pc + lat, random.uniform(0, 500), keyCode=39, key="ArrowRight"))
            truth.append((i, lat))
        # some filler events with LAN-only delay so the minimum is well defined
        for k in range(50):
            tv.append(blank("mousemove", t0 + k * 100, random.uniform(0.5, 3), x=1, y=1))
        tv.sort(key=lambda e: e["pc_recv"])
        with open(os.path.join(d, "run.jsonl"), "w") as f:
            for t in trials:
                f.write(json.dumps(t) + "\n")
        with open(os.path.join(d, "tv.jsonl"), "w") as f:
            for e in tv:
                f.write(json.dumps(e) + "\n")
        json.dump(dict(tv="real", apps=["K"], serial="x", target="phone", injector="inj"), open(os.path.join(d, "meta.json"), "w"))
        scored = {r["rec"]["id"]: r["s"] for r in score.score_dir(d)}
        self.assertEqual(scored["t0007"]["verdict"], "lost")
        self.assertEqual(scored["t0009"]["verdict"], "late")
        for i, lat in truth:
            s = scored[f"t{i:04d}"]
            self.assertLess(abs(s["lat"] - lat), 5.0, (i, s["lat"], lat))
        text = open(os.path.join(d, "summary.md")).read()
        self.assertIn("lost", text)
        self.assertIn("t0007", open(os.path.join(d, "anomalies.md")).read())


if __name__ == "__main__":
    unittest.main()
