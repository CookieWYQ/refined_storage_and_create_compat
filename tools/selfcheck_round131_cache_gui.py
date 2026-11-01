#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 131 轮契约自检（A 组「缓存仓界面收敛 + 记住上次编辑区域」）。

规格来源
--------
`docs/ROUND131_CONTRACT.md` §0 用户原话（前四张截图）+ §3.A 的 6 条硬判据 + §4 协作纪律。
本脚本逐条落地，失败信息里写「契约第几节 / 期望 / 实际」，名字统一以 `§A<N>` 开头。

本脚本是**规格判定程序**，不是实现
---------------------------------
* 只读 `src/**`（一个字节都不写），写作用域只有 `tools/`；
* 纯 stdlib python3，从**真源码**解析，不 import 游戏代码、不跑 gradlew / javac；
* 定位方法 / 字段一律用「正则找定义 + 括号配对」，**不写死行号**；
* 断言语义（方法体里的调用顺序、几何算式、守卫表达式），不是「文件里有这个字符串」；
* 每条断言都带**反例**：把旧写法拼回去 / 把源码换成空树后必须判负（见 `run_counterexamples`）。

覆盖（对应 §3.A 六条）
----------------------
§A1 状态带 hover tooltip 整条删除：本屏不再有 `renderStatusBandTooltip` / `renderFilterIconTooltip`
    这两个**定义**（连 javadoc 里的名字都不留，避免复活路径），也没有 `frameIcons` / `iconsDrawn` /
    `iconsStartX` / `collectFrameIcons` 这套图标状态；同时**基类**的这两个钩子必须还在（删的是本屏覆写，
    不是把基础版一起删 —— §3.A.6「基础版逐字不变」）。
§A2 匹配区空态提示删除：本屏不再有 `drawMatchAreaHint`（也就没有「先在右边点一个区域」那行黄字）；
    基类那份**空实现**必须还在（钩子保留，基础版靠它什么也不画）。
§A3 区域条行内过滤图标删除：`AreaStripPanel` 里 `drawFilterIcons` / `ROW_ICON_MAX` / `ROW_ICON_PITCH` /
    `visibleItemFilters` 一个不剩；`drawRowCoords` 的可用宽**只扣动作区**（`contentRight() - textX - actW`），
    方法体里不许再出现任何 icon 字样（「按删除后重算、不留空洞」）；而 `AdvancedCollectionCacheScreen`
    的**共享实现** `drawFilterIcons` 必须保留（旧「区域列表」界面还在用，删了会连带改别人的文件）。
§A4 橙色摘要行删除：本屏不再自己画状态带（`drawStatusBand(` 调用数 = 0）也没有 `editAreaSummary`；
    「正常时一个字都不画」的判据落在基类 `isStatusVisible` 白名单上：必须仍只有「真问题」那 6 个码，
    `NO_MARKER / NO_TARGET / THRESHOLD / TOGGLE_OFF` 这四个「正常/半正常」码必须仍然不在白名单里。
§A5 记住上次编辑区域：方块实体有 `LastEditingArea` NBT 键（字面量逐字核对）+ `lastEditingArea` 字段
    （默认 -1）+ 读写方法；`saveAdditional` 真的写、`loadAdditional` 缺键回 -1；菜单构造器从
    `block.getLastEditingArea()` 读回来并**收敛**（`>= 0 && < getAreaCount()`，越界 -1）；
    `setEditingArea` 是**唯一落盘写入点**且写在「值没变就 return」**之前**（否则点同一行不落盘）；
    客户端/服务端同步链路（数据槽 `DATA_AREA_EDITING` → `getEditingAreaIndex()` / `broadcastChanges`
    的 raw 下标换算）必须仍然完整，否则「重开界面」在客户端拿不到这个值。
§A6 基础版不动：`CollectionCacheScreen` 里不许出现 `LastEditingArea` / `AreaStripPanel` / `drawFilterIcons`
    / `drawStatusBand(` 之外的新东西 —— 本轮的收敛全部在高级版；同时基类的 `drawStatusLine` /
    `isStatusVisible` / `matchRegionLabel` / 两个空钩子必须原样还在。

