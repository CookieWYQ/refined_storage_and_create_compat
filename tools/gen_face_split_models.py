# -*- coding: utf-8 -*-
"""写出「拆面」后的方块模型 + 执行舱 blockstate，并清理被替换掉的旧贴图（幂等）。

内容：
  1. 序列执行舱（照 RS 自动合成舱的结构）：
     * 模型**以「前端面 = UP 面」烘焙**（与 RS / 原版观察者一致），四个侧面共用一张带指向性的贴图；
     * blockstate 用 x:90 系列旋转把前端面指向 ``facing``（正是 RS 自动合成舱的取值）。
  2. schematic_loader / advanced_schematic_loader / collection_cache / sequence_assembly_executor：
     由 ``cube_all``（六面一张图）改为 ``cube``，四个竖直侧面共用 side、顶面 top、底面 bottom。
  3. 删除以上方块不再被引用的旧贴图。

用法：python tools/gen_face_split_models.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat")
MODELS_BLOCK = os.path.join(ASSETS, "models", "block")
BLOCKSTATES = os.path.join(ASSETS, "blockstates")
TEXTURES = os.path.join(ASSETS, "textures", "block")
NS = "rs_create_compat:block/"

# ---- 1. 序列执行舱 ----
CHAMBER = "sequence_execution_chamber"
# 面 -> 贴图后缀（模型以「朝上」为前端面：front 贴图放在 up，back 贴图放在 down）
CHAMBER_FACES = {
    "north": "side", "east": "side", "south": "side", "west": "side",
    "up": "front", "down": "back",
}
# facing -> 模型旋转（照 RS 自动合成舱：direction=north/east/south/west 的取值）
CHAMBER_ROTATIONS = {
    "north": {"x": 90},
    "east": {"x": 90, "y": 90},
    "south": {"x": 90, "y": 180},
    "west": {"x": 90, "y": 270},
}

# ---- 2. 由 cube_all 拆成 side/top/bottom 的方块 ----
SPLIT_BLOCKS = ["schematic_loader", "advanced_schematic_loader", "collection_cache",
                "range_charger", "sequence_assembly_executor"]
SPLIT_FACES = {
    "north": "side", "east": "side", "south": "side", "west": "side",
    "up": "top", "down": "bottom",
}

STALE_TEXTURES = [
    # 执行舱：旧的「六面一张图」两态 + 一张历史遗留的未引用图
    "sequence_execution_chamber.png",
    "sequence_execution_chamber_inactive.png",
    "sequence_execution_chamber_active.png",
    # range_charger：还有一张历史遗留的未引用图
    "range_charger.png",
    # item_collector：方块早已改名成 collection_cache，这两张图全工程无人引用（且内容完全重复）
    "item_collector_active.png",
    "item_collector_inactive.png",
] + [f"{b}_{state}.png" for b in SPLIT_BLOCKS for state in ("inactive", "active")]


def dump_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("[OK] %s" % os.path.relpath(path, ROOT).replace(os.sep, "/"))


def write_cube_model(block_id, faces, active):
    """写 cube 模型：四个竖直侧面共用一张 side 贴图，顶/底各自独立。"""
    tail = "_active" if active else "_inactive"
    textures = {"particle": NS + block_id + "_" + faces["north"] + tail}
    for face, suffix in faces.items():
        textures[face] = NS + block_id + "_" + suffix + tail
    dump_json(os.path.join(MODELS_BLOCK, "%s%s.json" % (block_id, "_active" if active else "")),
              {"parent": "minecraft:block/cube", "textures": textures})


def main():
    # 1. 执行舱模型（前端面 = UP）
    for active in (False, True):
        write_cube_model(CHAMBER, CHAMBER_FACES, active)

    # 1b. 执行舱 blockstate：x:90 系列旋转（照 RS 自动合成舱）
    variants = {}
    for facing, rotation in CHAMBER_ROTATIONS.items():
        for active in (False, True):
            entry = {"model": NS + CHAMBER + ("_active" if active else "")}
            entry.update(rotation)
            variants["facing=%s,active=%s" % (facing, str(active).lower())] = entry
    dump_json(os.path.join(BLOCKSTATES, CHAMBER + ".json"), {"variants": variants})

    # 2. 拆面方块模型
    for block_id in SPLIT_BLOCKS:
        for active in (False, True):
            write_cube_model(block_id, SPLIT_FACES, active)

    # 3. 清理旧贴图
    for name in STALE_TEXTURES:
        path = os.path.join(TEXTURES, name)
        if os.path.exists(path):
            os.remove(path)
            print("[OK] 清理旧贴图 block/%s" % name)


if __name__ == "__main__":
    main()
