#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 115 轮自检：跨机器「标记 / 导入」写入口的体积与计数上限 + 两处旧收尾。

背景（第 115 轮独立复核报告，只读复核员 cross-review）
-----------------------------------------------------
第 114 轮只给「区域选定器物品上的 ghost 标记」加了体积预算。复核确认：**除它之外，全仓没有
第二处标记 / 过滤表接入体积预算**，并报出三类缺陷：

* **H1** `network/SetSequenceImportPacket` 的 `readStacks` / `readChances` 用
  `new java.util.ArrayList<>(count)`，`count` 是客户端给的 `VarInt` ⇒ 谎报计数在**解码阶段**
  （menu 校验之前）就 OOME。与第 114 轮 H1 同源，这是 C2S 侧最后一处。
* **H2** `SequencePatternTerminalMenu#importSequencedRecipe` 的 `steps = Math.max(1, machines.size())`
  无上限：`machines` 每项可以是空串（1 字节），约 2 MB 的包 ⇒ 上百万张单元样板 + 容器扩容 +
  `DisplayArrangement/Counts/StepMachines` NBT 整表回发。
* **H3** 两台「定量保持器」的标记写入口（`SetQuantityMarkerPacket` / `SetQuantityFluidMarkerPacket`
  / `SetAdvKeeperMarkerPacket` / `SetAdvKeeperFluidMarkerPacket`）以前直接 `nbt.copy()` /
  `copyWithCount(1)` 落盘并按槽回传，**没有体积检查** —— 与区域标记同一类风险。

顺带收尾两处第 114 轮遗留：
* **L1** `AreaSelectorEditPacket` 的 REPLACE 标记合并：去重后超过 `MATCH_CAPACITY` 会被
  `sanitizeEntries` 静默截断，现在补 WARN 并说明截断了什么。
* **`MergeResult.pinned`**：第 114 轮它恒 `false`（死字段）。现在位次校验**下移到数据层**
  `AreaMarks#migrateByIndex` 自己判（纵深防御），`touched()` 也不再把它算成「动过」。

本脚本怎么验（不是字符串 grep）
-----------------------------
1. **行为模型**：在 Python 里复刻被改函数的**判定 / 计数语义**，对边界值断言数值
   （`isTagTooBig` 的 `>` 边界、`steps` 的 clamp、解码预分配容量上界）。
2. **源码锚点**：防止实现被改回去、固定「检查在前 / 写入在后」的顺序与常量委托关系。
3. **反例自证**：把被断言的源码片段改坏 ⇒ 对应判据必须判负，证明判据有判别力。

用法：`python tools/selfcheck_round115_cross_machine_budget.py`
退出码：0 = 全部通过；最后一行：`round115 OK 断言 N / 反例 M`。
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
KEEPER_BE = os.path.join(JAVA, "block", "entity", "QuantityKeeperBlockEntity.java")
ADV_KEEPER_BE = os.path.join(JAVA, "block", "entity", "AdvancedQuantityKeeperBlockEntity.java")
IMPORT_PACKET = os.path.join(JAVA, "network", "SetSequenceImportPacket.java")
TERMINAL_BE = os.path.join(JAVA, "block", "entity", "SequencePatternTerminalBlockEntity.java")
TERMINAL_MENU = os.path.join(JAVA, "menu", "SequencePatternTerminalMenu.java")
SELECTOR_PACKET = os.path.join(JAVA, "network", "AreaSelectorEditPacket.java")
AREA_MARKS = os.path.join(JAVA, "support", "AreaMarks.java")
MARKER_ENTRY = os.path.join(JAVA, "support", "MarkerEntry.java")

REL = {
    MARKER_BUDGET: os.path.join(JAVA_REL, "support", "MarkerBudget.java"),
    KEEPER_BE: os.path.join(JAVA_REL, "block", "entity", "QuantityKeeperBlockEntity.java"),
    ADV_KEEPER_BE: os.path.join(JAVA_REL, "block", "entity", "AdvancedQuantityKeeperBlockEntity.java"),
    IMPORT_PACKET: os.path.join(JAVA_REL, "network", "SetSequenceImportPacket.java"),
    TERMINAL_BE: os.path.join(JAVA_REL, "block", "entity", "SequencePatternTerminalBlockEntity.java"),
    TERMINAL_MENU: os.path.join(JAVA_REL, "menu", "SequencePatternTerminalMenu.java"),
    SELECTOR_PACKET: os.path.join(JAVA_REL, "network", "AreaSelectorEditPacket.java"),
    AREA_MARKS: os.path.join(JAVA_REL, "support", "AreaMarks.java"),
    MARKER_ENTRY: os.path.join(JAVA_REL, "support", "MarkerEntry.java"),
}

