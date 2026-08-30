# -*- coding: utf-8 -*-
"""最终验证：背景无透明洞 + 所有槽位齐全 + 按钮区无槽位。"""
import sys
from PIL import Image

SLOT = 18


def is_slot_at(img, x, y):
    w, h = img.size
    if x < 0 or y < 0 or x + SLOT > w or y + SLOT > h:
        return None
    dark = 0
    for dy in range(3):
        for dx in range(3):
            p = img.getpixel((x + dx, y + dy))
            if p[3] > 200 and sum(abs(a - b) for a, b in zip(p[:3], (55, 55, 55))) < 40:
                dark += 1
    return dark >= 5


def check(path, slots, buttons):
    img = Image.open(path).convert("RGBA")
    w, h = img.size
    print(f"\n=== {path} ({w}x{h}) ===")
    # 1. 背景透明检测：非槽位区域采样
    transparent = 0
    total = 0
    for y in range(0, h, 4):
        for x in range(0, w, 4):
            # 跳过所有槽位区域
            in_slot = False
            for (sx, sy, sc, sr) in slots:
                if sx <= x < sx + sc * SLOT and sy <= y < sy + sr * SLOT:
                    in_slot = True
                    break
            if in_slot:
                continue
            total += 1
            if img.getpixel((x, y))[3] < 128:
                transparent += 1
    if total:
        ratio = transparent * 100.0 / total
        print(f"  [背景] 透明像素: {ratio:.1f}% {'OK' if ratio < 1 else 'FAIL'}")
    # 2. 槽位检测
    ok = miss = 0
    for (sx, sy, sc, sr) in slots:
        for r in range(sr):
            for c in range(sc):
                r2 = is_slot_at(img, sx + c * SLOT, sy + r * SLOT)
                if r2:
                    ok += 1
                else:
                    miss += 1
                    if miss <= 5:
                        print(f"  [槽位 MISS] ({sx + c * SLOT},{sy + r * SLOT})")
    print(f"  [槽位] {ok} OK / {miss} MISS")
    # 3. 按钮区无槽位
    for (bx, by, bw, bh, name) in buttons:
        bad = 0
        for y in range(by, by + bh, 2):
            for x in range(bx, bx + bw, 2):
                if is_slot_at(img, x - 1, y - 1) or is_slot_at(img, x, y):
                    bad += 1
        print(f"  [按钮区 {name} @({bx},{by}) {bw}x{bh}] 槽位冲突: {bad}{'' if bad == 0 else ' FAIL'}")


check("src/main/resources/assets/rs_create_compat/textures/gui/schematic_loader.png",
      [(8, 8, 1, 1), (35, 8, 6, 1), (8, 26, 9, 6), (8, 140, 9, 3), (8, 198, 9, 1)],
      [])
check("src/main/resources/assets/rs_create_compat/textures/gui/advanced_schematic_loader.png",
      [(8, 30, 9, 3), (8, 98, 9, 12), (8, 326, 6, 1), (8, 348, 9, 3), (8, 406, 9, 1)],
      [(8, 428, 18, 14, "A"), (28, 428, 18, 14, "D"), (48, 428, 18, 14, "R"),
       (68, 428, 18, 14, "G"), (100, 428, 68, 14, "Start")])
check("src/main/resources/assets/rs_create_compat/textures/gui/quantity_keeper.png",
      [(8, 20, 1, 1), (62, 20, 2, 3), (8, 84, 9, 3), (8, 142, 9, 1)],
      [(128, 20, 20, 14, "-"), (150, 20, 20, 14, "+"), (110, 42, 60, 16, "destroy")])
