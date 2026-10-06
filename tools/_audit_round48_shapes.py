# -*- coding: utf-8 -*-
"""round48：把 latest.log 里每条 [rscc*] 行归一化（去数字）后归类，看「到底在刷什么」。"""
import sys, os, re, collections

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
LOG = os.path.join("run", "logs", "latest.log")
lines = open(LOG, "r", encoding="utf-8", errors="replace").read().splitlines()

rows = []
for line in lines:
    i = line.find("[rscc")
    if i < 0:
        continue
    body = line[i:]
    fam = body.split("]")[0] + "]"
    rest = body[len(fam):].strip()
    shape = re.sub(r"\(-?\d+,-?\d+,-?\d+\)", "(P)", rest)
    shape = re.sub(r"-?\d+", "N", shape)
    shape = re.sub(r"[a-z_0-9]+:[a-z_0-9/]+", "ID", shape)
    shape = re.sub(r"\s+", " ", shape).strip()
    rows.append((fam, shape, rest))

c = collections.Counter((f, s) for f, s, _ in rows)
print("=== latest.log 中 [rscc*] 行按「家族 + 归一化形状」归类（共 %d 行）===" % len(rows))
for (f, s), n in c.most_common(40):
    print("%5d  %s %s" % (n, f, s[:200]))
print()
print("=== 每族的首个实例（原文，便于核对）===")
seen = set()
for f, s, rest in rows:
    if f in seen:
        continue
    seen.add(f)
    print("%s -> %s" % (f, rest[:220]))
