#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 130 轮 · 「区域条」三态 **像素示意图**（Pillow 版，不是游戏截图）。

为什么要有它
------------
这一轮的两条用户抱怨都是「看不见的几何问题」：
  * 「字很小」  —— 128 宽的侧栏里坐标串被 `RsccTextFit` 缩到 0.6 倍；
  * 「有些没必要的废话」 —— tooltip 6 行；
  * 新加的搜索框 / 删除确认 / 三个动作图标会不会互相压住，光看代码看不出来。
所以本脚本用**源码里的真实常量**（`AreaStripPanel` 的 W / PAD / TITLE_H / SEARCH_H / ROW_H / ACT_*）
+ **真实文案**（`zh_cn.json` 的 `area_strip.*` 键）+ 一份样例区域数据，画三张并排：
  A 常态（5 个区域，第 2 个是「当前在干活」，带 2 个过滤图标，第 4 行展开 6 个坐标框）
  B 删除确认态（第 2 行 armed：红字「确认删除 #2？」+ ✓ / ✗）
  C 搜索过滤中（输入框 `100`，标题「区域 x/5 · 命中 3」，只列命中行）

硬约定（防止图文漂移）
----------------------
* 几何**只从 Java 源码解析**（`AREA_STRIP_W` 这类常量名改了脚本会直接报错，不会静默用旧数字）。
* 文案**只从 `zh_cn.json`** 取，`%1$s` 之类占位符按游戏里的真实参数填。
* 字宽用近似表（CJK 9px / ASCII 6px / 空格 4px，与原版 9px 字体的常见字宽一致），
  并在终端打印「每行文字宽度 vs 可用宽度」——**缩字号（<1.0 倍）会以 WARN 打出来**，
  这就是「字太小」的算术判据。
* 图是示意图：不冒充游戏截图，也不声称验证了渲染顺序。

