# -*- coding: utf-8 -*-
"""第 13 轮用户反馈 5 条的源码锚点 + <b>可离线推演的反例表</b>。

 ① 下单 1 件 vs 64 件的消耗差异（多耗一份黑曜石粉 / 少用岩浆）
 ② 齿轮堵塞（连说三次）：输入线自动模式 / 配置把「本步还要」判错
 ③ 金板自动触发精密构件（无订单却开工）
 ④ 断缝（拔线缆）后没有「已挂起」横幅
 ⑤ 数量快速增减 —— 无条件禁止

为什么要有它：这五条都不能靠肉眼看刷屏日志判定。本脚本把
「每条期望的代码落点」逐个断言，并<b>复刻</b>对应的判定函数，用反例表证明
「哪条路径会导致多耗 / 往返 / 误触发」→「新逻辑为什么挡住它」。

用法：python tools/selfcheck_round13_feedback.py
      → 全绿输出 `SELFCHECK OK (n checks)`；失败退出码 1。

2026-10-06（第 29 轮）就地更新：⑤ 的锚点①/② 原先钉的是 `requestBatch` 那条三行三元式的**源码排版**
（`? Math.max(…)` 与换行后的 `: 1L;`）。新增第三档「按机器台数发」时该三元式被套进一层
「未启用第三档才走旧口径」的分支，于是这两条**排版锚点**失效，而它们要钉的**行为**（概率性批次经
allowedConcurrentUnits 收敛 / 确定性一次一份）一个字都没变。这里改为钉「旧口径表达式本身仍在」
「未启用第三档时仍落到 1L 分支」，并补一条「第三档分量默认恒为 0」的新锚点 —— 行为断言不变、
还多了一层「默认档不受新档影响」的保护；第三档自己的请求量上界由
tools/selfcheck_round29_refill_machine_mode.py 断言。
"""
import io
import os
import re
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
    with io.open(os.path.join(*parts), "r", encoding="utf-8") as handle:
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
    print("=" * 84)
    print(title)
    print("=" * 84)


def code_only(src):
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


