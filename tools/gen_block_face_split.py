# -*- coding: utf-8 -*-
"""把「六个面共用一张 PNG」的方块拆成 side / top / bottom 三张各自独立的贴图（幂等）。

用户硬规则：贴图不许一图多用（唯一例外 = 同一台机器的四个竖直侧面）。
本脚本给目标方块生成三张**各自独立**的贴图：
  * ``<id>_side[_active]``   —— 沿用原贴图（四个竖直侧面共用这一张，用户明确允许）；
  * ``<id>_top[_active]``    —— RS 灰色机壳 + 四角螺钉 + 一条强调色指示灯（顶面）；
  * ``<id>_bottom[_active]`` —— RS 灰色机壳 + 三道散热格栅（底面，不发光）。
强调色从原 ``_active`` 贴图里**取最饱和像素**，保证与既有配色协调（取不到则回退中性灰蓝）。

只处理「其它三路未占用」的方块；SPT / 回流总线 / 保持器 贴图按硬规则不动（见
``tools/audit_shared_textures.py`` 报告里的排除项）。

用法：python tools/gen_block_face_split.py
"""
import os
import numpy as np
from PIL import Image
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE_SIDE = os.path.join(ROOT, "tmp_rs_ref2", "block", "side.png")
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "textures", "block")
PREVIEW = os.path.join(ROOT, "tmp_textures", "face_split")

TARGETS = [
    "schematic_loader",
    "advanced_schematic_loader",
    "collection_cache",
    "range_charger",
    "sequence_assembly_executor",
]

DARK = (20, 20, 20, 255)
RECESS = (17, 19, 22, 255)
LIGHT = (122, 122, 122, 255)
FALLBACK_ACCENT = (90, 160, 190, 255)


def dim(c, factor):
    return (int(c[0] * factor), int(c[1] * factor), int(c[2] * factor), 255)


def base():
    return np.array(Image.open(BASE_SIDE).convert("RGBA")).copy()


def setpx(arr, x, y, c):
    if 0 <= x < 16 and 0 <= y < 16:
        arr[y, x] = c


def accent_of(path):
    """从原「已接入」贴图里取最饱和的非灰像素，作为该方块的强调色。"""
    if not os.path.exists(path):
        return FALLBACK_ACCENT
    px = Image.open(path).convert("RGBA").load()
    best, best_sat = FALLBACK_ACCENT, 0
    for y in range(16):
        for x in range(16):
            r, g, b, a = px[x, y]
            if a < 128:
                continue
            hi, lo = max(r, g, b), min(r, g, b)
            sat = hi - lo
            if sat > best_sat and hi > 90:
                best_sat, best = sat, (r, g, b, 255)
    return best


def top_tex(accent):
    arr = base()
    for (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12)):
        setpx(arr, x, y, DARK)
        setpx(arr, x + (1 if x == 3 else -1), y, DARK)
    # 中央指示灯条（顶面唯一的强调色，静/动两态靠明暗区分）
    for x in range(5, 11):
        setpx(arr, x, 7, RECESS)
        setpx(arr, x, 8, accent)
        setpx(arr, x, 9, RECESS)
    return arr


def bottom_tex(accent):
    arr = base()
    for sy in (4, 7, 10):
        for x in range(3, 13):
            setpx(arr, x, sy, RECESS)
            setpx(arr, x, sy + 1, LIGHT)
    # 底缘一道强调色接缝：底面不发光（与顶面/侧面一眼区分），但保留本方块配色
    for x in range(4, 12):
        setpx(arr, x, 12, accent)
    return arr


def save(arr, name, rows):
    img = Image.fromarray(arr, "RGBA")
    os.makedirs(OUT, exist_ok=True)
    img.save(os.path.join(OUT, name + ".png"))
    img.save(os.path.join(PREVIEW, name + ".png"))  # tmp_textures/ 同步留档
    rows.append((name, img))
    print("[OK] block/%s.png" % name)


def source_for(block, active):
    """原始「六面一张图」贴图；若已被拆面（旧图删除）则回退到拆出来的 _side 贴图（幂等）。"""
    suffix = "_active" if active else "_inactive"
    for pattern in ("%s%s.png", "%s_side%s.png"):
        path = os.path.join(OUT, pattern % (block, suffix))
        if os.path.exists(path):
            return path
    raise SystemExit("缺少原贴图：%s%s.png" % (block, suffix))


def main():
    os.makedirs(PREVIEW, exist_ok=True)
    rows = []
    for block in TARGETS:
        src_active = source_for(block, True)
        accent = accent_of(src_active)
        for active in (False, True):
            suffix = "_active" if active else "_inactive"
            img = Image.open(source_for(block, active)).convert("RGBA")
            img.save(os.path.join(OUT, "%s_side%s.png" % (block, suffix)))
            rows.append(("%s_side%s" % (block, suffix), img))
            print("[OK] block/%s_side%s.png（四个侧面共用）" % (block, suffix))
            acc = accent if active else dim(accent, 0.35)
            save(top_tex(acc), "%s_top%s" % (block, suffix), rows)
            save(bottom_tex(acc), "%s_bottom%s" % (block, suffix), rows)

    scale = 3
    strip = Image.new("RGBA", (len(rows) * 16 * scale, 16 * scale), (0, 0, 0, 0))
    for i, (_n, img) in enumerate(rows):
        strip.paste(img.resize((16 * scale, 16 * scale), Image.NEAREST), (i * 16 * scale, 0))
    strip.save(os.path.join(PREVIEW, "face_split_preview.png"))
    print("[OK] tmp_textures/face_split/face_split_preview.png")


if __name__ == "__main__":
    main()
