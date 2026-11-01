#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 121 轮自检：序列装配样板库 F2 真修法 —— 有限上游缓冲（RS 绝不再吞输入）。

背景（第 119/120 轮复核 §F2）
------------------------------
RS 2.0.9 的 ``ExternalTaskPattern#acceptsIterationInputs`` 先把本轮输入从任务内部暂存
``extractAll(..., Action.EXECUTE)`` **扣掉**，再调 sink 的 ``accept(EXECUTE)``；只要返回值不是
``ACCEPTED``，RS **只打一条 warn 就当作成功**（源码注释原文：``we lie, do as if the insertion was
successful, and potentially void the extracted resources``）。第 120 轮的「整批 SIMULATE 闸」只堵住
「一条都没插」时的半批落地，堵不住：
* (b) 同一类存储里**不同资源合计**超过剩余容量（各自 SIMULATE 都能过）；
* (c) SIMULATE 与 EXECUTE 之间空间被别的机器抢走。
这两条边仍然是「插一半再 REJECTED」= 后半批被 RS 吞掉。本轮修法：执行器自建**有限上游缓冲**，
``accept(SIMULATE)`` 只判缓冲、``accept(EXECUTE)`` 只写缓冲且**恒 ACCEPTED**，每 tick 由
``doWork()`` 把缓冲 drain 回网络（部分写入留剩余）—— RS 因此永远拿不到非 ACCEPTED。

本脚本三条腿
------------
① **源码锚点**：缓冲形状/容量常量（从源码里解析，不写死）、accept 只写缓冲不直连网络、
   SIMULATE 只判缓冲、EXECUTE 分支恒 ACCEPTED（**唯一 REJECTED 被 ``action == SIMULATE`` 守卫**）、
   doWork 有 drain 且保留剩余、saveAdditional/loadAdditional 有缓冲键、拆方块走既有
   ``BlockContentReleaser.Host`` 结算（不是第二条路径）、注释写的是 RS 真实语义；
② **行为模型**：Python 复刻「聚合 → 整批复演 → EXECUTE 写缓冲 → drain 部分写入留剩余」，
   断言 EXECUTE 永不 REJECTED / 永不 void、守恒（accepted = buffered + drained + spilled）、
   SIMULATE 绝不改状态、同资源聚合、不可堆叠物品的复演精确性、硬上限（无无限增长）；
③ **反例自证**：删除「EXECUTE 恒 ACCEPTED」/ 恢复「直连网络 + 失败 REJECTED」/ 删 drain 剩余扣减 /
   删落盘键 / 空 Host 接线 / 改容量常量 —— 每一条都必须被 ① 的判据判负；旧行为模型必须在
   「不 void」不变量上判负。

用法：python tools/selfcheck_round121_executor_buffer.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import os
import random
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
EXECUTOR = os.path.join(PKG, "block", "entity", "SequenceAssemblyExecutorBlockEntity.java")
EXECUTOR_BLOCK = os.path.join(PKG, "block", "SequenceAssemblyExecutorBlock.java")

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
# 文本工具（与第 116～120 轮同一套）
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


def group1(text, pattern):
    match = re.search(pattern, text)
    return match.group(1) if match else None


# ======================================================================================
# 容量常量：**从源码解析**（不在这里写死第二份），并且值必须落在文档写明的理由上
# ======================================================================================

def parse_capacities(raw):
    item_slots = group1(raw, r"public static final int BUFFER_ITEM_SLOTS\s*=\s*([0-9_]+)\s*;")
    stack_limit = group1(raw, r"public static final int BUFFER_ITEM_STACK_LIMIT\s*=\s*([0-9_]+)\s*;")
    overflow_slots = group1(raw, r"public static final int BUFFER_ITEM_OVERFLOW_SLOTS\s*=\s*([0-9_]+)\s*;")
    fluid_cap = group1(raw, r"public static final long BUFFER_FLUID_CAPACITY_MB\s*=\s*([0-9_]+\s*L\s*\*\s*[0-9_]+\s*L)\s*;")
    fluid_hard = group1(raw, r"public static final long BUFFER_FLUID_HARD_CAPACITY_MB\s*=\s*"
                             r"BUFFER_FLUID_CAPACITY_MB\s*\*\s*([0-9_]+)\s*L\s*;")
    try:
        item_slots = int(item_slots.replace("_", ""))
        stack_limit = int(stack_limit.replace("_", ""))
        overflow_slots = int(overflow_slots.replace("_", ""))
        parts = fluid_cap.replace("L", "").replace("_", "").split("*")
        fluid_cap = int(parts[0].strip()) * int(parts[1].strip())
        fluid_hard = fluid_cap * int(fluid_hard.replace("_", ""))
    except (AttributeError, ValueError, IndexError):
        return None
    return {
        "item_slots": item_slots,
        "stack_limit": stack_limit,
        "overflow_slots": overflow_slots,
        "item_capacity": item_slots * stack_limit,
        "item_hard": (item_slots + overflow_slots) * stack_limit,
        "fluid_cap": fluid_cap,
        "fluid_hard": fluid_hard,
    }


# ======================================================================================
# 判据（正例与反例共用同一份，避免「检查写两套」导致反例失真）
# ======================================================================================

# ---------- accept：只写缓冲、SIMULATE 只判缓冲、EXECUTE 恒 ACCEPTED ----------

def a_aggregates(body):
    """先按 ResourceKey 聚合总量（同批重复出现的同种资源合并）。"""
    return ("final Map<ResourceKey, Long> totals = new LinkedHashMap<>();" in body
            and "totals.merge(resource, count, Long::sum);" in body)


def a_zero_guard(body):
    """amount <= 0 直接跳过；不再有 Math.max(1, …) 凭空 +1。"""
    return "if (count <= 0L) {" in body and "Math.max(1" not in body


def a_no_direct_network(body):
    """EXECUTE 只写缓冲，绝不直连网络（accept 里没有 storage.insert / getNetworkOrNull）。"""
    return "storage.insert(" not in body and "getNetworkOrNull(" not in body and "Action.EXECUTE" not in body


def a_buffer_only(body):
    """批量的唯一落点是「缓冲」：判缓冲 + 写缓冲两个方法都在。"""
    return "canBufferForIteration(totals)" in body and "writeIterationToBuffer(totals)" in body


def a_simulate_accepted_before_write(body):
    """SIMULATE 分支在写缓冲之前返回 ACCEPTED（SIMULATE 绝不落写入）。"""
    sim = body.find("return ExternalPatternSink.Result.ACCEPTED;")
    wr = body.find("writeIterationToBuffer(totals);")
    return 0 <= sim < wr


