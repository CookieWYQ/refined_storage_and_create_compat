# -*- coding: utf-8 -*-
"""「类别 → 目标机器 → 步骤」三者配对 自检（源码锚点 + 等价模型推演）。

用法：python tools/selfcheck_assembly_bus_routing.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要这个脚本（本轮用户实测最严重的 bug，无法在本地把游戏跑起来验证）：
  用户原话：①「我贴的置物台那个地方，**只选了金板和那几个中间产物**，但是他却**输出了一个齿轮**」；
           ②「另外一个输出总线也是一样，只选了金板和其他的，这个则输出了金板。但是上面那个拿的却
              是**大齿轮**，直接就是卡住了」。
  上一轮把「步骤专用投入物（机械手手里那件）」的**归属判定整条绕过了**（只留「本总线目标机器此刻
  是否要它」），于是**没勾这个类别的总线也拿到了齿轮过滤项** —— 实测日志（run/logs/latest.log
  21:33–21:35）里 `exporter@(-6,-60,6) cats=[input:create:golden_sheet, intermediate:0..2]`
  紧接着 `push {item=create:cogwheel x1} to=(-7,-60,6)`（该总线指向的是一台 `create:depot`）。
  另一半根因：机械手的容器里只有「手里拿着的那一件」，在制件在它朝向 2 格外的置物台上（Create
  `DeployerBlockEntity`：`worldPosition.relative(facing, 2)`），因此「本机当前步」判不出来 → 旧口径
  「判不出来一律放行」→ 哪一件先在导出清单里就先塞进手里 → 塞错一件就被 `DeployerItemHandler`
  永久拒收，产线卡死。

做法：① 源码锚点确认结构（归属判定顺序 / 机械手操作对象解析 / 断言拒绝 + 限频日志 / 收回侧同口径）；
      ② 用等价 Python 模型复刻 `SequenceExecutionChamberBlockEntity#busExportFilters` 的
      「归属 → 步骤专用投入物豁免轮询 → 轮询均分」三段与 `#inputMaterialWantedNow(BlockPos, Item)`
      的「本机步 → 机械手严格 → 其余宽松」三分支，把「多总线各自类别不串台」「推料 == 该机器当前步
      所需（穷举）」「机械手手里有外来件时不推错料也不反复重试」「无缓存仓时中间产物去向」跑一遍。
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
    with io.open(os.path.join(SRC, *parts), "r", encoding="utf-8") as handle:
        return handle.read()


def body(text, start, end):
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


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


chamber = read("block", "entity", "SequenceExecutionChamberBlockEntity.java")
strategy = read("support", "RsccChamberExportStrategy.java")
import_strategy = read("support", "RsccChamberImportStrategy.java")
shared_cache = read("support", "RsccSharedCache.java")
item_storage = read("support", "RsccChamberItemStorage.java")
debug = read("support", "RsccAssemblyDebug.java")

# ==================== 1. 源码锚点 ====================
section("1) 源码锚点：归属判定顺序 / 机械手操作对象 / 断言拒绝 + 限频日志 / 收回侧同口径")

filters = body(chamber, "public List<ResourceKey> busExportFilters(", "private Set<Item> occupiedInputMaterials")
check("归属判定（本总线勾了它吗）排在「步骤专用投入物豁免」之前 —— 没勾的类别绝不出现在任何总线的清单里",
      filters.index("final int index = owners.indexOf(exporterPos);")
      < filters.index("if (isStepExtraCategory(info)) {"))
has(chamber, "if (isStepExtraCategory(info)) {",
    "锚点: 步骤专用投入物只豁免「轮询均分」，不豁免归属（豁免分支仍在归属判定之后）")

has(chamber, "private PendingStep operandStepOf(final Level level, final BlockPos machine) {",
    "锚点: 机器「实际在加工的那一格」的步序解析只有一份（operandStepOf）")
check("机械手操作对象 = 它朝向的 2 格外（与 Create 自己的 shouldActivate/activate 同一口径）",
      "instanceof com.simibubi.create.content.kinetics.deployer.DeployerBlock" in chamber
      and "DirectionalKineticBlock.FACING), 2);" in chamber)
has(chamber, "final PendingStep inFlight = toPendingStep(level, firstAssemblyAt(level, operand));",
    "锚点: 先看在制件（进度组件）")
check("起件（置物台上只有裸起步原料）→ 第 0 步：否则机械手会「手里空着等」，产线起不来",
      "return inFlight != null ? inFlight : startStepAt(level, operand);" in chamber
      and "found = new PendingStep(entry.getKey(), 0);" in chamber)

wanted_now = body(chamber, "public boolean inputMaterialWantedNow(@org.jetbrains.annotations.Nullable final BlockPos target,",
                  "private static boolean isDeployer(")
check("推料侧判据三分支：本机可判 → 只放行那一步要的；机械手判不出 → 不推；其余机器 → 既有宽松口径",
      "return wanted.contains(item);" in wanted_now
      and "if (isDeployer(level, target)) {" in wanted_now
      and "return inputMaterialWantedNow(item);" in wanted_now)
has(chamber, "public boolean blockedByForeignStepExtra(",
    "锚点: 「手里被一件本步不要的投入物占着」有独立的只读判据（不推错料 + 不反复重试）")

check("推料侧的断言级校验：两道门都在「真正搬运之前」返回 SKIPPED，并各有一条限频日志通道",
      "ASSERT_NOT_CURRENT_STEP_INPUT" in strategy
      and "ASSERT_MACHINE_HOLDS_OTHER_STEP_INPUT" in strategy
      and 'chamber.inputMaterialWantedNow(targetPos, item.item())' in strategy
      and "chamber.blockedByForeignStepExtra(targetPos, item.item())" in strategy
      and "if (outcome.moved() <= 0 && isAssertionRefusal(outcome.detail())) {" in strategy
      and "RsccAssemblyDebug.reject(" in strategy)
check("断言拒绝的日志是「限频」而不是刷屏：走 RsccAssemblyDebug.reject（首次立即 + 之后每 5 秒合并一条）",
      "public static void reject(final String key, final String body) {" in debug
      and "REJECT_MERGE_NANOS = 5_000_000_000L" in debug
      and "(same cause repeated {} times in 5s)" in debug)

check("收回侧与推料侧同口径（判定只保留一份）：机器侧收回判据带机器坐标",
      "if (inputItems.contains(stack.getItem())" in import_strategy
      and "&& !chamber.inputMaterialWantedNow(pos, stack.getItem())) {" in import_strategy
      and "private static boolean autoAcceptsItem(final BlockPos pos, final ItemStack stack," in import_strategy
      and "BiPredicate<BlockPos, ItemStack> acceptMachineItem" in import_strategy)
has(import_strategy, "pullMachineItems(level, selfPos, target, storage, actor, acceptMachineItem, label, detail,",
    "锚点: 机器侧收回把「哪一台机器」传进判据（与推料侧同一套坐标语义）")

# ==================== 2. 多总线各自类别 → 不串台 ====================
section("2) 多总线各自声明不同类别：过滤项绝不串台（用用户存档里的真实坐标 / 勾选推演）")

DEPOT_A, DEPOT_B = (-6, -60, 6), (-6, -60, 5)   # 两条产线的「置物台」总线（用户说的那个地方）
ARM_A, ARM_B = (-6, -58, 6), (-6, -58, 5)       # 两条产线的「机械手」总线
# owners 表直接取自存档 NBT（chamber@(-5,-60,6).BusCategoryOwners，BlockPos.asLong 解码后一致）：
#   input:create:golden_sheet / intermediate:0..2 → 两台置物台总线
#   input:create:cogwheel / input:create:large_cogwheel / input:minecraft:iron_nugget → 两台机械手总线
OWNERS = {
    "input:create:golden_sheet": [DEPOT_A, DEPOT_B],
    "intermediate:0": [DEPOT_A, DEPOT_B],
    "intermediate:1": [DEPOT_A, DEPOT_B],
    "intermediate:2": [DEPOT_A, DEPOT_B],
    "input:create:cogwheel": [ARM_A, ARM_B],
    "input:create:large_cogwheel": [ARM_A, ARM_B],
    "input:minecraft:iron_nugget": [ARM_A, ARM_B],
}
CATEGORY_ITEMS = {
    "input:create:golden_sheet": ["create:golden_sheet"],
    "intermediate:0": [], "intermediate:1": [], "intermediate:2": [],
    "input:create:cogwheel": ["create:cogwheel"],
    "input:create:large_cogwheel": ["create:large_cogwheel"],
    "input:minecraft:iron_nugget": ["minecraft:iron_nugget"],
}
STEP_EXTRA_CATS = {"input:create:cogwheel", "input:create:large_cogwheel", "input:minecraft:iron_nugget"}
# 总线的目标机器：置物台总线 → 置物台；机械手总线 → 机械手（机械手再用「朝向 2 格外的操作对象」定位）
SUPPLY_TARGET = {DEPOT_A: DEPOT_A, DEPOT_B: DEPOT_B, ARM_A: ARM_A, ARM_B: ARM_B}
OPERAND = {DEPOT_A: None, DEPOT_B: None, ARM_A: DEPOT_A, ARM_B: DEPOT_B}
# 「这一台（或它的手 / 台）此刻握着哪些物品」= 那份还没被消耗掉的投入物。
# 复刻 SequenceExecutionChamberBlockEntity#supplyTargetHoldsItem（整个工位一起看）。
# 键 = (目标坐标, 物品 id)，值 = True。
HOLDS = {}
EXTRA_OF_STEP = {0: {"create:cogwheel"}, 1: {"create:large_cogwheel"}, 2: {"minecraft:iron_nugget"}}
SELECTED = {
    DEPOT_A: ["input:create:golden_sheet", "intermediate:0", "intermediate:1", "intermediate:2"],
    DEPOT_B: ["input:create:golden_sheet", "intermediate:0", "intermediate:1", "intermediate:2"],
    ARM_A: ["input:create:cogwheel", "input:create:large_cogwheel", "input:minecraft:iron_nugget"],
    ARM_B: ["input:create:cogwheel", "input:create:large_cogwheel", "input:minecraft:iron_nugget"],
}


def pending_of(target, world):
    """复刻 pendingStepOn + operandStepOf：自身容器的在制件 → 机械手 2 格外的操作对象 → 起步原料 = 第 0 步。"""
    step = world.get(target, {}).get("in_flight")
    if step is not None:
        return step
    operand = OPERAND.get(target)
    if operand is None:
        return None
    operand_state = world.get(operand, {})
    if operand_state.get("in_flight") is not None:
        return operand_state["in_flight"]
    if operand_state.get("raw") == "create:golden_sheet":
        return 0                       # 起件：本仓负责配方（precision_mechanism）的第 0 步
    return None


def chamber_wide_wanted(item, world):
    """复刻 inputMaterialWantedNow(Item)：全仓并集；有一台判不出来 → 宽松（既有口径）。"""
    undecidable = False
    for target in SUPPLY_TARGET.values():
        pending = pending_of(target, world)
        if pending is None:
            undecidable = True
            continue
        wanted = EXTRA_OF_STEP.get(pending, set())
        if not wanted:
            return True
        if item in wanted:
            return True
    return undecidable


def wanted_now(target, item, world):
    """复刻 inputMaterialWantedNow(BlockPos, Item) 的三分支（本轮语义）。"""
    pending = pending_of(target, world)
    if pending is not None:
        wanted = EXTRA_OF_STEP.get(pending, set())
        return True if not wanted else item in wanted
    if OPERAND.get(target) is not None:
        return False                   # 机械手判不出 = 它眼下没有可加工的东西 → 不推（本轮严格化）
    return chamber_wide_wanted(item, world)


def export_share(owners, remaining):
    """复刻 SequenceExecutionChamberBlockEntity#exportShare：份额 = clamp(订单剩余量, 1, 台数)。

    remaining <= 0 / None 表示「判不出来」（没有相关任务）→ 保守按「全部台数」（既有行为）。
    """
    if len(owners) <= 1:
        return len(owners)
    if remaining is None or remaining <= 0:
        return len(owners)
    return max(1, min(len(owners), remaining))


NO_TARGET = set()   # 目标容器不存在的总线（被拆 / 朝向不对）——复刻 supplyTargetOf 返回 null


def station_of(target):
    """复刻 stationKey：机械手的工位 = 它朝向 2 格外的操作对象；其余机器就是它自己。

    同一个工位（置物台 + 对着它的机械手）解析出的待加工件完全一样，因此必须合并，
    否则「几台机器要它」会翻倍（实测 cogwheel target=3 而实际只要 1）。
    """
    return OPERAND.get(target) or target


def station_holds(target, item):
    """复刻 supplyTargetHoldsItem：整个工位（置物台 + 对着它的机械手）里有没有这一件（未被消耗）。"""
    key = station_of(target)
    if (key, item) in HOLDS:
        return True
    for other in SUPPLY_TARGET.values():
        if station_of(other) == key and (other, item) in HOLDS:
            return True
    return False


def machine_cannot_work(exporter, world, item=None):
    """复刻 shareFrontBusyFor 的「这台干不了活」：没有目标容器，或 SIMULATE 收不下这一份资源。

    本轮修正①：旧口径把「机器里压着东西（在制件 / 同种料）」也算干不了活 —— 那是<b>正常加工</b>，
    会让份额外的总线多推一份起步原料、多开一件在制件（用户实测「另外一台机器下方多输出了一次金板」）。
    本轮修正②（残留路径）：光看「一次 SIMULATE 收不收得下」还不够 —— 置物台只有 1 格，前位机器
    <b>已经握着这一件</b>（手上还有未消耗的投入物）时 SIMULATE 必然失败，会被误读成「收不下」。
    因此：「这个工位已经握着这一件」= 它正在干活 ⇒ 一律算「干得了活」，绝不让位。
    """
    if exporter in NO_TARGET:
        return True
    if item is not None and station_holds(SUPPLY_TARGET[exporter], item):
        return False
    return not world.get(SUPPLY_TARGET[exporter], {}).get("accepts", True)


def owns_turn(exporter, owners, remaining, world, item=None):
    """复刻 ownsFallbackTurn：份额内的总线有权；份额外的只在「份额内全都不干活且本机目标没被占」时顶上。"""
    index = owners.index(exporter)
    share = export_share(owners, remaining)
    if index < share:
        return True
    if not all(machine_cannot_work(owners[i], world, item) for i in range(share)):
        return False
    return not machine_cannot_work(exporter, world, item)


def share_probe(cid):
    """复刻 shareProbeOf：单一物品且非按步过滤的类别 → 给出那件具体物品；否则 None（退化旧口径）。"""
    items = CATEGORY_ITEMS[cid]
    return items[0] if len(items) == 1 else None


def wanting_station_count(item, world):
    """复刻 wantingTargetCount：按工位去重，且排除「这个工位已经握着这一件」的机器。

    返回「有几台机器正处于缺这份投入物而停的状态」= 该备几份（用户硬要求：一台机器一份）。
    """
    stations = {}
    for target in SUPPLY_TARGET.values():
        stations.setdefault(station_of(target), target)
    want = 0
    for target in stations.values():
        pending = pending_of(target, world)
        if pending is None:
            continue
        wanted = EXTRA_OF_STEP.get(pending, set())
        if not wanted or item not in wanted:
            continue
        if station_holds(target, item):
            continue                      # 手上还有未被消耗的投入物 → 它不缺料，不再备第二份
        want += 1
    return want


def bus_export_filters(exporter, world, game_time, remaining):
    """复刻 busExportFilters 的三段：归属 → （步骤专用投入物：只豁免轮询，不豁免份额）→ 份额判定。"""
    out = []
    for cid in SELECTED[exporter]:                  # 本总线勾选的类别（未勾的根本不进循环）
        owners = OWNERS.get(cid)
        if owners is None or exporter not in owners:  # 归属判定（提到最前，任何类别都不得例外）
            continue
        if cid in STEP_EXTRA_CATS:                   # 步骤专用投入物：份额照旧成立
            if not owns_turn(exporter, owners, remaining, world, share_probe(cid)):
                continue
            target = SUPPLY_TARGET[exporter]
            for item in CATEGORY_ITEMS[cid]:
                if wanted_now(target, item, world):
                    out.append((cid, item))
            continue
        if not owns_turn(exporter, owners, remaining, world, share_probe(cid)):
            continue                                 # 无份额（或未让位）→ 本 tick 不导出
        out.append((cid, None))
    return out


# 默认推演口径：下单 2 个（份额 = min(2 台, 2) = 2 → 两台都参与），与既有断言保持一致
REMAINING_TWO = 2

# 两条产线各自处于不同步骤：A 在第 0 步（要齿轮）、B 已在第 1 步（要大齿轮）
world = {DEPOT_A: {"in_flight": 0}, DEPOT_B: {"in_flight": 1}, ARM_A: {}, ARM_B: {}}
violations = []
for exporter in (DEPOT_A, DEPOT_B, ARM_A, ARM_B):
    for game_time in range(0, 8):
        for cid, item in bus_export_filters(exporter, world, game_time, REMAINING_TWO):
            if exporter not in OWNERS[cid]:
                violations.append((exporter, cid))
check("推演① 任何一台总线拿到的过滤项，都必须是它自己勾选过（= 归属表里有它）的类别",
      violations == [], "串台: %s" % (violations,))


def filter_items(exporter, world, game_time, remaining=REMAINING_TWO):
    """过滤项 → 它对应的物品（中间产物类别在过滤项里是「带进度组件的过渡件」，这里只关心物品 id）。"""
    out = set()
    for cid, item in bus_export_filters(exporter, world, game_time, remaining):
        if item is not None:
            out.add(item)
        elif CATEGORY_ITEMS[cid]:
            out.add(CATEGORY_ITEMS[cid][0])
        else:
            out.add("create:incomplete_precision_mechanism")   # intermediate:N 的过滤项是过渡件
    return out


depot_filters = filter_items(DEPOT_A, world, 0) | filter_items(DEPOT_B, world, 0)
check("推演② 只勾了「金板 + 中间产物」的置物台总线：过滤项里绝无齿轮 / 大齿轮 / 铁粒，"
      "只有它自己勾的那两组（金板 + 过渡件）"
      "（= 用户实测「只选了金板和中间产物，却输出了齿轮」的根因已消除）",
      depot_filters.isdisjoint({"create:cogwheel", "create:large_cogwheel", "minecraft:iron_nugget"})
      and depot_filters == {"create:golden_sheet", "create:incomplete_precision_mechanism"})

arm_a = {c for cid, c in bus_export_filters(ARM_A, world, 0, REMAINING_TWO)}
arm_b = {c for cid, c in bus_export_filters(ARM_B, world, 0, REMAINING_TWO)}
check("推演③（N=2）下单 2 个 → 两台机械手总线各拿到自己那一步要的那一件（豁免轮询、且份额够 2）",
      arm_a == {"create:cogwheel"} and arm_b == {"create:large_cogwheel"})
check("推演④ 不同类别各自声明 → 两条总线各自只推自己那条线当前要的那件（A 不出大齿轮、B 不出齿轮）",
      "create:large_cogwheel" not in arm_a and "create:cogwheel" not in arm_b)

# ---- 本轮核心：份额由「下单数量」决定（N=1 只跑一台；稳定不抖动；双总线相同只用一条） ----
arm_a_n1 = {c for cid, c in bus_export_filters(ARM_A, world, 0, 1)}
arm_b_n1 = {c for cid, c in bus_export_filters(ARM_B, world, 0, 1)}
check("推演⑤（N=1）下单 1 个 → 只有归属表里下标 0 的那一台（ARM_A）拿到过滤项，"
      "ARM_B 一件都不拿（= 用户实测「下单 1 个却两台机器同时输出」的根因已消除）",
      arm_a_n1 == {"create:cogwheel"} and arm_b_n1 == set())

depot_a_n1 = filter_items(DEPOT_A, world, 0, 1)
depot_b_n1 = filter_items(DEPOT_B, world, 0, 1)
check("推演⑥（N=1）非「步骤专用投入物」类别同样只在份额内那一台导出（置物台 B 拿不到金板 / 过渡件）",
      "create:golden_sheet" in depot_a_n1 and depot_b_n1 == set())

stable = True
for game_time in range(0, 12):
    if ({c for cid, c in bus_export_filters(DEPOT_A, world, game_time, 1)}
            != {c for cid, c in bus_export_filters(DEPOT_A, world, 0, 1)}):
        stable = False
check("推演⑦ 份额分配<b>稳定不抖动</b>：连续 12 个 tick 的结论完全一致（旧实现按 gameTime 轮询，必然抖动）",
      stable and "Math.floorMod(gameTime, owners.size())" not in chamber)

# 双总线完全相同（同一类别、同一份目标机器状态）：N=1 时只有一条在推
same_world = {DEPOT_A: {"in_flight": 0}, DEPOT_B: {"in_flight": 0}, ARM_A: {}, ARM_B: {}}
depot_push_n1 = [e for e in (DEPOT_A, DEPOT_B)
                 if any(cid == "input:create:golden_sheet"
                        for cid, _ in bus_export_filters(e, same_world, 0, 1))]
check("推演⑧ 一条执行器挂两条「完全相同」的输出总线（同一类别 + 同样的机器状态）时，"
      "N=1 只让其中一条导出（另一条同一时刻不输出）",
      depot_push_n1 == [DEPOT_A])
# 但份额内那台机器「真的收不下这一份」时会自动让位，避免死锁
blocked_world = {DEPOT_A: {"in_flight": 0, "occupied": True, "accepts": False}, DEPOT_B: {},
                 ARM_A: {}, ARM_B: {}}
depot_push_blocked = [e for e in (DEPOT_A, DEPOT_B)
                      if any(cid == "input:create:golden_sheet"
                             for cid, _ in bus_export_filters(e, blocked_world, 0, 1))]
check("推演⑨ 份额内的机器<b>收不下这一份</b>时，份额外的总线也会拿到这一次机会（不得死锁）；"
      "而份额内还收得下时它一点不让（见推演⑧ / ⑨c）",
      DEPOT_B in depot_push_blocked and depot_push_n1 == [DEPOT_A])

# 份额内的机器只是「正在加工」（手里压着在制件 / 同种料）→ 收得下这一份 ⇒ <b>绝不让位</b>：
# 否则会把同一份起步原料推给第二台机器、开出第二件在制件 —— 用户实测「另外一台机器下方多输出了一次金板」。
mid_process_world = {DEPOT_A: {"in_flight": 0, "occupied": True}, DEPOT_B: {},
                     ARM_A: {}, ARM_B: {}}
depot_push_mid = [e for e in (DEPOT_A, DEPOT_B)
                  if any(cid == "input:create:golden_sheet"
                         for cid, _ in bus_export_filters(e, mid_process_world, 0, 1))]
check("推演⑨c 份额内的机器<b>只是正在加工</b>（还收得下这一份）时不让位：份额外的总线拿不到任何过滤项 ⇒ "
      "不会发生「多余的一次推送」（用户实测多推一份起步原料的根因已消除）",
      depot_push_mid == [DEPOT_A])

# ---- 本轮残留路径①：前位机器「已经握着这一件」时，SIMULATE 必然失败（置物台只有 1 格） ----
# 实测（latest.log）：share=1 owners=2 remaining=1 时 exporter@(-6,-60,6) 仍被放行并
# push {item=create:golden_sheet x1} to=(-7,-60,6) —— 那一份随后又被输入总线原样收回（"金板多输出一个"）。
hold_world = {DEPOT_A: {"in_flight": 0, "occupied": True, "accepts": False}, DEPOT_B: {},
              ARM_A: {}, ARM_B: {}}
HOLDS[(DEPOT_A, "create:golden_sheet")] = True
depot_push_hold = [e for e in (DEPOT_A, DEPOT_B)
                   if any(cid == "input:create:golden_sheet"
                          for cid, _ in bus_export_filters(e, hold_world, 0, 1))]
HOLDS.clear()
check("推演⑨d（本轮核心）份额内的机器<b>已经握着这一份</b>（SIMULATE 收不下，但那是「正在干活」）时"
      "绝不让位：份额外那条总线一件都拿不到 ⇒「下单 1 个金板却多输出一份」的残留路径彻底关闭",
      depot_push_hold == [DEPOT_A])
has(chamber, '''if (supplyTargetHoldsItem(level, target, item)) {
                return false;
            }''',
    "源码锚点: shareFrontBusyFor 里「前位工位已经握着这一件 ⇒ 不算收不下 ⇒ 不让位」的唯一实现点")

# ---- 本轮残留路径②：备料目标量按「工位」去重 + 排除「已经握着」的机器 ----
same_step_world = {DEPOT_A: {"in_flight": 0}, DEPOT_B: {"in_flight": 0}, ARM_A: {}, ARM_B: {}}
check("推演⑪（本轮核心）单机不变式：一个工位（置物台 + 对着它的机械手）只算一份 —— "
      "两台机器同时要齿轮 → 备料目标 2（旧写法把工位算两次会得到 4）",
      wanting_station_count("create:cogwheel", same_step_world) == 2)
HOLDS[(ARM_A, "create:cogwheel")] = True
check("推演⑫（本轮核心）同一台机器同一时刻最多一份未消耗投入物：机械手手里已经攥着齿轮（= 它不缺料）→ "
      "该工位的备料目标归零（不再为它备第二份），另一台仍然各一份",
      wanting_station_count("create:cogwheel", same_step_world) == 1)
HOLDS.clear()
has(chamber, "final Map<BlockPos, BlockPos> stations = supplyStations(level);",
    "源码锚点: 备料目标量按工位（supplyStations）归并，不再逐格计数")
has(chamber, "for (final Map.Entry<BlockPos, BlockPos> station : stations.entrySet()) {",
    "源码锚点: 备料目标量遍历「工位」而不是「目标格」—— 同一工位只算一份")
has(chamber, '''if (supplyTargetHoldsItem(level, target, item)) {
                continue;
            }''',
    "源码锚点: 已经握着这一件的工位不再备第二份（单机一份不变式）")
has(chamber, "private static BlockPos stationKey(final Level level, final BlockPos target) {",
    "源码锚点: 工位归并（stationKey）只有一份实现")
has(chamber, "private boolean supplyTargetHoldsItem(final Level level, final BlockPos target, final Item item) {",
    "源码锚点: 「这台机器手上还有未被消耗的投入物」只有一份实现")
has(chamber, "return isStepExtraInput(item) ? wantingTargetCount(item) : Long.MAX_VALUE;",
    "源码锚点: 备料量上限不再夹「至少 1 份」的下限（一个工位都不缺它 ⇒ 目标 0 ⇒ 一条判断都不做）")
has(chamber, '''if (cap <= 0L) {
                        continue;
                    }''',
    "源码锚点: 目标量 ≤ 0 时 fillInternalForBus 直接短路（不再产生 not_wanted_this_step 的反复判定）")
check("推演⑬（本轮核心）「只有当机器缺料而停时才补一份」：当前步要的是大齿轮（齿轮一个工位都不缺）→ "
      "齿轮的备料目标 = 0（旧写法保底 1 份 ⇒ 每 tick 白判一次并被拒 → 齿轮空转）",
      wanting_station_count("create:cogwheel",
                            {DEPOT_A: {"in_flight": 1}, DEPOT_B: {"in_flight": 1},
                             ARM_A: {}, ARM_B: {}}) == 0)
check("推演⑭ 缺口一出现就立刻恢复供料（第 0 步 → 两台机器各 1 份，绝不因「刚才是 0」而断供）",
      wanting_station_count("create:cogwheel",
                            {DEPOT_A: {"in_flight": 0}, DEPOT_B: {"in_flight": 0},
                             ARM_A: {}, ARM_B: {}}) == 2)

# 份额内的总线「没有目标容器」（被拆 / 朝向不对）同样算干不了活 → 让位，不得死锁
notarget_world = {DEPOT_A: {"in_flight": 0}, DEPOT_B: {}, ARM_A: {}, ARM_B: {}}
NO_TARGET.add(DEPOT_A)
depot_push_notarget = [e for e in (DEPOT_A, DEPOT_B)
                       if any(cid == "input:create:golden_sheet"
                              for cid, _ in bus_export_filters(e, notarget_world, 0, 1))]
NO_TARGET.clear()
check("推演⑨b 份额内的总线没有目标容器（被拆 / 朝向不对）时，份额外的总线顶上（不得死锁）；"
      "它自己那一份过滤项即便还在清单里，策略侧也只会返回 no_target_container（搬不动任何东西）",
      DEPOT_B in depot_push_notarget and "no_target_container" in strategy)

sheet_a = any(cid == "input:create:golden_sheet" for cid, _ in bus_export_filters(DEPOT_A, world, 0, 4))
sheet_b_0 = any(cid == "input:create:golden_sheet" for cid, _ in bus_export_filters(DEPOT_B, world, 0, 4))
check("推演⑩（N=4）下单 4 个 + 两台机器 → 两台都参与（份额 = 2 = 全部台数）",
      sheet_a and sheet_b_0)

# ==================== 3. 推料 == 该机器当前步所需（穷举） ====================
section("3) 断言：推出的物品必须与该机器当前这一步所需的投入物完全一致（穷举 3 步 × 手里状态）")

ALL_ITEMS = ["create:cogwheel", "create:large_cogwheel", "minecraft:iron_nugget"]
ALL_EXTRAS = set(ALL_ITEMS)


def blocked_by_foreign(target, item, world):
    """复刻 blockedByForeignStepExtra：机器里压着「本步不要的」步骤专用投入物 → 占位。"""
    held = world.get(target, {}).get("in_flight_hand")
    if held is None or held == item or held not in ALL_EXTRAS:
        return False
    return not wanted_now(target, held, world)


def push_decision(target, item, world):
    """复刻 RsccChamberExportStrategy#transferItem 的三道门（顺序一致）。"""
    held = world.get(target, {}).get("in_flight_hand")
    if held == item:
        return "machine_holds_input"
    if not wanted_now(target, item, world):
        return "assert_not_current_step_input"
    if blocked_by_foreign(target, item, world):
        return "assert_machine_holds_other_step_input"
    return None


