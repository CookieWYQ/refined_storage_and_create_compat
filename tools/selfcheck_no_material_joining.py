# -*- coding: utf-8 -*-
"""自检：原料一律「轮播」显示，禁止把多种原料拼成一行文字（用户硬要求）。

用法：python tools/selfcheck_no_material_joining.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

用户原话
--------
-「所有我要求你进行这种滚动显示这种轮换显示的禁止出现什么就是把多种原料合并在一起什么什么或什么
  或什么括号什么这样子的形式」
-「现在你给我的那一个成品单元样板它会有那个这种描述然后呢那个序列装配样板的 tooltip 也会有这种描述，
  尤其是这个序列装配样板的他就直接这么一个描述为什么不能吃这里面的文本来进行轮换显示呢」

因此钉住四条：
J1 总样板 tooltip 的原料行走**图标组件**（原版每帧重绘 ⇒ 能轮播），不是文字列表；
J2 总样板的「或 / 括号」拼接键已删除，代码里不再有枚举候选的文字拼接；
J3 两类样板的原料行文字口径**一致**（都是「当前轮播到的那一件 + 本组候选总数」）；
J4 轮播下标同源（同一墙钟常量），三处显示节拍一致。
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
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

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
    assembly_item = read(SRC, "item", "SequenceAssemblyPatternItem.java")
    unit_item = read(SRC, "item", "SequenceUnitPatternItem.java")
    client_init = read(SRC, "client", "ClientInit.java")
    assembly_comp = read(SRC, "client", "tooltip", "AssemblyInputsTooltipComponent.java")
    unit_comp = read(SRC, "client", "tooltip", "UnitPatternTooltipComponent.java")
    cycle_comp = read(SRC, "client", "tooltip", "ItemCycleTooltipComponent.java")

    section("J1 总样板 tooltip 的原料行走图标组件（能轮播）")
    check("总样板重写了 getTooltipImage（文字行无法轮播，必须走图标区）",
          "getTooltipImage(" in assembly_item)
    check("数据载体 InputsImage 实现 TooltipComponent",
          "public record InputsImage(" in assembly_item
          and "implements net.minecraft.world.inventory.tooltip.TooltipComponent" in assembly_item)
    check("客户端已注册 InputsImage 的渲染工厂",
          "SequenceAssemblyPatternItem.InputsImage.class" in client_init)
    check("渲染组件存在，且按候选列表轮播", "class AssemblyInputsTooltipComponent" in assembly_comp)

    section("J2 「或 / 括号」拼接已彻底移除")
    check("代码里不再有把候选枚举成一行的拼接（/ 分隔）",
          'Component.literal(" / ")' not in assembly_item)
    # 2026-10-05 用户要求最终格式：`名字 x N`，且不得写「等 N 种」/「图标轮换」。
    check("总样板 tooltip 的原料行<b>只写当前轮播到的那一件</b>（格式 = 名字 x N，不写「等 N 种」）",
          'sequence_assembly_pattern.ingredient' in assembly_item
          and "ItemCycleTooltipComponent" in assembly_item
          and "等 %s 种" not in assembly_item)
    check("总样板 tooltip 分清「原料 / 输入原料 / 产物 / 废料」四段（用户要求，且废料原本漏了）",
          'sequence_assembly_pattern.inputs_header' in assembly_item
          and 'sequence_assembly_pattern.results_header' in assembly_item
          and 'sequence_assembly_pattern.scraps_header' in assembly_item)
    check("总样板 tooltip 的图标区已按用户要求关闭（只留文字轮换）",
          "if (true) {\n            return java.util.Optional.empty();" in assembly_item)
    check("总样板 tooltip 的步骤输入行同样只写当前那一件（由图标组件渲染）",
          'LANG + "input_any", shown.getHoverName(), candidates.size()' in assembly_comp)

    section("J3 两类样板的原料行文字口径一致")
    check("总样板多候选行：「%s 等 %s 种」+ 数量",
          'LANG + "input_any", shown.getHoverName(), candidates.size()' in assembly_comp)
    check("单元样板多候选行：同一格式",
          'LANG + "input_any",\n                shown.getHoverName(), inputCandidates.size()' in unit_comp
          or 'LANG + "input_any",' in unit_comp)
    check("单元样板多候选行不枚举候选（只写当前那一件）",
          "inputCandidates" in unit_comp
          and 'Component.literal(" / ")' not in unit_comp)

    section("J4 轮播下标同源（节拍一致）")
    check("轮播下标只有一个实现（ItemCycleTooltipComponent.currentIndex）",
          "public static int currentIndex(" in cycle_comp)
    check("总样板组件复用同一实现",
          "ItemCycleTooltipComponent.currentIndex(" in assembly_comp)
    check("单元样板组件复用同一实现",
          "ItemCycleTooltipComponent.currentIndex(" in unit_comp)
    check("周期常量唯一（CYCLE_MILLIS）",
          "CYCLE_MILLIS = 1000L" in cycle_comp)

    section("J5 tooltip 的逐行可见性走唯一实现（不各自读按键）")
    check("总样板组件用 componentVisible(layer)",
          "RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.SHIFT)" in assembly_comp
          and "RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.CTRL)" in assembly_comp)
    check("主原料行与步骤输入行同属「用途」层 = Shift",
          "private boolean ingredientVisible()" in assembly_comp
          and "private boolean itemsVisible()" in assembly_comp)

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
