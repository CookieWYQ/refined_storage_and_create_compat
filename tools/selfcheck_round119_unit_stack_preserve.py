#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 119 轮自检：单元样板「按张搬运」，一叠 N 张存入后不许静默丢 N−1 张（复核 F1）。

背景（docs/ROUND119_AUDIT_SEQUENCE_CHAMBER.md §F1）
----------------------------------------------------
* 单元样板物品 ``stacksTo(64)``；执行仓的「单元槽」是 ``SimpleContainer``，每格也能放 64 张；
* 旧「取回」把**整叠** ``stack.copy()`` 写进单元样板库的**一格**（库内一格叠 N 张）；
* 旧「存入」只 ``copyWithCount(1)`` 放回 1 张，然后 ``setStackInSlot(i, EMPTY)`` **整格清空**；
* ⇒ 一叠 N 张经过「取回 → 存入」后只剩 1 张，**N−1 张没有任何提示地消失**。

修法（不改变玩法语义）
----------------------
1. 取回：逐张写库（库内一格一张），写进 1 张才从执行仓 ``shrink(1)``；库满 / 体积预算拒绝
   就停在原地（先确认能写、再写、最后清空）；
2. 存入：逐张从库里取出（旧档同格多张也逐张取，能取几张取几张），先写执行仓再 ``extractItem``；
3. 界面「张数」按 ``sum(ItemStack.getCount())`` 显示，而不是按格数 ``units.size()``；
4. 库满 / 体积预算导致只搬了一部分时，至少给玩家一条可见动作栏提示。

本脚本三条腿
------------
① **源码锚点**：正例（当前实现）必须全部命中；每条锚点都配一个「坏写法」反例，证明锚点会判负；
② **行为模型**：Python 复刻「取回 / 存入」的逐张搬运，跑 N = 1..64，断言总张数守恒、
   库满时留在原处、库内一格一张、旧档同格多张也能逐张取出；
③ **反例自证**：把「整叠写一格」「先清空再写」拼进源码副本 / 行为模型，断言判据真的判负。

用法：python tools/selfcheck_round119_unit_stack_preserve.py
退出码：0 = 全部通过。
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
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
BE = os.path.join(PKG, "block", "entity", "SequencePatternTerminalBlockEntity.java")
PACKET = os.path.join(PKG, "network", "TransferChamberUnitPacket.java")
SCREEN = os.path.join(PKG, "client", "screen", "ChamberUnitsSummaryScreen.java")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

LANG_KEY = "gui.rs_create_compat.sequence_pattern_terminal.summary.pull.blocked"

CHECKS = [0]
COUNTER = [0]
FAILS = []


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS " + name)
    else:
        FAILS.append(name)
        print("FAIL " + name + (("  <- " + detail) if detail else ""))


def counterexample(name, ok):
    COUNTER[0] += 1
    check("反例 " + name, ok)


# ======================================================================================
# 文本工具（与第 116～118 轮同一套）
# ======================================================================================

def strip_comments(text):
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


def brace_body(text, signature):
    """按签名在**去注释后的源码**里定位方法体，返回大括号内的原文（找不到返回 ''）。"""
    idx = text.find(signature)
    if idx < 0:
        return ""
    start = text.find("{", idx + len(signature))
    if start < 0:
        return ""
    depth = 0
    i = start
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
        i += 1
    return ""


# ======================================================================================
# 判据（正例与反例共用同一份，避免「检查写两套」导致反例失真）
# ======================================================================================

def pull_writes_one_item(body):
    """取回：逐张写库。坏写法 = 把整叠 copy() / copyWithCount(whole.getCount()) 写进一格。"""
    return ("final ItemStack stack = whole.copyWithCount(1);" in body
            and "trySetStackInSlot(empty, stack)" in body
            and "trySetStackInSlot(empty, whole" not in body
            and "copyWithCount(whole.getCount())" not in body)


def pull_writes_before_clear(body):
    """取回：先写库、后清执行仓格（顺序反过来 = 中途失败就丢）。"""
    write = body.find("trySetStackInSlot(empty, stack)")
    clear = body.find("chamber.unitSlots.setItem(i, ItemStack.EMPTY);")
    return write >= 0 and clear > write


def pull_shrinks_after_write(body):
    """取回：写入成功后才 shrink(1)。"""
    write = body.find("trySetStackInSlot(empty, stack)")
    shrink = body.find("whole.shrink(1);")
    return write >= 0 and shrink > write