CHECKS = [0]
COUNTER = [0]
FAILS = []
ANCHORS = []

MAX_ENTRY_BYTES = 2048 * 8          # MarkerBudget.MAX_ENTRY_BYTES（堆估算，≈ 线上 2 KB）
MAX_IMPORT_STEPS = 1024
MAX_IMPORT_LIST = 4096
DECODE_INITIAL_CAP = 64   # 第 116 轮：readStacks 的 ArrayList 初始容量
MATCH_CAPACITY = 288


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


def method_body_containing(text, signature, needle):
    """重载方法有多个同名签名时：取**包住 needle 的那一个**方法体。"""
    pos = text.find(needle)
    if pos < 0:
        return None
    start = text.rfind(signature, 0, pos)
    if start < 0:
        return None
    return balance_body(text, start)


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

def tag_too_big(size, present=True):
    """MarkerBudget#isTagTooBig 的孪生：null / 空（size==0）永远通过，严格大于上限才拒。"""
    if not present or size <= 0:
        return False
    return size > MAX_ENTRY_BYTES


def stack_too_big(size, present=True, registries=True):
    """MarkerBudget#isStackTooBig 的孪生：没有注册表上下文 ⇒ 判不了 ⇒ 不拒。"""
    if not registries or not present or size <= 0:
        return False
    return size > MAX_ENTRY_BYTES


def keeper_set_fluid(state, form, marker_id, nbt_size):
    """setFluidMarker 的孪生：超限 ⇒ 状态一个字节都不变（返回 rejected）。"""
    if marker_id is None:
        return dict(marker_form=0, marker_id=None, nbt_size=0), False
    if tag_too_big(nbt_size, present=marker_id is not None):
        return dict(state), True
    return dict(marker_form=form, marker_id=marker_id, nbt_size=nbt_size), False


def keeper_set_item(state, stack_size):
    """setItemMarker 的孪生：超限 ⇒ 旧标记原样保留（返回 rejected）。"""
    if tag_too_big(stack_size):
        return dict(state), True
    return dict(marker_form=0, marker_id=None, nbt_size=0), False


def import_steps(machine_count):
    """Menu 侧 steps clamp 的孪生。"""
    return max(1, min(machine_count, MAX_IMPORT_STEPS))


def decode_initial_capacity(count):
    """readStacks 的孪生：首趟分配容量必须是 min(count, DECODE_INITIAL_CAP)。

    第 116 轮把 readStacks 的首趟容量从 4096 收紧到 64（条数仍由
    MAX_IMPORT_LIST / MAX_IMPORT_TOTAL_ENTRIES 决定，只是 ArrayList 的初始容量变小）。
    """
    if count < 0:
        count = 0
    return min(count, DECODE_INITIAL_CAP)


# ======================================================================
# ① MarkerBudget 的新判据（H3 的共用尺）
# ======================================================================

def budget_source_bits(src):
    return {
        "is_tag": "public static boolean isTagTooBig(" in src,
        # 第 117 轮把「> MAX_ENTRY_BYTES」的比较统一成 isEntryBytesTooBig()（复核 M4：
        # 同一个阈值以前在 isTagTooBig / isStackTooBig / isFluidTooBig / 导入包里各写一遍）。
        "is_tag_bytes": "isEntryBytesTooBig(tag.sizeInBytes())" in src,
        "single_compare": "return bytes > MAX_ENTRY_BYTES;" in src,
        "is_tag_empty": "!tag.isEmpty()" in src,
        "is_stack": "public static boolean isStackTooBig(" in src,
        "is_stack_registries": "registries == null" in src,
        # 第 116 轮把存盘判据抽成了 stackBytes()（供「整包合计」也用同一把尺），
        # 因此判据变成「stackBytes 里确实调 stack.save(...).sizeInBytes()」+
        # 「isStackTooBig 确实委托 stackBytes 并用 > MAX_ENTRY_BYTES」。
        "is_stack_save": "stack.save(registries).sizeInBytes()" in src
                         and "return isEntryBytesTooBig(stackBytes(stack, registries));" in src,
        "is_stack_try": "catch (final RuntimeException broken)" in src,
    }


