# -*- coding: utf-8 -*-
# 为所有本模组方块生成“挖掉掉落自身”战利品表（data/<mod>/loot_table/blocks/<id>.json）。
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


NS = "rs_create_compat"
ROOT = r"d:\MODS\refined_storage_and_create_compat\src\main\resources\data"

# 与 RS_Create_Compat.java 里 BLOCKS.register 的方块一致
BLOCK_IDS = [
    "advanced_schematic_loader",
    "collection_cache",
    "quantity_keeper",
    "range_charger",
    "schematic_loader",
    "sequence_assembly_executor",
    "sequence_execution_chamber",
    "sequence_pattern_terminal",
]

for block_id in BLOCK_IDS:
    loot = {
        "type": "minecraft:block",
        "pools": [
            {
                "rolls": 1.0,
                "entries": [
                    {"type": "minecraft:item", "name": f"{NS}:{block_id}"}
                ],
                "conditions": [
                    {"condition": "minecraft:survives_explosion"}
                ],
            }
        ],
    }
    path = os.path.join(ROOT, NS, "loot_table", "blocks", block_id + ".json")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(loot, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("wrote", path)
