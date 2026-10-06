# -*- coding: utf-8 -*-
"""round48：检查 [rscc-keeper] / [loader] 是否真的在「逐 tick / 每秒」重复（按时间戳间隔判定）。"""
import sys, os, re, gzip, collections

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
_rep = open(os.path.join("build", "round48_rate.txt"), "w", encoding="utf-8")
_rp = print


def print(*a, **kw):  # noqa: A001
    _rp(*a, **kw)
    kw.pop("flush", None)
    _rp(*a, file=_rep, **kw)


LOG_DIR = os.path.join("run", "logs")
TS = re.compile(r"^\[[^\]]*?(\d{2}:\d{2}:\d{2})")


def lines_of(path):
    if path.endswith(".gz"):
        with gzip.open(path, "rt", encoding="utf-8", errors="replace") as fh:
            return fh.read().splitlines()
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read().splitlines()


def report(token, label):
    print("=== %s（%s）同一秒内的行数分布 ===" % (label, token))
    worst = []
    for name in sorted(os.listdir(LOG_DIR)):
        if not (name.endswith(".log") or name.endswith(".log.gz")) or name.startswith("debug"):
            continue
        sec = collections.Counter()
        for line in lines_of(os.path.join(LOG_DIR, name)):
            if token not in line:
                continue
            m = TS.match(line)
            if m:
                sec[m.group(1)] += 1
        if not sec:
            continue
        total = sum(sec.values())
        mx = max(sec.values())
        worst.append((total, mx, name, sum(1 for v in sec.values() if v >= 2)))
    worst.sort(reverse=True)
    for total, mx, name, multi in worst[:5]:
        print("   %-24s 总 %6d 行，最大同秒 %3d 行，有 %d 个「同一秒 ≥2 行」的时刻" % (name, total, mx, multi))
    print()


report("开始销毁过量", "[rscc-keeper] 销毁过量")
report("[loader work]", "[loader work]")
report("[rscc-assembly]", "[rscc-assembly]")
report("[rscc-range-charger] save", "[rscc-range-charger] save")
_rep.close()
