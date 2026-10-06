"""生成“未接入(灰)/已接入(亮)”双贴图的方块模型与 blockstate JSON。

规则：
- models/block/<id>.json           -> cube_all，贴图 <id>_inactive（未接入，灰）
- models/block/<id>_active.json    -> cube_all，贴图 <id>_active （已接入，亮）
- blockstates/<id>.json            -> 原 variants 的每个 key 展开成 active=false / active=true 两份

脚本可重复执行：展开前会先剥离已存在的 active=* 片段，避免二次运行产生重复属性。
"""
from __future__ import annotations

import json
from collections import OrderedDict
from pathlib import Path
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


# 8 个方块 id
BLOCK_IDS = [
    "range_charger",
    "quantity_keeper",
    "schematic_loader",
    "advanced_schematic_loader",
    "sequence_pattern_terminal",
    "sequence_return_bus",
    "sequence_assembly_executor",
    "sequence_execution_chamber",
]

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "src" / "main" / "resources" / "assets" / "rs_create_compat"
MODELS_DIR = ASSETS / "models" / "block"
BLOCKSTATES_DIR = ASSETS / "blockstates"


def dump_json(path: Path, data: object) -> None:
    """统一写出：UTF-8、2 空格缩进、结尾换行。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
        fh.write("\n")


def write_model(model_name: str, texture: str) -> None:
    """写入 cube_all 模型：模型文件名 -> 指定的贴图名。"""
    dump_json(
        MODELS_DIR / f"{model_name}.json",
        {
            "parent": "minecraft:block/cube_all",
            "textures": {"all": f"rs_create_compat:block/{texture}"},
        },
    )


def strip_active(key: str) -> str:
    """去掉 key 中已有的 active=* 片段，得到基础属性串（可含 facing=north 等）。"""
    parts = [p.strip() for p in key.split(",")]
    return ",".join(p for p in parts if p and not p.startswith("active="))


def expand_variants(block_id: str, variants: dict) -> "OrderedDict[str, dict]":
    expanded: "OrderedDict[str, dict]" = OrderedDict()
    for key, value in variants.items():
        base = strip_active(key)
        for active in (False, True):
            new_key = f"{base},active={str(active).lower()}" if base else f"active={str(active).lower()}"
            model_suffix = "_active" if active else ""
            entry = dict(value)  # y / uvlock / x 等字段原样复制
            entry["model"] = f"rs_create_compat:block/{block_id}{model_suffix}"
            expanded[new_key] = entry
    return expanded


def main() -> None:
    for block_id in BLOCK_IDS:
        # 1) 模型：未接入(灰) 复用基础模型文件，已接入(亮) 单独一个 *_active 模型
        write_model(block_id, f"{block_id}_inactive")
        write_model(f"{block_id}_active", f"{block_id}_active")

        # 2) blockstate：读取原有 variants 后展开
        state_path = BLOCKSTATES_DIR / f"{block_id}.json"
        with state_path.open("r", encoding="utf-8") as fh:
            state_data = json.load(fh)
        state_data["variants"] = expand_variants(block_id, state_data["variants"])
        dump_json(state_path, state_data)

        print(f"[ok] {block_id}: model(inactive/active) + blockstate(active=false/true)")

    print(f"done: {len(BLOCK_IDS)} blocks")


if __name__ == "__main__":
    main()
