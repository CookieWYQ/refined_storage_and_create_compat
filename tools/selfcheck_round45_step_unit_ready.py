#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 45 轮：流程编排行内「已有 / 没有」必须如实回答「该步的机器 / 样板是否已就位」。

用户实测（截图 + 关服存档 `run/saves/test`，终端 @-5,-60,11）：

    1  机械手      USE    ×2  已有
    2  动力冲压机  冲压    ×1  没有     ← 动力冲压机槽里明明放着冲压样板

旧实现把行内那两个字接到 `SyncStepMachinesPacket.Entry#duplicateExists` 上，而它是**判重**结论
（`UnitPatternDedupe#sameSemantics`：同一条配方 + 同一个步序）。存档里的两条事实是：

    * 流程编排那一行 = 列车轨道第 3 步冲压   (Recipe=create:sequenced_assembly/track, Step=2)
    * 动力冲压机 @-16,-60,10 槽里的样板 = 坚固板第 2 步冲压
                                            (Recipe=create:sequenced_assembly/sturdy_sheet, Step=1)

⇒ 操作类型相同（create:pressing）、配方与步序都不同 ⇒ 判重 false ⇒ 界面把「有」显示成「没有」。

本脚本做三件事：
    ① 用**存档里的真实数据**把两条判据各算一遍：旧判据复现缺陷，新判据给出用户期望的结论；
    ② 真值表：单元样板在链上任一台 ⇒ 已有；网络里确实没有 ⇒ 没有；跨链不串味；
    ③ 源码锚点：新判据复用既有唯一事实源（链推导 / 网络视图 / 操作类型归一化），
      且判重、生成、属主语义与产线闸门一个字未动。

用法：python tools/selfcheck_round45_step_unit_ready.py
退出码：0 = 没有任何 FAIL。
"""

from __future__ import annotations

import io
import json
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
SPT = "gui.rs_create_compat.sequence_pattern_terminal."

FAILURES = []
CHECKS = [0]


def read(rel):
    with io.open(os.path.join(JAVA, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail="", hard=True):
    CHECKS[0] += 1
    if not ok and hard:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else ("FAIL" if hard else "BASE"), name,
                       (" | " + detail) if detail else ""))


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def method_body(source, signature):
    """按花括号配对取方法体（断言判定写在哪个方法里）。"""
    start = source.index(signature)
    open_index = source.index("{", start)
    depth = 0
    for i in range(open_index, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[open_index:i + 1]
    raise SystemExit("方法体未闭合: " + signature)


def langs():
    out = {}
    for name in ("zh_cn.json", "en_us.json"):
        with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
            out[name] = json.load(handle)
    return out


# ======================================================================
# 判据的两个模型（逐字复刻源码口径；存档数据与真值表都喂给它们）
# ======================================================================

def unit(op, recipe="", step=-1, requires_input=False, item="", candidates=()):
    """一张单元样板（只保留判据用到的分量）。"""
    return {"unit": True, "op": op, "recipe": recipe, "step": step,
            "requires_input": requires_input, "input": item, "candidates": tuple(candidates)}


def chain(identity, recipe_type, members):
    """一个逻辑执行仓：链身份（链首坐标）+ 链级配方类型 + 各成员的单元槽。"""
    return {"identity": identity, "recipe_type": recipe_type, "members": members}


def operation_of(u):
    """复刻 UnitPatternDedupe#operationOf：非单元样板 / 空栈 = 空串（RecipeType 与 Machine 同源，模型里只留 op）。"""
    if not u or not u.get("unit"):
        return ""
    return u["op"].strip().lower()


def old_duplicate_exists(step_unit, chains):
    """旧判据 = stepDuplicateExists（判重：同配方 + 同步序 + 操作类型 + 输入 / 候选 / 流体）。"""
    target = operation_of(step_unit)
    if not target:
        return False
    for c in chains:
        for member in c["members"]:
            for u in member:
                if operation_of(u) != target:
                    continue
                if not step_unit["recipe"] or not u["recipe"]:
                    return False  # 老样板缺配方 ⇒ 不判重
                if step_unit["recipe"].lower() != u["recipe"].lower():
                    continue
                if step_unit["step"] < 0 or u["step"] < 0 or step_unit["step"] != u["step"]:
                    continue
                if step_unit["requires_input"] != u["requires_input"]:
                    continue
                if step_unit["input"] != u["input"]:
                    continue
                if step_unit["candidates"] and u["candidates"] \
                        and set(step_unit["candidates"]) != set(u["candidates"]):
                    continue
                return True
    return False


