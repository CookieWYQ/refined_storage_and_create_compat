# -*- coding: utf-8 -*-
"""序列装配样板终端 v10 语言键增删改（幂等；中文单条 ≤ 40 字，中英成对）。

用法：python tools/patch_spt_v10_lang.py

本轮（用户实机反馈第 2/3/5/6/7/8 条）语言键变化：
  增：generate.tooltip.{missing,blocked,invalid}（缺料 / 已有总样板 / 流程无效的按钮提示）、
      unit_page.tip（翻页方式：滚动条或滚轮）
  改：generate.slot_full（3 格满 → 已有总样板必须先取走）、loops.tip（去掉「1–64」并声明只读）
  删：pattern_slots.tip、unit_slots.tip、rs_pattern_slots.where（解释界面元素含义的自研说明，按用户要求删除）、
      generate.missing_patterns（缺料不再发聊天栏）、unit_page.prev.tip / unit_page.next.tip（◀ ▶ 按钮已删除）、
      loops.label（标签改为「循环 ×N」只读文本，直接用 loops 键）、
      cannot_generate（该失败路径已改为静默 + 按钮置灰）

校验：json.load 可解析、中英键成对、中文单条 ≤ 40 字、占位符数量中英一致；任一不满足即退出码 1。
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
PREFIX = "gui.rs_create_compat.sequence_pattern_terminal."

# (key, 中文, 英文, 占位符个数)
UPSERT = [
    ("generate.tooltip.missing", "缺少 %s 个样板", "Missing %s patterns", 1),
    ("generate.tooltip.blocked", "请先取走现有总样板", "Take the existing total pattern out first", 0),
    ("generate.tooltip.invalid", "无法生成：流程或产物无效", "Cannot generate: no valid steps or outputs", 0),
    ("unit_page.tip", "拖动滚动条或滚轮翻页", "Drag the scrollbar or use the mouse wheel to page", 0),
    ("generate.slot_full", "已有总样板：请先取走再生成",
     "A total pattern already exists: take it out before generating again", 0),
    ("loops.tip", "整体循环次数（由配方给定，只读）",
     "Overall loop count (given by the recipe, read-only)", 0),
]

DELETE = [
    "pattern_slots.tip",
    "unit_slots.tip",
    "rs_pattern_slots.where",
    "generate.missing_patterns",
    "unit_page.prev.tip",
    "unit_page.next.tip",
    "loops.label",
    "cannot_generate",
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

    added = updated = removed = 0
    for key, zh_text, en_text, placeholders in UPSERT:
        full = PREFIX + key
        if len(zh_text) > 40:
            PROBLEMS.append("中文超过 40 字：%s -> %s" % (key, zh_text))
        if zh_text.count("%s") != placeholders or en_text.count("%s") != placeholders:
            PROBLEMS.append("占位符数量不符（约定 %d）：%s" % (placeholders, key))
        if full in zh:
            if zh[full] != zh_text:
                updated += 1
        else:
            added += 1
        zh[full] = zh_text
        en[full] = en_text

    for key in DELETE:
        full = PREFIX + key
        if full in zh:
            removed += 1
        zh.pop(full, None)
        en.pop(full, None)

    # 中英键必须成对（键集合完全一致）
    only_zh = sorted(set(zh) - set(en))
    only_en = sorted(set(en) - set(zh))
    if only_zh or only_en:
        PROBLEMS.append("中英键不成对：仅中 %s / 仅英 %s" % (only_zh[:5], only_en[:5]))
    # 中文单条 ≤ 40 字：只校验本轮增删改的那批键（既存键由历史轮次各自把关）
    touched = [PREFIX + item[0] for item in UPSERT] + [PREFIX + key for key in DELETE]
    for key in touched:
        value = en.get(key)
        if key in zh and len(zh[key]) > 40:
            PROBLEMS.append("中文超过 40 字：%s -> %s" % (key, zh[key]))
        if (key in zh) != (key in en):
            PROBLEMS.append("本轮键中英不成对：%s" % key)

    if PROBLEMS:
        for problem in PROBLEMS:
            print("[X] %s" % problem)
        sys.exit(1)

    dump(zh_path, zh)
    dump(en_path, en)
    print("[OK] 新增 %d / 覆盖 %d / 删除 %d 条；中英各 %d 条" % (added, updated, removed, len(zh)))


if __name__ == "__main__":
    main()
