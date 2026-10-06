# -*- coding: utf-8 -*-
"""为归流缓存仓 v2 重排布局补充语言键（避免手写 JSON）。

本次新增/补齐：
- 收集范围控件（标题行右侧）：标签 / 提示 / 耗电说明（此前代码引用了这些键但语言文件里缺失，
  界面上会直接显示原始 key，属既有缺陷，这里一并补上）。
- 流体/气体独立面板标题与提示。
- 升级槽面板标题。

用法: python tools/patch_lang_gui_v5.py
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

CC = "gui.rs_create_compat.collection_cache."

zh_updates = {
    CC + "range": "范围",
    CC + "range.tip": "收集范围：以本机为中心 %s 格（范围越大扫描越远、耗电越高）",
    CC + "range.energy": "当前额外耗电：+%s FE/t",
    CC + "fluid_region": "流体/气体",
    CC + "fluid_region.tip": "缓存区内的流体/气体（每格 1000 mB）。滚轮可翻看更多种类，左键点击取出 1 桶。",
    CC + "upgrade": "升级",
}

en_updates = {
    CC + "range": "Range",
    CC + "range.tip": "Collection range: %s blocks around this block (a larger range scans further and costs more energy)",
    CC + "range.energy": "Extra energy cost: +%s FE/t",
    CC + "fluid_region": "Fluids/Gases",
    CC + "fluid_region.tip": "Fluids/gases cached here (1000 mB per slot). Scroll to see more kinds, left-click to extract 1 bucket.",
    CC + "upgrade": "Upgrades",
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
