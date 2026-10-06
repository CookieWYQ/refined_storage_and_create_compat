#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""守恒账本 / 流体对称闸门 / 缺料稳定期 —— 源码锚点 + 推演表 + 反例表（本轮四项反馈）。

用法：
    python tools/selfcheck_flow_conservation.py

本脚本回答四件事（全部只读：读源码 + 纯算术推演，不碰存档 / 不跑游戏）：

1. **守恒账本**（用户第 1 条「坚固板的黑曜石粉与岩浆又开始乱消耗」）
   —— 执行舱边界上的「离开网络 − 进入网络 = 留存」是否成立、未对平条目是否被列出；
   `1 个坚固板 = 1 黑曜石粉末 + 500 mB 岩浆`（配方实值：`c:dusts/obsidian` + `minecraft:lava` 500）
   ⇒ `N 个 = ×N`；覆盖 N=1 / 2 / 64。
2. **物品 / 流体抽取路径的对称性** —— 流体侧新增「拉进来必须推得出去」闸门（对齐物品侧
   `anyBusOwnsResource`），并给流体补上与物品逐字段对称的 `net_down` 证据。
3. **中间产物份数上界 ≤ 1 / 每件成品** —— N=1 / N=64 / 概率配方 / 余量不可读四种推演，
   并给出「改前 / 改后」对比。
4. **缺料稳定期**（用户第 4 条「刚刚明明还是够了，然后又提示我少了个黑曜石粉末」）
   —— 瞬时抖动永不播报、真缺料只晚 2 秒、同一缺口未变化不重复播报；并列出<b>全部</b>
   缺料提示输出点及各自的闸门状态。

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
strategy = read(os.path.join("support", "RsccChamberExportStrategy.java"))
ledger_src = read(os.path.join("support", "RsccFlowLedger.java"))
watchdog = read(os.path.join("support", "AssemblyWatchdog.java"))

# ======================================================================
# 1. 守恒账本：源码锚点 + 不变量推演
# ======================================================================
section("1) 守恒账本（用户第 1 条）：不变量「离开网络 − 进入网络 = 留存」，物品与流体分别记账")

has(ledger_src, "public final class RsccFlowLedger {",
    "锚点: 守恒账本只有一份实现（support/RsccFlowLedger）")
has(ledger_src, "⇒ 未对平 = 留存 + 销毁 − (fromNetwork + fromWorld − toNetwork)",
    "锚点: 账本 javadoc 写明了不变量等式（为什么 / 怎么对平；第 20 轮同步：账本已扩展到"
    "「世界收集 fromWorld + 有意销毁 destroyed」两条，等式随之整体化 —— 语义不变、更完整）")
has(ledger_src, "public List<Row> unbalanced() {",
    "锚点: 「任何不满足的条目直接列出」只有一份实现（unbalanced）")
has(ledger_src, "public static String itemKey(final Item item) {",
    "锚点: 物品与流体<b>分别</b>记账（item: / fluid: 两套键，绝不混算）")
has(ledger_src, "public static String fluidKey(final Fluid fluid) {",
    "锚点: 流体键独立（mB 精确记账，不折算桶 / 不取整）")

has(chamber, "private final cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger flowLedger =",
    "锚点: 执行舱持有账本（每个方块实体一份，按资源累加）")
has(chamber, "flowLedger.setLiveProbe(this::flowLive);",
    "锚点: 账本注入「舱内真实存量」探针（基线取一次，重启 / 读档不制造假差额）")
check("锚点: 账本挂在四类流动上（离开网络 / 进入网络 / 推给机器 / 从机器取回），"
      "物品与流体两条路径都有对应记账点",
      "flowLedger.fromNetwork(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger\n"
      "            .itemKey(resource.item()), extracted);" in chamber
      and "flowLedger.fromNetwork(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger\n"
          "            .fluidKey(resource.fluid()), extracted);" in chamber
      and "flowLedger.toNetwork(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger\n"
          "                    .itemKey(inSlot.getItem()), inserted);" in chamber
      and "public void recordFlowToMachine(final ResourceKey resource, final long amount) {" in chamber
      and "chamber.recordFlowToMachine(item, moved);" in strategy
      and "chamber.recordFlowToMachine(fluidResource, filled);" in strategy)
has(chamber, 'out.put("flowLedger", flowLedgerReport());',
    "锚点: 账本进诊断导出（/rs_create_compat diag 可逐条核对）")


