# -*- coding: utf-8 -*-
"""高级物品定量保持器：生成方块资源 JSON（blockstate / 方块模型 / 物品模型 / 掉落表）
并规范化追加中英语言键（避免手写 JSON 出错）。

贴图暂时复用现有定量保持器的 block/quantity_keeper_active|inactive，
等绘图 AI 交付新贴图后再替换。
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat"
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "rs_create_compat")
NS = "rs_create_compat"
BLOCK = "advanced_quantity_keeper"


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("wrote", path)


# ===== 方块状态 =====
write_json(os.path.join(ASSETS, "blockstates", BLOCK + ".json"), {
    "variants": {
        "active=false": {"model": f"{NS}:block/{BLOCK}"},
        "active=true": {"model": f"{NS}:block/{BLOCK}_active"},
    }
})

# ===== 方块模型（暂时复用定量保持器贴图）=====
write_json(os.path.join(ASSETS, "models", "block", BLOCK + ".json"), {
    "parent": "minecraft:block/cube_all",
    "textures": {"all": f"{NS}:block/quantity_keeper_inactive"},
})
write_json(os.path.join(ASSETS, "models", "block", BLOCK + "_active.json"), {
    "parent": "minecraft:block/cube_all",
    "textures": {"all": f"{NS}:block/quantity_keeper_active"},
})

# ===== 物品模型 =====
write_json(os.path.join(ASSETS, "models", "item", BLOCK + ".json"), {
    "parent": f"{NS}:block/{BLOCK}_active",
})

# ===== 掉落表（挖掉掉落自身；方块实体 NBT 由 Block#getDrops 附回）=====
write_json(os.path.join(DATA, "loot_table", "blocks", BLOCK + ".json"), {
    "type": "minecraft:block",
    "pools": [{
        "rolls": 1.0,
        "entries": [{"type": "minecraft:item", "name": f"{NS}:{BLOCK}"}],
        "conditions": [{"condition": "minecraft:survives_explosion"}],
    }],
})

# ===== 语言键 =====
zh = {
    "block.rs_create_compat.advanced_quantity_keeper": "高级物品定量保持器",
    "block.rs_create_compat.advanced_quantity_keeper.help":
        "把 4 个定量保持器融合成一个方块：4 个配置槽各自标记物品/流体/气体并保持目标数量。"
        "只接受已标记的类型（其它资源原样退回，绝不销毁）；允许空槽，顺序无关；每槽可单独开关自动合成。",
    "container.rs_create_compat.advanced_quantity_keeper": "高级物品定量保持器",
    "gui.rs_create_compat.advanced_quantity_keeper.marker_tooltip":
        "放入物品，或从 JEI 拖入流体/气体作为本行标记（不消耗）。空槽同样有效，只有已标记的类型允许输入。",
    "gui.rs_create_compat.advanced_quantity_keeper.target_tooltip": "本行要保持的目标数量（物品按个，流体/气体按 mB）",
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_label": "自动合成",
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_disabled_tooltip": "需在插件槽放入自动合成升级后才能开关",
    "gui.rs_create_compat.advanced_quantity_keeper.state_on": "当前：开（不足时自动合成补足）",
    "gui.rs_create_compat.advanced_quantity_keeper.state_off": "当前：关（不自动合成）",
    "gui.rs_create_compat.advanced_quantity_keeper.blocked": "堵塞",
    "gui.rs_create_compat.advanced_quantity_keeper.blocked.tooltip":
        "本行存在与标记不匹配的资源，已停止输出（不再写入网络）。资源不会被销毁，仍可被玩家或管道取出。",
    "gui.rs_create_compat.advanced_quantity_keeper.empty": "空槽",
    "gui.rs_create_compat.advanced_quantity_keeper.unit_item": "个",
    "gui.rs_create_compat.advanced_quantity_keeper.unit_mb": "mB",
}

en = {
    "block.rs_create_compat.advanced_quantity_keeper": "Advanced Quantity Keeper",
    "block.rs_create_compat.advanced_quantity_keeper.help":
        "Fuses 4 quantity keepers into one block: 4 independent slots, each marking an item/fluid/gas and "
        "keeping its target amount. Only marked types are accepted (anything else is returned untouched, never "
        "voided); empty slots are allowed and order does not matter; autocrafting toggles per slot.",
    "container.rs_create_compat.advanced_quantity_keeper": "Advanced Quantity Keeper",
    "gui.rs_create_compat.advanced_quantity_keeper.marker_tooltip":
        "Place an item, or drag a fluid/gas from JEI, as this row's marker (not consumed). Empty slots are fine; "
        "only marked types are accepted as input.",
    "gui.rs_create_compat.advanced_quantity_keeper.target_tooltip":
        "Target amount to keep for this row (items as count, fluids/gases as mB)",
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_label": "Autocraft",
    "gui.rs_create_compat.advanced_quantity_keeper.autocraft_disabled_tooltip":
        "Install an autocrafting upgrade in an upgrade slot to toggle this",
    "gui.rs_create_compat.advanced_quantity_keeper.state_on": "State: ON (autocrafts to refill)",
    "gui.rs_create_compat.advanced_quantity_keeper.state_off": "State: OFF (no autocrafting)",
    "gui.rs_create_compat.advanced_quantity_keeper.blocked": "Blocked",
    "gui.rs_create_compat.advanced_quantity_keeper.blocked.tooltip":
        "This row holds resources that no longer match its marker, so output is halted. Nothing is voided; the "
        "resources can still be extracted by players or pipes.",
    "gui.rs_create_compat.advanced_quantity_keeper.empty": "Empty",
    "gui.rs_create_compat.advanced_quantity_keeper.unit_item": "items",
    "gui.rs_create_compat.advanced_quantity_keeper.unit_mb": "mB",
}


def patch_lang(name, updates):
    path = os.path.join(ASSETS, "lang", name)
    with io.open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    data.update(updates)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("patched", path)


patch_lang("zh_cn.json", zh)
patch_lang("en_us.json", en)
