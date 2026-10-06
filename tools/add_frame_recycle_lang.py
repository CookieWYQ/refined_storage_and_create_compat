# -*- coding: utf-8 -*-
"""补 / 改「回收方式」相关的语言键（分隔框架 + 伪装框架外壳，用户第 1 / 4 条）。

写入的键
--------
新增：
  * ``message.rs_create_compat.frame_take_off_hint``
    —— 不潜行时空手 / 扳手右键一个「已套住分隔框架」的格子时的提示（告诉玩家怎么取下）；
  * ``block.rs_create_compat.camouflage_frame.hint.shell_removed``
    —— 潜行 + 扳手只取下了外壳方块、框架本体保留；
  * ``block.rs_create_compat.camouflage_frame.hint.no_shell``
    —— 这一格还没选过外观，「取回内层方块」没有东西可取。
改写：
  * ``block.rs_create_compat.camouflage_frame.hint.take_off_hint``
    —— 原来只说「潜行右键可取下」，现在要把「扳手只取外壳方块」这一条也讲清楚（否则玩家不会知道
       有两条不同的取下路径）。

形式要求（硬规则）
------------------
1. 一律用脚本写入（json.load 回读校验，可反复执行 = 幂等）；
2. 中文单条 ≤ 40 字；
3. 中英两份必须同键；键序保持原文件的分类分组（新键插在同类锚点之后，不堆到文件末尾）。

用法: python tools/add_frame_recycle_lang.py
"""

import io
import json
import os
import sys
from collections import OrderedDict
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

MAX_ZH = 40

#: 新增键：(键, 中文, 英文, 插在哪个键之后)
ADDITIONS = [
    ("message.rs_create_compat.frame_take_off_hint",
     "这一格已套住分隔框架：潜行右键即可取下并回收",
     "Separated frame here: sneak-right-click to take it back",
     "message.rs_create_compat.frame_remove_hint"),
    ("block.rs_create_compat.camouflage_frame.hint.shell_removed",
     "已取下外壳方块，框架本体保留着",
     "Shell block taken back; the frame itself stays",
     "block.rs_create_compat.camouflage_frame.hint.unwrapped"),
    ("block.rs_create_compat.camouflage_frame.hint.no_shell",
     "这一格还没有外壳方块，没有可取的",
     "No shell block here to take back",
     "block.rs_create_compat.camouflage_frame.hint.shell_removed"),
]

#: 改写键：(键, 中文, 英文)
REWRITES = [
    ("block.rs_create_compat.camouflage_frame.hint.take_off_hint",
     "已裹着伪装：潜行右键取下（扳手只取外壳方块）",
     "Camouflaged: sneak-right-click to take off (wrench: shell block only)"),
]


def read_ordered(path):
    """按原文件顺序读成 OrderedDict（JSON 对象顺序 = 文件里的书写顺序）。"""
    with io.open(path, "r", encoding="utf-8") as handle:
        return json.load(handle, object_pairs_hook=OrderedDict)


def write(path, data):
    text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    with io.open(path, "r", encoding="utf-8") as handle:
        return json.load(handle, object_pairs_hook=OrderedDict)


def apply(path, index):
    """index = 1 写中文（ADDITIONS 的二元组第 1 位）、2 写英文。"""
    data = read_ordered(path)
    for key, zh, en, _anchor in ADDITIONS:
        if key in data:
            data[key] = zh if index == 1 else en
    for key, zh, en in REWRITES:
        if key in data:
            data[key] = zh if index == 1 else en
    added = []
    for key, zh, en, anchor in ADDITIONS:
        if key in data:
            continue
        if anchor not in data:
            raise SystemExit("找不到锚点键：%s" % anchor)
        rebuilt = OrderedDict()
        for existing, value in data.items():
            rebuilt[existing] = value
            if existing == anchor:
                rebuilt[key] = zh if index == 1 else en
        data = rebuilt
        added.append(key)
    result = write(path, data)
    return result, added


def main():
    zh_path = os.path.join(LANG_DIR, "zh_cn.json")
    en_path = os.path.join(LANG_DIR, "en_us.json")
    zh, zh_added = apply(zh_path, 1)
    en, en_added = apply(en_path, 2)

    problems = []
    for key, zh_text, en_text, _anchor in ADDITIONS:
        if key not in zh or key not in en:
            problems.append("键缺失：" + key)
    if zh_added != en_added:
        problems.append("两份文件新增的键不一致：%s vs %s" % (zh_added, en_added))
    if set(zh) != set(en):
        problems.append("中英键集不一致（%d vs %d）" % (len(zh), len(en)))

    managed = [k for k, _z, _e, _a in ADDITIONS] + [k for k, _z, _e in REWRITES]
    for key in managed:
        if len(zh.get(key, "")) > MAX_ZH:
            problems.append("中文超长（%d 字）：%s" % (len(zh[key]), key))

    print("新增 %d 个键：%s" % (len(zh_added), ", ".join(zh_added) if zh_added else "<已存在，幂等跳过>"))
    for key, _zh, _en, _anchor in ADDITIONS:
        print("  %-64s %s" % (key, zh[key]))
    for key, _zh, _en in REWRITES:
        print("  改写 %-59s %s" % (key, zh[key]))
    print("中英键集一致：%s（%d 键）" % (set(zh) == set(en), len(zh)))
    if problems:
        print("问题：")
        for item in problems:
            print("  - " + item)
        return 1
    print("[OK] 语言键写入校验通过（json.load 回读通过、中英对齐、中文单条 ≤ %d 字）" % MAX_ZH)
    return 0


if __name__ == "__main__":
    sys.exit(main())
