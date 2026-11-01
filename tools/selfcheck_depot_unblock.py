# -*- coding: utf-8 -*-
"""自检：置物台堵塞自愈（齿轮 / 废料占住工位 ⇒ 执行舱把它收回来）。

用法：python tools/selfcheck_depot_unblock.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

用户原话（本自检逐条对应）
--------------------------
-「精密构件制作的时候它不是有个废料吗……如果运气不好装配失败了就是装配出废料了，此时如果说这个废料
  是小齿轮的话那么他就会堵着了，既不会把这个小齿轮回流到网络中也不会进行什么其他操作就在那一直卡着，
  现在是会弹一个弹窗出来说什么满啊什么的，但实际上根本不是满了，就只是小齿轮堵塞了」
-「为什么机器满了……机械手和这个置物台完全就是空的」
-「我在做到 12 个构建的时候已经挂起了差不多四五次了」

拆成可断言的规则
----------------
U1 拒收时**记下是哪台供料目标、哪一件**（只知道「有东西被拒收」无法把它收回来）；
U2 存在「把占住供料目标的那一件收回来」的实现，并且它**在 tick 里被调用**；
U3 回收的保守边界：只碰（a）最近真的拒收过的那个坐标（60 tick 窗口内）、
   （b）本仓这条产线的件（本配方过渡件 / 本仓输出类别的废料·成品）、
   （c）置物台上那件 ≠ 我们刚才想推的那件（否则是把机械手正常的放置抹掉）；
U4 回收走既有帐：进本仓内部存储 → `flowLedger.fromMachine` 记账 → 由既有回流写回网络；
U5 成功回收后清掉拒收证据（否则「收回了却还在报推不动」）；
U6 回收失败（本仓也装不下）时**绝不销毁**，原样留在来源。
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

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def read(*parts):
    with io.open(os.path.join(*parts), "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def main():
    chamber = read(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
    strategy = read(SRC, "support", "RsccChamberExportStrategy.java")

    section("U1 拒收时记下「哪台供料目标 + 哪一件」")
    check("执行舱记住最近被拒收的目标坐标",
          "private BlockPos lastRefusedTarget;" in chamber)
    check("执行舱记住最近被拒收的资源",
          "private net.minecraft.world.item.Item lastRefusedItem;" in chamber)
    check("记录方法接收 target / resource（不再只记时刻）",
          "public void rscc$noteDestinationRefusal(@org.jetbrains.annotations.Nullable final BlockPos target,"
          in chamber)
    check("输出总线把目标坐标传进来",
          "chamber.rscc$noteDestinationRefusal(targetPos," in strategy)

    section("U2 回收实现存在且在 tick 里被调用")
    check("存在 recoverBlockingTargetItem()",
          "private int recoverBlockingTargetItem() {" in chamber)
    check("tickEngine 里真的调用了它（否则等于没写）",
          "recoverBlockingTargetItem();" in chamber)
    check("调用点带说明「不受门控限制」（回收 = 还东西，不是开工）",
          "recoverBlockingTargetItem();\n" in chamber)
    check("读供料目标的物品能力走既有唯一口径（不另起一套）",
          "RsccChamberImportStrategy.itemHandlerAt(level, target)" in chamber)
    check("有解堵日志（可取证）",
          '" unblock_recovered {"' in chamber)

    section("U3 保守边界：绝不抢别人的料 / 不抹掉机械手的正常放置")
    check("只在「最近真的拒收过」的窗口内动手（判定 60 tick / 收尾 200 tick）",
          "if (age > REFUSED_TARGET_RECOVER_TICKS) {" in chamber
          and "private static final int REFUSED_TARGET_RECOVER_TICKS = 200;" in chamber)
    check("判定窗口与收尾窗口分开（任务结束后仍能收，用户实测：取消后中间产物没回去）",
          "final boolean withinStall = age <= PUSH_STALL_WINDOW_TICKS;" in chamber)
    check("收尾窗口内放宽「手里那件 == 想推那件」的竞态保护（那时不可能再有机械手推进）",
          "if (withinStall && lastRefusedItem != null && inSlot.getItem() == lastRefusedItem) {" in chamber)
    check("超窗即清掉目标（坐标可能已被别人复用）",
          "lastRefusedTarget = null;\n            lastRefusedItem = null;\n            return 0;" in chamber)
    check("只碰本仓这条产线的件（有独立判据）",
          "private boolean belongsToMyPipeline(final ItemStack stack) {" in chamber)
    check("过渡件判据按「本仓配方的产物名」反查（不依赖数据组件是否还在）",
          "private boolean isTransitionOfMyRecipes(final net.minecraft.world.item.Item item) {" in chamber
          and "ownedSteps().entrySet()" in chamber)
    check("废料 / 成品按「本仓输出类别」判定（输入类别不算）",
          "for (final BusCategoryInfo info : busCategories()) {" in chamber
          and "if (info.isInput()) {" in chamber)
    check("关键竞态保护：**判定窗口内**置物台上那件 == 我们想推的那件时不碰（那是机械手正常放置）",
          "if (withinStall && lastRefusedItem != null && inSlot.getItem() == lastRefusedItem) {" in chamber)

    section("U4 回收走既有帐（不破坏守恒）")
    check("先模拟抽取再真抽（避免抽一半又放回的抖动）",
          "final ItemStack simulated = handler.extractItem(slot, 1, true);" in chamber)
    check("先确认本仓收得下（canAcceptTransitionLocally）",
          "final int accepted = canAcceptTransitionLocally(simulated);" in chamber)
    check("进本仓内部存储并记 fromMachine 账（在 acceptTransitionLocally 内）",
          "flowLedger.fromMachine(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger" in chamber)

    section("U5 成功回收后清掉拒收证据")
    check("清 destinationRefusalAt / lastRefusedTarget / lastRefusedItem",
          "destinationRefusalAt = NEVER;" in chamber
          and "lastRefusedTarget = null;" in chamber
          and "lastRefusedItem = null;" in chamber)

    section("U6 失败时绝不销毁")
    check("收不下就直接返回（不抽出来）",
          "if (accepted <= 0) {" in chamber)
    check("抽出来了却收不进去 ⇒ 原样还回来源",
          "handler.insertItem(slot, taken, false);" in chamber)
    check("代码里没有对置物台内容调用任何销毁路径",
          "destroy" not in chamber.split("recoverBlockingTargetItem() {", 1)[1][:4000].lower())

    section("U7 复原：不误伤「输入类保护」与「任务结束一次性召回」")
    check("输入总线的「有单」闸门与一次性召回令牌未被改动",
          "busResidualInputToken" in chamber
          and "no_player_order_scrap_reclaim" in read(SRC, "support", "RsccChamberImportStrategy.java"))

    section("U8 运行期残留（2026-10-18：废料齿轮 / 金板 在正常运行中堵死站台）")
    # 与 U2/U3 的「堵塞自愈」互补：那条只处理「不在本仓输出类别里」的件，且明确排除输入类；
    # 「废料齿轮 / 废料金板」恰好既是输入类又是结果池物品 ⇒ 需要一条独立的、有凭据的清理。
    check("存在运行期残留清理（与 recoverBlockingTargetItem 各管一类，不重复搬运）",
          "private int reclaimRuntimeStationResidue() {" in chamber
          and "reclaimRuntimeStationResidue();" in chamber)
    check("凭据 = 在制登记表（本仓推过的那件）+ Create 配方结果池（台上那件是它的产出）",
          "new ArrayList<>(inFlightUnits.keySet())" in chamber
          and "for (final ProcessingOutput output : recipe.resultPool) {" in chamber)
    check("合法等待硬约束：机械手手里那件 / 我们推过去的结果池那一份 / 正在加工（进度在推进）的件 全部保留",
          "if (operatingPosOf(level, station) != null) {" in chamber
          and "if (unit.item() != null && unit.item() == item) {" in chamber
          and "final boolean sameProgress =" in chamber
          and "logRuntimeResidueSkip(station, item, \"assembly_progressing\");" in chamber)
    check("2026-10-23 修「过渡件永久占位」：裸过渡件 / 进度卡死的件 现在是<b>候选</b>（旧判据把它们一律排除）",
          "final boolean bareTransition = assembly == null && isTransitionOfMyRecipes(item);" in chamber
          and "if (frozenTicks < RUNTIME_RESIDUE_PROGRESS_STALL_TICKS) {" in chamber
          and "reasonClass = \"bare_transition_blocked\";" in chamber
          and "reasonClass = \"frozen_assembly_blocked\";" in chamber)
    check("强旁证：machine_full 计数（写点 = 拒收记录，读点 = rscc$refusedTargetHits）"
          "在涨才清；没旁证只观察",
          "refusedTargetHits.merge(target.immutable(), 1L, Long::sum);" in chamber
          and "public long rscc$refusedTargetHits(" in chamber
          and "rscc$refusedTargetHits(station) > 0L" in chamber
          and "\"bare_transition_not_blocking\"" in chamber)
    check("有界：每 40 tick 至多一轮、每轮至多 1 件、只扫登记表（不每 tick 全扫所有站台）",
          "RUNTIME_RESIDUE_SWEEP_INTERVAL_TICKS = 40L;" in chamber
          and "RUNTIME_RESIDUE_CLEAR_MAX = 1;" in chamber
          and "RUNTIME_RESIDUE_STATION_COOLDOWN_TICKS = 200L;" in chamber
          and "Math.min(size, RUNTIME_RESIDUE_STATION_MAX)" in chamber
          and "supplyStations(" not in chamber.split("private int reclaimRuntimeStationResidue() {", 1)[1]
                                                  .split("private boolean isSettledResultOfRecipe(", 1)[0])

    print()
    print("=" * 78)
    if FAILURES:
        print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("  - %s" % item)
        return 1
    print("SELFCHECK OK (%d checks)" % CHECKS[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