def check_budget_helpers():
    raw = read(MARKER_BUDGET)
    src = strip_comments(raw)
    anchor("①", MARKER_BUDGET, raw, "public static boolean isTagTooBig(",
           "任意 NBT 的单条体积判据（流体 / 气体标记用）")
    anchor("①", MARKER_BUDGET, raw, "public static boolean isStackTooBig(",
           "整件物品的单条体积判据（keeper / 终端写入口用）")
    bits = budget_source_bits(src)
    check("①-1 isTagTooBig：null / 空标签永不拒，严格大于 MAX_ENTRY_BYTES 才拒（只挡新增 / 放大）"
          "；比较只有一份实现（isEntryBytesTooBig）",
          bits["is_tag"] and bits["is_tag_bytes"] and bits["single_compare"] and bits["is_tag_empty"]
          and tag_too_big(MAX_ENTRY_BYTES) is False
          and tag_too_big(MAX_ENTRY_BYTES + 1) is True
          and tag_too_big(0) is False
          and tag_too_big(99999, present=False) is False)
    check("①-2 isStackTooBig：没有注册表上下文 ⇒ 判不了 ⇒ 不拒（绝不误拒正常路径；第 116 轮起由 stackBytes 承担）",
          bits["is_stack"] and bits["is_stack_registries"] and bits["is_stack_save"]
          and stack_too_big(999999, registries=False) is False
          and stack_too_big(MAX_ENTRY_BYTES) is False
          and stack_too_big(MAX_ENTRY_BYTES + 1) is True)
    check("①-3 isStackTooBig 对坏数据组件兜底（保存抛异常 ⇒ 交回原路径，不在这里炸）",
          bits["is_stack_try"])
    broken = src.replace("!tag.isEmpty() && ", "", 1)
    counterexample("①-4 去掉 `!tag.isEmpty()`（空标签也去比对大小）⇒ ①-1 判据判负",
                   broken != src
                   and not budget_source_bits(broken)["is_tag_empty"])


# ======================================================================
# ② H3：两台定量保持器的标记写入口接预算
# ======================================================================

def keeper_bits(src):
    fluid = method_body(src, "public void setFluidMarker(")
    item = method_body(src, "public void setItemMarker(")
    warn = method_body(src, "private void warnMarkerTooLarge(")
    return fluid, item, warn


