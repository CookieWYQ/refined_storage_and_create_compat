# -*- coding: utf-8 -*-
"""追加本轮（工具栏/过滤 + 多选输入循环显示 + 阻塞来源）语言键：中英成对、中文 ≤ 40 字。

一次执行内完成 load → 改 → dump（不手工编辑 json），并按硬规则复核：
  * 中英键集合完全一致；
  * 中文长度 ≤ 40；
  * 占位符 %s 个数中英一致。
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

CC = "gui.rs_create_compat.collection_cache."
SPT = "gui.rs_create_compat.sequence_pattern_terminal."
SFS = "gui.rs_create_compat.step_filter_select."

# key -> (zh, en)
ADD = {
    # ---- 归流缓存仓：阻塞来源（tooltip 必须说清「被谁阻塞」） ----
    CC + "blocked.by_id": ("阻塞来源：该资源被精确阻塞", "Blocked by: this resource exactly"),
    CC + "blocked.by_tag": ("阻塞来源：标签 %s", "Blocked by tag(s): %s"),

    # ---- 序列装配样板终端：多选（标签）输入原料的循环显示 / 过滤入口 ----
    SPT + "input_slot.any": ("可选原料（%s 种，任一皆可）", "Accepted inputs (%s kinds, any of them)"),
    SPT + "card.input_any": ("可选输入（%s 种，任一皆可）", "Accepted inputs (%s kinds, any of them)"),
    SPT + "filter": ("筛选…", "Filter..."),
    SPT + "filter.tip": ("按步骤类型 / 按机器过滤流程编排（从列表点选，不用输入名字或编号）",
                         "Filter the arrangement by step type / machine (pick from a list)"),
    SPT + "filter.current.type": ("按步骤类型：%s", "Step type: %s"),
    SPT + "filter.current.machine": ("按机器：%s", "Machine: %s"),
    SPT + "filter.all": ("全部", "All"),

    # ---- 过滤选择子窗口（与「机器选择」同一套可搜索选择器） ----
    SFS + "title": ("过滤流程编排", "Filter arrangement"),
    SFS + "dim.type": ("按步骤类型", "By step type"),
    SFS + "dim.machine": ("按机器", "By machine"),
    SFS + "dim.type.tip": ("按该步用到的机器种类过滤（例如机械手）",
                           "Filter by the machine kind used by the step"),
    SFS + "dim.machine.tip": ("按该步指派的执行仓过滤（同类机器多台时按名字区分）",
                              "Filter by the assigned chamber of the step"),
    SFS + "all": ("全部（不过滤）", "All (no filter)"),
    SFS + "current": ("当前过滤：步骤 %s ｜ 机器 %s", "Filter: step %s | machine %s"),
    SFS + "search_hint": ("按名称搜索", "Search by name"),
    SFS + "search.tip": ("只按列表里的显示名匹配（中英文都能用，不需要输入编号）",
                         "Matches the displayed name only (no ids needed)"),
    SFS + "hint": ("滚轮或点半边切换，选中后点确定", "Scroll or click a side, then confirm"),
    SFS + "confirm": ("确定", "Confirm"),
    SFS + "confirm.tip": ("应用当前维度与列表里选中的项（两个维度可组合）",
                          "Apply the selected entry of the current dimension"),
    SFS + "cancel": ("取消", "Cancel"),
    SFS + "cancel.tip": ("不改变任何过滤条件", "Change nothing"),
}


def load(name):
    with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
        return json.load(handle)


def dump(name, data):
    # 不排序：保留既有键顺序，新键追加在末尾（避免整文件重排带来无意义 diff / 覆盖并行新增键）
    with io.open(os.path.join(LANG_DIR, name), "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def main():
    zh, en = load("zh_cn.json"), load("en_us.json")
    problems = []
    for key, (zh_value, en_value) in ADD.items():
        zh[key] = zh_value
        en[key] = en_value
        if len(zh_value) > 40:
            problems.append("中文超 40 字（%d）：%s" % (len(zh_value), key))
        if zh_value.count("%s") != en_value.count("%s"):
            problems.append("占位符数量不一致：%s" % key)
    if set(zh) != set(en):
        problems.append("中英键集合不一致")
    if problems:
        for problem in problems:
            print("[X] " + problem)
        sys.exit(1)
    dump("zh_cn.json", zh)
    dump("en_us.json", en)
    print("新增键 %d 条（中英成对，中文均 ≤ 40 字）：" % len(ADD))
    for key in sorted(ADD):
        print("  + %s = %s" % (key, zh[key]))
    print("问题总数: 0")


if __name__ == "__main__":
    main()
