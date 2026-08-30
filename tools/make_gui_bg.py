#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
make_gui_bg.py - Minecraft GUI 背景图生成工具

使用 bg.png（MC 原生背景，9-slice 四角拉伸）与 slot.png（槽位）拼接生成
规整的 GUI 背景贴图，供模组 GUI 使用。所有参数均通过命令行传入，无需改文件。

bg.png 结构（256x256）：
    上边框：(0,0)-(247,4)     下边框：(0,165)-(247,169)
    左边框：(0,0)-(4,165)     右边框：(243,0)-(247,165)
    可拉伸中部：(4,4)-(243,161)

用法示例：
    # 范围充电器（无背包、无槽位）
    python make_gui_bg.py --out ..\\src\\main\\resources\\assets\\rs_create_compat\\textures\\gui\\range_charger.png --width 176 --height 84

    # 完整容器（含玩家背包 3x9 + 快捷栏区域由 bg.png 下半部拼接）
    python make_gui_bg.py --out ..\\src\\main\\resources\\assets\\rs_create_compat\\textures\\gui\\inventory.png --width 176 --height 166 --container-height 84 --with-inventory --slot-cols 9 --slot-rows 3

    # 自定义槽位网格（可多次 --slots，格式 X,Y,COLS,ROWS，间距 18px）
    python make_gui_bg.py --out ..\\gui\\loader.png --width 208 --height 222 --container-height 140 --with-inventory \
        --slot-start-x 8 --slot-start-y 140 --slot-cols 9 --slot-rows 3 \
        --slots "8,8,1,1" "35,8,6,1" "8,26,9,6" "8,198,9,1"