def new_unit_equipped(step_unit, chains):
    """新判据 = SequencePatternTerminalBlockEntity#stepUnitEquipped（链级「机器 / 样板是否已就位」）。"""
    op = operation_of(step_unit)
    if not op:
        return False  # 空行 / 读不出操作类型：保守判「没有」
    seen = set()
    for c in chains:
        if c["identity"] in seen:      # chainIdentity()：同链多台只算一次
            continue
        seen.add(c["identity"])
        chain_type = (c["recipe_type"] or "").strip().lower()   # getRecipeType()：链级唯一值
        if chain_type != op:
            continue                   # 这台机器不认这一步的操作类型（跨链不串味）
        for member in c["members"]:    # chainMembers()：链上任一台备好 ⇒ 整条链备好
            for u in member:
                if operation_of(u) == op:
                    return True
    return False


# ======================================================================
# ① 存档实测：同一条流程、同一个网络，两条判据各算一遍
# ======================================================================
section("① 存档实测（run/saves/test）：旧判据复现「没有」，新判据给出「已有」")

# 流程编排（DisplayArrangement 生效）两行 —— 值逐字取自存档 NBT
TRACK = "create:sequenced_assembly/track"
STURDY = "create:sequenced_assembly/sturdy_sheet"
DEPLOY = "create:deploying"
PRESS = "create:pressing"
FILL = "create:filling"

row_deploy = unit(DEPLOY, TRACK, 0, True, "minecraft:iron_nugget",
                  ("minecraft:iron_nugget", "create:zinc_nugget"))
row_press = unit(PRESS, TRACK, 2)

# 网络里的执行舱（链身份按「每台自成一条链」保守代入；链成员一节由 ② 单独覆盖）
#   冲压 @-16,-60,10：配方类型 create:pressing，槽里是坚固板第 2 步的冲压样板
#   机械手 @-12,-60,6：未配置（类型空），槽里是列车轨道第 0 步的机械手样板
#   机械手 @-9,-60,6 ：USE，配方类型 create:deploying，槽里是精密构件第 2 步的机械手样板
#   注液 @-10,-60,10：注液，配方类型 create:filling，槽里是坚固板第 1 步的注液样板
SAVE_CHAINS = [
    chain((-16, -60, 10), PRESS, [[unit(PRESS, STURDY, 1)]]),
    chain((-12, -60, 6), "", [[unit(DEPLOY, TRACK, 0, True, "minecraft:iron_nugget",
                                    ("minecraft:iron_nugget", "create:zinc_nugget"))]]),
    chain((-9, -60, 6), DEPLOY, [[unit(DEPLOY, "create:sequenced_assembly/precision_mechanism", 1,
                                       True, "create:large_cogwheel")]]),
    chain((-10, -60, 10), FILL, [[unit(FILL, STURDY, 0)]]),
]

old_deploy = old_duplicate_exists(row_deploy, SAVE_CHAINS)
old_press = old_duplicate_exists(row_press, SAVE_CHAINS)
new_deploy = new_unit_equipped(row_deploy, SAVE_CHAINS)
new_press = new_unit_equipped(row_press, SAVE_CHAINS)
print("      第 1 行 机械手 %-12s 旧判据(判重)=%-5s 新判据(就位)=%s" % ("USE", old_deploy, new_deploy))
print("      第 2 行 冲压   %-12s 旧判据(判重)=%-5s 新判据(就位)=%s" % ("冲压", old_press, new_press))
check("①a 缺陷复现：冲压那一行的旧判据 = false（这就是界面上「没有」的来源）",
      old_press is False)
check("①b 机械手那一行旧判据 = true（同配方同步序的样板确实在，所以它显示「已有」）",
      old_deploy is True)
check("①c 修复后：冲压那一行 = true（动力冲压机链上确实放着一张 create:pressing 样板）",
      new_press is True)
check("①d 修复后：机械手那一行仍 = true（不把已经正确的显示改坏）", new_deploy is True)
check("①e 判重口径没有被放宽（否则两条配方的冲压步会被当成同一张 = 第 21 轮回归）",
      old_press is False and old_duplicate_exists(row_press, SAVE_CHAINS) is False)

# ======================================================================
# ② 真值表：链上任一台有 ⇒ 已有；网络里确实没有 ⇒ 没有；跨链不串味
# ======================================================================
section("② 真值表（链级「一个逻辑执行仓」）")

press_step = unit(PRESS, TRACK, 2)
deploy_step = unit(DEPLOY, TRACK, 0, True, "minecraft:iron_nugget")