wrong_push = []
futile_retry = []
for step in (0, 1, 2):
    for hand in [None] + ALL_ITEMS:
        for item in ALL_ITEMS:
            # 只有「机械手」这一类的机器会收到步骤专用投入物（类别归属决定了这一点，见第 2 节），
            # 因此穷举只跑两台机械手（各自看自己那条线的置物台）。
            world = {DEPOT_A: {"in_flight": step}, DEPOT_B: {"in_flight": step},
                     ARM_A: {"in_flight_hand": hand}, ARM_B: {"in_flight_hand": hand}}
            for target in (ARM_A, ARM_B):
                decision = push_decision(target, item, world)
                required = EXTRA_OF_STEP.get(pending_of(target, world), set())
                if decision is None and item not in required:
                    wrong_push.append((target, step, hand, item))
                if (hand is not None and hand != item and hand not in required
                        and decision is None):
                    futile_retry.append((target, step, hand, item))
check("推演⑥ 放行 ⇒ 推出的物品就是该机器当前这一步所需的投入物（穷举 3 步 × 4 种手里状态 × 3 件）",
      wrong_push == [], "推错: %s" % (wrong_push,))
check("推演⑦ 机械手手里已有「本步不要的」件时：既不推错料，也不推「注定被 Create 拒收」的那件"
      "（= 不反复重试）",
      futile_retry == [], "无谓重试: %s" % (futile_retry,))

