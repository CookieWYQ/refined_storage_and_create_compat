#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 124 轮自检：「区域标记」界面文字压网格（AreaMarkScreen 渲染层硬 bug）。

用户实机反馈（`docs/ROUND122_USER_FEEDBACK.md` 第 1-1 条，beta.22 截图）
----------------------------------------------------------------------
「截图里左半『本区域的过滤标记』网格的第 2 行上，压着一行说明文字（与网格重叠）；
下方『标记 0 / 288 条』与『空的区域什么都不吸（安全默认）；标记跟着选定器走。』两行也贴得过近。」
用户明确说这是 **HARD BUG 不是审美问题**。

修复前的三处几何 bug（都是「两个矩形真的叠在一起」，不是主观感受）
-------------------------------------------------------------
1. `SUBTITLE_Y = 22` 与列标签 `GRID_Y - 10 = 24`：两行只差 2px、行高 9 ⇒ **互相压住 7px**；
2. 空状态提示画在 `GRID_Y + GRID_H / 2 - 4 = 66`：格子行 y = 34 / 52 / 70 / 88（槽框 18px），
   `66..75` 正压在第 2 行格子（`52..70`）与第 3 行（`70..88`）之间 ⇒ 就是用户看到的那行字；
3. `INFO_Y = 132` 与 `INFO_Y + HINT_LINE_H = 141`：行距 9 = 刚好顶死（用户「贴得过近」）。

本轮修法（只动 `client/screen/AreaMarkScreen.java` 的**文字区**，网格一格没挪）
----------------------------------------------------------------------------
* 标题与副标题**并成一行**（`TITLE_Y = 8`，标题在左、` · ` 分隔、副标题在右）：
  面板上沿到网格槽框上沿只有 33px，三行 9px 文字 + 两处 ≥2px 间隙最少 31px，再扣面板 4px 边框
  **真的塞不下** ⇒ 按 `docs/TEXT_NO_ELLIPSIS_RULES.md` 的优先级「改排列」而不是缩字号 / 加省略号；
* 列标签下移到 `LABEL_Y = 20`（与标题行行距 12，下沿 29 < 槽框上沿 33）；
* 空状态提示搬出网格 ⇒ 网格下方三行（`INFO_Y = 109` 数量 / `HINT_Y = 121` 状态 / `EMPTY_Y = 133` 空状态说明），
  行距统一 12px（行高 9 + 间隙 3，硬下限 2）；
* tooltip 的命中区跟着「真正画了字的那两行」走（旧写法命中的是早已没人绘制的 110..128 空区）。

第 124 轮收尾补丁（Lead 看 mock 图追加的两条）
--------------------------------------------
* **下半面板不再留死区**：旧 `PANEL_H = 232` 把按钮放在 208..226，最后一行文字底（142）到按钮顶
  之间空着 **66px**，整块「上面挤、下面空」。改为 `BUTTON_Y = 152`（离文字底 10px）、
  `BUTTON_H = 18`、`PANEL_H = 177`（按钮底 170 → 面板底 7px）。判据：**F2**（底留白 6..8）、
  **F3**（文字底→按钮顶 8..12）、**F4**（面板高 = 文字底 + 间隙 + 按钮 + 底留白，总账对得上）；
  反例 C-5 / C-5b / C-6b / C-6c 分别把「旧 232」「截太矮」「按钮搬回 208」「按钮顶死文字」拼回去判负。
* **标题行 24 字最坏宽**（⑦ 组）：区域名上限取 `Area.MAX_NAME_LENGTH`（跨文件解析，= 24）、
  体积上限取 `Config` 的 `areaSelectorMaxVolume`（默认 32768 / 允许顶到 1048576），
  按工程统一的原版字宽表（ASCII 缺省 6px、非 ASCII 一律 9px）算「标题 + 分隔 + 名字 + 体积」
  的实宽 ⇒ 结论：最坏会超出可用宽度但 `drawFitLine` 只**等比缩小**（en 最坏 0.81 倍 ≈ 7.3px，
  仍是 9px 行高内整行可读），**没有任何截断分支** ⇒「· 体积」不会被挤掉。

第 124 轮收尾补丁 ②（Lead 看 mock 图追加：空状态两行在说同一件事）
------------------------------------------------------------------
* 旧 `hint.marks_empty`「还没有标记 ⇒ 这个区域什么都不吸」与旧 `hint.blocks_all`
  「空的区域什么都不吸（安全默认）；标记跟着选定器走。」**前半句完全同义**（都在说「不吸」）：
  几何修好了，两行却啰嗦重复，还是「挤」。`hint.blocks_all` 只保留**不重复的信息**：
  `「标记跟着选定器走（换一个选定器 = 另一套标记）。」`（en：`"Marks travel with the selector
  (another selector = another mark set)."`）。
* 判据 **K1..K4**（⑧ 组，读语言文件）：两个键都存在且非空（换文案不许把键改成孤儿）、
  两行**不得同时**含「什么都不吸」/ "absorbs nothing"、两行去标点后的**最长公共子串**
  ≤ zh 5 字 / en 8 字、去重**不是删信息**（「不吸」仍在状态行、「选定器」仍在空状态行）。
  反例 C-12a / C-12b 把旧 zh / en 文案塞回去 ⇒ 判负；C-12c 删掉「选定器」⇒ K4 判负；
  C-12d 删掉 en 的键 ⇒ K1 判负。

本自检怎么证明「真的不压」
------------------------
① **从真源码解析几何**：正则取常量（含跨文件取 `McGui.SLOT_FRAME_SIZE`）→ 求值；
   再扫出**每一处** `RsccTextFit.drawFitLine(...)` 的 `(x, y, maxWidth)` 与**每一个**格子矩形
   `(px + LEFT_X + col*PITCH - 1, py + GRID_Y + row*PITCH - 1, 18, 18)`（含槽框那 1px 外沿，
   原点公式由源码 `McGui.slotFrame(guiGraphics, x - 1, y - 1)` 反证）；
   文字矩形 = `(x, y, maxWidth, 9)`。**两两求交必须为空**（矩形求交，不是「目测差不多」）。
② 再断言：文字行距 ≥ 11（行高 9 + 间隙 ≥2）、y 严格递增、全部元素在面板内、
   按钮与网格/文字都不相交、空状态说明的 y **不在** `[GRID_Y, GRID_Y + GRID_H]`、
   网格常量一个都没改、无省略号、`drawFitLine` 使用数 ≥ 文字行数、`fill` 只用于悬停高亮。
③ **反例自证**：把三处旧坐标 / 顶死行距 / 面板截断 / 省略号 / fill 画面板 / 按钮进网格
   分别拼回源码副本，断言同一条判据真的判负（证明判据不是摆设）。
④ **像素目视图**：用同一份「解析出来的几何」+ 本机 `msyh.ttc` 按 3 倍画 `build/mock/area_mark_mock_3x.png`
   （PIL），并打印每个文字行 / 每个格子行的 y 区间与两两求交结果（全空）。

纪律：不跑 gradlew / javac（Lead 统一编译）、不 git、不改 `src/**` 里除 `AreaMarkScreen.java` 以外的文件、
       不改 docs / CHANGELOG / README / gradle.properties；语言文件只改
       `gui.rs_create_compat.area_mark.hint.blocks_all` 这一条（Lead 批准的去重补丁，见上）。
