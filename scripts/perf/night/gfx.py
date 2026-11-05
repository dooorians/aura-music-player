#!/usr/bin/env python3
"""Summary rows from `dumpsys gfxinfo <pkg>` dumps (HWUI's own frame accounting for the app window).

Janky = frames that missed their deadline (Android's definition). Frame percentiles are HWUI frame durations
(intended vsync -> frame completed, pipelined: > 10.4 ms does not by itself mean a missed present).
GPU percentiles = GPU time per frame.
Usage: gfx.py LABEL_JOURNEY_gfx.txt ...  -> CSV on stdout
"""
import re, sys, os

FIELDS = [
    ('total', r'Total frames rendered: (\d+)'),
    ('janky', r'Janky frames: (\d+)'),
    ('janky_pct', r'Janky frames: \d+ \(([\d.]+)%\)'),
    ('p50', r'^50th percentile: (\d+)ms'),
    ('p90', r'^90th percentile: (\d+)ms'),
    ('p95', r'^95th percentile: (\d+)ms'),
    ('p99', r'^99th percentile: (\d+)ms'),
    ('gpu_p50', r'50th gpu percentile: (\d+)ms'),
    ('gpu_p90', r'90th gpu percentile: (\d+)ms'),
    ('gpu_p99', r'99th gpu percentile: (\d+)ms'),
    ('missed_vsync', r'Number Missed Vsync: (\d+)'),
    ('high_input_latency', r'Number High input latency: (\d+)'),
    ('slow_ui', r'Number Slow UI thread: (\d+)'),
    ('slow_draw', r'Number Slow issue draw commands: (\d+)'),
    ('deadline_missed', r'Number Frame deadline missed: (\d+)'),
]


def parse(path):
    text = open(path, errors='ignore').read()
    # Only the app-wide block (first one), not the per-window sections.
    row = {'file': os.path.basename(path).replace('_gfx.txt', '')}
    for k, rx in FIELDS:
        m = re.search(rx, text, re.M)
        row[k] = m.group(1) if m else ''
    return row


if __name__ == '__main__':
    rows = [parse(p) for p in sys.argv[1:]]
    if rows:
        keys = list(rows[0].keys())
        print(','.join(keys))
        for r in rows:
            print(','.join(r[k] for k in keys))
