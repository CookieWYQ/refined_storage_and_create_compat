#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成「单元样板管理舱」界面**独立**的背景贴图（不再与任何方块贴图共用）。

产物：src/main/resources/assets/rs_create_compat/textures/gui/unit_pattern_manager.png（256×256）

为什么要独立一张：
   此前该界面直接引用 `refinedstorage:textures/gui/autocrafter_manager.png`，而本模组的
   `unit_pattern_manager_*` **方块**贴图又是由 RS 的 autocrafter_manager **方块**贴图派生
   （见 tools/gen_unit_manager_resources.py），于是界面背景看起来和方块侧面「一模一样」。
   这里以 RS 的界面贴图为**几何底稿**（保证拉伸分区 19/18/18、底部 99 的像素位置完全不变），
   但重绘为**本模组专属**的暖色调，并把它烘焙的搜索框凹槽挪到本界面搜索框的实际位置。

几何（必须与 Java 常量一一对应，SRC = 精灵坐标；Menu = 精灵 + 1）：
    拉伸分区：顶行 v 0..18（19px）、循环行 v 19..36 / 37..54、尾行 v 55..72、底部 v 73..171（99px）
    文字输入凹槽：SRC (93,5) 76×12   ←→ 搜索框控件 Menu (95,7) 67×9（= RS 自动合成管理器同一位置）
    底部玩家背包槽框（烘焙，勿动）：x 7/25/…/151，行 y 89/107/125（背包 3 行）、y 147（快捷栏）

用法：python tools/gen_unit_manager_gui_bg.py   （幂等：重复执行结果一致）
"""
from __future__ import annotations

import os
from pathlib import Path

from PIL import Image
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = Path(__file__).resolve().parent.parent
SRC = (ROOT / "local_src" / "external" / "RefinedStorage" / "refinedstorage-common"
       / "src" / "main" / "resources" / "assets" / "refinedstorage" / "textures" / "gui"
       / "autocrafter_manager.png")
DST = (ROOT / "src" / "main" / "resources" / "assets" / "rs_create_compat"
       / "textures" / "gui" / "unit_pattern_manager.png")

# ---- 面板配色（与 RS 同明度，不暖色化，避免背景泛黄）----
BASE = (198, 198, 198)      # 面板底色
DARK = (85, 85, 85)         # 凹槽暗边
LIGHT = (255, 255, 255)     # 凹槽亮边
RECESS = (139, 139, 139)    # 凹槽底
TINT = (1.00, 1.00, 1.00)   # 不做暖色化：保持与 RS 底稿完全一致的 neutral 灰

# ---- 被 RS 烘焙、需要抹掉的原搜索框凹槽（精灵坐标）----
RS_RECESS = (93, 5, 76, 12)
# ---- 本界面旧版的搜索框凹槽（精灵坐标）：本轮右移后必须抹回面板底色，否则会残留一圈空凹槽 ----
OLD_RECESS = (71, 5, 56, 12)
# ---- 本界面的搜索框凹槽（精灵坐标，= Menu (95,7) 67×9 的 1px 外沿）----
# 与 RS 自动合成管理器**同一位置**（用户要求「移到和自动合成管理器一样的位置」）：
# RS 的凹槽就烘焙在 SRC (93,5) 76×12，因此这里直接用同一矩形，界面观感与 RS 完全一致。
OUR_RECESS = (93, 5, 76, 12)


def tint(image: Image.Image) -> Image.Image:
    """对不透明像素做暖色化（保留 alpha；纯黑外框不参与，避免边框泛色）。"""
    pixels = image.load()
    for y in range(image.height):
        for x in range(image.width):
            r, g, b, a = pixels[x, y]
            if a == 0 or (r, g, b) == (0, 0, 0):
                continue
            pixels[x, y] = (
                min(255, int(round(r * TINT[0]))),
                min(255, int(round(g * TINT[1]))),
                min(255, int(round(b * TINT[2]))),
                a,
            )
    return image


def fill(image: Image.Image, x: int, y: int, w: int, h: int, color: tuple[int, int, int]) -> None:
    pixels = image.load()
    for yy in range(y, y + h):
        for xx in range(x, x + w):
            pixels[xx, yy] = (*color, 255)


def bake_recess(image: Image.Image, x: int, y: int, w: int, h: int) -> None:
    """按 RS 的凹槽样式烘焙一个输入框凹槽（配色与 RS 完全一致，位置由调用方给定）。"""
    fill(image, x, y, w, h, RECESS)
    fill(image, x, y, w - 1, 1, DARK)              # 上边
    fill(image, x, y + 1, 1, h - 1, DARK)          # 左边
    fill(image, x + 1, y + h - 1, w - 1, 1, LIGHT)  # 下边
    fill(image, x + w - 1, y + 1, 1, h - 1, LIGHT)  # 右边
    image.putpixel((x, y + h - 1), (*RECESS, 255))  # 左下角留凹槽底（与 RS 一致）


def main() -> None:
    if not SRC.is_file():
        raise SystemExit("找不到 RS 底稿贴图：%s" % SRC)
    image = Image.open(SRC).convert("RGBA")
    if image.size != (256, 256):
        raise SystemExit("RS 底稿尺寸异常：%s（期望 256×256）" % (image.size,))

    # ① 抹掉 RS 原凹槽与「本界面旧版凹槽」的位置（都回填面板底色），② 再烘焙本界面凹槽。
    # 顺序很关键：先抹两处，再烘焙目标位置 —— 于是无论旧版凹槽在不在，结果都是同一张图（幂等）。
    fill(image, *RS_RECESS, BASE)
    fill(image, *OLD_RECESS, BASE)
    bake_recess(image, *OUR_RECESS)
    # ③ 保持 neutral 灰：不暖色化，避免背景泛黄（TINT = 1/1/1 时 tint 为恒等映射）
    tint(image)

    DST.parent.mkdir(parents=True, exist_ok=True)
    image.save(DST)
    print("[ok] %s (%dx%d) neutral 灰（无暖色化），凹槽 精灵 %s"
          % (os.path.relpath(DST, ROOT), image.width, image.height, OUR_RECESS))


if __name__ == "__main__":
    main()