def pull_keeps_library_gates(body):
    """取回：仍然尊重格数上限（firstEmptySlot == -1）与合计体积预算。"""
    return ("final int empty = unitLibrary.firstEmptySlot();" in body
            and "if (empty < 0) {" in body
            and "canAcceptMore(stack)" in body
            and "warnUnitLibraryFull()" in body
            and "warnUnitLibraryFullBytes()" in body)


def push_takes_one_item(body):
    """存入：逐张取；坏写法 = copyWithCount(1) 之后 setStackInSlot(i, EMPTY) 整格清空。"""
    return ("current.copyWithCount(1)" in body
            and "unitLibrary.extractItem(i, 1, false)" in body
            and "unitLibrary.setStackInSlot(i, ItemStack.EMPTY)" not in body)


def push_writes_before_taking(body):
    """存入：先写执行仓、后从库里扣（先清空再写 = F1 根因）。"""
    write = body.find("chamber.unitSlots.setItem(empty, current.copyWithCount(1));")
    take = body.find("unitLibrary.extractItem(i, 1, false)")
    return write >= 0 and take > write


def push_rereads_library_slot(body):
    """存入：每张都重新读库槽（extractItem 会替换 ItemStack 实例，缓存旧引用会死循环 / 漏搬）。"""
    return "final ItemStack current = unitLibrary.getStackInSlot(i);" in body


def packet_reports_blocked(source):
    """可见提示：库满 / 体积预算拒绝时必须告诉玩家（不能只搬了一部分却静默）。"""
    return ("lastUnitTransferBlockedByLibrary()" in source
            and LANG_KEY in source)


def screen_counts_items(source):
    """界面：张数按 sum(getCount())，而不是按格数 units().size()。"""
    return ('Component.translatable(LANG + "units", unitItemCount(entry))' in source
            and "for (final ItemStack unit : entry.units())" in source
            and "unit.getCount()" in source)


# ======================================================================================
# ① 源码锚点（正例）
# ======================================================================================

