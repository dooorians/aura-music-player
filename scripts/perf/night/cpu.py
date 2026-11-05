#!/usr/bin/env python3
"""CPU used by the app during a journey, from two /proc/<pid>/task/*/stat snapshots (utime+stime in clock ticks).

Usage: cpu.py raw_dir LABEL journey   -> total % of one core and the busiest threads
The journey's wall time comes from raw/LABEL_durations.txt. Clock tick = 10 ms (CONFIG_HZ=100 user ticks).
"""
import sys, collections

raw, label, journey = sys.argv[1:4]
TICK_MS = 10


def load(p):
    d = collections.Counter()
    for line in open(p, errors='ignore'):
        if '|' not in line:
            continue
        name, t = line.rstrip('\n').rsplit('|', 1)
        parts = t.split()
        if len(parts) == 2:
            d[name.strip()] += int(parts[0]) + int(parts[1])
    return d


a = load(f'{raw}/{label}_{journey}_cpu0.txt')
b = load(f'{raw}/{label}_{journey}_cpu1.txt')
dur = None
for line in open(f'{raw}/{label}_durations.txt'):
    j, s = line.split()
    if j == journey:
        dur = float(s)
delta = {k: b[k] - a.get(k, 0) for k in b}
total = sum(v for v in delta.values() if v > 0)
pct = lambda v: 100 * v * TICK_MS / 1000 / dur
top = sorted(delta.items(), key=lambda kv: -kv[1])[:6]
print(f"{label},{journey},{dur:.1f}s,total={pct(total):.0f}%," + ','.join(f"{k}={pct(v):.0f}%" for k, v in top if v > 0))
