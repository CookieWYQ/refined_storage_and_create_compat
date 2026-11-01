#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 99 轮自检：FE 能量立方（`rs_create_compat:fe_energy_cube`）的源码级锚点 + 行为模型。

本脚本只读源码 / 语言文件，**不改任何 src/main**；只用标准库。

断言清单：
  ① 集群模型（`support/EnergyCubeCluster`）：存量 = Σ 各成员自身存量、容量 = Σ 各成员自身容量、
     **不持有共享载荷 / 不做内容移交**（与 RsccMachineCluster 的关键区别）；extract 按坐标字典序
     跨成员扣、receive 先填发起方再按字典序填其它块；缓存 = WeakHashMap + 20 tick 校验 + 事件失效；
     客户端只返回单元素快照（不写缓存、不解析集群）。
  ② 自身记账 + NBT 往返：每块自己的 EnergyStorage、`restoreEnergy` 直接设值（绝不走 receiveEnergy，
     否则 5000 万会被 maxReceive 截断）；save/load 的 `Energy` 键名成对。
  ③ 破坏保留：`getDrops` 只把**本块**电量经原版 `saveToItem` 写进方块物品；其余块一格不动
     （无平均 / 无迁移）；工具不对导致掉落为空时电量随方块消失（如实注释）。
  ④ 门控三态：无网络 / 允许 / 控制器低电拒绝；读数减掉本集群自身贡献（不减 ⇒ percent 永远进不了闸）；
     percent 用 double（创造控制器 Long.MAX_VALUE 不溢出）、absolute 用 long。
  ⑤ 网络抽取三重夹 + 能量守恒：集群空间 / 每 tick 抽取上限 / 其它 provider 存量；抽到的必须整份收下
     （收不下要 WARN，绝不静默丢能量）。
  ⑥ 能力注册：`EnergyStorage.BLOCK` 六面（含 null）+ RS 网络节点容器能力；能力视图报集群、
     节点报本块（两套口径）；所有客户端路径不写世界（ticker 客户端为 null、写入口全部 isClientSide 判断）。
  ⑦ 界面契约：贴图 176×166；电量条**不再自绘**——走共享实现 `RsccEnergyBar`
     （= RS 原版 `ProgressWidget` + sprite `refinedstorage:widget/progress_bar`），
     几何 16 宽 × 58 高 @ (14,19)、外框 18×60 @ (13,18) 整条盖住贴图里的旧 14×60 凹槽；
     界面内 `drawEnergyBar` / `BAR_FILL` / `filledPixels` / `guiGraphics.fill(` 一个都不许留；
     条的 tooltip 由 widget 自己画，读数板判定先让位（同一帧不许两个框）；
     文字全部 dropShadow=false；数据槽 21 个且每 tick 只算一次；菜单无物品槽。
  ⑧ 注册 / 语言键（Lead 写域只读核对；资源未就绪时显式 SKIP，不假装通过）。
  ⑨ round103「低优先级到底多低」：从网络抽电必须 **控制器 ≥ 阈值 且 网络电量正在上涨**（严格变大，
     持平不算涨）才放行；外部 FE 直接给立方充电<b>不受上涨闸限制</b>；界面第 7 行显示趋势、
     数据槽 17/18/19 下发趋势与上窗口净变化；八态判定表 + 反例（删 rising / 持平当上涨 /
     只改界面不改门控 / 外部 FE 被趋势拦）全部判负。
  ⑩ round106「立方 = 传递介质 / 缓存」（beta.9 用户实机）：每 tick 向 6 面主动推送（先 simulate、
     再按 min(预算, 存量, 能吃) 扣、收不下退回；**不推能量立方**；预算六面分摊）；**注入 / 抽取
     永不受门控**（门控只拦「从 RS 网络抽电」）；网络池「立方最后」不依赖 provider 插入顺序
     （节点只补「非立方付不上」的差额）；界面第 8 行「相邻供能：开 / 关 / 无相邻」（数据槽 20）。
     推送行为模型（3 个可接收邻居 + 1 个立方 ⇒ 只推 3 个、总量 ≤ 上限、simulate 不动状态）+
     ≥5 条反例（推给立方 / simulate 里扣电 / 门控拦外部输入 / 推送超限 / 满收方仍写 / 不退款丢电）。

反例自证：把「旧写法 / 错误写法」或错误模型参数喂进同一批判据，必须判负（打印 `反例 ... PASS`）。

用法：python tools/selfcheck_round99_energy_cube.py
退出码：0 = 全部通过。
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
JAVA = os.path.join(ROOT, "src", "main", "java")
PKG = os.path.join(JAVA, "cretae", "cookiewyq", "rs_create_compat")

BE = os.path.join(PKG, "block", "entity", "FEEnergyCubeBlockEntity.java")
NODE = os.path.join(PKG, "network", "FEEnergyCubeNetworkNode.java")
CLUSTER = os.path.join(PKG, "support", "EnergyCubeCluster.java")
REGISTRY = os.path.join(PKG, "support", "EnergyCubeNetworkRegistry.java")
BLOCK = os.path.join(PKG, "block", "FEEnergyCubeBlock.java")
MENU = os.path.join(PKG, "menu", "FEEnergyCubeMenu.java")
SCREEN = os.path.join(PKG, "client", "screen", "FEEnergyCubeScreen.java")
CONFIG = os.path.join(PKG, "Config.java")
MAIN = os.path.join(PKG, "RS_Create_Compat.java")
CLIENT_INIT = os.path.join(PKG, "client", "ClientInit.java")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

FAILS = []
CHECKS = [0]
COUNTER = [0]
SKIPS = []


# ==================== 基础设施 ====================

def read(path: str) -> str:
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text: str) -> str:
    """去掉 // 与 /* */ 注释（保留字符串字面量，避免把 "http://" 当注释切掉）。"""
    out = []
    i = 0
    n = len(text)
    state = None  # None / "line" / "block" / "str" / "char"
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if state is None:
            if ch == "/" and nxt == "/":
                state = "line"
                i += 2
                continue
            if ch == "/" and nxt == "*":
                state = "block"
                i += 2
                continue
            if ch == '"':
                state = "str"
            elif ch == "'":
                state = "char"
            out.append(ch)
            i += 1
        elif state == "line":
            if ch == "\n":
                state = None
                out.append(ch)
            i += 1
        elif state == "block":
            if ch == "*" and nxt == "/":
                state = None
                i += 2
                continue
            i += 1
        elif state == "str":
            if ch == "\\":
                out.append(ch)
                if nxt:
                    out.append(nxt)
                i += 2
                continue
            if ch == '"':
                state = None
            out.append(ch)
            i += 1
        else:  # char
            if ch == "\\":
                out.append(ch)
                if nxt:
                    out.append(nxt)
                i += 2
                continue
            if ch == "'":
                state = None
            out.append(ch)
            i += 1
    return "".join(out)


def method_body(text: str, signature: str) -> str:
    """取 `signature` 之后第一个大括号块的完整内容（含大括号）。找不到返回空串。"""
    idx = text.find(signature)
    if idx < 0:
        return ""
    start = text.find("{", idx)
    if start < 0:
        return ""
    depth = 0
    for k in range(start, len(text)):
        if text[k] == "{":
            depth += 1
        elif text[k] == "}":
            depth -= 1
            if depth == 0:
                return text[start:k + 1]
    return ""


def int_consts(text: str):
    """解析 `static final int NAME = 123;` → {NAME: 123}。"""
    return {name: int(value) for name, value in
            re.findall(r"static\s+final\s+int\s+([A-Z0-9_]+)\s*=\s*(-?\d+)\s*;", text)}


def check(label: str, ok: bool, detail: str = "") -> bool:
    CHECKS[0] += 1
    if ok:
        print("PASS %s" % label)
    else:
        print("FAIL %s%s" % (label, ("  <- " + detail) if detail else ""))
        FAILS.append(label)
    return ok


def counterexample(label: str, detected: bool) -> bool:
    COUNTER[0] += 1
    if detected:
        print("反例 %s PASS" % label)
    else:
        print("反例 %s FAIL（这个错误写法没有被判据抓到 = 判据无效）" % label)
        FAILS.append("反例未抓住: " + label)
    return detected


def skip(label: str) -> None:
    SKIPS.append(label)
    print("SKIP %s" % label)


# ==================== 纯 Python 行为模型（与 Java 同一套语义） ====================

CAP = 50_000_000          # 单块容量 = Config.feEnergyCubeCapacity 默认值
PER_TICK = 10_000         # Config.feEnergyCubeNetworkPullPerTick 默认值
MAX_TRANSFER = 100_000    # Config.feEnergyCubeMaxTransfer 默认值
LONG_MAX = (1 << 63) - 1
INT_MAX = 2 ** 31 - 1

GATE_NO_NETWORK, GATE_ALLOW, GATE_DENY = 0, 1, 2
# round103：门控第四态 —— 阈值达标但网络没在涨（只拦网络抽取，不拦外部 FE）
GATE_DENY_NOT_RISING = 3
# round103：趋势编码（数据槽 17）
TREND_UNKNOWN, TREND_RISING, TREND_FLAT, TREND_FALLING = 0, 1, 2, 3
# round106：相邻供能三态（数据槽 20）
ADJACENT_NONE, ADJACENT_ON, ADJACENT_OFF = 0, 1, 2


def model_cluster_stored(blocks):
    return sum(b["stored"] for b in blocks)


def model_cluster_capacity(blocks):
    return len(blocks) * CAP


def model_extract(blocks, amount):
    """按成员顺序（= 坐标字典序）扣电。blocks 顺序即字典序。"""
    remaining = max(0, amount)
    extracted = 0
    for block in blocks:
        if remaining <= 0:
            break
        got = min(block["stored"], remaining)
        block["stored"] -= got
        extracted += got
        remaining -= got
    return extracted


def model_receive(blocks, amount, preferred=0):
    """先填 preferred，再按顺序填其它块。"""
    remaining = max(0, amount)
    received = 0
    order = [preferred] + [i for i in range(len(blocks)) if i != preferred]
    for index in order:
        if remaining <= 0:
            break
        space = CAP - blocks[index]["stored"]
        got = min(space, remaining)
        blocks[index]["stored"] += got
        received += got
        remaining -= got
    return received


def model_break(blocks, index):
    """拆掉一块：掉落物带它自己那份，其余块一格不动。返回掉落量。"""
    dropped = blocks[index]["stored"]
    del blocks[index]
    return dropped


def model_gate(network, cube_stored, cube_capacity, has_network,
               absolute_mode=False, percent_threshold=90, absolute_threshold=0,
               subtract_cubes=True):
    """门控三态。network = (存量, 容量) 或 None；
    cube_* = **本网络所有立方**的贡献之和（不是单个集群 —— 同网可有多组立方簇）。"""
    if not has_network or network is None:
        return GATE_NO_NETWORK
    net_stored, net_capacity = network
    if subtract_cubes:
        non_cube_stored = max(0, net_stored - cube_stored)
        non_cube_capacity = max(0, net_capacity - cube_capacity)
    else:
        non_cube_stored, non_cube_capacity = net_stored, net_capacity
    if absolute_mode:
        return GATE_ALLOW if non_cube_stored >= absolute_threshold else GATE_DENY
    percent = 0.0 if non_cube_capacity <= 0 else non_cube_stored * 100.0 / float(non_cube_capacity)
    return GATE_ALLOW if percent >= percent_threshold else GATE_DENY


def model_pull(blocks, others_stored, per_tick=PER_TICK, clamp_space=True, clamp_others=True):
    """返回 (抽取量, 实际收下的量)。抽取量按三重夹收窄。"""
    space = model_cluster_capacity(blocks) - model_cluster_stored(blocks)
    request = per_tick
    if clamp_space:
        request = min(request, space)
    if clamp_others:
        request = min(request, others_stored)
    request = max(0, request)
    received = model_receive(blocks, request, preferred=0) if request > 0 else 0
    return request, received


def model_restore_direct(value):
    """读档语义：直接设值（夹到 [0, capacity]）。"""
    return min(max(0, value), CAP)


def model_restore_via_receive(value, max_receive=MAX_TRANSFER):
    """错误写法：用 receiveEnergy 恢复 ⇒ 被 maxReceive 截断。"""
    return min(max(0, value), max_receive, CAP)


# ---- round103：网络电量趋势 + 「低优先级」门控模型 ----

def model_trend(previous, current):
    """与 Java 严格一致：<b>严格变大</b>才是 RISING；相等是 FLAT（这条是用户口径的核心）。"""
    if current > previous:
        return TREND_RISING
    if current < previous:
        return TREND_FALLING
    return TREND_FLAT


def model_trend_loose(previous, current):
    """错误写法：把「持平」当上涨（`delta >= 0`）。"""
    if current >= previous:
        return TREND_RISING
    return TREND_FALLING


def model_charge_gate(network, cube_stored, cube_capacity, has_network, trend,
                      absolute_mode=False, percent_threshold=90, absolute_threshold=0,
                      subtract_cubes=True):
    """round103 门控：<b>阈值</b>（控制器低电优先报告）<b>且</b> <b>趋势 = RISING</b> 才 ALLOW。"""
    threshold_gate = model_gate(network, cube_stored, cube_capacity, has_network,
                                absolute_mode=absolute_mode,
                                percent_threshold=percent_threshold,
                                absolute_threshold=absolute_threshold,
                                subtract_cubes=subtract_cubes)
    if threshold_gate == GATE_NO_NETWORK:
        return GATE_NO_NETWORK
    if threshold_gate != GATE_ALLOW:
        return GATE_DENY
    return GATE_ALLOW if trend == TREND_RISING else GATE_DENY_NOT_RISING


