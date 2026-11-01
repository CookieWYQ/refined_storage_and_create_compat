#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 97 轮自检 B4：容器代理器界面「布局 / 贴图 / 文案承载方式」的回归判据。

背景（docs/ROUND97_USER_FEEDBACK.md 第 2 节 B2/B3，用户第 2、3 条）：
  · 面板原来是 224×248：快捷栏 y=230 的格子底边正好压在贴图最底边框上（看起来被切掉），
    而六面按钮（底边 126）到玩家背包（171 起是格子顶边）之间空了 45px（用户：「上面这么大空位」）；
  · 那条「字太小」的深红说明行已删除，信息改由「悬停六面按钮」的 tooltip 承载。

本脚本钉住（任一 FAIL ⇒ 退出码 1）：
  ① Screen：imageWidth=224、imageHeight=222；六面按钮几何 (108,20)/110×16/步长 18 不变；
  ② Menu：玩家背包行首 y=136（`136 + row * 18`、`26 + col * 18`）、快捷栏 y=196；旧值 172 / 230 不许回来；
  ③ 贴图 PNG：结构体读 IHDR 必须正好 224×222（**不依赖 PIL**），并解出像素后验证
     四边外框闭合 + 左/顶/底内高光 + 每格槽位 18×18 的倒角/高光，且槽位坐标由 ② 的菜单坐标逐格推出
     （三处口径不一致 ⇒ 界面要么空洞要么错位）；
  ④ 文案承载方式：源码里不许再出现 renderWarning / warning 绘制键 / 常量 WARNING_* / pose 缩放；
     必须按角色 id 取 `face_tip.*` 并用 tooltip 画；状态文字必须走 `font.split(..., 98)` 且 x=10、
     最多 4 行（防止再跑回「单行等比缩小 = 蚂蚁字」，也防止折行糊进 x≥108 的按钮列）；
  ⑤ 语言文件：`inventory_proxy.warning` 在中英两边都已删除；4 条 `face_tip.*` 中英齐全、非空、
     中文 ≤60 字且非纯 ASCII、英文不含中日韩字符；中英键集相等。

**回归实测**（第 97 轮回报里的证据）：把 imageHeight 改回 248 / 快捷栏 y 改回 230 / 贴图换回 224×248，
本脚本都必须判负 —— 脚本末尾 4 个源码级反例（①..④ 的纯函数）就是这条要求的日常自证。

用法：python tools/selfcheck_round97_proxy_layout.py    （退出码 0 = 全部 PASS）
"""

from __future__ import annotations

import io
import json
import os
import re
import struct
import sys
import zlib

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
ASSETS = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat")
LANG_DIR = os.path.join(ASSETS, "lang")
TEXTURE = os.path.join(ASSETS, "textures", "gui", "inventory_proxy.png")

SCREEN = os.path.join(JAVA, "client", "screen", "InventoryProxyScreen.java")
MENU = os.path.join(JAVA, "menu", "InventoryProxyMenu.java")

# 本轮唯一布局口径（与 docs/ROUND97_USER_FEEDBACK.md B2 表逐字一致）
EXPECT_IMAGE_W = 224
EXPECT_IMAGE_H = 222
EXPECT_FACE_X = 108
EXPECT_FACE_Y = 20
EXPECT_FACE_W = 110
EXPECT_FACE_H = 16
EXPECT_FACE_STEP = 18
EXPECT_INV_ORIGIN = (26, 136)
EXPECT_HOTBAR_ORIGIN = (26, 196)
EXPECT_PROXY_SLOT = (10, 22)
EXPECT_STATUS_X = 10
EXPECT_STATUS_Y = 46
EXPECT_STATUS_WIDTH = 98
EXPECT_STATUS_MAX_LINES = 4

# 贴图调色板（沿用旧图 5 色）
FRAME = 55
HIGHLIGHT = 255
PANEL = 198
PLATE = 176
SLOT_BASE = 139

FACE_TIPS = ("disabled", "proxy_object", "contents", "contents_input")

PROBLEMS = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("  PASS %s" % name)
    else:
        print("  FAIL %s%s" % (name, ("  <<< " + detail) if detail else ""))
        PROBLEMS.append(name + (("  " + detail) if detail else ""))


def info(text):
    print("  [info] %s" % text)


def read(path):
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def strip_comments_and_strings(text):
    """去掉 // 与 /* */ 注释和字符串字面量内容（保留引号本身），避免注释里的字样干扰判据。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
        elif ch == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
        elif ch == '"':
            out.append('"')
            i += 1
            while i < n and text[i] != '"':
                if text[i] == "\\":
                    i += 2
                else:
                    i += 1
            out.append('"')
            i += 1
        else:
            out.append(ch)
            i += 1
    return "".join(out)


