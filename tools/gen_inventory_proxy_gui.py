#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""重新生成容器代理器界面背景图 inventory_proxy.png（第 97 轮 B3）。

旧图 224×248 的问题（docs/ROUND97_USER_FEEDBACK.md 0.3 节）：
  · 玩家背包 3 行在 y=172/190/208、快捷栏在 y=230 —— 快捷栏格子底边正好压在贴图最底边框上，
    看起来像被切掉一截；
  · 六面按钮（底边 y=126）和玩家背包（y=171 起是格子顶边）之间空了 45px，正是用户说的「上面这么大空位」。

新图 224×222（与 InventoryProxyScreen#imageHeight 逐字一致，否则 blit 会拉伸/裁切）：
  · 顶部区域（标题 / 代理对象槽 / 按钮底板 x104..219,y16..129）原样保留 —— 界面那一半没动；
  · 玩家背包行首菜单 y=136 / 154 / 172（行距 18）、快捷栏菜单 y=196，格子整格 18×18 沿用旧图样式；
  · 底部补一条外框（y=221 深灰、y=220 内高光），快捷栏底 214 ⇒ 底部留白 8px，四边闭合。

**为什么槽位格子用「程序化规则」而不是从旧图裁一块贴上来**：
旧图里每个 18×18 格子都是同一套像素（1px 深灰左/上边、139 底色、右列与底行 255 高光），
把这套规则写死在这里 ⇒ 本脚本**不依赖旧图**就能完整复现新图（旧图被覆盖后仍可重跑，
脚本可以随时重新生成同一张图）。脚本会在写盘**之前**顺手做一次「旧图比对」：
若当前文件还是 224×248，就把 y∈[0,135) 与规则生成结果逐像素对比，证明「顶部整块搬」没有走样。

用法：
    /d/mods/pyenv-staritem/scripts/python.exe tools/gen_inventory_proxy_gui.py
输出：写入 src/main/resources/assets/rs_create_compat/textures/gui/inventory_proxy.png，
      并打印尺寸、每格槽位左上角坐标、调色板、边框闭合自检、旧图比对结果、新图 sha256。
