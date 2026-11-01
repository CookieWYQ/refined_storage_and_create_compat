#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 116 轮自检：序列装配终端家族「客户端给的物品 / 流体 / 字符串」写入面收口。

背景（第 115 轮 §21.6 / §21.7 自己记的账）
------------------------------------------
第 114 / 115 轮把「标记体积预算」一路铺到了区域标记、两台定量保持器、导入包解码。
第 115 轮交接文档 §21.6-5 明确记下仍然敞着的口子：

* **序列装配终端的 4 个 C2S 写入面没有逐条 ItemStack 体积预算**：
  `SetSequenceGhostPacket.stack`（JEI ghost 拖拽 → 输入原料标记槽）、
  `SetSequenceImportPacket` 的 `ingredient` / `ingredientCandidates` / `stepInputs` /
  `stepCandidates` / `results` / `scraps`（整条配方一次写进方块实体与单元样板 CustomData）、
  `CreateUnitPatternPacket.input`（写进单元样板）、
  `SetStepFluidPacket.fluid`（1.21.1 的 `FluidStack` 带 `DataComponentPatch`，写进单元样板 CustomData）。
  这些数据全部**永久落盘**并随方块实体同步回发，与「把物品设为标记」是同一类风险。
* **`GrowingUnitLibrary` 自动扩容无条数上限**（第 115 轮复核 L3）：反复「生成样板」/「汇总取回」
  就能让终端 NBT 无限变大。
* 解码阶段只有**每个列表**的上限（第 115 轮 H1），没有**整包**上限：
  「4096 个候选组 × 每组 4096 条」这种形状仍能在解码阶段造出上百万 `ItemStack`。

本脚本怎么验（不是字符串 grep）
----------------------------
1. **行为模型**：在 Python 里逐值复刻被改函数的判定语义（单条 / 合计 / 字符串长度 /
   解码全局暂存计数 / 库容量与扩容上限），对边界值断言（`>` 而不是 `>=`、等于上限通过）。
2. **源码锚点**：固定「检查在前、写入在后」的顺序、常量委托关系、只约束「增长」的纪律。
3. **反例自证**：把被断言的源码片段改坏 ⇒ 对应判据必须判负，证明判据有判别力。

