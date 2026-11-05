#!/usr/bin/env bash
# Measures the player sheet open/close animation: avg fps, frame time and GPU time percentiles.
# Usage: scripts/perf/sheet_bench.sh [runs=8] [device=192.168.50.130:5555]
set -euo pipefail
RUNS="${1:-8}"
DEV="${2:-192.168.50.130:5555}"
PKG="com.ikko.aura.qa"
A="adb -s $DEV shell"
OUT="$(mktemp -d)"

$A "am start -n $PKG/com.ikko.aura.MainActivity" >/dev/null
sleep 2
for i in $(seq 1 "$RUNS"); do
  $A "dumpsys gfxinfo $PKG reset" >/dev/null
  $A "input tap 400 1095"            # mini player -> open
  sleep 1.6
  $A "dumpsys gfxinfo $PKG framestats" > "$OUT/open_$i.txt"
  $A "dumpsys gfxinfo $PKG reset" >/dev/null
  $A "input swipe 540 500 540 1000 120"  # swipe down -> close
  sleep 1.6
  $A "dumpsys gfxinfo $PKG framestats" > "$OUT/close_$i.txt"
done

python3 - "$OUT" <<'PY'
import sys, glob, statistics as st
d = sys.argv[1]
def parse(path):
    rows = []
    on = False
    for l in open(path):
        l = l.strip()
        if l == '---PROFILEDATA---':
            on = not on; continue
        if on and l and l[0].isdigit():
            c = l.split(',')
            if len(c) >= 23 and c[0] == '0':
                rows.append([int(x) for x in c[:23] if x])
    return rows
def pct(v, p):
    v = sorted(v); return v[min(len(v)-1, int(len(v)*p))] if v else 0
for kind in ('open', 'close'):
    fps, ft, gpu, jank, tot = [], [], [], 0, 0
    for f in sorted(glob.glob(f'{d}/{kind}_*.txt')):
        r = parse(f)
        if len(r) < 3: continue
        # longest run of frames spaced < 40 ms apart = the animation
        best, cur = [], [r[0]]
        for a, b in zip(r, r[1:]):
            if (b[20] - a[20]) / 1e6 < 40: cur.append(b)
            else:
                if len(cur) > len(best): best = cur
                cur = [b]
        if len(cur) > len(best): best = cur
        if len(best) < 5: continue
        span = (best[-1][20] - best[0][20]) / 1e9
        fps.append((len(best) - 1) / span)
        for x in best:
            t = (x[16] - x[2]) / 1e6; ft.append(t); tot += 1
            if t > 1000 / 96: jank += 1
            if x[19] > x[14]: gpu.append((x[19] - x[14]) / 1e6)
    if not fps: print(kind, 'no data'); continue
    print(f"{kind:5s} avg fps {st.mean(fps):5.1f} (min run {min(fps):4.1f}, max {max(fps):4.1f}) | "
          f"frame ms p50 {pct(ft,.5):4.1f} p90 {pct(ft,.9):4.1f} p99 {pct(ft,.99):4.1f} | "
          f"gpu ms p50 {pct(gpu,.5):4.1f} p90 {pct(gpu,.9):4.1f} | over 10.4ms: {100*jank/max(tot,1):.0f}%")
PY
