# -*- coding: utf-8 -*-
"""「重复处理器配方（按步序唯一定位）+ 按步过滤 + 原版 tooltip」自检。

用法：python tools/selfcheck_assembly_step_filter.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要这个脚本（本轮三个验收点，无法在本地把游戏跑起来验证）：
  1. **重复处理器配方**（坚固板 create:sturdy_sheet 的第 2、3 步都是冲压）必须能走完整条链：
     每一步都按「配方序列下标」唯一定位，第 2 步做完的件不被收回、末步成品必须产出；
  2. **中间产物类别必须按「步骤」精确过滤**（不是只按物品）：「勾第 2 步」与「勾第 3 步」是两个
     **互不覆盖**的过滤项，勾中任一都只放行该步的件；
  3. 中间产物物品上**不得**再残留自研的「禁止回流 / 未完成件 / 阈值」文案，
     原版进度描述改由 Create 自己的 tooltip 承担。

做法：① 源码锚点确认结构与调用点没被改坏；② 用等价 Python 模型复刻
`SequenceMaterialGuard#sameStep` / `#judgeStep` 与 `SequencedAssemblyRecipe#advance`，
把「全链路推演 + 过滤项互斥矩阵」一次跑完。
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

FAILURES = []
CHECKS = [0]

# 自研文案里必须消失的术语（用户点名删掉的那批；中英各一套写法）
FORBIDDEN_TERMS = ("禁止回流", "未完成件", "阈值", "no-return", "Incomplete item", "past-threshold")


def read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def section(title):
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


# ==================== 1. 源码锚点 ====================
section("1) 源码锚点：步序唯一化 + 按步过滤 + 无自研文案")

guard = read(os.path.join("support", "SequenceMaterialGuard.java"))
chamber = read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))
exporter = read(os.path.join("support", "RsccChamberExportStrategy.java"))
importer = read(os.path.join("support", "RsccChamberImportStrategy.java"))
jei = read(os.path.join("client", "SequenceTerminalJeiPlugin.java"))
terminal = read(os.path.join("block", "entity", "SequencePatternTerminalBlockEntity.java"))
menu = read(os.path.join("menu", "SequencePatternTerminalMenu.java"))

has(guard, "public static boolean sameStep(@Nullable final SequencedAssembly candidate,",
    "锚点: 按步比较本体只有一份（SequenceMaterialGuard#sameStep）")
has(guard, "return Math.floorMod(candidate.step(), sequenceSize)\n"
           "            == Math.floorMod(prototype.step(), sequenceSize);",
    "锚点: 按步比较对总步数取模（loops > 1 时同一步仍算同一步）")
has(guard, "if (prototype == null) {\n            return true;",
    "锚点: 不带步序的过滤项（原料 / 成品）一律放行，行为与既有一致")

has(chamber, "ItemStack filterPrototype,",
    "锚点: 类别携带「按步过滤」原型（BusCategoryInfo#filterPrototype）")
has(chamber, "public boolean hasStepFilter() {",
    "锚点: 类别可查询是否带按步原型")
has(chamber, "stepPrototypes.put(key, stepPrototypeOf(transitionalStack, unitRecipe, busStep));",
    "锚点: 每个「配方 + 步序」各建一份「过渡件 + 该步进度组件」原型"
    "（本轮把键从「只按步序」改成「配方 + 步序」：否则不同配方的同一步序共用一个原型 ⇒ "
    "另一条配方的过渡件永远导不出去，用户实测「冲压那里输出侧啥也没检测到」）")
check("锚点: 中间产物类别 id 带配方（{@code intermediate:<配方id>:<步序>}）—— 「配方 + 步序」共同定位，"
      "不同配方的同一步序因此是两个互不覆盖的类别（用户第 3 条：中间产物必须按配方分开）",
      "RsccBusCategory.intermediateId(recipeId, step)" in chamber
      and "return INTERMEDIATE_PREFIX + \":\" + recipeId + \":\" + step;"
          in read(os.path.join("support", "RsccBusCategory.java")))
has(chamber, "result.add(ItemResource.ofItemStack(info.filterPrototype()));",
    "锚点: 输出总线过滤项 = 带步序的原型资源（不是裸 ItemResource）")
has(chamber, "public boolean busExportMatches(final ItemStack candidate, final ItemResource filter) {",
    "锚点: 输出总线推料侧按步匹配")
has(chamber, "public boolean matchesCategoryStep(final BusCategoryInfo info, final ItemStack candidate) {",
    "锚点: 输入总线收回侧按步匹配（与推料侧同源）")
check("锚点: 两处匹配点都走 SequenceMaterialGuard#sameStep（只保留一份判据）",
      chamber.count("SequenceMaterialGuard.sameStep(") >= 2,
      "found=%d" % chamber.count("SequenceMaterialGuard.sameStep("))

has(exporter, "if (!chamber.isNextForMyMachines(inSlot)) {",
    "锚点: 推料侧仍先过「本仓还要不要它」")
has(chamber, "public boolean busExportAcceptsForPush(final ItemStack candidate, final ItemResource filter) {",
    "锚点: 推料侧的按步放行判据（放宽一档：物品 + 「本仓还要它」+ 同配方，步序不必逐位相同 —— "
    "见 SequenceExecutionChamberBlockEntity#busExportAcceptsForPush 的说明）")
has(exporter, "if (!chamber.busExportAcceptsForPush(inSlot, item)) {",
    "锚点: 推料侧过「按步放行」闸门"
    "（旧锚点 `busExportMatches` 固化了「少勾一个步的类别就推不出去」这个 bug，2026-10-06 随实现更新）")
has(importer, "info.items().contains(stack.getItem())",
    "锚点: 手动收回仍按类别物品匹配（既有行为保留）")
has(importer, "&& definer.matchesCategoryStep(info, stack);",
    "锚点: 手动收回加上「按步」匹配"
    "（2026-10-06 链展开后判据由「定义该类别的仓」回答：本仓定义的类别仍由本仓回答 ⇒ 未串链时"
    "与旧写法 `chamber.matchesCategoryStep(info, stack)` 逐字等价，锚点随实现更新）")

# 导入不再合并重复步骤（否则步序不唯一 → 丢掉后面那个序列下标）
check("锚点: JEI 导入一步一行（合并逻辑已删除）",
      "stepCounts.set(last, stepCounts.get(last) + 1);" not in jei
      and "ItemStack.matches(stepInputs.get(last), stepInput)" not in jei
      and "machines.add(machine);" in jei
      and "stepCounts.add(1);" in jei)
check("锚点: 单元样板的步序 = 编排下标（第 i 步写第 i 个步序），步序因此天然唯一",
      'recipeId == null ? "" : recipeId.toString(), i,' in menu
      and "public boolean importCreateRecipe(" not in terminal)

# 自研 tooltip 类已删除 + 语言键已删除
tooltip_path = os.path.join(SRC, "client", "SequenceMaterialTooltip.java")
check("自研 tooltip 处理器已整体删除（改由 Create 原版展现）", not os.path.exists(tooltip_path))
residue = []
for lang in ("zh_cn.json", "en_us.json"):
    with io.open(os.path.join(LANG_DIR, lang), "r", encoding="utf-8") as handle:
        data = json.load(handle)
    leftover = [k for k in data if "raw_material.tip" in k
                or "sequence_incomplete.tip" in k or "disallow_inputting_by_step.tip" in k]
    check("%s：自研 tooltip 语言键已删除" % lang, not leftover, str(leftover))
    # 只查「物品提示」命名空间：中间产物 / 样板上的自研文案全部落在 item.rs_create_compat.*
    #（RS 总线界面自己的说明文案（gui.rs_create_compat.*）不在本轮范围内，不动）
    for key, value in data.items():
        if key.startswith("item.rs_create_compat.") and any(term in value for term in FORBIDDEN_TERMS):
            residue.append("%s:%s" % (lang, key))
check("物品提示（item.rs_create_compat.*）里不再出现「禁止回流 / 未完成件 / 阈值」这类术语",
      not residue, str(residue[:5]))

# ==================== 2. 等价模型 ====================
section("2) 等价模型：Create 序列装配 3 步（坚固板：0 注液 / 1 冲压 / 2 冲压）")

TOTAL = 3
RECIPE = "create:sturdy_sheet"
TRANSITIONAL = "create:unprocessed_obsidian_sheet"
PRODUCT = "create:sturdy_sheet"


def same_step(candidate, prototype, size):
    """复刻 SequenceMaterialGuard#sameStep；candidate / prototype = (recipe, step) 或 None。"""
    if prototype is None:
        return True
    if candidate is None:
        return False
    if candidate[0] != prototype[0]:
        return False
    if size <= 0:
        return candidate[1] == prototype[1]
    return (candidate[1] % size) == (prototype[1] % size)