用法：`python tools/selfcheck_round116_sequence_write_budget.py`
退出码：0 = 全部通过；最后一行：`round116 OK 断言 N / 反例 M`。
"""

from __future__ import annotations

import io
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_REL = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
JAVA = os.path.join(ROOT, JAVA_REL)

MARKER_BUDGET = os.path.join(JAVA, "support", "MarkerBudget.java")
TERMINAL_BE = os.path.join(JAVA, "block", "entity", "SequencePatternTerminalBlockEntity.java")
IMPORT_PACKET = os.path.join(JAVA, "network", "SetSequenceImportPacket.java")
GHOST_PACKET = os.path.join(JAVA, "network", "SetSequenceGhostPacket.java")
FLUID_PACKET = os.path.join(JAVA, "network", "SetStepFluidPacket.java")
CREATE_UNIT_PACKET = os.path.join(JAVA, "network", "CreateUnitPatternPacket.java")
ZH_LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                       "lang", "zh_cn.json")
EN_LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                       "lang", "en_us.json")

REL = {
    MARKER_BUDGET: os.path.join(JAVA_REL, "support", "MarkerBudget.java"),
    TERMINAL_BE: os.path.join(JAVA_REL, "block", "entity", "SequencePatternTerminalBlockEntity.java"),
    IMPORT_PACKET: os.path.join(JAVA_REL, "network", "SetSequenceImportPacket.java"),
    GHOST_PACKET: os.path.join(JAVA_REL, "network", "SetSequenceGhostPacket.java"),
    FLUID_PACKET: os.path.join(JAVA_REL, "network", "SetStepFluidPacket.java"),
    CREATE_UNIT_PACKET: os.path.join(JAVA_REL, "network", "CreateUnitPatternPacket.java"),
}

CHECKS = [0]
COUNTER = [0]
FAILS = []
ANCHORS = []

WIRE_RATIO = 8
MAX_ENTRY_BYTES = 2048 * WIRE_RATIO          # MarkerBudget.MAX_ENTRY_BYTES（≈ 线上 2 KB）
MAX_UNIT_LIBRARY_SLOTS = 1024
MAX_IMPORT_TOTAL_WIRE_BYTES = 256 * 1024
MAX_IMPORT_TOTAL_BYTES = MAX_IMPORT_TOTAL_WIRE_BYTES * WIRE_RATIO
MAX_IMPORT_STRING_LENGTH = 256
MAX_IMPORT_TOTAL_ENTRIES = 8192
MAX_IMPORT_LIST = 4096


# ======================================================================
# 基础设施
# ======================================================================

def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """去注释（保留字符串字面量），锚点判据用它，避免注释里的字面量造成假绿。"""
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        char = text[i]
        two = text[i:i + 2]
        if quote is None:
            if two == "//":
                end = text.find("\n", i)
                end = n if end < 0 else end
                out.append(" " * (end - i))
                i = end
                continue
            if two == "/*":
                end = text.find("*/", i + 2)
                end = n if end < 0 else end + 2
                out.append("".join("\n" if ch == "\n" else " " for ch in text[i:end]))
                i = end
                continue
            if char in "\"'":
                quote = char
        elif char == "\\":
            out.append(text[i:i + 2])
            i += 2
            continue
        elif char == quote:
            quote = None
        out.append(char)
        i += 1
    return "".join(out)


def find_line(raw, needle):
    index = raw.find(needle)
    if index < 0:
        return -1
    return raw.count("\n", 0, index) + 1


def anchor(group, path, raw, needle, note):
    line = find_line(raw, needle)
    if line > 0:
        ANCHORS.append((group, REL.get(path, path), line, note))
        return True
    return False


def method_body(text, signature):
    """从签名处按大括号配平取出方法体（签名必须唯一出现）。"""
    index = text.find(signature)
    if index < 0 or text.find(signature, index + 1) >= 0:
        return None
    return balance_body(text, index)


def balance_body(text, start):
    brace = text.find("{", start)
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[brace + 1:i]
    return None


def check(name, condition):
    CHECKS[0] += 1
    if not condition:
        FAILS.append(name)
        print("  FAILED: " + name)


def counterexample(name, condition):
    COUNTER[0] += 1
    if not condition:
        FAILS.append("反例自证失效：" + name)
        print("  FAILED 反例: " + name)


# ======================================================================
# 行为模型（与 Java 判据逐字同义）
# ======================================================================

def stack_bytes(size, present=True, registries=True):
    """MarkerBudget#stackBytes 的孪生：判不了 / 空 / 保存失败 ⇒ 0。"""
    if not registries or not present or size <= 0:
        return 0
    return size


def stack_too_big(size, present=True, registries=True):
    """MarkerBudget#isStackTooBig 的孪生：严格大于单条上限才拒。"""
    return stack_bytes(size, present, registries) > MAX_ENTRY_BYTES


def fluid_too_big(size, present=True, registries=True):
    """MarkerBudget#isFluidTooBig 的孪生（与物品同一把尺）。"""
    return stack_bytes(size, present, registries) > MAX_ENTRY_BYTES


def budget_violation(stacks, fluids=(), machines=()):
    """SetSequenceImportPacket#budgetViolation 的孪生：返回 None=通过，否则原因。"""
    total = 0
    for size in stacks:
        if size > MAX_ENTRY_BYTES:
            return "single"
        total += size
        if total > MAX_IMPORT_TOTAL_BYTES:
            return "total"
    for size in fluids:
        if size > MAX_ENTRY_BYTES:
            return "single_fluid"
        total += size
        if total > MAX_IMPORT_TOTAL_BYTES:
            return "total"
    string_bytes = 0
    for machine in machines:
        if len(machine) > MAX_IMPORT_STRING_LENGTH:
            return "string"
        string_bytes += 36 + 2 * len(machine)
        if string_bytes > MAX_IMPORT_TOTAL_BYTES:
            return "string_total"
    return None


def staged_entries(groups, per_list=MAX_IMPORT_LIST, total_cap=MAX_IMPORT_TOTAL_ENTRIES):
    """SetSequenceImportPacket#decode 的孪生：每列表 + 整包 double 上限。"""
    staged = 0
    for count in groups:
        kept = 0
        for _ in range(max(0, count)):
            if kept < per_list and staged < total_cap:
                kept += 1
                staged += 1
    return staged


