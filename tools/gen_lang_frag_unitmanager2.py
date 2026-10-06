#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""写出本轮（v7：SPT 删除单元样板库交互区 + 管理舱搜索/配方名称）的语言键片段：
tools/lang_frag_unitmanager2.json

写完请执行：python tools/apply_lang_frag.py
（文件名排在 lang_frag_unitmanager.json 之后，因此可覆盖同名键）
"""
from __future__ import annotations

import json
from pathlib import Path
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "tools" / "lang_frag_unitmanager2.json"

UPM = "gui.rs_create_compat.unit_pattern_manager"
SPT = "gui.rs_create_compat.sequence_pattern_terminal"

FRAGMENT = {
    # ── 管理舱：分组提示 + 搜索框 + 空状态 ───────────────────────────────
    UPM + ".group.terminal": {
        "en": "Terminal unit library (legacy)",
        "zh": "终端单元样板库（旧）",
    },
    UPM + ".group.name": {
        "en": "Chamber: %s",
        "zh": "执行舱：%s",
    },
    UPM + ".group.recipe": {
        "en": "Recipe: %s",
        "zh": "配方：%s",
    },
    UPM + ".group.no_recipe": {
        "en": "No recipe type bound",
        "zh": "未绑定配方类型",
    },
    UPM + ".group.extract_only": {
        "en": "This group is extract-only (take out, cannot insert)",
        "zh": "该组只出不进（可取出、不可放入）",
    },
    UPM + ".group.read_write": {
        "en": "This group accepts and yields patterns",
        "zh": "该组可放可取",
    },
    UPM + ".hint.no_match": {
        "en": "No matching unit pattern",
        "zh": "没有匹配的单元样板",
    },
    UPM + ".search.tip": {
        "en": "Filter unit patterns by recipe name, pattern name, or input item name/id",
        "zh": "过滤单元样板：按配方名称 / 单元样板名 / 输入物名或物品 id",
    },
    # ── 单元样板 tooltip：输入原料标注（图标 + 名字由图标区给出，id 由文本行给出）──
    "item.rs_create_compat.sequence_unit_pattern.input": {
        "en": "Input: %s",
        "zh": "输入原料：%s",
    },
    # ── SPT：左列说明文字（原单元样板库交互区已删除）─────────────────────
    SPT + ".library.moved.1": {
        "en": "Unit library has moved to",
        "zh": "样板库已移至",
    },
    SPT + ".library.moved.2": {
        "en": "the Unit Pattern Manager",
        "zh": "单元样板管理舱",
    },
    SPT + ".library.moved.3": {
        "en": "(old patterns kept)",
        "zh": "旧样板仍可取出",
    },
    SPT + ".library.moved.tip": {
        "en": "The unit library is no longer part of this terminal: unit patterns of every chamber are managed "
              "in the Unit Pattern Manager. Patterns stored here before are kept as-is and can be taken out there.",
        "zh": "单元样板库不再由本终端承载：单元样板统一在「单元样板管理舱」里按执行仓分组管理。"
              "此前存在本终端里的样板原样保留，可在管理舱里直接取出。",
    },
    # ── 失效键清理（原「单元样板库」交互区 / 旧 Tab 页专用）──────────────
    SPT + ".library.tip": {"delete": True},
    SPT + ".library.slot.tip": {"delete": True},
    SPT + ".library.section.none": {"delete": True},
    SPT + ".library.section.unmatched": {"delete": True},
    SPT + ".library.section.unmatched.sub": {"delete": True},
    SPT + ".library.section.push.tip": {"delete": True},
    SPT + ".library.section.count": {"delete": True},
    SPT + ".library.section.empty": {"delete": True},
    SPT + ".tab.unit": {"delete": True},
    SPT + ".tab.assembly": {"delete": True},
    SPT + ".tab.unit.tip": {"delete": True},
    SPT + ".tab.assembly.tip": {"delete": True},
    SPT + ".generate_unit.tip": {"delete": True},
}


def main() -> None:
    with TARGET.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(FRAGMENT, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    upserts = sum(1 for v in FRAGMENT.values() if not (isinstance(v, dict) and v.get("delete")))
    deletes = len(FRAGMENT) - upserts
    print("[ok] %s: 覆盖/新增 %d 键，删除 %d 键"
          % (TARGET.relative_to(ROOT), upserts, deletes))


if __name__ == "__main__":
    main()