def group_anchors():
    print("\n=== ① 源码锚点（当前实现必须命中；每条都配反例） ===")
    raw_be = read(BE)
    code_be = strip_comments(raw_be)
    pull = brace_body(code_be, "public int pullUnitsFrom(")
    push = brace_body(code_be, "public int pushUnitsTo(")
    raw_packet = read(PACKET)
    raw_screen = read(SCREEN)

    check("①-1 pullUnitsFrom / pushUnitsTo 两个方法都在（锚点能定位到方法体）",
          bool(pull) and bool(push),
          "pull=%d push=%d" % (len(pull), len(push)))
    check("①-2 取回逐张写库：整叠先 copyWithCount(1) 再写一格（不是整叠写一格）",
          pull_writes_one_item(pull),
          "未见 copyWithCount(1) 的单张写入")
    check("①-3 取回先写库、后清执行仓格（「先确认能写、再写、最后清空」）",
          pull_writes_before_clear(pull),
          "清空发生在写入之前 / 未找到清空")
    check("①-4 取回写入成功后才从执行仓 shrink(1)（中途失败那一张仍在原处）",
          pull_shrinks_after_write(pull),
          "shrink 不在写入之后")
    check("①-5 取回仍守第 116/118 轮两道闸：格数上限 + 合计体积预算，拒绝时停在原地",
          pull_keeps_library_gates(pull),
          "格数 / 体积判据缺失")
    check("①-6 取回不再把整叠写进库（无 copyWithCount(whole.getCount()) / trySet(empty, whole)）",
          "trySetStackInSlot(empty, whole" not in pull
          and "copyWithCount(whole.getCount())" not in pull,
          "仍有整叠写一格")
    check("①-7 存入逐张取：写执行仓 1 张 + 从库 extractItem(i, 1)，不再整格 setStackInSlot(i, EMPTY)",
          push_takes_one_item(push),
          "仍按整格清空 / 未逐张 extract")
    check("①-8 存入先写执行仓、后从库里扣（先清空再写 = F1 丢 N−1 张的根因）",
          push_writes_before_taking(push),
          "扣库发生在写仓之前")
    check("①-9 存入每张重新读库槽（旧档同格多张也能逐张取，能取几张取几张）",
          push_rereads_library_slot(push),
          "循环里缓存了旧 ItemStack 引用")
    check("①-10 存入仍守「仓满则留在库里」：firstEmptyUnitSlot < 0 ⇒ break，不销毁",
          "rscc$firstEmptyUnitSlot(chamber)" in push
          and "if (empty < 0) {" in push
          and "break;" in push,
          "仓满路径缺失")
    check("①-11 存入仍检查配方类型（acceptsUnit），类型不符的一律不动",
          "chamber.acceptsUnit(first)" in push,
          "类型判定缺失")

    # 可见提示：字段 + 置位 + 包处理
    field = "private boolean unitTransferBlockedByLibrary;"
    check("①-12 库满停止的可见提示：终端有「本次因库满停下」的状态位（不持久化）",
          field in code_be,
          "状态字段缺失")
    check("①-13 停止状态只在拒绝分支置 true（不是无条件 / 不是每次都报）",
          "unitTransferBlockedByLibrary = true;" in pull
          and pull.count("unitTransferBlockedByLibrary = true;") == 1,
          "置位点不唯一")
    check("①-14 TransferChamberUnitPacket 读取该状态并给动作栏提示（部分搬运被拒不再静默）",
          packet_reports_blocked(raw_packet),
          "包处理没有可见提示")
    check("①-15 提示在 moved <= 0 之外也生效（部分搬运 = moved > 0 但 blocked）",
          "if (terminal.lastUnitTransferBlockedByLibrary())" in raw_packet
          and raw_packet.find("lastUnitTransferBlockedByLibrary()")
          < raw_packet.find("else if (moved <= 0)"),
          "只处理了「一张都没搬」")

    # 界面张数口径
    check("①-16 汇总界面「张数」按 sum(getCount()) 显示（不再把一格 64 张显示成 1 张）",
          screen_counts_items(raw_screen),
          "仍按 units().size() 计数")
    check("①-17 界面不再用 entry.units().size() 喂「units（%s 张）」语言键",
          'Component.translatable(LANG + "units", entry.units().size())' not in raw_screen,
          "仍按格数显示张数")
    check("①-18 库分页仍按「格」计数（unitLibraryEntries 不许改成按张，否则窗口映射错位）",
          "return Math.max(0, highestUsedIndex(library) + 1);" in
          brace_body(code_be, "public static int unitLibraryEntries("),
          "分页口径被改坏")

    # 语言键 zh/en 成对、1 个占位符、不写死数字
    for lang in ("zh_cn", "en_us"):
        data = json.load(io.open(os.path.join(LANG_DIR, lang + ".json"), encoding="utf-8"))
        text = data.get(LANG_KEY)
        check("①-19 语言键 %s 在 %s 存在" % (LANG_KEY, lang), text is not None, "缺失")
        if text is None:
            continue
        placeholders = re.findall(r"%(\d+\$)?s", text)
        check("①-20 语言键 %s 在 %s 带 1 个 %%s 占位符（实际搬回张数）" % (LANG_KEY, lang),
              len(placeholders) == 1, "占位符 %r" % (placeholders,))
        check("①-21 语言键 %s 在 %s 不写死数字" % (LANG_KEY, lang),
              not re.search(r"\d{2,}", text), "文案写死数字：%r" % text)


# ======================================================================================
# ② 行为模型：逐张搬运，N = 1..64
# ======================================================================================

class Model:
    """复刻取回 / 存入的搬运语义（只关心张数守恒与容量，不关心 NBT / 体积细节）。

    * chamber：list[int]，每格张数（执行仓单元槽允许堆叠）；
    * library：list[int]，每格张数（新语义一格一张，旧档可能 >1）；
    * lib_max_slots：库格数硬上限；
    * item_bytes / byte_budget：合计体积预算（None = 不设预算）。
    """

    def __init__(self, chamber_slots=54, library_slots=108, lib_max_slots=1024,
                 item_bytes=100, byte_budget=None):
        self.chamber = [0] * chamber_slots
        self.library = [0] * library_slots
        self.lib_max_slots = lib_max_slots
        self.item_bytes = item_bytes
        self.byte_budget = byte_budget

    # ---- 库 ----
    def lib_used(self):
        return sum(self.library)

    def lib_bytes(self):
        return self.lib_used() * self.item_bytes

    def first_empty_lib(self):
        for i, count in enumerate(self.library):
            if count == 0:
                return i
        return len(self.library) if len(self.library) < self.lib_max_slots else -1

    def can_accept_one(self):
        return self.byte_budget is None or self.lib_bytes() + self.item_bytes <= self.byte_budget

    def write_one(self, slot):
        """一格一张。slot 越界且不许扩容 ⇒ False（与 first_empty_lib 同源）。"""
        if slot < 0:
            return False
        if slot >= len(self.library):
            if len(self.library) >= self.lib_max_slots:
                return False
            self.library.append(1)
            return True
        self.library[slot] = 1
        return True

    # ---- 仓 ----
    def first_empty_chamber(self):
        for i, count in enumerate(self.chamber):
            if count == 0:
                return i
        return -1

    # ---- 搬运（修好后的语义） ----
    def pull(self, n):
        """把 chamber[0] 的整叠 n 张逐张搬进库；库满 / 预算拒绝就停在原地。"""
        assert self.chamber[0] == n
        moved = 0
        while self.chamber[0] > 0:
            slot = self.first_empty_lib()
            if slot < 0 or not self.can_accept_one():
                break
            if not self.write_one(slot):
                break
            self.chamber[0] -= 1
            moved += 1
        return moved

    def push(self, item_bytes=None):
        """把库里所有样板逐张搬进执行仓；仓满就停在库里。返回搬走的张数。"""
        moved = 0
        for i in range(len(self.library)):
            while self.library[i] > 0:
                empty = self.first_empty_chamber()
                if empty < 0:
                    return moved
                self.chamber[empty] = 1
                self.library[i] -= 1
                moved += 1
        return moved


