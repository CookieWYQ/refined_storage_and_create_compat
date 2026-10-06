# -*- coding: utf-8 -*-
"""自检（第 35 轮 · 执行舱不放单元样板 ⇒ 类别表不猜「具体配方 + 步序」）。

用法：python tools/selfcheck_round35_no_unit_no_guessed_categories.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

用户现场（原话）
----------------
「如果说我这一个执行舱里面什么也不放……那个详细配置界面应该啥也不显示。准确来说，它的配方还是
显示的，但是其他东西应该不显示。因为你只是知道他能够干这个配方而已 —— 的确我能干这事情，只是
配方配上了。就比如说机械手装配，他不一定能把中间产物也配上。」

用户随后拍板的补充（2026-10-06，**覆盖**上面那句「啥也不显示」）
------------------------------------------------------------------
「总线配置界面仍建配方标签页，但页内一条都不显示。」
即：整链没有单元样板时，类别表仍然为空（本轮 K2 的结论不变），但界面上**那一页配方标签页照建**
（按处理器类型 recipeType，如 create:pressing），只是**页内零行**。
落地方式 = 服务端在两个界面快照的**返回值**里补一条**只喂标签页**的标记类别（`recipe:<处理器类型>`，
见 `RsccBusCategory#RECIPE_TAB_PREFIX`），它不画行 / 不可勾选 / 不进任何服务端判定
（真值表与不污染证明见第 39 轮的 `selfcheck_round39_recipe_tab_without_units.py`）。
因此本文件的 K4 锚点⑭（原来断言「类别 id 前缀只有 5 个，没有新增 recipe: 之类」）已被该拍板取代，
现改为断言「标记前缀存在，且它是只喂标签页的、没有任何可显示内容的那一种」。

语义拆解（本轮实现的口径）
--------------------------
① 「详细配置界面」= 总线「类别详细配置」子界面（`BusCategoryConfigScreen`），它的**唯一数据源**
   就是执行舱下发的类别快照（`busCategorySnapshot` / `busImportCategorySnapshot` ← `chainCategories()`
   ← 每台成员自己的 `busCategories()` ← `computeBusCategories()`）；
② 类别快照里**只有一类**东西能在「一台单元样板都没有」时凭空出现：第 ② 遍补出来的
   `intermediate:<配方id>:<步序>`（数据源 = 总样板的机器指派，`patternAssignedSteps`）。
   它正是「需要**具体配方 + 步序**才能得出」的类别 ⇒ 本轮把它闸掉；
③ 「配方 / 处理器类型」这一层信息**本来就不在类别表里**（`busCategories()` / `chainCategories()`
   里没有「处理器」这种类别）：它由执行舱自己的界面显示（`SequenceExecutionChamberScreen#recipeTypeLine`
   的「配方类型」行 + `ChamberBindingConfigScreen` 的绑定界面 + `SyncChamberBindingPacket` 的
   recipeType 字段）。第 39 轮起，**界面快照**另外补一条只喂标签页的标记类别，好让详细配置界面
   仍能建出那一页（页内零行）—— 它仍**不进**类别表，因此本文件 K5 的「空类别表 = 无动作」结论不变；
④ 判据取**链级**（`chainHasAnyUnit()`）而不是本台：一条链 = 一个逻辑执行仓，
   链上任意一台放着样板 ⇒ 整链照旧显示由它推出来的类别（绝不按「当前这台空不空」决定整链显示）。

本自检钉住四件事
----------------
K1 闸门落在**唯一的数据源**里（`computeBusCategories()` 第 ② 遍），不是散在界面 / 快照里，
   也不是新增的第二套判据；
K2 真值表：整链无样板 ⇒ 类别表为空（详细配置界面里没有任何可勾选的行；唯一的标签页由第 39 轮的
   标记类别提供，页内零行）；只要有样板 ⇒ 既有行为逐字不变
   （含「补类别仍只补中间产物」这条第 20 轮硬底线）；
K3 链级语义没被弄坏：判据是 `chainHasAnyUnit()`、快照仍是链级并集、链上任意一台有样板就照旧补；
K4 「配方 / 处理器类型」这一层仍在（执行舱界面 / 绑定同步），且**没有**被当成一种**真实类别**
   塞进类别表（类别表里仍然只有 5 个前缀；第 39 轮新增的 `recipe:` 只是**只喂标签页**的标记，
   没有图标 / 候选 / 勾选态，见锚点⑭）。
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


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def read(*parts):
    with io.open(os.path.join(*parts), "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(source):
    """去掉 /* */ 与 // 注释：断言「代码里没有某样东西」时不能被注释里的字面量干扰。"""
    out = []
    index = 0
    length = len(source)
    while index < length:
        if source.startswith("/*", index):
            stop = source.find("*/", index + 2)
            index = length if stop < 0 else stop + 2
            continue
        if source.startswith("//", index):
            stop = source.find("\n", index)
            index = length if stop < 0 else stop
            continue
        out.append(source[index])
        index += 1
    return "".join(out)


