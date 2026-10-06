#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""总线「详细配置」子界面改版后的语言键迁移（五分类 + 去掉一键整选 + 搜索跳页）。

为什么用脚本而不是手改 JSON：语言文件必须保持「键序字典序 + 合法 JSON + UTF-8」，
手改容易漏逗号 / 破坏编码 / 打破键序。本脚本
  1) 用 json 读入两份文件（顺带校验语法）；
  2) **删除**本轮取消的键（三个「一类整选」按钮、按步细分的中间产物分组、旧分组名）；
  3) **新增 / 就地更新**新键（保持字典序插入，diff 最小）；
  4) 用与工程既有文件一致的格式（2 空格缩进 / `": "` / 非 ASCII 原样 / 结尾换行）写回；
  5) 写回后再 json.load 一次自校验：语法 / 键序 / zh=en 成对 / 中文 ≤ 40 字。

用法：python tools/apply_bus_config_round2_lang.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

BUS = "gui.rs_create_compat.bus_config."

# ---------- 本轮删除的键 ----------
# 三个一键整选按钮（用户：「全部物品 / 全部流体 / 全部中间产物……就没必要」）
# 「中间产物按步细分」的分组名（用户：「中间产物下面不要再细分步骤」）
# 旧分组名 items / results / scraps（五分类改为 materials / feedstock / fluids / products / intermediates）
REMOVED = (
    "quick.items", "quick.items.tip",
    "quick.fluids", "quick.fluids.tip",
    "quick.intermediates", "quick.intermediates.tip",
    # 「全部清空」保留，但键名从 quick.clear 改为 btn.clear（与新的 btn.* 按钮组同族）
    "quick.clear", "quick.clear.tip",
    "group.items", "group.results", "group.scraps",
    "group.step", "group.step.machine", "group.step.unknown",
)

# ---------- 新增 / 更新键（前缀 gui.rs_create_compat.bus_config.） ----------
KEYS_ZH = {
    # 五个固定分类（顺序 = 界面顺序；用户指定）
    "group.materials": "原料",
    "group.feedstock": "输入时原料",
    "group.fluids": "流体",
    "group.products": "成品",
    "group.intermediates": "中间产物",
    # 表头 / 跳页 / 行内后缀
    "group.tip": "点表头 = 本组全选 / 全不选（左侧三角 = 折叠本组）",
    "group.collapse.tip": "点左侧三角 = 折叠 / 展开本组（不影响勾选）",
    "jump.text": "跳到「%s」",
    "jump.tip": "点一下展开这一页并翻到它（不改勾选）",
    "row.step": "第 %s 步",
    # 控制带（只有「全部清空」与「全部折叠 / 展开」）
    "btn.clear": "全部清空",
    "btn.clear.tip": "清空全部勾选（之后不会导出 / 收回任何类别）",
    "btn.fold": "全部折叠",
    "btn.fold.tip": "只留五个分类的目录行（不改变勾选）",
    "btn.unfold": "全部展开",
    "btn.unfold.tip": "展开全部分组（不改变勾选）",
    # 搜索（物品名 + 分组 / 工序名两种命中）
    "search.tip": "按物品 / 流体名或分组（工序）名搜索，点结果行直接勾选",
}
KEYS_EN = {
    "group.materials": "Materials",
    "group.feedstock": "Input-bus materials",
    "group.fluids": "Fluids",
    "group.products": "Products",
    "group.intermediates": "Intermediates",
    "group.tip": "Click the header to select / clear this whole group",
    "group.collapse.tip": "Click the triangle to collapse / expand (ticks unchanged)",
    "jump.text": "Jump to \"%s\"",
    "jump.tip": "Click to expand that page and scroll to it (ticks unchanged)",
    "row.step": "step %s",
    "btn.clear": "Clear all",
    "btn.clear.tip": "Clear every tick (nothing is exported / reclaimed)",
    "btn.fold": "Collapse all",
    "btn.fold.tip": "Keep only the five group headers (ticks unchanged)",
    "btn.unfold": "Expand all",
    "btn.unfold.tip": "Expand every group (ticks unchanged)",
    "search.tip": "Search by item / fluid or group (recipe) name; click a result to tick it",
}