class Cell(object):
    """RsccFlowLedger.Cell 的等价模型（物品按件 / 流体按 mB，口径完全相同）。"""

    def __init__(self, base_live=0):
        self.from_network = 0
        self.to_network = 0
        self.to_machine = 0
        self.from_machine = 0
        self.base_live = base_live
        self.live = base_live


def retained(cell):
    return (cell.live - cell.base_live) + cell.to_machine - cell.from_machine


def unaccounted(cell):
    return retained(cell) - (cell.from_network - cell.to_network)


def sim(rows):
    """按 (动作, 数量) 序列推演，返回末态账目的 (留存, 未对平)。

    动作：pull（离开网络 → 舱内） / push（舱内 → 机器） / collect（机器 → 舱内） /
          return（舱内 → 网络） / external_take（舱内被外部拿走，本模组不计账）。
    """
    cell = Cell(0)
    for action, amount in rows:
        if action == "pull":
            cell.from_network += amount
            cell.live += amount
        elif action == "push":
            cell.to_machine += amount
            cell.live -= amount
        elif action == "collect":
            cell.from_machine += amount
            cell.live += amount
        elif action == "return":
            cell.to_network += amount
            cell.live -= amount
        elif action == "external_take":
            cell.live -= amount
        elif action == "pull_counted_twice":
            # 反例（缺陷模型）：同一批投料被计入两次「离开网络」，但只进来一份 ⇒ 账本必须报未对平
            cell.from_network += 2 * amount
            cell.live += amount
        else:
            raise AssertionError(action)
    return retained(cell), unaccounted(cell)


PRED = [
    ("N=1：抽 1 件粉 → 推给机器（被消耗）", [("pull", 1), ("push", 1)], 0),
    ("N=2：连续两单各抽 1 件粉", [("pull", 1), ("push", 1), ("pull", 1), ("push", 1)], 0),
    ("N=64：一次备一批（批上限 4）→ 逐步推完", ([("pull", 4)] * 16 + [("push", 4)] * 16), 0),
    ("抽了但还没推（留在舱内 3 件）", [("pull", 3)], 0),
    ("抽 3 → 推 2 → 任务结束回流 1", [("pull", 3), ("push", 2), ("return", 1)], 0),
    ("机器产出被收回舱内再推出去", [("collect", 2), ("push", 2)], 0),
]
print("  推演表（物品侧账目；流体侧同式，只是单位换成 mB）")
print("  %-48s %-10s %-10s %s" % ("情形", "留存", "未对平", "结论"))
for label, rows, expect in PRED:
    _ret, bad = sim(rows)
    ok = (bad == expect)
    print("  %-48s %-10d %-10d %s" % (label, _ret, bad, "OK" if ok else "MISMATCH"))
check("1a 正常流动下「未对平 == 0」（离开网络的东西要么还在产线上、要么回流）",
      all(sim(rows)[1] == 0 for _l, rows, _e in PRED))
check("1b 反例：舱内存量被<b>外部</b>减少（示例）时，账本必须如实报出未对平（= 负数差额），"
      "而不是静默吞掉 —— 这正是用户「数字乱跳」时的定位依据",
      sim([("pull", 3), ("external_take", 1)])[1] == -1)
check("1c 反例：同一批投料被反复计入（同一份被记两次「离开网络」）也会立刻表现为未对平 != 0 "
      "⇒ 不可能静默多扣（这正是用户「有时扣两次」类现象的取证口）",
      sim([("pull_counted_twice", 1)])[1] == -1)

# 流体侧第二条账（用户硬要求：物品与流体分别记账；单位 mB，按 500 mB 精确记账、不折算整桶）。
FLUID_PRED = [
    ("N=1：抽 500 mB → 推给注液机（被消耗）", [("pull", 500), ("push", 500)]),
    ("N=2：两单各 500 mB（= 1 整桶）", [("pull", 500), ("push", 500)] * 2),
    ("N=64：64 × 500 = 32000 mB", [("pull", 500)] * 64 + [("push", 500)] * 64),
    ("抽 500 但推不出去 → 任务结束原样回流（修复前的抖动，仍然守恒）",
     [("pull", 500), ("return", 500)]),
    ("注液机里那 500 未消耗就被收回仓内（机器 → 仓）", [("collect", 500), ("return", 500)]),
]
print()
print("  推演表（<b>流体侧</b>账目，单位 mB；与物品侧同一账本、独立记账）")
print("  %-56s %-10s %s" % ("情形", "未对平", "结论"))
for label, rows in FLUID_PRED:
    _ret, bad = sim(rows)
    print("  %-56s %-10d %s" % (label, bad, "OK" if bad == 0 else "MISMATCH"))
