#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成「降解室发电机」的正式方块贴图（16×16，纯程序绘制，可复现）。

## 画风依据（工程既有成文规则，不是自创）
1. `icon/icon_README.md:140-156` —— 工程唯一的色板表：
   RS 机壳灰阶 `#494949` / `#7A7A7A` / `#858585` / `#727272` / `#777777` / `#222222` / `#111316`，
   机壳描边 `#141414`；强调色按「每个方块自己的强调色」隔离使用
   （`tmp_textures/NETWORK_IO_PALETTE_REPORT.md` §3）。
2. `tmp_textures/NETWORK_IO_TEXTURES.md` §1.1 —— 用户硬规则：
   **16×16、RS 灰色机壳底图 + 本模组强调色、互不共用贴图**
   （四个竖直侧面共用一张是唯一豁免；出处 `docs/DESIGN_DECISIONS_ROUND4.md:3380-3386`）。
3. 像素纪律（`icon/icon_README.md` §3.3/§3.4）：**无平滑渐变、无抗锯齿、无噪点纹理**，
   只用 1px 硬边分层：描边 / 左上高光 / 右下阴影 / 凸台 / 凹槽。
4. 同族参考实现（本脚本按它们的像素 code 手绘，不是另起画风）：
   `collection_cache_side_active.png`、`collection_cache_top_active.png`、
   `sequence_execution_chamber_front_active.png`
   —— 1px 噪声描边 → 左上 1px 高光 → 中灰面板（细噪声）→ 中央近黑凹槽 → 强调色构件。

## 本机强调色（延续「每方块一个强调色」的隔离规则）
- 植物语汇：暗叶 `#2F5C2F`、叶中调 `#3E6B36`（取自 `unit_pattern_manager_front_active.png` 既有绿）
- 电力语汇：能量青柠 `#70E602`（取自 `range_charger_active.png` 既有强调色；本机是发电机 ⇒ 同一「电力」色）
- 发光高光：`#C6FF9A`（active 帧 1px 亮点，与 `#70E602` 同色相提亮）

## 面语义
| 文件 | 用在哪 | 构图 |
| --- | --- | --- |
| `decomposition_generator_side[_active].png` | 模型 N/E/S/W | 机壳凸台 + 中央降解窗（窗内一株植物；active 叶脉发亮 + 窗沿亮点 + 右下指示灯） |
| `decomposition_generator_top[_active].png` | 模型 UP（投放口） | 亮灰顶面 + 中央圆形降解口（四片叶轮；active 叶轮青柠 + 中心发光） |
| `decomposition_generator_bottom[_active].png` | 模型 DOWN（散热/电力输出） | 暗灰底面 + 三道散热筋（active 中筋发亮 = 正在发电） |