def total(model):
    return sum(model.chamber) + sum(model.library)


def group_model():
    print("\n=== ② 行为模型：N = 1..64 取回 / 存入，总张数守恒 + 库满留在原处 ===")
    ok_conserve = True
    ok_one_per_slot = True
    detail = ""

    # 2-1 库装得下：N 张全部搬进库，一格一张
    for n in range(1, 65):
        model = Model()
        model.chamber[0] = n
        moved = model.pull(n)
        if total(model) != n or moved != n or model.chamber[0] != 0:
            ok_conserve = False
            detail = "N=%d 总张数 %d moved=%d 仓剩 %d" % (n, total(model), moved, model.chamber[0])
            break
        if any(count not in (0, 1) for count in model.library):
            ok_one_per_slot = False
            detail = "N=%d 库内出现同格多张" % n
            break
    check("②-1 取回 N=1..64：库装得下时全部搬入（张数守恒、执行仓清空）", ok_conserve, detail)
    check("②-2 取回后库内一格一张（不再把整叠写进一格）", ok_one_per_slot, detail)

    # 2-3 库格数不够：能搬几张搬几张，剩下的原样留在执行仓
    detail = ""
    ok_left = True
    for capacity in (1, 7, 16, 53):
        for n in (2, 17, 64):
            model = Model(library_slots=capacity, lib_max_slots=capacity)
            model.chamber[0] = n
            moved = model.pull(n)
            expect_moved = min(n, capacity)
            if total(model) != n or moved != expect_moved or model.chamber[0] != n - expect_moved:
                ok_left = False
                detail = "cap=%d N=%d 总张数 %d moved=%d 仓剩 %d" % (
                    capacity, n, total(model), moved, model.chamber[0])
                break
        if not ok_left:
            break
    check("②-3 库格数不够 N=1..64：只搬得下几张，其余原样留在执行仓（绝不销毁）",
          ok_left, detail)

    # 2-4 体积预算拒绝：同样留在原处
    detail = ""
    ok_budget = True
    for n in (2, 10, 64):
        model = Model(item_bytes=10, byte_budget=100)  # 只放得下 10 张
        model.chamber[0] = n
        moved = model.pull(n)
        expect = min(n, 10)
        if total(model) != n or moved != expect or model.chamber[0] != n - expect:
            ok_budget = False
            detail = "N=%d moved=%d 仓剩 %d" % (n, moved, model.chamber[0])
            break
    check("②-4 体积预算拒绝时同样「能搬几张搬几张」、其余留在原处", ok_budget, detail)

    # 2-5 存入：旧档同格多张（历史数据）逐张取出，绝不整格清空
    detail = ""
    ok_legacy = True
    for legacy_count in (2, 3, 17, 64):
        model = Model()
        model.library[0] = legacy_count  # 旧档：一格叠了 N 张
        moved = model.push()
        expect = min(legacy_count, len(model.chamber))
        if total(model) != legacy_count or moved != expect:
            ok_legacy = False
            detail = "legacy=%d 总张数 %d moved=%d" % (legacy_count, total(model), moved)
            break
        if model.library[0] != legacy_count - expect:
            ok_legacy = False
            detail = "legacy=%d 库剩 %d（期望 %d）" % (
                legacy_count, model.library[0], legacy_count - expect)
            break
        if any(count not in (0, 1) for count in model.chamber):
            ok_legacy = False
            detail = "legacy=%d 执行仓出现同格多张" % legacy_count
            break
    check("②-5 存入旧档同格多张：逐张取出，能取几张取几张，剩下的留在库格（不销毁）",
          ok_legacy, detail)

    # 2-6 完整往返：执行仓 N 张 → 库 → 执行仓，N = 1..64 总张数守恒
    detail = ""
    ok_roundtrip = True
    for n in range(1, 65):
        model = Model()
        model.chamber[0] = n
        model.pull(n)
        model.push()
        if total(model) != n:
            ok_roundtrip = False
            detail = "N=%d 往返后总张数 %d（丢/dupe %d）" % (n, total(model), total(model) - n)
            break
    check("②-6 往返（取回→存入）N=1..64：总张数始终 = N（F1 的核心断言）", ok_roundtrip, detail)

    # 2-7 仓满：库比仓能容纳的多，剩下的必须留在库里
    model = Model()
    model.library[0] = 64
    moved = model.push()
    check("②-7 存入仓满：54 空槽 × 1 张 = 54 张进仓，剩下 10 张留在库里（总数仍 64）",
          moved == len(model.chamber) and total(model) == 64 and sum(model.library) == 64 - moved,
          "moved=%d total=%d 库剩=%d" % (moved, total(model), sum(model.library)))


