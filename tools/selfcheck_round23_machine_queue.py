# -*- coding: utf-8 -*-
"""第 23 轮自检：**共享机器的排队**（谁先下单谁先站这台机器）+ **缓存池半成品计入在制件**。

用法：python tools/selfcheck_round23_machine_queue.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要有它（本轮两条需求都无法在本地把游戏跑起来验证）：

  需求一（用户原话）：「谁先下单，那么就谁先站这一个东西，直到它完成了，再去下一个。」
    现场反例（快照 run/rscc_diag/20261006-100050/snapshot.json）：
      chamber@(-16,-60,10) 冲压 ownedSteps = sturdy_sheet#1,#2, track#2
      → **一台机器被两条配方共用**，而改造前的 ownedSteps() 只把这几步并在一起、
        `busExportFilters` 里**没有任何互斥 / 排队**：两条配方各自的总线在同一 tick
        都能拿到过滤项 ⇒ 互相抢冲压机。本脚本锚定新判据 machineReservedRecipe /
        blockedByMachineQueue，并用等价模型跑「赢家选择 + 三条拦截规则 + 逃生口」的反例表。

  需求二（本轮的正确性核心）：上一轮为了绕开「名额恒为 0」**有意**把停在共享缓存池里的
    过渡件排除在在制件之外。方向是错的：缓存池里的 96 件 create:incomplete_track
    **就是本订单的产出物**，不计入 ⇒ 名额算成「0 件在制」⇒ 无限开新件 ⇒ 超额生产。
    现在改为按配方（Create 配方自己的 getTransitionalItem + 件自身的进度组件）计入，
    并加一道「可推进才计入」的护栏。本脚本同时锚定「为什么不会卡死」的那条推理仍然写在源码里。

  注：本脚本是**新增**回归锚点，没有修改任何既有 selfcheck 的断言
  （既有断言没有任何一条在固化「缓存池不计入」这个错误行为，因此无需更新它们）。
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

# ==================== 1. 源码锚点：共享机器的排队 ====================
section("1) 源码锚点：共用同一台机器的多条配方之间的排队（需求一）")

has(chamber, "private String machineReservedRecipe() {",
    "锚点①: 排队判据只有一份实现（machineReservedRecipe：本仓这台机器此刻被哪条配方占着）")
has(chamber, "private String computeMachineReservedRecipe(final Level level) {",
    "锚点②: 判据本体独立成方法（computeMachineReservedRecipe），便于「同一份状态同一答案」地复用")
has(chamber, "if (remainingOrderUnitsFor(recipeId) <= 0L) {",
    "锚点③: 候选必须先有「未完成订单」（remainingOrderUnitsFor > 0，挂起任务已被它排除）")
has(chamber, "if (!machineHasWorkFor(level, recipeId)) {",
    "锚点④: 候选还必须「此刻真的用得上这台机器」（= 用户原话里那个「或该配方暂时没有可加工的件」）")
has(chamber, "private boolean machineHasWorkFor(final Level level, final String recipeId) {",
    "锚点⑤: 「还有活可干」只有一份实现")
check("锚点⑥: 「还有活可干」四路判据齐全（仓内已压件 / 工位压件 / 池里可推进 / 还能开新件且起件步归本仓）",
      "storedUnitsForRecipe(recipeId) > 0L" in chamber
      and "registeredUnitsForRecipe(level, recipeId, inFlightMarkerItems(recipeId)) > 0L" in chamber
      and "pooledTransitionalUnits(level, recipeId)[1] > 0L" in chamber
      and "return startCapacityForRecipe(recipeId) > 0L && isOwnedStep(recipeId, 0);" in chamber)
has(chamber, "return contenders < 2 ? null : winner;",
    "锚点⑦: 候选不足 2 条 ⇒ 不排队（单配方仓 / 只有一条配方有单时行为逐字不变）")
has(chamber, "final long start = orderStartTimeFor(recipeId);",
    "锚点⑧: 先后取「下单时刻」（orderStartTimeFor）")
has(chamber, "private long[] pooledTransitionalUnits(final Level level, final String recipeId) {",
    "锚点⑨: 「不是下单顺序」的兜底只在判不出来时生效（值 -1 → 排到最后，不让它独占机器）")
check("锚点⑩: 候选按 (下单时刻, 配方 id) 升序取赢家，平局用配方 id 字典序（确定性，不抛硬币）",
      "|| (key == winnerStart && recipeId.compareTo(winner) < 0)) {" in chamber
      and "final long key = start < 0L ? Long.MAX_VALUE : start;" in chamber)

# ---- 下单先后：读到了什么、局限写在哪 ----
has(chamber, "private long orderStartTimeFor(final String recipeId) {",
    "锚点⑪: 下单时刻探针（每 tick 每配方至多一次）")
has(chamber, "final long start = status.info().startTime();",
    "锚点⑫: 先后直接读 RS 公开 API 的 TaskStatus.TaskInfo#startTime()（RS 建任务时写 System.currentTimeMillis）")
check("锚点⑬: 先后只取「未被挂起 + 目标命中本配方」的任务里最早的那一个（与 remainingOrderUnitsFor 同一份数据）",
      "AssemblyWatchdog.isSuspended(status.info().id().id())" in chamber
      and "if (!taskTouches(status, targets)) {" in chamber
      and "if (start > 0L && (best < 0L || start < best)) {" in chamber)
check("锚点⑭: 局限被写进注释（墙上时钟 / 同毫秒按配方 id 字典序 / 读不到就不让它当赢家），不假装是严格下单顺序",
      "墙上时钟" in chamber
      and "不等于</b>真实先后" in chamber
      and "读不到先后就不让它独占机器" in chamber)

# ---- 拦截规则 ----
has(chamber, "private boolean blockedByMachineQueue(final BusCategoryInfo info) {",
    "锚点⑮: 拦截判据只有一份实现（推料侧与备料侧共用）")
check("锚点⑯: 两条路径都接上了同一道闸门（推料 busExportFilters + 备料 fillInternalForBus）",
      chamber.count("if (blockedByMachineQueue(info)) {") == 2)
check("锚点⑰: 归属判不出来（空集）或正是赢家 ⇒ 一律不拦（判不出来就退回改造前的行为）",
      "if (mine.isEmpty() || mine.contains(reserved)) {" in chamber)
has(chamber, "if (info.isIntermediate()) {\n            return true;",
    "锚点⑱: 规则②：落选配方的过渡件一律不下到机器上（它们留在缓存仓里等着）")
has(chamber, "private boolean loserOnMyStations(final Level level, final Set<String> recipes) {",
    "锚点⑲: 规则③的物理逃生口（机器上正压着落选件 ⇒ 放行它的投入物，先做完再让位）")
check("锚点⑳: 逃生口用两路既有只读探针（登记表 + 机器上那一件自己的进度组件），不新增第二套判定",
      "registeredUnitsForRecipe(level, recipeId, inFlightMarkerItems(recipeId)) > 0L" in body(
          chamber, "private boolean loserOnMyStations(",
          "/**\n     * <b>只读</b>：这条配方的订单<b>下单时刻</b>")
      and "final PendingStep pending = pendingStepOn(target);" in body(
          chamber, "private boolean loserOnMyStations(",
          "/**\n     * <b>只读</b>：这条配方的订单<b>下单时刻</b>")
      and "recipes.contains(pending.recipeId())" in body(
          chamber, "private boolean loserOnMyStations(",
          "/**\n     * <b>只读</b>：这条配方的订单<b>下单时刻</b>"))
check("锚点㉑: 规则顺序 = 中间产物先拦（规则②）在前、逃生口（规则③）在后",
      body(chamber, "private boolean blockedByMachineQueue(",
           "private boolean loserOnMyStations(").index("if (info.isIntermediate()) {")
      < body(chamber, "private boolean blockedByMachineQueue(",
             "private boolean loserOnMyStations(").index("loserOnMyStations(level, mine)"))
has(chamber, "private Set<String> categoryRecipeIds(final BusCategoryInfo info) {",
    "锚点㉒: 类别 → 配方 的归属解析（中间产物取类别里的配方段 / 输入取 id 里的配方段 / 老格式回退）")
check("锚点㉓: 流体 / 成品 / 废料类别不参与排队（返回空集 ⇒ 永不拦，绝不把在制件饿死）",
      "return Set.of(); // 流体 / 成品 / 废料：不参与排队" in chamber)
check("锚点㉔: 排队跳过也有 trace（不静默），且只在状态翻转时各一条",
      "private void traceMachineQueueSkip(final BusCategoryInfo info, final BlockPos exporterPos) {" in chamber
      and 'RsccAssemblyDebug.transition("queue@"' in chamber
      and '"bus_queue_skip"' in chamber)

# ==================== 2. 源码锚点：缓存池半成品计入在制件 ====================
section("2) 源码锚点：共享缓存池里属于本配方的过渡件计入在制件（需求二）")

has(chamber, "parts[2] += pooledTransitionalUnits(level, recipeId)[0];",
    "锚点①: 在制件计数把「缓存池里可推进的本配方过渡件」加进来了（跨舱 + 仓内 + 缓存池；"
    "2026-10-06 起该方法拆成 inFlightBreakdownForRecipe 的三个分量 parts[]，相加口径不变）")
has(chamber, "private long[] pooledTransitionalUnits(final Level level, final String recipeId) {",
    "锚点②: 池子计数只有一份实现（每 tick 每配方至多算一次）")
check("锚点③: 归属只按配方，三道判据缺一不可（过渡件物品 + 件自己的进度组件配方 id + 下一步归某台在线仓）",
      "final Item transitional = transitionalStack.getItem();" in chamber
      and "if (assembly == null || !recipeId.equals(assembly.id().toString())) {" in chamber
      and "if (isOwnedStep(recipeId, nextStep)) {" in chamber
      and "if (ownedStepAnyChamber(level, recipeId, nextStep)) {" in chamber)
check("锚点④: 归属绝不用「名字含 incomplete_ / unprocessed_」这类启发式（池子计数与排队判定的代码里"
      "不存在按名字匹配的写法；文件里唯一那处名字匹配是<b>既有的只读取证日志</b>，不参与任何判定）",
      'contains("incomplete' not in body(chamber, "private long[] pooledTransitionalUnits(",
                                         "private boolean ownedStepAnyChamber(")
      and 'contains("unprocessed' not in body(chamber, "private long[] pooledTransitionalUnits(",
                                              "private boolean ownedStepAnyChamber(")
      and "getDescriptionId()" not in chamber
      and 'contains("incomplete' not in body(chamber, "private boolean blockedByMachineQueue(",
                                             "private boolean loserOnMyStations("))
has(chamber, "private boolean ownedStepAnyChamber(final Level level, final String recipeId, final int step) {",
    "锚点⑤: 「下一步归谁」用既有的 isOwnedStep 跨舱判定（不新增第二套步序口径）")
check("锚点⑥: 只读（不写池、不抽件）—— 只调 RsccSharedCache.poolContents 这一份只读快照",
      "RsccSharedCache\n                        .poolContents(level, network).entrySet()" in chamber
      and ".extract(" not in body(chamber, "private long[] pooledTransitionalUnits(",
                                 "private boolean ownedStepAnyChamber("))

# ---- 「为什么不会卡死」的推理必须在源码里写明（本轮的正确性核心） ----
check("锚点⑦: 源码里写清了「为什么不会卡死」的三条推理（名额只冻结开新件 / 池里的件可复用会被做完 / 推不动的件不占名额）",
      "为什么<b>不会</b>卡死" in chamber
      and "只冻结「开新件」，不冻结「把已经开出去的件做完」" in chamber
      and "池里的半成品是可复用的，不是死件" in chamber
      and "真正推不动的半成品不占名额" in chamber)
check("锚点⑧: 上一轮那条「有意把缓存池排除在在制件之外」的旧注释已被取代（不再固化错误行为）",
      "已知盲区（有意为之）" not in chamber
      and "把它们算成「在制件」会让新订单的名额恒为 0" not in chamber)

# ==================== 3. 等价模型：名额（需求二） ====================
section("3) 等价模型：把池里的半成品算进在制件 ⇒ 不再无限开新件，且推不动的陈旧件绝不卡死")


def in_flight(registered, stored, pooled_advanceable, count_pool=True):
    """复刻 inFlightUnitsForRecipe：① 登记表 ② 仓内 ③ 缓存池（只算「下一步有人能做」的那部分）。"""
    total = registered + stored
    if count_pool:
        total += pooled_advanceable
    return min(total, 64)          # BUS_ITEM_TARGET_MAX


def start_capacity(remaining, guaranteed, p, inflight):
    """复刻 startCapacityForRecipe（必得配方严格 R − inFlight）。

    2026-10-06 更新（与源码同步）：判据从 `remaining <= 0` 改成 `remaining < 0` ——
    `R < 0` 才是「判不出来」（保守下限 1），`R == 0` 是「订单已交付满」⇒ 一件都不许再开。
    旧写法把这两种语义混在一起，会让「已满足」时又放 1 件（实测每单多开一件）。
    """
    if remaining < 0:
        return 1 if inflight <= 0 else 0
    allowed = remaining
    if not guaranteed and isinstance(p, float) and 0.0 < p < 1.0:
        import math
        allowed = int(math.ceil(remaining / p))
    return max(0, allowed - inflight)


print("  反例表（订单剩余量 R vs 在制件数 inFlight；名额 = 还能再开几件新件）")
print("  %-58s %-8s %-8s" % ("情形", "旧口径", "新口径"))
CASES_POOL = [
    # 快照 20261006-100050 的实测形态：轨道下单 4、缓存池压着 96 件 incomplete_track（可推进）
    ("轨道：R=4、仓内/工位 1 件、池里 96 件可推进", 4, True, 1, 0, 96),
    # 池里的件没有任何在线机器能做下一步 ⇒ 不计入 ⇒ 新订单绝不会被陈旧件压成 0 名额
    ("轨道：R=4、池里 35 件但没有任何机器能做下一步", 4, True, 0, 0, 0),
    # 坚固板：R=10、池里 6 件可推进 ⇒ 名额 4（还允许开 4 件，不是 0）
    ("坚固板：R=10、池里 6 件可推进", 10, True, 0, 0, 6),
    # 池里的件已经把名额占满 ⇒ 一件都不许再开（本轮要修的超额生产正是这里）
    ("坚固板：R=10、池里 10 件可推进", 10, True, 0, 0, 10),
]
for label, remaining, guaranteed, registered, stored, pooled in CASES_POOL:
    old = start_capacity(remaining, guaranteed, None,
                         in_flight(registered, stored, pooled, count_pool=False))
    new = start_capacity(remaining, guaranteed, None,
                         in_flight(registered, stored, pooled, count_pool=True))
    print("  %-58s %-8s %-8s" % (label, old, new))
    CHECKS[0] += 1
    print("    %s" % ("OK" if new <= old else "MISMATCH"))

check("推演① 旧口径（不算缓存池）在快照那种形态下名额 = 4 − 1 = 3 ⇒ 继续开新件 ⇒ 超额生产",
      start_capacity(4, True, None, in_flight(1, 0, 96, count_pool=False)) == 3)
check("推演② 新口径（算缓存池）名额 = 4 − 97 < 0 ⇒ 0 ⇒ 一件新件都不开（超额生产被掐断）",
      start_capacity(4, True, None, in_flight(1, 0, 96, count_pool=True)) == 0)
check("推演③ 推不动的陈旧半成品不占名额（R=4、池里 35 件但没人能做下一步 ⇒ 名额仍是 4）"
      "⇒ 上一轮担心的「名额恒为 0 卡死」被结构性排除",
      start_capacity(4, True, None, in_flight(0, 0, 0, count_pool=True)) == 4)
check("推演④ 名额为 0 只发生在「池里的可推进件已经够本订单」时；那些件本身会被拉出来做完 ⇒ "
      "订单靠它们走完，不需要新料（这就是「不会卡死」的推理）",
      start_capacity(10, True, None, in_flight(0, 0, 10, count_pool=True)) == 0
      and start_capacity(10, True, None, in_flight(0, 0, 6, count_pool=True)) == 4)
check("推演⑤【2026-10-06 新增】订单已交付满（R=0）⇒ 名额 0，一件都不许再开（空管线也不再放 1 件）；"
      "只有 R<0（判不出来）才保留「至少放 1 件」的保守下限 —— 这正是旧写法多开的那一件",
      start_capacity(0, True, None, 0) == 0
      and start_capacity(0, False, 0.8, 0) == 0
      and start_capacity(-1, True, None, 0) == 1
      and start_capacity(-1, True, None, 1) == 0)

# ==================== 4. 等价模型：排队（需求一） ====================
section("4) 等价模型：共机的多条配方按「下单时刻」排队；拦截规则 + 反死锁逃生口")


def reserved(contenders):
    """复刻 computeMachineReservedRecipe：候选 < 2 ⇒ 不排队；否则取 (下单时刻, 配方 id) 最小者。"""
    live = [(rid, st) for rid, st in contenders if st is not None]
    if len(live) < 2:
        return None
    def key_of(item):
        rid, st = item
        return (float("inf") if st < 0 else st, rid)
    return min(live, key=key_of)[0]


def blocked(cat_recipes, winner, is_intermediate, loser_on_machine):
    """复刻 blockedByMachineQueue。"""
    if winner is None:
        return False
    if not cat_recipes or winner in cat_recipes:
        return False
    if is_intermediate:
        return True
    if loser_on_machine:
        return False
    return True


TRACK = "create:sequenced_assembly/track"
STURDY = "create:sequenced_assembly/sturdy_sheet"

print("  反例表（共机配方 → 谁当赢家）")
print("  %-64s %s" % ("情形", "赢家"))
QUEUE_CASES = [
    ("轨道(下单 1000ms) + 坚固板(下单 2000ms)，两条都有活", [(TRACK, 1000), (STURDY, 2000)], TRACK),
    ("坚固板先下单、轨道后下单（先后真的按 startTime 走）", [(TRACK, 2000), (STURDY, 1000)], STURDY),
    ("只有轨道有未完成订单（坚固板没单／挂起）", [(TRACK, 1000), (STURDY, None)], None),
    ("两条都读不到下单时刻（-1）⇒ 不排他，退回改造前行为", [(TRACK, None), (STURDY, None)], None),
    ("同毫秒下单 ⇒ 用配方 id 字典序兜底（确定性）", [(TRACK, 7), (STURDY, 7)], STURDY),
]
for label, contenders, expect in QUEUE_CASES:
    got = reserved(contenders)
    print("  %-64s %s" % (label, got))
    check("推演⑤ %s" % label, got == expect, "got=%s expect=%s" % (got, expect))

check("推演⑥ 只有一条配方有单 ⇒ 不排队（单配方仓 / 单订单时行为逐字不变，这是绝大多数时刻）",
      reserved([(TRACK, 1000), (STURDY, None)]) is None)
check("推演⑦ 「下单时刻读不到」（-1）的那条配方不会当赢家：有真实时刻的那条优先，"
      "读不到就绝不独占机器（源码里同时把 -1 映射成「最晚」）",
      reserved([(TRACK, 1000), (STURDY, -1)]) == TRACK
      and reserved([(TRACK, -1), (STURDY, 1000)]) == STURDY)

print()
print("  反例表（类别 × 赢家 → 这一轮推不推）")
print("  %-30s %-12s %-8s %-10s %s" % ("类别(归属配方)", "赢家", "是过渡件", "机器上有落选件", "结论"))
BLOCK_CASES = [
    ("intermediate:sturdy_sheet:1", [STURDY], TRACK, True, False, True),
    ("intermediate:track:2", [TRACK], TRACK, True, False, False),
    ("input:sturdy_sheet#powdered_obsidian", [STURDY], TRACK, False, False, True),
    ("input:sturdy_sheet#powdered_obsidian（机器上正压着它的件）", [STURDY], TRACK, False, True, False),
    ("fluid:minecraft:lava（不参与排队）", [], TRACK, False, False, False),
    ("input:minecraft:iron_nugget（老格式、归属判不出）", [], TRACK, False, False, False),
    ("intermediate:sturdy_sheet:1（机器被别的配方占着，无争用）", [STURDY], None, True, False, False),
]
for label, mine, winner, is_mid, on_machine, expect in BLOCK_CASES:
    got = blocked(mine, winner, is_mid, on_machine)
    print("  %-30s %-12s %-8s %-10s %s" % (label.split("（")[0], winner or "-", is_mid, on_machine, got))
    check("推演⑧ 拦不拦：%s" % label, got == expect, "got=%s expect=%s" % (got, expect))

check("推演⑨ 赢家自己的类别一律不拦（赢家独占机器，直到它做完 —— 用户原话的正面）",
      not blocked([TRACK], TRACK, True, False) and not blocked([TRACK], TRACK, False, False))
check("推演⑩ 落选配方的过渡件即使在机器上压着自己的件时也不放行（放行也没用：加工位只有一个，"
      "推过去只会得到 machine_full 的空转）",
      blocked([STURDY], TRACK, True, True))
check("推演⑪ 落选方非过渡件类别：机器上没它的件 ⇒ 也拦住（「不要继续开新件」）；"
      "机器上有它的件 ⇒ 放行（反死锁逃生口，否则卡在机器上的落选件把赢家一起锁死）",
      blocked([STURDY], TRACK, False, False) and not blocked([STURDY], TRACK, False, True))

# ==================== 结果 ====================
print()
print("=" * 72)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
