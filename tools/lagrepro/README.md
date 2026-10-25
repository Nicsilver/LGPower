# lagrepro: v1.39.1 vs master under slow-TV and bad-network conditions

Drives a debug build on the emulator against `tools/faketv/faketv.py` and scores the pointer frames the fake TV sees.

- `lagt.patch`: one log line in `MainActivity.dispatchTouchEvent` (tag `LagT`, touch-down/up event time). Apply it to each build under test so touch-down can be lined up with the TV timeline.
- `proxy.py`: delaying TCP relay in front of faketv (`--delay MS` or `--jitter MIN:MAX`, one way, in-order delivery, one extra round trip on a new connection). The emulator's own `-netdelay` / console `network delay` does not touch traffic to 10.0.2.2, so this stands in for it.
- `drive.py <v1391|master> <a|e|b|c|d>`: installs the APK, seeds prefs, starts faketv (and the proxy for e/b/c/d), runs swipes, locked drags, d-pad taps, held arrows and resume cycles through adb.
- `score.py <build>:<cond> ...`: prints the per-condition numbers.
- `resume.py <apk> <name> <a|b|c> <background s> [cycles] [--stale silent|abort] [--freeze] --out DIR`: first key after the app comes back from the background, one JSON line per run (latency per cycle, lost, duplicates, keep/stale verdicts from the app log). `--stale` uses faketv's `--stale-file` 1 s into the background: `silent` (every open socket stops answering), `abort` (every socket reset), `silent-ptr` (only the pointer socket goes silent while the command socket keeps answering), `silent-rst` (all go silent, then are armed just before the app returns and reset on the first thing the phone writes; `presence_after_rst_ms` is the time to the power-state resubscribe); `--freeze` holds the app in the cached-app freezer meanwhile.

`drive.py` takes the emulator from `ANDROID_SERIAL` and a custom APK from `LAGAPK` (build name `custom`); other paths (adb, scratch dir) are at its top. Results: `.plans/lag-repro-results.md`.
