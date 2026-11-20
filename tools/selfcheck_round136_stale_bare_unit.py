#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 136 轮自检：超龄裸在制件（step=-1 的起步原料）的自愈回收 + 孤儿登记清理。

现场（2026-11-13 用户远程服务器，`rscc_diag/20261113-211858/snapshot.json`）
----------------------------------------------------------------------------
- `(420,100,102)` / `(419,100,102)` 两条 `create:golden_sheet`、`step=-1` 的登记，
  age 9,409,580 / 9,409,805 tick（≈5.4 天，开服当天推进去，从未进入第一个工序）；
- 任务 `delivered=3`、`remaining=2` ⇒ 名额 `capacity = remaining − inFlight = 2 − 2 = 0`
  ⇒ 本仓从此**一次都不再推料** ⇒ 既有「卡死件」判据要求的 `machine_full` 强旁证
  **永远不可能再出现** ⇒ 判据自身的死锁。

本脚本守的四条腿
----------------
① 判据锚点：`staleBareUnitRecoverable` 的四个前置（step<0 / 非本仓过渡件 /
   起步原料或步骤专用投入物 / 自上次成功推送满 `STALE_BARE_UNIT_TICKS`）一个都不许少；
② 分支锚点：`reclaimStationResidueAt` 里「登记物品 == 台上物品」分支<b>不再无条件 return 0</b>，
   而是先问判据；这条分支<b>不得</b>要求 `machine_full` 强旁证（名额 0 时它恒不成立）；
③ 守恒锚点：回收仍是 SIMULATE → 精确抽 1 件 → 优先进网络 → 装不下才收本仓 →
   两侧都装不下原样还回；先搬后撤登记；逐笔记 flowLedger 的 fromMachine / toNetwork；
④ 孤儿锚点：`dropOrphanBareRegistration` 只删本模组账本、<b>绝不搬物品</b>（方法体里不许出现
   `extractItem`），且对账里必须排在「死件判定」之前；判不出来（异常）一律不动。
另有反例自证：旧写法「无条件 return 0」必须已经消失；时间闸门的边界值用 Python 镜像复算一遍。

用法：python tools/selfcheck_round136_stale_bare_unit.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CHAMBER = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq",
                       "rs_create_compat", "block", "entity",
                       "SequenceExecutionChamberBlockEntity.java")

STALE_TICKS = 1200

_passed = 0
_failed = 0
_failures = []


def check(name, ok, detail=""):
    global _passed, _failed
    if ok:
        _passed += 1
        print("  OK   %s" % name)
    else:
        _failed += 1
        _failures.append((name, detail))
        print("  FAIL %s" % name)
        if detail:
            for line in str(detail).strip().splitlines()[:12]:
                print("       " + line)


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def method_body(text, signature):
    index = text.find(signature)
    if index < 0:
        return ""
    start = text.find("{", index)
    if start < 0:
        return ""
    depth = 0
    for position in range(start, len(text)):
        char = text[position]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:position + 1]
    return ""


def branch_of(body, marker, end_markers):
    """取 body 里 from marker 到第一个 end_marker 之间的一段（用于分支内的先后判定）。"""
    start = body.find(marker)
    if start < 0:
        return ""
    best = len(body)
    for end in end_markers:
        found = body.find(end, start + len(marker))
        if 0 <= found < best:
            best = found
    return body[start:best]


# ---------------------------------------------------------------- 镜像判据（纯 Python）
def stale_bare_recoverable(step, is_transition, is_start_or_extra, age_ticks):
    """镜像 Java 的 staleBareUnitRecoverable（只复算判据，不碰文件）。"""
    if step >= 0:
        return False
    if is_transition:
        return False
    if not is_start_or_extra:
        return False
    return 0 <= age_ticks and age_ticks >= STALE_TICKS


