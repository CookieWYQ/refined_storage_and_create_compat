# -*- coding: utf-8 -*-
"""把两个框架的方块模型收回到<b>与一格严丝合缝</b>的几何（去掉 17/16 外扩）。

背景（用户实机反馈）
--------------------
「原本每个方块六个面拼的时候是<b>严丝合缝</b>的，套上壳之后我发现渲染<b>会隔上一点点非常小的间距</b>，
不仔细看看不出来，一靠近就很明显。」

根因：`models/block/camouflage_frame.json` 与 `models/block/separation_frame.json` 里那 12 根
1 像素边条的坐标都写到了 **17** —— 也就是比一格多出整整 1 像素，而且**只加在 +X / +Y / +Z 一侧**
（`0..17` 而不是 `0..16`，另一边仍是 0）。于是每个框架一边贴合格边界（齐平）、另一边凸出 1 像素；
相邻两格之间因此出现错缝，同一片区域里「有框架的格」与「没框架的格」比例也不再一致。

做法（保几何意图、只收边界）
----------------------------
对每个元素的每个轴：若该轴的 `(from, to)` 恰好是「贴合格边界的那一层 1 像素」
（`(0,1) / (1,0) / (15,16) / (16,15)`），保持原样；否则把该轴上的 `16 → 15`、`17 → 16`
（把 `0..17` 线性收成 `0..16`，两条边条 `[0,1]` 与 `[15,16]` 仍然贴合格边界）。
因此：`uv / rotation / groups / textures / parent / render_type` **一个字节都不动**
（rotation 的 angle 恒为 0，origin 是编辑器元数据），只有 12 个元素的 `from` / `to` 被收起。

* 幂等：已经收好的模型再跑一次不会有任何变化（脚本会打印「无需改动」）；
* 收完每个元素在每个轴上的长度都 ≥ 1（脚本会断言，绝不产生退化元素）；
* 用法：`python tools/flush_frame_models.py`
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
TARGETS = [
    os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                 "models", "block", "camouflage_frame.json"),
    os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                 "models", "block", "separation_frame.json"),
]

#: 12 根 1 像素边条的**目标几何**（下标 = 模型里的元素顺序，与两份模型完全一致）：
#: 每一根都贴合格边界（[0,1] 与 [15,16] 两圈 + 格边界 0 / 16），因此与一格严丝合缝。
#: 写成显式目标而不是「把 16 减 1」的换算，是为了**幂等**：跑一次与跑十次结果完全相同
#: （换算式规则在第二轮会把已经收好的 [0,16] 再收成 [0,15]，那是错的）。
TARGET_BARS = [
    # 组 1（z = 0..1 这一圈的四个边条）
    ((0, 15, 0), (16, 16, 1)),
    ((15, 0, 0), (16, 15, 1)),
    ((1, 0, 0), (15, 1, 1)),
    ((0, 0, 0), (1, 15, 1)),
    # 组 2（z = 15..16 这一圈）
    ((0, 15, 15), (16, 16, 16)),
    ((15, 0, 15), (16, 15, 16)),
    ((1, 0, 15), (15, 1, 16)),
    ((0, 0, 15), (1, 15, 16)),
    # 组 3（x = 0..1 这一圈）
    ((0, 15, 1), (1, 16, 15)),
    ((15, 15, 1), (16, 16, 15)),
    ((15, 0, 1), (16, 1, 15)),
    ((0, 0, 1), (1, 1, 15)),
]


def border_ring():
    """一格 16×16 的那个面「贴合格边界的一圈 1 像素」（以 1/16 格为单位）。"""
    return {(u, v) for u in range(16) for v in range(16) if u in (0, 15) or v in (0, 15)}


def covered_cells(bars, axis, plane):
    """在与 axis 轴垂直、位于 plane（0 或 16）的那个面上，被边条盖住的 1/16 格集合。"""
    cells = set()
    for frm, to in bars:
        if frm[axis] != plane and to[axis] != plane:
            continue
        u_axis, v_axis = [i for i in range(3) if i != axis]
        for u in range(frm[u_axis], to[u_axis]):
            for v in range(frm[v_axis], to[v_axis]):
                cells.add((u, v))
    return cells


def verify_flush(bars):
    """「严丝合缝」的几何判据：六个外表面上恰好只盖住那一圈 1 像素，中间是通透的。

    这一条正是用户报的那个症状的反面：外壳必须与它覆盖的方块<strong>同尺寸</strong>
    （模型坐标 0..16），六个面拼在一起时才严丝合缝，不会多出 / 少掉 1 像素。
    """
    ring = border_ring()
    for axis in range(3):
        for plane in (0, 16):
            got = covered_cells(bars, axis, plane)
            if got != ring:
                print("[FAIL] 轴 %d 的 %d 面不是「恰好一圈 1 像素」：多 %s / 少 %s"
                      % (axis, plane, sorted(got - ring)[:4], sorted(ring - got)[:4]))
                return False
    return True


def process(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        model = json.load(handle)
    elements = model.get("elements", [])
    if len(elements) != len(TARGET_BARS):
        print("[FAIL] %s 的元素数 %d != 预期 %d（模型结构变了，请先核对目标几何）"
              % (os.path.basename(path), len(elements), len(TARGET_BARS)))
        return False
    changed = 0
    for element, (frm, to) in zip(elements, TARGET_BARS):
        if list(element["from"]) != list(frm) or list(element["to"]) != list(to):
            element["from"], element["to"] = list(frm), list(to)
            changed += 1
        for axis in range(3):
            if to[axis] - frm[axis] < 1:
                print("[FAIL] %s 出现退化元素（轴 %d 长度 %d）：from=%s to=%s"
                      % (os.path.basename(path), axis, to[axis] - frm[axis], list(frm), list(to)))
                return False
    if changed == 0:
        print("[OK]   %-28s 已经收在 0..16（无需改动）" % os.path.basename(path))
        return True
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(model, ensure_ascii=False, indent=2) + "\n")
    # json.load 回读校验（形式要求：写进仓库的 JSON 必须能被解析器读回来）
    with io.open(path, "r", encoding="utf-8") as handle:
        loaded = json.load(handle)
    assert len(loaded["elements"]) == len(model["elements"])
    print("[FIX]  %-28s 收起 %d 个元素的坐标（%d 根边条全部落在 0..16）"
          % (os.path.basename(path), changed, len(loaded["elements"])))
    return True


def main():
    ok = True
    for path in TARGETS:
        if not os.path.exists(path):
            print("[FAIL] 找不到 %s" % path)
            ok = False
            continue
        ok = process(path) and ok
    if not verify_flush(TARGET_BARS):
        print("[FAIL] 目标几何自身不是「贴合格边界的一圈 1 像素」")
        ok = False
    if ok:
        print("两个框架的模型都与一格严丝合缝（0..16；六个面各是一圈 1 像素），uv / rotation / groups 未被改动")
        return 0
    return 1


if __name__ == "__main__":
    sys.exit(main())
