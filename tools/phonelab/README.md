# phonelab: fast phone and TV tests for LG Power

One command runs a whole trial matrix against the Kotlin app (`K`, `com.nic.lgpower`) and the Flutter app (`F`,
`com.nic.lgpower.flutter`), records everything, and writes two files an agent can read instead of watching a run:
`summary.md` (tables) and `anomalies.md` (log excerpts for every bad trial). Design: `.plans/fast-phone-tests.md`
(not in git). App-side log contract: `.plans/phonelab-app-log-schema.md` (the `LgpTest` lines and the `TEST_MARK` /
`TEST_WIDGETS` broadcasts, debug builds only).

## What it measures against

| TV mode | Truth for "did the key arrive" | Use |
|---|---|---|
| `--tv real` | the Blank app on the C4 reports every key and pointer event to the guard's embedded listener (`%TEMP%\tv-guard\blank-events.jsonl`) | TV-hang family, real latencies, keyboard on the real field |
| `--tv fake` | `tools/faketv/faketv.py --timeline` on this PC | everything phone-specific (lock, background, cold start, Wi-Fi blip) and fault injection (`--drop-cmd-every`), any time of day, no TV burn-in risk |

## Prerequisites

- Windows PC, Python 3.11 with `websockets` and `PyYAML` (and `Pillow` for faketv), `adb` on PATH.
- Debug builds of both apps that carry the `LgpTest` log (master after the phone-lab log merge). Release builds work for
  taps but have no app log, no `TEST_WIDGETS` (coordinates then come from `uiautomator`, slower) and no seeding via
  `run-as`, which needs `debuggable`.
- Real phone: `C:\Users\Nic\.claude\skills\adb\phone-adb.ps1` (borrow and park), `padb.py` (honours the pause tile), the
  PIN file `~/.phone-pin` for lock trials. Start `Awake` first and keep the PC awake for the whole run.
- Real TV: the `tv` skill (`tv.py`, guard, Blank app installed), its hard rules, the saved app key at
  `.plans/tv/client-key.txt` (the runner reads it, never prints it) and the helper key. Run `tv.py wake` first (it starts the
  guard) or pass `--tv-prep`.
- Emulator: `emu.ps1 up -Path <worktree> -NoWindow`, then pass its `emulator-NNNN` serial. The runner refuses to treat a
  non-emulator serial as an emulator, and refuses an emulator serial for the phone.

## Run it

```
# emulator + faketv, a few minutes (what to run after touching the kit)
python run.py --matrix matrices/emu-smoke.yaml --apps K,F --tv fake --serial emulator-5556

# real phone + faketv on this PC (device reaches faketv at the PC's LAN address; open inbound TCP 3001 and 3002)
python run.py --matrix matrices/faults-fake.yaml --apps K,F --tv fake --faketv-args "--drop-cmd-every 20"

# real phone + real C4
python run.py --matrix matrices/smoke.yaml --apps K,F --tv real --tv-prep
```

Useful flags: `--install-k/--install-f <apk>`, `--pkg-k/--pkg-f` (other package ids), `--out <dir>`, `--limit N`,
`--no-selftest`, `--tv-shots` (real TV: one screenshot before and after, panel on for a few seconds each), `--force-input` (use `adb shell input` instead of the injector), `--allow-click` (real TV only), `--keep-tv`,
`--no-park`, `--faketv-args "..."`. The run folder defaults to `runs/<timestamp>-<matrix>/` (gitignored). Progress is in
`progress.json` (done, total, void, eta); the runner prints one line per trial to stderr and then the two result paths.
Exit codes: 0 done, 4/5 aborted (self-test failed, ALERT never cleared), 1 crashed. A failed run still scores what it recorded.

If port 3001 is already taken on this PC (another faketv), the runner binds faketv to the PC's LAN address instead and
points the device there (`faketv.py --bind`).

## Matrix file

