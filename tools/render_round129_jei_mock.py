#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 129 轮 · JEI「降解发电」页 **像素示意图**（Pillow 版，不是游戏截图）。

为什么要有它
------------
用户这一轮要的是「点箭头 → 跳 JEI → 看到用什么、发多少电、耗多久」。文字说十遍不如画一张：
本脚本用**源码里那套常量**（`DecompositionGeneratorJeiCategory` 的 WIDTH / 各槽位 / 各行 y）
+ **真实数据**（`data/rs_create_compat/decomposition/builtin_plants.json`）
+ **真实文案**（`zh_cn.json` 的 `jei.*` 键）渲染三页，供肉眼核对「对齐 / 不重叠 / 装得下」。

硬约定（防止图文漂移）
----------------------
* 几何**只从 Java 源码解析**：脚本里不写 180 / 66 / 56 这类数字；常量改了，图跟着改。
* 数值格式**复刻** `client/RsccJeiText`（千分位 / 耗时 1 位小数 / 平均功率去尾零），
  与游戏里 `String.format(Locale.ROOT, ...)` 的输出逐字一致。
* 物品图标 / 熔炉箭头从**本机客户端 jar** 里取原版贴图（`versions/Create：Enjoy/Create：Enjoy.jar`），
  找不到就画占位方块并打一行 WARN（图仍可看，但不冒充"真图标"）。
* 字体：本机 `C:/Windows/Fonts/msyh.ttc` 近似原版字体的**字宽表**（CJK 9px / ASCII 6px / 空格 4px）。