def body(source, anchor, end=None):
    """取 anchor 之后的片段（到 end 或文件末尾）：用于「某方法体内」的断言。"""
    index = source.find(anchor)
    if index < 0:
        return ""
    index += len(anchor)
    rest = source[index:]
    if end is not None:
        stop = rest.find(end)
        if stop >= 0:
            return rest[:stop]
    return rest


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


# ======================================================================
# 与 Java 逐字同构的模型：computeBusCategories 的两遍 + chainCategories 的链级并集
# ======================================================================
COMPUTE_ANCHOR = "private List<BusCategoryInfo> computeBusCategories() {"


def chain_has_any_unit(has_unit_by_member):
    """chainHasAnyUnit() 的复刻：链上**任意一台**放着单元样板（含本台）。"""
    return any(has_unit_by_member.values())


def unit_derived_categories(own_has_unit, recipe_id):
    """第 ① 遍（本台单元样板推出）：输入 / 中间产物 / 成品 / 废料。无样板 ⇒ 一条都没有。"""
    if not own_has_unit:
        return []
    return [
        "input:%s#start" % recipe_id,
        "intermediate:%s:0" % recipe_id,
        "intermediate:%s:1" % recipe_id,
        "result:%s" % recipe_id,
    ]


def pattern_complement_categories(assigned, chain_has_unit):
    """第 ② 遍（总样板指派补出来的中间产物）—— **第 35 轮的闸门就在这里**。"""
    if not chain_has_unit:
        return []
    return ["intermediate:%s:%d" % (recipe, step) for recipe, step in assigned]


def compute_bus_categories(own_has_unit, own_recipe, assigned, units_by_member):
    """computeBusCategories() 的复刻（第 35 轮后）。"""
    mine = unit_derived_categories(own_has_unit, own_recipe)
    return mine + pattern_complement_categories(assigned, chain_has_any_unit(units_by_member))


def chain_categories(member_category_lists):
    """chainCategories() 的复刻：成员坐标升序 + 成员自己的类别表，按 id 去重（先到者定义）。"""
    out = []
    seen = set()
    for categories in member_category_lists:
        for cid in categories:
            if cid not in seen:
                seen.add(cid)
                out.append(cid)
    return out


def chain_of(units_by_member, assigned_by_member, recipe):
    """一条链（成员坐标升序）在界面上的类别并集。"""
    per_member = []
    for name in sorted(units_by_member):
        per_member.append(compute_bus_categories(
            units_by_member[name], recipe, assigned_by_member.get(name, []), units_by_member))
    return chain_categories(per_member)


