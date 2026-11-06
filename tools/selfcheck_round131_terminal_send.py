#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 131 轮 · D 组契约自检：高级远程多功能终端第 8 个「发送」模式。

规格来源
--------
`docs/ROUND131_CONTRACT.md` §0（用户原话「给我们的终端再加一个功能，就是也支持这一个
网络资源发送器的功能」）、§1 Q2（`MODE_SEND = 7` / `MODE_COUNT = 8`）、§3.D（四条硬判据）。

本脚本是**规格判定程序**
------------------------
* 只读 `src/**`，写作用域只有 `tools/`；不 import 游戏代码、不跑 gradlew / javac；
* 纯 stdlib python3；定位一律用「正则找定义 + 括号配对（先屏蔽注释与字符串）」，
  **不写死行号**，实现方随时改行号都不影响；
* 断言**语义**而不是「文件里有这个字符串」：模式常量 / 数组长度 / 图标非空 / 几何不重叠
  不越界 / CustomData 双向读写 / 不缓存 ItemStack / 不复制投递实现 / 六道发送闸门 ——
  每条都钉在具体方法体或具体算式上；
* **反例有判别力**：把旧写法拼回去（`MODE_COUNT` 回 7、第 8 颗图标留空、订单不落 CustomData、
  `stillValid` 缓存 ItemStack、`isConnected` 改回「网络判据」、绕开 `openFor` 自己开界面、
  删掉电量闸门、在终端里重写一份 `storage.extract` 投递）必须判负；**空树**也必须判负。
  反例只在内存里改字符串，**不写任何文件**。

PENDING_LEAD（不算失败，但会逐条打印）
------------------------------------
`lang/zh_cn.json` / `lang/en_us.json` 是 Lead 独占的：本组新增的 10 个键（第 8 颗 Tab 的名字 +
9 条终端发送提示）在 Lead 落盘之前，脚本把它们列进 `PENDING_LEAD`，
**不混进失败计数**（否则本组永远交不了），但也绝不静默：每一条都带键名与 zh/en 建议文案。

