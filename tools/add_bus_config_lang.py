#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「总线详细配置子界面 + 条上只读展示」这一轮的语言键写入 zh_cn / en_us。

为什么用脚本而不是手改 JSON：语言文件必须保持「行序稳定 + 合法 JSON + UTF-8」，
手改容易漏逗号 / 破坏编码；本脚本
  1) 用 json 读入两份文件（顺带校验语法）；
  2) 只**就地更新**已有键的值（不改变它们的相对顺序），新键按字典序插入到正确位置
     —— 于是「文件保持字典序」这一既有约定不破，diff 也最小；
  3) 用与工程既有文件一致的格式（2 空格缩进 / `": "` / 非 ASCII 原样 / 结尾换行）写回；
  4) 写回后再 json.load 一次做自校验，并打印每条新文案的字数（中文 ≤ 40 / 英文 ≤ 90）。

用法：python tools/add_bus_config_lang.py
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

# ---------- 新增键（前缀 gui.rs_create_compat.bus_config.） ----------
NEW_ZH = {
    "button": "详细配置…",
    "button.tip": "打开「详细配置」：搜索 + 分组勾选 + 按一类整选",
    "button.hint": "类别条只读展示，勾选动作都在子界面里完成",
    "auto.hint": "当前为自动收回：手动勾选在切到「手动」后生效",
    "disabled.tip": "停用期间不能打开「详细配置」（先恢复唯一归属）",
    "title": "类别详细配置",
    "step": "第 %s 步的中间产物",
    "step.unknown": "步骤未知的中间产物",
    "group.items": "物品输入",
    "group.fluids": "流体输入",
    "group.step": "第 %s 步中间产物",
    "group.step.unknown": "步骤未知的中间产物",
    "group.tip": "点表头 = 本组全选 / 全不选（未全选时一次勾上本组）",
    "row.tip": "点一下切换这一类的勾选（确定后才提交给服务端）",
    "list.scroll.tip": "列表：滚轮翻看 / 拖动右侧滚动条",
    "search.hint": "搜索",
    "search.tip": "按显示名或注册名搜索（输入即过滤，组内没有命中则整组隐藏）",
    "quick.items": "全部物品",
    "quick.items.tip": "把该配方涉及的全部物品输入一起勾上（= 逐个勾选，结果完全一致）",
    "quick.fluids": "全部流体",
    "quick.fluids.tip": "把该配方涉及的全部流体一起勾上（注水 + 注岩浆不用逐个勾）",
    "quick.intermediates": "全部中间产物",
    "quick.intermediates.tip": "把全部步骤的中间产物一起勾上",
    "quick.clear": "全部清空",
    "quick.clear.tip": "清空全部勾选（之后不会导出 / 收回任何类别）",
    "summary": "已选 %s / %s 项",
    "mode.auto": "自动模式：勾选切「手动」后生效",
    "mode.auto.tip": "当前自动收回：输入类不收、其余自动收；这里勾的会在切到手动后生效",
    "mode.manual": "点「确定」提交，取消则丢弃改动",
    "mode.manual.tip": "本界面只是编辑态：点「确定」才把整份勾选提交给服务端",
    "confirm": "确定",
    "confirm.tip": "把这份勾选一次性提交给服务端（服务端权威）",
    "cancel": "取消",
    "cancel.tip": "丢弃本次改动并返回总线界面",
    "empty": "没有匹配的类别",
}
NEW_EN = {
    "button": "Details…",
    "button.tip": "Open the detailed config: search, groups, bulk select",
    "button.hint": "The bar is read-only; all ticking happens in the sub-screen",
    "auto.hint": "Auto reclaim: manual ticks apply after switching to Manual",
    "disabled.tip": "Detailed config is unavailable while the bus is disabled",
    "title": "Bus category config",
    "step": "Intermediate of step %s",
    "step.unknown": "Intermediate (step unknown)",
    "group.items": "Item inputs",
    "group.fluids": "Fluid inputs",
    "group.step": "Step %s intermediates",
    "group.step.unknown": "Intermediates (step unknown)",
    "group.tip": "Click the header to select / clear this whole group",
    "row.tip": "Click to toggle this category (submitted on Confirm)",
    "list.scroll.tip": "List: mouse wheel or drag the scrollbar",
    "search.hint": "Search",
    "search.tip": "Search by display name or registry id (filters as you type)",
    "quick.items": "All items",
    "quick.items.tip": "Tick every item input (same as ticking them one by one)",
    "quick.fluids": "All fluids",
    "quick.fluids.tip": "Tick every fluid input (water + lava in one click)",
    "quick.intermediates": "All steps",
    "quick.intermediates.tip": "Tick the intermediates of every step",
    "quick.clear": "Clear all",
    "quick.clear.tip": "Clear every tick (then nothing is exported / reclaimed)",
    "summary": "%s / %s selected",
    "mode.auto": "Auto mode: ticks apply in Manual mode",
    "mode.auto.tip": "Auto reclaim: inputs are protected, the rest is reclaimed",
    "mode.manual": "Confirm submits; Cancel discards",
    "mode.manual.tip": "This screen is an edit buffer; Confirm submits the whole set",
    "confirm": "Confirm",
    "confirm.tip": "Submit this selection to the server",
    "cancel": "Cancel",
    "cancel.tip": "Discard changes and go back",
    "empty": "No matching category",
}