纪律：不跑 gradlew / javac（Lead 统一编译）、不 git、不改 `src/**`、不改 docs / lang。
用法：python tools/selfcheck_round131_cache_gui.py
退出码：0 = 全部通过；1 = 有失败。最后一行：ALL_PASS / FAILED。
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

CONTRACT = "docs/ROUND131_CONTRACT.md"

FILES = {
    "adv": os.path.join(JAVA, "client", "screen", "AdvancedCollectionCacheScreen.java"),
    "panel": os.path.join(JAVA, "client", "screen", "AreaStripPanel.java"),
    "menu": os.path.join(JAVA, "menu", "AdvancedCollectionCacheMenu.java"),
    "be": os.path.join(JAVA, "block", "entity", "AdvancedCollectionCacheBlockEntity.java"),
    "base": os.path.join(JAVA, "client", "screen", "CollectionCacheScreen.java"),
    "old_area_screen": os.path.join(JAVA, "client", "screen", "AreaSelectorScreen.java"),
}

#: 本轮 A 组允许改动的文件（写作用域，契约 §2）。基类只许**读**。
WRITE_SCOPE = ("client/screen/AdvancedCollectionCacheScreen.java",
               "client/screen/AreaStripPanel.java",
               "menu/AdvancedCollectionCacheMenu.java",
               "block/entity/AdvancedCollectionCacheBlockEntity.java")

COUNTER_TOTAL = [0]
COUNTER_PASS = [0]


# ======================================================================================
# 基础工具
# ======================================================================================

def rel(path):
    return os.path.relpath(path, ROOT).replace(os.sep, "/")


def read(path):
    try:
        with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
            return handle.read()
    except OSError:
        return ""


def occurrences(code, needle):
    return code.count(needle)


def find_method_body(code, name):
    """返回 `name(` 那个**方法定义**的方法体（含花括号）；找不到返回 None。

    只认「行首缩进 + 修饰符」开头、且名字后面紧跟 `(` 的位置 —— 调用点（`foo(...)`）前面
    通常是 `=` / `(` / `return`，不是修饰符，因此不会被误认成定义。
    """
    pattern = re.compile(
        r"(?m)^[ \t]*(?:@[A-Za-z0-9_.]+(?:\([^)]*\))?[ \t]*\n[ \t]*)*"
        r"(?:public|protected|private)[^;{}\n]*\b" + re.escape(name) + r"\s*\(")
    for match in pattern.finditer(code):
        brace = code.find("{", match.end())
        if brace < 0:
            continue
        depth = 0
        for index in range(brace, len(code)):
            char = code[index]
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 0:
                    return code[brace:index + 1]
    return None


def method_signature_line(code, name):
    """方法定义所在那一行（断言签名用；找不到返回空串）。"""
    pattern = re.compile(r"(?m)^[ \t]*(?:public|protected|private)[^;{}\n]*\b"
                         + re.escape(name) + r"\s*\(")
    match = pattern.search(code)
    return match.group(0).strip() if match else ""


def field_line(code, name):
    pattern = re.compile(r"(?m)^[ \t]*(?:private|public|protected)[^;{}\n]*\b"
                         + re.escape(name) + r"\s*[=;]")
    match = pattern.search(code)
    return match.group(0).strip() if match else ""


def window_after(code, anchor, size):
    """anchor 之后的一段源码（用于「同一段作用域内必须出现 X」这类断言；锚点缺失返回空串）。"""
    index = code.find(anchor)
    if index < 0:
        return ""
    return code[index:index + size]


def load_snapshot():
    snapshot = {}
    for key, path in FILES.items():
        snapshot[key] = read(path)
    return snapshot


# ======================================================================================
# §A1 状态带 hover tooltip 整条删除
# ======================================================================================

