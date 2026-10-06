# -*- coding: utf-8 -*-
"""「按步归属」（本仓负责的步）+ 拒绝抑制 + 绑定快照日志 自检（源码锚点 + 等价模型推演）。

用法：python tools/selfcheck_assembly_step_ownership.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要这个脚本（本轮用户实测 bug，无法在本地把游戏跑起来验证）：
  1. **一台机器承担同类型的多个步骤**（坚固板 create:sturdy_sheet 的第 2、3 步都是冲压）时，
     玩家在那个机器位置上**只放一份该类型的单元样板**是合法的；旧实现只认样板里记的那一个步序，
     于是「第 3 步做出来的件」被判成「不是我的」→ 先被输入总线**过早收回**网络，再没有任何机器肯接手
     → 任务永远卡死（用户原话：「还是会过早地回流，或者错误地回流」）。
  2. **判定只按步骤**：该件的步序 s、本仓负责的步 m、配方总步数 T、以及「下一步由谁负责」；
     **不做任何方块位置比较**（用户硬要求）。位置只用于「这一步是不是被别的仓显式认领了」的排除。
  3. 被本仓按步**拒绝**过的件要有短冷却（打断「收回 → 立刻再试 → 再拒」的 1 Hz 空转与刷屏），
     而**正常流绝不被抑制**。
  4. 任务开始时要有一次性的「步骤 → 负责机器」快照，并在「这一步没有任何在线机器认领」时显式 WARN。

做法：① 源码锚点确认结构与调用点没被改坏；② 用等价 Python 模型复刻
`SequenceExecutionChamberBlockEntity#computeOwnedSteps` / `#stepVerdictFor` / `#suppressStepRefusal`
与 `RsccAssemblyDebug#reject`，把「一机多步 / 多仓不越权 / 同集群 / 抑制 / 日志合并」一次跑完。
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


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


def section(title):
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


# ==================== 1. 源码锚点 ====================
section("1) 源码锚点：按步归属 + 拒绝抑制 + 绑定快照")

chamber = read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))
watchdog = read(os.path.join("support", "AssemblyWatchdog.java"))
debug = read(os.path.join("support", "RsccAssemblyDebug.java"))
cluster = read(os.path.join("support", "RsccMachineCluster.java"))

has(chamber, "private Map<String, Set<Integer>> ownedStepsCache;",
    "锚点: 本仓负责的步是一份缓存表（配方 id → 步序集合）")
has(chamber, "private Map<String, Set<Integer>> computeOwnedSteps(final Level level) {",
    "锚点: 按步归属本体只有一份（computeOwnedSteps）")
has(chamber, "final Map<String, Set<Integer>> owned = ownedSteps();",
    "锚点: stepVerdictFor 走「本仓负责的步」而不是「样板自己记的步」")
has(chamber, "recipeType.equals(SequencedRecipeProbe.recipeTypeId(sequence.get(step).getRecipe()))",
    "锚点: 同类型的多个步骤都归本仓（配方类型比对，与方块位置无关）")
has(chamber, "if (claimedElsewhere.contains(recipeId + \"#\" + step)) {",
    "锚点: 被别的（非同集群）仓显式认领的步不越权")
has(chamber, "private boolean isOwnedStep(final String recipeId, final int step) {",
    "锚点: 认领侧（matchUnit）用同一份归属兜底")
has(chamber, "private boolean anyBusOwnsResource(final ItemStack probe, final ItemResource resource) {",
    "锚点: 备料侧「拉进来必须推得出去」（没有总线选这一步就不吸进仓里）")
has(chamber, "private void suppressStepRefusal(final ItemResource resource, final long now) {",
    "锚点: 按步拒绝的抑制（5 秒冷却）")
has(chamber, "STEP_REFUSAL_COOLDOWN_TICKS = 100",
    "锚点: 抑制时长 = 100 tick（5 秒）")
check("锚点: 「该不该收 / 该不该推」的判据仍然只有一份（judgeStep）",
      chamber.count("SequenceMaterialGuard.judgeStep(") == 1,
      "found=%d" % chamber.count("SequenceMaterialGuard.judgeStep("))
def method_body(text, signature):
    """取某个方法体（signature 起、到下一个缩进 4 空格的右花括号止）。"""
    return text.split(signature)[1].split("\n    }")[0]


reclaim_body = method_body(chamber, "public boolean isTransitionReclaimAllowed(")
push_body = method_body(chamber, "public boolean isNextForMyMachines(")
assign_body = method_body(
    chamber, "private Map<String, Set<Integer>> patternAssignedSteps(final Level level) {")
check("锚点: 「该不该收 / 该不该推」的<b>单件判定</b>里没有任何「方块坐标相等」式比较"
      "（仍只按「配方 + 步序」）；坐标只作为「总样板把某一步显式指派给本仓」这一条<b>来源</b>出现",
      "machinePos" not in reclaim_body and "machinePos" not in push_body
      and "machinePos" in assign_body
      and chamber.count("SequenceMaterialGuard.judgeStep(") == 1,
      "found code that compares block positions")
has(chamber, "public String debugOwnedSteps() {",
    "锚点: 只读诊断出口（本仓到底认了哪几步）")
has(chamber, "RsccAssemblyDebug.warn(\"unowned@\"",
    "锚点: 「这一步没有任何机器认领」显式 WARN（一次性）")

has(watchdog, "private static void logBindingSnapshot(final ServerLevel level,",
    "锚点: 任务开始时一次性「步骤 → 负责机器」快照")
has(watchdog, "\" chamberExists=\" + (chamber != null)",
    "锚点: 快照逐行输出 machine / chamberExists / recipeType / chamberSteps / nextStepOwner")
has(watchdog, "\" nextStepOwner=\" + nextStepOwnerOf(",
    "锚点: 快照带「下一步由谁负责」")
has(watchdog, "RsccAssemblyDebug.warn(\"bindingorphan@\" + taskId + \"#\" + step,",
    "锚点: 指向不存在机器的步显式 WARN 一次")
has(watchdog, "private static int totalStepsOf(final ServerLevel level,",
    "锚点: 快照能报出配方单循环步数（不靠猜）")

has(debug, "public static void reject(final String key, final String body) {",
    "锚点: 同因 reject 合并日志（首次立即 + 每 5 秒汇总）")
has(debug, "REJECT_MERGE_NANOS = 5_000_000_000L",
    "锚点: reject 合并窗口 = 5 秒")
has(debug, "public static void warn(final String key, final String body) {",
    "锚点: 一次性 WARN（同一处只打一条）")

has(cluster, "public static List<BlockPos> clusterMembers(final Level level, final BlockPos pos) {",
    "锚点: 同集群成员只读查询（判定「同一台机器」用）")

# ==================== 2. 等价模型 ====================
section("2) 等价模型：坚固板（T=3：0 注液 / 1 冲压 / 2 冲压）")

TOTAL = 3
RECIPE = "create:sturdy_sheet"
SEQ_TYPES = ["create:filling", "create:pressing", "create:pressing"]


def compute_owned(units, claimed_elsewhere=None, same_cluster=(), total=TOTAL, types=SEQ_TYPES):
    """复刻 computeOwnedSteps：① 样板自己记的步（按 T 归一）∪ ② 同类型的所有步（别人显式认领的除外）。"""
    claimed_elsewhere = claimed_elsewhere or {}
    owned = {}
    for unit in units:
        recipe = unit.get("recipe", "")
        if not recipe:
            continue
        steps = owned.setdefault(recipe, set())
        if unit.get("step", -1) >= 0:
            steps.add(unit["step"] % total)
        rtype = unit.get("recipeType", "")
        if not rtype:
            continue
        for step, step_type in enumerate(types):
            if step_type != rtype:
                continue
            claimee = claimed_elsewhere.get("%s#%d" % (recipe, step))
            if claimee is not None and claimee not in same_cluster:
                continue
            steps.add(step)
    return owned


def judge_step(item_step, machine_step, total=TOTAL):
    """复刻 SequenceMaterialGuard#judgeStep（同一条配方）。"""
    if machine_step < 0:
        return "UNKNOWN"
    if item_step is None:
        return "NOT_MINE"
    if total <= 0:
        return "UNKNOWN"
    return "NEXT_FOR_MACHINE" if (item_step % total) == machine_step else "NOT_MINE"


