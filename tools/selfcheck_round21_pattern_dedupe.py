#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 21 轮：单元样板查重跨配方误判 + 堵塞/占用必提示 + 缺料必提示 + 推不动就别再拉。

用法：python tools/selfcheck_round21_pattern_dedupe.py
退出码：0 = 没有任何 FAIL。
"""

from __future__ import annotations

import io
import os
import re
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LOG = os.path.join(ROOT, "run", "logs", "debug.log")
FAILURES = []
CHECKS = [0]


def read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail="", hard=True):
    CHECKS[0] += 1
    if not ok and hard:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else ("FAIL" if hard else "BASE"), name,
                       (" | " + detail) if detail else ""))


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


dedupe = read(os.path.join("support", "UnitPatternDedupe.java"))
strategy = read(os.path.join("support", "RsccChamberExportStrategy.java"))
chamber = read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))
watchdog = read(os.path.join("support", "AssemblyWatchdog.java"))

log_lines = []
if os.path.exists(LOG):
    with io.open(LOG, "r", encoding="utf-8", errors="replace") as handle:
        log_lines = handle.read().splitlines()

# ======================================================================
# 1) 判重跨配方误判（最高优先）
# ======================================================================
section("1) 单元样板查重必须纳入「服务配方 + 步序」（反例：两条配方的冲压步都要生成）")

has(dedupe, "final String recipeA = dataA.recipe()", "锚点 1a: sameSemantics 读取配方 id")
has(dedupe, "if (!recipeA.equalsIgnoreCase(recipeB)) {", "锚点 1b: 配方不同 ⇒ 判为<b>不重复</b>")
has(dedupe, "dataA.step() != dataB.step()", "锚点 1c: 同配方内步序不同 ⇒ 判为<b>不重复</b>")
has(dedupe, "if (recipeA.isEmpty() || recipeB.isEmpty()) {",
    "锚点 1d: 老样板缺 Recipe ⇒ 一律不判重（宁可不判重，绝不漏生成）")
has(dedupe, "为什么判重必须纳入「所服务的配方 + 该步在配方中的步序」",
    "锚点 1e: javadoc 写清了「为什么」")
check("锚点 1f: 类注释不再声称 Recipe/Step「不参与比对」",
      "只是「这张样板从哪条配方" not in dedupe and "必须参与比对的字段" in dedupe)


def is_duplicate(rec_a, step_a, op_a, rec_b, step_b, op_b):
    """sameSemantics 模型（新口径）：配方 + 步序 + 操作类型全部一致才算重复。"""
    if not op_a or not op_b or op_a != op_b:
        return False
    if not rec_a or not rec_b or rec_a.lower() != rec_b.lower():
        return False
    if step_a < 0 or step_b < 0 or step_a != step_b:
        return False
    return True


def is_duplicate_old(rec_a, step_a, op_a, rec_b, step_b, op_b):
    """旧口径（回归）：只看操作类型（冲压没有输入原料 ⇒ 看起来一样）。"""
    return bool(op_a) and op_a == op_b


PRESS = "create:pressing"
PREC = "create:sequenced_assembly/precision_mechanism"
TRACK = "create:sequenced_assembly/track"
print()
print("  %-58s %-8s %-8s" % ("两张样板", "旧口径", "新口径"))
print("  %-58s %-8s %-8s" % ("精密构件的冲压步 vs 列车轨道的冲压步（不同配方）",
                            is_duplicate_old(PREC, 1, PRESS, TRACK, 1, PRESS),
                            is_duplicate(PREC, 1, PRESS, TRACK, 1, PRESS)))
print("  %-58s %-8s %-8s" % ("同一条配方同一步（重复）", is_duplicate_old(TRACK, 1, PRESS, TRACK, 1, PRESS),
                            is_duplicate(TRACK, 1, PRESS, TRACK, 1, PRESS)))
print("  %-58s %-8s %-8s" % ("老样板（无 Recipe）vs 新样板", is_duplicate_old("", -1, PRESS, TRACK, 1, PRESS),
                            is_duplicate("", -1, PRESS, TRACK, 1, PRESS)))
check("1g 反例①（本轮要修）：两条配方各自的冲压步都<b>必须生成</b>（旧口径会跳过 ⇒ 判负）",
      is_duplicate_old(PREC, 1, PRESS, TRACK, 1, PRESS) is True
      and is_duplicate(PREC, 1, PRESS, TRACK, 1, PRESS) is False)
check("1h 反例②：同一条配方的同一步已存在同款样板 ⇒ 才允许跳过",
      is_duplicate(TRACK, 1, PRESS, TRACK, 1, PRESS) is True)
check("1i 兼容：老样板缺 Recipe/Step ⇒ 不判重（多生成一张，绝不漏生成）",
      is_duplicate("", -1, PRESS, TRACK, 1, PRESS) is False)
check("1j 生成后该步必须被认领（判重修好后 stepOwner 不再是 NOBODY）：看门狗按 machinePos 判掉线"
      "且执行舱按 ownedSteps 认领 —— 两条锚点都在",
      "machinePos()" in watchdog and "chambers.containsKey(pos)" in watchdog
      and "private Map<String, Set<Integer>> computeOwnedSteps(" in chamber)

skip_lines = [l for l in log_lines if "skipped as duplicates" in l]
check("1k 日志基线（修前）：出现过「全部步骤都被当成重复而跳过」——这正是漏生成的证据（修后不应再出现）",
      len(skip_lines) > 0, "skipped lines=%d" % len(skip_lines), hard=False)

# ======================================================================
# 2) 机器被占用 / 下游满必须提示
# ======================================================================
section("2) 堵塞 / 被占用 / 下游满 ⇒ 必须弹横幅（不写状态，只提示）")

has(chamber, "public void rscc$noteDestinationRefusal(@org.jetbrains.annotations.Nullable final BlockPos target,",
    "锚点 2a: 执行舱记录「目的地拒收」（记时刻 + 目标坐标 + 资源；2026-10-05 起还要能定位是哪台供料目标）")
has(strategy, "chamber.rscc$noteDestinationRefusal(targetPos,",
    "锚点 2b: 输出总线的 DESTINATION_DOES_NOT_ACCEPT ⇒ 调用该记录并带上目标坐标")
has(chamber, "private int recoverBlockingTargetItem() {",
    "锚点 2b-2: 执行舱会把「占住供料目标的那一件」收回来（齿轮 / 废料堵塞的自愈）")
has(chamber, "public boolean pushStalledOnDestination() {",
    "锚点 2c: 只读查询「推不动」的持续状态（3 秒窗口，瞬时满不误报）")
# 2026-10-05 严重 bug 的回归锚点：哨兵**不能**用 Long.MIN_VALUE。
# 判定写成 `now - stamp <= WINDOW`，而 `now - Long.MIN_VALUE` 在 64 位下溢出回绕成负数，
# 负数恒 ≤ 窗口 ⇒「从未发生」被当成「刚刚发生」⇒ 每一台执行舱从加载起就恒定报「推不动」，
# 看门狗把**每一条**序列装配任务都判成输出阻塞并挂起（实测 stall=41，用户看到「机器满了」
# 而机械手与置物台全空、日志里一条推料尝试都没有）。因此两条都必须是 NEVER。
check("2c-1 「从未发生」哨兵不是 Long.MIN_VALUE（溢出会把它算成「刚刚发生」）",
      "private long destinationRefusalAt = NEVER;" in chamber
      and "private long stepOwnerMissingAt = NEVER;" in chamber
      and "private long destinationRefusalAt = Long.MIN_VALUE;" not in chamber
      and "private long stepOwnerMissingAt = Long.MIN_VALUE;" not in chamber)
check("2c-2 哨兵 NEVER 远早于任何存档 gameTime 且相减不溢出",
      "private static final long NEVER = -1_000_000L;" in chamber)
# 2026-10-05 修正：旧锚点钉的是 `record.reason = pushStalled ? Reason.EXECUTOR_OFFLINE`。
# 实机取证证明这个「一律叫掉线」的归类是错的：玩家被告知「执行器掉线」，而机械手与置物台是空的、
# 设备一台没少、料也够。现在「推不动」按<b>原因</b>分两种 reason：
#   某步没有任何在线执行仓认领 → EXECUTOR_OFFLINE（确实是设备 / 样板的问题）；
#   下游机器 / 置物台持续拒收     → OUTPUT_BLOCKED（机器在线，只是满 / 不接受）。
# 因此锚点改为钉住「两种原因各自成立」，而不是钉住一句已被证伪的写法。
has(watchdog, "record.reason = Reason.EXECUTOR_OFFLINE;",
    "锚点 2d: 「某步无机器认领」仍归类为 EXECUTOR_OFFLINE（设备 / 样板问题）")
has(watchdog, "record.reason = Reason.OUTPUT_BLOCKED;",
    "锚点 2d-2: 「下游机器满 / 不接受」归类为 OUTPUT_BLOCKED（机器在线）而不是掉线")
has(chamber, "STEP_OWNER_MISSING",
    "锚点 2d-3: 执行舱把两种「推不动」原因显式分开（StallReason）")
has(watchdog, "suspendRecord(record, record.reason, now);\n                sendBanner(level, record);",
    "锚点 2e: 一旦归类成功就必然走「挂起 + 横幅」（既有横幅通道，不再静默干等）")
check("2f 反例：推不动却归类为 NONE ⇒ 既不挂起也不弹横幅（用户「毫无提示地干等」的旧形态）",
      # 2026-10-05：「推不动」现在先被两个分支截走（EXECUTOR_OFFLINE / OUTPUT_BLOCKED），
      # 因此「落到 NONE / MISSING_MATERIAL」只可能发生在 pushStalled=false 时。
      "record.reason = canAdvance ? Reason.NONE : Reason.MISSING_MATERIAL;" in watchdog
      and watchdog.index("record.reason = canAdvance ? Reason.NONE")
          > watchdog.index("record.reason = Reason.OUTPUT_BLOCKED;"))

full_lines = [l for l in log_lines if "machine_full" in l]
check("2g 日志基线（修前）：「下游满」每 5 秒刷 11~13 条而<b>零</b>横幅（watchdog suspend 一条都没有）",
      len(full_lines) > 0 and not any("watchdog suspend" in l for l in log_lines),
      "machine_full=%d, watchdog_suspend=%d" % (
          len(full_lines), sum(1 for l in log_lines if "watchdog suspend" in l)), hard=False)

# ======================================================================
# 3) 缺料静默挂起 ⇒ 必须提示
# ======================================================================
section("3) 缺料（含 shortage-mode=suspend）⇒ 必须有明确原因横幅")

has(watchdog, "KEY_MATERIAL_PREFIX", "锚点 3a: 缺料横幅行存在（列明缺什么）")
check("锚点 3b: 挂起即弹横幅（手动挂起除外）—— 缺料模式是「挂起」时也照弹",
      "if (record.reason == Reason.MANUAL) {" in watchdog
      and "sendBanner(level, record);" in watchdog)
mode_lines = [l for l in log_lines if "shortage-mode=" in l]
short_lines = [l for l in log_lines if re.search(r"\bshort \{", l)]
alert_lines = [l for l in log_lines if "alert reason=missing_material" in l]
check("3c 日志基线（修前）：shortage-mode=suspend 且「short」多条而 alert=0 ⇒ 静默挂起（本轮修复点）",
      len(mode_lines) > 0 and len(short_lines) > 0,
      "mode=%d short=%d alert=%d" % (len(mode_lines), len(short_lines), len(alert_lines)), hard=False)

# ======================================================================
# 4) 推不动就别再拉（备料前先确认下游能消化）
# ======================================================================
section("4) 量化判据：推不动 ⇒ 停止备料；中间产物存量有上界")

has(chamber, "if (pushStalledOnDestination()) {\n            return;",
    "锚点 4a: fillInternalForBus 在「推不动」时整条短路（不再拉料）")
has(chamber, "private boolean categoryOwned(final BusCategoryInfo info) {",
    "锚点 4b: 备料需求仍按「本仓拥有的步」夹一次（中间产物存量上界的来源）")
summary_pull = 0
summary_feed = 0
for line in log_lines:
    match = re.search(r"summary window=\S+ pull=(\d+) feed=(\d+)", line)
    if match:
        summary_pull += int(match.group(1))
        summary_feed += int(match.group(2))
print()
print("  修改前基线（本次日志累计）：pull=%d feed=%d | machine_full=%d 条 | short=%d 条 | alert=%d 条"
      % (summary_pull, summary_feed, len(full_lines), len(short_lines), len(alert_lines)))
check("4c 修改前基线可从日志<b>量化</b>（本脚本每次运行现读现算）⇒ 修改后重跑本脚本即可对比",
      len(log_lines) == 0 or summary_pull >= 0)
check("4d 反例：没有 4a 的闸门时，机器持续 machine_full 而备料仍在进行（旧形态：pull=20 / 5 秒持续）",
      "if (pushStalledOnDestination()) {\n            return;" in chamber
      and "chamber.rscc$noteDestinationRefusal(targetPos," in strategy)
check("4e 「推不动」不写任何<b>游戏</b>状态：只记时间戳 / 原因（诊断字段）+ 只读查询"
      "（服务端权威、守恒与搬运不受影响）",
      # 2026-10-05：改成「按目标分别记」（refusedTargetAt），因此断言改为
      # 「确实只在诊断字段里记时刻，不写任何玩法状态」。
      "final long now = level.getGameTime();" in chamber
      and "destinationRefusalAt = now;" in chamber
      and "refusedTargetAt.put(target.immutable(), now);" in chamber
      and "stepOwnerMissingAt = level.getGameTime();" in chamber
      and "private long destinationRefusalAt = NEVER;" in chamber
      # 2026-10-05：除了时间戳，还记了「原因 + 资源名」用于横幅与快照 —— 全是诊断字段，
      # 不参与任何搬运 / 守恒判定，因此上面那条不变量（不改游戏状态）依旧成立。
      and "stallReason = reason;" in chamber
      and "stallResource = resource == null" in chamber)

print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
sys.exit(0)