def model_charge_gate_threshold_only(network, cube_stored, cube_capacity, has_network, trend,
                                     absolute_mode=False, percent_threshold=90,
                                     absolute_threshold=0):
    """错误写法（round103 之前的版本）：只看阈值，不管趋势。"""
    return model_gate(network, cube_stored, cube_capacity, has_network,
                      absolute_mode=absolute_mode,
                      percent_threshold=percent_threshold,
                      absolute_threshold=absolute_threshold)


def model_external_fe_allowed_round103(gate):
    """（已废弃，保留作对照）round103 口径：只有「控制器低电」拦外部 FE，趋势闸不管。

    第 106 轮起用户口径改为「外部 FE 完全不受门控」⇒ 判定用 {@link model_external_fe_allowed}。
    """
    return gate != GATE_DENY


def model_signed_fe(value):
    """与 FEEnergyCubeScreen#signedFe 同口径：符号自己拼、绝对值再分组。"""
    if value == 0:
        return "+0"
    magnitude = LONG_MAX if value == -(1 << 63) else abs(value)
    return ("+" if value > 0 else "-") + "{:,}".format(magnitude)


def model_grouped_clamps_negative(value):
    """错误写法：直接用 RsccNumberField.grouped(long) —— 内部 raw() 把负数夹成 0。"""
    return "{:,}".format(max(0, value))


def clamp_int(value):
    return max(0, min(value, INT_MAX))


# ---- round106：立方 = 传递介质 / 缓存（相邻主动推送 + 注入/抽取不受门控 + 网络池最低优先级） ----

PUSH_PER_TICK = 10_000    # Config.feEnergyCubePushPerTick 默认值


def model_push(cube_stored, receivers, limit=PUSH_PER_TICK, skip_cubes=True):
    """相邻主动推送（与 Java#pushToNeighbours 同一套语义）。

    receivers = [{'stored','capacity','is_cube'}, ...]（列表顺序 = 六面遍历顺序，会被就地修改）。
    口径：
      ① 每个邻居先 simulate（`receiveEnergy(offer, true)`）问「本 tick 能吃多少」，**不改任何状态**；
      ② 按 min(本 tick 预算, 自身存量, 对方能吃) 从集群扣电，再真收；
      ③ 预算 **六面分摊**（不是每个面各一份）；
      ④ **不推给能量立方**（`is_cube` 的邻居直接跳过，不消耗预算）。
    返回 (送出的总量, 每个邻居本次收下的量)。
    """
    budget = max(0, limit)
    total = 0
    got = []
    for receiver in receivers:
        # 预算用尽 / 立方没电：后面的邻居本轮一个 FE 也拿不到（**仍然记 0**，让返回值与入参一一对应）
        if budget <= 0 or cube_stored <= 0:
            got.append(0)
            continue
        if skip_cubes and receiver.get("is_cube"):
            got.append(0)
            continue
        space = max(0, receiver["capacity"] - receiver["stored"])
        accepted = min(space, budget, cube_stored)   # == receiveEnergy(offer, true)
        if accepted <= 0:
            got.append(0)
            continue
        cube_stored -= accepted                      # ② 真扣
        actual = min(accepted, max(0, receiver["capacity"] - receiver["stored"]))
        receiver["stored"] += actual
        cube_stored += accepted - actual             # 守恒兜底：收不下的退回
        total += actual
        budget -= actual
        got.append(actual)
    return total, got


def model_push_deduct_in_simulate(cube_stored, receivers, limit=PUSH_PER_TICK):
    """错误写法：simulate 阶段就把电从立方扣掉（还没真收）⇒ 收方最终没收下时这份电凭空消失。"""
    budget = max(0, limit)
    lost = 0
    for receiver in receivers:
        if budget <= 0 or cube_stored <= 0:
            continue
        space = max(0, receiver["capacity"] - receiver["stored"])
        offered = min(space, budget, cube_stored)
        if offered <= 0:
            continue
        cube_stored -= offered          # ← 错在这里：simulate 里扣电
        # 收方因为「这一 tick 之后又被灌满」等原因只收下一部分
        actual = 0
        receiver["stored"] += actual
        lost += offered - actual
        budget -= offered
    return cube_stored, lost


def model_network_extract(cube_stored, others_stored, amount):
    """网络耗能时的抽取（第 106 轮口径）：**非立方 provider 先出，立方只补差额**。

    返回 (非立方出电, 立方出电)。这条口径与 provider 的加入顺序无关 ⇒ 立方永远最后。
    """
    from_others = min(max(0, others_stored), max(0, amount))
    shortfall = max(0, amount - max(0, others_stored))
    from_cube = min(max(0, cube_stored), shortfall)
    return from_others, from_cube


def model_network_extract_by_insertion(cube_stored, others_stored, amount):
    """错误写法（第 106 轮之前）：按 provider 插入顺序抽 —— 立方若先入网就会先被抽干。"""
    from_cube = min(max(0, cube_stored), max(0, amount))
    from_others = min(max(0, others_stored), max(0, amount - from_cube))
    return from_others, from_cube


def model_external_fe_allowed(gate):
    """第 106 轮口径：外部 FE（注入 / 抽取）**永不受门控** —— 门控只决定「要不要从网络抽电」。"""
    return True


def model_external_fe_gated(gate):
    """round103 / beta.9 的旧口径（第 106 轮判定为错）：控制器低电时连外部注入都拦。"""
    return gate != GATE_DENY


def model_adjacent_state(has_receiver, pushed):
    """数据槽 20 的三态：没对象 ⇒ 无相邻；有对象且本 tick 送出了电 ⇒ 开；有对象但没送出 ⇒ 关。"""
    if not has_receiver:
        return ADJACENT_NONE
    return ADJACENT_ON if pushed > 0 else ADJACENT_OFF


def model_adjacent_state_early_break(cube_stored, has_receiver):
    """错误写法：先按「立方有没有电」提前 break，再记状态 ⇒ 空电池被误显示成「无相邻」。"""
    if cube_stored <= 0:
        return ADJACENT_NONE
    return ADJACENT_ON if has_receiver else ADJACENT_NONE


# ==================== ⑩ round106：立方 = 传递介质 / 缓存 ====================

def push_source_ok(be_text: str) -> bool:
    """⑩-a：相邻主动推送的源码锚点（每一条都对应一个真会犯的错）。"""
    body = strip_comments(be_text)
    push = method_body(body, "private int pushToNeighbours()")
    tick = method_body(body, "public void tickNeighbourPush()")
    return (
        # 服务端专属入口（客户端 ticker 为 null，这里再判一次；绝不在客户端写世界）
        "if (level == null || level.isClientSide()) {" in tick
        and "lastPushedPerTick = pushToNeighbours();" in tick
        # 不推给别的能量立方：按方块类型判（不依赖方块实体是否已加载）
        and "if (level.getBlockState(target).getBlock() instanceof FEEnergyCubeBlock) {" in push
        # 不为「充电」加载区块
        and "if (!level.isLoaded(target)) {" in push
        # 先 simulate 问收方（不改任何状态）
        and "final int accepted = storage.receiveEnergy(offer, true);" in push
        # 再按 accepted 从集群扣电
        and "final long taken = extractFromCluster(accepted);" in push
        # 最后真收；收不下的**退回集群**（守恒，绝不丢电）
        and "final int actual = storage.receiveEnergy((int) taken, false);" in push
        and "if (actual < taken) {" in push
        and "receiveIntoCluster(taken - actual);" in push
        # 每 tick 一个总预算、六面分摊
        and "long budget = Math.max(0L, (long) Config.feEnergyCubePushPerTick);" in push
        and "budget -= moved;" in push
        # 三态：有对象 + 本 tick 真送出 > 0 ⇒ 开；有对象但 0 ⇒ 关；没对象 ⇒ 无相邻
        and "adjacentSupplyState = hasReceiver == 0" in push
        and "ADJACENT_SUPPLY_ON : ADJACENT_SUPPLY_OFF" in push
        # 状态必须在「判预算 / 判存量」**之前**记（否则空电池 / 预算用尽时会被误显示成「无相邻」）
        and push.find("hasReceiver = 1;") < push.find("if (budget <= 0L) {")
        # 推的是「集群存量」（容量共享的整块电池），不是单个方块实体
        and "getClusterEnergyStored()" in push
        # 推送不看门控：门控只拦「从网络抽电」（放电是本职）
        and "getGateState" not in push
        and "Gate." not in push
    )


def node_push_before_network_ok(node_text: str) -> bool:
    """⑩-b：节点 doWork —— 相邻推送必须在 `network == null` 提前返回**之前**。

    这是本轮最容易被写反的一行：旧 doWork 第一句就是 `if (blockEntity == null || network == null) return;`，
    把推送接在它后面 ⇒ 没接 RS 网络的立方永远不供电，而用户恰恰是「一堆立方 + 紧贴一个范围充电器」
    的场景（充电器可能根本没连网络）。
    """
    body = strip_comments(node_text)
    work = method_body(body, "public void doWork()")
    pushed = work.find("blockEntity.tickNeighbourPush();")
    early = work.find("if (network == null) {")
    return (
        pushed >= 0
        and early >= 0
        and pushed < early
        and "blockEntity.onNetworkTick(network);" in work
    )


def node_cube_last_ok(node_text: str) -> bool:
    """⑩-c：网络池「立方最后」不再依赖插入顺序 —— extract 只补「非立方付不上」的差额。

    RS 的 provider 集合是 LinkedHashSet（插入序），而网络重建按**方块坐标**加容器 ⇒
    「先放立方、后放控制器」时立方会排在控制器前面。因此节点必须自己让位。
    """
    body = strip_comments(node_text)
    extract = method_body(body, "public long extract(final long amount)")
    return (
        "if (blockEntity.isPullingFromNetwork()) {" in extract
        and "network.getComponent(EnergyNetworkComponent.class).getStored()" in extract
        and "- EnergyCubeNetworkRegistry.totalStored(network));" in extract
        and "want = Math.max(0L, amount - nonCubeStored);" in extract
        and "return blockEntity.dischargeToNetwork(want);" in extract
        # 不能再无条件 dischargeToNetwork(amount)
        and "dischargeToNetwork(amount)" not in extract
    )


def screen_adjacent_row_ok(screen_text: str) -> bool:
    """⑩-d：第 8 行「相邻供能：开/关/无相邻」真的画出来，且客户端不自己遍历邻居。"""
    body = strip_comments(screen_text)
    labels = method_body(
        body, "protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY)")
    return (
        int_consts(body).get("ROW_8_Y") == 99
        and "drawRow(guiGraphics, ROW_8_Y, adjacentSupplyLine(), adjacentSupplyColor());" in labels
        and "private Component adjacentSupplyLine()" in body
        and "private String adjacentSupplyWordKey()" in body
        and all(key in body for key in ROUND106_KEYS)
        # 状态只读服务端数据槽 20：客户端不许去查邻居能力 / 方块实体
        and "menu.getAdjacentSupplyState()" in body
        and "Capabilities" not in body
        and "getBlockEntity" not in body
    )


# ==================== ① 集群模型 ====================

def cluster_sums_ok(text: str) -> bool:
    body = strip_comments(text)
    stored = method_body(body, "public long stored()")
    capacity = method_body(body, "public long capacity()")
    return (
        "sum += cube.getOwnEnergyStored();" in stored
        and "sum += cube.getOwnCapacity();" in capacity
        # 关键：不持有共享载荷、不做内容移交（那两件事是 RsccMachineCluster 的模型，电量不能这么算）
        and "payload" not in body
        and "handOver" not in body
        and "clusterMerge" not in body
    )


def cluster_order_ok(text: str) -> bool:
    body = strip_comments(text)
    extract = method_body(body, "public long extract(final long amount)")
    receive = method_body(body, "public long receive(final long amount, @Nullable final BlockPos preferred)")
    return (
        "found.sort(LEXICOGRAPHIC)" in body
        and "cube.extractOwn(remaining)" in extract
        and "for (final BlockPos pos : members)" in extract
        and "members.contains(preferred)" in receive
        and "if (pos.equals(preferred))" in receive
        and "MAX_MEMBERS = 64" in body
    )


def cluster_cache_ok(text: str) -> bool:
    body = strip_comments(text)
    return (
        "private static final Map<Level, Map<Long, Snapshot>> CACHE = new WeakHashMap<>();" in body
        and "cached.isFresh(server)" in body
        and "VALIDATE_INTERVAL_TICKS = 20" in body
        and "removeAll(map, snapshot);" in body
        and "return level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof FEEnergyCubeBlock;" in body
    )


def cluster_client_safe_ok(text: str) -> bool:
    body = strip_comments(text)
    resolve = method_body(body, "public static Snapshot resolve(@Nullable final Level level, final BlockPos pos)")
    return (
        "if (!(level instanceof ServerLevel server)) {" in resolve
        and "return new Snapshot(level, List.of(pos));" in resolve
        # CACHE 只在 ServerLevel 分支之后才碰
        and resolve.find("CACHE.computeIfAbsent") > resolve.find("return new Snapshot(level, List.of(pos));")
    )


# ==================== ② 自身记账 + NBT ====================

