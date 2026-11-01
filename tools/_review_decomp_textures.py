# -*- coding: utf-8 -*-
"""降解室发电机贴图审查图：6 张（16x 放大）+ 同族参考贴图（同样放大），一图看完画风是否一致。"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BLOCK = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "textures", "block")
OUT = os.path.join(ROOT, "build", "decomp_generator_texture_review.png")
SCALE = 16
LABEL_H = 12
PAD = 8

MINE = [
    "decomposition_generator_side_inactive.png", "decomposition_generator_side_active.png",
    "decomposition_generator_top_inactive.png", "decomposition_generator_top_active.png",
    "decomposition_generator_bottom_inactive.png", "decomposition_generator_bottom_active.png",
]
REF = [
    "collection_cache_side_inactive.png", "collection_cache_side_active.png",
    "collection_cache_top_active.png", "sequence_execution_chamber_front_active.png",
]

rows = [("本机（decomposition_generator）", MINE), ("同族参考（画风基准）", REF)]
cols = max(len(MINE), len(REF))
cell = 16 * SCALE + PAD
total_h = sum(LABEL_H + 16 * SCALE + PAD + 6 for _ in rows) + PAD
sheet = Image.new("RGBA", (cols * cell + PAD, total_h), (28, 28, 32, 255))
dr = ImageDraw.Draw(sheet)

y = PAD
for title, names in rows:
    dr.text((PAD, y), title, fill=(240, 240, 240, 255))
    y += LABEL_H
    for i, name in enumerate(names):
        path = os.path.join(BLOCK, name)
        img = Image.open(path).convert("RGBA").resize((16 * SCALE, 16 * SCALE), Image.NEAREST)
        x = PAD + i * cell
        sheet.alpha_composite(img, (x, y))
        dr.text((x, y + 16 * SCALE + 1), name.replace("decomposition_generator_", "").replace(".png", "")[:20],
                fill=(200, 200, 200, 255))
    y += 16 * SCALE + PAD + 6

sheet.save(OUT, "PNG")
print(OUT, sheet.size)