用法：python tools/selfcheck_round124_area_mark_layout.py
退出码：0 = 全部通过；最后一行：ALL_PASS
"""

from __future__ import annotations

import io
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
JAVA = os.path.join(ROOT, JAVA_REL)

MARK_SCREEN = os.path.join(JAVA, "client", "screen", "AreaMarkScreen.java")
MCGUI = os.path.join(JAVA, "client", "widget", "McGui.java")
TEXT_FIT = os.path.join(JAVA, "client", "widget", "RsccTextFit.java")
AREA = os.path.join(JAVA, "support", "Area.java")
CONFIG = os.path.join(JAVA, "Config.java")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
MOCK_DIR = os.path.join(ROOT, "build", "mock")
MOCK_PNG = os.path.join(MOCK_DIR, "area_mark_mock_3x.png")
MOCK_BOX_PNG = os.path.join(MOCK_DIR, "area_mark_mock_3x_boxes.png")
MOCK_TITLE_WORST_PNG = os.path.join(MOCK_DIR, "area_mark_title_worst_3x.png")

#: 行高（原版字体 9px，`RsccTextFit` 也按它排版）；从 `RsccTextFit` 的 javadoc / 常量反推不划算，
#: 这里直接按原版字体的行高钉死，并在断言里要求源码的 `TEXT_LINE_H` 与它一致。
FONT_LINE_H = 9
#: 文字行之间允许的最小垂直间隙（用户口径：不许「贴死」）。
MIN_TEXT_GAP = 2
#: 第 124 轮收尾（死区）：最后一行文字底 → 按钮顶的间隙区间（Lead 裁决 8..12）。
BUTTON_GAP_RANGE = (8, 12)
#: 第 124 轮收尾（死区）：按钮底 → 面板底的留白区间（Lead 裁决 6..8）。
BOTTOM_MARGIN_RANGE = (6, 8)
#: 按钮高（源码 `BUTTON_H`；这里只作为「源码必须能解析出来」的期望值，真值一律从源码取）。
EXPECTED_BUTTON_H = 18
#: 工程统一的原版字宽表（与 round50 / round84 等自检同一套：ASCII 查表、缺省 6px，非 ASCII 一律 9px）。
MC_ADVANCE = {
    " ": 4, "!": 2, "'": 2, ".": 2, ",": 2, ":": 2, ";": 2, "i": 2, "|": 2,
    "(": 4, ")": 4, "[": 4, "]": 4, "f": 4, "k": 4, "l": 4, "t": 4, "I": 4,
    "<": 5, ">": 5, "*": 5, '"': 5, "`": 5, "{": 5, "}": 5, "~": 5,
    "\\": 6, "@": 7,
}
#: 最坏情况的「全角名」样例字：用「铁」（字形易辨认）；别用「区」——它本身长得像
#: 方框加十字，画出来会被误认成豆腐块。
WIDE_NAME_CHAR = "铁"

#: `RsccTextFit.MIN_SCALE` 的期望值（低于它只能改走 tooltip，属于「真的放不下」）。
MIN_SCALE = 0.6

#: 「什么都不吸」这个概念只允许出现在空状态可见两行中的**一行**（zh / en 各一套说法）。
#: 背景：旧 `hint.marks_empty` 与旧 `hint.blocks_all` 前半句都在说它 ⇒ 两行同义重复。
ABSORB_NOTHING_PHRASES = {
    "zh_cn": ("什么都不吸", "什么也不吸"),
    "en_us": ("absorbs nothing", "absorb nothing"),
}
#: 空状态两行**去掉标点后**允许的最长公共子串长度（超过 = 疑似把同一句话说了两遍）。
#: 中文 5：「什么都不吸」正好 5 个字；旧文案两行共有「区域什么都不吸」(7 字) ⇒ 判负。
#: 英文 8：两行共有的领域词 "marks"(5) 属正常，旧文案共有 "area absorbs nothing"(18) ⇒ 判负。
DUP_LCS_LIMIT = {"zh_cn": 5, "en_us": 8}
#: 去重**不是删信息**：每个信息点必须仍有一行在讲（否则就是把整句删了）。
REQUIRED_INFO = {
    "zh_cn": {"hint.marks_empty": "什么都不吸", "hint.blocks_all": "选定器"},
    "en_us": {"hint.marks_empty": "absorbs nothing", "hint.blocks_all": "selector"},
}
#: 被替换掉的旧文案（反例要把它塞回去，证明判据真的判负）。
OLD_BLOCKS_ALL = {
    "zh_cn": "空的区域什么都不吸（安全默认）；标记跟着选定器走。",
    "en_us": "An empty area absorbs nothing (safe default); marks travel with the selector.",
}
#: 语言键前缀（与 `AreaMarkScreen.LANG` 同源）。
LANG_KEY = "gui.rs_create_compat.area_mark."


def mc_width(text):
    """按工程统一字宽表算一行的像素宽（`Font#width` 的同口径近似）。"""
    return sum(9 if ord(ch) >= 128 else MC_ADVANCE.get(ch, 6) for ch in text)
#: 第 124 轮**不许动**的网格常量（本轮的硬约束：只重排文字区）。
FROZEN_GRID = {
    "COLS": 9,
    "ROWS": 4,
    "PITCH": 18,       # = McGui.SLOT_FRAME_SIZE
    "LEFT_X": 12,
    "RIGHT_X": 178,
    "GRID_Y": 34,
}
#: 修复前的三处坏坐标（必须判负的「旧几何」）。
OLD_BAD_SUBTITLE_Y = 22
OLD_BAD_LABEL_Y = "GRID_Y - 10"
OLD_BAD_EMPTY_IN_GRID = "GRID_Y + GRID_H / 2 - 4"

CHECKS = [0]
COUNTER = [0]
SKIPS = [0]
FAILS = []


# ======================================================================================
# 基础工具（与第 119 / 120 / 122 轮同一套：去注释 / 取方法体 / 切参数）
# ======================================================================================

def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


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


def strip_comments(text):
    """去注释（保留字符串字面量），用于「语句 / 布局存在性」判据。"""
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


def method_body(code, hint):
    """从（已去注释的）源码里取一个方法体：从 hint 起找到第一个 '{' 并配平。"""
    start = code.find(hint)
    if start < 0:
        return None
    brace = code.find("{", start)
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[start:i + 1]
    return None


def split_args(arg_text):
    """按顶层逗号切参数（忽略嵌套括号里的逗号）。"""
    args = []
    depth = 0
    cur = []
    for ch in arg_text:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            args.append("".join(cur).strip())
            cur = []
            continue
        cur.append(ch)
    tail = "".join(cur).strip()
    if tail:
        args.append(tail)
    return args


def paren_call_args(code, needle):
    """扫出 `needle` 的每一次调用 → [(行号, [参数...], 调用原文)]（needle 自带左括号）。"""
    out = []
    start = 0
    while True:
        i = code.find(needle, start)
        if i < 0:
            return out
        j = i + len(needle)
        depth = 1
        k = j
        while k < len(code) and depth > 0:
            if code[k] == "(":
                depth += 1
            elif code[k] == ")":
                depth -= 1
            k += 1
        inner = code[j:k - 1] if depth == 0 else code[j:]
        out.append((code.count("\n", 0, i) + 1, split_args(inner), needle + inner + ")"))
        start = k


# ======================================================================================
# ① 常量求值（含跨文件 McGui.SLOT_FRAME_SIZE）
# ======================================================================================

INT_CONST_RE = re.compile(r"private\s+static\s+final\s+int\s+([A-Z][A-Z0-9_]*)\s*=\s*([^;]+);")
IDENT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_.]*")
SAFE_EXPR_RE = re.compile(r"^[0-9a-fA-FxX+\-*/() .]+$")


def eval_int(expr, table):
    """把 `GRID_Y + GRID_H + 3` 这类表达式求值（只认已解析的常量 + px/py=0 + 字面量）。

    除法用 int() 截断，与 Java 的整数除法一致（`(352-90-70-8)/2 = 92`）。
    * 还剩没解析的标识符 ⇒ 抛 `NameError`（`parse_constants` 会下一轮再试）；
    * 含非算术成分 ⇒ 抛 `ValueError`（真错误，直接记进解析错误）。
    """
    text = re.sub(r"\bpx\b|\bpy\b", "0", expr)

    def repl(match):
        token = match.group(0)
        return str(table[token]) if token in table else token

    text = IDENT_RE.sub(repl, text)
    # 十六进制字面量先抹平，否则 `0xFF333333` 里的 `xFF333333` 会被当成标识符
    scan = re.sub(r"0[xX][0-9a-fA-F]+", "0", text)
    left = sorted(set(IDENT_RE.findall(scan)))
    if left:
        raise NameError("未解析常量/标识符: " + ", ".join(left))
    if not SAFE_EXPR_RE.match(text):
        raise ValueError("表达式含非算术成分: %r -> %r" % (expr, text))
    value = eval(text, {"__builtins__": {}}, {})  # noqa: S307 —— 只喂过白名单字符
    return int(value)


def parse_constants(code, external):
    """解析 `private static final int X = ...;` → {name: value}。

    多轮迭代：声明顺序不要求前向依赖（例如把 `LABEL_Y` 改成 `GRID_Y - 10` 时 `GRID_Y`
    声明在后面）—— 只要最终能全部求值就通过；真的求不出来才记错误（绝不静默跳过）。
    """
    table = dict(external)
    exprs = [(match.group(1), match.group(2).strip())
             for match in INT_CONST_RE.finditer(code)]
    errors = []
    pending = list(exprs)
    progress = True
    while pending and progress:
        progress = False
        deferred = []
        for name, expr in pending:
            try:
                table[name] = eval_int(expr, table)
                progress = True
            except NameError:
                deferred.append((name, expr))      # 依赖还没解析出来 → 下一轮再来
            except Exception as exc:
                errors.append("%s = %s (%s)" % (name, expr, exc))
        pending = deferred
    for name, expr in pending:
        errors.append("%s = %s（依赖的常量始终解析不出来）" % (name, expr))
    return table, errors


def parse_mcgui_slot_size():
    """跨文件取 `McGui.SLOT_FRAME_SIZE`（槽框边长 = 格子步进），保证 PITCH 有真出处。"""
    code = strip_comments(read(MCGUI))
    match = re.search(r"public\s+static\s+final\s+int\s+SLOT_FRAME_SIZE\s*=\s*(\d+)\s*;", code)
    if not match:
        return None
    return int(match.group(1))


# ======================================================================================
# ② 从源码解析几何：文字行 / 格子矩形 / 按钮矩形
# ======================================================================================

CONST_Y_RE = re.compile(r"^0 \+ (.+)$")
LANG_KEY_RE = re.compile(r'"([a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+)"')


def parse_text_lines(code, table):
    """扫出每一处 `RsccTextFit.drawFitLine(...)` → 文字行矩形。

    参数顺序：`(graphics, font, text, x, y, maxWidth, color)`。
    x / y / maxWidth 都按**保守**处理：
    * y 求不出来 ⇒ 记为不可解析（判负，绝不静默跳过）；
    * x / maxWidth 求不出来（用到局部变量，如副标题要接在标题后面）⇒ 回退成
      `LEFT_X .. PANEL_W - LEFT_X`（比真实占地**更宽**；更宽的都不相交，真实位置更不可能相交）。
    """
    lines = []
    errors = []
    for index, (_line, args, text) in enumerate(paren_call_args(code, "RsccTextFit.drawFitLine(")):
        if len(args) != 7:
            errors.append("drawFitLine 参数个数 %d（应为 7）: %s" % (len(args), text))
            continue
        _, _, text_arg, x_arg, y_arg, w_arg, _color = args
        keys = LANG_KEY_RE.findall(text_arg)
        name = keys[0] if keys else "line#%d" % index
        y = None
        y_ok = True
        try:
            y = eval_int(y_arg, table)
        except Exception:
            y_ok = False
            errors.append("y 无法静态求值（%s）: %r" % (name, y_arg))
        try:
            x = eval_int(x_arg, table)
            x_ok = True
        except Exception:
            x, x_ok = table.get("LEFT_X", 12), False
        try:
            w = eval_int(w_arg, table)
            w_ok = True
        except Exception:
            w, w_ok = table.get("PANEL_W", 352) - 2 * table.get("LEFT_X", 12), False
        lines.append({
            "name": name,
            "y": y,
            "y_ok": y_ok,
            "x": x,
            "x_exact": x_ok,
            "w": w,
            "w_exact": w_ok,
            "y_expr": y_arg,
            "x_expr": x_arg,
            "w_expr": w_arg,
            "args": args,
            "call": text,
        })
    return lines, errors


def parse_cells(table):
    """格子矩形（含槽框 1px 外沿）：`(px + base + col*PITCH - 1, py + GRID_Y + row*PITCH - 1, 18, 18)`。"""
    cells = []
    pitch = table["PITCH"]
    for side, base in (("left", table["LEFT_X"]), ("right", table["RIGHT_X"])):
        for col in range(table["COLS"]):
            for row in range(table["ROWS"]):
                cells.append({
                    "side": side,
                    "col": col,
                    "row": row,
                    "rect": (base + col * pitch - 1, table["GRID_Y"] + row * pitch - 1, pitch, pitch),
                })
    return cells


BOUNDS_RE = re.compile(r"\.bounds\(\s*(.+?)\s*,\s*(.+?)\s*,\s*(.+?)\s*,\s*(.+?)\s*\)")


def parse_buttons(code, table):
    """按钮矩形：从 `init()` 里的 `.bounds(px + ..., py + BUTTON_Y, W, H)` 解析。"""
    body = method_body(code, "protected void init()")
    buttons = []
    errors = []
    if body is None:
        return buttons, ["找不到 init() 方法体"]
    for match in BOUNDS_RE.finditer(body):
        exprs = [match.group(i).strip() for i in (1, 2, 3, 4)]
        try:
            x, y, w, h = [eval_int(expr, table) for expr in exprs]
        except Exception as exc:
            errors.append("按钮 bounds 无法求值 %r (%s)" % (exprs, exc))
            continue
        buttons.append({"rect": (x, y, w, h), "exprs": exprs})
    return buttons, errors


def intersect(a, b):
    """两个矩形 (x, y, w, h) 是否真的重叠（边贴边不算）。"""
    ax, ay, aw, ah = a
    bx, by, bw, bh = b
    return not (ax + aw <= bx or bx + bw <= ax or ay + ah <= by or by + bh <= ay)


def analyze(src, mcgui_slot):
    """源码文本（**未去注释**的原文）→ 几何快照。"""
    code = strip_comments(src)
    external = {}
    if mcgui_slot is not None:
        external["McGui.SLOT_FRAME_SIZE"] = mcgui_slot
    table, const_errors = parse_constants(code, external)
    lines, line_errors = parse_text_lines(code, table)
    cells = parse_cells(table) if all(k in table for k in ("PITCH", "COLS", "ROWS",
                                                           "LEFT_X", "RIGHT_X", "GRID_Y")) else []
    buttons, button_errors = parse_buttons(code, table)
    return {
        "code": code,
        "raw": src,
        "constants": table,
        "const_errors": const_errors,
        "lines": lines,
        "cells": cells,
        "buttons": buttons,
        "errors": const_errors + line_errors + button_errors,
    }


# ======================================================================================
# ③ 判据（纯函数：喂源码快照 / 喂改坏的副本，产出逐条结论 —— 反例自证直接复用）
# ======================================================================================

def text_rect(line):
    return (line["x"], line["y"], line["w"], FONT_LINE_H)


def cells_of(d):
    return [cell["rect"] for cell in d["cells"]]


def lines_by_y(d):
    """按 y 去重（标题 / 分隔符 / 副标题是同一行的三段）→ [(y, [names])]。"""
    merged = {}
    for line in d["lines"]:
        if not line["y_ok"]:
            continue
        merged.setdefault(line["y"], []).append(line["name"])
    return sorted(merged.items())


def panel_metrics(d):
    """纵向总账：最后一行文字底 / 按钮上下沿 / 文字→按钮间隙 / 按钮→面板底留白。

    全部由解析出来的几何算，不写死任何期望值（期望值只在 `assertions` 的判据里出现）。
    """
    table = d["constants"]
    ys = [line["y"] for line in d["lines"] if line["y_ok"]]
    buttons = [b["rect"] for b in d["buttons"]]
    last_text_bottom = (max(ys) + FONT_LINE_H) if ys else None
    button_top = min((rect[1] for rect in buttons), default=None)
    button_bottom = max((rect[1] + rect[3] for rect in buttons), default=None)
    button_h = max((rect[3] for rect in buttons), default=None)
    panel_h = table.get("PANEL_H")
    return {
        "panel_h": panel_h,
        "last_text_bottom": last_text_bottom,
        "button_top": button_top,
        "button_bottom": button_bottom,
        "button_h": button_h,
        "gap_below_text": (button_top - last_text_bottom)
        if last_text_bottom is not None and button_top is not None else None,
        "bottom_margin": (panel_h - button_bottom)
        if panel_h is not None and button_bottom is not None else None,
    }


def count_draw_fit(code):
    return len(paren_call_args(code, "RsccTextFit.drawFitLine("))


def _body(code, hint):
    return method_body(code, hint)


def assertions(d):
    """→ [(判据名, 是否通过, 详情)]。反例自证时直接喂 `analyze(改坏的源码)`。"""
    out = []
    table = d["constants"]
    lines = d["lines"]
    cells = cells_of(d)
    buttons = [b["rect"] for b in d["buttons"]]

    # ---------- A. 解析完整性（解析不出来 = 判负，绝不静默跳过） ----------
    out.append(("A1 常量 / 文字行 / 按钮全部可静态解析", not d["errors"], "; ".join(d["errors"][:4])))
    out.append(("A2 至少解析出 5 个文字行（标题 / 标签 / 摘要 / 状态 / 空状态）",
                len(lines) >= 5, "解析到 %d 个 drawFitLine 调用" % len(lines)))
    out.append(("A3 至少解析出 72 个格子矩形（左右各 9×4）",
                len(cells) == 2 * table.get("COLS", 0) * table.get("ROWS", 0) and len(cells) >= 72,
                "解析到 %d 个格子" % len(cells)))
    out.append(("A4 两个按钮都解析出矩形", len(buttons) == 2, "解析到 %d 个按钮" % len(buttons)))
    out.append(("A5 源码的 TEXT_LINE_H 与字体行高 9 一致",
                table.get("TEXT_LINE_H") == FONT_LINE_H,
                "TEXT_LINE_H = %s" % table.get("TEXT_LINE_H")))

    # ---------- B. 本轮硬约束：网格一个都没动 ----------
    moved = {name: (table.get(name), want) for name, want in FROZEN_GRID.items()
             if table.get(name) != want}
    out.append(("B1 网格常量未被改动（GRID_Y=34 / PITCH=18 / LEFT_X=12 / RIGHT_X=178 / 9×4）",
                not moved, "被改动: %s" % moved))

    # ---------- C. 核心：文字矩形 × 格子矩形 求交必须为空 ----------
    hits = []
    for line in lines:
        if not line["y_ok"]:
            continue
        rect = text_rect(line)
        for cell in d["cells"]:
            if intersect(rect, cell["rect"]):
                hits.append("%s(y=%s) × %s格(r%d,c%d) y=%s..%s"
                            % (line["name"], rect[1], cell["side"], cell["row"], cell["col"],
                               cell["rect"][1], cell["rect"][1] + cell["rect"][3] - 1))
    out.append(("C1 任何文字行都不与任何格子矩形相交（矩形求交，边贴边不算）",
                not hits, "; ".join(hits[:6])))

    # ---------- D. 文字行之间：间隙 ≥ 2（行距 ≥ 11） ----------
    rows = lines_by_y(d)
    bad_gap = []
    for (y0, n0), (y1, n1) in zip(rows, rows[1:]):
        if y1 - y0 < FONT_LINE_H + MIN_TEXT_GAP:
            bad_gap.append("%s(y=%d) → %s(y=%d) 行距 %d < %d"
                           % ("/".join(n0), y0, "/".join(n1), y1, y1 - y0,
                              FONT_LINE_H + MIN_TEXT_GAP))
    out.append(("D1 文字行两两不重叠、垂直间隙 ≥ %dpx（行距 ≥ %d）"
                % (MIN_TEXT_GAP, FONT_LINE_H + MIN_TEXT_GAP), not bad_gap, "; ".join(bad_gap[:5])))

    # ---------- E. y 严格递增（标题 → 标签 → 摘要 → 状态 → 空状态） ----------
    wanted = ["title", "line#0", "label.marks", "label.inventory", "info.count", "line#6",
              "line#7", "hint.blocks_all"]
    ys = [line["y"] for line in lines if line["y_ok"]]
    out.append(("E1 所有文字行的 y 非递减（自上而下排版）", ys == sorted(ys), "y = %s" % ys))
    label_y = next((line["y"] for line in lines if line["name"] == "label.marks"), None)
    title_y = next((line["y"] for line in lines if line["name"] == "line#0"), None)
    count_y = next((line["y"] for line in lines if line["name"].startswith("line#")
                    and line["y"] is not None and label_y is not None
                    and line["y"] > label_y), None)
    out.append(("E2 标题行在列标签行之上", title_y is None or label_y is None or title_y < label_y,
                "title=%s label=%s" % (title_y, label_y)))
    out.append(("E3 列标签行在数量行之上", label_y is None or count_y is None or label_y < count_y,
                "label=%s count=%s" % (label_y, count_y)))
    empty_line = next((line for line in lines if line["name"] == "hint.blocks_all"), None)
    out.append(("E4 空状态说明在状态行之下",
                empty_line is None or not empty_line["y_ok"] or empty_line["y"] >= max(ys or [0]),
                "empty=%s" % (empty_line["y"] if empty_line else None)))

    # ---------- F. 全部元素在面板内 ----------
    panel_w = table.get("PANEL_W", 0)
    panel_h = table.get("PANEL_H", 0)
    outside = []
    for line in lines:
        if not line["y_ok"]:
            continue
        rect = text_rect(line)
        if rect[0] < 0 or rect[1] < 0 or rect[0] + rect[2] > panel_w or rect[1] + rect[3] > panel_h:
            outside.append("%s %s" % (line["name"], rect))
    for side, rect in (("cell", cell["rect"]) for cell in d["cells"]):
        if rect[0] < 0 or rect[1] < 0 or rect[0] + rect[2] > panel_w or rect[1] + rect[3] > panel_h:
            outside.append("%s %s" % (side, rect))
    for rect in buttons:
        if rect[0] < 0 or rect[1] < 0 or rect[0] + rect[2] > panel_w or rect[1] + rect[3] > panel_h:
            outside.append("button %s" % (rect,))
    out.append(("F1 所有文字 / 格子 / 按钮都在 %dx%d 面板内" % (panel_w, panel_h),
                not outside, "; ".join(outside[:5])))

    # ---------- F2/F3/F4. 纵向不留死区（第 124 轮收尾：旧面板「上面挤、下面空」） ----------
    m = panel_metrics(d)
    bottom_margin = m["bottom_margin"]
    out.append(("F2 按钮底（%s）→ 面板底留白落在 %d..%dpx（当前 %s）"
                % (m["button_bottom"], BOTTOM_MARGIN_RANGE[0], BOTTOM_MARGIN_RANGE[1], bottom_margin),
                bottom_margin is not None
                and BOTTOM_MARGIN_RANGE[0] <= bottom_margin <= BOTTOM_MARGIN_RANGE[1],
                "底留白 %s px（面板高 %s）" % (bottom_margin, m["panel_h"])))
    gap = m["gap_below_text"]
    out.append(("F3 最后一行文字底（%s）→ 按钮顶（%s）间隙落在 %d..%dpx（当前 %s；旧版是 66px 死区）"
                % (m["last_text_bottom"], m["button_top"],
                   BUTTON_GAP_RANGE[0], BUTTON_GAP_RANGE[1], gap),
                gap is not None and BUTTON_GAP_RANGE[0] <= gap <= BUTTON_GAP_RANGE[1],
                "间隙 %s px" % gap))
    total = (m["panel_h"] - m["last_text_bottom"]
             if m["panel_h"] is not None and m["last_text_bottom"] is not None else None)
    expected_total_range = (BUTTON_GAP_RANGE[0] + EXPECTED_BUTTON_H + BOTTOM_MARGIN_RANGE[0],
                            BUTTON_GAP_RANGE[1] + EXPECTED_BUTTON_H + BOTTOM_MARGIN_RANGE[1])
    out.append(("F4 总账：面板高 − 最后一行文字底 = 间隙+按钮+底留白 ∈ %d..%d（当前 %s）"
                % (expected_total_range[0], expected_total_range[1], total),
                total is not None and expected_total_range[0] <= total <= expected_total_range[1],
                "面板高 %s − 文字底 %s = %s" % (m["panel_h"], m["last_text_bottom"], total)))
    out.append(("F5 源码解析出的 BUTTON_H = %d（按钮高与 `bounds(...)` 同源）" % EXPECTED_BUTTON_H,
                m["button_h"] == EXPECTED_BUTTON_H, "button_h = %s" % m["button_h"]))

    # ---------- G. 按钮不与网格 / 文字相交 ----------
    bad_button = []
    for rect in buttons:
        for cell in d["cells"]:
            if intersect(rect, cell["rect"]):
                bad_button.append("button%s × cell%s" % (rect, cell["rect"]))
        for line in lines:
            if line["y_ok"] and intersect(rect, text_rect(line)):
                bad_button.append("button%s × %s" % (rect, line["name"]))
    out.append(("G1 按钮矩形不与格子 / 文字相交", not bad_button, "; ".join(bad_button[:5])))
    out.append(("G2 两个按钮都在所有文字行之下的独立带里（按钮顶 %s ≥ 文字块底 %s）"
                % (m["button_top"], m["last_text_bottom"]),
                m["button_top"] is not None and m["last_text_bottom"] is not None
                and m["button_top"] >= m["last_text_bottom"],
                "按钮顶 %s / 文字底 %s" % (m["button_top"], m["last_text_bottom"])))

    # ---------- H. 空状态说明必须真的在网格外 ----------
    grid_y = table.get("GRID_Y")
    grid_h = table.get("GRID_H", 0)
    empty_ok = False
    detail = "找不到 hint.blocks_all 的绘制点"
    if empty_line is not None and empty_line["y_ok"]:
        inside = grid_y <= empty_line["y"] <= grid_y + grid_h
        empty_ok = not inside
        detail = "y=%s，网格带 [%s, %s]" % (empty_line["y"], grid_y, grid_y + grid_h)
    out.append(("H1 空状态说明的 y 不在 [GRID_Y, GRID_Y+GRID_H]（已搬出网格）", empty_ok, detail))

    # ---------- I. 源头：网格里不许画任何文字 ----------
    code = d["code"]
    grid_body = _body(code, "private void drawMarkGrid(") or ""
    info_body = _body(code, "private void drawInfoAndNotice(") or ""
    tooltip_body = _body(code, "private void drawTooltips(") or ""
    out.append(("I1 drawMarkGrid 里没有任何界面文字（无 drawFitLine / 无语言键）",
                "drawFitLine(" not in grid_body
                and "Component.translatable" not in grid_body
                and "Component.literal" not in grid_body,
                "drawMarkGrid 里还有界面文字绘制"))
    out.append(("I1b 网格里唯一的 drawString 是槽内数量角标（原版物品装饰，不是排版文字行）",
                re.search(r"drawString\(font, amount, x \+ 17 - font\.width\(amount\), y \+ 9", code)
                is not None and grid_body.count("drawString(") == 1,
                "网格里的 drawString 不是数量角标"))
    out.append(("I2 hint.marks_empty 只在 drawInfoAndNotice 里出现（不在网格方法里）",
                "hint.marks_empty" in info_body and "hint.marks_empty" not in grid_body,
                "info=%s grid=%s" % ("hint.marks_empty" in info_body,
                                     "hint.marks_empty" in grid_body)))
    out.append(("I3 hint.blocks_all 与 hint.marks_empty 都还在（文案一个都没删）",
                "hint.blocks_all" in code and "hint.marks_empty" in code, ""))
    out.append(("I4 旧坐标不复活（GRID_Y - 10 / GRID_Y + GRID_H / 2 - 4）",
                OLD_BAD_LABEL_Y not in code and OLD_BAD_EMPTY_IN_GRID not in code,
                "代码里仍有旧坐标"))
    out.append(("I5 tooltip 命中区跟着 HINT_Y / EMPTY_Y（与绘制同源）",
                "py + HINT_Y" in tooltip_body and "py + EMPTY_Y" in tooltip_body,
                "命中区没跟着搬"))
    out.append(("I6 槽框原点公式是 (x - 1, y - 1)（格子矩形按它算）",
                code.count("McGui.slotFrame(guiGraphics, x - 1, y - 1);") == 2,
                "slotFrame 调用 = %d 处" % code.count("McGui.slotFrame(guiGraphics, x - 1, y - 1);")))

    # ---------- J. 文本硬规则（不省略 / 全部走 drawFitLine / fill 只做悬停） ----------
    no_ellipsis = ("…" not in code) and not re.search(r'"[^"]*\.\.\.[^"]*"', code)
    out.append(("J1 源码字符串里没有省略号（… / ...）", no_ellipsis, "出现了省略号"))
    fit_calls = count_draw_fit(code)
    out.append(("J2 RsccTextFit.drawFitLine 使用数 ≥ 文字行数（%d 处调用 / %d 个逻辑行）"
                % (fit_calls, len(lines_by_y(d))), fit_calls >= len(lines_by_y(d)),
                "drawFitLine = %d" % fit_calls))
    fills = re.findall(r"guiGraphics\.fill\([^;]*\);", code)
    bad_fill = [text for text in fills if "0x40FFFFFF" not in text]
    out.append(("J3 fill 只用于悬停高亮（0x40FFFFFF），不手画面板", not bad_fill,
                "; ".join(bad_fill[:3])))
    out.append(("J4 不新增 GUI 贴图（无 blitSprite / 无 new ResourceLocation）",
                "blitSprite" not in code and "new ResourceLocation" not in code, ""))
    out.append(("J5 文字工具仍是 RsccTextFit（不自己 font.width 截断）",
                "RsccTextFit.drawFitLine(" in code, ""))
    return out


def failures_of(d):
    return [name for name, ok, _detail in assertions(d) if not ok]


# ======================================================================================
# ④ 主流程：真源码
# ======================================================================================

def group_source():
    print("\n=== ① 源码锚点（真源码，未去注释的原文） ===")
    raw = read(MARK_SCREEN)
    slot = parse_mcgui_slot_size()
    check("①-1 跨文件取到 McGui.SLOT_FRAME_SIZE（槽框边长 = 格子步进）",
          slot is not None, "McGui.SLOT_FRAME_SIZE 找不到")
    d = analyze(raw, slot)
    for name, ok, detail in assertions(d):
        check(name, ok, detail)
    print("\n解析出的几何（面板 %sx%s）："
          % (d["constants"].get("PANEL_W"), d["constants"].get("PANEL_H")))
    for line in d["lines"]:
        print("  文字 %-18s y=%s..%s  x=%s..%s  (y_expr=%s)"
              % (line["name"],
                 line["y"], (line["y"] + FONT_LINE_H - 1) if line["y"] is not None else "?",
                 line["x"], line["x"] + line["w"] - 1, line["y_expr"]))
    for y0, names in lines_by_y(d):
        print("  文字行 y=%3d..%3d  %s" % (y0, y0 + FONT_LINE_H - 1, "/".join(names)))
    for row in range(d["constants"].get("ROWS", 0)):
        cell = next(cell for cell in d["cells"] if cell["row"] == row and cell["col"] == 0)
        x, y, w, h = cell["rect"]
        print("  格子行 row=%d  y=%3d..%3d  x=%3d..%3d" % (row, y, y + h - 1, x, x + w - 1))
    print("  按钮 %s" % ([(b["exprs"][0], b["rect"]) for b in d["buttons"]],))
    m = panel_metrics(d)
    print("\n纵向总账（死区检查）：")
    print("  上边框 → 标题顶            %s px" % (d["constants"].get("TITLE_Y")))
    print("  文字块                     %s .. %s" % (d["constants"].get("TITLE_Y"),
                                                    m["last_text_bottom"]))
    print("  最后一行文字底 → 按钮顶     %s px（要求 %d..%d）"
          % (m["gap_below_text"], BUTTON_GAP_RANGE[0], BUTTON_GAP_RANGE[1]))
    print("  按钮                       %s .. %s（高 %s）"
          % (m["button_top"], m["button_bottom"], m["button_h"]))
    print("  按钮底 → 面板底            %s px（要求 %d..%d）"
          % (m["bottom_margin"], BOTTOM_MARGIN_RANGE[0], BOTTOM_MARGIN_RANGE[1]))
    print("  面板高                     %s px" % m["panel_h"])
    return d


# ======================================================================================
# ⑤ 反例自证（源码副本：把坏几何拼回去，断言同一条判据真的判负）
# ======================================================================================

def group_counterexamples(real):
    print("\n=== ⑤ 反例自证（源码副本） ===")
    slot = parse_mcgui_slot_size()
    raw = read(MARK_SCREEN)
    code = strip_comments(raw)
    # 前提：真源码必须先全绿，否则下面的「改坏 ⇒ 判负」证明不了判据有判别力。
    check("⑤-0 前提：真源码的几何判据全绿（反例才有意义）",
          not failures_of(real), "真源码仍有失败: %s" % failures_of(real))

    def failed(broken_raw):
        names = failures_of(analyze(broken_raw, slot))
        return names

    # C-1：把「副标题独占一行 y=22」+「列标签 y=GRID_Y-10=24」的旧几何拼回去 ⇒ D1 / E1 判负
    anchor = ("            px + RIGHT_X, py + LABEL_Y, COLS * PITCH, COLOR_TEXT);\n    }")
    broken = code.replace(
        anchor,
        "            px + RIGHT_X, py + LABEL_Y, COLS * PITCH, COLOR_TEXT);\n"
        "        RsccTextFit.drawFitLine(guiGraphics, font,\n"
        "            Component.translatable(LANG + \"subtitle\", describeArea()),\n"
        "            px + LEFT_X, py + 22, PANEL_W - LEFT_X * 2, COLOR_DIM);\n    }", 1)
    broken = broken.replace("int LABEL_Y = TITLE_Y + TEXT_LINE_H + TEXT_LINE_GAP;",
                            "int LABEL_Y = GRID_Y - 10;", 1)
    names = failed(broken)
    counterexample("C-1 拼回「副标题 y=22 + 列标签 y=GRID_Y-10=24」的旧几何 ⇒ D1（行距 2 < 11）/ E1 判负",
                   broken != code and any(n.startswith(("D1", "E1")) for n in names),
                   "没判负，判据形同虚设")

    # C-2：空状态提示搬回网格内部（y = GRID_Y + GRID_H / 2 - 4 = 66）⇒ C1 / H1 判负
    broken = code.replace("            RsccTextFit.drawFitLine(guiGraphics, font,"
                          " Component.translatable(LANG + \"hint.blocks_all\"),\n"
                          "                px + LEFT_X, py + EMPTY_Y, PANEL_W - LEFT_X * 2, COLOR_DIM);",
                          "            RsccTextFit.drawFitLine(guiGraphics, font,"
                          " Component.translatable(LANG + \"hint.blocks_all\"),\n"
                          "                px + LEFT_X, py + GRID_Y + GRID_H / 2 - 4,"
                          " PANEL_W - LEFT_X * 2, COLOR_DIM);", 1)
    names = failed(broken)
    counterexample("C-2 把空状态说明搬回网格内部（y = GRID_Y + GRID_H/2 - 4 = 66）"
                   "⇒ C1（文字×格子求交）/ H1 / I4 判负",
                   broken != code and any(n.startswith(("C1", "H1", "I4")) for n in names),
                   "没判负，判据形同虚设")

    # C-3：行距缩到「顶死」（HINT_Y = INFO_Y + 9，间隙 0）⇒ D1 判负
    broken = code.replace("int HINT_Y = INFO_Y + TEXT_LINE_H + TEXT_LINE_GAP;",
                          "int HINT_Y = INFO_Y + TEXT_LINE_H;", 1)
    names = failed(broken)
    counterexample("C-3 把状态行改成只差 9px（顶着数量行，= 用户「贴得过近」）⇒ D1 判负",
                   broken != code and any(n.startswith("D1") for n in names),
                   "没判负")

    # C-4：文字行塞进网格带（EMPTY_Y = GRID_Y + 40 = 74）⇒ C1 判负
    broken = code.replace("int EMPTY_Y = HINT_Y + TEXT_LINE_H + TEXT_LINE_GAP;",
                          "int EMPTY_Y = GRID_Y + 40;", 1)
    names = failed(broken)
    counterexample("C-4 把空状态说明塞进网格带（y = GRID_Y + 40 = 74）⇒ C1 判负",
                   broken != code and any(n.startswith("C1") for n in names),
                   "没判负")

    # C-5：面板高度回到旧值 232 ⇒ 按钮下面留 62px 死区 ⇒ F2 / F4 判负
    broken = code.replace("private static final int PANEL_H = 177;",
                          "private static final int PANEL_H = 232;", 1)
    names = failed(broken)
    counterexample("C-5 面板高回到旧值 232（按钮 152..170 下面空 62px = 用户说的「下面空」）"
                   "⇒ F2 / F4 判负",
                   broken != code and any(n.startswith(("F2", "F4")) for n in names),
                   "没判负")

    # C-5b：面板高度截太矮（140）⇒ 按钮（152..170）掉到面板外 ⇒ F1 判负
    broken = code.replace("private static final int PANEL_H = 177;",
                          "private static final int PANEL_H = 140;", 1)
    names = failed(broken)
    counterexample("C-5b 面板高截到 140（按钮 152..170 出界）⇒ F1 判负",
                   broken != code and any(n.startswith("F1") for n in names),
                   "没判负")

    # C-6：按钮上移进网格（BUTTON_Y = 40）⇒ G1 判负
    broken = code.replace("private static final int BUTTON_Y = 152;",
                          "private static final int BUTTON_Y = 40;", 1)
    names = failed(broken)
    counterexample("C-6 按钮上移进网格（BUTTON_Y = 40）⇒ G1（按钮×格子）判负",
                   broken != code and any(n.startswith("G1") for n in names),
                   "没判负")

    # C-6b：按钮搬回旧值 208（文字底 142 → 按钮 208 = 66px 死区）⇒ F3 / F4 判负
    broken = code.replace("private static final int BUTTON_Y = 152;",
                          "private static final int BUTTON_Y = 208;", 1)
    names = failed(broken)
    counterexample("C-6b 按钮搬回旧坐标 208（文字底 142 → 按钮顶 208 = 66px 死区）⇒ F3 / F4 判负",
                   broken != code and any(n.startswith(("F3", "F4")) for n in names),
                   "没判负")

    # C-6c：按钮顶死最后一行文字（BUTTON_Y = 140）⇒ G1 / F3 判负
    broken = code.replace("private static final int BUTTON_Y = 152;",
                          "private static final int BUTTON_Y = 140;", 1)
    names = failed(broken)
    counterexample("C-6c 按钮上移到 140（压住空状态说明 y=133..141）⇒ G1 / F3 判负",
                   broken != code and any(n.startswith(("G1", "F3")) for n in names),
                   "没判负")

    # C-6d：按钮高偷偷改成 24（面板高没跟着长）⇒ 底留白只剩 1px ⇒ F2 判负
    broken = code.replace("private static final int BUTTON_H = 18;",
                          "private static final int BUTTON_H = 24;", 1)
    names = failed(broken)
    counterexample("C-6d 按钮高改成 24 而面板高不动（底留白 7 → 1）⇒ F2 判负",
                   broken != code and any(n.startswith("F2") for n in names),
                   "没判负")

    # C-7：网格被挪动（GRID_Y = 44）⇒ B1 判负（本轮硬约束：网格不许动）
    broken = code.replace("private static final int GRID_Y = 34;",
                          "private static final int GRID_Y = 44;", 1)
    names = failed(broken)
    counterexample("C-7 擅自把网格整体下移（GRID_Y 34 → 44）⇒ B1 判负",
                   broken != code and any(n.startswith("B1") for n in names),
                   "没判负")

    # C-8：网格方法里又画回文字（hint.marks_empty）⇒ I1 / I2 判负
    broken = code.replace("McGui.slotFrame(guiGraphics, x - 1, y - 1);",
                          "McGui.slotFrame(guiGraphics, x - 1, y - 1);\n"
                          "            RsccTextFit.drawFitLine(guiGraphics, font,"
                          " Component.translatable(LANG + \"hint.marks_empty\"),\n"
                          "                px + LEFT_X, py + GRID_Y + GRID_H / 2 - 4,"
                          " COLS * PITCH, COLOR_DIM);", 1)
    names = failed(broken)
    counterexample("C-8 网格方法里又画回文字 ⇒ I1 / I2 判负",
                   broken != code and any(n.startswith(("I1", "I2")) for n in names),
                   "没判负")

    # C-9：省略号复活（"999…"）⇒ J1 判负
    broken = code.replace('"?999+"', '"999…"') if '"?999+"' in code else code
    if broken == code:
        broken = code.replace('"999+"', '"999…"', 1)
    names = failed(broken)
    counterexample("C-9 字符串里塞回省略号（999+ → 999…）⇒ J1 判负",
                   broken != code and any(n.startswith("J1") for n in names),
                   "没判负")

    # C-10：用 fill 手画面板（悬停色改成纯黑）⇒ J3 判负
    broken = code.replace("0x40FFFFFF", "0xFF000000", 1)
    names = failed(broken)
    counterexample("C-10 把悬停高亮的 fill 改成手画面板的实色 ⇒ J3 判负",
                   broken != code and any(n.startswith("J3") for n in names),
                   "没判负")

    # C-11：删掉 TEXT_LINE_GAP（行距退化成行高 = 顶死）⇒ A1 或 D1 判负
    broken = re.sub(r"int LABEL_Y = TITLE_Y \+ TEXT_LINE_H \+ TEXT_LINE_GAP;",
                    "int LABEL_Y = TITLE_Y + TEXT_LINE_H;", code, count=1)
    names = failed(broken)
    counterexample("C-11 删掉行间隙（LABEL_Y = TITLE_Y + 9）⇒ D1 判负",
                   broken != code and any(n.startswith("D1") for n in names),
                   "没判负")


# ======================================================================================
# ⑥ mock 目视图（PIL，3 倍）：用同一份「解析出来的几何」画，人眼复核「不压」
# ======================================================================================

SCALE = 3


def load_lang():
    path = os.path.join(LANG_DIR, "zh_cn.json")
    with io.open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def group_mock(d):
    print("\n=== ⑥ mock 目视图（build/mock/area_mark_mock_3x.png，%d 倍） ===" % SCALE)
    try:
        from PIL import Image, ImageDraw, ImageFont
    except Exception as exc:  # pragma: no cover - 本机已确认有 PIL
        skip("⑥ mock 图", "PIL 不可用: %s" % exc)
        return
    try:
        lang = load_lang()
    except Exception as exc:  # pragma: no cover
        skip("⑥ mock 图", "读不到语言文件: %s" % exc)
        return

    table = d["constants"]
    panel_w = table["PANEL_W"]
    panel_h = table["PANEL_H"]
    left_x = table["LEFT_X"]
    right_x = table["RIGHT_X"]
    grid_y = table["GRID_Y"]
    pitch = table["PITCH"]
    lang_key = "gui.rs_create_compat.area_mark."

    def tr(key):
        return str(lang.get(lang_key + key, key))

    img = Image.new("RGB", (panel_w * SCALE, panel_h * SCALE), (30, 30, 30))
    draw = ImageDraw.Draw(img)

    def px_rect(x, y, w, h, fill):
        draw.rectangle([x * SCALE, y * SCALE, (x + w) * SCALE - 1, (y + h) * SCALE - 1], fill=fill)

    # ---- 面板（原版九宫格观感：1px 黑描边 + 白高光 + 右下暗边 + #C6C6C6 底） ----
    px_rect(0, 0, panel_w, panel_h, (198, 198, 198))
    px_rect(0, 0, panel_w, 1, (0, 0, 0))
    px_rect(0, panel_h - 1, panel_w, 1, (0, 0, 0))
    px_rect(0, 0, 1, panel_h, (0, 0, 0))
    px_rect(panel_w - 1, 0, 1, panel_h, (0, 0, 0))
    px_rect(1, 1, panel_w - 2, 1, (255, 255, 255))
    px_rect(1, 1, 1, panel_h - 2, (255, 255, 255))
    px_rect(1, panel_h - 2, panel_w - 2, 1, (85, 85, 85))
    px_rect(panel_w - 2, 1, 1, panel_h - 2, (85, 85, 85))

    # ---- 槽位（逐像素复刻 McGui.slotFrame：底 #8B8B8B + 上/左 #373737 + 下/右 #FFFFFF） ----
    def slot(x, y):
        # McGui.slotFrame 的 5 条 fill，逐条按 3 倍搬过来
        px_rect(x, y, pitch, pitch, (0x8B, 0x8B, 0x8B))
        px_rect(x, y, pitch - 1, 1, (0x37, 0x37, 0x37))
        px_rect(x, y, 1, pitch - 1, (0x37, 0x37, 0x37))
        px_rect(x + 1, y + pitch - 1, pitch - 1, 1, (0xFF, 0xFF, 0xFF))
        px_rect(x + pitch - 1, y + 1, 1, pitch - 1, (0xFF, 0xFF, 0xFF))

    for col in range(table["COLS"]):
        for row in range(table["ROWS"]):
            slot(left_x + col * pitch - 1, grid_y + row * pitch - 1)
            slot(right_x + col * pitch - 1, grid_y + row * pitch - 1)

    # ---- 右侧背包格放几个占位「物品」（左半是空状态 = 用户截图那种情况） ----
    placeholder = [(0x8B, 0x8B, 0x8B), (0x6E, 0x4B, 0x2A), (0x2E, 0x6B, 0x2E),
                   (0x8B, 0x1A, 0x1A), (0x4B, 0x4B, 0x8B), (0xC8, 0xC8, 0xC8)]
    for index, color in enumerate(placeholder):
        cell = index * 2 % (table["COLS"] * table["ROWS"])
        x = right_x + (cell % table["COLS"]) * pitch
        y = grid_y + (cell // table["COLS"]) * pitch
        px_rect(x, y, 16, 16, color)
        px_rect(x, y, 16, 1, tuple(max(0, c - 40) for c in color))
        px_rect(x, y + 15, 16, 1, tuple(min(255, c + 40) for c in color))

    # ---- 文字：真实字宽测量（与 RsccTextFit.drawFitLine 同口径：放不下就整体缩，不省略） ----
    import math

    main_path = "C:/Windows/Fonts/msyh.ttc"
    #: 中文字体缺字形的符号（本机 msyh / simhei 都把 `⇒` 画成豆腐块）—— 单独用符号字体补。
    #: 游戏里由 MC 自带的 unifont 兜底，正常显示；这里只是让 mock 图能看清原文。
    symbol_fonts = {"\u21d2": "C:/Windows/Fonts/seguisym.ttf"}
    font_cache = {}

    def font_for(path, size):
        key = (path, size)
        if key not in font_cache:
            try:
                font_cache[key] = ImageFont.truetype(path, size)
            except Exception:
                font_cache[key] = ImageFont.truetype(main_path, size)
        return font_cache[key]

    base_font = font_for(main_path, FONT_LINE_H * SCALE)

    def runs(text):
        """按「主字体放不下的符号」切段 → [(片段, 备用字体路径或 None)]。"""
        out = []
        cur = ""
        for ch in text:
            if ch in symbol_fonts:
                if cur:
                    out.append((cur, None))
                    cur = ""
                out.append((ch, symbol_fonts[ch]))
            else:
                cur += ch
        if cur:
            out.append((cur, None))
        return out

    def draw_line(text, x, y, max_w, fill):
        """画一行字；放不下时按 maxWidth/宽度 整体缩小（下限 0.6），绝不截断 / 省略。"""
        width = base_font.getlength(text) / SCALE
        if width <= max_w or width <= 0:
            scale = 1.0
        else:
            scale = max(0.6, max_w / width)
        size = max(1, int(round(FONT_LINE_H * SCALE * scale)))
        main = font_for(main_path, size)
        cursor = x
        for chunk, symbol_path in runs(text):
            font = font_for(symbol_path, size) if symbol_path else main
            draw.text((cursor * SCALE, y * SCALE), chunk, font=font, fill=fill)
            cursor += main.getlength(chunk) / SCALE   # 前进宽度按主字体算（与游戏口径一致）
        return width * scale

    # 标题行：标题（深）+ 分隔点 + 副标题（灰），与源码 drawTitleAndLabels 同一套算法
    title = tr("title")
    subtitle = "刷铁机 · %s" % tr("volume").replace("%s", "27")
    title_y = table["TITLE_Y"]
    label_y = table["LABEL_Y"]
    info_y = table["INFO_Y"]
    hint_y = table["HINT_Y"]
    empty_y = table["EMPTY_Y"]
    line_w = panel_w - left_x * 2
    sep = " · "
    title_w = draw_line(title, left_x, title_y, line_w, (0x33, 0x33, 0x33))
    sep_w = base_font.getlength(sep) / SCALE
    sep_x = left_x + int(math.ceil(title_w))
    draw_line(sep, sep_x, title_y, sep_w, (0x6A, 0x6A, 0x6A))
    draw_line(subtitle, sep_x + int(sep_w), title_y, left_x + line_w - (sep_x + int(sep_w)),
              (0x6A, 0x6A, 0x6A))
    # 列标签
    draw_line(tr("label.marks"), left_x, label_y, table["COLS"] * pitch, (0x40, 0x40, 0x40))
    draw_line(tr("label.inventory"), right_x, label_y, table["COLS"] * pitch, (0x40, 0x40, 0x40))
    # 网格下方三行（空状态：0 条）
    draw_line(tr("info.count").replace("%s", "0", 1).replace("%s", "288"), left_x, info_y, line_w,
              (0x40, 0x40, 0x40))
    draw_line(tr("hint.marks_empty"), left_x, hint_y, line_w, (0x6A, 0x6A, 0x6A))
    draw_line(tr("hint.blocks_all"), left_x, empty_y, line_w, (0x6A, 0x6A, 0x6A))

    # ---- 按钮（原版观感：#C6C6C6 底 + 黑描边 + 黑字） ----
    def button(rect, text):
        x, y, w, h = rect
        px_rect(x, y, w, h, (0x6C, 0x6C, 0x6C))
        px_rect(x + 1, y + 1, w - 2, h - 2, (0xC6, 0xC6, 0xC6))
        px_rect(x + 1, y + 1, w - 2, 1, (0xFF, 0xFF, 0xFF))
        px_rect(x + 1, y + h - 2, w - 2, 1, (0x55, 0x55, 0x55))
        text_w = base_font.getlength(text) / SCALE
        draw_line(text, x + (w - text_w) / 2, y + (h - FONT_LINE_H) / 2, w - 4, (0x40, 0x40, 0x40))

    for rect in [b["rect"] for b in d["buttons"]]:
        button(rect, tr("button.clear") if rect[2] == table["CLEAR_W"] else tr("button.back"))

    os.makedirs(MOCK_DIR, exist_ok=True)
    img.save(MOCK_PNG)
    check("⑥-1 mock 图已生成：%s（%dx%d，%d 倍）"
          % (os.path.relpath(MOCK_PNG, ROOT).replace("\\", "/"), img.width, img.height, SCALE),
          os.path.isfile(MOCK_PNG) and img.width == panel_w * SCALE
          and img.height == panel_h * SCALE, "尺寸不对")

    # ---- 像素证据：文字行 / 格子行 y 区间 + 两两求交（必须全空） ----
    print("\n[像素证据] 文字行 y 区间（行高 %d）：" % FONT_LINE_H)
    text_bands = []
    for y0, names in lines_by_y(d):
        text_bands.append((y0, y0 + FONT_LINE_H - 1, "/".join(names)))
        print("  y %3d..%3d   %s" % (y0, y0 + FONT_LINE_H - 1, "/".join(names)))
    print("[像素证据] 格子行 y 区间（槽框 %dpx，含 1px 外沿）：" % pitch)
    cell_bands = []
    for row in range(table["ROWS"]):
        y0 = grid_y + row * pitch - 1
        cell_bands.append((y0, y0 + pitch - 1))
        print("  y %3d..%3d   row=%d" % (y0, y0 + pitch - 1, row))
    print("[像素证据] 文字行 × 格子行 两两求交：")
    bad = []
    for t0, t1, name in text_bands:
        for c0, c1 in cell_bands:
            overlap = not (t1 < c0 or c1 < t0)
            if overlap:
                bad.append("%s(y %d..%d) × 格子行(y %d..%d)" % (name, t0, t1, c0, c1))
    for t0, t1, name in text_bands:
        hits = ["%d..%d" % (c0, c1) for c0, c1 in cell_bands if not (t1 < c0 or c1 < t0)]
        print("  %-28s vs 格子行 → %s" % (name + " (y %d..%d)" % (t0, t1),
                                          "无交集" if not hits else "交集 " + ", ".join(hits)))
    check("⑥-2 像素证据：所有文字行与所有格子行两两无交集", not bad, "; ".join(bad[:4]))

    # ---- 附：把矩形画出来（红=文字行 / 蓝=格子 / 绿=按钮 / 品红=面板底边）供人工复核 ----
    metrics = panel_metrics(d)
    legend_w = 176 * SCALE
    box = Image.new("RGB", (panel_w * SCALE + legend_w, panel_h * SCALE), (26, 26, 26))
    box.paste(img, (0, 0))
    box_draw = ImageDraw.Draw(box, "RGBA")

    def outline(x, y, w, h, color):
        box_draw.rectangle([x * SCALE, y * SCALE, (x + w) * SCALE - 1, (y + h) * SCALE - 1],
                           outline=color, width=2)

    def band(y0, y1, fill):
        if y1 > y0:
            box_draw.rectangle([0, y0 * SCALE, panel_w * SCALE - 1, y1 * SCALE - 1], fill=fill)

    # 两条「留白/间隙」色带（先画，免得盖住后面的框线）
    band(metrics["last_text_bottom"], metrics["button_top"], (0, 200, 255, 70))   # 青 = 文字→按钮
    band(metrics["button_bottom"], panel_h, (255, 215, 0, 90))                    # 黄 = 按钮→面板底

    for cell in d["cells"]:
        outline(*cell["rect"], (0, 0, 255, 255))
    for line in d["lines"]:
        if line["y_ok"]:
            outline(line["x"], line["y"], line["w"], FONT_LINE_H, (255, 0, 0, 255))
    for rect in [b["rect"] for b in d["buttons"]]:
        outline(*rect, (0, 160, 0, 255))

    # 面板底边线（品红，逻辑 1px = 3 倍下 3px 粗），让「底留白」肉眼可量
    box_draw.rectangle([0, (panel_h - 1) * SCALE, panel_w * SCALE - 1, panel_h * SCALE - 1],
                       fill=(255, 0, 255, 255))

    # 右侧图例（把每段像素数写出来，看图和读数字对得上）
    legend_font = font_for(main_path, 8 * SCALE)
    legend = [
        ("red", "文字行（高 %d）" % FONT_LINE_H),
        ("blue", "格子 18x18（含 1px 外沿）"),
        ("green", "按钮（高 %s）" % metrics["button_h"]),
        ("magenta", "面板底边 y=%d" % (panel_h - 1)),
        ("cyan", "文字底->按钮顶 = %s px" % metrics["gap_below_text"]),
        ("yellow", "按钮底->面板底 = %s px" % metrics["bottom_margin"]),
        ("text", "%d = %d + %s + %s + %s"
         % (panel_h, metrics["last_text_bottom"], metrics["gap_below_text"],
            metrics["button_h"], metrics["bottom_margin"])),
    ]
    legend_colors = {"red": (255, 60, 60), "blue": (90, 90, 255), "green": (0, 190, 0),
                     "magenta": (255, 0, 255), "cyan": (0, 200, 255),
                     "yellow": (255, 215, 0), "text": (230, 230, 230)}
    legend_x = panel_w * SCALE + 8 * SCALE
    for index, (kind, text) in enumerate(legend):
        row_y = (8 + index * 13) * SCALE
        if kind != "text":
            box_draw.rectangle([legend_x, row_y, legend_x + 10 * SCALE - 1, row_y + 7 * SCALE],
                               fill=legend_colors[kind] + (255,))
        box_draw.text((legend_x + (0 if kind == "text" else 15 * SCALE), row_y),
                      text, font=legend_font, fill=legend_colors[kind])
    box.save(MOCK_BOX_PNG)
    check("⑥-3 附加证据图（红=文字行 / 蓝=格子 / 绿=按钮 / 品红=面板底边 / 黄带=底留白）已生成：%s"
          % os.path.relpath(MOCK_BOX_PNG, ROOT).replace("\\", "/"), os.path.isfile(MOCK_BOX_PNG), "")

    # 像素证据：底边线真的是品红、黄带真的只占 bottom_margin - 1 行背景
    # （最后 1px 是面板边框本身，被品红底边线覆盖，所以可见背景 = 留白 px - 1）。
    # 判「黄带」用「暖色」（R 明显大于 B）：面板底色是中性灰（R == B），只有叠了黄色才会偏暖。
    probe = box.convert("RGB")
    edge = probe.getpixel((5, panel_h * SCALE - 1))
    band_rows = 0
    for row in range(metrics["button_bottom"], panel_h - 1):
        r, g, b = probe.getpixel((200 * SCALE, row * SCALE))
        if r - b > 40:
            band_rows += 1
    check("⑥-4 像素证据：面板底边线是品红 %s、按钮下背景带实测 %d 行（留白 %s px = %d 背景 + 1px 底边）"
          % (edge, band_rows, metrics["bottom_margin"], metrics["bottom_margin"] - 1),
          edge == (255, 0, 255) and band_rows == metrics["bottom_margin"] - 1
          and BOTTOM_MARGIN_RANGE[0] <= metrics["bottom_margin"] <= BOTTOM_MARGIN_RANGE[1],
          "edge=%s band_rows=%s bottom_margin=%s"
          % (edge, band_rows, metrics["bottom_margin"]))
    print("[目视] 死区已消除：文字底 %s → 按钮顶 %s 只有 %s px（旧版 66px）；"
          "按钮底 %s → 面板底 %s = %s px"
          % (metrics["last_text_bottom"], metrics["button_top"], metrics["gap_below_text"],
             metrics["button_bottom"], panel_h, metrics["bottom_margin"]))
    print("[目视] 主图 %s（给用户看）；叠加图 %s（红/蓝框证明不压）"
          % (os.path.relpath(MOCK_PNG, ROOT).replace("\\", "/"),
             os.path.relpath(MOCK_BOX_PNG, ROOT).replace("\\", "/")))


# ======================================================================================
# ⑦ 标题行「24 字最坏宽」：最坏多宽 / 缩多少 / 会不会把「· 体积」整段挤掉
# ======================================================================================

def parse_area_max_name_length():
    """跨文件取 `Area.MAX_NAME_LENGTH`（名字上限，不写死 24）。"""
    code = strip_comments(read(AREA))
    match = re.search(r"public\s+static\s+final\s+int\s+MAX_NAME_LENGTH\s*=\s*(\d+)\s*;", code)
    return int(match.group(1)) if match else None


def parse_volume_bounds():
    """`defineInRange("areaSelectorMaxVolume", 默认, 最小, 最大)` → (默认, 最大)。"""
    code = strip_comments(read(CONFIG))
    match = re.search(r'"areaSelectorMaxVolume"\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)', code)
    if not match:
        return None, None
    return int(match.group(1)), int(match.group(3))


def load_lang_pair():
    out = {}
    for name in ("zh_cn", "en_us"):
        with io.open(os.path.join(LANG_DIR, name + ".json"), "r", encoding="utf-8") as handle:
            out[name] = json.load(handle)
    return out


def title_line_cases(d):
    """标题行最坏宽：每行 = 标题 + " · " + 名字 + " · " + 体积（与 `describeArea` 同构）。"""
    import math
    table = d["constants"]
    left_x, panel_w = table["LEFT_X"], table["PANEL_W"]
    max_name = parse_area_max_name_length() or 24
    default_volume, max_volume = parse_volume_bounds()
    langs = load_lang_pair()
    key = "gui.rs_create_compat.area_mark."
    sep = " · "
    sep_w = mc_width(sep)
    samples = (("24字全角名", WIDE_NAME_CHAR * max_name), ("24字最宽ASCII", "@" * max_name))
    cases = []
    for lang_name in ("zh_cn", "en_us"):
        lang = langs[lang_name]
        title = str(lang.get(key + "title", ""))
        volume_tmpl = str(lang.get(key + "volume", ""))
        title_w = mc_width(title)
        for label, name in samples:
            for volume_value in (default_volume, max_volume):
                volume_text = volume_tmpl.replace("%s", str(volume_value))
                subtitle = name + sep + volume_text
                need = mc_width(subtitle)
                subtitle_x = left_x + int(math.ceil(title_w)) + sep_w
                avail = panel_w - left_x - subtitle_x
                required = (float(avail) / float(need)) if need > avail else 1.0
                cases.append({
                    "lang": lang_name,
                    "name_kind": label,
                    "volume": volume_value,
                    "title_w": title_w,
                    "sep_w": sep_w,
                    "whole": title_w + sep_w + need,
                    "need": need,
                    "avail": avail,
                    "required": required,
                    "glyph": FONT_LINE_H * max(MIN_SCALE, required),
                })
    return cases


def draw_worst_title_png(d, cases):
    """把「zh/en × 默认/上限体积」4 组最坏标题行按真实缩放画出来（3 倍）。"""
    try:
        from PIL import Image, ImageDraw, ImageFont
    except Exception as exc:  # pragma: no cover
        skip("⑦ 最坏宽目视图", "PIL 不可用: %s" % exc)
        return
    table = d["constants"]
    panel_w, left_x = table["PANEL_W"], table["LEFT_X"]
    key = "gui.rs_create_compat.area_mark."
    langs = load_lang_pair()
    main_path = "C:/Windows/Fonts/msyh.ttc"
    max_name = parse_area_max_name_length() or 24
    rows = [case for case in cases if case["name_kind"] == "24字全角名"]
    sep = " · "

    height = 12 + 26 * len(rows)
    img = Image.new("RGB", (panel_w * SCALE, height * SCALE), (198, 198, 198))
    draw = ImageDraw.Draw(img)

    def font(size):
        try:
            return ImageFont.truetype(main_path, max(1, int(size)))
        except Exception:
            return ImageFont.load_default()

    for index, case in enumerate(rows):
        top = 12 + index * 26
        lang = langs[case["lang"]]
        title = str(lang.get(key + "title", ""))
        volume = str(lang.get(key + "volume", "")).replace("%s", str(case["volume"]))
        subtitle = (WIDE_NAME_CHAR * max_name) + sep + volume
        scale = max(MIN_SCALE, case["required"])
        caption = ("%s | 24 full-width name | whole line %dpx (limit %d) | subtitle %d/%dpx"
                   " | scale %.3fx -> glyph %.1fpx"
                   % (case["lang"], case["whole"], panel_w - 2 * left_x, case["need"],
                      case["avail"], scale, case["glyph"]))
        draw.text((6 * SCALE, (top - 10) * SCALE), caption, font=font(6 * SCALE),
                  fill=(70, 70, 70))
        # 行尾界线（红色）：缩放后的字都在它左边 = 没被行尾吃掉任何一段
        draw.rectangle([(panel_w - left_x) * SCALE - 1, top * SCALE,
                        (panel_w - left_x) * SCALE, (top + FONT_LINE_H) * SCALE],
                       fill=(255, 0, 0))
        # 与 Java 同口径：x 用 MC 字宽算，字号用 drawFitLine 的缩放
        title_font = font(FONT_LINE_H * SCALE)
        body_font = font(FONT_LINE_H * SCALE * scale)
        cursor = left_x
        draw.text((cursor * SCALE, top * SCALE), title, font=title_font, fill=(0x33, 0x33, 0x33))
        cursor += case["title_w"]
        draw.text((cursor * SCALE, top * SCALE), sep, font=body_font, fill=(0x6A, 0x6A, 0x6A))
        cursor += case["sep_w"]
        draw.text((cursor * SCALE, top * SCALE), subtitle, font=body_font, fill=(0x6A, 0x6A, 0x6A))

    os.makedirs(MOCK_DIR, exist_ok=True)
    img.save(MOCK_TITLE_WORST_PNG)
    check("⑦-6 最坏宽目视图已生成：%s（%dx%d，红线 = 行尾，字都在左边）"
          % (os.path.relpath(MOCK_TITLE_WORST_PNG, ROOT).replace("\\", "/"),
             img.width, img.height), os.path.isfile(MOCK_TITLE_WORST_PNG), "")


def group_title_worst(d):
    print("\n=== ⑦ 标题行「24 字最坏宽」（名字上限跨文件取自 Area.MAX_NAME_LENGTH） ===")
    max_name = parse_area_max_name_length()
    default_volume, max_volume = parse_volume_bounds()
    check("⑦-1 跨文件取到 Area.MAX_NAME_LENGTH = 24 / Config 体积默认 32768、上限 1048576",
          max_name == 24 and default_volume == 32768 and max_volume == 1048576,
          "name=%s default=%s max=%s" % (max_name, default_volume, max_volume))

    langs = load_lang_pair()
    key = "gui.rs_create_compat.area_mark."
    templates_ok = all(str(langs[name].get(key + "subtitle", "")) == "%s"
                       and "%s" in str(langs[name].get(key + "volume", ""))
                       for name in ("zh_cn", "en_us"))
    check("⑦-2 语言模板：subtitle 就是 \"%s\"、volume 含 \"%s\"（⇒ 副标题实宽 = 名字 + \" · \" + 体积）",
          templates_ok, "")

    cases = title_line_cases(d)
    line_budget = d["constants"]["PANEL_W"] - 2 * d["constants"]["LEFT_X"]
    print("  整行预算 = PANEL_W - LEFT_X*2 = %d px（标题行实际是「标题 + · + 副标题」分段，"
          "副标题可用宽度 = 行尾 - 副标题起点）" % line_budget)
    for case in cases:
        print("  %-5s %-13s 体积 %-7d 整行 %3d px | 副标题需 %3d / 可用 %3d px"
              " ⇒ %s（缩后字高 %.1fpx）"
              % (case["lang"], case["name_kind"], case["volume"], case["whole"],
                 case["need"], case["avail"],
                 ("不缩" if case["required"] >= 1.0 else "缩到 %.3f 倍" % case["required"]),
                 case["glyph"]))

    worst_scale = min([case["required"] for case in cases] + [1.0])
    check("⑦-3 全部 %d 组最坏情况只需缩到 %.3f 倍（≥ MIN_SCALE %.1f ⇒ 永远用不上 tooltip 兜底）"
          % (len(cases), worst_scale, MIN_SCALE), worst_scale >= MIN_SCALE,
          "最坏 %.3f 倍" % worst_scale)

    worst_glyph = min(case["glyph"] for case in cases)
    check("⑦-4 最坏组缩后字高 %.1fpx ≥ 7.0px（仍在 9px 行高内整行完整可读；"
          "「· 体积」不会被挤掉）" % worst_glyph, worst_glyph >= 7.0,
          "最坏字高 %.2f" % worst_glyph)

    # 结构性保证：缩字号不是截断 —— drawFitLine 里没有任何「切字符串 / 补省略号」的分支
    fit_code = strip_comments(read(TEXT_FIT))
    body = method_body(fit_code, "public static float drawFitLine(") or ""
    no_trunc = ("…" not in body and "..." not in body
                and "substring(" not in body and ".split(" not in body
                and "truncat" not in body)
    min_scale_ok = re.search(r"MIN_SCALE\s*=\s*" + re.escape(str(MIN_SCALE)), fit_code) is not None
    check("⑦-5 RsccTextFit.drawFitLine 里没有截断/省略分支（只等比缩放）、MIN_SCALE = 0.6",
          no_trunc and min_scale_ok,
          "no_trunc=%s min_scale=%s" % (no_trunc, min_scale_ok))

    draw_worst_title_png(d, cases)


# ======================================================================================
# ⑧ 空状态两行文案不重复（第 124 轮收尾：几何修好了，两行却又在说同一件事）
# ======================================================================================

def normalize_words(text):
    """去掉标点 / 空白 / 符号，只留中日韩文字与字母数字，并转小写。

    目的：让「去标点后」的比较不受全角括号、`⇒`、空格、句号影响 ——
    「空的区域什么都不吸（安全默认）」与「还没有标记 ⇒ 这个区域什么都不吸」都归一到纯文字。
    """
    return "".join(ch for ch in str(text).lower()
                   if ch.isalnum() or "\u4e00" <= ch <= "\u9fff")


def longest_common_substring(a, b):
    """经典 DP：返回 `(长度, 子串)`（子串连续，不是子序列）。"""
    if not a or not b:
        return 0, ""
    best, best_end = 0, 0
    prev = [0] * (len(b) + 1)
    for i in range(1, len(a) + 1):
        cur = [0] * (len(b) + 1)
        for j in range(1, len(b) + 1):
            if a[i - 1] == b[j - 1]:
                cur[j] = prev[j - 1] + 1
                if cur[j] > best:
                    best, best_end = cur[j], i
        prev = cur
    return best, a[best_end - best:best_end]


def lang_dedup_assertions(langs):
    """K1..K4：空状态可见两行（`hint.marks_empty` / `hint.blocks_all`）不许同义重复。"""

    def line(lang_name, suffix):
        table = langs.get(lang_name) or {}
        return str(table.get(LANG_KEY + suffix, ""))

    pairs = dict((name, (line(name, "hint.marks_empty"), line(name, "hint.blocks_all")))
                 for name in ("zh_cn", "en_us"))
    out = []

    # K1：两个键都还在、都非空 —— 换文案不能把键改成孤儿 / 在语言文件里删掉
    missing = ["%s.%s" % (name, suffix)
               for name in ("zh_cn", "en_us")
               for suffix in ("hint.marks_empty", "hint.blocks_all")
               if not line(name, suffix).strip()]
    out.append(("K1 hint.marks_empty / hint.blocks_all 两个键在 zh_cn & en_us 都存在且非空"
                "（换文案没有制造孤儿键）", not missing, "缺: " + ", ".join(missing)))

    # K2：「什么都不吸」这个概念只许出现在一行
    double = []
    for name, (empty_line, all_line) in pairs.items():
        hits = [phrase for phrase in ABSORB_NOTHING_PHRASES[name]
                if phrase in empty_line and phrase in all_line]
        if hits:
            double.append("%s: 两行都含 %s" % (name, " / ".join(hits)))
    out.append(("K2 空状态两行不得同时说「什么都不吸」/ \"absorbs nothing\"（同义话只说一遍）",
                not double, "; ".join(double)))

    # K3：去标点后的最长公共子串不许长（长 = 同一句话重复）
    lcs_report, lcs_bad = [], []
    for name, (empty_line, all_line) in pairs.items():
        length, sub = longest_common_substring(normalize_words(empty_line),
                                              normalize_words(all_line))
        lcs_report.append("%s %d 字「%s」" % (name, length, sub))
        if length > DUP_LCS_LIMIT[name]:
            lcs_bad.append("%s 公共子串 %d > %d（「%s」）"
                           % (name, length, DUP_LCS_LIMIT[name], sub))
    out.append(("K3 两行去标点后最长公共子串 ≤ zh %d / en %d 字（%s）"
                % (DUP_LCS_LIMIT["zh_cn"], DUP_LCS_LIMIT["en_us"], "; ".join(lcs_report)),
                not lcs_bad, "; ".join(lcs_bad)))

    # K4：去重 ≠ 删信息 ——「不吸」仍归状态行，「选定器」仍归空状态行
    lost = []
    for name, (empty_line, all_line) in pairs.items():
        empty_need = REQUIRED_INFO[name]["hint.marks_empty"]
        all_need = REQUIRED_INFO[name]["hint.blocks_all"]
        if empty_need not in empty_line:
            lost.append("%s 状态行丢了「%s」" % (name, empty_need))
        if all_need not in all_line:
            lost.append("%s 空状态行丢了「%s」" % (name, all_need))
    out.append(("K4 去重不是删信息：「不吸」仍在状态行、「选定器 / selector」仍在空状态行",
                not lost, "; ".join(lost)))
    return out


def group_lang_dedup():
    print("\n=== ⑧ 空状态两行文案不重复（hint.marks_empty vs hint.blocks_all） ===")
    langs = load_lang_pair()
    real = lang_dedup_assertions(langs)
    for name, ok, detail in real:
        check(name, ok, detail)
    real_failures = [name for name, ok, _ in real if not ok]
    check("K0 前提：真语言文件的去重判据全绿（反例才有意义）", not real_failures,
          "仍有失败: %s" % real_failures)
    for name in ("zh_cn", "en_us"):
        empty_line = str(langs[name].get(LANG_KEY + "hint.marks_empty", ""))
        all_line = str(langs[name].get(LANG_KEY + "hint.blocks_all", ""))
        print("  %s 状态行 = %s" % (name, empty_line))
        print("  %s 空状态行 = %s" % (name, all_line))

    def failed_with(lang_name, suffix, text):
        """复制真语言文件，改一条文案，返回该副本里判负的断言名。"""
        broken = dict((name, dict(table)) for name, table in langs.items())
        if text is None:
            broken.get(lang_name, {}).pop(LANG_KEY + suffix, None)
        else:
            broken[lang_name][LANG_KEY + suffix] = text
        return [name for name, ok, _ in lang_dedup_assertions(broken) if not ok], broken

    # C-12a：旧 zh blocks_all 塞回去 ⇒ K2 / K3 判负
    old_zh = OLD_BLOCKS_ALL["zh_cn"]
    current_zh = str(langs["zh_cn"].get(LANG_KEY + "hint.blocks_all", ""))
    names, broken = failed_with("zh_cn", "hint.blocks_all", old_zh)
    counterexample("C-12a 把旧 zh 文案「%s」塞回去 ⇒ K2（两行都说「什么都不吸」）/ K3（公共子串 7 字）判负"
                   % old_zh,
                   current_zh != old_zh and broken != langs
                   and any(n.startswith(("K2", "K3")) for n in names),
                   "没判负，判据形同虚设（判负: %s）" % names)

    # C-12b：旧 en blocks_all 塞回去 ⇒ K2 / K3 判负
    old_en = OLD_BLOCKS_ALL["en_us"]
    current_en = str(langs["en_us"].get(LANG_KEY + "hint.blocks_all", ""))
    names, broken = failed_with("en_us", "hint.blocks_all", old_en)
    counterexample("C-12b 把旧 en 文案「%s」塞回去 ⇒ K2 / K3（公共子串 18 字）判负" % old_en,
                   current_en != old_en and broken != langs
                   and any(n.startswith(("K2", "K3")) for n in names),
                   "没判负，判据形同虚设（判负: %s）" % names)

    # C-12c：把「选定器」信息删掉（去重 ≠ 删信息）⇒ K4 判负
    names, broken = failed_with("zh_cn", "hint.blocks_all", "换一个 = 另一套标记。")
    counterexample("C-12c 把空状态行的「选定器」信息删成「换一个 = 另一套标记。」⇒ K4 判负",
                   broken != langs and any(n.startswith("K4") for n in names),
                   "没判负，判据形同虚设（判负: %s）" % names)

    # C-12d：把 en 的键删掉（换成孤儿 / 漏翻）⇒ K1 判负
    names, broken = failed_with("en_us", "hint.blocks_all", None)
    counterexample("C-12d 从 en_us 里删掉 hint.blocks_all（键变孤儿 / 漏翻）⇒ K1 判负",
                   broken != langs and any(n.startswith("K1") for n in names),
                   "没判负，判据形同虚设（判负: %s）" % names)

    # 顺带把「两行都在源码里被引用」写进 ⑧ 的结论（键不是孤儿）
    code = strip_comments(read(MARK_SCREEN))
    print("  源码引用：%s=%d 处 / %s=%d 处"
          % ("hint.marks_empty", code.count('"hint.marks_empty"'),
             "hint.blocks_all", code.count('"hint.blocks_all"')))


# ======================================================================================
# main
# ======================================================================================

def main():
    print("第 124 轮自检：「区域标记」界面文字压网格（AreaMarkScreen 渲染层硬 bug）")
    print("源码：%s" % os.path.relpath(MARK_SCREEN, ROOT).replace("\\", "/"))
    real = group_source()
    group_counterexamples(real)
    group_mock(real)
    group_title_worst(real)
    group_lang_dedup()
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