def judge_step(item_step, machine_step, total):
    """复刻 SequenceMaterialGuard#judgeStep（同一条配方）。"""
    if machine_step < 0:
        return "UNKNOWN"
    if item_step is None:
        return "NOT_MINE"
    if total <= 0:
        return "UNKNOWN"
    return "NEXT_FOR_MACHINE" if (item_step % total) == machine_step else "NOT_MINE"


def chamber_verdict(item_step, units, total=TOTAL):
    """复刻 stepVerdictFor：只要有一份样板说「下一步轮到它」就归它，否则判不出来则保守。"""
    if not units:
        return "UNKNOWN"
    unknown = False
    for machine_step in sorted(units):
        verdict = judge_step(item_step, machine_step, total)
        if verdict == "NEXT_FOR_MACHINE":
            return "NEXT_FOR_MACHINE"
        if verdict == "UNKNOWN":
            unknown = True
    return "UNKNOWN" if unknown else "NOT_MINE"


def reclaimable(item_step, units, total=TOTAL):
    """输入总线是否收回（isTransitionReclaimAllowed 的结论面）。
    <p>不带进度组件的件（起步原料 / 末步成品）**不由本判定负责**（Java 侧方法开头就 return false），
    它们走「是否输入类」的类别判据，因此这里恒为 False。"""
    if item_step is None:
        return False
    return chamber_verdict(item_step, units, total) == "NOT_MINE"