def main():
    chamber = read(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
    bus_category = read(SRC, "support", "RsccBusCategory.java")
    chamber_screen = read(SRC, "client", "screen", "SequenceExecutionChamberScreen.java")
    binding_packet = read(SRC, "network", "SyncChamberBindingPacket.java")

    compute = body(chamber, COMPUTE_ANCHOR, "private static void recordStepMachine(")
    compute_code = strip_comments(compute)

    # ---------------- K1 闸门落在唯一的数据源里 ----------------
    section("K1 闸门落在「唯一的数据源」computeBusCategories 第 ② 遍里（不散在界面 / 快照）")
    check("锚点① 第 ② 遍的数据源被链级闸门夹住：有样板才取总样板指派，没样板取空表",
          "chainHasAnyUnit() ? patternAssignedSteps(level) : Map.<String, Set<Integer>>of();" in chamber)
    check("锚点② 遍历的是被夹住的那一份表（不是直接遍历 patternAssignedSteps(...)）",
          "for (final Map.Entry<String, Set<Integer>> assigned : patternAssigned.entrySet()) {" in chamber
          and "for (final Map.Entry<String, Set<Integer>> assigned : patternAssignedSteps(level).entrySet()) {"
          not in chamber)
    check("锚点③ 全工程只有**一处** patternAssignedSteps(level) 调用 ⇒ 闸门不可能被第二处绕过"
          "（既有自检 selfcheck_round18/20 就靠这一处切片段）",
          chamber.count("patternAssignedSteps(level)") == 1)
    check("锚点④ 判据取链级 chainHasAnyUnit()，**不**看本台 unitSlots / unitsForExport() 空不空",
          "chainHasAnyUnit()" in compute_code
          and "unitSlots" not in compute_code
          and "units.isEmpty()" not in compute_code
          and "unitsForExport().isEmpty()" not in compute_code)
    check("锚点⑤ 闸门是纯读判据（不写任何状态 / 不搬任何资源）：computeBusCategories 里既没有 "
          "markDirty / setBlock / setRecipeType，也没有任何集合写回（只有本方法自己的局部登记表）",
          "markDirty" not in compute_code
          and "setBlock" not in compute_code
          and "setRecipeType" not in compute_code
          and "transitionalsByKey.computeIfAbsent" in compute_code
          and "stepPrototypes.putIfAbsent" in compute_code)

    # ---------------- K2 真值表 ----------------
    section("K2 真值表：整链无样板 ⇒ 类别表为空；有样板 ⇒ 既有行为逐字不变")
    RECIPE = "create:track"
    ASSIGNED = [("create:track", 2)]

    # ① 单台空仓 + 总样板把 track#2 指派给它（用户现场：什么也不放，只是配方配上了）
    empty = {"A": False}
    cats = compute_bus_categories(False, RECIPE, ASSIGNED, empty)
    check("① 单台执行舱什么也不放（但总样板把 track#2 指派给了它）⇒ 类别表**为空** "
          "⇒ 详细配置界面里没有任何可勾选的行（中间产物不再凭「配方配上了」硬推出来；"
          "唯一的标签页是第 39 轮那条只喂标签页的标记类别，页内零行）",
          cats == [], "%s" % (cats,))

    # ② 单台空仓、总样板也没指派
    check("② 单台空仓 + 总样板什么都没指派 ⇒ 同样是空表",
          compute_bus_categories(False, RECIPE, [], {"A": False}) == [])

    # ③ 修复前的反例（用户否定的行为）
    old_cats = pattern_complement_categories(ASSIGNED, True)
    check("③ 反例（修复前）：不看样板就把 intermediate:create:track:2 推出来 "
          "⇒ 玩家在详细配置里看见、勾上，进而被当成「本仓要这一步」",
          old_cats == ["intermediate:create:track:2"])

    # ④ 有样板 ⇒ 第 ② 遍照旧生效（既有行为逐字不变）
    with_unit = compute_bus_categories(True, RECIPE, ASSIGNED, {"A": True})
    check("④ 本台放着样板 ⇒ 第 ① 遍（输入 / 中间产物 / 成品）+ 第 ② 遍指派类别照旧全在"
          "（既有行为逐字不变）",
          with_unit == ["input:create:track#start", "intermediate:create:track:0",
                        "intermediate:create:track:1", "result:create:track",
                        "intermediate:create:track:2"], "%s" % (with_unit,))

    # ⑤ 4 台链全空：整链一条类别都没有
    chain_empty = {"A": False, "B": False, "C": False, "D": False}
    chain_assigned = {"A": [("create:track", 0)], "B": [("create:track", 1)],
                      "C": [("create:track", 2)], "D": [("create:track", 3)]}
    check("⑤ 4 台成链、**整条链**一台样板都没有 ⇒ 链级类别并集为空（不存在「链上别台补出来的」）",
          chain_of(chain_empty, chain_assigned, RECIPE) == [])

    # ⑥ 链上任意一台有样板 ⇒ 整链照旧显示（判据是链级，不是「当前这台」）
    one_has = {"A": False, "B": False, "C": True, "D": False}
    union = chain_of(one_has, chain_assigned, RECIPE)
    a_categories = compute_bus_categories(False, RECIPE, chain_assigned["A"], one_has)
    check("⑥ 4 台链只有 C 有样板、本台是空的 A ⇒ A 自己的类别表照旧补出它的指派类别"
          "（绝不按「当前这台空不空」决定整链显示）",
          a_categories == ["intermediate:create:track:0"], "%s" % (a_categories,))
    check("⑦ 同上：整链并集里 C 的样板类别与各台的指派类别都在（链上任意一台有样板 ⇒ 照旧显示）",
          "input:create:track#start" in union
          and "intermediate:create:track:0" in union
          and "intermediate:create:track:2" in union
          and "intermediate:create:track:3" in union,
          "%s" % (union,))

    # ⑧ 不串链 ⇒ 链 = 本台，口径与 busCategories() 逐字相同
    single = {"A": True}
    per_member = compute_bus_categories(True, RECIPE, ASSIGNED, single)
    check("⑧ 不串链时链 = 本台 ⇒ chainCategories 与 busCategories() 逐字相同（去重恒等）",
          chain_categories([per_member]) == per_member)

    # ⑨ 去重口径：同一类别 id 只保留链上坐标最小的定义者
    dup = chain_categories([["intermediate:create:track:2"], ["intermediate:create:track:2"]])
    check("⑨ 去重口径未动：同一 id 在整条链上只出现一次（一个类别一个属主）",
          dup == ["intermediate:create:track:2"])

    # ---------------- K3 链级语义没被弄坏 ----------------
    section("K3 链级语义没被弄坏：判据链级、快照链级、第 20 轮硬底线仍在")
    chain_cat_body = body(chamber, "public List<ChainBusCategory> chainCategories() {")
    check("锚点⑥ 链级类别仍是「成员坐标升序 + 成员自己的 busCategories()」，先到者定义",
          "for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {" in chain_cat_body
          and "member.busCategories()" in chain_cat_body)
    check("锚点⑦ 两个界面快照入口仍是**链级**（都遍历 chainCategories()）",
          "for (final ChainBusCategory entry : chainCategories()) {"
          in body(chamber, "public List<RsccBusCategory> busCategorySnapshot(")
          and "for (final ChainBusCategory entry : chainCategories()) {"
          in body(chamber, "public List<RsccBusCategory> busImportCategorySnapshot("))
    check("锚点⑧ 闸门用的 chainHasAnyUnit() 是既有实现（链上任一台 hasAnyUnit()），未新增第二套",
          "public boolean chainHasAnyUnit() {" in chamber
          and "if (member.hasAnyUnit()) {" in chamber
          and "public boolean hasAnyUnit() {" in chamber)
    check("锚点⑨ 硬底线未破：总样板指派仍**不**进属主判定 computeOwnedSteps（第 20 轮回归的根因）",
          "不得</b>进入本方法（= 属主判定）" in chamber
          and "final Set<Integer> steps = owned.computeIfAbsent(assigned.getKey()" not in chamber
          and "chainHasAnyUnit" not in strip_comments(body(
              chamber, "private Map<String, Set<Integer>> computeOwnedSteps(final Level level) {")))
    category_pass = chamber.split("patternAssignedSteps(level)")[1].split("// 7)")[0]
    check("锚点⑩ 第 20 轮硬底线仍在：补类别那一遍**只**补中间产物（不补该步的输入 / 流体 / 成品 / 废料 "
          "⇒ 勾中也不产生备料需求）",
          "addInputCategory(inputs, representative);" not in category_pass
          and "addFluidCategory(fluids, stack);" not in category_pass
          and "addProductCategories(recipe, results, scraps);" not in category_pass)
    check("锚点⑪ 补类别那一遍的三处登记一字未改（按步原型 / 复用键 / 该步机器）",
          "stepPrototypes.putIfAbsent(key, stepPrototypeOf(transitionalStack, recipeLocation, step));"
          in category_pass
          and "reuseKeys.putIfAbsent(key, stepReuseKey(sr));" in category_pass
          and "recordStepMachine(sr, key, stepMachines);" in category_pass)

    # ---------------- K4 配方 / 处理器类型这一层仍在，但类别表里仍只有 5 个真实类别前缀 ----------------
    section("K4 「配方 / 处理器类型」这一层信息仍在（执行舱界面 / 绑定同步），且没被当成真实类别塞进类别表")
    check("锚点⑫ 执行舱界面仍显示「配方类型」行（用户要看的那个「配方」）",
          "private Component recipeTypeLine() {" in chamber_screen
          and "sync.recipeType()" in chamber_screen)
    check("锚点⑬ 绑定快照仍把 recipeType 同步到客户端（链级委托：getRecipeType() 读链首）",
          "recipeType" in binding_packet
          and "public String getRecipeType() {" in chamber
          and "return chainHead().ownRecipeType();" in chamber)
    check("锚点⑭ 类别表里仍然只有 5 个**真实类别**前缀（中间产物 / 输入 / 流体 / 成品 / 废料）："
          "第 39 轮新增的 recipe: 是**只喂标签页**的标记 —— 没有图标 / 候选 / 勾选态，"
          "界面靠 isRecipeTab() 在每一处枚举里跳过它，因此它不会变成一条能勾选的条目"
          "（「界面仍建那一页、页内零行」这条用户拍板由第 39 轮自检做完整真值表）",
          "PROCESSOR_PREFIX" not in bus_category
          and "public static final String RECIPE_TAB_PREFIX = \"recipe:\";" in bus_category
          and "public boolean isRecipeTab() {" in bus_category
          and "public String recipeTabType() {" in bus_category
          and "public static String recipeTabId(" in bus_category
          and "public static final String INTERMEDIATE_PREFIX = \"intermediate\";" in bus_category
          and "public static final String INPUT_PREFIX = \"input:\";" in bus_category
          and "public static final String FLUID_PREFIX = \"fluid:\";" in bus_category
          and "public static final String RESULT_PREFIX = \"result:\";" in bus_category
          and "public static final String SCRAP_PREFIX = \"scrap:\";" in bus_category)

    # ---------------- K5 消费方同源：类别表空了，全链路也真的没有动作 ----------------
    section("K5 消费方同源：类别表是唯一事实源 ⇒ 空类别表 = 界面空 + 默认导出集空 + 备料 / 收料无目标")
    check("锚点⑮ 默认导出集 / 输入类别 / 收回侧判据全部逐条读 busCategories()（同一份缓存，无第二套口径）",
          "for (final BusCategoryInfo info : busCategories()) {" in
          body(chamber, "public List<String> defaultExportCategoryIds() {")
          and "for (final BusCategoryInfo info : busCategories()) {" in
          body(chamber, "public List<String> inputCategoryIds() {")
          and "for (final BusCategoryInfo info : busCategories()) {" in
          body(chamber, "public Set<Item> inputCategoryItems() {"))
    check("锚点⑯ 类别表仍只有一条缓存入口（节拍重建写它、其余全读它）",
          "if (busCategoriesCache == null) {" in chamber
          and "busCategoriesCache = computeBusCategories();" in chamber)
    check("锚点⑰ 链级默认集（新放的总线走默认集）同样是链级类别表的投影 ⇒ 链空则默认为空",
          "for (final ChainBusCategory entry : chainCategories()) {" in
          body(chamber, "private List<String> chainDefaultExportCategoryIds() {"))

    print()
    print("=" * 78)
    if FAILURES:
        print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("  - %s" % item)
        return 1
    print("SELFCHECK OK (%d checks)" % CHECKS[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