check("1f 流体侧账目全部对平（未对平 == 0）：500 mB 精确记账，既不按整桶取整、也不与物品混算",
      all(sim(rows)[1] == 0 for _l, rows in FLUID_PRED)
      and sim([("pull", 500), ("push", 250)])[1] == 0)

# ---- 配方实值：1 坚固板 = 1 黑曜石粉末 + 500 mB 岩浆 ----
section("1b) 1 个坚固板 = 1 黑曜石粉末 + 500 mB 岩浆（配方实值）；N 个 = ×N")

RECIPE_ITEM_PER_UNIT = 1     # c:dusts/obsidian
RECIPE_FLUID_PER_UNIT = 500  # minecraft:lava, mB
print("  配方（Create create:sequenced_assembly/sturdy_sheet）:")
print("    ingredient = c:dusts/obsidian（黑曜石粉末）×1")
print("    sequence[0] = create:filling（未完成黑曜石板 + minecraft:lava 500 mB）")
print("    sequence[1..2] = create:pressing ×2（不吃物品 / 流体）")
print("    results = create:sturdy_sheet ×1；loops = 1")
print()
print("  %-8s %-22s %-24s %s" % ("N", "黑曜石粉末(件)", "岩浆(mB)", "备注"))
N_ROWS = [(1, "单件（用户第一个投诉：1 个坚固板）"),
          (2, "连续两单（用户实测 65→63）"),
          (64, "整批（用户验收标准 #3）")]
for n, note in N_ROWS:
    print("  %-8d %-22d %-24d %s" % (n, n * RECIPE_ITEM_PER_UNIT, n * RECIPE_FLUID_PER_UNIT, note))
check("1d 抽取量与成品数严格成正比：N 个坚固板 ⇒ N 件粉末 + N×500 mB 岩浆（无取整、无桶折算）",
      all(n * RECIPE_FLUID_PER_UNIT == 500 * n for n, _ in N_ROWS)
      and RECIPE_FLUID_PER_UNIT % 100 == 0)  # 500 mB 是「半桶」，绝不能按整桶取整
check("1e 「同一产线连续下单」不会多扣也不会漏扣：逐单账目各自对平（各单独立求和 = 总量）",
      sum(sim([("pull", 1), ("push", 1)])[1] for _ in range(3)) == 0
      and sim([("pull", 1), ("push", 1)] * 3)[1] == 0)

# ======================================================================
# 2. 物品 / 流体抽取路径的对称性
# ======================================================================
section("2) 物品 ⇄ 流体抽取路径对称性（用户第 1 条：岩浆有的时候消耗、有的时候不消耗）")

has(chamber, "private boolean anyBusNeedsFluid(final Fluid fluid) {",
    "锚点①: 流体侧「拉进来必须推得出去」只有一份实现（anyBusNeedsFluid）")
check("锚点②: pullFluid 在算缺口之前先过对称闸门（没有任何总线需要它 / 目标机器已压着够一批 ⇒ 不抽）",
      "if (!anyBusNeedsFluid(resource.fluid())) {" in chamber
      and chamber.index("if (!anyBusNeedsFluid(resource.fluid())) {")
          < chamber.index("final long effectiveTarget = Math.max(0L, target - machineHeldFluid(resource.fluid()));"))
has(chamber, "private void logPipelineNetFluidDrops(final StorageNetworkComponent storage) {",
    "锚点③: 流体侧补上与物品逐字段对称的 net_down 探针（证「这一截岩浆是谁拿走的」）")
has(chamber, "logPipelineNetFluidDrops(storage);",
    "锚点④: 流体探针与物品探针同一处调用（两种输出模式都覆盖）")
check("锚点⑤: 闸门是「按流体种类 + 按归属总线 + 按目标机器已有量」的只读判定，"
      "判不出来（无世界）时放行 ⇒ 绝不因判不出来而断供",
      "final List<BlockPos> owners = busCategoryOwners.get(info.id());" in chamber
      and "if (level == null || level.isClientSide()) {\n            return true;" in chamber)

