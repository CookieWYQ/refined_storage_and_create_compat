# -*- coding: utf-8 -*-
"""第 33 轮自检：**「这一步归不归我」在链级认步，但「属主是谁 / 判据在哪台仓求值」逐字不变**。

用法：python tools/selfcheck_round33_chain_step_ownership.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要有它（本轮无法把游戏跑起来验证）
--------------------------------------------------------------------
用户把 4 台同配方执行仓沿箭头排成一条链，期望当作**一个**逻辑执行仓（扩容）。

上游已证明（实机日志 + 源码）：
  * 界面早就是链级（`chainCategories()` 按 id 去重、每项带唯一属主仓），4 台的链级 `cat_ids=`
    逐字相同；各台自己的 `cats=[…]` 是 2/2/2/5；
  * 但**搬运层**的「这一步是否归我」原先只读**本台** `ownedSteps()`（`unitSlots`），
    于是「本台样板没定义、链上别台定义了」的那一步被判 `NOT_MINE`
    ⇒ 用户抱怨的「界面看得到、勾得上，却推不动」的**引擎侧一半**。

本轮的修法（本脚本固化的口径）：
  * 新增**唯一**的链级并集 `chainOwnedSteps()`（= 本台 ∪ 同链各台 `ownedSteps()`；**只**供
    `stepVerdictFor` 消费）。它是「这条链**合起来能不能做**这一步」的唯一事实源；
  * `ownedSteps()` / `computeOwnedSteps()` 的**属主语义一字未改**（仍只来自本台 `unitSlots`），
    仍是 `chainCategories()` 里「谁是某个类别的属主」的唯一依据；
  * 判据**求值所在的仓**一字未改：`stepVerdictFor` 仍用**本仓**的 `level`（配方总步数 T）与本仓缓存；
    导出侧仍是总线绑定的那台仓（`RsccChamberExportStrategy`）、导入侧仍是工位属主仓
    （`RsccChamberImportStrategy#stationOwner`）；
  * 导出 / 导入**同源**：两侧都只经 `stepVerdictFor`（一个实现、一个三态结论），
    因此不会「清单里有、搬运层拒收」，也不会「整堆推给机器」。

本脚本做四件事：
  ① 源码锚点：链级并集只有一处实现、只被搬运层判定消费；
  ② 「属主 / 求值仓未变」的源码锚点（本台 unitSlots → 本台步表 → 链级类别属主）；
  ③ 5 组受保护判据（排队 / 名额 / 在制 / 池化 / 看门狗）**不读**链级并集的源码锚点；
  ④ 等价模型 + 反例表：
     - 不串链时并集 = 本台表（行为零变化）；
     - 4 台成链时并集 = {ss:1, ss:2, track:2}，且「勾得上推不动」的那一步对每台都判 NEXT_FOR_MACHINE；
     - 若把并集喂给计数判据 ⇒ 同一件事被算 4 倍（重复计数）；
     - 若把别台样板并进 `ownedSteps()` ⇒ `intermediate:sturdy_sheet:2` 的属主从真正定义它的
       坐标 10 翻成坐标最小的 7（属主换人 ⇒ 判据在**错的机器**上求值）。
"""
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