"""
import argparse
import os
import sys

from PIL import Image

# bg.png 9-slice 常量
BORDER = 4
CENTER_LEFT = 4
CENTER_TOP = 4
CENTER_RIGHT = 243  # 右边界 x（不含）
CENTER_BOTTOM = 161  # 下边界 y（不含）
BG_INV_Y = 165  # 背包区域起始 y

# 素材槽位尺寸：slot.png 的完整槽位是 17x17（边框在 0 与 16 行列），
# MC 槽位间距 18px，粘贴时相邻槽之间留 1px 背景空隙。
SLOT_PX = 17    # 槽位素材像素尺寸
SLOT_GAP = 18   # 槽位中心间距


def stretch(dst: Image.Image, src: Image.Image, sx, sy, sw, sh, dx, dy, dw, dh):
    """把源图区域 (sx,sy,sw,sh) 拉伸绘制到目标图 (dx,dy,dw,dh)。"""
    if dw <= 0 or dh <= 0 or sw <= 0 or sh <= 0:
        return
    region = src.crop((sx, sy, sx + sw, sy + sh))
    region = region.resize((dw, dh), Image.NEAREST)
    dst.paste(region, (dx, dy))


def draw_container(dst: Image.Image, src: Image.Image, width: int, height: int):
    """9-slice 拼接容器主体（bg.png 0-165 区域）到 height。"""
    cw = width - BORDER * 2
    cmh = max(1, height - BORDER * 2)
    mid_w = CENTER_RIGHT - CENTER_LEFT - BORDER * 2
    mid_h = CENTER_BOTTOM - CENTER_TOP - BORDER * 2

    # 中部（最大面积，先画）
    stretch(dst, src, CENTER_LEFT, CENTER_TOP, mid_w, mid_h, BORDER, BORDER, cw, cmh)
    # 四角
    stretch(dst, src, 0, 0, BORDER, BORDER, 0, 0, BORDER, BORDER)
    stretch(dst, src, CENTER_RIGHT, 0, BORDER, BORDER, width - BORDER, 0, BORDER, BORDER)
    stretch(dst, src, 0, CENTER_BOTTOM, BORDER, BORDER, 0, height - BORDER, BORDER, BORDER)
    stretch(dst, src, CENTER_RIGHT, CENTER_BOTTOM, BORDER, BORDER,
            width - BORDER, height - BORDER, BORDER, BORDER)
    # 上下边（横向拉伸）
    stretch(dst, src, CENTER_LEFT, 0, mid_w, BORDER, BORDER, 0, cw, BORDER)
    stretch(dst, src, CENTER_LEFT, CENTER_BOTTOM, mid_w, BORDER,
            BORDER, height - BORDER, cw, BORDER)
    # 左右边（纵向拉伸）
    stretch(dst, src, 0, CENTER_TOP, BORDER, mid_h, 0, BORDER, BORDER, cmh)
    stretch(dst, src, CENTER_RIGHT, CENTER_TOP, BORDER, mid_h,
            width - BORDER, BORDER, BORDER, cmh)


def draw_inventory(dst: Image.Image, src: Image.Image, width: int, top: int,
                   slot_img: Image.Image, sx, sy, cols, rows, gap):
    """背包区域：直接用 bg.png 上半部分（0-165 有效内容）连续拉伸到整图高度，
    再绘制槽位网格。bg.png 的 165 以下区域是透明的，不能作为背景。"""
    # 容器主体已经由 draw_container 铺满全高，这里只负责画背包槽位网格
    for row in range(rows):
        y = sy + row * gap
        if y + SLOT_PX > dst.height:
            break
        for col in range(cols):
            x = sx + col * gap
            if x + SLOT_PX > width:
                break
            dst.paste(slot_img, (x, y))


def main():
    parser = argparse.ArgumentParser(description="Minecraft GUI 背景生成工具")
    script_dir = os.path.dirname(os.path.abspath(__file__))
    parser.add_argument("--bg", default=os.path.join(script_dir, "bg.png"), help="背景源图（bg.png）")
    parser.add_argument("--slot", default=os.path.join(script_dir, "slot.png"), help="槽位源图（slot.png）")
    parser.add_argument("--out", required=True, help="输出 PNG 路径")
    parser.add_argument("--width", type=int, default=176)
    parser.add_argument("--height", type=int, default=84)
    parser.add_argument("--container-height", type=int, default=None, help="容器主体高度（默认=height）")
    parser.add_argument("--with-inventory", action="store_true", help="拼接玩家背包区域")
    parser.add_argument("--slot-start-x", type=int, default=7)
    parser.add_argument("--slot-start-y", type=int, default=17)
    parser.add_argument("--slot-cols", type=int, default=9)
    parser.add_argument("--slot-rows", type=int, default=3)
    parser.add_argument("--slot-gap", type=int, default=18)
    parser.add_argument("--slots", action="append", default=[], metavar="X,Y,COLS,ROWS",
                        help="自定义槽位网格，可多次指定（如 --slots \"8,8,1,1\" \"8,26,9,6\"）")
    args = parser.parse_args()

    bg = Image.open(args.bg).convert("RGBA")
    slot_img = Image.open(args.slot).convert("RGBA").crop((0, 0, SLOT_PX, SLOT_PX))
    container_h = args.container_height if args.container_height else args.height

    dst = Image.new("RGBA", (args.width, args.height), (0, 0, 0, 0))
    # 整幅背景连续铺满（bg.png 0-165 有效区域 9-slice 拉伸），保证无透明洞
    draw_container(dst, bg, args.width, args.height)
    if args.with_inventory:
        draw_inventory(dst, bg, args.width, min(container_h, args.height), slot_img,
                       args.slot_start_x, args.slot_start_y, args.slot_cols, args.slot_rows, args.slot_gap)

    # 自定义槽位网格（与 Menu 槽位坐标一一对应）
    for spec in args.slots:
        sx, sy, cols, rows = (int(v) for v in spec.split(","))
        for row in range(rows):
            for col in range(cols):
                x, y = sx + col * SLOT_GAP, sy + row * SLOT_GAP
                if x + SLOT_PX > args.width or y + SLOT_PX > args.height:
                    continue
                dst.paste(slot_img, (x, y))

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    dst.save(args.out)
    print(f"Generated: {args.out} ({args.width}x{args.height})")


if __name__ == "__main__":
    sys.exit(main())
