# -*- coding: utf-8 -*-
"""把「伪装框架」自己的方块模型改写成<b>框架族镂空框架</b>（用户第 5 条）。

背景（用户原话）
----------------
「把伪装框架中间那块『未加工黑曜石板』改成『框架本体』的贴图 / 外观，然后让它顺理成章地也具备
断联功能。该不该有断点？」

原模型是一个不透明的整格立方体（四面 + 顶 + 底三张灰贴图），它同时是三处的外观来源：
  1) 物品图标（`models/item/camouflage_frame.json` → 本模型，继承 `block/block` 的 display 变换）；
  2) 旧存档里「真的放下过」的伪装框架方块；
  3) **没选外观的「空壳」**（`client/model/CamouflageFrameModel#hasNoMaterial` → `originalModel`）。
第 3 条是用户抱怨的重点：那块实心板把被裹的线缆整个盖住，断开某一道缝之后外面什么都看不见。
本脚本把它换成与分隔框架同族的 1 像素<b>镂空框架</b>：线缆（含它的连接臂与断开状态）从开口里可见。

做法
----
* 几何与分隔框架同族（12 根 1 像素边条，三组：z=0..1 / z=15..16 / x=0..1），
  坐标沿用用户自绘的 17/16 外扩（相邻两格的边条因此不会共面闪烁）；
* 三张贴图仍是本方块自己的 `camouflage_frame_side / _top / _bottom`（**不新增任何贴图文件**），
  水平面用 side、朝上的面用 top、朝下的面用 bottom —— 与前一份立方体模型同一套对应关系；
* `render_type = cutout`：这三张贴图是完全不透明的（旧模型如此），不需要半透明排序；
* 保留 `parent: block/block`（物品栏 / 手持 / 掉落的 display 变换来源）。

用法: python tools/rewrite_camouflage_frame_model.py
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
TARGET = os.path.join(ROOT, "src", "main", "resources", "assets",
                      "rs_create_compat", "models", "block", "camouflage_frame.json")

NS = "rs_create_compat"

#: 12 根 1 像素边条（from, to, rotation），与 `separation_frame.json` 同一套几何。
#: 三组分别是：z=0..1 的一圈、z=15..16 的一圈、x=0..1 的一圈。
#: 坐标一律落在 0..16（贴合格边界，绝不外扩；外扩会让相邻两格之间出现错缝）。
BARS = [
    # --- 组 1：z = 0..1 的一圈 ---
    ((0, 15, 0), (16, 16, 1), None),
    ((15, 0, 0), (16, 15, 1), None),
    ((1, 0, 0), (15, 1, 1), None),
    ((0, 0, 0), (1, 15, 1), None),
    # --- 组 2：z = 15..16 的一圈 ---
    ((0, 15, 15), (16, 16, 16), (0, "y", (0, 0, 15))),
    ((15, 0, 15), (16, 15, 16), (0, "y", (0, 0, 15))),
    ((1, 0, 15), (15, 1, 16), (0, "y", (0, 0, 15))),
    ((0, 0, 15), (1, 15, 16), (0, "y", (0, 0, 15))),
    # --- 组 3：x = 0..1 的一圈 ---
    ((0, 15, 1), (1, 16, 15), (0, "y", (0, 16, 1))),
    ((15, 15, 1), (16, 16, 15), (0, "y", (16, 16, 1))),
    ((15, 0, 1), (16, 1, 15), (0, "y", (16, 0, 1))),
    ((0, 0, 1), (1, 1, 15), (0, "y", (0, 0, 1))),
]

#: 三组的分组成员下标（与分隔框架的 groups 一致：side1 / side2 / side3）。
GROUPS = [("side1", [0, 1, 2, 3], (0, 0, 0)),
          ("side2", [4, 5, 6, 7], (0, 0, 0)),
          ("side3", [8, 9, 10, 11], (0, 0, 1))]


def bar_faces(frm, to):
    """一根轴对齐 1 像素边条的六个面（uv 取该面的像素尺寸，长边压到 16 以内，贴图按面朝向选）。"""
    x0, y0, z0 = frm
    x1, y1, z1 = to
    dx, dy, dz = x1 - x0, y1 - y0, z1 - z0
    # 边条最长的一条边是 17（用户自绘的外扩 1 像素），uv 上限是 16 → 压到 16（与原模型同一取舍）
    flat = lambda a, b: [0, 0, min(a, 16), min(b, 16)]  # noqa: E731 - 脚本内部的小工具
    return {
        "north": {"uv": flat(dx, dy), "texture": "#side"},
        "south": {"uv": flat(dx, dy), "texture": "#side"},
        "east": {"uv": flat(dz, dy), "texture": "#side"},
        "west": {"uv": flat(dz, dy), "texture": "#side"},
        "up": {"uv": flat(dx, dz), "texture": "#top"},
        "down": {"uv": flat(dx, dz), "texture": "#bottom"},
    }


def build_model():
    elements = []
    for frm, to, rotation in BARS:
        element = {"from": list(frm), "to": list(to), "faces": bar_faces(frm, to)}
        if rotation is not None:
            angle, axis, origin = rotation
            element["rotation"] = {"angle": angle, "axis": axis, "origin": list(origin)}
        elements.append(element)
    return {
        "parent": "block/block",
        "format_version": "1.9.0",
        "credit": "Made with Blockbench",
        # 三张贴图都是完全不透明的，用 cutout（不是 translucent）：没有半透明排序的代价
        "render_type": "cutout",
        "textures": {
            "side": "%s:block/camouflage_frame_side" % NS,
            "top": "%s:block/camouflage_frame_top" % NS,
            "bottom": "%s:block/camouflage_frame_bottom" % NS,
            "particle": "%s:block/camouflage_frame_side" % NS,
        },
        "elements": elements,
        "groups": [{"name": name, "origin": list(origin), "scope": 0, "color": 0, "children": children}
                   for name, children, origin in GROUPS],
    }


def main():
    model = build_model()
    text = json.dumps(model, ensure_ascii=False, indent=2) + "\n"
    os.makedirs(os.path.dirname(TARGET), exist_ok=True)
    with io.open(TARGET, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    # json.load 回读校验（形式要求：写进仓库的 JSON 必须能被解析器读回来）
    with io.open(TARGET, "r", encoding="utf-8") as handle:
        loaded = json.load(handle)
    print("已写入 %s" % os.path.relpath(TARGET, ROOT))
    print("  parent      : %s" % loaded["parent"])
    print("  render_type : %s" % loaded["render_type"])
    print("  elements    : %d（%d 根边条）" % (len(loaded["elements"]), len(BARS)))
    print("  textures    : %s" % ", ".join(sorted(loaded["textures"].values())))
    print("  faces/元素  : %d" % len(loaded["elements"][0]["faces"]))
    if len(loaded["elements"]) != len(BARS) or len(loaded["textures"]) != 4:
        print("[FAIL] 结构不符合预期")
        return 1
    print("[OK] 伪装框架外观已改为框架族镂空框架")
    return 0


if __name__ == "__main__":
    sys.exit(main())
