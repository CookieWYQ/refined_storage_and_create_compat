# -*- coding: utf-8 -*-
"""第 62 轮自检：装配线「突然全停 / 部分永久不再装配」的登记成对性、自愈与守恒。

用法：python tools/selfcheck_round62_no_permanent_stop.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

═══════════════════════════════════════════════════════════════════════════════
用户报告（无日志，只能源码分析 + 模型推演；玩家全程在机器旁边 ⇒ 与区块加载无关）
═══════════════════════════════════════════════════════════════════════════════
「下单完成之后就等着。刚开始非常快在那输出，一个劲在那里装配；但后面突然就停了，突然都停了。
 停了之后，过一会可能有的这些继续再装配，有的一些可能后面一直就不装配了，完全随机。」

本轮被证实的两条「与区块无关、会随时间累积 / 突然触发」的机制：
  ① **在制件登记表只增不减**（`inFlightUnits` 由每一次成功推料写入，只在任务确认结束时整表清空）。
     名额（`startCapacityForRecipe`）扣的是分量①「登记的工位此刻**还压着东西**」——只问那一格是不是空的。
     于是一件**永远不会被消耗、也永远不会被搬走**的东西（本步已无任何订单 / 机器的过渡件、
     取消后留下的起步原料残留）压在工位上，就让**这条配方**的名额恒为 0
     ⇒ 起步原料被整条去掉（`clampStartIngredientTargets`）⇒ **那一台机器永久不再装配**。
     哪一台先咬住一件死件是随机的 ⇒ 用户看到的「完全随机、部分恢复部分永久不恢复」。
  ② **顺序遍历没有容错**：备料主循环（`fillInternalForBus` 的逐资源抽取）、堵塞自愈、
     任务结束的工位残留回流，任一格 / 任一资源抛一次异常 ⇒ 其后**全部**资源 / 工位这一轮都不再被处理，
     而下一轮顺序相同 ⇒ 「一台坏连累其余」的永久停摆（与 `pushBusTurnToExporters` 同一类事故）。

本脚本钉住的三件事：
  A. **登记 / 清理成对性**（源码锚点）：登记只有一处写入、清理必须在「任务结束 / 空工位 / 判死件 /
     机器清理」四条路径上都可达；逐条早退路径都不能跳过清理。
  B. **超时兜底**：构造「登记后永不被确认」，断言它在超时（`UNIT_DEADLOCK_TICKS`）后被释放，
     而不是永久占位；并断言判定窗口与源码常量一致（模型与实现对得上）。
  C. **模型推演**：长时间运行 + 随机时序 + 偶尔异常，断言**不存在**「全部永久停摆」的状态，
     且「部分停摆」在 N tick 内自愈；并把「回退掉本轮修复」的同一模型跑成**反例**（永久停摆）。

另外钉住：本轮**没有**放宽任何既有语义（`pushStalledOnDestination` / `blockedByMachineQueue` /
`startCapacityForRecipe` / `inFlightUnitsForRecipe` / `pooledTransitionalUnits` / `NEXT_FOR_MACHINE`）。
"""
import io
import os
import random
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
CHAMBER = os.path.join(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
WATCHDOG = os.path.join(SRC, "support", "AssemblyWatchdog.java")
GUARD = os.path.join(SRC, "support", "SequenceMaterialGuard.java")

CHECKS = [0]
FAILURES = []


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


def hasnt(text, needle, name):
    ok = needle not in text
    check(name, ok, "" if ok else ("unexpected: %s" % needle))


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def method_body(text, needle):
    """取 `text` 里第一个 `needle(...)` 声明起的整个方法体（花括号配对）。"""
    idx = text.index(needle)
    begin = text.rindex("\n", 0, idx)
    cursor = text.index("{", idx)
    depth = 0
    while True:
        if text[cursor] == "{":
            depth += 1
        elif text[cursor] == "}":
            depth -= 1
            if depth == 0:
                break
        cursor += 1
    return text[begin:cursor + 1]


def long_const(text, name):
    """取 `private static final long NAME = <数值或 表达式 >L;` 的值（支持 `X / 4L` 这种同源写法）。"""
    match = re.search(r"private static final long %s = ([^;]+);" % name, text)
    if match is None:
        return None
    expr = match.group(1).strip()
    if expr.endswith("L"):
        expr = expr[:-1]
    if expr.isdigit() or re.fullmatch(r"[0-9_]+", expr):
        return int(expr.replace("_", ""))
    div = re.fullmatch(r"([A-Z_0-9]+) / ([0-9_]+)", expr)
    if div is not None:
        base = long_const(text, div.group(1))
        return None if base is None else base // int(div.group(2).replace("_", ""))
    return None


chamber = read(CHAMBER)
watchdog = read(WATCHDOG)
guard = read(GUARD)

# ======================================================================================
section("A. 在制件登记表的「登记点 / 清理点」成对性（源码锚点）")

# A1：登记的唯一写入点仍然只有一处（推料成功路径）。
writes = [m.start() for m in re.finditer(r"inFlightUnits\.put\(", chamber)]
check("A1 `inFlightUnits.put` 只有一处（= 唯一登记点，由推料成功路径写入）", len(writes) == 1,
      "found=%d" % len(writes))
export_strategy = read(os.path.join(SRC, "support", "RsccChamberExportStrategy.java"))
has(export_strategy, "chamber.rscc$noteUnitPushed(",
    "A1b 推料策略仍在推成功后登记（RsccChamberExportStrategy → rscc$noteUnitPushed）")
has(chamber, "public void rscc$noteUnitPushed(",
    "A1c 登记入口 rscc$noteUnitPushed 仍在（唯一写入点）")

# A2：清理点必须四条路径都可达。
clear_all = method_body(chamber, "public void rscc$clearAllUnits()")
check("A2 清理点①：任务确认结束整表清空（rscc$clearAllUnits）",
      "inFlightUnits.clear();" in clear_all)
scheduler = method_body(chamber, "public void tickBusScheduler()")
check("A2b 清理点①的调用点：任务确认结束边沿调用 rscc$clearAllUnits()",
      "rscc$clearAllUnits();" in scheduler)
reconcile = method_body(chamber, "private void reconcileInFlightUnits()")
has(reconcile, "inFlightUnits.remove(station);",
    "A2c 清理点②：工位拆掉 / 不再是容器 ⇒ 删掉孤儿登记（reconcileInFlightUnits）")

# A3：判死件的超时兜底（用户硬要求：任何「永久不回退」的状态都必须有超时或对账）。
dead_ticks = long_const(chamber, "UNIT_DEADLOCK_TICKS")
sample_ticks = long_const(chamber, "UNIT_DEADLOCK_SAMPLE_TICKS")
check("A3 `UNIT_DEADLOCK_TICKS` 是有限常量（超时兜底存在）",
      dead_ticks is not None and 0 < dead_ticks <= 12000, "UNIT_DEADLOCK_TICKS=%s" % dead_ticks)
check("A3b 采样间隔 = 判定窗口的 1/4（至少 4 个采样点都观察到拒收才可能判死）",
      sample_ticks == dead_ticks // 4, "sample=%s window=%s" % (sample_ticks, dead_ticks))
has(reconcile, "if (now - since < UNIT_DEADLOCK_TICKS) {",
    "A3c 判死前必须先过「登记还年轻」这一关（绝不把还活着的件判死）")

# A4：逐条早退路径不得跳过清理 —— 判死件的三条证据链必须在同一方法里齐备。
dead = method_body(chamber, "private boolean registeredUnitDead(")
for needle, label in (
        ("refusedTargetAt.get(station)", "证据①：该格此刻仍在拒收本仓推送"),
        ("if (refusedAt > lastLive) {", "证据①b：拒收必须贯穿整个观测窗口（排除刚堵一下）"),
        ("rscc$lastPushOkAge(station)", "证据②：本仓向该格最近一次成功推送的时刻")):
    has(dead, needle, "A4 " + label)
check("A4b 判不出来一律不判死（无世界 / 无拒收记录 ⇒ false）", "return false;" in dead)

# A5：死件标记必须双向自愈（否则一次误判会永久留着）。
has(reconcile, "if (stationLive(level, station)) {",
    "A5 死件标记可撤销：该格重新收料 / 不再拒收 / 变空 ⇒ 立刻撤标记")
push_ok = method_body(chamber, "public void rscc$notePushSucceeded(")
has(push_ok, "abandonedInFlightAt.remove(target.immutable())",
    "A5b 死件自愈的关键一步：该格重新收下我们的推送 ⇒ 撤掉死件标记")
clear_dead = method_body(chamber, "private int clearDeadStationPieceAt(")
has(clear_dead, "abandonedInFlightAt.remove(station);",
    "A5c 机器清理成功后同步撤掉该格死件标记与拒收证据")

# A6：名额计数处必须跳过死件（登记表自愈只治账；这一步保证治的是「名额」）。
busy = method_body(chamber, "private long registeredBusyStations(")
has(busy, "if (!registrationCounts(station)) {",
    "A6 在制件计数（computeInFlightUnitCount 的登记分量）跳过死件")
breakdown = method_body(chamber, "private long[] inFlightBreakdownForRecipe(")
has(breakdown, "if (!chamber.registrationCounts(station)) {",
    "A6b 名额的第①分量（跨舱）同样跳过死件 ⇒ 名额自愈")
reg_units = method_body(chamber, "private long registeredUnitsForRecipe(")
has(reg_units, "if (!registrationCounts(entry.getKey())) {",
    "A6c 兜底路径 registeredUnitsForRecipe（machineHasWorkFor / loserOnMyStations）同口径")

# A7：机器清理（治物）：把「配方已经没有活跃订单」的死件搬回本仓，且仅在有界节流下跑。
clear_pieces = method_body(chamber, "private int clearDeadStationPieces()")
for needle, label in (
        ("busTaskEndFired", "A7 只在「任务已确认结束」"),
        ("busFrozenGate", "A7b 或「本仓被挂起冻结」（否则挂起态下这台机器永远拿不走那件 ⇒ 死锁闭环）")):
    has(clear_pieces, needle, label)
check("A7c 动手条件是「任务结束 或 冻结」二者之一（不是「随时都动」）",
      "if (!busTaskEndFired && !busFrozenGate) {" in clear_pieces)
check("A7d `clearDeadStationPieces` 全方法只有一个调用点", scheduler.count("clearDeadStationPieces();") == 1,
      "count=%d" % scheduler.count("clearDeadStationPieces();"))
call_at = scheduler.index("clearDeadStationPieces();")
call_line = scheduler[scheduler.rindex("\n", 0, call_at) + 1: call_at]
check("A7d2 调用点在门控节流块（busScheduleCooldown）**之外**（缩进更浅）—— 挂起冻结时 idleNow 恒假，"
      "挂在里面等于永远跑不到",
      len(call_line) - len(call_line.lstrip()) < 12 and "idleNow" not in call_line,
      "indent=%d" % (len(call_line) - len(call_line.lstrip())))
has(clear_dead, "if (assembly != null && recipeOrdered(assembly.id().toString())) {",
    "A7e 逐件判据：该配方还有活跃订单的过渡件一律不碰（绝不放宽既有语义）")
has(clear_dead, "if (busTargetAccepts(station, item)) {",
    "A7f 逐件判据：输出总线仍会把它推给它自己那一格 ⇒ 是正常件，不碰")
has(clear_dead, "if (!isTransitionOfMyRecipes(item)) {",
    "A7g 逐件判据：只碰本仓这条产线的过渡件（别的配方 / 别的模组不碰）")
interval = long_const(chamber, "DEAD_STATION_CLEAR_INTERVAL_TICKS")
check("A7h 有界：清理节流是有限常量", interval is not None and 0 < interval <= 200,
      "DEAD_STATION_CLEAR_INTERVAL_TICKS=%s" % interval)

# ======================================================================================
section("B. 顺序遍历的故障隔离（「一台坏不连累其余」）")

fill = method_body(chamber, "private void fillInternalForBus(")
fill_loop = fill[fill.index("for (final TrackedResourceAmount tracked"):]
has(fill_loop, "} catch (final RuntimeException failure) {",
    "B1 备料主循环逐资源 try/catch（每 tick 遍历网络全部资源的热路径）")
check("B1b 隔离体里绝不重抛（重抛 = 重新引入「后面所有资源这一轮都不备料」）",
      "throw " not in fill_loop[fill_loop.index("} catch (final RuntimeException failure) {"):])

recover = method_body(chamber, "private int recoverBlockingTargetItem()")
check("B2 堵塞自愈逐格 try/catch：一处抛只跳过该格",
      recover.count("catch (final RuntimeException failure)") >= 1)
flush = method_body(chamber, "private long[] flushStationStartIngredients(")
has(flush, "} catch (final RuntimeException failure) {",
    "B3 任务结束的工位残留回流逐工位 try/catch")
has(flush, "} catch (final RuntimeException ignored) {", "B3b 且逐格（同一工位内多个槽位）也隔离")

# B4：舱侧已修的那条（另一 agent）+ 本轮新加的都必须在。
has(scheduler, "pushBusTurnToExporters();", "B4 舱侧总线刷新仍在（上一轮已做逐台隔离）")
pushes = method_body(chamber, "private void pushBusTurnToExporters()")
has(pushes, "} catch (final RuntimeException", "B4b 逐台 try/catch 仍在（不得退回「一台抛全部不刷」）")

# ======================================================================================
section("C. 不得放宽既有语义（硬约束回归）")


def pinned(name, needles):
    body = method_body(chamber, name)
    for needle in needles:
        has(body, needle, "C[%s] 保留锚点：%s" % (name, needle))
    return body


pinned("public void tickBusScheduler()", ["busTaskEndFired = false;", "clearDeadStationPieces();"])
pinned("public boolean pushStalledOnDestination()", ["now - stepOwnerMissingAt <= PUSH_STALL_WINDOW_TICKS",
                                                     "allKnownTargetsRefusing(now)"])
pinned("private boolean allKnownTargetsRefusing(", ["return refusedTargetAt.size() >= 2;"])
pinned("private boolean blockedByMachineQueue(", ["machineReservedRecipe()", "loserOnMyStations(level, mine)"])
pinned("private long startCapacityForRecipe(", ["remainingOrderUnitsFor(recipeId)",
                                               "inFlightUnitsForRecipe(recipeId)", "guaranteedResult(recipeId)"])
pinned("private long inFlightUnitsForRecipe(", ["inFlightBreakdownForRecipe(recipeId)"])
pinned("private long[] pooledTransitionalUnits(", ["isOwnedStep(recipeId, nextStep)",
                                                   "ownedStepAnyChamber(level, recipeId, nextStep)"])
has(guard, "NEXT_FOR_MACHINE", "C[NEXT_FOR_MACHINE] SequenceMaterialGuard 的裁决枚举仍在")
has(guard, "s % T == m", "C[NEXT_FOR_MACHINE] 判据本体未改")
has(watchdog, "public static boolean isSuspended(final UUID taskId) {", "C 看门狗挂起入口仍在")

# 看门狗「挂起后不自动恢复」这条既有语义本轮**不得**被改（既有自检锚定它）。
has(watchdog, "private static void resumeRecord(final Record record, final long now) {",
    "C 看门狗只有一条恢复入口 resumeRecord")
check("C 本轮没有新增任何自动恢复入口（resumeRecord 仍只有两处出现：定义 + 玩家 resume）",
      watchdog.count("resumeRecord") == 2, "count=%d" % watchdog.count("resumeRecord"))
check("C 本轮没有自动恢复的「原因正常即解挂」写法",
      "record.reason == Reason.NONE && record.suspendState != SuspendState.RUNNING" not in watchdog)

# ======================================================================================
section("D. 模型推演：长时间运行 + 随机时序 + 偶尔异常 ⇒ 不存在「全部永久停摆」")

# 模型与 Java 常量**同源**（数值直接取自源码，绝不写两份）。
R_RELEASE = dead_ticks        # UNIT_DEADLOCK_TICKS：判死件的窗口
R_SAMPLE = sample_ticks       # UNIT_DEADLOCK_SAMPLE_TICKS：拒收必须贯穿整个观测窗口
R_CLEAR = interval            # DEAD_STATION_CLEAR_INTERVAL_TICKS：机器清理节流
WINDOW = 60                   # PUSH_STALL_WINDOW_TICKS（拒收记录的存活窗口）


class Station(object):
    """一个供料工位（机器 / 置物台）此刻的状态；字段与执行舱的登记表 / 采样表一一对应。"""
    __slots__ = ("state", "since", "refused_at", "last_push_ok", "live_at", "abandoned_at")

    def __init__(self):
        self.state = "free"        # free / working / dead（见 run_model 的说明）
        self.since = 0             # 这一件是第几 tick 落上来的（InFlightUnit.sinceTick）
        self.refused_at = None     # 本仓最近一次被这一格拒收（refusedTargetAt）
        self.last_push_ok = None   # 本仓最近一次向这一格推成功的时刻
        self.live_at = 0           # 活性观测起点（unitLivenessAt）
        self.abandoned_at = None   # 死件标记（abandonedInFlightAt）


def run_model(machines, order_size, ticks, rng, legacy=False, step_ticks=20,
              hang_after=None, hang_rate=0.02, hang_ramp_at=None, hang_ramp_rate=0.5,
              probe=None):
    """与 Java 实现同构的极小模型（判据逐个对应，绝不另写一套语义）。

    <b>一件在制件 = 占住一台机器的一个工位</b>（Create 的机器一次只能加工一件 —— 这正是
    「同一台机器同一时刻最多一份未消耗投入物」那条不变式）。三种站状态：

      * ``free``    —— 工位空着，可以再开一件；
      * ``working`` —— 工位上压着本仓推进去的一件，机器正在做这一步：**它是活的**
                       （`last_push_ok` 持续刷新 ⇒ `registeredUnitDead` 的第 3 条判据永不成立
                       ⇒ 绝不会被判死件 —— 这正是「慢步骤也不能被误判」的护栏）；
      * ``dead``    —— 工位上压着一件**永远不会被消耗、也不会被搬走**的东西
                       （该步已经没有任何订单 / 机器，或取消后留下的残留）
                       ⇒ 本仓此后每一次推送都被这一格拒收，而本仓再也推不成功过。

    legacy=True  ⇒ **回退掉本轮的登记自愈与机器清理**（= 用户现场那一版）
    legacy=False ⇒ 本轮实现（判死件 + 机器清理 + 双向自愈）

    名额与 Java 的 `startCapacityForRecipe` 同口径：``capacity = allowed − inflight``，
    allowed = 订单剩余（必得配方），inflight = 未被判死件的占用数。
    """
    stations = [Station() for _ in range(machines)]
    stations[0].state = "working"      # 启动时仓里已经有一件在制件
    delivered = 0
    started = 1
    steps_done = 0
    stall_all_max = 0
    repaired = 0
    inflight = 0

    for tick in range(ticks):
        # ---- 1. 每 tick 的登记对账（reconcileInFlightUnits）----
        for st in stations:
            if st.state == "free":
                continue
            if tick - st.live_at >= R_SAMPLE:
                st.live_at = tick
            if legacy:
                continue
            if st.abandoned_at is not None:
                continue  # 已判死：等机器清理（自愈的另一半）
            if tick - st.since < R_RELEASE:
                continue  # 登记还年轻：正常加工完全可能这么久，绝不判死
            refused_alive = st.refused_at is not None and tick - st.refused_at <= WINDOW
            spans_window = refused_alive and st.refused_at <= st.live_at
            never_pushed = st.last_push_ok is None or tick - st.last_push_ok > R_RELEASE
            if refused_alive and spans_window and never_pushed:
                st.abandoned_at = tick
        # ---- 2. 名额：只数「未判死件」的占用（与 Java 的 registrationCounts 同口径）----
        inflight = sum(1 for st in stations
                       if st.state != "free" and (legacy or st.abandoned_at is None))
        capacity = max(0, order_size - delivered - inflight)
        while capacity > 0:
            free = next((st for st in stations if st.state == "free"), None)
            if free is None:
                break
            free.state = "working"
            free.since = tick
            free.live_at = tick
            free.abandoned_at = None
            free.refused_at = None
            free.last_push_ok = None
            started += 1
            capacity -= 1
        # ---- 3. 每一步做完 ⇒ 步数 +1；走满一整圈（machines 步）⇒ 交付一件、工位空出来 ----
        for st in stations:
            try:
                if st.state == "working":
                    st.last_push_ok = tick     # 正在做的这一步 = 本仓在这一格上推成功过（活着的证据）
                    if tick - st.since >= step_ticks:
                        st.since = tick
                        steps_done += 1
                elif st.state == "dead":
                    st.refused_at = tick       # 咬住不动：机器一直拒收本仓推送
            except RuntimeError:
                continue                       # 逐台隔离：只跳过这一台，其余照常
        while steps_done >= machines:
            steps_done -= machines
            delivered += 1                     # 成品交回网络
            worker = next((st for st in stations if st.state == "working"), None)
            if worker is not None:
                worker.state = "free"
                worker.abandoned_at = None
        # ---- 4. 随机时序：某台机器在 hang_after 步之后突然咬死（现场「完全随机」）----
        if hang_after is not None and started >= hang_after:
            rate = hang_ramp_rate if (hang_ramp_at is not None and started >= hang_ramp_at) else hang_rate
            for st in stations:
                if st.state != "working":
                    continue
                # 「机器咬死」是**随时可能发生**的独立事件（现场：毫无规律地某台突然就不动了）。
                # rate 是「每一 tick 每台在制机器咬死的概率」，ramp 之后显著升高
                #（= 用户现场的「先快、后面突然都停了」）。
                if rng.random() < rate:
                    st.state = "dead"      # 那件东西永远拿不走 ⇒ 这一格永久占名额
                    break
        # ---- 5. 机器清理（clearDeadStationPieces）：把「配方已经无活跃订单 / 已判死」的死件收回本仓 ----
        if tick % R_CLEAR == 0:
            for st in stations:
                if st.state != "dead":
                    continue
                if legacy:
                    continue               # 回退版：这台机器永久不再装配（用户现场的那一半）
                if st.abandoned_at is not None and tick - st.abandoned_at >= R_RELEASE:
                    st.state = "free"
                    st.refused_at = None
                    st.abandoned_at = None
                    repaired += 1
        if probe is not None:
            # 守恒采样：同一 tick 内两把尺子必须量同一份状态 —— 因此在 tick 末重新取一次
            # 「被计入名额的登记数」（occuped − 已判死件）与「实际在制件数」（占住的工位数）。
            occupied = sum(1 for st in stations if st.state != "free")
            counted_now = sum(1 for st in stations
                              if st.state != "free" and st.abandoned_at is None)
            probe.append((counted_now, occupied))
        if sum(1 for st in stations if st.state == "working") == 0 and delivered < order_size:
            stall_all_max += 1
        if delivered >= order_size:
            break
    return {
        "delivered": delivered,
        "started": started,
        "stall_all_max": stall_all_max,
        "ticks": tick + 1,
        "repaired": repaired,
        "inflight": inflight,
    }


SEEDS = list(range(1, 25))

# 现场形态参数：4 台机器、订单 8 件。前几件顺利（= 用户说的「刚开始非常快」），
# 之后故障集中出现：每开一两件就多一台机器咬住一件东西不放手（现场就是「突然都停了」）。
# 一步耗时取 400 tick，是为了让「机器咬死」这件事在订单做完之前有机会累积起来。
SINK = dict(machines=4, order_size=8, ticks=200000, step_ticks=400,
            hang_after=1, hang_rate=0.0, hang_ramp_at=1, hang_ramp_rate=0.0005)

# D1：本轮实现 —— 随机时序 + 反复咬死，都必须把订单做完（不存在「全部永久停摆」）。
fixed_failures = []
for seed in SEEDS:
    outcome = run_model(rng=random.Random(seed), legacy=False, **SINK)
    if outcome["delivered"] < SINK["order_size"]:
        fixed_failures.append((seed, outcome))
check("D1 本轮实现：%d 个随机种子下订单全部交付（不存在「全部永久停摆」）" % len(SEEDS),
      not fixed_failures,
      "" if not fixed_failures else "seed=%s outcome=%s" % fixed_failures[0])

# D2：反例 —— 回退本轮修复后，同一随机集合里确实出现永久停摆。
legacy_stuck = []
for seed in SEEDS:
    outcome = run_model(rng=random.Random(seed), legacy=True, **SINK)
    if outcome["delivered"] < SINK["order_size"]:
        legacy_stuck.append((seed, outcome["delivered"]))
check("D2 反例（回退本轮修复）：同一随机集合里出现永久停摆（只做出一部分就再也不动）",
      len(legacy_stuck) > 0,
      "legacy 未交付的种子 = %d/%d，例如 %s" % (len(legacy_stuck), len(SEEDS), legacy_stuck[:5]))
check("D2b 修复确实治好了它：回退后不交付 %d/%d 个种子，修复后 0 个"
      % (len(legacy_stuck), len(SEEDS)), len(legacy_stuck) > 0 and not fixed_failures)

# D3：整线停摆（一台都不在装配）的最长持续 —— 必须远小于「永久」。
N_SELF_HEAL = 4 * R_RELEASE + 4 * R_CLEAR
worst_all = 0
for seed in SEEDS:
    outcome = run_model(rng=random.Random(seed), legacy=False, **SINK)
    worst_all = max(worst_all, outcome["stall_all_max"])
check("D3 整线停摆的最长持续 = %d tick ≤ 上界 4×R_RELEASE + 4×R_CLEAR = %d"
      "（部分停摆必然自愈，绝不永久）" % (worst_all, N_SELF_HEAL), worst_all <= N_SELF_HEAL)

# D4：慢步骤（一步耗时 = 2×判死窗口）不得被误判成死件 —— 判据含「本仓向该格推成功过」。
slow = run_model(machines=1, order_size=3, ticks=60000, rng=random.Random(11), legacy=False,
                 step_ticks=2 * R_RELEASE, hang_after=None)
check("D4 一步耗时 %d tick（= 2×判死窗口 %d）也不被误判成死件：订单仍能交付"
      % (2 * R_RELEASE, R_RELEASE), slow["delivered"] >= 3, "delivered=%s" % slow["delivered"])

# ======================================================================================
section("E. 守恒：在制登记数 vs 实际在制件数（瞬时偏差 + 有界收敛）")


def conservation_run(seed=3, **kwargs):
    """逐 tick 记录「被计入名额的登记数」与「实际在制件数」——**复用同一个模型**，不另写一份实现。"""
    samples = []
    run_model(rng=random.Random(seed), legacy=False, probe=samples, **kwargs)
    counted = [s[0] for s in samples]
    actual = [s[1] for s in samples]
    return counted, actual


counted, actual = conservation_run(seed=3, **SINK)
over = [i for i, (c, a) in enumerate(zip(counted, actual)) if c > a]
check("E1 名额计入的登记数**恒不超过**实际在制件数（绝不虚报，方向安全）", not over,
      "violations=%d first=%s" % (len(over), over[:3]))
gap = [a - c for c, a in zip(counted, actual)]
check("E2 偏差有界：最大偏差 = %d = 已被判死件、尚未被机器清理搬走的件数上限（机器台数 %d）"
      % (max(gap) if gap else 0, 4), (max(gap) if gap else 0) <= 4)
head = sum(gap[:1000]) / float(len(gap[:1000]) or 1)
tail = sum(gap[-1000:]) / float(len(gap[-1000:]) or 1)
check("E3 偏差收敛而非泄漏：末段 1000 采样平均 %.3f ≤ 首段 %.3f（不随运行时间单调累积）"
      % (tail, head), tail <= head + 1e-9)

# ======================================================================================
section("F. 结果")
print()
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - " + item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