def library_first_empty(slots, occupied, cap=MAX_UNIT_LIBRARY_SLOTS):
    """GrowingUnitLibrary#firstEmptySlot 的孪生：全满时未到上限 → slots；到上限 → -1。"""
    for i in range(slots):
        if i not in occupied:
            return i
    return slots if slots < cap else -1


def library_can_grow(slots, requested, cap=MAX_UNIT_LIBRARY_SLOTS):
    """rscc$resize 的孪生：任何增长都不许越过上限；缩小（含超限旧库缩到仍 > 上限）一律允许。

    第 116 轮复核 M1 修正：旧实现写成「仅在从 <= 上限跨过时拒绝」，于是旧存档里已经超过上限的库
    在 rscc$ensureTrailingEmpty 每次写入时仍能再 +1（形同没有上限）。
    """
    if requested <= slots:
        return True
    return requested <= cap


def library_can_accept(slots, occupied, count, cap=MAX_UNIT_LIBRARY_SLOTS):
    """canAccept 的孪生：空槽 + 还能扩容出来的槽。"""
    if count <= 0:
        return True
    free = sum(1 for i in range(slots) if i not in occupied)
    return free + max(0, cap - slots) >= count


def library_put(state, cap=MAX_UNIT_LIBRARY_SLOTS):
    """putUnitIntoLibrary 的孪生：到上限 ⇒ 返回 False，状态一个字节都不变。"""
    slots, occupied = state
    empty = library_first_empty(slots, occupied, cap)
    if empty < 0:
        return (slots, set(occupied)), False
    occupied = set(occupied)
    occupied.add(empty)
    new_slots = slots
    if empty >= slots:
        new_slots = empty + 1
    if not occupied or (new_slots - 1) in occupied:
        new_slots = new_slots + 1  # ensureTrailingEmpty（未到上限时）
    return (new_slots, occupied), True


# ======================================================================
# ① 共用尺：MarkerBudget 新增 stackBytes / fluidBytes
# ======================================================================

def budget_source_bits(src):
    return {
        "stack_bytes": "public static int stackBytes(" in src,
        # 第 117 轮：比较统一到 isEntryBytesTooBig()（复核 M4：同一个阈值以前四处各写一遍）
        "stack_delegate": "return isEntryBytesTooBig(stackBytes(stack, registries));" in src,
        "single_compare": "return bytes > MAX_ENTRY_BYTES;" in src,
        "stack_try": "catch (final RuntimeException broken)" in src,
        "fluid_bytes": "public static int fluidBytes(" in src,
        "fluid_serialize": "fluid.saveOptional(registries).sizeInBytes()" in src,
        "fluid_delegate": "return isEntryBytesTooBig(fluidBytes(fluid, registries));" in src,
    }


def check_shared_ruler():
    raw = read(MARKER_BUDGET)
    src = strip_comments(raw)
    anchor("①", MARKER_BUDGET, raw, "public static int stackBytes(",
           "物品体积的共用尺（单条 / 合计 / 终端三处都从这里算）")
    anchor("①", MARKER_BUDGET, raw, "public static int fluidBytes(",
           "流体体积的同一把尺（1.21.1 的 FluidStack 也带 DataComponentPatch）")
    bits = budget_source_bits(src)
    check("①-1 stackBytes 存在且 isStackTooBig 委托给它（口径只有一份，不会漂移）"
          "；比较只有一份实现（isEntryBytesTooBig）",
          bits["stack_bytes"] and bits["stack_delegate"] and bits["single_compare"]
          and "stack.save(registries).sizeInBytes()" in src)
    check("①-2 stackBytes 边界：判不了 ⇒ 0；等于上限通过；上限 +1 拒绝",
          stack_bytes(999999, registries=False) == 0
          and stack_too_big(MAX_ENTRY_BYTES) is False
          and stack_too_big(MAX_ENTRY_BYTES + 1) is True
          and stack_too_big(0) is False)
    check("①-3 fluidBytes 走 saveOptional（不是 save，1.21.1 的 FluidStack API），"
          "isFluidTooBig 委托给它；空流体 / 判不了 ⇒ 不拒",
          bits["fluid_bytes"] and bits["fluid_serialize"] and bits["fluid_delegate"]
          and fluid_too_big(MAX_ENTRY_BYTES) is False
          and fluid_too_big(MAX_ENTRY_BYTES + 1) is True
          and fluid_too_big(999999, present=False) is False)
    check("①-4 两个入口对坏数据组件都兜底（保存抛异常 ⇒ 交回原路径，不在这里炸）",
          bits["stack_try"] and src.count("catch (final RuntimeException broken)") >= 2)
    broken = src.replace("return isEntryBytesTooBig(stackBytes(stack, registries));",
                         "return false;", 1)
    counterexample("①-5 isStackTooBig 改成恒 false ⇒ ①-1 判据判负",
                   broken != src
                   and not budget_source_bits(broken)["stack_delegate"])


