#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""为三版本无线终端生成区分贴图：
- advanced_remote_terminal_charged.png: 蓝色能量光泽
- creative_advanced_remote_terminal.png: 紫色创造光泽
基于原贴图 advanced_remote_terminal.png 做颜色叠加（只作用于非透明像素）。
用法: python tools/make_terminal_textures.py
"""
import os
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "textures", "item",
                   "advanced_remote_terminal.png")
OUT_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "textures", "item")


def tint(src_path, out_path, color, amount=0.45, glow=True):
    img = Image.open(src_path).convert("RGBA")
    px = img.load()
    w, h = img.size
    cr, cg, cb = color
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            # 叠加目标色
            nr = int(r + (cr - r) * amount)
            ng = int(g + (cg - g) * amount)
            nb = int(b + (cb - b) * amount)
            if glow:
                # 增加亮度，模拟能量发光
                nr = min(255, nr + 30)
                ng = min(255, ng + 30)
                nb = min(255, nb + 30)
            px[x, y] = (nr, ng, nb, a)
    img.save(out_path)


if __name__ == "__main__":
    tint(SRC, os.path.join(OUT_DIR, "advanced_remote_terminal_charged.png"), (70, 140, 255), 0.5, True)
    tint(SRC, os.path.join(OUT_DIR, "creative_advanced_remote_terminal.png"), (200, 90, 255), 0.5, True)
    print("done")
