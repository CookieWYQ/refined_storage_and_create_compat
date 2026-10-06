# -*- coding: utf-8 -*-
"""推料门粒度 + 「机器在加工时不再开新件」自检（本轮两个验收点）。

用法：python tools/selfcheck_assembly_push_gate.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要这个脚本（无法在本地把游戏跑起来验证）：
  1. **每种输出各一份（按资源 / 按步骤）**，而不是「整台机器全局只推一份」：
     同一台机器上一次只留一份**同种**输入（同种料在机器里 / 在手里就不再补），
     但**不同**的输入（齿轮 / 大齿轮 / 铁粒）可以同时各留一份 —— 否则机械手会卡死；
  2. **不同机器可以同时各一份**：一台执行舱常常给多台机器供料，而各机器所处的步骤并不相同，
     推料门必须按「本总线面对的那一台机器」判（旧实现按全仓并集 / 取最小步，
     症状就是用户说的「不是每种输出各一份，而是总体只出一份」）；
  3. **机器还在加工这一步的件时不再开新件**（起步原料专用）：否则注液仓会以约 1 秒 1 份的速度
     补黑曜石粉，而整条线每件要 4 秒以上 —— 用户实测「三份粉才出一份板」；
     但这条闸门**绝不能**压在「正在加工的那一件自己的输入」上（注液步的岩浆），否则会把在制件饿死；
  4. **单件消耗量恰为 1**：一次搬运只搬 1 份（堆叠升级也压不住输入类原料）。
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


chamber = read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))
strategy = read(os.path.join("support", "RsccChamberExportStrategy.java"))

# ==================== 1. 源码锚点 ====================
section("1) 源码锚点：按机器 / 按资源 / 按步骤的推料门")

has(chamber, "public boolean isStartIngredient(",
    "锚点: 执行舱能认出「起步原料」（= 只有它会开一件新在制件）")
has(chamber, "public boolean stepUnitInFlightOn(",
    "锚点: 执行舱能判「这台机器上还压着正等本仓某一步加工的在制件」")
has(chamber, "public boolean inputMaterialWantedNow(@org.jetbrains.annotations.Nullable final BlockPos target,",
    "锚点: 「该喂哪一件投入物」按目标机器判（不再是全仓唯一答案）")
has(chamber, "if (step >= 0 && isOwnedStep(assembly.id().toString(), step)) {",
    "锚点: 在制件判定只按「配方 id + 步序」（isOwnedStep），不做方块坐标比较")
has(chamber, "final PendingStep pending = target == null ? pendingStepOnTargets() : pendingStepOn(target);",
    "锚点: 推料侧走按机器探针 pendingStepOn(target)，全仓并集只剩兜底")
has(chamber, "private PendingStep pendingStepOn(final BlockPos target) {",
    "锚点: 按机器探针只有一份实现（pendingStepOn）")
has(chamber, "private Set<Item> occupiedInputMaterialsAt(final BlockPos exporterPos) {",
    "锚点: 「机器还压着没消化掉的料」探针按本总线的目标机器（不是全仓并集）")
has(chamber, "if (info.isInput() && anyHeld(occupied, info.items())) {",
    "锚点: 总线喂料门仍只作用于输入类类别，但用的是「本机占用集」")
has(chamber, "private Set<Item> startIngredients() {",
    "锚点: 起步原料表按节拍缓存（判据与类别表同源）")

has(strategy, "chamber.inputMaterialWantedNow(targetPos, item.item())",
    "锚点: 输出总线把「本总线面对的那一台机器」交给执行舱判定")
has(strategy, "chamber.isStartIngredient(item.item()) && chamber.unitInFlightAt(targetPos)",
    "锚点: 起步原料在「工位上还压着在制件」时不再补料"
    "（本轮把判据从只看「下一步归本仓」的 stepUnitInFlightOn 放宽到按工位判 —— 跨仓产线不再多投一份）")
has(strategy, "machine_busy_with_my_step",
    "锚点: 该闸门有独立原因串（日志里可与机器满 / 非本步区分）")
has(strategy, "if (holdsSameItem(target, item.item())) {",
    "锚点: 「同种料还在机器里」这条判据保留（按目标机器）")
has(strategy, "chamber.isInputMaterial(item.item()) ? INPUT_FEED_UNIT : Long.MAX_VALUE",
    "锚点: 输入类原料单次搬运量硬夹到 1 件（堆叠升级也压不住）")

# 「不做整台机器全局只推一份」：四道闸门都是「按资源 / 按步骤 / 按消费者」的，
# 不存在「本机本轮只推一份、推完谁都别想推」这种全局闸门。
check("锚点: 推料门只有四条、且都按「资源 / 步骤 / 消费者」判（机器持同种料 / 非本步投入物 / "
      "本步在制件 / 步骤专用投入物的目标不是机械手），没有「整台机器本轮只推一份」的实现",
      strategy.count("machine_holds_input") == 1
      and strategy.count("not_current_step_input") == 1
      and strategy.count("machine_busy_with_my_step") == 1
      and strategy.count("assert_step_extra_not_consumer") == 1)
check("锚点（本轮新增，用户第 2 条：齿轮被推到置物台然后没有消费者）: 推料侧对「步骤专用投入物」"
      "先确认目标机器<b>真的是它的消费者</b>（机械手）；不是 ⇒ 放弃推送（留在本仓，绝不销毁）",
      "if (chamber.isStepExtraInputItem(item.item()) && !chamber.consumesStepExtraAt(targetPos)) {" in strategy
      and "public boolean consumesStepExtraAt(" in chamber
      and "public boolean isStepExtraInputItem(" in chamber)

# ==================== 2. 等价模型 ====================
section("2) 等价模型：一台仓 → 多台机器（机械手 / 注液机 / 冲压机）")

FILL, PRESS = 0, {1, 2}
DEPLOY = 0


def machine_occupied(items):
    """机器容器里有哪些物品种类（holdsSameItem / anyHeld 的原始数据）。"""
    return set(items)


def holds_same_item(machine, item):
    return item in machine_occupied(machine)


def pending_step(machine):
    """pendingStepOn(machine)：机器上过渡件的 (配方, 下一步)；没有 = None。"""
    for unit in machine:
        if unit[0] == "unit":
            return (unit[1], unit[2])
    return None


def unit_in_flight_for_my_step(machine, owned_steps):
    p = pending_step(machine)
    return p is not None and p[1] in owned_steps


def push_allowed(chamber_state, machine, item, is_input, is_start, owned_steps,
                 step_extra_inputs=None, is_step_extra=False, target_is_consumer=True):
    """复刻 RsccChamberExportStrategy#transferItem 的四道门（返回 reason 或 None=放行）。"""
    if is_input and is_step_extra and not target_is_consumer:
        # 步骤专用投入物只能推给它的真正消费者（机械手）：推到置物台 / 传输带上永远没人吃
        return "assert_step_extra_not_consumer"
    if is_input and holds_same_item(machine, item):
        return "machine_holds_input"
    if is_input and step_extra_inputs is not None:
        p = pending_step(machine)
        wanted = step_extra_inputs(p, item) if p is not None else True
        if not wanted:
            return "not_current_step_input"
    if is_start and unit_in_flight_for_my_step(machine, owned_steps):
        return "machine_busy_with_my_step"
    return None


