# -*- coding: utf-8 -*-
"""归流缓存仓：流体格 tooltip 文案修正（行为改了 → 文案必须同步）。

改动原因：点击流体格从「直接取出一桶」改成「用玩家自己的空容器换出一桶」，
因此 "左键取出 1 桶" 会误导玩家（看起来不需要容器）。
本脚本只改这两个已有键的<b>值</b>，不新增键、不删键，改完自动校验。
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


LANG = r"d:\MODS\refined_storage_and_create_compat\src\main\resources\assets\rs_create_compat\lang"
KEY = "gui.rs_create_compat.collection_cache.cache.fluid_click"

CHANGES = {
    "zh_cn.json": ("左键取出 1 桶", "左键：用手里的空容器换出 1 桶"),
    "en_us.json": ("Left-click: take 1 bucket", "Left-click: swap an empty container for 1 bucket"),
}

problems = []
for filename, (old, new) in CHANGES.items():
    path = os.path.join(LANG, filename)
    with open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle, object_pairs_hook=dict)
    if KEY not in data:
        problems.append("%s: 缺少键 %s" % (filename, KEY))
        continue
    current = data[KEY]
    if current == new:
        print("  [=] %s: 已是目标文案" % filename)
    elif current != old:
        problems.append("%s: 期望旧值 %r，实际 %r（键内容被别的改动动过，请人工确认）"
                        % (filename, old, current))
        continue
    else:
        data[KEY] = new
        with open(path, "w", encoding="utf-8") as handle:
            json.dump(data, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
        print("  [OK] %s: %r -> %r" % (filename, old, new))

    # 复核：写回后必须仍是合法 JSON，且键值就是目标文案
    with open(path, "r", encoding="utf-8") as handle:
        check = json.load(handle)
    if check.get(KEY) != new:
        problems.append("%s: 写回后复核失败" % filename)
    if len(new) > (60 if filename.startswith("en") else 30):
        problems.append("%s: 文案过长" % filename)

print("=" * 60)
print("中文文案:", json.load(open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))[KEY])
print("英文文案:", json.load(open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))[KEY])
print("问题总数:", len(problems))
for item in problems:
    print("  [X]", item)
sys.exit(1 if problems else 0)