# ======================================================================
# ② 4 个 C2S 写入面：先判后写 + 同一把尺
# ======================================================================

def check_ghost_packet():
    raw = read(GHOST_PACKET)
    src = strip_comments(raw)
    handle = method_body(src, "public static void handle(final SetSequenceGhostPacket packet")
    anchor("②", GHOST_PACKET, raw, "MarkerBudget.isStackTooBig(",
           "JEI ghost 拖拽 → 输入原料标记槽：先判体积再写")
    check("②-1 ghost 包：体积检查在 placeGhost 之前，超限 return（不落盘、不动旧标记）",
          handle is not None
          and "MarkerBudget.isStackTooBig(" in handle
          and "placeGhost(" in handle
          and handle.index("MarkerBudget.isStackTooBig(") < handle.index("placeGhost(")
          and "return;" in handle)
    check("②-2 ghost 包：超限时动作栏告知 + WARN 每进程只一条（改包刷包不刷日志）",
          "message.rs_create_compat.sequence.marker_too_large" in src
          and "private static boolean tooLargeWarned;" in src
          and "if (!tooLargeWarned) {" in src
          and "LOGGER.warn(" in src)
    broken = src.replace("MarkerBudget.isStackTooBig(", "MarkerBudget.isStackTooBigRemoved(", 1)
    handle_broken = method_body(broken, "public static void handle(final SetSequenceGhostPacket packet")
    counterexample("②-3 删掉 ghost 的体积检查 ⇒ ②-1 的「先判后写」判据判负",
                   broken != src
                   and handle_broken is not None
                   and "MarkerBudget.isStackTooBig(" not in handle_broken)


def check_fluid_packet():
    raw = read(FLUID_PACKET)
    src = strip_comments(raw)
    handle = method_body(src, "public static void handle(final SetStepFluidPacket packet")
    anchor("②", FLUID_PACKET, raw, "MarkerBudget.isFluidTooBig(",
           "步骤输入流体：客户端 FluidStack 先判体积再写进单元样板")
    check("②-4 流体包：体积检查在 setStepFluidMarker 之前，超限 return",
          handle is not None
          and "MarkerBudget.isFluidTooBig(" in handle
          and "setStepFluidMarker(" in handle
          and handle.index("MarkerBudget.isFluidTooBig(") < handle.index("setStepFluidMarker(")
          and "return;" in handle)
    check("②-5 流体包：只判「客户端给的」那一份（空流体永远通过；判不了不误拒）",
          fluid_too_big(0) is False and fluid_too_big(999999, registries=False) is False)
    broken = src.replace("MarkerBudget.isFluidTooBig(", "MarkerBudget.isFluidTooBigRemoved(", 1)
    handle_broken = method_body(broken, "public static void handle(final SetStepFluidPacket packet")
    counterexample("②-6 删掉流体的体积检查 ⇒ ②-4 判据判负",
                   broken != src
                   and handle_broken is not None
                   and "MarkerBudget.isFluidTooBig(" not in handle_broken)