def pushable(item_step, units, total=TOTAL):
    """输出总线是否推给机器（isNextForMyMachines 的结论面）。
    <p>不带进度组件的件一律 True（Java 侧方法开头就 return true，交由类别判据决定）。"""
    if item_step is None:
        return True  # 原料 / 成品：没有进度步，照旧（由类别判据决定）
    return chamber_verdict(item_step, units, total) == "NEXT_FOR_MACHINE"


def advance(item_step, loops):
    """复刻 SequencedAssemblyRecipe#advance：返回新进度步；None = 本轮已出成品。"""
    step = 0 if item_step is None else item_step
    if (step + 1) // TOTAL >= loops:
        return None
    return step + 1


CH_FILL = {0}
CH_PRESS1 = {1}
CH_PRESS2 = {2}
CH_PRESS_BOTH = {1, 2}


def simulate(chambers, loops=1, max_steps=64):
    """全链路推演：网络里的起步原料 → 各仓认领 → 机器加工 → 收回 → 下一步骤，
    直到出成品（PRODUCT）/ 出冲突（CONFLICT）/ 死锁（DEADLOCK）/ 超步数（TIMEOUT）。"""
    trace = []
    item_step = None  # 起步原料：不带进度组件
    for _ in range(max_steps):
        owner = None
        for index, units in enumerate(chambers):
            if item_step is None:
                if 0 in units:
                    owner = index
                    break
            elif chamber_verdict(item_step, units) == "NEXT_FOR_MACHINE":
                owner = index
                break
        if owner is None:
            trace.append((None, item_step, None, None, None))
            return trace, "DEADLOCK"
        units = chambers[owner]
        # 推出前：这台仓绝不能同时允许收回（否则「刚推出又立刻被收回」= 空转）
        conflict = reclaimable(item_step, units)
        nxt = advance(item_step, loops)
        trace.append((owner, item_step, 0 if item_step is None else item_step % TOTAL, conflict, nxt))
        if conflict:
            return trace, "CONFLICT"
        if nxt is None:
            return trace, "PRODUCT"
        item_step = nxt
    return trace, "TIMEOUT"


# ==================== 3. 任务 1 断言：重复处理器配方全链路推演 ====================
section("3) 重复处理器配方全链路：1 → 2 → 3 步都要走得通")
trace, outcome = simulate([CH_FILL, CH_PRESS1, CH_PRESS2])
print("  三仓（注液 / 冲压#1 / 冲压#2）推演：")
for owner, item_step, cur, conflict, nxt in trace:
    print("    仓#%s 收到 step=%-4s 加工下标=%-4s 推出当场被收回=%-5s → 下一步=%s"
          % (owner, item_step, cur, conflict, nxt))
check("重复处理器配方：三步走完并产出成品（不死锁、不超步数）", outcome == "PRODUCT", outcome)
check("每一步都落在「按序列下标」对应的那台仓上（0/1/2 依次）",
      [row[0] for row in trace] == [0, 1, 2], str([row[0] for row in trace]))
check("第 2 步冲压做完的件（step=2）确实交给「负责第 3 步」的仓#2（不再被判没人要）",
      trace[1][4] == 2 and trace[2][0] == 2)
check("推出当场绝不收回（三行都 conflict=False）", all(row[3] is False for row in trace))

trace_both, outcome_both = simulate([CH_FILL, CH_PRESS_BOTH])
check("同一台仓连管第 2、3 步冲压（units={1,2}）时同样能走完并产成品",
      outcome_both == "PRODUCT", outcome_both)
check("同一台仓连管两步时：第 2 步做完仍归它（不收回），第 3 步做完才放行",
      reclaimable(1, CH_PRESS_BOTH) is False and reclaimable(2, CH_PRESS_BOTH) is False
      and reclaimable(3, CH_PRESS_BOTH) is True)
