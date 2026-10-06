# -*- coding: utf-8 -*-
# 第三轮 GUI 反馈：追加/修正中英文语言键（归流缓存仓三轴范围 + 标签过滤、高级定量保持器开关、
# 序列装配样板终端材料清单 / 产物废料子窗口、执行仓绑定状态两行显示）。
# 按用户规则用 Python 规范化更新 lang JSON，避免手写整份 json。
import io
import json
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat\src\main\resources\assets\rs_create_compat\lang"

zh_updates = {
    # ---------- 归流缓存仓：匹配 NBT（具体物品）与标签过滤（一整类）语义分开 ----------
    "gui.rs_create_compat.collection_cache.match_nbt": "匹配 NBT（此物品）",
    "gui.rs_create_compat.collection_cache.match_nbt.tip": (
        "只针对当前这一个具体物品：要求数据组件（NBT）完全一致才命中。"
        "要一次匹配一整类物品（例如所有原木），请用下面的「标签过滤」。"
    ),
    "gui.rs_create_compat.collection_cache.tag_filter": "标签过滤",
    "gui.rs_create_compat.collection_cache.tag_filter.hint": "如 minecraft:logs（匹配所有原木）",
    "gui.rs_create_compat.collection_cache.tag_filter.tip": (
        "按标签匹配一整类物品 / 流体：填入标签名（如 minecraft:logs）即可一次过滤所有原木，"
        "与某一个具体物品无关。启用标签过滤后，「匹配 NBT」不再生效（两者语义互斥）。"
    ),
    "gui.rs_create_compat.collection_cache.tag_filter.on": "已启用",
    "gui.rs_create_compat.collection_cache.tag_filter.off": "未启用",
    "gui.rs_create_compat.collection_cache.apply": "应用",
    "gui.rs_create_compat.collection_cache.apply.tip": "按输入的标签名启用过滤（标签下必须存在已注册资源）",
    "gui.rs_create_compat.collection_cache.clear": "清除",
    "gui.rs_create_compat.collection_cache.clear.tip": "取消标签过滤，回到按当前具体物品匹配",
    "gui.rs_create_compat.marker.tag_filter": "标签过滤：%s",
    # ---------- 归流缓存仓：三轴收集范围子窗口 ----------
    "gui.rs_create_compat.collection_cache.range.title": "收集范围（三轴）",
    "gui.rs_create_compat.collection_cache.range.tip": "收集范围（三轴）：X %s / Y %s / Z %s 格",
    "gui.rs_create_compat.collection_cache.range.limit": "当前上限 %s 格（范围升级 ×%s，每级 +25）",
    "gui.rs_create_compat.collection_cache.range.info": (
        "范围升级 %s 个：每级让三轴上限 +25。放入创造范围升级后三轴无限，输入框显示 ∞。"
    ),
    "gui.rs_create_compat.collection_cache.range.step": "Shift + 点击 = 一次 ±5；也可直接在输入框输入；不会超过上限",
    "gui.rs_create_compat.collection_cache.range.close": "关闭",
    # ---------- 高级定量保持器：两个独立开关 + 已存 / 目标 ----------
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_label": "自动合成",
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_on.tip": (
        "本槽自动合成：已开启（需要「自动合成升级」，缺少时无法开启）"
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_off.tip": "本槽自动合成：已关闭（点击开启）",
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_no_upgrade.tip": (
        "未安装「自动合成升级」，本机无法自动合成：先放入一个自动合成升级，再用本按钮按槽位开关。"
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.target": "目标：%s",
    # ---------- 序列装配样板终端：材料清单 + 产物 / 废料子窗口 ----------
    "gui.rs_create_compat.sequence_pattern_terminal.materials": "材料：%s",
    "gui.rs_create_compat.sequence_pattern_terminal.amount": "产出数量：%s",
    "gui.rs_create_compat.sequence_pattern_terminal.chance.hint": "右键该格：打开子窗口设置「产出概率 + 产出数量」",
    "gui.rs_create_compat.sequence_pattern_terminal.result.config.title": "产物配置",
    "gui.rs_create_compat.sequence_pattern_terminal.scrap.config.title": "废料配置",
    "gui.rs_create_compat.sequence_pattern_terminal.result.config.chance": "产出概率",
    "gui.rs_create_compat.sequence_pattern_terminal.result.config.amount": "产出数量",
    "gui.rs_create_compat.sequence_pattern_terminal.result.config.step": "Shift + 点击可一次 ±5（概率）/ ±10（数量）",
    "gui.rs_create_compat.sequence_pattern_terminal.result.config.confirm": "确定",
    "gui.rs_create_compat.sequence_pattern_terminal.result.config.cancel": "取消",
    # ---------- 序列执行仓：绑定状态拆两行（名字优先） ----------
    "gui.rs_create_compat.sequence_execution_chamber.recipe_type": "配方类型：%s",
    "gui.rs_create_compat.sequence_execution_chamber.recipe_type.unbound": "配方类型：未绑定",
}

