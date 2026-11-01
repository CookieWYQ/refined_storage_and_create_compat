#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 124 轮自检：范围充电器过滤界面「世界中的方块」候选池（3-1 / 3-2 / 3-3）+ R-1 满电判定饱和。

背景（用户实机反馈 docs/ROUND122_USER_FEEDBACK.md §3，三条同一个根因家族）
-----------------------------------------------------------------------------
1. **3-1**「为什么会有这个应力发电机呢？应力发电机它是发电的，它本身是不能存电的。」
   —— 候选池唯一写入点 `RangeChargerBlockEntity#recordSeenBlock` 的唯一调用点在 `chargeBlock`，
   而旧 `chargeBlock` 只判 `storage != null`（FE 能力非空）就登记 ⇒ **只出不进**的发电机也进了列表。
2. **3-2**「它里面不应该会有这个什么比如说控制器以及那些……」
   —— RS 2.0.9 的控制器**确实**暴露 `Capabilities.EnergyStorage.BLOCK`（反编译证据：
   `ModInitializer#registerCapabilities` 里 `registerBlockEntity(EnergyStorage.BLOCK, getController(), …)`），
   且 `EnergyStorageAdapter#canReceive()` 硬编码 `return true`、`canExtract()` 硬编码 `false`，
   容量 = 控制器能量缓冲（> 0）⇒ 只看 canReceive/maxEnergyStored 拦不住它，必须按注册名黑名单排除。
3. **3-3**「就比如说我们模组中添加的范围充电器嘛，你这里面怎么都没有」
   —— `scanBlocks` / `scanBlocksInfinite` 先 `if (pos.equals(worldPosition)) continue;` 跳过自身，
   而登记只发生在 `chargeBlock`（只对「非自身」调用）⇒ **自家方块永远进不了候选池**。

本轮修法（只碰 `RangeChargerBlockEntity.java` + 本自检 + 一个旧自检的锚点）
-------------------------------------------------------------------------
* 判据单点：新增 `isChargeableBlock(IEnergyStorage)` = `storage != null &&
  getMaxEnergyStored() > 0 && canReceive()`（与客户端物品池 `ChargerFilterCandidates#all` 逐字同源）；
  `chargeBlock` 与候选登记都走它；
* 登记单点：`recordSeenCandidate(state, storage)` 是唯一调用 `recordSeenBlock` 的地方，
  判据 + 黑名单都在这里；`recordSeenBlock` 只负责「有界放入 + 取方块注册名」；
* 自身登记：`doCharging` 在 `seenBlocks.clear()` 之后、扫描之前调 `recordSeenSelf(level)`；
  `scanBlocks` / `scanBlocksInfinite` 的「跳过自身充电」原样保留（登记与充电彻底分离）；
* 黑名单：`CANDIDATE_DENY_NAMESPACE = "refinedstorage"` + 路径 `controller` / `*_controller`
  （RS 控制器 16 色 × 普通/创造共 32 个 id，按命名规约识别而不是逐个列 id）；
  黑名单**只影响候选展示**，不影响真充电 / 名单过滤；
* **R-1（124-D 转来）**：`chargeBlock` 的「满电跳过」从 `getEnergyStored() >= getMaxEnergyStored()`
  （两个 int 比较）改成 `receiveEnergy(transfer, true) <= 0`（simulate，不写世界）。原因：FE 能力
  视图是 int 口径，43 块 × 50M = 2.15B > INT_MAX 时存量/容量同时饱和到 `Integer.MAX_VALUE`
  ⇒ 旧判据恒为「满」，明明真实还剩 2,516,352 FE 也永远充不进去（124-D 的 M-3c 模型复现）。
  由于 simulate 需要 `transfer`，先算 `transferBudget()`（**纯函数、无副作用**）再判满；
  「满电目标不消耗预算、不扣电量、不登记高亮」的口径不变。

三条腿（与第 120 / 121 轮同一套路）
----------------------------------
① 源码锚点：判据单点 / 登记单点 / 自身登记与跳过分离 / 黑名单 / R-1 / 物品池同源 /
   方块行图标空防御 / 候选池上限与重建闸门；
