#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 134 轮自检：执行舱「按需补发」新口径（驳回 ceil(R/p) 预开名额）。

背景（用户 m01532 / 2026-11-08）
--------------------------------
旧设计：概率配方按<b>期望轮次</b>预开名额 —— `名额 = ceil(订单剩余 / p) − 在制件数`。
于是「下单 20 件、p=0.8」会<b>同时开工 25 件</b>，一上来就多占 5 份起步原料（金板）。
用户驳回原话（Lead 转述）：「先只按订单量开工，失败一件才补发一件」——多发的唯一合法理由
是**真的失败了一件**，而不是「按期望值先把料占进去」。

新口径（Lead 已改完源码并编译通过 `LEAD_COMPILE_OK sources=526`）
--------------------------------------------------------------
`SequenceExecutionChamberBlockEntity#startCapacityForRecipe(String)` 的 else 分支：
```
final long allowed = remaining;
budgetForLog = allowed;
capacity = allowed - inFlight;
```
即 `名额 = max(0, 订单剩余 R − 在制件数 inFlight)`，**与 p 无关**（必得 / 概率配方同一个公式）。
`p`（`mainResultChance`）与 `guaranteedResult` 仍在，但**只出现在诊断字符串**里
（`";P=" + pLog`、`" p=" + pLog + (guaranteedResult(recipeId) ? " guaranteed" : "")`），不参与判定。

本脚本五条腿
------------
① 源码锚点：新公式三行 + 四处「旧写法已不存在」的负锚点 + 诊断行两处 + javadoc 口径 +
   `mainResultChance` / `guaranteedResult` 只出现在诊断上下文（`RsccAssemblyDebug.isEnabled()` 之后）+
   相关方法链（`startCapacityFor` / `allowedConcurrentUnits` / `inFlightUnitsForRecipe` /
   `remainingOrderUnitsFor` / `NO_ORDER_READABLE` 分支）；
② 模型复刻：`capacity(R, I) = max(0, R − I)`，且对 p ∈ {1.0, 0.8, 0.5, 0.25, 0.1} **结果完全相同**；
③ 用户场景：`(R=20, I=5, p=0.8) ⇒ 15`（旧口径 20）、`(R=20, I=0, p=0.8) ⇒ 20`（旧口径 25）；
④ 确定性补发模拟（固定 LCG 种子，不依赖外部状态）：R=20、p=0.8、每 tick 至多 4 件结算；
   断言「任一时刻 inFlight ≤ remaining ≤ 20」「总开工 = 20 + 失败数」「补发数恒等于本 tick 失败数」
   「最终 delivered = 20」；同一事件流下旧 `ceil` 口径会一上来就开 25 件（inFlight > remaining）；
⑤ 反例自证：`legacy_capacity`（复刻 ceil 口径）在新旧两处场景必须给出不同答案；
   `capacity(0,0)=0`、`capacity(5,7)=0`、订单不可读 ⇒ 0；以及 p=1.0 时新旧口径一致（必得口径没被改坏）。

工具链：`tools/_run_all_selfchecks.py` 用 `glob(os.path.join(HERE, "selfcheck_*.py"))` 自动发现，
本脚本文件名匹配该 glob ⇒ **无需登记**（⑥ 组会断言这一点，含「最后一行必须是 ALL_PASS」的约定）。