def section_a1(s):
    results = []

    def check(name, ok, detail=""):
        results.append((name, bool(ok), detail))

    adv = s["adv"]
    base = s["base"]

    check("§A1.1 本屏不再定义 renderStatusBandTooltip（状态带 tooltip 整条删除）",
          occurrences(adv, "renderStatusBandTooltip") == 0,
          "期望 0 处，实际 %d 处" % occurrences(adv, "renderStatusBandTooltip"))
    check("§A1.2 本屏不再有 renderFilterIconTooltip（图标 tooltip 是花字的另一半）",
          occurrences(adv, "renderFilterIconTooltip") == 0,
          "期望 0 处，实际 %d 处" % occurrences(adv, "renderFilterIconTooltip"))
    for token in ("frameIcons", "iconsDrawn", "iconsStartX", "collectFrameIcons"):
        check("§A1.3 图标状态 %s 已随图标一起删除" % token,
              occurrences(adv, token) == 0,
              "期望 0 处，实际 %d 处" % occurrences(adv, token))
    check("§A1.4 基类的状态带 tooltip 钩子仍在（删的是本屏覆写，不是基础版）",
          find_method_body(base, "renderStatusBandTooltip") is not None,
          "基类 CollectionCacheScreen 里找不到 renderStatusBandTooltip 方法体")
    check("§A1.5 基类的匹配区空态钩子仍在（钩子保留、高级版不再覆写）",
          find_method_body(base, "drawMatchAreaHint") is not None,
          "基类里找不到 drawMatchAreaHint 方法体")
    return results


# ======================================================================================
# §A2 匹配区空态提示删除
# ======================================================================================

def section_a2(s):
    results = []
    adv = s["adv"]
    base = s["base"]
    results.append(("§A2.1 本屏不再覆写 drawMatchAreaHint（图 2 那行提示删除）",
                    occurrences(adv, "drawMatchAreaHint") == 0,
                    "期望 0 处，实际 %d 处" % occurrences(adv, "drawMatchAreaHint")))
    body = find_method_body(base, "drawMatchAreaHint")
    empty_hook = body is not None and "guiGraphics" not in body and "fill(" not in body
    results.append(("§A2.2 基类钩子是「空实现」（基础版画面因此不变）",
                    empty_hook,
                    "基类 drawMatchAreaHint 方法体不是空实现：%r" % (body,)))
    return results


# ======================================================================================
# §A3 区域条行内过滤图标删除
# ======================================================================================

def section_a3(s):
    results = []
    panel = s["panel"]
    adv = s["adv"]

    def check(name, ok, detail=""):
        results.append((name, bool(ok), detail))

    check("§A3.1 区域条不再调用 drawFilterIcons",
          occurrences(panel, "drawFilterIcons") == 0,
          "期望 0 处，实际 %d 处" % occurrences(panel, "drawFilterIcons"))
    for token in ("ROW_ICON_MAX", "ROW_ICON_PITCH", "visibleItemFilters"):
        check("§A3.2 行内图标常量 / 助手 %s 删干净" % token,
              occurrences(panel, token) == 0,
              "期望 0 处，实际 %d 处" % occurrences(panel, token))

    coords = find_method_body(panel, "drawRowCoords")
    check("§A3.3 drawRowCoords 存在且不再出现 icon 字样（不留空洞、不留预留宽）",
          coords is not None and "icon" not in coords.lower(),
          "方法不存在" if coords is None else "方法体里仍出现 icon：%r" % coords[:200])
    check("§A3.4 坐标行可用宽只扣动作区（contentRight() - textX - actW）",
          coords is not None and "Math.max(12, contentRight() - textX - actW)" in coords,
          "期望 `Math.max(12, contentRight() - textX - actW)`，实际：%r"
          % ((coords or "")[:300],))
    check("§A3.5 两端点式仍按 fullW 右对齐（textX + fullW - highW）",
          coords is not None and "textX + fullW - highW" in coords,
          "找不到 `textX + fullW - highW`（右端点会贴错位置）")
    check("§A3.6 共享实现 drawFilterIcons 仍在（旧「区域列表」界面还在用）",
          find_method_body(adv, "drawFilterIcons") is not None,
          "AdvancedCollectionCacheScreen.drawFilterIcons 被删了 —— 会连带改别人的文件")
    check("§A3.7 旧「区域列表」界面仍然引用共享实现（证明「保留」有实际依据）",
          "AdvancedCollectionCacheScreen.drawFilterIcons(" in s["old_area_screen"],
          "AreaSelectorScreen 里找不到对共享实现的引用")
    return results


# ======================================================================================
# §A4 橙色摘要行删除 / 正常时一个字都不画
# ======================================================================================