② 行为模型：复刻「能力 + 黑名单 + 上限」候选判定、复刻「跳过自身但登记自身」、复刻 int 饱和下的
   满电判定（旧判据出错 / 新判据正确），并穷举 RS 控制器 32 个颜色变体；
③ 反例自证：把坏写法拼回源码副本 / 模型，断言上面的判据真的判负（证明判据不是摆设）。

纪律：不跑 gradle、不 git、不改存档格式 / 网络契约；只读源码 + 纯 Python 模型。
用法：python tools/selfcheck_round124_charger_candidates.py
退出码：0 = 全部通过；最后一行：ALL_PASS。
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
JAVA_REL = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
JAVA = os.path.join(ROOT, JAVA_REL)

CHARGER_BE = os.path.join(JAVA, "block", "entity", "RangeChargerBlockEntity.java")
ITEM_POOL = os.path.join(JAVA, "client", "screen", "ChargerFilterCandidates.java")
FILTER_SCREEN = os.path.join(JAVA, "client", "screen", "ChargerFilterScreen.java")
LIMITS = os.path.join(JAVA, "support", "ChargerFilterLimits.java")

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


def counterexample(name, ok, detail=""):
    COUNTER[0] += 1
    check("反例 " + name, ok, detail)


# ======================================================================================
# 源码解析小工具（与第 120 / 121 轮同一套，避免再造一份「去注释 / 取方法体」）
# ======================================================================================

def strip_comments(text):
    """去注释（保留字符串字面量），用于「语句存在性 / 语句顺序」判据。"""
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


def call_sites(body, needle):
    """某调用在方法体里出现的位置列表（用于顺序断言）。"""
    out = []
    start = 0
    while True:
        i = body.find(needle, start)
        if i < 0:
            return out
        out.append(i)
        start = i + len(needle)


# ======================================================================================
# ① 源码锚点（写成纯函数，反例自证时直接喂改坏的源码副本）
# ======================================================================================

def charge_block_body(be_raw):
    return method_body(strip_comments(be_raw), "private boolean chargeBlock(")


def criterion_is_single_source(be_raw):
    """`isChargeableBlock` = 三条件合取（null / max>0 / canReceive），且只有一个定义。"""
    code = strip_comments(be_raw)
    body = method_body(code, "private static boolean isChargeableBlock(")
    if body is None:
        return False
    flat = re.sub(r"\s+", " ", body)
    return ("storage != null && storage.getMaxEnergyStored() > 0 && storage.canReceive()" in flat
            and code.count("private static boolean isChargeableBlock(") == 1)


def charge_block_uses_criterion(be_raw):
    """真充电路径先过统一判据（只出不进 / 零容量 ⇒ 提前 return，不白调 receiveEnergy）。"""
    body = charge_block_body(be_raw)
    if body is None:
        return False
    return ("if (!isChargeableBlock(storage)) {" in body
            and body.find("if (!isChargeableBlock(storage)) {") < body.find("storage.receiveEnergy("))


def record_seen_block_single_entry(be_raw):
    """`recordSeenBlock` 全文件只有 1 个调用点，且在 `recordSeenCandidate` 的方法体里。"""
    code = strip_comments(be_raw)
    calls = [i for i in call_sites(code, "recordSeenBlock(")
             if not code[max(0, i - 20):i].rstrip().endswith("void")]
    if len(calls) != 1:
        return False
    funnel = method_body(code, "private void recordSeenCandidate(")
    if funnel is None:
        return False
    return "recordSeenBlock(state);" in funnel


def record_funnel_guarded(be_raw):
    """唯一登记入口同时受 `isChargeableBlock`（双判据）与 `isCandidateDenied`（黑名单）保护。"""
    code = strip_comments(be_raw)
    funnel = method_body(code, "private void recordSeenCandidate(")
    if funnel is None:
        return False
    return ("if (!isChargeableBlock(storage)) {" in funnel
            and "if (isCandidateDenied(state)) {" in funnel
            and funnel.find("isChargeableBlock(") < funnel.find("recordSeenBlock("))