def a_only_simulate_rejected(body):
    """全方法体只有一个 REJECTED，且被 action == SIMULATE 守卫（EXECUTE 恒 ACCEPTED）。"""
    if body.count("ExternalPatternSink.Result.REJECTED") != 1:
        return False
    return re.search(
        r"if \(action == Action\.SIMULATE\) \{\s*(?://[^\n]*\s*)*return ExternalPatternSink\.Result\.REJECTED;",
        body) is not None


def a_execute_tail_accepted(body):
    """EXECUTE 的落点之后恒返回 ACCEPTED，且那一段没有任何 REJECTED。"""
    idx = body.find("writeIterationToBuffer(totals);")
    if idx < 0:
        return False
    tail = body[idx:]
    return ("return ExternalPatternSink.Result.ACCEPTED;" in tail
            and "REJECTED" not in tail)


def a_skipped_semantics(body):
    """SKIPPED 只在「没有本机适用的资源」时返回一次（不再有 network == null ⇒ SKIPPED）。"""
    return body.count("ExternalPatternSink.Result.SKIPPED") == 1


def a_execute_fallback_drains(body):
    """EXECUTE 兜底（理论竞态）：先就地 drain 一次再写，绝不把资源还给 RS。"""
    return "drainBufferedResources();" in body and body.find("drainBufferedResources();") > body.find("REJECTED")


def a_no_old_best_effort(code):
    """旧的「complete = true / complete ? ACCEPTED : REJECTED」写法已彻底消失。"""
    return "complete = true;" not in code and "complete ? ExternalPatternSink.Result.ACCEPTED" not in code


def a_comment_honest(raw):
    """注释必须写 RS 的真实语义（REJECTED 不重试 / we lie / potentially void）。"""
    return ("potentially void the extracted resources" in raw
            and "we lie" in raw
            and "REJECTED 不会重试" in raw)


# ---------- doWork / drain：每 tick 写回、部分写入留剩余 ----------

def a_do_work_drains(code):
    body = brace_body(code, "public void doWork()")
    return ("drainBufferedResources();" in body
            and "super.doWork();" in body
            and body.find("drainBufferedResources();") < body.find("super.doWork();"))


def a_drain_item_keeps_remainder(code):
    body = brace_body(code, "private boolean drainItemBuffer(")
    return ("storage.insert(" in body
            and "Math.min(inserted, stack.getCount())" in body
            and "copyWithCount((int) remaining)" in body)


def a_drain_fluid_keeps_remainder(code):
    body = brace_body(code, "private boolean drainFluidBuffer(")
    return ("storage.insert(" in body
            and "bufferFluids.extract(entry.id(), entry.nbt(), inserted)" in body)


def a_drain_server_only(code):
    body = brace_body(code, "private void drainBufferedResources()")
    return "level.isClientSide()" in body and "network == null" in body


# ---------- 落盘：saveAdditional / loadAdditional 有缓冲键 ----------

def a_save_buffer(code):
    body = brace_body(code, "public void saveAdditional(final CompoundTag tag, "
                            "final HolderLookup.Provider registries)")
    return ("tag.put(TAG_BUFFER_ITEMS" in body
            and "bufferFluids.save(tag, TAG_BUFFER_FLUIDS)" in body)


def a_load_buffer(code):
    body = brace_body(code, "public void loadAdditional(final CompoundTag tag, "
                            "final HolderLookup.Provider registries)")
    return ("tag.contains(TAG_BUFFER_ITEMS)" in body
            and "bufferFluids.load(tag, TAG_BUFFER_FLUIDS)" in body)


def a_buffer_keys_declared(code):
    return ('private static final String TAG_BUFFER_ITEMS = "BufferItems";' in code
            and 'private static final String TAG_BUFFER_FLUIDS = "BufferFluids";' in code)


# ---------- 有界容量：常量集中定义 + 判定处真的用了它们 ----------

def a_capacity_declared(raw, caps):
    if caps is None:
        return False
    return (caps["item_slots"] == 9
            and caps["stack_limit"] == 64
            and caps["overflow_slots"] == 9
            and caps["item_capacity"] == 576
            and caps["item_hard"] == 1152
            and caps["fluid_cap"] == 64000
            and caps["fluid_hard"] == 128000)


def a_capacity_used(code):
    body = brace_body(code, "private boolean canBufferForIteration(")
    return ("BUFFER_FLUID_CAPACITY_MB" in body
            and "BUFFER_ITEM_CAPACITY" in body
            and "BUFFER_ITEM_SLOTS" in body)


def a_hard_caps_used(code):
    write_body = brace_body(code, "private void writeIterationToBuffer(")
    return ("spillItemsToWorld(" in write_body
            and "spillFluidsToWorld(" in write_body
            and "BUFFER_ITEM_HARD_CAPACITY" in code
            and "BUFFER_FLUID_HARD_CAPACITY_MB" in code)


# ---------- 拆方块结算：走既有 Host（唯一结算路径，不是第二条） ----------

def a_collect_methods(code):
    items_body = brace_body(code, "public void collectBufferedItems(")
    fluids_body = brace_body(code, "public void collectBufferedFluids(")
    return ("slots.set(i, ItemStack.EMPTY);" in items_body
            and "out.add(stack);" in items_body
            and "bufferFluids.clear();" in fluids_body
            and "out.add(new FluidStack(" in fluids_body)


def a_block_host_wired(raw_block):
    code_block = strip_comments(raw_block)
    items_body = brace_body(code_block, "public void collectItems(")
    fluids_body = brace_body(code_block, "public void collectFluids(")
    return ("executor.collectBufferedItems(out);" in items_body
            and "executor.collectBufferedFluids(out);" in fluids_body)


def a_receive_fluids_respects_hard_cap(code):
    """流体余量回填：写回 MultiFluidCache（构造即硬上限 128000 mB），并返回放不下的余量。"""
    body = brace_body(code, "public List<FluidStack> receiveBufferedFluids(")
    return ("new MultiFluidCache(BUFFER_FLUID_HARD_CAPACITY_MB)" in code
            and "bufferFluids.insert(" in body
            and "return rejected;" in body)


def a_block_receive_fluids_wired(raw_block):
    """方块 Host 必须覆写 receiveFluids（默认全拒 ⇒ 装不成桶的气体类流体余量会丢）。"""
    code_block = strip_comments(raw_block)
    body = brace_body(code_block, "public List<FluidStack> receiveFluids(")
    return "executor.receiveBufferedFluids(stacks);" in body


def a_block_stale_comments_gone(raw_block):
    return ("执行器库存全部是样板类内容" not in raw_block
            and "本方块不含流体" not in raw_block)


# ======================================================================================
# 行为模型：Python 复刻 Java 的缓冲 / 复演 / drain
# ======================================================================================

ITEM, FLUID = "item", "fluid"
EXECUTE, SIMULATE = "execute", "simulate"
ACCEPTED, REJECTED, SKIPPED = "accepted", "rejected", "skipped"

