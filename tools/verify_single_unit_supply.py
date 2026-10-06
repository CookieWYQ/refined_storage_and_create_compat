#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""「单机一份」供料不变式 + 本轮四项验收 校验器（源码锚点 + 真实日志 + 账目明细）。

用法：
    python tools/verify_single_unit_supply.py

本轮（用户四条反馈）要回答的四件事
----------------------------------
① 「序列装配的还是没有正常」 —— 序列装配链路本身是否真的走完（RS 任务 RUNNING→COMPLETED），
   以及「同一单里多投一份原料 / 中间产物在仓间残留」是否已被份额闸门收敛。
② 「齿轮还是堵塞」 —— 分清「每 tick 重复打印的稳态噪声（{@code same cause repeated n}）」
   与「真实的无效往返」（同一坐标、同一件、5 秒内被收回又被推回）。
③ 「我怀疑岩浆没有被正常消耗」 —— 岩浆收支账目表（抽取 / 推送 / 收回 / 回流），判定是否守恒、
   是否多抽、是否抽了不用。
④ 「一个一个下单的时候，中间产物还是存在额外之类的」 —— N=1 时投入物的产出 / 消耗 / 残留是否守恒。

取证口径（本轮修正，务必读）
----------------------------
`run/logs/latest.log` 常常是**刚启动、还没跑产线**的会话（只有 `strategy_installed` 建链行）。
因此本脚本按下面的顺序选一份**真正有生产事件**的日志来判定，并**明确打印选了哪一份、为什么**：

1. `run/logs/latest.log` —— 有生产事件就用它；
2. 否则回退到 `run/logs/` 下**最新的、含生产事件**的轮转会话（`*.log.gz`，按生产事件数取最丰富者）。

判定分两层，**两层都真的跑、都打印实测值，绝不"跳过"**：

* **硬不变式（H）**：任何日志都必须成立（不复制 / 不销毁 / 不成对刷屏 / 无真实往返）→ PASS / FAIL；
* **修复后行为（F）**：只有"日志确实晚于当前编译产物"才有意义。若日志的生产事件**早于**最新
  编译产物（= 那份日志跑的是旧构建），本脚本把它标成 `FIXED-OLD-LOG` 并**打印实测值**，
  而不是假装通过 —— 用户重跑一次产线后，同样的断言会自动变成 PASS / FAIL。

退出码：0 = 没有任何 FAIL；1 = 有 FAIL。

⚠ 本轮修正的「假绿」（2026-10-04 审计 §7.3 的遗留问题）
------------------------------------------------------
旧实现有两处会把「什么都没查」当成 OK：
  ① `run/logs` 下**没有任何日志**时，打印 `SELFCHECK OK (n checks，日志断言无数据可判)` 并 **exit 0**；
  ② 日志里**没有 rscc 诊断行**时，同样打印 `SELFCHECK OK (...)` 并 **exit 0**。
两者都会让「没跑过游戏」看起来像「一切正常」。

另外旧实现把「日志会话早于本次构建」的所有修复后断言降级为 `FIXED-OLD-LOG`（`hard=not stale`），
于是**用一份跑的是旧代码的日志**运行本脚本也永远不会 FAIL —— 这正是上一轮把「晚于修复代码的日志」
误当成验证证据的机制。

现在的口径：
  * 无日志 / 无 rscc 行 ⇒ **FAIL**（要显式传 `--allow-empty` 才降级为跳过，供纯编码 / CI 场景）；
  * 日志会话早于本次构建 ⇒ 打印 `BASELINE-ONLY` 横幅 + 一条**失败**（`B 日志不得早于本次构建`），
    要显式传 `--allow-stale` 才允许只看基线；
  * 新增一条断言：日志里必须出现启动指纹行 `[rscc-build]`（否则无法证明这份日志对应哪一版代码，
    见 `tools/verify_build_stamp.py`）。