YAML or JSON. `settings` override the defaults (`gap_s` idle between trials 1.5, `settle_s` after switching apps 4,
`tap_wait_s` 4, `dup_watch_s` 1.2, `post_focus_ms` 250, `lock_key` 223, `borrow_every_s` 600). Each row is one
scenario with `n` trials per app; rows run in order and inside a row the apps alternate per trial (K, F, K, F) so a TV
hang episode hits both. A list on `ms`, `gap_ms`, `lock_s`, `bg_s`, `s` or `off_s` makes one group of `n` per value.
`apps: K` on a row limits it to one app.

| `sc` | params | what one trial does |
|---|---|---|
| `tap` | `key` (right) | steady-state tap on a d-pad button, retry once after a miss |
| `hold` | `ms` 3000, `key` | press and hold; scored on press count, first gap, repeat cadence |
| `fast` | `gap_ms` 110, `taps` 10 | back-to-back taps over up/down/left/right; arrived, order, latency |
| `drag` | `ms` 300 | drag that starts on the Touchpad button; pointer move frames on the TV |
| `lock` | `lock_s` 8 | screen off for `lock_s`, wake, unlock with the PIN, tap as soon as the app has input focus |
| `bg` | `bg_s` 8 | HOME for `bg_s`, `am start`, tap once the window has focus |
| `cold` | | force-stop, start, tap once the window has focus |
| `locked_pad` | `ms` | hold the Touchpad button to lock it, drag inside the overlay, leave |
| `pad_click` | | a tap on the locked pad overlay (one click). Real TV needs `--allow-click` |
| `idle_gap` | `s` 120 | sit idle, then tap |
| `wifi_blip` | `off_s` 5 | `svc wifi disable/enable`, then tap. Emulator or USB phone only (wireless adb would drop) |
| `kbd` | `text` hello, `expect_focus` | open the keyboard sheet, type, score from the TV's `surroundingTextLength` |

Why 8 s locks: the apps reset their sessions after 5 s idle, so 8 s covers the reset path. The lock key is
`KEYCODE_SLEEP` (223), not the power key, because the power key toggles and a wrong state would flip the wrong way.

## Reading a run

Read `summary.md` first. It has, in this order: run header (builds, injector kind, self-test, clock health), `## Flags`
(only when something is off), the result tables, findings by app. Then `anomalies.md`: one section per trial that was not
`ok`, with the verdict and reason, the app's own story (pointer session state at the tap, key sent/queued/dropped,
reconnects, close reasons), the ten nearest app log lines (ms relative to the tap) and the TV events in the window.
`scored.jsonl` has the per-trial numbers; `run.jsonl`, `app.jsonl`, `sys.jsonl`, `tv.jsonl` (or `faketv.timeline`) are the
raw recordings, and `python score.py <run dir>` rescores them.

Verdicts: `ok`; `lost` (no key, and why: app dropped it, queued it, sent it and the TV never saw it, got the touch but made
no key); `late` (over 500 ms); `dup` (extra keydowns, or a click during a drag); `order`; `stall` (hold repeat gap over
3x the median and 250 ms, or drag move gap over 150 ms); `rig-void` (Blank blur overlaps, the panel's wake swallowed the key,
or the app never saw the touch: counted separately, not as an app loss); `void` (the runner could not run the trial, with the
reason). The tap tables count a trial in `ok` only when it has no finding.

Flags worth reading: clock offset drift over 50 ms, Blank blur overlaps, pause events, guard ALERT lines, injector or
logcat restarts, trials with zero TV events (listener dead), missing app log, injector fallback.

### Clocks

Three clocks: PC, phone uptime (injector stamps and every `LgpTest` line use the same one), TV wall (Blank). The phone offset
is re-measured before every tap (median of the fastest 8 of 12 probes) and stored per trial. The TV offset is one number per
run: the minimum over all events of (PC receive time minus TV `Date.now()`), so the packet that crossed the LAN without
queueing sets it; the summary prints the 5th-percentile delay above that minimum, which is how much latencies may read
high on a busy network. Lost, duplicate and order verdicts need no offset at all. Joins use the `TEST_MARK` id in the app
log first (the scorer compares each mark's time with the runner's and flags a skew), timestamps second.