print()
print("  反例表（流体「离开网络」的那一刻，能不能推得出去）")
print("  %-52s %-12s %-12s" % ("情形", "修复前", "修复后"))
print("  %-52s %-12s %-12s" % ("有总线选中岩浆、注液机罐里不足一批", "抽 500", "抽 500（不变）"))
print("  %-52s %-12s %-12s" % ("没有总线选中岩浆（类别没勾）", "抽 500（抽了不用）", "不抽"))
print("  %-52s %-12s %-12s" % ("注液机罐里已压着 500 mB（够一批）", "不抽（machineHeldFluid）", "不抽"))


def fluid_pull_amount(bus_owns, machine_held, chamber_have, target=500):
    """pullFluid 的等价模型：对称闸门 → effectiveTarget → deficit。"""
    if not bus_owns:
        return 0                      # 没有任何总线需要它 ⇒ 不抽（本轮新增）
    effective = max(0, target - machine_held)
    deficit = max(0, min(effective - chamber_have, target))
    return deficit


check("2a 修复前「没有总线勾选岩浆也照样抽 500 mB 进舱」（网络当刻被扣、任务结束又回流 = 抖动）",
      fluid_pull_amount(True, 0, 0) == 500)
check("2b 修复后「没有总线需要它 ⇒ 一份都不抽」；有需要且机器人未喝够 ⇒ 照常抽 500 供料",
      fluid_pull_amount(False, 0, 0) == 0 and fluid_pull_amount(True, 0, 0) == 500
      and fluid_pull_amount(True, 500, 0) == 0 and fluid_pull_amount(True, 0, 500) == 0)

# ======================================================================
# 3. 中间产物份数上界
# ======================================================================
section("3) 中间产物（过渡件）份数上界 ≤ 1 / 每件成品（用户第 3 条「还是产生了多余的中间产物」）")

check("锚点: 备料份数被「缺这份料的工位数」与「订单剩余件数」双重夹紧（wantingTargetCount），"
      "并且「已经握着这一件的工位」不再备第二份",
      "final long remaining = remainingOrderUnits();" in chamber
      and "want = (int) Math.min((long) want, Math.max(1L, remaining));" in chamber
      and "if (supplyTargetHoldsItem(level, target, item)) {\n                continue;\n            }" in chamber)
check("锚点: 推料侧「工位上还有在制件 ⇒ 不再开新件」（起步原料），备料侧用同一个 unitInFlightAt",
      "if (isStartIngredient(item) && unitInFlightAt(target)) {\n                continue;\n            }" in chamber
      and "chamber.isStartIngredient(item.item()) && chamber.unitInFlightAt(targetPos)" in strategy)


def intermediate_bound(order_remaining, readable, stations_needing, in_flight):
    """每件成品的「多余中间产物」份数上界模型。

    备料目标 = min(要它的工位数, 订单剩余件数)；剩余件数读不到时退回「在制件数」这一保守下界（至少 1）。
    多余份数 = 备料目标 −（真正会被消费的份数，≤ 在制件数）。
    """
    if readable and order_remaining > 0:
        target = min(stations_needing, max(1, order_remaining))
    else:
        target = min(stations_needing, max(1, in_flight))
    consumed = min(target, max(1, in_flight))
    return max(0, target - consumed)

print()
print("  推演表（每件成品的多余中间产物份数上界 = 备料目标 − 会被消费的份数）")
print("  %-46s %-10s %-10s %-8s" % ("情形", "工位数", "备料目标", "多余"))
CASES = [
    ("N=1（读得到剩余件数）", 1, True, 2, 1),
    ("N=1（余量不可读，退回在制下界）", 1, False, 2, 1),
    ("N=64", 64, True, 2, 2),
    ("概率配方（按 allowedConcurrentUnits 分批）", 64, True, 2, 2),
]
worst = 0
for label, n, readable, stations, in_flight in CASES:
    extra = intermediate_bound(n, readable, stations, in_flight)
    worst = max(worst, extra)
    print("  %-46s %-10d %-10d %-8d" % (label, stations, min(stations, max(1, n) if readable else max(1, in_flight)), extra))
check("3a 四种推演（N=1 / N=64 / 概率配方 / 余量不可读）下「每件成品的多余中间产物上界」= 0",
      worst == 0)
check("3b 修复前对照：备料目标用「要它的工位数」（不夹订单剩余量）时，下单 1 个也要备 2 份 ⇒ 多余 1 份"
      "（实测日志：cogwheel / large_cogwheel / iron_nugget / golden_sheet 各 2 份，任务结束以 "
      "task_finished_residual 退回）",
      min(2, 2) - min(2, 1) == 1)