# (a) 样板放在链上第 2 台（用户要求的「四台 = 一台」）
two_member_chain = [chain((0, 0, 0), PRESS, [[], [unit(PRESS, STURDY, 1)]])]
print("      (a) 冲压链 2 台，样板只在第 2 台        ->", new_unit_equipped(press_step, two_member_chain))
check("②a 单元样板在链上任意一台 ⇒ 该步「已有」（链 = 一个逻辑执行仓）",
      new_unit_equipped(press_step, two_member_chain) is True)

# (b) 链对、类型对，但槽全空
empty_chain = [chain((0, 0, 0), PRESS, [[], []])]
print("      (b) 冲压链 2 台，槽全空                ->", new_unit_equipped(press_step, empty_chain))
check("②b 网络里确实没有该操作类型的样板 ⇒ 「没有」（不因「机器在」就谎报有）",
      new_unit_equipped(press_step, empty_chain) is False)

# (c) 网络里根本没有冲压链（只有注液链）
only_filling = [chain((0, 0, 0), FILL, [[unit(FILL, STURDY, 0)]])]
print("      (c) 网络里只有注液链                   ->", new_unit_equipped(press_step, only_filling))
check("②c 没有任何一条链的配方类型是 create:pressing ⇒ 「没有」",
      new_unit_equipped(press_step, only_filling) is False)

# (d) 跨链不串味：注液链的样板不能替冲压步判「已有」，但注液步自己必须判「已有」
filling_step = unit(FILL, STURDY, 0)
print("      (d) 同一批链：冲压步=%-5s 注液步=%s"
      % (new_unit_equipped(press_step, only_filling), new_unit_equipped(filling_step, only_filling)))
check("②d 跨链不串味：别的配方类型的链不满足该步，但本链自己的步照常判「已有」",
      new_unit_equipped(press_step, only_filling) is False
      and new_unit_equipped(filling_step, only_filling) is True)

# (e) 两条同类型链：一条空、一条有样板（同链去重不改变「任一链备好即已有」）
two_press_chains = [chain((0, 0, 0), PRESS, [[], []]),
                    chain((5, 0, 0), PRESS, [[unit(PRESS, STURDY, 1)]])]
print("      (e) 两条冲压链（一条空、一条有）        ->", new_unit_equipped(press_step, two_press_chains))
check("②e 同类型多条链：任意一条备好 ⇒ 「已有」",
      new_unit_equipped(press_step, two_press_chains) is True)

# (f) 空行 / 老样板（读不出操作类型）
print("      (f) 空行 / 无操作类型                   ->", new_unit_equipped(unit(""), two_member_chain))
check("②f 空行 / 读不出操作类型 ⇒ 「没有」（绝不猜）",
      new_unit_equipped(unit(""), two_member_chain) is False)

# (g) 同一份数据在两套判据下的差异只发生在「就位」这一问上
print("      (g) 判重 vs 就位（冲压步，坚固板样板）  -> 判重=%s 就位=%s"
      % (old_duplicate_exists(press_step, two_member_chain),
         new_unit_equipped(press_step, two_member_chain)))
check("②g 两个问题互不替代：判重 false（不会跳过生成）+ 就位 true（机器上有样板）",
      old_duplicate_exists(press_step, two_member_chain) is False
      and new_unit_equipped(press_step, two_member_chain) is True)

# (h) 机械手步（有输入 + 候选组）在有同类型样板时同样是「已有」
deploy_chain = [chain((0, 0, 0), DEPLOY, [[unit(DEPLOY, STURDY, 0, True, "create:cogwheel")]])]
print("      (h) 机械手步 + 同类型机械手样板          ->", new_unit_equipped(deploy_step, deploy_chain))
check("②h 是否需要输入 / 输入物不参与「就位」判定（问的是机器能不能承担这一步）",
      new_unit_equipped(deploy_step, deploy_chain) is True)

# ======================================================================
# ③ 源码锚点：复用既有唯一事实源 + 判重 / 生成 / 属主 / 产线闸门未动
# ======================================================================
section("③ 源码锚点")

terminal = read(os.path.join("block", "entity", "SequencePatternTerminalBlockEntity.java"))
equipped_body = method_body(terminal, "public boolean stepUnitEquipped(final int stepIndex) {")

has(terminal, "public boolean stepUnitEquipped(final int stepIndex) {",
    "③a 服务端唯一判据 stepUnitEquipped 存在（界面那两个字只读它）")
has(equipped_body, "UnitPatternDedupe.operationOf(arrangementUnit(stepIndex), regs)",
    "③b 该步的「操作类型」复用查重的唯一归一化 UnitPatternDedupe#operationOf")
