#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
gen_rs_textures.py - 把模组全部方块/物品贴图重置为 16x16 的 MC 像素风，
机壳/配色/版式仿 Refined Storage 原版（银灰金属 + 中央功能面板）。
用法: python tools/gen_rs_textures.py
"""
import os
from PIL import Image
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "textures")
BUILD = os.path.join(ROOT, "build", "resources", "main", "assets", "rs_create_compat", "textures")

S = 16

# ---------- 调色 ----------
# RS 风格金属灰阶
METAL_LIGHT = (206, 208, 212)
METAL_BASE = (176, 179, 185)
METAL_MID = (138, 141, 148)
METAL_DARK = (96, 99, 106)
METAL_LINE = (56, 58, 64)
OUTLINE = (30, 31, 35)
PAPER = (236, 240, 244)
PAPER_SHADOW = (168, 176, 186)


def machine(accent_hi, accent_mid, accent_dark, glyph, glyph_ink, name):
    """画一个 RS 风格机器方块贴图：金属机壳 + 中央功能面板 + 自定义图标。"""
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    px = im.load()
    # 金属底 + 微纹理（2px 棋盘渐变 + 四角铆钉）
    for y in range(S):
        for x in range(S):
            v = 0
            if ((x // 2) + (y // 2)) % 2 == 0:
                v = 1
            c = METAL_BASE if v == 0 else METAL_LIGHT
            # 四周描边：左上亮 / 右下暗（MC 立体感）
            if x == 0 or y == 0:
                c = METAL_LIGHT
            if x == S - 1 or y == S - 1:
                c = METAL_DARK
            px[x, y] = c
    # 四角铆钉
    for (cx, cy) in ((1, 1), (S - 2, 1), (1, S - 2), (S - 2, S - 2)):
        px[cx, cy] = METAL_MID
    # 中央面板区域（2..13，面积 12x12）
    panel = (2, 2, S - 2, S - 2)
    for y in range(panel[1], panel[3]):
        for x in range(panel[0], panel[2]):
            px[x, y] = accent_dark if (x == panel[0] or y == panel[1]) else \
                (accent_mid if (x == panel[2] - 1 or y == panel[3] - 1) else accent_hi)
    # 中央内部更亮一点
    for y in range(panel[1] + 1, panel[3] - 1):
        for x in range(panel[0] + 1, panel[2] - 1):
            c = px[x, y]
            px[x, y] = tuple(min(255, v + 14) for v in c)
    # 图标（8x8 区域 4..11）
    draw_icon(px, glyph, glyph_ink, 4, 4)
    out = os.path.join(TEX, "block", name + ".png")
    im.save(out)
    os.makedirs(os.path.join(BUILD, "block"), exist_ok=True)
    im.save(os.path.join(BUILD, "block", name + ".png"))
    print("block", name)


def draw_icon(px, rows, ink, ox, oy):
    """rows: 8 条长度 8 的字符串，'#'=墨水色，'.'=透明(保留底色)。"""
    for yy, row in enumerate(rows):
        for xx, ch in enumerate(row):
            if ch == "#":
                px[ox + xx, oy + yy] = ink


# 8x8 图标定义（# 为墨色）
ICON_BLUEPRINT = [
    "..####..",
    ".#....#.",
    ".#....#.",
    ".#.##.#.",
    ".#....#.",
    ".#.##.#.",
    ".#....#.",
    "..####..",
]
ICON_BLUEPRINT2 = [
    "..####..",
    ".#....#.",
    ".#.####.",
    ".#....#.",
    ".#.####.",
    ".#....#.",
    ".#.####.",
    "..####..",
]
ICON_ANTENNA = [
    "...##...",
    "..#..#..",
    ".#....#.",
    ".#....#.",
    "..####..",
    "...#....",
    "..#.....",
    "..#.....",
]
ICON_KEEPER = [
    "...##...",
    "...##...",
    "...##...",
    ".######.",
    "...#....",
    "...#....",
    "...#....",
    "...#....",
]
ICON_GRID = [
    "##..##..",
    "##..##..",
    "........",
    "##..##..",
    "##..##..",
    "........",
    "##..##..",
    "##..##..",
]
paper_item(UNIT_MARK, (66, 120, 190), "sequence_unit_pattern")
paper_item(ASM_MARK, (190, 90, 70), "sequence_assembly_pattern")

print("done")