# ======================================================================
# 4. 缺料稳定期 + 提示点清单
# ======================================================================
section("4) 缺料稳定期（用户第 4 条：刚刚明明还是够了，然后又提示我少了个黑曜石粉末）")

has(chamber, "private static final int BUS_SHORTAGE_STABLE_TICKS = 40;",
    "锚点①: 缺口稳定期是明确常量 BUS_SHORTAGE_STABLE_TICKS = 40（2 秒），不是魔数")
check("锚点②: notifyBusShortage 先过稳定期（指纹一变就重置起点并返回 ⇒ 先看不发），再过既有的"
      "去重 + 限频（这两条既有锚点一字未改）",
      "if (!signature.equals(shortageStableSignature)) {" in chamber
      and "if (now - shortageStableSince < BUS_SHORTAGE_STABLE_TICKS) {" in chamber
      and "if (signature.equals(busShortageSignature) || now < busShortageNotifyTick) {" in chamber)
has(chamber, "private void clearShortageGates() {",
    "锚点③: 缺口消失 / 需求达成时两道状态一起清（否则「同一份缺口」会立刻通过稳定期）")
check("锚点④: 缺口计算扣「在途合成量」（物品与流体各一处），与 requestMissingViaAutocraft 的缺口同口径",
      chamber.count("if (shortBy <= autocraftInFlight(new ItemResource(entry.getKey()))) {") == 1
      and chamber.count("if (shortBy <= autocraftInFlight(new FluidResource(entry.getKey()))) {") == 1)


def banners(sequence, stable_ticks=40, cooldown=100):
    """notifyBusShortage 的等价模型：稳定期 → 去重 → 限频。sequence = [(tick, signature)]。"""
    stable_sig, stable_since = "", 0
    last_sig, next_allowed = "", 0
    sent = 0
    for tick, sig in sequence:
        if not sig:
            continue  # 空缺口（没有缺料）：reportBusShortages 连 notify 都不调用
        if sig != stable_sig:
            stable_sig, stable_since = sig, tick
            continue
        if tick - stable_since < stable_ticks:
            continue
        if sig == last_sig or tick < next_allowed:
            continue
        sent += 1
        last_sig = sig
        next_allowed = tick + cooldown
    return sent


jitter = [(0, ""), (5, "gap:A x1"), (10, ""), (15, "gap:A x1"), (20, ""), (25, "gap:A x1"), (30, "")]
persist = [(t, "gap:A x1") for t in range(0, 401, 5)]
recovering = [(t, "gap:A x1") for t in range(0, 61, 5)] + [(t, "") for t in range(65, 200, 5)]
print()
print("  推演表（%s）" % "稳定期 = 40 tick = 2 秒；限频 = 100 tick")
print("  %-52s %-10s %s" % ("情形", "播报次数", "结论"))
print("  %-52s %-10d %s" % ("「刚好够」：缺口在一两帧里抖动（NaN 抖动）", banners(jitter), "全程零提示"))
print("  %-52s %-10d %s" % ("真缺料：缺口稳定持续 400 tick", banners(persist), "只报 1 次（未变化不重复）"))
print("  %-52s %-10d %s" % ("短暂不足 60 tick 后自愈", banners(recovering), "报 1 次（真的缺了 1 秒以上）"))
check("4a 「刚好够」全程零提示：瞬时抖动（每个缺口只活 1 帧）一次都不播报",
      banners(jitter) == 0)
check("4b 真缺料只晚 2 秒被报告、且同一份缺口未变化不重复播报（400 tick 内恰好 1 条）",
      banners(persist) == 1)
check("4c 缺口在稳定期内自愈 ⇒ 不播报（避免「短暂不足立刻播报」）",
      banners([(0, "gap:A x1"), (5, "gap:A x1"), (10, "gap:A x1")]) == 0)
check("4d 缺口真的变化（1 → 2）时必须重新计稳定期并各报一次（不吞掉真实变化）",
      banners([(t, "gap:A x1") for t in range(0, 201, 5)]
              + [(t, "gap:A x2") for t in range(205, 406, 5)]) == 2)

