#!/usr/bin/env python3
"""Top-down call tree (root = Choreographer.doFrame) of the main thread's samples in the heavy stretches (>= --min ms).
Prints nodes with at least --pct % of the stretches' samples, to --depth levels, collapsing frames named like noise.
Usage: sptree.py samples.txt [--min 28] [--max 70] [--pct 3] [--depth 40]"""
import re, sys, collections, argparse
ap = argparse.ArgumentParser(); ap.add_argument('f'); ap.add_argument('--min', type=float, default=28); ap.add_argument('--max', type=float, default=80)
ap.add_argument('--pct', type=float, default=3); ap.add_argument('--depth', type=int, default=60); ap.add_argument('--root', default='Choreographer.doFrame')
a = ap.parse_args()
hdr = re.compile(r'^(\S.*?)\s+(\d+)/(\d+)\s+\[\d+\]\s+([\d.]+):'); frame = re.compile(r'^\s+[0-9a-f]+\s+(.*?)\s+\(([^)]*)\)\s*$')
S = []; cur = None
for line in open(a.f, errors='ignore'):
    m = hdr.match(line)
    if m:
        if cur: S.append(cur)
        ok = m.group(1).strip() == 'com.ikko.aura.qa' and m.group(2) == m.group(3)
        cur = (float(m.group(4)), []) if ok else None
        continue
    if cur is not None:
        fm = frame.match(line)
        if fm and '[kernel.kallsyms]' not in fm.group(2): cur[1].append(fm.group(1))
if cur: S.append(cur)
BIN = 4; bins = collections.defaultdict(list)
for ts, st in S: bins[int(ts * 1000 // BIN)].append(st[::-1])   # root first
hot = sorted(b for b, v in bins.items() if len(v) >= 0.6 * 16)
st_ = []; c = [hot[0]]
for b in hot[1:]:
    if b - c[-1] <= 1: c.append(b)
    else: st_.append(c); c = [b]
st_.append(c)
sel = [s for s in st_ if a.min <= len(s) * BIN <= a.max]
stacks = [s for run in sel for b in run for s in bins[b]]
print(f"{len(sel)} stretches of {a.min}-{a.max} ms, {len(stacks)} samples")
tree = {}
def add(node, path):
    node['n'] = node.get('n', 0) + 1
    if path:
        child = node.setdefault('c', {}).setdefault(path[0], {})
        add(child, path[1:])
root = {}
for st in stacks:
    if a.root in ' '.join(st):
        i = next(i for i, f in enumerate(st) if a.root in f)
        add(root, st[i:])
tot = root.get('n', 1)
def show(node, name, depth):
    pct = 100 * node['n'] / tot
    kids = sorted(node.get('c', {}).items(), key=lambda kv: -kv[1]['n'])
    # collapse single-child chains
    chain = [name]
    while len(kids) == 1 and kids[0][1]['n'] == node['n']:
        name, node = kids[0]; chain.append(name); kids = sorted(node.get('c', {}).items(), key=lambda kv: -kv[1]['n'])
    label = chain[-1] if len(chain) == 1 else f"{chain[0].split('.')[-1]} … {chain[-1]}"
    print(f"{'  '*depth}{pct:5.1f}%  {label[:110]}")
    if depth < a.depth:
        for k, v in kids:
            if 100 * v['n'] / tot >= a.pct: show(v, k, depth + 1)
for k, v in root.get('c', {}).items(): show(v, k, 0)