def read(*parts):
    with io.open(os.path.join(SRC, *parts), "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(desc, ok):
    CHECKS[0] += 1
    if ok:
        print("  [OK]   %s" % desc)
    else:
        print("  [FAIL] %s" % desc)
        FAILURES.append(desc)


def section(title):
    print()
    print("-" * 78)
    print(title)
    print("-" * 78)


def body(text, marker):
    """按大括号配平取方法体（含首尾大括号）；找不到 marker 返回空串。

    不按行号 / 缩进取：本轮与其它轮在同一文件上并行改动，行号会漂。
    """
    start = text.find(marker)
    if start < 0:
        return ""
    open_brace = text.find("{", start)
    if open_brace < 0:
        return ""
    depth = 0
    index = open_brace
    while index < len(text):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[open_brace:index + 1]
        index += 1
    return text[open_brace:]


def strip_comments(source):
    """去掉注释（断言「某方法体里没有某样东西」时不能被注释里的字面量干扰）。"""
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


CHAMBER = ("block", "entity", "SequenceExecutionChamberBlockEntity.java")
chamber = read(*CHAMBER)
exporter = read("support", "RsccChamberExportStrategy.java")
importer = read("support", "RsccChamberImportStrategy.java")

# ==================== 1. 链级并集：一处实现、只被搬运层判定消费 ====================
section("1) 链级认步的唯一实现（chainOwnedSteps）与唯一消费点（stepVerdictFor）")

chain_owned = body(chamber, "private Map<String, Set<Integer>> chainOwnedSteps() {")
check("锚点①: chainOwnedSteps() 只有一处实现，成员集合取自唯一链推导 chainMembers()，"
      "逐成员并入它自己的 ownedSteps()（不新造第二套链口径）",
      chamber.count("private Map<String, Set<Integer>> chainOwnedSteps() {") == 1
      and "for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {" in chain_owned
      and "member.ownedSteps()" in chain_owned)

check("锚点②: 去重方式 = LinkedHashSet 按 (配方 id, 步序) 合并（集合语义 ⇒ 同一步被 N 台同时定义也只算一次，"
      "不存在「同一台仓被重复计数」）",
      "new LinkedHashSet<>()" in chain_owned
      and "computeIfAbsent(entry.getKey()" in chain_owned
      and "addAll(entry.getValue())" in chain_owned
      and "LinkedHashMap<>()" in chain_owned)

step_verdict = body(chamber, "private StepVerdict stepVerdictFor(")
check("锚点③: 判据本体 stepVerdictFor 的步序来源只有链级并集（体内出现 chainOwnedSteps()，"
      "**不**再直接读本台 ownedSteps()）—— 导出 / 导入 / 推料 / 备料四侧因此天然同源",
      "chainOwnedSteps()" in strip_comments(step_verdict)
      and "ownedSteps()" not in strip_comments(step_verdict))

check("锚点④: 链级并集全工程只有「定义 + stepVerdictFor 里那一次」两处**代码**出现（其余提及都在注释里）"
      "—— 它没有渗进任何产出「每仓数量 / 属主」的地方",
      strip_comments(chamber).count("chainOwnedSteps") == 2)

check("锚点⑤: 判据本体仍是唯一一份 SequenceMaterialGuard#judgeStep（不新增第二套步序比较）",
      "SequenceMaterialGuard.judgeStep(" in step_verdict
      and chamber.count("SequenceMaterialGuard.judgeStep(") == 1)

check("锚点⑥: 两个搬运层入口都经本判据 —— isNextForMyMachines → nextForMyMachines → stepVerdictFor，"
      "isTransitionReclaimAllowed → stepVerdictFor（三态：NEXT_FOR_MACHINE / NOT_MINE / UNKNOWN 语义未改）",
      "return nextForMyMachines(level, probe, assembly);" in body(chamber, "public boolean isNextForMyMachines(")
      and "private boolean nextForMyMachines(" in chamber
      and "stepVerdictFor(level, probe, assembly) == StepVerdict.NEXT_FOR_MACHINE;"
      in body(chamber, "private boolean nextForMyMachines(")
      and "stepVerdictFor(level, probe, assembly) == StepVerdict.NOT_MINE;"
      in body(chamber, "public boolean isTransitionReclaimAllowed(")
      and "StepVerdict.UNKNOWN" in step_verdict)

# ==================== 2. 属主语义与求值仓未变 ====================
section("2) 「属主是谁 / 判据在哪台仓求值」逐字不变的源码锚点")

owned_steps = body(chamber, "private Map<String, Set<Integer>> ownedSteps() {")
compute_owned = body(chamber, "private Map<String, Set<Integer>> computeOwnedSteps(final Level level) {")
check("锚点⑦: ownedSteps / computeOwnedSteps 的属主语义未变 —— 只来自本台 unitSlots，"
      "体内**不出现** chainMembers / chainOwnedSteps（链上别台样板不并进来）",
      "cachedUnits()" in compute_owned
      and "chainMembers(" not in strip_comments(compute_owned)
      and "chainOwnedSteps" not in strip_comments(compute_owned)
      and "chainOwnedSteps" not in strip_comments(owned_steps)
      and "chainMembers(" not in strip_comments(owned_steps))

check("锚点⑧: isOwnedStep（属主判定）仍只读本台 ownedSteps()（因而不被链级并集放宽）",
      "ownedSteps().get(recipeId)" in body(chamber, "private boolean isOwnedStep(")
      and "chainOwnedSteps" not in strip_comments(body(chamber, "private boolean isOwnedStep(")))

check("锚点⑨: 链级类别属主仍由 chainCategories() = 成员坐标升序 + 成员自己的 busCategories() 决定"
      "（先到者定义；不读 chainOwnedSteps）",
      "for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {" in
      body(chamber, "public List<ChainBusCategory> chainCategories() {")
      and "member.busCategories()" in body(chamber, "public List<ChainBusCategory> chainCategories() {")
      and "chainOwnedSteps" not in strip_comments(body(chamber, "public List<ChainBusCategory> chainCategories() {")))

compute_categories = body(chamber, "private List<BusCategoryInfo> computeBusCategories() {")
check("锚点⑩: 类别表（→ 属主）仍按**本台** ownedSteps() 展开每一步 / 成品 / 废料，"
      "**不**用链级并集（否则属主换人 + 每台各算一遍）",
      "ownedSteps().getOrDefault(unit.recipe(), Set.of())" in compute_categories
      and "chainOwnedSteps" not in strip_comments(compute_categories))

check("锚点⑪: 判据求值所在的仓未变 —— 输出侧仍是总线**绑定**的那台仓（rscc$getLinkedExecutor → "
      "isNextForMyMachines / busExportAcceptsForPush 都在它身上调用）",
      "final SequenceExecutionChamberBlockEntity chamber = owner.rscc$getLinkedExecutor();" in exporter
      and "chamber.isNextForMyMachines(inSlot)" in exporter
      and "chamber.busExportAcceptsForPush(inSlot, item)" in exporter)

check("锚点⑫: 导入侧仍按**工位属主仓**求值（stationOwner ← chainStationOwners），"
      "不是用绑定仓的步表去判别人的工位；未完成件两侧都走 isTransitionReclaimAllowed",
      "chamber.chainStationOwners()" in importer
      and "stationOwner(stationOwners, pos, chamber)" in importer
      and importer.count("chamber.isTransitionReclaimAllowed(stack);") == 2)

# ==================== 3. 5 组受保护判据不读链级并集 ====================
section("3) 受保护判据（排队 / 名额 / 在制 / 池化 / 看门狗）不读链级并集的源码锚点")

PROTECTED = [
    "private String machineReservedRecipe(",
    "private boolean blockedByMachineQueue(",
    "private long startCapacityForRecipe(",
    "private boolean unitCapacityLeftFor(",
    "private long inFlightUnitsForRecipe(",
    "private long[] pooledTransitionalUnits(",
    "public boolean rscc$anyDestinationStuck()",
    "public boolean pushStalledOnDestination()",
    "private void flushResidualInputs(",
    "private Set<Item> computeStartIngredients(",
    "public boolean rscc$bareTransitionIsMineNow(",
]
leaks = []
missing = []
for marker in PROTECTED:
    text = body(chamber, marker)
    if not text:
        missing.append(marker)
        continue
    if "chainOwnedSteps" in strip_comments(text):
        leaks.append(marker)
    # 这些判据的输入仍是「本台 / 工位」的事实源，因此绝不能经链级三态判定
    if "stepVerdictFor" in strip_comments(text) or "isNextForMyMachines" in strip_comments(text):
        leaks.append(marker + "(经 stepVerdictFor)")
check("锚点⑬: 11 个受保护判据的实现都在，且**没有一个**读链级并集 / 经链级三态判定"
      "（⇒ 它们的口径与上一轮逐字相同）",
      not missing and not leaks)

check("锚点⑭: 5 组判据的名字与数量仍是 selfcheck_round32 锚点⑩ 固化的那一份（未被本轮改名 / 合并）",
      chamber.count("private boolean blockedByMachineQueue(") == 1
      and chamber.count("private String machineReservedRecipe()") == 1
      and chamber.count("private long startCapacityForRecipe(") == 1
      and chamber.count("private boolean unitCapacityLeftFor(") == 1
      and chamber.count("private long inFlightUnitsForRecipe(") == 1
      and chamber.count("private long[] pooledTransitionalUnits(") == 1)

check("锚点⑮: rscc$bareTransitionIsMineNow 仍按本台 isOwnedStep 判（本轮刻意不放宽它：它同时是"
      "「该件是否就在本仓给它供料的机器上」的判据，属主换人会让收回侧抢件）",
      "isOwnedStep(recipeId, step)" in body(chamber, "public boolean rscc$bareTransitionIsMineNow("))

# ==================== 4. 等价模型 + 反例 ====================
section("4) 等价模型：不串链 = 本台；4 台成链 = 并集认步；反例（重复计数 / 属主换人）")

# 实机日志（x=-16 一排 4 台，坐标 7/8/9/10）里各台**自己的**类别表：
#   7 / 8 / 9 各 2 项，10 多出 sturdy_sheet:2 与 result:*。
# 换算成本台「负责的步」（本脚本的模型输入；与 round32 的类别模型逐字对应）：
OWN_STEPS = {
    7: {"create:sequenced_assembly/sturdy_sheet": {1}, "create:sequenced_assembly/track": {2}},
    8: {"create:sequenced_assembly/sturdy_sheet": {1}, "create:sequenced_assembly/track": {2}},
    9: {"create:sequenced_assembly/sturdy_sheet": {1}, "create:sequenced_assembly/track": {2}},
    10: {"create:sequenced_assembly/sturdy_sheet": {1, 2}, "create:sequenced_assembly/track": {2}},
}
SS = "create:sequenced_assembly/sturdy_sheet"
TRACK = "create:sequenced_assembly/track"
# 过渡件 -> 它所属配方（判据只按配方 + 步序，不看物品名）
RECIPE_OF = {"unprocessed_obsidian_sheet": SS, "incomplete_track": TRACK}
TOTAL = {SS: 4, TRACK: 3}


def chain_owned_steps_model(chain, own):
    """chainOwnedSteps() 的复刻：按 (配方, 步序) 并集，集合语义（无计数）。"""
    merged = {}
    for pos in sorted(chain):
        for recipe, steps in own[pos].items():
            merged.setdefault(recipe, set()).update(steps)
    return merged


def judge_step_model(item, s, machine_steps, machine_recipe, total):
    """SequenceMaterialGuard#judgeStep 的复刻（本模型只关心 NEXT_FOR_MACHINE / NOT_MINE / UNKNOWN）。"""
    if item not in RECIPE_OF:
        return "NOT_MINE"
    if RECIPE_OF[item] != machine_recipe:
        return "NOT_MINE"
    if total <= 0:
        return "UNKNOWN"
    return "NEXT_FOR_MACHINE" if (s % total) in machine_steps else "NOT_MINE"


def verdict(item, s, pos, chain, use_chain):
    """某台仓对「这份件（配方 + 当前进度步）」的三态结论（步序域 = 单循环 s % T）。"""
    recipe = RECIPE_OF[item]
    steps = (chain_owned_steps_model(chain, OWN_STEPS) if use_chain
             else OWN_STEPS[pos]).get(recipe, set())
    return judge_step_model(item, s, steps, recipe, TOTAL[recipe])


def owned_step_members(pos, recipe):
    """该台仓「自己的这一步归我」的步表（isOwnedStep 的属主口径，本轮未放宽）。"""
    return OWN_STEPS[pos].get(recipe, set())


# ---- ① 不串链：链 = 本台 ⇒ 并集与本台表逐字相同（行为零变化）----
single_ok = all(chain_owned_steps_model([pos], OWN_STEPS) == OWN_STEPS[pos] for pos in OWN_STEPS)
check("模型①: 不串链（chainMembers() = 本台）时，chainOwnedSteps() 与本台 ownedSteps() 逐字相同 "
      "⇒ 单台仓（改造前）行为零变化",
      single_ok)

# ---- ② 4 台成链：并集 = {ss:1, ss:2, track:2}，且是集合（每步恰一份，无计数）----
union = chain_owned_steps_model([7, 8, 9, 10], OWN_STEPS)
check("模型②: 4 台成链的并集 = {sturdy_sheet:1, sturdy_sheet:2, track:2}；"
      "ss:1 / track:2 被 7/8/9/10 中多台同时定义时只留一份（集合语义 ⇒ 每台都不会被重复计数）",
      union[SS] == {1, 2} and union[TRACK] == {2}
      and sum(len(steps) for steps in union.values()) == 3)

# ---- ③ 本轮修的正是这一步：别台定义的那一步，「勾得上、推不动」 ----
item = "unprocessed_obsidian_sheet"     # sturdy_sheet 的过渡件
step = 2                                # intermediate:sturdy_sheet:2（日志里唯一属主 = 坐标 10）
check("模型③: 目标场景 —— 类别 intermediate:sturdy_sheet:2 由坐标 10 定义（10 自己的步表含 ss:2），"
      "而坐标 7 自己的步表只有 ss:1",
      owned_step_members(10, SS) == {1, 2} and owned_step_members(7, SS) == {1})

check("模型④（修前 → 修后，量化）: 接在 7 上的输出总线拿这份件（配方 ss、进度 s=2）问「下一步归我吗」——"
      "修前（只读本台）= NOT_MINE ⇒ 推不动；修后（链级并集）= NEXT_FOR_MACHINE ⇒ 推得动。"
      "且 7 / 8 / 9 / 10 四台的结论**逐字相同**（链上唯一答案）",
      verdict(item, step, 7, [7, 8, 9, 10], False) == "NOT_MINE"
      and verdict(item, step, 7, [7, 8, 9, 10], True) == "NEXT_FOR_MACHINE"
      and len({verdict(item, step, pos, [7, 8, 9, 10], True) for pos in (7, 8, 9, 10)}) == 1)

check("模型⑤（互补性 / 同源）: 同一份件在「推料闸门」与「收回闸门」上是同一个三态结论 ⇒ "
      "NEXT_FOR_MACHINE 时「推得出去且绝不收回」，NOT_MINE 时「不推且允许收回」，"
      "UNKNOWN（T 查不到）时两侧都停手（保守）",
      all(verdict(item, step, pos, [7, 8, 9, 10], True) == "NEXT_FOR_MACHINE"
          for pos in (7, 8, 9, 10))
      and verdict(item, 3, 7, [7, 8, 9, 10], True) == "NOT_MINE"
      and judge_step_model(item, 2, {1, 2}, SS, -1) == "UNKNOWN")

# ---- ④ 反例 A：把链级并集喂给「计数」判据 ⇒ 同一件事被算 4 倍 ----
def answers_per_chamber(chain, own, use_chain):
    """「一个类别 / 一步被回答几次」的量化（chainCategories + ownedSteps 展开的等价模型）。"""
    out = []
    for pos in sorted(chain):
        steps = (chain_owned_steps_model(chain, own) if use_chain else own[pos])
        for recipe, step_set in steps.items():
            for s in sorted(step_set):
                out.append((recipe, s, pos))
    return out


own_answers = answers_per_chamber([7, 8, 9, 10], OWN_STEPS, False)
chain_answers = answers_per_chamber([7, 8, 9, 10], OWN_STEPS, True)
check("反例 A（量化，为什么链级并集绝不能喂给计数判据）: 各台自己的步表一共 9 条「(配方, 步序, 回答者)」"
      "记录，而 chainCategories() 的去重口径只有 3 条不同的 (配方, 步序)；若把并集当成各台自己的步表，"
      "就会变成 4 台 × 3 步 = 12 条 ⇒ 同一件事被算 4 倍（直接弄坏 blockedByMachineQueue / "
      "startCapacityForRecipe / inFlightUnitsForRecipe / pooledTransitionalUnits）",
      len(own_answers) == 9
      and len({(r, s) for r, s, _ in own_answers}) == 3
      and len({(r, s) for r, s, _ in chain_answers}) == 3
      and len(chain_answers) == 12 == 4 * 3)


# ---- ⑤ 反例 B：把别台样板并进 ownedSteps ⇒ 属主换人（判据在错的机器上求值）----
def chain_categories_model(chain, own, use_chain):
    """chainCategories() 的复刻：成员坐标升序，同一类别 id 只留第一次出现（= 坐标最小的定义者）。"""
    entries = []
    seen = set()
    for pos in sorted(chain):
        steps = (chain_owned_steps_model(chain, own) if use_chain else own[pos])
        for recipe, step_set in sorted(steps.items()):
            for s in sorted(step_set):
                cid = "intermediate:%s:%d" % (recipe, s)
                if cid not in seen:
                    seen.add(cid)
                    entries.append((cid, pos))
    return dict(entries)


owners_before = chain_categories_model([7, 8, 9, 10], OWN_STEPS, False)
owners_after = chain_categories_model([7, 8, 9, 10], OWN_STEPS, True)
check("反例 B（为什么刻意**不**做 ownedSteps 链级展开）: 现状下 intermediate:sturdy_sheet:2 的属主 = 真正"
      "定义它的那台（坐标 10）；一旦把别台样板并进 ownedSteps，属主就翻成链上坐标最小的 7 —— "
      "属主换人 ⇒ 排队 / 名额 / 在制判据会在**另一台机器**上求值",
      owners_before["intermediate:%s:2" % SS] == 10
      and owners_after["intermediate:%s:2" % SS] == 7
      and owners_before["intermediate:%s:1" % SS] == 7
      and owners_after["intermediate:%s:1" % SS] == 7)

check("反例 C: 本轮修法**不动**属主表 —— 链级并集只回答「合起来能不能做」，"
      "因此 intermediate:sturdy_sheet:2 的属主仍是 10，而「7 能不能推这一步」的答案是 NEXT_FOR_MACHINE；"
      "两者互不干扰（这正是「能不能做」与「属主是谁」的分工）",
      owners_before["intermediate:%s:2" % SS] == 10
      and verdict(item, 2, 7, [7, 8, 9, 10], True) == "NEXT_FOR_MACHINE")

# ==================== 结果 ====================
print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
