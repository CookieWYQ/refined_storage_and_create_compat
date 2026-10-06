# -*- coding: utf-8 -*-
# 生成「归流缓存仓」(collection_cache) 的方块状态/模型/物品模型/战利品表，并补 lang 键。
# 素材贴图（用户提供，本脚本不生成 PNG）：
#   textures/block/collection_cache_inactive.png
#   textures/block/collection_cache_active.png
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


NS = "rs_create_compat"
ROOT = r"d:\MODS\refined_storage_and_create_compat\src\main\resources"
ASSETS = os.path.join(ROOT, "assets", NS)
DATA = os.path.join(ROOT, "data", NS)
LANG = os.path.join(ASSETS, "lang")

BLOCK_ID = "collection_cache"


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("wrote", path)


# 方块状态：active=false/true 两个变体（沿用本模组双态机制）
write_json(os.path.join(ASSETS, "blockstates", BLOCK_ID + ".json"), {
    "variants": {
        "active=false": {"model": f"{NS}:block/{BLOCK_ID}"},
        "active=true": {"model": f"{NS}:block/{BLOCK_ID}_active"},
    }
})

# 方块模型：cube_all，未接入(灰) / 已接入(亮)
write_json(os.path.join(ASSETS, "models", "block", BLOCK_ID + ".json"), {
    "parent": "minecraft:block/cube_all",
    "textures": {"all": f"{NS}:block/{BLOCK_ID}_inactive"},
})
write_json(os.path.join(ASSETS, "models", "block", BLOCK_ID + "_active.json"), {
    "parent": "minecraft:block/cube_all",
    "textures": {"all": f"{NS}:block/{BLOCK_ID}_active"},
})

# 物品模型：指向非激活方块模型
write_json(os.path.join(ASSETS, "models", "item", BLOCK_ID + ".json"), {
    "parent": f"{NS}:block/{BLOCK_ID}",
})

# 战利品表：掉落自身
write_json(os.path.join(DATA, "loot_table", "blocks", BLOCK_ID + ".json"), {
    "type": "minecraft:block",
    "pools": [
        {
            "rolls": 1.0,
            "entries": [{"type": "minecraft:item", "name": f"{NS}:{BLOCK_ID}"}],
            "conditions": [{"condition": "minecraft:survives_explosion"}],
        }
    ],
})

zh_updates = {
    "block.rs_create_compat.collection_cache": "归流缓存仓",
    "block.rs_create_compat.collection_cache.help": "接入 RS 网络后吸取周围散落的掉落物：匹配区用 ghost 标记（数量 / 匹配 NBT / 匹配同类）指定可收集物，凑够标记数量才收集；物品优先流入网络，装不下的暂存缓存区。速度升级提高每 tick 入网速率，堆叠升级提高缓存每格上限。",
    "container.rs_create_compat.collection_cache": "归流缓存仓",
    "gui.rs_create_compat.collection_cache.region_marker": "匹配区",
    "gui.rs_create_compat.collection_cache.region_cache": "缓存区",
    "gui.rs_create_compat.collection_cache.hint": "右键匹配槽设置",
    "gui.rs_create_compat.collection_cache.stats": "收集 %s / 入网 %s",
    "gui.rs_create_compat.collection_cache.upgrades": "速度×%s 堆叠×%s 上限%s/格",
    "gui.rs_create_compat.collection_cache.config_title": "匹配设置",
    "gui.rs_create_compat.collection_cache.amount": "数量",
    "gui.rs_create_compat.collection_cache.match_nbt": "匹配 NBT：%s",
    "gui.rs_create_compat.collection_cache.match_fuzzy": "匹配同类：%s",
    "gui.rs_create_compat.collection_cache.on": "开",
    "gui.rs_create_compat.collection_cache.off": "关",
    "gui.rs_create_compat.collection_cache.confirm": "确认",
    "gui.rs_create_compat.collection_cache.cancel": "取消",
    "gui.rs_create_compat.collection_cache.duplicate": "该物品已在匹配区标记过（同一物品只能标记一次）",
}

en_updates = {
    "block.rs_create_compat.collection_cache": "Collection Cache",
    "block.rs_create_compat.collection_cache.help": "Once connected to an RS network, absorbs dropped items around it: the marker area holds ghost markers (amount / match NBT / fuzzy match). Items are only collected once the marked amount is reached; they flow into the network first and overflow stays in the cache area. Speed upgrades raise the insert rate, stack upgrades raise the per-slot capacity.",
    "container.rs_create_compat.collection_cache": "Collection Cache",
    "gui.rs_create_compat.collection_cache.region_marker": "Markers",
    "gui.rs_create_compat.collection_cache.region_cache": "Cache",
    "gui.rs_create_compat.collection_cache.hint": "Right-click a marker slot",
    "gui.rs_create_compat.collection_cache.stats": "Collected %s / Inserted %s",
    "gui.rs_create_compat.collection_cache.upgrades": "Speed x%s Stack x%s Cap %s/slot",
    "gui.rs_create_compat.collection_cache.config_title": "Match Settings",
    "gui.rs_create_compat.collection_cache.amount": "Amount",
    "gui.rs_create_compat.collection_cache.match_nbt": "Match NBT: %s",
    "gui.rs_create_compat.collection_cache.match_fuzzy": "Match Same Item: %s",
    "gui.rs_create_compat.collection_cache.on": "ON",
    "gui.rs_create_compat.collection_cache.off": "OFF",
    "gui.rs_create_compat.collection_cache.confirm": "Confirm",
    "gui.rs_create_compat.collection_cache.cancel": "Cancel",
    "gui.rs_create_compat.collection_cache.duplicate": "This item is already marked (each item can only be marked once)",
}


def patch_lang(path, updates):
    with io.open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    data.update(updates)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("patched", path)


patch_lang(os.path.join(LANG, "zh_cn.json"), zh_updates)
patch_lang(os.path.join(LANG, "en_us.json"), en_updates)