# 物品的「自身 maxStack」按资源名给（默认 64；不可堆叠物品 = 1），用来验证复演精确性。
RES_MAX_STACK = {("item", "tool"): 1, ("item", "egg"): 16}


def max_stack(resource):
    return RES_MAX_STACK.get(resource, 64)


class Capacities:
    """从 Java 源码解析出来的容量（避免自检里再写一份会漂移的常量）。"""

    def __init__(self, caps):
        self.item_slots = caps["item_slots"]
        self.stack_limit = caps["stack_limit"]
        self.overflow_slots = caps["overflow_slots"]
        self.item_capacity = caps["item_capacity"]
        self.item_hard = caps["item_hard"]
        self.fluid_cap = caps["fluid_cap"]
        self.fluid_hard = caps["fluid_hard"]


class Net:
    """RS StorageNetworkComponent 的最小模型：物品 / 流体**各自独立**的剩余空间。"""

    def __init__(self, item_free=0, fluid_free=0):
        self.free = {ITEM: item_free, FLUID: fluid_free}
        self.log = []
        self.inserted = {}

    def insert(self, resource, amount, action):
        kind = resource[0]
        inserted = min(amount, max(0, self.free[kind]))
        self.log.append((action, resource, amount, inserted))
        if action == EXECUTE:
            self.free[kind] -= inserted
            self.inserted[resource] = self.inserted.get(resource, 0) + inserted
        return inserted


def insert_items(slots, resource, amount, stack_limit):
    """与 Java 的 insertIntoItemBuffer 逐句对应：先并入同类堆、再占空格；返回余量。"""
    remaining = amount
    for i, stack in enumerate(slots):
        if remaining <= 0:
            break
        if stack is None or stack[0] != resource:
            continue
        room = min(max_stack(resource), stack_limit) - stack[1]
        if room <= 0:
            continue
        moved = min(room, remaining)
        slots[i] = (resource, stack[1] + moved)
        remaining -= moved
    for i, stack in enumerate(slots):
        if remaining <= 0:
            break
        if stack is not None:
            continue
        room = min(max_stack(resource), stack_limit)
        if room <= 0:
            continue
        moved = min(room, remaining)
        slots[i] = (resource, moved)
        remaining -= moved
    return remaining


class Buffer:
    def __init__(self, caps):
        self.caps = caps
        self.slots = [None] * (caps.item_slots + caps.overflow_slots)
        self.fluid = {}
        self.spilled = {}

    def item_units(self):
        return sum(stack[1] for stack in self.slots if stack is not None)

    def fluid_stored(self):
        return sum(self.fluid.values())

    def base_item_units(self):
        return sum(stack[1] for stack in self.slots[:self.caps.item_slots] if stack is not None)

    def snapshot(self):
        return (tuple(self.slots), tuple(sorted(self.fluid.items())), tuple(sorted(self.spilled.items())))


def can_buffer(buf, totals):
    """与 Java 的 canBufferForIteration 逐句对应（只读）。"""
    fluid_total = sum(amount for res, amount in totals.items() if res[0] == FLUID)
    if buf.fluid_stored() + fluid_total > buf.caps.fluid_cap:
        return False
    item_units = sum(amount for res, amount in totals.items() if res[0] == ITEM)
    if item_units > buf.caps.item_capacity:
        return False
    preview = [None if stack is None else (stack[0], stack[1])
               for stack in buf.slots[:buf.caps.item_slots]]
    for res, amount in totals.items():
        if res[0] == ITEM and insert_items(preview, res, amount, buf.caps.stack_limit) > 0:
            return False
    return True


def write_iteration(buf, totals):
    """与 Java 的 writeIterationToBuffer 逐句对应：正常缓冲 → 兜底段 → 余量落地（spilled）。"""
    for res, amount in totals.items():
        if res[0] != ITEM:
            continue
        leftover = insert_items(buf.slots, res, amount, buf.caps.stack_limit)
        if leftover > 0:
            buf.spilled[res] = buf.spilled.get(res, 0) + leftover
    for res, amount in totals.items():
        if res[0] != FLUID:
            continue
        room = max(0, buf.caps.fluid_hard - buf.fluid_stored())
        accepted = min(room, amount)
        if accepted > 0:
            buf.fluid[res] = buf.fluid.get(res, 0) + accepted
        if amount - accepted > 0:
            buf.spilled[res] = buf.spilled.get(res, 0) + (amount - accepted)


def drain(buf, net):
    changed = False
    for i, stack in enumerate(buf.slots):
        if stack is None:
            continue
        inserted = net.insert(stack[0], stack[1], EXECUTE)
        if inserted <= 0:
            continue
        changed = True
        remain = stack[1] - min(inserted, stack[1])
        buf.slots[i] = None if remain <= 0 else (stack[0], remain)
    for res in list(buf.fluid.keys()):
        amount = buf.fluid[res]
        inserted = net.insert(res, amount, EXECUTE)
        if inserted <= 0:
            continue
        changed = True
        if amount - inserted <= 0:
            del buf.fluid[res]
        else:
            buf.fluid[res] = amount - inserted
    return changed


def receive_fluids(buf, stacks):
    """与 Java 的 receiveBufferedFluids 对应：余量先回缓冲（硬上限内），放不下的原样返回。"""
    rejected = []
    for res, amount in stacks:
        if res[0] != FLUID or amount <= 0:
            continue
        room = max(0, buf.caps.fluid_hard - buf.fluid_stored())
        accepted = min(room, amount)
        if accepted > 0:
            buf.fluid[res] = buf.fluid.get(res, 0) + accepted
        if amount - accepted > 0:
            rejected.append((res, amount - accepted))
    return rejected


def accept(buf, net, resources, action):
    """与 Java 的 accept 逐句对应。返回 (result, 就地 drain 次数)。"""
    totals = {}
    for res, amount in resources:
        if res[0] not in (ITEM, FLUID) or amount <= 0:
            continue
        totals[res] = totals.get(res, 0) + amount
    if not totals:
        return SKIPPED, 0
    drains = 0
    if not can_buffer(buf, totals):
        if action == SIMULATE:
            return REJECTED, 0
        drain(buf, net)
        drains = 1
    if action == SIMULATE:
        return ACCEPTED, 0
    write_iteration(buf, totals)
    return ACCEPTED, drains


def accept_old(net, resources, action):
    """旧实现（第 120 轮）：直接插网络，插不满就 REJECTED，且不聚合、Math.max(1, …)。"""
    any_ = False
    for res, amount in resources:
        if res[0] not in (ITEM, FLUID):
            continue
        count = max(1, amount)
        inserted = net.insert(res, count, action)
        if inserted < count:
            return REJECTED
        any_ = True
    return ACCEPTED if any_ else SKIPPED


