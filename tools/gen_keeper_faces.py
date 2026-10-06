# -*- coding: utf-8 -*-
"""定量物品保持器（基础版 / 高级版）贴图拆面 + 高级版差异化（幂等）。

用户硬规则：贴图不许一图多用。
  * 一台机器的四个竖直侧面可共用一张（``*_side``），但**顶 / 底必须各自独立**；
  * **基础版与高级版必须明显不同**（不能引用同一张 PNG）。

本脚本只负责「保持器」这一类（其它机器由 ``tools/gen_block_face_split.py`` 处理）：
  * ``quantity_keeper_side[_active]``          —— 原贴图（四个竖直侧面共用）；
  * ``quantity_keeper_top / _bottom[_active]`` —— 复用 ``gen_block_face_split`` 的顶/底模板
    （RS 灰机壳 + 螺钉 + 指示灯 / 散热格栅），强调色取原 ``quantity_keeper_active.png`` 的最饱和色（黄）；
  * ``advanced_quantity_keeper_side[_active]`` —— 在基础版侧面上做**可见的「高级」改造**：
    强调色整体色相位移到品红（保留原明度层次）+ 四角铆钉 + 内侧强调色包边；
  * ``advanced_quantity_keeper_top / _bottom[_active]`` —— 同款模板，强调色 = 品红。

同时把 4 个方块模型改写为 ``minecraft:block/cube``（六个面显式指到各自的贴图），
并把结论写进 ``tmp_textures/KEEPER_FACE_TEXTURES.md``。

用法：python tools/gen_keeper_faces.py
"""
import os
import sys

from PIL import Image
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_block_face_split import (  # noqa: E402  （复用同款顶/底模板，保证与其它机器风格一致）
    OUT, PREVIEW, accent_of, bottom_tex, dim, save, top_tex,
)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat")
MODELS = os.path.join(ASSETS, "models", "block")

# 高级版强调色（品红）：与基础版黄、以及与其它机器的青/橙/绿/紫/红都拉得开
ACCENT_ADV = (236, 98, 214, 255)
# 侧面上「高级版」改造用的铆钉位置（避开外框 1px）
RIVETS = ((2, 2), (13, 2), (2, 13), (13, 13))


def load(path):
    return Image.open(path).convert("RGBA")


def shift_accent_to_magenta(img):
    """把原贴图里的「黄/橄榄系强调色像素」按明度映射成品红系（保留暗部层次）。

    判定用「绿分量明显高于蓝分量」而不是「蓝分量最低」——后者会把 r/g 仅相差 1 的灰色像素
    一并吞掉，导致整个机壳被染成品红。
    """
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a < 128:
                continue
            if g > b + 3 and r >= b:
                lum = 0.299 * r + 0.587 * g + 0.114 * b
                px[x, y] = (min(255, int(lum * 1.30)), int(lum * 0.52), min(255, int(lum * 1.20)), 255)
    return img


def make_advanced_side(src):
    """基础版侧面 -> 高级版侧面：色相位移 + 四角铆钉 + 内侧强调色包边。"""
    img = shift_accent_to_magenta(src.copy())
    px = img.load()
    # 内侧 1px 强调色包边（不含最外圈，保留原外框的立体感）
    for x in range(1, 15):
        px[x, 1] = ACCENT_ADV
        px[x, 14] = tuple(int(v * 0.55) for v in ACCENT_ADV[:3]) + (255,)
    for y in range(1, 15):
        px[1, y] = tuple(int(v * 0.75) for v in ACCENT_ADV[:3]) + (255,)
        px[14, y] = tuple(int(v * 0.55) for v in ACCENT_ADV[:3]) + (255,)
    # 四角铆钉：一眼看出是「加固过的高级版」
    for (x, y) in RIVETS:
        px[x, y] = (240, 240, 240, 255)
        px[x, y - 1 if y > 8 else y + 1] = tuple(int(v * 0.45) for v in ACCENT_ADV[:3]) + (255,)
    return img


