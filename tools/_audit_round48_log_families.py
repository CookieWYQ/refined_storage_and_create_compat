# -*- coding: utf-8 -*-
"""round48 日志审计：统计 run/logs/latest.log 里各日志族的实际行数（只读）。"""
import sys, os, re, collections

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

LOG = os.path.join("run", "logs", "latest.log")

# 族 → 正则（按前缀 / 事件 token 归类）
FAMILIES = [
    ("[rscc-build]", r"\[rscc-build\]"),
    ("[rscc-diag]", r"\[rscc-diag\]"),
    ("[rscc] (锚点)", r"\[rscc\] "),
    ("[rscc-assembly]", r"\[rscc-assembly\]"),
    ("[rscc-trace]", r"\[rscc-trace\]"),
    ("[rscc-dedupe]", r"\[rscc-dedupe\]"),
    ("[rscc-ledger]", r"\[rscc-ledger\]"),
    ("[rscc-range-charger]", r"\[rscc-range-charger\]"),
    ("[rscc-intermediate-flow]", r"\[rscc-intermediate-flow\]"),
    ("[rscc-cache-source]", r"\[rscc-cache-source\]"),
    # 事件 token（这些散落在 [rscc-assembly] / [rscc-trace] 正文里，单独统计以看谁最吵）
    ("token bus_push", r"\bbus_push\b"),
    ("token bus_skip", r"\bbus_skip\b"),
    ("token bus_share_skip", r"\bbus_share_skip\b"),
    ("token take_to_chamber", r"\btake_to_chamber\b"),
    ("token take_from_machine", r"\btake_from_machine\b"),
    ("token handover_to_chamber", r"\bhandover_to_chamber\b"),
    ("token push_stalled", r"\bpush_stalled\b"),
    ("token unblock_recovered", r"\bunblock_recovered\b"),
    ("token unbalanced", r"\bunbalanced\b"),
    ("token pull_hold", r"\bpull_hold\b"),
    ("token pull_rejected", r"\bpull_rejected\b"),
    ("token net_down", r"\bnet_down\b"),
    ("token reason=", r"\breason="),
    ("token reject {", r"\breject \{"),
    ("token summary window=5s", r"summary window=5s"),
    ("token same cause repeated", r"same cause repeated"),
]

OVERRIDE_CODE = [
    ("[rscc-assembly] 家族（含 trace 正文以外）", r"\[rscc-assembly\]"),
    ("[rscc-trace]", r"\[rscc-trace\]"),
    ("[rscc-dedupe]", r"\[rscc-dedupe\]"),
    ("[rscc]", r"\[rscc\]"),
    ("[rscc-build]", r"\[rscc-build\]"),
    ("[rscc-ledger] 前缀字面量", r"\[rscc-ledger\]"),
    ("[rscc-range-charger]", r"\[rscc-range-charger\]"),
    ("[rscc-intermediate-flow]", r"\[rscc-intermediate-flow\]"),
    ("[rscc-cache-source]", r"\[rscc-cache-source\]"),
    ("[rscc-diag]", r"\[rscc-diag\]"),
    ("[rscc-group]", r"\[rscc-group\]"),
]


def main():
    if not os.path.exists(LOG):
        print("MISSING " + LOG)
        return 1
    lines = open(LOG, "r", encoding="utf-8", errors="replace").read().splitlines()
    total = len(lines)
    print("latest.log 总行数 = %d" % total)
    print()
    print("%-28s %8s" % ("族 / token", "行数"))
    print("-" * 38)
    counted = collections.Counter()
    compiled = [(name, re.compile(rx)) for name, rx in FAMILIES]
    for line in lines:
        for name, rx in compiled:
            if rx.search(line):
                counted[name] += 1
    for name, _ in FAMILIES:
        print("%-28s %8d" % (name, counted[name]))
    print()
    # 只看本模组发出的行（logger name 含 rs_create_compat），避免被 MC 自身噪声干扰
    mod_lines = [l for l in lines if "rs_create_compat" in l or "Rscc" in l]
    print("含 rs_create_compat/Rscc 的行数 = %d" % len(mod_lines))
    print()
    print("=== 本模组各行按『族』排名 ===")
    rank = collections.Counter()
    for line in mod_lines:
        for name, rx in compiled:
            if name.startswith("token "):
                continue
            if rx.search(line):
                rank[name] += 1
    for name, n in rank.most_common(20):
        print("%-28s %8d" % (name, n))
    print()
    print("=== 本模组各行按『事件 token』排名（同一行可计入多个 token）===")
    trank = collections.Counter()
    for line in mod_lines:
        for name, rx in compiled:
            if not name.startswith("token "):
                continue
            if rx.search(line):
                trank[name] += 1
    for name, n in trank.most_common(20):
        print("%-28s %8d" % (name, n))
    return 0


if __name__ == "__main__":
    sys.exit(main())