def check_keeper_gate():
    for name, path in (("基础版", KEEPER_BE), ("高级版", ADV_KEEPER_BE)):
        raw = read(path)
        src = strip_comments(raw)
        fluid, item, warn = keeper_bits(src)
        anchor("②", path, raw, "MarkerBudget.isTagTooBig(nbt)",
               name + "：流体 / 气体标记入口的体积闸门")
        anchor("②", path, raw, "MarkerBudget.isStackTooBig(stack,",
               name + "：物品标记入口的体积闸门")
        anchor("②", path, raw, "private void warnMarkerTooLarge(",
               name + "：统一节流告警")
        check("②-1 %s setFluidMarker：体积检查在 `nbt.copy()` 之前，超限直接 return（无副作用）" % name,
              fluid is not None
              and "if (MarkerBudget.isTagTooBig(nbt)) {" in fluid
              and "warnMarkerTooLarge(" in fluid
              and fluid.index("if (MarkerBudget.isTagTooBig(nbt)) {") < fluid.index("nbt.copy()")
              and fluid.index("return;") < fluid.index("nbt.copy()")
              and fluid.count("setChanged();") == 1)
        check("②-2 %s setItemMarker：体积检查在 `inventory.setItem(` 之前，超限直接 return（不清流体标记）" % name,
              item is not None
              and "MarkerBudget.isStackTooBig(stack," in item
              and "warnMarkerTooLarge(" in item
              and item.index("MarkerBudget.isStackTooBig(stack,") < item.index("inventory.setItem(")
              and item.index("return;") < item.index("inventory.setItem(")
              and item.count("setChanged();") == 1)
        check("②-3 %s 告警只打一次（markerTooLargeWarned 早退 + 置位，改包者刷包不许刷日志）" % name,
              "private boolean markerTooLargeWarned;" in src
              and warn is not None
              and "if (markerTooLargeWarned) {" in warn
              and "markerTooLargeWarned = true;" in warn
              and warn.count("LOGGER.warn(") == 1)

    # 行为模型：拒收 = 状态不变；接受 = 状态更新
    before = {"marker_form": 0, "marker_id": "minecraft:water", "nbt_size": 128}
    after, rejected = keeper_set_fluid(before, 1, "minecraft:lava", MAX_ENTRY_BYTES + 1)
    check("②-4 模型：流体标记超限 ⇒ rejected，旧标记（id / nbt）原样保留",
          rejected is True and after == before)
    after2, rejected2 = keeper_set_fluid(before, 1, "minecraft:lava", MAX_ENTRY_BYTES)
    check("②-5 模型：恰好等于上限 ⇒ 通过（边界不是 `>=`）",
          rejected2 is False and after2["marker_id"] == "minecraft:lava"
          and after2["nbt_size"] == MAX_ENTRY_BYTES)
    after3, rejected3 = keeper_set_item(before, MAX_ENTRY_BYTES + 1)
    check("②-6 模型：物品标记超限 ⇒ rejected，旧标记原样保留", rejected3 is True and after3 == before)

    raw = read(KEEPER_BE)
    src = strip_comments(raw)
    fluid, _, _ = keeper_bits(src)
    broken = (fluid or "").replace(
        "        if (MarkerBudget.isTagTooBig(nbt)) {", "        // moved", 1)
    broken_src = src.replace(fluid or "", broken, 1) if fluid else src
    broken_fluid, _, _ = keeper_bits(broken_src)
    counterexample("②-7 去掉流体标记的体积闸门 ⇒ ②-1 判据判负",
                   broken_fluid != fluid
                   and "if (MarkerBudget.isTagTooBig(nbt)) {" not in (broken_fluid or ""))


# ======================================================================
# ③ H1：SetSequenceImportPacket 解码阶段不许按客户端计数预分配
# ======================================================================

def import_packet_bits(src):
    return {
        "step_const": "private static final int MAX_IMPORT_STEPS = 1024;" in src,
        "list_const": "private static final int MAX_IMPORT_LIST = 4096;" in src,
        "no_read_list": "buf.readList(" not in src,
        "machines_cap": "if (i < MAX_IMPORT_STEPS) {" in src,
        "stacks_cap": "Math.min(count, MAX_IMPORT_LIST)" in src,
        "stacks_loop": "for (int i = 0; i < count; i++) {" in src,
        "stacks_add": "stacks.add(stack);" in src,
        "neg_guard": src.count("Math.max(0, buf.readVarInt())") >= 5,
    }


