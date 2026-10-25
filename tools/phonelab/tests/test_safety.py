"""Safety rules of the kit: nothing here touches a device, a TV or the network."""
import asyncio, json, os, sys, tempfile, types, unittest
from unittest import mock

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, HERE)
import score
from pl import device as D
from pl import matrix as M
from pl import ports as P
from pl import runner as R
from pl import tvlevels as L
from pl.util import AbortRun


def trials(rows, apps=("K",)):
    return M.expand(dict(rows=rows), list(apps))[1]


class RealTvRefusals(unittest.TestCase):
    def bad(self, *rows):
        return M.real_tv_problems(trials(list(rows)))

    def test_ok_keys_refused(self):
        for key in ("ok", "OK", "enter", "btn_ok"):
            self.assertTrue(self.bad(dict(sc="tap", key=key)), key)
            self.assertTrue(self.bad(dict(sc="hold", key=key)), key)

    def test_fast_pattern(self):
        self.assertTrue(self.bad(dict(sc="fast", pattern="up,down,ok")))
        self.assertTrue(self.bad(dict(sc="fast", pattern="left,Enter")))
        self.assertFalse(self.bad(dict(sc="fast", pattern="up,down,left,right")))

    def test_scenarios_refused(self):
        self.assertTrue(self.bad(dict(sc="pad_click")))
        self.assertTrue(self.bad(dict(sc="padclick")))
        self.assertTrue(self.bad(dict(sc="kbd", text="hello")))
        self.assertTrue(self.bad(dict(sc="keyboard")))

    def test_shipped_real_matrices_pass(self):
        for name in ("smoke", "full"):
            _, t = M.expand(os.path.join(HERE, "matrices", name + ".yaml"), ["K", "F"])
            self.assertEqual(M.real_tv_problems(t), [], name)

    def test_defaults_pass(self):
        self.assertFalse(self.bad(dict(sc="tap"), dict(sc="hold"), dict(sc="fast"), dict(sc="lock"), dict(sc="locked_pad")))

    def test_wifi_blip_never_on_phone(self):
        self.assertTrue(M.phone_problems(trials([dict(sc="wifi_blip")])))
        self.assertFalse(M.phone_problems(trials([dict(sc="tap")])))

    def test_runtime_backstop(self):
        ctx = types.SimpleNamespace(truth=types.SimpleNamespace(kind="real"))
        with self.assertRaises(AbortRun):
            R.real_dirs_only(ctx, "ok")
        R.real_dirs_only(ctx, "up", "right")
        R.real_dirs_only(types.SimpleNamespace(truth=types.SimpleNamespace(kind="fake")), "ok")


class FakeInj:
    kind = "inj"

    def __init__(self, fail_home=False):
        self.lines, self.fail_home = [], fail_home

    def cmd(self, line):
        self.lines.append(line)
        if self.fail_home and line == "K 3":
            raise D.InjectorDead("gone")
        return f"{line[0]} 100 140", 1.0, 2.0


class FakeDev:
    phone = True

    def __init__(self):
        self.calls, self.last_cmd = [], 0

    def adb(self, *a, **k):
        self.calls.append(a)
        return ""

    def gate_if_idle(self, *_):
        pass

    def gate(self):
        self.calls.append(("gate",))

    def screen_size(self):
        return 1080, 2400


def fake_ctx(**kw):
    ctx = R.Ctx()
    ctx.inj, ctx.dev = FakeInj(), FakeDev()
    ctx.truth = types.SimpleNamespace(kind="fake", alert=lambda: False)
    ctx.pin = "123456"
    ctx.cfg = {}
    for k, v in kw.items():
        setattr(ctx, k, v)
    return ctx