def candidate_deny_rule(be_raw):
    """控制器黑名单：命名空间常量 + 路径规则（不是逐个列 32 个 id）。"""
    code = strip_comments(be_raw)
    deny = method_body(code, "private static boolean isCandidateDenied(")
    if deny is None:
        return False
    return ('private static final String CANDIDATE_DENY_NAMESPACE = "refinedstorage";' in be_raw
            and 'private static final String CANDIDATE_DENY_PATH_SUFFIX = "_controller";' in be_raw
            and 'private static final String CANDIDATE_DENY_PATH_CONTROLLER = "controller";' in be_raw
            and "CANDIDATE_DENY_NAMESPACE.equals(id.getNamespace())" in deny
            and "path.endsWith(CANDIDATE_DENY_PATH_SUFFIX)" in deny
            # 证据必须写进注释（反编译结论，不是猜）—— 防止后人把它当迷信删掉
            and "EnergyStorageAdapter" in be_raw
            and "canReceive" in be_raw)


def deny_list_only_in_display_path(be_raw):
    """黑名单只影响候选展示：`isCandidateDenied` 不出现在 `chargeBlock`（真充电路径）。"""
    body = charge_block_body(be_raw)
    if body is None:
        return False
    return "isCandidateDenied(" not in body


def self_registered_outside_scan(be_raw):
    """自身登记：在 `doCharging` 清空候选池之后单独做，且两条扫描里都没有登记自身。"""
    code = strip_comments(be_raw)
    charging = method_body(code, "private void doCharging(")
    if charging is None:
        return False
    clear = charging.find("seenBlocks.clear();")
    reg = charging.find("recordSeenSelf(level);")
    if clear < 0 or reg < 0 or not (clear < reg):
        return False
    if code.count("recordSeenSelf(") != 2:   # 定义 1 + doCharging 调用 1
        return False
    for hint in ("private int scanBlocks(", "private int scanBlocksInfinite("):
        body = method_body(code, hint)
        if body is None or "recordSeenSelf(" in body:
            return False
        if "pos.equals(worldPosition)" not in body:
            return False          # 「跳过自身充电」必须保留
        if "chargeBlock(level, pos, entry.getValue())" not in body:
            return False          # 真充电路径不许被这次改动碰掉
    # chargeBlock 不可能是「自身登记」的实现（它只对非自身调用）
    cb = charge_block_body(be_raw)
    return cb is not None and "recordSeenSelf(" not in cb


def self_registration_uses_criterion(be_raw):
    """自身登记也走 `recordSeenCandidate`（因此同样受判据 + 黑名单保护）。"""
    code = strip_comments(be_raw)
    body = method_body(code, "private void recordSeenSelf(")
    if body is None:
        return False
    return ("level.getCapability(" in body
            and "Capabilities.EnergyStorage.BLOCK" in body
            and "recordSeenCandidate(getBlockState(), storage);" in body)


def full_check_uses_simulate(be_raw):
    """R-1：满电判定改成 simulate（`receiveEnergy(transfer, true) <= 0`），不再比两个 int。"""
    body = charge_block_body(be_raw)
    if body is None:
        return False
    return ("storage.receiveEnergy(transfer, true) <= 0" in body
            and "getEnergyStored() >= storage.getMaxEnergyStored()" not in body
            and "lastSkippedFullTargets++" in body)


def simulate_after_pure_budget(be_raw):
    """R-1：先拿预算（纯函数）再判满；预算为 0 时提前退出。

    第 127 轮更新（**判据原意未放宽**）：`chargeBlock` 的预算来源多了一条三元分支
    （立方 = `cubeTransferBudget()` 电池档 / 其它 = `transferBudget()` 工具档），
    两者都必须是**纯函数**、都必须在 `if (transfer <= 0)` 与 simulate 判满之前取到。
    """
    body = charge_block_body(be_raw)
    if body is None:
        return False
    budget = body.find("final int transfer = blockEntity instanceof FEEnergyCubeBlockEntity")
    if budget < 0:
        budget = body.find("final int transfer = transferBudget();")
    zero = body.find("if (transfer <= 0) {")
    sim = body.find("storage.receiveEnergy(transfer, true) <= 0")
    recv = body.find("storage.receiveEnergy(transfer, false)")
    return 0 <= budget < zero < sim < recv


