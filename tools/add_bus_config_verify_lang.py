#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""加入「总线未配置（用户第 5 条）」横幅的语言键（中英成对）。

用法：python tools/add_bus_config_verify_lang.py

规则：每次调用都**现读现改现写**（不缓存旧内容），只补缺失键、不覆盖已有翻译。
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

ZH = {
    "gui.rs_create_compat.bus_config_banner.title": "总线未配置：已暂停，不会消耗原料",
    "gui.rs_create_compat.bus_config_banner.gap_input": "第 %s 步（%s）：缺输入总线配置",
    "gui.rs_create_compat.bus_config_banner.gap_output": "第 %s 步（%s）：缺输出总线配置",
    "gui.rs_create_compat.bus_config_banner.more": "另有 %s 步未配置总线",
    "gui.rs_create_compat.bus_config_banner.hint": "请为该步机器配置输入/输出总线后再继续",
    "gui.rs_create_compat.intermediate_visible.title": "本流程中间产物（终端看不到）",
    "gui.rs_create_compat.intermediate_visible.more": "另有 %s 种中间产物",
    "gui.rs_create_compat.intermediate_visible.hint": "放一台中间产物缓存仓即可在终端看到这些条目",
}
EN = {
    "gui.rs_create_compat.bus_config_banner.title": "Bus not configured: paused, nothing consumed",
    "gui.rs_create_compat.bus_config_banner.gap_input": "Step %s (%s): input bus not configured",
    "gui.rs_create_compat.bus_config_banner.gap_output": "Step %s (%s): output bus not configured",
    "gui.rs_create_compat.bus_config_banner.more": "%s more step(s) without bus config",
    "gui.rs_create_compat.bus_config_banner.hint": "Configure that step's input/output bus, then resume",
    "gui.rs_create_compat.intermediate_visible.title": "Pipeline intermediates (hidden from terminal)",
    "gui.rs_create_compat.intermediate_visible.more": "%s more kind(s) of intermediates",
    "gui.rs_create_compat.intermediate_visible.hint": "Place an intermediate cache block to see them in the terminal",
}


def merge(path, pairs):
    with io.open(path, "r", encoding="utf-8") as handle:
        data = json.load(handle)
    added = 0
    for key, value in pairs.items():
        if key not in data:
            data[key] = value
            added += 1
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    return added


def main():
    zh = merge(os.path.join(LANG_DIR, "zh_cn.json"), ZH)
    en = merge(os.path.join(LANG_DIR, "en_us.json"), EN)
    print("zh_cn.json added=%d / en_us.json added=%d" % (zh, en))
    for key, value in ZH.items():
        if len(value) > 40:
            raise SystemExit("中文超过 40 字: %s = %s" % (key, value))
    print("OK")


if __name__ == "__main__":
    main()
