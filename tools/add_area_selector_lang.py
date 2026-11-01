#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""round90：给「区域选定器 + 高级归流缓存仓」补齐中英语言键（幂等）。

键的命名空间（全部与代码里的字面量 / 动态前缀一一对应）：
  block.rs_create_compat.advanced_collection_cache[.help]
  item.rs_create_compat.area_selector[...]                 （物品 tooltip）
  message.rs_create_compat.area_selector.*                 （世界手势的动作栏提示）
  gui.rs_create_compat.area_selector.*                     （区域列表界面）
  gui.rs_create_compat.advanced_collection_cache.*         （高级仓界面新增控件）

用法：python tools/add_area_selector_lang.py
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

ZH = {
    "block.rs_create_compat.advanced_collection_cache": "高级归流缓存仓",
    "block.rs_create_compat.advanced_collection_cache.help":
        "缓存是基础版的两倍，吞吐不变；吸收范围只按「区域选定器」里的区域（取代 XYZ 半径）。",
    "item.rs_create_compat.area_selector": "区域选定器",
    "item.rs_create_compat.area_selector.help": "插进高级归流缓存仓的专用槽：只吸收框选区域内的资源。",
    "item.rs_create_compat.area_selector.dimension": "绑定维度：%s",
    "item.rs_create_compat.area_selector.unbound": "未绑定（第一次落笔时绑定到当前维度）",
    "item.rs_create_compat.area_selector.count": "区域：%s / %s",
    "item.rs_create_compat.area_selector.volume": "总体积：%s 格",
    "item.rs_create_compat.area_selector.tip.corner": "右键方块两次：先 A 角、再 B 角就提交一个区域",
    "item.rs_create_compat.area_selector.tip.scroll": "Ctrl + 滚轮：推拉正在框的盒子 / 调整选中区域（Shift ×5）",
    "item.rs_create_compat.area_selector.tip.sneak": "潜行 + 右键方块：取消当前框选；潜行 + 右键空气：打开区域列表",
    "item.rs_create_compat.area_selector.tip.list": "装进机器后，在机器界面点「区域」也能打开列表",
    "message.rs_create_compat.area_selector.first": "已记下 A 角 (%s, %s, %s)",
    "message.rs_create_compat.area_selector.committed": "已提交区域：%s 格 · %s",
    "message.rs_create_compat.area_selector.cancelled": "已取消当前框选",
    "message.rs_create_compat.area_selector.need_target": "请把准星对准一个方块",
    "message.rs_create_compat.area_selector.too_large": "区域超限：单区域最多 %s 格、单边最多 %s 格",
    "message.rs_create_compat.area_selector.limit": "一个选定器最多 %s 个区域",
    "message.rs_create_compat.area_selector.preview": "正在框：%s（%s 格）",
    "message.rs_create_compat.area_selector.no_selection": "先在区域列表里选中一个区域",
    "message.rs_create_compat.area_selector.resized": "已把选中区域调整为 %s 格",
    "gui.rs_create_compat.area_selector.title": "区域列表",
    "gui.rs_create_compat.area_selector.min": "最小",
    "gui.rs_create_compat.area_selector.max": "最大",
    "gui.rs_create_compat.area_selector.new": "新建",
    "gui.rs_create_compat.area_selector.delete": "删除",
    "gui.rs_create_compat.area_selector.delete.confirm": "确认删除",
    "gui.rs_create_compat.area_selector.show_all.on": "显示全部：开",
    "gui.rs_create_compat.area_selector.show_all.off": "显示全部：关",
    "gui.rs_create_compat.area_selector.close": "关闭",
    "gui.rs_create_compat.area_selector.rebind": "改绑维度",
    "gui.rs_create_compat.area_selector.dimension": "维度：%s",
    "gui.rs_create_compat.area_selector.unbound": "未绑定",
    "gui.rs_create_compat.area_selector.count": "区域 %s / %s",
    "gui.rs_create_compat.area_selector.volume": "体积 %s / 合计 %s",
    "gui.rs_create_compat.area_selector.hint.volume": "单区域上限 %s 格",
    "gui.rs_create_compat.area_selector.hint.empty": "还没有区域：右键方块两次，或点「新建」",
    "gui.rs_create_compat.area_selector.err.number": "输入不是合法整数",
    "gui.rs_create_compat.area_selector.err.side": "单边超限（最多 128 格）",
    "gui.rs_create_compat.area_selector.err.volume": "体积超限（见下方上限）",
    "gui.rs_create_compat.area_selector.err.world": "坐标超出世界边界",
    "gui.rs_create_compat.area_selector.err.limit": "区域数量已达上限",
    "gui.rs_create_compat.advanced_collection_cache.area_button": "区域",
    "gui.rs_create_compat.advanced_collection_cache.area_button.tip":
        "打开区域列表：新建 / 删除 / 精确编辑 6 个坐标",
    "gui.rs_create_compat.advanced_collection_cache.range_button": "范围",
    "gui.rs_create_compat.advanced_collection_cache.range_button.tip":
        "用橙色框画出选定器里的全部区域（5 秒）；吸收只按这些区域，XYZ 半径不再限制范围。",
    "gui.rs_create_compat.advanced_collection_cache.info.active": "区域 %s · %s 格",
    "gui.rs_create_compat.advanced_collection_cache.info.empty": "选定器没有区域 · 不吸收",
    "gui.rs_create_compat.advanced_collection_cache.info.dimension": "维度不符 · 不吸收",
    "gui.rs_create_compat.advanced_collection_cache.info.none": "未装选定器 · 不吸收",
    "gui.rs_create_compat.advanced_collection_cache.scope": "仅区域",
    "gui.rs_create_compat.advanced_collection_cache.area_slot.empty": "区域选定器槽",
    "gui.rs_create_compat.advanced_collection_cache.area_slot.empty.tip":
        "放入区域选定器：吸收范围只按它框选的区域（取代 XYZ 半径）；没装 / 区域为空 ⇒ 不吸收。",
    "gui.rs_create_compat.advanced_collection_cache.range_empty": "选定器里还没有区域",
}