chamber = read(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
import_strategy = read(SRC, "support", "RsccChamberImportStrategy.java")
watchdog = read(SRC, "support", "AssemblyWatchdog.java")

# ============================================================ ① 下单 1 件 vs 64 件
section("① 下单 1 件 vs 64 件：剩余件数读不到时，份额退回「在制件数」这一保守下界")

has(chamber, "private long allowedConcurrentUnits() {",
    "锚点①: 份额的唯一收敛值 allowedConcurrentUnits（可读时用订单剩余量，读不到时用确定性的在制下界）")
has(chamber, "private long inFlightUnitCount() {",
    "锚点②: 「当下真在制 / 待补的件数」只有一份实现（inFlightUnitCount，每 tick 至多算一次）")
has(chamber, "final long allowed = allowedConcurrentUnits();",
    "锚点③: exportShare 一律经 allowedConcurrentUnits（不再有「判不出来 → 全部台数」的旁路）")
check("锚点④: 「判不出来就退回全部台数」的旧旁路已彻底移除（它是「下单 1 件却两台机器各推一份」的机制）",
      "return ownerCount; // 判不出来" not in chamber
      and "return ownerCount;" not in chamber[chamber.index("private int exportShare("):
                                             chamber.index("private int exportShare(") + 1400])
has(chamber, "private void clampStartIngredientTargets(final Map<Item, Long> itemTargets) {",
    "锚点⑤: 起步原料「在制名额」闸门只有一份实现")
has(chamber, "final long cap = Math.max(0L, allowedConcurrentUnits() - inFlightUnitCount());",
    "锚点⑥: 名额 = 允许同时在制件数 − 已在制件数；为 0 时整条去掉起步原料目标（一条判定都不做）")
has(chamber, "clampStartIngredientTargets(itemTargets);",
    "锚点⑦: 闸门挂在备料目标表构建之后、抽取之前（唯一入口）")


def allowed_units(order_remaining, readable, inflight):
    """复刻 allowedConcurrentUnits：可读（> 0）用订单剩余量，否则取 max(1, 在制件数)。"""
    return order_remaining if (readable and order_remaining > 0) else max(1, inflight)


def export_share(owners, order_remaining, readable, inflight):
    """复刻 exportShare（本轮）。"""
    if owners <= 1:
        return owners
    return max(1, min(owners, allowed_units(order_remaining, readable, inflight)))


def old_export_share(owners, order_remaining, readable):
    """复刻 exportShare（修复前）：判不出来 → 全部台数。"""
    if owners <= 1:
        return owners
    if not readable or order_remaining <= 0:
        return owners
    return max(1, min(owners, order_remaining))


def start_cap(order_remaining, readable, inflight):
    """复刻 clampStartIngredientTargets 的名额（<= 0 ⇒ 整条去掉）。"""
    return max(0, allowed_units(order_remaining, readable, inflight) - inflight)


print("  反例表（两台机器 / 两条输出总线；起步原料 perBatch=1 ⇒ 目标量本来就是 1 份）")
print("  %-26s %-10s %-8s %-10s %-10s %-10s" % ("情形", "可读剩余", "在制", "旧份额", "新份额", "粉名额"))
ROWS = [
    ("N=1 读不到任务（真实现场）", 1, 0, 0),
    ("N=1 读得到", 1, 1, 0),
    ("N=1 已有一件在制", 1, 0, 1),
    ("N=64 读得到", 64, 1, 0),
    ("N=64 读得到 + 已两件在制", 64, 1, 2),
]
for name, remaining, readable, inflight in ROWS:
    print("  %-26s %-10s %-8d %-10d %-10d %-10d" % (
        name, bool(readable), inflight,
        old_export_share(2, remaining, readable), export_share(2, remaining, readable, inflight),
        start_cap(remaining, readable, inflight)))

check("①a 下单 1 件 + 读不到剩余件数 + 无在制：份额 = 1（旧 = 2）⇒ 只有一条总线导出 ⇒ 只开一件在制件",
      export_share(2, 1, False, 0) == 1 and old_export_share(2, 1, False) == 2)
check("①b 同情形 + 已在制一件：起步原料名额 = 0 ⇒ 一件在飞时绝不再备第二份粉（旧：再抽一份）",
      start_cap(1, False, 1) == 0)
check("①c 下单 1 件时「刚好够」零多余：把上面的份额与名额串起来，粉的总抽取量恒为 1",
      min(1, start_cap(1, False, 0)) == 1 and start_cap(1, False, 1) == 0)
check("①d 下单 64 件仍可并行：两台机器都参与（份额 = 2）且粉名额 ≫ 1，绝不因这次修正而减产",
      export_share(2, 64, True, 1) == 2 and start_cap(64, True, 2) == 62)

# ============================================================ ② 齿轮堵塞
section("② 齿轮堵塞：空闲机械手不再被当成「判不出来 ⇒ 保底备一份」")

has(chamber, "private boolean isStepExtraInput(final Item item) {",
    "锚点①: 「步骤专用投入物」只有一份判定")
check("锚点②: 机械手判不出待加工件 = 确定「不需要」，不再计入 undecidable（与推料侧同一口径）",
      "if (!isDeployer(level, target)) {\n                    undecidable = true;\n                }" in chamber)
has(chamber, "if (!stepExtraOnlyWorkingStations(exporterPos, owners)) {",
    "锚点③: 步骤专用投入物只投给「真的在加工」的工位")
has(chamber, "if (item != null && inputMaterialWantedNow(target, item)) {",
    "锚点④: 清单里只放「本总线目标机器此刻真的要的那一件」")
has(import_strategy, "&& !chamber.inputMaterialWantedNow(pos, stack.getItem())) {",
    "锚点⑤: 收回侧与推料侧同一判据（压在机器上的废料性残留会被收回 ⇒ 不会永久卡死）")


def step_extra_want(target, world, item):
    """复刻 wantingTargetCount 的 per-station 判定（本轮）。

    world[target] = {"pending": 步序或 None, "deployer": 是否机械手, "holds": 是否已握着该件}
    返回: (是否需要这一件, 是否计入 undecidable)
    """
    state = world.get(target, {})
    if state.get("holds"):
        return False, False          # 已握着 ⇒ 它不缺料
    pending = state.get("pending")
    if pending is None:
        if state.get("deployer"):
            return False, False      # 机械手空闲 = 确定不需要（本轮修正）
        return False, True           # 其它机器形态：判不出来 ⇒ 保底
    return (item in state.get("wants", set())), False


def wanting_count(world, item):
    want = 0
    undecidable = not world
    for target in world:
        need, undec = step_extra_want(target, world, item)
        undecidable = undecidable or undec
        if need:
            want += 1
    return want if want > 0 else (1 if undecidable else 0)


GEAR = "create:cogwheel"
idle_arm = {"arm": {"pending": None, "deployer": True}}
working_arm = {"arm": {"pending": 0, "deployer": True, "wants": {GEAR}}}
arm_step1 = {"arm": {"pending": 1, "deployer": True, "wants": {"create:large_cogwheel"}}}
arm_holds = {"arm": {"pending": 0, "deployer": True, "wants": {GEAR}, "holds": True}}
unknown_machine = {"machine": {"pending": None, "deployer": False}}

print("  反例表（wantingTargetCount → 备料量上限）")
print("  %-40s %-10s" % ("工位状态", "备料量"))
for label, world in (("机械手空闲（无工件，本轮修正点）", idle_arm),
                     ("机械手第 0 步要齿轮", working_arm),
                     ("机械手第 1 步（要的是大齿轮）", arm_step1),
                     ("机械手已握着齿轮", arm_holds),
                     ("判不出步序的其它机器（保持宽松）", unknown_machine)):
    print("  %-40s %-10d" % (label, wanting_count(world, GEAR)))

check("②a 机械手空闲 ⇒ 备料量 0（旧口径：保底 1 份 ⇒ 仓里多出一份齿轮 = 用户点名的「多余中间产物」）",
      wanting_count(idle_arm, GEAR) == 0)
check("②b 机械手第 0 步要齿轮 ⇒ 备料量 1（绝不因本次修正而断供）",
      wanting_count(working_arm, GEAR) == 1)
check("②c 机械手已握着齿轮 ⇒ 备料量 0（单机一份不变式：同一台机器同一时刻最多一份未消耗投入物）",
      wanting_count(arm_holds, GEAR) == 0)
check("②d 步序判不出的其它机器形态仍然保底 1 份（附属模组的自定义步不会被饿死）",
      wanting_count(unknown_machine, GEAR) == 1)

# ============================================================ ③ 无订单不产
section("③ 金板自动触发精密构件：无订单时两侧（取料 / 收回）一起停")

has(chamber, "private Set<Item> conservativeGoalItems() {",
    "锚点①: 产物集合判不出来时的保守回退只有一份实现")
has(chamber, "goals = conservativeGoalItems();",
    "锚点②: hasGoalTask 判不出来时改走保守产物集合（不再「任何任务都算有单」）")
has(import_strategy, "if (!chamber.isAutoCraftingEnabled() && !chamber.hasResidualInputReclaim()) {",
    "锚点③: 收回侧（输入总线）也要求「有人为本产线下单」，唯一例外是任务刚结束的一次性残留令牌")
has(chamber, "public boolean claimResidualInputReclaim() {",
    "锚点④: 一次性残留令牌仍是「取消立刻回收残留」的唯一通道（本条闸门不会误杀它）")


def conservative_goals(related, inputs, start):
    """复刻 conservativeGoalItems：相关超集 − 输入类 − 起步原料。"""
    if related is None:
        return set()
    return set(related) - set(inputs) - set(start)


def has_goal(goals, tasks, suspended):
    """复刻 hasGoalTask：goals 为 None 时走保守回退。"""
    if goals is None:
        goals = conservative_goals(RELATED, INPUTS, START)
    if not goals:
        return False
    return any(t in goals and t not in suspended for t in tasks)


RELATED = {"create:golden_sheet", "create:cogwheel", "create:large_cogwheel",
           "minecraft:iron_nugget", "create:incomplete_precision_mechanism",
           "create:precision_mechanism"}
INPUTS = {"create:golden_sheet", "create:cogwheel", "create:large_cogwheel", "minecraft:iron_nugget"}
START = {"create:golden_sheet"}

CASES = [
    ("产物集合判不出来 + 别人在做金板（定量保持器）", None, ["create:golden_sheet"], set(), False),
    ("产物集合判不出来 + 别人在做齿轮", None, ["create:cogwheel"], set(), False),
    ("产物集合判不出来 + 有人为本产线下单（精密构件）", None, ["create:precision_mechanism"], set(), True),
    ("产物集合判不出来 + 有人下单但在制件（未完成精密构件）", None,
     ["create:incomplete_precision_mechanism"], set(), True),
    ("正常产物集合 + 别人在做金板", {"create:precision_mechanism"}, ["create:golden_sheet"], set(), False),
    ("正常产物集合 + 下单但已被挂起", {"create:precision_mechanism"},
     ["create:precision_mechanism"], {"create:precision_mechanism"}, False),
    ("正常产物集合 + 无任何任务", {"create:precision_mechanism"}, [], set(), False),
]
print("  反例表（hasGoalTask = 能不能打开「有人为本产线下单」这道闸门）")
print("  %-52s %-8s" % ("情形", "闸门"))
for label, goals, tasks, suspended, expect in CASES:
    got = has_goal(goals, tasks, suspended)
    print("  %-52s %-8s %s" % (label, got, "OK" if got == expect else "MISMATCH"))
check("③a 穷举能打开闸门的条件：只有「任务目标落在 <b>产物 / 在制件</b> 且未被挂起」才为真",
      all(has_goal(g, t, s) == e for _l, g, t, s, e in CASES))
check("③b 无订单时全部为假：判不出来也绝不放宽（旧实现在 goals == None 时返回 true ⇒ 金板任务就能开闸）",
      has_goal(None, ["create:golden_sheet"], set()) is False
      and has_goal(None, ["create:cogwheel"], set()) is False)
check("③c 保守产物集合的算术：相关超集 − 输入类 − 起步原料 = {未完成精密构件, 精密构件}",
      conservative_goals(RELATED, INPUTS, START) ==
      {"create:incomplete_precision_mechanism", "create:precision_mechanism"})

# ============================================================ ④ 断缝必挂起 + 横幅
section("④ 拔线缆后必须挂起并弹横幅：两条离线路径 + 分类翻转也要能确认")

has(watchdog, "private static boolean servableByRecipeType(",
    "锚点①: 「本网络里有没有一台仓能承担这一步」只有一份实现（与「更换机器」候选同一判据）")
has(watchdog, "result.add(new SyncAssemblyAlertsPacket.OfflineStep(step, RecipeTypeNames.of(unassignedType),",
    "锚点②: 未指派机器的步骤也参与掉线判定（旧实现整条跳过 ⇒ 断缝判不出来 ⇒ 没有横幅）")
has(watchdog, "private int offlineStrikes;",
    "锚点③: 连续确认计数（不再要求两次报的是「同一批」步骤）")
has(watchdog, "record.offlineStrikes >= OFFLINE_CONFIRM_STRIKES",
    "锚点④: 确认判据 = 「上一次复查也观察到离线」")
has(watchdog, "sendBanner(level, record);",
    "锚点⑤: 挂起时必发一次横幅（唯一发送点，本轮未新增任何旁路）")
has(watchdog, "record.reason = Reason.EXECUTOR_OFFLINE;",
    "锚点⑥: 断缝 → EXECUTOR_OFFLINE")
check("锚点⑦: WAIT 档只压制「缺料」，不会误杀断缝（断缝归 EXECUTOR_OFFLINE）",
      "record.reason == Reason.MISSING_MATERIAL" in watchdog
      and "Reason.EXECUTOR_OFFLINE" in watchdog)


def offline_steps(units, chambers):
    """复刻 offlineSteps：units = [(step, machinePos|None, recipeType)]；返回离线步骤数。"""
    result = 0
    types = {c["recipeType"] for c in chambers}
    for _step, pos, recipe_type in units:
        if pos is None:
            if not recipe_type or recipe_type in types:
                continue
            result += 1
        elif pos in {c["pos"] for c in chambers}:
            continue
        else:
            result += 1
    return result


A = (1, "(0,0,1)", "create:pressing")
B = (2, None, "create:pressing")
B_UNKNOWN = (2, None, None)
print("  反例表（断缝 → 离线集合是否非空 = 是否挂起 + 弹横幅）")
print("  %-56s %-8s" % ("情形", "离线步数"))
CASES_OFF = [
    ("两台仓都在线（正常）", [A, B], [{"pos": "(0,0,1)", "recipeType": "create:pressing"}], 0),
    ("已指派的那台被拆 / 拔线（另一步未指派且无人可承担 ⇒ 2）", [A, B], [], 2),
    ("已指派的那台被拆 / 拔线（只有这一步）", [A], [], 1),
    ("未指派机器 + 网络里没有同配方类型的仓（旧实现：整条跳过）", [B],
     [{"pos": "(9,9,9)", "recipeType": "create:filling"}], 1),
    ("未指派机器 + 网络里有能承担它的仓（正常运行）", [B],
     [{"pos": "(9,9,9)", "recipeType": "create:pressing"}], 0),
    ("未指派机器 + 配方类型未知（判不出来就不打草惊蛇）", [B_UNKNOWN], [], 0),
]
for label, units, chambers, expect in CASES_OFF:
    got = offline_steps(units, chambers)
    print("  %-56s %-8d %s" % (label, got, "OK" if got == expect else "MISMATCH"))
check("④a 断缝的两种形态都能判出离线：已指派机器消失 / 未指派步没有任何在线仓能承担",
      all(offline_steps(u, c) == e for _l, u, c, e in CASES_OFF))


def confirm_sequence(observations):
    """复刻 classifySequence 的确认判据。

    observations ∈ {'offline', 'clean_seq', 'generic'}（每 20 tick 一次扫描）。
    'generic' = 任务这一轮被归到 RS 原版分类（不清空计数、也不参与序列判定）。
    """
    strikes = 0
    out = []
    for obs in observations:
        if obs == "generic":
            out.append(False)
            continue
        if obs == "clean_seq":
            strikes = 0
            out.append(False)
            continue
        confirmed = strikes >= 1
        strikes = min(strikes + 1, 1000000)
        out.append(confirmed)
    return out


seq_flip = confirm_sequence(["offline", "generic", "offline"])
seq_plain = confirm_sequence(["offline", "offline"])
seq_heal = confirm_sequence(["offline", "clean_seq", "offline"])
print("  反例表（连续确认：哪一扫描确认了离线）")
print("    offline,offline          -> %s" % seq_plain)
print("    offline,generic,offline  -> %s（旧判据在这一串上永远确认不了）" % seq_flip)
print("    offline,clean,offline    -> %s（真实抖动仍不算断缝）" % seq_heal)
check("④b 连续两次离线（中间夹一次 RS 原版分类）也能确认 ⇒ 真实断缝一定会走到 suspend + sendBanner",
      seq_plain[-1] is True and seq_flip[-1] is True)
check("④c 中途恢复过一次（clean 序列扫描）⇒ 计数归零，瞬时抖动不会被当成断缝",
      not any(seq_heal) and seq_heal == [False, False, False])

problem = re.compile(r"anyChamberInFlight\(chambers\)|chamberInFlight")
check("④d 断缝不会被「缺料可推进」短路：offline 确认在算 needed / missing 之前就 return（源码顺序）",
      chamber.count("chamberInFlight") == 0 and problem.search(watchdog) is not None
      and watchdog.index("if (offlineConfirmed) {") < watchdog.index("final List<ItemStack> needed"))

# ============================================================ ⑤ 数量快速增减
section("⑤ 数量快速增减无条件禁止：任何情形下都不出现「整批拉出 → 整批退回」")

check("锚点①: 概率性配方的自动合成请求量也经 allowedConcurrentUnits（不再在「读不到剩余件数」时整批 64）"
      "——第 29 轮新增第三档后，这条旧口径整段保留在「未启用第三档」分支里，表达式一字未改",
      "final long requestBatch = pipelineUsesChance()" in chamber
      and ": Math.max(1L, Math.min(BUS_AUTOCRAFT_BATCH, allowedConcurrentUnits())))" in chamber)
check("锚点②: 确定性配方仍然一次一份（business as before，本轮未改）"
      "——第 29 轮的三元式在未启用第三档（machineRefill=false）时仍然落到这个 1L 分支",
      ": (machineRefill ? machineRefillBatch : 1L);" in chamber)
has(chamber, "final long machineRefillBatch = machineRefill ? machineSupplyBatch() : 0L;",
    "锚点②b: 第三档（默认关）的分量在未启用时恒为 0 ⇒ 上面两条旧口径的行为逐字不变；"
    "第三档自己的请求量另有上限，见 tools/selfcheck_round29_refill_machine_mode.py")
has(chamber, "clampStartIngredientTargets(itemTargets);",
    "锚点③: 中（起）步原料的在途量被名额夹住 ⇒ 不出现「先抽一批再退回」")

print("  反例表（单种原料的在途 / 仓内量上界 = 当前真正需要的件数）")
print("  %-34s %-10s %-10s %-10s" % ("情形", "可读剩余", "在制", "在途上界"))
CASES_SMOOTH = [
    ("N=1 确定性，读不到", 1, False, 0, 1),
    ("N=1 确定性，读得到", 1, True, 0, 1),
    ("N=64 确定性", 64, True, 0, 64),
    ("N=64 概率性（请求批）", 64, True, 0, 64),
    ("N=1 概率性，读不到（旧：请求 64）", 1, False, 0, 1),
]
for label, remaining, readable, inflight, expect in CASES_SMOOTH:
    bound = allowed_units(remaining, readable, inflight)
    print("  %-34s %-10s %-10d %-10d" % (label, bool(readable), inflight, bound))
    check("⑤ %s ⇒ 在途上界 = %d" % (label, expect), bound == expect)
check("⑤ 终判：任何情形下「在途 / 仓内单种原料」≤ 当前需要的件数，且读不到订单时取小不取大",
      all(allowed_units(r, rd, f) == e for _l, r, rd, f, e in CASES_SMOOTH)
      and allowed_units(1, False, 5) == 5)  # 已在制的 5 件不因「读不到」而被高估，也不会被低估到 0

# ============================================================ ⑥ 本轮：金板不再触发精密构件（最后一道断言）
section("⑥ 用户第 ⑤ 条加固：投入物 / 起步原料在 hasGoalTask 里被最后断言一次（与缓存来源无关）")

has(chamber, "if (isStartIngredient(item.item()) || isInputMaterial(item.item())) {",
    "锚点: hasGoalTask 逐条断言「投入物 / 起步原料绝不是有人为本产线下单」")
check("锚点: 该断言在「任务目标落在 goals 里」之后、返回 true 之前（位置正确才拦得住）",
      chamber.index("if (isStartIngredient(item.item()) || isInputMaterial(item.item())) {")
      < chamber.index("return true; // 有人在为本产线的产物 / 在制件下单"))


def has_goal_v2(goals, tasks, suspended):
    """复刻本轮加固后的 hasGoalTask：goals 之外，投入物 / 起步原料一律不算「有人为本产线下单」。"""
    if goals is None:
        goals = conservative_goals(RELATED, INPUTS, START)
    if not goals:
        return False
    for t in tasks:
        if t in goals and t not in suspended and t not in INPUTS and t not in START:
            return True
    return False


CASES_GOLD = [
    ("定量保持器维持金板（金板=起步原料）", {"create:golden_sheet", "create:precision_mechanism"},
     ["create:golden_sheet"], set(), False),
    ("别人在做齿轮（齿轮=步骤投入物）", {"create:cogwheel", "create:precision_mechanism"},
     ["create:cogwheel"], set(), False),
    ("有人下单 64 个精密构件", {"create:precision_mechanism"},
     ["create:precision_mechanism"], set(), True),
    ("有人为在制件下单（未完成精密构件）", {"create:incomplete_precision_mechanism"},
     ["create:incomplete_precision_mechanism"], set(), True),
    ("有人下单但被挂起", {"create:precision_mechanism"},
     ["create:precision_mechanism"], {"create:precision_mechanism"}, False),
]
print("  反例表（加固后：金板 / 齿轮任务还能不能开闸）")
print("  %-44s %-8s" % ("情形", "闸门"))
for label, goals, tasks, suspended, expect in CASES_GOLD:
    got = has_goal_v2(goals, tasks, suspended)
    print("  %-44s %-8s %s" % (label, got, "OK" if got == expect else "MISMATCH"))
check("⑥a 即使 goals（保守回退）把起步原料 / 投入物漏进来，也不再有「别人维持金板 ⇒ 本产线开工」",
      all(has_goal_v2(g, t, s) == e for _l, g, t, s, e in CASES_GOLD))

# ============================================================ ⑦ 本轮：补合成不再被「在制名额夹量」吃掉
section("⑦ 用户第 ④ 条：缺料时 RS 补合成必须被调用（与备料夹量解耦）")

check("锚点①: 「补合成」目标表在 clampStartIngredientTargets <b>之前</b>取快照",
      chamber.index("final Map<Item, Long> autocraftItemTargets = new LinkedHashMap<>(itemTargets);")
      < chamber.index("clampStartIngredientTargets(itemTargets);"))
check("锚点②: 请求用夹量前的目标表（因此「已有一件在制」时也不会漏掉补合成）",
      "requestMissingViaAutocraft(network, autocraftItemTargets, autocraftFluidTargets);" in chamber)
check("锚点③: 空的判据同时考虑 autocraft 表 ⇒ 备料全被夹掉时仍会走到补合成",
      "&& autocraftItemTargets.isEmpty() && autocraftFluidTargets.isEmpty()" in chamber)
check("锚点④: 缺口 = 目标量 −（网络 + 本仓 [+ 机器侧]），不会把「仓里已有的一份」当缺口重复请求",
      "final long have = storage.get(resource) + storedItemAmount(entry.getKey());" in chamber
      and "+ machineHeldFluid(entry.getKey());" in chamber)

print("  反例表（补合成是否发出：target / 网络 / 本仓 ⇒ deficit>0 才请求）")


def autocraft_deficit(target, net, chamber_have, machine_have=0):
    return target - (net + chamber_have + machine_have) > 0


CASES_AUTO = [
    ("金板缺料：target=1 网络=0 本仓=0", 1, 0, 0, 0, True),
    ("金板：本仓已备着一份（旧：每 40 tick 多请求一份）", 1, 0, 1, 0, False),
    ("熔岩：target=500 且注液机里已压着 500 mB", 500, 0, 0, 500, False),
    ("熔岩：target=500 网络=200 机器=0（缺 300）", 500, 200, 0, 0, True),
]
for label, t, net, ch, mh, expect in CASES_AUTO:
    got = autocraft_deficit(t, net, ch, mh)
    print("  %-50s %-6s %s" % (label, got, "OK" if got == expect else "MISMATCH"))
check("⑦a 「有缺口才请求、刚好够就不请求」在四条路径上都成立（不整批拉出、不重复请求）",
      all(autocraft_deficit(t, n, c, m) == e for _l, t, n, c, m, e in CASES_AUTO))

# ============================================================ ⑧ 在制名额感知结果池概率（2026-10-06）
section("⑧ 区分必得（100%）与概率（<100%）配方：在制名额 = 订单剩余 − 跨执行舱在制件（概率配方按 ceil(R/p) 放大）")

# 说明：① 的锚点⑥⑦ 断言的那条旧式（allowedConcurrentUnits() − inFlightUnitCount()）现在只作为
# 「判不出这份起步原料属于哪条配方」时的回退口径保留（见 startCapacityFor / unitCapacityLeftFor），
# 因此锚点仍然成立；真正决定「能不能再开一件」的判据已细化为下面这几个方法。
has(chamber, "private boolean guaranteedResult(final String recipeId) {",
    "锚点①: 「主产物是否必得」只有一份判据（复用 SequencedRecipeProbe.splitResultPool，不另写轮盘口径）")
has(chamber, "return SequencedRecipeProbe.splitResultPool(recipe.resultPool);",
    "锚点②: 判据直接吃 Create 的 resultPool（经探针的归一换算），不猜、不看别的字段")
has(chamber, "private long startCapacityForRecipe(final String recipeId) {",
    "锚点③: 「某条配方还能再开几件」只有一份实现（每 tick 每配方至多真算一次）")
has(chamber, "final long remaining = remainingOrderUnitsFor(recipeId);",
    "锚点④: 订单剩余量按配方取（一个仓同时挂两条配方时，整仓最大值会把另一条带偏）")
has(chamber, "allowed = (long) Math.ceil(remaining / (double) p);",
    "锚点⑤: 非必得配方的放大倍数 = ceil(R / p)（推导：X=第 R 次成功的投料次数，E[X] = R/p）")
has(chamber, "capacity = allowed - inFlight;",
    "锚点⑥: 名额 = 允许同时在制数 − 在制件数（必得配方时严格 = 订单剩余 − 已在制）")
has(chamber, "private long inFlightUnitsForRecipe(final String recipeId) {",
    "锚点⑦: 在制件计数必须跨执行舱（件推到别的仓的机器上后，本仓工位是空的）")
has(chamber, "chambersProbeCache = chambersOf(getNode().getNetworkOrNull());",
    "锚点⑧: 跨舱计数用既有的 chambersOf(Network)（只读，不产生任何搬运）")
has(chamber, "if (info.isInput() && isStartIngredientCategory(info) && !unitCapacityLeftFor(info)) {",
    "锚点⑨: 推料侧的起步原料类别也过同一个名额闸门（否则仓里那一份起步原料仍会被推出去多开一件）")


def start_capacity(remaining, guaranteed, p, in_flight):
    """复刻 startCapacityForRecipe（2026-10-06 本轮修正后）。

    remaining < 0（判不出来）→ 一件在制都没有时允许开一件，否则 0（既有保守口径）。
    remaining == 0（订单已交付满）→ 0：一件都不许再开（**本轮修正**：旧写法把 0 也当成
      「判不出来」，于是每单在空管线时又多开一件）。
    必得（100%）→ 名额 = R − in_flight（严格，有多少订单就发多少件）。
    非必得 → 名额 = ceil(R / p) − in_flight（p 判不出来时不放大，退回 R）。
    """
    if remaining < 0:
        return 1 if in_flight <= 0 else 0
    allowed = remaining
    if not guaranteed and isinstance(p, float) and 0.0 < p < 1.0:
        import math
        allowed = int(math.ceil(remaining / p))
    return max(0, allowed - in_flight)


print("  反例表（名额 = 还能再开几件新在制件；in_flight 为全网络同配方的在制件数）")
print("  %-52s %-10s %-8s" % ("情形", "名额", "期望"))
CASES_CAP = [
    # 订单剩余量判不出来（短命任务 / RS 状态瞬时为空）
    ("轨道：剩余量判不出来、一件都没开", -1, True, None, 0, 1),
    ("轨道：剩余量判不出来、别的执行舱已有 1 件在制", -1, True, None, 1, 0),
    # 列车轨道 = 必得（results 只有一项、无 chance），订单 1 条
    ("轨道：下单 1、别的执行舱已有 1 件在制（旧：再开 1 件 ⇒ 出 2 条）", 1, True, 1.0, 1, 0),
    ("轨道：下单 1、一件都没开", 1, True, 1.0, 0, 1),
    ("轨道：下单 64、已开 1 件（不收紧）", 64, True, 1.0, 1, 63),
    # 精密构件 = 非必得（120 : 8 : 8 : 5 : 3 : 2 : 2 : 1 : 1 ⇒ p = 120/150 = 0.8）
    ("精密构件：下单 1、p=0.8、一件都没开（动态多发）", 1, False, 0.8, 0, 2),
    ("精密构件：下单 64、p=0.8（既有 64 ⇒ 只放宽，绝不收紧）", 64, False, 0.8, 0, 80),
    ("精密构件：下单 64、p=0.8、已在制 80 件", 64, False, 0.8, 80, 0),
    ("精密构件：p 判不出来（配方缺失）⇒ 不放大，退回既有口径", 64, False, -1.0, 0, 64),
]
for label, remaining, guaranteed, p, in_flight, expect in CASES_CAP:
    got = start_capacity(remaining, guaranteed, p, in_flight)
    print("  %-52s %-10s %-8s %s" % (label, got, expect, "OK" if got == expect else "MISMATCH"))


check("⑧a 必得（100%）配方：下单 1 条轨道、件已推到别的执行舱时名额 = 0（不再多开一件）",
      start_capacity(1, True, 1.0, 1) == 0 and start_capacity(1, True, 1.0, 0) == 1)
check("⑧b 必得（100%）配方：有名额等价于「订单剩余 − 已在制」，下单 64 时仍可并行（不收紧）",
      start_capacity(64, True, 1.0, 1) == 63)
check("⑧c 非必得（<100%）配方：下单 1 个精密构件（p=0.8）允许 2 件在制（按 E[X]=R/p 动态多发）",
      start_capacity(1, False, 0.8, 0) == 2)
check("⑧d 非必得（<100%）配方：下单 64 个精密构件允许 80 件在制（≥ 既有 64 ⇒ 绝不卡死）",
      start_capacity(64, False, 0.8, 0) == 80 and start_capacity(64, False, 0.8, 0) >= 64)
check("⑧e 概率 p 判不出来时<b>不放大</b>（退回既有口径 R）：判不出来绝不收紧、也绝不放宽",
      start_capacity(64, False, -1.0, 0) == 64)
check("⑧f 订单剩余量判不出来时沿用保守口径（无在制允许 1 件 / 已有在制 0 件）",
      start_capacity(-1, True, None, 0) == 1 and start_capacity(-1, True, None, 1) == 0)
check("⑧g【2026-10-06 新增】订单剩余量 = 0（已交付满）⇒ 名额 0："
      "空管线时也绝不再放一件（旧写法把 0 并进「判不出来」分支 ⇒ 每单多开一件）",
      start_capacity(0, True, None, 0) == 0
      and start_capacity(0, False, 0.8, 0) == 0)

# ============================================================ 结果
print()
print("=" * 84)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
sys.exit(0)