用法：python tools/render_round130_strip_mock.py
输出：build/round130_area_strip_mock.png（3 倍图）
"""
from __future__ import annotations

import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    print("需要 Pillow：pip install pillow", file=sys.stderr)
    raise

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PANEL_JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq",
                          "rs_create_compat", "client", "screen", "AreaStripPanel.java")
ADV_JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq",
                        "rs_create_compat", "client", "screen",
                        "AdvancedCollectionCacheScreen.java")
LANG_ZH = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                       "lang", "zh_cn.json")
OUT = os.path.join(ROOT, "build", "round130_area_strip_mock.png")

SCALE = 3
FONT_PATH = "C:/Windows/Fonts/msyh.ttc"
#: 符号字形（✎ ✕ ✓ ✗ ◎）在中文字体里缺字 ⇒ 用 Windows 的符号字体兜底；
#: 游戏里这些字形来自 Minecraft 自带的 unicode 页，不存在缺字问题。
SYMBOL_FONT_PATH = "C:/Windows/Fonts/seguisym.ttf"
SYMBOLS = set("✎✕✓✗◎▶→×")

# 面板配色（与 AreaStripPanel 的常量同族：橙 0xFF9F1C + 原版灰）
BG = (0xC6, 0xC6, 0xC6)
BG_DARK = (0x8B, 0x8B, 0x8B)
BORDER = (0x37, 0x37, 0x37)
TEXT = (0x40, 0x40, 0x40)
TITLE = (0x2B, 0x2B, 0x2B)
DIM = (0x6A, 0x6A, 0x6A)
ACCENT = (0xFF, 0x9F, 0x1C)
ROW_CURRENT = (0xD8, 0xC6, 0xA8)
ERROR = (0xB0, 0x00, 0x20)
SLOT_OUTER = (0x8B, 0x8B, 0x8B)
SLOT_INNER = (0x37, 0x37, 0x37)
FIELD_OUTER = (0xA0, 0xA0, 0xA0)
FIELD_INNER = (0x00, 0x00, 0x00)

WARNINGS = []


# ======================================================================================
# ① 几何：只从 Java 源码解析
# ======================================================================================

def read_text(path):
    return io.open(path, encoding="utf-8").read()


def const_int(source, name):
    """取 `[public|private] static final int NAME = N;` 的 N（源码里找不到就报错）。"""
    match = re.search(r"\bint\s+%s\s*=\s*(\d+)\s*;" % re.escape(name), source)
    if match is None:
        raise SystemExit("AreaStripPanel 里找不到常量 %s（脚本与源码已漂移）" % name)
    return int(match.group(1))


def load_geometry():
    source = read_text(PANEL_JAVA)
    names = ["W", "MIN_W", "HARD_MIN_W", "GAP", "SCREEN_MARGIN", "ROW_H", "PAD",
             "TITLE_H", "SEARCH_H", "SEARCH_GAP", "SEARCH_ROWS_GAP",
             "FOOTER_H", "FOOTER_GAP", "ROW_ICON_MAX", "ROW_ICON_PITCH",
             "ACT_ICON", "ACT_PITCH", "ACT_COUNT", "NAME_LINE_H", "EXPAND_H",
             "FIELD_H", "SCROLL_W", "HIGHLIGHT_W", "BG_W", "BG_H"]
    geom = {name: const_int(source, name) for name in names}
    # 文字区宽度与源码同一条公式：contentW() = max(24, w - 2*PAD - SCROLL_W)
    geom["CONTENT_W"] = max(24, geom["W"] - 2 * geom["PAD"] - geom["SCROLL_W"])
    geom["CONTENT_X"] = geom["PAD"]
    # rowsAreaTop() = y + PAD + TITLE_H + 1 + SEARCH_GAP + SEARCH_H + SEARCH_ROWS_GAP
    geom["SEARCH_Y"] = geom["PAD"] + geom["TITLE_H"] + 1 + geom["SEARCH_GAP"]
    geom["ROWS_TOP"] = geom["SEARCH_Y"] + geom["SEARCH_H"] + geom["SEARCH_ROWS_GAP"]
    geom["PANEL_H"] = geom["BG_H"] - 8
    geom["ROWS_BOTTOM"] = geom["PANEL_H"] - geom["PAD"] - geom["FOOTER_H"] - 2
    return geom


# ======================================================================================
# ② 文案：只从 zh_cn.json 取
# ======================================================================================

def load_lang():
    return json.load(io.open(LANG_ZH, encoding="utf-8"))


def fmt(template, *args):
    """把 %1$s / %2$s / 裸 %s 占位符按游戏里的真实参数填掉（zh_cn 里两种风格都有）。

    注意：**必须两种都支持**。第 130 轮第一版只换了 `%<n>$s`，于是标题原样画出「区域 %s/%s · 当前：%s」
    —— 这类「占位符没替换」在示意图里一眼能看出来，也正是本脚本存在的意义（图文漂移预警）。
    """
    out = template
    for index, value in enumerate(args, start=1):
        out = out.replace("%%%d$s" % index, str(value))
    for value in args:
        if "%s" not in out:
            break
        out = out.replace("%s", str(value), 1)
    return out


def lang_of(lang, key):
    full = "gui.rs_create_compat.area_strip." + key
    if full not in lang:
        raise SystemExit("zh_cn.json 缺键 %s（文案与源码已漂移）" % full)
    return lang[full]


# ======================================================================================
# ③ 画布（逻辑像素 ×SCALE）
# ======================================================================================

class Canvas:
    def __init__(self, width_logic, height_logic):
        self.image = Image.new("RGB", (width_logic * SCALE, height_logic * SCALE), (0x1E, 0x1E, 0x1E))
        self.draw = ImageDraw.Draw(self.image)
        self._fonts = {}

    def font(self, size_logic=8):
        size = max(4, int(size_logic * SCALE))
        if size not in self._fonts:
            try:
                self._fonts[size] = ImageFont.truetype(FONT_PATH, size)
            except OSError:  # pragma: no cover
                self._fonts[size] = ImageFont.load_default()
        return self._fonts[size]

    def symbol_font(self, size_logic=8):
        key = "sym%d" % size_logic
        size = max(4, int(size_logic * SCALE))
        if key not in self._fonts:
            try:
                self._fonts[key] = ImageFont.truetype(SYMBOL_FONT_PATH, size)
            except OSError:  # pragma: no cover
                self._fonts[key] = self.font(size_logic)
        return self._fonts[key]

    def rect(self, x, y, w, h, color):
        self.draw.rectangle([x * SCALE, y * SCALE, (x + w) * SCALE - 1, (y + h) * SCALE - 1],
                            fill=color)

    def frame(self, x, y, w, h, color):
        self.draw.rectangle([x * SCALE, y * SCALE, (x + w) * SCALE - 1, (y + h) * SCALE - 1],
                            outline=color)

    @staticmethod
    def advance(char):
        if char == " ":
            return 4
        if ord(char) > 0x2E7F or char in "▶◎✎✕✓✗→":
            return 9
        return 6

    def text_width(self, text):
        return sum(self.advance(c) for c in text)

    def text(self, x, y, text, color=TEXT):
        """按原版字宽近似逐字摆放（CJK / 符号 9px、ASCII 6px、空格 4px），y 是行顶。"""
        cursor = x
        font = self.font(8)
        symbol = self.symbol_font(8)
        for char in text:
            self.draw.text((cursor * SCALE, (y - 1) * SCALE), char, fill=color,
                           font=(symbol if char in SYMBOLS else font))
            cursor += self.advance(char)
        return cursor

    def fit_text(self, x, y, text, avail, color=TEXT, label=""):
        """复刻 `RsccTextFit.drawFitLine` 的口径：放得下 1.0 倍；放不下就缩（下限 0.6），并 WARN。"""
        width = self.text_width(text)
        if width <= avail:
            self.text(x, y, text, color)
            return 1.0
        scale = max(0.6, avail / float(width))
        if scale < 1.0:
            WARNINGS.append("%s: 宽 %dpx > 可用 %dpx ⇒ 缩到 %.2f 倍（%s）"
                            % (label, width, avail, scale, text))
        layer = Image.new("RGBA", (max(1, int(width * scale * SCALE)), 12 * SCALE), (0, 0, 0, 0))
        sub = Canvas.__new__(Canvas)
        sub.image = layer
        sub.draw = ImageDraw.Draw(layer)
        sub._fonts = self._fonts
        sub.text(1, 1, text, color)
        resized = layer.resize((max(1, int(layer.width * scale)),
                                max(1, int(layer.height * scale))), Image.LANCZOS)
        self.image.paste(resized, (x * SCALE, y * SCALE), resized)
        return scale


# ======================================================================================
# ④ 一屏区域条
# ======================================================================================

AREAS = [
    # (rawIndex, 名字, min, max, 过滤数)
    (0, "刷铁机", (120, 60, -340), (140, 76, -320), 2),
    (1, "甘蔗农场", (100, 64, 100), (120, 80, 120), 1),
    (2, "熔岩池边", (-210, 30, 55), (-180, 40, 70), 0),
    (3, "（无名）", (880, 100, 880), (900, 120, 900), 0),
    (4, "刷怪塔", (-500, 20, -500), (-460, 60, -460), 2),
]


def area_name(raw, name, lang):
    if name == "（无名）":
        return fmt(lang["gui.rs_create_compat.area_selector.region"], raw + 1)
    return name


def coord_text(area):
    lo, hi = area[2], area[3]
    return "%d,%d,%d→%d,%d,%d" % (lo[0], lo[1], lo[2], hi[0], hi[1], hi[2])


def draw_panel(canvas, ox, oy, geom, lang, state):
    """state: dict(query=..., current=..., armed=..., show_rows=[...], expanded=None)"""
    w, h = geom["W"], geom["PANEL_H"]
    # 原版九宫格面板的近似：外一圈深色描边 + 浅灰底 + 内侧高光
    canvas.rect(ox, oy, w, h, BG_DARK)
    canvas.rect(ox + 1, oy + 1, w - 2, h - 2, BG)
    canvas.frame(ox, oy, w, h, BORDER)
    canvas.rect(ox + 1, oy + 1, w - 2, 1, (0xFF, 0xFF, 0xFF))

    cx = ox + geom["CONTENT_X"]
    cw = geom["CONTENT_W"]
    title_y = oy + geom["PAD"]

    # ---- 标题 ----
    query = state.get("query", "")
    current = state.get("current", -1)
    total = len(AREAS)
    if query:
        hits = len(state["show_rows"])
        title = fmt(lang_of(lang, "title.filtered"),
                    current + 1 if current >= 0 else 0, total, hits)
    elif current < 0:
        title = fmt(lang_of(lang, "title.none"), total)
    else:
        name = area_name(*[AREAS[current][0], AREAS[current][1]], lang=lang)
        title = fmt(lang_of(lang, "title"), current + 1, total, name)
    canvas.fit_text(cx, title_y, title, cw, TITLE, "标题")
    # 标题下的 1px 橙线（源码同款 0x60FF9F1C）
    canvas.rect(cx, title_y + 10, cw, 1, (0xE7, 0xC6, 0x8F))

    # ---- 搜索框 ----
    sy = oy + geom["SEARCH_Y"]
    canvas.rect(cx, sy, cw, geom["SEARCH_H"], FIELD_OUTER)
    canvas.rect(cx + 1, sy + 1, cw - 2, geom["SEARCH_H"] - 2, FIELD_INNER)
    hint = lang_of(lang, "search.hint")
    if query:
        canvas.text(cx + 3, sy + 2, query, (0xFF, 0xFF, 0xFF))
        canvas.text(cx + cw - 12, sy + 2, "✕", (0xB0, 0xB0, 0xB0))
    else:
        canvas.text(cx + 3, sy + 2, hint, (0x80, 0x80, 0x80))

    # ---- 行 ----
    rows = state.get("show_rows", list(range(len(AREAS))))
    armed = state.get("armed", -1)
    expanded = state.get("expanded", -1)
    top = oy + geom["ROWS_TOP"]
    act_w = geom["ACT_COUNT"] * geom["ACT_PITCH"] + (geom["ACT_COUNT"] - 1)
    current_raw = AREAS[current][0] if current >= 0 else -1
    for list_index in rows:
        raw, name, lo, hi, filters = AREAS[list_index]
        row_h = geom["ROW_H"] + (geom["EXPAND_H"] if list_index == expanded else 0)
        is_current = raw == current_raw
        if is_current:
            canvas.rect(ox + geom["PAD"] - 3, top + 1, w - geom["PAD"] - 1, row_h - 2, ROW_CURRENT)
            canvas.rect(ox + geom["PAD"] - 3, top + 1, geom["HIGHLIGHT_W"], row_h - 2, ACCENT)
            canvas.text(ox + geom["PAD"], top + 2, "▶", ACCENT)
        text_x = cx + (8 if is_current else 2)
        name_w = max(12, cx + cw - text_x - act_w - 2)
        display = area_name(raw, name, lang)
        if list_index == armed:
            # armed：红字「确认删除 #N？」+ 右侧 ✓ / ✗
            canvas.fit_text(text_x, top + 2, fmt(lang_of(lang, "delete.confirm.this"), raw + 1),
                            name_w + act_w, ERROR, "确认文案")
            yes_x = cx + cw - 2 * geom["ACT_PITCH"] - 1
            canvas.rect(yes_x - 1, top - 1, geom["ACT_ICON"], 10, (0x30, 0xFF, 0x9F, 0x1C) and (0xE7, 0xC6, 0x8F))
            canvas.text(yes_x, top + 2, lang_of(lang, "confirm.glyph"), ACCENT)
            canvas.text(yes_x + geom["ACT_PITCH"], top + 2, lang_of(lang, "cancel.glyph"), DIM)
        else:
            canvas.fit_text(text_x, top + 2, "%d. %s" % (raw + 1, display), name_w,
                            TITLE if is_current else TEXT, "名字行")
            ax = cx + cw - act_w
            for index, glyph in enumerate((lang_of(lang, "rename.glyph"),
                                           lang_of(lang, "delete.glyph"))):
                canvas.text(ax + index * geom["ACT_PITCH"], top + 2, glyph, DIM)
        # 坐标行 + 右侧过滤图标摘要：复刻源码的「三段式择优」（整串 → 两端点 → 才缩字）
        coords = coord_text((raw, name, lo, hi))
        joined_w = canvas.text_width(coords)
        low = "%d,%d,%d" % lo
        high = "%d,%d,%d" % hi
        low_w, high_w = canvas.text_width(low), canvas.text_width(high)
        full_w = cx + cw - (cx + 2) - act_w
        icons = min(filters, geom["ROW_ICON_MAX"])
        icon_w = 0 if icons <= 0 else icons * geom["ROW_ICON_PITCH"] + 2
        if joined_w + 2 * geom["ROW_ICON_PITCH"] + 2 > full_w:
            icons = 0
            icon_w = 0
        coord_w = max(12, full_w - icon_w)
        coord_x = cx + 2
        if joined_w <= coord_w:
            canvas.fit_text(coord_x, top + geom["NAME_LINE_H"], coords, coord_w, DIM, "坐标行")
        elif low_w + 2 + high_w <= coord_w:
            canvas.text(coord_x, top + geom["NAME_LINE_H"], low, DIM)
            canvas.text(coord_x + coord_w - high_w, top + geom["NAME_LINE_H"], high, DIM)
        else:
            canvas.fit_text(coord_x, top + geom["NAME_LINE_H"], coords, coord_w, DIM, "坐标行")
        for index in range(icons):
            ix = cx + cw - (icons - index) * geom["ROW_ICON_PITCH"]
            canvas.rect(ix, top + geom["NAME_LINE_H"], 8, 8, (0x5A, 0x8A, 0x3C))

        # 展开：6 个数字框（两行 × 3 列）
        if list_index == expanded:
            label_w = 14
            field_w = max(18, (cw - label_w - 4) // 3)
            for line, label_key in ((0, "from"), (1, "to")):
                canvas.text(cx, top + geom["ROW_H"] + 3 + line * (geom["FIELD_H"] + 2),
                            lang_of(lang, label_key), DIM)
                for column in range(3):
                    fx = cx + label_w + column * (field_w + 2)
                    fy = top + geom["ROW_H"] + line * (geom["FIELD_H"] + 2)
                    canvas.rect(fx, fy, field_w, geom["FIELD_H"], FIELD_OUTER)
                    canvas.rect(fx + 1, fy + 1, field_w - 2, geom["FIELD_H"] - 2, FIELD_INNER)
                    value = (lo[column] if line == 0 else hi[column])
                    canvas.text(fx + 2, fy + 2, str(value), (0xFF, 0xFF, 0xFF))
        top += row_h

    # ---- 底部按钮 ----
    footer_y = oy + geom["PANEL_H"] - geom["PAD"] - geom["FOOTER_H"]
    btn_w = max(24, (cw - geom["FOOTER_GAP"]) // 2)
    for index, key in enumerate(("show_all", "close")):
        bx = cx + index * (btn_w + geom["FOOTER_GAP"])
        canvas.rect(bx, footer_y, btn_w, geom["FOOTER_H"], (0x8B, 0x8B, 0x8B))
        canvas.rect(bx, footer_y, btn_w, 1, BORDER)
        canvas.text(bx + 2, footer_y + 4, lang_of(lang, key), TITLE)
    return top


# ======================================================================================
# ⑤ 主流程
# ======================================================================================

def main():
    geom = load_geometry()
    lang = load_lang()
    panels = [
        ("A 常态（第 4 行展开改坐标）",
         dict(current=1, expanded=3, query="")),
        ("B 删除确认（红字 + ✓/✗，3 秒回退）",
         dict(current=1, armed=1, query="")),
        ("C 搜索 100（标题显示命中数）",
         dict(current=1, query="100", show_rows=[1, 2, 4])),
    ]
    gap = 16
    header = 40
    total_w = len(panels) * geom["W"] + (len(panels) - 1) * gap + 2 * 12
    total_h = geom["PANEL_H"] + 2 * 12 + header
    canvas = Canvas(total_w, total_h)
    canvas.rect(0, 0, total_w, total_h, (0x1E, 0x1E, 0x1E))
    canvas.text(12, 10, "第 130 轮 · 区域条三态（几何/文案取自 AreaStripPanel.java 与 zh_cn.json）",
                (0xF0, 0xF0, 0xF0))
    for index, (caption, state) in enumerate(panels):
        ox = 12 + index * (geom["W"] + gap)
        oy = 12 + header
        canvas.text(ox, 28, caption, (0xE0, 0xE0, 0xE0))
        draw_panel(canvas, ox, oy, geom, lang, state)

    canvas.image.save(OUT)
    print("已生成 %s  (%dx%d, %d 倍图)" % (OUT, total_w, total_h, SCALE))
    print("几何（全部来自 AreaStripPanel）：W=%d MIN_W=%d HARD_MIN_W=%d 内容宽=%d 行区=%d..%d"
          % (geom["W"], geom["MIN_W"], geom["HARD_MIN_W"], geom["CONTENT_W"],
             geom["ROWS_TOP"], geom["ROWS_BOTTOM"]))
    print("可放行数：%d（(行区高 %d) / ROW_H %d）"
          % ((geom["ROWS_BOTTOM"] - geom["ROWS_TOP"]) // geom["ROW_H"],
             geom["ROWS_BOTTOM"] - geom["ROWS_TOP"], geom["ROW_H"]))
    if WARNINGS:
        print("")
        print("WARN 有 %d 处文字需要缩字号（<1.0 倍）—— 这就是「字很小」的算术判据：" % len(WARNINGS))
        for line in WARNINGS:
            print("  " + line)
        return 1
    print("")
    print("OK 所有文字按 1.0 倍放得下（没有一处触发缩字号）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