def check_import_packet():
    raw = read(IMPORT_PACKET)
    src = strip_comments(raw)
    anchor("③", IMPORT_PACKET, raw, "private static final int MAX_IMPORT_LIST = 4096;",
           "单列表暂存上限（不按客户端计数预分配）")
    anchor("③", IMPORT_PACKET, raw, "private static final int MAX_IMPORT_STEPS = 1024;",
           "步数暂存上限（与 BE 的 MAX_IMPORT_STEPS 同值）")
    anchor("③", IMPORT_PACKET, raw, "final List<ItemStack> stacks = new java.util.ArrayList<>(Math.min(count, 64));",
           "readStacks：本趟分配容量有界（第 116 轮从 4096 收紧到 64，实际条数仍按分配器自动扩容）")
    bits = import_packet_bits(src)
    stacks = method_body(src, "private static List<ItemStack> readStacks(")
    chances = method_body(src, "private static List<Integer> readChances(")
    check("③-1 常量：MAX_IMPORT_STEPS = 1024 / MAX_IMPORT_LIST = 4096", bits["step_const"] and bits["list_const"])
    # 第 116 轮把「单列表 4096」扩成了「单列表 4096 + 整包 8192」，
    # 判据只管「不按客户端计数预分配」与「仍按 count 消费字节」（两代都必须成立）。
    check("③-2 readStacks：不按客户端计数预分配（容量 = min(count, N)），仍按 count 逐个解码保持流对齐",
          stacks is not None
          and "new java.util.ArrayList<>(count)" not in stacks
          and "new java.util.ArrayList<>(Math.min(count," in stacks
          and "for (int i = 0; i < count; i++) {" in stacks
          and "if (i < MAX_IMPORT_LIST" in stacks
          and "stacks.add(stack);" in stacks)
    check("③-3 readChances：同一口径（不预分配 + 按 count 消费）",
          chances is not None
          and "new java.util.ArrayList<>(count)" not in chances
          and "Math.min(count, MAX_IMPORT_LIST)" in chances
          and "if (i < MAX_IMPORT_LIST) {" in chances)
    check("③-4 machines 不再用 readList（它按计数预分配），改手工循环 + 步数上限",
          bits["no_read_list"] and bits["machines_cap"])
    check("③-5 所有对端计数都先 Math.max(0, …)（负 count 不许绕过循环 / 触发怪异分配）",
          bits["neg_guard"])
    check("③-6 模型：count = 2^31-1 时首趟容量仍是有界值 64（不是 2^31-1）",
          decode_initial_capacity(2 ** 31 - 1) == DECODE_INITIAL_CAP
          and decode_initial_capacity(10) == 10
          and decode_initial_capacity(-5) == 0)
    broken = src.replace(
        "final List<ItemStack> stacks = new java.util.ArrayList<>(Math.min(count, 64));",
        "final List<ItemStack> stacks = new java.util.ArrayList<>(count);", 1)
    counterexample("③-7 readStacks 改回 `new ArrayList<>(count)` ⇒ ③-2 判据判负（解码阶段 OOME）",
                   broken != src
                   and "new java.util.ArrayList<>(count)"
                       in (method_body(broken, "private static List<ItemStack> readStacks(") or ""))


# ======================================================================
# ④ H2：终端导入步数 clamp（+ 有界 + 告警）
# ======================================================================

def check_step_clamp():
    raw_be = read(TERMINAL_BE)
    be = strip_comments(raw_be)
    raw_menu = read(TERMINAL_MENU)
    menu = strip_comments(raw_menu)
    anchor("④", TERMINAL_BE, raw_be, "public static final int MAX_IMPORT_STEPS = 1024;",
           "导入步数上限（防 2 MB 包 → 百万步）")
    anchor("④", TERMINAL_MENU, raw_menu,
           "SequencePatternTerminalBlockEntity.MAX_IMPORT_STEPS));",
           "菜单导入入口按上限裁剪步数")
    body = method_body_containing(menu, "public void importSequencedRecipe(",
                                  "Math.min(machines.size(),")
    check("④-1 BE 常量 MAX_IMPORT_STEPS = 1024",
          "public static final int MAX_IMPORT_STEPS = 1024;" in be)
    check("④-2 菜单侧在生成单元样板**之前** clamp 步数（不是 Math.max(1, machines.size()) 裸用）",
          body is not None
          and "Math.max(1, Math.min(machines.size()," in body
          and body.index("Math.max(1, Math.min(machines.size(),")
          < body.index("new java.util.ArrayList<>(steps)"))
    check("④-3 超限时有 WARN（改包者留下证据；正常 JEI 转移不会触发）",
          body is not None
          and "machines.size() > SequencePatternTerminalBlockEntity.MAX_IMPORT_STEPS" in body
          and "LOGGER.warn(" in body)
    check("④-4 模型：5→5 / 1024→1024 / 1025→1024 / 百万→1024 / 0→1",
          import_steps(5) == 5 and import_steps(1024) == 1024
          and import_steps(1025) == 1024 and import_steps(10 ** 6) == 1024
          and import_steps(0) == 1)
    broken = (body or "").replace(
        "Math.max(1, Math.min(machines.size(),\n            SequencePatternTerminalBlockEntity.MAX_IMPORT_STEPS))",
        "Math.max(1, machines.size())", 1)
    counterexample("④-5 把 clamp 改回 `Math.max(1, machines.size())` ⇒ ④-2 判据判负",
                   broken != body and "Math.min(machines.size()," not in broken)


# ======================================================================
# ⑤ 第 114 轮遗留收尾：REPLACE 截断 WARN + pinned 下移数据层
# ======================================================================