def source(block, active):
    """原「六面一张图」贴图；已被拆面时回退到 ``_side``（幂等）。"""
    suffix = "_active" if active else "_inactive"
    for pattern in ("%s%s.png", "%s_side%s.png"):
        path = os.path.join(OUT, pattern % (block, suffix))
        if os.path.exists(path):
            return path
    raise SystemExit("缺少原贴图：%s%s.png" % (block, suffix))


def write_model(name, side, top, bottom):
    """写出 cube 模型：六个面显式指到各自的贴图（四个竖直侧面共用 side）。"""
    ns = "rs_create_compat:block/"
    faces = {"north": side, "east": side, "south": side, "west": side, "up": top, "down": bottom}
    lines = [
        "{",
        '  "parent": "minecraft:block/cube",',
        '  "textures": {',
        '    "particle": "%s%s",' % (ns, side),
    ]
    for key in ("north", "east", "south", "west", "up", "down"):
        lines.append('    "%s": "%s%s",' % (key, ns, faces[key]))
    # 去掉最后一个逗号
    lines[-1] = lines[-1].rstrip(",")
    lines += ["  }", "}", ""]
    path = os.path.join(MODELS, name + ".json")
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines))
    print("wrote", os.path.relpath(path, ROOT))


def main():
    os.makedirs(PREVIEW, exist_ok=True)
    rows = []

    # ---------- 基础版：黄（强调色从原 active 图里取） ----------
    base_accent = accent_of(os.path.join(OUT, "quantity_keeper_active.png"))
    for active in (False, True):
        suffix = "_active" if active else "_inactive"
        side = load(source("quantity_keeper", active))
        side.save(os.path.join(OUT, "quantity_keeper_side%s.png" % suffix))
        rows.append(("quantity_keeper_side%s" % suffix, side))
        print("[OK] block/quantity_keeper_side%s.png（四个侧面共用）" % suffix)
        acc = base_accent if active else dim(base_accent, 0.35)
        save(top_tex(acc), "quantity_keeper_top%s" % suffix, rows)
        save(bottom_tex(acc), "quantity_keeper_bottom%s" % suffix, rows)

    # ---------- 高级版：品红 + 加固标记 ----------
    for active in (False, True):
        suffix = "_active" if active else "_inactive"
        advanced_side = make_advanced_side(load(source("quantity_keeper", active)))
        advanced_side.save(os.path.join(OUT, "advanced_quantity_keeper_side%s.png" % suffix))
        rows.append(("advanced_quantity_keeper_side%s" % suffix, advanced_side))
        print("[OK] block/advanced_quantity_keeper_side%s.png（四个侧面共用）" % suffix)
        acc = ACCENT_ADV if active else dim(ACCENT_ADV, 0.35)
        save(top_tex(acc), "advanced_quantity_keeper_top%s" % suffix, rows)
        save(bottom_tex(acc), "advanced_quantity_keeper_bottom%s" % suffix, rows)

    # ---------- 模型：cube，六面显式 ----------
    write_model("quantity_keeper", "quantity_keeper_side_inactive",
                "quantity_keeper_top_inactive", "quantity_keeper_bottom_inactive")
    write_model("quantity_keeper_active", "quantity_keeper_side_active",
                "quantity_keeper_top_active", "quantity_keeper_bottom_active")
    write_model("advanced_quantity_keeper", "advanced_quantity_keeper_side_inactive",
                "advanced_quantity_keeper_top_inactive", "advanced_quantity_keeper_bottom_inactive")
    write_model("advanced_quantity_keeper_active", "advanced_quantity_keeper_side_active",
                "advanced_quantity_keeper_top_active", "advanced_quantity_keeper_bottom_active")

    # ---------- 预览条 ----------
    scale = 3
    strip = Image.new("RGBA", (len(rows) * 16 * scale, 16 * scale), (0, 0, 0, 0))
    for i, (_n, img) in enumerate(rows):
        strip.paste(img.resize((16 * scale, 16 * scale), Image.NEAREST), (i * 16 * scale, 0))
    strip.save(os.path.join(PREVIEW, "keeper_faces_preview.png"))
    print("[OK] tmp_textures/face_split/keeper_faces_preview.png")


if __name__ == "__main__":
    main()
