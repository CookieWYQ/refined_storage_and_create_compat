# -*- coding: utf-8 -*-
"""round50：定量保持器「目标数量输入框 + 桶换算」新增文案（中英成对）。

口径（用户硬要求）：
  * 不写「或」、不写「等 N 种」、不写「图标轮换」、不枚举种类数；
  * 中文 <= 40 字；
  * 中英键集合必须一致（本脚本最后自检）。
"""
import sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

import io
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

# 键 -> (zh, en)
NEW = {
    "gui.rs_create_compat.quantity_keeper.target_tooltip": (
        "本机保持的目标数量（流体/气体按 mB，1 桶 = 1000 mB）：%s",
        "Target kept by this machine (fluids/gases in mB, 1 bucket = 1000 mB): %s",
    ),
    # 高级版：原来的 target_tooltip 文案补上「输入框写入的永远是 mB 原值」这句口径
    "gui.rs_create_compat.advanced_quantity_keeper.target_tooltip": (
        "本行保持的目标数量（流体/气体按 mB，1 桶 = 1000 mB）：%s",
        "Target kept for this row (fluids/gases in mB, 1 bucket = 1000 mB): %s",
    ),
}


def main():
    tables = {}
    for name in ("zh_cn.json", "en_us.json"):
        with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
            tables[name] = json.load(handle)
    for key, (zh, en) in NEW.items():
        tables["zh_cn.json"][key] = zh
        tables["en_us.json"][key] = en
        print("  [KEY] %s" % key)
        print("        zh = %s (%d 字)" % (zh, len(zh)))
        print("        en = %s" % en)
        if len(zh) > 40:
            raise SystemExit("中文超 40 字: " + key)
        for banned in ("或", "等 %s 种", "图标轮换"):
            if banned in zh:
                raise SystemExit("禁用措辞 %s: %s" % (banned, key))
    for name, table in tables.items():
        with io.open(os.path.join(LANG_DIR, name), "w", encoding="utf-8") as handle:
            json.dump(table, handle, ensure_ascii=False, indent=2)
            handle.write("\n")
    zh_keys = set(tables["zh_cn.json"])
    en_keys = set(tables["en_us.json"])
    print("键数：zh %d / en %d（一致：%s）" % (len(zh_keys), len(en_keys), zh_keys == en_keys))
    if zh_keys != en_keys:
        print("差集 zh-en: %s" % sorted(zh_keys - en_keys)[:5])
        print("差集 en-zh: %s" % sorted(en_keys - zh_keys)[:5])
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