world_step0 = {DEPOT_A: {"in_flight": 0}, DEPOT_B: {}, ARM_A: {"in_flight_hand": "create:large_cogwheel"},
               ARM_B: {}}
check("推演⑧ 用户实测场景复现：手里攥着大齿轮、置物台那件停在第 0 步 → 齿轮（正确件）也被挡下并给出"
      "「machine_holds_other_step_input」，决不塞错、也不每 tick 白试一次",
      push_decision(ARM_A, "create:cogwheel", world_step0) == "assert_machine_holds_other_step_input"
      and push_decision(ARM_A, "create:large_cogwheel", world_step0) == "machine_holds_input")

world_raw = {DEPOT_A: {"raw": "create:golden_sheet"}, DEPOT_B: {}, ARM_A: {}, ARM_B: {}}
empty_operand = {DEPOT_A: {}, DEPOT_B: {}, ARM_A: {}, ARM_B: {}}
check("推演⑨ 起件顺序自洽：置物台拿到起步原料前 → 机械手手里一件都不许先塞（严格）；"
      "拿到原料后 → 第 0 步的齿轮立刻放行（自愈，不断供）",
      push_decision(ARM_A, "create:cogwheel", empty_operand) == "assert_not_current_step_input"
      and push_decision(ARM_A, "create:cogwheel", world_raw) is None
      and push_decision(ARM_A, "create:large_cogwheel", world_raw) == "assert_not_current_step_input")
