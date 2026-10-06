# -*- coding: utf-8 -*-
"""语言键批处理（一次执行内完成，中英成对）：

1) 删除整类「筛选」弹窗与终端筛选按钮的语言键：
   - 前缀 `gui.rs_create_compat.step_filter_select.`（整个子窗口）
   - 终端「筛选…」按钮相关的 5 条：...sequence_pattern_terminal.filter / .filter.all /
     .filter.current.machine / .filter.current.type / .filter.tip
   （终端自身的搜索框及其键保留：search_hint / search.tip 不在此列）
2) 新增「每一步行内跳过重复开关」与「全部已有」提示的键（中英成对、中文 ≤ 40 字）。
3) 把管理舱的全局勾选框 help 文案补一句「兼作新步骤默认档」。

用法：python tools/remove_step_filter_add_step_dedupe_lang.py
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

SFS_PREFIX = "gui.rs_create_compat.step_filter_select."
SPT = "gui.rs_create_compat.sequence_pattern_terminal."
UPM = "gui.rs_create_compat.unit_pattern_manager."

DROP_EXACT = {
    SPT + "filter",
    SPT + "filter.all",
    SPT + "filter.current.machine",
    SPT + "filter.current.type",
    SPT + "filter.tip",
}

NEW = {
    # 行内「跳过重复」开关
    SPT + "card.skip.tip": ("生成时跳过重复单元样板（本步）", "Skip duplicate unit patterns on generate (this step)"),
    SPT + "card.skip.exists": ("相同单元样板：%s", "Same unit pattern: %s"),
    SPT + "card.skip.state": ("生成时跳过：%s", "Skip on generate: %s"),
    SPT + "card.skip.has": ("已有", "Exists"),
    SPT + "card.skip.missing": ("没有", "None"),
    SPT + "card.skip.on": ("开", "ON"),
    SPT + "card.skip.off": ("关", "OFF"),
    SPT + "card.skip.help": ("点击切换本步是否跳过", "Click to toggle this step"),
    # 「全部步骤都已存在相同样板 ⇒ 一个也不生成」的一次性提示
    SPT + "generate.all_duplicate": ("每一步的相同单元样板都已存在，未生成任何样板",
                                     "Every step's identical unit pattern already exists; nothing was generated"),
}

# 全局勾选框的说明：现在是「新步骤默认档」的来源（终端改为每步开关）
HELP = {
    UPM + "dedupe.help": ("判据：操作类型 + 输入式原料；兼作新步骤默认档",
                          "Match: same operation + inputs; also the default for new steps"),
}


def main():
    for name, is_zh in (("zh_cn.json", True), ("en_us.json", False)):
        path = os.path.join(LANG_DIR, name)
        with io.open(path, encoding="utf-8") as handle:
            table = json.load(handle)
        before = len(table)
        for key in list(table):
            if key.startswith(SFS_PREFIX) or key in DROP_EXACT:
                del table[key]
        removed = before - len(table)
        added = 0
        for key, (zh, en) in NEW.items():
            table[key] = zh if is_zh else en
            added += 1
        for key, (zh, en) in HELP.items():
            if key in table:
                table[key] = zh if is_zh else en
        with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(table, handle, ensure_ascii=False, indent=2, sort_keys=True)
            handle.write("\n")
        print("%s: 删除 %d 条，新增 %d 条，现有 %d 条" % (name, removed, added, len(table)))


if __name__ == "__main__":
    main()
