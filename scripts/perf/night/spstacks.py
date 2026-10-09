#!/usr/bin/env python3
"""Inclusive-time profile of the app's main thread and RenderThread from a `report_sample.py` text dump (simpleperf),
restricted to the busy stretches: 16 ms bins in which the thread was running for more than --busy of the bin.
Usage: spstacks.py samples.txt [--busy 0.5] [--top 40] [--thread main|render|all] [--match substr]"""
import sys, re, collections, argparse
ap = argparse.ArgumentParser(); ap.add_argument('f'); ap.add_argument('--busy', type=float, default=0.5)
ap.add_argument('--top', type=int, default=40); ap.add_argument('--thread', default='main'); ap.add_argument('--match', default='')
a = ap.parse_args()
hdr = re.compile(r'^(\S.*?)\s+(\d+)/(\d+)\s+\[\d+\]\s+([\d.]+):')
frame = re.compile(r'^\s+[0-9a-f]+\s+(.*?)\s+\(([^)]*)\)\s*$')
samples = []   # (t, tuple(frames))
intern = {}
cur = None
want = {'main': ('com.ikko.aura.qa',), 'render': ('RenderThread',), 'all': ('com.ikko.aura.qa', 'RenderThread')}[a.thread]
pid = None
with open(a.f, errors='ignore') as fh:
    for line in fh:
        m = hdr.match(line)
        if m:
            if cur: samples.append(cur)
            name, p, t, ts = m.group(1), int(m.group(2)), int(m.group(3)), float(m.group(4))
            ok = name.strip() in want and (name.strip() != 'com.ikko.aura.qa' or p == t)
            cur = (ts, []) if ok else None
            continue
        if cur is not None:
            fm = frame.match(line)
            if fm and '[kernel.kallsyms]' not in fm.group(2):
                cur[1].append(fm.group(1))
if cur: samples.append(cur)
print(f"{len(samples)} samples of {a.thread}")
bins = collections.Counter(int(ts * 1000 // 16) for ts, _ in samples)
full = max(bins.values())
hot = {b for b, c in bins.items() if c >= a.busy * 64}
sel = [(ts, st) for ts, st in samples if int(ts * 1000 // 16) in hot]
print(f"{len(hot)} busy bins (>{a.busy:.0%} of 16 ms), {len(sel)} samples")
inc = collections.Counter(); self_ = collections.Counter()
for ts, st in sel:
    seen = set()
    for f in st:
        if f not in seen:
            inc[f] += 1; seen.add(f)
    if st: self_[st[0]] += 1
n = len(sel)
print("--- inclusive")
shown = 0
for f, c in inc.most_common(400):
    if a.match and a.match not in f: continue
    print(f"{100*c/n:5.1f}%  {f[:110]}")
    shown += 1
    if shown >= a.top: break
print("--- self")
for f, c in self_.most_common(15): print(f"{100*c/n:5.1f}%  {f[:110]}")