class PinNeverRecorded(unittest.TestCase):
    def test_pin_role_is_masked(self):
        ctx = fake_ctx()
        rec = dict(inj=[])
        app = types.SimpleNamespace(screen=(1080, 2400))
        with mock.patch.object(R.time, "sleep"):
            R.unlock_phone(ctx, rec, app)
        pins = [e for e in rec["inj"] if e["role"] == "pin"]
        self.assertEqual(len(pins), 6)
        self.assertTrue(all(e["cmd"] == "K *" for e in pins))
        self.assertNotIn("123456", json.dumps(rec))
        # the digits did reach the injector
        self.assertEqual([l for l in ctx.inj.lines if l.startswith("K ")], ["K 224"] + [f"K {7 + int(d)}" for d in "123456"])
        self.assertIn(("gate",), ctx.dev.calls)

    def test_other_commands_keep_their_text(self):
        ctx, rec = fake_ctx(), dict(inj=[])
        e = R.inject(ctx, rec, "T 10 20", "tap")
        self.assertEqual(e["cmd"], "T 10 20")


class LeaveLock(unittest.TestCase):
    def test_force_stop_even_when_home_fails_and_never_taps(self):
        ctx = fake_ctx()
        ctx.inj = FakeInj(fail_home=True)
        app = types.SimpleNamespace(pkg="com.x", xy={"pad_exit": dict(x=1, y=2)})
        with mock.patch.object(R.time, "sleep"):
            R.leave_lock(ctx, dict(inj=[]), app)
        self.assertIn(("shell", "am", "force-stop", "com.x"), ctx.dev.calls)
        self.assertFalse([l for l in ctx.inj.lines if l.startswith("T ")])
        self.assertEqual(ctx.after_lock, {"com.x"})

    def test_failed_trial_still_leaves(self):
        ctx = fake_ctx()
        app = types.SimpleNamespace(pkg="com.x", xy={"touchpad": dict(x=5, y=5)}, screen=(1080, 2400), label="K")
        rec = dict(inj=[], i=0)
        with mock.patch.object(R, "prep"), mock.patch.object(R, "center_pointer"), mock.patch.object(R, "engage_lock"), \
                mock.patch.object(R, "arm", side_effect=R.Skip("boom")), mock.patch.object(R.time, "sleep"):
            with self.assertRaises(R.Skip):
                R.sc_locked_pad(ctx, rec, app)
        self.assertIn(("shell", "am", "force-stop", "com.x"), ctx.dev.calls)

    def test_next_trial_resets(self):
        ctx = fake_ctx(after_lock={"com.k"})
        ctx.apps = {}
        app = types.SimpleNamespace(pkg="com.f")
        with mock.patch.object(R.time, "sleep"):
            R.reset_after_lock(ctx, app)
        stopped = {c[3] for c in ctx.dev.calls if c[:3] == ("shell", "am", "force-stop")}
        self.assertEqual(stopped, {"com.k", "com.f"})
        self.assertEqual(ctx.after_lock, set())

    def test_pad_click_refused_on_real_tv(self):
        ctx = fake_ctx()
        ctx.truth.kind = "real"
        with self.assertRaises(R.Skip):
            R.sc_pad_click(ctx, dict(inj=[]), types.SimpleNamespace())