check("末步成品（不带进度组件）不由「按步」判定负责 → 走「非输入类」的正向回收路径",
      chamber_verdict(None, CH_PRESS2) == "NOT_MINE"
      and chamber_verdict(None, CH_PRESS_BOTH) == "NOT_MINE")

# 旧实现（导入时把两步合并成一个步骤）为什么会坏：把根因固化成断言
legacy_trace, legacy_outcome = simulate([CH_FILL, CH_PRESS1])
check("旧实现（合并步骤 → 只有 step=1 的样板）在 step=2 死锁：正是「最终产物死活不产出」的根因",
      legacy_outcome == "DEADLOCK")
check("旧实现下 step=2 的件在冲压仓被判「不是我的」→ 被输入总线收回（用户实测的「你收回个毛线啊」）",
      reclaimable(2, CH_PRESS1) is True)
check("旧实现下没有任何仓认领 step=2（推料侧 pushable=False，料再也喂不回机器）",
      pushable(2, CH_PRESS1) is False and pushable(2, CH_FILL) is False)

# 互补性：可判定时必须一真一假；判不出来时两侧都停手
complement_ok = True
for units in (CH_FILL, CH_PRESS1, CH_PRESS2, CH_PRESS_BOTH):
    steps = [None, 0, 1, 2, 3, 4, 5]
    for step in steps:
        if step is None:
            continue  # 无进度步：两侧各自按类别判据处理，不属本互补关系
        verdict = chamber_verdict(step, units)
        if verdict == "UNKNOWN":
            if pushable(step, units) or reclaimable(step, units):
                complement_ok = False
        elif pushable(step, units) == reclaimable(step, units):
            complement_ok = False
check("推料侧与收回侧严格互补（同一个 (料, 机器) 不可能既推又收）", complement_ok)
check("判不出来（没有可用样板 / 配方总步数查不到）时两侧都停手",
      pushable(1, set()) is False and reclaimable(1, set()) is False
      and pushable(1, CH_PRESS1, total=0) is False and reclaimable(1, CH_PRESS1, total=0) is False
      and pushable(1, {-1}) is False and reclaimable(1, {-1}) is False)

# ==================== 4. 任务 2 断言：按步过滤两项互不覆盖 ====================
section("4) 按步过滤：中间产物类别带步序组件，两个过滤项互不覆盖")


def filter_prototype(step):
    """某一步的过滤项原型 = 过渡件 + (配方, 步序) 进度组件。"""
    return (RECIPE, step)


def filter_key(step):
    """过滤项资源键：物品 + (配方, 步序) —— 两个不同步就必须是两个不同的键。"""
    return (TRANSITIONAL, RECIPE, step)


step2 = filter_prototype(1)
step3 = filter_prototype(2)
cand_after_step1 = (RECIPE, 1)   # 第 1 步做完的件（s%3 == 1）
cand_after_step2 = (RECIPE, 2)   # 第 2 步做完的件（s%3 == 2）
cand_next_loop = (RECIPE, 4)     # 下一循环里同一序号（4 % 3 == 1）
raw_or_product = None            # 起步原料 / 末步成品：不带进度组件

check("「第 2 步」过滤项只放行第 1 步做完的件",
      same_step(cand_after_step1, step2, TOTAL) and not same_step(cand_after_step2, step2, TOTAL))
check("「第 3 步」过滤项只放行第 2 步做完的件",
      same_step(cand_after_step2, step3, TOTAL) and not same_step(cand_after_step1, step3, TOTAL))
check("两个过滤项互不覆盖：没有任何一份件被同时放行",
      not any(same_step(cand, step2, TOTAL) and same_step(cand, step3, TOTAL)
              for cand in (cand_after_step1, cand_after_step2, cand_next_loop, raw_or_product)))
check("两个过滤项的资源键不同（不再退化成同一个裸 ItemResource）",
      filter_key(1) != filter_key(2))
check("下一循环里的同一序号仍算同一步（loops > 1 不会漏料）",
      same_step(cand_next_loop, step2, TOTAL))
check("不带进度组件的件（起步原料 / 末步成品）不被任何「某一步」的过滤项放行",
      not same_step(raw_or_product, step2, TOTAL) and not same_step(raw_or_product, step3, TOTAL))
check("不带步序的过滤项（输入类 / 旧类别）一律放行（既有行为逐字不变）",
      same_step(cand_after_step2, None, TOTAL) and same_step(raw_or_product, None, TOTAL))
check("配方不同（同一个过渡件属于多条配方）时不匹配",
      not same_step(("create:other_recipe", 1), step2, TOTAL))
check("总步数查不到时退化为「绝对步相等」（保守：宁可少放，也不误放）",
      same_step((RECIPE, 2), step2, -1) is False
      and same_step((RECIPE, 1), step2, -1) is True)

# ==================== 结果 ====================
print()
print("=" * 72)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