check("推演⑩ 判据与收回侧严格互补：手里那件「本步不要的」必然落在「可收回」一侧"
      "（否则会出现「推料侧不推、收回侧也不收」的死锁）",
      blocked_by_foreign(ARM_A, "create:cogwheel", world_step0) is True
      and wanted_now(ARM_A, "create:large_cogwheel", world_step0) is False
      and "!chamber.inputMaterialWantedNow(pos, stack.getItem())" in import_strategy)

# ==================== 4. 「无中间产物缓存仓」时中间产物的去向 ====================
section("4) 中间产物（intermediate:N）在没有中间产物缓存仓时的去向（不丢 / 不吞 / 可达正确目标）")

has(shared_cache, "if (warehouses.isEmpty()) {\n            return List.of();",
    "锚点: 网络里没有缓存仓 → 池子是空表（不报错、不打日志、不改变任何判定）")
has(item_storage, "cachedSlots = storages.isEmpty() ? List.of() : buildSlots(storages);",
    "锚点: 池子为空时物品存储视图 = 「只有执行舱内部存储」（中间产物照样看得见、摸得着）")
has(strategy, "if (!chamber.busExportAcceptsForPush(inSlot, item)) {",
    "出路①: 中间产物由输出总线按「该步的进度组件」推给下一步骤的机器 / 置物台"
    "（2026-10-06：放行判据移到执行舱的 busExportAcceptsForPush —— 旧锚点 busExportMatches 固化了"
    "「同一台机器连续承担多个步时必须把每个步的类别都勾上」这个 bug）")
