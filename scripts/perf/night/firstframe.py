#!/usr/bin/env python3
"""The heavy first frames of the player journeys: UI-thread frames (Choreographer#doFrame) over 20 ms, with how much of
each was spent recording the draw ("Record View#draw()") and waiting for the RenderThread ("postAndWait").
Usage: firstframe.py trace.pftrace ...   -> per trace: count of >20 ms frames, their median, and median record time."""
import sys, os, statistics as st
from perfetto.trace_processor import TraceProcessor

for path in sys.argv[1:]:
    tp = TraceProcessor(trace=path)
    up = list(tp.query("select upid from process where name like 'com.ikko.aura.qa%' limit 1"))[0].upid
    pid = list(tp.query(f"select pid from process where upid={up}"))[0].pid
    frames, records, waits, seen = [], [], [], set()
    for r in tp.query(f"""select s.id, s.ts, s.dur, s.depth, s.track_id from slice s join thread_track tt on s.track_id=tt.id
                          join thread t using(utid) where t.tid={pid} and s.name like 'Choreographer#doFrame%' and s.dur>20000000 order by s.ts"""):
        if any(abs(r.ts - x) < 2e6 for x in seen):
            continue
        seen.add(r.ts)
        frames.append(r.dur / 1e6)
        k = {c.name: c.dur / 1e6 for c in tp.query(f"""select name, max(dur) dur from slice where track_id={r.track_id} and ts>={r.ts}
                and ts+dur<={r.ts + r.dur} and depth>{r.depth} and name in ('Record View#draw()','postAndWait') group by name""")}
        records.append(k.get('Record View#draw()', 0.0))
        waits.append(k.get('postAndWait', 0.0))
    tp.close()
    med = lambda v: st.median(v) if v else float('nan')
    print(f"{os.path.basename(path)[:-9]:28s} frames>20ms={len(frames):3d} median={med(frames):5.1f} max={max(frames) if frames else 0:5.1f} "
          f"record_median={med(records):5.1f} wait_median={med(waits):5.1f}")