def push_amount(quota, is_input):
    """RS 配额 → 实际搬运量：输入类原料一律夹到 1 件。"""
    return min(quota, 1 if is_input else quota)


# ==================== 3. 每种输出各一份 / 不同输出可同时各一份 ====================
section("3) 每种输出各一份（按资源）+ 不同输出可同时各一份")

# 机械手（一台机器，管第 0/1/2 步）：手上拿着齿轮、置物台压着第 0 步的在制件
# 注意：机器内容是一串「持有的物品」——过渡件用 ("unit", 配方, 步序) 表达，普通物品就是物品名本身
#      （旧写法把齿轮写成 ("cogwheel",) 这种单元素元组，导致 holds_same_item 永远判不出「手里有齿轮」，断言假失败）
deployer = [("unit", "create:precision_mechanism", 0), "cogwheel"]
extra_of_step = {0: {"cogwheel"}, 1: {"large_cogwheel"}, 2: {"iron_nugget"}}
wanted = lambda p, item: item in extra_of_step.get(p[1], set())

check("同种输入：机器/手里已有该件 → 不再补第二份（machine_holds_input）",
      push_allowed(None, deployer, "cogwheel", True, False, {DEPLOY}, wanted) == "machine_holds_input")
hand_empty = [("unit", "create:precision_mechanism", 0)]
check("不同输入可以同时各一份：手里空 → 第 0 步的齿轮放行",
      push_allowed(None, hand_empty, "cogwheel", True, False, {DEPLOY}, wanted) is None)
check("不同输入不可以乱插：第 0 步的件还没走完，大齿轮一律被挡（not_current_step_input）",
      push_allowed(None, hand_empty, "large_cogwheel", True, False, {DEPLOY}, wanted)
      == "not_current_step_input")
unit_step1 = [("unit", "create:precision_mechanism", 1)]
check("步进到第 1 步后：大齿轮放行、齿轮不再放行（每步各一件，互不顶掉）",
      push_allowed(None, unit_step1, "large_cogwheel", True, False, {DEPLOY}, wanted) is None
      and push_allowed(None, unit_step1, "cogwheel", True, False, {DEPLOY}, wanted)
      == "not_current_step_input")

# 不同机器可同时各一份：同一条仓给 A（第 0 步）与 B（第 1 步）供料
machine_a = [("unit", "create:precision_mechanism", 0)]
machine_b = [("unit", "create:precision_mechanism", 1)]


def chamber_wide_min_step(machines):
    """旧实现：全仓并集、取最小步（症状 = 总体只出一份）。"""
    steps = [pending_step(m)[1] for m in machines if pending_step(m) is not None]
    return min(steps) if steps else None


