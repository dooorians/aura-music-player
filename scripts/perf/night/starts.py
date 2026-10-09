#!/usr/bin/env python3
"""Cold / warm start times (am start -W TotalTime, ms) per run of an ABBA: median and range per side.
Usage: starts.py raw_dir TAG"""
import sys, os, re, statistics as st

raw, tag = sys.argv[1:3]
side = {'A': {'cold': [], 'warm': []}, 'B': {'cold': [], 'warm': []}}
for run in ('A1', 'B1', 'B2', 'A2'):
    f = os.path.join(raw, f'{tag}_{run}_starts.csv')
    if not os.path.exists(f):
        continue
    for line in open(f):
        m = re.match(r'(cold|warm),\d+,.*TotalTime: (\d+)', line)
        if m:
            side[run[0]][m.group(1)].append(int(m.group(2)))
for s in 'AB':
    for k in ('cold', 'warm'):
        v = side[s][k]
        if v:
            print(f"{s} {k:4s} n={len(v):2d} median={st.median(v):6.0f} ms  min={min(v)} max={max(v)}")