class PhoneRules(unittest.TestCase):
    def dev(self):
        return D.Dev("192.168.1.5:5555", phone=True)

    def test_padb_75_is_paused_and_76_aborts(self):
        for rc, exc in ((75, D.Paused), (76, AbortRun)):
            with mock.patch.object(D.subprocess, "run", return_value=types.SimpleNamespace(returncode=rc, stdout=b"")):
                with self.assertRaises(exc):
                    self.dev().adb("shell", "am", "get-current-user")

    def test_gate_uses_a_verb_padb_refuses_after_a_wait(self):
        d = self.dev()
        with mock.patch.object(D.subprocess, "run", return_value=types.SimpleNamespace(returncode=0, stdout=b"0")) as m:
            d.gate()
        argv = m.call_args[0][0]
        self.assertEqual(argv[-3:], ["shell", "am", "get-current-user"])

    def test_gate_idle_only_after_a_gap(self):
        d = self.dev()
        with mock.patch.object(D.Dev, "gate") as g:
            d.last_cmd = D.time.time()
            d.gate_if_idle(2.0)
            g.assert_not_called()
            d.last_cmd = D.time.time() - 5
            d.gate_if_idle(2.0)
            g.assert_called_once()

    def test_wifi_blip_refused_on_phone(self):
        ctx = fake_ctx()
        with self.assertRaises(R.Skip):
            R.sc_wifi_blip(ctx, dict(inj=[]), types.SimpleNamespace())
        self.assertFalse(ctx.dev.calls)

    def test_wifi_comes_back_after_a_failure(self):
        ctx = fake_ctx()
        ctx.dev.phone = False
        sh = []
        ctx.dev.sh = lambda c, **k: (sh.append(c), "inet 1")[1]
        with mock.patch.object(R, "prep"), mock.patch.object(R.time, "sleep", side_effect=RuntimeError("stop")):
            with self.assertRaises(RuntimeError):
                R.sc_wifi_blip(ctx, dict(inj=[]), types.SimpleNamespace(), off_s=1)
        self.assertEqual(sh, ["svc wifi disable", "svc wifi enable"])

    def test_keyguard_still_up(self):
        ctx = fake_ctx()
        ctx.dev.keyguard_showing = lambda: True
        ctx.dev.unlock = lambda: 0
        with self.assertRaises(R.Skip):
            R.ensure_unlocked(ctx, {})
        with self.assertRaises(AbortRun):
            R.ensure_unlocked(ctx, {})
        ctx2 = fake_ctx()
        ctx2.dev.keyguard_showing = lambda: False
        ctx2.unlock_fail = 1
        R.ensure_unlocked(ctx2, {})
        self.assertEqual(ctx2.unlock_fail, 0)


class Housekeeping(unittest.TestCase):
    def ctx(self, alert, guard_alive=True, phone=True):
        ctx = fake_ctx()
        ctx.dev.phone = phone
        ctx.dev.park = lambda: ctx.dev.calls.append(("park",)) or 0
        ctx.dev.borrow = lambda: ctx.dev.calls.append(("borrow",)) or 0
        ctx.out = tempfile.mkdtemp()
        ctx.run = types.SimpleNamespace(write=lambda r: None)
        ctx.truth = types.SimpleNamespace(kind="real", alert=lambda: alert[0], guard_alive=lambda: guard_alive)
        ctx.logs_app = ctx.logs_sys = types.SimpleNamespace(alive=lambda: True)
        ctx.cfg = dict(borrow_every_s=10 ** 6)
        ctx.last_borrow = R.time.time()
        return ctx

    def test_dead_guard_aborts(self):
        with self.assertRaises(AbortRun):
            R.housekeep(self.ctx([False], guard_alive=False))

    def test_long_alert_parks_then_borrows(self):
        alert = [True]
        ctx = self.ctx(alert)
        n = [0]

        def fake_sleep(s):
            n[0] += 1
            if n[0] == 3:
                alert[0] = False

        with mock.patch.object(R, "PARK_AFTER_S", -1), mock.patch.object(R.time, "sleep", fake_sleep):
            R.housekeep(ctx)
        calls = [c[0] for c in ctx.dev.calls]
        self.assertEqual(calls.count("park"), 1)
        self.assertGreater(calls.index("borrow"), calls.index("park"))

    def test_alert_blocks_injection(self):
        ctx = fake_ctx()
        ctx.truth = types.SimpleNamespace(kind="real", alert=lambda: True)
        with self.assertRaises(R.Skip):
            R.inject(ctx, dict(inj=[]), "T 1 1", "tap")
        R.inject(ctx, dict(inj=[]), "K 3", "home", force=True)


