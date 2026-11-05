#!/usr/bin/env python3
"""Frame statistics of Aura QA from Perfetto traces (SurfaceFlinger frame timeline + app slices).

For each trace: frames the app's main window presented, how many SurfaceFlinger classed as janky
(app deadline missed / dropped), the presented-frame cadence inside animation windows (= frames that
really reached the screen per second while something moved), and UI-thread / RenderThread time per frame.

Usage: frames.py trace.pftrace [more traces...]   -> one CSV row per trace on stdout
"""
import sys, os, statistics as st
from perfetto.trace_processor import TraceProcessor

PKG = 'com.ikko.aura.qa'
VSYNC_MS = 1000 / 96
GAP_MS = 60  # a pause longer than this ends an animation window


def pct(v, p):
    if not v:
        return float('nan')
    v = sorted(v)
    k = (len(v) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (k - lo)


def analyse(path):
    tp = TraceProcessor(trace=path)
    q = lambda s: list(tp.query(s))
    rows = q(f"""select a.ts ts, a.dur dur, a.jank_type jt, a.present_type pt, a.layer_name ln, a.upid upid
                from actual_frame_timeline_slice a
                where a.layer_name like '%{PKG}/com.ikko.aura.MainActivity%' and a.layer_name not like 'SurfaceView%'
                order by a.ts""")
    if not rows:
        return None
    upid = rows[0].upid
    pid = q(f"select pid from process where upid={upid}")[0].pid
    # One row per frame (a frame can appear once per layer instance); keep the main layer.
    ends = [(r.ts + r.dur) / 1e6 for r in rows]
    n = len(rows)
    app_jank = sum(1 for r in rows if r.jt and ('App Deadline Missed' in r.jt))
    any_jank = sum(1 for r in rows if r.jt and r.jt not in ('None', 'Buffer Stuffing'))
    dropped = sum(1 for r in rows if r.pt and 'Dropped' in r.pt)
    lat = [r.dur / 1e6 for r in rows]
    # Animation windows: consecutive presents closer than GAP_MS.
    win_frames, win_time, intervals, windows = 0, 0.0, [], 0
    seg = [ends[0]]
    def close(seg):
        nonlocal win_frames, win_time, windows
        if len(seg) >= 6:
            windows += 1
            win_frames += len(seg) - 1
            win_time += seg[-1] - seg[0]
            intervals.extend(b - a for a, b in zip(seg, seg[1:]))
    for a, b in zip(ends, ends[1:]):
        if b - a < GAP_MS:
            seg.append(b)
        else:
            close(seg); seg = [b]
    close(seg)
    fps = 1000 * win_frames / win_time if win_time else float('nan')
    long_iv = sum(1 for x in intervals if x > 1.5 * VSYNC_MS)
    ui = [r.d / 1e6 for r in q(f"""select s.dur d from slice s join thread_track tt on s.track_id=tt.id
            join thread t using(utid) where t.upid={upid} and t.tid={pid} and s.name like 'Choreographer#doFrame%'""")]
    rt = [r.d / 1e6 for r in q(f"""select s.dur d from slice s join thread_track tt on s.track_id=tt.id
            join thread t using(utid) where t.upid={upid} and t.name='RenderThread' and s.name like 'DrawFrame%'""")]
    tp.close()
    return dict(
        trace=os.path.basename(path), frames=n, windows=windows,
        win_fps=round(fps, 1),
        iv_p50=round(pct(intervals, .5), 2), iv_p95=round(pct(intervals, .95), 2), iv_p99=round(pct(intervals, .99), 2),
        long_iv_pct=round(100 * long_iv / max(len(intervals), 1), 1),
        sf_app_jank_pct=round(100 * app_jank / n, 1), sf_any_jank_pct=round(100 * any_jank / n, 1), dropped=dropped,
        lat_p50=round(pct(lat, .5), 1), lat_p95=round(pct(lat, .95), 1), lat_p99=round(pct(lat, .99), 1),
        ui_p50=round(pct(ui, .5), 2), ui_p95=round(pct(ui, .95), 2), ui_p99=round(pct(ui, .99), 2),
        rt_p50=round(pct(rt, .5), 2), rt_p95=round(pct(rt, .95), 2), rt_p99=round(pct(rt, .99), 2),
    )


if __name__ == '__main__':
    out = [analyse(p) for p in sys.argv[1:]]
    out = [o for o in out if o]
    if out:
        keys = list(out[0].keys())
        print(','.join(keys))
        for o in out:
            print(','.join(str(o[k]) for k in keys))