def section_a4(s):
    results = []
    adv = s["adv"]
    base = s["base"]

    def check(name, ok, detail=""):
        results.append((name, bool(ok), detail))

    check("§A4.1 本屏不再自己画状态带（drawStatusBand 调用数 = 0）",
          occurrences(adv, "drawStatusBand(") == 0,
          "期望 0 处，实际 %d 处" % occurrences(adv, "drawStatusBand("))
    check("§A4.2 橙色摘要行 editAreaSummary 已删除",
          occurrences(adv, "editAreaSummary") == 0,
          "期望 0 处，实际 %d 处" % occurrences(adv, "editAreaSummary"))
    for key in ("filter.row", "filter.none", "filter.howto", "filter.empty.tip"):
        check("§A4.3 摘要文案键 %s 不再被本屏引用" % key,
              key not in adv,
              "仍引用 %s（摘要行文案残留）" % key)
    check("§A4.4 本屏不再覆写 drawStatusLine（状态带只剩基类一个出口）",
          occurrences(adv, "void drawStatusLine(") == 0,
          "期望 0 处，实际 %d 处" % occurrences(adv, "void drawStatusLine("))

    visible = find_method_body(base, "isStatusVisible")
    keep = ("ABSORB_STATUS_NO_NETWORK", "ABSORB_STATUS_INACTIVE", "ABSORB_STATUS_CACHE_FULL",
            "ABSORB_STATUS_AREA_NO_SELECTOR", "ABSORB_STATUS_AREA_EMPTY",
            "ABSORB_STATUS_AREA_DIMENSION")
    drop = ("ABSORB_STATUS_NO_MARKER", "ABSORB_STATUS_NO_TARGET",
            "ABSORB_STATUS_THRESHOLD", "ABSORB_STATUS_TOGGLE_OFF")
    check("§A4.5 白名单仍只放 6 个「真问题」码",
          visible is not None and all(code in visible for code in keep),
          "缺码：%r" % ([code for code in keep if visible is None or code not in visible],))
    if visible is not None:
        # 只统计 case 标签里的码：`-> true` 之前那段
        true_branch = visible.split("->", 1)[0]
        leaked = [code for code in drop if code in true_branch]
    else:
        leaked = list(drop)
    check("§A4.6 「正常/半正常」的 4 个码仍不上屏（正常时一个字都不画）",
          not leaked,
          "这些码重新进了白名单：%r" % (leaked,))
    return results


# ======================================================================================
# §A5 记住上次编辑区域
# ======================================================================================

