#!/usr/bin/env bash
# The first opening of the player after a cold start, then the next ones: one trace per cold start (N cycles).
# Usage: firstopen.sh TAG [cycles]     -> traces/TAG_k.pftrace ; k = 1..N
source "$(dirname "$0")/lib.sh"
TAG="$1"; N=${2:-3}
for k in $(seq 1 $N); do
  awake || exit 3
  adb shell am force-stop "$PKG"; sleep 2
  adb shell am start -n "$ACT" >/dev/null; sleep ${WAIT:-8}
  CUR="$TAG/$k"; go_library
  play_set on
  trace_start; gfx_reset
  for i in 1 2 3; do pill_tap; player_close; done
  gfx_dump "$OUT/raw/${TAG}_${k}_gfx.txt"
  trace_stop "$OUT/traces/${TAG}_${k}.pftrace"
done