def push_allowed_old(machines, item):
    """旧口径：用「全仓最小步」那一步的投入物表去卡每一台机器。"""
    step = chamber_wide_min_step(machines)
    return item in extra_of_step.get(step, set())


check("旧口径（全仓最小步）只会放行靠前那一步的投入物 —— 正是「总体只出一份」的根因",
      push_allowed_old([machine_a, machine_b], "cogwheel") is True
      and push_allowed_old([machine_a, machine_b], "large_cogwheel") is False)
check("新口径（按本机判）下两台机器各自的投入物都放行",
      push_allowed(None, machine_a, "cogwheel", True, False, {0, 1}, wanted) is None
      and push_allowed(None, machine_b, "large_cogwheel", True, False, {0, 1}, wanted) is None)

# ==================== 4. 机器在加工时不补料（起步原料专用） ====================
section("4) 机器还在加工这一步的件 → 不再开新件（且绝不饿死在制件）")

filling_busy = [("unit", "create:sturdy_sheet", 0)]  # 注液机上台面压着第 0 步的在制件
check("起步原料（黑曜石粉）：机器上还压着本仓这一步的件 → 不再补一份",
      push_allowed(None, filling_busy, "powdered_obsidian", True, True, {FILL}) == "machine_busy_with_my_step")
check("同一 tick 里该步自己的输入（岩浆）照常放行 —— 否则在制件会被饿死",
      push_allowed(None, filling_busy, "lava", False, False, {FILL}) is None)
filling_step1 = [("unit", "create:sturdy_sheet", 1)]  # 注液已完成，轮到冲压
check("本仓这一步加工完（步序离开本仓的步）→ 闸门立刻放行（自愈，不需要任何计数）",
      push_allowed(None, filling_step1, "powdered_obsidian", True, True, {FILL}) is None)
check("闸门只压在起步原料上：过渡件（未完成板材）的推料不受它影响",
      push_allowed(None, filling_step1, "unprocessed_obsidian_sheet", False, False, {FILL}) is None
      and push_allowed(None, [("unit", "create:sturdy_sheet", 1)], "unprocessed_obsidian_sheet",
                       False, False, PRESS) is None)
check("判不出在制件（机器上没有过渡件）时一律放行（旧行为，绝不据此断供）",
      push_allowed(None, [], "powdered_obsidian", True, True, {FILL}) is None)

# ==================== 5. 单件消耗量恰为 1 ====================
section("5) 单件消耗量：一次搬运恰为 1 份（堆叠升级也压不住）")
check("输入类原料：配额 1 / 64 都被夹到 1 件", push_amount(1, True) == 1 and push_amount(64, True) == 1)
check("非输入类（中间产物 / 成品）仍按 RS 配额走（不误伤既有吞吐）", push_amount(64, False) == 64)
check("起步原料那一份闸门是布尔判据（不改变搬运量），因此一次装配恰消耗 1 份原料",
      "machine_busy_with_my_step" in strategy
      and strategy.count("Result.SKIPPED") >= 1
      and "INPUT_FEED_UNIT" in strategy)

# ==================== 6. 步骤专用投入物：目标不是消费者 ⇒ 一律不推（本轮新增） ====================
section("6) 步骤专用投入物只能推给机械手（用户第 2 条：齿轮被推到置物台然后没有消费者）")

arm = [("unit", "create:precision_mechanism", 0)]
depot = [("unit", "create:precision_mechanism", 0)]
print("  反例表（目标机器形态 → 齿轮该不该被推出去）")
print("  %-46s %-8s %-8s" % ("情形", "旧口径", "本轮"))
print("  %-46s %-8s %-8s" % ("目标是机械手（第 0 步正等齿轮）", "推", "推"))
print("  %-46s %-8s %-8s" % ("目标是置物台（第 0 步在制件让判据说「要」）", "推（卡死）", "不推"))
check("目标机器不是机械手（置物台 / 传输带）⇒ 齿轮一份都不推（旧口径会推过去、然后永久卡在那里）",
      push_allowed(None, depot, "cogwheel", True, False, {DEPLOY}, wanted,
                   is_step_extra=True, target_is_consumer=False) == "assert_step_extra_not_consumer")
check("目标是机械手（真正的消费者）⇒ 行为一字未改，按既有四道门正常放行",
      push_allowed(None, arm, "cogwheel", True, False, {DEPLOY}, wanted,
                   is_step_extra=True, target_is_consumer=True) is None)
check("非「步骤专用投入物」（起步原料 / 过渡件）不受本闸门影响（不误伤主原料与中间产物）",
      push_allowed(None, [], "powdered_obsidian", True, True, {FILL},
                   is_step_extra=False, target_is_consumer=False) is None
      and push_allowed(None, depot, "unprocessed_obsidian_sheet", False, False, {FILL},
                       is_step_extra=False, target_is_consumer=False) is None)

# ==================== 结果 ====================
print()
print("=" * 72)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