class DarkPanel(unittest.TestCase):
    def ctx(self, off_ok):
        ctx = fake_ctx()
        ctx.truth = types.SimpleNamespace(kind="real", last_recv=lambda: R.now_ms())
        ctx.mouse = mock.Mock()
        ctx.mouse.screen_off.return_value = off_ok
        return ctx

    def test_dark_between_trials_then_prime(self):
        ctx = self.ctx(True)
        R.dark_panel(ctx)
        self.assertTrue(ctx.dark)
        self.assertTrue(R.maybe_prime(ctx))
        self.assertFalse(ctx.dark)
        self.assertFalse(R.maybe_prime(ctx))   # the last key was just now

    def test_falls_back_to_tv_py(self):
        ctx = self.ctx(False)
        with mock.patch.object(R.T, "tv_py", return_value=types.SimpleNamespace(returncode=0, stdout="")) as m:
            R.dark_panel(ctx)
        m.assert_called_once()
        self.assertEqual(m.call_args[0][0], "screen-off")

    def test_fake_tv_untouched(self):
        ctx = fake_ctx()
        ctx.mouse = mock.Mock()
        R.dark_panel(ctx)
        ctx.mouse.screen_off.assert_not_called()


class FakeRun:
    def __init__(self, tv, app):
        self._tv, self.by_app = tv, {"k": app}

    def tv_between(self, a, b, kinds=None):
        return [e for e in self._tv if a <= e["pc"] <= b and (kinds is None or e["kind"] in kinds)]


class Scorer(unittest.TestCase):
    def hold(self, n_tv, sent):
        t0 = 1_000_000.0
        rec = dict(app="K", sc="hold", expect="ArrowRight", pc_start=t0 - 500, pc_end=t0 + 6000, off=dict(phone_ms=0.0),
                   inj=[dict(role="hold", nums=[t0, t0 + 3000])])
        tv = [dict(kind="key", key="ArrowRight", pc=t0 + 40 + i * 120, t=t0 + 40 + i * 120) for i in range(n_tv)]
        app = [dict(ev="key", st="sent", t=t0 + 30 + i * 120) for i in range(sent)] + [dict(ev="touch", a="down", t=t0)]
        return score.score_hold(FakeRun(tv, app), rec)

    def test_exact_match_ok(self):
        self.assertEqual(self.hold(20, 20)["verdict"], "ok")

    def test_one_missing_is_lost(self):
        s = self.hold(19, 20)
        self.assertEqual(s["verdict"], "lost")
        self.assertIn("20", s["why"])

    def test_one_extra_is_dup(self):
        self.assertEqual(self.hold(21, 20)["verdict"], "dup")

    def test_no_app_count_no_check(self):
        self.assertIsNone(score.hold_count_verdict(20, {}))
        self.assertIsNone(score.hold_count_verdict(20, None))

    def test_resume_miss_column(self):
        story = dict(touch_seen=False, key_st=None)
        run = types.SimpleNamespace(tv=[], blur=[], tv_between=lambda a, b, kinds=None: [])
        for sc, want in (("lock", "resume-miss"), ("bg", "resume-miss"), ("cold", "resume-miss"), ("tap", "rig-void"), ("idle_gap", "rig-void")):
            v, _ = score.classify_lost(run, dict(sc=sc), 1000.0, 4000, story, True)
            self.assertEqual(v, want, sc)

    def test_resume_miss_is_not_counted_ok(self):
        rec = dict(app="K", sc="lock", params={}, i=0, id="t1", pc_start=0, pc_end=1, void=None, inj=[])
        r = dict(rec=rec, s=dict(verdict="resume-miss", kind="tap", lat=None, dups=0, story=dict(ready_ms=None)), kind_group="tap", grp="lock 8 s")
        txt = "\n".join(score.build_tables([r]))
        self.assertIn("resume-miss", txt)
        head = [l for l in txt.splitlines() if l.startswith("| scenario")][0].split("|")[1:-1]
        row = [l for l in txt.splitlines() if l.startswith("| lock 8 s")][0].split("|")[1:-1]
        cols = dict(zip([h.strip() for h in head], [c.strip() for c in row]))
        self.assertEqual(cols["ok"], "0")
        self.assertEqual(cols["resume-miss"], "1")
        self.assertEqual(cols["rig-void"], "0")