def be_own_storage_ok(text: str) -> bool:
    body = strip_comments(text)
    own = method_body(body, "private static final class OwnEnergyStorage extends EnergyStorage")
    load = method_body(body, "public void loadAdditional(final CompoundTag tag, final HolderLookup.Provider registries)")
    save = method_body(body, "public void saveAdditional(final CompoundTag tag, final HolderLookup.Provider registries)")
    return (
        "this.energy = Math.clamp(amount, 0, this.capacity);" in own
        and "public int getOwnEnergyStored()" in body
        and "public int getOwnCapacity()" in body
        and "tag.putInt(TAG_ENERGY, energyStorage.getEnergyStored());" in save
        and "final int saved = Math.max(0, tag.getInt(TAG_ENERGY));" in load
        and "energyStorage.restoreEnergy(saved);" in load
        # 读档绝不能用 receiveEnergy（会被 maxReceive 截断）
        and "receiveEnergy(saved" not in load
    )


# ==================== ③ 破坏保留 ====================

def drop_preserves_ok(block_text: str) -> bool:
    body = strip_comments(block_text)
    drops = method_body(body, "protected List<ItemStack> getDrops(final BlockState state, final LootParams.Builder params)")
    return (
        "cube.saveToItem(stack, params.getLevel().registryAccess());" in drops
        and "RS_Create_Compat.FE_ENERGY_CUBE_ITEM.get()" in drops
        and "cube.getOwnEnergyStored() <= 0" in drops
        and "if (drops.isEmpty()) {" in drops
        # 只搬本块那一份：getDrops 里不许出现集群总和的读取
        and "getClusterEnergyStored" not in drops
        and "getClusterCapacity" not in drops
    )


def removed_keeps_others_ok(text: str) -> bool:
    body = strip_comments(text)
    removed = method_body(body, "public void setRemoved()")
    return (
        "EnergyCubeCluster.invalidateAround(level, worldPosition);" in removed
        # 成员离场只作废缓存：没有把电量搬给别人 / 平均 / 合并
        and "receiveOwn" not in removed
        and "extractOwn" not in removed
        and "merge" not in removed
    )


# ==================== ④ 门控 ====================

def gate_source_ok(text: str) -> bool:
    body = strip_comments(text)
    gate = method_body(body, "private Gate computeGate(@Nullable final Network network, final Stats stats)")
    return (
        "return Gate.NO_NETWORK;" in gate
        and "if (Config.feEnergyCubeChargeModeAbsolute) {" in gate
        and "nonCubeStored >= Config.feEnergyCubeChargeThresholdAbsolute" in gate
        # percent：容量为 0 时按 0%（不是「有存量就放行」的旧特例），且用 double 防 Long.MAX 溢出
        and "final double percent = nonCubeCapacity <= 0L" in gate
        and "? 0.0D : nonCubeStored * 100.0D / (double) nonCubeCapacity;" in gate
        and ">= Config.feEnergyCubeChargeThresholdPercent" in gate
        # round103：低电优先报告，然后 **趋势必须是 RISING** 才 ALLOW（低优先级的核心）
        and "return Gate.DENY_LOW_CONTROLLER;" in gate
        and "return chargeTrend == Trend.RISING ? Gate.ALLOW : Gate.DENY_NOT_RISING;" in gate
        # 减掉的是「本网络所有立方」，不是「本集群」
        and "return Math.max(0L, networkStored - cubeStored);" in body
        and "return Math.max(0L, networkCapacity - cubeCapacity);" in body
        and "EnergyCubeNetworkRegistry.totalStored(network)" in body
        and "EnergyCubeNetworkRegistry.totalCapacity(network)" in body
    )


def trend_source_ok(text: str) -> bool:
    """round103：趋势采样（rising 闸）的源码锚点。

    核心判据（每一条都对应一个真会犯的错）：
      · 采样窗口 = {@code TREND_WINDOW_TICKS}（5 tick），不是每 tick；
      · 判据是<b>严格大于</b>（相等 = FLAT）——「持平当上涨」是用户口径里明确不准的；
      · 量的是<b>非立方存量</b>（控制器口径），不是网络总量：总量会被「外部 FE 给立方充电」抬高
        ⇒ 假阳性（立方一边收外部 FE、一边从 RS 网络抽电）；
      · 采样读数要<b>加回本立方自己抽走的量</b>（{@code trendPulledSinceBaseline}）：否则「一充就不涨」
        ⇒ 停抽 ⇒ 再涨 的自反馈振荡；
      · 客户端不采样（在 {@code onNetworkTick} 里，且再判一次 {@code isClientSide}）；
      · {@code getGateState} / {@code computeGate} <b>只读缓存</b>，绝不采样（它们被界面逐槽、
        能力每面高频调用，在那里采样等于把窗口打碎成每 tick）。
    """
    body = strip_comments(text)
    sample = method_body(body, "private void sampleChargeTrend(final Stats stats)")
    gate = method_body(body, "public Gate getGateState()")
    tick = method_body(body, "public void onNetworkTick(@Nullable final Network network)")
    return (
        "public static final int TREND_WINDOW_TICKS = 5;" in body
        and "if (now >= trendSampleTick && now - trendSampleTick < TREND_WINDOW_TICKS) {" in sample
        # 反事实读数 = 非立方存量 + 本立方抽走的量（两个口径都在这一行，改一个就红）
        and "final long counterfactual = stats.nonCubeStored() + trendPulledSinceBaseline;" in sample
        # 严格大于 ⇒ RISING；相等 ⇒ FLAT（不能写成 >=）
        and "chargeTrend = delta > 0L ? Trend.RISING : (delta < 0L ? Trend.FALLING : Trend.FLAT);" in sample
        and "final long delta = counterfactual - trendBaseline;" in sample
        # 无基线时只建基线，趋势保持 UNKNOWN（不算上涨 ⇒ 冷启动不抢电）
        and "if (!trendHasBaseline) {" in sample
        and "chargeTrend = Trend.UNKNOWN;" in sample
        # 客户端不采样
        and "if (level == null || level.isClientSide()) {" in sample
        # 抽电后必须记账（「加回」的前提）
        and "trendPulledSinceBaseline += extracted;" in tick
        # 门控只读缓存：getGateState 里不许出现采样调用（只有 computeGate(network, stats(network))）
        and "return computeGate(network, stats(network));" in gate
        and "sampleChargeTrend" not in gate
        # 网络节点变化 / 读档都要作废基线
        and "resetChargeTrend" in body
    )


def external_fe_never_gated_ok(text: str) -> bool:
    """⑨-c（第 106 轮重新定性）：外部 FE 的注入 / 抽取**永不受门控**。

    <p>为什么这条判据必须反过来：round103 时外部 FE 还会被「控制器低电」拦（用户当时的
    「备用电池」口径）；beta.9 用户实机明确要求立方是「传递的介质 / 缓存」，
    「可以被别人注入，也可以被别的吸取」。于是门控<b>只</b>作用于「从 RS 网络抽电进立方」
    那一条路径。判据：能力视图的接收 / 抽取路径里<b>一个门控读数都不许有</b>，
    而门控判定必须仍然存在（否则就从「只拦网络抽取」退化成「完全不拦」）。
    """
    body = strip_comments(text)
    receive = method_body(body, "public int receiveEnergy(final int toReceive, final boolean simulate)")
    extract = method_body(body, "public int extractEnergy(final int toExtract, final boolean simulate)")
    can_receive = method_body(body, "public boolean canReceive()")
    can_extract = method_body(body, "public boolean canExtract()")
    return (
        "getGateState" not in receive
        and "Gate." not in receive
        and "chargeTrend" not in receive
        and "getGateState" not in extract
        and "Gate." not in extract
        and "return true;" in can_receive
        and "return true;" in can_extract
        # 门控本身还在（只用于 onNetworkTick 那条路径）
        and "if (computeGate(network, stats) != Gate.ALLOW) {" in body
    )


# ==================== ⑤ 抽取三重夹 + 守恒 ====================

def pull_source_ok(text: str) -> bool:
    body = strip_comments(text)
    tick = method_body(body, "public void onNetworkTick(@Nullable final Network network)")
    return (
        "if (computeGate(network, stats) != Gate.ALLOW) {" in tick
        and "final long space = stats.clusterCapacity() - stats.clusterStored();" in tick
        and "final long others = stats.nonCubeStored();" in tick
        and "Math.min(Math.min(space, (long) Config.feEnergyCubeNetworkPullPerTick), others)" in tick
        # 第 106 轮：extract 包在 try/finally 里（抽取期间置位「自己抽自己 = 0」的重入标记），
        # 所以这里断言的写法从 `final long extracted = ...` 变成先在 try 里赋值。
        and "extracted = network.getComponent(EnergyNetworkComponent.class).extract(request);" in tick
        and "pullingFromNetwork = true;" in tick
        and "pullingFromNetwork = false;" in tick
        and "final long received = receiveIntoCluster(extracted);" in tick
        and "if (received < extracted) {" in tick
    )


# ==================== ⑥ 能力 / 端侧 ====================

def capability_source_ok(text: str) -> bool:
    """⑥-b：能力视图（round106 语义）。

    第 106 轮口径变了：**外部 FE 的注入 / 抽取永不受门控**，门控只拦「从 RS 网络抽电」。
    因此判据从「receiveEnergy 里有 DENY_LOW_CONTROLLER 分支」变成「receiveEnergy 里**不许**
    出现任何门控读数」，同时 canReceive 必须是恒真。
    """
    body = strip_comments(text)
    receive = method_body(body, "public int receiveEnergy(final int toReceive, final boolean simulate)")
    can_receive = method_body(body, "public boolean canReceive()")
    extract = method_body(body, "public int extractEnergy(final int toExtract, final boolean simulate)")
    return (
        # 注入：完全不看门控（不能出现 getGateState / Gate.* / chargeTrend）
        "getGateState" not in receive
        and "Gate." not in receive
        and "chargeTrend" not in receive
        and "Math.min(Math.min((long) toReceive, transferLimit()), Math.max(0L, space))" in receive
        # canReceive 恒真：满了由空间夹量返回 0，不是「不可接收」
        and "return true;" in can_receive
        # 抽取：恒可用、也不看门控（备用电池 / 缓存的本质）
        and "Math.min(Math.min((long) toExtract, transferLimit())," in extract
        and "getGateState" not in extract
        and "Gate." not in extract
        and "return clampToInt(getClusterEnergyStored());" in body
        and "return clampToInt(getClusterCapacity());" in body
    )


def caps_registered_ok(text: str) -> bool:
    body = strip_comments(text)
    caps = method_body(body, "public static void registerCapabilities(final RegisterCapabilitiesEvent event)")
    return (
        "Capabilities.EnergyStorage.BLOCK" in caps
        and "RefinedStorageNeoForgeApi.INSTANCE.getNetworkNodeContainerProviderCapability()" in caps
        and caps.count("(blockEntity, direction) ->") >= 2
        and "blockEntity.getExternalEnergyStorage()" in caps
        and "blockEntity.getContainerProvider()" in caps
    )


def node_own_reporting_ok(text: str) -> bool:
    body = strip_comments(text)
    extract = method_body(body, "public long extract(final long amount)")
    return (
        "implements EnergyProvider" in body
        and "return blockEntity == null ? 0L : blockEntity.getOwnEnergyStored();" in body
        and "return blockEntity == null ? 0L : blockEntity.getOwnCapacity();" in body
        # 节点绝不报集群值：网络组件会逐 provider 求和，再报集群 = 重复计数
        and "getCluster" not in body
        # 第 106 轮：放电量被「非立方存量」的让位逻辑夹过 ⇒ 交给方块实体的是 want 而不是裸 amount
        and "return blockEntity.dischargeToNetwork(want);" in extract
        # 第 106 轮：自己抽自己 = 0（拉取路径的重入标记）
        and "if (blockEntity.isPullingFromNetwork()) {" in extract
    )


def no_client_writes_ok(be_text: str, block_text: str) -> bool:
    be = strip_comments(be_text)
    guarded = True
    for signature in (
        "public void onNetworkTick(@Nullable final Network network)",
        "public long dischargeToNetwork(final long amount)",
        "private long extractFromCluster(final long amount)",
        "private long receiveIntoCluster(final long amount)",
    ):
        body = method_body(be, signature)
        guarded = guarded and "isClientSide()" in body
    block = strip_comments(block_text)
    ticker = method_body(block, "public <T extends BlockEntity> BlockEntityTicker<T> getTicker(")
    return guarded and "if (level.isClientSide()) {" in ticker and "return null;" in ticker


# ==================== ⑦ 界面契约 ====================

def data_slots_ok(be_text: str, menu_text: str) -> bool:
    be = strip_comments(be_text)
    menu = strip_comments(menu_text)
    # 第 106 轮：20 → 21（新增数据槽 20 = 相邻供能三态）
    ok = "public static final int DATA_SLOT_COUNT = 21;" in be
    for index in range(21):
        ok = ok and re.search(r"case %d ->" % index, be) is not None
    # 每 tick 只算一次快照（21 个 slot 会被逐格读取，不能每格 O(N)）
    ok = ok and "private void refresh()" in be and "level.getGameTime()" in be
    ok = ok and "if (now == snapshotTick) {" in be
    # 趋势三槽（round103）：17 = 趋势编码、18/19 = 上窗口净变化 lo/hi（带符号，join 可还原负数）
    ok = ok and "case 17 -> trend;" in be
    ok = ok and "case 18 -> lo(trendDelta);" in be
    ok = ok and "case 19 -> hi(trendDelta);" in be
    # 相邻供能槽（round106）：20 = 服务端实测三态，refresh 只读缓存、绝不遍历邻居
    ok = ok and "case 20 -> adjacent;" in be
    ok = ok and "adjacent = FEEnergyCubeBlockEntity.this.adjacentSupplyState;" in be
    return (
        ok
        and "public static final int DATA_SLOT_COUNT = FEEnergyCubeBlockEntity.DATA_SLOT_COUNT;" in menu
        and "new SimpleContainerData(DATA_SLOT_COUNT)" in menu
        and "FEEnergyCubeBlockEntity.join(data.get(2), data.get(3))" in menu
        # 两个速率走数据槽（15/16）：界面绝不读 Config，服务端是什么就显示什么
        and "return data.get(15);" in menu
        and "return data.get(16);" in menu
        # 趋势 + 净变化给客户端（17/18/19）：界面绝不自己比较两次读数
        and "return data.get(17);" in menu
        and "FEEnergyCubeBlockEntity.join(data.get(18), data.get(19))" in menu
        # 相邻供能三态（20）
        and "return data.get(20);" in menu
    )