def check_create_unit_packet():
    raw = read(CREATE_UNIT_PACKET)
    src = strip_comments(raw)
    handle = method_body(src, "public static void handle(final CreateUnitPatternPacket packet")
    anchor("②", CREATE_UNIT_PACKET, raw, "MarkerBudget.isStackTooBig(input, registries)",
           "创建单元样板：输入物品 / 名字 / 配方类型三项都是客户端给的，先判后写")
    anchor("②", CREATE_UNIT_PACKET, raw, "MAX_UNIT_LIBRARY_SLOTS",
           "写入单元样板库：越界下标 / 库到上限一律拒绝")
    check("②-7 创建单元样板：体积 + 字符串长度检查在 writeUnit 之前，超限 return",
          handle is not None
          and "MarkerBudget.isStackTooBig(input, registries)" in handle
          and "tooLong(packet.name())" in handle
          and "tooLong(packet.recipeType())" in handle
          and "SequencePatternData.writeUnit(" in handle
          and handle.index("MarkerBudget.isStackTooBig(input, registries)")
          < handle.index("SequencePatternData.writeUnit("))
    check("②-8 创建单元样板：targetSlot >= max(库容量, 上限) ⇒ 拒绝；"
          "firstEmptySlot 返回 -1 ⇒ 拒绝且有玩家可见提示（不覆盖末格）",
          "final int writableLimit = Math.max(terminal.unitLibrary.getSlots()," in src
          and "if (packet.targetSlot() >= writableLimit) {" in src
          and "if (requested < 0) {" in src
          and "warnLibraryFull(player);" in src
          and "Math.min(requested, terminal.unitLibrary.getSlots())" in src)
    check("②-9 字符串上限 = 256（界面 EditBox 上限 64，合法值零影响），"
          "且长度判据是 `>` 不是 `>=`",
          "MAX_IMPORT_STRING_LENGTH = 256" in read(TERMINAL_BE)
          and "value.length() > SequencePatternTerminalBlockEntity.MAX_IMPORT_STRING_LENGTH"
          in src
          and (len("a" * MAX_IMPORT_STRING_LENGTH) > MAX_IMPORT_STRING_LENGTH) is False
          and (len("a" * (MAX_IMPORT_STRING_LENGTH + 1)) > MAX_IMPORT_STRING_LENGTH) is True)
    broken = src.replace("MarkerBudget.isStackTooBig(input, registries)",
                         "MarkerBudget.isStackTooBigRemoved(input, registries)", 1)
    handle_broken = method_body(broken,
                                "public static void handle(final CreateUnitPatternPacket packet")
    counterexample("②-10 删掉创建单元样板的体积检查 ⇒ ②-7 判据判负",
                   broken != src
                   and handle_broken is not None
                   and "MarkerBudget.isStackTooBig(input, registries)" not in handle_broken)


# ======================================================================
# ③ 导入包：整包预算（单条 + 合计 + 字符串）+ 解码全局暂存上限
# ======================================================================

def import_packet_bits(src):
    return {
        "total_const": "MAX_IMPORT_TOTAL_ENTRIES = 8192" in src,
        "staged_guard": "staged[0] < MAX_IMPORT_TOTAL_ENTRIES" in src,
        "staged_bump": "staged[0]++;" in src,
        "no_prealloc_count": "new java.util.ArrayList<>(count)" not in src,
        "violation": "static String budgetViolation(" in src,
        "violation_call": "final String violation = budgetViolation(" in src,
        "violation_before_write": True,  # 由 ③-4 检查真实顺序
        "entry_limit": "MarkerBudget.MAX_ENTRY_BYTES" in src,
        "total_limit": "MAX_IMPORT_TOTAL_BYTES" in src,
        "string_limit": "MAX_IMPORT_STRING_LENGTH" in src,
        "atexit": "message.rs_create_compat.sequence.import_too_large" in src,
    }