# ======================================================================================
# ③ 反例自证：坏写法必须被判负
# ======================================================================================

def group_counterexamples():
    print("\n=== ③ 反例自证：坏写法 / 旧实现必须判负 ===")
    raw_be = read(BE)
    code_be = strip_comments(raw_be)
    good_pull = brace_body(code_be, "public int pullUnitsFrom(")
    good_push = brace_body(code_be, "public int pushUnitsTo(")

    # (a) 「整叠写一格」：把单张 copy 改成整叠 copy
    bad_whole = raw_be.replace("final ItemStack stack = whole.copyWithCount(1);",
                               "final ItemStack stack = whole.copy();")
    counterexample("③-1 源码副本：取回改成 whole.copy()（整叠写一格）⇒ ①-2 判据判负",
                   bad_whole != raw_be
                   and not pull_writes_one_item(
                       brace_body(strip_comments(bad_whole), "public int pullUnitsFrom(")))

    # (b) 「先清空再写」：把清空提到写入之前
    old_pull = brace_body(strip_comments(raw_be), "public int pullUnitsFrom(")
    bad_clear_first = old_pull.replace(
        "                final int empty = unitLibrary.firstEmptySlot();",
        "                chamber.unitSlots.setItem(i, ItemStack.EMPTY);\n"
        "                final int empty = unitLibrary.firstEmptySlot();", 1)
    counterexample("③-2 方法体副本：取回改成「先清空执行仓格、再写库」⇒ ①-3 判据判负",
                   bad_clear_first != old_pull
                   and not pull_writes_before_clear(bad_clear_first))

    # (c) 旧实现整体：整叠写库 + 整格清空
    bad_old_pull = (
        "final ItemStack stack = chamber.unitSlots.getItem(i);\n"
        "final int empty = unitLibrary.firstEmptySlot();\n"
        "if (empty < 0) { warnUnitLibraryFull(); break; }\n"
        "if (!unitLibrary.canAcceptMore(stack)) { warnUnitLibraryFullBytes(); break; }\n"
        "if (!unitLibrary.trySetStackInSlot(empty, stack.copy())) { warnUnitLibraryFullBytes(); break; }\n"
        "chamber.unitSlots.setItem(i, ItemStack.EMPTY);\n")
    counterexample("③-3 旧实现方法体：整叠 copy() 写一格 + 整格清空 ⇒ ①-2 / ①-4 判据都判负",
                   not pull_writes_one_item(bad_old_pull)
                   and not pull_shrinks_after_write(bad_old_pull))

    # (d) 旧「存入」：只放回 1 张却整格清空
    bad_old_push = (
        "final ItemStack stack = unitLibrary.getStackInSlot(i);\n"
        "if (stack.isEmpty() || !chamber.acceptsUnit(stack)) { continue; }\n"
        "final int empty = rscc$firstEmptyUnitSlot(chamber);\n"
        "if (empty < 0) { break; }\n"
        "chamber.unitSlots.setItem(empty, stack.copyWithCount(1));\n"
        "unitLibrary.setStackInSlot(i, ItemStack.EMPTY);\n"
        "moved++;\n")
    counterexample("③-4 旧实现「存入」：copyWithCount(1) 后整格 setStackInSlot(i, EMPTY) "
                   "⇒ ①-7 判据判负（正是 F1 丢 N−1 张的写法）",
                   not push_takes_one_item(bad_old_push))

    # (e) 先清空再写（存入方向）：extract 提前到写仓之前
    bad_push_clear_first = good_push.replace(
        "                chamber.unitSlots.setItem(empty, current.copyWithCount(1));\n"
        "                final ItemStack removed = unitLibrary.extractItem(i, 1, false);",
        "                final ItemStack removed = unitLibrary.extractItem(i, 1, false);\n"
        "                chamber.unitSlots.setItem(empty, current.copyWithCount(1));", 1)
    counterexample("③-5 方法体副本：存入先把库格扣掉再写执行仓 ⇒ ①-8 判据判负",
                   bad_push_clear_first != good_push
                   and not push_writes_before_taking(bad_push_clear_first))

    # (f) 界面按格计数（旧口径）
    raw_screen = read(SCREEN)
    bad_screen = raw_screen.replace(
        'Component.translatable(LANG + "units", unitItemCount(entry))',
        'Component.translatable(LANG + "units", entry.units().size())', 1)
    counterexample("③-6 界面副本：张数改回 entry.units().size()（按格计数）⇒ ①-16 判据判负",
                   bad_screen != raw_screen
                   and not screen_counts_items(bad_screen))

    # (g) 去掉可见提示
    raw_packet = read(PACKET)
    bad_packet = raw_packet.replace(
        "if (terminal.lastUnitTransferBlockedByLibrary()) {", "if (false) {", 1)
    counterexample("③-7 包处理副本：去掉 lastUnitTransferBlockedByLibrary 判断 ⇒ ①-14 判据判负",
                   bad_packet != raw_packet
                   and not packet_reports_blocked(bad_packet))

    # (h) 行为模型反例：旧「整叠写一格 + 只放回 1 张」
    class OldBugModel(Model):
        def pull(self, n):
            # 整叠写进库的一格
            slot = self.first_empty_lib()
            if slot < 0:
                return 0
            self.write_one(slot)
            self.library[slot] = n
            self.chamber[0] = 0
            return n

        def push(self, item_bytes=None):
            moved = 0
            for i in range(len(self.library)):
                if self.library[i] <= 0:
                    continue
                empty = self.first_empty_chamber()
                if empty < 0:
                    return moved
                self.chamber[empty] = 1
                self.library[i] = 0  # 整格清空 ⇒ N−1 张消失
                moved += 1
            return moved

    lost = []
    for n in range(2, 65):
        model = OldBugModel()
        model.chamber[0] = n
        model.pull(n)
        model.push()
        if total(model) != n:
            lost.append((n, n - total(model)))
    counterexample("③-8 行为模型：旧实现 N=2..64 往返后总张数 != N（至少 N=2 丢 1 张），"
                   "而 ②-6 的守恒断言会判负",
                   bool(lost) and lost[0] == (2, 1) and lost[-1] == (64, 63))

    # (i) 行为模型反例：先清空再写（预算在写到一半时拒绝）
    class ClearFirstModel(Model):
        def pull(self, n):
            self.chamber[0] = 0  # 先清空
            moved = 0
            while moved < n:
                slot = self.first_empty_lib()
                if slot < 0 or not self.can_accept_one():
                    break
                self.write_one(slot)
                moved += 1
            return moved  # 没写进去的那些已经不在仓里

    model = ClearFirstModel(item_bytes=10, byte_budget=100)
    model.chamber[0] = 64
    model.pull(64)
    counterexample("③-9 行为模型：先清空执行仓、再逐张写库（写到第 10 张被体积预算拒绝）"
                   "⇒ 总张数 < 64（54 张凭空消失），而 ②-4 会判负",
                   total(model) < 64)


def main():
    print("第 119 轮自检：单元样板按张搬运（一叠 N 张存入不许丢 N−1 张）")
    group_anchors()
    group_model()
    group_counterexamples()
    print("\n断言 %d 条（其中反例 %d 条），失败 %d 条" % (CHECKS[0], COUNTER[0], len(FAILS)))
    if FAILS:
        print("FAILED:")
        for name in FAILS:
            print("  - " + name)
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