def menu_no_slots_ok(menu_text: str) -> bool:
    body = strip_comments(menu_text)
    return (
        "addSlot(" not in body
        and "public ItemStack quickMoveStack(final Player player, final int index) {" in body
        and "return ItemStack.EMPTY;" in body
        and "cube.getLevel().getBlockEntity(cube.getBlockPos()) == cube" in body
    )


def screen_blit_ok(screen_text: str) -> bool:
    """背景 blit 必须是 9 参（或 8 参）并把贴图真实宽高传给重载。

    <p>1.21.1 的 7 参重载 {@code blit(rl,x,y,u,v,w,h)} 把纹理尺寸硬编码成 256×256
    （{@code blit(rl,x,y,0,u,v,w,h,256,256)}）；对 176×166 的贴图只会取左上 121×108 再拉伸
    ⇒ 边框被裁、凹槽与手画填充区错位。这是「看图看不出、上机才花屏」的典型，必须在自检里钉死。
    """
    body = strip_comments(screen_text)
    calls = re.findall(r"blit\(\s*TEXTURE\s*,[^;]*?\);", body, re.S)
    if not calls:
        return False
    nine = "guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);"
    seven = "guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);"
    return nine in body and seven not in body


def screen_geometry_ok(screen_text: str) -> bool:
    """⑦-a：画面尺寸 + 能量条走共享实现 RsccEnergyBar（第 108 轮「反签」，判据只许升不许降）。

    旧判据（第 99~107 轮）钉的是「界面自己画」：`BAR_X/BAR_Y/BAR_W/BAR_H/BAR_INSET` 一组常量、
    `0xFF0D8FA5`/`0xFF18B6D1` 两个自绘色、`drawEnergyBar` 方法体、以及「`ProgressWidget`
    不在 body 里」。第 108 轮用户口径反向：「所有那种纯电的能量条……应该保持和那个范围充电器、
    以及那个控制器一样，你应该直接复用他们的代码」⇒ 判据必须一起反签，并且**加严**：

    * 画面与贴图契约不变：`imageWidth=176` / `imageHeight=166`，旧凹槽 (14,18) 14×60 仍在 PNG 里
      （本轮不动 PNG，只由代码外框盖住）；
    * 条 = `RsccEnergyBar`（内部 RS 原版 `ProgressWidget` + sprite
      `refinedstorage:widget/progress_bar`），几何 16 宽 × 58 高 @ (14,19)；
    * 界面内**零自绘能量条**：旧特征（`drawEnergyBar` / `BAR_FILL` / `BAR_INSET` / `filledPixels` /
      `0xFF0D8FA5` / `0xFF18B6D1`）一个都不许留，且**不许出现任何 `guiGraphics.fill(`**
      —— 本界面面板本来就是整张贴图，一条 fill 都不该有；出现 fill = 又回到手绘；
    * 不许在界面里再手搓 `ProgressWidget`（必须经共享类，防「三处各写一份」）；
    * tooltip 不许叠：`isOverReadout` 必须先 `isOverEnergyBar(...)` 后 `return false`，
      否则同一帧会出现 widget 的框 + 读数板的框两个。
    """
    body = strip_comments(screen_text)
    consts = int_consts(body)
    ok = (
        "this.imageWidth = 176;" in body
        and "this.imageHeight = 166;" in body
        and consts.get("ENERGY_BAR_X") == 14
        and consts.get("ENERGY_BAR_Y") == 19
        and consts.get("ENERGY_BAR_HEIGHT") == 58
    )
    # 共享实现的两个调用点：renderBg 画外框/槽底、init 挂 widget
    ok = ok and "RsccEnergyBar.drawTrack(" in body
    ok = ok and "leftPos + ENERGY_BAR_X" in body
    ok = ok and "topPos + ENERGY_BAR_Y" in body
    ok = ok and "ENERGY_BAR_HEIGHT);" in body
    ok = ok and "RsccEnergyBar.create(" in body
    ok = ok and "menu.getClusterEnergy()" in body and "menu.getClusterCapacity()" in body
    # 旧自绘实现的特征一个都不许留
    for dead in ("drawEnergyBar", "BAR_FILL", "BAR_INSET", "filledPixels",
                 "0xFF0D8FA5", "0xFF18B6D1", "guiGraphics.fill("):
        ok = ok and dead not in body
    # 不许在界面里手搓 widget（必须经 RsccEnergyBar）
    ok = ok and "ProgressWidget" not in body
    # tooltip 不可叠：读数板判定先让位给条
    readout = method_body(body, "private boolean isOverReadout(final int mouseX, final int mouseY)")
    ok = ok and "if (isOverEnergyBar(mouseX, mouseY))" in readout and "return false;" in readout
    return ok


def screen_no_shadow_ok(screen_text: str) -> bool:
    """⑦-b：本界面所有文字都不带阴影。

    第 104 轮：正文 7 行改走唯一工具 `RsccTextFit.drawFitLine(...)`（它内部就是
    `drawString(font, text, x, y, color, false)`，没有阴影参数），于是本类里只剩标题那一处
    直接 `drawString`。判据据此保持同等强度：**每一处**直接 drawString 都必须是 `false`，
    且文字绘制点仍 ≥2 处（标题 + 读数行），并确实用上了 drawFitLine（不允许退回别的文字 API）。
    """
    body = strip_comments(screen_text)
    calls = re.findall(r"drawString\((?:[^;]*?)\);", body, re.S)
    fitted = re.findall(r"RsccTextFit\.drawFitLine\((?:[^;]*?)\);", body, re.S)
    return (len(calls) + len(fitted) >= 2
            and bool(fitted)
            and all(", false)" in call for call in calls))


def screen_gate_from_server_ok(screen_text: str) -> bool:
    body = strip_comments(screen_text)
    return (
        "menu.getGateState()" in body
        and "FEEnergyCubeMenu.GATE_ALLOW" in body
        and "FEEnergyCubeMenu.GATE_DENY_LOW_CONTROLLER" in body
        and "FEEnergyCubeMenu.GATE_DENY_NOT_RISING" in body
        # 趋势也只读服务端数据槽（17）：客户端不自己比较两次读数
        and "menu.getTrendState()" in body
        and "FEEnergyCubeMenu.TREND_RISING" in body
        # 客户端不重算门控：界面里不许出现 Config 阈值参与判定的式子
        and "feEnergyCubeChargeThresholdPercent" not in body
        and "feEnergyCubeChargeThresholdAbsolute" not in body
    )


def signed_fe_source_ok(screen_text: str) -> bool:
    """净变化必须带符号显示：`RsccNumberField.grouped(long)` 内部 `raw()` 把负数夹成 0（数量口径的
    约定），直接拿它显示「−123 FE」会变成「0 FE」—— 正好把「网络在掉电」说成「没变化」。"""
    body = strip_comments(screen_text)
    signed = method_body(body, "private static String signedFe(final long value)")
    return (
        "final long magnitude = value == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(value);" in signed
        and 'return (value > 0L ? "+" : "-") + RsccNumberField.grouped(magnitude);' in signed
        and "signedFe(menu.getTrendDelta())" in body
    )


def screen_trend_row_ok(screen_text: str) -> bool:
    """⑨-f：第 7 行「网络电量：上涨/持平/下降」必须真的画出来，且词表三态齐全。

    这条盯的是「只改门控不改界面」（玩家看不到为什么没充）和「只改界面不改门控」（本轮核心反例）
    的**界面那一半**：ROW_7_Y 常量被 renderLabels 真正使用、三态文案键一个不少地出现在 Screen 里。
    """
    body = strip_comments(screen_text)
    labels = method_body(body, "protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY)")
    return (
        int_consts(body).get("ROW_7_Y") == 88
        and "drawRow(guiGraphics, ROW_7_Y, trendLine(), trendColor());" in labels
        and "private Component trendLine()" in body
        and "private String trendWordKey()" in body
        and all(key in body for key in (
            "gui.rs_create_compat.fe_energy_cube.trend",
            "gui.rs_create_compat.fe_energy_cube.trend.rising",
            "gui.rs_create_compat.fe_energy_cube.trend.flat",
            "gui.rs_create_compat.fe_energy_cube.trend.falling",
            "gui.rs_create_compat.fe_energy_cube.trend.unknown",
        ))
        # 界面上不允许出现「自己大于一下上一个值」这种客户端重算
        and "getTrendState()" in body
    )


SCREEN_KEYS = [
    "gui.rs_create_compat.fe_energy_cube.cluster",
    "gui.rs_create_compat.fe_energy_cube.cluster_energy",
    "gui.rs_create_compat.fe_energy_cube.cluster_stored",
    "gui.rs_create_compat.fe_energy_cube.own",
    "gui.rs_create_compat.fe_energy_cube.own_stored",
    "gui.rs_create_compat.fe_energy_cube.percent",
    "gui.rs_create_compat.fe_energy_cube.gate.allow",
    "gui.rs_create_compat.fe_energy_cube.gate.deny",
    "gui.rs_create_compat.fe_energy_cube.gate.nonetwork",
    "gui.rs_create_compat.fe_energy_cube.transfer",
    "gui.rs_create_compat.fe_energy_cube.pull",
    # ---- round103：网络电量趋势（「正在上涨」这条闸的界面）----
    "gui.rs_create_compat.fe_energy_cube.gate.not_rising",
    "gui.rs_create_compat.fe_energy_cube.trend",
    "gui.rs_create_compat.fe_energy_cube.trend.rising",
    "gui.rs_create_compat.fe_energy_cube.trend.flat",
    "gui.rs_create_compat.fe_energy_cube.trend.falling",
    "gui.rs_create_compat.fe_energy_cube.trend.unknown",
    "gui.rs_create_compat.fe_energy_cube.trend_delta",
]

# round103 新增的语言键（第 7 行趋势 + 新的拒绝文案）。lang 由 Lead 落盘：
# 这些键 <b>缺席时本脚本显式 SKIP</b>（不假装通过），一旦落盘自动转硬断言。
ROUND103_KEYS = [
    "gui.rs_create_compat.fe_energy_cube.gate.not_rising",
    "gui.rs_create_compat.fe_energy_cube.trend",
    "gui.rs_create_compat.fe_energy_cube.trend.rising",
    "gui.rs_create_compat.fe_energy_cube.trend.flat",
    "gui.rs_create_compat.fe_energy_cube.trend.falling",
    "gui.rs_create_compat.fe_energy_cube.trend.unknown",
    "gui.rs_create_compat.fe_energy_cube.trend_delta",
]

# round104 新增的语言键（「绝对模式下的门控行只写状态、数字进 tooltip」那两句）。
# 为什么必须新增：绝对模式的实参是「2,147,483,647 FE」这种十位数，`gate.deny` 整行中文 244px、
# 英文 225px，而第 4 行可用宽度只有 136px（缩到 0.6 倍也放不下）⇒ 主行改画完整短句，
# 控制器电量 / 阈值的完整数值由读数板的悬停提示给出（`gateTooltipLine()` 复用既有 gate.* 键）。
ROUND104_KEYS = [
    "gui.rs_create_compat.fe_energy_cube.gate.short.allow",
    "gui.rs_create_compat.fe_energy_cube.gate.short.deny",
]

# round106 新增的语言键（第 8 行「相邻供能：开/关/无相邻」）。
# 与 round103/104 同一套「缺席显式 SKIP、落盘自动转硬断言」的规则（lang 由 Lead 落盘）。
ROUND106_KEYS = [
    "gui.rs_create_compat.fe_energy_cube.adjacent",
    "gui.rs_create_compat.fe_energy_cube.adjacent.on",
    "gui.rs_create_compat.fe_energy_cube.adjacent.off",
    "gui.rs_create_compat.fe_energy_cube.adjacent.none",
]

# 界面源码必须**恰好**引用的键集合（= 契约；第 104 轮把上面两句短句也算进契约，第 106 轮加入相邻供能 4 条）。
# 它们由 Lead 落盘；缺席时 ⑧-e3/e4 显式 SKIP（不假装通过），落盘后自动转硬断言。
ALL_SCREEN_KEYS = SCREEN_KEYS + ROUND104_KEYS + ROUND106_KEYS


def screen_render_tooltip_ok(screen_text: str) -> bool:
    """渲染链可达：必须覆写 `render(...)` 并在 `super.render` 之后<b>手动</b>调用 `renderTooltip(...)`。

    1.21.1 的 `AbstractContainerScreen#render` 方法体里没有任何 `renderTooltip` 调用
    （javap -c 逐字节核实：只做背景/事件/控件/槽位/浮动物品后直接 return），
    `protected void renderTooltip(...)` 是留给子类的钩子 ⇒ 不手动调，悬浮提示永远不出现。
    这条与 `selfcheck_round95_screen_tooltips.py` 的 ② 可达性同源。
    """
    body = strip_comments(screen_text)
    render = method_body(
        body, "public void render(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,")
    if not render:
        return False
    return "super.render(guiGraphics, mouseX, mouseY, partialTick);" in render \
        and "renderTooltip(guiGraphics, mouseX, mouseY);" in render