EN = {
    "block.rs_create_compat.advanced_collection_cache": "Advanced Collection Cache",
    "block.rs_create_compat.advanced_collection_cache.help":
        "Twice the cache of the basic version, same throughput; absorption uses only the areas in an Area Selector (replacing the XYZ radii).",
    "item.rs_create_compat.area_selector": "Area Selector",
    "item.rs_create_compat.area_selector.help":
        "Put it in the Advanced Collection Cache: only resources inside the selected areas are absorbed.",
    "item.rs_create_compat.area_selector.dimension": "Bound dimension: %s",
    "item.rs_create_compat.area_selector.unbound": "Unbound (binds to the current dimension on first use)",
    "item.rs_create_compat.area_selector.count": "Areas: %s / %s",
    "item.rs_create_compat.area_selector.volume": "Total volume: %s blocks",
    "item.rs_create_compat.area_selector.tip.corner": "Right-click twice: corner A, then corner B commits one area",
    "item.rs_create_compat.area_selector.tip.scroll": "Ctrl + scroll: push/pull the box being drawn, or resize the selected area (Shift x5)",
    "item.rs_create_compat.area_selector.tip.sneak": "Sneak + right-click a block: cancel the current box; sneak + right-click air: open the area list",
    "item.rs_create_compat.area_selector.tip.list": "Once installed, the \"Area\" button in the machine GUI opens the same list",
    "message.rs_create_compat.area_selector.first": "Corner A set at (%s, %s, %s)",
    "message.rs_create_compat.area_selector.committed": "Area committed: %s blocks - %s",
    "message.rs_create_compat.area_selector.cancelled": "Current selection cancelled",
    "message.rs_create_compat.area_selector.need_target": "Aim at a block first",
    "message.rs_create_compat.area_selector.too_large": "Area too large: at most %s blocks and %s blocks per side",
    "message.rs_create_compat.area_selector.limit": "A selector holds at most %s areas",
    "message.rs_create_compat.area_selector.preview": "Drawing: %s (%s blocks)",
    "message.rs_create_compat.area_selector.no_selection": "Select an area in the area list first",
    "message.rs_create_compat.area_selector.resized": "Selected area resized to %s blocks",
    "gui.rs_create_compat.area_selector.title": "Area List",
    "gui.rs_create_compat.area_selector.min": "Min",
    "gui.rs_create_compat.area_selector.max": "Max",
    "gui.rs_create_compat.area_selector.new": "New",
    "gui.rs_create_compat.area_selector.delete": "Delete",
    "gui.rs_create_compat.area_selector.delete.confirm": "Confirm",
    "gui.rs_create_compat.area_selector.show_all.on": "Show all: ON",
    "gui.rs_create_compat.area_selector.show_all.off": "Show all: OFF",
    "gui.rs_create_compat.area_selector.close": "Close",
    "gui.rs_create_compat.area_selector.rebind": "Rebind",
    "gui.rs_create_compat.area_selector.dimension": "Dimension: %s",
    "gui.rs_create_compat.area_selector.unbound": "unbound",
    "gui.rs_create_compat.area_selector.count": "Areas %s / %s",
    "gui.rs_create_compat.area_selector.volume": "Volume %s / total %s",
    "gui.rs_create_compat.area_selector.hint.volume": "Per-area limit: %s blocks",
    "gui.rs_create_compat.area_selector.hint.empty": "No areas yet: right-click twice, or press New",
    "gui.rs_create_compat.area_selector.err.number": "Not a valid integer",
    "gui.rs_create_compat.area_selector.err.side": "Side too long (max 128 blocks)",
    "gui.rs_create_compat.area_selector.err.volume": "Volume too large (see the limit below)",
    "gui.rs_create_compat.area_selector.err.world": "Coordinates outside the world border",
    "gui.rs_create_compat.area_selector.err.limit": "Area limit reached",
    "gui.rs_create_compat.advanced_collection_cache.area_button": "Area",
    "gui.rs_create_compat.advanced_collection_cache.area_button.tip":
        "Open the area list: create, delete, or edit the 6 coordinates precisely",
    "gui.rs_create_compat.advanced_collection_cache.range_button": "Range",
    "gui.rs_create_compat.advanced_collection_cache.range_button.tip":
        "Draw every area of the selector with an orange box (5 s); absorption uses only these areas, XYZ radii no longer limit it.",
    "gui.rs_create_compat.advanced_collection_cache.info.active": "Areas %s - %s blocks",
    "gui.rs_create_compat.advanced_collection_cache.info.empty": "Selector has no areas - idle",
    "gui.rs_create_compat.advanced_collection_cache.info.dimension": "Wrong dimension - idle",
    "gui.rs_create_compat.advanced_collection_cache.info.none": "No selector - idle",
    "gui.rs_create_compat.advanced_collection_cache.scope": "Areas only",
    "gui.rs_create_compat.advanced_collection_cache.area_slot.empty": "Area Selector Slot",
    "gui.rs_create_compat.advanced_collection_cache.area_slot.empty.tip":
        "Insert an Area Selector: absorption uses only its areas (replacing the XYZ radii). No selector / no areas = idle.",
    "gui.rs_create_compat.advanced_collection_cache.range_empty": "The selector has no areas yet",
}


def apply(name, extra):
    path = os.path.join(LANG_DIR, name)
    with io.open(path, "r", encoding="utf-8") as handle:
        table = json.load(handle)
    added = 0
    for key, value in extra.items():
        if table.get(key) != value:
            table[key] = value
            added += 1
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(table, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    return added


def main():
    zh = apply("zh_cn.json", ZH)
    en = apply("en_us.json", EN)
    print("[info] zh_cn 更新 %d 键；en_us 更新 %d 键" % (zh, en))
    print("[result] 区域选定器 / 高级归流缓存仓语言键齐备（%d 条 × 2 语言）" % len(ZH))
    return 0


if __name__ == "__main__":
    sys.exit(main())
