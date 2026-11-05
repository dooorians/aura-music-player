#!/usr/bin/env python3
"""Heavy main-thread stretches in a simpleperf dump: contiguous 4 ms bins at >60% busy, merged; for each stretch of at
least --min ms, the share of its samples under each UI phase and the top app/compose frames beneath the heaviest phase.
Usage: spphases.py samples.txt [--min 20] [--n 8]"""
import re, sys, collections, argparse
ap = argparse.ArgumentParser(); ap.add_argument('f'); ap.add_argument('--min', type=float, default=20); ap.add_argument('--n', type=int, default=8); ap.add_argument('--under', default=''); ap.add_argument('--all', action='store_true'); ap.add_argument('--top', type=int, default=25)
a = ap.parse_args()
hdr = re.compile(r'^(\S.*?)\s+(\d+)/(\d+)\s+\[\d+\]\s+([\d.]+):'); frame = re.compile(r'^\s+[0-9a-f]+\s+(.*?)\s+\(([^)]*)\)\s*$')
S = []; cur = None
for line in open(a.f, errors='ignore'):
    m = hdr.match(line)
    if m:
        if cur: S.append(cur)
        ok = m.group(1).strip() == 'com.ikko.aura.qa' and m.group(2) == m.group(3)
        cur = (float(m.group(4)), []) if ok else None
        continue
    if cur is not None:
        fm = frame.match(line)
        if fm and '[kernel.kallsyms]' not in fm.group(2): cur[1].append(fm.group(1))
if cur: S.append(cur)
BIN = 4  # ms; 4 kHz => 16 samples per full bin
bins = collections.defaultdict(list)
for ts, st in S: bins[int(ts * 1000 // BIN)].append(st)
hot = sorted(b for b, v in bins.items() if len(v) >= 0.6 * 16)
stretches = []; cur = [hot[0]]
for b in hot[1:]:
    if b - cur[-1] <= 1: cur.append(b)
    else: stretches.append(cur); cur = [b]
stretches.append(cur)
phases = [('recompose', 'Recomposer.performRecompose'), ('applyChanges', 'applyChanges'), ('animation callbacks', 'Choreographer.doCallbacks'),
          ('measureAndLayout', 'AndroidComposeView.measureAndLayout'), ('draw/record', 'AndroidComposeView.dispatchDraw'),
          ('rectmanager', 'RectManager.insertOrUpdate'), ('input', 'InputEventReceiver')]
big = [s for s in stretches if (len(s) * BIN) >= a.min]
big.sort(key=lambda s: -len(s))
print(f"{len(stretches)} stretches, {len(big)} of at least {a.min:.0f} ms")
for s in big[:a.n]:
    sm = [st for b in s for st in bins[b]]
    t0 = s[0] * BIN / 1000
    print(f"\n== t={t0:.3f}s  {len(s)*BIN} ms  {len(sm)} samples")
    for name, key in phases:
        c = sum(1 for st in sm if any(key in f for f in st))
        if c: print(f"   {name:20s} {100*c/len(sm):5.1f}%")
    inc = collections.Counter()
    for st in sm:
        seen = set()
        for f in st:
            if ('ikko' in f) and f not in seen: inc[f] += 1; seen.add(f)
    for f, c in inc.most_common(6): print(f"      {100*c/len(sm):5.1f}%  {f[:100]}")

if a.under:
    sm = [st for s in big for b in s for st in bins[b]]
    sub = [st for st in sm if any(a.under in f for f in st)]
    print(f"\n#### all heavy stretches: {len(sm)} samples; under '{a.under}': {len(sub)} ({100*len(sub)/len(sm):.0f}%)")
    inc = collections.Counter()
    for st in sub:
        k = next(i for i, f in enumerate(st) if a.under in f)
        seen = set()
        for f in st[:k]:           # frames called from the matched frame (leaf side), 'ikko' or compose
            if f not in seen and ('ikko' in f or (a.all and 'androidx' in f)): inc[f] += 1; seen.add(f)
    for f, c in inc.most_common(a.top): print(f"   {100*c/len(sub):5.1f}%  {f[:105]}")
