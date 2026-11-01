# -*- coding: utf-8 -*-
"""把 16x16 贴图转成字符画（亮度 + 强调色标记），用于学既有贴图的像素code。"""
import os
import sys
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BLOCK = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "textures", "block")

RAMP = " .:-=+*#%@"


def dump(name: str) -> None:
    img = Image.open(os.path.join(BLOCK, name)).convert("RGBA")
    px = img.load()
    print("==", name)
    for y in range(16):
        row = []
        for x in range(16):
            r, g, b, a = px[x, y]
            if a == 0:
                row.append(" ")
                continue
            lum = (r * 299 + g * 587 + b * 114) // 1000
            ch = RAMP[min(len(RAMP) - 1, lum * len(RAMP) // 256)]
            # 强调色：饱和度高（max-min 大）
            if max(r, g, b) - min(r, g, b) > 60:
                if r > g and r > b:
                    ch = "R"
                elif g > r and g > b:
                    ch = "G"
                elif b > g and b > r:
                    ch = "B"
                if r > 200 and g > 200:
                    ch = "Y"
            row.append(ch)
        print("".join(row))
    print()


for n in sys.argv[1:]:
    dump(n)
