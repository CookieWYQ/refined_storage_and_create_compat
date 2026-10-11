# -*- coding: utf-8 -*-
"""第 57 轮自检：输出总线「分配」的**前进保证**与复杂度回归（卡住 → 恢复 / 永久卡死）。

用法：python tools/selfcheck_round57_bus_alloc_liveness.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

═══════════════════════════════════════════════════════════════════════════════
用户报告（无日志，只能靠源码分析 + 自证）
═══════════════════════════════════════════════════════════════════════════════
「针对这个很多的这一个输入输出总线，然后呢进行**分配**的时候……偶尔会出现，也不是偶尔吧，
算是比较经常了，会出现它这个分配的时候突然卡住。然后呢，过一会有可能恢复了，有可能也没有恢复。
然后呢，可能最后一直卡在那导致啥也不动。」

分配 = 执行仓把「某个类别这一轮交给哪几条输出总线」算出来并逐台下发过滤项
（SequenceExecutionChamberBlockEntity#busExportFilters → #pushBusTurnToExporters）。

本脚本钉住本轮修掉的三条「可能永久不回退」的路径：
  ① **连接检测被静默截断**（旧 `BUS_LINK_MAX_STEPS = 64`，到点 while 直接结束）⇒ 线缆长 / 总线多时，
     排在后面的总线**从来不在可达集合里** ⇒ 永远拿不到过滤项。旧实现没有任何东西会让它回来
     （「有时恢复」只可能来自区块加载顺序变了导致截断点不同）。
     → 改为 `BUS_LINK_TICK_BUDGET` 每 tick 预算 + 跨 tick 续扫 + **只有扫完才发布快照**。
  ② **一台总线抛异常 ⇒ 排在它后面的总线这一轮全都不刷**（刷新循环里没有 try/catch），
     且下一次迭代顺序相同 ⇒ 永久饿死。
     → 改为逐台 try/catch 隔离 + 轮转预算 + 游标（进度记录）。
  ③ **某台总线不在可达集合里 ⇒ 无人刷新它，且全程静默**（刷新是「拿到过滤项」的唯一入口）。
     → 新增停滞看门狗：超过 `BUS_TURN_STALE_TICKS` 没有任何链上成员刷过它 ⇒ 强制作废连接检测、
       重扫并打一条锚点日志（要么被刷到、要么被点名，绝不静默冻结）。

另外：把「分配」链路上随总线台数平方 / 立方增长的成本压掉（每 tick 备忘 + 预建集合），
但**刻意不动**任何会被「本 tick 自己刚推的那一份」改变的判据（容器内容一律现读）——
那些判据是「同一工位最多一份未消耗投入物」这条硬不变式的唯一凭据。
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
CHAMBER = os.path.join(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
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


def body(text, start, end):
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


chamber = read(CHAMBER)
guard = read(GUARD)

# ======================================================================================
section("① 源码锚点：三条「永久不回退」路径的修法确实在源码里（不是只在注释里）")
# ======================================================================================

hasnt(chamber, "private static final int BUS_LINK_MAX_STEPS",
      "旧的静默截断上限 BUS_LINK_MAX_STEPS 作为常量/字段已被彻底移除（只在注释里作为历史说明出现）")
hasnt(chamber, "<= BUS_LINK_MAX_STEPS", "旧上限没有被任何比较表达式继续引用")
hasnt(chamber, "{@link #BUS_LINK_MAX_STEPS}", "旧上限的 javadoc 引用也已清掉（不留悬空链接）")
hasnt(chamber, "while (!queue.isEmpty() && steps <= BUS_LINK_MAX_STEPS)",
      "旧的「到点就结束 while」（静默丢尾巴的现场）已不存在")
has(chamber, "private static final int BUS_LINK_TICK_BUDGET = 256;",
    "洪泛每 tick 预算（前进保证：本 tick 扫不完不算放弃）")
has(chamber, "private static final int BUS_LINK_HARD_CAP = 4096;",
    "洪泛硬上限（触顶显式报出，不静默）")
has(chamber, "private static final int BUS_TURN_BUDGET_PER_TICK = 8;",
    "分派每 tick 刷新的总线台数预算（单刻有界，不再一次性刷全部）")
has(chamber, "private static final int BUS_TURN_STALE_TICKS = 60;",
    "分派停滞看门狗窗口（永久刷不到 ⇒ 强制重扫 + 点名）")

has(chamber, "private static final class BusLinkFlood {",
    "可续扫洪泛状态类（队列 + 已访问 + 上一次完整快照）")
has(chamber, "List<BlockPos> snapshot;", "洪泛持有「上一次完整结果」（续扫期间继续对外服务）")
has(chamber, "void restart(final BlockPos origin, final long now) {",
    "洪泛可重新开始（并保留旧快照直到新结果就绪）")
has(chamber, "private List<BlockPos> collectBusPositions(final boolean restrictToMyChain, final boolean importers) {",
    "输出 / 输入总线连接检测收敛到唯一实现（同一套预算 + 续扫 + 发布口径）")
has(chamber, "private void advanceBusLinkFlood(",
    "每 tick 只推进一次的续扫入口（多个调用方共享同一份进度）")
has(chamber, "private void stepBusLinkFlood(",
    "洪泛的一步（至多 budget 格；Integer.MAX_VALUE = 一次扫完）")
has(chamber, "private void publishBusLinkSnapshot(final BusLinkFlood flood, final boolean importers) {",
    "**只有扫完（或触顶）才发布**——这是「绝不发布半份集合」的落点")
has(chamber, "link_flood_cap reached",
    "触顶时打可核对的锚点日志（绝不静默截断）")
has(chamber, "if (flood.snapshot == null) {",
    "首次访问（无快照）⇒ 一次扫完：读档 / 开界面不会看到「前几秒总线全不见」")

has(chamber, "private void pushBusTurnToExporters() {", "分派入口仍在（唯一实现）")
turn = body(chamber, "private void pushBusTurnToExporters() {", "private void busTurnWatchdog(")
has(turn, "try {", "分派循环里有 try/catch（故障隔离：一台坏掉不带走后面的）")
has(turn, "exporter.rscc$refreshBusTurn();", "仍在调用总线的刷新入口")
has(turn, "} catch (final RuntimeException failure) {", "捕获的是 RuntimeException（能力查询 / 方块实体读取那一类）")
has(turn, "busturnfail@", "刷新失败有翻转日志（同一条总线同一原因只打一次）")
has(turn, "busTurnCursor++", "轮转游标推进（进度记录 = 本 tick 处理到哪）")
has(turn, "budget > 0 && visited < total", "每 tick 有界（至多 BUS_TURN_BUDGET_PER_TICK 台）")
has(turn, "busTurnSweepActive = visited < total;",
    "本 tick 没轮完 ⇒ 标记「一轮未扫完」，下一 tick 继续（不放弃整轮）")
has(turn, "if (busTurnCursor >= total) {\n                busTurnCursor = 0; // 一轮走到底",
    "游标越界检查在循环内（避免自己制造 IndexOutOfBounds ⇒ 那一类「后面全不刷」的事故）")
has(chamber, "} else if (busRoundRobinActive || busTurnSweepActive) {",
    "调度器在一轮未扫完时每 tick 继续调（整轮耗时夹在 ceil(台数/预算) tick）")

has(chamber, "private void busTurnWatchdog(final Level level) {", "停滞看门狗存在")
watchdog = body(chamber, "private void busTurnWatchdog(final Level level) {", "private void invalidateBusLinkFloodsForChain(")
has(watchdog, "lastRefresh < staleBefore", "判据：窗口内没有任何链上成员刷过它")
has(watchdog, "invalidateBusLinkFloodsForChain(members);", "强制重扫（明确的重试点，不是「永久不可用」）")
has(watchdog, "busturnstale@", "强制重扫时点名（绝不静默冻结）")
has(watchdog, "chainMembers()", "判据是「链上任何成员」而不是本仓（同链另一台仓刷它也算正常）")

# ======================================================================================
section("② 成本：随总线台数增长的那几处（本轮压掉的与刻意保留的）")
# ======================================================================================

has(chamber, "private List<BlockPos> supplyTargetsProbeChain;", "供料目标（链口径）每 tick 备忘")
has(chamber, "private List<BlockPos> supplyTargetsProbeSelf;", "供料目标（物理口径）每 tick 备忘")
has(chamber, "private Map<BlockPos, BlockPos> supplyStationsProbe;", "供料工位表每 tick 备忘")
has(chamber, "private BlockPos stationKeyCached(final Level level, final BlockPos target) {",
    "工位归属每 tick 备忘（结构性探针，安全）")
has(chamber, "private List<BlockPos> stationSiblings(final Level level, final BlockPos station) {",
    "「工位 → 同工位的全部供料目标」预建分组（把 O(全部目标) 的兄弟查找降到 O(同工位目标数)）")
has(chamber, "private Set<Item> pipelineCategoryItems() {",
    "本仓类别物品并集每 tick 构建一次（替换 machineBusyOnPipeline 里 O(类别×物品) 的重复扫描）")
has(chamber, "for (final BlockPos other : stationSiblings(level, station)) {",
    "supplyTargetHoldsItem 用预建分组（不再现场遍历全部供料目标）")
hasnt(chamber, "occupiedItemsCached", "容器内容**没有**被缓存（那是「同一工位最多一份」不变式的凭据）")
has(chamber, "final Set<Item> held = occupiedItemsOf(level, target);",
    "machineBusyOnPipeline 仍旧**现读**容器（刻意：必须看得见本 tick 自己刚推的那一份）")

busy = body(chamber, "private boolean shareFrontBusyFor(", "private boolean machineBusyOnPipeline(")
order = [busy.index("if (supplyTargetHoldsItem(level, target, item)) {"),
         busy.index("if (targetCanAccept(level, target, item)) {"),
         busy.index("if (machineBusyOnPipeline(level, target)) {"),
         busy.index("if (stepUnitInFlightOn(target)) {")]
check("让位判据的四条「前位在干活 ⇒ 不让位」顺序一字未改（本轮只改成本，不改语义）",
      order == sorted(order), "order=%s" % order)
has(busy, "if (supplyTargetHoldsItem(level, target, item)) {\n                return false;\n            }",
    "锚点：前位工位已经握着这一件 ⇒ 不算收不下（硬不变式仍在）")

# ======================================================================================
section("③ 既有保护一条都没被放宽（放宽会引入新回归）")
# ======================================================================================

for needle, name in [
    ("private boolean blockedByMachineQueue(final BusCategoryInfo info) {", "blockedByMachineQueue 仍在"),
    ("private long startCapacityForRecipe(final String recipeId) {", "startCapacityForRecipe 仍在"),
    ("private long inFlightUnitsForRecipe(final String recipeId) {", "inFlightUnitsForRecipe 仍在"),
    ("private long[] pooledTransitionalUnits(final Level level, final String recipeId) {", "pooledTransitionalUnits 仍在"),
    ("public boolean rscc$anyDestinationStuck() {", "rscc$anyDestinationStuck 仍在"),
    ("public boolean pushStalledOnDestination() {", "pushStalledOnDestination 仍在"),
    ("if (index >= exportShare(owners.size())) {", "份额闸门仍在（下标 ≥ 份额拿不到过滤项）"),
    ("if (!unitCapacityLeft || !ownsFallbackTurn(exporterPos, owners, shareProbeOf(info))) {",
     "让位仍同时要求「有在制名额」与 ownsFallbackTurn"),
]:
    has(chamber, needle, name)
has(guard, "NEXT_FOR_MACHINE", "SequenceMaterialGuard 的 NEXT_FOR_MACHINE 仍在")
has(chamber, "private boolean shareFrontBusy(final List<BlockPos> owners, final int index) {",
    "旧口径让位判据仍保留（item == null 的类别退化成它）")

# ======================================================================================
section("④ 前进保证模型 A：洪泛（旧：单次 64 格静默截断；新：跨 tick 续扫 + 只在扫完时发布）")
# ======================================================================================

BUDGET = 256        # 与源码 BUS_LINK_TICK_BUDGET 一致
HARD_CAP = 4096     # 与源码 BUS_LINK_HARD_CAP 一致
CACHE_TICKS = 20    # 与源码 BUS_CONNECT_CACHE_TICKS 一致
OLD_CAP = 64        # 旧 BUS_LINK_MAX_STEPS


def buses_in(cells):
    """线缆网络里有几台总线：每 2 格线缆挂 1 台（「很多总线」的现场）。"""
    return cells // 2


def old_flood(cells, ticks, start=0):
    """旧实现：每 CACHE_TICKS 重扫一次，单次最多 OLD_CAP+1 格后**静默结束**。"""
    published = []
    for t in range(start, start + ticks):
        if (t - start) % CACHE_TICKS == 0:
            published.append(min(cells, OLD_CAP + 1))
    return published


def new_flood(cells, ticks, start=0):
    """新实现：可续扫洪泛（每 tick 至多 BUDGET 格，硬上限 HARD_CAP，只有扫完才发布）。

    返回 (每 tick 对外可见的已完成格数, 每 tick 实际推进格数, 是否触顶, 推进期间的 tick 数)。
    """
    published = []          # 每一 tick 对外可见的「已完成格数」
    polled_ticks = []       # 每一 tick 实际推进的格数
    running_ticks = []      # 哪些 tick 处于「续扫中」（进展保证只在这些 tick 上成立）
    snapshot = None
    running = False
    polled = 0
    next_scan = start
    capped = False
    for t in range(start, start + ticks):
        if not running and t >= next_scan:
            running = True
            polled = 0
            capped = False
            next_scan = t + CACHE_TICKS
        advanced = 0
        if running:
            running_ticks.append(t)
            left = BUDGET
            while running and left > 0:
                if polled >= cells:
                    running = False
                    snapshot = polled
                    break
                if polled >= HARD_CAP:
                    running = False
                    capped = True
                    snapshot = polled
                    break
                polled += 1
                advanced += 1
                left -= 1
        if snapshot is None:
            published.append(None)   # 还没有任何完整结果（bootstrap 分支会同步扫完，见源码）
        else:
            published.append(snapshot)
        polled_ticks.append(advanced)
        if snapshot is None and running:
            # bootstrap 分支：首次调用一次扫完（有界）
            while running:
                if polled >= cells:
                    running = False
                    snapshot = polled
                    break
                if polled >= HARD_CAP:
                    running = False
                    capped = True
                    snapshot = polled
                    break
                polled += 1
            published[-1] = snapshot
    return published, polled_ticks, capped, running_ticks


MANY_CELLS = 400   # 「很多总线」：400 格线缆 ≈ 200 台总线（远超旧的 64 格上限）

old_pub = old_flood(MANY_CELLS, 200)
old_lost = [buses_in(MANY_CELLS) - buses_in(p) for p in old_pub]
check("旧实现：线缆一长（%d 格 ≈ %d 台总线）就**每次都**只发布 %d 格 ⇒ 尾部 %d 台总线永久缺席"
      "（任何 tick 都不会出现）"
      % (MANY_CELLS, buses_in(MANY_CELLS), OLD_CAP + 1, old_lost[0]),
      min(old_lost) > 0 and max(old_lost) == min(old_lost),
      "lost=%d..%d" % (min(old_lost), max(old_lost)))
check("旧实现：尾部缺席**不是「过一会恢复」**——200 tick 里发布集合逐字相同（永久冻结而非抖动）",
      len(set(old_pub)) == 1, "published=%s" % sorted(set(old_pub)))

new_pub, new_polled, new_capped, new_running = new_flood(MANY_CELLS, 200)
new_lost = [buses_in(MANY_CELLS) - buses_in(p) for p in new_pub if p is not None]
check("新实现：一次完整洪泛在 ceil(格数/预算)=%d tick 内扫完，此后对外可见集合**完整**（尾部缺席 = 0）"
      % ((MANY_CELLS + BUDGET - 1) // BUDGET),
      min(new_lost) == 0 and max(new_lost) == 0, "lost=%d..%d" % (min(new_lost), max(new_lost)))
check("新实现：续扫期间每一 tick 都**有进展**（推进 ≥1 格），不存在「本 tick 什么都没做就等下一轮」",
      all(new_polled[t] > 0 for t in new_running),
      "running=%d ticks; polled=%s" % (len(new_running), [new_polled[t] for t in new_running[:6]]))
check("新实现：第一次洪泛在 %d tick 内扫完（bootstrap 同步分支 + 续扫），不存在「永远扫不完」"
      % next(t for t, p in enumerate(new_pub) if p == MANY_CELLS and t > 0),
      any(p == MANY_CELLS for p in new_pub))
check("新实现：对外可见的集合**永远是一份完整结果**（绝不会发布半份：发布值只取 0 / 全部格数）",
      all(p is None or p in (0, MANY_CELLS) for p in new_pub),
      "published=%s" % sorted({p for p in new_pub}))
check("新实现：完整结果一旦就绪就不再缩水（单调不降 ⇒ 不会有「总线忽然消失」）",
      all(b >= a for a, b in zip([p for p in new_pub if p is not None],
                                 [p for p in new_pub if p is not None][1:])))
huge = new_flood(200000, 40)
check("新实现：异常巨大的线缆网络触硬上限时**仍然发布**（并标记 capped ⇒ 源码打 link_flood_cap 日志），"
      "不是静默丢弃", huge[2] is True and max(p for p in huge[0] if p is not None) == HARD_CAP)
has(chamber, "if (!flood.running && (flood.snapshot == null || now >= flood.nextScanAt)) {",
    "「刚作废（无快照）」时无条件重开洪泛 —— 否则作废点落在下一次复扫之前，"
    "本 tick 既不重开也不推进 ⇒ 调用方看到空的可达集合（所有总线都拿不到过滤项）")


def after_invalidate(cells, invalidate_at, ticks, buggy):
    """作废（结构 / 模式变化）之后的可达集合。

    buggy=True 复刻「只在 now >= nextScanAt 时才重开」的写法（漏掉 `snapshot == null` 这一支）：
    作废点落在下一次复扫时刻之前时，本 tick 既不重开也不推进 ⇒ 调用方看到空集合。
    """
    snapshot = cells            # 作废前：已有一份完整结果
    running = False
    polled = 0
    next_scan = 0
    published = []
    for t in range(ticks):
        if t == invalidate_at:
            snapshot = None
            running = False
        restart = (t >= next_scan) if buggy else (snapshot is None or t >= next_scan)
        if not running and restart:
            running = True
            polled = 0
            next_scan = t + CACHE_TICKS
        if running:
            left = BUDGET
            while running and left > 0:
                if polled >= cells:
                    running = False
                    snapshot = polled
                    break
                polled += 1
                left -= 1
            if snapshot is None and running:
                # bootstrap 分支（源码：snapshot == null ⇒ 一次扫完，有界）
                while running:
                    if polled >= cells:
                        running = False
                        snapshot = polled
                        break
                    polled += 1
        published.append(snapshot)
    return published


inv_ok = after_invalidate(MANY_CELLS, 5, 40, buggy=False)
inv_bug = after_invalidate(MANY_CELLS, 5, 40, buggy=True)
check("新实现：作废之后**当 tick 就重扫并恢复完整集合**（第 %d tick 起可见集合 = 完整；不停摆）"
      % next(t for t, p in enumerate(inv_ok) if t >= 5 and p == MANY_CELLS),
      all(p == MANY_CELLS for p in inv_ok[5:]))
check("反例（证明这条锚点不是形式主义）：若漏掉「无快照即重开」，作废后会长达 %d tick 返回空集合"
      "（所有总线都拿不到过滤项 ⇒ 表现正是「突然卡住」）"
      % sum(1 for t, p in enumerate(inv_bug) if t >= 5 and p is None),
      any(p is None for p in inv_bug[5:]))

# ======================================================================================
section("⑤ 前进保证模型 B：分派轮转（预算 + 游标 + 故障隔离 + 停滞看门狗）")
# ======================================================================================

BUS_BUDGET = 8      # 与源码 BUS_TURN_BUDGET_PER_TICK 一致
STALE = 60          # 与源码 BUS_TURN_STALE_TICKS 一致
SCHEDULE = 20       # 与源码 BUS_SCHEDULE_INTERVAL_TICKS 一致
GRACE = STALE       # 首次只上表不判定（宽限期）


class Dispatch(object):
    """复刻 pushBusTurnToExporters + tickBusScheduler 刷新段 + busTurnWatchdog。"""

    def __init__(self, reachable, owners, failing=(), isolate=True, watchdog=True):
        self.reachable = list(reachable)   # 连接检测可达的总线（有序）
        self.owners = list(owners)         # 归属表里的总线（可能有人不可达）
        self.failing = set(failing)        # 每次刷新都抛异常的总线
        self.isolate = isolate             # False = 旧实现（无 try/catch，抛了就跳出整轮）
        self.watchdog = watchdog
        self.cursor = 0
        self.sweep_active = False
        self.refresh_at = {}
        self.first_at = {}                 # 每台总线**第一次**被刷到的 tick（公平性判据）
        self.forced = []
        self.fail_log = set()
        self.watchdog_tick = None
        self.passes = 0

    def tick(self, t):
        if not self.isolate:
            # ── 旧实现：`for (bus : connectedExporterPositions()) { refresh(bus); }`
            #    没有游标、没有预算；任一台抛异常就跳出整个 for（后面的这一轮全不刷），
            #    而下一次仍从第 0 台开始 ⇒ 同一台仍抛 ⇒ 后面的总线**永久**刷不到。
            if self.reachable and t % SCHEDULE == 0:
                self.passes += 1
                for pos in self.reachable:
                    if pos in self.failing:
                        self.fail_log.add(pos)
                        break
                    self.refresh_at[pos] = t
                    if pos not in self.first_at:
                        self.first_at[pos] = t
            self._watchdog(t)
            return
        due = (t % SCHEDULE == 0) or self.sweep_active
        if due and self.reachable:
            self.passes += 1
            total = len(self.reachable)
            if self.cursor >= total:
                self.cursor = 0
            budget = BUS_BUDGET
            visited = 0
            while budget > 0 and visited < total:
                # 越界检查必须在循环内（与源码同款修正）：游标从中间走到末尾时 visited 还很小。
                if self.cursor >= total:
                    self.cursor = 0
                pos = self.reachable[self.cursor]
                self.cursor += 1
                visited += 1
                budget -= 1
                if pos in self.failing:
                    self.fail_log.add(pos)
                    continue             # 新实现：只跳过这一台（故障隔离）
                self.refresh_at[pos] = t
                if pos not in self.first_at:
                    self.first_at[pos] = t
            if self.cursor >= total:
                self.cursor = 0
            self.sweep_active = visited < total
        self._watchdog(t)

    def _watchdog(self, t):
        if not self.watchdog:
            return
        if self.watchdog_tick is None:
            self.watchdog_tick = t + GRACE
            return
        if t < self.watchdog_tick:
            return
        self.watchdog_tick = t + STALE
        starved = [p for p in self.owners if self.refresh_at.get(p, -10 ** 9) < t - STALE]
        if starved:
            self.forced.append((t, starved[0]))


def run(dispatch, ticks):
    for t in range(ticks):
        dispatch.tick(t)
    return dispatch


# ---- B1 公平性：200 台总线，任意一台都必须在 ceil(N/预算) tick 内被刷到（明确的重试点） ----
big = run(Dispatch(range(200), range(200)), 200)
first = big.first_at
worst = max(first[p] for p in range(200) if p in first)
check("B1 公平性：200 台总线**全部**被刷到，最慢一台在第 %d tick 首次刷到（预算 8 ⇒ 一轮 ≤ ceil(200/8)+1=26 tick）"
      % worst,
      len(first) == 200 and worst <= 25, "missing=%s worst=%s" % ([p for p in range(200) if p not in first], worst))
per_tick = {}
for p, at in first.items():
    per_tick[at] = per_tick.get(at, 0) + 1
check("B1b 每 tick 至多刷 %d 台（单刻有界，不再「一次刷完 200 台」）" % BUS_BUDGET,
      max(per_tick.values()) <= BUS_BUDGET, "max/tick=%d" % max(per_tick.values()))

# ---- B2 故障隔离：一台总线的刷新每次都抛异常，后面的总线不许被饿死 ----
old_d = run(Dispatch(range(10), range(10), failing={2}, isolate=False), 1000)
new_d = run(Dispatch(range(10), range(10), failing={2}, isolate=True), 1000)
old_starved = [p for p in range(3, 10) if p not in old_d.refresh_at]
new_starved = [p for p in range(10) if p not in {2} and p not in new_d.refresh_at]
check("B2 旧实现：第 3 台总线一直抛异常 ⇒ 排在它后面的 %d 台在 1000 tick 内**一次都没刷到**"
      "（永久卡死，且循环顺序固定 ⇒ 永远不会自愈）" % len(old_starved),
      len(old_starved) == 7, "starved=%s" % old_starved)
check("B2b 新实现：同一故障下其余 9 台**全部**被刷到（故障隔离），坏的那台每次轮到都重试",
      new_starved == [] and len(new_d.fail_log) == 1,
      "starved=%s failed=%s" % (new_starved, sorted(new_d.fail_log)))

# ---- B3 停滞看门狗：某台总线不在可达集合里（区块未加载 / 归属未解析） ----
nowd = run(Dispatch([0, 1, 2], [0, 1, 2, 3], watchdog=False), 300)
wd = run(Dispatch([0, 1, 2], [0, 1, 2, 3], watchdog=True), 300)
check("B3 旧实现（没有看门狗）：归属表里的第 4 台永远不在可达集合里 ⇒ 全程静默、"
      "没有任何重试点（这就是「最后一直卡在那、啥也不动」里最难自愈的一种）",
      nowd.forced == [] and 3 not in nowd.refresh_at)
check("B3b 新实现：看门狗在窗口内必然触发一次强制重扫并点名（明确的重试点：第 %s tick）"
      % (wd.forced[0][0] if wd.forced else "-"),
      len(wd.forced) >= 1, "forced=%s" % wd.forced[:3])

# ---- B4 形式化：不存在「既无进展、又无重试点」的永久冻结态 ----
def frozen_states(dispatch, ticks):
    """返回 [(t, bus)]：该 tick 前 STALE tick 内既没被刷到、也没有强制重扫（= 无进展无重试点）。"""
    out = []
    forced_at = [t for t, _ in dispatch.forced]
    for t in range(2 * STALE, ticks):
        if any(t - STALE <= f <= t for f in forced_at):
            continue
        for p in dispatch.owners:
            at = dispatch.refresh_at.get(p)
            if at is None or t - at > STALE:
                out.append((t, p))
                break
    return out


check("B4 形式化（无永久冻结态）：200 台总线 + 3 台不可达（在归属表里但刷不到）跑 600 tick，"
      "任何时刻都不存在「窗口内既没进展、也没有强制重扫」的总线",
      frozen_states(run(Dispatch(range(200), list(range(200)) + [900, 901, 902]), 600), 600) == [])
check("B4b 反例（证明判据不是空转）：同样的场景关掉看门狗后，判据**必然**报出冻结态",
      len(frozen_states(run(Dispatch(range(200), list(range(200)) + [900, 901, 902],
                                    watchdog=False), 600), 600)) > 0)

# ======================================================================================
section("⑥ 复杂度对比：分配链路里「世界查询」的次数（旧 vs 新，N=10/50/200）")
# ======================================================================================

CATEGORIES = 8      # 类别数（每步各一类 + 输入类）
ITEMS_PER_CAT = 2   # 每类物品数
SIBLINGS = 2        # 同一工位上的供料目标数（典型：置物台 + 对着它的机械手）


def old_lookups(n, share=1):
    """旧实现：一次「把所有总线的清单刷一遍」里的方块实体 / 能力查询次数。

    * 份额外的 (n-share) 条总线走让位判据 shareFrontBusyFor：
      - 每条前位工位一次 supplyTargetOf（1 次查询）
      - supplyTargetHoldsItem：**现场遍历全部 n 个供料目标**做 stationKey（operatingPosOf → 1 次查询/目标）
        + 自己 / 工位本体的 holdsItemAt（2 次）
      - machineBusyOnPipeline：一次 occupiedItemsOf（1 次）+ **类别×物品**的集合扫描
      - stepUnitInFlightOn：1 次
    """
    lookups = 0
    for b in range(n):
        lookups += 1                                  # 本总线 supplyTargetOf
        lookups += 1                                  # occupiedInputMaterialsAt 的 supplyTargetOf
        if b >= share:
            for _ in range(share):
                lookups += 1                          # 前位 supplyTargetOf
                lookups += n                          # ← supplyTargetHoldsItem 现场遍历全部供料目标
                lookups += 2                          # 自己 + 工位本体的 holdsItemAt
                lookups += 1                          # machineBusyOnPipeline 的 occupiedItemsOf
                lookups += 1                          # stepUnitInFlightOn
    return lookups, n * CATEGORIES * ITEMS_PER_CAT    # (世界查询, 类别×物品集合扫描)


def new_lookups(n, share=1):
    """新实现：同一轮里的世界查询次数。

    * 每 tick 一份：供料目标表（n 次枚举）+ 工位归属备忘（n 次 operatingPosOf）
    * 每 tick 一次：类别物品并集（CATEGORIES × ITEMS_PER_CAT，只做一次）
    * 每条总线：2 次 supplyTargetOf；份额外总线每条前位工位：
      1 次 supplyTargetOf + 现读 3 次（自己 / 工位本体 / 同工位兄弟）+ 1 次 occupiedItemsOf + 1 次 stepUnit
    """
    lookups = n + n
    scans = CATEGORIES * ITEMS_PER_CAT
    for b in range(n):
        lookups += 1
        lookups += 1
        if b >= share:
            for _ in range(share):
                lookups += 1
                lookups += 3
                lookups += 1
                lookups += 1
    return lookups, scans


rows = []
for n in (10, 50, 200):
    o, o_scan = old_lookups(n)
    w, w_scan = new_lookups(n)
    rows.append((n, o + o_scan, w + w_scan))
    print("  N=%-4d 旧 = %-8d 新 = %-8d 降幅 = %.1f×" % (n, o + o_scan, w + w_scan,
                                                        (o + o_scan) / float(w + w_scan)))
n10, n50, n200 = rows[0][2], rows[1][2], rows[2][2]
o10, o50, o200 = rows[0][1], rows[1][1], rows[2][1]
check("C1 旧实现的成本随台数**平方**增长：N 从 50 → 200（×4）成本 ×%.1f（≈16）"
      % (o200 / float(o50)), o200 / float(o50) > 10.0)
check("C2 新实现近似线性：N 从 50 → 200 成本 ×%.1f（< 6）" % (n200 / float(n50)),
      n200 / float(n50) < 6.0)
check("C3 绝对降幅随台数拉开：N=200 时降 %.1f×（N=10 时 %.1f×）"
      % (o200 / float(n200), o10 / float(n10)),
      o200 / float(n200) > 10.0 and o200 / float(n200) > o10 / float(n10))

index_cmp = {n: n * n for n in (10, 50, 200)}
print("  说明：归属表下标查找 owners.indexOf 仍是 O(N) 的**纯整数比较**（未变，且被既有自检锚定）："
      "N=200 时 %d 次比较 ≈ 远低于一次方块实体查询的成本；它不随总线台数增长而触碰世界。" % index_cmp[200])
has(chamber, "final int index = owners.indexOf(exporterPos);",
    "归属表下标查找保留（纯整数比较；既有的份额自检锚定它）")

# ======================================================================================
print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - " + item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