def screen_no_config_ok(screen_text: str) -> bool:
    """界面不得读 Config.*：所有数值（含两个速率）必须来自服务端数据槽。"""
    body = strip_comments(screen_text)
    return (
        "Config." not in body
        and "menu.getMaxTransfer()" in body
        and "menu.getNetworkPullPerTick()" in body
        # 数字格式统一走仓库现成的千分位，不再自造 M/k 缩写
        and "RsccNumberField.grouped(" in body
    )


def registry_ok(registry_text: str) -> bool:
    """网络级立方登记表：按 Network 分组、离开即注销、读时自愈剔幽灵。"""
    body = strip_comments(registry_text)
    return (
        "WeakHashMap<>();" in body
        and "public static void register(" in body
        and "public static void unregister(" in body
        and "nodes.removeIf(node -> node.getNetwork() != network || !node.isAlive());" in body
        and "public static long totalStored(" in body
        and "public static long totalCapacity(" in body
    )


def node_registration_ok(node_text: str) -> bool:
    """节点在 setNetwork 变更时登记 / 注销；并提供 isAlive / 兜底注销给登记表用。"""
    body = strip_comments(node_text)
    return (
        "public void setNetwork(@Nullable final Network network) {" in body
        and "EnergyCubeNetworkRegistry.unregister(previous, this);" in body
        and "EnergyCubeNetworkRegistry.register(network, this);" in body
        and "public boolean isAlive()" in body
        and "public void unregisterFromNetwork()" in body
    )


def capability_long_space_ok(be_text: str) -> bool:
    """「还能装多少」必须用 long 精确值算：int 饱和只出现在返回值，不参与空间计算。"""
    body = strip_comments(be_text)
    receive = method_body(body, "public int receiveEnergy(final int toReceive, final boolean simulate)")
    return (
        "final long space = getClusterCapacity() - getClusterEnergyStored();" in receive
        and "final long request = Math.min(Math.min((long) toReceive, transferLimit()), Math.max(0L, space));" in receive
        and "return clampToInt(request);" in receive
    )


def screen_keys_ok(screen_text: str) -> bool:
    body = strip_comments(screen_text)
    used = set(re.findall(r'"(gui\.rs_create_compat\.fe_energy_cube\.[a-z_.]+)"', body))
    return used == set(ALL_SCREEN_KEYS)


def translatable_arg_counts(screen_text: str) -> dict:
    """扫出本类里每个 `Component.translatable("key", ...)` 传了几个实参（key 本身不算）。

    用真实括号深度扫描，而不是正则凑：实参里有嵌套调用
    （`RsccNumberField.grouped(menu.getMaxTransfer())`、`formatPercent(a, b)`），
    逗号出现在深度 >1 时不能切。同名 key 出现多次取<b>最大值</b>（保守：任一处传满即算过关）。
    """
    body = strip_comments(screen_text)
    token = "Component.translatable("
    counts = {}
    index = 0
    while True:
        start = body.find(token, index)
        if start < 0:
            break
        cursor = start + len(token)
        depth = 1
        begin = cursor
        args = []
        while cursor < len(body) and depth > 0:
            char = body[cursor]
            if char in "([{":
                depth += 1
            elif char in ")]}":
                depth -= 1
                if depth == 0:
                    args.append(body[begin:cursor])
                    break
            elif char == "," and depth == 1:
                args.append(body[begin:cursor])
                begin = cursor + 1
            cursor += 1
        index = cursor + 1
        if not args:
            continue
        head = args[0].strip()
        if len(head) < 2 or not head.startswith('"') or not head.endswith('"'):
            continue
        key = head[1:-1]
        counts[key] = max(counts.get(key, 0), len(args) - 1)
    return counts


_FMT = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXeEgGcCbBhH%]")


def format_spec_count(text: str) -> int:
    """数一个语言串里有几个「要实参」的格式符（`%%` 字面量转义不算）。"""
    return sum(1 for m in _FMT.finditer(text) if not m.group(0).endswith("%"))


def lang_placeholder_ok(screen_text: str, zh: dict, en: dict, keys=None) -> bool:
    """⑧-f：每个被引用的语言键，格式符个数 == Java 实参个数（两个方向都是 bug）。

    少传 ⇒ `String.format` 抛 `MissingFormatArgumentException` ⇒ 原版把整行渲染成
    「Format error: ...」（第 99 轮 `transfer` 真踩过：lang 双占位 / Java 单实参）；
    多传 ⇒ 死实参（且模板若真有两个占位会把同一个值显示成两处）。
    """
    keys = SCREEN_KEYS if keys is None else keys
    counts = translatable_arg_counts(screen_text)
    for key in keys:
        for table in (zh, en):
            if key not in table:
                return False
            if format_spec_count(table[key]) != counts.get(key, 0):
                return False
    return True


def lang_pair_same_shape_ok(zh: dict, en: dict, keys=None) -> bool:
    """⑧-g：同一 key 在 zh_cn / en_us 里占位符个数必须相同（否则只有一种语言会爆）。"""
    keys = SCREEN_KEYS if keys is None else keys
    for key in keys:
        if key not in zh or key not in en:
            return False
        if format_spec_count(zh[key]) != format_spec_count(en[key]):
            return False
    return True


# ==================== 主流程 ====================