"""

from __future__ import annotations

import collections
import datetime
import glob
import gzip
import io
import json
import os
import re
import sys
import time
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# Windows 控制台默认 GBK：中文 / 箭头字符会直接抛 UnicodeEncodeError 把校验打断。
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
LOGDIR = os.path.join(ROOT, "run", "logs")
CLASSES = os.path.join(ROOT, "build", "manual_compile")
BUILD_INFO = os.path.join(ROOT, "src", "main", "resources", "build_info.properties")

# 命令行开关（见模块 docstring 的「假绿修正」）：
#   --allow-empty : 没有日志时不算失败（CI / 只跑源码锚点）
#   --allow-stale : 允许用「早于本次构建」的会话日志只看基线（修复后断言不判 FAIL）
ARGV = set(sys.argv[1:])
ALLOW_EMPTY = "--allow-empty" in ARGV
ALLOW_STALE = "--allow-stale" in ARGV

FAILURES = []
CHECKS = [0]

REP = re.compile(r"same cause repeated (\d+) times in 5s")
STAMP = re.compile(r"(\d{2}):(\d{2}):(\d{2})\.(\d{3})")
TRACE_ITEM = re.compile(r"\bitem=([a-z_0-9:]+)|fluid=([a-z_0-9:]+)")
EVENT = re.compile(r"event=([A-Za-z_0-9]+)")
REASON = re.compile(r"reason=([^\s|]+)")

# 本轮的三个「过渡件」（Create 序列装配的未完成件），用于断言⑤。
TRANSITION_MARKERS = ("create:incomplete_precision_mechanism", "create:unprocessed_obsidian_sheet",
                      "create:incomplete_")
# 「持续性无效重复」的三种因由（本轮要压下去的关键 reason）。
SPIN_REASONS = ("machine_busy_with_my_step", "chamber_empty", "machine_full")
# 关键 reason 的同因合并上限：稳态下一次清单重算最多一次，5 秒窗口 ≤ 8。
SPIN_REPEAT_MAX = 8
# 生产事件的判据（有这些 event 才算「真的跑过产线」）。
PRODUCTION_EVENTS = ("bus_push", "insert_network", "take_to_chamber", "bus_skip",
                     "handover_to_chamber", "take_from_machine")
# ④ 账目里关注的关键物品。
KEY_ITEMS = ("create:powdered_obsidian", "create:golden_sheet", "create:sturdy_sheet",
             "create:precision_mechanism", "create:unprocessed_obsidian_sheet",
             "create:incomplete_precision_mechanism", "create:cogwheel",
             "create:large_cogwheel", "minecraft:iron_nugget")


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(name, ok, detail="", hard=True):
    """hard=True 的失败计入退出码；hard=False 的只打印（用于「日志早于构建」的修复后行为）。"""
    CHECKS[0] += 1
    if not ok and hard:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else ("FAIL" if hard else "FIXED-OLD-LOG"),
                       name, (" | " + detail) if detail else ""))


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def body(text, start, end):
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


def weight(line):
    m = REP.search(line)
    return int(m.group(1)) if m else 1


def stamp(line):
    """日志行 → 当天的秒级时间戳（用于「同一秒 / 5 秒窗口」判定）。"""
    m = STAMP.search(line)
    if not m:
        return -1.0
    return (int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))
            + int(m.group(4)) / 1000.0)


def item_of(line):
    m = re.search(r"\bitem=([a-z_0-9:]+)", line)
    return m.group(1) if m else None


def bus_of(line):
    m = re.search(r"from=(exporter@\([-\d,]+\))", line)
    return m.group(1) if m else None


def machine_of(line):
    m = re.search(r"(?:event=bus_push \| )?to=(machine@\([-\d,]+\))", line)
    return m.group(1) if m else None


def machine_from_of(line):
    """推料行里的目标机器坐标（仅坐标，便于判「同一台机器」的往返）。"""
    m = re.search(r"to=machine@(\([-\d,]+\))", line)
    return m.group(1) if m else None


def machine_src_of(line):
    m = re.search(r"from=machine@(\([-\d,]+\))", line)
    return m.group(1) if m else None


def chamber_of(line):
    m = re.search(r"(chamber@\([-\d,]+\))", line)
    return m.group(1) if m else None


def num(line):
    m = re.search(r"\bx(\d+)\b", line.split("event=")[0])
    return int(m.group(1)) if m else 0


# ==================== A. 源码锚点（硬断言） ====================

chamber = read(os.path.join(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java"))
export_strategy = read(os.path.join(SRC, "support", "RsccChamberExportStrategy.java"))
import_strategy = read(os.path.join(SRC, "support", "RsccChamberImportStrategy.java"))


def strip_java_comments(text):
    """去掉 Java 的行注释与块注释（保留字符串字面量不动）。

    为什么必须去注释后再做「锚点计数」：本脚本多处断言形如
    ``count("return residualEdge || !inputItems.contains(...)") == 1``。
    而代码的历史注释里会**逐字引用**那些语句（用于解释为什么写成那样），
    于是计数变成 2，脚本报 FAIL —— 但真实代码只有一处，脚本失败了却
    「其实没问题」。反之，若注释里恰好少了那行，脚本又可能假绿。
    两种都是「数注释」造成的噪声，所以这里统一在**去注释后的代码**上计数。
    """
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        if c == '"':  # 字符串字面量：整段照搬，避免把 // 当注释
            out.append(c)
            i += 1
            while i < n:
                if text[i] == "\\" and i + 1 < n:
                    out.append(text[i:i + 2])
                    i += 2
                    continue
                out.append(text[i])
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


import_code = strip_java_comments(import_strategy)
watchdog = read(os.path.join(SRC, "support", "AssemblyWatchdog.java"))

section("A1) 工位归并 +「握着料 = 正在干活」只有一份实现，且被两处调用")

has(chamber, "private static BlockPos stationKey(final Level level, final BlockPos target) {",
    "锚点: 物理工位 = 机械手朝向 2 格外的操作对象（stationKey），只有一份实现")
has(chamber, "private Map<BlockPos, BlockPos> supplyStations(final Level level) {",
    "锚点: 供料目标按工位归并（supplyStations），只有一份实现")
has(chamber, "private boolean supplyTargetHoldsItem(final Level level, final BlockPos target, final Item item) {",
    "锚点: 「这台机器手上还有未被消耗的投入物」只有一份实现（整工位一起看）")
has(chamber, "private static boolean holdsItemAt(final Level level, final BlockPos pos, final Item item) {",
    "锚点: 「这一格压着这件物品」只有一份只读实现")

wanting = body(chamber, "private int wantingTargetCount(", "/**\n     * 「步骤专用投入物」的备料量上限")
check("备料目标量按工位遍历（supplyStations）而不是逐格遍历 —— 同一工位只算一份",
      "supplyStations(level)" in wanting and "busSupplyTargets()" not in wanting)
check("已经握着这一件的工位不再备第二份（= 同一台机器同一时刻最多一份未消耗投入物）",
      "if (supplyTargetHoldsItem(level, target, item)) {\n                continue;\n            }" in wanting)
check("原有口径逐字保留：判不出来仍按 1 份（绝不因判不出来而断供）、非步骤专用投入物返回 0",
      "return undecidable ? 1 : 0;" in wanting)

section("A2) 断言② 主原料精确消耗：起步原料的闸门按「工位有没有在制件」判，推料侧与备料侧只留一份判定")

has(chamber, "public boolean unitInFlightAt(@org.jetbrains.annotations.Nullable final BlockPos target) {",
    "锚点: 「工位上有在制件」只有一份实现（unitInFlightAt，含机械手自身与它的操作对象）")
has(chamber, "if (isStartIngredient(item) && unitInFlightAt(target)) {\n                continue;\n            }",
    "锚点: 备料侧（wantingTargetCount）对起步原料在「工位有在制件」时不再备第二份")
has(chamber, "if (info.isInput() && isStartIngredientCategory(info)",
    "锚点: 导出清单侧（busExportFilters）对起步原料在「工位有在制件」时不下发该类别")
transfer_item = body(export_strategy, "private TransferOutcome transferItem(",
                     "private static boolean holdsSameItem(")
check("锚点: 推料侧（RsccChamberExportStrategy#transferItem）的起步原料闸门改用 unitInFlightAt"
      "（不再用只看「下一步归本仓」的 stepUnitInFlightOn —— 那正是跨仓产线多投一份主原料的漏点）",
      "chamber.isStartIngredient(item.item()) && chamber.unitInFlightAt(targetPos)" in transfer_item
      and "chamber.stepUnitInFlightOn(targetPos)" not in transfer_item)
check("锚点: 「机器还在加工这一步的件时不再开新件」的拒绝原因标签保持 machine_busy_with_my_step"
      "（行为标签不变，便于前后对比）",
      '"machine_busy_with_my_step"' in transfer_item)

section("A2b) ② 齿轮堵塞：份额外的总线不再抢「正在干活」的工位（本轮新增）")

check("锚点: 「工位上压着本仓产线的一件东西 ⇒ 它正在干活 ⇒ 绝不让位」只有一份实现"
      "（machineBusyOnPipeline：机械手手里握着本步的另一样投入物时，不会被误判成「干不了活」"
      "而把这一件推给第二台用不上它的机器）",
      "private boolean machineBusyOnPipeline(final Level level, final BlockPos target) {" in chamber)
busy_zone = body(chamber, "private boolean shareFrontBusyFor(", "private boolean machineBusyOnPipeline(")
check("锚点: 让位判据（shareFrontBusyFor）把 machineBusyOnPipeline 排在 targetCanAccept 之后、"
      "stepUnitInFlightOn 之前，且结论是「不让位」（返回 false）",
      "if (machineBusyOnPipeline(level, target)) {\n                return false;\n            }" in busy_zone
      and busy_zone.index("machineBusyOnPipeline(level, target)")
          > busy_zone.index("targetCanAccept(level, target, item)"))
check("锚点: 判据只认「本仓类别里的物品」（外来杂物不算「在干活」⇒ 仍会走让位分支，不会死锁）",
      "for (final BusCategoryInfo info : busCategories()) {" in body(
          chamber, "private boolean machineBusyOnPipeline(",
          "/**\n     * 只读：目标机器此刻能否收下这一份物品"))

section("A3) ③ 岩浆：流体侧补齐与物品侧对称的「一次一份」闸门（本轮新增）")

check("锚点: 流体能力查询对执行舱公开（fluidHandlerAt 由 private 改 public）—— 免得出现"
      "「收回读得到、闸门读不到」的两套口径",
      "public static IFluidHandler fluidHandlerAt(final Level level, final BlockPos pos) {" in import_strategy)
check("锚点: 「机器上还压着够一批的同类流体 ⇒ 本类别这一轮不交给总线」只有一份只读实现"
      "（holdsInputFluidAt，busExportFilters 里对纯流体类别生效）",
      "private boolean holdsInputFluidAt(final Level level, final BlockPos exporterPos," in chamber
      and "holdsInputFluidAt(level, exporterPos, info.fluids(), Math.max(1L, info.amount()))" in chamber)
check("锚点: 备料侧同样按「机器侧已经压着多少」扣目标量（machineHeldFluid → pullFluid 的 "
      "effectiveTarget），因此「推进注液机 → 没消耗 → 又抽一份」不会发生（抽了不用的根因）",
      "private long machineHeldFluid(final Fluid fluid) {" in chamber
      and "final long effectiveTarget = Math.max(0L, target - machineHeldFluid(resource.fluid()));" in chamber)
check("锚点: 两条流体闸门都只读到「本总线自己的目标机器」（supplyTargetOf），不用全仓并集",
      "final BlockPos target = supplyTargetOf(level, exporterPos);\n        return target != null"
      " && machineHeldFluidAt(level, target, fluids) >= perBatch;" in chamber)

section("A4) ④ 中间产物不多出：备料份数受「订单剩余件数」约束（本轮新增）")

check("锚点: 备料目标量（wantingTargetCount）在算完「几个工位要它」之后，再用 remainingOrderUnits() "
      "夹一道份额（下单 1 个 ⇒ 只备 1 份，多出来的那份不再抽进仓）",
      "final long remaining = remainingOrderUnits();" in wanting
      and "want = (int) Math.min((long) want, Math.max(1L, remaining));" in wanting)
check("锚点: 份额判不出来（remaining ≤ 0，例如没有本仓相关任务）时退回既有行为，绝不因判不出来而断供",
      "if (remaining > 0L) {" in wanting and "return want;" in wanting)

section("A5) 断言③ 缺料必提示：只在真的缺料时发，且限频 + 中文单条 ≤ 40 字 + 中英成对")

has(chamber, "private void reportBusShortages(final StorageNetworkComponent storage,",
    "锚点: 缺料上报只有一个入口（reportBusShortages）")
check("锚点: 缺料上报不受诊断开关影响（关掉 [rscc-assembly] 也必须照样弹提示）",
      "if (RsccAssemblyDebug.isEnabled()) {\n            reportBusShortages" not in chamber
      and "reportBusShortages(storage, itemTargets, fluidTargets, intermediateOnly(intermediateItems));"
          in chamber)
has(chamber, "BUS_SHORTAGE_NOTIFY_INTERVAL_TICKS = 100;",
    "锚点: 同一份缺口最多每 5 秒（100 tick）提示一次（限频，不刷屏）")
check("锚点: 缺料统计不含「纯中间产物」（用户：提示缺少原料时要自动忽略中间产物；真实原料缺料照旧会报）",
      "private Set<Item> intermediateOnly(final Set<Item> intermediateItems)" in chamber
      and "result.removeAll(realInputs);" in chamber)
has(watchdog, "if (chamber.hasInFlightUnit()) {",
    "锚点: 看门狗的「本仓还在加工吗」改用「有没有在制件」")
has(chamber, "public boolean hasInFlightUnit() {",
    "锚点: 「本仓真的在加工」只有一份实现")

zh_lang = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
en_lang = json.load(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))
shortage_keys = [k for k in zh_lang if k.startswith("gui.rs_create_compat.assembly.shortage.")]
check("锚点: 缺料横幅的 4 条语言键中英成对存在（title / entry.item / entry.fluid / more）",
      len(shortage_keys) == 4 and all(k in en_lang for k in shortage_keys),
      "zh=%s" % sorted(shortage_keys))
check("锚点: 缺料横幅中文单条 ≤ 40 字",
      all(len(zh_lang[k]) <= 40 for k in shortage_keys),
      "%s" % [(k, len(zh_lang[k])) for k in shortage_keys])
check("锚点: 物品条目译文带单位「个」，英文带 pcs",
      "个" in zh_lang["gui.rs_create_compat.assembly.shortage.entry.item"]
      and "pcs" in en_lang["gui.rs_create_compat.assembly.shortage.entry.item"])
check("锚点: 流体条目译文带单位 mB（中英一致）",
      "mB" in zh_lang["gui.rs_create_compat.assembly.shortage.entry.fluid"]
      and "mB" in en_lang["gui.rs_create_compat.assembly.shortage.entry.fluid"])
check("锚点: 旧的无单位条目键已彻底移除（不留两套语义）",
      "gui.rs_create_compat.assembly.shortage.entry" not in zh_lang
      and "gui.rs_create_compat.assembly.shortage.unit.item" not in zh_lang)

section("A6) 断言⑤ 最终交付不含过渡件 + ① 日志口径（本轮新增/修正）")

check("锚点: 「任务刚结束的一次性边沿」不再对未完成件放行（用户硬要求：最终回流 / 交付里不得有半成品）",
      import_code.count("return chamber.isTransitionReclaimAllowed(stack);") == 2)
check("锚点: 一次性残留回流令牌仍然只作用于「输入类原料」（岩浆那类残留的回流一字未改）",
      import_code.count("return residualEdge || !inputItems.contains(stack.getItem());") == 1
      and import_code.count("return residualEdge;") == 2)
check("锚点: ① claimed_by_task 口径改用「本次插入的网络增量」（landed）而不是「插入后的网络总存量」"
      "—— 旧写法几乎恒为 false，会把 RS 已记账的任务产出也误报成 claimed_by_task=no（本轮修正）",
      "final boolean claimed = !transition && landed < reportedInserted;" in import_strategy
      and "&& net < reportedInserted;" not in import_strategy)

# ==================== B. 选一份「真的有生产事件」的日志 ====================

section("B) 取证日志的选择（选中谁 / 为什么 / 这次会话是不是跑在本次构建之后）")


def read_any(path):
    try:
        if path.endswith(".gz"):
            with gzip.open(path, "rt", encoding="utf-8", errors="replace") as fh:
                return fh.read()
        return read(path)
    except Exception:  # noqa: BLE001 - 读不了就当不存在
        return ""


def production_count(lines):
    n = 0
    for line in lines:
        if "rscc-trace" not in line:
            continue
        m = EVENT.search(line)
        if m and m.group(1) in PRODUCTION_EVENTS:
            n += 1
    return n


# 日志时间戳的两种写法（同一份 log4j 配置在不同 JVM 语言环境下会给出不同前缀）：
#   [0110月2026 00:16:59.615] ...   （含日期：日 月 年 + 时分秒）
#   [00:16:59] ...                  （只有时分秒 —— 轮转日志用文件名里的日期补）
LOG_DATETIME = re.compile(r"\[(\d{2})(\d{2})月(\d{4}) (\d{2}):(\d{2}):(\d{2})")
LOG_TIMEOFDAY = re.compile(r"\[(\d{2}):(\d{2}):(\d{2})")
DATE_IN_NAME = re.compile(r"(\d{4})-(\d{2})-(\d{2})")


def session_start(path, lines):
    """该会话的 <b>JVM 启动时刻</b>（datetime）—— 取日志首行的时间戳；取不到返回 None。

    为什么要「会话起点」而不是「日志文件写盘时间」：轮转日志（*.log.gz）是在会话<b>结束时</b>
    落盘的，它的 mtime 比会话内部真实的时间晚得多（甚至可能晚于本次构建），据此判定
    「日志是不是跑在当前构建之后」会把<old>b>旧构建的会话误判成新会话</b>（用户抱怨的
    「脚本老说日志不够 / 日志早于构建」就是这一类误判）。会话起点是日志第一行的时间，
    它才是「这次 JVM 是什么时候起来的」。
    """
    for line in lines[:60]:
        m = LOG_DATETIME.search(line)
        if m:
            day, month, year, hour, minute, second = (int(g) for g in m.groups())
            try:
                return datetime.datetime(year, month, day, hour, minute, second)
            except ValueError:
                return None
    for line in lines[:60]:
        m = LOG_TIMEOFDAY.search(line)
        if m:
            hour, minute, second = (int(g) for g in m.groups())
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


def debug_switch_note(lines):
    """从日志里读出「开发日志总开关」的真实状态（供报告自解释）。

    2026-10-06（round48）更新：开关已统一为配置 {@code devLogs}，<b>默认 false（发布版安静）</b>，
    因此「日志里没有诊断行」现在最常见的解释就是「开关没开」——玩家需要执行
    {@code /rs_create_compat devlogs on} 或把配置改成 true，再重跑产线。
    开关行本身有两种历史写法（新 {@code devLogs=} / 旧 {@code debug=}），两种都认。
    """
    for line in lines:
        if PREFIX_LINE in line and ("devLogs=" in line or "debug=" in line):
            m = re.search(r"(?:devLogs|debug)=(on|off) \(source=([a-z]+)\)", line)
            if m:
                return "devLogs=%s（来自 %s）" % (m.group(1), m.group(2))
    return "未在日志里检出开关行 ⇒ 按默认处理（默认 off，需 /rs_create_compat devlogs on）"


PREFIX_LINE = "[rscc-assembly]"

# RS 自己的任务日志是 DEBUG 级 —— 它落在 <b>debug.log</b>（以及轮转的 debug-N.log.gz），
# 不在 latest.log 里。旧脚本只读 latest.log，于是「RS 自建任务数」恒为 0，
# 把「链路真的走完了」误报成失败（用户抱怨的「每次都跟我说日志不够」的一大来源）。
RS_TASK_MARK = "AutocraftingNetworkComponentImpl"
# 任务「状态迁移」由另一个类打（TaskImpl），因此完成行里<b>没有</b> RS_TASK_MARK：
#   [.../autocrafting.AutocraftingNetworkComponentImpl/]: Created task <uuid> ...
#   [.../autocrafting.task.TaskImpl/]: Task <uuid> state changed from RUNNING to COMPLETED
TASK_DONE_PHRASE = "state changed from RUNNING to COMPLETED"
TASK_STATE_MARK = "autocrafting.task.TaskImpl"


def rs_task_line_count(lines):
    return sum(1 for l in lines if "Created task" in l)


def pick_candidate():
    """挑一份「真的有生产事件」的会话日志，并返回 (path, lines, 生产事件数, 会话起点, 选择原因)。"""
    candidates = []
    for name in ("latest.log", "debug.log"):
        path = os.path.join(LOGDIR, name)
        if os.path.isfile(path):
            candidates.append(path)
    candidates.extend(sorted(glob.glob(os.path.join(LOGDIR, "*.log.gz"))))

    scored = []
    for path in candidates:
        text = read_any(path)
        if not text:
            continue
        lines = text.splitlines()
        # 排序键优先用「会话起点」（真实反映 JVM 何时起来），取不到退回文件 mtime
        start = session_start(path, lines)
        scored.append({
            "path": path,
            "lines": lines,
            "prod": production_count(lines),
            "tasks": rs_task_line_count(lines),
            "start": start,
            "mtime": os.path.getmtime(path),
            "sort": (start.timestamp() if start else os.path.getmtime(path)),
        })
    if not scored:
        return None

    print("   候选会话（按会话起点从新到旧；★ = 含生产事件；任务行 = RS 自建任务数，DEBUG 级，在 debug*.log）：")
    for entry in sorted(scored, key=lambda e: e["sort"], reverse=True)[:12]:
        print("     %s %-28s 会话起点=%-19s 生产事件=%-5d 任务行=%d"
              % ("★" if entry["prod"] > 0 else " ",
                 os.path.relpath(entry["path"], ROOT),
                 entry["start"].strftime("%Y-%m-%d %H:%M:%S") if entry["start"] else "未检出",
                 entry["prod"], entry["tasks"]))

    with_production = [e for e in scored if e["prod"] > 0]
    pool = with_production if with_production else scored
    # 同一会话常常同时有 latest.log（INFO）与 debug.log（DEBUG，内容更全）：
    # 先按「是否含 RS 任务行」优先，再按会话起点 —— 这样「链路真的走完」这条断言才有数据可判。
    pool.sort(key=lambda e: (1 if e["tasks"] > 0 else 0, e["sort"]), reverse=True)
    chosen = pool[0]
    if with_production:
        reason = "含生产事件；其中又优先取「含 RS 任务行（DEBUG 级）」的那一份，再按会话起点取最新"
    else:
        reason = "所有候选都没有生产事件（本轮没跑产线）⇒ 取会话起点最新的那一份作硬不变式基线"
    return chosen["path"], chosen["lines"], chosen["prod"], chosen["start"], reason


picked = pick_candidate()
if picked is None:
    print("[FAIL] run/logs 下没有任何可读日志 —— 没有任何运行期证据。")
    print("       这不等于「一切正常」：旧实现会打印 SELFCHECK OK 并 exit 0（假绿），现在判 FAIL。")
    print("       处理：python tools/manual_compile.ps1 → 启动游戏 → 跑一遍产线 → 重跑本脚本；")
    print("       若确实只想跑源码锚点（CI / 纯编码），显式加 --allow-empty。")
    if ALLOW_EMPTY:
        print()
        print("=" * 78)
        print("SELFCHECK OK (%d checks，--allow-empty：只跑源码锚点)" % CHECKS[0])
        sys.exit(1 if FAILURES else 0)
    FAILURES.append("日志可用性 没有找到任何可读日志（无运行期证据，不能报 OK）")
    print()
    print("=" * 78)
    print("VERIFY FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)

LOG_PATH, LOG_LINES, prod_hits, session_started, pick_reason = picked
print("   选中: %s（生产事件 %d 条）" % (os.path.relpath(LOG_PATH, ROOT), prod_hits))
print("   为什么选它: %s" % pick_reason)

lines = LOG_LINES
rscc = [l for l in lines if "rscc-assembly" in l or "rscc-trace" in l]

# 本次构建时间 = 最新 .class 的写盘时间（本仓库用 javac 直接编译到 build/manual_compile）
newest_class = 0.0
for base, _dirs, files in os.walk(CLASSES):
    for name in files:
        if name.endswith(".class"):
            newest_class = max(newest_class, os.path.getmtime(os.path.join(base, name)))
build_time = datetime.datetime.fromtimestamp(newest_class) if newest_class else None

# 判定的根据：<b>该会话的 JVM 启动时刻</b>是否晚于本次构建（而不是「日志文件写盘时间」——
# 轮转日志在会话结束时落盘，那个时间与「这次跑的是哪份构建」无关，正是旧脚本误判的根源）。
if session_started is not None and build_time is not None:
    stale = session_started < build_time
    basis = "会话 JVM 启动 %s vs 本次构建 %s" % (
        session_started.strftime("%Y-%m-%d %H:%M:%S"), build_time.strftime("%Y-%m-%d %H:%M:%S"))
elif build_time is not None:
    stale = os.path.getmtime(LOG_PATH) < newest_class
    basis = "会话起点未检出 ⇒ 退回比较文件写盘时间 %s vs 本次构建 %s" % (
        datetime.datetime.fromtimestamp(os.path.getmtime(LOG_PATH)).strftime("%Y-%m-%d %H:%M:%S"),
        build_time.strftime("%Y-%m-%d %H:%M:%S"))
else:
    stale = False
    basis = "没有编译产物可比（build/manual_compile 为空）⇒ 不做早/晚判定"

print("   判定依据: %s" % basis)
print("   诊断开关: %s" % debug_switch_note(lines))
print("   ⇒ %s" % ("日志会话早于本次构建（修复后行为只能作基线）" if stale
                   else "日志会话晚于本次构建（修复后行为 = 实判定）"))

# ---- 本轮新增：日志必须能自证版本 + 早于构建的日志不得静默通过 ----
# 为什么：审计 §7.3 的结论就是「日志与源码的对应关系不可自证」，而旧脚本又把 stale 会话下的
# 所有修复后断言降级成永不 FAIL 的 FIXED-OLD-LOG ⇒ 用旧构建的日志跑也能「全绿」。
# 现在：指纹行缺失 = FAIL；stale = FAIL（除非显式 --allow-stale 只看基线）。
stamp_lines = [l for l in lines if "[rscc-build]" in l]
check("B1 日志自证版本（日志里出现启动指纹行 [rscc-build]，含 revision / built；"
      "缺失 = 跑的是「加指纹之前」的构建，无法证明这份日志对应哪一版代码）",
      len(stamp_lines) >= 1,
      ("%s" % stamp_lines[-1].split("[rscc-build]")[-1].strip()[:160]) if stamp_lines
      else "没有 [rscc-build] 行（先 python tools/manual_compile.ps1 刷新指纹，再启动游戏）")
if stale and not ALLOW_STALE:
    check("B2 选中日志的会话必须晚于本次构建（早于 = 只能作基线，旧实现把它降级成永不 FAIL 的 "
          "FIXED-OLD-LOG ⇒ 用旧构建的日志也能「全绿」；要只看基线请显式加 --allow-stale）",
          False, basis)
elif stale:
    print("BASELINE-ONLY %s（--allow-stale：修复后断言只打印实测值，不判 FAIL）" % basis)

if not rscc:
    print("[FAIL] 选中的日志里没有 rscc 诊断行（%s 一次都没出现）—— 没有可判的运行期证据。" % PREFIX_LINE)
    print("       排查顺序：① 这一份是不是「刚启动、还没跑产线」的会话（看上面的候选表）；")
    print("       ② 开发日志总开关是否还关着（配置 devLogs 默认 false ⇒ 先 /rs_create_compat devlogs on）；")
    print("       ③ 若确无数据，请照 docs/SEQUENCE_ASSEMBLY_LOG_GUIDE.md 的步骤重跑一次产线。")
    print("       旧实现在这里打印 SELFCHECK OK 并 exit 0（假绿），现在判 FAIL；")
    print("       若确实只想跑源码锚点，显式加 --allow-empty。")
    if ALLOW_EMPTY:
        print()
        print("=" * 78)
        print("SELFCHECK OK (%d checks，--allow-empty：日志里无诊断行)" % CHECKS[0])
        sys.exit(1 if FAILURES else 0)
    FAILURES.append("日志可用性 选中日志里没有 rscc 诊断行（无运行期证据，不能报 OK）")
    print()
    print("=" * 78)
    print("VERIFY FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)


# ==================== C. 分布 / 账目 ====================

def dist(item_filter=None):
    counter = collections.Counter()
    for line in rscc:
        if item_filter and item_filter not in line:
            continue
        if "rscc-trace" in line:
            ev = EVENT.search(line)
            rsn = REASON.search(line)
            body_txt = "%s/%s" % (ev.group(1) if ev else "-", rsn.group(1) if rsn else "-")
        else:
            rsn = re.search(r"reason=([A-Za-z_0-9]+)", line)
            body_txt = "%s" % (rsn.group(1) if rsn else "-")
        counter[body_txt] += weight(line)
    return counter


def count_events(item, needle):
    return sum(weight(l) for l in rscc if item in l and needle in l)


def max_spin_repeat(item):
    worst = 0
    for line in rscc:
        if item not in line:
            continue
        if not any(reason in line for reason in SPIN_REASONS):
            continue
        m = REP.search(line)
        if m:
            worst = max(worst, int(m.group(1)))
    return worst


section("C1) ② 齿轮 / 金板的 event/reason 加权分布（条数 × 同因合并次数）")

for item in ("create:cogwheel", "create:large_cogwheel", "minecraft:iron_nugget",
             "create:golden_sheet"):
    print()
    print("---- %s ----" % item)
    for key, value in dist(item).most_common(10):
        print("   %6d  %s" % (value, key))

print()
print("---- ② 齿轮被推给「哪一台机器」（份额外的机器不该拿到） ----")
gear_targets = collections.defaultdict(collections.Counter)
for line in rscc:
    if "bus_push" not in line:
        continue
    item = item_of(line)
    if item not in ("create:cogwheel", "create:large_cogwheel", "minecraft:iron_nugget"):
        continue
    gear_targets[item][machine_from_of(line)] += weight(line)
for item, targets in gear_targets.items():
    print("   %-26s 总推 %d 次 → %s" % (item, sum(targets.values()), dict(targets)))

section("C2) ③ 岩浆（minecraft:lava）账目表（mB）")

lava = {"抽取(网络→仓)": 0, "推送(仓→注液机)": 0, "收回(注液机→仓)": 0,
        "回流(仓→网络)": 0, "推不动次数": 0}
for line in rscc:
    text = line.split("] ", 1)[-1]
    n = weight(line)
    if "lava" in text:
        if "pull {fluid=minecraft:lava" in text:
            lava["抽取(网络→仓)"] += num(text) * n
        elif "bus_push" in text:
            lava["推送(仓→注液机)"] += num(text) * n
        elif "took {" in text:
            lava["收回(注液机→仓)"] += num(text) * n
        elif "bus_skip" in text:
            lava["推不动次数"] += n
    if "return {item" in text:
        m = re.search(r"fluid x(\d+)\}", text)
        if m:
            lava["回流(仓→网络)"] += int(m.group(1)) * n
for key in ("抽取(网络→仓)", "推送(仓→注液机)", "收回(注液机→仓)", "回流(仓→网络)", "推不动次数"):
    print("   %-18s %8d" % (key, lava[key]))
print("   注：仓内 / 注液机罐内残留 = 抽取 − 回流 − 机器净耗；本脚本只审计「不复制 / 不凭空多推」。")

section("C3) ④ 关键物品账目（产出/消耗/残留）")

for item in KEY_ITEMS:
    print("   %-38s push=%-4d take_to_chamber=%-4d take_from_machine=%-4d insert_net=%-4d residual=%d"
          % (item,
             count_events(item, "bus_push"),
             count_events(item, "take_to_chamber"),
             count_events(item, "take_from_machine"),
             count_events(item, "insert_network"),
             count_events(item, "task_finished_residual")))

section("C4) ① 序列装配：RS 任务是否真的走完 + 中间产物是否守恒")


def rs_tasks(lines_all):
    """从 RS 自己的 DEBUG 行取任务状态（RUNNING → COMPLETED）。

    <b>注意这些行在 debug.log 里</b>（DEBUG 级），不在 latest.log —— 选日志时已优先挑选
    含任务行的那一份（见 B 段的 pick_candidate），因此这里拿得到真数据。
    """
    created = [l for l in lines_all if RS_TASK_MARK in l and "Created task" in l]
    done = [l for l in lines_all if TASK_DONE_PHRASE in l]
    others = [l for l in lines_all if TASK_STATE_MARK in l and "state changed" in l
              and TASK_DONE_PHRASE not in l]
    return created, done, others


created, done, other_states = rs_tasks(lines)
print("   RS 自建任务数 = %d，RUNNING→COMPLETED 次数 = %d（其它状态迁移 %d 条，例如被取消）"
      % (len(created), len(done), len(other_states)))
for item in ("create:unprocessed_obsidian_sheet", "create:incomplete_precision_mechanism"):
    print("   %-38s push=%-4d handover=%-4d from_machine=%-4d"
          % (item, count_events(item, "bus_push"), count_events(item, "handover_to_chamber"),
             count_events(item, "take_from_machine")))

# ==================== D. 断言 ====================

section("D1) 硬不变式（任何日志都必须成立；违反即 FAIL）")

pushes = collections.defaultdict(list)
shares = collections.defaultdict(list)
for line in rscc:
    if "rscc-trace" not in line:
        continue
    item = item_of(line)
    bus = bus_of(line)
    if not item or not bus:
        continue
    if "event=bus_push" in line:
        pushes[(bus, item)].append((stamp(line), machine_of(line)))
    elif "event=bus_share_skip" in line:
        m = re.search(r"share=(\d+)", line)
        if m:
            shares[(bus, item)].append((stamp(line), int(m.group(1))))


def share_at(key, t):
    best = None
    for ts, value in shares.get(key, ()):
        if ts <= t + 5.0:
            best = value if best is None else max(best, value)
    return best


over = []
for key, entries in pushes.items():
    for t, machine in entries:
        if machine is None:
            continue
        share = share_at(key, t)
        if share is None:
            continue
        same_window = {m for ts, m in entries if abs(ts - t) <= 5.0 and m is not None}
        if len(same_window) > share:
            over.append((key, share, sorted(same_window)))
check("H1 金板不多推：同一 (执行器段, 物品) 的 5 秒窗口内推送到的不同机器数 ≤ 该物品当时报出的份额",
      not over, "越份额推送: %s" % (over[:5],))

reject_weight = sum(weight(l) for l in rscc if "cogwheel" in l and "not_wanted_this_step" in l)
check("H2 齿轮的「本仓当前待加工步不要它 ⇒ 不备料」没有持续性的无效重复（修复前 488 次加权）",
      reject_weight == 0, "仍见 reason=not_wanted_this_step 加权 %d 次" % reject_weight)

# ③ 岩浆守恒：推送不得多于抽取；机器净得不得多于抽取（不复制、不凭空多推）。
check("H3 ③ 岩浆不复制：推送(仓→注液机) ≤ 抽取(网络→仓) 且 机器净得(推送−收回) ≤ 抽取",
      lava["推送(仓→注液机)"] <= lava["抽取(网络→仓)"]
      and lava["推送(仓→注液机)"] - lava["收回(注液机→仓)"] <= lava["抽取(网络→仓)"],
      "抽取=%d 推送=%d 收回=%d" % (lava["抽取(网络→仓)"], lava["推送(仓→注液机)"],
                                   lava["收回(注液机→仓)"]))

# ④ 中间产物不凭空多出：入网量不得超过「从机器 / 网络取回的量」。
dup = []
for item in KEY_ITEMS:
    gained = count_events(item, "take_to_chamber") + count_events(item, "take_from_machine")
    landed = count_events(item, "insert_network")
    if landed > gained + 1:
        dup.append((item, landed, gained))
check("H4 ④ 关键物品不凭空多出：入网量 ≤ 从网络 / 机器取回的量（+1 容差）",
      not dup, "凭空多出入网: %s" % (dup[:5],))

# 齿轮无「真实无效往返」：同一坐标、同一件、5 秒内被收回后又被推回。
# 口径按 docs/GEAR_BLOCKAGE_AND_FLOW_CHECK.md 判据 B/C：只看「齿轮类」（用户第 ② 条的当事人），
# 且「任务刚结束的一次性边沿（[auto+edge] / reason=task_finished_edge）」是设计内的残留回流，不算往返。
gear_pushes = collections.defaultdict(list)
gear_takes = collections.defaultdict(list)
GEAR_ITEMS = ("create:cogwheel", "create:large_cogwheel", "minecraft:iron_nugget")
for line in rscc:
    if "rscc-trace" not in line:
        continue
    item = item_of(line)
    if item not in GEAR_ITEMS:
        continue
    if "event=bus_push" in line:
        gear_pushes[item].append((stamp(line), machine_from_of(line)))
    elif "event=take_from_machine" in line:
        gear_takes[item].append((stamp(line), machine_src_of(line)))
round_trips = []
for item, entries in gear_pushes.items():
    for t, pos in entries:
        for ts, taken_from in gear_takes.get(item, ()):
            if abs(ts - t) <= 5.0 and pos and pos == taken_from:
                round_trips.append((item, pos, round(ts - t, 2)))
check("H5 ② 齿轮无真实无效往返（同一坐标、同一件、5 秒内「收回 → 再推」）"
      "—— 这条依赖 ② 的修复，因此日志早于本次构建时只作基线（标 FIXED-OLD-LOG）",
      not round_trips, "真实往返: %s" % (round_trips[:5],), hard=not stale)

trans_lines = [l for l in rscc if any(marker in l for marker in TRANSITION_MARKERS)]
trans_claimed = [l for l in trans_lines if "claimed_by_task=yes" in l]
trans_edge = [l for l in trans_lines if "task_finished_edge" in l and "[handover]" not in l]
check("H6 ⑤ 过渡件绝不被任务计为交付（日志里不存在 claimed_by_task=yes 的过渡件行）",
      not trans_claimed, "过渡件被计交付 %d 条" % len(trans_claimed))
check("H7 ⑤ 任务结束的一次性残留边沿不把过渡件收进网络（reason=task_finished_edge 里无过渡件）",
      not trans_edge, "边沿收过渡件 %d 条" % len(trans_edge))

short_lines = [l for l in rscc if re.search(r"\bshort \{", l)]
# 第 21 轮：横幅出口除「缺料」外，新增「推不动（机器被占用 / 下游满 / 该步无机器认领）」
# —— 看门狗把它归类为 EXECUTOR_OFFLINE（枚举文档本就含「或拒绝接收」），因此两种 alert 都算「已提示」。
alert_lines = [l for l in rscc if "alert reason=missing_material" in l
               or "alert reason=executor_offline" in l]
check("H8 ③ 缺料 / 推不动必提示：出现「本仓缺料」时一定同时有横幅记录"
      "（alert reason=missing_material / executor_offline）—— 这条依赖本轮修复，"
      "因此日志早于本次构建时只作基线（标 FIXED-OLD-LOG）",
      len(short_lines) == 0 or len(alert_lines) >= 1,
      "short=%d alert=%d" % (len(short_lines), len(alert_lines)), hard=not stale)
per_chamber = collections.Counter(chamber_of(l) for l in alert_lines if chamber_of(l))
span = 0.0
if len(rscc) >= 2:
    span = max(0.0, stamp(rscc[-1]) - stamp(rscc[0]))
budget = int(span / 5.0) + 2 if span > 0 else len(alert_lines)
check("H9 ③ 缺料提示限频：同一执行仓的提示条数 ≤ 日志时长 / 5 秒 + 2（不刷屏）",
      all(v <= budget for v in per_chamber.values()),
      "各仓提示数=%s 预算=%d" % (dict(per_chamber), budget))

sheet_inserts = [l for l in rscc if "create:golden_sheet" in l and "insert_network" in l]
voided = [l for l in sheet_inserts if re.search(r"not_retained=(\d+)", l)
          and int(re.search(r"not_retained=(\d+)", l).group(1)) > 0]
check("H10 ④ 入网没有「假收下」（reported > landed）：金板 not_retained 恒为 0",
      not voided, "假收下 %d 条" % len(voided))

section("D2) 修复后行为（日志晚于当前构建 ⇒ 实判定；否则打印实测值并标 FIXED-OLD-LOG）")

spin = {item: max_spin_repeat(item) for item in
        ("create:cogwheel", "create:large_cogwheel", "create:golden_sheet")}
worst_spin = max(spin.values()) if spin else 0
check("F1 ①c 空转因由（%s）在 5 秒窗口内不再被连续重复判定（修复前实测最多 37 次 / 5 秒）："
      "每个 (仓|总线 + 件) 的 5 秒合并计数 ≤ %d" % ("/".join(SPIN_REASONS), SPIN_REPEAT_MAX),
      worst_spin <= SPIN_REPEAT_MAX, "各物品 5 秒内最大重复: %s" % spin, hard=not stale)

check("F2 ③ 岩浆的 machine_full 刷屏被流体闸门压掉（每条 5 秒窗口内加权 ≤ %d；修复前实测 126 次加权、"
      "单条最高 10 次 / 5 秒）" % SPIN_REPEAT_MAX,
      lava["推不动次数"] <= SPIN_REPEAT_MAX, "machine_full 加权 %d 次" % lava["推不动次数"],
      hard=not stale)

# 齿轮被推给「份额外机器」= 同一 5 秒窗口内推到 >1 台不同机器（下单 1 个时份额 = 1）。
widest = 0
for item, entries in gear_pushes.items():
    if item not in ("create:cogwheel", "create:large_cogwheel", "minecraft:iron_nugget"):
        continue
    for t, _pos in entries:
        window = {m for ts, m in entries if m and abs(ts - t) <= 5.0}
        widest = max(widest, len(window))
check("F3 ② 齿轮不再被推给份额外的第二台机器（同一 5 秒窗口内推送到的机器台数 ≤ 1；"
      "修复前实测 cogwheel 被推给 (-7,-58,5) 与 (-7,-58,6) 两台，其中第二台这一步根本用不上它 —— "
      "那件齿轮永久卡死）",
      widest <= 1, "单个 5 秒窗口内齿轮最多推到 %d 台机器" % widest, hard=not stale)

# ④ N=1 备料份数：同一物品在 5 秒窗口内被 take_to_chamber 的加权次数 ≤ 3
#（目标量 = min(要它的工位数, 订单剩余件数) = 1 ⇒ 同一时刻该物品在执行仓里最多一份在飞；
#  每 5 秒最多「抽 → 推 → 被机械手消耗」两三个来回。修复前目标量 = 工位数 = 2，实测 6~7 次 / 5 秒。）
worst_feed = 0
for item in ("create:cogwheel", "create:large_cogwheel", "minecraft:iron_nugget",
             "create:golden_sheet"):
    marks = [(stamp(l), weight(l)) for l in rscc
             if item in l and "take_to_chamber" in l and "event=take_to_chamber" in l and stamp(l) > 0]
    for t, _w in marks:
        worst_feed = max(worst_feed, sum(w for ts, w in marks if 0 <= ts - t <= 5.0))
check("F4 ④ N=1 时同一种投入物在 5 秒窗口内最多备 3 份（份额闸门：目标量 = min(工位数, 订单剩余量) = 1；"
      "修复前目标量 = 工位数 = 2，实测 6~7 次 / 5 秒，多出来的那份在任务结束时以 task_finished_residual 退回）",
      worst_feed <= 3, "单个 5 秒窗口内同一物品最多备 %d 份" % worst_feed, hard=not stale)

# ① 序列装配真的走完：真的建过 RS 任务，且至少有一次 RUNNING→COMPLETED
#（不要求「全部 COMPLETED」：会话结束时可能还有在跑的任务、也可能有被玩家取消的任务 ——
#  要求 done ≥ created 会把正常会话误判成失败。）
check("F5 ① 序列装配链路真的走完（RS 自建任务数 ≥ 1，且至少一次 RUNNING→COMPLETED；"
      "数据来自 debug*.log 的 DEBUG 任务行，选日志时已优先挑含任务行的那一份）",
      len(created) >= 1 and len(done) >= 1,
      "created=%d completed=%d 其它状态=%d%s"
      % (len(created), len(done), len(other_states),
         "（本会话没有 RS 任务行 ⇒ 多半是一份没跑产线的会话）" if not created and not done else ""),
      hard=not stale)

# ⑥ 坚固板端到端仍能出成品。
sturdy_ok = any("create:sturdy_sheet" in l and "insert_network" in l
                and re.search(r"landed=(\d+)", l) and int(re.search(r"landed=(\d+)", l).group(1)) >= 1
                for l in rscc)
sturdy_seen = any("create:sturdy_sheet" in l and "insert_network" in l for l in rscc)
check("F6 ① 坚固板端到端仍能出成品（日志里存在 create:sturdy_sheet 成功入网 landed ≥ 1）",
      (not sturdy_seen) or sturdy_ok,
      "" if sturdy_seen else "本段日志没有坚固板入网记录（未跑这条产线）")

print()
print("=" * 78)
if FAILURES:
    print("VERIFY FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("VERIFY OK (%d checks)" % CHECKS[0])
sys.exit(0)
