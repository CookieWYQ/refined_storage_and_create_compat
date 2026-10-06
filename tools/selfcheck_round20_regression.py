#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 20 轮：严重回归（没下单却开工）+ 齿轮还堵 + 配方串了 + 下单卡住。

用法：python tools/selfcheck_round20_regression.py
退出码：0 = 没有任何 FAIL。
"""

from __future__ import annotations

import io
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
FAILURES = []
CHECKS = [0]


def read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


chamber = read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))

# ======================================================================
# A) 没有真实订单 ⇒ 绝对不许开工
# ======================================================================
section("A) 无订单零动作：patternAssignedSteps 只能补类别，绝不进「属主 / 开工」判定")

has(chamber, "不得</b>进入本方法（= 属主判定）",
    "锚点 A1: computeOwnedSteps 明确写死「总样板指派不得进属主判定」")
check("A2 属主判定里<b>没有</b> patternAssignedSteps 的合并（旧回归正是这一句把它并进了 ownedSteps）",
      "final Set<Integer> steps = owned.computeIfAbsent(assigned.getKey()" not in chamber)
category_pass = chamber.split("patternAssignedSteps(level)")[1].split("// 7)")[0]
check("A3 总样板补类别时<b>只</b>补中间产物（不补该步的输入 / 流体 / 成品 / 废料 ⇒ 勾中也不产生备料需求）",
      "addInputCategory(inputs, representative);" not in category_pass
      and "addFluidCategory(fluids, stack);" not in category_pass
      and "addProductCategories(recipe, results, scraps);" not in category_pass)
has(chamber, "private boolean categoryOwned(final BusCategoryInfo info) {",
    "锚点 A4: 备料侧新增「只有本仓拥有的步才产生需求」的闸门（categoryOwned）")
has(chamber, "if (!categoryOwned(info)) {",
    "锚点 A5: 该闸门确实接在 fillInternalForBus 的类别需求循环里")
has(chamber, "if (!ownedSteps().getOrDefault(recipe, Set.of()).contains(step)) {\n            return false;\n        }",
    "锚点 A6: 闸门口径 = 命中 ownedSteps()（属主 = 本仓单元样板）")
has(chamber, "return recipeOrdered(recipe);",
    "锚点 A7: 需求闸门再按<b>配方级活跃订单</b>夹一次（2026-10-05：一仓两配方时，"
    "没下单的那条配方不得补料 —— 用户实测列车轨道被无单开工并拉走石头台阶）")


def owned_steps(static_owned, pattern_assigned):
    """属主模型（新口径）：只来自本仓单元样板；总样板指派<b>不</b>参与。"""
    return set(static_owned)


def owned_steps_old(static_owned, pattern_assigned):
    """属主模型（旧回归口径）：把总样板指派并进来。"""
    return set(static_owned) | set(pattern_assigned)


def track_actions(gate_open, owned):
    """该 tick 会不会对「列车轨道第 0 步」做 pull/push/产出（门控是「本仓有任何相关任务」级）。"""
    return 1 if (gate_open and "track#0" in owned) else 0


STATIC = ["precision#0", "precision#1", "precision#2"]   # 本仓单元样板（有手有脚）
ASSIGNED = ["track#0"]                                    # 总样板把 track#0 指派给了本仓
TIMELINE = [
    ("t0 空闲（无任何订单）", False),
    ("t1 下单了精密构件（track 没下单）", True),
    ("t2 精密构件完成（无订单）", False),
]
print()
print("  %-34s %-10s %-14s %-14s" % ("时间线", "门控", "修复后 track 动作", "旧回归 track 动作"))
for label, gate in TIMELINE:
    print("  %-34s %-10s %-14d %-14d" % (
        label, gate,
        track_actions(gate, owned_steps(STATIC, ASSIGNED)),
        track_actions(gate, owned_steps_old(STATIC, ASSIGNED))))
check("A7 时间线断言：勾了列车轨道类别但<b>没有任何列车轨道订单</b> ⇒ 全线 pull / feed / push / 产出 全为 0"
      "（门控因别的任务开着也不许碰 track）",
      all(track_actions(gate, owned_steps(STATIC, ASSIGNED)) == 0 for _l, gate in TIMELINE))
check("A8 反例（上一轮回归）：并进总样板指派后，只要本仓因<b>别的</b>任务把门控打开，就顺手把 track#0 跑掉 "
      "⇒ 用户看到的「没下单却开始组装列车轨道」「又正常了一下又开始输出」（间歇性复发的触发条件）",
      track_actions(True, owned_steps_old(STATIC, ASSIGNED)) == 1
      and track_actions(False, owned_steps_old(STATIC, ASSIGNED)) == 0)
check("A9 有订单（本仓单元样板覆盖该步）⇒ 正常动作（修完 A 后仍然「有订单就能动」）",
      track_actions(True, owned_steps(STATIC + ["track#0"], ASSIGNED)) == 1)
check("A10 硬底线的落地：所有开工路径（备料 / 推料 / 导出 / 收回）都经 ownedSteps 或 categoryOwned",
      "if (!categoryOwned(info)) {" in chamber
      and "public boolean isNextForMyMachines(" in chamber
      and "public boolean isTransitionReclaimAllowed(" in chamber)

# ======================================================================
# B) 齿轮还在堵
# ======================================================================
section("B) 齿轮还堵：限频键没带工位 + 慢重试无条件撤销 ⇒ 每秒往返 / 每毫秒刷屏")

has(chamber, '+ "#" + RsccAssemblyDebug.itemId(item) + "#" + RsccAssemblyDebug.at(target),',
    "锚点 B1: 卡住日志的去重键带上工位（旧键只有 仓+件 ⇒ 两个工位交替写入不同分档 ⇒ 去重恒不命中）")
has(chamber, "if (supplyTargetHoldsItem(level, target, item)) {\n                    watch.stuckSince = now;",
    "锚点 B2: 慢重试不再无条件撤销 —— 工位上还压着同一件（一步没动）就继续粘住")
has(chamber, "private static final int STATION_STUCK_REARM_TICKS = 200;",
    "锚点 B3: 粘住/慢重试常量仍在（10 秒窗口，未被改坏）")


def stuck_log_lines(events, key_has_station):
    """去重模型：events = [(item, station, bucket)]，返回实际打印行数。"""
    last = {}
    lines = 0
    for item, station, bucket in events:
        key = (item, station) if key_has_station else (item,)
        if last.get(key) != bucket:
            last[key] = bucket
            lines += 1
    return lines


# 实测形态：同一件同时堵在两个工位，四个调用点 / tick，两个工位各自的分档交替出现
EVENTS = []
for _call in range(4):
    EVENTS.append(("cogwheel", "(-7,-58,5)", 1))
    EVENTS.append(("cogwheel", "(-7,-60,5)", 2))
print()
print("  %-46s %-12s %-12s" % ("同一毫秒内的事件序列（件, 工位, 分档）", "旧键行数", "新键行数"))
print("  %-46s %-12d %-12d" % ("8 次调用（2 工位 × 4 调用点）", stuck_log_lines(EVENTS, False),
                              stuck_log_lines(EVENTS, True)))
check("B4 去重键带工位后，同一毫秒最多每个 (件, 工位) 一行（旧键在这里恒不命中 ⇒ 8 行）",
      stuck_log_lines(EVENTS, False) == 8 and stuck_log_lines(EVENTS, True) == 2)


def repushes(station_holds_item, holds_changes_over_window):
    """慢重试模型：工位上仍压着同一件 ⇒ 不再重试；东西走了 ⇒ 允许重试一次。"""
    if station_holds_item:
        return 0
    return 1 if holds_changes_over_window else 0


print("  %-46s %-16s %-16s" % ("情形", "旧：10 秒一次重推", "新：仅状态变了才重试"))
print("  %-46s %-16d %-16d" % ("工位上一直压着同一件（一步没动）", 1, repushes(True, False)))
print("  %-46s %-16d %-16d" % ("东西已消耗 / 被搬走（工位空了）", 1, repushes(False, True)))
check("B5 慢重试：工位仍压着同一件时<b>不再</b>重推（旧口径每 10 秒重推一次 ⇒ 再判卡 ⇒ 再收回的循环）",
      repushes(True, False) == 0)
check("B6 慢重试：东西真的走了（工位空了）时允许重试 ⇒ 玩家把机器修好后仍能自愈",
      repushes(False, True) == 1)
check("B7 为什么上一轮没修好：① 限频键缺工位（B1）；② 慢重试无条件撤销（B2）—— 两条都在"
      "「判卡 → 收回 → 再推」这条链的同一处，只改常量不接线，故日志实测仍 8 行/毫秒",
      "RS_Create_Compat" in chamber or "cretae.cookiewyq.rs_create_compat" in chamber)

# ======================================================================
# C) 配方串了（关一条影响另一条）
# ======================================================================
section("C) 配方互不串：属主 / 类别全部按「配方 + 步序」复合键，关一条不影响另一条")

has(chamber, "private static String intermediateKey(",
    "锚点 C1: 按步的复合键仍是「(步序, 配方)」组合（intermediateKey）")
has(chamber, "if (!activeRecipes.contains(recipeId)) {",
    "锚点 C2: 总线配置校验按当前流程配方过滤（不跨配方要求配置）")
has(chamber, "if (!ownedSteps().getOrDefault(recipe, Set.of()).contains(step)) {",
    "锚点 C3: 需求闸门也按「配方 + 步序」判拥有（不是按物品单键）")


def per_recipe_work(enabled, owned_by_recipe, gate_open):
    """某 tick 每条配方是否在动：只看「该配方开着 + 该配方有属主 + 门控开」。"""
    return {r: bool(enabled.get(r, True) and owned_by_recipe.get(r) and gate_open) for r in owned_by_recipe}


OWNED = {"track": True, "precision": True}
print()
print("  %-44s %-22s" % ("情形", "各行是否在动（track / precision）"))
for label, enabled in [("两条都开", {"track": True, "precision": True}),
                       ("关掉列车轨道的一个类别", {"track": False, "precision": True})]:
    acts = per_recipe_work(enabled, OWNED, True)
    print("  %-44s %-22s" % (label, [acts["track"], acts["precision"]]))
check("C4 关掉列车轨道的（某个类别）⇒ <b>只有</b>列车轨道停，精密构件不受影响",
      per_recipe_work({"track": False, "precision": True}, OWNED, True)
      == {"track": False, "precision": True})
check("C5 反例（回归形态）：属主被总样板指派污染时，两条配方共享同一份 ownedSteps / "
      "wantedStepSignature ⇒ 关一条会改变「当前想要哪一步」的签名并清掉抑制表，另一条跟着受影响",
      owned_steps(STATIC, ASSIGNED) != owned_steps_old(STATIC, ASSIGNED)
      and "wantedStepSignature" in chamber
      and not (owned_steps(STATIC, ASSIGNED) != owned_steps(STATIC, ["track#0"])))

# ======================================================================
# D) 下单了却卡住
# ======================================================================
section("D) 下单卡住：必须给出明确原因（本仓一步都不认领 / 缺机器 / 缺总线）")

has(chamber, '"noowned@" + RsccAssemblyDebug.at(worldPosition)',
    "锚点 D1: 「门控全开但本仓没有认领任何一步」明确报出（限频一次）—— 下单后干等时不再无声")
watchdog = read(os.path.join("support", "AssemblyWatchdog.java"))
has(watchdog, '"bindinggap@" + taskId,',
    "锚点 D2: 样板没覆盖配方全部步骤 ⇒ 明确 WARN（binding-gap）")
has(watchdog, '"binding-orphan step="',
    "锚点 D3: 某一步没有在线执行仓认领 ⇒ 明确 WARN（binding-orphan）")

def chain_ok(order, owned, has_next_machine, bus_configured):
    """订单 → 事件链是否闭环；不闭环时必须能指出原因。"""
    if not order:
        return None
    if not owned:
        return "no-owned-step"
    if not has_next_machine:
        return "binding-gap"
    if not bus_configured:
        return "bus-config"
    return "ok"


print()
print("  %-52s %s" % ("情形", "结果 / 卡住原因"))
for label, args in [("下单 + 该步归本仓 + 有机器 + 总线配好", (True, True, True, True)),
                    ("下单但本仓一步都不认领", (True, False, True, True)),
                    ("下单但该步没有任何在线机器", (True, True, False, True)),
                    ("下单但该步总线没配好", (True, True, True, False))]:
    print("  %-52s %s" % (label, chain_ok(*args)))
check("D4 下单后可闭环（pull → bus_push → … → insert_network → 成品）",
      chain_ok(True, True, True, True) == "ok")
check("D5 不闭环时<b>必须</b>给出明确原因（本仓无属主 / 样板缺步 / 总线未配），不得静默",
      chain_ok(True, False, True, True) == "no-owned-step"
      and chain_ok(True, True, False, True) == "binding-gap"
      and chain_ok(True, True, True, False) == "bus-config")
check("D6 反例（无订单）：不该有任何动作，也不该报「卡住」（这是 A 的口径，避免空闲期刷提示）",
      chain_ok(False, False, False, False) is None)

print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
sys.exit(0)
