# -*- coding: utf-8 -*-
"""自检：候选组（铁粒/锌粒、任意台阶）全链路 —— 落盘 / 补算 / 显示回落。

用法：python tools/selfcheck_candidate_pipeline.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

用户原话（本自检逐条对应）
--------------------------
-「这个铁力心力然后这个几个台阶都可以使用的问题你已经解决了，但是我还是没看到他有任何成效啊，
  还是只写是个石头台阶还是没有什么轮换显示，然后这个铁力还是只显示个铁力」
-「我就放了一个样板……」

实机证据（决定断点）
--------------------
`tools/inspect_save_candidates.py` 解压关服存档后：有 `DisplayArrangement` / `sequence_unit_pattern`，
但 `InputCandidates` 出现 **0** 次 ⇒ 候选组从未落盘 ⇒ 界面读到的恒为「代表物一件」，
`size() > 1` 的轮播分支永远不进。

因此本自检钉住四条：
C1 主原料（起步原料）候选**有地方落盘**（`AssemblyData#ingredientCandidates` + NBT 读写对称）；
C2 单元样板候选的写入链没有被「改一个字段顺手抹掉整条」的重建调用截断；
C3 老样板**自愈**：按配方补算候选并写回（幂等），且真的被界面同步路径调用；
C4 显示端：候选为空时**不缓存**（首帧配方没就绪不能把空表留整局）。
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
    data = read(SRC, "data", "SequencePatternData.java")
    terminal = read(SRC, "block", "entity", "SequencePatternTerminalBlockEntity.java")
    packet = read(SRC, "network", "SetSequenceImportPacket.java")
    menu = read(SRC, "menu", "SequencePatternTerminalMenu.java")
    jei = read(SRC, "client", "SequenceTerminalJeiPlugin.java")
    screen = read(SRC, "client", "screen", "SequencePatternTerminalScreen.java")
    sync = read(SRC, "network", "SyncStepMachinesPacket.java")

    section("C1 主原料候选有地方落盘（用户实测「只写是个石头台阶」的根因）")
    check("AssemblyData 含 ingredientCandidates 分量",
          "List<ItemStack> ingredientCandidates) {" in data
          and "public record AssemblyData(" in data)
    # 2026-10-06 更新：本条原本锚定「保留旧 5 参构造器（供既有代码编译）」，等于固化
    # 「5 参构造器可接受」这一已被用户否定的行为 —— 而它正是静默吞掉主原料候选组的唯一入口
    # （AssemblyWatchdog#changeStepMachine 用它重建总样板 ⇒ 石头台阶那组候选消失）。
    # 现在改为断言**它已不存在**：重建总样板只能显式给出第 6 参，坑在编译期就不可达。
    check("已删除会吞主原料候选组的 5 参便捷构造器（重建必须显式给第 6 参）",
          "public AssemblyData(final ItemStack ingredient, final int loops, final List<UnitEntry> units,"
          not in data)
    check("监视器「更换机器」重建总样板时带上主原料候选（与 inputCandidates 同一口径）",
          "assembly.results(), assembly.scraps(),\n                assembly.ingredientCandidates());"
          in read(SRC, "support", "AssemblyWatchdog.java"))
    check("writeAssembly 写主原料候选（复用同一 TAG_INPUT_CANDIDATES）",
          "writeCandidates(tag, assembly.ingredientCandidates(), registries);" in data)
    check("readAssembly 读回主原料候选",
          "readOutputs(data, TAG_SCRAPS, registries),\n            readCandidates(data, registries));" in data)
    check("提供 candidatesOrRepresentative 回落（缺候选 ⇒ 代表物一件）",
          "public List<ItemStack> candidatesOrRepresentative() {" in data)
    check("**总样板登记整组主原料候选**（不是写死代表物一件）",
          "mergeInput(items, data.candidatesOrRepresentative()," in read(SRC, "item", "SequenceAssemblyPatternItem.java"))
    check("终端把候选写进总样板（AssemblyData 第 6 参）",
          "ingredientCandidates()\n        );" in terminal)
    check("终端 NBT 写出候选（客户端镜像才能读到，顶部那一格才轮播）",
          "SequencePatternData.writeCandidates(tag, ingredientCandidates, registries);" in terminal)

    section("C2 单元样板候选不会被「重建」截断")
    check("bindStepCrafter 整条透传（含 inputCandidates）",
          "unit.recipeType(), unit.inputCandidates()), registries);" in terminal
          and "private" not in terminal.split("bindStepCrafter")[1][:400].split("unit.inputCandidates()")[0][-200:])
    check("unbindStepCrafter 同样整条透传",
          terminal.count("unit.recipeType(), unit.inputCandidates()), registries);") >= 2)
    check("不再有把 UnitData 重建为 5 参的调用（会把候选/requiresInput/displayName 抹掉）",
          "new SequencePatternData.UnitData(unit.machine(), unit.input(), crafterName,\n"
          "                unit.recipe(), unit.step())" not in terminal
          and "new SequencePatternData.UnitData(unit.machine(), unit.input(), \"\",\n"
              "                unit.recipe(), unit.step())" not in terminal)

    section("C3 老样板自愈：按配方补算并写回（幂等）")
    check("存在 healUnitCandidateTags()",
          "public int healUnitCandidateTags() {" in terminal)
    check("只补「已有配方 id 但候选 < 2」的步（判不出来绝不猜）",
          "unit == null || unit.inputCandidates().size() >= 2" in terminal
          and "if (recipe == null) {" in terminal)
    check("重算结果仍 < 2 时不写（保持老格式）",
          "if (candidates.size() < 2) {\n                continue;" in terminal)
    check("写回时整条透传（只补候选组）",
          "unit.recipeType(), candidates), registries);" in terminal)
    check("顺带补起步原料候选（查配方 ingredient）",
          "private static List<ItemStack> ingredientCandidatesByRecipe(" in terminal)
    check("界面同步路径真的调用它（SyncStepMachinesPacket#from）",
          "terminal.healUnitCandidateTags();" in sync)
    check("幂等说明写清（已有候选直接跳过）",
          "幂等" in terminal)

    section("C4 显示端：空结果不入缓存")
    check("stepInputCache 只在非空时写入",
          "if (!candidates.isEmpty()) {\n            stepInputCache.put(key, candidates);" in screen)
    check("assemblyInputCache 只在非空时写入",
          "if (!candidates.isEmpty()) {\n            assemblyInputCache.put(key, candidates);" in screen)
    check("行图标用 cycleCandidate（>1 才轮播）",
          "guiGraphics.renderItem(GhostMarkerRenderer.cycleCandidate(candidates), x, y);" in screen)
    check("轮播由 gameTime/CYCLE_TICKS 驱动（图标真的会换）",
          "Math.floorDiv(tick, CYCLE_TICKS)" in read(SRC, "client", "widget", "GhostMarkerRenderer.java"))

    section("C5 导入链把两组候选都带下去（新样板立刻就对）")
    check("包内含 ingredientCandidates 字段",
          "List<ItemStack> ingredientCandidates," in packet)
    check("encode/decode 对称（漏读会整表串位）",
          "writeStacks(buf, packet.ingredientCandidates());" in packet
          and "final List<ItemStack> ingredientCandidates = readStacks(buf);" in packet)
    check("handle 把它交给菜单",
          "packet.stepCandidates(), packet.ingredientCandidates());" in packet)
    check("JEI 侧算出起步原料候选",
          "SequencedRecipeProbe.candidates(input)" in jei)
    check("菜单把它落到终端",
          "terminal.setIngredientCandidates(ingredientCandidates);" in menu)
    check("终端把它持久化（NBT）",
          "SequencePatternData.writeCandidates(tag, ingredientCandidates, registries);" in terminal
          and "ingredientCandidates.addAll(SequencePatternData.readCandidates(tag, registries));" in terminal)

    section("C6 输入类别按配方限定（精密构件只铁粒、列车轨道才铁粒或锌粒）")
    chamber = read(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
    check("存在 inputCategoryIdForRecipe（id = input:<配方>#<物品>）",
          "public static String inputCategoryIdForRecipe(final Item item," in chamber)
    check("computeBusCategories 的步骤输入用配方限定 id",
          "final String inputCategory = inputCategoryIdForRecipe(representative.getItem(), recipeIdForCategory);"
          in chamber)
    check("起步原料兜底同样用配方限定 id",
          "final String mainCategory = inputCategoryIdForRecipe(representative.getItem(), recipeIdForCategory);"
          in chamber)

    check("存在 categoryRecipeOrdered（输入类别按 id 里的配方段查订单）",
          "private boolean categoryRecipeOrdered(final BusCategoryInfo info) {" in chamber)
    # 2026-10-05：该闸门新增了 /rs_create_compat reuse on 的中间产物豁免
    # （`if (!categoryRecipeOrdered(info) && !reuseIntermediate)`），锚点随之更新。
    check("fillInternalForBus 用它拦住「没下单的配方」的输入类别（中间产物在 reuse 开启且有网内存量时豁免）",
          "if (!categoryRecipeOrdered(info) && !reuseIntermediate) {" in chamber
          and "RsccIntermediateReusePolicy.reuseIntermediates(getLevel())" in chamber
          and "hasNetworkStock(info)" in chamber)
    check("recipeOrdered 在缓存未就绪时不误停整仓",
          "不作判断" in chamber)

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