has(equipped_body, "UnitManagerSources.chambers(network)",
    "③c 扫描范围复用 UnitManagerSources#chambers（与查重 / 管理舱同一个网络视图）")
has(equipped_body, "chamber.chainIdentity().asLong()",
    "③d 链身份复用 chainIdentity()（链首坐标，全工程唯一链口径）")
has(equipped_body, "chamber.chainMembers()",
    "③e 链成员复用 chainMembers()（链上任一台备好 ⇒ 整条链备好）")
has(equipped_body, "chamber.getRecipeType()",
    "③f 链级配方类型复用 getRecipeType()（链首唯一值）")
check("③g 只读：判据体内不写任何容器 / 不标脏（不参与搬运与守恒）",
      all(token not in equipped_body for token in
          ("setItem(", "setStackInSlot(", "setChanged()", "markDirty", "putUnitIntoLibrary",
           "setStepSkipDuplicate", "extractItem")))
check("③h 判重（生成侧）保持第 21 轮口径：同配方 + 同步序",
      "if (!recipeA.equalsIgnoreCase(recipeB)) {" in read(os.path.join("support", "UnitPatternDedupe.java"))
      and "dataA.step() != dataB.step()" in read(os.path.join("support", "UnitPatternDedupe.java")))
check("③i 生成侧仍读判重缓存 + 每步开关（本轮的显示改动没有碰生成语义）",
      "if (getStepSkipDuplicate(i) && stepDuplicateExists(i))" in terminal
      and "refreshStepDuplicateCache();" in terminal
      and "stepDuplicateCache.set(i, found != null);" in terminal)

chamber = read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))
check("③j 属主语义未动：ownedSteps 仍只来自「本仓自己的单元样板 + 运行中订单」那条硬底线",
      "private Map<String, Set<Integer>> computeOwnedSteps(final Level level) {" in chamber
      and "不得</b>进入本方法（= 属主判定）" in chamber)
for gate in ("blockedByMachineQueue", "startCapacityForRecipe", "inFlightUnitsForRecipe",
             "NEXT_FOR_MACHINE"):
    check("③k 产线闸门仍在：%s" % gate, gate in chamber)

packet = read(os.path.join("network", "SyncStepMachinesPacket.java"))
check("③l 快照新增 unitEquipped 分量，且两个既有分量都还在（客户端只读）",
      "boolean duplicateExists, boolean skipDuplicate, boolean unitEquipped" in packet
      and "terminal.stepUnitEquipped(i)" in packet)
check("③m 7 个分量 ⇒ 手写编解码（StreamCodec.composite 最多 6 个），两端各字段成对",
      "StreamCodec.of(" in packet and packet.count("ByteBufCodecs.BOOL.encode") == 4
      and packet.count("ByteBufCodecs.BOOL.decode") == 4
      and "ByteBufCodecs.STRING_UTF8.encode(buf, entry.machineName());" in packet
      and "ByteBufCodecs.STRING_UTF8.decode(buf)" in packet)

screen = read(os.path.join("client", "screen", "SequencePatternTerminalScreen.java"))
toggle_body = method_body(screen, "private void renderStepSkipToggle(final GuiGraphics guiGraphics,")
tip_body = method_body(screen, "private List<Component> stepSkipToggleTooltipLines(final int globalStep) {")
check("③n 行内那两个字改读「就位」，且不再读判重",
      "stepUnitEquipped(globalStep)" in toggle_body and "stepDuplicateExists(globalStep)" not in toggle_body)
check("③o tooltip 把两件事分开列（就位 + 判重），判重事实没有被删掉",
      'LANG + "card.skip.equipped"' in tip_body and 'LANG + "card.skip.exists"' in tip_body
      and "stepDuplicateExists(globalStep)" in tip_body and "stepUnitEquipped(globalStep)" in tip_body)
check("③p 客户端仍只读快照（不新增任何本地判定 / 不写权威状态）",
      "entry.unitEquipped()" in screen and "entry.duplicateExists()" in screen)

data = langs()
for name, table in data.items():
    key = SPT + "card.skip.equipped"
    ok = key in table
    check("③q %s 含 card.skip.equipped" % name, ok)
    if ok:
        check("③r %s card.skip.equipped 占位符 1 个（中文 ≤ 40）" % name,
              table[key].count("%s") == 1 and (name != "zh_cn.json" or len(table[key]) <= 40))
check("③s zh_cn / en_us 键集合一致", set(data["zh_cn.json"]) == set(data["en_us.json"]))

print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
sys.exit(0)
