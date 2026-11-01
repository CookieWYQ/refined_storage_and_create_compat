#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 125 轮契约自检：区域条（AreaStrip）取代独立区域界面。

**规格来源**：`docs/ROUND125_AREA_STRIP_CONTRACT.md`（唯一权威；本脚本的每一条断言都对应
契约里的某一节，失败信息里写明「契约第几节 / 期望 / 实际」）。

本脚本是**规格守卫**：只读源码 + 语言文件，不改 `src/**` 一个字节（写作用域只到 `tools/`、`docs/`）。
实现在另一条线上（`area-strip`），因此：

* **实现未就绪时跑红是正常的**（文件不存在 / 方法不存在 ⇒ 断言失败，反例跳过）；
* 实现落定后由 Lead 跑：要求 **≥ 40 断言 / ≥ 15 反例 / 0 失败**（契约 §6.1）。

覆盖（与契约逐节对应）
----------------------
§2 几何字面量：`W=128` / `MIN_W=96` / `HARD_MIN_W=72` / `GAP=6` / `SCREEN_MARGIN=4` /
   `ROW_H=22` / `PAD=4` 逐个断言；`x = leftPos + BG_W + GAP`、`avail = screenW - SCREEN_MARGIN - x`、
   `w = clamp(avail, MIN_W, W)`、`avail < HARD_MIN_W ⇒ visible=false`、`y = topPos + 4`、
   `h = BG_H - 8`、矩形**每帧**写 `AreaStripBounds`（不可见写 null）。
§3.1 `AreaStripBounds`：`public final class`、字段 `volatile`、`set/get/clear` 三个 **static** 签名 +
   null 语义（`clear()` 置 null、`get()` 返回字段）+ `removed()` 调 `clear()`。
§3.2 `AreaStripPanel`：不继承 `AbstractWidget`；构造器 + `width/layout/render/mouseClicked/
   mouseScrolled/keyPressed/isEditing/removed` 九个方法签名逐字；`AdvancedCollectionCacheScreen`
   六处转发（init / render 的 layout+render 顺序 / mouseClicked / mouseScrolled / keyPressed /
   removed）各一条；`new AreaSelectorScreen` 在机器界面里 **0 次**；`(186,219)/(218,219)` 两颗按钮删除。
§3.3 JEI 避让：`addGuiContainerHandler` + `AdvancedCollectionCacheScreen.class` + `getGuiExtraAreas`
   + 返回 `AreaStripBounds.get()` + null 时 `List.of()`（绝不返回 null）。
§4 行为：槽空不画侧栏、点行发 `SetAreaFilterContextPacket`（与 `AreaSelectorScreen` 同一条链路）、
   改名**打字时捕获 rawIndex** 且提交后复位、✕ 二次确认 3 秒、`AreaOutline.chase` 每帧重喂 +
   slot `strip/focus`、关界面清框、行高亮 `0xFF9F1C`/`0x30FF9F1C`/`▶`、「显示全部」5 秒、滚动、
   6 个数字框、数据源只读 `AdvancedAreaSnapshotPacket#lastFor`（不读方块实体 / 不判断可用性）。
§4.5 标记编辑就地做（**第 130 轮已整页删除**；本节只保留「退役范围」三条：
   `AreaMarkScreen.java` 文件保留、机器路径 0 次 `new AreaMarkScreen`、`AreaMarkEditPacket`
   线格式仍 5 参。网格 / 接缝 / 新包的判据移交 tools/selfcheck_round130_area_strip_rework.py）：
反例（≥15，实际 38）：把上面每一条改坏都必须**判负**（常量改数字、volatile 删掉、clear 空实现、
   JEI 回 null、机器路径复活 `new AreaSelectorScreen` / `new AreaMarkScreen`、转发少一条、
   keyPressed 不判 isEditing、`(186,219)` 按钮复活、removed 不清框、方法体空实现、Rect2i 用 GUI
   内部坐标、点行不发包、槽空仍画、改名不复位、关界面不清框、客户端自己判断可用性、`visible`
   判据删掉、标记网格用 `globalIndex`、侧栏分支发旧包 / 匹配区通道、新包少分量、旧包扩字段、
   新包不实现 `CustomPacketPayload`、`AreaMarkScreen` 被删）。