def verdict(owned, recipe, item_step, total=TOTAL):
    """复刻 stepVerdictFor：任一步说「下一步轮到本仓」就是它；判不出来则保守。"""
    if not owned:
        return "UNKNOWN"
    unknown = False
    for machine_recipe, steps in owned.items():
        for step in sorted(steps):
            v = judge_step(item_step, step if machine_recipe == recipe else -1, total)
            if v == "NEXT_FOR_MACHINE":
                return "NEXT_FOR_MACHINE"
            if v == "UNKNOWN":
                unknown = True
    return "UNKNOWN" if unknown else "NOT_MINE"


def reclaimable(owned, item_step, recipe=RECIPE):
    """isTransitionReclaimAllowed 的结论面（不带进度组件的件不由本判定负责 → False）。"""
    if item_step is None:
        return False
    return verdict(owned, recipe, item_step) == "NOT_MINE"


def pushable(owned, item_step, recipe=RECIPE):
    """isNextForMyMachines 的结论面（不带进度组件的件一律 True，交由类别判据）。"""
    if item_step is None:
        return True
    return verdict(owned, recipe, item_step) == "NEXT_FOR_MACHINE"


def owner_detail(owned, recipe, step, claimed_elsewhere):
    """复刻 stepOwnerDetail：MINE / ELSEWHERE / NOBODY。"""
    if step in owned.get(recipe, set()):
        return "MINE"
    if claimed_elsewhere.get("%s#%d" % (recipe, step)) is not None:
        return "ELSEWHERE"
    return "NOBODY"


