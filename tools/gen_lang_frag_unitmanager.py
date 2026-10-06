#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""写出「单元样板管理舱」及其无线终端模式的语言键片段：tools/lang_frag_unitmanager.json

写完请执行：python tools/apply_lang_frag.py
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
TARGET = ROOT / "tools" / "lang_frag_unitmanager.json"
GUI = "gui.rs_create_compat.unit_pattern_manager"

FRAGMENT = {
    "block.rs_create_compat.unit_pattern_manager": {
        "en": "Unit Pattern Manager",
        "zh": "单元样板管理舱",
    },
    "block.rs_create_compat.unit_pattern_manager.help": {
        "en": "Manages the unit patterns of every execution chamber in the network, grouped by chamber; "
              "all sequence assembly vaults are merged into one in-memory view. "
              "Distinct from the autocrafter manager (which manages autocrafting patterns).",
        "zh": "管理网络中每台执行舱的单元样板（按执行舱分组），并把全部序列装配样板库的样板内存合并为一个视图。"
              "与「自动合成管理舱」（管自动合成样板）区分。",
    },
    GUI: {
        "en": "Unit Pattern Manager",
        "zh": "单元样板管理舱",
    },
    GUI + ".group.merged": {
        "en": "Sequence vaults (merged)",
        "zh": "序列装配样板库（网络合并）",
    },
    GUI + ".hint.inactive": {
        "en": "Not connected to a network",
        "zh": "未接入网络",
    },
    GUI + ".hint.empty": {
        "en": "No execution chamber in this network",
        "zh": "本网络内没有执行舱",
    },
    "gui.rs_create_compat.advanced_remote_terminal.mode.unit_manager": {
        "en": "Unit pattern manager",
        "zh": "单元样板管理",
    },
}


def main() -> None:
    with TARGET.open("w", encoding="utf-8", newline="\n") as handle:
        json.dump(FRAGMENT, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("[ok] %s: %d 键" % (TARGET.relative_to(ROOT), len(FRAGMENT)))


if __name__ == "__main__":
    main()
