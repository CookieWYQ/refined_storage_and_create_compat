# -*- coding: utf-8 -*-
"""第 122 轮自检：quickMoveStack 交给 moveItemStackTo 的槽位区间不得越界。

## 崩溃现场（用户实机，2026-10-31 21:13 客户端）

    java.lang.IndexOutOfBoundsException: Index 91 out of bounds for length 91
      at net.minecraft.core.NonNullList.get
      at net.minecraft.world.inventory.AbstractContainerMenu.moveItemStackTo(AbstractContainerMenu.java:609)
      at cretae.cookiewyq.rs_create_compat.menu.DecompositionGeneratorMenu.quickMoveStack(...:329)
    Menu: rs_create_compat:decomposition_generator  Slot Count: 91  Slot: 58  Type: QUICK_MOVE

崩溃根因：`DecompositionGeneratorMenu.PLAYER_SLOT_COUNT` 被写成 `36 + 9`（= 45），
而构造器实际只按「主背包 27 + 快捷栏 9」加了 36 格 ⇒ 菜单真实槽数 91
（1 展示 + 54 分页 + 36 玩家），常量却声明到 100。Shift+左键玩家主背包（槽 58）时
`moveItemStackTo(82, 100, ...)` 遍历到下标 91 直接越界。

## 本自检断言什么

A. 分解发电机的**槽位数必须自证**（第 123 轮重做后：恒定 62 槽，<b>没有分页</b>）：
   - 从真源码逐个 `addSlot` 数出构造器实际槽数（含 for 循环重数）；
   - 该数必须 == `TOTAL_SLOT_COUNT` 常量求值 == `PLAYER_SLOT_END` == 62
     == 20 机器（16 输入 + 4 副产物）+ 6 升级 + 36 玩家；
   - 常量链 `MACHINE_SLOT_* / UPGRADE_SLOT_* / PLAYER_MAIN_SLOT_COUNT / PLAYER_HOTBAR_SLOT_COUNT /
     PLAYER_SLOT_COUNT / PLAYER_SLOT_*` 逐项求值，并与
     `support/DecompositionGeneratorSpec`（`INVENTORY_SIZE` / `UPGRADE_SLOTS` / `PLAYER_SLOT_COUNT`）对账；
   - `PLAYER_SLOT_COUNT` 不得再出现 `36 + 9` 这类「把 36 格总数又加快捷栏」的字面量，
     且必须由行列常量推导（崩溃根因就是这里手写了一个 45）；
   - 分页 / 展示槽的残留（`PAGE_*` / `SLOT_COUNT` / `PagedSlot` / `DisplaySlot` / `isLocalIndexUsable`）
     必须为 0 处。
B. `moveItemStackTo` 的每个 (start, end) 区间满足 `0 <= start < end <= slots.size()`；
   并用 `simulate_move()` 逐下标模拟原版循环（end 不含），任何一次 `slots.get(i)` 越界都判负。
   六个区间（机器→玩家 / 升级→玩家 / 玩家→升级 / 玩家→机器 / 主背包→快捷栏 / 快捷栏→主背包）
   的并集必须正好覆盖 `[0, 62)`：每个槽都有去处，且没有区间跨过区界。
C. 语义（不是把上界改小糊过去）：
   - 「机器 → 玩家背包」必须覆盖整个玩家区（玩家主背包 + 快捷栏）；
   - 「玩家 → 机器」必须走**全库存扫描**的 `moveItemStackTo(machineStart, machineEnd, false)`，
     并先判 `isDecomposable`（不可降解物原地不动 + 提示）；
   - 玩家区内部的主背包 ↔ 快捷栏互换两段区间也要正；
   - 每个区间上界都与 `this.slots.size()` 取 `Math.min` 兜底。
D. 反例（必须判负，证明自检不是恒真）：
   D1 把 `PLAYER_SLOT_COUNT` 改回 `36 + 9` ⇒ 声明区间上界 71 > 实际 62，必须报错；
   D2 把上界改成 `slots.size() + 1` ⇒ 必须报错（模拟到下标 62 越界，与崩溃日志同型）；
   D3 构造器少 addSlot 一行（主背包 3 行 → 2 行）⇒ 实际 53 槽与常量 62 不符，必须报错；
   D4 构造器漏掉 6 个升级槽 ⇒ 实际 56 槽与常量 62 不符，必须报错；
   D5 用崩溃时的真实区间 (82, 100) + 真实槽数 91 跑模拟 ⇒ 必须复现
      `Index 91 out of bounds for length 91`。
E. 全仓其它 Menu 的 `moveItemStackTo` 做同一套静态检查（构造器实际槽数 vs 区间上界）。
   静态解析拿不到真实槽数的菜单必须进 `MANUAL_SLOT_TOTALS` 白名单并写明理由与人工核对的槽数，
   不允许「静默跳过」。

用法：python tools/selfcheck_round122_quickmove_bounds.py
退出码：0 = 全部通过；1 = 有问题。
"""
from __future__ import annotations

import copy
import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
MENU_DIR = os.path.join(JAVA, "menu")
DECOMP_MENU = os.path.join(MENU_DIR, "DecompositionGeneratorMenu.java")
DECOMP_BE = os.path.join(JAVA, "block", "entity", "DecompositionGeneratorBlockEntity.java")
DECOMP_SCREEN = os.path.join(JAVA, "client", "screen", "DecompositionGeneratorScreen.java")

problems = []
warnings = []


def read(path):
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def require(ok, message):
    if ok:
        print("  [OK] %s" % message)
    else:
        problems.append(message)
        print("  [X]  %s" % message)
    return ok


def warn(message):
    warnings.append(message)
    print("  [!]  %s" % message)


def section(title):
    print("\n===== %s =====" % title)


# =============================================================================
# 一、极简 Java 源码处理（去注释/字符串、括号配对、常量求值）
# =============================================================================