def voided_after_execute(result, resources, kept):
    """模拟 RS 侧：输入已从任务暂存扣掉；sink 非 ACCEPTED ⇒ 未保留部分被 RS 吞掉。"""
    total = sum(amount for _res, amount in resources)
    if result == ACCEPTED:
        return max(0, total - kept)
    return total - kept


def new_kept(buf, resources):
    """新实现里 RS 的输入有多少被本机保留（缓冲 + 溢出落地）。"""
    totals = {}
    for res, amount in resources:
        if res[0] in (ITEM, FLUID) and amount > 0:
            totals[res] = totals.get(res, 0) + amount
    buffered = 0
    for res, amount in totals.items():
        if res[0] == ITEM:
            buffered += sum(stack[1] for stack in buf.slots if stack and stack[0] == res)
        else:
            buffered += buf.fluid.get(res, 0)
    spilled = sum(buf.spilled.values())
    return buffered + spilled


# ======================================================================================
# ① 源码锚点（正例）
# ======================================================================================

def group_anchors():
    print("\n=== ① 源码锚点（当前实现必须命中；每条都配反例） ===")
    raw_executor = read(EXECUTOR)
    code_executor = strip_comments(raw_executor)
    raw_block = read(EXECUTOR_BLOCK)
    code_block = strip_comments(raw_block)

    accept = brace_body(code_executor, "public ExternalPatternSink.Result accept(")
    check("①-1 能把 accept 方法体定位出来（锚点本身有效）", bool(accept), "accept body 为空")
    check("①-2 先按 ResourceKey 聚合总量（Map + merge(Long::sum)）", a_aggregates(accept), "未见聚合")
    check("①-3 amount<=0 直接跳过、不再 Math.max(1, …) 凭空 +1", a_zero_guard(accept), "0 守卫缺失")
    check("①-4 EXECUTE 只写缓冲、不直连网络（accept 里无 storage.insert / getNetworkOrNull / Action.EXECUTE）",
          a_no_direct_network(accept), "accept 仍在直连网络")
    check("①-5 批量的唯一落点是缓冲（判缓冲 + 写缓冲）", a_buffer_only(accept), "canBuffer/writeBuffer 缺失")
    check("①-6 SIMULATE 分支在写缓冲之前返回 ACCEPTED（SIMULATE 不落写入）",
          a_simulate_accepted_before_write(accept), "ACCEPTED 不在 write 之前")
    check("①-7 全方法体只有 1 个 REJECTED，且被 action == SIMULATE 守卫",
          a_only_simulate_rejected(accept), "REJECTED 数 = %d" % accept.count("ExternalPatternSink.Result.REJECTED"))
    check("①-8 EXECUTE 落点之后恒 ACCEPTED，且该段无 REJECTED（EXECUTE 永不拒绝 = RS 永不吞输入）",
          a_execute_tail_accepted(accept), "EXECUTE 段还有 REJECTED")
    check("①-9 SKIPPED 只在「没有本机适用的资源」时返回一次", a_skipped_semantics(accept),
          "SKIPPED 次数 = %d" % accept.count("ExternalPatternSink.Result.SKIPPED"))
    check("①-10 EXECUTE 兜底先就地 drain 一次（绝不把资源还给 RS）",
          a_execute_fallback_drains(accept), "兜底 drain 缺失")
    check("①-11 旧的「complete ? ACCEPTED : REJECTED」半批落地写法已彻底消失",
          a_no_old_best_effort(code_executor), "旧写法仍在")
    check("①-12 注释写 RS 真实语义（REJECTED 不重试 / we lie / potentially void）",
          a_comment_honest(raw_executor), "注释未写真实语义")

    check("①-13 doWork 里先 drain 再 super.doWork()", a_do_work_drains(code_executor), "drain 不在 doWork 里")
    check("①-14 drainItemBuffer 按 insert 返回值扣减（不丢不双计）", a_drain_item_keeps_remainder(code_executor),
          "物品剩余扣减缺失")
    check("①-15 drainFluidBuffer 按插入量 extract（部分写入留剩余）", a_drain_fluid_keeps_remainder(code_executor),
          "流体剩余扣减缺失")
    check("①-16 drain 只在服务端、且没网络时保留缓冲", a_drain_server_only(code_executor), "drain 守卫缺失")

    check("①-17 缓冲物品键已落盘（saveAdditional）", a_save_buffer(code_executor), "save 缺缓冲键")
    check("①-18 缓冲物品/流体键已读回（loadAdditional）", a_load_buffer(code_executor), "load 缺缓冲键")
    check("①-19 落盘键常量集中声明（BufferItems / BufferFluids）", a_buffer_keys_declared(code_executor),
          "键常量缺失")

    caps = parse_capacities(raw_executor)
    check("①-20 容量常量集中定义且值 = 9 格 × 64 / 64000 mB（兜底段 ×2，总硬上限 1152 件 / 128000 mB）",
          a_capacity_declared(raw_executor, caps), "解析到 %r" % (caps,))
    check("①-21 SIMULATE 判定处真的用了容量常量（不是裸数字）", a_capacity_used(code_executor), "判定未用常量")
    check("①-22 硬上限兜底路径存在（spill 到世界 ≥ 不交还 RS）", a_hard_caps_used(code_executor), "硬上限兜底缺失")

    check("①-23 拆方块结算访问器存在且会清空缓冲（collectBufferedItems / collectBufferedFluids）",
          a_collect_methods(code_executor), "访问器缺失或不清空")
    check("①-24 方块 onRemove 的既有 Host 已接线到缓冲（collectItems / collectFluids）",
          a_block_host_wired(raw_block), "Host 仍是空实现")
    check("①-25 方块侧「库存全是样板 / 本方块不含流体」的旧注释已更新", a_block_stale_comments_gone(raw_block),
          "旧注释仍在（会误导下一个人把缓冲并进 collectPatterns）")
    check("①-26 拆方块流体余量回填：receiveBufferedFluids 写回 MultiFluidCache（硬上限 128000 mB）、"
          "返回放不下的余量", a_receive_fluids_respects_hard_cap(code_executor), "余量回填缺失/无上限")
    check("①-27 方块 Host 覆写 receiveFluids 并转给执行器（否则默认全拒 ⇒ 气体类流体余量丢失）",
          a_block_receive_fluids_wired(raw_block), "Host 未覆写 receiveFluids")


# ======================================================================================
# ② 行为模型
# ======================================================================================