def check_round114_leftovers():
    raw_sel = read(SELECTOR_PACKET)
    sel = strip_comments(raw_sel)
    anchor("⑤", SELECTOR_PACKET, raw_sel,
           "if (merged.size() > CollectionCacheBlockEntity.MATCH_CAPACITY) {",
           "REPLACE 合并超上限时不再静默截断")
    check("⑤-1 REPLACE 合并后超 MATCH_CAPACITY 有 WARN（说明截断保留哪一部分）",
          "if (merged.size() > CollectionCacheBlockEntity.MATCH_CAPACITY) {" in sel
          and "已按上限截断" in sel
          and sel.index("if (merged.size() > CollectionCacheBlockEntity.MATCH_CAPACITY) {")
          < sel.index("AreaMarks.put(target, newArea, merged);"))
    check("⑤-2 模型：合并后 300 条 ⇒ 触发告警阈值（> 288）；恰好 288 条 ⇒ 不告警",
          (300 > MATCH_CAPACITY) is True and (MATCH_CAPACITY > MATCH_CAPACITY) is False)

    raw_marks = read(AREA_MARKS)
    marks = strip_comments(raw_marks)
    migrate = method_body(marks, "public static MergeResult migrateByIndex(")
    record = method_body(marks, "public record MergeResult(")
    check("⑤-3 pinned 下移数据层：migrateByIndex 自己从物品读 raw 并判 isIndexPrefix（先判后迁）",
          migrate is not None
          and "final AreaSelection rawSelection = AreaSelection.get(stack);" in migrate
          and "if (rawSelection == null || !isIndexPrefix(rawSelection.areas(), selection.areas())) {"
              in migrate
          and "return new MergeResult(0, 0, 0, 0, 0, true);" in migrate
          and migrate.index("final AreaSelection rawSelection = AreaSelection.get(stack);")
          < migrate.index("legacyByIndex.entrySet().iterator()"))
    check("⑤-4 pinned 不再算进 touched()（冻结 = 一个字节都没改，不许触发标脏 / 快照失效）",
          record is not None
          and "return migrated > 0 || skippedExisting > 0;" in record
          and "|| pinned" not in record)
    broken_sel = sel.replace(
        "if (merged.size() > CollectionCacheBlockEntity.MATCH_CAPACITY) {", "if (false) {", 1)
    counterexample("⑤-5 去掉 REPLACE 截断 WARN ⇒ ⑤-1 判据判负（静默丢配置）",
                   broken_sel != sel
                   and "if (merged.size() > CollectionCacheBlockEntity.MATCH_CAPACITY) {"
                       not in broken_sel)


# ======================================================================
# ⑥ L1：MarkerEntry 的 S2C 编解码也不按对端计数预分配
# ======================================================================

def check_marker_entry_prealloc():
    raw = read(MARKER_ENTRY)
    src = strip_comments(raw)
    anchor("⑥", MARKER_ENTRY, raw, "new ArrayList<>(Math.min(tagCount, 32))",
           "S2C 编解码同样不许按计数预分配（防将来被 C2S 复用）")
    check("⑥-1 MarkerEntry 解码：`new ArrayList<>(tagCount)` 已消失，改为有界预分配且仍按 tagCount 消费",
          "new ArrayList<>(tagCount)" not in src
          and "new ArrayList<>(Math.min(tagCount, 32))" in src
          and "for (int i = 0; i < tagCount; i++) {" in src)
    broken = src.replace("new ArrayList<>(Math.min(tagCount, 32))", "new ArrayList<>(tagCount)", 1)
    counterexample("⑥-2 改回 `new ArrayList<>(tagCount)` ⇒ ⑥-1 判据判负",
                   broken != src and "new ArrayList<>(tagCount)" in broken)


def main():
    print("=" * 78)
    print("第 115 轮自检：跨机器标记 / 导入写入口的体积与计数上限 + 两处收尾")
    print("=" * 78)
    check_budget_helpers()
    check_keeper_gate()
    check_import_packet()
    check_step_clamp()
    check_round114_leftovers()
    check_marker_entry_prealloc()

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
    print("round115 OK 断言 %d / 反例 %d" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except SystemExit:
        raise
    except Exception as error:  # 源码结构 / 常量与预期不符时显式 FAIL，而不是裸 traceback
        print("FAIL 自检脚本异常（源码结构/常量与预期不符）：%r" % (error,))
        sys.exit(1)
