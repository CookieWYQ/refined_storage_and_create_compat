# -*- coding: utf-8 -*-
"""新增「按配方分页」本轮的语言键，并删除被否掉方案留下的两个键。

为什么用脚本：语言文件是 JSON，手改容易漏掉中英成对 / 破坏缩进；
且本轮**要删两个键**（`group.unclassified` / `group.recipe.tip`）——
它们属于「类别 · 配方名」那个被用户否掉的方案，留着就会变成 `audit_lang_keys.py` 眼里的孤儿键。
一次执行内完成 load → 改 → dump（与工程既有做法一致）。
用法：python tools/add_bus_tabs_lang.py
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
BUS = "gui.rs_create_compat.bus_config."

# 键 → (中文, 英文)。中文 ≤ 40 字（本模组硬约束）。
ADD = {
    # 第一级：配方标签页
    BUS + "tab.other": ("通用 / 其他", "General / Other"),
    BUS + "tab.tip": ("点标签页切换配方：每一页只含这套配方自己的类别", "Click a tab: one recipe per page"),
    BUS + "tab.scroll.tip": ("滚轮 / 点左右箭头翻看更多配方标签页", "Scroll or use the arrows for more tabs"),
    BUS + "tab.multi.tip": ("这条总线上有多套配方：每套配方各占一页", "Multiple recipes here: one page each"),
    # 第二级：行内标注 + 搜索切页行
    BUS + "row.shared": ("多配方共用", "Shared"),
    BUS + "jump.tab.tip": ("点一下切到这套配方的那一页（不改勾选）", "Switch to that recipe's page"),
}

# 被否掉的「类别 · 配方名」后缀方案：删掉，避免孤儿键
REMOVE = [BUS + "group.unclassified", BUS + "group.recipe.tip"]


def patch(name, pairs, removals):
    path = os.path.join(LANG, name)
    with io.open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    added, removed = [], []
    for key, value in pairs.items():
        if key not in data:
            data[key] = value
            added.append(key)
    for key in removals:
        if key in data:
            del data[key]
            removed.append(key)
    ordered = {k: data[k] for k in sorted(data.keys())}
    with io.open(path, "w", encoding="utf-8") as handle:
        json.dump(ordered, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("%s: +%d %s  -%d %s" % (name, len(added), added, len(removed), removed))


patch("zh_cn.json", {k: v[0] for k, v in ADD.items()}, REMOVE)
patch("en_us.json", {k: v[1] for k, v in ADD.items()}, REMOVE)

zh = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
en = json.load(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))
for key in ADD:
    assert key in zh and key in en, key
    assert len(zh[key]) <= 40, (key, len(zh[key]))
for key in REMOVE:
    assert key not in zh and key not in en, key
assert set(k for k in zh if k.startswith(BUS)) == set(k for k in en if k.startswith(BUS))
print("OK: +%d keys paired (zh <= 40 chars), -%d keys removed, key sets aligned"
      % (len(ADD), len(REMOVE)))
