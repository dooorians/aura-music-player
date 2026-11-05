#!/usr/bin/env python3
"""Per animation window of a trace: for each presented frame, its time from window start, interval from the previous one,
UI-thread doFrame duration and RenderThread DrawFrames duration that produced it (matched by time). Prints only windows
and frames over --over ms (interval) so the hitches of an open or a close are listed in order.
Usage: timeline.py trace.pftrace [--over 13] [--win 3]   (prints window #win only if given, else all)"""
import sys, argparse
from perfetto.trace_processor import TraceProcessor
ap = argparse.ArgumentParser(); ap.add_argument('trace'); ap.add_argument('--over', type=float, default=13.0); ap.add_argument('--win', type=int, default=-1)
a = ap.parse_args()
tp = TraceProcessor(trace=a.trace)
q = lambda s: list(tp.query(s))
up = q("select upid from process where name like 'com.ikko.aura.qa%' limit 1")[0].upid
pid = q(f"select pid from process where upid={up}")[0].pid
fr = q("""select ts, dur, display_frame_token tok from actual_frame_timeline_slice where layer_name like '%com.ikko.aura.qa/com.ikko.aura.MainActivity%' order by ts""")
ui = q(f"""select s.ts ts, s.dur dur from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) where t.tid={pid} and s.name like 'Choreographer#doFrame%' and s.depth=0""")
rt = q(f"""select s.ts ts, s.dur dur from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) where t.upid={up} and t.name='RenderThread' and s.name like 'DrawFrame%'""")
import bisect
uits = [u.ts for u in ui]; rtts = [r.ts for r in rt]
def near(lst, arr, t, back=40e6):
    i = bisect.bisect_right(arr, t) - 1
    return lst[i] if i >= 0 and t - lst[i].ts < back else None
ends = [(f.ts + f.dur) for f in fr]
wins = []; cur = [0]
for i in range(1, len(ends)):
    if (ends[i] - ends[i-1]) / 1e6 < 60: cur.append(i)
    else: wins.append(cur); cur = [i]
wins.append(cur)
k = 0
for w in wins:
    if len(w) < 20: continue
    k += 1
    if a.win >= 0 and k != a.win: continue
    t0 = ends[w[0]]
    print(f"--- window {k}: {len(w)} frames, {(ends[w[-1]]-t0)/1e6:.0f} ms")
    for j in range(1, len(w)):
        iv = (ends[w[j]] - ends[w[j-1]]) / 1e6
        if iv < a.over: continue
        t = fr[w[j]].ts
        u = near(ui, uits, t); r = near(rt, rtts, t)
        print(f"  +{(ends[w[j]]-t0)/1e6:6.0f}ms iv={iv:5.1f}  ui={u.dur/1e6 if u else float('nan'):5.1f}  rt={r.dur/1e6 if r else float('nan'):5.1f}")