def group_model(caps_dict):
    print("\n=== ② 行为模型：聚合 → 整批复演 → EXECUTE 恒 ACCEPTED → drain 留剩余 ===")
    caps = Capacities(caps_dict)
    A = (ITEM, "iron")
    B = (ITEM, "gold")
    W = (FLUID, "water")
    L = (FLUID, "lava")
    ENERGY = ("energy", "fe")

    # ②-1 EXECUTE：只写缓冲，一次网络调用都没有；返回 ACCEPTED。
    net = Net(item_free=0, fluid_free=0)
    buf = Buffer(caps)
    resources = [(A, 64), (B, 32), (W, 500)]
    result, _d = accept(buf, net, resources, EXECUTE)
    check("②-1 新实现 EXECUTE：恒 ACCEPTED、缓冲收下整批、accept 期间网络零调用",
          result == ACCEPTED and net.log == [] and buf.item_units() == 96 and buf.fluid_stored() == 500,
          "result=%s log=%r items=%d fluid=%d" % (result, net.log, buf.item_units(), buf.fluid_stored()))

    # ②-2 SIMULATE：只判缓冲，不改任何状态。
    net = Net(item_free=64)
    buf = Buffer(caps)
    before = buf.snapshot()
    result, _d = accept(buf, net, [(A, 64)], SIMULATE)
    check("②-2 SIMULATE 放得下 ⇒ ACCEPTED，缓冲与网络状态都不变",
          result == ACCEPTED and buf.snapshot() == before and net.log == [],
          "result=%s" % (result,))

    # ②-3 SIMULATE 放不下（超出正常缓冲）⇒ REJECTED，且不扣任何东西（RS 下一 tick 重试）。
    net = Net(item_free=64)
    buf = Buffer(caps)
    before = buf.snapshot()
    result, _d = accept(buf, net, [(A, caps.item_capacity + 1)], SIMULATE)
    check("②-3 SIMULATE 超出正常缓冲上限 ⇒ REJECTED，缓冲与网络都不变（不 void）",
          result == REJECTED and buf.snapshot() == before and net.log == [],
          "result=%s" % (result,))

    # ②-4 drain 部分写入：网络只吃得下 40 ⇒ 留 24 在缓冲，下一 tick 继续。
    net = Net(item_free=40)
    buf = Buffer(caps)
    accept(buf, net, [(A, 64)], EXECUTE)
    drain(buf, net)
    left_after_first = sum(stack[1] for stack in buf.slots if stack and stack[0] == A)
    net2 = Net(item_free=100)
    drain(buf, net2)
    check("②-4 drain 部分写入留剩余：网络 40 ⇒ 缓冲剩 24；下 tick 网络有空 ⇒ 清空",
          left_after_first == 24 and buf.item_units() == 0 and net2.inserted.get(A) == 24,
          "第一次剩 %d、第二次后缓冲 %d" % (left_after_first, buf.item_units()))

    # ②-5 流体 drain 同样留剩余。
    net = Net(fluid_free=200)
    buf = Buffer(caps)
    accept(buf, net, [(W, 1000)], EXECUTE)
    drain(buf, net)
    check("②-5 流体 drain 部分写入留剩余：网络 200 ⇒ 缓冲剩 800",
          buf.fluid_stored() == 800 and net.inserted.get(W) == 200,
          "buf=%d net=%d" % (buf.fluid_stored(), net.inserted.get(W, 0)))

    # ②-6 EXECUTE 时正常缓冲已满（理论竞态）：先就地 drain，再写；恒 ACCEPTED、无 void、无丢失。
    buf = Buffer(caps)
    fill_net = Net(item_free=100000, fluid_free=0)
    accept(buf, fill_net, [(A, caps.item_capacity)], EXECUTE)   # 灌满正常缓冲
    assert buf.base_item_units() == caps.item_capacity
    net = Net(item_free=caps.item_capacity)                    # drain 能腾空
    result, drains = accept(buf, net, [(B, 64)], EXECUTE)
    check("②-6 正常缓冲已满时 EXECUTE：先就地 drain 再写，恒 ACCEPTED、守恒无丢失",
          result == ACCEPTED and drains == 1
          and net.inserted.get(A) == caps.item_capacity
          and sum(stack[1] for stack in buf.slots if stack and stack[0] == B) == 64,
          "result=%s drains=%d netA=%d" % (result, drains, net.inserted.get(A, 0)))

    # ②-7 竞态 + 网络也满：drain 无效 ⇒ 退到兜底段（不 REJECTED、不丢）。
    buf = Buffer(caps)
    accept(buf, Net(item_free=100000), [(A, caps.item_capacity)], EXECUTE)
    net = Net(item_free=0)
    result, drains = accept(buf, net, [(B, 64)], EXECUTE)
    check("②-7 竞态且网络也满 ⇒ 退兜底段：恒 ACCEPTED、B 全在缓冲、无 spill",
          result == ACCEPTED and drains == 1
          and sum(stack[1] for stack in buf.slots if stack and stack[0] == B) == 64
          and buf.spilled == {},
          "result=%s spilled=%r" % (result, buf.spilled))

    # ②-8 硬上限：正常 + 兜底都满 ⇒ 余量落到世界（spill），仍然 ACCEPTED、无 void。
    buf = Buffer(caps)
    accept(buf, Net(item_free=100000), [(A, caps.item_hard)], EXECUTE)
    result, _d = accept(buf, Net(item_free=0), [(B, 64)], EXECUTE)
    check("②-8 硬上限（%d 件）被击穿 ⇒ 余量 spill 到世界、恒 ACCEPTED（绝不交还 RS）" % caps.item_hard,
          result == ACCEPTED and buf.item_units() == caps.item_hard and buf.spilled.get(B) == 64,
          "result=%s units=%d spilled=%r" % (result, buf.item_units(), buf.spilled))

    # ②-9 同资源聚合：同一批里 A 出现两次 ⇒ 合并成一个总量再判空间（按 64/格拆成 64+16）。
    buf = Buffer(caps)
    result, _d = accept(buf, Net(), [(A, 40), (A, 40)], EXECUTE)
    a_stacks = [stack for stack in buf.slots if stack and stack[0] == A]
    a_total = sum(stack[1] for stack in a_stacks)
    check("②-9 同资源 40+40 聚合：ACCEPTED、缓冲里 A 合计恰好 80（按 64/格拆成 64+16，"
          "不是两条各自 40 各自看到「未变状态」）",
          result == ACCEPTED and a_total == 80 and [stack[1] for stack in a_stacks] == [64, 16],
          "stacks=%r" % (a_stacks,))

    # ②-10 复演精确性（不可堆叠 / 小堆叠物品）：为什么不能用「总量 ≤ 576」估空间。
    #      场景：9 格全空，一批 = 9 件只能堆 1 的工具 + 64 铁。总量 73 ≤ 576 ⇒ 估算法放行；
    #      但 9 件工具把 9 格全占满、铁一件都进不去 ⇒ 真实写入会余 64 件（= 先扣后失败/void）。
    buf = Buffer(caps)
    mixed = {(ITEM, "tool"): caps.item_slots, (ITEM, "iron"): 64}

    def estimate_gate_only_units(buffer, totals):
        units = sum(amount for res, amount in totals.items() if res[0] == ITEM)
        return units <= buffer.caps.item_capacity

    preview_slots = [None] * caps.item_slots
    short = 0
    for res, amount in mixed.items():
        short += insert_items(preview_slots, res, amount, caps.stack_limit)
    check("②-10 复演精确性：估算法（总量 73 ≤ 576）会放行，但复演/真实写入余 %d 件 ⇒ "
          "can_buffer 必须拒绝（不可用「空格 × 64」估空间）" % short,
          estimate_gate_only_units(buf, mixed) and not can_buffer(buf, mixed) and short == 64,
          "estimate=%s can_buffer=%s short=%d" % (estimate_gate_only_units(buf, mixed),
                                                  can_buffer(buf, mixed), short))

    # ②-11 物品 / 流体分桶独立：正常缓冲物品满不影响流体 SIMULATE。
    buf = Buffer(caps)
    accept(buf, Net(item_free=100000), [(A, caps.item_capacity)], EXECUTE)
    result, _d = accept(buf, Net(), [(W, 1000)], SIMULATE)
    check("②-11 物品基段满、流体空 ⇒ 流体 SIMULATE 仍 ACCEPTED（两类各判各的）",
          result == ACCEPTED, "result=%s" % (result,))

    # ②-12 非本机资源 / 0 数量：SKIPPED（不误判 REJECTED 卡死任务）。
    buf = Buffer(caps)
    check("②-12 只有非 Item/Fluid 资源 ⇒ SKIPPED；amount=0 ⇒ SKIPPED（不凭空产 0 件）",
          accept(buf, Net(), [(ENERGY, 1000)], EXECUTE)[0] == SKIPPED
          and accept(buf, Net(), [(A, 0)], EXECUTE)[0] == SKIPPED
          and buf.item_units() == 0,
          "未按 SKIPPED 收口")

    # ②-13 守恒 fuzz：随机批次 + 随机网络空间，EXECUTE 恒 ACCEPTED，accepted = buffer + drained + spilled。
    rng = random.Random(121121)
    conserved = True
    never_rejected = True
    detail = ""
    pool = [(ITEM, "iron"), (ITEM, "tool"), (ITEM, "egg"), (FLUID, "water"), (FLUID, "lava")]
    for _round in range(120):
        buf = Buffer(caps)
        accepted = {}
        drained = {}
        net = Net(item_free=rng.choice([0, 8, 40, 128, 576, 4096]),
                  fluid_free=rng.choice([0, 200, 1000, 64000]))
        for _step in range(12):
            resources = []
            for resource in rng.sample(pool, rng.randint(1, 3)):
                amount = rng.randint(1, 700) if resource[0] == ITEM else rng.randint(1, 5000)
                resources.append((resource, amount))
            result, _d = accept(buf, net, resources, EXECUTE)
            if result != ACCEPTED:
                never_rejected = False
                detail = "EXECUTE 返回 %s" % (result,)
                break
            for res, amount in resources:
                accepted[res] = accepted.get(res, 0) + amount
            # accept 兜底里可能已经就地 drain 过一次：先记账。
            inserted = dict(net.inserted)
            net.inserted = {}
            for res, amount in inserted.items():
                drained[res] = drained.get(res, 0) + amount
            drain(buf, net)
            # 本次 drain 真的写进网络的量也要记账（不记账会把「已入网」误判成「已丢」）。
            inserted = dict(net.inserted)
            net.inserted = {}
            for res, amount in inserted.items():
                drained[res] = drained.get(res, 0) + amount
            # 硬上限不破
            if buf.item_units() > caps.item_hard or buf.fluid_stored() > caps.fluid_hard:
                conserved = False
                detail = "硬上限被破：units=%d fluid=%d" % (buf.item_units(), buf.fluid_stored())
                break
        if not conserved or not never_rejected:
            break
        # 守恒对账：accepted = 缓冲 + 已 drain + spill
        kept = {}
        for stack in buf.slots:
            if stack:
                kept[stack[0]] = kept.get(stack[0], 0) + stack[1]
        for res, amount in buf.fluid.items():
            kept[res] = kept.get(res, 0) + amount
        for res, amount in buf.spilled.items():
            kept[res] = kept.get(res, 0) + amount
        for res, amount in accepted.items():
            got = kept.get(res, 0) + drained.get(res, 0)
            if got != amount:
                conserved = False
                detail = "%r accepted=%d kept=%d drained=%d" % (res, amount, kept.get(res, 0),
                                                               drained.get(res, 0))
                break
    check("②-13 守恒 fuzz（120 轮 × 12 步，随机批次/网络空间/不可堆叠物品）："
          "EXECUTE 恒 ACCEPTED、accepted = 缓冲 + drain + spill、硬上限不破", conserved and never_rejected,
          detail)

    # ②-14 无 void：EXECUTE 恒 ACCEPTED ⇒ RS 侧 void 量恒 0（这才是 F2 的目标）。
    buf = Buffer(caps)
    net = Net(item_free=0)
    resources = [(A, 64), (W, 500)]
    result, _d = accept(buf, net, resources, EXECUTE)
    voided = voided_after_execute(result, resources, new_kept(buf, resources))
    old_net = Net(item_free=64, fluid_free=0)
    old_result = accept_old(old_net, resources, EXECUTE)
    old_kept = sum(old_net.inserted.values())
    old_voided = voided_after_execute(old_result, resources, old_kept)
    check("②-14 无 void：新实现 void=0；旧实现（先插网络、失败 REJECTED）在「物品 64 有空间 / 流体 0」"
          "场景 void=%d" % old_voided,
          voided == 0 and old_voided > 0,
          "new_void=%d old_void=%d" % (voided, old_voided))

    # ②-15 旧行为模型的反证：不聚合的 40+40 在「只剩 64 空间」时旧实现仍 void。
    old_net = Net(item_free=64)
    old_result = accept_old(old_net, [(A, 40), (A, 40)], EXECUTE)
    old_voided = voided_after_execute(old_result, [(A, 40), (A, 40)], sum(old_net.inserted.values()))
    check("②-15 旧模型 40+40 且只剩 64 空间 ⇒ 插进 64、仍 REJECTED（RS 记 80 ⇒ void %d）" % old_voided,
          old_result == REJECTED and old_voided > 0,
          "result=%s void=%d" % (old_result, old_voided))

    # ②-16 流体余量回填：拆方块时 collectFluids 先清空缓冲；网络写不下的余量回缓冲（硬上限内）。
    buf = Buffer(caps)
    buf.fluid[(FLUID, "water")] = 1000
    collected = list(buf.fluid.items())
    buf.fluid.clear()                                   # collectFluids 会清空缓冲
    net = Net(fluid_free=0)                             # 网络写不下任何流体
    rejected = receive_fluids(buf, collected)           # Host.receiveFluids → receiveBufferedFluids
    check("②-16 流体余量回填：网络全满 ⇒ collectFluids 清出的 1000 mB 全部回到缓冲（rejected=0）",
          rejected == [] and buf.fluid_stored() == 1000,
          "rejected=%r stored=%d" % (rejected, buf.fluid_stored()))

    # ②-16b 硬上限：缓冲只剩 200 mB 空间时回填 500 mB ⇒ 收 200、退回 300（守恒、不无界增长）。
    buf2 = Buffer(caps)
    buf2.fluid[(FLUID, "lava")] = caps.fluid_hard - 200
    rejected = receive_fluids(buf2, [((FLUID, "water"), 500)])
    accepted = 500 - sum(amount for _res, amount in rejected)
    check("②-16b 余量回填受硬上限约束：只剩 200 mB 空间 ⇒ 收 200、退回 300（200+300=500 守恒，不无界增长）",
          buf2.fluid_stored() == caps.fluid_hard and accepted == 200
          and sum(amount for _r, amount in rejected) == 300,
          "stored=%d accepted=%d rejected=%r" % (buf2.fluid_stored(), accepted, rejected))


