#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""用户第 4 条：把 `generate.all_duplicate` 从「错误提示」改写成「说明性提示」（中英成对）。

用法：python tools/patch_total_pattern_lang.py

规则：每次调用都**现读现改现写**（不缓存旧内容）；只改这一条文案，不动其它键。
"""

from __future__ import annotations

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

KEY = "gui.rs_create_compat.sequence_pattern_terminal.generate.all_duplicate"
ZH = "单元样板均已存在：本次只生成总样板"
EN = "Unit patterns already exist: total pattern only"


def patch(path, value):
    with io.open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    if data.get(KEY) == value:
        return False
    data[KEY] = value
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    return True


def main():
    changed_zh = patch(os.path.join(LANG_DIR, "zh_cn.json"), ZH)
    changed_en = patch(os.path.join(LANG_DIR, "en_us.json"), EN)
    if len(ZH) > 40:
        raise SystemExit("中文超过 40 字: %s (%d)" % (ZH, len(ZH)))
    print("zh_cn.json changed=%s / en_us.json changed=%s" % (changed_zh, changed_en))
    print("OK")


if __name__ == "__main__":
    main()
