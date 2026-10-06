# -*- coding: utf-8 -*-
"""第 16 轮语言键（幂等；中文单条 ≤ 40 字，中英成对）。

用法：python tools/patch_round16_keeper_machine_lang.py

本轮对应三条用户反馈：
  ① 高级定量保持器「升级槽均匀分配 + 槽位用尽时明确提示」：
       gui.rs_create_compat.advanced_quantity_keeper.upgrade_remain
  ② 序列装配样板终端「输入原料格必须有 tooltip」+「打开机器选择界面的两条入口」：
       sequence_pattern_terminal.input_slot.tip / .empty / .count
       sequence_pattern_terminal.card.machine_open
  ③ 机器选择子界面「支持搜索」（终端与自动合成监视器「更换机器」复用同一个类）：
       step_machine_select.search_hint / search.tip

校验：json.load 可解析、中英键集合完全一致、中文单条 ≤ 40 字、占位符数量中英一致。
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

KEEPER = "gui.rs_create_compat.advanced_quantity_keeper."
SPT = "gui.rs_create_compat.sequence_pattern_terminal."
SELECT = "gui.rs_create_compat.step_machine_select."

# (完整键, 中文, 英文, 占位符个数)
UPSERT = [
    (KEEPER + "upgrade_remain",
     "升级槽已满或已达上限，剩余 %s 个留在原处",
     "Upgrade slots are full: %s left in place", 1),
    (SPT + "input_slot.tip",
     "本步消耗的主原料，由配方导入决定",
     "Main ingredient of this step, set by the imported recipe", 0),
    (SPT + "input_slot.empty",
     "尚未导入配方，或该步没有物品原料",
     "No recipe imported yet, or this step takes no item", 0),
    (SPT + "input_slot.count",
     "数量：%s",
     "Amount: %s", 1),
    (SPT + "card.machine_open",
     "左键点机器名或 Ctrl+左键点该步骤：打开机器选择",
     "Click the machine name or Ctrl+click the step to open the machine picker", 0),
    (SELECT + "search_hint",
     "搜索机器名称",
     "Search machine name", 0),
    (SELECT + "search.tip",
     "按机器名搜索：命中 %s / 共 %s 台",
     "Search by machine name: %s of %s shown", 2),
]

PROBLEMS = []


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

    added = updated = 0
    for key, zh_text, en_text, placeholders in UPSERT:
        if len(zh_text) > 40:
            PROBLEMS.append("中文超过 40 字：%s -> %s" % (key, zh_text))
        if zh_text.count("%s") != placeholders or en_text.count("%s") != placeholders:
            PROBLEMS.append("占位符数量不符（约定 %d）：%s" % (placeholders, key))
        if key in zh:
            if zh[key] != zh_text or en.get(key) != en_text:
                updated += 1
        else:
            added += 1
        zh[key] = zh_text
        en[key] = en_text

    # 中英键必须成对（全量校验：任何一次增删都不许只落一边）
    only_zh = sorted(set(zh) - set(en))
    only_en = sorted(set(en) - set(zh))
    if only_zh or only_en:
        PROBLEMS.append("中英键不成对：仅中 %s / 仅英 %s" % (only_zh[:5], only_en[:5]))

    if PROBLEMS:
        for problem in PROBLEMS:
            print("[X] %s" % problem)
        sys.exit(1)

    dump(zh_path, zh)
    dump(en_path, en)
    print("[OK] 新增 %d / 覆盖 %d 条；中英各 %d 条" % (added, updated, len(zh)))


if __name__ == "__main__":
    main()