def strip_comments(text):
    """把注释与字符串字面量替换成等长空白（保留换行），避免误匹配与偏移错乱。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif ch == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if c == "\n" else " " for c in text[i:j]))
            i = j
        elif ch in "\"'":
            quote = ch
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == quote:
                    j += 1
                    break
                j += 1
            out.append(" " * (j - i))
            i = j
        else:
            out.append(ch)
            i += 1
    return "".join(out)


_CLOSERS = {"(": ")", "{": "}", "[": "]"}


def match_delim(text, start):
    """text[start] 是开括号，返回配对闭括号下标（找不到 -1）。"""
    opener = text[start]
    closer = _CLOSERS.get(opener)
    if closer is None:
        return -1
    depth = 0
    i = start
    n = len(text)
    while i < n:
        ch = text[i]
        if ch == opener:
            depth += 1
        elif ch == closer:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def split_top_level(text, sep=","):
    parts = []
    depth = 0
    cur = []
    for ch in text:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == sep and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
    parts.append("".join(cur))
    return [p.strip() for p in parts]


_CONST_RE = re.compile(r"\bstatic\s+final\s+int\s+([A-Za-z_$][\w$]*)\s*=\s*([^;]+);")
_IDENT_RE = re.compile(r"[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*")


class Unresolved(Exception):
    """表达式无法静态求值（含方法参数 / 局部变量 / 运行期值）。"""


class ConstantTable:
    """从全仓 java 源码收集 `static final int NAME = EXPR;`，并递归求值。"""

    def __init__(self):
        self.by_class = {}
        self.supers = {}

    def add_source(self, cls, text):
        consts = self.by_class.setdefault(cls, {})
        for match in _CONST_RE.finditer(text):
            consts[match.group(1)] = match.group(2).strip()
        extends = re.search(r"\bclass\s+" + re.escape(cls) + r"\s+extends\s+([A-Za-z_$][\w$]*)", text)
        if extends:
            self.supers[cls] = extends.group(1)

    def raw(self, cls, name):
        return self.by_class.get(cls, {}).get(name)

    def eval(self, expr, cls, local=None, slots_total=None, _depth=0):
        if _depth > 32:
            raise Unresolved("常量递归过深: %s" % expr)
        text = expr.strip()
        # 跨行限定的常量（`pkg.Cls\n    .SLOTS`）先把点两侧空白吃掉，否则标识符正则切不开
        text = re.sub(r"\s*\.\s*", ".", text)
        text = re.sub(r"\s+", " ", text)
        text = re.sub(r"\(\s*(?:int|long|short|byte|final)\s*\)", "", text)
        text = text.replace("this.slots.size()", "__SLOTS__").replace("slots.size()", "__SLOTS__")
        text = text.replace("Math.min", "__min").replace("Math.max", "__max")
        text = text.replace("this.", "")

        def repl(match):
            name = match.group(0)
            if name in ("__min", "__max", "__SLOTS__"):
                return name
            value = self.lookup(name, cls, local, slots_total, _depth + 1)
            return "(%d)" % value

        text = _IDENT_RE.sub(repl, text)
        if not re.fullmatch(r"(?:__min|__max|__SLOTS__|[-+*/%(),\s\d])*", text):
            raise Unresolved("表达式含非算术成分: %r" % expr)
        try:
            return int(eval(text, {"__builtins__": {}},  # noqa: S307 - 只含数字与四则运算
                            {"__min": min, "__max": max, "__SLOTS__": slots_total}))
        except Unresolved:
            raise
        except Exception:
            raise Unresolved("无法求值: %r" % expr)

    def lookup(self, name, cls, local, slots_total, depth):
        if local and name in local:
            return self.eval(local[name], cls, local, slots_total, depth)
        if "." in name:
            owner, const = name.rsplit(".", 1)
            owner = owner.split(".")[-1]
            if owner in self.by_class and const in self.by_class[owner]:
                return self.eval(self.by_class[owner][const], owner, None, slots_total, depth)
            raise Unresolved(name)
        for candidate in self.lineage(cls):
            if name in self.by_class.get(candidate, {}):
                return self.eval(self.by_class[candidate][name], candidate, local, slots_total, depth)
        raise Unresolved(name)

    def lineage(self, cls):
        """本类 + 所有父类（常量会被子类继承，`MATCH_WINDOW` 这类就是这么来的）。"""
        seen = []
        current = cls
        for _ in range(8):
            if current is None or current in seen:
                break
            seen.append(current)
            current = self.supers.get(current)
        return seen


def class_name_of(text, path):
    match = re.search(r"\bclass\s+([A-Za-z_$][\w$]*)", text)
    return match.group(1) if match else os.path.basename(path)[:-5]


# =============================================================================
# 二、从构造器数出真实槽数（addSlot 次数 × 外层 for 重数）
# =============================================================================

_FOR_HEAD = re.compile(r"\bfor\s*\(")
_FOR_VAR = re.compile(
    r"\s*(?:final\s+)?(?:int\s+)?([A-Za-z_$][\w$]*)\s*=\s*[^;]+;\s*\1\s*(<=|<)\s*([^;]+);")
_KEYWORDS = ("for", "if", "else", "while", "switch", "catch", "try", "do", "synchronized")
_KW_RE = re.compile(r"(for|if|else|while|switch|catch|try|do|synchronized)\b")


def parse_for_bound(header):
    """`int row = 0; row < BOUND; row++` → BOUND 表达式；解析不了返回 None。"""
    match = _FOR_VAR.match(header)
    if not match:
        return None
    bound = match.group(3).strip()
    if "&&" in bound or "||" in bound:
        return None
    if match.group(2) == "<=":
        bound = "(%s) + 1" % bound
    return bound


def analyze_body_slots(body, cls, table):
    """统计一段代码里 addSlot 的**实际执行次数**（外层 for 重数相乘）。

    返回 (total, ambiguous_reasons)。
    条件分支（if/else/while/switch/catch）里的 addSlot 静态次数不可信 ⇒ 记 ambiguous。
    """
    total = 0
    ambiguous = []
    stack = []  # (kind, bound) —— kind: "for" / "cond" / "block"
    i = 0
    n = len(body)
    while i < n:
        ch = body[i]
        if ch == "{":
            stack.append(("block", None))
            i += 1
            continue
        if ch == "}":
            if stack:
                stack.pop()
            i += 1
            continue
        if not (ch.isalpha() or ch in "_$"):
            i += 1
            continue
        if i > 0 and (body[i - 1].isalnum() or body[i - 1] in "_$."):
            i += 1
            continue
        kw_match = _KW_RE.match(body, i)
        if kw_match:
            keyword = kw_match.group(1)
            j = kw_match.end()
            k = j
            while k < n and body[k].isspace():
                k += 1
            if keyword in ("for", "if", "while", "switch", "catch", "synchronized"):
                if k < n and body[k] == "(":
                    close = match_delim(body, k)
                    if close > 0:
                        after = close + 1
                        while after < n and body[after].isspace():
                            after += 1
                        if after < n and body[after] == "{":
                            if keyword == "for":
                                # 上界可能解析不了（for-each / 运行期列表）：先记 None，
                                # 只有当 addSlot 真的落在里面时才算「槽数不可信」
                                stack.append(("for", parse_for_bound(body[k + 1:close])))
                            elif keyword in ("if", "while", "switch", "catch"):
                                stack.append(("cond", None))
                            else:
                                stack.append(("block", None))
                            i = after + 1
                            continue
                        elif keyword in ("for", "if"):
                            # 无花括号的单语句体：槽位计数不肯靠，直接标记歧义（交给白名单人工核对）
                            ambiguous.append("无花括号的 %s，槽位计数不可靠" % keyword)
                            i = close + 1
                            continue
                        i = close + 1
                        continue
            if keyword == "else":
                if k < n and body[k] == "{":
                    stack.append(("cond", None))
                    i = k + 1
                    continue
                i = j
                continue
            if keyword in ("try", "do"):
                if k < n and body[k] == "{":
                    stack.append(("block", None))
                    i = k + 1
                    continue
                i = j
                continue
            i = j
            continue
        j = i
        while j < n and (body[j].isalnum() or body[j] in "_$"):
            j += 1
        word = body[i:j]
        if word == "addSlot":
            k = j
            while k < n and body[k].isspace():
                k += 1
            if k < n and body[k] == "(":
                mult = 1
                conditional = False
                for kind, bound in stack:
                    if kind == "cond":
                        conditional = True
                    elif kind == "for":
                        if bound is None:
                            conditional = True
                        else:
                            try:
                                mult *= table.eval(bound, cls)
                            except Unresolved:
                                conditional = True
                if conditional:
                    # 保守：按 1 次计入（真实值可能更多），并标记为不可信
                    ambiguous.append("addSlot 位于条件分支 / 上界不可解析处，静态次数不可信")
                    total += 1
                else:
                    total += mult
        i = j
    return total, ambiguous


def constructor_bodies(text, cls):
    bodies = []
    pattern = re.compile(r"\b(?:public|protected|private)\s+" + re.escape(cls) + r"\s*\(")
    for match in pattern.finditer(text):
        paren = text.find("(", match.end() - 1)
        close = match_delim(text, paren)
        if close < 0:
            continue
        k = close + 1
        while k < len(text) and text[k].isspace():
            k += 1
        if k < len(text) and text[k] == "{":
            end = match_delim(text, k)
            if end > 0:
                bodies.append(text[k + 1:end])
    return bodies


def method_body(text, name):
    """取方法定义体（同名调用 `foo(...);` 不会被误当成定义）。"""
    for match in re.finditer(r"\b" + re.escape(name) + r"\s*\(", text):
        paren = text.find("(", match.end() - 1)
        close = match_delim(text, paren)
        if close < 0:
            continue
        k = close + 1
        while k < len(text) and text[k].isspace():
            k += 1
        if k < len(text) and text[k] == "{":
            end = match_delim(text, k)
            if end > 0:
                return text[k + 1:end]
    return None


_LOCAL_DECL = re.compile(r"\b(?:final\s+)?int\s+([A-Za-z_$][\w$]*)\s*=\s*([^;]+);")


def local_ints(scope_text):
    """按出现顺序收集 `int NAME = EXPR;`（后声明的可以引用前面的）。"""
    return [(m.group(1), m.group(2).strip()) for m in _LOCAL_DECL.finditer(scope_text)]


_MOVE_RE = re.compile(r"\bmoveItemStackTo\s*\(")


def move_ranges(text):
    """提取所有 moveItemStackTo 调用的 (start_expr, end_expr, 行号)。"""
    out = []
    for match in _MOVE_RE.finditer(text):
        paren = text.find("(", match.end() - 1)
        close = match_delim(text, paren)
        if close < 0:
            continue
        args = split_top_level(text[paren + 1:close])
        if len(args) < 3:
            continue
        line = text.count("\n", 0, match.start()) + 1
        out.append((args[1], args[2], line))
    return out


def simulate_move(start, end, total, reverse):
    """逐下标模拟 AbstractContainerMenu#moveItemStackTo 的访问序列（end 为不含上界）。

    越界立刻抛 IndexError，模拟客户端真崩的那一下：区间 (82, 100) + 91 槽时，
    正向循环第一次摸到的非法下标就是 91，与崩溃日志 `Index 91 out of bounds for length 91` 同型。
    """
    if start < 0:
        raise IndexError(start)
    if end > total:
        raise IndexError(max(start, total))
    if start >= end:
        raise IndexError(start)
    accessed = []
    i = end - 1 if reverse else start
    while (i >= start) if reverse else (i < end):
        if i < 0 or i >= total:
            raise IndexError(i)
        accessed.append(i)
        i += -1 if reverse else 1
    return accessed


# =============================================================================
# 三、人工白名单：静态解析拿不到构造器真实槽数的菜单
#    （必须写理由 + 人工核对的真实槽数，不允许静默跳过）
# =============================================================================

MANUAL_SLOT_TOTALS = {
    "QuantityKeeperMenu": (
        43,
        "构造器是 if (keeper != null) {...} else {...} 二选一，两条分支各 addSlot 7 次"
        "（1 标记槽 + 6 升级槽），静态求和会把两条分支都算上（=14）；人工核对实际只走一条分支："
        "7 机器槽 + 36 玩家槽 = 43，quickMoveStack 区间 (7,43) / (0,7) 正好铺满 [0,43)。"),
    "NetworkResourceReceiverMenu": (
        42,
        "第 132 轮改成 RS 的 AbstractGridContainerMenu：玩家背包 36 槽由 super.resized() 追加，"
        "6 个升级槽由本菜单覆写的 resized() 在同一处追加；槽位顺序恒为 [0,36) 玩家 + [36,42) 升级。"
        "静态解析看不到 resized() 里的 addSlot（构造器外），故人工核对 42 槽；"
        "quickMoveStack 区间 (0,36) / (36,42) 正好铺满 [0,42)。"),
}

# 槽数随数据动态变化、静态给不出具体数字的菜单：必须写清楚「为什么安全」的可核对依据。
UNVERIFIABLE_REASONS = {
    "UnitPatternManagerMenu": (
        "槽数 = sectionSlots + 36，sectionSlots 由运行期分组数量决定（rebuildSlots 逐分组 addSlot 累计）；"
        "quickMoveStack 的 playerStart = sectionSlots、playerEnd = sectionSlots + 36 用的是同一个字段，"
        "而 rebuildSlots 在 `this.sectionSlots = slotCount;` 之后立刻用原版 addPlayerInventory（固定 36 槽）"
        "追加玩家背包，所以玩家区恰好是 [sectionSlots, sectionSlots+36) = [slots.size()-36, slots.size())。"
        "脚本用 check_unit_pattern_menu_invariant() 单独核对这几条结构不变量。"),
}


def check_unit_pattern_menu_invariant(path):
    """UnitPatternManagerMenu 的动态槽数不变量（静态给不出数字，但可以核对结构）。"""
    text = strip_comments(read(path))
    ok = True
    qmb = method_body(text, "quickMoveStack") or ""
    ok &= require(re.search(r"playerStart\s*=\s*sectionSlots\s*;", qmb) is not None,
                  "UnitPatternManagerMenu：playerStart 直接取 sectionSlots")
    ok &= require(re.search(r"playerEnd\s*=\s*sectionSlots\s*\+\s*36\s*;", qmb) is not None,
                  "UnitPatternManagerMenu：playerEnd = sectionSlots + 36")
    rebuild = method_body(text, "rebuildSlots") or ""
    ok &= require("this.sectionSlots = slotCount;" in rebuild,
                  "UnitPatternManagerMenu：sectionSlots 由分组实际 addSlot 次数累计（slotCount）")
    ok &= require(re.search(r"this\.sectionSlots\s*=\s*slotCount;\s*addPlayerInventory\(", rebuild) is not None,
                  "UnitPatternManagerMenu：sectionSlots 定稿后才追加玩家背包（顺序不会错位）")
    # 原版 AbstractContainerMenu#addPlayerInventory(Inventory, int, int) 固定加 27 主背包 + 9 快捷栏 = 36
    slots_in_rebuild = len(re.findall(r"\baddSlot\s*\(", rebuild))
    ok &= require(slots_in_rebuild == 1,
                  "UnitPatternManagerMenu：rebuildSlots 里只有 1 处 addSlot（分组循环内），玩家区交给原版 helper")
    ok &= require(re.search(r"addSlot\(new SectionSlot\([^;]*;\s*slotCount\+\+;", rebuild) is not None,
                  "UnitPatternManagerMenu：分组 addSlot 与 slotCount++ 一一对应（sectionSlots == 已加分组槽数）")
    ok &= require(qmb.count("moveItemStackTo") == 2,
                  "UnitPatternManagerMenu：quickMoveStack 只有两个 moveItemStackTo 方向")
    ok &= require(re.search(r"moveItemStackTo\(stackInSlot,\s*playerStart,\s*playerEnd,", qmb) is not None
                  and re.search(r"moveItemStackTo\(stackInSlot,\s*0,\s*playerStart,", qmb) is not None,
                  "UnitPatternManagerMenu：两段区间 = [0, playerStart) 与 [playerStart, playerEnd)，正好铺满")
    return ok


CLASS_PATH = {}
_PARENT_TOTAL_CACHE = {}


def parent_slot_total(parent, table, _depth=0):
    """父类构造器加的槽数（`super(..., inventory, ...)` 时父类先 addSlot，子类总槽数要加上它）。"""
    if parent is None or _depth > 4:
        return None
    if parent in _PARENT_TOTAL_CACHE:
        return _PARENT_TOTAL_CACHE[parent]
    path = CLASS_PATH.get(parent)
    if not path:
        _PARENT_TOTAL_CACHE[parent] = None
        return None
    text = strip_comments(read(path))
    bodies = constructor_bodies(text, parent)
    if not bodies:
        _PARENT_TOTAL_CACHE[parent] = None
        return None
    best = max(bodies, key=lambda b: b.count("addSlot"))
    total, ambiguous = analyze_body_slots(best, parent, table)
    if ambiguous:
        _PARENT_TOTAL_CACHE[parent] = None
        return None
    grand = parent_slot_total(table.supers.get(parent), table, _depth + 1)
    if grand is None and table.supers.get(parent) in table.by_class:
        # 父类自己在表里但抽不出来 ⇒ 不可信
        _PARENT_TOTAL_CACHE[parent] = None
        return None
    total += grand or 0
    _PARENT_TOTAL_CACHE[parent] = total
    return total


def analyze_menu(path, table, source_override=None):
    """解析一个菜单：构造器实际槽数 + 所有 moveItemStackTo 区间（尽量求值）。"""
    raw = source_override if source_override is not None else read(path)
    text = strip_comments(raw)
    cls = class_name_of(text, path)
    bodies = constructor_bodies(text, cls)
    ambiguous = []
    parent_extra = 0
    if bodies:
        best = max(bodies, key=lambda b: b.count("addSlot"))
        ctor_total, ambiguous_body = analyze_body_slots(best, cls, table)
        ambiguous.extend(ambiguous_body)
        # 继承型菜单（如高级归流缓存仓 extends 基础版）：super 里传了 inventory ⇒ 父构造器也 addSlot
        super_call = re.search(r"\bsuper\s*\(", best)
        if super_call:
            paren = best.find("(", super_call.end() - 1)
            close = match_delim(best, paren)
            if close > 0 and re.search(r"\binventory\b", best[paren + 1:close]):
                parent = table.supers.get(cls)
                inherited = parent_slot_total(parent, table)
                if inherited is None:
                    ambiguous.append("父类 %s 的槽数未知（super 传了 inventory，父构造器也会 addSlot）" % parent)
                else:
                    parent_extra = inherited
    else:
        ctor_total = 0
        ambiguous.append("找不到构造器")
    ctor_total += parent_extra
    total_addslot = len(re.findall(r"\baddSlot\s*\(", text))
    in_ctors = sum(len(re.findall(r"\baddSlot\s*\(", b)) for b in bodies)
    if total_addslot > in_ctors:
        ambiguous.append("构造器外还有 addSlot 调用（辅助方法 / 字段初始化），构造器计数不完整")

    manual = MANUAL_SLOT_TOTALS.get(cls)
    if manual is not None:
        total = manual[0]
        total_source = "人工白名单: %s" % manual[1]
    elif ambiguous:
        total = None
        total_source = "无（无法静态确定）"
    elif parent_extra:
        total = ctor_total
        total_source = "构造器 addSlot 静态计数（含父类继承 %d 槽）" % parent_extra
    else:
        total = ctor_total
        total_source = "构造器 addSlot 静态计数"

    qmb = method_body(text, "quickMoveStack")
    locals_scope = None
    if qmb:
        decls = local_ints(qmb)
        # 只保留能依次求值的局部量（前面的失败会让后面的也失败，这是预期）
        locals_scope = {name: expr for name, expr in decls}

    ranges = []
    for start_expr, end_expr, line in move_ranges(text):
        entry = {"start_expr": start_expr, "end_expr": end_expr, "line": line,
                 "start": None, "end": None, "error": None}
        if total is None:
            entry["error"] = "菜单真实槽数未知"
            ranges.append(entry)
            continue
        try:
            entry["start"] = table.eval(start_expr, cls, locals_scope, total)
            entry["end"] = table.eval(end_expr, cls, locals_scope, total)
        except Unresolved as exc:
            entry["error"] = "表达式无法静态求值: %s" % exc
        ranges.append(entry)

    return {
        "path": path,
        "cls": cls,
        "text": text,
        "ctor_total": ctor_total,
        "ambiguous": ambiguous,
        "total": total,
        "total_source": total_source,
        "ranges": ranges,
        "quickmove_body": qmb,
    }


def check_ranges(info, label_prefix="", expect_ok=True):
    """检查区间 0 <= start < end <= total，并逐下标模拟。返回是否全部合法。"""
    all_ok = True
    if info["total"] is None:
        reason = UNVERIFIABLE_REASONS.get(info["cls"])
        if reason:
            warn("%s%s：槽数无法静态确定（%s）⇒ 人工白名单理由：%s"
                 % (label_prefix, info["cls"], "；".join(info["ambiguous"]), reason))
        else:
            all_ok = False
            problems.append("%s%s：槽数无法静态确定，且没有进 MANUAL_SLOT_TOTALS 白名单（%s）"
                            % (label_prefix, info["cls"], "；".join(info["ambiguous"])))
            print("  [X]  %s%s：槽数无法静态确定（%s）" % (label_prefix, info["cls"], "；".join(info["ambiguous"])))
        return False
    total = info["total"]
    if not info["ranges"]:
        print("  [--] %s%s：没有 moveItemStackTo 调用" % (label_prefix, info["cls"]))
        return True
    for entry in info["ranges"]:
        desc = "L%s moveItemStackTo(%s, %s)" % (entry["line"], entry["start_expr"], entry["end_expr"])
        if entry["error"]:
            reason = UNVERIFIABLE_REASONS.get(info["cls"])
            if reason:
                warn("%s%s %s ⇒ 人工白名单理由：%s" % (label_prefix, info["cls"], desc, reason))
            else:
                all_ok = False
                problems.append("%s%s %s：%s" % (label_prefix, info["cls"], desc, entry["error"]))
                print("  [X]  %s%s %s：%s" % (label_prefix, info["cls"], desc, entry["error"]))
            continue
        start, end = entry["start"], entry["end"]
        ok = 0 <= start < end <= total
        if not ok:
            all_ok = False
            problems.append("%s%s %s ⇒ 解析为 (%d, %d)，但菜单只有 %d 槽（要求 0 <= start < end <= %d）"
                            % (label_prefix, info["cls"], desc, start, end, total, total))
            print("  [X]  %s%s %s ⇒ (%d, %d) 越界（总槽数 %d）"
                  % (label_prefix, info["cls"], desc, start, end, total))
            continue
        try:
            simulate_move(start, end, total, reverse=False)
            simulate_move(start, end, total, reverse=True)
        except IndexError as exc:
            all_ok = False
            problems.append("%s%s %s ⇒ 模拟 moveItemStackTo 时 slots.get(%s) 越界（总槽数 %d）"
                            % (label_prefix, info["cls"], desc, exc, total))
            print("  [X]  %s%s %s ⇒ 模拟越界 slots.get(%s)（总槽数 %d）"
                  % (label_prefix, info["cls"], desc, exc, total))
            continue
        print("  [OK] %s%s %s ⇒ (%d, %d) ⊂ [0, %d)  %s"
              % (label_prefix, info["cls"], desc, start, end, total, info["total_source"]))
    return all_ok


def menu_infos(table):
    out = []
    for name in sorted(os.listdir(MENU_DIR)):
        if not name.endswith("Menu.java"):
            continue
        path = os.path.join(MENU_DIR, name)
        text = strip_comments(read(path))
        if "moveItemStackTo(" in text and "quickMoveStack" in text:
            out.append(analyze_menu(path, table))
    return out


# =============================================================================
# 主流程
# =============================================================================

def main():
    table = ConstantTable()
    for dirpath, _dirnames, filenames in os.walk(JAVA):
        for filename in sorted(filenames):
            if not filename.endswith(".java"):
                continue
            path = os.path.join(dirpath, filename)
            text = strip_comments(read(path))
            cls = class_name_of(text, path)
            table.add_source(cls, text)
            CLASS_PATH.setdefault(cls, path)

    # -------------------------------------------------------------------------
    section("A. 分解发电机：常量链必须自洽（崩溃根因 = PLAYER_SLOT_COUNT 45 ≠ 实际 36）")
    # -------------------------------------------------------------------------
    be = strip_comments(read(DECOMP_BE))
    be_cls = class_name_of(be, DECOMP_BE)
    table.add_source(be_cls, be)

    spec_path = os.path.join(JAVA, "support", "DecompositionGeneratorSpec.java")
    spec_text = strip_comments(read(spec_path))
    spec_cls = class_name_of(spec_text, spec_path)
    table.add_source(spec_cls, spec_text)

    inventory_size = table.eval("%s.INVENTORY_SIZE" % spec_cls, spec_cls)
    input_count = table.eval("%s.INPUT_COUNT" % spec_cls, spec_cls)
    output_count = table.eval("%s.OUTPUT_COUNT" % spec_cls, spec_cls)
    upgrade_slots = table.eval("%s.UPGRADE_SLOTS" % spec_cls, spec_cls)
    spec_player = table.eval("%s.PLAYER_SLOT_COUNT" % spec_cls, spec_cls)
    print("  Spec：INPUT_COUNT=%d OUTPUT_COUNT=%d INVENTORY_SIZE=%d UPGRADE_SLOTS=%d PLAYER_SLOT_COUNT=%d"
          % (input_count, output_count, inventory_size, upgrade_slots, spec_player))
    require(inventory_size == input_count + output_count,
            "Spec：INVENTORY_SIZE(%d) == INPUT_COUNT(%d) + OUTPUT_COUNT(%d)"
            % (inventory_size, input_count, output_count))
    require(input_count == 16 and output_count == 4,
            "Spec：16 格输入（4×4）+ 4 格副产物（2×2）一次全画出（不再分页）")
    require(upgrade_slots == 6, "Spec：升级槽 6 格（3 列 × 2 行）")
    require(spec_player == 36,
            "Spec：玩家区 36 格 = 27 + 9（崩溃时被写成 36 + 9 = 45）")

    menu = analyze_menu(DECOMP_MENU, table)
    cls = menu["cls"]
    ctor_total = menu["ctor_total"]

    machine_start = table.eval("MACHINE_SLOT_START", cls)
    machine_end = table.eval("MACHINE_SLOT_END", cls)
    machine_count = table.eval("MACHINE_SLOT_COUNT", cls)
    upgrade_start = table.eval("UPGRADE_SLOT_START", cls)
    upgrade_end = table.eval("UPGRADE_SLOT_END", cls)
    player_main = table.eval("PLAYER_MAIN_SLOT_COUNT", cls)
    player_hotbar = table.eval("PLAYER_HOTBAR_SLOT_COUNT", cls)
    player_count = table.eval("PLAYER_SLOT_COUNT", cls)
    player_start = table.eval("PLAYER_SLOT_START", cls)
    player_end = table.eval("PLAYER_SLOT_END", cls)
    total_const = table.eval("TOTAL_SLOT_COUNT", cls)
    print("  常量：MACHINE=[%d, %d)（库存 %d 格） UPGRADE=[%d, %d) 玩家主背包=%d 快捷栏=%d "
          "玩家区=[%d, %d) TOTAL=%d"
          % (machine_start, machine_end, machine_count, upgrade_start, upgrade_end,
             player_main, player_hotbar, player_start, player_end, total_const))

    require(machine_start == 0, "机器槽从 0 开始（MACHINE_SLOT_START = %d）" % machine_start)
    require(machine_count == inventory_size,
            "机器槽数 %d == Spec.INVENTORY_SIZE(%d)（16 输入 + 4 副产物）"
            % (machine_count, inventory_size))
    require(machine_end - machine_start == inventory_size,
            "机器槽区间宽 %d == INVENTORY_SIZE(%d)" % (machine_end - machine_start, inventory_size))
    require(upgrade_start == machine_end,
            "升级槽紧跟机器槽：UPGRADE_SLOT_START(%d) == MACHINE_SLOT_END(%d)"
            % (upgrade_start, machine_end))
    require(upgrade_end - upgrade_start == upgrade_slots,
            "升级槽区间宽 %d == Spec.UPGRADE_SLOTS(%d)" % (upgrade_end - upgrade_start, upgrade_slots))
    require(player_main == 27, "玩家主背包 3×9 = 27 格（PLAYER_MAIN_SLOT_COUNT = %d）" % player_main)
    require(player_hotbar == 9, "快捷栏 9 格（PLAYER_HOTBAR_SLOT_COUNT = %d）" % player_hotbar)
    require(player_count == 36,
            "PLAYER_SLOT_COUNT = %d（原版 Inventory 总共 36 格；崩溃时是 45）" % player_count)
    require(player_start == upgrade_end,
            "玩家区紧跟升级槽：PLAYER_SLOT_START(%d) == UPGRADE_SLOT_END(%d)"
            % (player_start, upgrade_end))
    require(player_end == player_start + 36,
            "PLAYER_SLOT_END(%d) == PLAYER_SLOT_START(%d) + 36" % (player_end, player_start))
    require(total_const == player_end, "TOTAL_SLOT_COUNT(%d) == PLAYER_SLOT_END(%d)" % (total_const, player_end))
    require(total_const == 62, "TOTAL_SLOT_COUNT == 62 == 20 机器 + 6 升级 + 36 玩家")

    # 构造器实际加进去的槽，必须与常量声明一致（这正是崩溃时 91 ≠ 100 的那一步）
    require(ctor_total == total_const,
            "构造器 addSlot 实际次数 %d == 常量声明的 TOTAL_SLOT_COUNT %d" % (ctor_total, total_const))
    require(ctor_total == inventory_size + upgrade_slots + 36,
            "槽位数分解：%d 机器 + %d 升级 + 36 玩家 = %d"
            % (inventory_size, upgrade_slots, inventory_size + upgrade_slots + 36))
    require(not menu["ambiguous"],
            "构造器槽位计数无歧义（无 if 分支 / 无辅助方法 addSlot）：%s" % (menu["ambiguous"] or "无"))
    require(menu["text"].count("addSlot(") == 5,
            "构造器只有 5 处 addSlot（输入 / 副产物 / 升级 / 主背包 / 快捷栏），实得 %d"
            % menu["text"].count("addSlot("))

    # PLAYER_SLOT_COUNT 的写法必须由「行×列」推导，不得再出现 36 + 9
    player_count_expr = table.raw(cls, "PLAYER_SLOT_COUNT") or ""
    require("PLAYER_MAIN_SLOT_COUNT" in player_count_expr and "PLAYER_HOTBAR_SLOT_COUNT" in player_count_expr,
            "PLAYER_SLOT_COUNT 由 addSlot 次数推导（%r），不是手写常量" % player_count_expr)
    for derived in ("PLAYER_MAIN_SLOT_COUNT", "PLAYER_HOTBAR_SLOT_COUNT"):
        raw_expr = table.raw(cls, derived) or ""
        require("DecompositionGeneratorSpec." in raw_expr,
                "%s 由 Spec 的行列常量推导（%r）" % (derived, raw_expr))
    require("36 + 9" not in menu["text"] and "36+9" not in menu["text"],
            "源码里不再出现 `36 + 9`（把 36 格总数又加快捷栏的写法）")

    # 分页 / 展示槽的残留必须清零（第 123 轮把 256 格分页整套删掉）
    require(not re.search(r"\b(?:PAGE_SIZE|PAGE_COUNT|PAGE_ROWS|PAGE_COLS|SLOT_COUNT)\b", menu["text"]),
            "菜单里不再残留分页 / 256 格常量（PAGE_* / 独立的 SLOT_COUNT）")
    require(not any(token in menu["text"] for token in ("PagedSlot", "PagedContainer", "DisplaySlot",
                                                        "isLocalIndexUsable", "getDisplayHandler")),
            "分页槽 / 分页容器 / 展示槽 / 本地下标映射已全部删除")

    # 构造器的玩家循环必须用同一组常量（行数/每行格数），防止再次「常量说 45、循环加 36」
    player_loop = re.search(
        r"for\s*\(\s*int\s+row\s*=\s*0;\s*row\s*<\s*DecompositionGeneratorSpec\.PLAYER_INV_ROWS;",
        menu["text"])
    hotbar_loop = re.search(
        r"for\s*\(\s*int\s+col\s*=\s*0;\s*col\s*<\s*PLAYER_HOTBAR_SLOT_COUNT;", menu["text"])
    require(bool(player_loop), "构造器主背包循环用 Spec.PLAYER_INV_ROWS（与常量同源）")
    require(bool(hotbar_loop), "构造器快捷栏循环用 PLAYER_HOTBAR_SLOT_COUNT（与常量同源）")

    # -------------------------------------------------------------------------
    section("B. 分解发电机：quickMoveStack 的六个区间 + 语义")
    # -------------------------------------------------------------------------
    qmb = menu["quickmove_body"] or ""
    require("tryInsertIntoMachine" not in qmb,
            "不再有「只扫当前页」的 tryInsertIntoMachine（20 格库存一次全扫）")
    require("DecompositionTable.isDecomposable" in qmb,
            "「玩家 → 机器」先判 isDecomposable（不可降解 ⇒ 提示且原地不动）")
    require("reject(player)" in qmb
            and "decomposition_generator.reject" in read(DECOMP_MENU),
            "不可降解物原地不动 + actionbar 提示（不是「放进去再被吐出来」）")
    require("this.slots.size()" in qmb,
            "区间上界与 slots.size() 取交（常量再漂也不会崩）")
    require(re.search(r"if\s*\(\s*index\s*<\s*0\s*\|\|\s*index\s*>=\s*this\.slots\.size\(\)\s*\)", qmb) is not None,
            "入口先判 index ∈ [0, slots.size())（越界直接返回，不摸 slots.get）")
    for guarded in ("MACHINE_SLOT_START", "MACHINE_SLOT_END", "UPGRADE_SLOT_START",
                    "UPGRADE_SLOT_END", "PLAYER_SLOT_START", "PLAYER_SLOT_END"):
        require(re.search(r"Math\.min\(%s,\s*this\.slots\.size\(\)\)" % guarded, qmb) is not None,
                "%s 与 this.slots.size() 取 Math.min 兜底" % guarded)
    require(re.search(r"final\s+int\s+playerEnd\s*=\s*Math\.min\(PLAYER_SLOT_END,\s*this\.slots\.size\(\)\)", qmb)
            is not None,
            "playerEnd = Math.min(PLAYER_SLOT_END, this.slots.size())")
    require(re.search(r"final\s+int\s+mainEnd\s*=\s*Math\.min\(playerStart\s*\+\s*PLAYER_MAIN_SLOT_COUNT,\s*playerEnd\)",
                      qmb) is not None,
            "mainEnd = Math.min(playerStart + PLAYER_MAIN_SLOT_COUNT, playerEnd)")
    require(not re.search(r"PLAYER_SLOT_START\s*\+\s*27", qmb),
            "主背包分界不再硬编码 +27（改用 PLAYER_MAIN_SLOT_COUNT）")
    require(re.search(r"if\s*\(machineStart\s*>=\s*machineEnd\s*\|\|\s*playerStart\s*>=\s*playerEnd\)",
                      qmb) is not None,
            "槽位没被加进菜单时宁可不移动（绝不越界）")

    menu_ok = check_ranges(menu, label_prefix="")

    resolved = [entry for entry in menu["ranges"] if entry["start"] is not None]
    triples = [(entry["start"], entry["end"]) for entry in resolved]
    require((player_start, player_end) in triples,
            "「机器 / 升级槽 → 玩家背包」区间 = (%d, %d)（整个玩家区）" % (player_start, player_end))
    require((upgrade_start, upgrade_end) in triples,
            "「玩家 → 升级槽」区间 = (%d, %d)" % (upgrade_start, upgrade_end))
    require((machine_start, machine_end) in triples,
            "「玩家 → 机器」区间 = (%d, %d)（覆盖整份 %d 格库存）"
            % (machine_start, machine_end, inventory_size))
    require((player_start + player_main, player_end) in triples,
            "「玩家主背包 → 快捷栏」区间 = (%d, %d)" % (player_start + player_main, player_end))
    require((player_start, player_start + player_main) in triples,
            "「快捷栏 → 玩家主背包」区间 = (%d, %d)" % (player_start, player_start + player_main))

    require(all(0 <= start < end <= total_const for start, end in triples),
            "所有区间满足 0 <= start < end <= TOTAL_SLOT_COUNT(%d)" % total_const)
    covered = set()
    for start, end in triples:
        covered.update(range(start, end))
    require(covered == set(range(total_const)),
            "六个区间的并集恰好覆盖 [0, %d)（每个槽都有去处、没有区间跨过区界；缺 %s）"
            % (total_const, sorted(set(range(total_const)) - covered) or "无"))

    # 真·崩溃路径模拟：崩溃时真实槽数 91，玩家主背包 → 目标区间 (82, 100)
    crash_reproduced = False
    try:
        simulate_move(82, 100, 91, reverse=False)
    except IndexError as exc:
        crash_reproduced = int(str(exc)) == 91
    require(crash_reproduced,
            "崩溃复现：真实槽数 91 下区间 (82, 100) 第一次越界下标 = 91（与日志 `Index 91 out of bounds for length 91` 一致）")
    require(menu_ok, "修复后的所有区间在 %d 槽下逐下标模拟均无越界" % total_const)

    # -------------------------------------------------------------------------
    section("C. 反例：自检必须能判负（否则等于没检）")
    # -------------------------------------------------------------------------
    src = read(DECOMP_MENU)

    def patched(needle, replacement, label):
        """把源码改坏一份（并重建常量表），用来验证自检真能判负。"""
        text = src.replace(needle, replacement)
        require(text != src, "%s 反例注入成功" % label)
        patched_table = copy.deepcopy(table)
        patched_table.add_source(cls, strip_comments(text))
        return text, patched_table

    bad_count, bad1_table = patched(
        "PLAYER_SLOT_COUNT = PLAYER_MAIN_SLOT_COUNT + PLAYER_HOTBAR_SLOT_COUNT;",
        "PLAYER_SLOT_COUNT = 36 + 9;", "D1")
    bad1 = analyze_menu(DECOMP_MENU, bad1_table, source_override=bad_count)
    bad1_declared_end = bad1_table.eval("PLAYER_SLOT_END", bad1["cls"])
    bad1_detected = bad1["ctor_total"] != bad1_declared_end
    if not bad1_detected:
        bad1_detected = any(
            entry["start"] is not None and entry["end"] is not None
            and not (0 <= entry["start"] < entry["end"] <= bad1["total"])
            for entry in bad1["ranges"])
    require(bad1_detected,
            "D1 `PLAYER_SLOT_COUNT = 36 + 9` 必须判负（声明上界 %d vs 实际 %d 槽）"
            % (bad1_declared_end, bad1["ctor_total"]))

    bad_end, bad2_table = patched(
        "final int playerEnd = Math.min(PLAYER_SLOT_END, this.slots.size());",
        "final int playerEnd = this.slots.size() + 1;", "D2")
    bad2 = analyze_menu(DECOMP_MENU, bad2_table, source_override=bad_end)
    bad2_detected = False
    for entry in bad2["ranges"]:
        if entry["start"] is None or entry["end"] is None:
            continue
        if not (0 <= entry["start"] < entry["end"] <= bad2["total"]):
            bad2_detected = True
            continue
        try:
            simulate_move(entry["start"], entry["end"], bad2["total"], reverse=False)
        except IndexError:
            bad2_detected = True
    require(bad2_detected,
            "D2 上界 `slots.size() + 1` 必须判负（区间 (%s, %s) 在 %d 槽下模拟越界）"
            % (bad2["ranges"][0]["start"] if bad2["ranges"] else "?",
               bad2["ranges"][0]["end"] if bad2["ranges"] else "?", bad2["total"]))

    bad_slots, bad3_table = patched(
        "for (int row = 0; row < DecompositionGeneratorSpec.PLAYER_INV_ROWS; row++) {",
        "for (int row = 0; row < DecompositionGeneratorSpec.PLAYER_INV_ROWS - 1; row++) {", "D3")
    bad3 = analyze_menu(DECOMP_MENU, bad3_table, source_override=bad_slots)
    bad3_detected = bad3["ctor_total"] != bad3_table.eval("TOTAL_SLOT_COUNT", bad3["cls"])
    require(bad3_detected,
            "D3 构造器少 addSlot 一行必须判负（实际 %d vs 常量 %d）"
            % (bad3["ctor_total"], bad3_table.eval("TOTAL_SLOT_COUNT", bad3["cls"])))

    bad_upgrade, bad4_table = patched(
        "for (int index = 0; index < DecompositionGeneratorSpec.UPGRADE_SLOTS; index++) {",
        "for (int index = 0; index < 0; index++) {", "D4")
    bad4 = analyze_menu(DECOMP_MENU, bad4_table, source_override=bad_upgrade)
    bad4_detected = bad4["ctor_total"] != bad4_table.eval("TOTAL_SLOT_COUNT", bad4["cls"])
    require(bad4_detected,
            "D4 构造器漏掉 6 个升级槽必须判负（实际 %d vs 常量 %d）"
            % (bad4["ctor_total"], bad4_table.eval("TOTAL_SLOT_COUNT", bad4["cls"])))

    # -------------------------------------------------------------------------
    section("D. 全仓其它菜单：构造器真实槽数 vs moveItemStackTo 区间")
    # -------------------------------------------------------------------------
    checked = 0
    for info in menu_infos(table):
        if info["cls"] == cls:
            continue
        checked += 1
        if info["ambiguous"] or info["cls"] in MANUAL_SLOT_TOTALS:
            print("  [..] %s：槽数来源=%s；静态提示=%s"
                  % (info["cls"], info["total_source"], "；".join(info["ambiguous"]) or "无"))
        check_ranges(info, label_prefix="")
    print("\n  共扫描其它菜单 %d 个。" % checked)

    # 槽数动态的那个菜单：静态给不出数字，但结构不变量必须成立
    unit_path = os.path.join(MENU_DIR, "UnitPatternManagerMenu.java")
    if os.path.exists(unit_path):
        print()
        check_unit_pattern_menu_invariant(unit_path)

    # -------------------------------------------------------------------------
    section("E. 结果")
    # -------------------------------------------------------------------------
    if warnings:
        print("  白名单/警告 %d 条：" % len(warnings))
        for message in warnings:
            print("   - %s" % message)
    if problems:
        print("\n  失败 %d 条：" % len(problems))
        for message in problems:
            print("   - %s" % message)
        print("\n结果：FAIL")
        return 1
    print("\n结果：PASS（0 失败，%d 条白名单/警告）" % len(warnings))
    return 0


if __name__ == "__main__":
    sys.exit(main())