# ---------- 就地更新已有键的值（文案随本轮改版而变） ----------
UPDATE = {
    "gui.rs_create_compat.bus_config.button.tip": (
        "打开「详细配置」：搜索 + 五个分类分组勾选",
        "Open the detailed config: search + five category groups"),
}


def load(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def ordered_insert(data, key, value):
    """把 key 插到字典序正确的位置（已存在则只更新值，位置不变）。"""
    if key in data:
        data[key] = value
        return False
    keys = list(data.keys())
    position = len(keys)
    for index, existing in enumerate(keys):
        if key < existing:
            position = index
            break
    items = list(data.items())
    items.insert(position, (key, value))
    data.clear()
    data.update(items)
    return True


def save(path, data):
    lines = ["{"]
    items = list(data.items())
    for index, (key, value) in enumerate(items):
        comma = "," if index < len(items) - 1 else ""
        lines.append("  %s: %s%s" % (json.dumps(key, ensure_ascii=False),
                                     json.dumps(value, ensure_ascii=False), comma))
    lines.append("}")
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")


def main():
    problems = []
    added_total = 0
    for name, new_keys, lang_index in (("zh_cn.json", KEYS_ZH, 0), ("en_us.json", KEYS_EN, 1)):
        path = os.path.join(LANG_DIR, name)
        data = load(path)
        removed = 0
        for suffix in REMOVED:
            if data.pop(BUS + suffix, None) is not None:
                removed += 1
        added = 0
        for suffix, value in new_keys.items():
            if ordered_insert(data, BUS + suffix, value):
                added += 1
        for key, values in UPDATE.items():
            if key not in data:
                problems.append("%s 缺少待更新的键: %s" % (name, key))
                continue
            data[key] = values[lang_index]
        save(path, data)
        reloaded = load(path)
        if len(reloaded) != len(data):
            problems.append("%s 写回后键数不一致：%d → %d" % (name, len(data), len(reloaded)))
        keys = list(reloaded.keys())
        # 只校验本脚本负责的 bus_config 段仍是字典序：整份文件的键序由历史累积决定，
        # 早就存在本脚本无权「顺手重排」的倒序（重排会让 diff 淹没一切），因此不对全文件断言。
        bus_only = [key for key in keys if key.startswith(BUS)]
        if bus_only != sorted(bus_only):
            first_bad = next(i for i in range(1, len(bus_only)) if bus_only[i] < bus_only[i - 1])
            problems.append("%s 的 bus_config 键序不再字典序（首个倒序位置 %d：%r → %r）"
                            % (name, first_bad, bus_only[first_bad - 1], bus_only[first_bad]))
        added_total += added
        print("[OK] %s：删除 %d 键 / 新增 %d 键 / 现有 %d 键" % (name, removed, added, len(reloaded)))

    zh = load(os.path.join(LANG_DIR, "zh_cn.json"))
    en = load(os.path.join(LANG_DIR, "en_us.json"))
    if set(zh) != set(en):
        problems.append("zh / en 键集不一致：%s" % sorted(set(zh) ^ set(en))[:6])

    bus_keys = [key for key in zh if key.startswith(BUS)]
    leftover = [key for key in bus_keys if key.endswith(tuple(REMOVED))]
    if leftover:
        problems.append("仍有本轮应删除的键：%s" % leftover)
    missing = [BUS + suffix for suffix in KEYS_ZH if BUS + suffix not in zh]
    if missing:
        problems.append("新键缺失：%s" % missing)

    long_zh = sorted((key, len(zh[key])) for key in bus_keys if len(zh[key]) > 40)
    long_en = sorted((key, len(en[key])) for key in bus_keys if len(en[key]) > 90)
    if long_zh:
        problems.append("中文超 40 字：%s" % long_zh)
    if long_en:
        problems.append("英文超 90 字符：%s" % long_en)

    print("[OK] bus_config 键数：%d（zh = en）" % len(bus_keys))
    print("[OK] 最长中文 %d 字 / 最长英文 %d 字符"
          % (max(len(zh[key]) for key in bus_keys), max(len(en[key]) for key in bus_keys)))
    print("[OK] 本轮新增键：%d" % added_total)

    if problems:
        print("问题总数: %d" % len(problems))
        for problem in problems:
            print("  - " + problem)
        return 1
    print("问题总数: 0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