# ==================================================================== ③ PNG 读取（不依赖 PIL）
def png_ihdr_size(path):
    """只读 IHDR：返回 (width, height)。"""
    with open(path, "rb") as handle:
        head = handle.read(33)
    if head[:8] != b"\x89PNG\r\n\x1a\n" or head[12:16] != b"IHDR":
        return None
    return struct.unpack(">II", head[16:24])


def png_pixels(path):
    """最小 PNG 解码：8-bit、非隔行（PIL 写出的就是这种）。返回 (w, h, channels, pixels)。

    为什么手写：本工程的自检脚本要在**没有 PIL**的环境也能跑（Lead 的构建机装的是最小 Python），
    而「贴图尺寸/边框闭合」这类判据必须能硬判，不能因为缺库就静默跳过。
    """
    raw = open(path, "rb").read()
    if raw[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    pos = 8
    width = height = depth = color_type = None
    idat = b""
    while pos + 8 <= len(raw):
        length = struct.unpack(">I", raw[pos:pos + 4])[0]
        kind = raw[pos + 4:pos + 8]
        payload = raw[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, color_type, comp, filt, interlace = struct.unpack(">IIBBBBB", payload)
            if depth != 8 or interlace != 0:
                return None
        elif kind == b"IDAT":
            idat += payload
        elif kind == b"IEND":
            break
    channels = {0: 1, 2: 3, 4: 2, 6: 4}.get(color_type)
    if channels is None:
        return None
    stride = width * channels
    data = zlib.decompress(idat)
    out = bytearray(height * stride)
    previous = bytearray(stride)
    offset = 0
    for y in range(height):
        filter_type = data[offset]
        offset += 1
        line = bytearray(data[offset:offset + stride])
        offset += stride
        if filter_type == 1:
            for i in range(channels, stride):
                line[i] = (line[i] + line[i - channels]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - channels] if i >= channels else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                a = line[i - channels] if i >= channels else 0
                b = previous[i]
                c = previous[i - channels] if i >= channels else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                predictor = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + predictor) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        previous = line
    return width, height, channels, bytes(out)


# ==================================================================== ② 菜单槽位解析
def parse_menu_slots(source):
    """从 addSlot(...) 调用里解析出菜单坐标。返回 [(名字, x, y)]。"""
    code = strip_comments_and_strings(source)
    slots = []
    proxy = re.search(r"new\s+ProxySlot\(\s*objectView\s*,\s*0\s*,\s*(\d+)\s*,\s*(\d+)\s*\)", code)
    if proxy:
        slots.append(("proxy_object", int(proxy.group(1)), int(proxy.group(2))))
    for match in re.finditer(
            r"new\s+Slot\(\s*inventory\s*,\s*col\s*\+\s*row\s*\*\s*9\s*\+\s*9\s*,"
            r"\s*(\d+)\s*\+\s*col\s*\*\s*18\s*,\s*(\d+)\s*\+\s*row\s*\*\s*18\s*\)", code):
        for row in range(3):
            for col in range(9):
                slots.append(("player_inventory[%d][%d]" % (row, col),
                              int(match.group(1)) + col * 18, int(match.group(2)) + row * 18))
    for match in re.finditer(
            r"new\s+Slot\(\s*inventory\s*,\s*col\s*,\s*(\d+)\s*\+\s*col\s*\*\s*18\s*,\s*(\d+)\s*\)", code):
        for col in range(9):
            slots.append(("hotbar[%d]" % col, int(match.group(1)) + col * 18, int(match.group(2))))
    return slots, code


# ==================================================================== 判据主体
def lang_problems(zh, en):
    """⑤ 语言键：旧 warning 键必须已删；4 条 face_tip 中英齐全、短句、zh 非纯 ASCII、en 无 CJK。"""
    problems = []
    old_key = "gui.rs_create_compat.inventory_proxy.warning"
    if old_key in zh or old_key in en:
        problems.append("lang-warning-key-kept")
    for name in FACE_TIPS:
        key = "gui.rs_create_compat.inventory_proxy.face_tip." + name
        zh_value = str(zh.get(key, ""))
        en_value = str(en.get(key, ""))
        if not zh_value.strip() or not en_value.strip():
            problems.append("lang-face-tip:%s" % name)
            continue
        if len(zh_value) > 60:
            problems.append("lang-face-tip-too-long:%s" % name)
        if zh_value.isascii():
            problems.append("lang-face-tip-ascii:%s" % name)
        if any("\u4e00" <= ch <= "\u9fff" or "\u3040" <= ch <= "\u30ff" or "\uac00" <= ch <= "\ud7af"
               for ch in en_value):
            problems.append("lang-face-tip-cjk-in-en:%s" % name)
    if set(zh) != set(en):
        problems.append("lang-key-set")
    return problems


def layout_problems(screen_source, menu_source, png_path, zh, en):
    """返回问题码列表。真实源码/贴图必须为空；反例自证要求对应问题码出现。"""
    problems = []
    screen = strip_comments_and_strings(screen_source)
    screen_raw = screen_source

    # ---------------- ① Screen 尺寸 / 按钮几何 / 状态文字折算方式 ----------------
    m = re.search(r"this\.imageWidth\s*=\s*(\d+)", screen)
    if not m or int(m.group(1)) != EXPECT_IMAGE_W:
        problems.append("screen-width:%s" % (m.group(1) if m else "missing"))
    m = re.search(r"this\.imageHeight\s*=\s*(\d+)", screen)
    if not m or int(m.group(1)) != EXPECT_IMAGE_H:
        problems.append("screen-height:%s" % (m.group(1) if m else "missing"))
    for name, expect in (("FACE_X", EXPECT_FACE_X), ("FACE_Y", EXPECT_FACE_Y),
                         ("FACE_W", EXPECT_FACE_W), ("FACE_H", EXPECT_FACE_H),
                         ("FACE_STEP", EXPECT_FACE_STEP)):
        m = re.search(r"\b%s\s*=\s*(\d+)\s*;" % name, screen)
        if not m or int(m.group(1)) != expect:
            problems.append("face-geometry:%s=%s" % (name, m.group(1) if m else "missing"))
    # 面板尺寸只由常量决定：blit 不许写别的宽高（否则贴图与面板会错位）
    if "blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight)" not in screen.replace("\n", " ").replace("  ", " "):
        problems.append("blit-size")

    # ---------------- ④ 文案承载方式 ----------------
    if "renderWarning" in screen_raw or "renderWarning" in screen:
        problems.append("warning-restored")
    if 'LANG + "warning"' in screen_raw:
        problems.append("warning-restored")
    for dead in ("WARNING_X", "WARNING_Y", "COLOR_WARNING", "pose().scale("):
        if dead in screen:
            problems.append("warning-restored:" + dead)
    if 'LANG + "face_tip."' not in screen_raw:
        problems.append("face-tip-key-missing")
    if "renderTooltip" not in screen:
        problems.append("face-tip-no-tooltip")
    # tooltip 必须画在 super.render 之后（否则被背景 / 按钮盖住）
    render_body = screen[screen.find("public void render("):]
    render_body = render_body[:render_body.find("\n    }\n") + 7] if "\n    }\n" in render_body else render_body
    pos_super = render_body.find("super.render(")
    pos_tip = render_body.find("renderFaceTip(")
    if pos_super < 0 or pos_tip < 0 or pos_tip < pos_super:
        problems.append("face-tip-order")
    # 状态文字：x=10 / 宽 98 / font.split / 最多 4 行 / 行高 9
    for name, expect in (("STATUS_X", EXPECT_STATUS_X), ("STATUS_Y", EXPECT_STATUS_Y),
                         ("STATUS_WIDTH", EXPECT_STATUS_WIDTH),
                         ("STATUS_MAX_LINES", EXPECT_STATUS_MAX_LINES), ("LINE_HEIGHT", 9)):
        m = re.search(r"\b%s\s*=\s*(\d+)\s*;" % name, screen)
        if not m or int(m.group(1)) != expect:
            problems.append("status-geometry:%s=%s" % (name, m.group(1) if m else "missing"))
    if "font.split(status, STATUS_WIDTH)" not in screen.replace("\n", " ").replace("  ", " "):
        problems.append("status-no-split")
    if "STATUS_Y + line * LINE_HEIGHT" not in screen.replace("\n", " ").replace("  ", " "):
        problems.append("status-line-step")
    if "this.inventoryLabelY = 10000" not in screen:
        problems.append("inventory-label")

    # ---------------- ② Menu 槽位 ----------------
    slots, menu = parse_menu_slots(menu_source)
    by_name = {name: (x, y) for name, x, y in slots}
    if by_name.get("proxy_object") != EXPECT_PROXY_SLOT:
        problems.append("menu-proxy-slot:%s" % (by_name.get("proxy_object"),))
    for row in range(3):
        for col in range(9):
            want = (EXPECT_INV_ORIGIN[0] + col * 18, EXPECT_INV_ORIGIN[1] + row * 18)
            if by_name.get("player_inventory[%d][%d]" % (row, col)) != want:
                problems.append("menu-inv:%d,%d" % (row, col))
                break
    for col in range(9):
        want = (EXPECT_HOTBAR_ORIGIN[0] + col * 18, EXPECT_HOTBAR_ORIGIN[1])
        if by_name.get("hotbar[%d]" % col) != want:
            problems.append("menu-hotbar:%d" % col)
            break
    # 旧坐标不许以任何形式回来（`172 + row * 18` / `, 230)` 是旧实现的两处特征写法）
    if "172 + row * 18" in menu or ", 230)" in menu:
        problems.append("menu-old-coords")
    if len(slots) != 1 + 27 + 9:
        problems.append("menu-slot-count:%d" % len(slots))

    # ---------------- ⑤ 语言键（先于贴图判：贴图坏了不该连带把语言结论/反例一起跳过） ----------------
    problems.extend(lang_problems(zh, en))

    # ---------------- ③ 贴图：尺寸 + 边框 + 槽位格 ----------------
    # 注意：贴图坏了只该让 ③ 判负，所以上面 ①②④⑤ 全部先跑完再进这里。
    decoded = None
    if not os.path.isfile(png_path):
        problems.append("png-missing")
    else:
        size = png_ihdr_size(png_path)
        if size != (EXPECT_IMAGE_W, EXPECT_IMAGE_H):
            problems.append("png-size:%s" % (size,))
        decoded = png_pixels(png_path)
        if decoded is None or decoded[0] != EXPECT_IMAGE_W or decoded[1] != EXPECT_IMAGE_H:
            problems.append("png-decode")
            decoded = None
    if decoded is None:
        return problems
    width, height, channels, pixels = decoded

    def gray(x, y):
        base = (y * width + x) * channels
        return pixels[base]

    # 四边外框 + 左/顶/底内高光
    if any(gray(x, 0) != FRAME for x in range(width)):
        problems.append("png-top-frame")
    if any(gray(x, height - 1) != FRAME for x in range(width)):
        problems.append("png-bottom-frame")
    if any(gray(0, y) != FRAME for y in range(height)):
        problems.append("png-left-frame")
    if any(gray(width - 1, y) != FRAME or gray(width - 2, y) != FRAME for y in range(height)):
        problems.append("png-right-frame")
    if any(gray(1, y) != HIGHLIGHT for y in range(1, height - 1)):
        problems.append("png-left-highlight")
    if any(gray(x, 1) != HIGHLIGHT for x in range(1, width - 2)):
        problems.append("png-top-highlight")
    if any(gray(x, height - 2) != HIGHLIGHT for x in range(1, width - 2)):
        problems.append("png-bottom-highlight")
    # 快捷栏下方必须真的留出 8px（旧图是 0：格子底边就是最底边框）
    hotbar_bottom_menu = EXPECT_HOTBAR_ORIGIN[1] + 18
    if height - hotbar_bottom_menu < 8:
        problems.append("png-bottom-air:%d" % (height - hotbar_bottom_menu))
    # 面板底色（按钮底板之外的非边框区域）必须仍是 198，槽位必须是 139 底 + 55 左/上 + 255 右/下
    for name, menu_x, menu_y in slots:
        cell_x, cell_y = menu_x - 1, menu_y - 1
        if cell_x < 0 or cell_y < 0 or cell_x + 18 > width or cell_y + 18 > height:
            problems.append("png-cell-outside:%s" % name)
            break
        if gray(cell_x, cell_y) != FRAME:
            problems.append("png-cell-top-left:%s" % name)
            break
        if gray(cell_x + 5, cell_y + 5) != SLOT_BASE:
            problems.append("png-cell-base:%s" % name)
            break
        if gray(cell_x + 17, cell_y + 17) != HIGHLIGHT:
            problems.append("png-cell-highlight:%s" % name)
            break
        if gray(cell_x + 17, cell_y + 5) != HIGHLIGHT:
            problems.append("png-cell-right:%s" % name)
            break
    # 调色板只允许 5 色（多出颜色说明有人手绘/贴错素材）
    palette = set()
    for y in range(height):
        for x in range(width):
            palette.add(gray(x, y))
    if not palette <= {FRAME, HIGHLIGHT, PANEL, PLATE, SLOT_BASE}:
        problems.append("png-palette:%s" % sorted(palette))

    return problems


def main():
    print("=== 第 97 轮自检：容器代理器界面布局（224×222 / 背包上移 / 说明改 tooltip） ===")
    for path in (SCREEN, MENU):
        if not os.path.isfile(path):
            check("源码存在：%s" % os.path.basename(path), False, path)
            print("[result] 自检失败：缺文件")
            return 1
    screen_source = read(SCREEN)
    menu_source = read(MENU)
    zh = json.loads(read(os.path.join(LANG_DIR, "zh_cn.json")))
    en = json.loads(read(os.path.join(LANG_DIR, "en_us.json")))

    problems = layout_problems(screen_source, menu_source, TEXTURE, zh, en)

    print("-" * 78)
    print("[① Screen 尺寸与按钮几何]")
    m = re.search(r"this\.imageWidth\s*=\s*(\d+).*?this\.imageHeight\s*=\s*(\d+)", screen_source, re.S)
    info("Screen imageWidth x imageHeight = %s" % ((m.group(1) + " x " + m.group(2)) if m else "?"))
    print("[② Menu 槽位坐标（从 addSlot 调用解析）]")
    slots, _menu_code = parse_menu_slots(menu_source)
    for name, menu_x, menu_y in slots[:2]:
        info("%-22s 菜单(%d, %d)" % (name, menu_x, menu_y))
    info("玩家背包行首菜单 y = %s，快捷栏菜单 y = %s"
         % (sorted({y for name, x, y in slots if name.startswith("player_inventory")}),
            sorted({y for name, x, y in slots if name.startswith("hotbar")})))
    print("[③ 贴图]")
    info("PNG 结构体尺寸 = %s，调色板校验见判据" % (png_ihdr_size(TEXTURE),)
         if os.path.isfile(TEXTURE) else "缺贴图")
    print("[⑤ 语言键]")
    info("face_tip 条数 zh/en = %d / %d"
         % (len([k for k in zh if ".face_tip." in k]), len([k for k in en if ".face_tip." in k])))

    print("-" * 78)
    check("① Screen imageWidth=224 / imageHeight=222，六面按钮 (108,20) 110×16 步长 18，"
          "状态文字 font.split(...,98) 且 x=10/y=46/最多 4 行",
          not [p for p in problems if p.startswith(("screen-", "face-", "status-", "blit-"))],
          ", ".join([p for p in problems if p.startswith(("screen-", "face-", "status-", "blit-"))][:4]))
    check("② Menu 槽位 y：玩家背包 136/154/172、快捷栏 196、代理槽 (10,22)，旧值 172/230 未回归",
          not [p for p in problems if p.startswith("menu-")],
          ", ".join([p for p in problems if p.startswith("menu-")][:4]))
    check("③ 贴图 224×222、四边外框闭合、底部留白 ≥8px、每格槽位 18×18 与菜单坐标逐格对齐、调色板 5 色",
          not [p for p in problems if p.startswith("png-")],
          ", ".join([p for p in problems if p.startswith("png-")][:4]))
    check("④ 常驻红字彻底删除（无 renderWarning / 无 warning 键 / 无 WARNING_* / 无 pose 缩放），"
          "面说明改由悬停按钮的 face_tip tooltip 承载且在 super.render 之后",
          not [p for p in problems if p.startswith("warning-")],
          ", ".join([p for p in problems if p.startswith("warning-")][:4]))
    check("⑤ 语言文件：warning 键中英都已删除，4 条 face_tip 中英齐全非空、中文 ≤60 字、英文无中日韩字符",
          not [p for p in problems if p.startswith("lang-")],
          ", ".join([p for p in problems if p.startswith("lang-")][:4]))

    # =============================================================== 反例自证
    print("-" * 78)
    print("[反例自证] 把布局 / 文案口径退回旧实现，上面的判据必须判负")
    print("-" * 78)

    def mutate(before, after):
        return screen_source.replace(before, after, 1)

    case = mutate("this.imageHeight = 222;", "this.imageHeight = 248;")
    check("反例 ①a（imageHeight 退回 248）判负",
          case != screen_source
          and any(p.startswith("screen-height") for p in layout_problems(case, menu_source, TEXTURE, zh, en)))

    case = menu_source.replace("136 + row * 18", "172 + row * 18", 1)
    check("反例 ②a（玩家背包退回 y=172）判负",
          case != menu_source and "menu-inv:0,0" in layout_problems(screen_source, case, TEXTURE, zh, en))

    case = menu_source.replace("26 + col * 18, 196", "26 + col * 18, 230", 1)
    check("反例 ②b（快捷栏退回 y=230）判负",
          case != menu_source and "menu-hotbar:0" in layout_problems(screen_source, case, TEXTURE, zh, en))

    case = mutate("        final List<FormattedCharSequence> lines = font.split(status, STATUS_WIDTH);",
                  "        guiGraphics.drawString(font, status, STATUS_X, STATUS_Y, COLOR_LABEL, false);")
    check("反例 ③a（状态文字退回单行不折行）判负",
          case != screen_source and "status-no-split" in layout_problems(case, menu_source, TEXTURE, zh, en))

    case = mutate("        renderFaceTip(guiGraphics, mouseX, mouseY);\n", "")
    check("反例 ④a（删掉面 tooltip 调用）判负",
          case != screen_source and "face-tip-order" in layout_problems(case, menu_source, TEXTURE, zh, en))

    case = screen_source.replace("        renderFaceTip(guiGraphics, mouseX, mouseY);\n", "", 1)
    case = case.replace("        super.render(guiGraphics, mouseX, mouseY, partialTick);",
                        "        renderFaceTip(guiGraphics, mouseX, mouseY);\n"
                        "        super.render(guiGraphics, mouseX, mouseY, partialTick);", 1)
    check("反例 ④b（面 tooltip 画到 super.render 之前 ⇒ 会被背景/按钮盖住）判负",
          case != screen_source
          and "face-tip-order" in layout_problems(case, menu_source, TEXTURE, zh, en))

    zh_bad = dict(zh)
    zh_bad.pop("gui.rs_create_compat.inventory_proxy.face_tip.contents", None)
    check("反例 ⑤a（删掉一条 face_tip 键）判负",
          "lang-face-tip:contents" in layout_problems(screen_source, menu_source, TEXTURE, zh_bad, en))

    zh_back = dict(zh)
    zh_back["gui.rs_create_compat.inventory_proxy.warning"] = "旧红字回来了"
    check("反例 ⑤b（把 warning 键加回去）判负",
          "lang-warning-key-kept" in layout_problems(screen_source, menu_source, TEXTURE, zh_back, en))

    # =============================================================== 汇总
    print("=" * 78)
    if PROBLEMS:
        print("[result] 自检失败：%d 个问题（断言 %d 项）" % (len(PROBLEMS), CHECKS[0]))
        for item in PROBLEMS:
            print("  - %s" % item)
        return 1
    print("[result] 自检通过：%d 项断言（含 8 个源码级反例）；容器代理器界面 224×222 布局、"
          "贴图闭合、说明改 face_tip tooltip 均满足第 97 轮口径" % CHECKS[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
