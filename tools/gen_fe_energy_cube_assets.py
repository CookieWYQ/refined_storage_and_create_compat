#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 99 轮新增内容（FE 能量立方 + 电动飞行背包）的**全部资源与美术**，程序化生成（幂等）。

为什么要有这个脚本（而不是手摆像素）：
  · 用户硬规则「贴图不许一图多用」：本方块的三张面贴图必须各自独立生成（`_side` 允许被四个
    竖直侧面共用），同一张图若在两个不同用途里出现，`tools/audit_shared_textures.py` 会判负；
  · 贴图是**契约**：`FEEnergyCubeScreen` 用第 108 轮起统一的 RS `ProgressWidget` 电量条
    （16×58 @ (14,19)，外框 18×60 @ (13,18)，见 `client/widget/RsccEnergyBar`）**整条盖住**
    这里的 (14,18) 14×60 凹槽 —— 凹槽从此只是「被盖子压住的底板」，界面不再自绘填充。
    这个坐标一旦和 PNG 对不上（比如凹槽比外框大），进游戏就会露出 1px 旧边框——所以凹槽位置
    必须是脚本里的常量、写完盘后逐像素回读断言，而不是靠肉眼看；
  · GUI 贴图尺寸必须是 **176×166 = Screen.imageWidth × imageHeight（整图就是面板）**。
    配套的 Java 必须用 **9 参 `GuiGraphics#blit(rl, x, y, u, v, w, h, texW, texH)`**：1.21.1 的
    **7 参**重载内部固定传 `256, 256`（已从 `net/minecraft/client/gui/GuiGraphics.java` 源码核实），
    拿 176×166 的图配 7 参会只采样左上 121×108 像素再拉伸，右/下边框被裁掉、凹槽整体错位。
    仓库既有界面（`RangeChargerScreen#renderBg`）就是 9 参写法。本脚本只负责贴图；那条 Java
    由 `FEEnergyCubeScreen` 的 owner 负责（Lead 已裁决按 9 参修）。
  · 一个方块 / 一个物品要同时落地 9 类文件（blockstate / 3 张面贴图 / 2 个模型 / 掉落 / 配方 /
    标签 / GUI），漏一个就是「能造出来但看不见」或「放下是紫黑方块」；
  · **语言文案也是契约**：正文 6 行的可用宽度由 `FEEnergyCubeScreen.TEXT_MAX_W = 136` 定死，
    超出就被 `trimToWidth` 截断成半句话；而且 `%s` 的个数必须等于 Java 传的实参个数（少一个就是
    `String.format` 缺参）。所以本脚本直接读 `lang/zh_cn.json` / `lang/en_us.json` 逐键断言
    「6 行 × 中英 ≤ 136px」和「占位符个数 == 实参个数」，并用 Java 源码反查契约表是否漂移。
  · 脚本自检覆盖：尺寸、模式、边框闭合、凹槽像素区间、**文字区 (32,20)..(168,84) 零装饰纯色**、
    物品图标可辨识度（不是纯色块）、素材互不重复（md5）、**中英文正文宽度 ≤ 136px + 占位符个数**、
    既有标签条目一条不少、range_charger 配方只改一个原料、幂等（连跑两次字节一致）。

产出（相对仓库根）：
  src/main/resources/assets/rs_create_compat/textures/block/fe_energy_cube_side.png    16×16
  src/main/resources/assets/rs_create_compat/textures/block/fe_energy_cube_top.png     16×16
  src/main/resources/assets/rs_create_compat/textures/block/fe_energy_cube_bottom.png  16×16
  src/main/resources/assets/rs_create_compat/textures/item/fe_energy_cube.png          16×16
  src/main/resources/assets/rs_create_compat/textures/item/electric_jetpack.png        16×16
  src/main/resources/assets/rs_create_compat/textures/gui/fe_energy_cube.png           176×166
  src/main/resources/assets/rs_create_compat/blockstates/fe_energy_cube.json           单变体
  src/main/resources/assets/rs_create_compat/models/block/fe_energy_cube.json          cube_bottom_top
  src/main/resources/assets/rs_create_compat/models/item/fe_energy_cube.json           parent = block 模型
  src/main/resources/assets/rs_create_compat/models/item/electric_jetpack.json         item/generated
  src/main/resources/data/rs_create_compat/loot_table/blocks/fe_energy_cube.json       自掉
  src/main/resources/data/rs_create_compat/recipe/fe_energy_cube.json                  新配方
  src/main/resources/data/rs_create_compat/recipe/electric_jetpack.json                新配方
  src/main/resources/data/rs_create_compat/recipe/range_charger.json                   **改**：中心铁块 → FE 能量立方
  src/main/resources/data/minecraft/tags/block/mineable/pickaxe.json                    追加 fe_energy_cube
  src/main/resources/data/curios/tags/item/back.json                                   electric_jetpack
  src/main/resources/data/curios/tags/item/chest.json                                  electric_jetpack

用法：
    D:\\mods\\pyenv-staritem\\scripts\\python.exe tools/gen_fe_energy_cube_assets.py