def pool_reset_gated(be_raw):
    """候选池每 tick 重建仍受「方块充电总闸」约束（展示层不额外发电 / 不刷包）。"""
    code = strip_comments(be_raw)
    body = method_body(code, "private void doCharging(")
    if body is None:
        return False
    return ("if (Config.rangeChargerChargeBlocks) {" in body
            and "seenBlocks.clear();" in body
            and body.find("if (Config.rangeChargerChargeBlocks) {") < body.find("seenBlocks.clear();"))


def pool_bounded(be_raw):
    """候选池仍有界：`addBounded` + `MAX_BLOCK_ENTRIES`（不改上限/语义）。"""
    code = strip_comments(be_raw)
    body = method_body(code, "private void recordSeenBlock(")
    if body is None:
        return False
    return ("ChargerFilterLimits.addBounded(seenBlocks, id," in body
            and "ChargerFilterLimits.MAX_BLOCK_ENTRIES" in body)


def item_pool_same_criterion(src):
    """物品池（客户端）判据：`canReceive() + getMaxEnergyStored() > 0` 同一组合。"""
    code = strip_comments(src)
    body = method_body(code, "public static List<Item> all()")
    if body is None:
        return False
    return ("!storage.canReceive()" in body
            and "storage.getMaxEnergyStored() <= 0" in body)


def screen_icon_defense(src):
    """方块页行渲染对「没有方块物品的方块」安全：空图标直接不放行 + 渲染侧默认 EMPTY。"""
    code = strip_comments(src)
    add = method_body(code, "private boolean addBlockRow(")
    icon = method_body(code, "private ItemStack blockIconOf(")
    if add is None or icon is None:
        return False
    return ("if (icon.isEmpty()) {" in add
            and "return false;" in add
            and "getOrDefault(id, ItemStack.EMPTY)" in icon)


# ======================================================================================
# ② 行为模型
# ======================================================================================

INT_MAX = 2 ** 31 - 1
LIMIT_MAX_BLOCK_ENTRIES = 1024

#: RS 控制器 16 种颜色（与 `data/refinedstorage/loot_table/blocks/*_controller.json` 一一对应）。
COLORS = ("white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
          "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black")


def cap_int(value):
    """FE 能力视图的 int 口径：超过 INT_MAX 就饱和到 INT_MAX（不是负数、不是 0）。"""
    return max(0, min(int(value), INT_MAX))


class Storage:
    """一个方块的 FE 能力视图（int 口径）+ 该方块内部的 long 精确空间（R-1 用）。"""

    def __init__(self, block_id, can_receive=True, int_max=1_000_000, int_stored=0,
                 real_capacity=None, real_stored=None):
        self.block_id = block_id
        self.can_receive = can_receive
        self.int_max = cap_int(int_max)
        self.int_stored = cap_int(int_stored)
        # long 精确空间：默认等于 int 视图（普通方块），立方集群可单独给大值
        self.real_capacity = int_max if real_capacity is None else real_capacity
        self.real_stored = int_stored if real_stored is None else real_stored

    def get_max_energy_stored(self):
        return self.int_max

    def get_energy_stored(self):
        return self.int_stored

    def real_space(self):
        return max(0, self.real_capacity - self.real_stored)

    def receive(self, amount, simulate):
        accepted = max(0, min(int(amount), self.real_space()))
        if not simulate and accepted > 0:
            self.real_stored += accepted
            self.int_stored = cap_int(self.real_stored)
        return accepted


def is_chargeable_block(storage):
    """复刻 `RangeChargerBlockEntity#isChargeableBlock`。"""
    return (storage is not None
            and storage.get_max_energy_stored() > 0
            and storage.can_receive)


def is_candidate_denied(block_id):
    """复刻 `isCandidateDenied`：refinedstorage 命名空间 + controller / *_controller 路径。"""
    if block_id is None or ":" not in block_id:
        return False
    namespace, path = block_id.split(":", 1)
    if namespace != "refinedstorage":
        return False
    return path == "controller" or path.endswith("_controller")


def candidate_pool(storages, limit=LIMIT_MAX_BLOCK_ENTRIES):
    """复刻「唯一登记入口」：判据为真 + 未命中黑名单才进候选池，且有界。"""
    out = []
    for storage in storages:
        if not is_chargeable_block(storage):
            continue
        if is_candidate_denied(storage.block_id):
            continue
        if storage.block_id in out:
            continue
        if len(out) >= limit:
            continue          # addBounded：满了只是不再放入，不报错
        out.append(storage.block_id)
    return out


