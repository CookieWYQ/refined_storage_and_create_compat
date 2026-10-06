# -*- coding: utf-8 -*-
"""生成「一键诊断快照」的**完整假状态**夹具（供 selfcheck 断言快照八项字段齐全）。

为什么用脚本而不是手写 JSON：用户规则要求「有规则和形式要求的文件用 python 脚本编写」，
且夹具必须是**确定性的**（同一份输入每次生成逐字节一致，便于幂等断言）。

用法：python tools/gen_diag_fixture.py
产物：tools/fixtures/diag_snapshot_full.json
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
OUT = os.path.join(ROOT, "tools", "fixtures", "diag_snapshot_full.json")


def fixture():
    chamber_pos = "-7,-58,5"
    return {
        "meta": {
            "schema": "rscc_diag_snapshot",
            "version": 1,
            "tag": "run",
            "generatedAt": "2026-10-01T12:00:00",
            "sessionStartedAt": "2026-10-01T11:50:00",
            "levels": ["minecraft:overworld"],
        },
        "chambers": [
            {
                "pos": chamber_pos,
                "name": "冲压仓",
                "recipeType": "create:pressing",
                "outputMode": "face",
                "clusterSize": 1,
                "inFlight": True,
                "ownedSteps": "create:sturdy_sheet#0",
                "energyUsage": 8,
                "faces": {"north": "INPUT", "south": "OUTPUT", "up": "NONE",
                          "down": "NONE", "east": "NONE", "west": "INTERMEDIATE"},
                "units": ["rs_create_compat:sequence_unit_pattern"],
                "storedItems": [{"item": "create:powdered_obsidian", "count": 1}],
                "storedFluids": [{"fluid": "minecraft:lava", "mB": 500}],
                "materials": [
                    {"res": "create:powdered_obsidian", "type": "item", "target": 1,
                     "net": 32, "chamber": 1, "machine": 1, "inflight": 0},
                    {"res": "minecraft:lava", "type": "fluid", "target": 500,
                     "net": 64000, "chamber": 500, "machine": 500, "inflight": 0},
                ],
                "categories": [
                    {"id": "input:create:powdered_obsidian", "kind": "ingredient", "step": -1,
                     "items": ["create:powdered_obsidian"], "fluids": [], "amount": 1, "estimated": 1},
                    {"id": "fluid:minecraft:lava", "kind": "inputFluid", "step": -1,
                     "items": [], "fluids": ["minecraft:lava"], "amount": 500, "estimated": 500},
                    {"id": "intermediate:0", "kind": "intermediate", "step": 0,
                     "items": ["create:unprocessed_obsidian_sheet"], "fluids": [], "amount": 0,
                     "estimated": 0},
                    {"id": "result:create:sturdy_sheet", "kind": "product", "step": -1,
                     "items": ["create:sturdy_sheet"], "fluids": [], "amount": 0, "estimated": 0},
                    {"id": "scrap:create:cogwheel", "kind": "scrap", "step": -1,
                     "items": ["create:cogwheel"], "fluids": [], "amount": 0, "estimated": 0},
                ],
            }
        ],
        "buses": [
            {
                "pos": "-7,-58,6",
                "kind": "exporter",
                "linkedLayout": True,
                "executorMode": True,
                "forceNormal": False,
                "autoCrafting": True,
                "linkedExecutor": chamber_pos,
                "selectedCategories": ["input:create:powdered_obsidian"],
                "selectionExplicit": True,
                "interference": {"disabled": False, "truncated": False, "reachableCount": 1},
                "visibleCategories": [
                    {"id": "input:create:powdered_obsidian", "group": "ingredient",
                     "iconItem": "create:powdered_obsidian", "iconFluid": "", "stepMachine": "",
                     "selected": True, "sharedCount": 1, "amount": 1, "estimated": 1},
                    {"id": "intermediate:0", "group": "intermediate",
                     "iconItem": "create:unprocessed_obsidian_sheet", "iconFluid": "",
                     "stepMachine": "create:mechanical_press", "selected": False,
                     "sharedCount": 1, "amount": 0, "estimated": 0},
                ],
            },
            {
                "pos": "-7,-58,4",
                "kind": "importer",
                "linkedLayout": True,
                "executorMode": True,
                "forceNormal": False,
                "autoCollect": True,
                "linkedExecutor": chamber_pos,
                "selectedCategories": ["result:create:sturdy_sheet"],
                "interference": {"disabled": False, "truncated": False, "reachableCount": 1},
                "visibleCategories": [
                    {"id": "result:create:sturdy_sheet", "group": "product",
                     "iconItem": "create:sturdy_sheet", "iconFluid": "", "stepMachine": "",
                     "selected": True, "sharedCount": 1, "amount": 0, "estimated": 0},
                ],
            },
        ],
        "counters": {
            chamber_pos + "|create:powdered_obsidian|pull": 1,
            chamber_pos + "|create:sturdy_sheet|push": 1,
            chamber_pos + "|create:sturdy_sheet|return": 1,
        },
        "recentEvents": [
            {"at": "2026-10-01T11:51:00", "pos": chamber_pos, "direction": "network->chamber",
             "resource": "create:powdered_obsidian", "amount": 1, "kind": "pull"},
            {"at": "2026-10-01T11:51:05", "pos": "-7,-58,6", "direction": "chamber->machine",
             "resource": "create:powdered_obsidian", "amount": 1, "kind": "push"},
            {"at": "2026-10-01T11:51:20", "pos": chamber_pos, "direction": "chamber->network",
             "resource": "create:sturdy_sheet", "amount": 1, "kind": "return"},
        ],
        "shortage": {
            "mode": "suspend",
            "suspended": [
                {"dimension": "minecraft:overworld", "task": "00000000-0000-0000-0000-000000000001",
                 "pos": chamber_pos, "product": "坚固板", "amount": 1, "reason": "MISSING_MATERIAL",
                 "suspended": True, "suspendState": "SUSPENDED", "stallTicks": 120,
                 "sequence": True, "returning": False, "missingMaterials": 1, "offlineSteps": "-"}
            ],
            "banners": {
                "sent": {"watchdog_missing_material|t1": 1, "shortage_chamber|-7,-58,5": 1},
                "dedupedSuppressed": {"watchdog_missing_material|t1": 6, "shortage_chamber|-7,-58,5": 4},
            },
        },
        "conservation": {
            "entries": 1,
            "ledger": [
                {"key": chamber_pos + "|create:powdered_obsidian",
                 "in": 1, "out": 0, "retained": 1, "diff": 0}
            ],
            "unbalanced": [],
        },
        "keepers": {
            "count": 2,
            "entries": [
                {"pos": "-6,-58,5", "kind": "quantity_keeper", "label": "定量保持器 @(-6,-58,5)",
                 "destroyOverflow": True, "destroyedTotal": 0,
                 "claimed": ["ItemResource{create:powdered_obsidian}"], "yielded": []},
                {"pos": "-5,-58,5", "kind": "advanced_quantity_keeper",
                 "label": "高级定量保持器 @(-5,-58,5)",
                 "slots": [{"slot": 0, "destroyOverflow": False, "destroyedTotal": 0, "target": 64}],
                 "claimed": ["ItemResource{create:powdered_obsidian}"],
                 "yielded": ["ItemResource{create:powdered_obsidian}"]},
            ],
        },
        "camouflage": {
            "camouflagedPositions": 12,
            "revealToggleCount": 1,
            "lastToggleAffectedPositions": 12,
            "lastToggleTick": 12345,
            "rebuildNote": "客户端网格重建的方块数 / 区段数在客户端侧，服务端不可观测",
        },
        "recipes": [
            {"chamber": chamber_pos, "recipeType": "create:pressing",
             "categories": [
                 {"id": "input:create:powdered_obsidian", "kind": "ingredient", "step": -1,
                  "items": ["create:powdered_obsidian"], "fluids": [], "amount": 1, "estimated": 1},
                 {"id": "intermediate:0", "kind": "intermediate", "step": 0,
                  "items": ["create:unprocessed_obsidian_sheet"], "fluids": [], "amount": 0,
                  "estimated": 0},
                 {"id": "result:create:sturdy_sheet", "kind": "product", "step": -1,
                  "items": ["create:sturdy_sheet"], "fluids": [], "amount": 0, "estimated": 0},
                 {"id": "scrap:create:cogwheel", "kind": "scrap", "step": -1,
                  "items": ["create:cogwheel"], "fluids": [], "amount": 0, "estimated": 0},
             ]},
        ],
    }


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with io.open(OUT, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(fixture(), handle, ensure_ascii=False, indent=2, sort_keys=True)
        handle.write("\n")
    print("[OK] 已写出夹具 %s" % os.path.relpath(OUT, ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