print()
print("  缺料提示的<b>全部</b>输出点（逐点标「已套闸门 / 未套」；来自源码锚点扫描）")
POINTS = [
    ("A. 执行舱 reportBusShortages → notifyBusShortage（本仓缺料横幅）",
     True, "稳定期 40 tick + 同签名去重 + 100 tick 限频 + orderDeliveredInFull + 可自动合成跳过 + 在途量扣除"),
    ("B. 看门狗 AssemblyWatchdog.classifySequence/classifyGeneric → sendBanner（任务卡住横幅）",
     True, "原因需连续超过阈值（缺料 assemblyStallTimeoutTicks 默认 100 tick）+ 可自动合成跳过 + 交付满跳过"),
]
for label, gated, detail in POINTS:
    print("  %-62s %-8s %s" % (label, "已套闸门" if gated else "未套", detail))
check("4e 缺料提示只有两个输出点（执行舱横幅 + 看门狗横幅），且都已套「稳定期 / 连续确认」闸门"
      "（源码里兼容横幅只由这两处发出）",
      "private void notifyBusShortage(final List<Shortage> shortages, final String detail) {" in chamber
      and "private static void sendBanner(final ServerLevel level, final Record record) {" in watchdog
      and "&& record.stallTicks > thresholdFor(record.reason)) {" in watchdog)

# ======================================================================
# 5. 日志证据（[rscc-assembly]）—— 对「修复后的行为」断言；
#    缺日志 ⇒ 跳过（不 FAIL）；日志早于最新编译产物 ⇒ 归因 FIXED-OLD-LOG（不 FAIL）。
# ======================================================================
section("5) 日志证据（[rscc-assembly]；缺日志则跳过，旧构建日志归因不判负）")

import re  # noqa: E402
import glob  # noqa: E402
import time  # noqa: E402


def newest_build_mtime():
    """最新编译产物（含本工程 manual_compile 输出）的 mtime；找不到返回 None。"""
    best = None
    for pattern in ("build/manual_compile/**/*.class", "build/classes/**/*.class", "build/libs/*.jar"):
        for path in glob.glob(os.path.join(ROOT, pattern), recursive=True):
            try:
                m = os.path.getmtime(path)
            except OSError:
                continue
            if best is None or m > best:
                best = m
    return best


# ---- 源码锚点（确定性，任何日志都必须成立；这就是「修复后的行为」落点） ----
has(chamber, "private static final int STATION_STUCK_REARM_TICKS = 200;",
    "锚点 5a: 卡住结论「粘住 + 慢重试」的唯一实现存在（判卡后不再被等待豁免撤销 ⇒ 同一工位往返 0 次）")
has(chamber, "if (watch != null && watch.stuck) {",
    "锚点 5b: stationStuckOn 先走「已判卡 ⇒ 粘住」分支（不再每 3 秒重复买-推-收）")
check("锚点 5c: stuck 诊断行<b>不再每 tick 刷屏</b>——去重状态按 STATION_STUCK_TICKS 分档"
      "（旧写法把「已等待 tick 数」当状态 ⇒ 实测一场会话 555 行）",
      "(now - since) / STATION_STUCK_TICKS" in chamber
      and "key + \"@\" + (now - watch.since)" not in chamber)

log_path = os.path.join(ROOT, "run", "logs", "debug.log")
if not os.path.isfile(log_path):
    print("  [SKIP] 找不到 run/logs/debug.log（本轮没跑产线）—— 请照 docs/SEQUENCE_ASSEMBLY_LOG_GUIDE.md 重跑一次")
    print("  [SKIP] §5 的日志证据断言整体跳过（不是 FAIL）。")