# ---------- 其它新增键（挂在两条总线各自的既有前缀下：条上只读展示后的「已选摘要」行） ----------
NEW_OTHER = {
    "gui.rs_create_compat.exporter_executor.summary.selected": ("当前已选：%s", "Selected: %s"),
    "gui.rs_create_compat.importer_executor.summary.selected": ("当前已选：%s", "Selected: %s"),
}

# ---------- 就地更新已有键（条上改成只读展示后，旧文案会误导玩家） ----------
UPDATE = {
    "gui.rs_create_compat.exporter_executor.click": (
        "条上只读展示；改勾选请点「详细配置…」", "Read-only bar; use the Details button"),
    "gui.rs_create_compat.exporter_executor.wheel": (
        "滚轮：翻看更多类别", "Wheel: browse more categories"),
    "gui.rs_create_compat.importer_executor.click": (
        "条上只读展示；改勾选请点「详细配置…」", "Read-only bar; use the Details button"),
    "gui.rs_create_compat.importer_executor.wheel": (
        "滚轮：翻看更多类别", "Wheel: browse more categories"),
    "gui.rs_create_compat.importer_executor.auto.locked.tip": (
        "自动模式：本格仅展示；勾选在「详细配置…」里改，切手动后生效",
        "Auto mode: display only; ticks apply in Manual mode"),
    "gui.rs_create_compat.importer_executor.locked.tip": (
        "已连到总线输出执行仓。条上只读；勾选请点「详细配置…」",
        "Linked to a Bus Output chamber. Read-only bar; ticking is in Details"),
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
    # 本轮改版后本脚本已作废：它会把已删除的「一键整选 / 按步中间产物」键重新写回去。
    # 语言键迁移请改用 tools/apply_bus_config_round2_lang.py（同样保持行序 + json.load 校验）。
    print("[skip] 本脚本已被 tools/apply_bus_config_round2_lang.py 取代（再跑会把已删除的键写回），"
          "不执行任何写入。")
    return 0


def _legacy_main():
    problems = []
    for name, new_keys, lang_index in (("zh_cn.json", NEW_ZH, 0), ("en_us.json", NEW_EN, 1)):
        path = os.path.join(LANG_DIR, name)
        data = load(path)
        added = 0
        for suffix, value in new_keys.items():
            if ordered_insert(data, BUS + suffix, value):
                added += 1
        for key, values in NEW_OTHER.items():
            if ordered_insert(data, key, values[lang_index]):
                added += 1
        for key, values in UPDATE.items():
            if key not in data:
                problems.append("%s 缺少待更新的键: %s" % (name, key))
                continue
            data[key] = values[lang_index]
        save(path, data)
        # 写回后重新解析一次（自校验：语法 / 编码 / 无尾逗号）
        reloaded = load(path)
        if len(reloaded) != len(data):
            problems.append("%s 写回后键数不一致：%d → %d" % (name, len(data), len(reloaded)))
        keys = list(reloaded.keys())
        if keys != sorted(keys):
            first_bad = next(i for i in range(1, len(keys)) if keys[i] < keys[i - 1])
            problems.append("%s 键序不再字典序（首个倒序位置 %d：%r → %r）"
                            % (name, first_bad, keys[first_bad - 1], keys[first_bad]))
        print("[OK] %s：新增 %d 键，更新 %d 键，现有 %d 键" % (name, added, len(UPDATE), len(reloaded)))

    zh = load(os.path.join(LANG_DIR, "zh_cn.json"))
    en = load(os.path.join(LANG_DIR, "en_us.json"))
    if set(zh) != set(en):
        problems.append("zh / en 键集不一致：%s" % sorted(set(zh) ^ set(en))[:6])
    new_keys = [BUS + suffix for suffix in NEW_ZH] + list(NEW_OTHER)
    missing = [key for key in new_keys if key not in zh or key not in en]
    if missing:
        problems.append("新键缺失：%s" % missing[:6])
    long_zh = sorted((key, len(zh[key])) for key in new_keys if len(zh[key]) > 40)
    long_en = sorted((key, len(en[key])) for key in new_keys if len(en[key]) > 90)
    if long_zh:
        problems.append("中文超 40 字：%s" % long_zh)
    if long_en:
        problems.append("英文超 90 字符：%s" % long_en)
    print("[OK] bus_config 键数：%d（zh = en）" % len(new_keys))
    print("[OK] 最长中文 %d 字 / 最长英文 %d 字符"
          % (max(len(zh[key]) for key in new_keys), max(len(en[key]) for key in new_keys)))

    if problems:
        print("问题总数: %d" % len(problems))
        for problem in problems:
            print("  - " + problem)
        return 1
    print("问题总数: 0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