def main():
    print("=== ① 源码锚点：判据 / 常量 / 两个入口都在 ===")
    check("①-1 SequenceExecutionChamberBlockEntity.java 存在", os.path.isfile(CHAMBER))
    if not os.path.isfile(CHAMBER):
        return 1
    src = read(CHAMBER)

    check("①-2 STALE_BARE_UNIT_TICKS = 1200L（60 秒：远超任何正常起件节奏）",
          "STALE_BARE_UNIT_TICKS = 1200L" in src)
    check("①-3 STALE_BARE_RECOVER_COOLDOWN_TICKS = 6000L（5 分钟冷却，防推收往返）",
          "STALE_BARE_RECOVER_COOLDOWN_TICKS = 6000L" in src)

    predicate = method_body(src, "private boolean staleBareUnitRecoverable(")
    check("①-4 staleBareUnitRecoverable 方法体可定位", bool(predicate))
    orphan = method_body(src, "private boolean dropOrphanBareRegistration(")
    check("①-5 dropOrphanBareRegistration 方法体可定位", bool(orphan))

    print("\n=== ② 判据锚点：四个前置一个都不许少 ===")
    if predicate:
        check("②-1 step<0 才处理（过渡件登记一律留在既有分支）",
              "unit.step() >= 0" in predicate and "return false" in predicate,
              predicate[:400])
        check("②-2 本仓过渡件即使 step=-1 也不在这条分支动",
              "isTransitionOfMyRecipes(item)" in predicate)
        check("②-3 必须是起步原料 / 步骤专用投入物（双重保险）",
              "isStartIngredient(item)" in predicate and "isStepExtraInput(item)" in predicate)
        check("②-4 自上次成功推送满 STALE_BARE_UNIT_TICKS（sinceTick 由每次成功推送刷新）",
              "unit.sinceTick()" in predicate and "STALE_BARE_UNIT_TICKS" in predicate)
        check("②-5 负年龄（时钟回退 / 坏 NBT）不得判真",
              "age >= 0L" in predicate)

    print("\n=== ③ 分支锚点：旧「无条件 return 0」必须消失，且不要求 machine_full ===")
    reclaim = method_body(src, "private int reclaimStationResidueAt(")
    check("③-1 reclaimStationResidueAt 方法体可定位", bool(reclaim))
    if reclaim:
        old_shape = ("if (unit.item() != null && unit.item() == item) {\n"
                     "                return 0;")
        check("③-2 【反例自证】旧写法「登记物品 == 台上物品 ⇒ 无条件 return 0」已不存在",
              old_shape not in reclaim)
        seg = branch_of(reclaim,
                        "if (unit.item() != null && unit.item() == item) {",
                        ["} else if (!isSettledResultOfRecipe", "} else {"])
        check("③-3 该分支先问判据", "staleBareUnitRecoverable(now, unit)" in seg, seg[:600])
        check("③-4 判据不成立才保持旧语义（合法等待，绝不碰）",
              "!staleBareUnitRecoverable(now, unit)" in seg and "return 0;" in seg)
        check("③-5 【核心】这条分支不要求 machine_full 强旁证（名额 0 时它恒不成立）",
              "blocking" not in seg, seg[:600])
        check("③-6 新分支打上独立归因 stale_bare_registration",
              '"stale_bare_registration"' in seg or '"stale_bare_registration"' in reclaim)
        check("③-7 用 staleBare 标记与其它两类区分（事件归因 / 冷却）",
              "staleBare = true" in reclaim and "staleBare" in reclaim)

    print("\n=== ④ 守恒锚点：先搬后撤登记 / 不丢不复制 / 逐笔记账 ===")
    if reclaim:
        i_sim = reclaim.find("extractItem(slot, 1, true)")
        i_real = reclaim.find("extractItem(slot, 1, false)")
        check("④-1 SIMULATE 夹量在前，精确抽 1 件在后", 0 <= i_sim < i_real,
              "simulate=%d real=%d" % (i_sim, i_real))
        check("④-2 优先 insert 进网络（StorageNetworkComponent）",
              "storage.insert(" in reclaim)
        check("④-3 网络装不下才收本仓（acceptTransitionLocally）",
              "acceptTransitionLocally(" in reclaim)
        check("④-4 两侧都装不下 ⇒ 原样还回站台，绝不销毁",
              "handler.insertItem(slot," in reclaim)
        i_extract = reclaim.find("extractItem(slot, 1, false)")
        i_dereg = reclaim.find("inFlightUnits.remove(station)")
        check("④-5 【核心】先把物品搬走，再撤在制登记（顺序不可颠倒）",
              0 <= i_extract < i_dereg, "extract=%d dereg=%d" % (i_extract, i_dereg))
        check("④-6 逐笔记守恒账：fromMachine + toNetwork 两侧同额",
              "flowLedger.fromMachine(" in reclaim and "flowLedger.toNetwork(" in reclaim)
        check("④-7 一个字节都没搬走时不改登记（moved <= 0 分支先返回）",
              "moved <= 0L" in reclaim and "return 0;" in reclaim)

    print("\n=== ⑤ 冷却锚点：超龄回收有独立 5 分钟冷却 ===")
    check("⑤-1 staleBareClearedRecently 用 STALE_BARE_RECOVER_COOLDOWN_TICKS 计时",
          "staleBareClearedRecently" in src
          and "STALE_BARE_RECOVER_COOLDOWN_TICKS" in src)
    check("⑤-2 命中冷却时日志原因 station_cooldown_long（与 10 秒那条区分）",
          '"station_cooldown_long"' in src)

    print("\n=== ⑥ 孤儿锚点：只删账本、不搬物品、判不出来不动 ===")
    if orphan:
        check("⑥-1 【核心】孤儿清理方法体里绝不搬物品（无 extractItem）",
              "extractItem" not in orphan)
        check("⑥-2 必须用整工位残留判定确认「本产线的东西已经不在」",
              "stationHoldsPipelineResidue(station, unit)" in orphan)
        check("⑥-3 判不出来（异常）一律返回 false：宁可不删，不丢凭据",
              "catch (final RuntimeException failure)" in orphan
              and "return false" in orphan)
        check("⑥-4 空的工位不删（不占名额，留给下一次推送重写）",
              "stationIsOccupied(level, station)" in orphan)
        check("⑥-5 只删本模组账本（inFlightUnits / 观测表），不碰世界",
              "inFlightUnits.remove(station)" in orphan)
        check("⑥-6 诊断事件 inflight_orphan_removed 可查",
              '" inflight_orphan_removed station="' in orphan)
    reconcile = method_body(src, "private void reconcileInFlightUnits(")
    check("⑥-7 对账调用 dropOrphanBareRegistration", bool(reconcile)
          and "dropOrphanBareRegistration(level, station, now)" in reconcile)
    if reconcile:
        i_orphan = reconcile.find("dropOrphanBareRegistration(level, station, now)")
        i_occ = reconcile.find("final boolean occupied = stationIsOccupied(level, station)")
        check("⑥-8 孤儿清理排在死件判定之前", 0 <= i_orphan < i_occ,
              "orphan=%d occupied=%d" % (i_orphan, i_occ))

    print("\n=== ⑦ 时间闸门镜像复算（边界值）===")
    check("⑦-1 age=1200 且 step=-1 起步原料 ⇒ 可回收",
          stale_bare_recoverable(-1, False, True, 1200))
    check("⑦-2 age=1199 ⇒ 还年轻，不动", not stale_bare_recoverable(-1, False, True, 1199))
    check("⑦-3 age=9,409,805（现场值）⇒ 可回收",
          stale_bare_recoverable(-1, False, True, 9409805))
    check("⑦-4 step=0（过渡件登记）⇒ 永不走这条分支",
          not stale_bare_recoverable(0, False, True, 10 ** 9))
    check("⑦-5 本仓过渡件 ⇒ 不走", not stale_bare_recoverable(-1, True, True, 10 ** 9))
    check("⑦-6 不是起步原料 / 步骤专用投入物 ⇒ 不走",
          not stale_bare_recoverable(-1, False, False, 10 ** 9))
    check("⑦-7 负年龄（时钟回退）⇒ 不走", not stale_bare_recoverable(-1, False, True, -5))

    print("\n=== ⑧ 现场数字锚点（与本轮快照逐字对齐）===")
    check("⑧-1 注释里写明 capacity = remaining − inFlight 的死锁推导",
          "remaining − inFlight" in src or "remaining − inFlight" in src.replace("\u2212", "−"))
    check("⑧-2 注释里记录现场两格坐标（可回溯）",
          "(419,100,102)" in src and "(420,100,102)" in src)
    check("⑧-3 注释里记录 9,409,580 / 9,409,805 两个 age",
          "9,409,580" in src and "9,409,805" in src)

    print("\n" + "=" * 60)
    print("PASSED %d / FAILED %d" % (_passed, _failed))
    if _failed:
        print("\n失败项：")
        for name, detail in _failures:
            print(" - " + name)
        return 1
    print("ALL_PASS round136 stale bare unit recovery")
    return 0


if __name__ == "__main__":
    sys.exit(main())