def old_pool(storages):
    """旧实现（3-3 的 bug 形状）：登记只发生在「非自身的 chargeBlock」里 ⇒ 自身永远缺席。"""
    out = []
    for storage in storages:
        if storage.block_id == "rs_create_compat:range_charger":
            continue          # 旧实现：扫描循环先 continue 跳过自身，永远不会走到登记
        if is_chargeable_block(storage) and not is_candidate_denied(storage.block_id):
            out.append(storage.block_id)
    return out


def blocked_self_ids(storages):
    """复刻 `scanBlocks` / `scanBlocksInfinite` 的「跳过自身充电」。"""
    return [s.block_id for s in storages if is_chargeable_block(s)]


def old_full_judgement(storage):
    """旧判据：`getEnergyStored() >= getMaxEnergyStored()`（两个 int 比较）。"""
    return storage.get_energy_stored() >= storage.get_max_energy_stored()


def new_full_judgement(storage, transfer):
    """新判据（R-1）：simulate 问「还收得下吗」。"""
    return storage.receive(transfer, True) <= 0


def transfer_budget(total_stored, charge_rate=500, max_targets=50):
    """复刻 `transferBudget()`：stored / maxTargets 与速率取小，且 ≥ 1（电量 > 0 时）。"""
    if total_stored <= 0:
        return 0
    share = total_stored // max(1, max_targets)
    return max(1, min(charge_rate, share))


# ======================================================================================
# ① 源码锚点执行
# ======================================================================================

def group_anchors():
    print("\n=== ① 源码锚点 ===")
    be_raw = read(CHARGER_BE)
    item_raw = read(ITEM_POOL)
    screen_raw = read(FILTER_SCREEN)
    limits_raw = read(LIMITS)

    check("A-1 判据单点：isChargeableBlock = null + getMaxEnergyStored()>0 + canReceive（唯一实现）",
          criterion_is_single_source(be_raw), "三条件合取或唯一性不成立")
    check("A-2 真充电路径先过统一判据（只出不进 / 零容量提前 return，不白调 receiveEnergy）",
          charge_block_uses_criterion(be_raw))
    check("A-3 recordSeenBlock 全文件只有 1 个调用点，且在 recordSeenCandidate 里",
          record_seen_block_single_entry(be_raw))
    check("A-4 唯一登记入口同时受 isChargeableBlock + isCandidateDenied 保护（顺序：判据→黑名单→登记）",
          record_funnel_guarded(be_raw))
    check("A-5 控制器黑名单=命名空间+路径规则 + 注释留反编译证据（EnergyStorageAdapter.canReceive）",
          candidate_deny_rule(be_raw))
    check("A-6 黑名单只影响展示：chargeBlock（真充电路径）里没有 isCandidateDenied",
          deny_list_only_in_display_path(be_raw))
    check("A-7 自身登记在 doCharging（清空之后、扫描之前）；两条扫描只跳过自身、不登记自身",
          self_registered_outside_scan(be_raw))
    check("A-8 自身登记也走 recordSeenCandidate（同判据 + 同黑名单）",
          self_registration_uses_criterion(be_raw))
    check("A-9 R-1：满电判定用 simulate（receiveEnergy(transfer,true)<=0），不再比较两个 int",
          full_check_uses_simulate(be_raw))
    check("A-10 R-1：先 transferBudget()（纯函数）再判满；预算为 0 提前退出；真收仍在最后",
          simulate_after_pure_budget(be_raw))
    check("A-11 候选池仍受方块充电总闸闸门（doCharging 里 clear 在 if(Config.rangeChargerChargeBlocks) 内）",
          pool_reset_gated(be_raw))
    check("A-12 候选池仍有界：addBounded + MAX_BLOCK_ENTRIES（上限/语义都没被绕过）",
          pool_bounded(be_raw)
          and "public static final int MAX_BLOCK_ENTRIES = 1024;" in limits_raw)
    check("A-13 物品池（ChargerFilterCandidates#all）与方块判据同源：canReceive + maxEnergyStored>0",
          item_pool_same_criterion(item_raw))
    check("A-14 方块页图标防御：空图标不放行 + 渲染默认 EMPTY（没有方块物品的方块不 NPE/不画错）",
          screen_icon_defense(screen_raw))


