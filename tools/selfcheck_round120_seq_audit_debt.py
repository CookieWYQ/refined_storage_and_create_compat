#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 120 轮自检：序列家族审计债清理（ROUND119_AUDIT_SEQUENCE_CHAMBER §F2 / §F5 / §F6 / §F7 / §F8）。

背景（docs/ROUND119_AUDIT_SEQUENCE_CHAMBER.md）
------------------------------------------------
* **F2**（中）``SequenceAssemblyExecutorBlockEntity#accept`` 逐条 ``storage.insert``，EXECUTE 中途失败
  时前面的已经插进去、不回滚却返回 REJECTED；而 RS 2.0.9 的真实语义（``ExternalTaskPattern``）是
  「REJECTED 不会重试，未插入的部分被 RS 自己吞掉」⇒ 旧注释「网络放不下全部则让任务稍后再试」是假的，
  实际后果是**半批销毁**。
  * 第 121 轮更新：F2 已由「执行仓有限上游缓冲」真修法取代（实现见
    ``tools/selfcheck_round121_executor_buffer.py``）；本脚本 F2 组的判据已随之迁移（accept 只写缓冲、
    EXECUTE 恒 ACCEPTED、网络写入只在 drain 里），**原意「RS 永不吞料」未放宽**；其余组（F5~F8）不变。
* **F5**（低）``SetStepMachinePacket.name`` 无上限直接落盘（每步可达 64KB），服务端本可自己取执行仓显示名。
* **F6**（低）注释与实现不符：执行仓「右键放入 / 取出样板」已不存在；「容量 6」「3×2」也是错的。
* **F7**（低）``dropOutputContents()`` 自称方块破坏结算入口，实际无人调用、且会绕过集群移交。
* **F8**（低）终端 ``saveAdditional`` 把 ``writeCandidates`` 连写两遍。

本脚本三条腿
------------
① **源码锚点**：五条修复的关键片段必须全部命中；每条都配「坏写法」反例，证明锚点真的会判负；
② **行为模型**：Python 复刻 accept 的「聚合 → 全量 SIMULATE → 全过关才 EXECUTE」，断言
   网络只剩 64 件空间的「64 物品 + 500mB 流体」整批被拒且插入量 0（旧实现会先插 64 件再拒）、
   单资源刚好装满、多资源合计刚好装满、amount=0 不产生凭空物品、SIMULATE 不改网络状态；
③ **反例自证**：旧写法 / 旧模型必须被判负（源码副本 + 行为模型各一组）。

