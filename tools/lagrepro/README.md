# lagrepro: v1.39.1 vs master under slow-TV and bad-network conditions

Drives a debug build on the emulator against `tools/faketv/faketv.py` and scores the pointer frames the fake TV sees.

- `lagt.patch`: one log line in `MainActivity.dispatchTouchEvent` (tag `LagT`, touch-down/up event time). Apply it to each build under test so touch-down can be lined up with the TV timeline.
- `proxy.py`: delaying TCP relay in front of faketv (`--delay MS` or `--jitter MIN:MAX`, one way, in-order delivery, one extra round trip on a new connection). The emulator's own `-netdelay` / console `network delay` does not touch traffic to 10.0.2.2, so this stands in for it.
- `drive.py <v1391|master> <a|e|b|c|d>`: installs the APK, seeds prefs, starts faketv (and the proxy for e/b/c/d), runs swipes, locked drags, d-pad taps, held arrows and resume cycles through adb.
- `score.py <build>:<cond> ...`: prints the per-condition numbers.

Paths (adb, APKs, serial, scratch dir) are hard-coded at the top of `drive.py`. Results: `.plans/lag-repro-results.md`.
