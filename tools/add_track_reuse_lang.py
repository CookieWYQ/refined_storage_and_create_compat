# -*- coding: utf-8 -*-
"""新增「列车轨道支持」本轮的两条语言键（总线详细配置界面：可复用标记 + tooltip 说明）。

为什么要脚本：语言文件是 JSON，手改容易漏掉中英成对 / 破坏缩进。
本脚本一次执行内完成 load → 改 → dump（与工程既有做法一致）。
用法：python tools/add_track_reuse_lang.py
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

# 键 → (中文, 英文)。中文 ≤ 40 字（本模组硬约束）。
KEYS = {
    # 行内后缀（跟在「第 N 步」后面）：只是提示，不改变勾选语义
    "gui.rs_create_compat.bus_config.row.reusable": ("可复用", "Reusable"),
    # 表头 tooltip 的一行说明（手动渲染）
    "gui.rs_create_compat.bus_config.group.reusable.tip": (
        "本页含与其它配方的同类型步骤（步骤类型与输入一致），可复用同一台机器",
        "Some steps here match other recipes (same type and inputs): reuse one machine"),
}


def patch(name, pairs):
    path = os.path.join(LANG, name)
    with io.open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    added = []
    for key, value in pairs.items():
        if key not in data:
            data[key] = value
            added.append(key)
    # 保持「键有序」这一既有风格（json.dump 后仍是合法 JSON，客户端不依赖顺序）
    ordered = {k: data[k] for k in sorted(data.keys())}
    with io.open(path, "w", encoding="utf-8") as handle:
        json.dump(ordered, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("%s: +%d %s" % (name, len(added), added))


patch("zh_cn.json", {k: v[0] for k, v in KEYS.items()})
patch("en_us.json", {k: v[1] for k, v in KEYS.items()})

# 自检：中英成对 + 中文长度
zh = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
en = json.load(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))
for key in KEYS:
    assert key in zh and key in en, key
    assert len(zh[key]) <= 40, (key, len(zh[key]))
print("OK: %d keys paired, zh length <= 40" % len(KEYS))