# ======================================================================================
# ③ 反例自证：坏写法 / 旧实现必须被判负
# ======================================================================================

def group_counterexamples(caps_dict):
    print("\n=== ③ 反例自证：坏写法 / 旧实现必须判负 ===")
    raw_executor = read(EXECUTOR)
    code_executor = strip_comments(raw_executor)
    raw_block = read(EXECUTOR_BLOCK)
    accept_body = brace_body(code_executor, "public ExternalPatternSink.Result accept(")

    # ③-1 恢复「直连网络」：accept 里加 storage.insert ⇒ ①-4 判负。
    bad = accept_body.replace("        if (!canBufferForIteration(totals)) {",
                              "        storage.insert(totals.keySet().iterator().next(), 1L, Action.EXECUTE,"
                              " Actor.EMPTY);\n        if (!canBufferForIteration(totals)) {", 1)
    counterexample("③-1 accept 副本：把 storage.insert 加回去（直连网络）⇒ ①-4 判负",
                   bad != accept_body and not a_no_direct_network(bad))

    # ③-2 去掉 SIMULATE 守卫 ⇒ 全方法体 REJECTED 不再被 action 守卫 ⇒ ①-7 判负。
    bad = accept_body.replace("            if (action == Action.SIMULATE) {", "            if (true) {", 1)
    counterexample("③-2 accept 副本：去掉 action == SIMULATE 守卫（EXECUTE 也可能 REJECTED）⇒ ①-7 判负",
                   bad != accept_body and not a_only_simulate_rejected(bad))

    # ③-3 删掉写缓冲 ⇒ EXECUTE 段不再恒 ACCEPTED ⇒ ①-8 判负。
    bad = accept_body.replace("        writeIterationToBuffer(totals);", "", 1)
    counterexample("③-3 accept 副本：删掉 writeIterationToBuffer ⇒ ①-8 判负",
                   bad != accept_body and not a_execute_tail_accepted(bad))

    # ③-4 accept 里重新出现 Action.EXECUTE 的直写 ⇒ ①-4 判负（同一判据的另一半）。
    bad = accept_body + "\n        storage.insert(null, 0L, Action.EXECUTE, Actor.EMPTY);\n"
    counterexample("③-4 accept 副本：方法体末尾再塞一条 Action.EXECUTE 直写 ⇒ ①-4 判负",
                   not a_no_direct_network(bad))

    # ③-5 恢复旧「半批落地」三目写法 ⇒ ①-11 判负。
    bad_code = code_executor.replace("        writeIterationToBuffer(totals);\n        return "
                                     "ExternalPatternSink.Result.ACCEPTED;",
                                     "        boolean complete = true;\n        complete = false;\n"
                                     "        return complete ? ExternalPatternSink.Result.ACCEPTED"
                                     " : ExternalPatternSink.Result.REJECTED;", 1)
    counterexample("③-5 源码副本：恢复 complete ? ACCEPTED : REJECTED（旧半批落地）⇒ ①-11 判负",
                   bad_code != code_executor and not a_no_old_best_effort(bad_code))

    # ③-6 doWork 里删掉 drain ⇒ ①-13 判负（缓冲永远不会写回网络）。
    do_work = brace_body(code_executor, "public void doWork()")
    bad_do_work = do_work.replace("        drainBufferedResources();\n", "", 1)
    bad_code = code_executor.replace(do_work, bad_do_work, 1)
    counterexample("③-6 源码副本：doWork 删掉 drainBufferedResources() ⇒ ①-13 判负",
                   bad_do_work != do_work and not a_do_work_drains(bad_code))

    # ③-7 drain 里删掉「按返回值扣减」⇒ ①-14 判负（要么丢要么双计）。
    drain_body = brace_body(code_executor, "private boolean drainItemBuffer(")
    bad_drain = drain_body.replace("Math.min(inserted, stack.getCount())", "stack.getCount()", 1)
    bad_code = code_executor.replace(drain_body, bad_drain, 1)
    counterexample("③-7 源码副本：drain 里不按 insert 返回值扣减 ⇒ ①-14 判负",
                   bad_drain != drain_body and not a_drain_item_keeps_remainder(bad_code))

    # ③-8 saveAdditional 删掉缓冲键 ⇒ ①-17 判负（重启即丢）。
    save_body = brace_body(code_executor, "public void saveAdditional(final CompoundTag tag, "
                                         "final HolderLookup.Provider registries)")
    bad_save = re.sub(r"tag\.put\(TAG_BUFFER_ITEMS[\s\S]*?bufferFluids\.save\(tag, TAG_BUFFER_FLUIDS\);",
                      "", save_body, count=1)
    bad_code = code_executor.replace(save_body, bad_save, 1)
    counterexample("③-8 源码副本：saveAdditional 删掉缓冲落盘 ⇒ ①-17 判负",
                   bad_save != save_body and not a_save_buffer(bad_code))

    # ③-9 容量常量被改成无界/离谱 ⇒ ①-20 判负。
    bad_raw = raw_executor.replace("public static final int BUFFER_ITEM_SLOTS = 9;",
                                   "public static final int BUFFER_ITEM_SLOTS = 9999;", 1)
    counterexample("③-9 源码副本：BUFFER_ITEM_SLOTS 改成 9999（容量失控）⇒ ①-20 判负",
                   bad_raw != raw_executor and not a_capacity_declared(bad_raw, parse_capacities(bad_raw)))

    bad_raw = raw_executor.replace("BUFFER_FLUID_CAPACITY_MB = 64L * 1000L",
                                   "BUFFER_FLUID_CAPACITY_MB = 64L * 10L", 1)
    counterexample("③-10 源码副本：流体容量改成 640 mB（不足以桥接一次迭代）⇒ ①-20 判负",
                   bad_raw != raw_executor and not a_capacity_declared(bad_raw, parse_capacities(bad_raw)))

    # ③-11 方块 Host 恢复空实现（旧注释）⇒ ①-24 判负（拆方块缓冲蒸发）。
    bad_block = raw_block.replace("executor.collectBufferedItems(out);",
                                  "// 执行器库存全部是样板类内容（见 collectPatterns）", 1)
    counterexample("③-11 方块副本：Host.collectItems 恢复空实现（旧注释）⇒ ①-24 判负",
                   bad_block != raw_block and not a_block_host_wired(bad_block))

    bad_block = raw_block.replace("executor.collectBufferedFluids(out);", "", 1)
    counterexample("③-12 方块副本：Host.collectFluids 删掉接线 ⇒ ①-24 判负",
                   bad_block != raw_block and not a_block_host_wired(bad_block))

    # ③-13 方块侧注释改回「本方块不含流体」⇒ ①-25 判负。
    bad_block = raw_block.replace(
        "现在不空了：上游缓冲里可能停着还没写回网络的流体（本方块仍然没有自己的流体储罐）。",
        "本方块不含流体", 1)
    counterexample("③-13 方块副本：流体注释改回「本方块不含流体」⇒ ①-25 判负",
                   bad_block != raw_block and not a_block_stale_comments_gone(bad_block))

    # ③-14 行为模型：旧实现（直连网络 + 失败 REJECTED）在「不 void」上判负。
    resources = [((ITEM, "iron"), 64), ((FLUID, "water"), 500)]
    old_net = Net(item_free=64, fluid_free=0)
    old_result = accept_old(old_net, resources, EXECUTE)
    old_voided = voided_after_execute(old_result, resources, sum(old_net.inserted.values()))
    counterexample("③-14 行为模型：旧实现在「物品 64 有空间 / 流体 0」场景 void=%d（必须判负）" % old_voided,
                   old_result == REJECTED and old_voided > 0)

    # ③-15 行为模型：作弊实现「缓冲满就 REJECTED」⇒ 无 void 不变量判负（EXECUTE 必须恒 ACCEPTED）。
    def accept_cheat(buf, net, resources, action):
        totals = {}
        for res, amount in resources:
            if res[0] in (ITEM, FLUID) and amount > 0:
                totals[res] = totals.get(res, 0) + amount
        if not can_buffer(buf, totals):
            return REJECTED
        if action == SIMULATE:
            return ACCEPTED
        write_iteration(buf, totals)
        return ACCEPTED

    caps = Capacities(caps_dict)
    cheat_buf = Buffer(caps)
    accept(cheat_buf, Net(item_free=100000), [((ITEM, "iron"), caps.item_capacity)], EXECUTE)
    cheat_resources = [((ITEM, "gold"), 64)]
    cheat_result = accept_cheat(cheat_buf, Net(item_free=0), cheat_resources, EXECUTE)
    cheat_voided = voided_after_execute(cheat_result, cheat_resources, 0)
    counterexample("③-15 行为模型：作弊实现「缓冲满就 REJECTED」会 void=%d ⇒ 「EXECUTE 恒 ACCEPTED」"
                   "判据有区分力" % cheat_voided,
                   cheat_result == REJECTED and cheat_voided > 0)

    # ③-16 行为模型：drain 不按插入量扣减（全扣）⇒ 守恒判负。
    def drain_cheat(buf, net):
        for i, stack in enumerate(buf.slots):
            if stack is None:
                continue
            net.insert(stack[0], stack[1], EXECUTE)
            buf.slots[i] = None  # 不管网络吃没吃下都清空 ⇒ 丢料

    buf = Buffer(caps)
    accept(buf, Net(item_free=100000), [((ITEM, "iron"), 64)], EXECUTE)
    net = Net(item_free=10)
    drain_cheat(buf, net)
    lost = 64 - sum(net.inserted.values())
    counterexample("③-16 行为模型：drain 不按返回值扣减会丢 %d 件 ⇒ ①-14 的守恒判据有区分力" % lost,
                   lost > 0)

    # ③-17 行为模型：Host 不覆写 receiveFluids（默认全拒）⇒ 装不成桶的气体类余量丢失。
    def bucket_lost(rejected):
        bucketable = {(FLUID, "water"), (FLUID, "lava")}
        return sum(amount for res, amount in rejected if res not in bucketable)

    collected = [((FLUID, "gas"), 500)]
    default_rejected = list(collected)              # 默认 receiveFluids 全拒 ⇒ 全部交给按桶兜底
    new_rejected = receive_fluids(Buffer(caps), collected)  # 新接线：先回缓冲
    counterexample("③-17 行为模型：Host 默认全拒时 500 mB 气体余量丢失 %d；新接线丢失 %d ⇒ "
                   "①-27 判据有区分力" % (bucket_lost(default_rejected), bucket_lost(new_rejected)),
                   bucket_lost(default_rejected) > 0 and bucket_lost(new_rejected) == 0)

    # ③-18 源码副本：MultiFluidCache 改成无上限 ⇒ ①-26 判负（不得无界增长）。
    bad_code = code_executor.replace("new MultiFluidCache(BUFFER_FLUID_HARD_CAPACITY_MB)",
                                     "new MultiFluidCache(Long.MAX_VALUE)", 1)
    counterexample("③-18 源码副本：流体缓冲改成无上限 ⇒ ①-26 判负",
                   bad_code != code_executor and not a_receive_fluids_respects_hard_cap(bad_code))

    # ③-19 方块副本：删掉 receiveFluids 覆写里的转交 ⇒ ①-27 判负。
    bad_block = raw_block.replace("return executor.receiveBufferedFluids(stacks);", "", 1)
    counterexample("③-19 方块副本：删掉 receiveFluids 的转交 ⇒ ①-27 判负",
                   bad_block != raw_block and not a_block_receive_fluids_wired(bad_block))


def main():
    print("第 121 轮自检：执行仓上游缓冲（F2 真修法 —— RS 绝不再吞输入）")
    caps = parse_capacities(read(EXECUTOR))
    if caps is None:
        print("FAIL 无法从源码解析缓冲容量常量")
        print("\n断言 0 条（其中反例 0 条），失败 1 条")
        return 1
    group_anchors()
    group_model(caps)
    group_counterexamples(caps)
    print("\n断言 %d 条（其中反例 %d 条），失败 %d 条" % (CHECKS[0], COUNTER[0], len(FAILS)))
    if FAILS:
        print("FAILED:")
        for name in FAILS:
            print("  - " + name)
        return 1
    print("SELFCHECK OK 断言=%d 反例=%d 失败=0" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