en_updates = {
    "gui.rs_create_compat.collection_cache.match_nbt": "Match NBT (this item)",
    "gui.rs_create_compat.collection_cache.match_nbt.tip": (
        "Applies to this one concrete item: data components (NBT) must match exactly. "
        "To match a whole class of items (e.g. all logs), use the tag filter below."
    ),
    "gui.rs_create_compat.collection_cache.tag_filter": "Tag filter",
    "gui.rs_create_compat.collection_cache.tag_filter.hint": "e.g. minecraft:logs",
    "gui.rs_create_compat.collection_cache.tag_filter.tip": (
        "Matches a whole class by tag: enter a tag name (e.g. minecraft:logs) to filter every log, "
        "independent of any concrete item. While a tag filter is active, Match NBT no longer applies."
    ),
    "gui.rs_create_compat.collection_cache.tag_filter.on": "active",
    "gui.rs_create_compat.collection_cache.tag_filter.off": "inactive",
    "gui.rs_create_compat.collection_cache.apply": "Apply",
    "gui.rs_create_compat.collection_cache.apply.tip": "Enable the filter for the entered tag (the tag must contain registered resources)",
    "gui.rs_create_compat.collection_cache.clear": "Clear",
    "gui.rs_create_compat.collection_cache.clear.tip": "Remove the tag filter and match the concrete item again",
    "gui.rs_create_compat.marker.tag_filter": "Tag filter: %s",
    "gui.rs_create_compat.collection_cache.range.title": "Collect range (3 axes)",
    "gui.rs_create_compat.collection_cache.range.tip": "Collect range (3 axes): X %s / Y %s / Z %s blocks",
    "gui.rs_create_compat.collection_cache.range.limit": "Current limit %s blocks (range upgrades x%s, +25 each)",
    "gui.rs_create_compat.collection_cache.range.info": (
        "%s range upgrade(s): each adds +25 to every axis limit. With a creative range upgrade all axes are infinite (shows ∞)."
    ),
    "gui.rs_create_compat.collection_cache.range.step": "Shift + click = ±5 at once; you can also type directly; values never exceed the limit",
    "gui.rs_create_compat.collection_cache.range.close": "Close",
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_label": "Autocraft",
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_on.tip": (
        "Autocraft for this slot: ON (requires an autocrafting upgrade)"
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_off.tip": "Autocraft for this slot: OFF (click to enable)",
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_no_upgrade.tip": (
        "No autocrafting upgrade installed, so this machine cannot autocraft. Insert one, then use this button per slot."
    ),
    "gui.rs_create_compat.advanced_quantity_keeper.target": "Target: %s",
    "gui.rs_create_compat.sequence_pattern_terminal.materials": "Materials: %s",
    "gui.rs_create_compat.sequence_pattern_terminal.amount": "Output amount: %s",
    "gui.rs_create_compat.sequence_pattern_terminal.chance.hint": "Right-click the slot to set chance + output amount in a sub-window",
    "gui.rs_create_compat.sequence_pattern_terminal.result.config.title": "Result config",
    "gui.rs_create_compat.sequence_pattern_terminal.scrap.config.title": "Scrap config",
    "gui.rs_create_compat.sequence_pattern_terminal.result.config.chance": "Chance",
    "gui.rs_create_compat.sequence_pattern_terminal.result.config.amount": "Amount",
    "gui.rs_create_compat.sequence_pattern_terminal.result.config.step": "Shift + click steps by 5 (chance) / 10 (amount)",
    "gui.rs_create_compat.sequence_pattern_terminal.result.config.confirm": "Confirm",
    "gui.rs_create_compat.sequence_pattern_terminal.result.config.cancel": "Cancel",
    "gui.rs_create_compat.sequence_execution_chamber.recipe_type": "Recipe type: %s",
    "gui.rs_create_compat.sequence_execution_chamber.recipe_type.unbound": "Recipe type: unbound",
}


def patch(path, updates):
    with io.open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    data.update(updates)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("patched", path, "keys:", len(updates))


patch(ROOT + r"\zh_cn.json", zh_updates)
patch(ROOT + r"\en_us.json", en_updates)
