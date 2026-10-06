# -*- coding: utf-8 -*-
"""「装填器：蓝图解析/打印失败必须可见」的语言键增改。

用户要求（原话要点）
--------------------
「我尝试打印一个包含传送带的结构，但是它并没有收集到任何东西，点击打印的时候他也什么都没打，
但是他却提示成功收集了。」→ 失败路径不得报成功、不得静默，必须给玩家一句能看懂的原因。

本脚本补两句（都只在失败路径出现，成功路径一字不改）：

1. ``no_materials``：蓝图在位却解析不出材料清单（文件缺失 / 为空 / 蓝图里没有需要物品的方块）。
   原先只写了一条 10 秒心跳日志，玩家侧完全静默 —— 表现为"机器什么都没做"。
   用于：``cacheRequirementsFromBlueprint`` 兜底失败、独立模式 ``standaloneNeeds`` 返回空、
   完成报告前需求清单为空（此时绝不能报"全部资源都已收集完毕"）。
2. ``not_printed``：Create 把「蓝图没能加载」也表现为"输出槽多一张空白蓝图"
   （``initializePrinter`` 的 ``schematicErrored`` / ``schematicExpired``：清空蓝图槽 + 输出槽 +1），
   旧实现据此发「收集完成」横幅 = 用户看到的假成功；这里补一句如实说明"一个方块都没放"。

做法
----
``json.load`` → 改键 → ``json.load`` 回读校验 → 中文单条 ≤ 40 字断言 → 按既有排版（键名排序）写回。
用法：``python tools/add_loader_schematic_parse_lang.py``
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
LIMIT = 40

ZH = {
    "block.rs_create_compat.schematic_loader.no_materials":
        "这个蓝图不需要任何材料（或未能解析出方块）",
    "block.rs_create_compat.schematic_loader.not_printed":
        "蓝图 %s 未能打印：加农炮没有放置任何方块",
}

EN = {
    "block.rs_create_compat.schematic_loader.no_materials":
        "No materials needed (or no blocks could be parsed)",
    "block.rs_create_compat.schematic_loader.not_printed":
        "Blueprint %s was not printed: the cannon placed no blocks",
}


def apply(lang_file, table):
    path = os.path.join(LANG_DIR, lang_file)
    with io.open(path, "r", encoding="utf-8") as handle:
        data = json.loads(handle.read())
    sorted_before = list(data) == sorted(data)
    for key, value in table.items():
        data[key] = value
    if sorted_before:
        data = {key: data[key] for key in sorted(data)}
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    # 回读校验 + 中文长度断言
    with io.open(path, "r", encoding="utf-8") as handle:
        loaded = json.load(handle)
    for key, value in table.items():
        assert loaded.get(key) == value, "%s 回读不一致：%r" % (key, loaded.get(key))
        if lang_file.startswith("zh"):
            assert len(value) <= LIMIT, "%s 中文 %d 字（上限 %d）" % (key, len(value), LIMIT)
    print("[OK] %-11s 已写入 %d 个键（原文件%s排序）"
          % (lang_file, len(table), "本就有序，保持" if sorted_before else "未排序，保持原序"))
    return True


def main():
    ok = apply("zh_cn.json", ZH) and apply("en_us.json", EN)
    if ok:
        print("语言键增改完成（json.load 回读一致；中文单条 ≤ %d 字）" % LIMIT)
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
