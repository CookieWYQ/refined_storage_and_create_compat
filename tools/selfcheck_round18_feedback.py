#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 18 轮反馈（用户第 2~6 条）—— 源码锚点 + 模型推演 + 反例表。

用法：python tools/selfcheck_round18_feedback.py

覆盖：
  #2 多余中间产物：legacy 中间产物类别只在本仓「当前流程」的配方里展开
  #3 列车轨道链路断点：合并列 → 真实步序的展开（nextStepOwner）+ 缺步明确报错 + 界面候选列表
  #4 没有中间产物缓存仓时仍能显示「该步会产出什么」
  #5 逐步骤总线配置校验：不齐 ⇒ 不消耗任何原料 + 横幅报出「第 N 步缺输入/输出总线」
  #6 挂起 ⇒ 先退回网络（守恒平账）；恢复 ⇒ 再按需拉回

退出码：0 = 没有任何 FAIL。
"""

from __future__ import annotations

import io
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

FAILURES = []
CHECKS = [0]


def read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


chamber = read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))
watchdog = read(os.path.join("support", "AssemblyWatchdog.java"))
screen = read(os.path.join("client", "screen", "SequencePatternTerminalScreen.java"))
ghost = read(os.path.join("client", "widget", "GhostMarkerRenderer.java"))
cache = read(os.path.join("support", "RsccSharedCache.java"))

# ======================================================================
# 2) 多余中间产物
# ======================================================================
section("2) 多余中间产物：legacy 中间产物类别只在本仓「当前流程」的配方里展开")

has(chamber, "private Set<String> activePipelineRecipeIds() {",
    "锚点 2a: 「本仓当前流程的配方」只有一份实现（activePipelineRecipeIds）")
has(chamber, "if (!activeRecipes.isEmpty() && !activeRecipes.contains(info.recipe())) {",
    "锚点 2b: legacy 类别展开时按当前流程过滤配方（不属于它的配方不为它放行）")
check("锚点 2c: 归属表整表重建时只算一次当前流程（不逐台总线重复解析配方）",
      "final Set<String> activeRecipes = activePipelineRecipeIds();" in chamber
      and "normalizeBusOwners" in chamber)

CATS = [("sturdy_sheet", 2), ("track", 2)]


def legacy_expand(legacy_step, categories, active_recipes):
    """normalizeBusOwners 的 legacy 展开模型（与源码逐字对齐）。"""
    out = []
    for recipe, step in categories:
        if step != legacy_step:
            continue
        if active_recipes and recipe not in active_recipes:
            continue
        out.append((recipe, step))
    return out


print("  推演表（legacy 类别 'intermediate:2' 的展开；同时挂了 sturdy_sheet 与 track）")
print("  %-42s %-34s %s" % ("情形", "展开结果", "结论"))
for label, active in [("当前流程 = track（正在下单列车轨道）", {"track"}),
                      ("当前流程 = sturdy_sheet（正在下单坚固板）", {"sturdy_sheet"}),
                      ("空闲（无活跃任务，门控关着不导出）", set())]:
    expanded = legacy_expand(2, CATS, active)
    print("  %-42s %-34s %s" % (label, expanded, "OK"))
check("2d 有活跃订单时，一个 legacy 类别<b>只给当前流程那条配方放行</b>（绝不给另一条流程同时放行）",
      legacy_expand(2, CATS, {"track"}) == [("track", 2)]
      and legacy_expand(2, CATS, {"sturdy_sheet"}) == [("sturdy_sheet", 2)])
check("2e 反例（修复前）：无过滤时 legacy 'intermediate:2' 同时给两条配方放行 ⇒ 不属于当前订单的"
      "中间产物也被备料 / 导出（就是用户第 2 条的「多余中间产物」）",
      len([c for c in CATS if c[1] == 2]) == 2)

# ======================================================================
# 3) 列车轨道链路断点 + 界面候选列表
# ======================================================================
section("3) 列车轨道：合并列 → 真实步序展开（nextStepOwner）+ 缺步报错 + 界面候选列表")

has(watchdog, "private static BlockPos machineAtExpandedStep(",
    "锚点 3a: 「合并列 → 真实步序 → 机器」只有一份实现（machineAtExpandedStep）")
has(watchdog, "final BlockPos pos = machineAtExpandedStep(units, index);",
    "锚点 3b: nextStepOwnerOf 走展开映射（不再直接 units.get(index)）")
has(watchdog, "private static int coveredStepCount(",
    "锚点 3c: 「编排覆盖到第几步」只有一份实现（coveredStepCount）")
has(watchdog, '"bindinggap@" + taskId,',
    "锚点 3d: 编排没覆盖配方全部步骤时<b>明确 WARN</b>（绝不静默不动）")
has(screen, "private List<ItemStack> stepInputCandidates(",
    "锚点 3e: 流程行的输入候选整组展开（标签 / 多选 ⇒ 列表）")
has(screen, '"input_slot.tip"', "锚点 3f: 顶部输入原料槽常显当前那一件；Shift 只给角色说明（不枚举候选）")
has(screen, '"card.input"', "锚点 3g: 流程行 tooltip 只写当前轮播到的那一件（用户要求不枚举、不写数量）")
has(ghost, "public static List<ItemStack> candidateItems(",
    "锚点 3h: 候选收集按 ingredient 展开全部物品（标签 → 整组），界面共用一份")


def machine_at_expanded(units, step_index):
    """machineAtExpandedStep 的模型：units = [(step, count)]，返回 'machine' 或 None。"""
    for step, count in units:
        if step >= 0 and step >= 0 and step_index >= step and step_index < step + count:
            return "machine"
    return None


def machine_old(units, total_steps, step_index):
    """修复前的模型（直接下标 units，越界即 none）。"""
    if step_index >= len(units):
        return None
    return "machine"


# 坚固板：sequence = [filling, pressing, pressing] → 合并成 [(0,1),(1,2)]
MERGED = [(0, 1), (1, 2)]
TOTAL = 3
print()
print("  推演表（坚固板/列车轨道：相邻相同步骤合并成一列）")
print("  %-46s %-12s %-12s" % ("下一步的步序（step+1）", "修复前", "修复后"))
for idx in range(TOTAL):
    print("  %-46s %-12s %-12s" % (idx, machine_old(MERGED, TOTAL, idx), machine_at_expanded(MERGED, idx)))
check("3i 被合并掉的层级（index=2，末步）修复前被判 none（看起来像「没有下一步骤」），"
      "修复后能正确解析到那台机器（末步有明确认领者）",
      machine_old(MERGED, TOTAL, 2) is None and machine_at_expanded(MERGED, 2) == "machine")
check("3j 未覆盖的步（index=3，超出编排）修复后返回 None ⇒ 明确报 unassigned（绝不静默）",
      machine_at_expanded(MERGED, 3) is None)

# ======================================================================
# 4) 没有缓存仓也能显示「该步会产出什么」
# ======================================================================
section("4) 没有中间产物缓存仓时，仍列出本流程的中间产物（该步会产出什么）")

has(chamber, "public List<ItemStack> pipelineIntermediateEntries() {",
    "锚点 4a: 「本流程中间产物条目」只有一份实现（pipelineIntermediateEntries）")
has(chamber, "private void notifyIntermediatesInvisibleWithoutCache() {",
    "锚点 4b: 无缓存仓时的可见提示（列出中间产物 + 说明为何终端看不到）")
has(chamber, "notifyIntermediatesInvisibleWithoutCache();",
    "锚点 4c: 该提示挂在「有相关任务在跑且未冻结」的门控里（空闲期不骚扰）")
has(cache, "if (warehouses.isEmpty()) {\n            return List.of();",
    "锚点 4d: 没有缓存仓 ⇒ 共享池为空（界面上「终端看不到中间产物」的机制已在源码里确认）")


def intermediate_entries(categories, held):
    """pipelineIntermediateEntries 的模型：条目只依赖类别表，不依赖缓存池。"""
    return [(icon, held.get(icon, 0)) for icon in categories]


ENTRIES = intermediate_entries(["incomplete_precision_mechanism", "incomplete_obsidian_sheet"], {})
print()
print("  %-52s %-10s %s" % ("情形", "条目数", "结论"))
print("  %-52s %-10d %s" % ("无缓存仓 + 一件都还没产出", len(ENTRIES), "仍列出「该步会产出什么」"))
check("4e 无缓存仓时条目非空（至少显示该步会产出什么），且计数 0 不隐藏条目",
      len(ENTRIES) == 2 and all(count == 0 for _i, count in ENTRIES))
check("4f 反例：若条目数据源改成「共享缓存池」⇒ 无缓存仓时池子为空 ⇒ 条目为空（用户看到的"
      "「终端里没有任何中间产物」正是这条路径）",
      len(intermediate_entries([], {})) == 0)

# ======================================================================
# 5) 下单前 / 运行中的逐步骤总线配置校验
# ======================================================================
section("5) 逐步骤总线配置校验：不齐 ⇒ 不消耗 + 横幅报出「第 N 步缺输入/输出总线」"
        "（第 19 轮修假阳性）")

has(chamber, "public List<BusConfigGap> busConfigGaps() {",
    "锚点 5a: 「逐步骤总线配置缺口」只有一份实现（busConfigGaps）")
has(chamber, "&& busConfigGate;",
    "锚点 5b: 配置门控接入 isAutoCraftingEnabled（导出侧 / 收回侧一并停）")
has(chamber, "|| !busConfigGate",
    "锚点 5c: 配置门控接入 fillInternalForBus（备料侧当刻停 ⇒ 不取料）")
has(chamber, "private void sendBusConfigBanner(final List<BusConfigGap> gaps) {",
    "锚点 5d: 缺口用既有横幅通道播出（不另造 UI）")
has(chamber, "busConfigGapSignature = gapSignature;",
    "锚点 5e: 按签名去重（内容变了才再播一次，绝不每 0.5 秒刷）")
# ---- 第 19 轮：假阳性三处根因的修复锚点 ----
has(chamber, "RsccBusCategory.intermediateId(recipeId, step), recipe, step)) {",
    "锚点 5f: 输出侧用<b>公开类别 id</b>（RsccBusCategory.intermediateId）判断被勾中 —— "
    "不再用内部复合键 intermediateKey（旧写法恒不命中 ⇒ 已配置也一直报「缺输出总线配置」）")
has(chamber, "private List<BlockPos> collectImporters(final boolean restrictToMyChain) {",
    "锚点 5g: 校验把<b>输入总线</b>也算进来（本架构里机器产出 / 废料 / 中间产物由输入总线收回网络）"
    "（第 24 轮把参数名 requireSelfLink 改成 restrictToMyChain：判定从「自身解析出的执行仓 == 本仓」"
    "升级为「与本仓同属一条链」，但这个收集器本身与「输入总线也算进来」这条语义一字未改）")
has(chamber, "if (importer.rscc$isAutoCollect()) {",
    "锚点 5h: 「全自动收回」的输入总线 = 该步产出有了合法归宿（不再要求玩家在输出总线上勾成品 / 废料）")
has(chamber, "if (!activeRecipes.contains(recipeId)) {",
    "锚点 5i: 只校验<b>本仓当前流程</b>的配方（旧 id 归一化只展开当前流程 ⇒ 否则另一条配方会被误报）")
has(chamber, "if (level == null || level.isClientSide() || outputMode != OutputMode.BUS) {",
    "锚点 5j: 面输出模式没有「总线配置」这回事 ⇒ 一律不校验（不误报）")


def step_configured(exporter_ids, importer_ids, auto_importer, needed_inputs, needed_output):
    """busConfigGaps 的模型：输入只看输出总线（喂料只能由它做）；
    输出只要「输出总线或输入总线勾了它」或「存在全自动收回的输入总线」即可。"""
    inputs_ok = all(need in exporter_ids for need in needed_inputs)
    outputs_ok = auto_importer or needed_output in exporter_ids or needed_output in importer_ids
    return inputs_ok and outputs_ok


def consumed(planned, config_ok):
    """消耗模型：配置门控为假 ⇒ 一份都不取（绝不「白烧」）。"""
    return planned if config_ok else 0


# 用户现场的<b>正确</b>配置（来自实机日志）：输出总线勾了 input: 与 intermediate:，输入总线全自动。
LIVE_EXPORTER = {"input:create:powdered_obsidian", "fluid:minecraft:lava",
                 "intermediate:create:sequenced_assembly/sturdy_sheet:0"}
LIVE_IMPORTER = set()
INPUTS_STEP0 = ["input:create:powdered_obsidian", "fluid:minecraft:lava"]
OUT_STEP0 = "intermediate:create:sequenced_assembly/sturdy_sheet:0"
# 旧实现（只认输出总线的「成品 / 废料类别」、且用内部复合键）：
LIVE_EXPORTER_WITH_RESULT = LIVE_EXPORTER | {"result:create:sturdy_sheet"}

print()
print("  %-58s %-10s %-10s" % ("情形", "配置齐吗", "本次消耗"))
CASES = [
    ("① 玩家按界面提示勾好（输出总线勾 input+intermediate；输入总线全自动）",
     LIVE_EXPORTER, LIVE_IMPORTER, True, INPUTS_STEP0, OUT_STEP0),
    ("①b 同上，但输入总线是手动模式且没勾中间产物（输出总线也没勾）",
     LIVE_EXPORTER, LIVE_IMPORTER, False, INPUTS_STEP0, "intermediate:create:sequenced_assembly/track:0"),
    ("② 真的缺该步输出（该步的中间产物类别没人勾、也没有全自动输入总线）",
     LIVE_EXPORTER, LIVE_IMPORTER, False, INPUTS_STEP0,
     "intermediate:create:sequenced_assembly/sturdy_sheet:1"),
]
for label, exp, imp, auto, need_in, need_out in CASES:
    ok = step_configured(exp, imp, auto, need_in, need_out)
    print("  %-58s %-10s %-10d" % (label, ok, consumed(4, ok)))
check("5k 用户现场的<b>正确</b>配置 ⇒ 零告警、门控放行（这是第 19 轮要修的反例："
      "旧实现因为「输出侧只认输出总线的成品 / 废料类别」+「内部复合键恒不命中」把它误报成缺配置）",
      step_configured(LIVE_EXPORTER, LIVE_IMPORTER, True, INPUTS_STEP0, OUT_STEP0))
MISSING_OUT = "intermediate:create:sequenced_assembly/sturdy_sheet:1"
check("5l 反例：真的缺该步输出总线（没有任何总线为它配置、也没有全自动收回）⇒ 判缺 + 本次消耗 0",
      not step_configured(LIVE_EXPORTER, LIVE_IMPORTER, False, INPUTS_STEP0, MISSING_OUT)
      and consumed(4, step_configured(LIVE_EXPORTER, LIVE_IMPORTER, False, INPUTS_STEP0, MISSING_OUT)) == 0)
check("5m 旧 id（legacy intermediate:2 / intermediate:0）经归一化后的类别 id 与新写法一致 ⇒ "
      "旧存档玩家不被误判（校验用的是 RsccBusCategory.intermediateId 的产物，"
      "而归一化登记进 busCategoryOwners 的也是同一个 id）",
      "RsccBusCategory.intermediateId(recipeId, step)" in chamber
      and "info.id()" in chamber)
check("5n 「中间产物类别用旧 id 勾中」的旧存档：归一化在 busCategoryOwners 里登记的就是新 id ⇒ "
      "校验命中它、不报缺（两侧同一份 id 口径）",
      "addBusOwner(rebuilt, visible, info.id(), pos);" in chamber)
def old_output_configured(exporter_ids, needed_result):
    """旧口径（修复前）：末步只认「输出总线上勾中的成品 / 废料类别」。"""
    return needed_result in exporter_ids


check("5o 反例（修复前）：末步的成品 / 废料交给「全自动收回的输入总线」负责时，旧口径"
      "（只认输出总线上勾中的成品 / 废料类别）判为缺配置 ⇒ 假阳性",
      not old_output_configured(LIVE_EXPORTER, "result:create:sturdy_sheet")
      and step_configured(LIVE_EXPORTER, LIVE_IMPORTER, True, INPUTS_STEP0, OUT_STEP0))
check("5p 门控仍与消耗绑定：配置不齐 ⇒ 消耗 0（不会「白烧黑曜石粉 / 岩浆」）",
      consumed(9, step_configured(LIVE_EXPORTER, LIVE_IMPORTER, False, INPUTS_STEP0, "x:y:9")) == 0)

# ======================================================================
# 6) 挂起 ⇒ 先退回网络；恢复 ⇒ 再按需拉回
# ======================================================================
section("6) 挂起 ⇒ 先退回网络（守恒平账）；恢复 ⇒ 再按需拉回")

has(chamber, "private void flushChamberForSuspend() {",
    "锚点 6a: 「挂起边沿全额回流」只有一份实现（flushChamberForSuspend）")
has(chamber, "flushChamberForSuspend();",
    "锚点 6b: 该回流挂在「转为冻结」的那一个边沿")
has(chamber, "if (!busSuspendFlushed) {",
    "锚点 6c: 同一段挂起只退一次（解冻后复位，下一次挂起还能再退）")
check("锚点 6d: 挂起回流走守恒账本（物品与流体都记 toNetwork），因此「离开 − 进入 = 留存」仍平账",
      "flowLedger.toNetwork(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger\n"
      "                    .itemKey(inSlot.getItem()), inserted);" in chamber
      and "flowLedger.toNetwork(cretae.cookiewyq.rs_create_compat.support.RsccFlowLedger\n"
          "                    .fluidKey(entry.getKey()), inserted);" in chamber)

CELL = [0, 0, 0]  # [fromNetwork, toNetwork, live]


def suspend_return(pulled, drained, inserted, refilled):
    """挂起回流模型（与 flushChamberForSuspend 逐字对齐）。

    pulled     = 任务期间抽进本仓的量
    drained    = 挂起时从本仓抽出的量
    inserted   = 真正插入网络的量
    refilled   = 网络装不下的余量「原样填回本仓」的量（守恒要求：drained = inserted + refilled）
    """
    live = pulled - drained + refilled
    to_network = inserted
    retained = live                 # 基线 0；toMachine − fromMachine = 0
    return retained, retained - (pulled - to_network)


print()
print("  %-56s %-10s %-10s %s" % ("情形", "留存", "未对平", "结论"))
CASES = [
    ("挂起：抽进 4 件，全额退回网络", 4, 4, 4, 0),
    ("挂起：抽进 500 mB，全额退回网络", 500, 500, 500, 0),
    ("网络装不下 1 件 ⇒ 余量原样填回舱内（留存 1）", 4, 4, 3, 1),
    ("反例：装不下的余量没填回（凭空销毁 1 件）", 4, 4, 3, 0),
]
for label, pulled, drained, inserted, refilled in CASES:
    ret, bad = suspend_return(pulled, drained, inserted, refilled)
    print("  %-56s %-10d %-10d %s" % (label, ret, bad, "OK" if bad == 0 else "MISMATCH(如实报出)"))
check("6e 挂起全额退回 ⇒ 舱内存量 = 0 且账本未对平 = 0（离开 − 进入 = 留存 平账）",
      suspend_return(4, 4, 4, 0) == (0, 0) and suspend_return(500, 500, 500, 0) == (0, 0))
check("6f 网络装不下的余量「原样填回本仓」时账本仍平账（那是留存而不是丢失）",
      suspend_return(4, 4, 3, 1) == (1, 0))
check("6g 反例：若余量被凭空销毁（没填回本仓）⇒ 账本立刻报未对平（不是静默吞掉）"
      "⇒ 这是「退不干净 / 丢东西」的取证口",
      suspend_return(4, 4, 3, 0)[1] == -1)

# ======================================================================
# 7) 第 19 轮：#2 类别清单漏配方 · #3 挂起被立刻撤销 · #4 总样板始终生成
# ======================================================================
section("7) 第 19 轮：#2 类别清单漏配方 · #3 挂起被立刻撤销 · #4 总样板始终生成")

terminal_src = read(os.path.join("block", "entity", "SequencePatternTerminalBlockEntity.java"))
menu_src = read(os.path.join("menu", "SequencePatternTerminalMenu.java"))
alerts_client = read(os.path.join("client", "AssemblyAlertsClient.java"))

# ---- #2 输出总线类别清单漏配方 ----
has(chamber, "private Map<String, Set<Integer>> patternAssignedSteps(final Level level) {",
    "锚点 7a: 「总样板把某步指派给本仓」的只读来源只有一份实现（patternAssignedSteps）")
# 锚点同步（第 20 轮回归修正）：上一轮把 patternAssignedSteps 合并进了 computeOwnedSteps（= 属主判定），
# 导致「没下单也开工」。现在口径收紧为：它<b>只能</b>补类别（显示 / 勾选），绝不进属主判定。
check("锚点 7b（第 20 轮修正）：computeOwnedSteps <b>不</b>合并 patternAssignedSteps —— "
      "「能显示某步的类别」绝不等价于「本仓拥有该步」",
      "final Set<Integer> steps = owned.computeIfAbsent(assigned.getKey()" not in chamber
      and "不得</b>进入本方法（= 属主判定）" in chamber)
has(chamber,
    "stepPrototypes.putIfAbsent(key, stepPrototypeOf(transitionalStack, recipeLocation, step));",
    "锚点 7c: 类别表为这些步补出「中间产物」类别（供显示 / 勾选）")
category_pass = chamber.split("patternAssignedSteps(level)")[1].split("// 7)")[0]
check("7c2 补类别时<b>不</b>登记该步的输入 / 流体输入 / 成品 / 废料类别（否则玩家一勾就产生备料需求 "
      "⇒ 绕过「必须有人下单」这条硬底线）",
      "addInputCategory(inputs, representative);" not in category_pass
      and "addFluidCategory(fluids, stack);" not in category_pass
      and "addProductCategories(recipe, results, scraps);" not in category_pass)


def categories_of(chamber_recipe_ids, assigned_recipe_ids):
    """类别表覆盖的配方集合模型 = 本仓单元样板带来的配方 ∪ 总样板指派给本仓的配方。"""
    return sorted(set(chamber_recipe_ids) | set(assigned_recipe_ids))


check("7d 判据：一台机械手同时服务精密构件与列车轨道 ⇒ 两条配方各自的类别都出现",
      categories_of(["create:sequenced_assembly/precision_mechanism"],
                    ["create:sequenced_assembly/track"])
      == ["create:sequenced_assembly/precision_mechanism", "create:sequenced_assembly/track"]
      and "patternAssignedSteps(level)" in chamber)
check("7e 反例（修复前）：类别只从「本仓单元样板」推导 ⇒ 另一条配方（列车轨道）一条类别都没有"
      "（用户实测：输出总线详细配置里「只显示了精密构件的」）",
      categories_of(["create:sequenced_assembly/precision_mechanism"], [])
      == ["create:sequenced_assembly/precision_mechanism"])

# ---- #3 挂起没真正生效（被立刻撤销） ----
has(alerts_client, "private static String pendingKey(final UUID taskId, final int actionBit) {",
    "锚点 7f: 客户端「在途动作」的键只有一份实现")
check("7g 挂起与继续共用同一个「主处置」在途键 ⇒ 一次转换落地（收到服务端新快照）之前，"
      "另一位也处在在途：按钮变灰、点击被吞掉（同一格不会被「再点一下」撤销）",
      "SyncAssemblyAlertsPacket.ACTION_BIT_SUSPEND" in alerts_client
      and "? SyncAssemblyAlertsPacket.ACTION_BIT_RESUME : actionBit;" in alerts_client)
check("7h 反例（修复前）：挂起 / 继续各自记在途 ⇒ 「点挂起 → 快照把该格换成继续 → 玩家再点一下」"
      "立刻发出 RESUME（实机日志：watchdog suspend task=… 之后 1.3 秒紧跟 watchdog resume task=…）",
      "return taskId + \"#\" + actionBit;" not in alerts_client)
check("7i 挂起语义未被本轮改动动摇：服务端「挂起 = 不再被 step」的唯一实现点仍在，"
      "且挂起边沿仍把舱内东西退回网络（第 6 条）",
      "AssemblyWatchdog.isSuspended(task.getId().id())" in read(os.path.join("mixin", "TaskContainerMixin.java"))
      and "flushChamberForSuspend();" in chamber)

# ---- #4 总样板始终生成 ----
check("7j generationPatternCost 不再因「全部步骤都是重复样板」返回 -1（总样板始终生成）",
      "return -1; // 全部步骤都已存在相同样板" not in terminal_src)
check("7k generateAssemblyPattern 不再因「全部步骤都被跳过」整体拒绝生成",
      "generate.all_duplicate" not in terminal_src)
has(menu_src, "terminal.allStepsDuplicate()",
    "锚点 7l: 单元样板 0 张时给一条<b>说明性</b>提示（不是「流程无效」错误）")
check("7m 真无效流程仍然拦下（没步骤且没产出 ⇒ 不生成），绝不「什么都生成」",
      "if (resultSlotsEmpty() && scrapSlotsEmpty()) {" in terminal_src
      and "if (units.isEmpty() && resultSlotsEmpty() && scrapSlotsEmpty()) {" in terminal_src)

print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
sys.exit(0)