class FakeSession:
    def __init__(self, vol=31, light=80):
        self.vol, self.light = vol, light

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        pass

    async def call(self, uri, payload=None):
        if uri == L.VOL_GET:
            return dict(type="response", payload=dict(returnValue=True, volumeStatus=dict(volume=self.vol, muteStatus=False)))
        if uri == L.SET_GET:
            return dict(type="response", payload=dict(returnValue=True, settings=dict(backlight=str(self.light))))
        if uri == L.VOL_SET:
            self.vol = payload["volume"]
        elif uri == L.SET_SET:
            self.light = int(payload["settings"]["backlight"])
        else:
            raise AssertionError(uri)
        return dict(type="response", payload=dict(returnValue=True))


class TvLevels(unittest.TestCase):
    def test_note_zero_restore_and_crash_note_survives(self):
        note = os.path.join(tempfile.mkdtemp(), "note.json")
        s = FakeSession()
        with mock.patch.object(L, "_session", lambda: asyncio.sleep(0, result=s)):
            self.assertEqual(L.note_and_zero(note), dict(volume=31, backlight=80))
            self.assertEqual((s.vol, s.light), (0, 0))
            # a second run after a crash must keep the first originals, not note the zeros
            self.assertEqual(L.note_and_zero(note), dict(volume=31, backlight=80))
            self.assertTrue(L.restore(note))
            self.assertEqual((s.vol, s.light), (31, 80))
        self.assertFalse(os.path.exists(note))
        self.assertTrue(L.restore(note))

    def test_unreadable_levels_abort(self):
        class Blind(FakeSession):
            async def call(self, uri, payload=None):
                return dict(type="error", payload={})

        note = os.path.join(tempfile.mkdtemp(), "note.json")
        with mock.patch.object(L, "_session", lambda: asyncio.sleep(0, result=Blind())):
            with self.assertRaises(Exception):
                L.note_and_zero(note)
        self.assertFalse(os.path.exists(note))


class FakePort(unittest.TestCase):
    free = staticmethod(lambda host, port: False)

    def test_default_port_is_unchanged(self):
        p = P.plan_faketv(True, 3001, "10.0.2.2", self.free)
        self.assertEqual((p["bind"], p["device_host"], p["faketv_args"], p["reverse"]), ("0.0.0.0", "10.0.2.2", [], []))

    def test_emulator_alt_port_reverses_both_ports(self):
        p = P.plan_faketv(True, 3101, "10.0.2.2", self.free)
        self.assertEqual(p["device_host"], "127.0.0.1")
        self.assertEqual(p["faketv_args"], ["--port", "3101", "--icon-port", "3102"])
        self.assertEqual(p["reverse"], [(3001, 3101), (3102, 3102)])

    def test_taken_default_port_is_refused_with_the_way_out(self):
        taken = lambda host, port: port == 3001
        with self.assertRaises(SystemExit) as e:
            P.plan_faketv(True, 3001, "10.0.2.2", taken)
        self.assertIn("--fake-port", str(e.exception))
        with self.assertRaises(SystemExit) as e:
            P.plan_faketv(False, 3001, "192.168.1.5", taken)
        self.assertNotIn("Rerun", str(e.exception))

    def test_phone_never_gets_an_alt_port(self):
        with self.assertRaises(SystemExit):
            P.plan_faketv(False, 3101, "192.168.1.5", self.free)

    def test_alt_port_checks(self):
        for bad in (80, 3002, 65535):
            with self.assertRaises(SystemExit, msg=bad):
                P.plan_faketv(True, bad, "10.0.2.2", self.free)
        with self.assertRaises(SystemExit):
            P.plan_faketv(True, 3101, "10.0.2.2", lambda host, port: port == 3102)


if __name__ == "__main__":
    unittest.main()
