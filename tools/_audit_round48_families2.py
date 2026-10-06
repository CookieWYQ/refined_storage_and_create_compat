# -*- coding: utf-8 -*-
"""round48：按「logger 名 + 消息前缀」双维度统计 latest.log 与全量历史日志里的本模组输出。"""
import sys, os, re, gzip, collections

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
LOG_DIR = os.path.join("run", "logs")
OUT = os.path.join("build", "round48_log_audit2.txt")
_rep = open(OUT, "w", encoding="utf-8")
_rp = print


def print(*a, **kw):  # noqa: A001
    _rp(*a, **kw)
    kw.pop("flush", None)
    _rp(*a, file=_rep, **kw)


# MC 日志行：[时间] [线程/级别] [logger/]: 消息
LINE = re.compile(r"^\[[^\]]*\] \[([^\]]*)/([A-Z]+)\] \[([^\]]*)/?\]:? ?(.*)$")
MOD = "rs_create_compat"
PREFIXES = ["[rscc-build]", "[rscc-diag]", "[rscc] ", "[rscc-assembly]", "[rscc-trace]",
            "[rscc-dedupe]", "[rscc-ledger]", "[rscc-range-charger]", "[rscc-intermediate-flow]",
            "[rscc-cache-source]", "[rscc-keeper]", "[rs_create_compat]", "[loader "]


def fam_of(msg):
    for p in PREFIXES:
        if msg.startswith(p):
            return p
    return "(无前缀)"


def scan(path):
    if path.endswith(".gz"):
        with gzip.open(path, "rt", encoding="utf-8", errors="replace") as fh:
            lines = fh.read().splitlines()
    else:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            lines = fh.read().splitlines()
    fam = collections.Counter()
    lvl = collections.Counter()
    for line in lines:
        if MOD not in line and "Rscc" not in line:
            continue
        m = LINE.match(line)
        if not m:
            continue
        logger = m.group(3)
        if MOD not in logger and not logger.startswith("rs_create_compat"):
            continue
        fam[fam_of(m.group(4))] += 1
        lvl[m.group(2)] += 1
    return len(lines), fam, lvl


print("=== latest.log（最近一次会话）===")
n, fam, lvl = scan(os.path.join(LOG_DIR, "latest.log"))
print("总行 %d，本模组输出 %d 行" % (n, sum(fam.values())))
for k, v in fam.most_common():
    print("   %-26s %6d" % (k, v))
print("   级别分布: %s" % dict(lvl))
print()

print("=== 全部历史日志合计 ===")
allfam = collections.Counter()
alllvl = collections.Counter()
total = 0
for name in sorted(os.listdir(LOG_DIR)):
    if not (name.endswith(".log") or name.endswith(".log.gz")):
        continue
    if name.startswith("debug"):
        continue
    _n, f, l = scan(os.path.join(LOG_DIR, name))
    allfam.update(f)
    alllvl.update(l)
    total += sum(f.values())
print("本模组输出合计 %d 行" % total)
for k, v in allfam.most_common():
    print("   %-26s %6d" % (k, v))
print("   级别分布: %s" % dict(alllvl))
print("=== [rscc-keeper] / [loader] 细分（全量历史）===")
SUB = {
    "[rscc-keeper] 开始销毁过量": "开始销毁过量",
    "[rscc-keeper] 过量已清完": "过量已清完",
    "[rscc-keeper] 停止销毁过量": "停止销毁过量",
    "[rscc-keeper] 生效目标被钳制": "生效目标被钳制",
    "[rscc-keeper] 让位给": "让位给同网络更权威",
    "[rscc-keeper] 恢复自主控制": "恢复自主控制",
    "[loader] work": "[loader work]",
    "[loader] waiting": "[loader waiting",
    "[loader] cache": "[loader cache",
    "[loader] standalone": "[loader standalone",
    "[loader] cluster": "[loader cluster",
    "[loader] printer": "[loader printer",
}
sub = collections.Counter()
for name in sorted(os.listdir(LOG_DIR)):
    if not (name.endswith(".log") or name.endswith(".log.gz")) or name.startswith("debug"):
        continue
    path = os.path.join(LOG_DIR, name)
    if path.endswith(".gz"):
        with gzip.open(path, "rt", encoding="utf-8", errors="replace") as fh:
            ls = fh.read().splitlines()
    else:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            ls = fh.read().splitlines()
    for line in ls:
        for label, token in SUB.items():
            if token in line:
                sub[label] += 1
for k, v in sub.most_common():
    print("   %-32s %7d" % (k, v))
print()
_rep.close()
