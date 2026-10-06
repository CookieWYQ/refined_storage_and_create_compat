# -*- coding: utf-8 -*-
"""序列装配样板终端 V5（恢复左列单元样板库）补充语言键。

按用户规则：语言文件用脚本改，不手写整份 JSON。
本次新增：
- library.slot.tip：单元样板库空槽的手动 tooltip（左列 24 格可见窗口）。

用法: python tools/patch_lang_spt_v5_library_slot_tip.py
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat"
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

SPT = "gui.rs_create_compat.sequence_pattern_terminal."

zh_updates = {
    SPT + "library.slot.tip": "单元样板库空槽：放入单元样板，即可在流程编排中引用它",
}

en_updates = {
    SPT + "library.slot.tip":
        "Empty unit library slot: put a unit pattern here so the arrangement can reference it",
}


def patch(path, updates):
    with io.open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    data.update(updates)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("patched", os.path.basename(path), "->", len(updates), "keys")


if __name__ == "__main__":
    patch(os.path.join(LANG_DIR, "zh_cn.json"), zh_updates)
    patch(os.path.join(LANG_DIR, "en_us.json"), en_updates)
