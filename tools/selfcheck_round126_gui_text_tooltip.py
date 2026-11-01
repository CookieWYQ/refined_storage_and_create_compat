#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 126 轮契约自检：GUI「自绘文本」必须有 hover tooltip 兜底（或登记豁免）。

**用户原话（第 126 轮）**
    「GUI 界面中带长文本的话，如果不手动进行渲染，那么它的 tooltip 是没有的。
      这一个也添加到自检脚本之中。比如我们现在这个发电机界面里就是没有的。」

**为什么需要这条自检**
第 104 轮起界面文本禁止省略号，放不下就缩字号（`client/widget/RsccTextFit.java`，
下限 0.6 倍）。但缩字号是**单向**的：玩家在屏幕上只看到变小的一行，看不到全文。
Minecraft 的 tooltip 也不会自动出现 —— 它必须由界面自己 `guiGraphics.renderTooltip(...)`
画出来（原版 `AbstractContainerScreen#renderTooltip` 只负责**槽位里物品**的提示）。
于是「缩了字号但没补 tooltip」= 文本仍然不完整，正是用户说的缺口。

**规则（`docs/ROUND126_GUI_TEXT_TOOLTIP.md` §2 是权威原文，本脚本逐条实现）**

自绘文本点（site）= 扫描集合里的一处文本绘制调用：
  * `RsccTextFit.drawFitLine / drawFitBlock / drawFitLines`（会被缩字号的文本），或
  * `xxx.drawString(...)`（直接画的文本）。

每一处必须满足**其一**：
  (a) **tooltip 覆盖**：同一文件里存在 `renderTooltip(...)` / `renderComponentTooltip(...)`，
      且其参数引用了同一 `Component` 变量 / 同一 `translatable` key / 同一文本变量 / 同一文本工厂方法；
  (b) **豁免注释**：调用行**或紧邻上一行**有 `// rscc:no-tooltip <理由>`，理由必须以三类之一开头：
      `固定短标签` / `标题` / `纯装饰`（理由写别的 ⇒ 判负）；
  (c) **冻结登记**：`tools/gui_text_tooltip_exemptions.json` 里有一条本次判据下**指纹相同**且
      「剩余配额」还有的条目。冻结是第 126 轮一次性盘点存量（理由 + 文件 + 行号 + 指纹），
      之后**新增**的未覆盖点不在冻结表里 ⇒ FAIL —— 这就是「以后所有 GUI 都自检」。

另有三类**可机械判定**的豁免，脚本自动归类（等价于带理由的豁免注释，见文档 §2.3）：
  * `标题`：文本参数就是 `title` / `this.title` / `getTitle()`（容器标题由 lang 固定，
    `renderLabels` 里画的也是它）；
  * `固定短标签`：`drawString` 的文本是**编译期常量**（字符串字面量、`Component.literal("字面量")`、
    `Component.translatable("字面量 key")` 且**没有运行时参数**）——长度由我们自己控，不会「运行时变长」；
  * `纯装饰`：文本只由装饰字符组成（`▶ ▼ ◀ ✖ → < > · —` 等）。

**扫描集合**（`docs` §3 覆盖矩阵同口径）
  `client/screen/*.java` + `client/widget/*.java` + `client/grid/ResourcePickerScreen.java`。
  不含 `client/tooltip/**`（它们本身就在 tooltip 里画字）、不含 HUD overlay（不是「界面」）、
  不含 `client/screen/AreaStripPanel.java` 之外的非 Screen 类。

**已知边界（诚实说明，别当成 100% 形式化证明）**
  * 判据是**启发式**：只看「同文件里有没有引用同一 key / 同一变量」，看不见「hover 区间是否
    真的覆盖这行字、这段 tooltip 是否在另一个分支里」；
  * 因此它只能挡住「新加一行缩字号文本但完全不管 tooltip」这类回归，不能证明交互正确；
  * `--report` 打印的矩阵是给人复核用的，不是证据。

用法：
  `python tools/selfcheck_round126_gui_text_tooltip.py`           检查（0 = 通过）
  `python tools/selfcheck_round126_gui_text_tooltip.py --report`  打印覆盖矩阵 + 全部点
  `python tools/selfcheck_round126_gui_text_tooltip.py --emit-json` 打印未覆盖点的冻结 JSON 草稿
  `python tools/selfcheck_round126_gui_text_tooltip.py --self-test` 只跑反例自证
退出码：0 = 通过；1 = 有未覆盖点 / 冻结表非法 / 反例未判负。
最后一行固定为 `ALL_PASS`（通过时）。

