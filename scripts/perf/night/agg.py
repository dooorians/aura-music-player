#!/usr/bin/env python3
"""Groups raw/frames.csv rows by configuration and prints each run plus the mean per configuration.

Usage: agg.py raw_dir PREFIX journey     e.g. agg.py raw X4 home_scroll
A configuration is the label without the PREFIX_ and without a trailing #n (repeat counter).
Means are of per-run values (not pooled percentiles); every run is listed so the spread is visible.
"""
import csv, sys, re, statistics as st, os

raw, prefix, journey = sys.argv[1:4]
rows = [r for r in csv.DictReader(open(os.path.join(raw, 'frames.csv')))
        if r['label'].startswith(prefix + '_') and r['journey'] == journey]
gfx = {}
import subprocess
here = os.path.dirname(os.path.abspath(__file__))
for r in rows:
    f = os.path.join(raw, f"{r['label']}_{journey}_gfx.txt")
    if os.path.exists(f):
        out = subprocess.run(['python3', os.path.join(here, 'gfx.py'), f], capture_output=True, text=True).stdout.splitlines()
        if len(out) > 1:
            gfx[r['label']] = dict(zip(out[0].split(','), out[1].split(',')))
groups = {}
for r in rows:
    cfg = re.sub(r'#\d+$', '', r['label'][len(prefix) + 1:])
    groups.setdefault(cfg, []).append(r)
keys = ['win_fps', 'long_iv_pct', 'iv_p95', 'iv_p99', 'sf_app_jank_pct', 'rt_p50', 'rt_p95', 'ui_p95']
print(f"{'config':45s} n " + ' '.join(f'{k:>9s}' for k in keys) + '  gfxJank%  gpu50')
for cfg, rs in groups.items():
    for r in rs:
        g = gfx.get(r['label'], {})
        print(f"  {r['label'][:43]:43s}   " + ' '.join(f"{float(r[k]):9.1f}" for k in keys) + f"  {g.get('janky_pct',''):>7s}  {g.get('gpu_p50','')}")
    m = lambda k: st.mean(float(r[k]) for r in rs)
    gj = [float(gfx[r['label']]['janky_pct']) for r in rs if r['label'] in gfx]
    print(f"{cfg[:45]:45s} {len(rs)} " + ' '.join(f'{m(k):9.1f}' for k in keys) + f"  {st.mean(gj) if gj else float('nan'):7.1f}")
