# -*- coding: utf-8 -*-
# 归流缓存仓重构（前端）：追加吸取开关 / 流体标记 / 匹配 tag / 缓存流体内容所需的中英文语言键。
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
    # 归流缓存仓帮助文本：补充流体/气体与 4 个吸取开关
    "block.rs_create_compat.collection_cache.help": (
        "接入 RS 网络后吸取周围散落的掉落物与流体/气体：匹配区用 ghost 标记（数量 / 匹配 NBT / 匹配 tag）"
        "指定可收集对象，凑够标记数量才收集；物品与流体优先进网络，装不下的暂存缓存区再逐 tick 回流。"
        "四个吸取开关分别控制物品 / 流体 / 气体 / 经验；速度升级提高吸取与回流入网速率，堆叠升级提高缓存每格上限。"
    ),
    # 匹配区说明：补充物品 / 流体 / 气体
    "gui.rs_create_compat.collection_cache.region_marker.tip": (
        "匹配区：ghost 标记可收集对象（物品 / 流体 / 气体）。左键手持物品标记，空手左键点击已标记槽打开配置，"
        "Shift+左键清除；同一物品只能标记一次。"
    ),
    # 4 个吸取开关（一行文本 + 一个按钮）
    "gui.rs_create_compat.collection_cache.absorb.item": "物品",
    "gui.rs_create_compat.collection_cache.absorb.fluid": "流体",
    "gui.rs_create_compat.collection_cache.absorb.gas": "气体",
    "gui.rs_create_compat.collection_cache.absorb.experience": "经验",
    "gui.rs_create_compat.collection_cache.absorb.item.tip": (
        "吸取开关 · 物品：开启后自动吸取范围内命中的掉落物（达到标记数量后收集）。"
    ),
    "gui.rs_create_compat.collection_cache.absorb.fluid.tip": (
        "吸取开关 · 流体：开启后自动吸取范围内的世界流体（每格 = 1000 mB）。"
    ),
    "gui.rs_create_compat.collection_cache.absorb.gas.tip": (
        "吸取开关 · 气体：开启后自动吸取范围内的气体（需安装提供气体的模组，如 Mekanism）。"
    ),
    "gui.rs_create_compat.collection_cache.absorb.experience.tip": (
        "吸取开关 · 经验：开启后自动吸取范围内的经验（装了附魔工业可用液态经验 / 经验颗粒，未装则用机械动力经验颗粒）。"
    ),
    # 标记配置界面：匹配 tag 与流体数量单位
    "gui.rs_create_compat.collection_cache.match_tag": "匹配 tag：%s",
    "gui.rs_create_compat.collection_cache.match_nbt.tip": "匹配 NBT：要求数据组件（NBT）完全一致才命中。",
    "gui.rs_create_compat.collection_cache.match_tag.tip": "匹配 tag：按对象所属标签匹配，忽略具体 id 的差异。",
    "gui.rs_create_compat.collection_cache.amount_mb": "数量 (mB)",
    # 缓存区流体信息（逐种内容未同步时的汇总 / 统计 tooltip 行）
    "gui.rs_create_compat.collection_cache.stats.fluid": "流体缓存：%s / %s",
    "gui.rs_create_compat.collection_cache.cache.fluid_summary": "流体 %s",
    # ghost 标记的通用 tooltip 行
    "gui.rs_create_compat.marker.amount": "数量：%s",
    "gui.rs_create_compat.marker.match_nbt": "匹配 NBT",
    "gui.rs_create_compat.marker.match_tag": "匹配 tag",
}

en_updates = {
    "block.rs_create_compat.collection_cache.help": (
        "Once connected to an RS network it absorbs nearby dropped items, fluids and gases: the marker area holds "
        "ghost markers (amount / match NBT / match tag). Items and fluids flow into the network first; overflow stays "
        "in the cache area and is retried every tick. Four absorb toggles control items / fluids / gases / experience; "
        "speed upgrades raise the absorb and insert rate, stack upgrades raise the per-slot capacity."
    ),
    "gui.rs_create_compat.collection_cache.region_marker.tip": (
        "Markers: ghost markers for what to collect (items / fluids / gases). Left-click with a held item to mark, "
        "empty-hand left-click an existing marker to configure it, Shift+left-click to clear. Each item can only be marked once."
    ),
    "gui.rs_create_compat.collection_cache.absorb.item": "Items",
    "gui.rs_create_compat.collection_cache.absorb.fluid": "Fluids",
    "gui.rs_create_compat.collection_cache.absorb.gas": "Gases",
    "gui.rs_create_compat.collection_cache.absorb.experience": "Experience",
    "gui.rs_create_compat.collection_cache.absorb.item.tip": (
        "Absorb · Items: when on, nearby matching dropped items are absorbed (once the marked amount is reached)."
    ),
    "gui.rs_create_compat.collection_cache.absorb.fluid.tip": (
        "Absorb · Fluids: when on, world fluids in range are absorbed (one block = 1000 mB)."
    ),
    "gui.rs_create_compat.collection_cache.absorb.gas.tip": (
        "Absorb · Gases: when on, gases in range are absorbed (requires a mod providing gases, e.g. Mekanism)."
    ),
    "gui.rs_create_compat.collection_cache.absorb.experience.tip": (
        "Absorb · Experience: when on, experience in range is absorbed (liquid XP / XP nuggets with Create Enchantment "
        "Industry, otherwise Create's experience nuggets)."
    ),
    "gui.rs_create_compat.collection_cache.match_tag": "Match tag: %s",
    "gui.rs_create_compat.collection_cache.match_nbt.tip": "Match NBT: only matches when data components (NBT) are identical.",
    "gui.rs_create_compat.collection_cache.match_tag.tip": "Match tag: matches by the tags the entry belongs to, ignoring the exact id.",
    "gui.rs_create_compat.collection_cache.amount_mb": "Amount (mB)",
    "gui.rs_create_compat.collection_cache.stats.fluid": "Fluid cache: %s / %s",
    "gui.rs_create_compat.collection_cache.cache.fluid_summary": "Fluid %s",
    "gui.rs_create_compat.marker.amount": "Amount: %s",
    "gui.rs_create_compat.marker.match_nbt": "Match NBT",
    "gui.rs_create_compat.marker.match_tag": "Match tag",
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
