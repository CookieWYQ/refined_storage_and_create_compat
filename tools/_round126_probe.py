# -*- coding: utf-8 -*-
"""第 126 轮临时探针（`_` 前缀，`_run_all_selfchecks.py` 不会收集它）。

用途：`selfcheck_round126_gui_text_tooltip.py --stats` 是正式入口；本文件只是把
「按文件 / 按文本类别统计未覆盖点」这一步单独跑一遍，方便写文档时贴数字。

用法：`python tools/_round126_probe.py`
"""
from __future__ import print_function

import collections
import importlib.util
import os
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
SPEC = importlib.util.spec_from_file_location(
    "round126", os.path.join(HERE, "selfcheck_round126_gui_text_tooltip.py"))
MOD = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)

freeze = MOD.load_freeze(MOD.FREEZE, [])
report = MOD.evaluate_sources(MOD.scanned_sources(), freeze)
uncovered = report["uncovered"]
print("未覆盖 %d 处（fit %d / 数值 %d）"
      % (len(uncovered),
         sum(1 for s in uncovered if s["kind"] == "fit"),
         sum(1 for s in uncovered if s["facts"]["numeric"])))
print("类别：%s" % dict(collections.Counter(s["facts"]["kind"] for s in uncovered)))