用法：python tools/selfcheck_round131_terminal_send.py
退出码：0 = D 组判据全过（PENDING_LEAD 允许非空）；1 = 有失败。最后一行：ALL_PASS / FAILED。
"""

from __future__ import annotations

import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_REL = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG_REL = os.path.join("src", "main", "resources", "assets", "rs_create_compat", "lang")
CONTRACT = "docs/ROUND131_CONTRACT.md"

PICKER_MENU = os.path.join(JAVA_REL, "client", "grid", "ResourcePickerMenu.java")
PICKER_SCREEN = os.path.join(JAVA_REL, "client", "grid", "ResourcePickerScreen.java")

#: 本组写作用域内的文件（键名稳定；断言 / 反例都按这个键取内容）。
FILES = {
    "item": os.path.join(JAVA_REL, "item", "AdvancedRemoteTerminalItem.java"),
    "overlay": os.path.join(JAVA_REL, "client", "TerminalModeTabOverlay.java"),
    "backend": os.path.join(JAVA_REL, "support", "TerminalSenderBackend.java"),
    "picker_menu": PICKER_MENU,
    "picker_screen": PICKER_SCREEN,
    "lang_zh": os.path.join(LANG_REL, "zh_cn.json"),
    "lang_en": os.path.join(LANG_REL, "en_us.json"),
}

#: §3.D1：模式常量（键 = 常量名，值 = 契约钉死的字面量）。
MODE_CONSTANTS = (
    ("MODE_SEND", 7, "第 8 个模式页 = 发送（用户拍板 Q2）"),
    ("MODE_COUNT", 8, "Tab 一排画这么多颗；必须 = MODE_SEND + 1"),
)

#: §3.D3：终端物品 CustomData 的键（跟物品走）。
TAG_ORDERS = "RsccSenderOrders"
TAG_TARGET = "RsccSenderTarget"

#: §3.D2：第 8 颗 Tab 的名字语言键。
MODE_SEND_KEY = "gui.rs_create_compat.advanced_remote_terminal.mode.send"

#: §3.D4：终端发送提示的语言键后缀（文案由 Lead 落 lang；脚本只校验键名引用 + 中文兜底）。
TERMINAL_MSG_SUFFIXES = (
    "no_terminal", "no_network", "no_power", "unconfigured", "no_orders",
    "pending_carry", "order_not_saved", "buffered_to_player", "buffered_retry_later",
)

#: §3.D4：发送闸门 —— `triggerWithPlan` 里每一条都必须有「检查 + 一句可见原因」。
GATES = (
    ("MSG_NO_TERMINAL", "no_terminal", "终端已不在身上"),
    ("MSG_NO_NETWORK", "no_network", "终端没有绑定可用网络"),
    ("MSG_NO_POWER", "no_power", "绑定网络没电"),
    ("MSG_UNCONFIGURED", "unconfigured", "还没选目标接收器"),
    ("stateFail(\"ambiguous\")", "ambiguous", "重名接收器拒绝"),
    ("stateFail(\"not_found\")", "not_found", "目标不存在"),
    ("MSG_NO_ORDERS", "no_orders", "订单为空"),
)


# ======================================================================================
# 文本工具：屏蔽注释 / 字符串 → 括号配对 → 取方法体（不写死行号）
# ======================================================================================

def read_text(path: str) -> str:
    if not os.path.isfile(path):
        return ""
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def mask_syntax(text: str) -> str:
    """把注释与字符串 / 字符字面量替换成空格（长度不变）。

    为什么必须屏蔽：LOGGER 的 `{}` 占位符与 javadoc 里的括号都会让裸括号配对错位，
    一旦配对错了，下面所有「方法体」断言都变成对着错误区间做判断（假绿）。
    """
    out = list(text)
    index = 0
    size = len(text)
    while index < size:
        char = text[index]
        if char == "/" and index + 1 < size and text[index + 1] == "/":
            end = text.find("\n", index)
            end = size if end < 0 else end
            for pos in range(index, end):
                out[pos] = " "
            index = end
        elif char == "/" and index + 1 < size and text[index + 1] == "*":
            end = text.find("*/", index + 2)
            end = size if end < 0 else end + 2
            for pos in range(index, end):
                if text[pos] != "\n":
                    out[pos] = " "
            index = end
        elif char == '"':
            out[index] = " "
            index += 1
            while index < size:
                if text[index] == "\\":
                    out[index] = " "
                    if index + 1 < size:
                        out[index + 1] = " "
                    index += 2
                    continue
                if text[index] == '"':
                    out[index] = " "
                    index += 1
                    break
                if text[index] != "\n":
                    out[index] = " "
                index += 1
        elif char == "'":
            out[index] = " "
            index += 1
            while index < size:
                if text[index] == "\\":
                    out[index] = " "
                    if index + 1 < size:
                        out[index + 1] = " "
                    index += 2
                    continue
                if text[index] == "'":
                    out[index] = " "
                    index += 1
                    break
                if text[index] != "\n":
                    out[index] = " "
                index += 1
        else:
            index += 1
    return "".join(out)


def strip_comments(text: str) -> str:
    """只去掉注释，**保留字符串字面量**（键名 / 图标物品名都在字符串与代码里）。"""
    out = list(text)
    index = 0
    size = len(text)
    while index < size:
        char = text[index]
        if char == "/" and index + 1 < size and text[index + 1] == "/":
            end = text.find("\n", index)
            end = size if end < 0 else end
            for pos in range(index, end):
                out[pos] = " "
            index = end
        elif char == "/" and index + 1 < size and text[index + 1] == "*":
            end = text.find("*/", index + 2)
            end = size if end < 0 else end + 2
            for pos in range(index, end):
                if text[pos] != "\n":
                    out[pos] = " "
            index = end
        elif char == '"':
            index += 1
            while index < size:
                if text[index] == "\\":
                    index += 2
                    continue
                if text[index] == '"':
                    index += 1
                    break
                index += 1
        elif char == "'":
            index += 1
            while index < size:
                if text[index] == "\\":
                    index += 2
                    continue
                if text[index] == "'":
                    index += 1
                    break
                index += 1
        else:
            index += 1
    return "".join(out)


def match_paren(masked: str, open_index: int) -> "int | None":
    depth = 0
    for pos in range(open_index, len(masked)):
        char = masked[pos]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return pos
    return None


def match_brace(masked: str, open_index: int) -> "int | None":
    depth = 0
    for pos in range(open_index, len(masked)):
        char = masked[pos]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return pos
    return None


def method_spans(masked: str, name: str) -> "list[tuple[int, int]]":
    """返回所有**方法定义**的 [起, 止) 区间（起 = 定义所在行行首，止 = 方法体右括号之后）。"""
    spans = []
    pattern = re.compile(r"(?<![A-Za-z0-9_$])" + re.escape(name) + r"\s*\(")
    for match in pattern.finditer(masked):
        close = match_paren(masked, match.end() - 1)
        if close is None:
            continue
        probe = close + 1
        while probe < len(masked) and masked[probe] in " \t\r\n":
            probe += 1
        if probe >= len(masked) or masked[probe] != "{":
            continue  # 调用点（不是定义）：调用后面不会紧跟方法体
        line_start = masked.rfind("\n", 0, match.start()) + 1
        prefix = masked[line_start:match.start()]
        if not re.search(r"\b(public|private|protected|static|final|default|abstract|synchronized)\b",
                         prefix):
            continue  # 至少要有修饰符，才算定义
        end = match_brace(masked, probe)
        if end is None:
            continue
        spans.append((line_start, end + 1))
    return spans


class Tree:
    """一份源码快照 + 派生视图（去注释 / 屏蔽语法）。"""

    def __init__(self, raw: "dict[str, str]"):
        self.raw = dict(raw)
        self._masked = {}
        self._code = {}

    def raw_code(self, key: str) -> str:
        return self.raw.get(key, "")

    def code(self, key: str) -> str:
        if key not in self._code:
            self._code[key] = strip_comments(self.raw.get(key, ""))
        return self._code[key]

    def masked(self, key: str) -> str:
        if key not in self._masked:
            self._masked[key] = mask_syntax(self.raw.get(key, ""))
        return self._masked[key]

    def bodies(self, key: str, name: str) -> "list[str]":
        masked = self.masked(key)
        raw = self.raw.get(key, "")
        out = []
        for start, end in method_spans(masked, name):
            out.append(strip_comments(raw[start:end]))
        return out

    def body(self, key: str, name: str) -> str:
        found = self.bodies(key, name)
        assert found, "找不到方法定义 %s（%s）" % (name, key)
        return max(found, key=len)  # 重载时取最长的那个（真正的实现）

    def has_method(self, key: str, name: str) -> bool:
        return bool(method_spans(self.masked(key), name))


def collapse(text: str) -> str:
    return re.sub(r"\s+", " ", text)


def literal_int(code: str, name: str) -> "int | None":
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*(\d+)\s*;", code)
    return int(match.group(1)) if match else None


def count_of(code: str, needle: str) -> int:
    return code.count(needle)


# ======================================================================================
# 断言注册表
# ======================================================================================

CHECKS = []


def check(name):
    def register(function):
        CHECKS.append((name, function))
        return function
    return register


def require(condition, message):
    assert condition, message


# ======================================================================================
# §D1 模式号 / 开关 / 打开链路（item/AdvancedRemoteTerminalItem.java）
# ======================================================================================

@check("§D1.1 MODE_SEND=7、MODE_COUNT=8，且 MODE_COUNT = MODE_SEND + 1")
def _d1_1(tree: Tree):
    code = tree.code("item")
    for name, expected, why in MODE_CONSTANTS:
        actual = literal_int(code, name)
        require(actual == expected,
                "%s = %r，契约要求 %d（%s）" % (name, actual, expected, why))
    require(literal_int(code, "MODE_COUNT") == literal_int(code, "MODE_SEND") + 1,
            "MODE_COUNT(%r) 必须 = MODE_SEND(%r) + 1"
            % (literal_int(code, "MODE_COUNT"), literal_int(code, "MODE_SEND")))


@check("§D1.2 isModeSupported 覆盖 MODE_SEND（不可用模式会被 Tab 直接拦掉）")
def _d1_2(tree: Tree):
    body = tree.body("item", "isModeSupported")
    require("MODE_SEND" in body or re.search(r"mode\s*==\s*7\b", body),
            "isModeSupported 没覆盖 MODE_SEND ⇒ 第 8 颗 Tab 永远是「不可用模式」")
    require("MODE_COUNT" not in body or "MODE_SEND" in body,
            "isModeSupported 若按 MODE_COUNT 范围放行必须显式包含 MODE_SEND")


@check("§D1.3 openModeScreen 的 switch 有 case MODE_SEND 且指向 openSendMode(…, title)")
def _d1_3(tree: Tree):
    code = tree.code("item")
    require(re.search(r"case\s+MODE_SEND\s*->\s*opened\s*=\s*openSendMode\s*\(", code),
            "openModeScreen 没有 `case MODE_SEND -> opened = openSendMode(...)` "
            "（没有这一档，点第 8 颗 Tab 只会落到 default 打开合成终端）")
    require(tree.has_method("item", "openSendMode"), "openSendMode 方法不存在")


@check("§D1.4 openSendMode 复用同一个发送界面（ResourcePickerMenu.openFor + TerminalSenderBackend）")
def _d1_4(tree: Tree):
    raw_body = tree.body("item", "openSendMode")
    body = collapse(raw_body)
    require("TerminalSenderBackend.open(" in body,
            "openSendMode 没有构造 TerminalSenderBackend（后端 = 终端物品实现）")
    require("client.grid.ResourcePickerMenu" in body and ".openFor(player, backend, title)" in body,
            "openSendMode 没有调 ResourcePickerMenu.openFor(player, backend, title) "
            "⇒ 终端会开出「第二套发送界面」（契约 §2/§3.D2 要求同一个界面）")
    require("new ResourcePickerMenu(" not in tree.code("item")
            and "new ResourcePickerScreen(" not in tree.code("item"),
            "终端里出现了 `new ResourcePickerMenu/Screen` ⇒ 绕开了唯一打开入口 openFor")
    require(re.search(r"private\s+boolean\s+openSendMode\s*\(", raw_body),
            "openSendMode 必须返回 boolean（打开失败要能告诉调用方，别静默）")


@check("§D1.5 终端不在身上 ⇒ 打开阶段就拒绝并给提示（不让界面一闪就没）")
def _d1_5(tree: Tree):
    body = collapse(tree.body("item", "openSendMode"))
    require("TerminalSenderBackend.open(player, slotReference)" in body,
            "openSendMode 没有走 TerminalSenderBackend.open(...)（打开阶段无法判定终端是否还在）")
    require("backend == null" in body, "openSendMode 没有判 backend == null")
    require("MSG_NO_TERMINAL" in body, "openSendMode 的「终端不在身上」分支没有专用提示键")
    require("displayClientMessage" in body, "openSendMode 的失败分支没有给玩家一句话（静默）")
    require(re.search(r"return\s+false\s*;", body),
            "openSendMode 在失败分支没有 return false（调用方无法知道界面没开）")


@check("§D1.6 openFor 返回 false（openMenu 没成）也有一句话")
def _d1_6(tree: Tree):
    body = collapse(tree.body("item", "openSendMode"))
    require("MSG_OPEN_FAILED" in body,
            "openSendMode 没有处理 openFor=false 的第二种失败（openMenu 没成）⇒ 玩家看到点了没反应")
    require(body.count("displayClientMessage") >= 2,
            "openSendMode 的两条失败路径（终端没了 / 界面没开成）都必须各有一句提示")


# ======================================================================================
# §D2 Tab（client/TerminalModeTabOverlay.java）
# ======================================================================================

@check("§D2.1 MODE_KEYS 条目数 = MODE_COUNT，且第 8 条是 mode.send")
def _d2_1(tree: Tree):
    code = tree.code("overlay")
    match = re.search(r"MODE_KEYS\s*=\s*\{(.*?)\}\s*;", code, re.S)
    require(match, "找不到 MODE_KEYS 数组字面量")
    keys = re.findall(r'"([^"]+)"', match.group(1))
    count = literal_int(tree.code("item"), "MODE_COUNT")
    require(count is not None, "读不到 MODE_COUNT")
    require(len(keys) == count,
            "MODE_KEYS 有 %d 条，但 MODE_COUNT=%d ⇒ 新 Tab 拿不到名字（Tooltip 变裸键名）"
            % (len(keys), count))
    require(keys[-1] == MODE_SEND_KEY,
            "MODE_KEYS 最后一条是 %r，应为 %r（下标必须 = 模式号）" % (keys[-1], MODE_SEND_KEY))


@check("§D2.2 iconFor 覆盖 0..7 且第 8 颗非空（Items.HOPPER）")
def _d2_2(tree: Tree):
    code = tree.code("overlay")
    body = tree.body("overlay", "iconFor")
    count = literal_int(tree.code("item"), "MODE_COUNT")
    require(count is not None, "读不到 MODE_COUNT")
    explicit = {}
    for match in re.finditer(r"case\s+(\d+)\s*->\s*([^;]+);", body):
        explicit[int(match.group(1))] = match.group(2).strip()
    missing = [mode for mode in range(count) if mode not in explicit]
    require(not missing,
            "iconFor 缺少模式 %r 的显式分支（会落到 default，图标语义漂移）" % (missing,))
    require("ItemStack.EMPTY" not in body,
            "iconFor 里出现 ItemStack.EMPTY ⇒ 会画出一颗不可见的 Tab")
    hopper = explicit.get(7, "")
    require("Items.HOPPER" in hopper,
            "第 8 颗 Tab 的图标是 %r，契约要求 Items.HOPPER（非空、语义 = 送出去）" % (hopper,))
    require("new ItemStack(" in hopper, "第 8 颗 Tab 的图标必须是 new ItemStack(...)，不能是裸 item 变量")
    require(count_of(code, "iconCached(") >= 2,
            "图标没有走会话级缓存（renderWidget 每帧新建 ItemStack）")


@check("§D2.3 ICONS 数组长度派生自 MODE_COUNT（不写第二个魔法数字）")
def _d2_3(tree: Tree):
    code = tree.code("overlay")
    require("new ItemStack[AdvancedRemoteTerminalItem.MODE_COUNT]" in code,
            "ICONS 数组长度没有直接取 MODE_COUNT ⇒ 加模式时新 Tab 图标恒空")


@check("§D2.4 几何：8 颗纵向排列不重叠、不越界（用解析出的常量复算屏幕坐标）")
def _d2_4(tree: Tree):
    overlay = tree.code("overlay")
    tab_w = literal_int(overlay, "TAB_W")
    tab_h = literal_int(overlay, "TAB_H")
    margin = literal_int(overlay, "SCREEN_MARGIN")
    count = literal_int(tree.code("item"), "MODE_COUNT")
    require(tab_w and tab_h and margin is not None and count, "读不到 Tab 几何常量 / MODE_COUNT")
    compact = re.sub(r"\s+", "", overlay)
    require("columnH=TAB_H*count" in compact,
            "一排 Tab 的总高必须由 TAB_H * count 算出（不能写死 154）")
    require(re.search(r"for\s*\(\s*int\s+i\s*=\s*0;\s*i\s*<\s*count;\s*i\+\+\s*\)", overlay),
            "Tab 循环没有用 count（MODE_COUNT 派生）")
    require("columnTop + (TAB_H * i)" in overlay,
            "每颗 Tab 的 y 必须是 columnTop + TAB_H * i（否则会重叠）")

    column_h = tab_h * count

    def clamp(value, low, high):
        return max(low, min(value, max(low, high)))

    # 常见 GUI 缩放下的可用区域 + 各模式面板尺寸（网格 176/193、监视器 254、序列样板 256×324）
    cases = [(426, 240, 176, 176), (426, 240, 193, 176), (426, 240, 254, 176),
             (426, 240, 256, 324), (320, 240, 193, 176), (640, 360, 193, 176)]
    for screen_w, screen_h, gui_w, gui_h in cases:
        left = (screen_w - gui_w) // 2
        top = (screen_h - gui_h) // 2
        x = clamp(left + gui_w + margin, margin, screen_w - tab_w - margin)
        column_top = clamp(top + gui_h - column_h, margin, screen_h - column_h - margin)
        ys = [column_top + tab_h * index for index in range(count)]
        for index in range(1, len(ys)):
            require(ys[index] >= ys[index - 1] + tab_h,
                    "第 %d / %d 颗 Tab 在 %dx%d 屏幕上重叠（y=%d / %d）"
                    % (index, index + 1, screen_w, screen_h, ys[index - 1], ys[index]))
        require(ys[0] >= margin, "%dx%d 屏幕上第一颗 Tab 越过上边界" % (screen_w, screen_h))
        require(ys[-1] + tab_h <= screen_h - margin,
                "%dx%d 屏幕上最后一颗 Tab 越过下边界（%d > %d）"
                % (screen_w, screen_h, ys[-1] + tab_h, screen_h - margin))
        require(x + tab_w <= screen_w - margin,
                "%dx%d 屏幕上 Tab 越过右边界" % (screen_w, screen_h))


@check("§D2.5 发送界面（ResourcePickerScreen）的 Tab 放左侧，且避开 RS 侧键 [leftPos-20, leftPos-2]")
def _d2_5(tree: Tree):
    overlay = tree.code("overlay")
    compact = collapse(overlay)
    require("instanceof cretae.cookiewyq.rs_create_compat.client.grid.ResourcePickerScreen" in compact,
            "Tab 排布没有为发送界面（ResourcePickerScreen）单独分支")
    # 第 132 轮：RS 侧键 18×18 占 leftPos-20..leftPos-2，发送档 Tab 列必须再左移 18+4。
    require("AbstractSideButtonWidget.SIZE - 4" in compact,
            "发送界面的 Tab 没有按 RS 侧键宽度（SIZE+4）让位")
    require("getGuiLeft() - TAB_W - AbstractSideButtonWidget.SIZE - 4" in compact,
            "发送界面的 Tab 不是 leftPos - TAB_W - 侧键宽 - 4（仍会压住 RS 侧键）")
    require("getGuiLeft() - TAB_W - SCREEN_MARGIN" not in compact,
            "旧的 leftPos - TAB_W - 2 公式仍存在（与 RS 侧键重叠）")
    picker = tree.code("picker_screen")
    grid_w = literal_int(picker, "GRID_IMAGE_W")
    panel_gap = literal_int(picker, "PANEL_GAP")
    panel_w = literal_int(picker, "PANEL_W")
    require(None not in (grid_w, panel_gap, panel_w), "读不到发送界面的面板几何常量")
    require("PANEL_X_OFFSET = GRID_IMAGE_W + PANEL_GAP" in collapse(picker),
            "PANEL_X_OFFSET 不再是 GRID_IMAGE_W + PANEL_GAP（本断言的面板位置会失效）")
    # 复算：193 宽网格居中在 426 可用宽度上 ⇒ leftPos = 116
    tab_w = literal_int(tree.code("overlay"), "TAB_W")
    margin = literal_int(tree.code("overlay"), "SCREEN_MARGIN")
    screen_w, gui_w = 426, grid_w
    left = (screen_w - gui_w) // 2
    right_side_x = left + gui_w + margin
    panel_left = left + grid_w + panel_gap
    panel_right = panel_left + panel_w
    require(right_side_x < panel_right and right_side_x + tab_w > panel_left,
            "复算显示「右侧放 Tab」并不会压住订单清单（%d..%d vs 清单 %d..%d）"
            " ⇒ 这个分支的存在理由不成立，需重新评估"
            % (right_side_x, right_side_x + tab_w, panel_left, panel_right))
    # 侧键：x ∈ [leftPos-20, leftPos-2]；Tab 列 x = leftPos-46，右缘 leftPos-22。
    side_button_left = left - 18 - 2
    side_button_right = left - 2
    left_side_x = left - tab_w - 18 - 4
    require(left_side_x + tab_w <= side_button_left,
            "左侧 Tab 列压住 RS 侧键（Tab 右缘 %d > 侧键左缘 %d）"
            % (left_side_x + tab_w, side_button_left))
    require(left_side_x + tab_w < side_button_right,
            "左侧 Tab 列没有和 RS 侧键保持间隙（Tab 右缘 %d，侧键右缘 %d）"
            % (left_side_x + tab_w, side_button_right))
    require(left_side_x >= margin,
            "左侧放置的 Tab 越过屏幕左边界（x=%d）" % left_side_x)


# ======================================================================================
# §D3 CustomData 持久化（support/TerminalSenderBackend.java）
# ======================================================================================

@check("§D3.1 CustomData 键名 = RsccSenderOrders / RsccSenderTarget")
def _d3_1(tree: Tree):
    code = tree.code("backend")
    require('TAG_ORDERS = "%s"' % TAG_ORDERS in code,
            "订单键不是 %r（契约 §3.D3 钉死的键名）" % TAG_ORDERS)
    require('TAG_TARGET = "%s"' % TAG_TARGET in code,
            "目标键不是 %r（契约 §3.D3 钉死的键名）" % TAG_TARGET)


@check("§D3.2 订单用 RS 自己的 AMOUNT_CODEC（不自拼 key/amount 两个字段）")
def _d3_2(tree: Tree):
    code = tree.code("backend")
    require("ResourceCodecs.AMOUNT_CODEC.listOf()" in code,
            "订单序列化没有复用 ResourceCodecs.AMOUNT_CODEC.listOf() "
            "（自拼 key/amount 会漏掉流体 / 第三方资源的数据组件）")
    require("ORDER_CODEC" in code, "没有 ORDER_CODEC 常量")


@check("§D3.3 save() 把订单 + 目标写进物品 DataComponents.CUSTOM_DATA")
def _d3_3(tree: Tree):
    body = collapse(tree.body("backend", "save"))
    require("DataComponents.CUSTOM_DATA" in body, "save() 没写 CUSTOM_DATA")
    require("stack.update(" in body, "save() 没走 stack.update(组件, 默认值, 修改器) 的写法")
    require("TAG_ORDERS" in body, "save() 没写订单键 ⇒ 关界面就丢订单")
    require("TAG_TARGET" in body, "save() 没写目标键")
    require("ORDER_CODEC.encodeStart(" in body, "save() 没有真正编码订单")


@check("§D3.4 load() 从 CustomData 读回（坏数据不抛异常、不静默改成别的资源）")
def _d3_4(tree: Tree):
    body = collapse(tree.body("backend", "load"))
    require("TAG_TARGET" in body, "load() 没读目标键")
    require("TAG_ORDERS" in body, "load() 没读订单键")
    require("ORDER_CODEC.parse(" in body, "load() 没有真正解析订单")
    require("catch (RuntimeException" in body or "catch (final RuntimeException" in body,
            "load() 没有兜住坏数据 ⇒ 一条坏 NBT 会让界面打不开")
    require("orders.addAll(sanitize(" in body or "setOrders(" in body,
            "load() 读回的条目没有走清洗（同资源合并 / 丢非正数）")
    constructor = collapse(tree.body("backend", "TerminalSenderBackend"))
    require("load()" in constructor,
            "后端构造时没有 load() ⇒ 打开界面读不回上次的订单 / 目标")


@check("§D3.5 open(...) 在终端不存在时返回 null（打开阶段判定，不缓存状态）")
def _d3_5(tree: Tree):
    body = collapse(tree.body("backend", "open"))
    require("new TerminalSenderBackend(" in body, "open() 没有构造后端")
    require("liveStack().isEmpty() ? null" in body,
            "open() 没有在「终端不在身上」时返回 null（界面会开出来再被 stillValid 关掉）")


# ======================================================================================
# §D4 不缓存 ItemStack / stillValid 现定位
# ======================================================================================

@check("§D4.1 TerminalSenderBackend 没有任何 ItemStack 类型字段（不缓存物品引用）")
def _d4_1(tree: Tree):
    masked = tree.masked("backend")
    # 字段声明：修饰符 … ItemStack 名字 ;
    declarations = re.findall(
        r"(?m)^[ \t]*(?:private|protected|public)[^;()=]*?\bItemStack\s+([A-Za-z_$][\w$]*)\s*[;=]",
        masked)
    require(not declarations,
            "后端缓存了 ItemStack 字段 %r ⇒ 背包内容包换实例后写的是「没人读的对象」"
            "（TerminalModeTabOverlay 类注释里写尽了这个坑）" % (declarations,))


@check("§D4.2 liveStack() 每次按引用现取 + 失效时按固定优先级重新定位")
def _d4_2(tree: Tree):
    body = collapse(tree.body("backend", "liveStack"))
    require("slotReference.resolve(player)" in body,
            "liveStack() 没有每次都按 SlotReference 现取")
    require("RsccTerminalLocator.locate(player)" in body,
            "liveStack() 引用失效时没有按固定优先级（locate）重新定位")
    require("isTerminal(" in body, "liveStack() 没校验「取到的确实是终端物品」")


@check("§D4.3 stillValid 用现定位判定，且只认打开它的那名玩家")
def _d4_3(tree: Tree):
    body = collapse(tree.body("backend", "stillValid"))
    require("liveStack()" in body, "stillValid 没有现定位（可能是缓存引用）")
    require("player != this.player" in body,
            "stillValid 没校验「同一张菜单只属于打开它的玩家」")
    require("isConnected()" not in body,
            "stillValid 里调 isConnected() 会把「界面可打开」当成「菜单仍有效」（语义重复）")


# ======================================================================================
# §D5 isConnected 的语义（契约 §3.D4「界面照开」）
# ======================================================================================

@check("§D5.1 isConnected() = 终端还在（界面可打开），不含网络判据")
def _d5_1(tree: Tree):
    body = collapse(tree.body("backend", "isConnected"))
    require("return !liveStack().isEmpty();" in body,
            "isConnected() 不是「终端物品仍在」这条判据（%r）—— 契约 §3.D4 要求断网/没电时"
            "发送界面照开，否则 openFor 会在第一行就把界面拒掉" % body)
    require("network()" not in body,
            "isConnected() 里混进了网络判据 ⇒ 断网时 openFor 直接返回 false，与 §3.D4 冲突")
    code = tree.code("backend")
    require("MSG_NO_NETWORK" in code and "MSG_NO_POWER" in code,
            "「网络不可达 / 没电」必须由 trigger() 给可见拒绝原因")


@check("§D5.2 打开阶段不拿网络判据拦人（网络故障留给 trigger()）")
def _d5_2(tree: Tree):
    body = collapse(tree.body("backend", "open"))
    require("network()" not in body,
            "open() 里出现了网络判据 ⇒ 断网时连界面都开不出来")
    require("isConnected()" not in body, "open() 里调 isConnected() 判终端有效性")


@check("§D5.3 打开界面就把「第一条不能发送的原因」写进界面提示行（说明而不是静默）")
def _d5_3(tree: Tree):
    backend_body = collapse(tree.body("backend", "firstIssueKey"))
    for constant in ("MSG_NO_TERMINAL", "MSG_UNCONFIGURED", "MSG_NO_NETWORK",
                     "MSG_NO_POWER", "MSG_NO_ORDERS"):
        require(constant in backend_body,
                "firstIssueKey() 没覆盖 %s（这一档失效时界面一进来仍然没有任何说明）" % constant)
    order = [backend_body.find(name) for name in
             ("MSG_NO_TERMINAL", "MSG_UNCONFIGURED", "MSG_NO_NETWORK", "MSG_NO_POWER",
              "MSG_NO_ORDERS")]
    require(order == sorted(order),
            "firstIssueKey() 的闸门顺序与 triggerWithPlan 不一致（两处说法会互相矛盾）：%r" % (order,))
    open_body = collapse(tree.body("item", "openSendMode"))
    require("firstIssueKey()" in open_body,
            "openSendMode 打开成功后没有取 firstIssueKey()（失效时玩家要点一次发送才知道原因）")
    require("syncTo(" in open_body,
            "openSendMode 没有把原因经 syncTo 送进界面底部提示行")


# ======================================================================================
# §D6 同一套实现（不复制投递 / 询问）
# ======================================================================================

@check("§D6.1 实现 SenderBackend 并覆盖全部 15 个方法")
def _d6_1(tree: Tree):
    code = tree.code("backend")
    require(re.search(r"class\s+TerminalSenderBackend\s+implements\s+SenderBackend", code),
            "TerminalSenderBackend 没有实现 SenderBackend")
    required = ("network", "isConnected", "stillValid", "orders", "setOrders", "clearOrders",
                "targetName", "receiverNames", "setTargetName", "trigger", "triggerWithPlan",
                "batchesPerTick", "batchSize", "lastTriggerSucceeded", "save")
    missing = [name for name in required if not tree.has_method("backend", name)]
    require(not missing, "没有实现 %r（默认实现会让终端少一道闸门 / 不落盘）" % (missing,))


@check("§D6.2 投递只走 SenderDelivery.deliver（终端里没有第二份取料 → 写缓存实现）")
def _d6_2(tree: Tree):
    code = tree.code("backend")
    require(count_of(code, "SenderDelivery.deliver(") == 1,
            "SenderDelivery.deliver( 出现 %d 次，应恰好 1 次（多轮同一次调用即可）"
            % count_of(code, "SenderDelivery.deliver("))
    require(count_of(code, "storage.extract(") == 0,
            "终端里出现了 storage.extract( ⇒ 第二套取料实现（守恒语义会漂移）")
    require(count_of(code, "Action.SIMULATE") == 0,
            "终端里出现了 Action.SIMULATE ⇒ 自己算「能取多少」而不是交给 SenderDelivery")
    require(count_of(code, "receiver.applyResource(") == 1,
            "接收器写入点应恰好 1 处（sink 适配器），实际 %d"
            % count_of(code, "receiver.applyResource("))


@check("§D6.3 缺口询问共用 SenderShortage.ask（不复制询问 / 合成逻辑）")
def _d6_3(tree: Tree):
    body = collapse(tree.body("backend", "triggerWithPlan"))
    require(body.count("SenderShortage.ask(player, this)") == 1,
            "triggerWithPlan 里没有恰好一次 SenderShortage.ask(player, this)")
    require("plan == null && SenderShortage.ask(" in body,
            "缺口询问必须在「按整张订单投」时问（plan != null 的那次再问会死循环）")
    code = tree.code("backend")
    require("autocrafting.ensureTask(" not in code and "getMaxAmount(" not in code,
            "终端里自己写了自动合成 / 最多可合成 ⇒ 必须共用 SenderShortage")


@check("§D6.4 triggerWithPlan 七道闸门齐全（每条都有可见原因，绝不静默）")
def _d6_4(tree: Tree):
    body = collapse(tree.body("backend", "triggerWithPlan"))
    missing = [token for token, _reason, _why in GATES if token not in body]
    require(not missing, "缺少闸门 %r（对应的发送失败会变成「点了没反应」）" % (missing,))
    require("TerminalNetworkPower.isPowered(player, stack)" in body,
            "triggerWithPlan 没有判「绑定网络有没有电」（判据唯一实现 = TerminalNetworkPower）")
    require("SenderDelivery.resolveReceiversByName(network, target)" in body,
            "triggerWithPlan 没有用 SenderDelivery 解析目标（0/1/≥2 三种结果的唯一口径）")
    require("lastTriggerSucceeded = false;" in body or "lastTriggerSucceeded=false;" in body,
            "triggerWithPlan 开头没有把上一轮结论清掉（界面会显示过期的「成功」）")
    require(re.search(r"lastTriggerSucceeded\s*=\s*movedTotal\s*>\s*0L\s*&&\s*!shortfall", collapse(tree.code("backend"))),
            "投递结论没有按「投出过且无缺口」计算 ⇒ 界面会把缺口报成成功")


@check("§D6.5 setTargetName 直接写（不回调 applyTarget，防无限递归）")
def _d6_5(tree: Tree):
    body = collapse(tree.body("backend", "setTargetName"))
    require("applyTarget(" not in body,
            "setTargetName 调了 RequesterSenderBackend.applyTarget ⇒ applyTarget 内部又回调 "
            "setTargetName，会无限递归（C 组已踩过一次）")
    require("targetName =" in body, "setTargetName 没写入内存状态")
    require("save()" in body, "setTargetName 没写回物品 CustomData")


@check("§D6.6 节拍用与请求器同一组常量（用户拍板：红石激活多快就发多快）")
def _d6_6(tree: Tree):
    for method, constant in (("batchesPerTick", "SenderDelivery.FIXED_BATCHES_PER_TICK"),
                             ("batchSize", "SenderDelivery.FIXED_BATCH_SIZE")):
        body = collapse(tree.body("backend", method))
        require(constant in body, "%s() 没有返回 %s（两端节拍会漂移）" % (method, constant))


@check("§D6.7 终端暂不接补投：代码里不登记 PendingSenderSends（限制写在注释里）")
def _d6_7(tree: Tree):
    code = tree.code("backend")
    require("PendingSenderSends" not in code,
            "终端里调了 PendingSenderSends ⇒ 它只接受 RequesterSenderBackend，"
            "登记会被静默丢弃；要么先让 PendingSenderSends 支持终端，要么别写这行死代码")


# ======================================================================================
# §D7 语言键（Lead 独占 lang；本组只校验「引用 + 兜底」）
# ======================================================================================

@check("§D7.1 backend 的每个 LANG_MSG 常量都有中文兜底（缺 lang 也不显示裸键名）")
def _d7_1(tree: Tree):
    code = tree.code("backend")
    declared = re.findall(r'(?m)^[ \t]*public\s+static\s+final\s+String\s+(MSG_[A-Z_]+)\s*=\s*LANG_MSG\s*\+\s*"([^"]+)"\s*;', code)
    require(len(declared) >= len(TERMINAL_MSG_SUFFIXES),
            "LANG_MSG 常量只有 %d 个，少于约定的 %d 条提示"
            % (len(declared), len(TERMINAL_MSG_SUFFIXES)))
    fallback = tree.body("backend", "fallbackFor")
    missing = [suffix for _const, suffix in declared if '"%s"' % suffix not in fallback]
    require(not missing, "这些键在 fallbackFor 里没有中文兜底：%r" % (missing,))
    require('case "%s" ->' % "no_terminal" in fallback or '"no_terminal" ->' in fallback,
            "兜底表没有用 switch 分支（格式变了要同步改本断言）")


@check("§D7.2 引用的语言键没人拼错（键格式统一 gui. / item. 前缀）")
def _d7_2(tree: Tree):
    code = tree.code("backend")
    keys = re.findall(r'"((?:gui|item)\.[a-z0-9_.]+)"', code)
    require(keys, "backend 里没有任何语言键字符串")
    bad = [key for key in keys if not re.fullmatch(r"(gui|item)\.[a-z0-9_.]+", key)]
    require(not bad, "语言键格式不对：%r" % (bad,))


def run_pending(tree: Tree) -> "list[str]":
    """Lead 独占 lang：新键没落盘不算失败，但要逐条打印（绝不静默）。"""
    pending = []
    needed = [MODE_SEND_KEY] + ["gui.rs_create_compat.terminal_send.msg." + suffix
                                for suffix in TERMINAL_MSG_SUFFIXES]
    for lang_key, name in (("lang_zh", "zh_cn"), ("lang_en", "en_us")):
        raw = tree.raw_code(lang_key)
        if not raw.strip():
            pending.append("%s.json 读不到（Lead 未落盘？）" % name)
            continue
        try:
            table = json.loads(raw)
        except json.JSONDecodeError as error:
            pending.append("%s.json 不是合法 JSON：%s" % (name, error))
            continue
        for key in needed:
            if key not in table:
                pending.append("%s 缺键 %s" % (name, key))
    return pending


# ======================================================================================
# 运行 / 反例
# ======================================================================================

def load_tree() -> Tree:
    return Tree({key: read_text(path) for key, path in FILES.items()})


def run_checks(tree: Tree) -> "list[tuple[str, bool, str]]":
    results = []
    for name, function in CHECKS:
        try:
            function(tree)
            results.append((name, True, ""))
        except AssertionError as error:
            results.append((name, False, str(error)))
        except Exception as error:  # pragma: no cover - 脚本自身问题也要可见
            results.append((name, False, "脚本内部错误：%r" % (error,)))
    return results


def mutate_replace(tree: Tree, key: str, old: str, new: str) -> None:
    text = tree.raw_code(key)
    require(old in text, "反例锚点在 %s 里找不到：%r（反例失效，必须修脚本）" % (key, old))
    tree.raw[key] = text.replace(old, new, 1)


def mutate_in_method(tree: Tree, key: str, method: str, old: str, new: str) -> None:
    masked = mask_syntax(tree.raw_code(key))
    spans = method_spans(masked, method)
    require(spans, "反例：%s 里找不到方法 %s" % (key, method))
    start, end = max(spans, key=lambda span: span[1] - span[0])
    body = tree.raw_code(key)[start:end]
    require(old in body, "反例锚点不在 %s.%s 里：%r" % (key, method, old))
    tree.raw[key] = tree.raw_code(key)[:start] + body.replace(old, new, 1) + tree.raw_code(key)[end:]


COUNTER_TOTAL = 0
COUNTER_PASS = 0


def counterexamples() -> None:
    global COUNTER_TOTAL, COUNTER_PASS
    base = run_checks(load_tree())
    base_bad = set(name for name, ok, _detail in base if not ok)

    def case(label, mutate, targets):
        global COUNTER_TOTAL, COUNTER_PASS
        COUNTER_TOTAL += 1
        tree = load_tree()
        try:
            mutate(tree)
        except AssertionError as error:
            print("FAIL 反例 %s（构造失败：%s）" % (label, error))
            return
        results = run_checks(tree)
        fresh = [name for name, ok, _detail in results if not ok and name not in base_bad]
        hit = [name for name in fresh if any(name.startswith(prefix) for prefix in targets)]
        if hit:
            COUNTER_PASS += 1
            print("PASS 反例 %s ⇒ 新增判负 %d 项（例：%s）" % (label, len(fresh), hit[0]))
            return
        base_hit = [name for name in base_bad if any(name.startswith(p) for p in targets)]
        if base_hit:
            COUNTER_PASS += 1
            print("PASS 反例 %s（基线该组本就红：%s，天然有区分力）" % (label, base_hit[0]))
            return
        print("FAIL 反例 %s ⇒ 改坏了却没有对应判负（新增失败：%s）" % (label, fresh[:4]))

    # 1. 模式号退回 7：Tab 少一颗 / MODE_KEYS 长度不符
    case("MODE_COUNT 8→7", lambda tree: mutate_replace(tree, "item", "MODE_COUNT = 8", "MODE_COUNT = 7"),
         ["§D1", "§D2.1"])
    # 2. MODE_SEND 改号，与 MODE_COUNT 不再相邻
    case("MODE_SEND 7→6", lambda tree: mutate_replace(tree, "item", "MODE_SEND = 7", "MODE_SEND = 6"),
         ["§D1.1"])
    # 3. 第 8 颗 Tab 图标留空
    case("第 8 颗 Tab 图标改成 ItemStack.EMPTY",
         lambda tree: mutate_replace(tree, "overlay", "case 7 -> new ItemStack(Items.HOPPER);",
                                     "case 7 -> ItemStack.EMPTY;"),
         ["§D2.2"])
    # 4. 语言键数组漏掉第 8 条
    case("MODE_KEYS 漏掉 mode.send",
         lambda tree: mutate_replace(tree, "overlay",
                                     '"gui.rs_create_compat.advanced_remote_terminal.mode.send"',
                                     "null"),
         ["§D2.1"])
    # 5. 订单不写进 CustomData（只剩目标）
    case("save() 不写订单键",
         lambda tree: mutate_in_method(tree, "backend", "save", "tag.put(TAG_ORDERS, encoded);", ""),
         ["§D3.3"])
    # 6. stillValid 改回缓存 ItemStack
    def cache_stack(tree):
        mutate_replace(tree, "backend", "    private final SlotReference slotReference;",
                       "    private final SlotReference slotReference;\n"
                       "    private final ItemStack cachedStack = ItemStack.EMPTY;")
        mutate_in_method(tree, "backend", "stillValid", "return !liveStack().isEmpty();",
                         "return !cachedStack.isEmpty();")
    case("stillValid 缓存 ItemStack", cache_stack, ["§D4"])
    # 7. isConnected 改回「网络判据」（断网时界面就打不开了）
    case("isConnected 改回 network() != null",
         lambda tree: mutate_in_method(tree, "backend", "isConnected",
                                       "return !liveStack().isEmpty();", "return network() != null;"),
         ["§D5"])
    # 8. 绕开 openFor 自己开界面
    case("openSendMode 绕开 ResourcePickerMenu.openFor",
         lambda tree: mutate_in_method(tree, "item", "openSendMode", ".openFor(player, backend, title)",
                                       ".openBypassed(player, backend, title)"),
         ["§D1.4"])
    # 9. 删掉电量闸门
    case("triggerWithPlan 删掉电量闸门",
         lambda tree: mutate_in_method(tree, "backend", "triggerWithPlan",
                                       "if (!TerminalNetworkPower.isPowered(player, stack)) {", "if (false) {"),
         ["§D6.4"])
    # 10. 终端里重写一份取料（第二套投递实现）
    case("终端里自己写 storage.extract",
         lambda tree: mutate_in_method(tree, "backend", "deliver",
                                       "final SenderDelivery.Result result = SenderDelivery.deliver(",
                                       "final long peek = network.getComponent(StorageNetworkComponent.class)"
                                       ".extract(source.get(0).resource(), 1L, Action.SIMULATE, Actor.EMPTY);\n"
                                       "        final SenderDelivery.Result result = SenderDelivery.deliver("),
         ["§D6.2"])
    # 11. 空树：所有判据必须判负（证明「文件消失」不会被当成通过）
    COUNTER_TOTAL += 1
    empty = Tree({key: "" for key in FILES})
    results = run_checks(empty)
    empty_bad = [name for name, ok, _detail in results if not ok]
    if len(empty_bad) >= len(CHECKS) - 5:
        COUNTER_PASS += 1
        print("PASS 反例 空树 ⇒ 判负 %d/%d 项" % (len(empty_bad), len(CHECKS)))
    else:
        print("FAIL 反例 空树 ⇒ 只判负 %d/%d 项（有判据在源码缺失时会假绿）"
              % (len(empty_bad), len(CHECKS)))


# ======================================================================================
# main
# ======================================================================================

def main() -> int:
    print("第 131 轮 · D 组契约自检：终端第 8 个「发送」模式（MODE_SEND=7 / MODE_COUNT=8）")
    print("规格：%s §1 Q2 / §3.D" % CONTRACT)
    print("源码根：%s" % JAVA_REL.replace(os.sep, "/"))

    missing = [path for key, path in FILES.items() if not os.path.isfile(path)]
    if missing:
        print("\n[注意] 以下文件不存在（实现未就绪时正常）：")
        for path in missing:
            print("  - %s" % path)

    tree = load_tree()
    results = run_checks(tree)
    for name, ok, detail in results:
        if ok:
            print("PASS " + name)
        else:
            print("FAIL " + name + (("  <- " + detail) if detail else ""))

    bad = [name for name, ok, _detail in results if not ok]
    pending = run_pending(tree)
    print("\nD 组检查项：%d，失败：%d" % (len(results), len(bad)))
    if bad:
        for name in bad:
            print("  FAIL " + name)
    if pending:
        print("\nPENDING_LEAD（lang 由 Lead 独占，不算失败；键名 + 建议文案见完成消息）：")
        for item in pending:
            print("  PENDING " + item)

    counterexamples()

    print("\nD 组检查项：%d，反例：%d/%d，失败：%d，PENDING_LEAD：%d"
          % (len(results), COUNTER_PASS, COUNTER_TOTAL, len(bad), len(pending)))
    if bad or COUNTER_PASS != COUNTER_TOTAL:
        print("FAILED")
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
