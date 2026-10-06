# -*- coding: utf-8 -*-
"""本轮（归流缓存仓：标签条目 tooltip / 输入面配置对齐）的语言片段更新脚本。

按仓库约定：语言键一律写进 tools/lang_frag_cc.json，再由 tools/apply_lang_frag.py 幂等合并。
本脚本只负责把本轮的增删写进片段文件；写完请执行：
    python tools/apply_lang_frag.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FRAG = os.path.join(ROOT, "tools", "lang_frag_cc.json")

# 新增 / 覆盖
UPSERTS = {
    # 标签过滤器 tooltip 标题（替换掉"只显示示例物名字"的旧观感）
    "gui.rs_create_compat.collection_cache.marker.tag_filter": {
        "zh": "按标签匹配（会匹配到一整类方块 / 物品）",
        "en": "Match by tag (a whole class of blocks/items)",
    },
    # 命中规模：物品（方块 / 物品）与流体分开表述
    "gui.rs_create_compat.collection_cache.marker.matched": {
        "zh": "会匹配到的方块 / 物品：%s 种",
        "en": "Matches %s block/item type(s)",
    },
    "gui.rs_create_compat.collection_cache.marker.matched_fluid": {
        "zh": "会匹配到的流体：%s 种",
        "en": "Matches %s fluid type(s)",
    },
}

# 删除：输入面配置改为与执行仓面配置同款「十字网 + 图例」，底部说明块已移除
DELETES = [
    "gui.rs_create_compat.collection_cache.input_face.hint.1",
    "gui.rs_create_compat.collection_cache.input_face.hint.2",
    "gui.rs_create_compat.collection_cache.input_face.hint.3",
    "gui.rs_create_compat.collection_cache.input_face.hint.tip",
]


def main():
    with open(FRAG, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    for key, value in UPSERTS.items():
        data[key] = value
    for key in DELETES:
        data[key] = {"delete": True}
    with open(FRAG, "w", encoding="utf-8") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("[OK] lang_frag_cc.json：新增/覆盖 %d 键，标记删除 %d 键" % (len(UPSERTS), len(DELETES)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
