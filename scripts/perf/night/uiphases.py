#!/usr/bin/env python3
"""Per UI frame (Choreographer#doFrame) of an animation window: time spent in animation callbacks, recomposition,
applying changes, measure+layout, and recording the draw, in ms. Windows as in timeline.py.
Usage: uiphases.py trace --win N [--skip 0] [--n 40]"""
import argparse
from perfetto.trace_processor import TraceProcessor
ap = argparse.ArgumentParser(); ap.add_argument('trace'); ap.add_argument('--win', type=int, default=1); ap.add_argument('--n', type=int, default=45)
a = ap.parse_args()
tp = TraceProcessor(trace=a.trace); q = lambda s: list(tp.query(s))
up = q("select upid from process where name like 'com.ikko.aura.qa%' limit 1")[0].upid
pid = q(f"select pid from process where upid={up}")[0].pid
fr = q("""select ts, dur from actual_frame_timeline_slice where layer_name like '%com.ikko.aura.qa/com.ikko.aura.MainActivity%' order by ts""")
ends = [(f.ts + f.dur) for f in fr]
wins = []; cur = [0]
for i in range(1, len(ends)):
    if (ends[i] - ends[i-1]) / 1e6 < 60: cur.append(i)
    else: wins.append(cur); cur = [i]
wins.append(cur)
wins = [w for w in wins if len(w) >= 20]
w = wins[a.win - 1]
t0, t1 = fr[w[0]].ts - 30e6, ends[w[-1]]
rows = q(f"""select s.id id, s.ts ts, s.dur dur, s.name name, s.depth d, s.track_id tr from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid)
             where t.tid={pid} and s.ts>={t0} and s.ts<={t1} order by s.ts""")
allf = [r for r in rows if r.name.startswith('Choreographer#doFrame')]
frames = []
for r in allf:
    if frames and r.ts < frames[-1].ts + frames[-1].dur: continue
    frames.append(r)
KEYS = [('anim', lambda n: n == 'animation'), ('recomp', lambda n: n == 'Recomposer:recompose'), ('apply', lambda n: n == 'Compose:applyChanges'),
        ('layout', lambda n: n == 'AndroidOwner:measureAndLayout'), ('record', lambda n: n.startswith('Record View#draw'))]
print(f"window {a.win}: {len(w)} presented frames; UI frames listed from window start")
print(f"{'t(ms)':>7s} {'total':>6s} {'anim':>5s} {'recomp':>6s} {'apply':>5s} {'layout':>6s} {'record':>6s}")
base = frames[0].ts if frames else t0
shown = 0
for f in frames:
    inside = [r for r in rows if r.ts >= f.ts and r.ts + r.dur <= f.ts + f.dur and r.d >= f.d and r.id != f.id]
    v = {}
    for k, fn in KEYS:
        v[k] = max([r.dur for r in inside if fn(r.name)] or [0]) / 1e6
    print(f"{(f.ts-base)/1e6:7.0f} {f.dur/1e6:6.1f} {v['anim']:5.1f} {v['recomp']:6.1f} {v['apply']:5.1f} {v['layout']:6.1f} {v['record']:6.1f}")
    shown += 1
    if shown >= a.n: break