def section_a5(s):
    results = []
    be = s["be"]
    menu = s["menu"]
    adv = s["adv"]

    def check(name, ok, detail=""):
        results.append((name, bool(ok), detail))

    tag = re.search(r'private\s+static\s+final\s+String\s+TAG_LAST_EDITING_AREA\s*=\s*"([^"]*)"', be)
    check("§A5.1 NBT 键字面量逐字为 LastEditingArea（契约 §3.A.5 指定）",
          tag is not None and tag.group(1) == "LastEditingArea",
          "实际：%r" % (tag.group(1) if tag else None))
    check("§A5.2 方块实体有 lastEditingArea 实例字段且默认 -1",
          re.search(r'private\s+int\s+lastEditingArea\s*=\s*-1\s*;', be) is not None,
          "字段行：%r" % field_line(be, "lastEditingArea"))
    getter = find_method_body(be, "getLastEditingArea")
    check("§A5.3 getLastEditingArea() 返回字段本值",
          getter is not None and "return lastEditingArea;" in getter,
          "方法体：%r" % (getter,))
    setter = find_method_body(be, "setLastEditingArea")
    check("§A5.4 setLastEditingArea 写字段并 setChanged()（真的会落盘）",
          setter is not None and "lastEditingArea = areaIndex;" in setter
          and "setChanged();" in setter,
          "方法体：%r" % (setter,))
    save = find_method_body(be, "saveAdditional")
    check("§A5.5 saveAdditional 真的写 TAG_LAST_EDITING_AREA",
          save is not None and "tag.putInt(TAG_LAST_EDITING_AREA, lastEditingArea)" in save,
          "saveAdditional 里找不到写入行")
    load = find_method_body(be, "loadAdditional")
    check("§A5.6 loadAdditional 缺键回 -1（旧档 / 从没切过区域）",
          load is not None and "tag.contains(TAG_LAST_EDITING_AREA)" in load
          and re.search(r'tag\.contains\(TAG_LAST_EDITING_AREA\)\s*\?\s*tag\.getInt'
                        r'\(TAG_LAST_EDITING_AREA\)\s*:\s*-1', load) is not None,
          "loadAdditional 里找不到 `contains ? getInt : -1`")

    # 构造器有 4 个重载（1 参 / 2 参 / 3 参 / 委托用的 this(...)），不能按「第一个同名方法」取；
    # 这里锚定「this.advanced = block;」之后的那一段（唯一一处），语义就是「服务端构造器里」。
    ctor_window = window_after(menu, "this.advanced = block;", 1200)
    check("§A5.7 菜单构造器从方块实体读回记忆值（打开界面即生效）",
          "block.getLastEditingArea()" in ctor_window,
          "构造器里（this.advanced = block 之后）找不到 block.getLastEditingArea()")
    check("§A5.8 读回时走越界收敛（不能指向不存在的行）",
          "clampEditingArea(block.getLastEditingArea())" in ctor_window,
          "构造器里没有 clampEditingArea(...) 包裹")
    clamp = find_method_body(menu, "clampEditingArea")
    check("§A5.9 clampEditingArea 判据 = 0..getAreaCount()-1，越界 -1",
          clamp is not None and "areaIndex >= 0" in clamp
          and "areaIndex < getAreaCount()" in clamp and "-1" in clamp,
          "方法体：%r" % (clamp,))

    set_edit = find_method_body(menu, "setEditingArea")
    write_at = set_edit.find("advanced.setLastEditingArea(clamped)") if set_edit else -1
    early_at = set_edit.find("if (editingAreaIndex == clamped)") if set_edit else -1
    check("§A5.10 setEditingArea 是唯一落盘写入点（SetAreaFilterContextPacket 生效处）",
          write_at >= 0,
          "方法体里找不到 advanced.setLastEditingArea(clamped)")
    check("§A5.11 落盘写在「值没变就 return」之前（点同一行也要记住）",
          write_at >= 0 and early_at >= 0 and write_at < early_at,
          "顺序：写入@%d 早退@%d（必须是 写入 < 早退）" % (write_at, early_at))
    check("§A5.12 切换后仍 syncMarkers()（匹配区窗口换成该区域的过滤）",
          set_edit is not None and "syncMarkers();" in set_edit
          and "setMatchOffset(0);" in set_edit,
          "setEditingArea 少了 syncMarkers()/setMatchOffset(0)")

    getter_edit = find_method_body(menu, "getEditingAreaIndex")
    check("§A5.13 客户端读数据槽 DATA_AREA_EDITING（同步链路完整）",
          getter_edit is not None
          and "areaClientData[DATA_AREA_EDITING]" in getter_edit
          and "return editingAreaIndex;" in getter_edit,
          "getEditingAreaIndex 方法体：%r" % (getter_edit,))
    broadcast = find_method_body(menu, "broadcastChanges")
    check("§A5.14 broadcastChanges 仍把「可用位次」换算成快照 raw 下标下发",
          broadcast is not None
          and "base.entries().get(editingAreaIndex).rawIndex()" in broadcast,
          "broadcastChanges 里找不到 entries.get(editingAreaIndex).rawIndex()")

    label = find_method_body(adv, "matchRegionLabel")
    check("§A5.15 标题带写「区域 N · 名字」（重开后一眼看出在编辑哪个区域）",
          label is not None and 'LANG + "match_label"' in label
          and "editing + 1" in label and 'LANG + "match_label.none"' in label,
          "matchRegionLabel 方法体：%r" % (label,))
    return results


# ======================================================================================
# §A6 基础版逐字不变
# ======================================================================================

def section_a6(s):
    results = []
    base = s["base"]

    def check(name, ok, detail=""):
        results.append((name, bool(ok), detail))

    for token in ("LastEditingArea", "lastEditingArea", "AreaStripPanel", "clampEditingArea"):
        check("§A6.1 基类不被本轮高级版状态污染：%s 出现 0 次" % token,
              occurrences(base, token) == 0,
              "期望 0 处，实际 %d 处" % occurrences(base, token))
    check("§A6.2 基类状态带仍由 absorbStatusText() 驱动",
          find_method_body(base, "drawStatusLine") is not None
          and "absorbStatusText()" in find_method_body(base, "drawStatusLine"),
          "基类 drawStatusLine 方法体变了")
    check("§A6.3 基类标题带钩子仍在（基础版返回「匹配区」）",
          find_method_body(base, "matchRegionLabel") is not None,
          "基类 matchRegionLabel 被删了")
    # 写作用域纪律：本轮 A 组只许动 4 个文件，基类只读。
    check("§A6.4 写作用域表里没有基类（契约 §2：基类只许加钩子，本轮 A 组不加）",
          "client/screen/CollectionCacheScreen.java" not in WRITE_SCOPE,
          "写作用域表被改坏了")
    return results