## What the runner does so nobody has to

- Self-test per app before the matrix: 10 injected taps must land on the TV, else it switches to `adb shell input` and tests
  again, else it aborts (a night of empty trials is worse than no run).
- Before every tap: waits out a Blank blur or hidden state, wakes the panel if the last key was over 8 s ago, re-syncs the
  phone clock, writes the trial mark (`Inj L` into logcat plus `TEST_MARK` to the app), checks blur again.
- Real TV: refuses to start without the guard running or with an ALERT set; during the run an ALERT (or a file named `PAUSE`
  in the run folder) pauses the matrix between trials until cleared, and aborts after 30 min. The matrix sends only
  RIGHT/UP/LEFT/DOWN, touchpad moves and, with `--allow-click`, clicks. It never sends ENTER or OK.
- Phone: `phone-adb.ps1 borrow` again every 10 minutes (stale borrow), `park` at the end or on a crash. A padb pause exit
  voids the trial and re-borrows.
- Restarts the injector or a dead logcat stream and says so in the flags.
- Real TV at the end: `tv.py off` with up to 6 attempts; if it never confirms standby the summary says to check the TV.
  The guard log of the run is copied into the run folder.

## Keyboard trials

`kbd` opens the keyboard sheet, types `text` with `adb shell input text`, presses Enter, and compares the TV's
`registerRemoteKeyboard` `surroundingTextLength` before and after (a PC-side subscriber logs every frame to `kbd.jsonl`).
It also looks for the "Select a text field on the TV first" hint in a UI dump: it must not show while the TV reports
`focus:true`. No screenshots. A real TV needs a text field focused first (the URL bar; panel on for that one step); the
trial is voided with that reason otherwise. On faketv `--kbd-focus field` (default) gives a focused field. Unverified on the
C4: whether a second registerRemoteKeyboard subscriber (this watcher, next to the app's) is accepted there.

## Files

```
run.py            runner CLI           score.py          scorer CLI
pl/               runner pieces: device (adb, injector, logcat), truth (Blank / faketv), runner (scenarios), kbd, matrix
inj/Inj.java      injector source      inj/inj.dex       built dex (6 KB); rebuild with python inj/build.py
matrices/         smoke, full, emu-smoke, all-scenarios, faults-fake, keyboard
blank/monitor.js  Blank app source with immediate event flush (see below)
extras/           measure-id.gradle from the old harness (a debuggable release as com.nic.lgpower.m)
tests/            python -m unittest tests.test_score_blank
```

Injector commands (stdin, one per line, stamps are phone uptime ms): `N` clock probe (`N <uptime> <wall>`), `T x y [hold]`,
`H x y ms`, `G x0 y0 x1 y1 ms step`, `S gap hold x,y ...` (fast sequence), `K keycode`, `L label` (marker line, tag `LgpRig`).
It calls the hidden `InputManagerGlobal.injectInputEvent`; if an Android update breaks that, the self-test catches it and the
`input` fallback takes over (slower, no fast sequences, stamps estimated from the PC clock).

### Blank monitor.js

`blank/monitor.js` flushes key and click events at once and coalesces moves within 25 ms (the tv skill's copy batches every
500 ms). It is not installed on the TV: that needs `build.ps1 -Install` in the tv skill with the TV free. The scorer works
with either version because the clock offset uses the minimum delay.

## Not covered

Real phone and real C4 runs are the first smoke run's job (`.plans/phone-phonelab-smoke.md`). Pairing (`accept-prompt`) stays a
human-checked step (tv skill rule 1). `wifi_blip` on a wireless-adb phone and `pad_click` on the C4 are off by default.
