# -*- coding: utf-8 -*-
"""round48：把 latest.log 里属于本模组但不带 [rscc*] 前缀的行归类（找漏掉的族）。"""
import sys, os, re, collections

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
LOG = os.path.join("run", "logs", "latest.log")
lines = open(LOG, "r", encoding="utf-8", errors="replace").read().splitlines()
pat = re.compile(r"\[([A-Za-z0-9_./-]+)/(INFO|WARN|ERROR|DEBUG|FATAL)\]")
out = collections.Counter()
sample = {}
for line in lines:
    if "rs_create_compat" not in line and "Rscc" not in line:
        continue
    if "[rscc" in line:
        continue
    m = pat.search(line)
    key = m.group(1) + " " + m.group(2) if m else "NO-LOGGER-PREFIX"
    out[key] += 1
    sample.setdefault(key, line[:180])
print("=== latest.log 中「本模组相关但不带 [rscc*] 前缀」的行 ===")
for k, n in out.most_common(30):
    print("%-60s %5d" % (k, n))
    print("     例: %s" % sample[k])
print()
print("=== 全部 INFO/WARN/ERROR 计数（按 level，全体）===")
lvl = collections.Counter()
for line in lines:
    m = pat.search(line)
    if m:
        lvl[m.group(2)] += 1
for k, n in lvl.most_common():
    print("%-8s %5d" % (k, n))
