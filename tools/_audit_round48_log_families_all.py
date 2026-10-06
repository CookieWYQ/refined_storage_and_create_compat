# -*- coding: utf-8 -*-
"""round48 日志审计（补充）：把 run/logs 下全部（含 .gz 历史）日志的族行数一起统计。"""
import sys, os, re, gzip, collections

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

LOG_DIR = os.path.join("run", "logs")
FAMILIES = [
    "rscc-build", "rscc-diag", "rscc-assembly", "rscc-trace", "rscc-dedupe",
    "rscc-ledger", "rscc-range-charger", "rscc-intermediate-flow", "rscc-cache-source",
    "rscc-group",
]
EVENTS = ["bus_push", "bus_skip", "bus_share_skip", "take_to_chamber", "take_from_machine",
          "handover_to_chamber", "push_stalled", "unblock_recovered", "unbalanced",
          "pull_hold", "pull_rejected", "net_down", "summary window=5s", "same cause repeated",
          "reject {", "reason=", "stuck {", "net_up", "claim", "fetch"]


def read_all(path):
    if path.endswith(".gz"):
        with gzip.open(path, "rt", encoding="utf-8", errors="replace") as fh:
            return fh.read().splitlines()
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        return fh.read().splitlines()


def main():
    out = open(os.path.join("build", "round48_log_audit.txt"), "w", encoding="utf-8")
    global print
    real_print = print

    def print(*a, **kw):  # noqa: A001 - 双写：屏幕 + 报告文件
        real_print(*a, **kw)
        kw.pop("flush", None)
        real_print(*a, file=out, **kw)

    fam = collections.Counter()
    ev = collections.Counter()
    per_file = []
    files = sorted(os.listdir(LOG_DIR), key=lambda n: os.path.getmtime(os.path.join(LOG_DIR, n)))
    for name in files:
        if not (name.endswith(".log") or name.endswith(".log.gz")):
            continue
        if name.startswith("debug"):
            continue
        path = os.path.join(LOG_DIR, name)
        lines = read_all(path)
        local = collections.Counter()
        for line in lines:
            if "[rscc" not in line:
                continue
            for f in FAMILIES:
                if "[" + f + "]" in line:
                    fam[f] += 1
                    local[f] += 1
            for e in EVENTS:
                if e in line:
                    ev[e] += 1
        per_file.append((name, len(lines), local))
    print("=== 有本模组日志的文件（只列非空）===")
    for name, n, local in per_file:
        if local:
            print("%-26s 总行=%6d  %s" % (name, n, dict(local.most_common())))
    print()
    print("=== 全部日志合计：族排名 ===")
    for f, n in fam.most_common():
        print("%-28s %7d" % (f, n))
    print()
    print("=== 全部日志合计：事件 token 排名 ===")
    for e, n in ev.most_common():
        print("%-28s %7d" % (e, n))
    out.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
