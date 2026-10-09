#!/usr/bin/env python3
"""A/B table for an ABBA run (labels TAG_A1 TAG_B1 TAG_B2 TAG_A2): per journey, each run and the mean of A vs B.

Metrics: frames = frames the app presented (SurfaceFlinger timeline); fps = presented frames per second inside
animation windows; long% = share of frame-to-frame intervals > 1.5 vsync inside those windows (a visible hitch);
p95/p99 = frame-to-frame interval percentiles (ms); gfxJank% = HWUI frames that missed their deadline;
rt50 = RenderThread DrawFrame p50 (ms); ui95 = UI thread doFrame p95 (ms); cpu% = app CPU over the journey (% of one core).
Means are of per-run values. Writes TAG_compare.csv next to frames.csv.
Usage: compare.py raw_dir TAG
"""
import csv, os, sys, subprocess, statistics as st

raw, tag = sys.argv[1:3]
here = os.path.dirname(os.path.abspath(__file__))
rows = [r for r in csv.DictReader(open(os.path.join(raw, 'frames.csv'))) if r['label'].split('#')[0] in
        (f'{tag}_A1', f'{tag}_A2', f'{tag}_B1', f'{tag}_B2')]


def gfx(label, j):
    f = os.path.join(raw, f'{label}_{j}_gfx.txt')
    if not os.path.exists(f):
        return {}
    out = subprocess.run(['python3', os.path.join(here, 'gfx.py'), f], capture_output=True, text=True).stdout.splitlines()
    return dict(zip(out[0].split(','), out[1].split(','))) if len(out) > 1 else {}


def cpu(label, j):
    try:
        out = subprocess.run(['python3', os.path.join(here, 'cpu.py'), raw, label, j], capture_output=True, text=True).stdout
        return float(out.split('total=')[1].split('%')[0])
    except Exception:
        return float('nan')


journeys = []
for r in rows:
    if r['journey'] not in journeys:
        journeys.append(r['journey'])
out = []
print(f"{'journey':14s} {'run':7s} {'frames':>6s} {'fps':>5s} {'long%':>6s} {'p95':>5s} {'p99':>5s} {'gfxJank%':>8s} {'rt50':>5s} {'ui95':>5s} {'cpu%':>5s}")
for j in journeys:
    per = {'A': [], 'B': []}
    for r in [x for x in rows if x['journey'] == j]:
        label = r['label']
        g = gfx(label, j)
        f = lambda k: float(r.get(k) or 'nan')
        v = dict(frames=float(r.get('frames') or 0), fps=f('win_fps'), long=f('long_iv_pct'), p95=f('iv_p95'),
                 p99=f('iv_p99'), jank=float(g.get('janky_pct') or 'nan'), rt50=f('rt_p50'),
                 ui95=f('ui_p95'), cpu=cpu(label, j))
        per['A' if '_A' in label else 'B'].append(v)
        print(f"{j:14s} {label.split('_')[-1]:7s} {v['frames']:6.0f} {v['fps']:5.1f} {v['long']:6.1f} {v['p95']:5.1f} {v['p99']:5.1f} {v['jank']:8.2f} {v['rt50']:5.1f} {v['ui95']:5.1f} {v['cpu']:5.0f}")
    for side in 'AB':
        if not per[side]:
            continue
        m = {k: st.mean(x[k] for x in per[side]) for k in per[side][0]}
        print(f"{j:14s} {'mean '+side:7s} {m['frames']:6.0f} {m['fps']:5.1f} {m['long']:6.1f} {m['p95']:5.1f} {m['p99']:5.1f} {m['jank']:8.2f} {m['rt50']:5.1f} {m['ui95']:5.1f} {m['cpu']:5.0f}")
        out.append(dict(journey=j, side=side, runs=len(per[side]), **{k: round(v, 2) for k, v in m.items()}))
    print()
with open(os.path.join(raw, f'{tag}_compare.csv'), 'w', newline='') as f:
    w = csv.DictWriter(f, fieldnames=list(out[0].keys()))
    w.writeheader(); w.writerows(out)
