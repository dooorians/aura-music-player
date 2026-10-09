#!/usr/bin/env bash
# Close behaviour with a human-speed swipe (SWIPE_MS, default 350): per close, when the heavy UI frame(s) (>20 ms) fall
# relative to the finger lifting, and how long after the lift the last presented frame of the close comes.
# Usage: closelat.sh LABEL apk
source "$(dirname "$0")/lib.sh"
L="$1"; APK="$2"; M=${SWIPE_MS:-350}
awake || exit 3
adb install -r "$APK" | tail -1; adb shell cmd package compile -m speed -f "$PKG" | tail -1
adb shell am start -n "$ACT" >/dev/null; sleep 5
CUR=cl; eval "$(sed -n '/^setup_track()/,/^}/p' "$HERE/suite.sh")"; to_root; setup_track; to_root; go_library
for i in 1 2 3; do pill_tap; adb shell input swipe 540 500 540 1000 120; sleep 1.8; done
PCFG="$HERE/perfetto_full.cfg" trace_start
for i in 1 2 3 4 5 6; do pill_tap; sleep 0.4; adb shell input swipe 540 450 540 950 $M; sleep 1.8; done
trace_stop "$OUT/traces/${L}_closelat.pftrace"
python3 "$HERE/closelat.py" "$OUT/traces/${L}_closelat.pftrace"
