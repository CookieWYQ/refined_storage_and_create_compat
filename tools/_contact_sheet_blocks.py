# -*- coding: utf-8 -*-
"""拼一张对比图：既有机器贴图（放大 8x） + 当前降解室发电机占位贴图。"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BLOCK = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "textures", "block")
OUT = os.path.join(ROOT, "build", "texture_contact_sheet.png")
SCALE = 8

FILES = [
    "collection_cache_side_inactive.png", "collection_cache_side_active.png",
    "collection_cache_top_inactive.png", "collection_cache_top_active.png",
    "collection_cache_bottom_inactive.png", "collection_cache_bottom_active.png",
    "sequence_execution_chamber_front_inactive.png", "sequence_execution_chamber_front_active.png",
    "range_charger_inactive.png", "range_charger_active.png",
    "intermediate_cache_side_inactive.png", "intermediate_cache_side_active.png",
    "unit_pattern_manager_front.png", "unit_pattern_manager_front_active.png",
    "decomposition_generator_side_inactive.png", "decomposition_generator_side_active.png",
    "decomposition_generator_top_inactive.png", "decomposition_generator_top_active.png",
    "decomposition_generator_bottom_inactive.png", "decomposition_generator_bottom_active.png",
]

COLS = 5
CELL = 16 * SCALE + 10
LABEL_H = 12
rows = (len(FILES) + COLS - 1) // COLS
sheet = Image.new("RGBA", (COLS * CELL + 6, rows * (CELL + LABEL_H) + 6), (30, 30, 34, 255))
dr = ImageDraw.Draw(sheet)
for i, name in enumerate(FILES):
    r, c = divmod(i, COLS)
    x = 6 + c * CELL
    y = 6 + r * (CELL + LABEL_H)
    path = os.path.join(BLOCK, name)
    if not os.path.isfile(path):
        dr.text((x, y + 2), "MISSING", fill=(255, 80, 80, 255))
        continue
    img = Image.open(path).convert("RGBA").resize((16 * SCALE, 16 * SCALE), Image.NEAREST)
    sheet.alpha_composite(img, (x, y + LABEL_H))
    dr.text((x, y), name.replace(".png", "")[:38], fill=(220, 220, 220, 255))
sheet.save(OUT, "PNG")
print(OUT, sheet.size)