"""

from __future__ import annotations

import hashlib
import io
import os
import sys

from PIL import Image

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                      "textures", "gui", "inventory_proxy.png")

# ----------------------------------------------------------------- 调色板（沿用旧图 5 色）
FRAME = 55      # 外框深灰
HIGHLIGHT = 255  # 内侧高光（左上收边 + 底部收边）
PANEL = 198      # 面板浅灰
PLATE = 176      # 按钮区底板
SLOT_BASE = 139  # 槽位底色

# 第 97 轮 B2 几何（与 InventoryProxyScreen / InventoryProxyMenu 逐字一致）
IMAGE_W = 224
IMAGE_H = 222
PLATE_RECT = (104, 16, 219, 129)     # 按钮底板：x104..219、y16..129（含端点）
PROXY_SLOT = (10, 22)                # 代理对象槽菜单坐标（不变）
INV_SLOT_ORIGIN = (26, 136)          # 玩家背包 9×3 菜单坐标（行首 y=136，行距 18）
HOTBAR_ORIGIN = (26, 196)            # 快捷栏菜单坐标
SLOT = 18                            # 单格边长（SlotItemHandler 间距）


def slot_cells():
    """返回 [(名字, 菜单 x, 菜单 y)]：菜单坐标 → 贴图格子左上角是 (x-1, y-1)。"""
    cells = [("proxy_object", PROXY_SLOT[0], PROXY_SLOT[1])]
    for row in range(3):
        for col in range(9):
            cells.append(("player_inventory[%d][%d]" % (row, col),
                          INV_SLOT_ORIGIN[0] + col * SLOT, INV_SLOT_ORIGIN[1] + row * SLOT))
    for col in range(9):
        cells.append(("hotbar[%d]" % col, HOTBAR_ORIGIN[0] + col * SLOT, HOTBAR_ORIGIN[1]))
    return cells


def new_canvas():
    """生成底图：外框 + 内高光 + 面板 + 按钮底板（不含槽位格子）。"""
    img = Image.new("RGBA", (IMAGE_W, IMAGE_H), (PANEL, PANEL, PANEL, 255))
    px = img.load()

    def put(x, y, value):
        px[x, y] = (value, value, value, 255)

    # 左外框 x=0 / 右外框 x=222,223 / 顶外框 y=0 / 底外框 y=221
    for y in range(IMAGE_H):
        put(0, y, FRAME)
        put(222, y, FRAME)
        put(223, y, FRAME)
        if 1 <= y <= IMAGE_H - 2:
            put(1, y, HIGHLIGHT)     # 左侧内高光整列
    for x in range(IMAGE_W):
        put(x, 0, FRAME)
    for x in range(1, 222):
        put(x, 1, HIGHLIGHT)         # 顶部内高光整行
        put(x, IMAGE_H - 2, HIGHLIGHT)  # 底部内高光整行（y=220）
    put(0, IMAGE_H - 1, FRAME)
    for x in range(IMAGE_W):
        put(x, IMAGE_H - 1, FRAME)   # 底部外框整行（y=221）

    # 按钮区底板（顶部区域整块与旧图相同）
    for y in range(PLATE_RECT[1], PLATE_RECT[3] + 1):
        for x in range(PLATE_RECT[0], PLATE_RECT[2] + 1):
            put(x, y, PLATE)
    return img


def draw_cell(img, cell_x, cell_y):
    """画一格 18×18 槽位（与旧图 (25,171) 起那一格逐像素一致）。

    规则（旧图实测）：第 0 行 x0..16 深灰、x17 高光；第 1..16 行 x0 深灰、x1..16 槽底、x17 高光；
    第 17 行整行高光。
    """
    px = img.load()
    for dy in range(SLOT):
        for dx in range(SLOT):
            if dy == SLOT - 1:
                value = HIGHLIGHT
            elif dy == 0:
                value = FRAME if dx <= SLOT - 2 else HIGHLIGHT
            elif dx == 0:
                value = FRAME
            elif dx <= SLOT - 2:
                value = SLOT_BASE
            else:
                value = HIGHLIGHT
            px[cell_x + dx, cell_y + dy] = (value, value, value, 255)


def build():
    img = new_canvas()
    for _name, menu_x, menu_y in slot_cells():
        # 原版把 18×18 的槽位背景画在 (slot.x - 1, slot.y - 1)
        draw_cell(img, menu_x - 1, menu_y - 1)
    return img


def border_report(img):
    """边框闭合自检：四边外框 1px 深灰、左侧/顶部/底部 1px 高光、面板被完整包起来。"""
    px = img.load()
    problems = []
    for x in range(IMAGE_W):
        if px[x, 0][0] != FRAME:
            problems.append("顶外框 y=0 x=%d" % x)
            break
    for x in range(IMAGE_W):
        if px[x, IMAGE_H - 1][0] != FRAME:
            problems.append("底外框 y=%d x=%d" % (IMAGE_H - 1, x))
            break
    for y in range(IMAGE_H):
        if px[0, y][0] != FRAME:
            problems.append("左外框 x=0 y=%d" % y)
            break
        if px[IMAGE_W - 2, y][0] != FRAME or px[IMAGE_W - 1, y][0] != FRAME:
            problems.append("右外框 x=222/223 y=%d" % y)
            break
    for y in range(1, IMAGE_H - 1):
        if px[1, y][0] != HIGHLIGHT:
            problems.append("左侧内高光 x=1 y=%d" % y)
            break
    for (label, y) in (("顶部内高光", 1), ("底部内高光", IMAGE_H - 2)):
        for x in range(1, IMAGE_W - 2):
            if px[x, y][0] != HIGHLIGHT:
                problems.append("%s y=%d x=%d" % (label, y, x))
                break
    return problems


def palette_report(img):
    seen = sorted({img.getpixel((x, y)) for y in range(IMAGE_H) for x in range(IMAGE_W)})
    return seen


def legacy_compare(old_path):
    """若现有文件还是旧的 224×248，就证明「顶部整块搬」逐像素无差异。"""
    if not os.path.isfile(old_path):
        return "（没有旧图，跳过比对）"
    old = Image.open(old_path).convert("RGBA")
    if old.size != (224, 248):
        return "（现有 %dx%d 不是旧版 224x248，跳过比对）" % old.size
    new = build()
    diff = 0
    first = None
    for y in range(135):
        for x in range(IMAGE_W):
            if new.getpixel((x, y)) != old.getpixel((x, y)):
                diff += 1
                if first is None:
                    first = (x, y)
    return "顶部 y=0..134 与旧图逐像素比对：差异 %d 像素%s" % (
        diff, "" if diff == 0 else "，首个 (x=%d,y=%d)" % first)


def main():
    old_shadow = None
    if os.path.isfile(TARGET):
        old_shadow = Image.open(TARGET).convert("RGBA").copy()

    img = build()
    assert img.size == (IMAGE_W, IMAGE_H)

    print("=== 容器代理器界面背景图生成（第 97 轮 B3） ===")
    print("目标文件: %s" % TARGET)
    print("尺寸: %d x %d  (要求 == Screen.imageWidth x imageHeight)" % img.size)
    print("调色板: %s" % ", ".join("%d" % c[0] for c in palette_report(img)))
    print("按钮底板: x%d..%d, y%d..%d"
          % (PLATE_RECT[0], PLATE_RECT[2], PLATE_RECT[1], PLATE_RECT[3]))
    print("")
    print("槽位格子（贴图左上角坐标 = 菜单坐标 - 1；整格 18×18）:")
    for name, menu_x, menu_y in slot_cells():
        print("  %-22s 菜单(%3d,%3d) -> 贴图(%3d,%3d)  [%d..%d, %d..%d]"
              % (name, menu_x, menu_y, menu_x - 1, menu_y - 1,
                 menu_x - 1, menu_x - 1 + SLOT - 1, menu_y - 1, menu_y - 1 + SLOT - 1))
    print("")

    border_problems = border_report(img)
    print("边框闭合自检: %s" % ("PASS（四边外框 1px 深灰 + 左/顶/底内高光 1px）"
                              if not border_problems else "FAIL " + "; ".join(border_problems)))
    print("旧图比对: %s" % (legacy_compare(TARGET) if old_shadow is not None else "（没有旧图，跳过比对）"))
    print("底部留白: 快捷栏格底 y=%d -> 面板底 y=%d，留白 %d px"
          % (HOTBAR_ORIGIN[1] + SLOT - 1, IMAGE_H - 1, IMAGE_H - 1 - (HOTBAR_ORIGIN[1] + SLOT - 1)))

    if border_problems:
        print("[result] 生成失败：边框不闭合")
        return 1

    os.makedirs(os.path.dirname(TARGET), exist_ok=True)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    data = buf.getvalue()
    with open(TARGET, "wb") as handle:
        handle.write(data)
    print("已写入 %d 字节；sha256=%s" % (len(data), hashlib.sha256(data).hexdigest()[:16]))
    print("[result] OK：%dx%d，槽位 %d 格，边框闭合自检通过" % (IMAGE_W, IMAGE_H, len(slot_cells())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