退出码：0 = 全部生成 + 全部自检通过；1 = 任一自检失败（**失败时不写任何文件**）。
"""

from __future__ import annotations

import hashlib
import io
import json
import os
import re
import sys

from PIL import Image

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat")
DATA = os.path.join(ROOT, "src", "main", "resources", "data")
MODID = "rs_create_compat"

# ======================================================================================
# 一、调色板
# ======================================================================================
# 机壳照抄既有机器方块（`advanced_quantity_keeper_*` / `range_charger_*` 用的同一张 RS 参考底图）。
# 为什么把底图**内嵌**成像素表：`tools/gen_block_face_split.py` 依赖仓库外的临时目录
# `tmp_rs_ref2/block/side.png`；内嵌之后本脚本在任何一份干净检出上都能重跑，产物字节完全一致。
CASING_ROWS = [
    "AAAABBAABBACABBB",
    "CDDDDDEDDEDEEFFB",
    "GDHIJJKILMJINOFB",
    "GDPQIKILMJINNNRB",
    "CSQIIILMJITILTEC",
    "CDJUILLNNNIIIETB",
    "BDJUUULINILITVTG",
    "GDQUUWINMLMEQLXB",
    "YSQUWIJMMMJNNZTA",
    "CDUWIJNLMJNUIZXB",
    "CSUUJNIJNNJIMJaC",
    "GSUNQINNNJIMbcaB",
    "GNdQQQNNUIMbcUXA",
    "CNHIQINIJMNcIeaB",
    "CZPPPaaPPaaPaaaY",
    "BABfgBYfgBYhBiYY",
]
CASING_PAL = {
    "A": 0x4E4E4E, "B": 0x494949, "C": 0x4B4B4B, "D": 0x858585, "E": 0x7B7B7B,
    "F": 0x737373, "G": 0x484848, "H": 0x4F4F4F, "I": 0x727272, "J": 0x7A7A7A,
    "K": 0x707070, "L": 0x787878, "M": 0x7E7E7E, "N": 0x777777, "O": 0x595959,
    "P": 0x686868, "Q": 0x6F6F6F, "R": 0x706F6F, "S": 0x7D7D7D, "T": 0x747474,
    "U": 0x757575, "V": 0x767676, "W": 0x717171, "X": 0x6B6B6B, "Y": 0x454545,
    "Z": 0x7C7C7C, "a": 0x626262, "b": 0x838383, "c": 0x7F7F7F, "d": 0x646464,
    "e": 0x535353, "f": 0x4C4C4C, "g": 0x4A4A4A, "h": 0x474747, "i": 0x464646,
}
# 能量强调色：青绿（与归流缓存仓 #00C8AA 同族但更亮，避免「两台机器一个色」）+ 琥珀（满电刻度）
INK = (0x11, 0x13, 0x16, 255)          # RS 贴图里已有的深凹槽色（既有 top/bottom 的格栅就是它）
ACCENT_HI = (0x7F, 0xEF, 0xE0, 255)    # 亮青（每格电量条的高光行）
ACCENT = (0x18, 0xC8, 0xAA, 255)       # 青绿主色
ACCENT_DEEP = (0x08, 0x6B, 0x5E, 255)  # 暗青（每格电量条的底行 / 底面接缝）
ACCENT_GLASS = (0x0A, 0x23, 0x20, 255)  # 电量窗口的玻璃底
AMBER = (0xFF, 0xA8, 0x00, 255)        # 琥珀（顶面电池正极 / 电量窗口上沿刻度）
GRID_LIGHT = (0x85, 0x85, 0x85, 255)   # 底面散热格栅的高光行（照抄既有 bottom 模板）

# GUI 五色（外框 / 高光 / 面板照抄 `gen_inventory_proxy_gui.py`，保证与既有界面同观感）
GUI_FRAME = 55
GUI_HIGHLIGHT = 255
GUI_PANEL = 198
# 电量条凹槽的三色：由 FEEnergyCubeScreen 的实现方实测 RS 原生能量条后对齐 ——
# 填充体 #0D8FA5 / 顶部高光 #18B6D1，凹槽本身要比填充暗，边框接近黑，且「上/左暗、下/右亮」。
GUI_GROOVE_BORDER = (0x00, 0x00, 0x00, 255)   # 凹槽上 / 左 1px 边框（往里凹）
GUI_GROOVE_BEVEL = (0x3A, 0x4A, 0x4E, 255)    # 凹槽下 / 右 1px 受光亮边
GUI_GROOVE_BASE = (0x1B, 0x2A, 0x2E, 255)     # 凹槽底（电量条的「空」部分；Screen 在这上面填）

# ======================================================================================
# 二、几何契约（与 FEEnergyCubeScreen / Menu 对齐；改这里之前先改 Java）
# ======================================================================================
GUI_W, GUI_H = 176, 166
BAR_X, BAR_Y, BAR_W, BAR_H = 14, 18, 14, 60     # 竖向电量条凹槽：左上角 + 尺寸（契约，别动）
TITLE_RULE_Y = 16                                 # 标题下方的 1px 分隔线

# ---- 文字区契约（**由 FEEnergyCubeScreen 定死，本脚本只能反着给它让出干净区**）----
# Java 侧：TEXT_X = 32；6 行正文行顶 y = 20/31/42/53/64/75（行距 11、字高 9 ⇒ 最后一行到 75+9 = 84）；
# TEXT_MAX_W = 176 - TEXT_X - 8 = 136 ⇒ 文字最远画到 x = 32+136-1 = 167，168 是它自己留的右边距。
# 因此 x = 32..168、y = 20..84 这块**必须是纯色、零装饰**（无边框 / 无暗纹 / 无花纹）：超长串会被
# `trimToWidth` 按像素截断，英文更容易顶满，任何一条暗线都会从字缝里透出来。
TEXT_X = 32
TEXT_MAX_W = GUI_W - TEXT_X - 8              # = 136（== FEEnergyCubeScreen.TEXT_MAX_W）
TEXT_ROW_Y = (20, 31, 42, 53, 64, 75)        # == ROW_1_Y..ROW_6_Y
TEXT_BOTTOM = TEXT_ROW_Y[-1] + 9             # = 84：最后一行 75 起、字高 9

# 「读数板」：把电量条 + 6 行正文圈成一块比面板略深的底（底 192 比面板 198 深一档，
# 明暗边只差 12~14 灰度 —— 不要做成按钮）。**板的内框必须整块落在文字区之外**：
# 左 12 < 32、上 17 < 20、右 170 > 168、下 86 > 84，四边各留 ≥ 1px 干净缝，
# 这样文字区内部是纯色底、没有任何边框穿插，而电量条凹槽整块仍在板内。
PLATE_X0, PLATE_Y0, PLATE_X1, PLATE_Y1 = 12, 17, 170, 86
GUI_PLATE = 192        # 读数板底
GUI_PLATE_SHADOW = 178  # 读数板上 / 左内阴影
GUI_PLATE_SHINE = 206   # 读数板下 / 右受光边

# ======================================================================================
# 三、自检框架
# ======================================================================================
PROBLEMS = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    print("%s %s%s" % ("[OK  ]" if ok else "[FAIL]", name,
                       "" if ok or not detail else " | " + detail))
    if not ok:
        PROBLEMS.append(name + ((" | " + detail) if detail else ""))
    return ok


# ======================================================================================
# 四、像素绘制工具
# ======================================================================================
def casing():
    """16×16 RGBA 机壳底图（由内嵌像素表还原，返回 PIL Image）。"""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    for y in range(16):
        for x in range(16):
            value = CASING_PAL[CASING_ROWS[y][x]]
            px[x, y] = ((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF, 255)
    return img


def put(px, x, y, color):
    if 0 <= x < 16 and 0 <= y < 16:
        px[x, y] = color


def rect(px, x0, y0, x1, y1, color):
    """闭区间填充矩形（含端点）。"""
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            put(px, x, y, color)


def block_side():
    """侧面：机壳 + 中央竖向电量窗口（三段电量格 + 琥珀满电刻度）。

    为什么是「竖向窗口」：FE 立方是竖直站立的电池，侧面一条从上到下的电量窗口在方块上
    一眼可辨；同时它和顶面的「电池极头」、底面的「散热格栅」形成三个不同面，满足
    「贴图不许一图多用」。
    """
    img = casing()
    px = img.load()
    # 窗口外框（深凹槽）：x=4..11、y=1..13
    for x in range(4, 12):
        put(px, x, 1, INK)
        put(px, x, 13, INK)
    for y in range(1, 14):
        put(px, 4, y, INK)
        put(px, 11, y, INK)
    # 窗口玻璃底
    rect(px, 5, 2, 10, 12, ACCENT_GLASS)
    # 三段电量格（每格 3 行：高光 / 主色 / 暗色；格间留 1 行玻璃底当隔条）
    for top in (2, 6, 10):
        rect(px, 5, top, 10, top, ACCENT_HI)
        rect(px, 5, top + 1, 10, top + 1, ACCENT)
        rect(px, 5, top + 2, 10, top + 2, ACCENT_DEEP)
    # 窗口上沿的琥珀「满电」刻度（2px，居中，避免与上边框混在一起）
    put(px, 7, 1, AMBER)
    put(px, 8, 1, AMBER)
    return img


def block_top():
    """顶面：机壳 + 四角螺钉 + 中央「电池极头」（电池轮廓 + 正极凸点 + 三道电量条）。"""
    img = casing()
    px = img.load()
    # 四角螺钉（与既有 gen_block_face_split.top_tex 同款：深色一点 + 相邻深色一点）
    for (x, y) in ((3, 3), (12, 3), (3, 12), (12, 12)):
        put(px, x, y, INK)
        put(px, x + (1 if x == 3 else -1), y, INK)
    # 电池轮廓：x=4..11、y=5..11（外框 INK，内部深灰板）
    for x in range(4, 12):
        put(px, x, 5, INK)
        put(px, x, 11, INK)
    for y in range(5, 12):
        put(px, 4, y, INK)
        put(px, 11, y, INK)
    rect(px, 5, 6, 10, 10, (0x2A, 0x2E, 0x33, 255))
    # 正极凸头（琥珀，露在电池上沿）
    put(px, 7, 4, AMBER)
    put(px, 8, 4, AMBER)
    put(px, 7, 5, AMBER)
    put(px, 8, 5, AMBER)
    # 三道电量条（竖向），表示这是电池的极面
    for x in (5, 7, 9):
        put(px, x, 6, ACCENT_HI)
        rect(px, x, 7, x, 9, ACCENT)
        put(px, x, 10, ACCENT_DEEP)
    return img


def block_bottom():
    """底面：机壳 + 三道散热格栅 + 一道青绿输出接缝（不发光，与顶面一眼区分）。"""
    img = casing()
    px = img.load()
    for sy in (4, 7, 10):
        for x in range(3, 13):
            put(px, x, sy, INK)
            put(px, x, sy + 1, GRID_LIGHT)
    # 底缘输出接缝（青绿；底面不画亮条，避免和顶面极头混淆）
    for x in range(4, 12):
        put(px, x, 13, ACCENT_DEEP)
    return img


def _in_poly(points, x, y):
    """射线法：像素中心 (x+0.5, y+0.5) 是否落在多边形内。"""
    px, py = x + 0.5, y + 0.5
    inside = False
    count = len(points)
    for i in range(count):
        x0, y0 = points[i]
        x1, y1 = points[(i + 1) % count]
        if (y0 > py) != (y1 > py):
            cross = x0 + (py - y0) * (x1 - x0) / (y1 - y0)
            if cross > px:
                inside = not inside
    return inside


def item_fe_energy_cube():
    """物品图标：等轴（iso）方块 —— 亮顶面 + 琥珀极耳「＋」、左面青绿电量窗、右面散热格栅。

    为什么画等轴而不是直接拿方块贴图拼：16×16 物品图标要在物品栏里一眼认出「这是一块电池
    方块」；为了让 16px 下仍像**立方体**（而不是一块石头），三个面用了明显不同的明度，
    并且把顶面与两个侧面的交界处压一道 1px 深色棱线。
    """
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    top_face = [(7.5, 0.8), (14.8, 4.2), (7.5, 7.6), (0.2, 4.2)]
    left_face = [(0.2, 4.2), (7.5, 7.6), (7.5, 15.0), (0.2, 11.6)]
    right_face = [(7.5, 7.6), (14.8, 4.2), (14.8, 11.6), (7.5, 15.0)]
    FACE_TOP = (0xA8, 0xAE, 0xB8, 255)
    FACE_LEFT = (0x6A, 0x6F, 0x78, 255)
    FACE_RIGHT = (0x43, 0x47, 0x4E, 255)
    EDGE = (0x2E, 0x32, 0x38, 255)
    for y in range(16):
        for x in range(16):
            if _in_poly(top_face, x, y):
                px[x, y] = FACE_TOP
            elif _in_poly(left_face, x, y):
                px[x, y] = FACE_LEFT
            elif _in_poly(right_face, x, y):
                px[x, y] = FACE_RIGHT
    # 棱线：顶面的下边界（与两个侧面交界）+ 正面竖直中缝 —— 立体的关键
    for y in range(16):
        for x in range(16):
            if _in_poly(top_face, x, y) and not _in_poly(top_face, x, y + 1):
                px[x, y] = EDGE
            if _in_poly(left_face, x, y) and not _in_poly(right_face, x, y) \
                    and _in_poly(right_face, x + 1, y):
                px[x, y] = EDGE
    # 面向外轮廓的像素提亮一档（像素风立体感：顶面最亮、左面次之、右面最暗）
    SHINE = {FACE_TOP: (0xC8, 0xCE, 0xD8, 255),
             FACE_LEFT: (0x8A, 0x8F, 0x98, 255),
             FACE_RIGHT: (0x5A, 0x5F, 0x68, 255)}
    for y in range(16):
        for x in range(16):
            color = px[x, y]
            if color not in SHINE:
                continue
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if not (0 <= nx < 16 and 0 <= ny < 16) or px[nx, ny][3] == 0:
                    px[x, y] = SHINE[color]
                    break
    # 顶面中央：琥珀正极「＋」（3 宽 3 高，控制在顶面内，不压住棱线）
    rect(px, 6, 3, 8, 3, AMBER)
    rect(px, 7, 2, 7, 4, AMBER)
    # 左面：青绿电量窗（2px 宽、3 段，边框 INK）
    for y in range(6, 13):
        put(px, 3, y, INK)
        put(px, 6, y, INK)
    for (top, color) in ((6, ACCENT_HI), (7, ACCENT), (8, ACCENT_DEEP),
                         (9, ACCENT_HI), (10, ACCENT), (11, ACCENT_DEEP)):
        rect(px, 4, top, 5, top, color)
    # 右面：两道散热格栅
    for y in (8, 10):
        rect(px, 9, y, 12, y, (0x2E, 0x32, 0x38, 255))
    _outline(img, (0x1C, 0x1E, 0x22, 255))
    return img


def item_electric_jetpack():
    """物品图标：小背包（皮革背带 + 控制面板）+ 左右推进器（青绿喷流），物品栏可辨识。"""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    px = img.load()
    body = (0x3E, 0x42, 0x49, 255)
    body_hi = (0x5A, 0x5F, 0x68, 255)
    strap = (0x7A, 0x52, 0x30, 255)
    metal = (0x6E, 0x73, 0x7B, 255)
    # 背包本体
    rect(px, 4, 3, 11, 11, body)
    # 顶部翻盖
    rect(px, 4, 3, 11, 4, body_hi)
    # 两条皮质背带（竖直，压在背包上）
    rect(px, 5, 5, 5, 10, strap)
    rect(px, 10, 5, 10, 10, strap)
    # 中央控制面板（深色 + 中央青绿电量灯）
    rect(px, 7, 6, 8, 9, (0x24, 0x27, 0x2C, 255))
    put(px, 7, 7, ACCENT_HI)
    put(px, 8, 8, ACCENT)
    # 底部扣带
    rect(px, 4, 11, 11, 11, body_hi)
    # 左右推进器（金属筒 + 连接支架 + 青绿喷口 + 两粒火光）
    for x0 in (1, 13):
        rect(px, x0, 4, x0 + 1, 10, metal)
        rect(px, x0, 11, x0 + 1, 11, (0x33, 0x38, 0x3F, 255))   # 喷口上沿
        put(px, x0, 12, ACCENT)
        put(px, x0 + 1, 12, ACCENT_DEEP)
        put(px, x0, 13, ACCENT_HI)                               # 火光
        put(px, x0 + 1, 13, ACCENT)
    # 支架：把推进器与背包连起来（否则看起来是两根浮空的柱子）
    rect(px, 2, 6, 3, 6, metal)
    rect(px, 12, 6, 13, 6, metal)
    rect(px, 2, 9, 3, 9, metal)
    rect(px, 12, 9, 13, 9, metal)
    # 顶部提手（两根金属短柱 + 横梁）
    put(px, 7, 2, metal)
    put(px, 8, 2, metal)
    _outline(img, (0x1C, 0x1E, 0x22, 255))
    return img


def _outline(img, color):
    """给「有内容的连通块」描 1px 外轮廓（透明像素若有非透明邻居 → 画成 color）。"""
    px = img.load()
    filled = [[px[x, y][3] > 0 for x in range(16)] for y in range(16)]
    for y in range(16):
        for x in range(16):
            if filled[y][x]:
                continue
            neighbour = False
            for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                nx, ny = x + dx, y + dy
                if 0 <= nx < 16 and 0 <= ny < 16 and filled[ny][nx]:
                    neighbour = True
                    break
            if neighbour:
                px[x, y] = color


def gui_panel():
    """176×166 的 RS 风格面板（= FEEnergyCubeScreen 的 imageWidth × imageHeight）。

    布局：[外框 1px 深灰 + 内侧 1px 高光] → 标题行（y=0..15）+ y=16 分隔线 →
    「读数板」(12,17)..(170,86)：板内左侧是 (14,18) 起 14×60 竖向电量条凹槽，
    板内右侧是 6 行正文的干净底（x=32..168、y=20..84 一行不落全留白，见 TEXT_* 常量）→
    面板下半部留空。**不画任何物品槽**（能量立方没有库存，Lead 已定）。
    """
    img = Image.new("RGBA", (GUI_W, GUI_H), (GUI_PANEL, GUI_PANEL, GUI_PANEL, 255))
    px = img.load()

    def put_gray(x, y, value):
        px[x, y] = (value, value, value, 255)

    # 四边外框（深灰）+ 左 / 顶 / 底内侧 1px 高光（照抄既有 GUI 的画法）
    # 角上的像素归外框：高光只铺在 x=1..W-2 / y=1..H-2 的内侧段上。
    for x in range(GUI_W):
        put_gray(x, 0, GUI_FRAME)
        put_gray(x, GUI_H - 1, GUI_FRAME)
    for y in range(GUI_H):
        put_gray(0, y, GUI_FRAME)
        put_gray(GUI_W - 1, y, GUI_FRAME)
    for x in range(1, GUI_W - 1):
        put_gray(x, 1, GUI_HIGHLIGHT)
        put_gray(x, GUI_H - 2, GUI_HIGHLIGHT)
    for y in range(1, GUI_H - 1):
        put_gray(1, y, GUI_HIGHLIGHT)

    # 标题下方的分隔线（把「电量立方」标题行和内容区切开）
    for x in range(2, GUI_W - 2):
        put_gray(x, TITLE_RULE_Y, GUI_FRAME)

    # 「读数板」：Screen 在这块上画 6 行正文（x 从 32 起、行距 11，行顶 y = 20/31/42/53/64/75）
    # 和左侧电量条。它不是槽位、不参与点击，只是给文字一块比面板略深的底；
    # 板的内框整块让在文字区之外（见 PLATE_* / TEXT_* 常量），所以文字区内部是纯色。
    plate = (PLATE_X0, PLATE_Y0, PLATE_X1, PLATE_Y1)
    for y in range(plate[1], plate[3] + 1):
        for x in range(plate[0], plate[2] + 1):
            on_edge = x in (plate[0], plate[2]) or y in (plate[1], plate[3])
            if not on_edge:
                put_gray(x, y, GUI_PLATE)
    for x in range(plate[0], plate[2] + 1):
        put_gray(x, plate[1], GUI_PLATE_SHADOW)
        put_gray(x, plate[3], GUI_PLATE_SHINE)
    for y in range(plate[1], plate[3] + 1):
        put_gray(plate[0], y, GUI_PLATE_SHADOW)
        put_gray(plate[2], y, GUI_PLATE_SHINE)
    # 四角归暗边（上/左优先），避免「亮边压暗边」的视觉冲突
    put_gray(plate[0], plate[3], GUI_PLATE_SHADOW)
    put_gray(plate[2], plate[1], GUI_PLATE_SHADOW)

    # ---- 竖向电量条凹槽：左上角 (14,18)、14×60 ----
    # 第 108 轮起界面不再自绘填充：RS 的 ProgressWidget 条（16×58 @ (14,19)）连同 18×60 黑框
    # @ (13,18) 会把这块凹槽**整条盖住**（x=28..30 / y=18..77 已逐像素确认是纯底板 #C0C0C0）。
    # 因此这里仍然画凹槽，只是它的用途从「让界面填色」变成「被盖子压住的底板 + 历史契约」：
    # 若哪天有人把外框改窄/挪位，露出的就是这圈旧边框 ⇒ 本段逐像素断言必须继续存在。
    x0, y0, x1, y1 = BAR_X, BAR_Y, BAR_X + BAR_W - 1, BAR_Y + BAR_H - 1
    for x in range(x0, x1 + 1):
        px[x, y0] = GUI_GROOVE_BORDER
    for y in range(y0, y1):                       # 左下角留给下面的「下亮边」，避免角上冲突
        px[x0, y] = GUI_GROOVE_BORDER
    for x in range(x0, x1 + 1):
        px[x, y1] = GUI_GROOVE_BEVEL
    for y in range(y0 + 1, y1 + 1):               # 右上角保持黑边（受光边只在下/右）
        px[x1, y] = GUI_GROOVE_BEVEL
    for y in range(y0 + 1, y1):
        for x in range(x0 + 1, x1):
            px[x, y] = GUI_GROOVE_BASE
    return img


# ======================================================================================
# 五、图片自检
# ======================================================================================
def image_bytes_report(name, img):
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return len(buf.getvalue()), hashlib.sha256(buf.getvalue()).hexdigest()


def alpha_stats(img):
    px = img.load()
    solid = 0
    xs, ys = [], []
    colors = set()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a > 0:
                solid += 1
                xs.append(x)
                ys.append(y)
                colors.add((r, g, b, a))
    bbox = (min(xs), min(ys), max(xs), max(ys)) if xs else None
    return solid, bbox, colors


def accent_pixels(img, predicate):
    px = img.load()
    hits = []
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a > 0 and predicate(r, g, b):
                hits.append((x, y, (r, g, b)))
    return hits


def is_teal(r, g, b):
    """青绿系（ACCENT_HI / ACCENT / ACCENT_DEEP 三档都算）：绿、蓝明显高于红。"""
    return g >= 80 and b >= 55 and g > r + 40 and b > r + 20


def is_amber(r, g, b):
    """琥珀系：红高、蓝低。"""
    return r >= 180 and 90 <= g <= 200 and b < 90


def check_block_face(name, img, expect_kind):
    solid, bbox, colors = alpha_stats(img)
    check("%s 尺寸 16×16" % name, img.size == (16, 16), "实际 %s" % (img.size,))
    check("%s 模式为 RGBA" % name, img.mode == "RGBA", img.mode)
    check("%s 不透明像素铺满 256（方块面不能有透明洞）" % name, solid == 256, "实际 %d" % solid)
    check("%s 与机壳底图不同（确实画了细节）" % name, len(colors) > 20, "唯一色 %d" % len(colors))
    teal = accent_pixels(img, is_teal)
    amber = accent_pixels(img, is_amber)
    if expect_kind == "side":
        window = [p for p in teal if BAR_X - 9 <= p[0] <= BAR_X - 2]  # x=5..10 窗口列
        check("%s 有竖向电量窗口（青绿像素 ≥ 18 且集中在中央列）" % name,
              len(window) >= 18, "中央列青绿像素 %d，全图青绿 %d" % (len(window), len(teal)))
        check("%s 窗口上沿有琥珀满电刻度" % name, len(amber) >= 2, "琥珀像素 %d" % len(amber))
    elif expect_kind == "top":
        check("%s 有电池极头（琥珀正极 ≥ 4 像素）" % name, len(amber) >= 4, "琥珀像素 %d" % len(amber))
        check("%s 有竖向电量条（青绿 ≥ 12 像素）" % name, len(teal) >= 12, "青绿像素 %d" % len(teal))
    else:
        grid = accent_pixels(img, lambda r, g, b: r == g == b and 0x70 <= r <= 0x95)
        check("%s 有散热格栅（灰色格栅像素 ≥ 18）" % name, len(grid) >= 18, "格栅像素 %d" % len(grid))
        check("%s 有青绿输出接缝（≥ 6 像素）" % name, len(teal) >= 6, "青绿像素 %d" % len(teal))


def check_item_icon(name, img, kind):
    solid, bbox, colors = alpha_stats(img)
    check("%s 尺寸 16×16" % name, img.size == (16, 16), "实际 %s" % (img.size,))
    check("%s 模式为 RGBA" % name, img.mode == "RGBA", img.mode)
    check("%s 不是纯色块（唯一色 ≥ 8）" % name, len(colors) >= 8, "唯一色 %d" % len(colors))
    check("%s 留透明边（不顶满 16×16）" % name,
          bbox is not None and (bbox[0] >= 0) and (bbox[2] <= 15) and (bbox[3] <= 15)
          and solid < 256, "bbox=%s solid=%d" % (bbox, solid))
    check("%s 图标可辨识（非透明像素 110..250）" % name, 110 <= solid <= 250, "实际 %d" % solid)
    check("%s 有外轮廓（暗色描边 ≥ 12 像素）" % name,
          len(accent_pixels(img, lambda r, g, b: r < 0x30 and g < 0x30 and b < 0x30)) >= 12)
    teal = accent_pixels(img, is_teal)
    if kind == "cube":
        light = accent_pixels(img, lambda r, g, b: r > 0x80 and abs(r - g) < 12 and abs(g - b) < 14)
        check("%s 有等轴顶面（亮灰面 ≥ 20 像素）" % name, len(light) >= 20, "亮面 %d" % len(light))
        check("%s 有青绿电量窗（≥ 8 像素）" % name, len(teal) >= 8, "青绿 %d" % len(teal))
    else:
        check("%s 有推进器喷流（底部 4 行青绿 ≥ 8 像素）" % name,
              len([p for p in teal if p[1] >= 12]) >= 8,
              "底部青绿 %d / 全图 %d" % (len([p for p in teal if p[1] >= 12]), len(teal)))
        check("%s 有背包主体（中灰金属像素 ≥ 40）" % name,
              len(accent_pixels(img, lambda r, g, b: 0x35 <= r <= 0x85 and abs(r - g) < 16
                                and abs(g - b) < 20)) >= 40)


def check_gui(img):
    name = "textures/gui/fe_energy_cube.png"
    px = img.load()
    check("%s 尺寸 176×166（== FEEnergyCubeScreen.imageWidth × imageHeight）" % name,
          img.size == (GUI_W, GUI_H), "实际 %s" % (img.size,))
    check("%s 模式为 RGBA 且整图不透明（blit 不应出现透明洞）" % name,
          img.mode == "RGBA" and all(px[x, y][3] == 255 for y in range(0, GUI_H, 7)
                                     for x in range(0, GUI_W, 7)))
    # 边框闭合：四边外框 + 左/顶/底内高光
    border_ok = True
    for x in range(GUI_W):
        border_ok &= px[x, 0][0] == GUI_FRAME and px[x, GUI_H - 1][0] == GUI_FRAME
    for y in range(GUI_H):
        border_ok &= px[0, y][0] == GUI_FRAME and px[GUI_W - 1, y][0] == GUI_FRAME
    for x in range(1, GUI_W - 1):
        border_ok &= px[x, 1][0] == GUI_HIGHLIGHT and px[x, GUI_H - 2][0] == GUI_HIGHLIGHT
    for y in range(1, GUI_H - 1):
        border_ok &= px[1, y][0] == GUI_HIGHLIGHT
    check("%s 边框闭合（四边外框 55 + 左/顶/底内侧高光 255，角上归外框）" % name, border_ok)
    # 标题分隔线
    rule_ok = all(px[x, TITLE_RULE_Y][0] == GUI_FRAME for x in range(2, GUI_W - 2))
    check("%s 标题分隔线在 y=%d（x=2..%d 全为外框色）" % (name, TITLE_RULE_Y, GUI_W - 3), rule_ok)
    # 电量条凹槽：四角 + 上/左黑边 + 下/右亮边 + 内部全为凹槽底（Screen 的填充区 = 内部 12×58）
    x0, y0 = BAR_X, BAR_Y
    x1, y1 = BAR_X + BAR_W - 1, BAR_Y + BAR_H - 1
    edge_ok = True
    for x in range(x0, x1 + 1):
        edge_ok &= px[x, y0] == GUI_GROOVE_BORDER and px[x, y1] == GUI_GROOVE_BEVEL
    for y in range(y0, y1):
        edge_ok &= px[x0, y] == GUI_GROOVE_BORDER
    for y in range(y0 + 1, y1 + 1):
        edge_ok &= px[x1, y] == GUI_GROOVE_BEVEL
    check("%s 电量条凹槽外沿正确（上/左 黑边、下/右 亮边）" % name, edge_ok)
    inner_ok = all(px[x, y] == GUI_GROOVE_BASE
                   for y in range(y0 + 1, y1) for x in range(x0 + 1, x1))
    check("%s 电量条凹槽内部 %d×%d 全为凹槽底（第 108 轮起被 RS 条外框整条盖住，此处只保证底板像素一致）"
          % (name, BAR_W - 2, BAR_H - 2), inner_ok)
    check("%s 凹槽不压标题分隔线（凹槽顶 y=%d > 分隔线 y=%d）" % (name, y0, TITLE_RULE_Y),
          y0 > TITLE_RULE_Y)
    check("%s 凹槽完整落在面板内并留边（x1=%d ≤ %d-8、y1=%d ≤ %d-8）"
          % (name, x1, GUI_W, y1, GUI_H), x1 <= GUI_W - 8 and y1 <= GUI_H - 8)
    # ---- 文字区契约：FEEnergyCubeScreen 的 6 行正文全画在 x=32..168 / y=20..84 ----
    check("%s 6 行正文行顶 = %s（行距 11、末行到 %d）"
          % (name, "/".join(str(v) for v in TEXT_ROW_Y), TEXT_BOTTOM),
          TEXT_ROW_Y[0] == 20 and TEXT_ROW_Y[5] == 75 and TEXT_BOTTOM == 84
          and all(TEXT_ROW_Y[i + 1] - TEXT_ROW_Y[i] == 11 for i in range(5)))
    check("%s 电量条不压文字区（凹槽右 x=%d < TEXT_X=%d）" % (name, x1, TEXT_X), x1 < TEXT_X)
    # 读数板：内框必须整块落在文字区之外（左/上在其左上方、右/下在其右下方），
    # 否则 1px 明暗边会从字缝里透出来（176 宽的板子放不下两个 9 位数，英文截断后更容易顶满）。
    check("%s 读数板内框整块避开文字区（板 x=%d..%d / y=%d..%d；文字 x=%d..%d / y=%d..%d）"
          % (name, PLATE_X0, PLATE_X1, PLATE_Y0, PLATE_Y1,
             TEXT_X, TEXT_X + TEXT_MAX_W, TEXT_ROW_Y[0], TEXT_BOTTOM),
          PLATE_X0 < TEXT_X and PLATE_Y0 < TEXT_ROW_Y[0]
          and PLATE_X1 > TEXT_X + TEXT_MAX_W and PLATE_Y1 > TEXT_BOTTOM)
    check("%s 读数板不越过面板内高光（右 x1=%d ≤ %d-5、底 y1=%d ≤ %d-5）"
          % (name, PLATE_X1, GUI_W, PLATE_Y1, GUI_H),
          PLATE_X1 <= GUI_W - 5 and PLATE_Y1 <= GUI_H - 5)
    check("%s 电量条凹槽整块落在读数板内（板四边都在凹槽外）"
          % name,
          PLATE_X0 < BAR_X and PLATE_Y0 < BAR_Y
          and PLATE_X1 > BAR_X + BAR_W - 1 and PLATE_Y1 > BAR_Y + BAR_H - 1)
    # 板内框配色（上/左暗、下/右亮；四角归暗边，与 gui_panel() 的实现一一对应）
    ring_ok = all(px[x, PLATE_Y0][0] == GUI_PLATE_SHADOW for x in range(PLATE_X0, PLATE_X1 + 1))
    ring_ok &= all(px[x, PLATE_Y1][0] == (GUI_PLATE_SHADOW if x == PLATE_X0 else GUI_PLATE_SHINE)
                   for x in range(PLATE_X0, PLATE_X1 + 1))
    ring_ok &= all(px[PLATE_X0, y][0] == GUI_PLATE_SHADOW for y in range(PLATE_Y0, PLATE_Y1 + 1))
    ring_ok &= all(px[PLATE_X1, y][0] == (GUI_PLATE_SHADOW if y == PLATE_Y0 else GUI_PLATE_SHINE)
                   for y in range(PLATE_Y0, PLATE_Y1 + 1))
    check("%s 读数板内框配色正确（上/左 %d、下/右 %d，四角归暗边）"
          % (name, GUI_PLATE_SHADOW, GUI_PLATE_SHINE), ring_ok)
    plate_ok = True
    for y in range(PLATE_Y0 + 1, PLATE_Y1):
        for x in range(PLATE_X0 + 1, PLATE_X1):
            if BAR_X <= x <= x1 and BAR_Y <= y <= y1:      # 凹槽内部另判（见上）
                continue
            plate_ok &= px[x, y][0] == GUI_PLATE
    check("%s 读数板内部为纯色底 %d（凹槽以外；6 行正文全画在纯色上）"
          % (name, GUI_PLATE), plate_ok)
    # ★ 硬要求：文字区一个装饰像素都不能有（边框 / 暗纹 / 花纹，一个都不许）
    dirty = [(x, y, px[x, y]) for y in range(TEXT_ROW_Y[0], TEXT_BOTTOM + 1)
             for x in range(TEXT_X, TEXT_X + TEXT_MAX_W + 1) if px[x, y] != (GUI_PLATE, GUI_PLATE, GUI_PLATE, 255)]
    check("%s 文字区 x=%d..%d / y=%d..%d 全为纯色 %d（共 %d 像素，无装饰）"
          % (name, TEXT_X, TEXT_X + TEXT_MAX_W, TEXT_ROW_Y[0], TEXT_BOTTOM, GUI_PLATE,
             (TEXT_MAX_W + 1) * (TEXT_BOTTOM - TEXT_ROW_Y[0] + 1)),
          not dirty, "脏像素 %d 个，首个 %s" % (len(dirty), dirty[0] if dirty else None))
    check("%s 面板内没有 18×18 物品槽格（能量立方没有库存）" % name,
          not _looks_like_slot_grid(px))


def _looks_like_slot_grid(px):
    """粗判：是否存在「左上深灰 55 + 内部 139 槽底」的 18×18 单元（有 ⇒ 违背「无槽」契约）。"""
    for y in range(0, GUI_H - 17):
        for x in range(0, GUI_W - 17):
            if (px[x, y][0] == GUI_FRAME and px[x + 1, y + 1][0] == 139
                    and px[x + 17, y + 17][0] == GUI_HIGHLIGHT):
                return True
    return False


# ======================================================================================
# 六、语言文案契约（正文 6 行宽度 + 占位符个数；与 FEEnergyCubeScreen 逐键对齐）
# ======================================================================================
# 为什么要在生成脚本里断言文案宽度（而不是等进游戏看）：
#   · 正文 6 行的可用宽度由 Java 定死：TEXT_MAX_W = 176 - 32 - 8 = 136px。超长串会被
#     `trimToWidth` 按像素截断并在尾部补 "..."，玩家读到的是半句话；第 99 轮实测英文
#     `gate.deny` 一度 265px、四条文案超宽 —— 靠肉眼看 PNG 是发现不了的（文字区本来
#     就空着，截断点不显眼，只有真进游戏才看得出来）；
#   · 同一批键还有「占位符个数」契约：`%s` 的个数必须等于 Java 传的实参个数。少一个就是
#     `String.format` 缺参 —— `transfer` 曾写成 2 个 %s 而 Java 只传 1 个，进游戏直接是
#     格式错误回退文案（比超宽严重得多）。
# 覆盖范围：**只对正文 6 行实际引用的键做宽度断言**（tooltip 会折行，不受 136px 约束）；
# 占位符个数对 body + tooltip 全部键都断言（同一个 bug 类）。
# 与 `tools/selfcheck_round99_energy_cube.py` 的 ⑧-f 互补（「实参个数 == %s 个数」这个契约
# 两个方向都要守，各守一头）：
#   · 这里：期望实参个数**按契约表手写** → 抓「lang 漂移」（有人把 %s 改成两个）；
#   · ⑧-f：实参个数**扫 Java 源码的括号深度得到** → 抓「Java 漂移」（多传死实参 / 少传缺参）。
# 宽度一律对**格式化之后的串**量（如 `grouped()` 出来的 "3,200,000,000"，11 个字符）：
# 拿裸数字量会少算千分位逗号那几像素，把接近上限的行误判成安全。
LANG_PREFIX = "gui.rs_create_compat.fe_energy_cube."
LANG_FILES = (
    ("zh_cn", os.path.join(ASSETS, "lang", "zh_cn.json")),
    ("en_us", os.path.join(ASSETS, "lang", "en_us.json")),
)
SRC_JAVA_ROOT = os.path.join(ROOT, "src", "main", "java")
# 全仓**唯一**允许引用 fe_energy_cube 界面语言键的源文件。为什么扫全仓而不是只扫 Screen：
# 只锁一个文件的话，将来 BE 的 actionbar / Menu 的 tooltip 新增一个带前缀的键，反查就看不见
# （等于断言悄悄失效）。所以断言「引用方文件集合 == 这一个文件」，多一个就变红，逼人回来看注释。
EXPECTED_LANG_CONSUMERS = (
    "src/main/java/cretae/cookiewyq/rs_create_compat/client/screen/FEEnergyCubeScreen.java",
)
# 文本扫描的**已知极限**（实测对照，别再凭印象写理由）：
#   · 同一文件里抽常量（`static final String P = "gui....fe_energy_cube.";`）**不是**盲区：
#     常量的字面量本身就是「双引号 + 前缀 + 点」，子串判据和 `"前缀` 正则都能逐字命中 ⇒ 变红；
#   · 真正两种判据都兜不住的只有两类：①前缀字面量被拆开（"gui.rs_create_compat." + "fe_energy_cube."）
#     ②键名来自变量 / 拼接（`P + "brandNewKey"`，键只从**字面量**里抽，这种写法对键集合隐形）；
#   · 子串判据 = `"前缀` 正则的**超集**：多抓「注释里提到前缀」「前缀在字符串中段」两类假阳性，
#     代价只是多红一次（详情自解释），换中段写法不漏 —— 所以用子串。
# 因此这几条断言只保证「字面量键表」对得上；能兜的兜底是：Screen 自己若改成变量拼接，字面量键
# 会消失 ⇒ java_keys 变少 ⇒ 键集合断言必红。剩下的「拆字面量」由下面这条守卫断言单独盯：
# 全仓不许出现最常见的两种拆法（拆在 `gui.rs_create_compat.` 之后 / 拆在 `fe_energy_cube.` 之前）。
# 拆在别的位置（如 "gui.rs_create_" + "compat.…"）仍会逃过一切文本扫描 —— 那是扫描的极限，
# 靠 code review 兜；写这条只是把最可能发生的拆法变成红的。
# 为什么不用更宽的正则（`"gui\.rs_create_compat\.[^"]*"[ \t]*\+`）：全仓有 3 处**合法**的
# 「语言键前缀 + 变量」拼接（都在别的界面键上，与 fe_energy_cube 无关），宽正则会把它们误伤：
#   block/entity/NetworkResourceRequesterBlockEntity.java  "gui.rs_create_compat.network_resource_requester.fail." + reason
#   client/screen/AdvancedSchematicLoaderScreen.java       "gui.rs_create_compat.schematic_loader.tip." + TOGGLE_TIP_KEYS[i]
#   client/screen/SchematicLoaderScreen.java               同上
# 所以只锁 fe_energy_cube 相关的两个切点。
SPLIT_PREFIX_PATTERNS = (
    r'"gui\.rs_create_compat\."[ \t]*\+',        # "gui.rs_create_compat." + "..."  —— 拆前缀
    r'\+[ \t]*"fe_energy_cube\.([A-Za-z_.]+)"',  # "..." + "fe_energy_cube.pull" —— 拆键名
)
# 第 2 条模式要排除「资源名拼接」：`"textures/gui/" + "fe_energy_cube.png"` 是合法的资源路径写法，
# 不是语言键（否则详情文案会误导人）。当前仓库没有这种写法，防的是将来。
SPLIT_RESOURCE_SUFFIXES = (".png", ".json", ".obj", ".ogg", ".nbt", ".mcmeta")
# (行号, 键名, 宽度代入值)。第三个元素**个数就是 Java 传的实参个数**，值取
# 「默认配置下的典型 ~ 最坏显示」（不是理论极限 —— 极限值一律会被 trimToWidth 截断，属既定行为）：
#   行1 集群块数 64（六面相连的量级上限）；
#   行2 集群存量 64 × 5000 万 = 32 亿（块数上限 × 单块容量；FEEnergyCubeBlockEntity.join 是 64 位）；
#   行3 本块存量 = 单块容量 5000 万（Config.feEnergyCubeCapacity 默认值）；
#   行4 门控：percent 模式（Config.feEnergyCubeChargeMode 默认 percent）下最宽的百分比写法；
#        gate.allow 的实参 = 控制器电量、gate.deny = 控制器电量 + 阈值，取 "100.0%" / "90%"；
#   行5/行6 速率 = Config 默认 100,000 / 10,000 FE/t。
BODY_ROW_SPECS = (
    (1, "cluster", ["64"]),
    (2, "cluster_stored", ["3,200,000,000"]),
    (3, "own_stored", ["50,000,000"]),
    (4, "gate.allow", ["100.0%"]),
    (4, "gate.deny", ["100.0%", "90%"]),
    (4, "gate.nonetwork", []),
    # 第 102 轮：门控补了「网络电量必须正在上涨」这一条 ⇒ 多一个拒绝态文案。
    (4, "gate.not_rising", []),
    (5, "transfer", ["100,000"]),
    (6, "pull", ["10,000"]),
    # 第 102 轮：第 7 行显示「网络电量：上涨 / 持平 / 下降 / 采样中」，画在读数板之外（y=86，
    # 板底 86），因此不影响下面「文字区必须纯色」的 6 行扫描；宽度仍按同一个 TEXT_MAX_W 判。
    (7, "trend", ["上涨"]),
    # 第 106 轮：第 8 行「相邻供能：开 / 关 / 无相邻」（y=99，同样在读数板之外）。
    # 外层相邻键带 1 个 %s（内嵌三态词），词本身 0 实参；宽度按最长的英文词 "None" 量。
    (8, "adjacent", ["None"]),
    (8, "adjacent.on", []),
    (8, "adjacent.off", []),
    (8, "adjacent.none", []),
)
# tooltip 键（只断言占位符个数；实参个数见 FEEnergyCubeScreen#renderTooltip）
TOOLTIP_ARG_COUNTS = (("own", 2), ("cluster_energy", 2), ("percent", 1), ("cluster", 1),
                      # 第 102 轮：门控趋势的四个词 + 「最近 N tick 网络净变化：X FE」
                      ("trend.rising", 0), ("trend.flat", 0), ("trend.falling", 0),
                      ("trend.unknown", 0), ("trend_delta", 2),
                      # 第 104 轮：三态门控的缩写词（tooltip 里单独出现，0 实参）。
                      # 第 108 轮补进契约表：round104 交付时漏登记，导致本脚本的
                      # 「Java 键集合 == 契约表」硬断言一直假红（与本轮能量立方改造无关）。
                      ("gate.short.allow", 0), ("gate.short.deny", 0))
# int 上限（Config 里 feEnergyCubeChargeThresholdAbsolute 等的取值上限，绝对模式报告用）
INT_MAX = 2 ** 31 - 1

# 原版默认字体的 ASCII 字宽（与 tools/selfcheck_round50_keeper_fluid_display.py 的 MC_ADVANCE
# 同源：空格 4、窄字符 2~5、其余拉丁 6、@ 7；非 ASCII（CJK 全角 / 全角标点）= 9）。
MC_ADVANCE = {
    " ": 4, "!": 2, "'": 2, ".": 2, ",": 2, ":": 2, ";": 2, "i": 2, "|": 2,
    "(": 4, ")": 4, "[": 4, "]": 4, "f": 4, "k": 4, "l": 4, "t": 4, "I": 4,
    "<": 5, ">": 5, "*": 5, '"': 5, "`": 5, "{": 5, "}": 5, "~": 5,
    "\\": 6, "@": 7,
}


def char_width(ch):
    if ord(ch) >= 128:
        return 9
    return MC_ADVANCE.get(ch, 6)


def text_width(text):
    return sum(char_width(ch) for ch in text)


def apply_args(template, args):
    """按顺序把模板里的 %s 换成实参（与 Java String.format 的「顺序填充」同义）。"""
    out = template
    for value in args:
        out = out.replace("%s", value, 1)
    return out


def lang_prefix_consumers():
    """扫全仓 src/main/java，返回引用了 fe_energy_cube 界面语言键前缀的 .java（相对路径，升序）。

    判据用**子串**（`LANG_PREFIX in 文件全文`）而不是 `"前缀` 正则：子串是正则的超集 ——
    正则能抓的（字符串开头就是前缀、同文件里的连续常量）子串都抓；子串多抓的两类（注释里
    提到前缀、前缀出现在字符串中段）都是假阳性，代价只是多红一次（失败详情里已写明怎么处理），
    换「前缀出现在字符串中段」不漏。两种判据都兜不住的「拆字面量 / 拼接键名」由
    SPLIT_PREFIX_PATTERNS 那条守卫断言单独盯（见该常量注释）。
    """
    hits = []
    for dirpath, _dirnames, filenames in os.walk(SRC_JAVA_ROOT):
        for name in filenames:
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
                if LANG_PREFIX in handle.read():
                    hits.append(os.path.relpath(path, ROOT).replace(os.sep, "/"))
    return sorted(hits)


def split_prefix_evidence():
    """扫全仓 .java，找「用拼接构造界面语言键前缀 / 拆开键名字面量」的证据（相对路径:行号）。"""
    hits = []
    for dirpath, _dirnames, filenames in os.walk(SRC_JAVA_ROOT):
        for name in filenames:
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            rel_path = os.path.relpath(path, ROOT).replace(os.sep, "/")
            with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
                for line_no, line in enumerate(handle, 1):
                    hit = False
                    # ①拆前缀：命中即算（「"gui.rs_create_compat." +」不可能是资源路径）
                    if re.search(SPLIT_PREFIX_PATTERNS[0], line):
                        hit = True
                    # ②拆键名：排除资源名（.png/.json/... 那是 `"textures/gui/" + "fe_energy_cube.png"`
                    #    这类合法资源路径拼接，不是语言键）
                    for suffix in re.findall(SPLIT_PREFIX_PATTERNS[1], line):
                        # 捕获组不含前导点（"fe_energy_cube.png" ⇒ "png"），补点再比扩展名
                        if not ("." + suffix).endswith(SPLIT_RESOURCE_SUFFIXES):
                            hit = True
                    if hit:
                        hits.append("%s:%d" % (rel_path, line_no))
    return sorted(set(hits))


def check_lang_contract():
    """中英文正文 6 行宽度 ≤ TEXT_MAX_W + 全部键的占位符个数 == Java 实参个数 + 键表不漂移。"""
    langs = {}
    for code, path in LANG_FILES:
        langs[code] = read_json(path) if os.path.isfile(path) else None
        check("lang/%s.json 存在且是 JSON 对象" % code, isinstance(langs[code], dict), path)
    if not all(isinstance(v, dict) for v in langs.values()):
        return

    body_keys = [key for _, key, _ in BODY_ROW_SPECS]
    tooltip_keys = [key for key, _ in TOOLTIP_ARG_COUNTS]
    all_keys = sorted(set(body_keys) | set(tooltip_keys))
    for code, table in langs.items():
        missing = [LANG_PREFIX + key for key in all_keys if (LANG_PREFIX + key) not in table]
        check("lang/%s.json：正文 %d 个键 + tooltip %d 个键齐全（并集 %d 个，cluster 两边共用）"
              % (code, len(body_keys), len(tooltip_keys), len(all_keys)),
              not missing, "缺 %s" % missing)

    # 契约表不许和 Java 漂移：Java 里新出现 / 消失一个 fe_energy_cube 语言键，这里必须同步。
    # 三个方向锁：①引用方文件集合（防新增引用文件）②字面量键集合（防增删键）
    # ③不许用拼接构造前缀 / 拆开键名字面量（否则①②都会静默失效，见 SPLIT_PREFIX_PATTERNS 注释）。
    consumers = lang_prefix_consumers()
    check("全仓只有 %d 个源文件引用 %r 前缀（多一个就说明有新的引用方，必须扩 EXPECTED_LANG_CONSUMERS）"
          % (len(EXPECTED_LANG_CONSUMERS), LANG_PREFIX),
          consumers == sorted(EXPECTED_LANG_CONSUMERS),
          "实际引用方 %s ／ 期望 %s ／ 若新增文件只是在注释里提到该前缀，把那句注释改成字符串字面量"
          "以外的写法或扩 EXPECTED_LANG_CONSUMERS" % (consumers, sorted(EXPECTED_LANG_CONSUMERS)))
    split_hits = split_prefix_evidence()
    check("全仓没有用拼接构造 fe_energy_cube 界面语言键的代码（拆 `gui.rs_create_compat.` / 拆 "
          "`fe_energy_cube.*` 两种切法；资源名拼接 .png/.json 不算）—— 有的话文本扫描就不再可信，"
          "先改成整串字面量",
          not split_hits, "命中 %s" % split_hits)
    java_keys = []
    for rel_path in consumers:
        with io.open(os.path.join(ROOT, rel_path), "r", encoding="utf-8", errors="replace") as handle:
            java_keys += re.findall(r'"gui\.rs_create_compat\.fe_energy_cube\.([A-Za-z_.]+)"',
                                    handle.read())
    java_keys = sorted(set(java_keys))
    check("引用方 Java 里出现的 %d 个界面语言键 == 本脚本契约表（Java 增删键必须先同步这里）"
          % len(java_keys),
          java_keys == all_keys,
          "Java 有而表里没有 %s ／ 表里有而 Java 没引用 %s"
          % (sorted(set(java_keys) - set(all_keys)), sorted(set(all_keys) - set(java_keys))))

    # ---- 占位符个数：body + tooltip 全部键（同一个键只查一次）----
    expectations = []
    for key, want_args in [(key, len(args)) for _, key, args in BODY_ROW_SPECS] + list(TOOLTIP_ARG_COUNTS):
        if (key, want_args) not in expectations:
            expectations.append((key, want_args))
    for key, want_args in expectations:
        for code, table in langs.items():
            template = table.get(LANG_PREFIX + key)
            if not isinstance(template, str):
                check("lang/%s.json %s 是字符串" % (code, key), False)
                continue
            got = template.count("%s")
            check("lang/%s.json %s：%%s 占位符 %d 个 == Java 实参 %d 个（少一个就是 format 缺参）"
                  % (code, key, want_args, want_args),
                  got == want_args,
                  "模板 %r 里 %d 个 %%s，Java 传 %d 个" % (template, got, want_args))

    # ---- 宽度：正文 6 行（8 个键变体）× 中英，全部 ≤ TEXT_MAX_W ----
    for row, key, args in BODY_ROW_SPECS:
        for code, table in langs.items():
            template = table.get(LANG_PREFIX + key)
            if not isinstance(template, str):
                continue
            rendered = apply_args(template, args)
            check("lang/%s.json 第 %d 行 %-14s %3dpx ≤ %dpx（%r）"
                  % (code, row, key, text_width(rendered), TEXT_MAX_W, rendered),
                  text_width(rendered) <= TEXT_MAX_W)
    check("正文 6 行的宽度上限与 Java 同源（TEXT_MAX_W = %d - %d - 8 = %d）"
          % (GUI_W, TEXT_X, TEXT_MAX_W), TEXT_MAX_W == 176 - 32 - 8)

    # ---- 绝对模式（Config.feEnergyCubeChargeMode = absolute）只报告不判负 ----
    # 绝对模式下第 4 行要塞 "2,147,483,647 FE" 这种 10 位数，136px 必然放不下 —— 这是**面板宽度
    # 的物理限制**，由 Java 的 trimToWidth 截断（既定行为），不是文案写得长。这里把最坏宽度
    # 打出来，避免以后有人为了让它「绿」而把 percent 模式的文案也改坏。
    absolute_arg = grouped(INT_MAX) + " FE"
    for code, table in langs.items():
        worst, worst_key = 0, ""
        for key, args in (("gate.allow", [absolute_arg]),
                          ("gate.deny", [absolute_arg, "1,000,000 FE"])):
            template = table.get(LANG_PREFIX + key)
            if isinstance(template, str):
                width = text_width(apply_args(template, args))
                if width > worst:
                    worst, worst_key = width, key
        print("[INFO] lang/%s.json 绝对模式第 4 行最宽 %dpx（%s）> %dpx ⇒ 由 Java trimToWidth "
              "截断补 \"...\"（面板宽度物理限制，不判负）" % (code, worst, worst_key, TEXT_MAX_W))

    # ---- 配置被拉到上限（Config 的 defineInRange 上界 = int 上限）时只报告不判负 ----
    # 与绝对模式同一类「面板宽度物理限制」：本块容量 / 速率配成 2,147,483,647 这种极值时，
    # 行 3 / 5 / 6 会超 136px，同样由 trimToWidth 截断。集群行（行 2）随块数无上限增长，
    # 任何固定样本都会超，所以它的口径就是「64 块 × 默认容量」那条样本，不在这里重复报告。
    over = []
    for key in ("own_stored", "transfer", "pull"):
        for code, table in langs.items():
            template = table.get(LANG_PREFIX + key)
            if isinstance(template, str):
                width = text_width(apply_args(template, [grouped(INT_MAX)]))
                if width > TEXT_MAX_W:
                    over.append("%s/%s %dpx" % (code, key, width))
    print("[INFO] 配置取上限（本块容量/速率 = %s FE）时超宽的行：%s ⇒ 同样由 trimToWidth 截断（不判负）"
          % (grouped(INT_MAX), "、".join(over) if over else "无"))


def grouped(value):
    """与 RsccNumberField.grouped 同口径：从右往左每 3 位插一个逗号。"""
    digits = str(value)
    out = []
    head = len(digits) % 3
    for i, ch in enumerate(digits):
        if i > 0 and (i - head) % 3 == 0:
            out.append(",")
        out.append(ch)
    return "".join(out)


# ======================================================================================
# 七、数据资源（模型 / 掉落 / 配方 / 标签）
# ======================================================================================
BLOCK_MODEL = {
    "parent": "minecraft:block/cube_bottom_top",
    "textures": {
        "particle": "%s:block/fe_energy_cube_side" % MODID,
        "top": "%s:block/fe_energy_cube_top" % MODID,
        "bottom": "%s:block/fe_energy_cube_bottom" % MODID,
        "side": "%s:block/fe_energy_cube_side" % MODID,
    },
}
BLOCKSTATE = {"variants": {"": {"model": "%s:block/fe_energy_cube" % MODID}}}
ITEM_MODEL_BLOCK = {"parent": "%s:block/fe_energy_cube" % MODID}
ITEM_MODEL_JETPACK = {
    "parent": "minecraft:item/generated",
    "textures": {"layer0": "%s:item/electric_jetpack" % MODID},
}
LOOT = {
    "type": "minecraft:block",
    "pools": [{
        "rolls": 1.0,
        "entries": [{"type": "minecraft:item", "name": "%s:fe_energy_cube" % MODID}],
        "conditions": [{"condition": "minecraft:survives_explosion"}],
    }],
}
# FE 能量立方：四角石英富铁 + 上下红石块（能量）/ 左右铜块（导体）+ 中心机器外壳 + 高级处理器
RECIPE_FE_CUBE = {
    "type": "minecraft:crafting_shaped",
    "pattern": ["QRQ", "CMC", "QPQ"],
    "key": {
        "Q": {"item": "refinedstorage:quartz_enriched_iron"},
        "R": {"item": "minecraft:redstone_block"},
        "C": {"item": "minecraft:copper_block"},
        "M": {"item": "refinedstorage:machine_casing"},
        "P": {"item": "refinedstorage:advanced_processor"},
    },
    "result": {"id": "%s:fe_energy_cube" % MODID},
}
# 电动飞行背包：皮革背带 + 高级处理器 + 两个构造核心（把能量推出去）+ 精密构件 + 活塞推进
RECIPE_JETPACK = {
    "type": "minecraft:crafting_shaped",
    "pattern": ["LAL", "CMC", " P "],
    "key": {
        "L": {"item": "minecraft:leather"},
        "A": {"item": "refinedstorage:advanced_processor"},
        "C": {"item": "refinedstorage:construction_core"},
        "M": {"item": "create:precision_mechanism"},
        "P": {"item": "minecraft:piston"},
    },
    "result": {"id": "%s:electric_jetpack" % MODID},
}
# range_charger 的「改前」原文（只把中心 B 的铁块换成 FE 能量立方，其余 4 个键 / pattern / result 不动）
RANGE_CHARGER_BEFORE = {
    "type": "minecraft:crafting_shaped",
    "pattern": ["ARA", "GBG", " I "],
    "key": {
        "A": {"item": "refinedstorage:controller"},
        "R": {"item": "minecraft:redstone_block"},
        "G": {"item": "create:large_cogwheel"},
        "B": {"item": "minecraft:iron_block"},
        "I": {"item": "minecraft:iron_block"},
    },
    "result": {"id": "%s:range_charger" % MODID},
}
RANGE_CHARGER_AFTER = json.loads(json.dumps(RANGE_CHARGER_BEFORE))
RANGE_CHARGER_AFTER["key"]["B"] = {"item": "%s:fe_energy_cube" % MODID}
# 第 89 行 / 既有 tag 原文（脚本断言「原有条目一条不少」）
PICKAXE_ORIGINAL = [
    "rs_create_compat:advanced_collection_cache",
    "rs_create_compat:advanced_quantity_keeper",
    "rs_create_compat:advanced_schematic_loader",
    "rs_create_compat:camouflage_frame",
    "rs_create_compat:collection_cache",
    "rs_create_compat:infinite_separation_frame",
    "rs_create_compat:intermediate_cache",
    "rs_create_compat:inventory_proxy",
    "rs_create_compat:network_resource_receiver",
    "rs_create_compat:network_resource_requester",
    "rs_create_compat:quantity_keeper",
    "rs_create_compat:range_charger",
    "rs_create_compat:schematic_loader",
    "rs_create_compat:separation_frame",
    "rs_create_compat:sequence_assembly_executor",
    "rs_create_compat:sequence_execution_chamber",
    "rs_create_compat:sequence_pattern_terminal",
    "rs_create_compat:unit_pattern_manager",
]
PICKAXE_TARGET = sorted(PICKAXE_ORIGINAL + ["rs_create_compat:fe_energy_cube"])
CURIOS = {"replace": False, "values": ["%s:electric_jetpack" % MODID]}

PATHS = {
    "tex_block_side": os.path.join(ASSETS, "textures", "block", "fe_energy_cube_side.png"),
    "tex_block_top": os.path.join(ASSETS, "textures", "block", "fe_energy_cube_top.png"),
    "tex_block_bottom": os.path.join(ASSETS, "textures", "block", "fe_energy_cube_bottom.png"),
    "tex_item_cube": os.path.join(ASSETS, "textures", "item", "fe_energy_cube.png"),
    "tex_item_jetpack": os.path.join(ASSETS, "textures", "item", "electric_jetpack.png"),
    "tex_gui": os.path.join(ASSETS, "textures", "gui", "fe_energy_cube.png"),
    "blockstate": os.path.join(ASSETS, "blockstates", "fe_energy_cube.json"),
    "model_block": os.path.join(ASSETS, "models", "block", "fe_energy_cube.json"),
    "model_item_cube": os.path.join(ASSETS, "models", "item", "fe_energy_cube.json"),
    "model_item_jetpack": os.path.join(ASSETS, "models", "item", "electric_jetpack.json"),
    "loot": os.path.join(DATA, MODID, "loot_table", "blocks", "fe_energy_cube.json"),
    "recipe_cube": os.path.join(DATA, MODID, "recipe", "fe_energy_cube.json"),
    "recipe_jetpack": os.path.join(DATA, MODID, "recipe", "electric_jetpack.json"),
    "recipe_charger": os.path.join(DATA, MODID, "recipe", "range_charger.json"),
    "tag_pickaxe": os.path.join(DATA, "minecraft", "tags", "block", "mineable", "pickaxe.json"),
    "curios_back": os.path.join(DATA, "curios", "tags", "item", "back.json"),
    "curios_chest": os.path.join(DATA, "curios", "tags", "item", "chest.json"),
}


def read_json(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def write_json(path, data):
    """幂等写盘（内容一致就不动文件，保持 mtime，便于 diff）。"""
    text = json.dumps(data, indent=2, ensure_ascii=False) + "\n"
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if os.path.isfile(path):
        with io.open(path, "r", encoding="utf-8") as handle:
            if handle.read() == text:
                return False
    with io.open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)
    return True


def write_png(path, img):
    """幂等写 PNG（像素一致就不动文件）。"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    data = buf.getvalue()
    if os.path.isfile(path):
        with open(path, "rb") as handle:
            if handle.read() == data:
                return False
    with open(path, "wb") as handle:
        handle.write(data)
    return True