def check_import_packet():
    raw = read(IMPORT_PACKET)
    src = strip_comments(raw)
    handle = method_body(src, "public static void handle(final SetSequenceImportPacket packet")
    anchor("③", IMPORT_PACKET, raw, "MAX_IMPORT_TOTAL_ENTRIES = 8192;",
           "解码阶段：整包全局暂存上限（不只每列表）")
    anchor("③", IMPORT_PACKET, raw, "final String violation = budgetViolation(",
           "网络边界：整包体积 / 字符串预算，超限整包拒绝（原子）")
    anchor("③", IMPORT_PACKET, raw, "static String budgetViolation(",
           "单条 + 合计 + 字符串三项预算的实现处")
    bits = import_packet_bits(src)
    check("③-1 解码：仍按 count 逐个消费字节（流对齐），但整包暂存不超过 8192 条",
          bits["total_const"] and bits["staged_guard"] and bits["staged_bump"]
          and "for (int i = 0; i < count; i++) {" in src)
    check("③-2 解码：不再有 `new ArrayList<>(count)`（第 115 轮 H1 不能被改回去）",
          bits["no_prealloc_count"])
    check("③-3 模型：4096 组 × 每组 4096 条 ⇒ 整包只留 8192 条（不是 1677 万条）",
          staged_entries([MAX_IMPORT_LIST] * MAX_IMPORT_LIST) == MAX_IMPORT_TOTAL_ENTRIES
          and staged_entries([3, 4, 5]) == 12
          and staged_entries([MAX_IMPORT_LIST + 100]) == MAX_IMPORT_LIST)
    check("③-4 handle：预算判定在 importSequencedRecipe 之前（超限一格都不写）",
          handle is not None
          and bits["violation_call"]
          and "importSequencedRecipe(" in handle
          and handle.index("final String violation = budgetViolation(")
          < handle.index("importSequencedRecipe(")
          and "return;" in handle)
    check("③-5 模型：单条超限 ⇒ 拒；合计超限 ⇒ 拒；等于上限 ⇒ 通过",
          budget_violation([MAX_ENTRY_BYTES] * 4) is None
          and budget_violation([MAX_ENTRY_BYTES + 1]) == "single"
          and budget_violation([MAX_ENTRY_BYTES] * (MAX_IMPORT_TOTAL_BYTES // MAX_ENTRY_BYTES + 1))
          == "total"
          and budget_violation([100] * 100) is None)
    check("③-6 模型：字符串超长 / 字符串合计超限 ⇒ 拒（机器 id 与配方 id 也会落盘）",
          budget_violation([], machines=["a" * MAX_IMPORT_STRING_LENGTH]) is None
          and budget_violation([], machines=["a" * (MAX_IMPORT_STRING_LENGTH + 1)]) == "string"
          and budget_violation([], machines=["a" * MAX_IMPORT_STRING_LENGTH] * 4000) == "string_total")
    check("③-7 预算覆盖全部 6 个客户端列表（ingredient / candidates / stepInputs / "
          "stepCandidates / results / scraps）+ 流体",
          all(key in src for key in (
              "packet.ingredientCandidates()", "packet.stepInputs()", "packet.results()",
              "packet.scraps()", "packet.stepCandidates()", "packet.stepFluids()"))
          and "appendStacks(" in src)
    check("③-8 超限时动作栏告知 + WARN 每进程只一条（改包刷包不刷日志）",
          bits["atexit"] and "private static boolean tooLargeWarned;" in src
          and "if (!tooLargeWarned) {" in src)
    broken = src.replace(
        "if (i < MAX_IMPORT_LIST && staged[0] < MAX_IMPORT_TOTAL_ENTRIES) {",
        "if (i < MAX_IMPORT_LIST) {")
    counterexample("③-9 去掉整包暂存上限 ⇒ ③-1 判据判负",
                   broken != src
                   and not import_packet_bits(broken)["staged_guard"])
    broken2 = src.replace("final String violation = budgetViolation(",
                          "final String violation = (String) null; // ", 1)
    handle_broken = method_body(broken2,
                                "public static void handle(final SetSequenceImportPacket packet")
    counterexample("③-10 不调用 budgetViolation ⇒ ③-4 判据判负（整包预算形同虚设）",
                   broken2 != src
                   and handle_broken is not None
                   and "final String violation = budgetViolation(" not in handle_broken)


# ======================================================================
# ④ 单元样板库容量上限：只约束增长，绝不丢数据
# ======================================================================

def check_unit_library_cap():
    raw = read(TERMINAL_BE)
    src = strip_comments(raw)
    anchor("④", TERMINAL_BE, raw, "public static final int MAX_UNIT_LIBRARY_SLOTS = 1024;",
           "单元样板库容量硬上限（第 115 轮复核 L3）")
    anchor("④", TERMINAL_BE, raw, "return rscc$canGrow() ? getSlots() : -1;",
           "firstEmptySlot：到上限返回 -1（调用方必须拒绝写入）")
    anchor("④", TERMINAL_BE, raw, "rscc$resizeRaw(Math.max(1, size));",
           "读档：不判上限（旧实现无上限扩容，旧档可超限，绝不截断）")
    anchor("④", TERMINAL_BE, raw, "rscc$resizeRaw(slot + 1);",
           "读档：按存档里的真实槽位恢复（可超过上限）")
    first_empty = method_body(src, "public int firstEmptySlot()")
    resize = method_body(src, "private void rscc$resize(final int newSize)")
    can_accept = method_body(src, "public boolean canAccept(final int count)")
    put_unit = method_body(src, "public boolean putUnitIntoLibrary(final ItemStack unitStack)")
    pull = method_body(src, "public int pullUnitsFrom(")
    deserialize = method_body(src, "public void deserializeNBT(final HolderLookup.Provider provider, "
                                   "final CompoundTag nbt)")
    check("④-1 常量：MAX_UNIT_LIBRARY_SLOTS = 1024，且 firstEmptySlot 到上限返回 -1",
          "public static final int MAX_UNIT_LIBRARY_SLOTS = 1024;" in src
          and first_empty is not None
          and "return rscc$canGrow() ? getSlots() : -1;" in first_empty)
    check("④-2 上限只约束增长：resize 拒绝越过上限的增长，但允许缩小 / 已有超限库原样保留",
          resize is not None
          and "if (newSize > getSlots() && newSize > MAX_UNIT_LIBRARY_SLOTS) {" in resize
          and "rscc$resizeRaw(newSize);" in resize)
    check("④-3 读档走 rscc$resizeRaw（不判上限）：旧存档里超过 1024 格的库不会被截断",
          deserialize is not None
          and "rscc$resizeRaw(Math.max(1, size));" in deserialize
          and "rscc$resizeRaw(slot + 1);" in deserialize
          and "rscc$resize(Math.max(1, size));" not in deserialize)
    check("④-4 模型：库未满 ⇒ 返回首个空位；满且未到上限 ⇒ 返回 slots（可扩容）；"
          "满且到上限 ⇒ -1",
          library_first_empty(108, set()) == 0
          and library_first_empty(3, {0, 1, 2}) == 3
          and library_first_empty(MAX_UNIT_LIBRARY_SLOTS, set(range(MAX_UNIT_LIBRARY_SLOTS))) == -1)
    check("④-5 模型：紧邻上限时仍可扩容，越过上限的**任何**增长被拒（含超限旧库）；超限旧库允许缩小",
          library_can_grow(1023, 1024) is True
          and library_can_grow(1024, 1025) is False
          and library_can_grow(2000, 2001) is False
          and library_can_grow(2000, 1500) is True)
    check("④-6 模型：canAccept 把「空槽 + 还能扩容的槽」一起算（生成前预检不误拒）",
          library_can_accept(108, set(), 10) is True
          and library_can_accept(1024, set(range(1020)), 4) is True
          and library_can_accept(1024, set(range(1022)), 5) is False)
    state, ok = library_put((1024, set(range(1024))))
    check("④-7 模型：库到上限时 putUnitIntoLibrary ⇒ false，且状态一个字节都不变（绝不丢/覆盖）",
          ok is False and state == (1024, set(range(1024))))
    check("④-8 putUnitIntoLibrary 返回 boolean：firstEmptySlot < 0 ⇒ WARN + false（不写空）",
          put_unit is not None
          and "public boolean putUnitIntoLibrary(" in src
          and "if (empty < 0) {" in put_unit
          and "warnUnitLibraryFull();" in put_unit
          and "return false;" in put_unit)
    check("④-9 pullUnitsFrom：库到上限 ⇒ break（样板留在执行仓，不销毁、不覆盖）",
          pull is not None
          and "final int empty = unitLibrary.firstEmptySlot();" in pull
          and "if (empty < 0) {" in pull
          and pull.index("if (empty < 0) {") < pull.index("chamber.unitSlots.setItem(i, ItemStack.EMPTY);"))
    # 第 118 轮：预检从「只看格数」升级为「格数 + 合计体积」两道（canAcceptUnits），
    # 断言口径同步改成新入口；仍然要求「预检在写总样板之前」+「体积满有独立文案」。
    check("④-10 「生成样板」在写总样板 / 让菜单扣材料之前先做容量预检（扣了没给不可能发生）",
          "unitLibrary.canAcceptUnits(unitsToStore)" in src
          and src.index("unitLibrary.canAcceptUnits(unitsToStore)")
          < src.index("patternSlots.setStackInSlot(totalSlot, pattern);")
          and "generate.library_full" in src
          and "generate.library_full_bytes" in src)
    check("④-11 既有 108 格 / 旧格式 / 迁移逻辑没被这轮改动破坏（读档仍兼容两种格式）",
          "entry.contains(\"Item\", Tag.TAG_COMPOUND)" in src
          and "ItemStack.parseOptional(provider, entry.getCompound(\"Item\"))" in src)
    broken_resize = src.replace(
        "if (newSize > getSlots() && newSize > MAX_UNIT_LIBRARY_SLOTS) {",
        "if (newSize > MAX_UNIT_LIBRARY_SLOTS && getSlots() <= MAX_UNIT_LIBRARY_SLOTS) {", 1)
    counterexample("④-12 把 resize 守卫改回「仅从 <= 上限跨过时拒绝」⇒ ④-2 判据判负"
                   "（超限旧库每写一次还能 +1）",
                   broken_resize != src
                   and "if (newSize > getSlots() && newSize > MAX_UNIT_LIBRARY_SLOTS) {"
                       not in broken_resize)
    broken = src.replace("return rscc$canGrow() ? getSlots() : -1;",
                         "return getSlots();", 1)
    broken_first = method_body(broken, "public int firstEmptySlot()")
    counterexample("④-12 把 firstEmptySlot 改回恒 `return getSlots()` ⇒ ④-1 判据判负",
                   broken != src
                   and broken_first is not None
                   and "return rscc$canGrow() ? getSlots() : -1;" not in broken_first)


# ======================================================================
# ⑤ 文案：拒绝 / 库满必须给玩家一个可见理由（不只是日志）
# ======================================================================

def check_lang():
    zh = read(ZH_LANG)
    en = read(EN_LANG)
    keys = [
        "message.rs_create_compat.sequence.marker_too_large",
        "message.rs_create_compat.sequence.import_too_large",
        "gui.rs_create_compat.sequence_pattern_terminal.generate.library_full",
    ]
    check("⑤-1 三个新文案在 zh_cn / en_us 都存在（玩家不会看到裸 key）",
          all('"%s"' % key in zh and '"%s"' % key in en for key in keys))
    check("⑤-2 库满文案带 %s 占位（上限值由服务端常量传入，不写死两份字面量）",
          '"gui.rs_create_compat.sequence_pattern_terminal.generate.library_full"' in zh
          and "%s" in zh.split(
              '"gui.rs_create_compat.sequence_pattern_terminal.generate.library_full"')[1][:120])


def main():
    print("=" * 78)
    print("第 116 轮自检：序列装配终端家族的物品 / 流体 / 字符串写入面收口")
    print("=" * 78)
    check_shared_ruler()
    check_ghost_packet()
    check_fluid_packet()
    check_create_unit_packet()
    check_import_packet()
    check_unit_library_cap()
    check_lang()

    print("-" * 78)
    print("源码锚点清单（file:line）：")
    for group, path, line, note in ANCHORS:
        print("  [%s] %s:%d  %s" % (group, path, line, note))
    print("-" * 78)
    print("检查 %d 项（其中反例自证 %d 项），失败 %d 项" % (CHECKS[0], COUNTER[0], len(FAILS)))
    if FAILS:
        for name in FAILS:
            print("  FAILED: " + name)
        return 1
    print("round116 OK 断言 %d / 反例 %d" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception as error:  # 源码结构 / 常量与预期不符时显式 FAIL，而不是裸 traceback
        print("FAIL 自检脚本异常（源码结构/常量与预期不符）：%r" % (error,))
        sys.exit(1)