# ======================================================================================
# 汇总 / 反例
# ======================================================================================

SECTIONS = (section_a1, section_a2, section_a3, section_a4, section_a5, section_a6)


def run_all(snapshot):
    results = []
    for section in SECTIONS:
        results.extend(section(snapshot))
    return results


def failures_of(snapshot):
    return set(name for name, ok, _detail in run_all(snapshot) if not ok)


def copy_with(snapshot, **overrides):
    out = dict(snapshot)
    out.update(overrides)
    return out


def repl(snapshot, key, old, new, expect=1):
    """把 snapshot[key] 里的 old 换成 new；锚点缺失返回 None（反例判负）。"""
    text = snapshot.get(key, "")
    if text.count(old) < expect:
        return None
    return copy_with(snapshot, **{key: text.replace(old, new, expect)})


def run_counterexamples(base_snapshot, base_bad):
    cases = []

    # ① 「状态带 tooltip」复活
    def case_tooltip(snap):
        return copy_with(snap, adv=snap["adv"] + "\n    protected void renderStatusBandTooltip("
                         "final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {\n    }\n")

    cases.append(("注入状态带 tooltip 覆写", ["§A1.1"], case_tooltip))

    # ② 「过滤图标 tooltip」复活（花字的另一半）
    def case_icon_tooltip(snap):
        return copy_with(snap, adv=snap["adv"] + "\n    private void renderFilterIconTooltip("
                         "final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {\n    }\n")

    cases.append(("注入图标 tooltip 方法", ["§A1.2"], case_icon_tooltip))

    # ③ 图标状态字段复活
    def case_icons_state(snap):
        return copy_with(snap, adv=snap["adv"] + "\n    private int iconsDrawn;\n")

    cases.append(("复活 iconsDrawn 状态字段", ["§A1.3"], case_icons_state))

    # ④ 匹配区空态提示复活（图 2 那行黄字）
    def case_hint(snap):
        return copy_with(snap, adv=snap["adv"] + "\n    @Override\n    protected void drawMatchAreaHint("
                         "final GuiGraphics guiGraphics) {\n        guiGraphics.fill(0, 0, 1, 1, 0);\n    }\n")

    cases.append(("复活匹配区空态提示", ["§A2.1"], case_hint))

    # ⑤ 区域条重新画行内图标 + 预留宽度
    def case_row_icons(snap):
        text = snap["panel"]
        anchor = ("    private void drawRowCoords(final GuiGraphics guiGraphics, final Row row, "
                  "final int lineTop,\n                               final int textX, final int actW) {")
        if anchor not in text:
            return None
        text = text.replace(anchor, anchor + "\n        final int iconW = 20;\n"
                                        "        AdvancedCollectionCacheScreen.drawFilterIcons("
                                        "guiGraphics, contentRight() - iconW, lineTop, "
                                        "java.util.List.of(), 2);", 1)
        return copy_with(snap, panel=text)

    cases.append(("区域条重新画行内图标", ["§A3.1", "§A3.3"], case_row_icons))

    # ⑥ 坐标行重新预留图标宽度（几何回退）
    def case_geometry(snap):
        return repl(snap, "panel",
                    "Math.max(12, contentRight() - textX - actW)",
                    "Math.max(12, contentRight() - textX - actW - iconW)")

    cases.append(("坐标行重新预留图标宽度", ["§A3.3", "§A3.4"], case_geometry))

    # ⑦ 摘要行复活
    def case_summary(snap):
        return copy_with(snap, adv=snap["adv"] + '\n    private Component editAreaSummary() {\n'
                         '        return Component.translatable(LANG + "filter.row", 1, 2, 3);\n    }\n')

    cases.append(("复活橙色摘要行 editAreaSummary", ["§A4.1", "§A4.2", "§A4.3"], case_summary))

    # ⑧ NBT 键改名（存档契约被打断）
    def case_tag(snap):
        return repl(snap, "be", 'TAG_LAST_EDITING_AREA = "LastEditingArea";',
                    'TAG_LAST_EDITING_AREA = "LastEditArea";')

    cases.append(("NBT 键改成 LastEditArea", ["§A5.1"], case_tag))

    # ⑨ 保存不落盘
    def case_no_save(snap):
        return repl(snap, "be",
                    "        if (lastEditingArea >= 0) {\n"
                    "            tag.putInt(TAG_LAST_EDITING_AREA, lastEditingArea);\n        }",
                    "        if (lastEditingArea >= 0) {\n            // 不落盘\n        }")

    cases.append(("saveAdditional 不写 LastEditingArea", ["§A5.5"], case_no_save))

    # ⑩ 记忆值不做越界收敛
    def case_no_clamp(snap):
        step = repl(snap, "menu", "clampEditingArea(block.getLastEditingArea())",
                    "block.getLastEditingArea()")
        if step is None:
            return None
        return repl(step, "menu",
                    "return areaIndex >= 0 && areaIndex < getAreaCount() ? areaIndex : -1;",
                    "return areaIndex;")

    cases.append(("记忆值不做越界收敛", ["§A5.8", "§A5.9"], case_no_clamp))

    # ⑪ 落盘被挪到早退之后
    def case_order(snap):
        old = ("        if (advanced != null) {\n"
               "            advanced.setLastEditingArea(clamped);\n        }\n"
               "        if (editingAreaIndex == clamped) {\n            return;\n        }")
        new = ("        if (editingAreaIndex == clamped) {\n            return;\n        }\n"
               "        if (advanced != null) {\n"
               "            advanced.setLastEditingArea(clamped);\n        }")
        return repl(snap, "menu", old, new)

    cases.append(("落盘挪到「值没变就 return」之后", ["§A5.11"], case_order))

    # ⑫ 空树：所有源文件为空时，全部判据必须判负
    def case_empty(snap):
        return copy_with(snap, **{key: "" for key in FILES})

    cases.append(("空树（所有源文件为空）", ["§A"], case_empty))

    for label, targets, builder in cases:
        COUNTER_TOTAL[0] += 1
        broken = builder(base_snapshot)
        if broken is None:
            print("FAIL 反例 %s（锚点缺失：实现文本变了却没同步本脚本）" % label)
            continue
        fresh = [name for name in failures_of(broken) if name not in base_bad]
        hit = [name for name in fresh if any(name.startswith(prefix) for prefix in targets)]
        if hit:
            COUNTER_PASS[0] += 1
            print("PASS 反例 %s ⇒ 新增判负 %d 项（例：%s）" % (label, len(fresh), hit[0]))
            continue
        print("FAIL 反例 %s（改了却没多出目标判负；新增失败：%s）" % (label, fresh[:4]))


