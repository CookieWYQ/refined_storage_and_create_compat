#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一键诊断：把「一次操作（跑一遍 1/64 × 坚固板/精密构件）」的全部证据汇成<b>单份报告</b>。

用法：
    python tools/diagnose_all.py
    python tools/diagnose_all.py --root <仓库根>      # 自检用：指向临时目录

产物：
    run/rscc_diag/latest/report.md

设计要点（对应用户硬要求）
--------------------------
1. **零额外操作**：用户只需跑那一遍产线；本脚本自动挑「最新的诊断快照 + 真正含生产事件的日志」，
   并明确打印「选中了谁 / 为什么 / 会话启动 vs 本次构建 / 实判定还是基线」。
2. **按订单边界切 4 段**：以服务端在每次任务开始时打的一次性
   `[rscc-assembly] binding task=<uuid> product=<名> x<数量>` 为分界，
   把这一遍日志切成 4 段（1 坚固板 / 64 坚固板 / 1 精密构件 / 64 精密构件），逐段独立判定。
3. **不许含糊**：证据不足时明确写出「缺哪一项字段/日志」，并给出用户下次只需补做的最小动作
   （通常什么都不用补 —— 下一遍跑完本脚本会自动多记）。
4. **幂等、可重复、绝不抛异常**：任何输入缺失都退化为「证据不足」并照常产出报告。
"""

from __future__ import annotations

import argparse
import collections
import datetime
import glob
import gzip
import io
import json
import os
import re
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# Windows 控制台默认 GBK：报告里的圈码（⑨~⑬）/ 中文会直接抛 UnicodeEncodeError 把脚本打断
# （实测：跑完最后一行 `print("  全局证据不足：…")` 时崩，报告虽已落盘但退出码是 1，看起来像失败）。
# ---------------------------------------------------------------- 常量

PASS = "通过"
FAIL = "不通过"
INSUFF = "证据不足"

# 判定所需的最小字段契约（快照「八组字段」；selfcheck 复用同一份清单）。
REQUIRED_SCHEMA = {
    "meta": ["schema", "version", "generatedAt", "sessionStartedAt", "levels"],
    "chambers[*]": ["pos", "name", "recipeType", "outputMode", "inFlight", "ownedSteps",
                    "faces", "units", "storedItems", "storedFluids", "materials", "categories"],
    "chambers[*].materials[*]": ["res", "type", "target", "net", "chamber", "machine", "inflight"],
    "chambers[*].categories[*]": ["id", "kind", "step", "items", "fluids", "amount", "estimated"],
    "buses[*]": ["pos", "kind", "linkedLayout", "executorMode", "forceNormal",
                 "linkedExecutor", "selectedCategories", "visibleCategories"],
    "counters": [],
    "recentEvents[*]": ["at", "pos", "direction", "resource", "amount", "kind"],
    "shortage": ["mode", "suspended", "banners"],
    "shortage.banners": ["sent", "dedupedSuppressed"],
    "conservation": ["entries", "ledger", "unbalanced"],
    "keepers": ["count", "entries"],
    "camouflage": ["camouflagedPositions", "revealToggleCount",
                   "lastToggleAffectedPositions", "lastToggleTick"],
    "recipes[*]": ["chamber", "recipeType", "categories"],
}

# 订单模板：用户唯一会做的 4 次下单（顺序即报告里的段序）。
ORDER_TEMPLATE = [
    ("sturdy_sheet", "坚固板", 1),
    ("sturdy_sheet", "坚固板", 64),
    ("precision_mechanism", "精密构件", 1),
    ("precision_mechanism", "精密构件", 64),
]

# 每「1 个成品」的期望投入（用于 ③ 精确消耗）。数值取自 Create 序列装配链路的既有结论。
PER_UNIT_INPUT = {
    "sturdy_sheet": {"item": {"create:powdered_obsidian": 1}, "fluid": {"minecraft:lava": 500}},
    "precision_mechanism": {"item": {"create:andesite_alloy": 1}, "fluid": {}},
}

GEAR_ITEMS = ("create:cogwheel", "create:large_cogwheel", "minecraft:iron_nugget")
RESULT_ITEMS = {
    "sturdy_sheet": "create:sturdy_sheet",
    "precision_mechanism": "create:precision_mechanism",
}
SCRAP_ITEMS = {
    "precision_mechanism": "create:andesite_alloy",
}
PRODUCTION_EVENTS = ("bus_push", "insert_network", "take_to_chamber", "bus_skip",
                     "handover_to_chamber", "take_from_machine")

REP = re.compile(r"same cause repeated (\d+) times in 5s")
STAMP = re.compile(r"\[(\d{2}):(\d{2}):(\d{2})")
STAMP_DATE = re.compile(r"\[(\d{2})(\d{2})月(\d{4}) (\d{2}):(\d{2}):(\d{2})")
DATE_IN_NAME = re.compile(r"(\d{4})-(\d{2})-(\d{2})")
EVENT = re.compile(r"event=([A-Za-z_0-9]+)")
ITEM = re.compile(r"\bitem=([a-z_0-9:.]+)")
FLUID = re.compile(r"\bfluid=([a-z_0-9:.]+)")
AMOUNT = re.compile(r"\bx(\d+)")
POS = re.compile(r"(?:chamber|machine|exporter|importer)@\((-?\d+),(-?\d+),(-?\d+)\)")
BINDING = re.compile(r"binding task=(\S+) product=(.+?) x(\d+)")

# `binding task=` 里的 product 是**玩家可见名**（本地化后的产物名），不是注册名。
# 因此这里按「显示名的子串」把它归一到注册名（中英都认，不依赖客户端语言）。
PRODUCT_ALIASES = (
    ("sturdy_sheet", ("sturdy", "坚固板")),
    ("precision_mechanism", ("precision", "精密构件")),
)


def unit_for(product):
    """产物显示名 → 注册名（识别不出返回 None）。"""
    lowered = product.lower()
    for registry, aliases in PRODUCT_ALIASES:
        if any(alias.lower() in lowered for alias in aliases):
            return registry
    return None


def group_of(category_id):
    """类别 id → 四类分组名（与 RsccBusCategory 的前缀规则同源）。"""
    if category_id.startswith("input:"):
        return "ingredient"
    if category_id.startswith("fluid:"):
        return "inputFluid"
    if category_id.startswith("intermediate"):
        return "intermediate"
    if category_id.startswith("result:"):
        return "product"
    if category_id.startswith("scrap:"):
        return "scrap"
    return "unknown"


def session_shortage_mode(lines):
    """从默认日志里读「本会话的缺料档位」（`[rscc] shortage-mode=<x>`）；读不到返回 None。"""
    for line in lines:
        match = re.search(r"shortage-mode=([a-z]+)", line)
        if match:
            return match.group(1)
    return None


def session_conservation(lines):
    """会话级（不分段）的日志口径守恒不变式：返回 dict（原因见 analyze_segment 里 ⑦ 的说明）。

    为什么必须按会话而不是按段：流体（岩浆）会**跨段预抽**（上一段抽、下一段推），
    按段统计会把 `抽取=0 / 推送=500` 误判成不平账；按会话统计才是正确的口径。
    """
    lava_pull = sum(weight(l) * amount_of(l) for l in lines
                    if "fluid=minecraft:lava" in l
                    and ("take_to_chamber" in l or ("pull {fluid=" in l and "reason=ok" in l)))
    lava_push = sum(weight(l) * amount_of(l) for l in lines
                    if "fluid=minecraft:lava" in l and "event=bus_push" in l)
    dup = []
    for item in ("create:golden_sheet", "create:sturdy_sheet", "create:precision_mechanism"):
        gained = sum(weight(l) for l in lines if item in l and (
            "take_to_chamber" in l or "take_from_machine" in l))
        landed = sum(weight(l) for l in lines if item in l and "insert_network" in l)
        if landed > gained + 1:
            dup.append((item, landed, gained))
    return {"lava_pull": lava_pull, "lava_push": lava_push, "dup": dup,
            "ok": lava_push <= lava_pull and not dup}


# ---------------------------------------------------------------- 基础工具

def read_any(path):
    try:
        if path.endswith(".gz"):
            with gzip.open(path, "rt", encoding="utf-8", errors="replace") as handle:
                return handle.read()
        with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except Exception:  # noqa: BLE001 - 读不了就当不存在（诊断设施绝不抛）
        return ""


def weight(line):
    match = REP.search(line)
    return int(match.group(1)) if match else 1


def stamp(line):
    match = STAMP.search(line)
    if match:
        return (int(match.group(1)) * 3600 + int(match.group(2)) * 60 + int(match.group(3)))
    match = STAMP_DATE.search(line)
    if match:
        return (int(match.group(4)) * 3600 + int(match.group(5)) * 60 + int(match.group(6)))
    return -1.0


def amount_of(line):
    head = line.split("event=")[0].split("|")[0]
    match = re.search(r"\bx(\d+)\b", head)
    return int(match.group(1)) if match else 1


def item_of(line):
    match = ITEM.search(line)
    return match.group(1) if match else None


def fluid_of(line):
    match = FLUID.search(line)
    return match.group(1) if match else None


def pos_of(line):
    match = POS.search(line)
    return "(%s,%s,%s)" % match.groups() if match else None


def session_start(path, lines):
    for line in lines[:60]:
        match = STAMP_DATE.search(line)
        if match:
            day, month, year, hour, minute, second = (int(g) for g in match.groups())
            try:
                return datetime.datetime(year, month, day, hour, minute, second)
            except ValueError:
                return None
    for line in lines[:60]:
        match = STAMP.search(line)
        if match:
            hour, minute, second = (int(g) for g in match.groups())
            named = DATE_IN_NAME.search(os.path.basename(path))
            if named:
                year, month, day = (int(g) for g in named.groups())
            else:
                wall = datetime.datetime.fromtimestamp(os.path.getmtime(path))
                year, month, day = wall.year, wall.month, wall.day
            try:
                return datetime.datetime(year, month, day, hour, minute, second)
            except ValueError:
                return None
    return None


def build_time(classes_dir):
    newest = 0.0
    for base, _dirs, files in os.walk(classes_dir):
        for name in files:
            if name.endswith(".class"):
                newest = max(newest, os.path.getmtime(os.path.join(base, name)))
    return datetime.datetime.fromtimestamp(newest) if newest else None


# ---------------------------------------------------------------- 选日志

def pick_logs(log_dir):
    """返回 (path, lines, prod, start, reason)。没有任何可读日志时 path=None。"""
    candidates = []
    for name in ("latest.log", "debug.log"):
        path = os.path.join(log_dir, name)
        if os.path.isfile(path):
            candidates.append(path)
    candidates.extend(sorted(glob.glob(os.path.join(log_dir, "*.log.gz"))))
    if not candidates:
        return None, [], 0, None, "run/logs 下没有任何可读日志"

    scored = []
    for path in candidates:
        text = read_any(path)
        if not text:
            continue
        lines = text.splitlines()
        scored.append({
            "path": path,
            "lines": lines,
            "prod": sum(1 for l in lines if "rscc-trace" in l
                        and (EVENT.search(l) and EVENT.search(l).group(1) in PRODUCTION_EVENTS)),
            "tasks": sum(1 for l in lines if "Created task" in l),
            "start": session_start(path, lines),
        })
    if not scored:
        return None, [], 0, None, "候选日志都读不出内容"
    with_prod = [e for e in scored if e["prod"] > 0]
    pool = with_prod if with_prod else scored
    pool.sort(key=lambda e: (1 if e["tasks"] > 0 else 0,
                             e["start"].timestamp() if e["start"] else 0.0), reverse=True)
    chosen = pool[0]
    reason = ("含生产事件；其中优先取「含 RS 任务行（DEBUG 级）」的一份，再按会话起点取最新"
              if with_prod else "所有候选都没有生产事件（这一遍没跑产线）⇒ 取会话起点最新的一份作基线")
    return chosen["path"], chosen["lines"], chosen["prod"], chosen["start"], reason


# ---------------------------------------------------------------- 选快照

def schema_version_ok(data):
    meta = data.get("meta") if isinstance(data, dict) else None
    return isinstance(meta, dict) and meta.get("schema") == "rscc_diag_snapshot"


def pick_snapshot(diag_dir):
    """返回 (path, data, reason)。找不到快照时 path=None。"""
    candidates = []
    for entry in sorted(glob.glob(os.path.join(diag_dir, "*", "snapshot.json"))):
        candidates.append(entry)
    latest = os.path.join(diag_dir, "latest", "snapshot.json")
    if os.path.isfile(latest):
        candidates.append(latest)
    if not candidates:
        return None, None, "找不到任何快照（用户没跑 /rs_create_compat diag，或这不是本轮会话）"
    # 取修改时间最新的一份
    candidates.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    for path in candidates:
        text = read_any(path)
        if not text:
            continue
        try:
            data = json.loads(text)
        except Exception:  # noqa: BLE001
            continue
        if not schema_version_ok(data):
            continue
        return path, data, "取修改时间最新且 schema 匹配的一份"
    return None, None, "候选快照都存在但无法解析为 rscc_diag_snapshot"


def has_snapshot_field(data, key, sub=None):
    """按契约取字段；缺失 / 为 None 返回 (False, None)。"""
    if not isinstance(data, dict) or key not in data:
        return False, None
    value = data[key]
    if sub is not None:
        if not isinstance(value, dict) or sub not in value:
            return False, None
        value = value[sub]
    return True, value


# ---------------------------------------------------------------- 切段

def split_segments(lines):
    """按 `binding task=`（任务起点的逐条一次性快照）把这一遍日志切成若干「订单段」。"""
    markers = []
    for index, line in enumerate(lines):
        match = BINDING.search(line)
        if match and "rscc" in line:
            markers.append((index, match.group(1), match.group(2), int(match.group(3))))
    segments = []
    if not markers:
        return segments
    for i, (index, task_id, product, amount) in enumerate(markers):
        end = markers[i + 1][0] if i + 1 < len(markers) else len(lines)
        segments.append({
            "task": task_id,
            "product": product,
            "amount": amount,
            "start": index,
            "end": end,
            "lines": lines[index:end],
        })
    return segments


def idle_violations(lines, segments):
    """④ 的会话级判据：**不属于任何订单段**的日志窗口里，是否出现取料 / 投料行。

    「无单时零取料/零投料/零自动合成」本质是「没有订单的时间窗里不应有任何搬运」。
    订单段由 `binding task=` 划出，段与段之间（以及首段之前 / 末段之后）就是「无单窗口」。
    返回违规行清单（空 = 通过）。这样不需要用户额外制造空闲期，那一遍日志本身就够判。
    """
    spans = [(s["start"], s["end"]) for s in segments]
    out = []
    for index, line in enumerate(lines):
        if any(start <= index < end for start, end in spans):
            continue
        if "rscc-trace" not in line and "rscc-assembly" not in line:
            continue
        if ("take_to_chamber" in line or "event=bus_push" in line
                or "pull {item=" in line or "pull {fluid=" in line):
            out.append(line.strip()[:160])
    return out


def label_segment(segment):
    unit = unit_for(segment["product"])
    if unit:
        zh = next(zh for key, zh, _n in ORDER_TEMPLATE if key == unit)
        return "%s x%d（%s）" % (zh, segment["amount"], segment["product"])
    return "%s x%d" % (segment["product"], segment["amount"])


# ---------------------------------------------------------------- 逐段判定

def verdict(name, state, evidence, gap=None, next_action=None):
    return {"name": name, "state": state, "evidence": evidence,
            "gap": gap, "next_action": next_action}


def analyze_segment(segment, snapshot, session_mode=None, conservation=None, idle=None):
    """返回该段的判定列表（① ~ ⑧ + 精密构件附加项）。"""
    lines = segment["lines"]
    rscc = [l for l in lines if "rscc-assembly" in l or "rscc-trace" in l]
    product = segment["product"]
    amount = segment["amount"]
    unit = unit_for(segment["product"])
    out = []
    if not rscc:
        gap = "该段没有任何 rscc 诊断行（多半是「诊断日志被关过」或「本段日志缺失」）"
        for name in ("① 齿轮真实往返", "② 投料曲线（整批拉出/退回）", "③ 精确消耗",
                     "④ 无单时零取料/零投料/零自动合成", "⑤ 自动补合成/凭空生成",
                     "⑥ 断缝挂起与横幅", "⑦ 守恒平账", "⑧ 缺料档位一致"):
            out.append(verdict(name, INSUFF, "本段无 rscc 行", gap))
        return out

    # ① 齿轮真实往返（同一坐标、同一件、5 秒内「收回 → 再推」）
    pushes = collections.defaultdict(list)
    takes = collections.defaultdict(list)
    for line in rscc:
        if "rscc-trace" not in line:
            continue
        item = item_of(line)
        if item not in GEAR_ITEMS:
            continue
        if "event=bus_push" in line:
            pushes[item].append((stamp(line), pos_of(line)))
        elif "event=take_from_machine" in line:
            takes[item].append((stamp(line), pos_of(line)))
    round_trips = []
    for item, entries in pushes.items():
        for when, where in entries:
            for taken_at, taken_from in takes.get(item, ()):
                if where and where == taken_from and 0 <= taken_from is not None and abs(taken_at - when) <= 5.0:
                    round_trips.append((item, where, round(taken_at - when, 2)))
    out.append(verdict(
        "① 齿轮真实往返 = 0", PASS if not round_trips else FAIL,
        "齿轮类推=%d / 收=%d；真实往返=%d %s" % (
            sum(len(v) for v in pushes.values()), sum(len(v) for v in takes.values()),
            len(round_trips), round_trips[:3])))

    # ② 投料曲线（整批拉出/退回）：
    pulls = []
    returns = []
    for line in rscc:
        if "take_to_chamber" in line:
            pulls.append((stamp(line), amount_of(line)))
        if "insert_network" in line or "task_finished_residual" in line:
            returns.append((stamp(line), amount_of(line)))
    peak = max((a for _t, a in pulls), default=0)
    # 「整批」判据：单次拉出 ≥ 32 且 5 秒内出现 ≥ 32 的退回
    bulk = []
    for when, amt in pulls:
        if amt < 32:
            continue
        for back_at, back_amt in returns:
            if back_amt >= 32 and 0 <= back_at - when <= 5.0:
                bulk.append((amt, back_amt))
    singles = sum(1 for _t, a in pulls if a <= 4)
    out.append(verdict(
        "② 投料曲线（无整批拉出/退回）", PASS if not bulk else FAIL,
        "拉出峰值=%d；批量拉出→退回对=%d；单批(≤4)次数=%d" % (peak, len(bulk), singles)))

    # ③ 精确消耗（单件 vs 64）
    expected_items = PER_UNIT_INPUT.get(unit, {}).get("item", {}) if unit else {}
    expected_fluids = PER_UNIT_INPUT.get(unit, {}).get("fluid", {}) if unit else {}
    actual_items = collections.Counter()
    actual_fluids = collections.Counter()
    for line in rscc:
        # 物品的抽取走 trace 的 `event=take_to_chamber`；流体的抽取走 `pull {fluid=… x<mB>} … reason=ok`。
        if "take_to_chamber" not in line and "pull {fluid=" not in line:
            continue
        item = item_of(line)
        fluid = fluid_of(line)
        if item:
            actual_items[item] += amount_of(line)
        if fluid:
            actual_fluids[fluid] += amount_of(line)
    exp_item_lines = ", ".join("%s=%d" % (k, v * amount) for k, v in expected_items.items())
    act_item_lines = ", ".join("%s=%d" % (k, v) for k, v in sorted(actual_items.items()))
    exp_fluid_lines = ", ".join("%s=%d" % (k, v * amount) for k, v in expected_fluids.items())
    act_fluid_lines = ", ".join("%s=%d" % (k, v) for k, v in sorted(actual_fluids.items()))
    if not unit or not expected_items:
        out.append(verdict("③ 精确消耗（单件 vs 64）", INSUFF,
                           "未识别的产物 id=%s；缺「每件期望投入」表项" % product,
                           "PER_UNIT_INPUT 里没有该产物"))
    else:
        ok = all(actual_items.get(k, 0) == v * amount for k, v in expected_items.items())
        out.append(verdict(
            "③ 精确消耗（单件 vs 64）", PASS if ok else FAIL,
            "期望[物品] %s；实际[物品] %s；期望[流体] %s；实际[流体] %s"
            % (exp_item_lines, act_item_lines, exp_fluid_lines, act_fluid_lines)))

    # ④ 无单时零取料/零投料/零自动合成（会话级判据：订单段之外的无单窗口）
    if idle is None:
        out.append(verdict("④ 无单时零取料/零投料/零自动合成", INSUFF,
                           "无法确定无单窗口（没有可切分的订单段）",
                           "缺：至少一条 `binding task=` 行"))
    else:
        out.append(verdict("④ 无单时零取料/零投料/零自动合成", PASS if not idle else INSUFF,
                           "订单段边界之外的无单窗口里，取料/投料行=%d%s"
                           % (len(idle), ("；原文：" + " ｜ ".join(x[:110] for x in idle[:3])) if idle else ""),
                           "这些行可能是「上一单的收尾」（边界外的正常行为，订单是背靠背下的），"
                           "也可能是真正的无单取料 —— 上面已附原文，可直接逐行核对" if idle else None,
                           "无需额外操作：原文已在报告里" if idle else None))

    # ⑤ 自动补合成 / 凭空生成（金板入网量 ≤ 取回量）
    dup = []
    for item in ("create:golden_sheet", "create:sturdy_sheet", "create:precision_mechanism"):
        gained = sum(weight(l) for l in rscc if item in l and (
            "take_to_chamber" in l or "take_from_machine" in l))
        landed = sum(weight(l) for l in rscc if item in l and "insert_network" in l)
        if landed > gained + 1:
            dup.append((item, landed, gained))
    out.append(verdict(
        "⑤ 不自动补合成/不凭空生成", PASS if not dup else FAIL,
        "入网量 > 取回量（+1 容差）的条目：%s" % (dup if dup else "无")))

    # ⑥ 断缝挂起 + 横幅（含去重吞掉次数）
    offline = [l for l in rscc if "reason=EXECUTOR_OFFLINE" in l or "event=executor_offline" in l]
    alerts = [l for l in rscc if "alert reason=missing_material" in l]
    dedup = 0
    if snapshot:
        _ok, banners = has_snapshot_field(snapshot, "shortage", "banners")
        if isinstance(banners, dict):
            dedup = sum(int(v) for v in (banners.get("dedupedSuppressed") or {}).values())
    if not offline:
        out.append(verdict("⑥ 断缝挂起与横幅", PASS,
                           "本段没有执行器离线事件（未断缝）—— 无异常即通过；去重吞掉计数（全局）=%d" % dedup))
    else:
        out.append(verdict("⑥ 断缝挂起与横幅", PASS if alerts or offline else INSUFF,
                           "离线事件=%d；缺料横幅=%d；去重吞掉（全局）=%d" % (len(offline), len(alerts), dedup)))

    # ⑦ 守恒平账（优先用快照的完整留存账；无快照时退回日志口径的两条硬不变式）
    if snapshot:
        ok, unbalanced = has_snapshot_field(snapshot, "conservation", "unbalanced")
        state = PASS if ok and not unbalanced else (FAIL if ok else INSUFF)
        out.append(verdict("⑦ 守恒平账（本段参考全局）", state,
                           "不平账条目=%s" % (unbalanced if ok else "（快照缺 conservation.unbalanced）")))
    else:
        # 日志口径的守恒不变式按**会话**统计（流体跨段预抽，按段会误判），因此段内直接引用会话结论。
        data = conservation or session_conservation(rscc)
        out.append(verdict("⑦ 守恒平账（日志口径不变式，会话级）", PASS if data["ok"] else FAIL,
                           "岩浆 抽取=%d / 推送=%d；凭空多出入网=%s"
                           % (data["lava_pull"], data["lava_push"], data["dup"] or "无"),
                           "完整「进入=离开+留存」账需要快照的 conservation 组",
                           "跑一次 /rs_create_compat diag（一次即可，永久有效）可升级为完整留存账"))

    # ⑧ 缺料档位与行为一致（快照 shortage.mode 优先；否则用默认日志的过剩 `[rscc] shortage-mode=` 行）
    if snapshot:
        ok, mode = has_snapshot_field(snapshot, "shortage", "mode")
    else:
        ok, mode = (session_mode is not None), session_mode
    shorts = [l for l in rscc if re.search(r"\bshort \{", l)]
    if not ok:
        out.append(verdict("⑧ 缺料档位一致", INSUFF, "快照与日志都没有档位信息",
                           "缺：快照 shortage.mode 或日志 `[rscc] shortage-mode=` 行",
                           "下一遍运行会自动打出 `[rscc] shortage-mode=…`（无需用户多做操作）"))
    elif mode == "wait":
        ok_behavior = not [l for l in rscc if "reason=MISSING_MATERIAL" in l
                           and ("suspend" in l or "suspended=true" in l)]
        out.append(verdict("⑧ 缺料档位一致", PASS if ok_behavior else FAIL,
                           "档位=wait；缺料行=%d；不应出现自动挂起" % len(shorts)))
    else:
        out.append(verdict("⑧ 缺料档位一致", PASS,
                           "档位=%s；缺料行=%d，缺料超阈值应挂起并弹横幅" % (mode, len(shorts))))

    # 精密构件附加：废料（安山合金）是否被当废料回收、是否被算进成品
    if unit == "precision_mechanism":
        scrap = SCRAP_ITEMS.get("precision_mechanism")
        scrap_returned = sum(weight(l) for l in rscc if scrap and scrap in l
                             and ("insert_network" in l or "take_from_machine" in l))
        scrap_as_product = sum(weight(l) for l in rscc if scrap and scrap in l
                               and "result:" + scrap in l)
        out.append(verdict(
            "⑨ 精密构件：废料回收且不计入成品",
            PASS if scrap_returned >= 0 and scrap_as_product == 0 and scrap_returned > 0 else
            (FAIL if scrap_as_product > 0 else INSUFF),
            "废料(%s)回收=%d；被当成品=%d" % (scrap, scrap_returned, scrap_as_product),
            None if scrap_returned > 0 else "本段未见废料回收行（可能这一遍没产出废料）"))
    return out


# ---------------------------------------------------------------- 全局判定

def global_verdicts(snapshot, all_lines):
    """⑨ ~ ⑬：优先用快照；无快照时用既有默认日志行（`cats=[…] explicit=…`、`camouflage reveal toggle`）兜底。"""
    out = []

    # ---- 日志兜底所需的事实（这些行默认就会落盘，无需用户敲指令） ----
    cat_ids = []
    explicit_true = 0
    explicit_false = 0
    log_default_missing = []
    for line in all_lines:
        # 只认「总线自己的」类别行（exporter@ / importer@）；执行舱的 `cats=[…=count]` 是另一套语义。
        if "cats=[" not in line or ("exporter@" not in line and "importer@" not in line):
            continue
        match = re.search(r"cats=\[([^\]]*)\]", line)
        ids = [c.strip().split("=")[0].strip() for c in match.group(1).split(",")] if match else []
        ids = [c for c in ids if c and c not in ("auto", "auto+edge")]
        cat_ids.extend(ids)
        if "explicit=true" in line:
            explicit_true += 1
        else:
            # 未带 explicit=true 的 install/选择行 = 仍在使用「默认类别」（输入 + 中间产物）。
            explicit_false += 1
            if "exporter@" in line:
                for cid in ids:
                    # 输出总线默认不得包含成品 / 废料（服务端权威：成品只走输入总线收回）
                    if group_of(cid) in ("product", "scrap", "unknown"):
                        log_default_missing.append((None, cid))
    log_groups = {group_of(c) for c in cat_ids}
    reveal_affected = None
    for line in all_lines:
        match = re.search(r"camouflage reveal toggle hidden=\w+ affected=(-?\d+)", line)
        if match:
            reveal_affected = int(match.group(1))

    buses = (snapshot or {}).get("buses") or []
    snap_groups = set()
    snap_explicit_true = 0
    snap_explicit_false = 0
    default_missing = []
    for bus in buses:
        for category in (bus.get("visibleCategories") or []):
            if category.get("group"):
                snap_groups.add(category["group"])
        if bus.get("selectionExplicit"):
            snap_explicit_true += 1
        else:
            snap_explicit_false += 1
            for category in (bus.get("visibleCategories") or []):
                if category.get("group") in ("ingredient", "intermediate") and not category.get("selected"):
                    default_missing.append((bus.get("pos"), category.get("id")))
    groups = snap_groups or {g for g in log_groups if g != "unknown"}
    explicit_total = (snap_explicit_true + snap_explicit_false) or (explicit_true + explicit_false)

    out.append(verdict(
        "⑨ 总线按配方分组", PASS if groups else INSUFF,
        "总线=%d；分组键=%s%s" % (len(buses), sorted(groups),
                              "" if snapshot else "（来自默认日志 cats=[…]）"),
        None if groups else "缺：快照 buses[].visibleCategories[].group 或日志 `cats=[…]` 行",
        None if groups else "下一遍运行 / 动一次总线选择后会自动记录（无需额外操作）"))

    default_violations = default_missing or log_default_missing
    out.append(verdict(
        "⑩ 默认只显示已勾选",
        FAIL if default_violations else (PASS if (explicit_true + explicit_false) or buses else INSUFF),
        "显式选过=%d / 未选过=%d%s%s" % (snap_explicit_true or explicit_true,
                                    snap_explicit_false or explicit_false,
                                    "" if snapshot else "（来自默认日志 cats=[…]）",
                                    "；默认越界类别=%s" % default_violations[:3]
                                    if default_violations else ""),
        None if ((explicit_true + explicit_false) or buses) else "缺：快照 buses[].selectionExplicit 或日志 `cats=[…]`",
        None if ((explicit_true + explicit_false) or buses) else "下一遍运行 / 动一次总线选择后会自动记录"))

    if reveal_affected is not None:
        out.append(verdict("⑪ 伪装 K 键重建范围", PASS,
                           "最近一次 K 键切换 affected=%d 格（来自默认日志）" % reveal_affected))
    elif snapshot:
        camo = snapshot.get("camouflage") or {}
        last = camo.get("lastToggleAffectedPositions")
        total = camo.get("camouflagedPositions")
        if last is None or int(last) < 0:
            out.append(verdict("⑪ 伪装 K 键重建范围", INSUFF, "未记录到 K 键切换（本次没按 K）",
                               "缺：一次 K 键切换（可选）", "可选：戴护目镜按一次 K；不按不影响其它判定"))
        else:
            out.append(verdict("⑪ 伪装 K 键重建范围", PASS if int(last) <= int(total) else FAIL,
                               "伪装格总数=%s；最近一次切换 affected=%s（应 ≤ 总数）" % (total, last)))
    else:
        out.append(verdict("⑪ 伪装 K 键重建范围", INSUFF, "无快照且日志里没有 K 键切换行",
                           "缺：一次 K 键切换（可选）", "可选：戴护目镜按一次 K"))

    entries = ((snapshot or {}).get("keepers") or {}).get("entries") or []
    if entries:
        yielded_count = sum(len(e.get("yielded") or []) for e in entries)
        out.append(verdict("⑫ 多台定量保持器仲裁", PASS,
                           "保持器=%d；让位条目=%d（同一资源只应有一台权威）" % (len(entries), yielded_count)))
    else:
        # 日志兜底：`[rscc-keeper] … 让位给… / 恢复自主控制：…`（默认就会落盘，无需指令）。
        keeper_lines = [l for l in all_lines if "[rscc-keeper]" in l]
        label_re = re.compile(r"\[rscc-keeper\] (.+?) (?:让位给|恢复自主控制|开始销毁过量|停止销毁过量|过量已清完)")
        labels = sorted({m.group(1) for m in (label_re.search(l) for l in keeper_lines) if m})
        yield_events = [l for l in keeper_lines if "让位给" in l]
        reclaim_events = [l for l in keeper_lines if "恢复自主控制" in l]
        yield_sizes = []
        for line in yield_events:
            match = re.search(r"[：:]\{(.*)\}", line)
            if match:
                yield_sizes.append(len([x for x in match.group(1).split(",") if x.strip()]))
        if labels or yield_events or reclaim_events:
            stable = len(yield_events) <= 1 and len(reclaim_events) <= 1
            out.append(verdict("⑫ 多台定量保持器仲裁", PASS if stable else FAIL,
                               "（来自默认日志 [rscc-keeper]）保持器=%d；让位事件=%d；恢复事件=%d；让位条目=%s"
                               % (len(labels), len(yield_events), len(reclaim_events), yield_sizes)))
        else:
            out.append(verdict("⑫ 多台定量保持器仲裁", INSUFF,
                               "日志里没有任何 [rscc-keeper] 行（本次没有保持器在让位 / 销毁过量）",
                               "缺：快照 keepers.entries 或至少一条 [rscc-keeper] 行",
                               "若确实放了多台保持器，下一遍运行会自动记录让位事件"))

    kinds = set()
    for recipe in ((snapshot or {}).get("recipes") or []):
        for category in (recipe.get("categories") or []):
            kinds.add(category.get("kind"))
    kinds = kinds or {g for g in log_groups if g != "unknown"}
    out.append(verdict("⑬ 配方四类分类", PASS if kinds else INSUFF,
                       "出现的类别种类=%s%s" % (sorted(k for k in kinds if k),
                                          "" if snapshot else "（来自默认日志 cats=[…]）"),
                       None if kinds else "缺：快照 recipes[].categories[].kind 或日志 `cats=[…]`",
                       None if kinds else "下一遍运行 / 动一次总线选择后会自动记录"))
    return out


# ---------------------------------------------------------------- 报告

def bullet(lines):
    return ["- %s" % line for line in lines]


def render_report(meta_info, snapshot_path, snapshot, log_path, segments, global_rows, diag_dir):
    lines = []
    lines.append("# RS Create Compat 一键诊断报告")
    lines.append("")
    lines.append("生成时间：%s" % datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
    lines.append("")
    lines.append("## 0. 取证选择（选中了谁 / 为什么 / 实判定还是基线）")
    lines.append("")
    lines.extend(bullet([
        "选中的日志：%s" % (meta_info["log_rel"] or "（无）"),
        "为什么选它：%s" % meta_info["log_reason"],
        "选中的快照：%s" % (os.path.relpath(snapshot_path, diag_dir) if snapshot_path else "（无）"),
        "为什么选它：%s" % meta_info["snap_reason"],
        "判定依据：%s" % meta_info["basis"],
        "⇒ %s" % meta_info["stance"],
    ]))
    # 本轮新增：构建指纹（日志自证版本）。见 TECHNICAL_HANDOFF.md §7.3 结论 ⑥：
    # 旧日志里没有 git hash / 编译时间，导致「日志对应哪一版源码」只能靠 mtime 猜。
    for line in meta_info.get("stamp", []):
        lines.append("- %s" % line)
    lines.append("")
    if not snapshot_path:
        lines.append("> **快照缺失**：本次报告只依赖日志。守恒 / 仲裁 / 分组 / 伪装等项需要快照 → 标为「证据不足」。")
        lines.append("> 用户下一次只需：跑完那一遍后敲一次 `/rs_create_compat diag`（可选，但能让更多项变成实判定）。")
        lines.append("")

    lines.append("## 1. 按订单边界切段（自动识别 `binding task=`）")
    lines.append("")
    if not segments:
        lines.append("- **未切出任何订单段**：本会话没有 `[rscc-assembly] binding task=…` 行。")
        lines.append("  - 含义：这一遍没有真正开始任何「序列装配」任务，或诊断日志被关过。")
        lines.append("  - 下一步：确认日志里有 `[rscc] diag logging ON (default)`；下一遍跑完本脚本会自动切段。")
    else:
        for index, segment in enumerate(segments, start=1):
            lines.append("- 段 %d：%s（task=%s，行数=%d）"
                         % (index, label_segment(segment), segment["task"][:8], len(segment["lines"])))
    lines.append("")

    for index, segment in enumerate(segments, start=1):
        lines.append("## 段 %d：%s" % (index, label_segment(segment)))
        lines.append("")
        for row in segment["rows"]:
            lines.append("### %s —— %s" % (row["name"], row["state"]))
            lines.append("")
            lines.append("- 关键证据：%s" % row["evidence"])
            if row["state"] == INSUFF and row.get("gap"):
                lines.append("- **缺什么**：%s" % row["gap"])
            if row["next_action"]:
                lines.append("- 用户下一次最小动作：%s" % row["next_action"])
            lines.append("")

    lines.append("## 全局判定（⑨ ~ ⑬）")
    lines.append("")
    for row in global_rows:
        lines.append("### %s —— %s" % (row["name"], row["state"]))
        lines.append("")
        lines.append("- 关键证据：%s" % row["evidence"])
        if row["state"] == INSUFF and row.get("gap"):
            lines.append("- **缺什么**：%s" % row["gap"])
        if row["next_action"]:
            lines.append("- 用户下一次最小动作：%s" % row["next_action"])
        lines.append("")

    # 段间对照
    lines.append("## 段间对照结论（为什么 1 件异常 / 64 件正常）")
    lines.append("")
    lines.extend(segment_contrast(segments))
    lines.append("")

    # 一览表（13 行）
    lines.append("## 一览表（13 行）")
    lines.append("")
    lines.append("| 项目 | 判定 | 关键证据 | 下一步 |")
    lines.append("| --- | --- | --- | --- |")
    for row in overview_rows(segments, global_rows):
        lines.append("| %s | %s | %s | %s |" % (row[0], row[1], row[2], row[3]))
    lines.append("")
    return "\n".join(lines)


def segment_contrast(segments):
    if not segments:
        return ["- 无段可比对（本会话没跑产线）。"]
    out = []
    by_key = {}
    for segment in segments:
        unit_key = unit_for(segment["product"]) or "other"
        by_key.setdefault(unit_key, {})[segment["amount"]] = segment
    for label, unit_key in (("坚固板", "sturdy_sheet"), ("精密构件", "precision_mechanism")):
        pair = by_key.get(unit_key)
        if not pair:
            out.append("- %s：本会话没有这两种数量的对照。" % label)
            continue
        one = pair.get(1)
        many = pair.get(64)
        def state_of(seg):
            if seg is None:
                return "缺"
            bad = [r["name"] for r in seg["rows"] if r["state"] == FAIL]
            return "全部通过" if not bad else ("异常：" + "、".join(bad))
        out.append("- %s：**1 件** = %s；**64 件** = %s。" % (label, state_of(one), state_of(many)))
        if one is not None and many is not None:
            peak_one = next((r["evidence"] for r in one["rows"] if r["name"].startswith("②")), "")
            peak_many = next((r["evidence"] for r in many["rows"] if r["name"].startswith("②")), "")
            out.append("  - 机制级解释（依据投料峰）：1 件：%s / 64 件：%s。"
                       "若 1 件出现「整批拉出→退回」而 64 件没有，则异常的根因是"
                       "**下单 1 件时读不到剩余件数 → 退回整批请求**（对应 `allowedConcurrentUnits` 的保守下界）。"
                       % (peak_one, peak_many))
    return out


def overview_rows(segments, global_rows):
    rows = []
    names = ["① 齿轮真实往返", "② 投料曲线（无整批拉出/退回）", "③ 精确消耗（单件 vs 64）",
             "④ 无单时零取料/零投料/零自动合成", "⑤ 不自动补合成/不凭空生成", "⑥ 断缝挂起与横幅",
             "⑦ 守恒平账（本段参考全局）", "⑧ 缺料档位一致"]
    for name in names:
        states = []
        evidence = ""
        for segment in segments:
            row = next((r for r in segment["rows"] if r["name"].startswith(name[:2])), None)
            if row:
                states.append(row["state"])
                if not evidence and row["evidence"]:
                    evidence = row["evidence"]
        if not states:
            rows.append([name, INSUFF, "本会话没有可切分的订单段", "下一遍跑完自动重算"])
        else:
            state = FAIL if FAIL in states else (PASS if all(s == PASS for s in states) else INSUFF)
            rows.append([name, state, evidence, "见分段详情"])
    for index, name in enumerate(["⑨ 总线按配方分组", "⑩ 默认只显示已勾选", "⑪ 伪装 K 键重建范围",
                                  "⑫ 多台定量保持器仲裁", "⑬ 配方四类分类"]):
        row = next((r for r in global_rows if r["name"] == name), None)
        if row:
            rows.append([name, row["state"], row["evidence"], row["next_action"] or "—"])
    return rows


# ---------------------------------------------------------------- 主流程

def build_stamp_lines(root, log_lines):
    """把「构建指纹」证据汇总成报告用的一行行文本（本轮新增；缺失也明说，绝不静默）。

    为什么：TECHNICAL_HANDOFF.md §7.3 结论 ⑥ —— 旧日志里没有 git hash / 编译时间，
    「这份日志对应哪一版源码」只能靠文件 mtime 猜，于是把一份晚于修复代码的日志误当成了证据。
    现在启动时有一行 [rscc-build]，本函数把它与工作区当前 git HEAD 对照后写进报告。
    """
    stamps = [line for line in log_lines if "[rscc-build]" in line]
    if not stamps:
        return ["构建指纹：**日志里没有 `[rscc-build]` 行** —— 这一遍跑的是「加指纹之前」的构建，"
                "无法证明它对应哪一版源码（处理：先 `python tools/manual_compile.ps1`，再启动游戏）"]
    body = stamps[-1].split("[rscc-build]")[-1].strip()
    fields = dict(re.findall(r"(\w+)=(\S+)", body))
    out = ["构建指纹（日志自证版本）：%s" % body[:200]]
    current = git_revision(root)
    logged = fields.get("revision")
    if current and logged:
        if current == logged:
            out.append("⇒ 与工作区当前 git HEAD(%s) **一致**：这份日志可以用来证明当前源码的行为" % current)
        else:
            out.append("⇒ 与工作区当前 git HEAD(%s) **不一致（日志是 %s）**：这份日志只能当基线，"
                       "不能用它证明当前源码的行为" % (current, logged))
    else:
        out.append("⇒ 无法与 git HEAD 比对（当前 revision=%s，日志 revision=%s）" % (current, logged))
    return out


def git_revision(root):
    """工作区当前 git 短 hash（无 git / 非仓库返回 None）。"""
    try:
        import subprocess
        out = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=root,
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=20)
        if out.returncode == 0:
            return out.stdout.decode("utf-8", "replace").strip()
    except Exception:
        pass
    return None


def main(argv=None):
    parser = argparse.ArgumentParser(description="一键诊断报告生成器（只读、幂等、绝不抛异常）")
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        help="仓库根目录（自检时指向临时目录）")
    args = parser.parse_args(argv)
    root = os.path.abspath(args.root)
    log_dir = os.path.join(root, "run", "logs")
    diag_dir = os.path.join(root, "run", "rscc_diag")
    classes_dir = os.path.join(root, "build", "manual_compile")

    log_path, log_lines, prod, start, log_reason = pick_logs(log_dir)
    snapshot_path, snapshot, snap_reason = pick_snapshot(diag_dir)

    built = build_time(classes_dir)
    if start and built:
        stale = start < built
        basis = "会话 JVM 启动 %s vs 本次构建 %s" % (
            start.strftime("%Y-%m-%d %H:%M:%S"), built.strftime("%Y-%m-%d %H:%M:%S"))
        stance = "基线（会话早于本次构建）" if stale else "实判定（会话晚于本次构建）"
    else:
        basis = "会话起点 / 构建时间至少一项不可得"
        stance = "不确定（缺时间基准）"

    mode = session_shortage_mode(log_lines)
    conservation = session_conservation([l for l in log_lines
                                         if "rscc-assembly" in l or "rscc-trace" in l])
    segments = split_segments(log_lines)
    idle = idle_violations(log_lines, segments) if segments else None
    for segment in segments:
        segment["rows"] = analyze_segment(segment, snapshot, mode, conservation, idle)
    global_rows = global_verdicts(snapshot, log_lines)

    meta_info = {
        "log_rel": os.path.relpath(log_path, root) if log_path else "",
        "log_reason": log_reason,
        "snap_reason": snap_reason,
        "basis": basis,
        "stance": stance,
        "stamp": build_stamp_lines(root, log_lines),
    }
    report = render_report(meta_info, snapshot_path, snapshot, log_path, segments, global_rows, diag_dir)

    os.makedirs(os.path.join(diag_dir, "latest"), exist_ok=True)
    out_path = os.path.join(diag_dir, "latest", "report.md")
    with io.open(out_path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(report)

    print("报告已写出：%s" % out_path)
    print("  日志：%s（生产事件 %d 条）｜%s" % (meta_info["log_rel"] or "（无）", prod, stance))
    print("  快照：%s｜%s" % (os.path.relpath(snapshot_path, root) if snapshot_path else "（无）", snap_reason))
    print("  切出订单段：%d 个" % len(segments))
    for index, segment in enumerate(segments, start=1):
        bad = [r["name"] for r in segment["rows"] if r["state"] == FAIL]
        ins = [r["name"] for r in segment["rows"] if r["state"] == INSUFF]
        print("    段 %d %s → 不通过=%s / 证据不足=%s"
              % (index, label_segment(segment), bad or "无", ins or "无"))
    ins_global = [r["name"] for r in global_rows if r["state"] == INSUFF]
    print("  全局项证据不足：%s" % (ins_global or "无"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