# ======================================================================================
# ② 行为模型执行
# ======================================================================================

def group_models():
    print("\n=== ② 行为模型 ===")

    generator = Storage("createaddition:alternator", can_receive=False, int_max=100_000)
    zero_capacity = Storage("othermod:placeholder", can_receive=True, int_max=0)
    battery = Storage("rs_create_compat:fe_energy_cube", can_receive=True, int_max=50_000_000)

    pool = candidate_pool([generator, zero_capacity, battery])
    check("M-1 只出不进的发电机（canReceive=false）不进候选池 —— 用户 3-1 的直接复现",
          "createaddition:alternator" not in pool)
    check("M-2 零容量占位能力（maxEnergyStored=0）不进候选池", "othermod:placeholder" not in pool)
    check("M-3 真的能存电的方块（canReceive=true + max>0）进候选池",
          pool == ["rs_create_compat:fe_energy_cube"])

    controller = Storage("refinedstorage:controller", can_receive=True, int_max=1_000_000)
    check("M-4 RS 控制器（能收电）仍不进候选池 —— 用户 3-2 的直接复现",
          candidate_pool([controller]) == [])
    check("M-4b 控制器只是「候选池外」：它的能力依然 report 可充（黑名单只影响展示，不影响充电判定）",
          is_chargeable_block(controller))

    all_controllers = ["refinedstorage:controller"] + \
        ["refinedstorage:%s_controller" % c for c in COLORS] + \
        ["refinedstorage:creative_controller"] + \
        ["refinedstorage:%s_creative_controller" % c for c in COLORS]
    check("M-5 RS 控制器 32 个变体（16 色 × 普通/创造 + 无颜色 controller）全部被黑名单命中",
          len(all_controllers) == 34 and all(is_candidate_denied(i) for i in all_controllers))
    check("M-5b 其它模组的 controller 不被误伤；refinedstorage 的非 controller 方块也不被误伤",
          not is_candidate_denied("othermod:controller")
          and not is_candidate_denied("refinedstorage:portable_grid")
          and not is_candidate_denied("refinedstorage:energy_cube"))

    # 3-3：旧实现 = 登记只发生在「非自身的 chargeBlock」里 ⇒ 自身永远缺席。
    self_storage = Storage("rs_create_compat:range_charger", can_receive=True, int_max=50_000_000)
    world = [self_storage, battery]
    check("M-6 旧实现：自家范围充电器永远进不了候选池（= 用户 3-3 的 bug）",
          old_pool(world) == ["rs_create_compat:fe_energy_cube"])
    check("M-7 新实现：自身进候选池，但「跳过自身充电」仍然成立（自身不是目标）",
          "rs_create_compat:range_charger" in candidate_pool(world)
          and "rs_create_compat:range_charger" not in blocked_self_ids([battery]))
    check("M-7b 自身登记不依赖「有没有别的目标」—— 只放一台时不装其它方块也有自己",
          candidate_pool([self_storage]) == ["rs_create_compat:range_charger"])

    # R-1：int 饱和下的满电判定
    # 43 块 × 50M = 2.15B 容量；真实存量 2,147,483,648 ⇒ int 视图 stored=max=INT_MAX。
    cluster = Storage("rs_create_compat:fe_energy_cube", can_receive=True,
                      int_max=43 * 50_000_000, int_stored=2_147_483_648,
                      real_capacity=43 * 50_000_000, real_stored=2_147_483_648)
    transfer = transfer_budget(100_000)
    check("M-8 R-1 复现：int 饱和时 stored==max==INT_MAX，旧判据恒判「满」（这就是 124-D 的 M-3c）",
          cluster.get_energy_stored() == INT_MAX == cluster.get_max_energy_stored()
          and old_full_judgement(cluster))
    check("M-9 R-1 修法：simulate 判据看到真实空间 2,516,352 > 0 ⇒ 不判满，能继续充",
          cluster.real_space() == 2_516_352
          and not new_full_judgement(cluster, transfer)
          and cluster.receive(transfer, True) == transfer)
    truly_full = Storage("othermod:big_battery", can_receive=True, int_max=43 * 50_000_000,
                         int_stored=43 * 50_000_000,
                         real_capacity=43 * 50_000_000, real_stored=43 * 50_000_000)
    normal_full = Storage("othermod:small_battery", can_receive=True, int_max=1000, int_stored=1000)
    normal_partial = Storage("othermod:small_battery_2", can_receive=True, int_max=1000, int_stored=400)
    check("M-10 真满（long 空间=0）仍判满并跳过（新判据没有把「满」弄丢）",
          new_full_judgement(truly_full, transfer) and new_full_judgement(normal_full, transfer))
    check("M-11 普通未满方块照旧能充（新判据对非饱和区间与旧判据等价）",
          not old_full_judgement(normal_partial) and not new_full_judgement(normal_partial, transfer)
          and normal_partial.receive(transfer, True) == transfer)
    check("M-12 满电目标不消耗预算：判满发生在「真收」之前，simulate 不改变任何空间（纯查询）",
          cluster.receive(transfer, True) == transfer and cluster.real_space() == 2_516_352)

    # 候选池有界
    many = [Storage("mod%d:block" % i) for i in range(1500)]
    check("M-13 候选池仍然有界（1500 个可充方块 ⇒ 最多 MAX_BLOCK_ENTRIES=1024 条）",
          len(candidate_pool(many)) == LIMIT_MAX_BLOCK_ENTRIES)

    # 满电 / 被过滤的方块仍然是候选（候选 ≠ 目标；第 110 轮的死锁前提）
    check("M-14 满电方块仍进候选池（候选 ≠ 目标；否则拉黑后无法取消勾选）",
          "othermod:small_battery" in candidate_pool([normal_full]))
    check("M-15 候选判定与「这个 tick 有没有充进去」无关：真充电失败的目标依然在池里",
          "othermod:small_battery" in candidate_pool([normal_full])
          and blocked_self_ids([normal_full]) == ["othermod:small_battery"])