# ======================================================================================
# main
# ======================================================================================

def main():
    print("第 131 轮契约自检（A 组）：缓存仓界面收敛 + 记住上次编辑区域")
    print("规格：%s §3.A" % CONTRACT)
    print("源码根：%s" % JAVA_REL.replace(os.sep, "/"))

    missing = [rel(path) for key, path in FILES.items() if not os.path.isfile(path)]
    if missing:
        print("\n[注意] 以下文件还不存在（实现未就绪时正常）：")
        for item in missing:
            print("  - %s" % item)

    snapshot = load_snapshot()
    results = run_all(snapshot)
    for name, ok, detail in results:
        if ok:
            print("PASS " + name)
        else:
            print("FAIL " + name + (("  <- " + detail) if detail else ""))

    base_bad = set(name for name, ok, _detail in results if not ok)
    print("\n检查项：%d，失败：%d" % (len(results), len(base_bad)))
    if base_bad:
        for name in sorted(base_bad):
            print("  FAIL " + name)

    run_counterexamples(snapshot, base_bad)

    print("\n检查项：%d，反例：%d/%d，失败：%d"
          % (len(results), COUNTER_PASS[0], COUNTER_TOTAL[0], len(base_bad)))
    if base_bad or COUNTER_PASS[0] != COUNTER_TOTAL[0]:
        print("FAILED")
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