def main() -> int:
    be_text = read(BE)
    node_text = read(NODE)
    cluster_text = read(CLUSTER)
    registry_text = read(REGISTRY)
    block_text = read(BLOCK)
    menu_text = read(MENU)
    screen_text = read(SCREEN)
    config_text = read(CONFIG)
    main_text = read(MAIN)
    client_init_text = read(CLIENT_INIT)

    # ---------------- ① 集群模型 ----------------
    print("=== ① 集群：Σ 自身存量 / Σ 自身容量；extract 字典序跨块、receive 先自己；缓存与失效 ===")
    check("①-a stored/capacity = Σ 各成员自身值，且不持有共享载荷 / 不做内容移交（与 RsccMachineCluster 的分界）",
          cluster_sums_ok(cluster_text))
    check("①-b extract 按成员（坐标字典序）逐个扣、receive 先填 preferred 再填其它；MAX_MEMBERS=64",
          cluster_order_ok(cluster_text))
    check("①-c 缓存 = WeakHashMap + 20 tick 周期校验 + 事件失效摘整片；穿行只读方块状态",
          cluster_cache_ok(cluster_text))
    check("①-d 客户端（非 ServerLevel）只返回单元素快照：不解析集群、不写缓存",
          cluster_client_safe_ok(cluster_text))
    check("①-h 网络级立方登记表：按 Network 分组、离开即注销、读时自愈剔幽灵（否则门控被幽灵节点关死）",
          registry_ok(registry_text))
    check("①-i 节点在 setNetwork 变更时登记/注销，并提供 isAlive / unregisterFromNetwork",
          node_registration_ok(node_text))
    check("①-j 方块实体 setRemoved 兜底注销网络登记",
          "mainNetworkNode.unregisterFromNetwork();" in strip_comments(be_text))
    counterexample("源码：登记表不剔幽灵（removeIf 改成空操作）⇒ ①-h 判负",
                   registry_ok(registry_text.replace(
                       "nodes.removeIf(node -> node.getNetwork() != network || !node.isAlive());",
                       "// no prune")) is False)
    counterexample("源码：节点 setNetwork 只调 super 不登记 ⇒ ①-i 判负",
                   node_registration_ok(node_text.replace(
                       "EnergyCubeNetworkRegistry.register(network, this);",
                       "// no register")) is False)

    blocks = [{"stored": 30_000_000}, {"stored": 40_000_000}, {"stored": 10_000_000}]
    check("①-e 模型：3 块 30M/40M/10M ⇒ 存量 80M、容量 150M（= 3×50M）",
          model_cluster_stored(blocks) == 80_000_000 and model_cluster_capacity(blocks) == 150_000_000)
    got = model_extract(blocks, 60_000_000)
    check("①-f 模型：extract(60M) 从坐标最小块开始扣 ⇒ 得 60M，剩 [0, 10M, 10M]",
          got == 60_000_000 and [b["stored"] for b in blocks] == [0, 10_000_000, 10_000_000])
    got = model_receive(blocks, 80_000_000, preferred=2)
    check("①-g 模型：receive(80M, 先第 3 块) ⇒ 收满 80M（先自己 40M，再按顺序补满）」",
          got == 80_000_000 and [b["stored"] for b in blocks] == [40_000_000, 10_000_000, 50_000_000])
    counterexample("模型：extract 改成从成员末尾开始扣 ⇒ 每块扣减结果改变（30M 块不再被抽）",
                   (lambda: (lambda mut: (model_extract(mut, 60_000_000),
                                          [b["stored"] for b in mut])[1] != [0, 10_000_000, 10_000_000])(
                       [{"stored": 10_000_000}, {"stored": 40_000_000}, {"stored": 30_000_000}]))())
    counterexample("源码：stored() 不累加自身值（写成 0）⇒ ①-a 判负",
                   cluster_sums_ok(cluster_text.replace(
                       "sum += cube.getOwnEnergyStored();", "sum += 0;")) is False)
    counterexample("源码：把共享载荷搬回来（出现 payload）⇒ ①-a 判负",
                   cluster_sums_ok(cluster_text.replace(
                       "public long stored() {", "public long stored() {\n            Object payload = null;")) is False)
    counterexample("源码：extract 只扣第 0 块（不按成员遍历）⇒ ①-b 判负",
                   cluster_order_ok(cluster_text.replace(
                       "for (final BlockPos pos : members)", "for (final BlockPos pos : members.subList(0, 1))")) is False)

    # ---------------- ② 自身记账 / NBT ----------------
    print("=== ② 自身记账 + NBT 往返：每块自己的 EnergyStorage；读档直接设值（不走 receiveEnergy） ===")
    check("②-a 每块自己的 OwnEnergyStorage；restoreEnergy 直接设 energy；save/load 的 Energy 键成对",
          be_own_storage_ok(be_text))
    check("②-b 模型：直接设值恢复 50M ⇒ 50M（与 maxReceive 无关）", model_restore_direct(50_000_000) == 50_000_000)
    check("②-c 模型：NBT 往返（5000 万 → tag → 5000 万）不变", model_restore_direct(50_000_000) == 50_000_000)
    check("②-d 模型：超过容量的存档值夹到 5000 万（不溢出、不丢约束）",
          model_restore_direct(60_000_000) == CAP)
    counterexample("模型：用 receiveEnergy 恢复 50M ⇒ 只写进 maxReceive（100k）= 电量丢失",
                   model_restore_via_receive(50_000_000) != 50_000_000)
    counterexample("源码：读档改回 receiveEnergy ⇒ ②-a 判负",
                   be_own_storage_ok(be_text.replace(
                       "energyStorage.restoreEnergy(saved);",
                       "energyStorage.receiveEnergy(saved, false);")) is False)

    # ---------------- ③ 破坏保留 ----------------
    print("=== ③ 破坏保留：掉落物带本块那一份；其余块一格不动（不平均 / 不迁移） ===")
    check("③-a getDrops 只把本块电量经 saveToItem 写进方块物品；掉落为空时如实放弃（不假装）",
          drop_preserves_ok(block_text))
    check("③-b setRemoved 只作废集群缓存：没有把电量搬给邻居 / 合并 / 平均",
          removed_keeps_others_ok(be_text))
    broken = [{"stored": 50_000_000}, {"stored": 10_000_000}, {"stored": 45_000_000}]
    dropped = model_break(broken, 1)
    check("③-c 模型：拆掉 10M 那块 ⇒ 掉落物 10M，其余 [50M, 45M] 一格不动，容量 2×50M",
          dropped == 10_000_000 and [b["stored"] for b in broken] == [50_000_000, 45_000_000]
          and model_cluster_capacity(broken) == 2 * CAP
          and model_cluster_stored(broken) <= model_cluster_capacity(broken))
    averaged = [{"stored": 50_000_000}, {"stored": 10_000_000}, {"stored": 45_000_000}]
    total = model_cluster_stored(averaged)
    model_break(averaged, 1)
    for block in averaged:
        block["stored"] = total // 3
    counterexample("模型：拆一块时按平均值重分配 ⇒ 幸存块电量被改动（用户口径禁止）",
                   [b["stored"] for b in averaged] != [50_000_000, 45_000_000])
    counterexample("源码：getDrops 改成把集群总和塞进物品 ⇒ ③-a 判负",
                   drop_preserves_ok(block_text.replace(
                       "cube.getOwnEnergyStored() <= 0", "cube.getClusterEnergyStored() <= 0")) is False)
    counterexample("源码：setRemoved 里把电量搬给邻居（调用 receiveOwn）⇒ ③-b 判负",
                   removed_keeps_others_ok(be_text.replace(
                       "mainNetworkNode.unregisterFromNetwork();",
                       "mainNetworkNode.unregisterFromNetwork();\n        receiveOwn(1L);")) is False)

    # ---------------- ④ 门控三态 ----------------
    print("=== ④ 门控三态：无网络 / 允许 / 低电拒绝；读数减掉自身贡献；percent 用 double ===")
    check("④-a computeGate：三态齐全、absolute 用 long、percent 用 double（容量 0 ⇒ 0%）、"
          "读数 = 网络总量 − 本网络所有立方",
          gate_source_ok(be_text))
    check("④-b 模型：无网络 ⇒ NO_NETWORK（单独立着 = 普通电池，允许外部 FE 充放）",
          model_gate(None, 0, 0, has_network=False) == GATE_NO_NETWORK)
    # 控制器 1M 容量、90% 电量；本网络立方共 3 块空（150M 容量）⇒ 网络池 = 控制器 + 立方
    cube_stored, cube_capacity = 0, 150_000_000
    network = (900_000 + cube_stored, 1_000_000 + cube_capacity)
    check("④-c 模型：控制器 90%/阈值 90% ⇒ ALLOW（减掉立方后 90% ≥ 90%，含等号）",
          model_gate(network, cube_stored, cube_capacity, has_network=True) == GATE_ALLOW)
    check("④-d 模型：控制器 89% ⇒ DENY_LOW_CONTROLLER",
          model_gate((890_000, 1_000_000 + cube_capacity), cube_stored, cube_capacity,
                     has_network=True) == GATE_DENY)
    check("④-e 模型：absolute 模式 阈值 1,000,000 ⇒ 恰等放行 / 差 1 拒绝",
          model_gate((1_000_000, 1_000_000 + cube_capacity), cube_stored, cube_capacity,
                     has_network=True, absolute_mode=True, absolute_threshold=1_000_000) == GATE_ALLOW
          and model_gate((999_999, 1_000_000 + cube_capacity), cube_stored, cube_capacity,
                         has_network=True, absolute_mode=True, absolute_threshold=1_000_000) == GATE_DENY)
    check("④-f 模型：创造控制器（Long.MAX_VALUE）⇒ percent 判 ALLOW（double 不溢出）",
          model_gate((LONG_MAX, LONG_MAX), cube_stored, cube_capacity,
                     has_network=True) == GATE_ALLOW)
    # 多簇场景：控制器满电 100%，本集群空，另有 10 块别的立方簇空着（500M 容量）
    other_cubes = 10 * CAP
    multi = (1_000_000, 1_000_000 + other_cubes)
    check("④-g 模型：同网多组立方簇 ⇒ 必须减掉**全部**立方：控制器 100% 判 ALLOW",
          model_gate(multi, 0, other_cubes, has_network=True) == GATE_ALLOW)
    counterexample("模型：只减自己这一簇（本集群空 ⇒ 等于不减）⇒ 被别的立方簇的巨大分母压成 0.2% ⇒ 判 DENY"
                   "（『网络里立方越多、闸越关不上』）",
                   model_gate(multi, 0, 0, has_network=True) == GATE_DENY)
    counterexample("模型：完全不减 ⇒ 控制器 90% 却被 150M 分母压到 0.6% ⇒ 判 DENY（功能废掉）",
                   model_gate(network, cube_stored, cube_capacity, has_network=True,
                              subtract_cubes=False) == GATE_DENY)
    counterexample("源码：门控读数不减立方（直接用 networkStored）⇒ ④-a 判负",
                   gate_source_ok(be_text.replace(
                       "return Math.max(0L, networkStored - cubeStored);",
                       "return Math.max(0L, networkStored);")) is False)
    counterexample("源码：只减本集群（cubeStored 换回 snapshot.stored()）⇒ ④-a 判负",
                   gate_source_ok(be_text.replace(
                       "EnergyCubeNetworkRegistry.totalStored(network)",
                       "snapshot.stored()")) is False)

    # ---------------- ⑤ 抽取三重夹 + 守恒 ----------------
    print("=== ⑤ 网络抽取：三重夹（空间 / 每 tick / 非立方存量）+ 抽到多少收多少 ===")
    check("⑤-a doWork：门控通过才抽、三重夹齐全、抽完 receive、收不下要 WARN",
          pull_source_ok(be_text))
    # 3 块都接近满：48M + 50M + 49.9995M ⇒ 集群只剩 500 空间
    nearly_full = [{"stored": 49_999_500}, {"stored": CAP}, {"stored": CAP}]
    request, received = model_pull(nearly_full, others_stored=10_000_000)
    check("⑤-b 模型：集群只剩 500 空间 ⇒ 只抽 500（不按 space 无条件抽满；全收下、无损耗）",
          request == 500 and received == 500)
    request, received = model_pull([{"stored": 0}, {"stored": 0}, {"stored": 0}], others_stored=5_000)
    check("⑤-c 模型：非立方 provider 只有 5k ⇒ 只抽 5k（绝不反过来抽自己/同网其它立方）",
          request == 5_000 and received == 5_000)
    request, received = model_pull([{"stored": 0}], others_stored=10 ** 12)
    check("⑤-d 模型：正常情形只按每 tick 上限抽（10k），且抽到的整份收下",
          request == PER_TICK and received == PER_TICK)
    unclamped = model_pull([{"stored": 49_999_500}, {"stored": CAP}, {"stored": CAP}],
                           others_stored=10_000_000, clamp_space=False)
    counterexample("模型：去掉「集群剩余空间」夹 ⇒ 抽的比收的多（差额 = 凭空消失的能量）",
                   unclamped[1] < unclamped[0])
    counterexample("源码：抽取量改成无条件按空间抽（去掉 others 夹）⇒ ⑤-a 判负",
                   pull_source_ok(be_text.replace(
                       "Math.min(Math.min(space, (long) Config.feEnergyCubeNetworkPullPerTick), others)",
                       "space")) is False)

    # ---------------- ⑥ 能力 / 端侧 ----------------
    print("=== ⑥ 能力注册 / 两套口径 / 客户端不写世界 ===")
    check("⑥-a 能力：EnergyStorage.BLOCK 六面 + RS 网络节点容器能力；两个 lambda 都忽略 direction（六面同一视图）",
          caps_registered_ok(be_text))
    check("⑥-b 能力视图（第 106 轮语义）：报集群、按 feEnergyCubeMaxTransfer 限速、"
          "输入/输出都**不看门控**（外部 FE 注入 / 抽取永不受门控）",
          capability_source_ok(be_text))
    check("⑥-c 节点口径：getStored/getCapacity 只报本块（网络组件求和后才 = 集群总量）",
          node_own_reporting_ok(node_text))
    check("⑥-d 客户端不写世界：ticker 客户端返回 null；四个写入口都有 isClientSide 判断",
          no_client_writes_ok(be_text, block_text))
    check("⑥-e 模型：能力视图 int 饱和与 Java 一致（64 块 3.2G 饱和到 Integer.MAX_VALUE）",
          clamp_int(64 * CAP) == INT_MAX and clamp_int(150_000_000) == 150_000_000)
    check("⑥-f 43 块边界：对外容量饱和到 Integer.MAX_VALUE，但内部「还能装多少」仍用 long 精确值"
          "（灌满到 int 上限后还剩 2,516,353 FE 可装，绝不当成 0）",
          capability_long_space_ok(be_text)
          and clamp_int(43 * CAP) == INT_MAX
          and 43 * CAP - INT_MAX == 2_516_353)
    counterexample("源码：把「控制器低电」门控加回 receiveEnergy ⇒ ⑥-b 判负"
                   "（第 106 轮口径：外部注入永不受门控，否则玩家自己接的发电机被网络状态拦掉）",
                   capability_source_ok(be_text.replace(
                       "public int receiveEnergy(final int toReceive, final boolean simulate) {",
                       "public int receiveEnergy(final int toReceive, final boolean simulate) {\n"
                       "            final Gate gate = getGateState();\n"
                       "            if (gate == Gate.DENY_LOW_CONTROLLER) { return 0; }")) is False)
    counterexample("源码：extractEnergy 也加门控 ⇒ ⑥-b 判负（低电时连放电都被禁，备用电池失效）",
                   capability_source_ok(be_text.replace(
                       "final long available = Math.min(Math.min((long) toExtract, transferLimit()),",
                       "getGateState(); final long available = Math.min(Math.min((long) toExtract, transferLimit()),")) is False)
    counterexample("源码：节点改报集群值 ⇒ ⑥-c 判负（网络组件求和后重复计数）",
                   node_own_reporting_ok(node_text.replace(
                       "blockEntity.getOwnEnergyStored();", "blockEntity.getClusterEnergyStored();")) is False)
    counterexample("源码：客户端也返回 ticker ⇒ ⑥-d 判负（客户端会跑集群解析 = 写缓存）",
                   no_client_writes_ok(be_text, block_text.replace(
                       "if (level.isClientSide()) {\n            return null; // 客户端不 tick",
                       "if (false) {\n            return null; // 客户端不 tick")) is False)

    # ---------------- ⑦ 界面契约 ----------------
    print("=== ⑦ 界面/数据槽：176×166、能量条走 RsccEnergyBar（16×58 @ (14,19)，外框 18×60 @ (13,18)）、"
          "无自绘 fill、tooltip 不叠、21 槽、不读 Config ===")
    check("⑦-a 画面 176×166；能量条走共享 RsccEnergyBar（RS 原版 ProgressWidget，16×58 @ (14,19)）；"
          "界面零自绘（drawEnergyBar/BAR_FILL/filledPixels/guiGraphics.fill 全无）；tooltip 不叠",
          screen_geometry_ok(screen_text))
    check("⑦-a2 背景 blit 走 9 参且传真实贴图宽高（7 参会把 256×256 写死 ⇒ 边框被裁 + 凹槽错位）",
          screen_blit_ok(screen_text))
    check("⑦-b 全部文字 dropShadow=false（title / 五行 / tooltip 同口径）", screen_no_shadow_ok(screen_text))
    check("⑦-c 门控状态只读服务端数据槽（客户端不重算阈值）", screen_gate_from_server_ok(screen_text))
    check("⑦-d 语言键引用集合 = 契约里的 %d 条（不多不少）" % len(ALL_SCREEN_KEYS), screen_keys_ok(screen_text))
    check("⑦-e 数据槽 21 个齐（含 17 趋势 / 18+19 净变化 / 20 相邻供能）、BE 每 tick 只算一次快照、"
          "菜单用同一组常量与 lo/hi 还原",
          data_slots_ok(be_text, menu_text))
    check("⑦-f 菜单无物品槽（addSlot 零调用）、快速移动空实现、stillValid 校验方块实体身份",
          menu_no_slots_ok(menu_text))
    check("⑦-g 界面不读 Config.*（速率走服务端数据槽），数字统一走 RsccNumberField.grouped",
          screen_no_config_ok(screen_text))
    check("⑦-h 渲染链可达：覆写 render(...) 且在 super.render 后手动调 renderTooltip"
          "（原版不会替子类调，否则电量条悬浮提示永不出现）",
          screen_render_tooltip_ok(screen_text))
    counterexample("源码：把 drawTrack 调用注释掉（外框/槽底没了）⇒ ⑦-a 判负",
                   screen_geometry_ok(screen_text.replace(
                       "RsccEnergyBar.drawTrack(", "// RsccEnergyBar.drawTrack(")) is False)
    counterexample("源码：把共享 create 换回自绘方法名 ⇒ ⑦-a 判负",
                   screen_geometry_ok(screen_text.replace(
                       "RsccEnergyBar.create(", "drawEnergyBarSelf(")) is False)
    counterexample("源码：把条高 58 改回旧自绘的 60 ⇒ ⑦-a 判负（外框不能再完整盖住旧凹槽）",
                   screen_geometry_ok(screen_text.replace(
                       "ENERGY_BAR_HEIGHT = 58", "ENERGY_BAR_HEIGHT = 60")) is False)
    counterexample("源码：界面里又手写一条 guiGraphics.fill 自绘条 ⇒ ⑦-a 判负",
                   screen_geometry_ok(screen_text.replace(
                       "RsccEnergyBar.drawTrack(guiGraphics,",
                       "guiGraphics.fill(0, 0, 1, 1, 0xFF0D8FA5);\n"
                       "        RsccEnergyBar.drawTrack(guiGraphics,")) is False)
    counterexample("源码：读数板 tooltip 不再让位给条（同一帧两个框）⇒ ⑦-a 判负",
                   screen_geometry_ok(screen_text.replace(
                       "if (isOverEnergyBar(mouseX, mouseY)) {", "if (false) {")) is False)
    counterexample("源码：blit 改回 7 参（纹理尺寸写死 256×256）⇒ ⑦-a2 判负",
                   screen_blit_ok(screen_text.replace(
                       "guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);",
                       "guiGraphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight);")) is False)
    counterexample("源码：标题改回带阴影 drawString(..., true) ⇒ ⑦-b 判负",
                   screen_no_shadow_ok(screen_text.replace(
                       "title, titleLabelX, titleLabelY, COLOR_TITLE, false",
                       "title, titleLabelX, titleLabelY, COLOR_TITLE, true")) is False)
    counterexample("源码：界面自己拿 Config 阈值重算门控 ⇒ ⑦-c 判负",
                   screen_gate_from_server_ok(screen_text.replace(
                       "default:", "case 7: Config.feEnergyCubeChargeThresholdPercent = 0;")) is False)
    counterexample("源码：删掉 render() 里手动调 renderTooltip ⇒ ⑦-h 判负（tooltip 永不绘制）",
                   screen_render_tooltip_ok(screen_text.replace(
                       "        renderTooltip(guiGraphics, mouseX, mouseY);", "")) is False)
    counterexample("源码：界面又读回本地 Config 速率 ⇒ ⑦-g 判负（专服上会显示错的速率）",
                   screen_no_config_ok(screen_text.replace(
                       "RsccNumberField.grouped(menu.getMaxTransfer())",
                       "RsccNumberField.grouped(Config.feEnergyCubeMaxTransfer)")) is False)
    counterexample("源码：菜单加一个物品槽 ⇒ ⑦-f 判负",
                   menu_no_slots_ok(menu_text.replace(
                       "addDataSlots(data);", "addDataSlots(data);\n        addSlot(new Slot(inventory, 0, 8, 8));")) is False)

    # ---------------- ⑧ 注册 / 资源 / 语言 ----------------
    print("=== ⑧ 注册（Lead 写域只读核对）/ 资源状态 / 语言键 ===")
    registered = (
        "FE_ENERGY_CUBE_BLOCK" in main_text
        and "FE_ENERGY_CUBE_ITEM" in main_text
        and "FE_ENERGY_CUBE_BLOCK_ENTITY" in main_text
        and "FE_ENERGY_CUBE_MENU" in main_text
        and "FEEnergyCubeBlockEntity::registerCapabilities" in main_text
    )
    check("⑧-a 主类：四个注册项 + registerCapabilities 监听都在", registered)
    config_ok = (
        "feEnergyCubeCapacity" in config_text
        and "feEnergyCubeMaxTransfer" in config_text
        and "feEnergyCubeChargeMode" in config_text
        and "feEnergyCubeChargeModeAbsolute" in config_text
        and "feEnergyCubeChargeThresholdPercent" in config_text
        and "feEnergyCubeChargeThresholdAbsolute" in config_text
        and "feEnergyCubeNetworkPullPerTick" in config_text
    )
    check("⑧-b Config：6 个键 + 模式解析都在（本任务只读）", config_ok)
    if "FEEnergyCubeScreen" in client_init_text:
        check("⑧-c ClientInit 已注册 FEEnergyCubeScreen", True)
    else:
        skip("⑧-c ClientInit 的 FEEnergyCubeScreen 注册尚未落地（Lead 写域，待其添加；本脚本不做假通过）")
    blockstates = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                               "blockstates", "fe_energy_cube.json")
    if os.path.exists(blockstates):
        states = read(blockstates)
        check("⑧-d blockstates/fe_energy_cube.json 单变体（方块无 BlockState 属性）",
              '"variants"' in states and '"facing"' not in states and '"active"' not in states)
    else:
        skip("⑧-d blockstates/fe_energy_cube.json 尚未产出（资源同学写域）")
    lang_paths = {code: os.path.join(LANG_DIR, code + ".json") for code in ("zh_cn", "en_us")}
    if all(os.path.exists(path) for path in lang_paths.values()):
        zh = read(lang_paths["zh_cn"])
        en = read(lang_paths["en_us"])
        present = [key for key in SCREEN_KEYS if key in zh or key in en]
        if not present:
            skip("⑧-e 界面语言键尚未写进 zh_cn/en_us（资源同学写域；产出后本脚本自动转硬断言）")
        else:
            legacy_keys = [key for key in SCREEN_KEYS
                           if key not in ROUND103_KEYS and key not in ROUND104_KEYS]
            check("⑧-e 界面语言键 zh/en 全部存在（且成对）",
                  all(key in zh and key in en for key in legacy_keys))
            # round103 新增的 7 条键：lang 由 Lead 落盘。缺席时**显式 SKIP**（不假装通过），
            # 一旦落盘自动转硬断言（键存在 + 占位符个数 + zh/en 同构）。
            missing_new = [key for key in ROUND103_KEYS if key not in zh and key not in en]
            if missing_new:
                skip("⑧-e2 round103 新增 %d 条语言键未落盘（Lead 写域；清单见交付回报）：%s"
                     % (len(missing_new), "、".join(key.rsplit(".", 1)[-1] for key in missing_new)))
                ready_keys = legacy_keys
            else:
                check("⑧-e2 round103 新增 %d 条语言键 zh/en 全部存在（趋势三态 + 新拒绝文案 + tooltip 净变化）"
                      % len(ROUND103_KEYS), True)
                ready_keys = SCREEN_KEYS
            # round104 新增的 2 条短句键（绝对模式门控行）：与 round103 同一套「缺席 SKIP、落盘转硬断言」，
            # 落盘后还要核对它们确实是 **0 个 %s**（Java 侧 `Component.translatable(shortGateKey())`
            # 不传实参，多一个占位符就会变成 Format error）。
            # 注意：main 里 zh/en 此时还是「文件原文」，要判字段必须自己 json.loads 一次。
            try:
                zh_map = json.loads(zh)
                en_map = json.loads(en)
            except ValueError:
                zh_map, en_map = {}, {}
            missing_104 = [key for key in ROUND104_KEYS if key not in zh_map and key not in en_map]
            if missing_104:
                skip("⑧-e3 round104 新增 %d 条语言键未落盘（Lead 写域；清单见交付回报）：%s"
                     % (len(missing_104), "、".join(k.rsplit(".", 1)[-1] for k in missing_104)))
            else:
                check("⑧-e3 round104 新增 %d 条短句键 zh/en 成对且 0 个 %%s"
                      "（绝对模式门控行不传实参）"
                      % len(ROUND104_KEYS),
                      all(zh_map.get(key) and en_map.get(key)
                          and format_spec_count(zh_map[key]) == 0
                          and format_spec_count(en_map[key]) == 0 for key in ROUND104_KEYS))
                ready_keys = list(ready_keys) + [key for key in ROUND104_KEYS
                                                 if key not in ready_keys]
            # round106 新增的 4 条键（第 8 行「相邻供能：开/关/无相邻」）：同一套「缺席 SKIP、
            # 落盘转硬断言」。落盘后要核对：adjacent 外层恰好 1 个 %s，三个词键恰好 0 个 %s
            # （Java 侧 `Component.translatable(adjacentWordKey())` 不传实参）。
            missing_106 = [key for key in ROUND106_KEYS if key not in zh_map and key not in en_map]
            if missing_106:
                skip("⑧-e4 round106 新增 %d 条语言键未落盘（Lead 写域；清单见交付回报）：%s"
                     % (len(missing_106), "、".join(k.rsplit(".", 1)[-1] for k in missing_106)))
            else:
                check("⑧-e4 round106 新增 %d 条相邻供能键 zh/en 成对（外层 1 个 %%s、三个词键 0 个 %%s）"
                      % len(ROUND106_KEYS),
                      all(zh_map.get(key) and en_map.get(key) for key in ROUND106_KEYS)
                      and format_spec_count(zh_map[ROUND106_KEYS[0]]) == 1
                      and format_spec_count(en_map[ROUND106_KEYS[0]]) == 1
                      and all(format_spec_count(zh_map[key]) == 0
                              and format_spec_count(en_map[key]) == 0 for key in ROUND106_KEYS[1:]))
                ready_keys = list(ready_keys) + [key for key in ROUND106_KEYS
                                                 if key not in ready_keys]
            # ⑧-f/g：占位符 × 实参个数逐键核对（第 99 轮资源同学抓到的真 bug：lang 的
            # transfer 是双占位，Java 只传 1 个实参 ⇒ MissingFormatArgumentException ⇒
            # 原版把整行渲染成「Format error: ...」。只查「键存在」是查不出这种的。）
            try:
                zh_table = json.loads(zh)
                en_table = json.loads(en)
            except ValueError as exc:
                skip("⑧-f/g 语言文件不是严格 JSON（%s），无法逐键核对占位符" % exc)
            else:
                check("⑧-f 每个界面语言键的格式符个数 == Java 实参个数（少传实参 ⇒ 该行变 Format error）",
                      lang_placeholder_ok(screen_text, zh_table, en_table, ready_keys))
                check("⑧-g zh_cn / en_us 同一个键的格式符个数相同（否则只有一种语言会爆）",
                      lang_pair_same_shape_ok(zh_table, en_table, ready_keys))
                # 反例：把 lang 的 transfer 退回第 99 轮的双占位，则上面全线判负
                transfer_key = "gui.rs_create_compat.fe_energy_cube.transfer"
                legacy_zh = dict(zh_table)
                legacy_en = dict(en_table)
                legacy_zh[transfer_key] = "对外充放 %s FE/t，从网络抽取 %s FE/t"
                legacy_en[transfer_key] = "I/O %s FE/t, network intake %s FE/t"
                counterexample(
                    "lang：transfer 退回双占位（Java 只传 1 个实参）⇒ ⑧-f 判负（真实踩过的 bug）",
                    lang_placeholder_ok(screen_text, legacy_zh, en_table, [transfer_key]) is False
                    and lang_placeholder_ok(screen_text, zh_table, legacy_en, [transfer_key]) is False)
    else:
        skip("⑧-e lang 文件缺失（资源同学写域）")

    # ---------------- ⑨ round103：网络电量「正在上涨」闸（低优先级到底多低） ----------------
    print("=== ⑨ 低优先级闸：控制器 ≥ 阈值 **且** 网络电量在涨才从网络抽电；外部 FE 不受趋势闸 ===")
    check("⑨-a 源码：趋势按 5 tick 窗口采样、严格大于才 RISING（相等＝持平）、量的是网络能量池总量"
          "（非立方存量会被本立方自己的抽取压低 ⇒ 负反馈 / 多簇互相打架）、无基线时 UNKNOWN 不算上涨、"
          "门控只读缓存不采样、换网络 / 读档作废基线",
          trend_source_ok(be_text))
    check("⑨-b 源码：computeGate = 阈值（低电优先报告）且 趋势必须 RISING 才 ALLOW",
          gate_source_ok(be_text))
    check("⑨-c 源码：外部 FE 的注入 / 抽取**永不受门控**（canReceive/canExtract 恒真；门控只留网络抽取那条路径）",
          external_fe_never_gated_ok(be_text))
    check("⑨-d 源码：数据槽 17 = 趋势、18/19 = 上窗口净变化（菜单 getter 齐），界面只读服务端",
          data_slots_ok(be_text, menu_text))
    check("⑨-e 源码：第 7 行趋势行真的画出来（ROW_7_Y=88、三态词表齐全、客户端不自己重算）",
          screen_trend_row_ok(screen_text))
    check("⑨-f 源码：净变化带符号显示（负值不许被 grouped 夹成 0）", signed_fe_source_ok(screen_text))

    # —— 用户口径的判定表：趋势（上涨/持平/下降）× 阈值（达标/不达标），外加「采样还没完成」——
    ctrl_cap, cube_cap = 1_000_000, 150_000_000
    high = (950_000, ctrl_cap + cube_cap)   # 控制器 95%（≥ 默认 90%）
    low_net = (800_000, ctrl_cap + cube_cap)  # 控制器 80%（< 默认 90%）
    table = [
        ("上涨", TREND_RISING, high, "≥ 阈值", GATE_ALLOW),
        ("上涨", TREND_RISING, low_net, "< 阈值", GATE_DENY),
        ("持平", TREND_FLAT, high, "≥ 阈值", GATE_DENY_NOT_RISING),
        ("持平", TREND_FLAT, low_net, "< 阈值", GATE_DENY),
        ("下降", TREND_FALLING, high, "≥ 阈值", GATE_DENY_NOT_RISING),
        ("下降", TREND_FALLING, low_net, "< 阈值", GATE_DENY),
        ("采样中", TREND_UNKNOWN, high, "≥ 阈值", GATE_DENY_NOT_RISING),
        ("采样中", TREND_UNKNOWN, low_net, "< 阈值", GATE_DENY),
    ]
    bad_rows = [name for name, trend, net, _, expect in table
                if model_charge_gate(net, 0, cube_cap, True, trend) != expect]
    check("⑨-g 模型：八态判定表（3 趋势 × 2 阈值 + 采样中 × 2）与 Java 完全一致", not bad_rows)
    check("⑨-h 模型：用户口径的核心 —— 「持平 + 控制器 95%」必须<b>不吸</b>（相等不算上涨）",
          model_charge_gate(high, 0, cube_cap, True, model_trend(1_000_000, 1_000_000))
          == GATE_DENY_NOT_RISING
          and model_charge_gate(high, 0, cube_cap, True, model_trend(1_000_000, 1_000_001))
          == GATE_ALLOW)
    check("⑨-i 模型（第 106 轮重新定性）：四种门控状态下，外部 FE 的注入 / 抽取**都放行**"
          "（门控只决定「要不要从网络抽电」）",
          all(model_external_fe_allowed(gate)
              for gate in (GATE_NO_NETWORK, GATE_ALLOW, GATE_DENY, GATE_DENY_NOT_RISING)))
    counterexample("模型：门控只判阈值（round103 之前的版本）⇒ 持平/下降 + 达标 全变 ALLOW，"
                   "用户可以白嫖「网络静止也充」（本轮核心反例）",
                   any(model_charge_gate_threshold_only(net, 0, cube_cap, True, trend) == GATE_ALLOW
                       for _, trend, net, _, expect in table
                       if trend in (TREND_FLAT, TREND_FALLING)
                       and expect == GATE_DENY_NOT_RISING))
    counterexample("模型：把「持平」当上涨（判据写成 delta >= 0）⇒ 持平被判 RISING（用户明确禁止）",
                   model_trend_loose(1_000_000, 1_000_000) == TREND_RISING
                   and model_trend(1_000_000, 1_000_000) == TREND_FLAT)
    counterexample("模型：用非立方存量做趋势 ⇒ 本立方抽得越多「看起来涨得越少」，抽满就变「持平」"
                   "⇒ 门控停顿 → 网络再涨 → 再抽（自己把自己钉在振荡上）",
                   (30_000 - 50_000) <= 0 and (30_000 - 0) > 0)
    counterexample("模型：净变化直接走 grouped(long) ⇒ −123 FE 显示成 0（把「掉电」说成「没变化」）",
                   model_grouped_clamps_negative(-123) == "0"
                   and model_signed_fe(-123) == "-123")
    counterexample("源码：computeGate 去掉趋势判据（阈值达标就 ALLOW）⇒ ⑨-b 判负",
                   gate_source_ok(be_text.replace(
                       "return chargeTrend == Trend.RISING ? Gate.ALLOW : Gate.DENY_NOT_RISING;",
                       "return Gate.ALLOW;")) is False)
    counterexample("源码：上涨判据改成 delta >= 0（持平也算涨）⇒ ⑨-a 判负",
                   trend_source_ok(be_text.replace(
                       "chargeTrend = delta > 0L ? Trend.RISING : (delta < 0L ? Trend.FALLING : Trend.FLAT);",
                       "chargeTrend = delta >= 0L ? Trend.RISING : Trend.FALLING;")) is False)
    counterexample("源码：趋势改成量「网络池总量」⇒ ⑨-a 判负（外部 FE 给立方充电会把总量抬高 ⇒ 假阳性）",
                   trend_source_ok(be_text.replace(
                       "final long counterfactual = stats.nonCubeStored() + trendPulledSinceBaseline;",
                       "final long counterfactual = stats.networkStored();")) is False)
    counterexample("源码：采样不加回本立方自己抽走的量（直接 nonCubeStored）⇒ ⑨-a 判负"
                   "（一充就不涨 ⇒ 停抽 ⇒ 再涨 的自反馈振荡）",
                   trend_source_ok(be_text.replace(
                       "final long counterfactual = stats.nonCubeStored() + trendPulledSinceBaseline;",
                       "final long counterfactual = stats.nonCubeStored();")) is False)
    counterexample("源码：抽电后不记账（删掉 trendPulledSinceBaseline += extracted）⇒ ⑨-a 判负",
                   trend_source_ok(be_text.replace(
                       "        trendPulledSinceBaseline += extracted;", "")) is False)
    counterexample("模型：沿用 round103 口径（DENY_LOW_CONTROLLER 把外部注入也拦掉）⇒ 与 beta.9 用户口径矛盾"
                   "（「立方可以被别人注入、也可以被别的吸取」）—— 这是第 106 轮的根因反例",
                   model_external_fe_gated(GATE_DENY) is False
                   and not model_external_fe_allowed(GATE_DENY) is False)
    counterexample("源码：把门控加回 receiveEnergy（外部注入被拦）⇒ ⑨-c 判负",
                   external_fe_never_gated_ok(be_text.replace(
                       "public int receiveEnergy(final int toReceive, final boolean simulate) {",
                       "public int receiveEnergy(final int toReceive, final boolean simulate) {\n"
                       "            final Gate gate = getGateState();\n"
                       "            if (gate == Gate.DENY_LOW_CONTROLLER) { return 0; }")) is False)
    counterexample("源码：extractEnergy 被门控（低电时连放电都禁）⇒ ⑨-c 判负",
                   external_fe_never_gated_ok(be_text.replace(
                       "final long available = Math.min(Math.min((long) toExtract, transferLimit()),",
                       "getGateState(); final long available = "
                       "Math.min(Math.min((long) toExtract, transferLimit()),")) is False)
    counterexample("源码：界面不画趋势行（删掉第 7 行 drawRow）⇒ ⑨-e 判负（玩家看不出为什么没充）",
                   screen_trend_row_ok(screen_text.replace(
                       "        drawRow(guiGraphics, ROW_7_Y, trendLine(), trendColor());", "")) is False)
    counterexample("源码：数据槽 17 不发趋势（改成常量 0）⇒ ⑨-d 判负（界面拿不到服务端趋势）",
                   data_slots_ok(be_text.replace("case 17 -> trend;", "case 17 -> 0;"), menu_text) is False)

    # ---------------- ⑩ round106：立方 = 传递介质 / 缓存 ----------------
    print("=== ⑩ 第 106 轮：相邻主动供能 + 注入/抽取不受门控 + 网络池「立方最后」+ 界面相邻供能行 ===")
    check("⑩-a 源码：相邻推送先 simulate、再按 min(预算,存量,能吃) 扣、真收、收不下退回；"
          "不推能量立方、不加载区块、每 tick 六面分摊一个预算、不看门控",
          push_source_ok(be_text))
    check("⑩-b 源码：节点 doWork 先推送、后判 network == null（没接网络的立方也必须供电）",
          node_push_before_network_ok(node_text))
    check("⑩-c 源码：网络池「立方最后」不依赖 provider 插入顺序（只补非立方付不上的差额）"
          "且拉取期间不让立方做自己的电源",
          node_cube_last_ok(node_text))
    check("⑩-d 源码：第 8 行「相邻供能」真的画出来（ROW_8_Y=99、三态词表齐全、客户端只读数据槽 20）",
          screen_adjacent_row_ok(screen_text))

    # —— 推送行为模型：3 个可接收邻居 + 1 个能量立方 ⇒ 只推前 3 个、总量 ≤ 上限 ——
    receivers = [{"stored": 0, "capacity": CAP, "is_cube": False},
                 {"stored": CAP - 5_000, "capacity": CAP, "is_cube": False},
                 {"stored": 0, "capacity": 2_000, "is_cube": False},
                 {"stored": 0, "capacity": CAP, "is_cube": True}]
    total, got = model_push(40_000, receivers)
    check("⑩-e 模型：3 个可接收邻居 + 1 个能量立方 ⇒ 只有前 3 个收到、能量立方一格不涨、"
          "总送出 ≤ 每 tick 上限（10,000）",
          got[3] == 0
          and receivers[3]["stored"] == 0
          and total == PUSH_PER_TICK
          and receivers[0]["stored"] == PUSH_PER_TICK
          and sum(got) == total)
    # simulate 不动状态：只问不收时立方电量、收方电量都不变
    probe = [{"stored": 0, "capacity": CAP, "is_cube": False}]
    before_cube, before_recv = 40_000, probe[0]["stored"]
    probe_result = min(probe[0]["capacity"] - probe[0]["stored"], PUSH_PER_TICK, before_cube)  # simulate
    check("⑩-f 模型：simulate 只回答「能吃多少」，立方存量 / 收方存量都不变（真正扣电只发生在执行阶段）",
          probe_result == PUSH_PER_TICK and before_cube == 40_000 and probe[0]["stored"] == before_recv)
    # 满立方 / 满邻居：simulate 得 0，一格都不写
    full_receivers = [{"stored": CAP, "capacity": CAP, "is_cube": False}]
    total, got = model_push(40_000, full_receivers)
    check("⑩-g 模型：收方已满 ⇒ simulate 得 0 ⇒ 一个 FE 都不推（「满」是容量限制，不是门控）",
          total == 0 and got == [0] and full_receivers[0]["stored"] == CAP)
    # 一块空立方只有 3,000 电，邻居空间 50M ⇒ 只能推 3,000
    total, got = model_push(3_000, [{"stored": 0, "capacity": CAP, "is_cube": False}])
    check("⑩-h 模型：本块只有 3,000 FE ⇒ 最多推 3,000（绝不推超自身存量、不产生负电量）",
          total == 3_000 and got == [3_000])
    # 预算六面分摊：两个空邻居、预算 10,000 ⇒ 总送出仍是 10,000 而不是 20,000
    split = [{"stored": 0, "capacity": CAP, "is_cube": False},
             {"stored": 0, "capacity": CAP, "is_cube": False}]
    total, got = model_push(40_000, split)
    check("⑩-i 模型：预算六面分摊 —— 两个空邻居也只共分 10,000（不是每个面各 10,000）",
          total == PUSH_PER_TICK and got == [PUSH_PER_TICK, 0])
    # 三态（数据槽 20）：空电池 + 有邻居 ⇒ 关（不是无相邻）；有对象且送出 ⇒ 开；没对象 ⇒ 无相邻
    check("⑩-l 模型：三态口径 —— 空电池贴着可接收邻居显示「关」、真送出显示「开」、没对象显示「无相邻」",
          model_adjacent_state(True, 0) == ADJACENT_OFF
          and model_adjacent_state(True, PUSH_PER_TICK) == ADJACENT_ON
          and model_adjacent_state(False, 0) == ADJACENT_NONE)
    # 网络池顺序：非立方先出、立方只补差额；且与插入顺序无关
    others, cube = model_network_extract(1_000_000, 1_000_000, 500)
    check("⑩-j 模型：网络耗能 500 FE，控制器有 1M ⇒ 全部由非立方出，立方一格不动（最低优先级）",
          (others, cube) == (500, 0))
    others, cube = model_network_extract(1_000_000, 30, 100)
    check("⑩-k 模型：非立方只有 30 但请求 100 ⇒ 非立方出 30、立方补 70（总量不缩水）",
          (others, cube) == (30, 70))

    counterexample("模型：先按「立方有没有电」提前 break 再记状态 ⇒ 空电池被误显示成「无相邻」"
                   "（玩家会以为旁边没机器，而其实只是没电）",
                   model_adjacent_state_early_break(0, True) == ADJACENT_NONE
                   and model_adjacent_state(True, 0) == ADJACENT_OFF)
    counterexample("模型：仍按 provider 插入顺序抽（立方恰好先入网）⇒ 控制器满电却先抽立方的电"
                   "（第 106 轮之前的真实形态）",
                   model_network_extract_by_insertion(1_000_000, 1_000_000, 500) == (0, 500))
    counterexample("模型：把电推给相邻能量立方 ⇒ 立方之间来回倒电（本轮明确禁止）",
                   model_push(40_000, [{"stored": 0, "capacity": CAP, "is_cube": True}],
                              skip_cubes=False)[0] == PUSH_PER_TICK)
    counterexample("模型：simulate 阶段就扣电 ⇒ 收方最终没收下时这份电凭空消失（守恒被破坏）",
                   model_push_deduct_in_simulate(40_000, [{"stored": 0, "capacity": CAP}])[1] == PUSH_PER_TICK)
    counterexample("模型：不按每 tick 预算夹 ⇒ 六面各推 10,000（总共 40,000，超上限 4 倍）",
                   model_push(1_000_000, [{"stored": 0, "capacity": CAP, "is_cube": False}
                                          for _ in range(6)], limit=PUSH_PER_TICK)[0] == PUSH_PER_TICK
                   and sum(model_push(1_000_000,
                                      [{"stored": 0, "capacity": CAP, "is_cube": False} for _ in range(6)],
                                      limit=PUSH_PER_TICK * 4)[1]) == PUSH_PER_TICK * 4)
    counterexample("模型：满收方仍照写 ⇒ 超出容量（负空间被当成可写）",
                   min(0, CAP - CAP) == 0
                   and model_push(40_000, [{"stored": CAP, "capacity": CAP, "is_cube": False}])[0] == 0
                   and max(0, CAP - CAP) == 0)
    counterexample("源码：推送改成推给能量立方（删掉 instanceof FEEnergyCubeBlock 跳过）⇒ ⑩-a 判负",
                   push_source_ok(be_text.replace(
                       "if (level.getBlockState(target).getBlock() instanceof FEEnergyCubeBlock) {",
                       "if (false) {")) is False)
    counterexample("源码：推送不先 simulate 而是直接满额灌（删掉 simulate 那一行）⇒ ⑩-a 判负",
                   push_source_ok(be_text.replace(
                       "final int accepted = storage.receiveEnergy(offer, true);",
                       "final int accepted = offer;")) is False)
    counterexample("源码：真收后不退回差额（删掉守恒兜底）⇒ ⑩-a 判负（收不下的电凭空消失）",
                   push_source_ok(be_text.replace(
                       "                receiveIntoCluster(taken - actual);", "")) is False)
    counterexample("源码：doWork 把推送放在 network == null 提前返回之后 ⇒ ⑩-b 判负"
                   "（没接 RS 网络的立方永不供电 = 用户实机场景直接失效）",
                   node_push_before_network_ok(node_text.replace(
                       "        blockEntity.tickNeighbourPush();\n        if (network == null) {\n"
                       "            return;\n        }",
                       "        if (network == null) {\n            return;\n        }\n"
                       "        blockEntity.tickNeighbourPush();")) is False)
    counterexample("源码：节点 extract 改回无条件放电（按插入顺序先抽立方）⇒ ⑩-c 判负",
                   node_cube_last_ok(node_text.replace(
                       "        return blockEntity.dischargeToNetwork(want);",
                       "        return blockEntity.dischargeToNetwork(amount);")) is False)
    counterexample("源码：界面不画相邻供能行 ⇒ ⑩-d 判负（玩家看不出它到底在不在给旁边供电）",
                   screen_adjacent_row_ok(screen_text.replace(
                       "        drawRow(guiGraphics, ROW_8_Y, adjacentSupplyLine(), adjacentSupplyColor());",
                       "")) is False)
    counterexample("源码：数据槽 20 不发相邻供能（改成常量 0）⇒ ⑦-e 判负",
                   data_slots_ok(be_text.replace("case 20 -> adjacent;", "case 20 -> 0;"), menu_text) is False)


    # ---------------- 汇总 ----------------
    print("")
    print("断言总数 %d（含反例自证 %d 条，SKIP %d 条）" % (CHECKS[0], COUNTER[0], len(SKIPS)))
    for item in SKIPS:
        print("SKIP - " + item)
    if FAILS:
        print("问题总数: %d" % len(FAILS))
        for item in FAILS:
            print(" - " + item)
        return 1
    print("SELFCHECK OK (%d checks, %d counterexamples)" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