# ---- 用户实测形态：冲压仓里只放了一份「冲压」单元样板（步序 1），但配方的第 2、3 步都是冲压 ----
PRESS_ONLY_STEP1 = [{"recipe": RECIPE, "recipeType": "create:pressing", "step": 1}]
owned_press = compute_owned(PRESS_ONLY_STEP1)
check("一机多步：冲压仓只放一份「步序 1」的样板，也认下同类型的第 2、3 步（即 step=1 与 step=2）",
      owned_press.get(RECIPE) == {1, 2}, str(owned_press))
check("第 1 步（注液）做完的件（s=1）→ 冲压仓「下一步就是我的」→ 不许收回、可以推给机器",
      reclaimable(owned_press, 1) is False and pushable(owned_press, 1) is True)
check("第 2 步（冲压）做完的件（s=2）→ 仍是「下一步就是我的」→ 绝不被过早收回（用户实测根因）",
      reclaimable(owned_press, 2) is False and pushable(owned_press, 2) is True)
check("旧实现（只认样板记的那一步）：s=2 的件被判「不是我的」→ 被收回 → 任务卡死（把根因固化成断言）",
      reclaimable({RECIPE: {1}}, 2) is True and pushable({RECIPE: {1}}, 2) is False)
check("末步成品（不带进度组件）不由按步判定负责 → 走「非输入类」的正向回收",
      verdict(owned_press, RECIPE, None) == "NOT_MINE")
check("判不出来（本仓没有任何可用样板）时两侧都停手（不推、也不收）",
      pushable({}, 1) is False and reclaimable({}, 1) is False)
check("配方总步数查不到（T<=0）时两侧都停手",
      judge_step(1, 1, 0) == "UNKNOWN")

# ---- 两台同类型机器各自负责不同步：互不越权 ----
PRESS_A_STEP1 = [{"recipe": RECIPE, "recipeType": "create:pressing", "step": 1}]
PRESS_B_STEP2 = [{"recipe": RECIPE, "recipeType": "create:pressing", "step": 2}]
claimed = {"%s#2" % RECIPE: "chamberB", "%s#1" % RECIPE: "chamberA"}
owned_a = compute_owned(PRESS_A_STEP1, claimed_elsewhere=claimed)
owned_b = compute_owned(PRESS_B_STEP2, claimed_elsewhere=claimed)
check("多仓不越权：A 仓（放步序 1）只认第 1 步（第 2 步被 B 显式认领）",
      owned_a.get(RECIPE) == {1}, str(owned_a))
check("多仓不越权：B 仓（放步序 2）只认第 2 步",
      owned_b.get(RECIPE) == {2}, str(owned_b))
check("多仓不越权：s=2 的件在 A 仓「不是我的」（交给 B），在 B 仓「就是我的」（不许收回）",
      reclaimable(owned_a, 2) is True and reclaimable(owned_b, 2) is False)
check("同集群成员不互相排除（共用同一份内部存储 = 等效同一台机器）",
      compute_owned(PRESS_A_STEP1, claimed_elsewhere=claimed,
                    same_cluster=("chamberB",)).get(RECIPE) == {1, 2})

# ---- 注液仓（第 0 步）不该认下冲压的件 ----
# 别的仓只显式认领了第 1 步（用户现场：装配样板里第 3 步的机器坐标是旧的 / 没有仓放着那一步的样板）
claimed_fill = {"%s#1" % RECIPE: "chamberA"}
owned_fill = compute_owned([{"recipe": RECIPE, "recipeType": "create:filling", "step": 0}],
                           claimed_elsewhere=claimed_fill)
check("注液仓只认第 0 步（同类型只有一步）→ s=2 的件不归它",
      owned_fill.get(RECIPE) == {0}, str(owned_fill))
check("s=2 的件在注液仓：既不是我的、也不是别人认领的 → NOBODY（真正的「没有任何机器认领」）",
      owner_detail(owned_fill, RECIPE, 2, claimed_fill) == "NOBODY")
check("s=1 的件在注液仓：别的仓（冲压）显式认领了第 1 步 → ELSEWHERE（正常换机器，不是异常）",
      owner_detail(owned_fill, RECIPE, 1, claimed_fill) == "ELSEWHERE")
check("本仓认下的步报 MINE（快照里的 chamberSteps 就是它）",
      owner_detail(owned_fill, RECIPE, 0, claimed_fill) == "MINE")