纪律：本脚本**只读**源码，不改 src / 不改其它 tools / 不 commit。
用法：D:\\Python313\\python.exe tools/selfcheck_round134_on_demand_replenish.py
退出码：0 = 全部通过；最后一行：ALL_PASS。
"""

from __future__ import annotations

import io
import math
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
CHAMBER = os.path.join(PKG, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
RUNNER = os.path.join(ROOT, "tools", "_run_all_selfchecks.py")
SELF = os.path.abspath(__file__)

#: 用户场景常量（与 docs / 任务书逐字一致）。
ORDER = 20
MAIN_CHANCE = 0.8
PROBES = (1.0, 0.8, 0.5, 0.25, 0.1)

_passed = 0
_failed = 0
_counter = 0
_warnings = []
_failures = []


def check(name, ok, detail=""):
    global _passed, _failed
    if ok:
        _passed += 1
        print("  OK   %s" % name)
    else:
        _failed += 1
        _failures.append((name, detail))
        print("  FAIL %s" % name)
        if detail:
            for line in str(detail).strip().splitlines()[:10]:
                print("       " + line)


def counterexample(name, ok, detail=""):
    """反例自证：判据必须能抓住坏写法（计入总断言，同时单独计数）。"""
    global _counter
    _counter += 1
    check("反例 " + name, ok, detail)


def warn(text):
    _warnings.append(text)
    print("  WARN %s" % text)


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def method_body(text, signature):
    """取签名之后第一个 `{...}` 块（含嵌套配平）。"""
    index = text.find(signature)
    if index < 0:
        return ""
    start = text.find("{", index)
    if start < 0:
        return ""
    depth = 0
    for position in range(start, len(text)):
        char = text[position]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:position + 1]
    return ""


def javadoc_before(text, signature):
    """取方法签名紧邻的 `/** ... */` 注释块（找不到返回空串）。"""
    index = text.find(signature)
    if index < 0:
        return ""
    start = text.rfind("/**", 0, index)
    if start < 0:
        return ""
    end = text.find("*/", start)
    if end < 0 or end > index:
        return ""
    return text[start:end + 2]


def where(text, needle):
    """needle 在源码里的行号（给报告用，找不到返回 L?）。"""
    index = text.find(needle)
    if index < 0:
        return "L?"
    return "L%d" % (text.count("\n", 0, index) + 1)


# ======================================================================================
# 模型：新口径 / 旧口径（ceil）
# ======================================================================================

def capacity(remaining, in_flight, p=None):
    """新口径 `max(0, R − inFlight)`；**p 参数只为证明「与 p 无关」**，不参与计算。

    remaining = None 表示「订单不可读」⇒ 按订单计算的名额 = 0（真实实现另有一条
    「空管线允许 1 件」的兜底，见 unreadable_capacity()，仍满足「不多占」）。
    """
    if remaining is None:
        return 0
    return max(0, remaining - in_flight)


def unreadable_capacity(in_flight):
    """`remaining < 0`（判不出来）时的保守下限：`inFlight <= 0 ? 1 : 0`（源码 L8389 口径）。"""
    return 1 if in_flight <= 0 else 0


def legacy_capacity(remaining, in_flight, p):
    """旧口径（被用户驳回的 ceil 预开）：`max(0, ceil(R / p) − inFlight)`。"""
    if remaining is None:
        return 0
    expected = 0 if remaining <= 0 else int(math.ceil(remaining / p))
    return max(0, expected - in_flight)


class Lcg:
    """确定性伪随机（固定种子；不依赖时间 / 环境 / 外部状态，重跑结果逐位一致）。"""

    def __init__(self, seed):
        self.state = seed & 0x7FFFFFFF

    def next_float(self):
        self.state = (self.state * 1103515245 + 12345) & 0x7FFFFFFF
        return self.state / float(0x7FFFFFFF)


def simulate_replenish(use_legacy, order=ORDER, p=MAIN_CHANCE, throughput=4, seed=134,
                       max_ticks=20000):
    """补发模拟（事件流由固定 LCG 决定 ⇒ 新旧口径跑的是**同一串**成败）。

    t=0 的初始开工量就是两种口径的分歧点：新口径 = 订单量 20；旧 ceil 口径 = ceil(20/0.8) = 25。
    每 tick：① 至多 throughput 件在制件结算（成功 ⇒ delivered+1；失败 ⇒ 丢弃）；
    ② 用当时的 `capacity(remaining, inFlight)` 顶新件（同时受 throughput 限制）；
    ③ 记录不变量：inFlight ≤ remaining、remaining ≤ order、补发数 == 本 tick 失败数。

    返回 dict：initial / started / delivered / failures / ticks / max_concurrent /
    violations（inFlight > remaining 的时刻）/ mismatch（补发数 ≠ 失败数的时刻）。
    """
    rng = Lcg(seed)
    initial = legacy_capacity(order, 0, p) if use_legacy else order   # 25 or 20
    in_flight = initial
    started = in_flight
    delivered = 0
    failures = 0
    ticks = 0
    max_concurrent = in_flight
    violations = []
    mismatch = []
    while delivered < order and ticks < max_ticks:
        ticks += 1
        remaining = max(0, order - delivered)
        if in_flight > remaining:
            violations.append((ticks, "settle 前", in_flight, remaining))
        # ① 结算（至多 throughput 件）
        settle = min(throughput, in_flight)
        in_flight -= settle
        tick_failures = 0
        for _ in range(settle):
            if rng.next_float() < p:
                delivered += 1
            else:
                failures += 1
                tick_failures += 1
        remaining = max(0, order - delivered)
        # ② 顶新件：新口径 max(0, R − I)；旧口径 max(0, ceil(R/p) − I)
        cap = (legacy_capacity(remaining, in_flight, p) if use_legacy
               else capacity(remaining, in_flight))
        new_units = min(cap, throughput)
        in_flight += new_units
        started += new_units
        if new_units != tick_failures:
            mismatch.append((ticks, new_units, tick_failures))
        if in_flight > remaining:
            violations.append((ticks, "顶新件后", in_flight, remaining))
        max_concurrent = max(max_concurrent, in_flight)
    return {
        "initial": initial,
        "started": started,
        "delivered": delivered,
        "failures": failures,
        "ticks": ticks,
        "max_concurrent": max_concurrent,
        "violations": violations,
        "mismatch": mismatch,
    }


# ======================================================================================
# ① 源码锚点
# ======================================================================================

def group_source_anchors():
    print("\n=== ① 源码锚点：名额 = max(0, R − inFlight)，与 p 无关 ===")
    check("①-1 SequenceExecutionChamberBlockEntity.java 存在", os.path.isfile(CHAMBER))
    if not os.path.isfile(CHAMBER):
        return
    src = read(CHAMBER)

    body = method_body(src, "private long startCapacityForRecipe(")
    check("①-2 startCapacityForRecipe(String) 方法体可定位 (%s)"
          % where(src, "private long startCapacityForRecipe("), bool(body))
    if not body:
        return

    check("①-3 【核心】else 分支写着 `final long allowed = remaining;` (%s)"
          % where(src, "final long allowed = remaining;"),
          "final long allowed = remaining;" in body)
    check("①-4 【核心】诊断预算取 allowed 本身 (`budgetForLog = allowed;`) (%s)"
          % where(src, "budgetForLog = allowed;"),
          "budgetForLog = allowed;" in body)
    check("①-5 【核心】`capacity = allowed - inFlight;`（allowed 就是 R，不再放大）(%s)"
          % where(src, "capacity = allowed - inFlight;"),
          "capacity = allowed - inFlight;" in body)
    check("①-6 结果统一夹 0：`final long result = Math.max(0L, capacity);` (%s)"
          % where(src, "final long result = Math.max(0L, capacity);"),
          "final long result = Math.max(0L, capacity);" in body)

    # ---- 负锚点：旧写法在整个文件里必须彻底消失（改回旧口径 ⇒ 本组立刻变红）----
    check("①-7 全文件不含旧期望值公式 `Math.ceil(remaining`（旧口径已删干净）",
          "Math.ceil(remaining" not in src)
    check("①-8 全文件不含 `(double) p)`（旧口径的 double 放大已删干净）",
          "(double) p)" not in src)
    check("①-9 全文件不含 `if (!guaranteedResult(recipeId)) {`（必得 / 概率不再分支处理名额）",
          "if (!guaranteedResult(recipeId)) {" not in src)
    check("①-10 全文件不含 `ceil` 参与**代码**（注释里的历史叙述不算：只查去注释后的代码）",
          "Math.ceil" not in strip_comments(src) and "ceil(" not in strip_comments(src))

    # ---- 诊断：p 仍算，但只在诊断字符串里 ----
    check("①-11 诊断行含 `;P=` 与 pLog (%s)" % where(src, '";P=" + pLog'),
          '";P=" + pLog' in body)
    check("①-12 诊断行含 `\" p=\" + pLog` 与 `guaranteedResult(recipeId) ? \" guaranteed\"` (%s)"
          % where(src, '" p=" + pLog + (guaranteedResult(recipeId) ? " guaranteed" : "")'),
          '" p=" + pLog + (guaranteedResult(recipeId) ? " guaranteed" : "")' in body)

    # ---- mainResultChance / guaranteedResult 只允许出现在诊断上下文（Debug 开关之后）----
    debug_at = body.find("if (RsccAssemblyDebug.isEnabled())")
    chance_at = body.find("mainResultChance(")
    check("①-13 mainResultChance( 在 startCapacityForRecipe 里只出现一次 (%s)"
          % where(src, "final float chanceForLog = mainResultChance(recipeId);"),
          body.count("mainResultChance(") == 1)
    check("①-14 【关键】chanceForLog 只出现在 `RsccAssemblyDebug.isEnabled()` 之后（诊断块内）",
          0 <= debug_at < chance_at,
          "debug_at=%d chance_at=%d" % (debug_at, chance_at))

    assign_lines = [line.strip() for line in body.splitlines()
                    if line.strip().startswith(("capacity =", "final long allowed =",
                                                "budgetForLog =", "final long result ="))]
    check("①-15 所有名额赋值行都不含 mainResultChance / guaranteedResult / ceil",
          bool(assign_lines) and not any(("mainResultChance" in line or "guaranteedResult" in line
                                          or "ceil" in line) for line in assign_lines),
          "赋值行：%s" % assign_lines)

    doc = javadoc_before(src, "private long startCapacityForRecipe(")
    check("①-16 javadoc 写明新口径「按需补发」（%s）"
          % where(src, "按需补发的闭环"),
          "按需补发" in doc)
    check("①-17 javadoc 写明「不预开，按实际损失补发」且点名 ceil(R/p) 是被驳回的旧设计",
          "不预开，按实际损失补发" in doc and "ceil(R/p)" in doc)
    check("①-18 javadoc 写明「与 p 无关」/「p 只用于诊断」",
          "与 p 无关" in doc and "不参与任何名额判定" in doc)

    # ---- 相关方法链 ----
    per_item = method_body(src, "private long startCapacityFor(final Item item)")
    check("①-19 startCapacityFor(Item) 逐配方取 min(startCapacityForRecipe) (%s)"
          % where(src, "capacity = Math.min(capacity, startCapacityForRecipe(recipeId));"),
          "capacity = Math.min(capacity, startCapacityForRecipe(recipeId));" in per_item)

    allowed = method_body(src, "private long allowedConcurrentUnits()")
    check("①-20 allowedConcurrentUnits()：R>0 用 R，否则 max(1, inFlight)（与 p 无关）(%s)"
          % where(src, "private long allowedConcurrentUnits()"),
          "final long remaining = remainingOrderUnits();" in allowed
          and "if (remaining > 0L)" in allowed
          and "return remaining;" in allowed
          and "Math.max(1L, inFlightUnitCount())" in allowed
          and "mainResultChance" not in allowed and "ceil" not in allowed)

    in_flight = method_body(src, "private long inFlightUnitsForRecipe(final String recipeId)")
    check("①-21 inFlightUnitsForRecipe = 四分量之和并夹 BUS_ITEM_TARGET_MAX "
          "（失败件从工位消失 ⇒ 分量减少 ⇒ 名额回到 1）(%s)"
          % where(src, "return Math.min(parts[0] + parts[1] + parts[2] + parts[3], BUS_ITEM_TARGET_MAX);"),
          "return Math.min(parts[0] + parts[1] + parts[2] + parts[3], BUS_ITEM_TARGET_MAX);"
          in in_flight)

    check("①-22 NO_ORDER_READABLE = -2L 且 `remaining == NO_ORDER_READABLE` ⇒ capacity 0 (%s)"
          % where(src, "private static final long NO_ORDER_READABLE = -2L;"),
          "private static final long NO_ORDER_READABLE = -2L;" in src
          and "if (remaining == NO_ORDER_READABLE) {" in body
          and "capacity = 0L;" in body)
    check("①-23 判不出来（remaining < 0）时保守下限 `capacity = inFlight <= 0L ? 1L : 0L;` (%s)"
          % where(src, "capacity = inFlight <= 0L ? 1L : 0L;"),
          "capacity = inFlight <= 0L ? 1L : 0L;" in body)
    check("①-24 名额每 tick 每配方至多算一次（startCapacityProbeCache，同 tick 纯函数）",
          "startCapacityProbeCache.get(recipeId)" in body
          and "startCapacityProbeCache.put(recipeId, result);" in body)


def strip_comments(text):
    """去注释（保留字符串字面量），用于「代码里不能有 ceil」这类判据。"""
    out = []
    index = 0
    length = len(text)
    quote = None
    while index < length:
        pair = text[index:index + 2]
        if quote is None and pair == "//":
            end = text.find("\n", index)
            if end < 0:
                break
            index = end
            continue
        if quote is None and pair == "/*":
            end = text.find("*/", index + 2)
            index = length if end < 0 else end + 2
            continue
        char = text[index]
        if quote is None and char in "\"'":
            quote = char
        elif quote == char and text[index - 1] != "\\":
            quote = None
        out.append(char)
        index += 1
    return "".join(out)


# ======================================================================================
# ② 模型：p 无关
# ======================================================================================

def group_model():
    print("\n=== ② 模型复刻：capacity = max(0, R − inFlight)，且与 p 无关 ===")
    check("②-1 capacity(20, 5) == 15", capacity(20, 5) == 15, "得到 %r" % capacity(20, 5))
    check("②-2 capacity(20, 0) == 20", capacity(20, 0) == 20, "得到 %r" % capacity(20, 0))
    check("②-3 capacity(0, 0) == 0", capacity(0, 0) == 0, "得到 %r" % capacity(0, 0))
    check("②-4 capacity(5, 7) == 0（在制多于剩余 ⇒ 夹 0，不是 -2）",
          capacity(5, 7) == 0, "得到 %r" % capacity(5, 7))
    check("②-5 capacity(None, 0) == 0（订单不可读 ⇒ 不按订单追加名额）",
          capacity(None, 0) == 0, "得到 %r" % capacity(None, 0))
    check("②-6 判不出来时的保守下限：空管线允许 1 件、已有在制则 0",
          unreadable_capacity(0) == 1 and unreadable_capacity(1) == 0
          and unreadable_capacity(5) == 0)

    same_all_p = all(capacity(20, 5, p) == 15 for p in PROBES)
    check("②-7 【核心】p ∈ {1.0, 0.8, 0.5, 0.25, 0.1} 时 capacity(20, 5) 全部 == 15（与 p 无关）",
          same_all_p, "各 p 结果：%r" % [(p, capacity(20, 5, p)) for p in PROBES])
    same_sweep = all(
        len({capacity(remaining, in_flight, p) for p in PROBES}) == 1
        for remaining in (0, 1, 5, 20, 64)
        for in_flight in (0, 1, 3, 20, 25)
    )
    check("②-8 全扫描 (R ∈ {0,1,5,20,64}) × (I ∈ {0,1,3,20,25}) 内，各 p 的结果集合大小都为 1",
          same_sweep)

    legacy_varies = len({legacy_capacity(20, 5, p) for p in PROBES}) > 1
    check("②-9 对照：旧 ceil 口径在同一扫描上**随 p 变化**（证明「与 p 无关」不是永真断言）",
          legacy_varies,
          "旧口径各 p：%r" % [(p, legacy_capacity(20, 5, p)) for p in PROBES])


# ======================================================================================
# ③ 用户场景
# ======================================================================================

def group_user_scenario():
    print("\n=== ③ 用户场景：R=20、p=0.8 ===")
    new5 = capacity(20, 5, 0.8)
    old5 = legacy_capacity(20, 5, 0.8)
    check("③-1 R=20、inFlight=5、p=0.8 ⇒ 新口径 **15**（实际 %d）" % new5, new5 == 15)
    check("③-2 同一场景旧口径会是 **20**（ceil(20/0.8)−5），新口径必须不是 20",
          old5 == 20 and new5 != old5,
          "旧=%d 新=%d" % (old5, new5))

    new0 = capacity(20, 0, 0.8)
    old0 = legacy_capacity(20, 0, 0.8)
    check("③-3 R=20、inFlight=0、p=0.8 ⇒ 新口径 **20**（只按订单量开工，实际 %d）" % new0,
          new0 == 20)
    check("③-4 旧口径会 **25**（正是用户抱怨的「多占 5 份起步原料」）⇒ 新口径少开 5 件",
          old0 == 25 and new0 == 20 and old0 - new0 == 5,
          "旧=%d 新=%d" % (old0, new0))

    at_start = simulate_replenish(use_legacy=False)
    check("③-5 模拟 t=0 实开 %d 件（不是 25）" % ORDER, at_start["started"] >= ORDER
          and at_start["max_concurrent"] <= ORDER,
          "started=%d max_concurrent=%d" % (at_start["started"], at_start["max_concurrent"]))


# ======================================================================================
# ④ 补发模拟
# ======================================================================================

def group_simulation():
    print("\n=== ④ 确定性补发模拟（R=20、p=0.8、每 tick 至多结算 4 件、固定种子 134） ===")
    sim = simulate_replenish(use_legacy=False)
    check("④-1 最终 delivered == 20（订单做满）", sim["delivered"] == ORDER,
          "delivered=%d ticks=%d" % (sim["delivered"], sim["ticks"]))
    check("④-2 总开工数 == 20 + 失败数（补发只由真实失败触发）",
          sim["started"] == ORDER + sim["failures"],
          "started=%d failures=%d" % (sim["started"], sim["failures"]))
    check("④-3 【核心】任一时刻 inFlight ≤ remaining（从未多占起步原料）",
          not sim["violations"],
          "违规时刻：%r" % (sim["violations"][:5],))
    check("④-4 任一时刻 remaining ≤ 20 且同时在制 ≤ 20",
          sim["max_concurrent"] <= ORDER,
          "max_concurrent=%d" % sim["max_concurrent"])
    check("④-5 每一 tick 的补发数都恰好等于本 tick 失败数（失败一件补一件，不多补）",
          not sim["mismatch"],
          "不等时刻：%r" % (sim["mismatch"][:5],))
    check("④-6 模拟确实发生过失败（否则「补发」这条路径没被覆盖）",
          sim["failures"] > 0, "failures=%d" % sim["failures"])
    check("④-7 模拟在有限 tick 内收敛（ticks < 20000，无死循环）",
          sim["ticks"] < 20000, "ticks=%d" % sim["ticks"])

    again = simulate_replenish(use_legacy=False)
    check("④-8 重跑结果逐位一致（确定性：固定种子，不依赖外部状态）",
          again == sim)

    legacy = simulate_replenish(use_legacy=True)
    check("④-9 对照：同一事件流下旧 ceil 口径 t=0 就开 %d 件（新口径 20）"
          % legacy["initial"],
          legacy["initial"] == 25 and legacy["max_concurrent"] == 25,
          "initial=%d max_concurrent=%d" % (legacy["initial"], legacy["max_concurrent"]))
    check("④-10 对照：旧口径违反了「inFlight ≤ remaining」不变量（新口径不变量不是永真）",
          bool(legacy["violations"]),
          "违规时刻（前 5 个）：%r" % (legacy["violations"][:5],))


# ======================================================================================
# ⑤ 反例自证
# ======================================================================================

def group_counterexamples():
    print("\n=== ⑤ 反例自证：把模型改回 ceil 必须被抓住 ===")
    counterexample("A 旧口径 (R=20, I=0, p=0.8) == 25 ≠ 新口径 20",
                   legacy_capacity(20, 0, 0.8) == 25
                   and capacity(20, 0, 0.8) == 20
                   and legacy_capacity(20, 0, 0.8) != capacity(20, 0, 0.8))
    counterexample("B 旧口径 (R=20, I=5, p=0.8) == 20 ≠ 新口径 15",
                   legacy_capacity(20, 5, 0.8) == 20
                   and capacity(20, 5, 0.8) == 15
                   and legacy_capacity(20, 5, 0.8) != capacity(20, 5, 0.8))
    counterexample("C capacity(0, 0) == 0（订单已满 ⇒ 一件都不开）",
                   capacity(0, 0) == 0)
    counterexample("D capacity(5, 7) == 0（在制 > 剩余 ⇒ 夹 0，而不是负名额）",
                   capacity(5, 7) == 0)
    counterexample("E 订单不可读（None）⇒ capacity == 0（不按订单追加；真实实现另有 ≤1 件兜底）",
                   capacity(None, 0) == 0 and capacity(None, 5) == 0)
    counterexample("F p=1.0（必得）时新旧口径一致 == 20（本轮只改概率口径，没改坏必得口径）",
                   legacy_capacity(20, 0, 1.0) == capacity(20, 0, 1.0) == 20
                   and legacy_capacity(20, 5, 1.0) == capacity(20, 5, 1.0) == 15)
    counterexample("G 旧口径在 p=0.5 时 (20, 0) 会开 40 件（新口径恒 20）",
                   legacy_capacity(20, 0, 0.5) == 40 and capacity(20, 0, 0.5) == 20)
    counterexample("H 若把新模型误写成 max(0, R − I·p)（用 p 缩放在制件）也会被抓住："
                   "(20, 5, 0.8) 得 16 ≠ 15",
                   max(0, 20 - int(5 * 0.8)) == 16 and capacity(20, 5, 0.8) == 15)


# ======================================================================================
# ⑥ 自动发现 + 历史注释残留
# ======================================================================================

def group_discovery():
    print("\n=== ⑥ 工具链自动发现（tools/_run_all_selfchecks.py） ===")
    check("⑥-1 _run_all_selfchecks.py 存在", os.path.isfile(RUNNER))
    if not os.path.isfile(RUNNER):
        return
    runner = read(RUNNER)
    check("⑥-2 runner 用 glob 自动发现 `selfcheck_*.py`（无需显式登记）",
          'glob.glob(os.path.join(HERE, "selfcheck_*.py"))' in runner)
    check("⑥-3 本脚本文件名匹配该 glob（selfcheck_*.py）",
          os.path.basename(SELF).startswith("selfcheck_") and SELF.endswith(".py"),
          os.path.basename(SELF))
    check("⑥-4 runner 以退出码判定成败（非 0 即失败）⇒ 本脚本失败时必须返回 1",
          "ok = proc.returncode == 0" in runner and "sys.exit(1 if fails else 0)" in runner)
    check("⑥-5 runner 取 stdout 最后一行做摘要 ⇒ 本脚本最后一行必须是 ALL_PASS",
          '[-1:] or [""]' in runner and 'print("ALL_PASS")' in read(SELF))


def group_stale_notes():
    """历史注释残留：代码已改对，但少数注释仍在描述被驳回的 ceil 口径。

    **这里只报警告、不算失败**：本轮写域只有本脚本，改 src 不归我；报给 Lead 由他决定。
    只挑与「订单名额」相关的行（`ceil(R/p)` / `ceil(订单剩余 / p)`），
    避免把无关的 `ceil(台数 / 预算)`（总线轮转预算）误报进来；
    已经写明「被用户驳回 / 不再用」的说明性注释也不算残留。
    """
    print("\n=== ⑦ 只读巡检：历史注释残留（警告，不计失败） ===")
    if not os.path.isfile(CHAMBER):
        return
    lines = read(CHAMBER).splitlines()
    stale = []
    for number, line in enumerate(lines, 1):
        if "ceil(" not in line:
            continue
        if "R/p" not in line and "订单剩余" not in line:
            continue
        if "驳回" in line or "不再用" in line:
            continue
        stale.append((number, line.strip()))
    if stale:
        for number, text in stale:
            warn("历史注释仍描述旧 ceil 名额口径 @L%d：%s" % (number, text[:100]))
    else:
        print("  OK   未发现描述旧 ceil 名额口径的注释残留")


# ======================================================================================
# main
# ======================================================================================

def main():
    print("第 134 轮自检：执行舱「按需补发」——名额 = max(0, 订单剩余 − 在制件数)，与 p 无关")
    group_source_anchors()
    group_model()
    group_user_scenario()
    group_simulation()
    group_counterexamples()
    group_discovery()
    group_stale_notes()

    print("\n=== 汇总 ===")
    print("断言 %d 条（其中反例 %d 条），失败 %d 条" % (_passed, _counter, _failed))
    if _warnings:
        print("警告 %d 条（不计失败，见 ⑦ 组）：" % len(_warnings))
        for text in _warnings:
            print("  - %s" % text)
    if _failed:
        print("失败清单：")
        for name, detail in _failures:
            print("  - %s%s" % (name, ("  <- " + str(detail).strip().splitlines()[0])
                                if detail else ""))
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
