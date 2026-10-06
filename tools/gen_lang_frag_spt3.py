# -*- coding: utf-8 -*-
"""生成 tools/lang_frag_spt3.json（序列执行仓 + SPT 流程编排本轮改动的语言键）。

本轮只涉及：
  * 执行仓「配方类型锁定」（仓里还有单元样板时不可改类型）新增 2 个键；
  * SPT 流程行「该步无输入原料」说明新增 1 个键；
  * tooltip 精简：缩短若干条「操作说明」、删除不再使用的 card.hints。

按用户规则用 Python 脚本产出 json（不手写），再交给 tools/apply_lang_frag.py 幂等合并。
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "tools", "lang_frag_spt3.json")

FRAG = {
    # ===== 新增：执行仓配方类型锁定 =====
    # 口径已升级为「链级」：本链上任一台还有单元样板即锁定（见 gen_lang_frag_execchain.py 的链语义）。
    "gui.rs_create_compat.sequence_execution_chamber.config.recipe_type.locked": {
        "en": "Locked: a chamber in this chain still holds unit patterns. Remove them all to change the recipe type.",
        "zh": "已锁定：本链上还有执行仓放着单元样板。全部取走后才能更改配方类型。",
    },
    "message.rs_create_compat.chamber_recipe_locked": {
        "en": "Unit patterns are still inside a chamber of this chain; take them all out before changing the recipe type.",
        "zh": "本链上还有执行仓放着单元样板：请先把它们全部取走，再更改配方类型。",
    },
    # ===== 新增：SPT 流程行「该步无输入原料」 =====
    "gui.rs_create_compat.sequence_pattern_terminal.card.no_item_input": {
        "en": "No input item for this step",
        "zh": "该步无输入原料",
    },

    # ===== 精简：操作说明大幅缩短 =====
    "gui.rs_create_compat.widget.scroll_select.hint": {
        "en": "Scroll to switch",
        "zh": "滚轮切换",
    },
    "gui.rs_create_compat.sequence_pattern_terminal.op.input.tip": {
        "en": "Input marker (filled on import; click to set)",
        "zh": "输入原料标记（导入时自动填入；点击可设置）",
    },
    "gui.rs_create_compat.sequence_pattern_terminal.pattern_slots.hint": {
        "en": "Holds only this mod's assembly patterns, one per slot",
        "zh": "只放本模组的序列装配总样板，一格一张",
    },
    "gui.rs_create_compat.sequence_pattern_terminal.library.tip": {
        "en": "Unit pattern library (click the title for the chamber summary)",
        "zh": "单元样板库（左键标题打开执行仓汇总）",
    },
    "gui.rs_create_compat.sequence_pattern_terminal.generate.tip": {
        "en": "Generate the assembly pattern into the slot at the bottom-right",
        "zh": "生成总样板到右下角生成槽（取走即可）",
    },
    "gui.rs_create_compat.sequence_pattern_terminal.import.tip": {
        "en": "Open the recipe picker and import a Create sequenced assembly recipe",
        "zh": "打开配方选择界面，一键导入 Create 序列装配配方",
    },
    "gui.rs_create_compat.sequence_pattern_terminal.section_readonly.tip": {
        "en": "Read-only marker slot (cannot be taken or placed)",
        "zh": "只读标记槽（不可取出 / 放入）",
    },
    "gui.rs_create_compat.sequence_pattern_terminal.generate.slot.take": {
        "en": "Take it out to use",
        "zh": "取走即可使用",
    },
    "gui.rs_create_compat.sequence_execution_chamber.config.recipe_type.hint": {
        "en": "Scroll to switch the type; click the icon to change machine",
        "zh": "滚轮切换配方类型；点图标换机器",
    },
    "gui.rs_create_compat.sequence_execution_chamber.config.recipe_type.tip": {
        "en": "The recipe type this chamber handles",
        "zh": "本执行仓负责的配方类型",
    },

    # ===== 删除：不再使用的操作说明（tooltip 精简） =====
    "gui.rs_create_compat.sequence_pattern_terminal.card.hints": {"delete": True},
}


def main():
    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(FRAG, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("[OK] 已写出 %s（%d 键）" % (os.path.relpath(OUT, ROOT), len(FRAG)))


if __name__ == "__main__":
    main()
