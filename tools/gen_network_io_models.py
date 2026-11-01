# -*- coding: utf-8 -*-
"""写出网络资源接收器 / 请求器的模型、blockstate、物品模型（幂等，可复跑）。

**只出资源，不注册方块**（Java 注册是下一步；本脚本不碰任何 .java）。

口径（用户拍板）：
  * **网络资源接收器 = 有朝向**，**照 `sequence_execution_chamber` 逐字一致**：
    - ``models/block/network_resource_receiver.json`` / ``_active.json``：
      父 = ``minecraft:block/cube``，六面**显式**指定 —— ``north/east/south/west`` = `_side`，
      ``up`` = `_front`（前端面烘焙在模型 UP），``down`` = `_back`；
    - ``blockstates/network_resource_receiver.json``：**6 向 `facing`** × `active` 两态，
      旋转取值与执行舱逐字相同 —— north ``x:90``、east ``x:90,y:90``、south ``x:90,y:180``、
      west ``x:90,y:270``、up（空）、down ``x:180``。
  * **网络资源请求器 = 没有朝向**：blockstate **只有 `active` 两态、绝不含 `facing`**；
    模型 6 面各自独立、**不含任何 `x`/`y` 旋转**。
  * 物品模型照既有机器（执行舱）的写法：父指向 ``..._active``。

用法：``python tools/gen_network_io_models.py``
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
MODELS_ITEM = os.path.join(ASSETS, "models", "item")
BLOCKSTATES = os.path.join(ASSETS, "blockstates")
NS = "rs_create_compat:block/"

RECEIVER = "network_resource_receiver"
REQUESTER = "network_resource_requester"

# 与 tools/gen_face_split_models.py 的 CHAMBER_FACES 逐字相同（模型面 -> 贴图后缀）
RECEIVER_FACES = {
    "north": "side", "east": "side", "south": "side", "west": "side",
    "up": "front", "down": "back",
}
# 与 tools/gen_face_split_models.py 的 CHAMBER_ROTATIONS 逐字相同（facing -> 模型旋转）
RECEIVER_ROTATIONS = {
    "north": {"x": 90},
    "east": {"x": 90, "y": 90},
    "south": {"x": 90, "y": 180},
    "west": {"x": 90, "y": 270},
    "up": {},                      # 前端面本来就是模型 UP：不旋转
    "down": {"x": 180},            # 模型 UP 转到 DOWN：翻 180°
}

# 请求器：无朝向 —— 6 面各自独立，且不带任何旋转
REQUESTER_FACES = {
    "north": "front", "south": "back", "east": "right", "west": "left",
    "up": "top", "down": "bottom",
}
REQUESTER_ORDER = ("north", "east", "south", "west", "up", "down")


def dump_json(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print("[OK] %s" % os.path.relpath(path, ROOT).replace(os.sep, "/"))


def write_cube_model(block_id, faces, active, order=None):
    """写 cube 模型：六面**显式**指定，粒子取侧面（照执行舱模型的组织方式）。"""
    tail = "_active" if active else "_inactive"
    keys = order or tuple(faces)
    textures = {"particle": NS + block_id + "_" + faces[keys[0]] + tail}
    for face in keys:
        textures[face] = NS + block_id + "_" + faces[face] + tail
    dump_json(os.path.join(MODELS_BLOCK, "%s%s.json" % (block_id, "_active" if active else "")),
              {"parent": "minecraft:block/cube", "textures": textures})


def write_item_model(block_id):
    """物品模型：父指向该方块的 active 模型（照 sequence_execution_chamber 的写法）。"""
    dump_json(os.path.join(MODELS_ITEM, "%s.json" % block_id),
              {"parent": "rs_create_compat:block/%s_active" % block_id})


def main():
    # 1. 接收器：模型（前端面 = UP）+ blockstate（6 向 facing × active，x:90 系列）
    for active in (False, True):
        write_cube_model(RECEIVER, RECEIVER_FACES, active,
                         order=("north", "east", "south", "west", "up", "down"))
    variants = {}
    for facing in ("north", "east", "south", "west", "up", "down"):
        for active in (False, True):
            entry = {"model": NS + RECEIVER + ("_active" if active else "")}
            entry.update(RECEIVER_ROTATIONS[facing])
            variants["facing=%s,active=%s" % (facing, str(active).lower())] = entry
    dump_json(os.path.join(BLOCKSTATES, RECEIVER + ".json"), {"variants": variants})

    # 2. 请求器：模型（无旋转，6 面独立）+ blockstate（**只有 active**，不含 facing）
    for active in (False, True):
        write_cube_model(REQUESTER, REQUESTER_FACES, active, order=REQUESTER_ORDER)
    variants = {}
    for active in (False, True):
        variants["active=%s" % str(active).lower()] = {
            "model": NS + REQUESTER + ("_active" if active else "")}
    dump_json(os.path.join(BLOCKSTATES, REQUESTER + ".json"), {"variants": variants})

    # 3. 物品模型
    for block_id in (RECEIVER, REQUESTER):
        write_item_model(block_id)


if __name__ == "__main__":
    main()
