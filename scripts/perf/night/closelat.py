import sys, bisect
from perfetto.trace_processor import TraceProcessor
tp = TraceProcessor(trace=sys.argv[1]); q = lambda s: list(tp.query(s))
up = q("select upid from process where name like 'com.ikko.aura.qa%' limit 1")[0].upid
pid = q(f"select pid from process where upid={up}")[0].pid
ev = q(f"select s.ts ts, s.name n from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) where t.upid={up} and s.name like 'dispatchInputEvent MotionEvent%' order by s.ts")
ups = sorted({e.ts for e in ev if 'ACTION_UP' in e.n}); downs = sorted({e.ts for e in ev if 'ACTION_DOWN' in e.n})
ui = q(f"select s.ts ts, s.dur dur from slice s join thread_track tt on s.track_id=tt.id join thread t using(utid) where t.tid={pid} and s.name like 'Choreographer#doFrame%' order by s.ts")
fr = q("select ts, dur from actual_frame_timeline_slice where layer_name like '%com.ikko.aura.qa/com.ikko.aura.MainActivity%' order by ts")
ends = [f.ts + f.dur for f in fr]
# swipes = every second DOWN/UP pair (tap, swipe): take UP events preceded by a DOWN >250 ms before (the swipes)
pairs = []
for u in ups:
    d = max([x for x in downs if x < u] or [None]) if downs else None
    if d is not None and (u - d) / 1e6 > 250: pairs.append((d, u))
res = []
for d, u in pairs:
    heavy = [((x.ts - u) / 1e6, x.dur / 1e6) for x in ui if x.dur > 20e6 and d - 50e6 < x.ts < u + 900e6]
    i = bisect.bisect_right(ends, u)
    after = [(e - u) / 1e6 for e in ends[i:i + 140]]
    # the close animation = frames after the lift at <60 ms spacing until a gap
    last = 0; prev = after[0] if after else 0
    for a in after[1:]:
        if a - prev > 60: break
        prev = a
    res.append((heavy, prev))
    print(f"swipe {(u-d)/1e6:.0f} ms: heavy frames (ms rel. to lift, dur) {[(round(a),round(b)) for a,b in heavy]}; presented frames continue until +{prev:.0f} ms after lift")
tp.close()