# ---- loops > 1：归属是「单循环」域，因此下一循环里的同一步照样归它 ----
check("loops > 1：s=2+3=5（下一循环的第 2 步）仍归同一个冲压仓（不许收回、可以推）",
      reclaimable(owned_press, 5) is False and pushable(owned_press, 5) is True)
check("loops > 1：末步成品（s=3，成品无进度组件）不由按步判定负责",
      verdict(owned_press, RECIPE, None) == "NOT_MINE")

# ---- 下一步由谁负责（快照 nextStepOwner）----
def next_owner(units, chambers, step, total=TOTAL, loops=1):
    """复刻 AssemblyWatchdog#nextStepOwnerOf。"""
    if not units or step < 0:
        return "none"
    nxt = step + 1
    if nxt >= total and loops <= 1:
        return "none(成品)"
    index = nxt % total if total > 0 else nxt
    if index < 0 or index >= len(units):
        return "none"
    pos = units[index].get("machinePos")
    if pos is None:
        return "unassigned"
    return pos if pos in chambers else pos + "(offline)"


UNITS = [{"machinePos": "A"}, {"machinePos": "A"}, {"machinePos": "A"}]
check("快照：第 0 步做完 → 下一步由第 1 步的机器负责（同一台 → 不许收回）",
      next_owner(UNITS, {"A"}, 0) == "A")
check("快照：第 1 步做完 → 下一步仍由同一台负责（这正是用户看到的「过早回流」现场）",
      next_owner(UNITS, {"A"}, 1) == "A")
check("快照：末步做完 = 成品，没有下一步",
      next_owner(UNITS, {"A"}, 2) == "none(成品)")
check("快照：某步的机器不在网络里 → 标 offline（装配样板里的旧坐标就是这种）",
      next_owner([{"machinePos": "A"}, {"machinePos": "GHOST"}, {"machinePos": "A"}],
                 {"A"}, 0) == "GHOST(offline)")

# ==================== 3. 拒绝抑制 ====================
section("3) 拒绝抑制：只压「拒绝过的件」，正常流不受影响")

COOLDOWN = 100


def refuse(suppressed, resource, now):
    """复刻 pullItem 里「按步拒绝」的那条路径：记冷却并返回（不搬运）。"""
    suppressed[resource] = now + COOLDOWN
    return False


def try_pull(suppressed, resource, now, wants_it):
    """复刻 pullItem 的开头：冷却未过 → 直接跳过（不搬运、不抱怨）。"""
    until = suppressed.get(resource)
    if until is not None and now < until:
        return "suppressed"
    return "pulled" if wants_it else refuse(suppressed, resource, now)


suppressed = {}
check("抑制：刚被拒绝的件在本 tick 之后不再被重复判定（1 Hz 空转被打断）",
      try_pull(suppressed, "sheet@step2", 1000, False) is False
      and try_pull(suppressed, "sheet@step2", 1050, False) == "suppressed")
check("抑制：冷却到期后恢复判定（玩家改配置最多 5 秒自愈）",
      try_pull(suppressed, "sheet@step2", 1101, False) is False)
check("抑制：正常流（本仓真正需要的件）从不被抑制、照常拉进仓",
      try_pull(suppressed, "cogwheel", 1000, True) == "pulled")
check("抑制：不同件互不影响（键 = 资源，不是机器坐标）",
      try_pull(suppressed, "sheet@step1", 1001, True) == "pulled")

# ==================== 4. 同因 reject 合并 ====================
section("4) 同因 reject：首次立即 + 每 5 秒汇总（不再每秒刷屏）")

WINDOW = 5.0
logs = []


def reject_log(key, now, body="reject {... step=2} reason=step_not_mine"):
    """复刻 RsccAssemblyDebug#reject：首次立即一条，之后每满 5 秒汇总一条（含本 tick）。"""
    win = reject_log.windows.get(key)
    if win is None:
        reject_log.windows[key] = [1, now]
        logs.append(body)
        return
    win[0] += 1
    if now - win[1] >= WINDOW:
        logs.append("summary x%d" % win[0])
        win[0] = 0
        win[1] = now


reject_log.windows = {}
for i in range(60):                    # 60 秒、每秒一次同因拒绝
    reject_log("notmine@chamber#sheet", float(i))
check("日志：60 秒同因拒绝 → 首次 1 条 + 每 5 秒 1 条汇总（旧实现是 60 条）",
      2 <= len(logs) <= 14, "lines=%d" % len(logs))
check("日志：首次立即输出明细（保留可诊断性）", logs[0].startswith("reject {"))
check("日志：汇总行说得清「同一原因重复了多少次」",
      any(line.startswith("summary x") for line in logs))

# ==================== 结果 ====================
print()
print("=" * 72)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