# ======================================================================================
# 八、主流程：先在内存里生成 + 自检，全部通过才写盘
# ======================================================================================
def main():
    print("=== FE 能量立方 / 电动飞行背包：资源与美术生成（第 99 轮） ===")
    print("仓库根：%s" % ROOT)

    # ---------- 1. 图片在内存里生成 ----------
    images = {
        "tex_block_side": block_side(),
        "tex_block_top": block_top(),
        "tex_block_bottom": block_bottom(),
        "tex_item_cube": item_fe_energy_cube(),
        "tex_item_jetpack": item_electric_jetpack(),
        "tex_gui": gui_panel(),
    }

    print("\n--- ① 方块三面贴图（16×16，各自独立）---")
    check_block_face("block/fe_energy_cube_side.png", images["tex_block_side"], "side")
    check_block_face("block/fe_energy_cube_top.png", images["tex_block_top"], "top")
    check_block_face("block/fe_energy_cube_bottom.png", images["tex_block_bottom"], "bottom")
    side_sha = image_bytes_report("side", images["tex_block_side"])[1]
    top_sha = image_bytes_report("top", images["tex_block_top"])[1]
    bottom_sha = image_bytes_report("bottom", images["tex_block_bottom"])[1]
    check("三张面贴图互不相同（用户硬规则：贴图不许一图多用）",
          len({side_sha, top_sha, bottom_sha}) == 3)

    print("\n--- ② 物品图标（16×16，物品栏可辨识）---")
    check_item_icon("item/fe_energy_cube.png", images["tex_item_cube"], "cube")
    check_item_icon("item/electric_jetpack.png", images["tex_item_jetpack"], "jetpack")
    check("两个物品图标互不相同",
          image_bytes_report("c", images["tex_item_cube"])[1]
          != image_bytes_report("j", images["tex_item_jetpack"])[1])

    print("\n--- ③ GUI 贴图（176×166，电量条凹槽 (14,18) 14×60）---")
    check_gui(images["tex_gui"])

    print("\n--- ④ 语言文案：正文 6 行宽度 ≤ %dpx + 占位符个数（中英双语）---" % TEXT_MAX_W)
    check_lang_contract()

    # ---------- 2. 数据资源：先在内存里算好 ----------
    print("\n--- ⑤ 模型 / 方块状态 / 掉落 / 配方 / 标签 ---")
    check("blockstate 只有单变体（FEEnergyCubeBlock 没有 active/lit 属性）",
          BLOCKSTATE == {"variants": {"": {"model": "%s:block/fe_energy_cube" % MODID}}})
    check("block 模型 parent = minecraft:block/cube_bottom_top",
          BLOCK_MODEL["parent"] == "minecraft:block/cube_bottom_top")
    check("block 模型的 top/bottom/side 指向三张**不同**贴图",
          len({BLOCK_MODEL["textures"]["top"], BLOCK_MODEL["textures"]["bottom"],
               BLOCK_MODEL["textures"]["side"]}) == 3)
    check("item 方块模型 parent 指向 block 模型（与既有方块物品同口径：铁桶式 3D 渲染）",
          ITEM_MODEL_BLOCK["parent"] == "%s:block/fe_energy_cube" % MODID)
    check("item 背包模型 = item/generated + layer0 → item/electric_jetpack",
          ITEM_MODEL_JETPACK["parent"] == "minecraft:item/generated"
          and ITEM_MODEL_JETPACK["textures"]["layer0"] == "%s:item/electric_jetpack" % MODID)
    check("掉落表自掉 fe_energy_cube",
          LOOT["pools"][0]["entries"][0]["name"] == "%s:fe_energy_cube" % MODID)
    for label, recipe, want in (("fe_energy_cube", RECIPE_FE_CUBE, "fe_energy_cube"),
                                ("electric_jetpack", RECIPE_JETPACK, "electric_jetpack")):
        used = {char for row in recipe["pattern"] for char in row if char != " "}
        check("recipe/%s.json：pattern 与 key 字符一一对应" % label, used == set(recipe["key"]),
              "pattern 用 %s / key 有 %s" % (sorted(used), sorted(recipe["key"])))
        check("recipe/%s.json：result.id = %s:%s" % (label, MODID, want),
              recipe["result"]["id"] == "%s:%s" % (MODID, want))
    # range_charger：只允许 B 一个原料变化
    changed = {char for char in RANGE_CHARGER_BEFORE["key"]
               if RANGE_CHARGER_BEFORE["key"][char] != RANGE_CHARGER_AFTER["key"][char]}
    check("range_charger 配方只改了中心 B 一个原料（其余 4 键 / pattern / result 不变）",
          changed == {"B"}
          and RANGE_CHARGER_AFTER["pattern"] == RANGE_CHARGER_BEFORE["pattern"]
          and RANGE_CHARGER_AFTER["result"] == RANGE_CHARGER_BEFORE["result"],
          "实际变化键 %s" % sorted(changed))
    check("range_charger 的中心 B 换成了 rs_create_compat:fe_energy_cube",
          RANGE_CHARGER_AFTER["key"]["B"] == {"item": "%s:fe_energy_cube" % MODID})

    # ---------- 3. 既有文件的「改前状态」核验（不静默覆盖别人的改动） ----------
    print("\n--- ⑥ 既有文件改前状态核验（防止误覆盖并行改动）---")
    pickaxe_path = PATHS["tag_pickaxe"]
    pickaxe_now = read_json(pickaxe_path) if os.path.isfile(pickaxe_path) else None
    if check("mineable/pickaxe.json 存在且格式正确", isinstance(pickaxe_now, dict)):
        check("mineable/pickaxe.json 是追加语义（replace=false）",
              pickaxe_now.get("replace") is False)
        values_now = pickaxe_now.get("values") or []
        missing = [v for v in values_now if v not in PICKAXE_ORIGINAL + ["rs_create_compat:fe_energy_cube"]]
        check("mineable/pickaxe.json 除 fe_energy_cube 外没有别的未知条目（不会顺手覆盖别的方块）",
              not missing, "未知条目 %s" % missing)
        check("mineable/pickaxe.json 原有 18 条一条不少",
              all(v in values_now for v in PICKAXE_ORIGINAL),
              "缺 %s" % [v for v in PICKAXE_ORIGINAL if v not in values_now])
        check("mineable/pickaxe.json 处于「改前」或「改后」状态（不是第三个版本）",
              sorted(values_now) in (sorted(PICKAXE_ORIGINAL), PICKAXE_TARGET),
              "当前条目数 %d" % len(values_now))

    charger_path = PATHS["recipe_charger"]
    charger_now = read_json(charger_path) if os.path.isfile(charger_path) else None
    check("recipe/range_charger.json 存在", isinstance(charger_now, dict))
    if isinstance(charger_now, dict):
        check("recipe/range_charger.json 处于「改前」或「改后」状态（不是第三个版本）",
              charger_now in (RANGE_CHARGER_BEFORE, RANGE_CHARGER_AFTER),
              "当前内容既不是改前也不是改后：%s" % json.dumps(charger_now, ensure_ascii=False)[:200])

    # ---------- 4. 全部自检通过才写盘 ----------
    if PROBLEMS:
        print("\n[result] 自检失败 %d 项 —— **没有写任何文件**（先修脚本再重跑）" % len(PROBLEMS))
        for item in PROBLEMS:
            print("  - %s" % item)
        return 1

    print("\n--- ⑦ 写盘（幂等：内容一致则不动文件）---")
    changed_pngs = 0
    for key, img in images.items():
        changed = write_png(PATHS[key], img)
        changed_pngs += 1 if changed else 0
        size, sha = image_bytes_report(key, img)
        print("[%s] %-46s %d×%d  %d 字节  sha256=%s"
              % ("写入" if changed else "不变",
                 os.path.relpath(PATHS[key], ROOT).replace(os.sep, "/"),
                 img.width, img.height, size, sha[:16]))
    changed_json = 0
    for key, data in (("blockstate", BLOCKSTATE), ("model_block", BLOCK_MODEL),
                      ("model_item_cube", ITEM_MODEL_BLOCK),
                      ("model_item_jetpack", ITEM_MODEL_JETPACK),
                      ("loot", LOOT), ("recipe_cube", RECIPE_FE_CUBE),
                      ("recipe_jetpack", RECIPE_JETPACK),
                      ("curios_back", CURIOS), ("curios_chest", CURIOS)):
        changed = write_json(PATHS[key], data)
        changed_json += 1 if changed else 0
        print("[%s] %s" % ("写入" if changed else "不变",
                           os.path.relpath(PATHS[key], ROOT).replace(os.sep, "/")))
    # range_charger：改一个原料（带改前断言，已在第 ⑥ 步核验）
    changed = write_json(charger_path, RANGE_CHARGER_AFTER)
    changed_json += 1 if changed else 0
    print("[%s] %s（中心 B：minecraft:iron_block → rs_create_compat:fe_energy_cube）"
          % ("写入" if changed else "不变",
             os.path.relpath(charger_path, ROOT).replace(os.sep, "/")))
    # pickaxe：保留原有全部条目 + 追加（按字典序合并）
    merged = {"replace": False, "values": PICKAXE_TARGET}
    changed = write_json(pickaxe_path, merged)
    changed_json += 1 if changed else 0
    print("[%s] %s（原有 18 条保留 + 追加 fe_energy_cube）"
          % ("写入" if changed else "不变", os.path.relpath(pickaxe_path, ROOT).replace(os.sep, "/")))

    # ---------- 5. 写盘后回读再验一遍（防止写坏 / 写到别的位置） ----------
    print("\n--- ⑧ 写盘后回读校验 ---")
    for key in ("tex_block_side", "tex_block_top", "tex_block_bottom", "tex_item_cube",
                "tex_item_jetpack", "tex_gui"):
        img = Image.open(PATHS[key]).convert("RGBA")
        want = images[key].size
        check("回读 %s 尺寸 %s" % (os.path.basename(PATHS[key]), "×".join(map(str, want))),
              img.size == want, "实际 %s" % (img.size,))
    check("回读 GUI：凹槽内部仍是凹槽底、外沿仍是黑边/亮边",
          Image.open(PATHS["tex_gui"]).convert("RGBA").load()[BAR_X + 5, BAR_Y + 5]
          == GUI_GROOVE_BASE)
    disk_px = Image.open(PATHS["tex_gui"]).convert("RGBA").load()
    disk_dirty = [(x, y) for y in range(TEXT_ROW_Y[0], TEXT_BOTTOM + 1)
                  for x in range(TEXT_X, TEXT_X + TEXT_MAX_W + 1)
                  if disk_px[x, y] != (GUI_PLATE, GUI_PLATE, GUI_PLATE, 255)]
    check("回读 GUI：盘上文字区 x=%d..%d / y=%d..%d 仍是纯色 %d（磁盘 PNG = 内存，%d 像素全干净）"
          % (TEXT_X, TEXT_X + TEXT_MAX_W, TEXT_ROW_Y[0], TEXT_BOTTOM, GUI_PLATE,
             (TEXT_MAX_W + 1) * (TEXT_BOTTOM - TEXT_ROW_Y[0] + 1)),
          not disk_dirty, "脏像素 %d 个" % len(disk_dirty))
    check("回读 blockstate：单变体 + 指向 block 模型",
          read_json(PATHS["blockstate"]) == BLOCKSTATE)
    check("回读 block 模型：parent 正确",
          read_json(PATHS["model_block"]) == BLOCK_MODEL)
    check("回读 item（背包）模型：layer0 正确",
          read_json(PATHS["model_item_jetpack"]) == ITEM_MODEL_JETPACK)
    check("回读 loot：自掉", read_json(PATHS["loot"]) == LOOT)
    check("回读 fe_energy_cube 配方", read_json(PATHS["recipe_cube"]) == RECIPE_FE_CUBE)
    check("回读 electric_jetpack 配方", read_json(PATHS["recipe_jetpack"]) == RECIPE_JETPACK)
    check("回读 range_charger 配方 = 改后版本（且只改了 B）",
          read_json(charger_path) == RANGE_CHARGER_AFTER)
    after_pickaxe = read_json(pickaxe_path)
    check("回读 pickaxe 标签：原有 18 条 + 新方块 = %d 条"
          % (len(PICKAXE_ORIGINAL) + 1), after_pickaxe.get("values") == PICKAXE_TARGET)
    for key in ("curios_back", "curios_chest"):
        got = read_json(PATHS[key])
        check("回读 %s：replace=false 且只含 electric_jetpack"
              % os.path.basename(PATHS[key]),
              got == {"replace": False, "values": ["%s:electric_jetpack" % MODID]})

    if PROBLEMS:
        print("\n[result] 写盘后校验失败 %d 项" % len(PROBLEMS))
        return 1

    print("\n[result] OK：6 张 PNG + 11 个 JSON（含 1 个既有文件改动）全部生成并通过 %d 项自检；"
          "本次实际改动 %d 张图 / %d 个 JSON" % (CHECKS[0], changed_pngs, changed_json))
    print("[info] 语言键不在本脚本写（assets/rs_create_compat/lang/** 由 Lead 统一维护）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
