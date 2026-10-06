# -*- coding: utf-8 -*-
"""齿轮堵塞 / 多余中间产物 / 序列装配问题 —— 实验日志分析器。

用法：
    python tools/analyze_blockage_experiment.py                 # 自动选**最新**的、有生产事件的日志
    python tools/analyze_blockage_experiment.py --log <日志路径>
    python tools/analyze_blockage_experiment.py --scenario 3    # 只报告第 3 段
    python tools/analyze_blockage_experiment.py --scenario 精密构件

它回答什么（这三件事在旧日志里结构上读不出来）
----------------------------------------------
  ① **多余中间产物（多开一件在制件）**：把同一 (资源, 工位) 的**推送事件**按 5 秒窗口聚类，
     并与「在制过渡件」的净变化对齐。一秒钟内把同一件重复推给同一工位 ⇒ 那一件就是多开的在制件。
     注意：按**事件数**计数，绝不按数量加权 —— 岩浆一次 500 mB 是**一份**，不是 500 份
     （旧实现用数量加权，于是「每 5 秒推 500 次岩浆」这种假超推会被报出来）。
  ② **齿轮堵塞**：`stuck {item=… station=… waited=…}` 的等待阶梯。`waited=` 只在判卡那一条里
     按 `STATION_STUCK_TICKS=60` 分档翻转 ⇒ 同一 (件,工位) 出现 60/120/180… 多档 = 粘住后的慢重试
     每 10 秒故意再放行一次；同时给出该工位的推送事件数是否异常集中。
  ③ **序列装配的共同因**：每个订单段的「推送 / 卡住 / 回收 / 重推」循环，以及「在制过渡件」
     的净变化（产出 − 完成）是否收敛（收敛 = 没有多余在制件堆积）。

为什么必须由脚本算
------------------
* 推送行只在 `moved > 0` 时打、且走「状态翻转」节流 ⇒ 人眼数不准；
* 「是不是多推了一份」必须按 5 秒窗口聚类再与在制件数对齐；
* 幂等：同一份日志跑两次输出逐字节一致，结论可复核。
"""

from __future__ import annotations

import argparse
import collections
import datetime
import glob
import gzip
import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOGDIR = os.path.join(ROOT, "run", "logs")
BUILD_INFO = os.path.join(ROOT, "src", "main", "resources", "build_info.properties")

# ---- 日志行解析 ----
STAMP = re.compile(r"\[(\d{2})(\d{2})[月\u6708](\d{4}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})\]")
BINDING = re.compile(r"binding task=(\S+) product=(\S+) x(\d+) steps=(\d+)"
                     r"(?: sequence=(\d+))? loops=(\d+)")
# `[rscc-assembly] chamber@(x,y,z) push {item=<id> x<n>} to=(x,y,z) result=<R> reason=<r>`
# 与 `… push {fluid=<id> x<n>} …` 两种资源都要匹配。
PUSH = re.compile(r"(chamber|exporter)@\((-?\d+),(-?\d+),(-?\d+)\) push \{(\w+)=([a-z_0-9:.]+) x(\d+)\} "
                  r"to=\((-?\d+),(-?\d+),(-?\d+)\) result=(\w+) reason=(\w+)")
# `[rscc-trace] item=<id> x<n> | from=<f> | event=<e> | to=<t> | reason=<r> | net=<n>`
TRACE = re.compile(r"\[rscc-trace\] (\w+)=([a-z_0-9:.]+) x(\d+) \| from=(\S+) \| event=(\w+) \| to=(\S+) "
                   r"\| reason=(\S+) \| net=(\S+)")
STUCK = re.compile(r"chamber@\((-?\d+),(-?\d+),(-?\d+)\) stuck \{item=([a-z_0-9:.]+) "
                   r"station=\((-?\d+),(-?\d+),(-?\d+)\) recipe=(\S+) step=(-?\d+) waited=(\d+)")
NET_DOWN = re.compile(r"net_down \{item=([a-z_0-9:.]+)\} delta=(-?\d+)")

PRODUCT_LABEL = {
    "create:precision_mechanism": "精密构件",
    "create:track": "列车轨道",
    "create:sturdy_sheet": "坚固板",
}
# Create 序列装配的「未完成件」命名规则（在制过渡件）
TRANSITION = re.compile(r"(item:)?create:(incomplete_|unprocessed_)")
# 判断一个资源键是不是过渡件（键已在 TRACE 里带 item:/fluid: 前缀）
def is_transition(key):
    return bool(TRANSITION.match(key))