纪律：不跑 gradlew / javac（Lead 统一编译）、不 git、不改 `src/**`、不改 docs 正文（只新建本任务的两份）。
锚点与实现写法冲突时：**以契约原文为准改锚点，不要放宽判据**（并在汇报里写明改了哪条锚点）。
用法：`python tools/selfcheck_round125_area_strip.py`
退出码：0 = 全部通过；最后一行：ALL_PASS
"""

from __future__ import annotations

import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_REL = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
JAVA = os.path.join(ROOT, JAVA_REL)

#: 契约书位置（失败信息里引用它）。
CONTRACT = "docs/ROUND125_AREA_STRIP_CONTRACT.md"

#: 本轮涉及的文件（键名稳定；反例/断言都按这个键取内容）。
FILES = (
    ("bounds", os.path.join(JAVA, "client", "AreaStripBounds.java")),
    ("panel", os.path.join(JAVA, "client", "screen", "AreaStripPanel.java")),
    ("adv", os.path.join(JAVA, "client", "screen", "AdvancedCollectionCacheScreen.java")),
    ("jei", os.path.join(JAVA, "client", "CollectionCacheJeiPlugin.java")),
    ("base", os.path.join(JAVA, "client", "screen", "CollectionCacheScreen.java")),
    ("area", os.path.join(JAVA, "client", "screen", "AreaSelectorScreen.java")),
    #: §4.5：`AreaMarkScreen` **文件必须保留**（主手世界路径仍用它），只是机器路径不再打开它。
    ("mark", os.path.join(JAVA, "client", "screen", "AreaMarkScreen.java")),
    #: §4.5 旧包：线格式必须仍是 5 参（反例：被扩字段）。
    ("maredit", os.path.join(JAVA, "network", "AreaMarkEditPacket.java")),
    #: §4.5-5 新包的 payload 注册行由 Lead 补进这里（area-strip 不改该文件）。
    ("mod", os.path.join(JAVA, "RS_Create_Compat.java")),
)

#: §2 的字面量契约（键 = 常量名，值 = 字面量）。**不许改这些数字**（改就是改契约）。
GEOMETRY = (
    # 第 130 轮：128 → 176（用户「有一些字很小」；128 宽必然把坐标行缩到 0.6 倍），
    # MIN_W / HARD_MIN_W 同比上调。新契约见 docs/ROUND130_AREA_STRIP_REWORK.md §2 与
    # tools/selfcheck_round130_area_strip_rework.py。
    ("W", 176),
    ("MIN_W", 140),
    ("HARD_MIN_W", 108),
    ("GAP", 6),
    ("SCREEN_MARGIN", 4),
    ("ROW_H", 22),
    ("PAD", 4),
)

#: §3.2 `AreaStripPanel` 的九方法（名字 → 签名正则以「契约签名逐字」为准，允许 `final` 修饰）。
PANEL_METHODS = (
    ("构造器 AreaStripPanel(AdvancedCollectionCacheScreen)",
     r"public\s+AreaStripPanel\s*\(\s*(?:final\s+)?AdvancedCollectionCacheScreen\s+\w+\s*\)"),
    ("width()", r"public\s+int\s+width\s*\(\s*\)"),
    ("layout(leftPos, topPos, screenW, screenH)",
     r"public\s+void\s+layout\s*\(\s*(?:final\s+)?int\s+\w+\s*,\s*(?:final\s+)?int\s+\w+\s*,"
     r"\s*(?:final\s+)?int\s+\w+\s*,\s*(?:final\s+)?int\s+\w+\s*\)"),
    ("render(GuiGraphics, int, int, float)",
     r"public\s+void\s+render\s*\(\s*(?:final\s+)?GuiGraphics\s+\w+\s*,\s*(?:final\s+)?int\s+\w+\s*,"
     r"\s*(?:final\s+)?int\s+\w+\s*,\s*(?:final\s+)?float\s+\w+\s*\)"),
    ("mouseClicked(double, double, int)",
     r"public\s+boolean\s+mouseClicked\s*\(\s*(?:final\s+)?double\s+\w+\s*,"
     r"\s*(?:final\s+)?double\s+\w+\s*,\s*(?:final\s+)?int\s+\w+\s*\)"),
    ("mouseScrolled(double, double, double)",
     r"public\s+boolean\s+mouseScrolled\s*\(\s*(?:final\s+)?double\s+\w+\s*,"
     r"\s*(?:final\s+)?double\s+\w+\s*,\s*(?:final\s+)?double\s+\w+\s*\)"),
    ("keyPressed(int, int, int)",
     r"public\s+boolean\s+keyPressed\s*\(\s*(?:final\s+)?int\s+\w+\s*,"
     r"\s*(?:final\s+)?int\s+\w+\s*,\s*(?:final\s+)?int\s+\w+\s*\)"),
    ("isEditing()", r"public\s+boolean\s+isEditing\s*\(\s*\)"),
    ("removed()", r"public\s+void\s+removed\s*\(\s*\)"),
)

CHECKS = [0]
COUNTER = [0]
SKIPS = [0]
FAILS = []


# ======================================================================================
# 基础工具（与 round120 / round124 同一套口径：去注释 / 取方法体 / 找常量）
# ======================================================================================

def read(path):
    """读文本；文件不存在返回空串（实现未就绪时不让脚本崩）。"""
    if not os.path.isfile(path):
        return ""
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """去注释（保留字符串字面量），用于「语句 / 方法存在性」判据。"""
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        two = text[i:i + 2]
        if quote is None and two == "//":
            end = text.find("\n", i)
            if end < 0:
                break
            i = end
            continue
        if quote is None and two == "/*":
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        ch = text[i]
        if quote is None and ch in "\"'":
            quote = ch
        elif quote == ch and text[i - 1] != "\\":
            quote = None
        out.append(ch)
        i += 1
    return "".join(out)


def method_body(code, pattern):
    """按正则找方法起点，再从第一个 '{' 配平返回方法体；找不到返回 None。"""
    match = re.search(pattern, code)
    if not match:
        return None
    brace = code.find("{", match.start())
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[match.start():i + 1]
    return None


def const_int(code, name):
    """取 `int NAME = 123;` 的字面量（不存在返回 None）。"""
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*(\d+)\s*;", code)
    return int(match.group(1)) if match else None


def count(source, token):
    return source.count(token)


def has(source, pattern):
    return re.search(pattern, source) is not None


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS " + name)
    else:
        FAILS.append(name)
        print("FAIL " + name + (("  <- " + str(detail)) if detail else ""))


def counterexample(name, ok, detail=""):
    COUNTER[0] += 1
    check("反例 " + name, ok, detail)


def skip(name, why):
    SKIPS[0] += 1
    print("SKIP " + name + (("  <- " + str(why)) if why else ""))


def add(checks, section, name, ok, expected, actual):
    """统一构造断言：失败信息固定写「契约第几节 / 期望 / 实际」。"""
    checks.append((
        "§%s %s" % (section, name),
        bool(ok),
        "契约 §%s：期望 %s；实际 %s" % (section, expected, actual),
    ))


# ======================================================================================
# 源码装载
# ======================================================================================

def load_sources():
    """返回 {文件键: 去注释后的源码}（缺失文件 = 空串，由断言给出「文件不存在」的失败信息）。"""
    return dict((key, strip_comments(read(path))) for key, path in FILES)


def missing_files():
    return [os.path.relpath(path, ROOT).replace("\\", "/")
            for _key, path in FILES if not os.path.isfile(path)]


# ======================================================================================
# ① §2 几何契约
# ======================================================================================

def _geometry_checks(src, checks):
    panel = src["panel"]
    base = src["base"]

    for name, expected in GEOMETRY:
        actual = const_int(panel, name)
        add(checks, "2", "%s = %d（字面量，不得改）" % (name, expected),
            actual == expected, "%s = %d" % (name, expected),
            "%s（判据：面板里必须能找到 `int %s = %d;`）" % (actual, name, expected))

    declared = re.findall(r"public\s+static\s+final\s+int\s+(W|MIN_W|HARD_MIN_W|GAP|SCREEN_MARGIN|ROW_H|PAD)\s*=",
                          panel)
    add(checks, "2", "7 个常量都是 `public static final int`（跨文件可见，JEI / 界面共用同一份）",
        len(set(declared)) == 7, "7 个 `public static final int`",
        "只找到 %d 个：%s" % (len(set(declared)), sorted(set(declared))))

    bg_w = const_int(base, "BG_W")
    bg_h = const_int(base, "BG_H")
    add(checks, "2", "GUI 本体尺寸不变：`CollectionCacheScreen.BG_W = 256`",
        bg_w == 256, "BG_W = 256", "BG_W = %s" % bg_w)
    add(checks, "2", "GUI 本体尺寸不变：`CollectionCacheScreen.BG_H = 324`",
        bg_h == 324, "BG_H = 324", "BG_H = %s" % bg_h)

    add(checks, "2", "水平位置 `x = leftPos + BG_W + GAP`（屏幕坐标，不是 GUI 内部坐标）",
        has(panel, r"leftPos\s*\+\s*(?:BG_W|imageWidth|owner\.imageWidth)\s*\+\s*GAP"),
        "表达式 `leftPos + BG_W + GAP`",
        "没找到（用 GUI 内部坐标 = JEI / 屏幕右缘避让全错）")

    add(checks, "2", "可用宽 `avail = screenWidth - SCREEN_MARGIN - x`",
        has(panel, r"SCREEN_MARGIN") and has(panel, r"\bavail\b"),
        "`avail` 变量 + `SCREEN_MARGIN` 参与计算",
        "avail=%s SCREEN_MARGIN=%s" % (has(panel, r"\bavail\b"), has(panel, r"SCREEN_MARGIN")))

    clamp_ok = (has(panel, r"(?:MathUtil\.clamp|Math\.max)\s*\(\s*")
                and has(panel, r"MIN_W") and has(panel, r"\bW\b") and has(panel, r"\bavail\b"))
    add(checks, "2", "`w = clamp(avail, MIN_W, W)`（挤的时候收窄，但不小于 MIN_W）",
        clamp_ok, "clamp 表达式同时出现 `avail` / `MIN_W` / `W`",
        "clamp=%s MIN_W=%s W=%s avail=%s"
        % (has(panel, r"(?:MathUtil\.clamp|Math\.max)\s*\("),
           has(panel, r"MIN_W"), has(panel, r"\bW\b"), has(panel, r"\bavail\b")))

    narrow_ptn = r"\bavail\s*<\s*HARD_MIN_W"
    narrow_body = re.search(r"visible\s*=\s*false;\s*\n\s*AreaStripBounds\.set\(null\);",
                            panel) is not None
    add(checks, "2", "`avail < HARD_MIN_W ⇒ visible = false`（低于硬下限就不画侧栏）",
        has(panel, narrow_ptn) and narrow_body,
        "`avail < HARD_MIN_W` 分支里紧接 `visible = false;` + `AreaStripBounds.set(null);`",
        "比较=%s 分支体=%s" % (has(panel, narrow_ptn), narrow_body))

    add(checks, "2", "垂直位置 `y = topPos + 4`",
        has(panel, r"topPos\s*\+\s*4\b"), "`topPos + 4`", "没找到")

    add(checks, "2", "高度 `h = BG_H - 8`（与 GUI 顶/底对齐，不超出屏幕）",
        has(panel, r"(?:BG_H|imageHeight)\s*-\s*8\b"), "`BG_H - 8`", "没找到")

    layout = method_body(panel, r"public\s+void\s+layout\s*\(") or ""
    add(checks, "2", "侧栏矩形**每帧**写入 `AreaStripBounds`（写在 `layout(...)` 里）",
        "AreaStripBounds.set(" in layout, "layout(...) 里调用 `AreaStripBounds.set(...)`",
        "layout body 里 %d 处 set" % layout.count("AreaStripBounds.set("))
    add(checks, "2", "不可见时写 `null`（`AreaStripBounds.set(null)`）",
        has(panel, r"AreaStripBounds\.set\(\s*null\s*\)"), "`AreaStripBounds.set(null)`", "没找到")


# ======================================================================================
# ② §3.1 AreaStripBounds
# ======================================================================================

def _bounds_checks(src, checks):
    bounds = src["bounds"]

    add(checks, "3.1", "文件存在且是 `public final class AreaStripBounds`",
        has(bounds, r"public\s+final\s+class\s+AreaStripBounds\b"),
        "public final class AreaStripBounds", "文件/类声明不存在")

    add(checks, "3.1", "字段是 `volatile`（客户端主线程 + JEI 回调同线程访问，仍按契约加）",
        has(bounds, r"volatile\s+(?:@Nullable\s+)?Rect2i"),
        "`private static volatile Rect2i ...`", "没有 volatile 字段")

    add(checks, "3.1", "`public static void set(@Nullable Rect2i area)`",
        has(bounds, r"public\s+static\s+void\s+set\s*\(\s*(?:@Nullable\s+)?(?:final\s+)?Rect2i\s+\w+\s*\)"),
        "public static void set(@Nullable Rect2i area)", "签名不存在")

    add(checks, "3.1", "`public static @Nullable Rect2i get()`",
        has(bounds, r"public\s+static\s+(?:@Nullable\s+)?Rect2i\s+get\s*\(\s*\)"),
        "public static @Nullable Rect2i get()", "签名不存在")

    add(checks, "3.1", "`public static void clear()`",
        has(bounds, r"public\s+static\s+void\s+clear\s*\(\s*\)"),
        "public static void clear()", "签名不存在")

    setters = len(re.findall(r"public\s+static\s+void\s+set\s*\(", bounds))
    getters = len(re.findall(r"public\s+static\s+(?:@Nullable\s+)?Rect2i\s+get\s*\(\s*\)", bounds))
    clearers = len(re.findall(r"public\s+static\s+void\s+clear\s*\(", bounds))
    add(checks, "3.1", "set/get/clear 都是 `static`（实例方法 ⇒ JEI 拿不到）",
        setters == 1 and getters == 1 and clearers == 1,
        "三个方法各 1 处且都带 static", "set=%d get=%d clear=%d" % (setters, getters, clearers))

    set_body = method_body(bounds, r"public\s+static\s+void\s+set\s*\(") or ""
    add(checks, "3.1", "`set(...)` 真的把参数写进字段（不是空实现）",
        re.search(r"=\s*\w+\s*;", set_body) is not None,
        "方法体里有 `字段 = 参数;`", "方法体：%r" % set_body[:80])

    clear_body = method_body(bounds, r"public\s+static\s+void\s+clear\s*\(") or ""
    add(checks, "3.1", "`clear()` 把字段置 null（关界面后 JEI 不再避让一个已消失的矩形）",
        re.search(r"=\s*null\s*;", clear_body) is not None,
        "方法体里有 `字段 = null;`", "方法体：%r" % clear_body[:80])

    get_body = method_body(bounds, r"public\s+static\s+(?:@Nullable\s+)?Rect2i\s+get\s*\(") or ""
    add(checks, "3.1", "`get()` 返回字段本身（不 new 一份、不做换算）",
        re.search(r"return\s+\w+\s*;", get_body) is not None,
        "方法体里有 `return 字段;`", "方法体：%r" % get_body[:80])

    adv = src["adv"]
    removed = method_body(adv, r"protected\s+void\s+removed\s*\(") or method_body(
        adv, r"public\s+void\s+removed\s*\(") or ""
    add(checks, "3.1", "`AdvancedCollectionCacheScreen#removed()` 调 `AreaStripBounds.clear()`",
        "AreaStripBounds.clear()" in removed, "removed() 里有 `AreaStripBounds.clear()`",
        "removed body 里 %d 处" % removed.count("AreaStripBounds.clear()"))


# ======================================================================================
# ③ §3.2 AreaStripPanel + 机器界面六处转发
# ======================================================================================

def _panel_api_checks(src, checks):
    panel = src["panel"]

    add(checks, "3.2", "文件存在且是 `public final class AreaStripPanel`",
        has(panel, r"public\s+final\s+class\s+AreaStripPanel\b"),
        "public final class AreaStripPanel", "文件/类声明不存在")

    add(checks, "3.2", "**不**继承 `AbstractWidget`（自绘组件：render + 转发，与 SptScrollbarWidget 同风格）",
        not has(panel, r"class\s+AreaStripPanel\s+extends\b"),
        "class 声明里没有 `extends`", "出现了 extends")

    for label, pattern in PANEL_METHODS:
        add(checks, "3.2", "方法签名 %s" % label, has(panel, pattern),
            "契约 §3.2 的签名逐字", "签名不存在或参数类型不符")

    bodies_ok = []
    for label, pattern in PANEL_METHODS:
        body = method_body(panel, pattern)
        bodies_ok.append((label, body is not None and len(body) > 30))
    add(checks, "3.2", "九个方法都**有实现**（没有 stub / 空方法体）",
        all(ok for _label, ok in bodies_ok),
        "每个方法体非空（> 30 字符）",
        "空/缺失：%s" % [label for label, ok in bodies_ok if not ok])


def _forwarding_checks(src, checks):
    adv = src["adv"]
    field = re.search(r"(?:private|protected)\s+(?:final\s+)?AreaStripPanel\s+(\w+)\s*;", adv)
    strip = field.group(1) if field else "strip"
    add(checks, "3.2", "机器界面有 `AreaStripPanel` 字段（%s）" % strip, field is not None,
        "private/protected (final) AreaStripPanel <名字>;", "没有该字段")

    init = method_body(adv, r"protected\s+void\s+init\s*\(") or ""
    add(checks, "3.2", "转发 1/6：`init()` 里 `new AreaStripPanel(this)`",
        re.search(r"new\s+AreaStripPanel\s*\(\s*this\s*\)", init) is not None,
        "init() 里 new AreaStripPanel(this)", "init body 里 %d 处" % count(init, "new AreaStripPanel"))

    render = method_body(adv, r"public\s+void\s+render\s*\(") or ""
    add(checks, "3.2", "转发 2/6：`render(...)` 调 `%s.layout(leftPos, topPos, width, height)`" % strip,
        re.search(r"\.layout\(\s*leftPos\s*,\s*topPos\s*,\s*(?:this\.)?width\s*,\s*"
                  r"(?:this\.)?height\s*\)", render) is not None,
        "render() 里 `strip.layout(leftPos, topPos, width, height)`", "没找到 layout 转发")

    super_pos = render.find("super.render(")
    strip_pos = render.find(strip + ".render(")
    add(checks, "3.2", "转发 2b/6：顺序 = `layout` → `super.render` → `strip.render`",
        super_pos >= 0 and strip_pos > 0 and super_pos < strip_pos
        and render.find(".layout(") < super_pos,
        "layout 在最前、strip.render 在 super.render 之后",
        "layout@%d super@%d strip.render@%d" % (render.find(".layout("), super_pos, strip_pos))

    click = method_body(adv, r"public\s+boolean\s+mouseClicked\s*\(") or ""
    strip_click = click.find(strip + ".mouseClicked(")
    super_click = click.find("super.mouseClicked(")
    add(checks, "3.2", "转发 3/6：`mouseClicked` 先给 strip（命中即 return true）",
        strip_click >= 0 and super_click > strip_click
        and "return true;" in click[strip_click:strip_click + 500],
        "strip.mouseClicked(...) 在 super 之前 + 命中 return true",
        "strip@%d super@%d" % (strip_click, super_click))

    scroll = method_body(adv, r"public\s+boolean\s+mouseScrolled\s*\(") or ""
    strip_scroll = scroll.find(strip + ".mouseScrolled(")
    super_scroll = scroll.find("super.mouseScrolled(")
    add(checks, "3.2", "转发 4/6：`mouseScrolled` 先给 strip",
        strip_scroll >= 0 and super_scroll > strip_scroll,
        "strip.mouseScrolled(...) 在 super 之前",
        "strip@%d super@%d" % (strip_scroll, super_scroll))

    key = method_body(adv, r"public\s+boolean\s+keyPressed\s*\(") or ""
    add(checks, "3.2", "转发 5/6：`keyPressed` 在 `strip.isEditing()`（EditBox 聚焦）时先给 strip",
        "isEditing()" in key and ".keyPressed(" in key
        and key.find("isEditing()") < key.find(".keyPressed("),
        "if (strip.isEditing()) → strip.keyPressed(...)（在 super 之前）",
        "isEditing@%d strip.keyPressed@%d super@%d"
        % (key.find("isEditing()"), key.find(".keyPressed("), key.find("super.keyPressed(")))

    removed = method_body(adv, r"protected\s+void\s+removed\s*\(") or method_body(
        adv, r"public\s+void\s+removed\s*\(") or ""
    add(checks, "3.2", "转发 6/6：`removed()` 调 `%s.removed()`" % strip,
        ".removed()" in removed, "removed() 里 strip.removed()", "没找到")

    add(checks, "3.2", "`openAreaScreen` 不再打开独立界面：本文件 `new AreaSelectorScreen` **0 次**",
        count(adv, "new AreaSelectorScreen") == 0,
        "0 次（契约 §3.2 / §5）", "出现 %d 次" % count(adv, "new AreaSelectorScreen"))

    pairs = [(int(x), int(y)) for x, y in
             re.findall(r"bounds\(\s*leftPos\s*\+\s*(\d+)\s*,\s*topPos\s*\+\s*(\d+)", adv)]
    banned = (186, 219), (218, 219)
    add(checks, "3.2", "原「单显 / 全显」两颗按钮删除（`(186,219)` / `(218,219)` 不再有按钮）",
        not any(pair in banned for pair in pairs),
        "按钮坐标里没有 (186,219) / (218,219)",
        "实际按钮坐标 = %s" % pairs)

    add(checks, "3.2", "「区域」按钮改为开合侧栏：存在跨界面记忆的 `static` 布尔状态",
        has(adv, r"static\s+(?:volatile\s+)?boolean\s+\w+"),
        "`static boolean` 字段（收起状态跨界面记忆，契约 §5）", "没有 static 布尔字段")
    add(checks, "3.2", "「区域」按钮仍存在（只是行为改成开合侧栏）",
        has(init, r"LANG\s*\+\s*\"area_button\""),
        "init() 里仍有 `LANG + \"area_button\"` 的按钮", "没有该按钮")


def _jei_checks(src, checks):
    jei = src["jei"]

    add(checks, "3.3", "JEI 插件调用 `addGuiContainerHandler(...)`",
        "addGuiContainerHandler(" in jei, "`registration.addGuiContainerHandler(`", "没找到")

    add(checks, "3.3", "注册的界面类型是 `AdvancedCollectionCacheScreen.class`",
        "AdvancedCollectionCacheScreen.class" in jei,
        "`AdvancedCollectionCacheScreen.class`", "没找到（JEI 用 isAssignableFrom 查找，必须传子类）")

    add(checks, "3.3", "handler 覆写 `getGuiExtraAreas(...)`",
        "getGuiExtraAreas" in jei, "`getGuiExtraAreas`", "没找到")

    add(checks, "3.3", "返回的是屏幕坐标的 `AreaStripBounds.get()`",
        "AreaStripBounds.get()" in jei, "`AreaStripBounds.get()`", "没找到")

    add(checks, "3.3", "侧栏不可见时返回 `List.of()`（**绝不返回 null**：JEI 会 NPE / 直接不用这个矩形）",
        has(jei, r"==\s*null\s*\?[^;]*List\.of\(\s*\)"), "`strip == null ? List.of() : List.of(strip)`",
        "没找到 null → List.of() 的保护")

    register = method_body(jei, r"public\s+void\s+registerGuiHandlers\s*\(") or ""
    add(checks, "3.3", "注册发生在 `registerGuiHandlers` 里（不是构造器 / 静态块）",
        "addGuiContainerHandler(" in register, "registerGuiHandlers 里有 addGuiContainerHandler",
        "registerGuiHandlers body 里 %d 处" % register.count("addGuiContainerHandler("))


# ======================================================================================
# ⑤ §4 行为契约
# ======================================================================================

def _behaviour_checks(src, checks):
    panel, adv, area = src["panel"], src["adv"], src["area"]

    # 常驻条件
    gate_src = (method_body(panel, r"public\s+void\s+render\s*\(") or "") + \
               (method_body(panel, r"public\s+void\s+layout\s*\(") or "")
    add(checks, "4", "槽空不画侧栏：绘制/布局路径里同时判 `AreaSelectorItem` 与 `isEmpty()`",
        "AreaSelectorItem" in gate_src and "isEmpty()" in gate_src,
        "render()/layout() 里同时出现 `AreaSelectorItem` 与 `isEmpty()`（槽空 ⇒ 不画）",
        "AreaSelectorItem=%s isEmpty=%s"
        % ("AreaSelectorItem" in gate_src, "isEmpty()" in gate_src))

    # 点行 → SetAreaFilterContextPacket
    add(checks, "4", "点行发 `SetAreaFilterContextPacket(containerId, usableIndex)`",
        "new SetAreaFilterContextPacket(" in panel,
        "panel 里 `new SetAreaFilterContextPacket(`", "没找到")
    add(checks, "4", "与 `AreaSelectorScreen#sendFilterContext` **同一条链路**（同一个包类两处都在发）",
        "new SetAreaFilterContextPacket(" in area,
        "AreaSelectorScreen 里也有 `new SetAreaFilterContextPacket(`（旧链路保留）",
        "AreaSelectorScreen 里没找到 ⇒ 可能是新造了一条平行链路")
    add(checks, "4", "发包走 `PacketDistributor.sendToServer(...)`",
        "PacketDistributor.sendToServer(" in panel, "`PacketDistributor.sendToServer(`", "没找到")

    # 改名捕获 rawIndex
    rename_field = re.search(r"(?:private\s+)?int\s+(\w*[Rr]ename\w*Index)\s*[;=]", panel)
    add(checks, "4", "改名用「打字时捕获的区域下标」字段（而不是提交时读即时选中行）",
        rename_field is not None,
        "字段名形如 `pendingRenameIndex` / `renameIndex`", "没找到该字段")
    add(checks, "4", "捕获点在 EditBox 打字回调里（`setResponder(...)` 里写该下标）",
        "setResponder(" in panel and rename_field is not None,
        "`editBox.setResponder(...)` 里捕获下标", "setResponder=%s" % ("setResponder(" in panel))
    add(checks, "4", "提交用 `AreaSelectorEditPacket.ACTION_RENAME`",
        "AreaSelectorEditPacket.ACTION_RENAME" in panel,
        "`AreaSelectorEditPacket.ACTION_RENAME`", "没找到")
    add(checks, "4", "提交后立刻复位（`…Index = -1`，防止第 124 轮 2-2 的「每帧重投」）",
        re.search(r"\w*[Rr]ename\w*Index\s*=\s*-1", panel) is not None,
        "捕获字段提交后 `= -1`", "没找到复位")

    # ✕ 二次确认
    add(checks, "4", "✕ 二次确认：3 秒窗口 + 二次点击才发 `ACTION_DELETE`",
        "ACTION_DELETE" in panel and re.search(r"3_?000", panel) is not None
        and re.search(r"confirm", panel, re.I) is not None,
        "`ACTION_DELETE` + 3000ms + confirm 状态",
        "delete=%s 3000=%s confirm=%s"
        % ("ACTION_DELETE" in panel, re.search(r"3_?000", panel) is not None,
           re.search(r"confirm", panel, re.I) is not None))

    # 世界框
    add(checks, "4", "常驻世界框走 `AreaOutline.chase(...)`",
        "AreaOutline.chase(" in panel, "`AreaOutline.chase(`", "没找到")
    add(checks, "4", "框的 slot 前缀是 `strip/focus`",
        "strip/focus" in panel, "slot 名 `strip/focus`", "没找到")
    panel_removed_body = method_body(panel, r"public\s+void\s+removed\s*\(\s*\)") or ""
    add(checks, "4", "关界面清框（`removed()` 里 `AreaOutline.remove(...)`，避免「关掉界面框还在」）",
        "AreaOutline.remove(" in panel_removed_body or "AreaOutline.remove(" in
        (method_body(adv, r"protected\s+void\s+removed\s*\(") or ""),
        "panel.removed() 或 screen.removed() 里 `AreaOutline.remove(`", "没找到")

    # 当前行高亮
    arrow_ok = ("\u25b6" in panel) or re.search(r"\\u25b6", panel, re.I) is not None
    add(checks, "4", "当前行标记：橙 `0xFF9F1C` + 行底 `0x30FF9F1C` + 行首 `▶`",
        "0xFF9F1C" in panel and "0x30FF9F1C" in panel and arrow_ok,
        "0xFF9F1C / 0x30FF9F1C / ▶ 三个都在",
        "orange=%s bg=%s arrow=%s" % ("0xFF9F1C" in panel, "0x30FF9F1C" in panel, arrow_ok))

    # 显示全部 5 秒
    add(checks, "4", "「显示全部」一次性常亮 5 秒（走 `showForView` + VIEW_TTL）",
        "showForView(" in panel and ("VIEW_TTL" in panel or re.search(r"\b100\b", panel) is not None),
        "`AreaOutline.showForView(` + 5 秒 TTL", "没找到")

    # 滚动
    scroll = method_body(panel, r"public\s+boolean\s+mouseScrolled\s*\(") or ""
    add(checks, "4", "滚动按 ROW_H 走（行数 × ROW_H > 可视高才对）",
        "ROW_H" in scroll and re.search(r"scroll", scroll, re.I) is not None,
        "mouseScrolled() 里用 `ROW_H` + 滚动偏移", "ROW_H=%s scroll=%s"
        % ("ROW_H" in scroll, re.search(r"scroll", scroll, re.I) is not None))

    # 坐标编辑
    # `RsccNumberField` 是 final 静态工具类（**没有构造器**）：坐标框的正确形态是
    # `new EditBox(...)` + `RsccNumberField.parse(...)`（工程唯一的数字输入口径），不是 `new RsccNumberField(`。
    field_array = re.search(r"EditBox\[\]\s*\w+\s*=\s*new\s+EditBox\[6\]", panel) is not None
    parse_uses = len(re.findall(r"RsccNumberField\.parse\s*\(", panel))
    six_loop = re.search(r"for\s*\([^)]*<\s*(?:coordBoxes\.length|6)\s*;", panel) is not None
    add(checks, "4", "坐标编辑 = 6 个数字框且解析走 `RsccNumberField.parse`（工程唯一数字口径）",
        field_array and parse_uses >= 1 and six_loop,
        "`EditBox[] … = new EditBox[6]` + `RsccNumberField.parse(` + 六次循环",
        "数组=%s parse=%d 六次循环=%s" % (field_array, parse_uses, six_loop))

    # 数据源
    add(checks, "4", "数据源只读 `AdvancedAreaSnapshotPacket#lastFor(containerId)`",
        "AdvancedAreaSnapshotPacket.lastFor(" in panel,
        "`AdvancedAreaSnapshotPacket.lastFor(`", "没找到")
    add(checks, "4", "不读方块实体（客户端只读快照）",
        "BlockEntity" not in panel, "panel 里没有 `BlockEntity`", "出现了 BlockEntity")
    add(checks, "4", "客户端不判断可用性（`invalidReason` 只在服务端用）",
        "invalidReason" not in panel, "panel 里没有 `invalidReason`", "出现了 invalidReason")
    add(checks, "4", "客户端不自己读选定器算几何（不出现 `AreaSelection.get(`）",
        "AreaSelection.get(" not in panel, "panel 里没有 `AreaSelection.get(`", "出现了")

    # 反 stub
    joined = src["bounds"] + src["panel"] + adv
    add(checks, "4", "没有 `UnsupportedOperationException` / `TODO` / `FIXME` 占位实现",
        "UnsupportedOperationException" not in joined
        and re.search(r"\bTODO\b|\bFIXME\b", joined) is None,
        "三个文件里都没有占位标记",
        "TODO=%s FIXME=%s UOE=%s"
        % (re.search(r"\bTODO\b", joined) is not None,
           re.search(r"\bFIXME\b", joined) is not None,
           "UnsupportedOperationException" in joined))


# ======================================================================================
# ⑥ §4.5 标记编辑就地做（侧栏标记网格 + JEI 拖入）
# ======================================================================================

def enclosing_block(source, token):
    """取 `token` 之后紧随的那个 `{...}` 块（前后配平）。

    §4.5-2 的「侧栏分支」是一个 `if (... isMarkGridOpen() ...) { ... }` 块：同一个
    `getTargetsTyped` 里还有匹配区 48 格通道，用「块」而不是「字符窗口」切分才不会误判。
    """
    index = source.find(token)
    if index < 0:
        return ""
    brace = source.find("{", index)
    if brace < 0:
        return ""
    depth = 0
    for i in range(brace, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[brace:i + 1]
    return ""


def record_components(source, record_name):
    """取 `public record X(a a1, B b2, ...)` 的分量列表（去注解 / 去修饰符），返回 [(类型, 名字)]。"""
    match = re.search(r"public\s+record\s+" + re.escape(record_name) + r"\s*\(", source)
    if not match:
        return None
    start = source.find("(", match.start())
    depth = 0
    end = -1
    for i in range(start, len(source)):
        if source[i] == "(":
            depth += 1
        elif source[i] == ")":
            depth -= 1
            if depth == 0:
                end = i
                break
    if end < 0:
        return None
    raw = source[start + 1:end]
    parts = []
    depth = 0
    current = ""
    for ch in raw:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += ch
    if current.strip():
        parts.append(current)
    out = []
    for part in parts:
        tokens = [t for t in re.sub(r"@\w+(\([^)]*\))?", "", part).split() if t]
        if len(tokens) >= 2:
            out.append((tokens[-2], tokens[-1]))
    return out


def _mark_grid_checks(src, checks):
    """§4.5：第 130 轮起「侧栏标记网格」整页删除，区域过滤只在匹配区 48 格里编辑。

    第 130 轮契约（docs/ROUND130_AREA_STRIP_REWORK.md §3 / §4.3）把这一节的判据**移交**给
    tools/selfcheck_round130_area_strip_rework.py（那边逐条断言 isMarkGridOpen / markGridCellAreas /
    acceptGhost* / AreaMarkAddItemPacket / `area_strip.mark.*` 全部为 0，且 48 格通道必须仍在）。
    这里只保留**仍然成立**的「退役范围」判据（契约 §5：不许一刀切删能力 / 不许顺手改线格式）。
    """
    mark, maredit, adv, panel = src.get("mark", ""), src["maredit"], src["adv"], src["panel"]

    add(checks, "4.5", "`AreaMarkScreen.java` **文件保留**（主手世界路径仍用它；§5 不许一刀切删能力）",
        bool(mark.strip()), "文件存在且非空", "文件被删了（§5 明令保留）")
    add(checks, "4.5", "机器界面 `AdvancedCollectionCacheScreen` 里 `new AreaMarkScreen` **0 次**",
        count(adv, "new AreaMarkScreen") == 0, "0 次",
        "出现 %d 次" % count(adv, "new AreaMarkScreen"))
    add(checks, "4.5", "侧栏 `AreaStripPanel` 里 `new AreaMarkScreen` **0 次**（标记在匹配区里配）",
        count(panel, "new AreaMarkScreen") == 0, "0 次",
        "出现 %d 次" % count(panel, "new AreaMarkScreen"))

    old_components = record_components(maredit, "AreaMarkEditPacket")
    add(checks, "4.5", "`AreaMarkEditPacket` 仍是 5 参 record（线格式没为侧栏功能被扩）",
        old_components == [("int", "containerId"), ("int", "action"), ("Area", "area"),
                           ("int", "markerIndex"), ("int", "inventorySlot")],
        "5 参 (int containerId, int action, Area area, int markerIndex, int inventorySlot)",
        "实际 = %s" % old_components)
    # 只查 record 头：注释里出现 fluid / count 是作者说明，不是字段（原来的全文匹配会误报）。
    _old_head = (maredit.split("public record", 1)[1][:240]
                 if "public record" in maredit else "")
    add(checks, "4.5", "`AreaMarkEditPacket` record 头里没有 componentsPatch / count / fluid 字段",
        not re.search(r"\bcomponentsPatch\b|\bfluid\b|\bcount\b", _old_head),
        "旧包 record 头里没有这三个字段",
        "旧包 record 头里混入了新包字段：%s" % _old_head[:120])

    # 第 130 轮整页删除的 21 条断言（网格 / 接缝 / 新包）不在这里逐条 SKIP：反例 `failures_of`
    # 每次都会重跑本函数，写成 SKIP 会刷出几十行噪声。删除本身由 round130 脚本逐 token 判零承担。


# ======================================================================================
# 全部断言
# ======================================================================================

def contract_assertions(src):
    """返回 [(断言名, ok, 失败详情)]；纯函数（反例测试同一函数）。"""
    checks = []
    _geometry_checks(src, checks)
    _bounds_checks(src, checks)
    _panel_api_checks(src, checks)
    _forwarding_checks(src, checks)
    _jei_checks(src, checks)
    _behaviour_checks(src, checks)
    _mark_grid_checks(src, checks)
    return checks


def failures_of(src):
    return [name for name, ok, _detail in contract_assertions(src) if not ok]


# ======================================================================================
# 反例自证（把契约的每一条改坏，必须判负）
# ======================================================================================

class AnchorMissing(Exception):
    """反例锚点不存在（实现未就绪 / 写法与契约不同）⇒ 该反例 skip，不误报。"""


def mutate(src, key, pattern, replacement):
    """按 regex 替换 1 处；锚点不存在抛 AnchorMissing。pattern=None ⇒ 把该文件置空（模拟被删）。"""
    if pattern is None:
        out = dict(src)
        out[key] = ""
        return out
    text = src.get(key, "")
    new_text, hits = re.subn(pattern, replacement, text, count=1)
    if hits == 0:
        raise AnchorMissing("%s 里找不到锚点 /%s/" % (key, pattern))
    out = dict(src)
    out[key] = new_text
    return out


def counterexample_specs():
    """[(标签, 文件键, 正则, 替换, 期望判负的断言前缀)]。全部 count=1。"""
    return (
        # ---- §2 常量/几何 ----
        ("C-1 把 `W` 改成 160（理想宽被偷偷缩 16px）",
         "panel", r"\bW\s*=\s*176\s*;", "W = 160;", ("§2 W = 176",)),
        ("C-2 把 `MIN_W` 改成 64（挤的时候收得比契约更窄）",
         "panel", r"\bMIN_W\s*=\s*140\s*;", "MIN_W = 64;", ("§2 MIN_W = 140",)),
        ("C-3 把 `HARD_MIN_W` 改成 64（屏幕很窄时仍硬画侧栏）",
         "panel", r"\bHARD_MIN_W\s*=\s*108\s*;", "HARD_MIN_W = 64;", ("§2 HARD_MIN_W = 108",)),
        ("C-4 把 `GAP` 改成 0（侧栏贴死 GUI 右缘）",
         "panel", r"\bGAP\s*=\s*6\s*;", "GAP = 0;", ("§2 GAP = 6",)),
        ("C-5 把 `ROW_H` 改成 18（行高与 22 契约不符 ⇒ 命中区/滚动全错）",
         "panel", r"\bROW_H\s*=\s*22\s*;", "ROW_H = 18;", ("§2 ROW_H = 22",)),
        ("C-6 去掉 `volatile`（JEI 回调看不到最新矩形）",
         "bounds", r"volatile\s+", "", ("§3.1",)),
        ("C-7 `clear()` 改成空实现（关界面后 JEI 仍避让一个已消失的矩形）",
         "bounds", r"(public\s+static\s+void\s+clear\s*\(\s*\)\s*\{)[^}]*\}", r"\1}", ("§3.1",)),
        ("C-8 `layout` 里写 `AreaStripBounds.set(new Rect2i(BG_W + GAP, ...))`（GUI 内部坐标当屏幕坐标）",
         "panel", r"leftPos\s*\+\s*(?:BG_W|imageWidth|owner\.imageWidth)", "", ("§2",)),
        ("C-9 窄屏时不再收起侧栏（`visible = false` 改成 true，硬画到屏幕外）",
         "panel", r"visible\s*=\s*false;", "visible = true;", ("§2",)),
        # ---- §3.1 / §3.2 ----
        ("C-10 `removed()` 不调 `AreaStripBounds.clear()`（关界面不清框）",
         "adv", r"AreaStripBounds\.clear\(\)", "/* removed: clear() */AreaStripBounds.clear0()", ("§3.1",)),
        ("C-11 `init()` 不建侧栏（`new AreaStripPanel(this)` 消失）",
         "panel", r"public\s+AreaStripPanel\s*\(", "public AreaStripPanelX(", ("§3.2",)),
        ("C-12 `mouseScrolled` 不转发给 strip（滚轮滚的是 GUI，不是侧栏）",
         "adv", r"\.mouseScrolled\(", ".mouseScrolledX(", ("§3.2",)),
        ("C-13 `keyPressed` 不判 `strip.isEditing()`（打字时按键被 GUI 抢走）",
         "adv", r"\.isEditing\(\)", ".isEditingX()", ("§3.2",)),
        ("C-14 把 `(186,219)` 那颗「单显」按钮加回来（契约 §5 明令删除）",
         "adv", r"(super\.init\(\);)",
         r"\1\n        addRenderableWidget(new Button.Builder(Component.literal(\"x\"), b -> {\n"
         r"        }).bounds(leftPos + 186, topPos + 219, 30, 16).build());", ("§3.2",)),
        ("C-15 机器路径又 `new AreaSelectorScreen(...)`（复活全屏界面；锚 toggleStrip）",
         "adv", r"(void\s+toggleStrip\s*\(\s*\)\s*\{)",
         r"\1\n        minecraft.setScreen(new AreaSelectorScreen(this, advancedMenu()));", ("§3.2",)),
        ("C-16 `AreaStripPanel#removed()` 改成空实现（关界面不提交改名 / 不清框）",
         "panel", r"(public\s+void\s+removed\s*\(\s*\)\s*\{)[^}]*\}", r"\1}", ("§3.2",)),
        # ---- §3.3 ----
        ("C-17 JEI `getGuiExtraAreas` 返回 null（JEI 直接 NPE / 不避让）",
         "jei", r"return\s+[^;]*\?[^;]*List\.of\(\s*\)[^;]*;", "return null;", ("§3.3",)),
        ("C-18 JEI 不注册容器 handler（`addGuiContainerHandler` 删掉 ⇒ JEI 不知道侧栏占地）",
         "jei", r"\.addGuiContainerHandler\(", ".addXxxHandler(", ("§3.3",)),
        # ---- §4 行为 ----
        ("C-19 点行不发 `SetAreaFilterContextPacket`（选中不生效）",
         "panel", r"new\s+SetAreaFilterContextPacket\(", "new SetAreaFilterContextPacketX(", ("§4",)),
        ("C-20 槽空仍画侧栏（去掉 `instanceof AreaSelectorItem` 判断）",
         "panel", r"instanceof\s+(?:[\w.]+\.)?AreaSelectorItem", "instanceof Object", ("§4",)),
        ("C-21 改名提交后不复位捕获下标（第 124 轮 2-2 的每帧重投）",
         "panel", r"\w*[Rr]ename\w*Index\s*=\s*-1\s*;", ";", ("§4",)),
        ("C-22 关界面不清世界框（removed() 里删掉 `strip/focus` 的 clear ⇒ 关了界面框还在）",
         "panel",
         r'(public\s+void\s+removed\s*\(\s*\)\s*\{)([^{}]*?)'
         r'AreaOutline\.remove\(AreaOutline\.slot\("strip/focus"\)\);',
         r"\1\2", ("§4",)),
        ("C-23 客户端自己判断可用性（`area.invalidReason(...)` 出现在 panel 里）",
         "panel", r"(public\s+void\s+render\s*\(\s*(?:final\s+)?GuiGraphics[^)]*\)\s*\{)",
         r"\1\n        area.invalidReason(0);", ("§4",)),
        ("C-24 用 `AreaSelection.get(...)` 自己读选定器算几何（客户端读权威数据）",
         "panel", r"(public\s+void\s+render\s*\(\s*(?:final\s+)?GuiGraphics[^)]*\)\s*\{)",
         r"\1\n        AreaSelection.get(null);", ("§4",)),
        # ---- §4.5 退役范围（标记网格已在第 130 轮删除；这里只剩「文件保留 / 线格式不扩」）----
        ("C-34 扩旧包线格式（`AreaMarkEditPacket` record 头插 tags 字段）",
         "maredit", r"(int\s+inventorySlot)", r"\1, java.util.List<String> tags", ("§4.5",)),
        ("C-36 机器路径又弹 `AreaMarkScreen`（`new AreaMarkScreen` 复活）",
         "adv", r"(public\s+void\s+removed\s*\(\s*\)\s*\{)",
         r"\1\n        minecraft.setScreen(new AreaMarkScreen(this, 0, null));", ("§4.5",)),
        ("C-37 把 `AreaMarkScreen.java` 删掉（§5 明令文件保留）",
         "mark", None, "", ("§4.5",)),
    )


def group_counterexamples(src):
    print("\n=== 反例自证（把契约逐条改坏 ⇒ 期望判负；实现未就绪则 SKIP） ===")
    real_failures = failures_of(src)
    check("反例前置：真实源码的契约判据全绿（反例才有判别力）", not real_failures,
          "真源码仍有 %d 条失败，例如：%s" % (len(real_failures), real_failures[:3]))

    for label, key, pattern, replacement, prefixes in counterexample_specs():
        try:
            broken = mutate(src, key, pattern, replacement)
        except AnchorMissing as exc:
            skip(label, str(exc))
            continue
        names = failures_of(broken)
        hit = any(name.startswith(prefix) for name in names for prefix in prefixes)
        counterexample(label, broken != src and hit,
                       "没判负（判负 %d 条：%s）" % (len(names), names[:4]))


# ======================================================================================
# main
# ======================================================================================

def main():
    print("第 125 轮契约自检：区域条（AreaStrip）取代独立区域界面")
    print("契约：%s" % CONTRACT)
    print("源码根：%s" % JAVA_REL)
    missing = missing_files()
    if missing:
        print("\n[注意] 以下文件还不存在（实现未就绪时正常）：")
        for item in missing:
            print("  - %s" % item)

    src = load_sources()
    print("\n=== 契约断言（§2 几何 / §3.1 Bounds / §3.2 Panel+转发 / §3.3 JEI / §4 行为） ===")
    for name, ok, detail in contract_assertions(src):
        check(name, ok, detail)

    group_counterexamples(src)

    print("\n断言 %d 条（其中反例 %d 条，跳过 %d 条），失败 %d 条"
          % (CHECKS[0], COUNTER[0], SKIPS[0], len(FAILS)))
    if FAILS:
        print("FAILED:")
        for name in FAILS:
            print("  - " + name)
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