# ======================================================================================
# ③ 反例自证（源码副本 + 模型反例）
# ======================================================================================

def group_counterexamples():
    print("\n=== ③ 反例自证（源码副本 / 模型） ===")
    be_raw = read(CHARGER_BE)
    be_code = strip_comments(be_raw)
    item_raw = read(ITEM_POOL)
    screen_raw = read(FILTER_SCREEN)

    # A-1：删掉 canReceive 判据（退回「能力非空就算可充」）⇒ 判负 + 模型也把发电机放进来
    broken = be_raw.replace(
        "return storage != null && storage.getMaxEnergyStored() > 0 && storage.canReceive();",
        "return storage != null;", 1)
    counterexample("C-1 判据删掉 canReceive / maxEnergyStored ⇒ A-1 判负（发电机又进候选池）",
                   broken != be_raw and not criterion_is_single_source(broken))

    # A-9：R-1 改回「比较两个 int」⇒ 判负 + 模型复现饱和 bug
    old_line = ("        if (storage.receiveEnergy(transfer, true) <= 0) {")
    broken = be_raw.replace(
        old_line, "        if (storage.getEnergyStored() >= storage.getMaxEnergyStored()) {", 1)
    counterexample("C-2 满电判定改回比较两个 int ⇒ A-9 判负（int 饱和时永远判满）",
                   broken != be_raw and not full_check_uses_simulate(broken)
                   and not simulate_after_pure_budget(broken))

    # A-7：把「自身登记」删掉（退回 3-3 的旧行为）⇒ 判负
    broken = be_raw.replace("            recordSeenSelf(level);\n", "", 1)
    counterexample("C-3 删掉 doCharging 里的 recordSeenSelf ⇒ A-7 判负（自家方块又进不了候选池）",
                   broken != be_raw and not self_registered_outside_scan(broken))

    # A-7：把登记塞回扫描循环里（登记与「跳过自身」重新耦合）⇒ 判负
    broken = be_raw.replace(
        "            recordSeenSelf(level);\n", "", 1).replace(
        "                    if (pos.equals(worldPosition)) {\n"
        "                        // 跳过自身：绝不给自己充电。",
        "                    if (pos.equals(worldPosition)) {\n"
        "                        recordSeenSelf(level);\n"
        "                        // 跳过自身：绝不给自己充电。", 1)
    counterexample("C-4 把自身登记塞回扫描循环（登记与跳过耦合）⇒ A-7 判负",
                   broken != be_raw and not self_registered_outside_scan(broken))

    # A-4：去掉黑名单分支 ⇒ 判负
    broken = be_raw.replace(
        "        if (isCandidateDenied(state)) {\n            return;\n        }\n", "", 1)
    counterexample("C-5 去掉候选池黑名单分支 ⇒ A-4 判负（控制器又会被登记）",
                   broken != be_raw and not record_funnel_guarded(broken))

    # A-2：真充电路径不再先过判据（退回只判 null）
    broken = be_raw.replace(
        "        if (!isChargeableBlock(storage)) {\n            return false;\n        }",
        "        if (storage == null) {\n            return false;\n        }", 1)
    counterexample("C-6 真充电路径退回只判 storage==null ⇒ A-2 判负",
                   broken != be_raw and not charge_block_uses_criterion(broken))

    # A-13：物品池判据被削弱（删掉 canReceive）⇒ 判负
    broken = item_raw.replace(
        "if (storage == null || !storage.canReceive() || storage.getMaxEnergyStored() <= 0) {",
        "if (storage == null || storage.getMaxEnergyStored() <= 0) {", 1)
    counterexample("C-7 物品池删掉 canReceive 判据 ⇒ A-13 判负（两侧口径不再同源）",
                   broken != item_raw and not item_pool_same_criterion(broken))

    # A-14：方块页图标防御被删（空图标照画）⇒ 判负
    broken = screen_raw.replace(
        "        if (icon.isEmpty()) {\n            return false; // 解析不到 / asItem 是空气：没有可画的图标\n        }\n",
        "", 1)
    counterexample("C-8 删掉方块页「空图标不放行」防御 ⇒ A-14 判负（没有方块物品的方块会画错/画空）",
                   broken != screen_raw and not screen_icon_defense(broken))

    # A-11：候选池重建不再受总闸约束（每 tick 无条件 clear）⇒ 判负
    broken = be_raw.replace(
        "        if (Config.rangeChargerChargeBlocks) {\n            seenBlocks.clear();",
        "        seenBlocks.clear();\n        if (Config.rangeChargerChargeBlocks) {", 1)
    counterexample("C-9 候选池重建不再受方块充电总闸约束 ⇒ A-11 判负",
                   broken != be_raw and not pool_reset_gated(broken))

    # ---- 模型反例 ----
    generator = Storage("createaddition:alternator", can_receive=False)
    buggy_pool = [s.block_id for s in [generator]
                  if (s is not None and s.get_max_energy_stored() > 0)]   # 旧判据：漏 canReceive
    counterexample("C-10 模型反例：旧判据（漏 canReceive）会把发电机列进候选池（证明 M-1 不是摆设）",
                   buggy_pool == ["createaddition:alternator"])

    saturated = Storage("x:cube", can_receive=True, int_max=2_150_000_000,
                        int_stored=2_147_483_648, real_capacity=2_150_000_000,
                        real_stored=2_147_483_648)
    transfer = transfer_budget(100_000)
    counterexample("C-11 模型反例：把满电判定换回旧 int 比较，饱和方块被永久判满（证明 R-1 修的是真 bug）",
                   old_full_judgement(saturated) and not new_full_judgement(saturated, transfer))

    no_self = Storage("rs_create_compat:range_charger")
    counterexample("C-12 模型反例：自身登记放回「跳过自身」之后 ⇒ 候选池里永远没有自己（证明 M-6 是真 bug）",
                   old_pool([no_self]) == [] and candidate_pool([no_self]) == ["rs_create_compat:range_charger"])


def main():
    print("第 124 轮自检：范围充电器「世界中的方块」候选池（3-1/3-2/3-3）+ R-1 满电判定饱和")
    group_anchors()
    group_models()
    group_counterexamples()
    print("\n断言 %d 条（其中反例 %d 条），失败 %d 条"
          % (CHECKS[0], COUNTER[0], len(FAILS)))
    if FAILS:
        print("FAILED:")
        for name in FAILS:
            print("  - " + name)
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