用法：python tools/selfcheck_round120_seq_audit_debt.py
退出码：0 = 全部通过。
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
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
EXECUTOR = os.path.join(PKG, "block", "entity", "SequenceAssemblyExecutorBlockEntity.java")
CHAMBER_BE = os.path.join(PKG, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
CHAMBER_BLOCK = os.path.join(PKG, "block", "SequenceExecutionChamberBlock.java")
TERMINAL = os.path.join(PKG, "block", "entity", "SequencePatternTerminalBlockEntity.java")
PACKET = os.path.join(PKG, "network", "SetStepMachinePacket.java")

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
# 文本工具（与第 116～119 轮同一套）
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

# ---------- F2：accept 整批原子化（第 121 轮起由「上游缓冲」取代直连网络实现） ----------

def f2_aggregates(body):
    """先按 resource 聚合总量（同批重复出现的同种资源合并），坏写法 = 逐条各自判定。"""
    return ("final Map<ResourceKey, Long> totals = new LinkedHashMap<>();" in body
            and "totals.merge(resource, count, Long::sum);" in body)


def f2_no_phantom(body):
    """amount <= 0 直接跳过；坏写法 = Math.max(1, …) 凭空 +1。"""
    return ("Math.max(1" not in body
            and "if (count <= 0L) {" in body
            and "continue;" in body)


def f2_accept_writes_buffer_only(body):
    """第121轮判据迁移：网络写入只允许出现在 drain 方法里；accept 内部**不得**有 storage.insert。

    原第120轮判据「先对网络全量 SIMULATE、再 EXECUTE」随实现替换而失效（现在 accept 只判/写
    本机缓冲，网络写入全部收敛到 doWork 的 drain）—— 原意「RS 永不吞料」未放宽，只换了落点。
    """
    return ("storage.insert(" not in body
            and "getNetworkOrNull(" not in body
            and "canBufferForIteration(totals)" in body
            and "writeIterationToBuffer(totals)" in body)


def f2_simulate_branch_accepts(body):
    """SIMULATE 分支：全批判定通过后就返回 ACCEPTED（不再走写入）。"""
    return re.search(r"if \(action == Action\.SIMULATE\) \{\s*return ExternalPatternSink\.Result\.ACCEPTED;",
                     body) is not None


def f2_execute_always_accepted(body):
    """第121轮判据迁移：EXECUTE 落点之后**恒**返回 ACCEPTED，且该段不允许出现 REJECTED。

    第120轮的「写不满也不 break、诚实返回 REJECTED」已作废：REJECTED 不会重试、只会让 RS 吞掉
    未写入部分；现在输入先进本机缓冲，所以 EXECUTE 必须恒 ACCEPTED（原意「RS 永不吞料」未放宽）。
    """
    index = body.find("writeIterationToBuffer(totals);")
    if index < 0:
        return False
    tail = body[index:]
    return ("return ExternalPatternSink.Result.ACCEPTED;" in tail
            and "REJECTED" not in tail)


def f2_skipped_only_no_applicable(body):
    """第121轮判据迁移：SKIPPED 只在「没有本机适用的资源」时返回一次。

    旧实现额外的「network == null ⇒ SKIPPED」已删除（无网络时缓冲同样能安全收下，不必把资源
    还给 RS）；对应地 accept 不再依赖网络（判据见 f2_accept_writes_buffer_only）。
    """
    return (body.count("ExternalPatternSink.Result.SKIPPED") == 1
            and "getNetworkOrNull(" not in body)


def f2_comment_honest(raw):
    """注释必须写 RS 的真实语义（REJECTED 不重试 / 资源被 RS 吞掉），旧的假承诺必须消失。"""
    return ("REJECTED 不会重试" in raw
            and "we lie" in raw
            and "potentially void the extracted resources" in raw
            and "网络放不下全部则让任务稍后再试" not in raw)


# ---------- F5：StepMachine 名字不信任客户端 ----------

def f5_packet_server_name(src):
    """服务端按 pos 取执行仓显示名；客户端 name 只作兜底。"""
    return ("SequencePatternTerminalBlockEntity.ChamberInfo::name" in src
            and "serverName.isEmpty() ? packet.name() : serverName" in src)


def f5_packet_no_trust(src):
    """旧写法（把客户端 name 原样写进 setStepMachine）必须消失。"""
    return "terminal.setStepMachine(packet.stepIndex(), packet.pos(), packet.name())" not in src


def f5_clamp_helper(code):
    """落盘侧有 256 字符上限的收口函数（与 MAX_IMPORT_STRING_LENGTH 同源）。"""
    body = brace_body(code, "static String clampStepMachineName(")
    return ("MAX_IMPORT_STRING_LENGTH" in code
            and bool(body)
            and "codePointCount" in body
            and "offsetByCodePoints" in body)


def f5_set_step_clamps(code):
    """setStepMachine 是 stepMachineNames 的唯一写入口，必须夹紧。"""
    body = brace_body(code, "public void setStepMachine(")
    return ("clampStepMachineName(name)" in body
            and 'stepMachineNames.set(stepIndex, name == null ? "" : name);' not in body)


# ---------- F6：注释与实现一致 + 死代码清除 ----------

def f6_dead_code_gone(code):
    """putUnit / takeUnit 已删除（零调用点的死代码）。"""
    return (" putUnit(" not in code
            and " takeUnit(" not in code
            and "public boolean putUnit" not in code
            and "public boolean takeUnit" not in code)


def f6_chamber_comment_ok(raw):
    """执行仓：54 格 + 「容器本身无类型限制」写清楚，旧「容量 6」谎话消失。"""
    return ("{@value #UNIT_SLOTS} 格" in raw
            and "6 行 × 9 列" in raw
            and "本容器本身不做任何类型限制" in raw
            and "只允许放本模组的单元样板，容量 6。" not in raw)


def f6_block_comment_ok(raw):
    """方块：右键真实行为（无条件开 GUI / 54 格），旧的「无 GUI / 右键放入 / 3×2」谎话消失。"""
    return ("无条件打开管理界面" in raw
            and "6 行 × 9 列 = 54 格单元样板槽" in raw
            and "节点，无 GUI。" not in raw
            and "手持单元样板右键放入样板槽，空手右键取出（见方块实体交互）" not in raw
            and "右键打开界面（3×2 单元样板槽，正常放入/取出）" not in raw)


def f6_use_opens_gui(code):
    """useWithoutItem 的实际行为：无条件 openMenu，且没有任何 putUnit/takeUnit 分支。"""
    body = brace_body(code, "protected InteractionResult useWithoutItem(")
    return ("player.openMenu(" in body
            and "putUnit" not in body
            and "takeUnit" not in body)


# ---------- F7：dropOutputContents 改名 + 禁止调用 ----------

def f7_legacy_named(code):
    return ("public void rscc$legacyDropOutputContents(" in code
            and "public void dropOutputContents(" not in code)


def f7_doc_forbids(raw):
    """醒目 javadoc：历史实现 / 已被 BlockContentReleaser.release 取代 / 禁止调用 / 双重结算。"""
    return ("历史实现" in raw
            and "BlockContentReleaser.release" in raw
            and "禁止调用" in raw
            and "双重结算" in raw)


# ---------- F8：writeCandidates 只写一次 ----------

def f8_single_write(code):
    body = brace_body(code, "public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries)")
    return body.count("SequencePatternData.writeCandidates(tag, ingredientCandidates, registries);") == 1


# ======================================================================================
# ① 源码锚点（正例）
# ======================================================================================

def group_anchors():
    print("\n=== ① 源码锚点（当前实现必须命中；每条都配反例） ===")
    raw_executor = read(EXECUTOR)
    code_executor = strip_comments(raw_executor)
    raw_chamber = read(CHAMBER_BE)
    code_chamber = strip_comments(raw_chamber)
    raw_block = read(CHAMBER_BLOCK)
    code_block = strip_comments(raw_block)
    raw_terminal = read(TERMINAL)
    code_terminal = strip_comments(raw_terminal)
    raw_packet = read(PACKET)

    accept = brace_body(code_executor, "public ExternalPatternSink.Result accept(")
    check("①-1 F2 能把 accept 方法体定位出来（锚点本身有效）", bool(accept), "accept body 为空")
    check("①-2 F2 先按 resource 聚合总量（Map + merge(Long::sum)）", f2_aggregates(accept), "未见聚合")
    # 第121轮：F2 真修法（上游缓冲）取代了第120轮的直连网络实现，判据随之迁移；原意「RS 永不吞料」未放宽。
    check("①-3 F2 amount<=0 直接跳过、不再 Math.max(1, …) 凭空 +1", f2_no_phantom(accept),
          "仍凭空 +1 / 未见 0 守卫")
    # 第121轮：F2 真修法（上游缓冲）取代了第120轮的直连网络实现，判据随之迁移；原意「RS 永不吞料」未放宽。
    check("①-4 F2（已被第121轮上游缓冲取代）accept 内部无 storage.insert / 无网络依赖：网络写入只出现在 drain 方法里",
          f2_accept_writes_buffer_only(accept), "accept 仍在直连网络 / 缓冲落点缺失")
    check("①-5 F2 SIMULATE 分支整批判定通过后返回 ACCEPTED（不落写入）",
          f2_simulate_branch_accepts(accept), "SIMULATE 分支缺失")
    # 第121轮：F2 真修法（上游缓冲）取代了第120轮的直连网络实现，判据随之迁移；原意「RS 永不吞料」未放宽。
    check("①-6 F2（已被第121轮上游缓冲取代）EXECUTE 落点之后恒 return ACCEPTED，该段无 REJECTED",
          f2_execute_always_accepted(accept), "EXECUTE 段仍有 REJECTED")
    # 第121轮：F2 真修法（上游缓冲）取代了第120轮的直连网络实现，判据随之迁移；原意「RS 永不吞料」未放宽。
    check("①-7 F2（已被第121轮上游缓冲取代）SKIPPED 只在「无本机适用资源」时返回一次，accept 不再依赖网络",
          f2_skipped_only_no_applicable(accept),
          "SKIPPED 出现次数 = %d" % accept.count("ExternalPatternSink.Result.SKIPPED"))
    # 第121轮：F2 真修法（上游缓冲）取代了第120轮的直连网络实现，判据随之迁移；原意「RS 永不吞料」未放宽。
    check("①-8 F2 注释写 RS 真实语义（REJECTED 不重试 / 未插入部分被 RS 吞掉），旧假承诺已删",
          f2_comment_honest(raw_executor), "注释仍是假承诺 / 未写 we lie")

    check("①-9 F5 包处理按 pos 取执行仓的服务端显示名，客户端 name 只作兜底",
          f5_packet_server_name(raw_packet), "仍直接用 packet.name()")
    check("①-10 F5 包处理不再把客户端 name 原样交给 setStepMachine",
          f5_packet_no_trust(raw_packet), "仍原样写入")
    check("①-11 F5 落盘侧有 clampStepMachineName（256 字符上限，按码点截断）",
          f5_clamp_helper(code_terminal), "收口函数缺失")
    check("①-12 F5 setStepMachine（唯一写入口）确实调用 clampStepMachineName",
          f5_set_step_clamps(code_terminal), "setStepMachine 未夹紧")

    check("①-13 F6 putUnit / takeUnit 死代码已删除", f6_dead_code_gone(code_chamber), "仍有定义")
    check("①-14 F6 执行仓注释改成 54 格 + 「容器本身无类型限制」", f6_chamber_comment_ok(raw_chamber),
          "旧「容量 6」仍在 / 新说明缺失")
    check("①-15 F6 方块注释改成「无条件开 GUI / 6×9=54 格」，旧「无 GUI / 右键放入 / 3×2」已删",
          f6_block_comment_ok(raw_block), "旧谎话仍在 / 新说明缺失")
    check("①-16 F6 useWithoutItem 确实无条件 openMenu（注释与实现同源）",
          f6_use_opens_gui(code_block), "useWithoutItem 与注释不一致")

    check("①-17 F7 旧名 dropOutputContents 已改成 rscc$legacyDropOutputContents",
          f7_legacy_named(code_chamber), "旧名仍在 / 新名缺失")
    check("①-18 F7 javadoc 醒目写明「历史实现 / 已被 release 取代 / 禁止调用 / 双重结算」",
          f7_doc_forbids(raw_chamber), "警示语缺失")

    check("①-19 F8 saveAdditional 里 writeCandidates 只写一次", f8_single_write(code_terminal),
          "仍写多遍 / 未定位到方法体")


# ======================================================================================
# ② 行为模型：accept 的「聚合 → 全量 SIMULATE → 全过关才 EXECUTE」
# ======================================================================================

ITEM, FLUID = "item", "fluid"
EXECUTE, SIMULATE = "execute", "simulate"
ACCEPTED, REJECTED, SKIPPED = "accepted", "rejected", "skipped"


class Net:
    """RS StorageNetworkComponent 的最小模型：物品 / 流体**各自独立**的剩余空间。

    为什么必须分桶：RS 的物品存储与流体存储是两套存储，往物品存储写东西不占用流体存储的空间
    （复核 §F2 的「两次模拟都通过」只有在两种资源各自都有空间时才成立 —— 见本文档的诚实清单）。
    """

    def __init__(self, item_free=0, fluid_free=0):
        self.free = {ITEM: item_free, FLUID: fluid_free}
        self.log = []  # (action, resource, requested, inserted)

    def insert(self, resource, amount, action):
        kind = resource[0]
        inserted = min(amount, max(0, self.free[kind]))
        self.log.append((action, resource, amount, inserted))
        if action == EXECUTE:
            self.free[kind] -= inserted
        return inserted


def accept_fixed(net, resources, action):
    """修好后的语义（与 Java 实现逐句对应）。返回 (result, 实际插进网络的量)。"""
    totals = {}
    for resource, amount in resources:
        if resource[0] not in (ITEM, FLUID):
            continue
        if amount <= 0:
            continue
        totals[resource] = totals.get(resource, 0) + amount
    if not totals:
        return SKIPPED, 0
    for resource, total in totals.items():
        if net.insert(resource, total, SIMULATE) < total:
            return REJECTED, 0
    if action == SIMULATE:
        return ACCEPTED, 0
    complete = True
    inserted_total = 0
    for resource, total in totals.items():
        inserted = net.insert(resource, total, action)
        inserted_total += inserted
        if inserted < total:
            complete = False
    return (ACCEPTED if complete else REJECTED), inserted_total


def accept_old(net, resources, action):
    """旧实现（F2 原文）：逐条边插边判 + Math.max(1, …)。返回 (result, 实际插入量)。"""
    any_ = False
    inserted_total = 0
    for resource, amount in resources:
        if resource[0] not in (ITEM, FLUID):
            continue
        count = max(1, amount)
        inserted = net.insert(resource, count, action)
        inserted_total += inserted
        if inserted < count:
            return REJECTED, inserted_total
        any_ = True
    return (ACCEPTED if any_ else SKIPPED), inserted_total


def rejected_means_nothing_inserted(result, inserted):
    """「整批被拒 ⇒ 网络里一件都没多」——第 120 轮要守住的不变量。"""
    return result != REJECTED or inserted == 0


def all_simulates_before_executes(net):
    """调用顺序不变量：任何一条 EXECUTE 都必须排在全部 SIMULATE 之后。"""
    seen_execute = False
    for action, _resource, _requested, _inserted in net.log:
        if action == EXECUTE:
            seen_execute = True
        elif action == SIMULATE and seen_execute:
            return False
    return True


def group_model():
    print("\n=== ② 行为模型：聚合 → 全量 SIMULATE → 全过关才 EXECUTE ===")

    A = (ITEM, "minecraft:iron_ingot")
    B = (ITEM, "minecraft:gold_ingot")
    W = (FLUID, "minecraft:water")
    ENERGY = ("energy", "rs_create_compat:fe")

    # ②-1 题目指定的形状：网络只剩 64 件空间（流体存储为 0）⇒「64 物品 + 500mB 流体」整批被拒、插入量 0。
    #     旧实现会把 64 件先插进网络、再因流体放不下而返回 REJECTED（前半批落地 / 后半批被 RS 吞掉）。
    fixed_net = Net(item_free=64, fluid_free=0)
    fixed_res, fixed_ins = accept_fixed(fixed_net, [(A, 64), (W, 500)], EXECUTE)
    old_net = Net(item_free=64, fluid_free=0)
    old_res, old_ins = accept_old(old_net, [(A, 64), (W, 500)], EXECUTE)
    check("②-1 新实现：64 件空间下「64 物品 + 500mB 流体」整批 REJECTED 且插入量 0、空间未被占用",
          fixed_res == REJECTED and fixed_ins == 0 and fixed_net.free[ITEM] == 64,
          "result=%s inserted=%d item_free=%d" % (fixed_res, fixed_ins, fixed_net.free[ITEM]))
    check("②-2 对照旧实现（同场景）：先插 64 件再拒（inserted=64）⇒ 正是要消灭的半批落地",
          old_res == REJECTED and old_ins == 64,
          "result=%s inserted=%d" % (old_res, old_ins))
    check("②-3 不变量「整批被拒 ⇒ 一件都没插」：新实现成立，旧实现不成立（判据本身有区分力）",
          rejected_means_nothing_inserted(fixed_res, fixed_ins)
          and not rejected_means_nothing_inserted(old_res, old_ins),
          "新/旧 = %s/%s" % ((fixed_res, fixed_ins), (old_res, old_ins)))
    check("②-4 顺序不变量：被 SIMULATE 整批拒掉时日志里没有任何 EXECUTE 调用",
          all_simulates_before_executes(fixed_net)
          and all(action == SIMULATE for action, _r, _q, _i in fixed_net.log)
          and len(fixed_net.log) == len({res for res, _a in [(A, 64), (W, 500)]}) + 0,
          "log=%r" % (fixed_net.log,))

    # ②-5 同种资源在批内重复出现 ⇒ 必须按聚合总量判（旧实现每条各自看到「空间还在」而全部放行）。
    fixed_net = Net(item_free=64)
    fixed_res, fixed_ins = accept_fixed(fixed_net, [(A, 40), (A, 40)], EXECUTE)
    old_net = Net(item_free=64)
    old_res, old_ins = accept_old(old_net, [(A, 40), (A, 40)], EXECUTE)
    check("②-5 聚合：同种资源 40+40 而只剩 64 空间 ⇒ 新实现整批 REJECTED、插入 0",
          fixed_res == REJECTED and fixed_ins == 0 and fixed_net.free[ITEM] == 64,
          "result=%s inserted=%d free=%d" % (fixed_res, fixed_ins, fixed_net.free[ITEM]))
    check("②-6 聚合对照：旧实现先插 40、第二条部分插 24 后返回 REJECTED（插入 %d，被 RS 算作 80）"
          % old_ins,
          old_res == REJECTED and old_ins == 64,
          "result=%s inserted=%d" % (old_res, old_ins))

    # ②-7 EXECUTE 阶段写不满时**不 break**：后面的资源（换了一个存储桶）会被抢救下来。
    #     物品侧合计 64+64 > 96（SIMULATE 无法预留容量，因此拦不住），流体侧还有 500mB 空间；
    #     旧实现遇到第二件物品写不满就 return ⇒ 500mB 流体整批被 RS 吞掉。
    fixed_net = Net(item_free=96, fluid_free=500)
    fixed_res, fixed_ins = accept_fixed(fixed_net, [(A, 64), (B, 64), (W, 500)], EXECUTE)
    old_net = Net(item_free=96, fluid_free=500)
    old_res, old_ins = accept_old(old_net, [(A, 64), (B, 64), (W, 500)], EXECUTE)
    check("②-7 EXECUTE 阶段写不满时不中断：新实现抢救后面的 500mB 流体（插入 %d），"
          "旧实现只落物品 %d 件、流体全丢" % (fixed_ins, old_ins),
          fixed_res == REJECTED and fixed_ins == 596
          and old_res == REJECTED and old_ins == 96,
          "new=(%s,%d) old=(%s,%d)" % (fixed_res, fixed_ins, old_res, old_ins))

    # ②-7b 诚实边界：SIMULATE 没有「预留容量」API ⇒ 同一物品存储里**多种不同资源合计**超容时
    #      拦不住（物品侧仍落地 96 件）。这里断言「不劣于旧实现」，并把这条列进文档的未修清单。
    check("②-7b 已知边界：物品侧合计超容时仍落地 96 件（不多不漏、与旧实现相同；"
          "SIMULATE 无法预留容量，需上游缓冲方案才能真正原子）",
          old_net.free[ITEM] == 0 and fixed_net.free[ITEM] == 0,
          "old_free=%d new_free=%d" % (old_net.free[ITEM], fixed_net.free[ITEM]))

    # ②-8 单资源刚好装满：接受、装满、空间归零。
    net = Net(item_free=64)
    res, ins = accept_fixed(net, [(A, 64)], EXECUTE)
    check("②-8 单资源刚好装满：ACCEPTED + 插入 64 + 剩余空间 0",
          res == ACCEPTED and ins == 64 and net.free[ITEM] == 0,
          "result=%s inserted=%d free=%d" % (res, ins, net.free[ITEM]))

    # ②-9 多资源合计刚好装满：两条都接受，空间正好归零。
    net = Net(item_free=128)
    res, ins = accept_fixed(net, [(A, 64), (B, 64)], EXECUTE)
    check("②-9 多资源合计刚好装满：ACCEPTED + 插入 128 + 剩余空间 0",
          res == ACCEPTED and ins == 128 and net.free[ITEM] == 0,
          "result=%s inserted=%d free=%d" % (res, ins, net.free[ITEM]))

    # ②-10 物品 / 流体分桶独立：流体有独立空间时两条都放得下（证明模型不是「共享空间」的假模型）。
    net = Net(item_free=64, fluid_free=500)
    res, ins = accept_fixed(net, [(A, 64), (W, 500)], EXECUTE)
    check("②-10 分桶独立：物品 64 + 流体 500mB 各有空间 ⇒ ACCEPTED + 插入 564",
          res == ACCEPTED and ins == 564,
          "result=%s inserted=%d" % (res, ins))

    # ②-11 amount == 0：不产生凭空物品（这里是旧实现 Math.max(1, …) 的真 bug）。
    net = Net(item_free=100)
    res, ins = accept_fixed(net, [(A, 0)], EXECUTE)
    old_net = Net(item_free=100)
    old_res, old_ins = accept_old(old_net, [(A, 0)], EXECUTE)
    check("②-11 amount=0：新实现 SKIPPED 且插入 0（旧实现会凭空插 1 件，inserted=%d）" % old_ins,
          res == SKIPPED and ins == 0 and net.free[ITEM] == 100
          and old_ins == 1 and old_res == ACCEPTED,
          "new=(%s,%d) old=(%s,%d)" % (res, ins, old_res, old_ins))
    net = Net(item_free=100)
    res, ins = accept_fixed(net, [(A, 0), (B, 32)], EXECUTE)
    check("②-12 混合 0 数量：只有 32 件真实资源被插入（不因 0 而多插）",
          res == ACCEPTED and ins == 32 and net.free[ITEM] == 68,
          "result=%s inserted=%d free=%d" % (res, ins, net.free[ITEM]))

    # ②-13 SIMULATE 分支：同样判定，但绝不改网络状态。
    net = Net(item_free=64)
    res, ins = accept_fixed(net, [(A, 64)], SIMULATE)
    check("②-13 SIMULATE 通过 ⇒ ACCEPTED，且网络剩余空间保持 64（模拟不落账）",
          res == ACCEPTED and net.free[ITEM] == 64
          and all(action == SIMULATE for action, _r, _q, _i in net.log),
          "result=%s free=%d log=%r" % (res, net.free[ITEM], net.log))
    net = Net(item_free=64)
    res, ins = accept_fixed(net, [(A, 65)], SIMULATE)
    check("②-14 SIMULATE 放不下 ⇒ REJECTED，网络剩余空间仍为 64",
          res == REJECTED and net.free[ITEM] == 64,
          "result=%s free=%d" % (res, net.free[ITEM]))
    check("②-15 SIMULATE 不在批内累计占用（RS 每条 sink 模拟都拿同一份未变状态；故必须靠聚合）",
          accept_fixed(Net(item_free=64), [(A, 32), (A, 32)], SIMULATE)[0] == ACCEPTED,
          "聚合后总量超了真实容量却仍接受")

    # ②-16 不适用的资源类型：整批 SKIPPED（不是 REJECTED，不能把任务卡死）。
    check("②-16 只有非 Item/Fluid 资源 ⇒ SKIPPED（不误判 REJECTED）",
          accept_fixed(Net(item_free=64), [(ENERGY, 1000)], EXECUTE)[0] == SKIPPED,
          "result=%s" % (accept_fixed(Net(item_free=64), [(ENERGY, 1000)], EXECUTE)[0],))

    # ②-17 EXECUTE 阶段极小窗口（SIMULATE 后空间被抢）：不 break，其余资源尽量插入 + 诚实 REJECTED。
    net = Net(item_free=96)
    net.insert_race = True
    # 先按 SIMULATE 通过的手工顺序模拟：由 accept_fixed 内部先模拟，再在 EXECUTE 前把空间压到 32
    fixed_res, fixed_ins = _accept_with_race(net, [(A, 32), (B, 32), (A, 32)], EXECUTE, race_free=32)
    check("②-17 极小窗口（模拟后空间被抢）：返回 REJECTED 但已插入 32、且没有「一条失败就放弃后面」",
          fixed_res == REJECTED and fixed_ins == 32,
          "result=%s inserted=%d" % (fixed_res, fixed_ins))


def _accept_with_race(net, resources, action, race_free):
    """在 accept_fixed 的 SIMULATE 与 EXECUTE 之间把空间改小（模拟别的机器抢走空间）。"""
    totals = {}
    for resource, amount in resources:
        if resource[0] not in (ITEM, FLUID) or amount <= 0:
            continue
        totals[resource] = totals.get(resource, 0) + amount
    for resource, total in totals.items():
        if net.insert(resource, total, SIMULATE) < total:
            return REJECTED, 0
    net.free[ITEM] = race_free  # 窗口：空间被别的机器抢走
    complete = True
    inserted_total = 0
    for resource, total in totals.items():
        inserted = net.insert(resource, total, action)
        inserted_total += inserted
        if inserted < total:
            complete = False
    return (ACCEPTED if complete else REJECTED), inserted_total


# ======================================================================================
# ③ 反例自证：坏写法 / 旧实现必须被判负
# ======================================================================================

def group_counterexamples():
    print("\n=== ③ 反例自证：坏写法 / 旧实现必须判负 ===")
    raw_executor = read(EXECUTOR)
    code_executor = strip_comments(raw_executor)
    good_accept = brace_body(code_executor, "public ExternalPatternSink.Result accept(")

    # F2（第121轮判据迁移）：把 storage.insert 加回 accept ⇒ ①-4 判负。
    # 第121轮：F2 真修法（上游缓冲）取代了第120轮的直连网络实现，判据随之迁移；原意「RS 永不吞料」未放宽。
    bad = good_accept.replace("        if (!canBufferForIteration(totals)) {",
                              "        storage.insert(null, 0L, Action.EXECUTE, Actor.EMPTY);\n"
                              "        if (!canBufferForIteration(totals)) {", 1)
    counterexample("③-1 F2 方法体副本：在 accept 里加回 storage.insert（直连网络）⇒ ①-4 判负",
                   bad != good_accept and not f2_accept_writes_buffer_only(bad))

    # F2：恢复 Math.max(1, …) 凭空 +1
    bad = good_accept.replace("final long count = amount.amount();",
                              "final long count = Math.max(1, amount.amount());")
    counterexample("③-2 F2 方法体副本：恢复 Math.max(1, amount.amount()) ⇒ ①-3 判负",
                   bad != good_accept and not f2_no_phantom(bad))

    # F2：去掉聚合（逐条各自 insert）⇒ 聚合判据失效
    bad = good_accept.replace("totals.merge(resource, count, Long::sum);", "")
    counterexample("③-3 F2 方法体副本：删掉 totals.merge（不聚合，逐条各判）⇒ ①-2 判负",
                   bad != good_accept and not f2_aggregates(bad))

    # F2（第121轮判据迁移）：注释恢复旧的假承诺
    # 第121轮：F2 真修法（上游缓冲）取代了第120轮的直连网络实现，判据随之迁移；原意「RS 永不吞料」未放宽。
    bad_raw = raw_executor.replace("REJECTED 不会重试", "REJECTED 只是让任务稍后再试", 1)
    counterexample("③-4 F2 源码副本：注释改回「REJECTED 只是让任务稍后再试」（旧假承诺）⇒ ①-8 判负",
                   bad_raw != raw_executor and not f2_comment_honest(bad_raw))

    # F5：包处理改回直接信任客户端 name
    raw_packet = read(PACKET)
    good_packet = raw_packet
    bad = good_packet.replace("serverName.isEmpty() ? packet.name() : serverName", "packet.name()")
    counterexample("③-5 F5 包副本：改回直接用 packet.name() ⇒ ①-9 判负",
                   bad != good_packet and not f5_packet_server_name(bad))
    bad = good_packet.replace("terminal.setStepMachine(packet.stepIndex(), packet.pos(),\n"
                              "                serverName.isEmpty() ? packet.name() : serverName);",
                              "terminal.setStepMachine(packet.stepIndex(), packet.pos(), packet.name());")
    counterexample("③-6 F5 包副本：改回把客户端 name 原样写进 setStepMachine ⇒ ①-10 判负",
                   bad != good_packet and not f5_packet_no_trust(bad))

    # F5：落盘侧去掉夹紧
    code_terminal = strip_comments(read(TERMINAL))
    good_set = brace_body(code_terminal, "public void setStepMachine(")
    bad_set = good_set.replace("clampStepMachineName(name)", 'name == null ? "" : name')
    counterexample("③-7 F5 方法体副本：setStepMachine 改回不夹紧（原样落盘）⇒ ①-12 判负",
                   bad_set != good_set and not f5_set_step_clamps(
                       code_terminal.replace(good_set, bad_set)))

    # F6：把死代码塞回去
    code_chamber = strip_comments(read(CHAMBER_BE))
    bad = code_chamber.replace("    public boolean acceptsUnit(final ItemStack stack) {",
                               "    public boolean putUnit(final ItemStack held) { return false; }\n"
                               "    public boolean takeUnit() { return false; }\n"
                               "    public boolean acceptsUnit(final ItemStack stack) {", 1)
    counterexample("③-8 F6 源码副本：把 putUnit / takeUnit 死代码塞回去 ⇒ ①-13 判负",
                   bad != code_chamber and not f6_dead_code_gone(bad))

    # F6：恢复旧「容量 6 / 无类型限制」谎话
    raw_chamber = read(CHAMBER_BE)
    bad_raw = raw_chamber.replace("{@value #UNIT_SLOTS} 格（6 行 × 9 列", "容量 6（6 行 × 9 列", 1)
    counterexample("③-9 F6 执行仓注释副本：改回「容量 6」⇒ ①-14 判负",
                   bad_raw != raw_chamber and not f6_chamber_comment_ok(bad_raw))

    # F6：方块注释改回「3×2」
    raw_block = read(CHAMBER_BLOCK)
    bad_raw = raw_block.replace("右键打开界面：6 行 × 9 列 = 54 格单元样板槽",
                                "右键打开界面（3×2 单元样板槽，正常放入/取出）", 1)
    counterexample("③-10 F6 方块注释副本：改回「3×2 单元样板槽」⇒ ①-15 判负",
                   bad_raw != raw_block and not f6_block_comment_ok(bad_raw))

    # F7：把方法名改回旧名（伪装成可调用入口）
    bad = code_chamber.replace("public void rscc$legacyDropOutputContents(",
                               "public void dropOutputContents(", 1)
    counterexample("③-11 F7 源码副本：改回 public void dropOutputContents( ⇒ ①-17 判负",
                   bad != code_chamber and not f7_legacy_named(bad))

    # F7：删掉「禁止调用 / 双重结算」警示
    bad_raw = raw_chamber.replace("禁止调用", "可以调用")
    counterexample("③-12 F7 javadoc 副本：去掉「禁止调用」⇒ ①-18 判负",
                   bad_raw != raw_chamber and not f7_doc_forbids(bad_raw))

    # F8：把 writeCandidates 塞回去第二遍
    good_save = brace_body(code_terminal,
                           "public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries)")
    bad_save = good_save.replace(
        "SequencePatternData.writeCandidates(tag, ingredientCandidates, registries);",
        "SequencePatternData.writeCandidates(tag, ingredientCandidates, registries);\n"
        "        SequencePatternData.writeCandidates(tag, ingredientCandidates, registries);")
    counterexample("③-13 F8 方法体副本：把 writeCandidates 连写两遍塞回去 ⇒ ①-19 判负",
                   bad_save != good_save
                   and not f8_single_write(code_terminal.replace(good_save, bad_save)))

    # 行为模型反例：旧实现在 ②-1 / ②-5 / ②-11 三个断言上都必须判负
    A = (ITEM, "minecraft:iron_ingot")
    W = (FLUID, "minecraft:water")
    old_net = Net(item_free=64, fluid_free=0)
    res, ins = accept_old(old_net, [(A, 64), (W, 500)], EXECUTE)
    counterexample("③-14 行为模型：旧实现（边插边判）在「整批被拒 ⇒ 插入 0」不变量上判负",
                   not rejected_means_nothing_inserted(res, ins))

    old_net = Net(item_free=64)
    res, ins = accept_old(old_net, [(A, 40), (A, 40)], EXECUTE)
    counterexample("③-15 行为模型：旧实现不聚合，40+40 场景把 64 件留给网络、却按 80 件从 RS 暂存扣掉",
                   res == REJECTED and ins == 64 and not rejected_means_nothing_inserted(res, ins))

    old_net = Net(item_free=100)
    accept_old(old_net, [(A, 0)], EXECUTE)
    counterexample("③-16 行为模型：旧实现 amount=0 时凭空插入 1 件（Math.max(1, …)）",
                   old_net.free[ITEM] == 99)


def main():
    print("第 120 轮自检：序列家族审计债清理（F2 整批原子性 / F5 名称上限 / F6-F8 注释与死代码）")
    group_anchors()
    group_model()
    group_counterexamples()
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
