# -*- coding: utf-8 -*-
"""第 17 轮语言键（幂等；json.load 前后校验、中英成对）。

用法：python tools/patch_round17_keeper_conservation_lang.py

本轮撤销上一轮新增的「升级槽均匀分配 + 剩余提示」功能（用户明确不要自动分配），
因此它唯一使用的语言键 `gui.rs_create_compat.advanced_quantity_keeper.upgrade_remain`
已无任何引用 —— 一并删除，保证语言键不残留孤儿。

校验：中英键集合完全一致（任何一次增删都不许只落一边）。
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
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

DELETE = [
    "gui.rs_create_compat.advanced_quantity_keeper.upgrade_remain",
]


def load(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def dump(path, data):
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def main():
    zh_path = os.path.join(LANG_DIR, "zh_cn.json")
    en_path = os.path.join(LANG_DIR, "en_us.json")
    zh, en = load(zh_path), load(en_path)

    removed = 0
    for key in DELETE:
        if key in zh:
            removed += 1
        zh.pop(key, None)
        en.pop(key, None)

    only_zh = sorted(set(zh) - set(en))
    only_en = sorted(set(en) - set(zh))
    if only_zh or only_en:
        print("[X] 中英键不成对：仅中 %s / 仅英 %s" % (only_zh[:5], only_en[:5]))
        sys.exit(1)

    dump(zh_path, zh)
    dump(en_path, en)
    print("[OK] 删除 %d 条；中英各 %d 条" % (removed, len(zh)))


if __name__ == "__main__":
    main()
