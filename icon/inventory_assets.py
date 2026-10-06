# -*- coding: utf-8 -*-
"""调研：列出仓库里可用的图形素材（路径 + 实测尺寸 + 非透明包围盒 + 主色）。"""
import os, sys
from PIL import Image
from collections import Counter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

TARGETS = []

def add_dir(rel, patterns=None, recursive=True):
    base = os.path.join(ROOT, rel)
    if not os.path.isdir(base):
        return
    for dirpath, dirnames, filenames in os.walk(base):
        for fn in sorted(filenames):
            if fn.lower().endswith(".png"):
                TARGETS.append(os.path.join(dirpath, fn))

add_dir("pic")
add_dir(os.path.join("src", "main", "resources", "assets", "rs_create_compat", "textures"))

def describe(p):
    try:
        im = Image.open(p)
        im.load()
    except Exception as e:
        return p, None, None, None, "ERR:%s" % e
    w, h = im.size
    mode = im.mode
    rgba = im.convert("RGBA")
    # bounding box of non-transparent
    alpha = rgba.getchannel("A")
    bbox = alpha.getbbox()
    # dominant colors (opaque only, quantized a bit)
    px = list(rgba.getdata())
    if len(px) > 40000:
        px = px[:: max(1, len(px) // 40000)]
    cnt = Counter()
    opaque = 0
    for r, g, b, a in px:
        if a > 8:
            opaque += 1
            cnt[(r // 16 * 16, g // 16 * 16, b // 16 * 16)] += 1
    top = cnt.most_common(3)
    return p, (w, h), mode, bbox, {"opaque_ratio": round(opaque / max(1, len(px)), 3),
                                  "top": top}

rows = []
for p in TARGETS:
    p, size, mode, bbox, info = describe(p)
    rows.append((os.path.relpath(p, ROOT), size, mode, bbox, info))

print("total png found: %d" % len(rows))
for rel, size, mode, bbox, info in rows:
    print("%-95s %-11s %-6s bbox=%-18s %s" % (rel, size, mode, bbox, info))