纪律：本脚本只读 `src/**`（写作用域只到 `tools/`、`docs/`），不跑 gradle/javac，不 git。
"""

from __future__ import annotations

import collections
import hashlib
import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CLIENT = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq",
                      "rs_create_compat", "client")

#: 冻结表（相对 ROOT）。文件不存在时视为空表：此时任何未覆盖点都算「新增」⇒ FAIL。
FREEZE_REL = "tools/gui_text_tooltip_exemptions.json"
FREEZE = os.path.join(ROOT, FREEZE_REL)
FREEZE_SCHEMA = "rscc-gui-text-tooltip-exemptions/1"

#: 文档（失败信息里引用它）。
DOC = "docs/ROUND126_GUI_TEXT_TOOLTIP.md"

#: 允许的豁免注释理由前缀（三类，逐字；理由必须**以**其中一类开头）。
EXEMPT_REASONS = ("固定短标签", "标题", "纯装饰")

#: 扫描目录（相对 CLIENT）与额外文件。
SCAN_DIRS = ("screen", "widget")
SCAN_EXTRA = ("grid/ResourcePickerScreen.java",)

#: 扫描集合里要排除的文件（键 = 相对 CLIENT 的路径，用 `/` 分隔）。
#: `RsccTextFit` 是**工具本身**（它就是所有界面的绘制原语），要求它自己带 tooltip 没有意义。
SKIP_FILES = {"widget/RsccTextFit.java"}

FIT_RE = re.compile(r"\bRsccTextFit\s*\.\s*(drawFitLine|drawFitBlock|drawFitLines)\s*\(")
#: `foo.drawString(` / `foo.guiGraphics.drawString(`；接收者里含 `font` 的不是文本绘制（font 没有该方法）。
DRAW_RE = re.compile(r"\b([\w$]+)\s*\.\s*drawString\s*\(")
TOOLTIP_RE = re.compile(r"(?:\b[\w$]+\s*\.\s*)?(?:rscc\$)?(?:renderTooltip|renderComponentTooltip)\s*\(")
#: 只匹配「方法声明」而不是调用：`void renderTooltip(` / `boolean rscc$renderTooltip(`。
TOOLTIP_DECL_RE = re.compile(r"\b(?:void|boolean)\s+(?:rscc\$)?(?:renderTooltip|renderComponentTooltip)\s*$")

#: 方法声明（行首起，修饰符可省 —— 包私有方法也算方法；`if/for/while` 等由 CONTROL 过滤）。
METHOD_RE = re.compile(
    r"(?m)^[ \t]*(?:(?:public|protected|private|static|final|synchronized|abstract|"
    r"default|native)\s+)*(?:<[^>]+>\s+)?[\w$.<>\[\], ?@]*?\b(\w+)\s*\([^;{}]*\)\s*\{")
CONTROL = {"if", "for", "while", "switch", "catch", "try", "else", "do", "return", "new"}

#: `Component.translatable(...)` / `font.split(...)` 这类工厂方法不算「同一文本」的证据
#: （否则「同文件里有任何 tooltip」都会被误判成覆盖）。
FACTORY_CALLS = {
    "translatable", "literal", "empty", "keybind", "nbt", "score", "selector", "split",
    "of", "asList", "copy", "getString", "getContents", "getString", "append", "formatted",
    "withStyle", "withColor", "copy", "grouped", "valueOf", "format", "formatCount",
    "toString", "round", "max", "min", "abs", "floor", "ceil", "trim", "isBlank",
    "renderTooltip", "renderComponentTooltip", "font", "list", "List", "String",
}

#: 文本表达式里出现这些模式 ⇒ 数值读数（用于人复核，不参与豁免判定）。
NUMERIC_RE = re.compile(r"String\.valueOf\(|Integer\.toString\(|RsccNumberField\.grouped\(|"
                        r"String\.format\(|formatCount\(|grouped\(")
#: 纯装饰字符串（只由这些字符组成 ⇒ 纯装饰）。
DECOR_CHARS = set("▶▼◀◄►✖✕×✓→←↑↓<>·—–-｜|+:/\\ \u25b6\u25bc\u25c0\u25c4\u25ba\u2716\u2715")

#: 结果计数（全局，报告用）。
COUNTER = collections.Counter()
FAILS: list[str] = []
WARNS: list[str] = []


# ======================================================================================
# 基础：去注释（保留行号与字符串字面量）
# ======================================================================================

def strip_comments(src: str) -> str:
    """把 `//` 与 `/* */` 注释替换成等长空白；保留换行（行号不变）与字符串字面量。"""
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            while i < n and src[i] != "\n":
                out.append(" ")
                i += 1
        elif c == "/" and nxt == "*":
            while i < n and not (src[i] == "*" and i + 1 < n and src[i + 1] == "/"):
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            for _ in range(2):
                if i < n:
                    out.append(" ")
                    i += 1
        elif c == '"':
            out.append(c)
            i += 1
            while i < n:
                ch = src[i]
                out.append(ch)
                i += 1
                if ch == "\\" and i < n:
                    out.append(src[i])
                    i += 1
                elif ch == '"':
                    break
        else:
            out.append(c)
            i += 1
    return "".join(out)


def strip_literals(code: str) -> str:
    """把字符串字面量替换成等长空白（括号匹配时避免把 `"("` 当括号）。"""
    out = []
    i = 0
    n = len(code)
    while i < n:
        c = code[i]
        if c == '"':
            out.append(" ")
            i += 1
            while i < n:
                ch = code[i]
                if ch == "\\" and i + 1 < n:
                    out.append("  ")
                    i += 2
                    continue
                out.append(" " if ch != "\n" else "\n")
                i += 1
                if ch == '"':
                    break
        else:
            out.append(c)
            i += 1
    return "".join(out)


def line_of(code: str, offset: int) -> int:
    return code.count("\n", 0, offset) + 1


# ======================================================================================
# 结构：括号配对 / 参数切分 / 方法区间
# ======================================================================================

def matching_paren(code: str, open_idx: int) -> int:
    """返回与 `code[open_idx]` 处 `(` 配对的 `)` 下标；不配平返回 -1。"""
    depth = 0
    n = len(code)
    for i in range(open_idx, n):
        ch = code[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i
    return -1


def split_args(code: str, start: int, end: int) -> list[str]:
    """把 `code[start+1:end]` 按顶层逗号切成参数列表（保留原文，去首尾空白）。"""
    args = []
    depth = 0
    current = []
    i = start + 1
    while i < end:
        ch = code[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            args.append("".join(current))
            current = []
        else:
            current.append(ch)
        i += 1
    tail = "".join(current)
    if tail.strip() or args:
        args.append(tail)
    return [a.strip() for a in args]


def method_ranges(code: str) -> list[tuple[int, int, str]]:
    """返回 [(start, end, name)]（end 为方法体 `}` 的下标）。"""
    ranges = []
    for match in METHOD_RE.finditer(code):
        if match.group(1) in CONTROL:
            continue
        brace = code.find("{", match.start())
        if brace < 0:
            continue
        depth = 0
        end = -1
        for i in range(brace, len(code)):
            if code[i] == "{":
                depth += 1
            elif code[i] == "}":
                depth -= 1
                if depth == 0:
                    end = i
                    break
        if end > 0:
            ranges.append((match.start(), end, match.group(1)))
    return ranges


def enclosing_method(ranges: list[tuple[int, int, str]], offset: int) -> tuple[str, int, int]:
    """最内层方法 (name, start, end)；找不到返回 ("", -1, -1)。"""
    best = ("", -1, -1)
    best_span = None
    for start, end, name in ranges:
        if start <= offset <= end:
            span = end - start
            if best_span is None or span < best_span:
                best_span = span
                best = (name, start, end)
    return best


# ======================================================================================
# 文本表达式分类
# ======================================================================================

KEYWORDS = {
    "new", "return", "if", "else", "null", "true", "false", "this", "super", "final",
    "int", "long", "float", "double", "boolean", "var", "String", "Component",
}


def _first_arg(expr: str) -> str:
    """`Component.translatable(<这里>, x, y)` 的第一个参数原文。"""
    open_idx = expr.find("(")
    if open_idx < 0:
        return ""
    end = matching_paren(expr, open_idx)
    if end < 0:
        return ""
    args = split_args(expr, open_idx, end)
    return args[0] if args else ""


def symbolic_key(expr: str) -> str:
    """把 translatable 的 key 参数转成符号：`"k"` → `"k"`；`LANG + "x.y"` → `LANG+"x.y"`；否则 `@<var>`。"""
    expr = expr.strip()
    while expr.startswith("(") and expr.endswith(")"):
        expr = expr[1:-1].strip()
    if re.fullmatch(r'"[^"]*"', expr):
        return expr
    concat = re.fullmatch(r'([\w$.]+)\s*\+\s*("(?:[^"\\]|\\.)*")', expr)
    if concat:
        return "%s+%s" % (concat.group(1), concat.group(2))
    return "@" + expr


def _idents(expr: str) -> set[str]:
    """变量名（先剥掉字符串字面量，否则 `"gui.a.status"` 会被拆成 gui / a / status 三个「变量」）。"""
    expr = strip_literals(expr)
    out = set()
    for token in re.findall(r"\b([a-zA-Z_$][\w$]*)\b", expr):
        if token in KEYWORDS:
            continue
        # 方法名（后面跟 `(`）单独收进 calls，这里只留变量
        pos = expr.find(token)
        tail = expr[pos + len(token):].lstrip()
        if tail.startswith("("):
            continue
        out.add(token)
    return out


def _calls(expr: str) -> set[str]:
    names = set(re.findall(r"\b([a-zA-Z_$][\w$]*)\s*\(", strip_literals(expr)))
    return {name for name in names if name not in FACTORY_CALLS}


def text_facts(expr: str, consts: dict | None = None) -> dict:
    """文本表达式的分类与可匹配特征。返回 dict(kind, keys, literals, idents, calls, numeric)。

    kind: `title` / `decor` / `fixed` / `dynamic`
    consts: 本文件 `static final String NAME = "…";` 常量表（`Component.literal(NAME)` 按常量值定性）。
    """
    consts = consts or {}
    raw = expr.strip()
    facts = {
        "kind": "dynamic",
        "keys": set(),
        "literals": set(),
        "idents": _idents(raw),
        "calls": _calls(raw),
        "numeric": NUMERIC_RE.search(raw) is not None,
    }

    # ---- 静态常量（`DELETE_GLYPH` / `SUBTITLE_SEP` / `ARROW_PREV` …）：宽度固定，改不了 ----
    if raw in consts:
        text = consts[raw]
        facts["kind"] = "decor" if text and all(ch in DECOR_CHARS for ch in text) else "fixed"
        facts["literals"].add(text)
        return facts

    # ---- 标题（容器标题由 lang 固定，且 `renderLabels` 里画的就是它）----
    if re.fullmatch(r"(?:this\.)?title", raw) or raw == "getTitle()":
        facts["kind"] = "title"
        return facts

    # ---- 纯装饰 / 字面量 ----
    literal = re.fullmatch(r'("(?:[^"\\]|\\.)*")', raw)
    if literal:
        text = literal.group(1)[1:-1]
        if text and all(ch in DECOR_CHARS for ch in text):
            facts["kind"] = "decor"
        else:
            facts["kind"] = "fixed"
            facts["literals"].add(text)
        return facts

    if raw.startswith("Component.translatable(") or raw.startswith(
            "net.minecraft.network.chat.Component.translatable("):
        key = symbolic_key(_first_arg(raw))
        facts["keys"].add(key)
        call_open = raw.find("(")
        call_end = matching_paren(raw, call_open)
        args = split_args(raw, call_open, call_end) if call_end > 0 else []
        facts["kind"] = "fixed" if len(args) == 1 else "dynamic"
        if facts["kind"] == "fixed" and not key.startswith("@"):
            return facts
        facts["kind"] = "dynamic"
        return facts

    if raw.startswith("Component.literal(") or raw.startswith(
            "net.minecraft.network.chat.Component.literal("):
        inner = _first_arg(raw).strip()
        literal_inner = re.fullmatch(r'("(?:[^"\\]|\\.)*")', inner)
        if literal_inner:
            text = literal_inner.group(1)[1:-1]
            if text and all(ch in DECOR_CHARS for ch in text):
                facts["kind"] = "decor"
            else:
                facts["kind"] = "fixed"
                facts["literals"].add(text)
        elif inner in consts:
            text = consts[inner]
            facts["kind"] = "decor" if text and all(ch in DECOR_CHARS for ch in text) else "fixed"
            facts["literals"].add(text)
        return facts

    if raw in ("Component.empty()", "null"):
        facts["kind"] = "decor"
        return facts

    return facts


def string_constants(code: str) -> dict:
    """本文件的 `static final String NAME = "…";` 常量表（用于把 `Component.literal(NAME)` 定性）。"""
    out = {}
    for match in re.finditer(
            r"static\s+final\s+String\s+([A-Za-z_$][\w$]*)\s*=\s*\"((?:[^\"\\]|\\.)*)\"\s*;", code):
        out[match.group(1)] = match.group(2)
    return out


# ======================================================================================
# tooltip 证据（同文件启发式）
# ======================================================================================

#: 工具方法的**参数名**不作为「同一文本」的证据（否则任何 tooltip 都会命中 `line` / `text`）。
GENERIC_PARAMS = {"line", "text", "lines", "component", "message", "tip", "label", "value",
                  "stack", "item", "entry", "row", "key", "args", "mouseX", "mouseY",
                  "guiGraphics", "graphics", "font", "width", "height", "x", "y", "color",
                  "Optional", "List", "String", "Component", "FormattedCharSequence"}


def _sink_flags(code_nolit: str, ranges: list[tuple[int, int, str]]) -> dict:
    """文件里哪些方法最终会把文本画进 tooltip（直接调 renderTooltip，或经本地 helper 转发）。

    工程里 tooltip 普遍包了一层（`renderTip(...)` / `drawArrowTooltip(...)`），
    直接只认 `renderTooltip` 会漏掉 EnergyAuditScreen 这类「已经做对了」的界面。
    """
    sinks = {}
    for _start, _end, name in ranges:
        sinks[name] = False
    changed = True
    while changed:
        changed = False
        for start, end, name in ranges:
            if sinks.get(name):
                continue
            body = code_nolit[start:end + 1]
            hit = False
            for match in TOOLTIP_RE.finditer(body):
                prefix = body[max(0, match.start() - 40):match.start()]
                if not TOOLTIP_DECL_RE.search(prefix):
                    hit = True
                    break
            if not hit:
                for other, is_sink in sinks.items():
                    if is_sink and re.search(r"\b" + re.escape(other) + r"\s*\(", body):
                        hit = True
                        break
            if hit:
                sinks[name] = True
                changed = True
    return sinks


def _local_assignments(code_nolit: str, method_range: tuple[int, int]) -> dict:
    """方法体内 `name = <expr>;` / `Component name = <expr>;` → {name: [expr]}。"""
    start, end = method_range
    if start < 0:
        return {}
    body = code_nolit[start:end + 1]
    out = {}
    for match in re.finditer(
            r"\b([A-Za-z_$][\w$]*)\s*=\s*([^;=][^;]*);", body):
        out.setdefault(match.group(1), []).append(match.group(2).strip())
    return out


def tooltip_evidence(code: str, code_nolit: str,
                     ranges: list[tuple[int, int, str]]) -> dict:
    """收集同文件所有 tooltip 渲染链参数里的 key / 字面量 / 变量 / 方法名。"""
    evidence = {"keys": set(), "literals": set(), "idents": set(), "calls": set(), "count": 0}
    sinks = _sink_flags(code_nolit, ranges)
    calls_to_check = []
    for match in TOOLTIP_RE.finditer(code_nolit):
        prefix = code_nolit[max(0, match.start() - 40):match.start()]
        if TOOLTIP_DECL_RE.search(prefix):
            continue
        calls_to_check.append(match)
    for _start, _end, name in ranges:
        if not sinks.get(name):
            continue
        for match in re.finditer(r"\b" + re.escape(name) + r"\s*\(", code_nolit):
            prefix = code_nolit[max(0, match.start() - 60):match.start()]
            # 跳过方法声明本身
            if re.search(r"(?:void|boolean|int|float|double|String|Component)\s+$", prefix):
                continue
            calls_to_check.append(match)

    for match in calls_to_check:
        open_idx = code_nolit.find("(", match.start())
        if open_idx < 0:
            continue
        end = matching_paren(code_nolit, open_idx)
        if end < 0:
            continue
        args_text = code[open_idx + 1:end]
        evidence["count"] += 1
        for tl in re.finditer(r"Component\s*\.\s*translatable\s*\(", args_text):
            inner_open = args_text.find("(", tl.start())
            inner_end = matching_paren(args_text, inner_open)
            if inner_end < 0:
                continue
            inner_args = split_args(args_text, inner_open, inner_end)
            if inner_args:
                evidence["keys"].add(symbolic_key(inner_args[0]))
        for lit in re.finditer(r'Component\s*\.\s*literal\s*\(\s*("(?:[^"\\]|\\.)*")', args_text):
            evidence["literals"].add(lit.group(1)[1:-1])
        idents = _idents(args_text)
        evidence["idents"] |= idents
        evidence["calls"] |= _calls(args_text)
        # tooltip 参数的变量回溯到**同一方法体内**的赋值（`final Component hint = hintText();`）
        method, m_start, m_end = enclosing_method(ranges, match.start())
        local = _local_assignments(code_nolit, (m_start, m_end))
        for ident in idents:
            if ident in GENERIC_PARAMS:
                continue
            for expr in local.get(ident, []):
                sub = text_facts(expr)
                evidence["keys"] |= sub["keys"]
                evidence["literals"] |= sub["literals"]
                evidence["idents"] |= sub["idents"]
                evidence["calls"] |= sub["calls"]

    # tooltip 渲染方法**自己的方法体**里拼出来的文本也算证据：
    # `renderReadoutLineTooltips` 这类 helper 的正文就是「这行字 = statusLine()」的来源。
    for start, end, name in ranges:
        if not sinks.get(name):
            continue
        body = code[start:end + 1]
        # 方法体里同时有「画字」的（例如 renderLabels 顺手渲染了一个 tooltip）不是 tooltip helper，
        # 它的正文不能当证据 —— 否则「同文件任何 tooltip」都会命中。
        if FIT_RE.search(body) or DRAW_RE.search(body):
            continue
        for tl in re.finditer(r"Component\s*\.\s*translatable\s*\(", body):
            inner_open = body.find("(", tl.start())
            inner_end = matching_paren(body, inner_open)
            if inner_end < 0:
                continue
            inner_args = split_args(body, inner_open, inner_end)
            if inner_args:
                evidence["keys"].add(symbolic_key(inner_args[0]))
        for lit in re.finditer(r'Component\s*\.\s*literal\s*\(\s*("(?:[^"\\]|\\.)*")', body):
            evidence["literals"].add(lit.group(1)[1:-1])
        evidence["calls"] |= _calls(body)
    return evidence


# ======================================================================================
# 站点抽取
# ======================================================================================

def collect_sites(rel: str, raw: str) -> list[dict]:
    """抽取一个文件里的全部自绘文本点（调用级）。"""
    code = strip_comments(raw)
    code_nolit = strip_literals(code)
    ranges = method_ranges(code)
    consts = string_constants(code)
    lines = raw.splitlines()
    sites = []

    def add(kind: str, match, callee: str) -> None:
        open_idx = code.find("(", match.start())
        if open_idx < 0:
            return
        end = matching_paren(code, open_idx)
        if end < 0:
            return
        args = split_args(code, open_idx, end)
        text_expr = ""
        if kind == "fit":
            candidates = [a for a in args
                          if ("Component" in a or re.fullmatch(r"[\w$.()\[\]]+", a))
                          and a not in ("guiGraphics", "graphics", "font", "font()")]
            # 签名固定是 (GuiGraphics, Font, Component, ...)：优先第 3 个参数。
            text_expr = args[2] if len(args) > 2 else ""
            if text_expr in ("guiGraphics", "graphics", "font", "font()"):
                text_expr = candidates[0] if candidates else ""
        else:
            text_expr = args[1] if len(args) > 1 else ""
        if not text_expr:
            return
        facts = text_facts(text_expr, consts)
        method, m_start, m_end = enclosing_method(ranges, match.start())
        line = line_of(code, match.start())
        call_text = re.sub(r"\s+", " ", code[match.start():end + 1]).strip()
        fp = hashlib.sha1(("%s|%s|%s" % (rel, kind, call_text)).encode("utf-8")).hexdigest()[:12]
        # 豁免注释：调用行或紧邻上一行
        comment = exemption_comment(lines, line)
        sites.append({
            "file": rel,
            "kind": kind,
            "callee": callee,
            "line": line,
            "method": method,
            "text": text_expr,
            "call": call_text,
            "fingerprint": fp,
            "facts": facts,
            "comment": comment,
        })

    for match in FIT_RE.finditer(code_nolit):
        add("fit", match, match.group(1))
    for match in DRAW_RE.finditer(code_nolit):
        if "font" in match.group(1).lower():
            continue
        add("raw", match, "drawString")
    sites.sort(key=lambda s: s["line"])
    return sites


def exemption_comment(lines: list[str], line: int) -> dict:
    """`// rscc:no-tooltip <理由>`：调用行或紧邻上一行。返回 dict(found, reason, ok, error)。"""
    found = None
    for idx in (line - 1, line - 2):  # 0 基：调用行 = line-1，上一行 = line-2
        if idx < 0 or idx >= len(lines):
            continue
        match = re.search(r"//\s*rscc:no-tooltip\s*(.*)$", lines[idx])
        if match:
            found = match.group(1).strip()
            break
    if found is None:
        return {"found": False, "reason": "", "ok": False, "error": ""}
    if not found:
        return {"found": True, "reason": "", "ok": False,
                "error": "豁免注释没写理由（必须 `// rscc:no-tooltip 固定短标签：…`）"}
    for prefix in EXEMPT_REASONS:
        if found.startswith(prefix):
            return {"found": True, "reason": found, "ok": True, "error": ""}
    return {"found": True, "reason": found, "ok": False,
            "error": "理由必须以下列三类之一开头：%s（实际 `%s`）"
                     % (" / ".join(EXEMPT_REASONS), found)}


# ======================================================================================
# 覆盖判定
# ======================================================================================

def resolve_assignment(code: str, method_range: tuple[int, int], expr: str) -> list[str]:
    """若文本表达式是单个变量/方法调用，回到方法体里找它的赋值/定义，返回候选表达式原文。"""
    m_start, m_end = method_range
    body = code[m_start:m_end + 1]
    out = []
    name = expr.strip()
    if re.fullmatch(r"[a-zA-Z_$][\w$]*", name):
        for assign in re.finditer(r"(?:^|[;{}\s])" + re.escape(name) + r"\s*=\s*([^;]+);", body):
            out.append(assign.group(1).strip())
        for decl in re.finditer(r"\b(?:final\s+)?(?:Component|String)\s+" + re.escape(name)
                                + r"\s*=\s*([^;]+);", body):
            out.append(decl.group(1).strip())
    elif re.fullmatch(r"[a-zA-Z_$][\w$]*\s*\(\s*\)", name):
        out.append(name)
    return out


def site_covered(site: dict, evidence: dict, code: str,
                 method_range: tuple[int, int]) -> str:
    """返回覆盖方式（"" = 未覆盖）。"""
    kind = site["facts"]["kind"]
    if kind == "decor":
        return "auto:纯装饰"
    if kind == "title":
        return "auto:标题"
    if kind == "fixed" and site["kind"] == "raw":
        return "auto:固定短标签"
    return _covered_by_evidence(site, site["facts"], evidence, code, method_range)


def _covered_by_evidence(site: dict, facts: dict, evidence: dict, code: str,
                         method_range: tuple[int, int], depth: int = 0) -> str:
    if facts["keys"] & evidence["keys"]:
        return "tooltip:key"
    literals = {lit for lit in facts["literals"] if len(lit) >= 2}
    if literals & evidence["literals"]:
        return "tooltip:literal"
    if facts["idents"] & evidence["idents"]:
        return "tooltip:变量"
    if facts["calls"] & evidence["calls"]:
        return "tooltip:方法"
    if depth == 0 and method_range[0] >= 0:
        for resolved in resolve_assignment(code, method_range, site["text"]):
            sub = text_facts(resolved)
            sub["idents"] |= facts["idents"]
            sub["calls"] |= facts["calls"]
            got = _covered_by_evidence(site, sub, evidence, code, method_range, depth + 1)
            if got:
                return got + "(经赋值回溯)"
    return ""


def load_freeze(path: str, errors: list[str]) -> dict:
    """读冻结表 → {(file, fingerprint): entry}；结构非法记 errors。

    表结构（`schema` 见 {@link FREEZE_SCHEMA}）：
        {"schema": "...", "round": 126, "note": "...",
         "groups": [{"file": "screen/X.java", "reason": "为什么这一批暂不补 tooltip",
                     "sites": [{"fingerprint": "12 位 sha1", "line": 508, "text": "…"}]}]}
    同一个 `(file, fingerprint)` 出现多次 ⇒ 配额 count 自动累加。
    """
    if not os.path.isfile(path):
        return {}
    try:
        with io.open(path, "r", encoding="utf-8") as handle:
            data = json.load(handle)
    except Exception as exc:  # pragma: no cover
        errors.append("冻结表无法解析：%s" % exc)
        return {}
    if not isinstance(data, dict) or data.get("schema") != FREEZE_SCHEMA:
        errors.append("冻结表 schema 不是 %s" % FREEZE_SCHEMA)
        return {}
    entries = {}
    groups = data.get("groups")
    if not isinstance(groups, list) or not groups:
        errors.append("冻结表缺 groups（必须是数组）")
        return {}
    for gi, group in enumerate(groups):
        if not isinstance(group, dict):
            errors.append("冻结表第 %d 组不是对象" % (gi + 1))
            continue
        rel = str(group.get("file", ""))
        reason = group.get("reason", "")
        sites = group.get("sites")
        if not rel:
            errors.append("冻结表第 %d 组缺 file" % (gi + 1))
            continue
        if not isinstance(reason, str) or len(reason.strip()) < 8:
            errors.append("冻结表第 %d 组（%s）reason 太短（≥8 字，写清为什么暂时不补 tooltip）"
                          % (gi + 1, rel))
            continue
        if not isinstance(sites, list) or not sites:
            errors.append("冻结表第 %d 组（%s）缺 sites" % (gi + 1, rel))
            continue
        for si, site in enumerate(sites):
            if not isinstance(site, dict):
                errors.append("冻结表第 %d 组第 %d 条不是对象" % (gi + 1, si + 1))
                continue
            fp = str(site.get("fingerprint", ""))
            if not re.fullmatch(r"[0-9a-f]{12}", fp):
                errors.append("冻结表第 %d 组（%s）第 %d 条 fingerprint 不是 12 位 sha1：%r"
                              % (gi + 1, rel, si + 1, fp))
                continue
            key = (rel, fp)
            entry = entries.get(key)
            if entry is None:
                entries[key] = {
                    "file": rel,
                    "fingerprint": fp,
                    "count": 1,
                    "line": site.get("line"),
                    "text": site.get("text", ""),
                    "reason": site.get("reason", reason),
                }
            else:
                entry["count"] += 1
    return entries


# ======================================================================================
# 评估
# ======================================================================================

def evaluate_sources(sources: dict, freeze: dict) -> dict:
    """纯函数：sources = {相对路径: 源码}；freeze = {(file, fp): entry}。"""
    report = {
        "per_file": collections.OrderedDict(),
        "uncovered": [],
        "comment_errors": [],
        "stale": [],
        "sites": [],
    }
    used = collections.Counter()
    for rel in sorted(sources):
        raw = sources[rel]
        code = strip_comments(raw)
        ranges = method_ranges(code)
        evidence = tooltip_evidence(code, strip_literals(code), ranges)
        sites = collect_sites(rel, raw)
        row = collections.Counter()
        row["total"] = len(sites)
        for site in sites:
            site["file"] = rel
            method, m_start, m_end = enclosing_method(ranges,
                                                      _offset_of_site(code, site))
            coverage = site_covered(site, evidence, code, (m_start, m_end))
            if site["comment"]["found"] and not site["comment"]["ok"]:
                report["comment_errors"].append(site)
                coverage = ""
            status = ""
            if coverage:
                status = coverage
            elif site["comment"]["ok"]:
                status = "comment:" + site["comment"]["reason"].split("：")[0]
            else:
                key = (rel, site["fingerprint"])
                entry = freeze.get(key)
                if entry is not None and used[key] < int(entry.get("count", 1)):
                    used[key] += 1
                    status = "frozen"
                else:
                    status = "UNCOVERED"
            site["status"] = status
            if site["kind"] == "fit":
                row["fit"] += 1
            if site["facts"]["kind"] == "dynamic":
                row["dynamic"] += 1
            if status.startswith("tooltip"):
                row["tooltip"] += 1
            elif status.startswith("auto"):
                row["auto"] += 1
            elif status.startswith("comment"):
                row["comment"] += 1
            elif status == "frozen":
                row["frozen"] += 1
            else:
                row["uncovered"] += 1
                report["uncovered"].append(site)
            report["sites"].append(site)
        report["per_file"][rel] = row
    for key, entry in freeze.items():
        if used[key] < int(entry.get("count", 1)):
            report["stale"].append((key, int(entry.get("count", 1)) - used[key]))
    return report


def _offset_of_site(code: str, site: dict) -> int:
    """站点在去注释源码里的字符偏移（用行首 + 调用片段定位）。"""
    target = site["line"]
    line_start = 0
    for _ in range(max(0, target - 1)):
        nxt = code.find("\n", line_start)
        if nxt < 0:
            break
        line_start = nxt + 1
    return line_start


def scanned_sources() -> dict:
    sources = {}
    for rel_dir in SCAN_DIRS:
        base = os.path.join(CLIENT, rel_dir)
        if not os.path.isdir(base):
            continue
        for name in sorted(os.listdir(base)):
            if not name.endswith(".java"):
                continue
            rel = "%s/%s" % (rel_dir, name)
            if rel in SKIP_FILES:
                continue
            sources[rel] = read_text(os.path.join(base, name))
    for rel in SCAN_EXTRA:
        full = os.path.join(CLIENT, rel.replace("/", os.sep))
        if os.path.isfile(full):
            sources[rel] = read_text(full)
    return sources

def read_text(path: str) -> str:
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


# ======================================================================================
# 输出
# ======================================================================================

def print_matrix(report: dict) -> None:
    print("覆盖矩阵（每行 = 一个界面/组件文件；fit = 走缩字号工具的点）")
    print("%-46s %5s %5s %5s %8s %5s %6s %6s %6s"
          % ("文件", "总数", "fit", "动态", "tooltip", "自动", "注释", "冻结", "未覆盖"))
    totals = collections.Counter()
    for rel, row in report["per_file"].items():
        print("%-46s %5d %5d %5d %8d %5d %6d %6d %6d"
              % (rel, row["total"], row["fit"], row["dynamic"], row["tooltip"],
                 row["auto"], row["comment"], row["frozen"], row["uncovered"]))
        totals.update(row)
    print("%-46s %5d %5d %5d %8d %5d %6d %6d %6d"
          % ("合计", totals["total"], totals["fit"], totals["dynamic"], totals["tooltip"],
             totals["auto"], totals["comment"], totals["frozen"], totals["uncovered"]))
    print("")


def print_site(site: dict) -> None:
    print("  %s:%d  [%s/%s]  方法=%s" % (site["file"], site["line"], site["kind"],
                                          site["facts"]["kind"], site["method"] or "?"))
    print("      文本：%s" % (site["text"][:130],))
    print("      调用：%s" % (site["call"][:150],))


# ======================================================================================
# 反例自证（判据本身要能被改坏 ⇒ 至少 3 条）
# ======================================================================================

SYNTH_HEAD = """class X {
    void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
"""

SYNTH_TAIL = """    }
}
"""


def _sites_of(source: str, freeze: dict | None = None) -> dict:
    return evaluate_sources({"synthetic/X.java": source}, freeze or {})


def run_counterexamples() -> int:
    """返回失败条数。"""
    fails = 0

    def expect(name: str, ok: bool, detail: str) -> None:
        nonlocal fails
        COUNTER["counter"] += 1
        if ok:
            print("PASS 反例自证：%s" % name)
        else:
            fails += 1
            print("FAIL 反例自证：%s  <- %s" % (name, detail))

    # C-1 动态文本、无 tooltip、无豁免、未冻结 ⇒ 必须判未覆盖
    src = SYNTH_HEAD + (
        '        RsccTextFit.drawFitLine(guiGraphics, font, statusLine(), 8, 8, 100, 0xFFFFFF);\n'
    ) + SYNTH_TAIL
    rep = _sites_of(src)
    expect("C-1 动态文本无 tooltip ⇒ 未覆盖",
           len(rep["uncovered"]) == 1, "实际未覆盖 %d 处" % len(rep["uncovered"]))

    # C-2 同文件有 tooltip 但 key 不同 ⇒ 不算覆盖
    src = SYNTH_HEAD + (
        '        RsccTextFit.drawFitLine(guiGraphics, font, '
        'Component.translatable("gui.a.status"), 8, 8, 100, 0xFFFFFF);\n'
        '        guiGraphics.renderTooltip(font, '
        'Component.translatable("gui.a.other"), mouseX, mouseY);\n'
    ) + SYNTH_TAIL
    rep = _sites_of(src)
    expect("C-2 tooltip 引用别的 key ⇒ 不算覆盖",
           len(rep["uncovered"]) == 1, "实际未覆盖 %d 处" % len(rep["uncovered"]))

    # C-3 同 key ⇒ 算覆盖
    src = SYNTH_HEAD + (
        '        RsccTextFit.drawFitLine(guiGraphics, font, '
        'Component.translatable("gui.a.status"), 8, 8, 100, 0xFFFFFF);\n'
        '        guiGraphics.renderTooltip(font, '
        'Component.translatable("gui.a.status"), mouseX, mouseY);\n'
    ) + SYNTH_TAIL
    rep = _sites_of(src)
    expect("C-3 同 key tooltip ⇒ 覆盖",
           len(rep["uncovered"]) == 0, "实际未覆盖 %d 处" % len(rep["uncovered"]))

    # C-4 豁免注释理由不在三类内 ⇒ 判负（且算未覆盖）
    src = SYNTH_HEAD + (
        '        // rscc:no-tooltip 因为我觉得好看\n'
        '        RsccTextFit.drawFitLine(guiGraphics, font, statusLine(), 8, 8, 100, 0xFFFFFF);\n'
    ) + SYNTH_TAIL
    rep = _sites_of(src)
    expect("C-4 豁免理由不在三类内 ⇒ 判负",
           len(rep["comment_errors"]) == 1 and len(rep["uncovered"]) == 1,
           "comment_errors=%d uncovered=%d"
           % (len(rep["comment_errors"]), len(rep["uncovered"])))

    # C-5 合法豁免理由 ⇒ 通过
    src = SYNTH_HEAD + (
        '        // rscc:no-tooltip 固定短标签：单字「速」\n'
        '        RsccTextFit.drawFitLine(guiGraphics, font, speedLabel(), 8, 8, 100, 0xFFFFFF);\n'
    ) + SYNTH_TAIL
    rep = _sites_of(src)
    expect("C-5 三类理由之一 ⇒ 豁免成立",
           len(rep["uncovered"]) == 0, "实际未覆盖 %d 处" % len(rep["uncovered"]))

    # C-6 豁免注释写在调用行上两行 ⇒ 不生效
    src = SYNTH_HEAD + (
        '        // rscc:no-tooltip 纯装饰：箭头\n'
        '        int unused = 0;\n'
        '        RsccTextFit.drawFitLine(guiGraphics, font, statusLine(), 8, 8, 100, 0xFFFFFF);\n'
    ) + SYNTH_TAIL
    rep = _sites_of(src)
    expect("C-6 豁免注释隔一行 ⇒ 不生效",
           len(rep["uncovered"]) == 1, "实际未覆盖 %d 处" % len(rep["uncovered"]))

    # C-7 冻结表配额用完 ⇒ 第 2 个同指纹点判未覆盖（新增点必须被挡住）
    src = SYNTH_HEAD + (
        '        RsccTextFit.drawFitLine(guiGraphics, font, statusLine(), 8, 8, 100, 0xFFFFFF);\n'
        '        RsccTextFit.drawFitLine(guiGraphics, font, statusLine(), 8, 8, 100, 0xFFFFFF);\n'
    ) + SYNTH_TAIL
    sites = collect_sites("synthetic/X.java", src)
    fp = sites[0]["fingerprint"]
    same = sum(1 for s in sites if s["fingerprint"] == fp)
    freeze = {("synthetic/X.java", fp): {"count": same - 1, "reason": "测试用冻结条目理由占位"}}
    rep = _sites_of(src, freeze)
    expect("C-7 冻结配额只够 1 处 ⇒ 多出来的同指纹点判未覆盖",
           same >= 2 and len(rep["uncovered"]) == 1,
           "同指纹 %d 处，未覆盖 %d 处" % (same, len(rep["uncovered"])))

    # C-8 冻结条目全部命中 ⇒ 无未覆盖；且不产生 stale
    freeze = {("synthetic/X.java", fp): {"count": same, "reason": "测试用冻结条目理由占位"}}
    rep = _sites_of(src, freeze)
    expect("C-8 冻结配额够 ⇒ 全部登记为冻结、无未覆盖",
           len(rep["uncovered"]) == 0 and not rep["stale"],
           "未覆盖 %d 处 stale %d 条" % (len(rep["uncovered"]), len(rep["stale"])))

    # C-9 源里已经没有该点 ⇒ 冻结条目过期（stale，不判负但要报）
    src = SYNTH_HEAD + """        int nothing = 0;\n    }\n}\n"""
    rep = _sites_of(src, freeze)
    expect("C-9 已消失的冻结条目 ⇒ 报 stale 而不是静默",
           len(rep["stale"]) == 1, "stale 条数 %d" % len(rep["stale"]))

    # C-10 固定短标签（无运行时参数）⇒ 自动豁免；带运行时参数的 translatable ⇒ 不豁免
    src = SYNTH_HEAD + (
        '        guiGraphics.drawString(font, Component.translatable("gui.a.speed"), 8, 8, 0xFFFFFF);\n'
        '    }\n}\n'
    )
    rep = _sites_of(src)
    expect("C-10 固定 key（无运行时参数）⇒ 自动豁免",
           len(rep["uncovered"]) == 0, "未覆盖 %d 处" % len(rep["uncovered"]))
    src = SYNTH_HEAD + (
        '        guiGraphics.drawString(font, '
        'Component.translatable("gui.a.status", count()), 8, 8, 0xFFFFFF);\n'
        '    }\n}\n'
    )
    rep = _sites_of(src)
    expect("C-11 带运行时参数的 translatable ⇒ 不算固定短标签",
           len(rep["uncovered"]) == 1, "未覆盖 %d 处" % len(rep["uncovered"]))

    # C-12 标题自动豁免；变量赋值回溯到同 key 也算覆盖
    src = SYNTH_HEAD + (
        '        guiGraphics.drawString(font, title, 8, 8, 0xFFFFFF);\n'
        '    }\n}\n'
    )
    rep = _sites_of(src)
    expect("C-12 `title` ⇒ 自动豁免（标题）",
           len(rep["uncovered"]) == 0, "未覆盖 %d 处" % len(rep["uncovered"]))
    src = SYNTH_HEAD + (
        '        Component text = Component.translatable("gui.a.status");\n'
        '        RsccTextFit.drawFitLine(guiGraphics, font, text, 8, 8, 100, 0xFFFFFF);\n'
        '        guiGraphics.renderTooltip(font, Component.translatable("gui.a.status"), mouseX, mouseY);\n'
    ) + SYNTH_TAIL
    rep = _sites_of(src)
    expect("C-13 变量赋值回溯到同 key ⇒ 算覆盖",
           len(rep["uncovered"]) == 0, "未覆盖 %d 处" % len(rep["uncovered"]))

    # C-14 无 tooltip 的 drawString 动态文本 ⇒ 未覆盖（drawString 也在规则内）
    src = SYNTH_HEAD + (
        '        guiGraphics.drawString(font, statusLine(), 8, 8, 0xFFFFFF);\n'
        '    }\n}\n'
    )
    rep = _sites_of(src)
    expect("C-14 drawString 画的动态文本也受规则约束",
           len(rep["uncovered"]) == 1, "未覆盖 %d 处" % len(rep["uncovered"]))

    # C-15 豁免注释写在被调用方法的定义处（不挨着调用行）⇒ 不生效
    src = SYNTH_HEAD + (
        '        drawStatus(guiGraphics);\n'
    ) + SYNTH_TAIL
    rep = _sites_of(src)
    expect("C-15 没有调用点就没有点（判定按调用处，不按方法名）",
           len(rep["uncovered"]) == 0 and rep["per_file"]["synthetic/X.java"]["total"] == 0,
           "点总数 %d" % rep["per_file"]["synthetic/X.java"]["total"])

    # C-16 经本地 helper（`renderTip(...)`）转发到 renderTooltip 也算 tooltip 覆盖
    src = SYNTH_HEAD + (
        '        RsccTextFit.drawFitLine(guiGraphics, font, hintText(), 8, 8, 100, 0xFFFFFF);\n'
        '        if (inBox(mouseX, mouseY)) {\n'
        '            renderTip(guiGraphics, hintText(), mouseX, mouseY);\n'
        '        }\n'
        '    }\n'
        '    private void renderTip(GuiGraphics guiGraphics, Component line, int mouseX, int mouseY) {\n'
        '        guiGraphics.renderTooltip(font, List.of(line), mouseX, mouseY);\n'
        '    }\n}\n'
    )
    rep = _sites_of(src)
    expect("C-16 经本地 renderTip(...) 转发的 tooltip 也算覆盖",
           len(rep["uncovered"]) == 0, "未覆盖 %d 处" % len(rep["uncovered"]))

    # C-17 tooltip 参数是局部变量 ⇒ 回溯同方法体内的赋值再比（`final Component hint = hintText();`）
    src = SYNTH_HEAD + (
        '        RsccTextFit.drawFitLine(guiGraphics, font, hintText(), 8, 8, 100, 0xFFFFFF);\n'
        '        final Component hint = hintText();\n'
        '        guiGraphics.renderTooltip(font, List.of(hint), mouseX, mouseY);\n'
        '    }\n'
        '    private Component hintText() {\n'
        '        return Component.translatable("gui.a.hint");\n'
        '    }\n}\n'
    )
    rep = _sites_of(src)
    expect("C-17 tooltip 局部变量回溯到同一工厂方法 ⇒ 覆盖",
           len(rep["uncovered"]) == 0, "未覆盖 %d 处" % len(rep["uncovered"]))

    return fails


# ======================================================================================
# main
# ======================================================================================

def check_repo() -> int:
    freeze_errors: list[str] = []
    freeze = load_freeze(FREEZE, freeze_errors)
    for err in freeze_errors:
        FAILS.append("冻结表：" + err)
        print("FAIL 冻结表：%s" % err)

    sources = scanned_sources()
    COUNTER["files"] = len(sources)
    if not sources:
        print("FAIL 找不到扫描集合（client/screen、client/widget 都不存在？）")
        return 1

    report = evaluate_sources(sources, freeze)
    totals = collections.Counter()
    for row in report["per_file"].values():
        totals.update(row)
    print("扫描文件 %d 个；自绘文本点 %d 处（其中走缩字号工具 %d 处）"
          % (len(sources), totals["total"], totals["fit"]))
    print("覆盖：tooltip %d / 自动豁免 %d / 注释豁免 %d / 冻结 %d / **未覆盖 %d**"
          % (totals["tooltip"], totals["auto"], totals["comment"], totals["frozen"],
             totals["uncovered"]))
    print("")

    for site in report["comment_errors"]:
        FAILS.append("comment:%s:%d" % (site["file"], site["line"]))
        print("FAIL %s:%d 豁免注释非法 —— %s"
              % (site["file"], site["line"], site["comment"]["error"]))
    if report["comment_errors"]:
        print("")

    if report["uncovered"]:
        print("未覆盖（必须补 tooltip / 加豁免注释 / 写进 %s）：" % FREEZE_REL)
        for site in report["uncovered"]:
            print_site(site)
        print("")
        for site in report["uncovered"]:
            FAILS.append("%s:%d" % (site["file"], site["line"]))

    if report["stale"]:
        for (key, missing) in report["stale"]:
            WARNS.append("stale:%s:%s" % key)
            print("WARN 冻结条目已过期（源里没有这个点了，建议删掉该条）：%s fingerprint=%s 缺额=%d"
                  % (key[0], key[1], missing))
        print("")

    if FAILS:
        print("FAIL: %d 个问题（未覆盖 %d 处 + 注释非法 %d 处）；规则见 %s"
              % (len(FAILS), len(report["uncovered"]), len(report["comment_errors"]), DOC))
        return 1

    total = totals["total"]
    print("SELFCHECK OK：%d 个文件 / %d 个自绘文本点，全部有 tooltip 或登记豁免"
          "（tooltip %d，自动豁免 %d，注释豁免 %d，冻结 %d）"
          % (len(sources), total, totals["tooltip"], totals["auto"],
             totals["comment"], totals["frozen"]))
    return 0


def stats(filters: list[str] | None = None) -> int:
    """打印未覆盖点的分布（给 Lead 复核 / 写文档用）。

    带参数时只列这些文件（子串匹配）的全部未覆盖点。
    """
    freeze_errors: list[str] = []
    freeze = load_freeze(FREEZE, freeze_errors)
    report = evaluate_sources(scanned_sources(), freeze)
    uncovered = report["uncovered"]
    if filters:
        uncovered = [s for s in uncovered if any(f in s["file"] for f in filters)]
        print("按文件过滤：%s" % ", ".join(filters))
        for site in uncovered:
            print("  %-46s %4d  %-26s %-34s %s"
                  % (site["file"], site["line"], site["method"], site["text"][:34],
                     site["facts"]["kind"]))
        print("合计 %d 处" % len(uncovered))
        return 0
    print("未覆盖点 %d 处" % len(uncovered))
    print("  其中走缩字号（fit）%d 处；数值读数 %d 处"
          % (sum(1 for s in uncovered if s["kind"] == "fit"),
             sum(1 for s in uncovered if s["facts"]["numeric"])))
    classes = collections.Counter(s["facts"]["kind"] for s in uncovered)
    print("  文本类别：%s" % dict(classes))
    print("  按文件：")
    for rel, num in collections.Counter(s["file"] for s in uncovered).most_common():
        print("    %-46s %3d" % (rel, num))
    print("")
    print("非 fit 且非数值（最需要人工判断有没有真 tooltip）：")
    for site in uncovered:
        if site["kind"] != "fit" and not site["facts"]["numeric"]:
            print("  %-46s %4d  %-26s %-30s %s"
                  % (site["file"], site["line"], site["method"], site["text"][:30],
                     site["facts"]["kind"]))
    return 0


def freeze_list() -> int:
    """打印未覆盖点的冻结条目（`文件<TAB>指纹<TAB>行号<TAB>文本`），用于生成冻结表。"""
    freeze_errors: list[str] = []
    freeze = load_freeze(FREEZE, freeze_errors)
    report = evaluate_sources(scanned_sources(), freeze)
    current = None
    for site in report["uncovered"]:
        if site["file"] != current:
            current = site["file"]
            print("## %s" % current)
        print("%s\t%s\t%d\t%s" % (site["file"], site["fingerprint"], site["line"],
                                  self_or_dash(site["text"])))
    print("## 合计 %d 处" % len(report["uncovered"]))
    return 0


def self_or_dash(text: str) -> str:
    return re.sub(r"[\t\r\n]+", " ", text)[:60]


def emit_json() -> int:
    """按冻结表格式（groups）打印未覆盖点草稿；`reason` 留空由人补。"""
    freeze_errors: list[str] = []
    freeze = load_freeze(FREEZE, freeze_errors)
    report = evaluate_sources(scanned_sources(), freeze)
    groups = collections.OrderedDict()
    for site in report["uncovered"]:
        group = groups.setdefault(site["file"], {
            "file": site["file"],
            "reason": "",
            "sites": [],
        })
        group["sites"].append({
            "fingerprint": site["fingerprint"],
            "line": site["line"],
            "text": site["text"][:80],
        })
    doc = {
        "schema": FREEZE_SCHEMA,
        "round": 126,
        "note": "第 126 轮一次性盘点的存量未覆盖点。冻结 ≠ 认可：新增点不在本表即 FAIL；"
                "修好一处就删掉对应条目（脚本会把残留条目标成 stale WARN）。",
        "groups": list(groups.values()),
    }
    print(json.dumps(doc, ensure_ascii=False, indent=2))
    return 0


def main(argv: list[str]) -> int:
    mode = argv[1] if len(argv) > 1 else ""
    if mode not in ("", "--report", "--emit-json", "--stats", "--self-test", "--freeze-list"):
        print("用法：python tools/selfcheck_round126_gui_text_tooltip.py "
              "[--report|--stats|--emit-json|--freeze-list|--self-test]")
        return 1

    print("=" * 100)
    print("第 126 轮自检：GUI 自绘文本的 hover tooltip 兜底")
    print("规则原文：%s §2" % DOC)
    print("=" * 100)
    print("")

    counter_fails = run_counterexamples()
    if counter_fails:
        print("")
        print("FAIL: 反例自证 %d 条未判负（判据被改坏了，禁止放行）" % counter_fails)
        return 1
    print("")

    if mode == "--self-test":
        print("SELF-TEST OK：%d 条反例全部判负" % COUNTER["counter"])
        return 0
    if mode == "--emit-json":
        return emit_json()
    if mode == "--stats":
        return stats(argv[2:])
    if mode == "--freeze-list":
        return freeze_list()

    if mode == "--report":
        freeze_errors: list[str] = []
        freeze = load_freeze(FREEZE, freeze_errors)
        report = evaluate_sources(scanned_sources(), freeze)
        print_matrix(report)
        if report["uncovered"]:
            print("未覆盖点（%d）：" % len(report["uncovered"]))
            for site in report["uncovered"]:
                print_site(site)
        return 0

    code = check_repo()
    if code == 0:
        print("ALL_PASS")
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv))
