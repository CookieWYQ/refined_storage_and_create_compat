# -*- coding: utf-8 -*-
"""生成 SPT（序列装配样板终端 / 步骤详细配置 / 执行绑定配置）本轮语言键片段。

产物：tools/lang_frag_spt2.json（由 apply_lang_frag.py 合并进中英语言文件，幂等）。
用 Python 产出 json（不手写整份语言文件），遵循工程既有约定。
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_spt2.json")

SPT = "gui.rs_create_compat.sequence_pattern_terminal."
SD = "gui.rs_create_compat.step_detail."

FRAGMENT = {
    # ---------- 样板槽：只收总样板（item4） ----------
    SPT + "pattern_slots": {"en": "Total patterns", "zh": "总样板"},
    SPT + "pattern_slots.hint": {
        "en": "Holds only this mod's sequence assembly patterns (one per slot). "
              "Unit patterns and refinedstorage:pattern are rejected; generating/importing consumes nothing.",
        "zh": "只放本模组的序列装配总样板（一格一张）：单元样板与 refinedstorage:pattern 都放不进去；"
              "生成样板 / 导入配方不再消耗材料。",
    },
    # ---------- 流程编排行：合并后的交互说明 + 输入流体行（item6 / item8） ----------
    SPT + "card.hints": {
        "en": "Scroll = list; Ctrl+LMB = step config; LMB machine area = pick machine; "
              "Shift+LMB/RMB = move up/down; RMB = delete step",
        "zh": "滚轮 = 滚动列表；Ctrl+左键 = 详细配置；左键机器区 = 选机器；"
              "Shift+左键/右键 = 上移/下移；右键 = 删除该步",
    },
    SPT + "card.fluid": {"en": "Input fluid: %s (%s)", "zh": "输入流体：%s（%s）"},
    # ---------- 单元样板库标题：一条说明（原本两条几乎重复） ----------
    SPT + "library.tip": {
        "en": "Unit pattern library: drag patterns into the flow list; "
              "click the title to open the chamber unit summary",
        "zh": "单元样板库：存放单元样板，可拖到流程编排行；左键标题打开「执行仓单元样板汇总」",
    },
    # ---------- 生成 / 导入：不再消耗材料 ----------
    SPT + "generate.tip": {
        "en": "Generate the assembly pattern into the slot at the bottom-right, then take it out "
              "(no material consumed; move the old pattern away first)",
        "zh": "生成装配样板：写入界面右下角的「生成槽」，取走即可（不再消耗材料；槽里有样板时需先取走）",
    },
    SPT + "import.tip": {
        "en": "Open the recipe list and transfer a Create sequenced-assembly recipe into the flow "
              "(no material consumed)",
        "zh": "打开配方选择界面：把一张 Create 序列装配配方一键转移进流程（不再消耗材料）",
    },
    SPT + "cannot_generate": {
        "en": "Cannot generate: the flow and the outputs are both empty.",
        "zh": "无法生成装配样板：流程与产物都为空",
    },
    # ---------- 步骤详细配置：配方类型必须显示（item3） ----------
    SD + "recipe_type.tip": {
        "en": "Recipe type of this step (the raw id is shown in the grey line below)",
        "zh": "本步的配方类型（原始 id 见下方灰字）",
    },
    SD + "recipe_type.unset": {"en": "Unset", "zh": "未设置"},
}

# 已废弃 / 精简掉的提示文本（中英同时删除，保持语言文件干净）
DELETED = [
    SPT + "no_patterns",
    SPT + "chance.hint",
    SPT + "library.summary.tip",
    SPT + "card.hint",
    SPT + "card.move.hint",
    SPT + "card.detail_hint",
    SPT + "card.delete_hint",
    SPT + "card.machine.hint",
    SPT + "pattern_count.tip",
    SPT + "op.assembly.tip",
]

if __name__ == "__main__":
    data = {}
    for key, value in FRAGMENT.items():
        data[key] = value
    for key in DELETED:
        data[key] = {"delete": True}
    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("[OK] %s：%d 键（含 %d 个删除）" % (OUT, len(data), len(DELETED)))