def read_any(path):
    if path.endswith(".gz"):
        with gzip.open(path, "rt", encoding="utf-8", errors="replace") as handle:
            return handle.read()
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def seconds(line):
    """日志行 → 会话内秒（用于 5 秒窗口；解析不了返回 None）。

    <p>只按「时:分:秒.毫秒」算相对值即可 —— 同一份日志里所有行的日期前缀一致，
    差异只在时钟部分，因此窗口判定不需要真实日历（也不能依赖它：旧归档的时间戳是
    mojibake，但数字部分仍然可读）。</p>
    """
    match = STAMP.search(line)
    if not match:
        return None
    _, _, _, hour, minute, second, milli = match.groups()
    return int(hour) * 3600 + int(minute) * 60 + int(second) + int(milli) / 1000.0


def pick_log(explicit):
    """挑日志：**先按修改时间新→旧**，同一会话里再取生产事件更多的那份。

    为什么以 mtime 为第一关键字：用户要分析的是「刚跑的那一遍」。按事件数优先会挑到历史归档
    （实测：一份 9 月的旧归档因为事件更多被选中，结论对不上这一次实验）。
    """
    if explicit:
        return explicit
    candidates = []
    for name in ("latest.log", "debug.log"):
        path = os.path.join(LOGDIR, name)
        if os.path.isfile(path):
            candidates.append(path)
    candidates.extend(sorted(glob.glob(os.path.join(LOGDIR, "*.log.gz")), reverse=True))
    scored = []
    for path in candidates:
        try:
            text = read_any(path)
        except OSError:
            continue
        if not text:
            continue
        prod = text.count(" push {") + text.count("event=bus_push")
        binding = text.count("binding task=")
        if prod <= 0 and binding <= 0:
            continue
        scored.append((os.path.getmtime(path), prod, binding, path))
    if not scored:
        return None
    scored.sort(reverse=True)
    return scored[0][3]


def build_revision():
    values = {}
    try:
        with io.open(BUILD_INFO, "r", encoding="utf-8", errors="replace") as handle:
            for line in handle:
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, _, value = line.partition("=")
                    values[key.strip()] = value.strip()
    except OSError:
        pass
    return values


def segments_of(lines):
    """按 binding 行切段。"""
    marks = []
    for index, line in enumerate(lines):
        match = BINDING.search(line)
        if match:
            marks.append((index, match.group(1), match.group(2), int(match.group(3)),
                          int(match.group(5)) if match.group(5) else 0))
    out = []
    for position, (index, task, product, amount, sequence) in enumerate(marks):
        end = marks[position + 1][0] if position + 1 < len(marks) else len(lines)
        out.append({
            "task": task, "product": product, "amount": amount,
            "sequence": sequence, "start": index, "end": end,
            "label": PRODUCT_LABEL.get(product, product),
            "lines": lines[index:end],
        })
    return out