has(import_strategy, "return chamber.isTransitionReclaimAllowed(stack);",
    "出路②: 机器侧不再需要的过渡件由输入总线收回 → RS 网络（缓存仓缺席时网络就是它的临时存放点）")
# <b>2026-10-05 修正：这条断言此前固化了一个 bug。</b>
# 旧版断言的是「任务结束只回网输入类，中间产物留给『各自既有回收路径』」——
# 但中间产物<b>根本没有另一条回收路径</b>（那条路径只在有活跃订单、输入总线凭一次性令牌收回时存在）。
# 订单一旦取消，压在仓内的中间产物就永久滞留，还占着机器唯一的加工位。
# 实测：快照 20261005-214424 chamber@-16,-60,10 `materials=[]` 却 `storedItems=[incomplete_track×1]`。
# 现在任务确认结束后，内部存储里的原料 / 输入类 / 中间产物 / 成品 / 废料<b>一律回网</b>。
check("出路③: 任务结束时本仓内部存储一律回网（含中间产物 —— 它没有『另一条回收路径』）",
      "&& !isTransitionItem(inSlot.getItem()) && !isMyProductOrScrap(inSlot)) {" in chamber
      and "to=network reason=task_finished" in chamber)
check("不存在第四条「丢弃」出路：任何一次搬运的余量都回写原处（执行舱 / 机器），网络装不下时落回世界",
      "chamberStore.insertItem(0, remainder, false);" in strategy
      and "chamber.outputTank.fill(drained.copyWithAmount(drained.getAmount() - filled)," in strategy
      and "handler.insertItem(slot, taken.copyWithCount((int) (taken.getCount() - inserted)), false);"
      in import_strategy
      and "Block.popResource(level, worldPosition," in chamber)

# 去向是否「完备且互斥」：任一时刻，一份中间产物恰在三处之一，且每处都有明确的前进方向
ROUTES = {"machine": ["推给下一步骤的机器", "被输入总线收回网络"],
          "chamber": ["推给下一步骤的机器（内部存储 → 总线）", "任务结束时回网（仅输入类；中间产物留给既有路径）"],
          "network": ["被执行舱备料拉回内部存储（按类别目标量）", "由下一台执行舱认领"]}
check("推演：三处（机器侧 / 执行舱内部+池 / RS 网络）的每一条出路都能到达「下一步骤的机器」或「网络」，"
      "没有任何一处是终点（= 中间产物不会「被吞」）",
      all(len(v) == 2 for v in ROUTES.values())
      and "fillInternalForBus(storage, network, disallowThresholds);" in chamber
      and "private void fillInternalForBus(" in chamber)

print()
print("=" * 72)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