用法：python tools/render_round129_jei_mock.py
输出：build/round129_jei_mock.png（3 倍图）
"""
from __future__ import annotations

import io
import json
import os
import re
import sys
import zipfile

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    print("需要 Pillow：pip install pillow", file=sys.stderr)
    raise

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
CATEGORY = os.path.join(JAVA, "client", "DecompositionGeneratorJeiCategory.java")
LANG_ZH = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang",
                       "zh_cn.json")
DATA = os.path.join(ROOT, "src", "main", "resources", "data", "rs_create_compat", "decomposition",
                    "builtin_plants.json")
MOD_TEXTURES = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                            "textures", "block")
OUT = os.path.join(ROOT, "build", "round129_jei_mock.png")
CLIENT_JAR = r"D:\minecraft\.minecraft\versions\Create：Enjoy\Create：Enjoy.jar"

SCALE = 3
FONT_PATH = "C:/Windows/Fonts/msyh.ttc"
TEXT_COLOR = (0x40, 0x40, 0x40)
PANEL = (0xC6, 0xC6, 0xC6)
BORDER = (0x40, 0x40, 0x40)
SLOT_OUTER = (0x8B, 0x8B, 0x8B)
SLOT_INNER = (0x37, 0x37, 0x37)
CAPTION = (0x10, 0x10, 0x10)


# ======================================================================================
# ① 从 Java 源码解析几何常量（不允许在本脚本里重写这些数字）
# ======================================================================================

_CONST_RE = re.compile(r"static\s+final\s+int\s+([A-Za-z_$][\w$]*)\s*=\s*([^;]+);")


def load_geometry(path):
    with io.open(path, encoding="utf-8") as handle:
        text = re.sub(r"/\*.*?\*/", "", handle.read(), flags=re.S)
    raw = {}
    for match in _CONST_RE.finditer(text):
        raw[match.group(1)] = match.group(2).strip()

    resolved = {}

    def resolve(name, depth=0):
        if name in resolved:
            return resolved[name]
        if depth > 8 or name not in raw:
            raise KeyError(name)
        expr = raw[name]
        # 只认「前面不是单词字符」的标识符：否则十六进制字面量 0xFF404040 会被当成
        # 标识符 xFF404040（Mock 脚本自己踩过一次）。
        for ident in set(re.findall(r"(?<![\w$])([A-Za-z_$][\w$]*)", expr)):
            expr = re.sub(r"\b%s\b" % re.escape(ident), str(resolve(ident, depth + 1)), expr)
        if not re.fullmatch(r"[0-9a-fA-Fx+\-*/ ().]+", expr):
            raise ValueError("无法求值的常量 %s = %s" % (name, raw[name]))
        value = int(eval(expr, {"__builtins__": {}}, {}))  # noqa: S307 - 只含数字与四则运算
        resolved[name] = value
        return value

    for name in raw:
        resolve(name)
    return resolved


# ======================================================================================
# ② 复刻 RsccJeiText / 语言文案
# ======================================================================================

def grouped(value):
    return "{:,}".format(int(value))


def one_decimal(value):
    return "%.1f" % value


def rate(value):
    text = "%.2f" % value
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text or "0"


def placeholders(template, *args):
    """Minecraft 的 translatable 用 String.format：`%s` 依次取参，`%%` 是字面百分号。"""
    out = []
    index = 0
    i = 0
    while i < len(template):
        char = template[i]
        if char == "%" and i + 1 < len(template):
            nxt = template[i + 1]
            if nxt == "s":
                out.append(str(args[index]))
                index += 1
                i += 2
                continue
            if nxt == "%":
                out.append("%")
                i += 2
                continue
        out.append(char)
        i += 1
    return "".join(out)


def load_lang(path):
    with io.open(path, encoding="utf-8") as handle:
        return json.load(handle)


def load_entries(path):
    with io.open(path, encoding="utf-8") as handle:
        doc = json.load(handle)
    entries = []
    for entry in doc.get("entries", []):
        results = []
        if "results" in entry:
            results = list(entry["results"])
        elif "result" in entry:
            results = [entry["result"]]
        key = entry.get("item") or ("#" + entry["tag"])
        entries.append({
            "key": key,
            "time": int(entry["time"]),
            "energy": int(entry["energy"]),
            "results": results,
        })
    return entries


# ======================================================================================
# ③ 贴图
# ======================================================================================

class Textures:
    def __init__(self):
        self._client = None
        self.missing = []

    def _jar(self):
        if self._client is None:
            self._client = zipfile.ZipFile(CLIENT_JAR) if os.path.isfile(CLIENT_JAR) else False
        return self._client

    def vanilla(self, path, quiet=False):
        jar = self._jar()
        if jar is False:
            if not quiet:
                self.missing.append(path)
            return None
        try:
            with jar.open("assets/minecraft/" + path) as handle:
                return Image.open(io.BytesIO(handle.read())).convert("RGBA")
        except KeyError:
            if not quiet:
                self.missing.append(path)
            return None

    def mod_block(self, name):
        path = os.path.join(MOD_TEXTURES, name)
        if not os.path.isfile(path):
            self.missing.append(name)
            return None
        return Image.open(path).convert("RGBA")


def item_icon(tex, item_id):
    """物品图标（16×16）。`minecraft:x` → 原版贴图（先 textures/item，再退回 textures/block，
    因为方块类物品的图标就是方块贴图）；`mod:x` → 本工程贴图；缺图则占位。"""
    namespace, path = item_id.split(":", 1)
    if namespace == "minecraft":
        image = tex.vanilla("textures/item/%s.png" % path, quiet=True)
        if image is None:
            # 方块类物品的图标是方块贴图；有些方块只有 _top / _side / _front 变体
            for candidate in (path, path + "_top", path + "_side", path + "_front"):
                image = tex.vanilla("textures/block/%s.png" % candidate, quiet=True)
                if image is not None:
                    break
    else:
        image = tex.mod_block("%s.png" % path)
    if image is None:
        tex.missing.append(item_id)
        image = Image.new("RGBA", (16, 16), (0x70, 0x70, 0x70, 0xFF))
    return image


# ======================================================================================
# ④ 画布小工具（全部逻辑像素 ×SCALE）
# ======================================================================================

class Canvas:
    def __init__(self, width_logic, height_logic):
        self.width = width_logic
        self.height = height_logic
        self.image = Image.new("RGB", (width_logic * SCALE, height_logic * SCALE), (0x1E, 0x1E, 0x1E))
        self.draw = ImageDraw.Draw(self.image)
        self._fonts = {}

    def font(self, size_logic=8):
        size = int(size_logic * SCALE)
        if size not in self._fonts:
            try:
                self._fonts[size] = ImageFont.truetype(FONT_PATH, size)
            except OSError:  # pragma: no cover
                self._fonts[size] = ImageFont.load_default()
        return self._fonts[size]

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
        if ord(char) > 0x2E7F:
            return 9
        return 6

    def text_width(self, text):
        return sum(self.advance(char) for char in text)

    def text(self, x, y, text, color=TEXT_COLOR):
        """按原版字宽近似逐字摆放（CJK 9px / ASCII 6px / 空格 4px），y 是行顶。"""
        cursor = x
        font = self.font(8)
        for char in text:
            self.draw.text((cursor * SCALE, (y - 1) * SCALE), char, fill=color, font=font)
            cursor += self.advance(char)
        return cursor

    def slot(self, x, y, icon=None):
        """原版 18×18 槽：外框浅灰 + 内底深灰 + 左上高光 / 右下阴影。"""
        self.rect(x, y, 18, 18, SLOT_OUTER)
        self.rect(x + 1, y + 1, 16, 16, SLOT_INNER)
        self.draw.line([x * SCALE, y * SCALE, (x + 18) * SCALE - 1, y * SCALE],
                       fill=(0xA8, 0xA8, 0xA8))
        self.draw.line([x * SCALE, y * SCALE, x * SCALE, (y + 18) * SCALE - 1],
                       fill=(0xA8, 0xA8, 0xA8))
        self.draw.line([(x + 1) * SCALE, (y + 17) * SCALE, (x + 18) * SCALE - 1, (y + 17) * SCALE],
                       fill=(0x5E, 0x5E, 0x5E))
        if icon is not None:
            enlarged = icon.resize((16 * SCALE, 16 * SCALE), Image.NEAREST)
            self.image.paste(enlarged, ((x + 1) * SCALE, (y + 1) * SCALE), enlarged)


def arrow_image(tex):
    """原版熔炉箭头（furnace.png 的 79,34 24×16）—— JEI 的 addRecipeArrow 用的是同一枚原版箭头。"""
    sheet = tex.vanilla("textures/gui/container/furnace.png")
    if sheet is None:
        return Image.new("RGBA", (24, 16), (0xB0, 0xB0, 0xB0, 0xFF))
    return sheet.crop((79, 34, 79 + 24, 34 + 16))


# ======================================================================================
# ⑤ 一页配方
# ======================================================================================

def entry_to_texts(entry, lang):
    energy = placeholders(lang["jei.rs_create_compat.decomposition_generator.energy"],
                         grouped(entry["energy"]))
    time = placeholders(lang["jei.rs_create_compat.decomposition_generator.time"],
                       entry["time"], one_decimal(entry["time"] / 20.0))
    avg = placeholders(lang["jei.rs_create_compat.decomposition_generator.rate"],
                       rate(entry["energy"] / entry["time"]))
    return [energy, time, avg]


def draw_page(canvas, ox, oy, entry, lang, geom, tex, arrow, note):
    """画一页配方。<b>只画分类页真正画的东西</b>：标题由 JEI 画在页外，这里不画；
    tag 候选在真实游戏里是「同一格里轮播」，所以这里也只有一格，候选数写在页脚注释里。"""
    width = geom["WIDTH"]
    height = geom["HEIGHT"]
    # JEI 的配方背景是浅灰矩形 + 一圈边框（needsRecipeBorder 的默认行为）
    canvas.rect(ox, oy, width, height, PANEL)
    canvas.frame(ox, oy, width, height, BORDER)

    # 输入槽：一格（tag 条目在 JEI 里轮播整组候选）
    inputs = entry["inputs"]
    canvas.slot(ox + geom["INPUT_X"], oy + geom["INPUT_Y"], item_icon(tex, inputs[0]))

    # 箭头（与 JEI addRecipeArrow 同一枚原版箭头）
    enlarged = arrow.resize((24 * SCALE, 16 * SCALE), Image.NEAREST)
    canvas.image.paste(enlarged, ((ox + geom["ARROW_X"]) * SCALE, (oy + geom["ARROW_Y"]) * SCALE),
                       enlarged)

    # 三行文字
    lines = entry_to_texts(entry, lang)
    canvas.text(ox + geom["TEXT_X"], oy + geom["TEXT_ENERGY_Y"], lines[0])
    canvas.text(ox + geom["TEXT_X"], oy + geom["TEXT_TIME_Y"], lines[1])
    canvas.text(ox + geom["TEXT_X"], oy + geom["TEXT_RATE_Y"], lines[2])

    # 副产物（概率在真实游戏里是**槽的 tooltip**，这里不画进页面，写进页脚注释）
    results = entry["results"]
    if not results:
        canvas.text(ox + geom["BYPRODUCT_LABEL_X"], oy + geom["BYPRODUCT_LABEL_Y"],
                    lang["jei.rs_create_compat.decomposition_generator.no_byproduct"])
        return
    canvas.text(ox + geom["BYPRODUCT_LABEL_X"], oy + geom["BYPRODUCT_LABEL_Y"],
                lang["jei.rs_create_compat.decomposition_generator.byproduct"])
    for index, result in enumerate(results[:geom["BYPRODUCT_MAX"]]):
        x = ox + geom["BYPRODUCT_X"] + geom["BYPRODUCT_STEP"] * index
        canvas.slot(x, oy + geom["BYPRODUCT_Y"], item_icon(tex, result["item"]))
    hidden = len(results) - geom["BYPRODUCT_MAX"]
    if hidden > 0:
        canvas.text(ox + geom["BYPRODUCT_MORE_X"], oy + geom["BYPRODUCT_LABEL_Y"],
                    placeholders(lang["jei.rs_create_compat.decomposition_generator.more_byproduct"],
                                 hidden))


# ======================================================================================
# ⑥ 组装成一张图
# ======================================================================================

SAMPLES = (
    ("#minecraft:saplings",
     "tag 条目：4 种树苗在同一格里轮播（游戏里的副产物提示：骨粉 ×1，概率 50%）"),
    ("#minecraft:leaves",
     "tag 条目：整组树叶轮播（游戏里的副产物提示：木棍 ×1，必定掉落）"),
    ("minecraft:hay_block",
     "单物品条目：180 刻 / 450 FE（游戏里的副产物提示：骨粉 ×9，必定掉落）"),
)

FOOTER = ("示意图：几何取自 DecompositionGeneratorJeiCategory 的源码常量；数值与文案取自数据包和 zh_cn；"
          "字体用本机 msyh 近似。不是游戏截图 —— 副产物概率 tooltip、JEI 页签 / 翻页按钮没有画。")


def resolve_entries(raw_entries, lang):
    """把数据包条目整理成「展示形状」（tag 条目要用真实 tag 展开 —— 这里用一份内置的代表物品表）。"""
    tag_members = {
        "#minecraft:saplings": ["minecraft:oak_sapling", "minecraft:spruce_sapling",
                                "minecraft:birch_sapling", "minecraft:jungle_sapling"],
        "#minecraft:leaves": ["minecraft:oak_leaves", "minecraft:spruce_leaves",
                              "minecraft:birch_leaves", "minecraft:jungle_leaves"],
    }
    out = []
    for entry in raw_entries:
        key = entry["key"]
        inputs = [key] if not key.startswith("#") else tag_members.get(key, [])
        out.append(dict(entry, inputs=inputs))
    return out


def wrap_text(canvas, text, max_width):
    """按节宽换行（CJK 逐字、ASCII 也不在单词中间断得太难看 —— 这里按字断，够用即可）。"""
    lines = []
    current = ""
    width = 0
    for char in text:
        advance = canvas.advance(char)
        if width + advance > max_width and current:
            lines.append(current)
            current = ""
            width = 0
        current += char
        width += advance
    if current:
        lines.append(current)
    return lines


def draw_sheet(geom, lang, entries, tex):
    pages = []
    for key, caption in SAMPLES:
        match = next((entry for entry in entries if entry["key"] == key), None)
        if match is None or not match["inputs"]:
            print("WARN 样例不存在 / 展开为空：%s" % key)
            continue
        pages.append((match, caption))

    width = geom["WIDTH"]
    height = geom["HEIGHT"]
    header = 16          # JEI 在配方区域上方画分类标题 + 页签；示意里用一行标题代表
    gap = 10
    line = 11            # 注释行行距

    # 先量好所有文字，再决定画布尺寸（避免图被右/下裁掉 —— 第一版就是这样被裁的）
    probe = Canvas(10, 10)
    wrapped_captions = [wrap_text(probe, caption, width) for _entry, caption in pages]
    caption_lines = max((len(lines) for lines in wrapped_captions), default=1)
    footer_lines = wrap_text(probe, FOOTER, width)
    group_height = header + height + 3 + caption_lines * line + 4
    total_w = gap + width + gap
    total_h = gap + len(pages) * (group_height + gap) + len(footer_lines) * line + gap

    canvas = Canvas(total_w, total_h)
    arrow = arrow_image(tex)
    for index, ((entry, caption), lines) in enumerate(zip(pages, wrapped_captions)):
        ox = gap
        oy = gap + index * (group_height + gap)
        title = lang["jei.rs_create_compat.decomposition_generator.title"]
        canvas.text(ox + 1, oy + 3, title, color=(0xF0, 0xF0, 0xF0))
        canvas.text(ox + 1 + canvas.text_width(title) + 6, oy + 3, ">" + entry["key"],
                    color=(0x9A, 0x9A, 0x9A))
        draw_page(canvas, ox, oy + header, entry, dict(lang), geom, tex, arrow, caption)
        for line_index, text in enumerate(lines):
            canvas.text(ox, oy + header + height + 3 + line_index * line, text,
                        color=(0xC8, 0xC8, 0xC8))

    footer_y = total_h - gap - len(footer_lines) * line
    for line_index, text in enumerate(footer_lines):
        canvas.text(gap, footer_y + line_index * line, text, color=(0xA0, 0xC0, 0xE0))
    return canvas.image


def main() -> int:
    geom = load_geometry(CATEGORY)
    lang = load_lang(LANG_ZH)
    entries = resolve_entries(load_entries(DATA), lang)
    tex = Textures()
    image = draw_sheet(geom, lang, entries, tex)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    image.save(OUT)
    print("几何：WIDTH=%d HEIGHT=%d INPUT=(%d,%d) ARROW=(%d,%d) TEXT_X=%d BYPRODUCT=(%d,%d,+%d)"
          % (geom["WIDTH"], geom["HEIGHT"], geom["INPUT_X"], geom["INPUT_Y"], geom["ARROW_X"],
             geom["ARROW_Y"], geom["TEXT_X"], geom["BYPRODUCT_X"], geom["BYPRODUCT_Y"],
             geom["BYPRODUCT_STEP"]))
    if tex.missing:
        print("WARN 缺贴图 %d 个（已用占位方块）：%s" % (len(tex.missing), ", ".join(tex.missing[:6])))
    print("OK " + OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())