def analyze_segment(segment):
    """一段订单的证据汇总（全部为实测计数，不做推测）。"""
    info = {
        # (资源, 工位) -> 推送【事件数】（不按数量加权：岩浆一次 500 mB 仍是一份）
        "push_events": collections.Counter(),
        # (资源, 工位, 5 秒窗口) -> 事件数
        "push_windows": collections.Counter(),
        # 资源 -> 推送总量（件 / mB，仅用于展示量级）
        "push_amount": collections.Counter(),
        "stuck": [],
        "stuck_ladder": collections.defaultdict(set),
        "residual": collections.Counter(),
        "insert_transition": collections.Counter(),
        "insert_products": collections.Counter(),
        "net_down": collections.Counter(),
        "transition_in": collections.Counter(),
    }
    for line in segment["lines"]:
        match = PUSH.search(line)
        if match:
            kind = match.group(5)
            key = "%s:%s" % (kind, match.group(6))
            amount = int(match.group(7))
            station = "(%s,%s,%s)" % (match.group(8), match.group(9), match.group(10))
            info["push_events"][(key, station)] += 1
            info["push_amount"][key] += amount
            second = seconds(line)
            if second is not None:
                info["push_windows"][(key, station, int(second // 5))] += 1
        trace = TRACE.search(line)
        if trace:
            kind = trace.group(1)
            key = "%s:%s" % (kind, trace.group(2))
            amount = int(trace.group(3))
            event = trace.group(5)
            if event in ("insert_network", "insert_claimed") and is_transition(key):
                info["insert_transition"][key] += amount
            if event in ("bus_push", "take_from_machine", "handover_to_chamber") and is_transition(key):
                info["transition_in"][key] += amount
            if event == "insert_network" and not is_transition(key):
                info["insert_products"][key] += amount
            if event == "task_finished_residual":
                info["residual"][key] += amount
            if event == "net_down":
                info["net_down"][key] += 1
        stuck = STUCK.search(line)
        if stuck:
            item = "item:" + stuck.group(4)
            station = "(%s,%s,%s)" % (stuck.group(5), stuck.group(6), stuck.group(7))
            waited = int(stuck.group(10))
            info["stuck"].append((item, station, waited))
            info["stuck_ladder"][(item, station)].add(waited)
        down = NET_DOWN.search(line)
        if down:
            info["net_down"][down.group(1)] += 1
    return info


def ladder_summary(values):
    """把 waited 分档压成一行可读摘要：{66..544 共 N 档，步长 6}。

    <p>为什么必须压缩：判卡结论「粘住」后，`waited=` 会随慢重试窗口一路增长
    （实测同一工位一个会话里能到 544，共 80 档）—— 原样打印会把报告淹掉，
    而真正要看的只有三件事：**最小档、最大档、档数**。</p>
    """
    ordered = sorted(set(values))
    if not ordered:
        return "无"
    if len(ordered) == 1:
        return "%d（单档 = 结论粘住，符合预期）" % ordered[0]
    step = ordered[1] - ordered[0]
    return "%d..%d 共 %d 档，步长 %d（**多档 = 每 10 秒故意再放行一次**）" % (
        ordered[0], ordered[-1], len(ordered), step)


def verdicts(segment, info):
    """五条断言（每条都带明文证据）。过渡件不计入「超推」——它们本来就该被推给机器。"""
    rows = []
    # ① 多余中间产物：5 秒窗口内把同一件重复推给同一工位
    over = []
    for (key, station, _window), count in sorted(info["push_windows"].items()):
        if count > 1 and not is_transition(key):
            over.append((key, station, count))
    worst = collections.Counter()
    for (key, station, _window), count in info["push_windows"].items():
        worst[(key, station)] = max(worst[(key, station)], count)
    rows.append((
        "① 5 秒窗口内没有把同一件重复推给同一工位（重复推送 = 多开一件在制件的直接机制）",
        not over,
        "超推: %s" % (over[:6] if over else "无")
        + ("｜各 (资源,工位) 5 秒窗口最大事件数: %s" % dict(sorted(worst.items())[:4]) if worst else ""),
    ))
    # ② 在制过渡件「产出 − 完成」不超过订单量（超出 = 有在制件永远走不完）
    produced = sum(info["transition_in"].values())
    finished = sum(info["insert_transition"].values())
    slack = max(4, segment["amount"] * 4)
    rows.append((
        "② 在制过渡件「产出 − 完成」≤ 订单量的 4 倍（超出 = 有在制件堆积 / 走不完）",
        produced - finished <= slack,
        "产出 %d / 完成 %d / 差值 %d（容差 %d）：产出 %s；完成 %s"
        % (produced, finished, produced - finished, slack,
           dict(info["transition_in"]) or "无", dict(info["insert_transition"]) or "无"),
    ))
    # ③ 齿轮堵塞：waited 分档 + 是否超过判卡阈值
    ladders = {key: sorted(value) for key, value in info["stuck_ladder"].items()}
    single = all(len(value) <= 1 for value in ladders.values())
    detail = "；".join("%s @%s → %s" % (key[0], key[1], ladder_summary(value))
                      for key, value in sorted(ladders.items())) or "无"
    rows.append((
        "③ 同一 (件,工位) 的 waited 只有单档（多档 = 粘住后每 10 秒/工位故意再放行一次）",
        single,
        "stuck 条目 %d｜%s" % (len(info["stuck"]), detail),
    ))
    # ③b 卡住金额外证据：等待时长跨过多个慢重试窗口
    over_window = [(key[0], key[1], max(value)) for key, value in ladders.items() if max(value) > 200]
    rows.append((
        "③b 没有 (件,工位) 的 waited 超过 200 tick（= 粘住后已被放行 ≥3 次，齿轮被反复重推）",
        not over_window,
        "超过 200 的: %s" % (over_window[:6] if over_window else "无"),
    ))
    # ④ 任务结束残留里没有过渡件
    rows.append((
        "④ 任务结束回收的残留里没有过渡件（过渡件只能被产线消化，不该被当废料回收）",
        not any(is_transition(key) for key in info["residual"]),
        "残留: %s" % (dict(info["residual"]) or "无"),
    ))
    # ⑤ 成品确实入网
    total_product = sum(info["insert_products"].values())
    rows.append((
        "⑤ 该订单的成品有入网记录（否则这一段根本没跑到终点）",
        total_product > 0,
        "成品入网: %s" % (dict(info["insert_products"]) or "无"),
    ))
    return rows


def main():
    parser = argparse.ArgumentParser(description="齿轮堵塞 / 多余中间产物 实验日志分析")
    parser.add_argument("--log", default="", help="指定日志（默认自动挑最新的、有生产事件的那份）")
    parser.add_argument("--scenario", default="", help="只报告匹配该关键字的段落（段号或产物名）")
    args = parser.parse_args()

    path = pick_log(args.log or None)
    if not path:
        print("[FAIL] run/logs 下找不到任何含生产事件的日志。")
        print("       先按 docs/BLOCKAGE_EXPERIMENT_PROTOCOL.md 跑一遍产线。")
        return 1
    text = read_any(path)
    lines = text.splitlines()

    print("== 实验日志分析 ==")
    print("  日志: %s" % os.path.relpath(path, ROOT))
    stamp = build_revision()
    print("  本次构建指纹: revision=%s builtAt=%s sources=%s"
          % (stamp.get("revision"), stamp.get("builtAt"), stamp.get("sources")))
    build_lines = [l for l in lines if "[rscc-build]" in l]
    if build_lines:
        print("  日志构建指纹: %s" % build_lines[-1].split("[rscc-build]")[-1].strip()[:170])
    else:
        print("  日志构建指纹: **缺失** ⇒ 这一份跑的是「加指纹之前」的构建，"
              "结论只作基线（要实判定必须先编译 + 重开游戏）")
    ledger = [l for l in lines if "[rscc-ledger]" in l]
    print("  [rscc-ledger] 行数: %d%s（对平时本来就该是 0）"
          % (len(ledger), "" if ledger else " "))
    for line in ledger[:6]:
        print("      %s" % line.split("[rscc-ledger]")[-1].strip()[:150])
    dedupe = [l for l in lines if "[rscc-dedupe]" in l]
    print("  [rscc-dedupe] 行数: %d" % len(dedupe))
    print()

    segments = segments_of(lines)
    if not segments:
        print("[WARN] 日志里没有 `binding task=` 行 ⇒ 这一遍没有真正开始任何序列装配任务。")
        print("       要么没下单，要么下单没被本模组接住（看 watchdog / binding-gap 相关行）。")
        return 1
    print("切出 %d 个订单段：" % len(segments))
    for index, segment in enumerate(segments, start=1):
        print("  段 %-2d %-6s x%-3d 序列 %-2d 步  行 %d..%d  task=%s"
              % (index, segment["label"], segment["amount"], segment["sequence"],
                 segment["start"], segment["end"], segment["task"][:8]))
    print()

    failures = 0
    reported = 0
    for index, segment in enumerate(segments, start=1):
        if args.scenario and args.scenario != str(index) and args.scenario not in segment["label"]:
            continue
        info = analyze_segment(segment)
        pushes = sum(info["push_events"].values())
        if pushes == 0 and not info["stuck"]:
            print("段 %-2d %-6s x%-3d：这一段没有任何推送 / 卡住事件（未跑到产线 / 任务没开始），跳过"
                  % (index, segment["label"], segment["amount"]))
            continue
        reported += 1
        print("=" * 78)
        print("段 %d：%s x%d（task=%s，序列 %d 步）"
              % (index, segment["label"], segment["amount"], segment["task"][:8], segment["sequence"]))
        print("=" * 78)
        print("  实测：推送事件 %d 条（不按数量加权）/ 卡住 %d 条 / 过渡件产出 %d / 过渡件完成 %d / 成品入网 %d"
              % (pushes, len(info["stuck"]), sum(info["transition_in"].values()),
                 sum(info["insert_transition"].values()), sum(info["insert_products"].values())))
        top = sorted(info["push_events"].items(), key=lambda kv: -kv[1])[:5]
        if top:
            print("  推送最频繁的 (资源, 工位)【事件数】: %s"
                  % ", ".join("%s→%s ×%d" % (k, s, c) for (k, s), c in top))
        for name, ok, detail in verdicts(segment, info):
            if not ok:
                failures += 1
            print("  %s %s" % ("PASS" if ok else "FAIL", name))
            print("       %s" % detail)
        print()

    print("=" * 78)
    if reported == 0:
        print("没有可判定的段落（所有段都没有推送 / 卡住事件）。")
        print("几乎可以肯定：这一遍没跑产线，或者日志不是这一次会话的。")
        return 1
    if failures:
        print("结论：%d 条断言 FAIL —— 上面每一条的「明文证据」就是定位入口。" % failures)
        print("      请把本输出 + run/logs/latest.log + run/logs/debug.log 一起发回。")
        return 1
    print("结论：本份日志的 %d 个有效段落里没有发现「重复推送 / waited 阶梯 / 过渡件被误回收」的证据。"
          % reported)
    print("      注意这只说明**这一遍**没问题；要证明修复生效，还需按协议跑对照段落（见 docs/BLOCKAGE_EXPERIMENT_PROTOCOL.md）。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