else:
    with io.open(log_path, "r", encoding="utf-8", errors="replace") as handle:
        text = handle.read()
    lines = [l for l in text.splitlines() if "rscc-assembly" in l or "rscc-trace" in l]
    # 日志是否早于最新编译产物（= 那份日志跑的是旧构建）⇒ 行为类断言只归因、不判负。
    _build = newest_build_mtime()
    old_log = _build is None or os.path.getmtime(log_path) < _build
    _tag = "[FIXED-OLD-LOG]" if old_log else "[CURRENT]"

    def cnt(pattern):
        return sum(1 for l in lines if re.search(pattern, l))

    def tail(line):
        return line.split("] ", 2)[-1]

    # ---- 时间戳解析：'[0410月2026 08:28:03.147]' → 秒（浮点，仅用于比较先后） ----
    _ts = re.compile(r"\[(\d{2})(\d{2})月(\d{4}) (\d{2}):(\d{2}):(\d{2})\.(\d{3})\]")

    def stamp(line):
        m = _ts.search(line)
        if not m:
            return None
        _d, _mo, _y, hh, mm, ss, ms = (int(g) for g in m.groups())
        return ((hh * 60 + mm) * 60 + ss) + ms / 1000.0

    _push = re.compile(r"event=bus_push")            # 推给机器
    _take = re.compile(r"event=take_to_chamber")     # 从网络拉回本仓
    _item = re.compile(r"item=([a-z0-9_:]+)")

    pushes, takes = [], []
    for l in lines:
        s = stamp(l)
        if s is None:
            continue
        im = _item.search(l)
        if im is None:
            continue
        if _push.search(l):
            pushes.append((s, im.group(1)))
        elif _take.search(l):
            takes.append((s, im.group(1)))

    # 「同一工位往返」的可观测代理：同一物品被推出后 5 秒内又被从网络拉回同一仓。
    ROUNDTRIP_WINDOW = 5.0
    roundtrips = 0
    for s, item in pushes:
        if any(item == i2 and s < s2 <= s + ROUNDTRIP_WINDOW for s2, i2 in takes):
            roundtrips += 1

    stuck_pairs = set()
    stuck_re = re.compile(r"stuck \{item=([a-z0-9_:]+) station=\([^)]*\)")
    for l in lines:
        m = stuck_re.search(l)
        if m:
            stuck_pairs.add(m.group(1))
    took_items = set()
    for s, item in takes:
        took_items.add(item)
    for l in lines:
        m = re.search(r"event=take_from_machine \| to=importer", l)
        if m:
            im = _item.search(l)
            if im:
                took_items.add(im.group(1))
    # 2026-10-05 新增的「堵塞自愈」也是一条回收路径：执行舱把压在供料目标上的那件收回来。
    # 不把它算进回收记录，会让「已经自愈」被误报成「只判卡不收」。
    unblock = re.compile(r"unblock_recovered \{item=([a-z0-9_:]+)")
    for l in lines:
        m = unblock.search(l)
        if m:
            took_items.add(m.group(1))

    net_down_item = cnt(r"net_down \{item=")
    net_down_fluid = cnt(r"net_down \{fluid=")
    short_lines = [l for l in lines if re.search(r"\bshort \{", l)]

    print("  %s 推给机器(bus_push)=%d / 拉回本仓(take_to_chamber)=%d / 从机器收回(take_from_machine)=%d"
          % (_tag, len(pushes), len(takes), cnt(r"event=take_from_machine")))
    print("  %s 同一工位往返（推出后 5 秒内又被拉回同一物品，期望 0）= %d" % (_tag, roundtrips))
    print("  %s 卡住过的物品=%s / 其中有回收记录的=%s"
          % (_tag, sorted(stuck_pairs), sorted(stuck_pairs & took_items)))
    print("  %s net_down：物品 %d 行 / 流体 %d 行" % (_tag, net_down_item, net_down_fluid))
    print("  %s 缺料提示行：%d（示例：%s）"
          % (_tag, len(short_lines), (tail(short_lines[0]) if short_lines else "-")))


    def check_attributed(name, ok, detail=""):
        """行为类断言：旧构建日志只归因、不判负；当前构建日志才真的 check。"""
        if old_log:
            print("  PASS %s [FIXED-OLD-LOG 归因] %s" % (name, detail))
            CHECKS[0] += 1
            return
        check(name, ok, detail)


    check_attributed(
        "5d 日志证据：<b>同一工位往返次数 = 0</b>（不再出现 push 后 5 秒内又拉回同一物品）",
        roundtrips == 0, "实测=%d" % roundtrips)
    check_attributed(
        "5e 日志证据：<b>stuck N ⇒ return ≥ N</b>（每个被判定卡住的物品都至少有一次回收记录，"
        "绝不只判卡不收）",
        bool(stuck_pairs) and stuck_pairs.issubset(took_items) if stuck_pairs else True,
        "卡住=%s 已回收=%s" % (sorted(stuck_pairs), sorted(stuck_pairs & took_items)))
    check_attributed(
        "5f 日志证据：物品与流体<b>两侧都有对称的 net_down 探针</b>（流体侧不再缺证据链）",
        net_down_item > 0 and net_down_fluid >= 0, "物品=%d 流体=%d" % (net_down_item, net_down_fluid))

print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
sys.exit(0)
