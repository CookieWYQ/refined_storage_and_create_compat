#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「高级归流缓存仓」的方块贴图与「区域选定器」的物品图标（**基于基础版贴图改色 / 加深**）。

为什么用脚本而不是新画贴图
--------------------------
工程约定：AI 不能凭空画像素风贴图。高级版必须<b>看得出是同一族机器</b>，因此做法是
「取基础版 `collection_cache_*` 的 6 张面贴图 → 统一压暗 + 往橙铜色调偏移」，
物品图标同样由基础版的侧面贴图派生（压暗后叠一个橙色选框），而不是另画一张。

产出
----
  assets/rs_create_compat/textures/block/advanced_collection_cache_{top,side,bottom}_{active,inactive}.png
  assets/rs_create_compat/textures/item/area_selector.png

用法：python tools/gen_advanced_collection_cache_assets.py   （幂等：重复跑结果一致）
"""
import os
import sys

from PIL import Image

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "textures")
BLOCK = os.path.join(TEX, "block")
ITEM = os.path.join(TEX, "item")

FACES = ("top", "side", "bottom")
STATES = ("active", "inactive")


def recolor(pixel):
    """基础版颜色 → 高级版颜色：压暗 + 橙铜偏色（保留 alpha 与明暗层次）。"""
    r, g, b, a = pixel
    if a == 0:
        return pixel
    # 先往橙铜方向偏：红略抬、绿压、蓝压得更狠
    r2 = min(255, int(r * 1.06 + 10))
    g2 = int(g * 0.78)
    b2 = int(b * 0.52)
    # 再整体压暗 12%（「加深」），让两个方块并排时高级版明显更沉
    r2, g2, b2 = int(r2 * 0.88), int(g2 * 0.88), int(b2 * 0.88)
    return (r2, g2, b2, a)


def make_block_texture(face, state):
    src = os.path.join(BLOCK, "collection_cache_%s_%s.png" % (face, state))
    dst = os.path.join(BLOCK, "advanced_collection_cache_%s_%s.png" % (face, state))
    if not os.path.isfile(src):
        raise SystemExit("缺少基础版贴图：%s" % src)
    image = Image.open(src).convert("RGBA")
    out = Image.new("RGBA", image.size)
    out.putdata([recolor(p) for p in image.getdata()])
    out.save(dst)
    return dst


def make_area_selector_icon():
    """物品图标：基础版侧面贴图压暗 → 叠一个橙色「框选」图形（四边框 + 四角加粗）。"""
    src = os.path.join(BLOCK, "collection_cache_side_active.png")
    image = Image.open(src).convert("RGBA")
    base = Image.new("RGBA", image.size)
    base.putdata([recolor(p) for p in image.getdata()])
    px = base.load()
    width, height = base.size
    orange = (255, 159, 28, 255)
    dark = (30, 18, 6, 255)
    # 内缩 2px 的方框（先画一圈暗底再画橙线，保证在任何底色上都看得清）
    left, top, right, bottom = 2, 2, width - 3, height - 3
    for x in range(left, right + 1):
        for y in (top, bottom):
            px[x, y] = dark
    for y in range(top, bottom + 1):
        for x in (left, right):
            px[x, y] = dark
    left, top, right, bottom = 3, 3, width - 4, height - 4
    for x in range(left, right + 1):
        for y in (top, bottom):
            px[x, y] = orange
    for y in range(top, bottom + 1):
        for x in (left, right):
            px[x, y] = orange
    # 四个角加粗成 2x2（像「选定角」的标记）
    for cx, cy in ((left, top), (right - 1, top), (left, bottom - 1), (right - 1, bottom - 1)):
        for dx in range(2):
            for dy in range(2):
                px[cx + dx, cy + dy] = orange
    out = os.path.join(ITEM, "area_selector.png")
    base.save(out)
    return out


def main():
    written = []
    for face in FACES:
        for state in STATES:
            written.append(make_block_texture(face, state))
    written.append(make_area_selector_icon())
    for path in written:
        print("written %s" % os.path.relpath(path, ROOT).replace("\\", "/"))
    print("[result] 生成 %d 张派生贴图（全部基于基础版 collection_cache 改色 / 加深）" % len(written))
    return 0


if __name__ == "__main__":
    sys.exit(main())
