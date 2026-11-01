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

坐标语义（**第 126 轮钉死，写错就是「槽位与背景差 1px」**）：
    MC 的 ``Slot.x / Slot.y`` 指的是**物品图标**的左上角；18×18 的槽框背景
    左上角在 ``(Slot.x - 1, Slot.y - 1)``，而本工装贴的 17×17 ``slot.png``
    正是那个「槽框」——所以 **PNG 精灵坐标 = Menu 坐标 - 1**。
    权威出处：``tools/make_gui_gui.py`` 第 21 / 905 / 1365 / 1495 行
    「精灵坐标 +1 = Menu 坐标」。

因此有两种传参方式，**新界面一律用 ``--slots-menu``**：
    ``--slots``      参数是**精灵坐标**（历史用法，贴图原样落位）；
    ``--slots-menu`` 参数是**Menu / Spec 坐标**（本工具自动 -1），
                     与 ``XxxSpec`` 里给 ``addSlot`` 的数字**逐字相同**，
                     杜绝「手算 -1 算反」这类事故。

    # 降解室发电机（Menu 坐标与 DecompositionGeneratorSpec 逐字一致）
    python make_gui_bg.py --out ..\\decomposition_generator.png --width 182 --height 212 \
        --slots-menu "8,18,4,4" "118,37,2,2" "118,76,3,2" "8,128,9,3" "8,188,9,1"
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
# 精灵坐标 = Menu 坐标 - SLOT_INSET（见文件头「坐标语义」；权威出处 make_gui_gui.py）。
SLOT_INSET = 1


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
    parser.add_argument("--slots", action="append", nargs="+", default=[], metavar="X,Y,COLS,ROWS",
                        help="自定义槽位网格（精灵坐标，原样贴图），可多次/多值指定（如 --slots \"8,8,1,1\" \"8,26,9,6\"）")
    parser.add_argument("--slots-menu", action="append", nargs="+", default=[], metavar="X,Y,COLS,ROWS",
                        help="自定义槽位网格（Menu/Spec 坐标，贴图时自动 -1）；新界面用这个")
    args = parser.parse_args()

    bg = Image.open(args.bg).convert("RGBA")
    slot_img = Image.open(args.slot).convert("RGBA").crop((0, 0, SLOT_PX, SLOT_PX))
    container_h = args.container_height if args.container_height else args.height

    # 精灵坐标 = Menu 坐标 - 1（MC 的 18×18 槽框左上角在 Slot.x/y 的 -1 处）。
    # 两种传参在这里合流，后面只有一条绘制路径，不会出现「两套语义」。
    flat = lambda groups: [spec for group in groups for spec in group]
    slot_specs = [tuple(int(v) for v in spec.split(",")) for spec in flat(args.slots)]
    slot_specs += [(sx - SLOT_INSET, sy - SLOT_INSET, cols, rows)
                   for sx, sy, cols, rows in
                   (tuple(int(v) for v in spec.split(",")) for spec in flat(args.slots_menu))]

    dst = Image.new("RGBA", (args.width, args.height), (0, 0, 0, 0))
    # 整幅背景连续铺满（bg.png 0-165 有效区域 9-slice 拉伸），保证无透明洞
    draw_container(dst, bg, args.width, args.height)
    if args.with_inventory:
        draw_inventory(dst, bg, args.width, min(container_h, args.height), slot_img,
                       args.slot_start_x, args.slot_start_y, args.slot_cols, args.slot_rows, args.slot_gap)

    # 自定义槽位网格（与 Menu 槽位坐标一一对应）
    for sx, sy, cols, rows in slot_specs:
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