运行：`python tools/gen_decomposition_generator_textures.py`
输出：`src/main/resources/assets/rs_create_compat/textures/block/decomposition_generator_*.png`
"""
from __future__ import annotations

import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                   "textures", "block")

SIZE = 16


# ----------------------------------------------------------------------
# 色板（全部取自工程既有像素值）
# ----------------------------------------------------------------------
def rgb(hex_value: int) -> tuple[int, int, int, int]:
    return ((hex_value >> 16) & 0xFF, (hex_value >> 8) & 0xFF, hex_value & 0xFF, 255)


BORDER_NOISE = (rgb(0x141414), rgb(0x1B1B1D), rgb(0x242426), rgb(0x101012))

SIDE_BASE = rgb(0x777777)      # 侧面机壳中灰（工程灰阶 top-3）
SIDE_BASE_ACTIVE = rgb(0x757575)
LIGHT = rgb(0x858585)          # 左上高光（最亮）
LIGHT_MID = rgb(0x7A7A7A)
SHADOW = rgb(0x494949)         # 右下阴影
SHADOW_DARK = rgb(0x2A2A2C)
RECESS = rgb(0x111316)         # 凹槽底（近黑）
RECESS_ACTIVE = rgb(0x0D1410)  # active 时窗底带一点点绿

TOP_BASE = rgb(0x858585)       # 顶面亮灰
TOP_BASE_ACTIVE = rgb(0x8A8A8A)
BOTTOM_BASE = rgb(0x5C5C5C)    # 底面暗灰
BOTTOM_BASE_ACTIVE = rgb(0x565656)
FIN_DARK = rgb(0x222222)       # 散热筋阴面
FIN_LIGHT = rgb(0x6A6A6A)      # 散热筋阳面

LEAF_DARK = rgb(0x2F5C2F)      # 暗叶
LEAF_MID = rgb(0x3E6B36)       # 叶中调
LIME = rgb(0x70E602)           # 能量青柠（range_charger 既有强调色）
LIME_HI = rgb(0xC6FF9A)        # 发光高光


# ----------------------------------------------------------------------
# 1px 硬边工具
# ----------------------------------------------------------------------
def _noise(x: int, y: int, base: tuple[int, int, int, int], amount: int) -> tuple[int, int, int, int]:
    """确定性微噪声（同坐标同结果，可复现；幅度 ≤ amount，不形成噪点纹理）。"""
    d = ((x * 73 + y * 151 + (x ^ y) * 37) % (2 * amount + 1)) - amount
    r = max(0, min(255, base[0] + d))
    g = max(0, min(255, base[1] + d))
    b = max(0, min(255, base[2] + d))
    return (r, g, b, 255)


def _border(img: Image.Image) -> None:
    """描边：整圈 1px 深色（带既有贴图那种轻微噪声，避免一圈死黑）。"""
    px = img.load()
    for i in range(SIZE):
        for (x, y) in ((i, 0), (0, i), (i, SIZE - 1), (SIZE - 1, i)):
            px[x, y] = BORDER_NOISE[(x * 31 + y * 17) % len(BORDER_NOISE)]


def _bevel(img: Image.Image) -> None:
    """左上 1px 高光 + 右下 1px 阴影（工程所有机器贴图的统一机壳 code）。"""
    draw = ImageDraw.Draw(img)
    draw.line([(1, 1), (SIZE - 2, 1)], fill=LIGHT)
    draw.line([(1, 1), (1, SIZE - 2)], fill=LIGHT_MID)
    draw.line([(1, SIZE - 2), (SIZE - 2, SIZE - 2)], fill=SHADOW)
    draw.line([(SIZE - 2, 1), (SIZE - 2, SIZE - 2)], fill=SHADOW)


def _fill(img: Image.Image, x0: int, y0: int, x1: int, y1: int,
          base: tuple[int, int, int, int], amount: int = 4) -> None:
    px = img.load()
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px[x, y] = _noise(x, y, base, amount)


def _new(base: tuple[int, int, int, int], amount: int = 4) -> Image.Image:
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    _fill(img, 0, 0, SIZE - 1, SIZE - 1, base, amount)
    _border(img)
    _bevel(img)
    return img


def _px(img: Image.Image, x: int, y: int, color: tuple[int, int, int, int]) -> None:
    if 0 <= x < SIZE and 0 <= y < SIZE:
        img.load()[x, y] = color


def _hline(img: Image.Image, x0: int, x1: int, y: int, color: tuple[int, int, int, int]) -> None:
    for x in range(x0, x1 + 1):
        _px(img, x, y, color)


def _vline(img: Image.Image, x: int, y0: int, y1: int, color: tuple[int, int, int, int]) -> None:
    for y in range(y0, y1 + 1):
        _px(img, x, y, color)


# （凸台 / 凹槽的通用 helpers 不保留：本机三个面各自把 1px 明暗方向写在自己函数里，
#   比抽成两个只被调用一次的 helper 更易读；下面直接进入三个面的绘制。）


# ----------------------------------------------------------------------
# 面：侧面（机壳 + 中央降解窗）
# ----------------------------------------------------------------------
def side(active: bool) -> Image.Image:
    img = _new(SIDE_BASE_ACTIVE if active else SIDE_BASE)
    # 降解窗：窗沿 (3,3)-(12,12) 只给 1px 凹边，窗内近黑 (4,4)-(11,11)（8×8）
    _hline(img, 3, 12, 3, SHADOW)
    _vline(img, 3, 3, 12, SHADOW)
    _hline(img, 3, 12, 12, SHADOW_DARK)
    _vline(img, 12, 3, 12, SHADOW_DARK)
    _fill(img, 4, 4, 11, 11, RECESS_ACTIVE if active else RECESS, 0)

    leaf = LIME if active else LEAF_DARK
    lmid = LIME_HI if active else LEAF_MID

    # 窗内：一株植物（Y 形茎 + 左右斜叶 + 底部残渣），全部 1px 硬边
    _vline(img, 7, 6, 9, leaf)                          # 茎
    _px(img, 8, 8, leaf)                                # 茎加宽 1px
    _px(img, 7, 5, lmid)                                # 茎尖
    for (x, y) in ((6, 6), (5, 7)):                     # 左叶（斜向上）
        _px(img, x, y, leaf)
    for (x, y) in ((9, 6), (10, 7)):                    # 右叶（斜向上）
        _px(img, x, y, lmid if active else LEAF_MID)
    _px(img, 5, 9, lmid if active else LEAF_MID)        # 下部小叶
    _px(img, 10, 9, leaf if active else LEAF_MID)
    _hline(img, 5, 10, 10, FIN_DARK if active else rgb(0x1E1A14))  # 降解残渣（暗土）

    if active:
        # 窗沿 2 个亮点 + 右下 1px 能量指示灯：一眼看出「在工作」
        _px(img, 4, 4, LIME_HI)
        _px(img, 11, 4, LIME_HI)
        _px(img, 13, 13, LIME)
    return img


# ----------------------------------------------------------------------
# 面：顶面（亮灰 + 中央圆形降解口）
# ----------------------------------------------------------------------
def top(active: bool) -> Image.Image:
    img = _new(TOP_BASE_ACTIVE if active else TOP_BASE)

    # 圆形降解口：亮灰顶面上直接挖圆（不铺大方台，否则整面变暗，与同族参考不符）
    cx, cy = 7.5, 7.5
    lip, hole = [], []
    for y in range(2, 14):
        for x in range(2, 14):
            d = ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5
            if 3.4 < d <= 4.3:
                lip.append((x, y))
            elif d <= 3.4:
                hole.append((x, y))
    for (x, y) in lip:
        _px(img, x, y, SHADOW)
    for (x, y) in hole:
        _px(img, x, y, RECESS_ACTIVE if active else RECESS)

    blade = LIME if active else LEAF_MID
    blade_hi = LIME_HI if active else LEAF_DARK
    # 四片叶轮：十字 1px 硬边（从圆心向外）
    for (x, y) in ((7, 4), (8, 5), (5, 7), (4, 8), (7, 11), (8, 10), (11, 7), (10, 8)):
        _px(img, x, y, blade)
    # 中心 2×2
    for (x, y) in ((7, 7), (8, 7), (7, 8), (8, 8)):
        _px(img, x, y, LIME_HI if active else LEAF_DARK)
    if active:
        for (x, y) in ((6, 6), (9, 6), (6, 9), (9, 9)):
            _px(img, x, y, blade_hi)

    # 四角螺栓（同族 collection_cache_top 的语汇：2×2 暗块 + 1px 亮边）
    for (bx, by) in ((3, 3), (11, 3), (3, 11), (11, 11)):
        _px(img, bx, by, FIN_DARK)
        _px(img, bx + 1, by, FIN_DARK)
        _px(img, bx, by + 1, FIN_DARK)
        _px(img, bx + 1, by + 1, SHADOW)
        _px(img, bx, by + 2, LIGHT_MID)
        _px(img, bx + 2, by, LIGHT_MID)
    return img


# ----------------------------------------------------------------------
# 面：底面（暗灰 + 三道散热筋）
# ----------------------------------------------------------------------
def bottom(active: bool) -> Image.Image:
    img = _new(BOTTOM_BASE_ACTIVE if active else BOTTOM_BASE)

    # 三道散热筋：阴面 1px + 阳面 1px（比底面亮一档，读起来是凸筋）
    fins = (4, 7, 10)
    for i, y in enumerate(fins):
        _hline(img, 3, 12, y, FIN_DARK)
        _hline(img, 3, 12, y + 1, FIN_LIGHT)
        if active and i == 1:
            # 中筋发亮 = 正在发电（只给中间那道强调色，避免整面发光）
            _hline(img, 5, 10, y, LIME)
            _hline(img, 5, 10, y + 1, LIME_HI)
    if active:
        _px(img, 3, 13, LIME_HI)
        _px(img, 12, 13, LIME)
    return img


# ----------------------------------------------------------------------
# 输出
# ----------------------------------------------------------------------
def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    targets = {
        "decomposition_generator_side_inactive.png": side(False),
        "decomposition_generator_side_active.png": side(True),
        "decomposition_generator_top_inactive.png": top(False),
        "decomposition_generator_top_active.png": top(True),
        "decomposition_generator_bottom_inactive.png": bottom(False),
        "decomposition_generator_bottom_active.png": bottom(True),
    }
    for name, img in targets.items():
        path = os.path.join(OUT, name)
        img.save(path, "PNG")
        print("%-46s 16x16 RGBA  唯一色 %3d" % (name, len(img.getcolors(4096))))
    print("TEXTURE_OK %d files" % len(targets))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
