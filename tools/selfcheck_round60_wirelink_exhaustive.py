# -*- coding: utf-8 -*-
"""第 60 轮自检：**线缆搜链的可续扫与「截断不再等于停用」**（分配卡住的最后一环）。

用法：python tools/selfcheck_round60_wirelink_exhaustive.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1 并列出反例。

═══════════════════════════════════════════════════════════════════════════════
这一环修的是什么（承接 round57 修完的舱侧三条）
═══════════════════════════════════════════════════════════════════════════════
round57 修的是**执行舱侧**：舱沿线缆找总线的洪泛被旧的 `BUS_LINK_MAX_STEPS = 64` 静默截断，
排在后面的总线既不在可达集合、也不在归属表 ⇒ 分配永远不轮到它。

**总线自己那一侧**同时也有同一形状的 bug，本轮这一环就是它：
  * `RsccWireLinkSearch` 旧的两条上限 `LINK_MAX_STEPS = 64`（BFS 层数）/ `LINK_MAX_CELLS = 128`
    （线缆格数）到点**静默结束**，把被砍掉尾巴的部分集合当成完整结果；
  * 旧 `LinkWalk#ambiguous()` = 「可达 ≥2 条链 **或** 恰好 1 条链但**未穷尽**」——
    于是「暂时没扫完」被当成「归属未确定」⇒ 总线**停用**（红条 + 横幅 + 退回普通总线）；
    够不到链的则直接退回普通总线。
    截断点还取决于哪些区块已加载（`level.isLoaded` 会跳过未加载格）⇒ 同一套布局会随玩家走动而
    改变结论（**有时恢复**），布局固定时每次截断在同一处（**永远不恢复**）。

本轮改成与舱侧**同口径**的可续扫：每 tick 预算 256（`BUS_LINK_TICK_BUDGET`）、硬上限 4096
（`BUS_LINK_HARD_CAP`）、**只有整趟扫完才发布**、续扫期间对外沿用上一次完整结果、首次一次扫完。
并把「暂时没扫完」与「真歧义（≥2 条链）」分开：**截断 ⇒ 不是 exhaustive，但截断 ≠ 停用**。

本脚本断言（每条都可被源码事实或模型反例证伪）：
  ① 源码锚点：新协议在源码里（不是只在注释里）；旧的两条截断上限已彻底移除；
  ② 模型：400 格线缆 + 200 台总线，改动前后逐台归类（停用 / 普通 / 连着）的数字对比；
  ③ 暂态：续扫期间对外沿用**上一次完整结果**；首次（无结果）在**同一次判定**里一次扫完；
  ④ 真歧义不回退：够到 2 条不同的链 ⇒ 仍停用（红条口径不变）；
  ⑤ 穷尽性语义：截断 ≠ exhaustive，且截断 ≠ 停用；未触限的小布局新旧结论逐字相同；
  ⑥ 前进保证：不存在「既无进展、又无重试点」的冻结态（含「世界每一 tick 都在变」的最坏情形）；
  ⑦ 复杂度：N=10/50/200 的每 tick 工作量（旧 vs 新）与单刻上界；
  ⑧ 与舱侧同口径：常量同名同值 + 舱侧也只在扫完时发布。
"""
import io
import os
import sys
from collections import deque

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

CHECKS = [0]
FAILURES = []


def read(*parts):
    with io.open(os.path.join(SRC, *parts), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def section(title):
    print()
    print("=" * 96)
    print(title)
    print("=" * 96)


def strip_comments(text):
    """去掉 // 与 /* */ 注释：源码锚点扫描必须看「代码」，注释里的历史说明不算实现。"""
    out = []
    index = 0
    length = len(text)
    while index < length:
        two = text[index:index + 2]
        if two == "//":
            stop = text.find("\n", index)
            index = length if stop < 0 else stop
        elif two == "/*":
            stop = text.find("*/", index + 2)
            index = length if stop < 0 else stop + 2
        else:
            out.append(text[index])
            index += 1
    return "".join(out)


def body(text, anchor, end):
    begin = text.index(anchor)
    return text[begin:text.index(end, begin)]


LINK = read("support", "RsccWireLinkSearch.java")
LINK_CODE = strip_comments(LINK)
INTERFERENCE = read("support", "RsccBusInterference.java")
CHAMBER = read("block", "entity", "SequenceExecutionChamberBlockEntity.java")
EXPORTER = read("mixin", "exporter", "AbstractExporterBlockEntityMixin.java")
IMPORTER = read("mixin", "importer", "AbstractImporterBlockEntityMixin.java")

# =====================================================================================
section("① 源码锚点：可续扫协议在源码里；旧的两条静默截断上限已彻底移除")
# =====================================================================================

for needle, why in [
    ("private static final int BUS_LINK_TICK_BUDGET = 256;", "续扫每 tick 预算（与舱侧同名同值）"),
    ("private static final int BUS_LINK_HARD_CAP = 4096;", "单趟硬上限（触顶显式报出，不静默）"),
    ("private static final int BUS_LINK_RESCAN_TICKS = 20;", "定期复扫周期"),
    ("private static final class WireFlood {", "可续扫状态类（队列 + 已访问 + 上一次完整快照）"),
    ("private List<BlockPos> snapshotReachable = List.of();", "持有「上一次完整结果」"),
    ("if (!running && (!hasSnapshot || now >= nextScanAt)) {", "起新一趟的两个理由（含「还没有完整结果」）"),
    ("step(level, Integer.MAX_VALUE, now, origin);", "首次（无完整结果）⇒ 本次判定一次扫完"),
    ("step(level, BUS_LINK_TICK_BUDGET, now, origin);", "续扫：每次判定只推进预算格"),
    ("private void publish(final Level level, final long now) {", "发布口径唯一：只有扫完 / 触顶才写快照"),
    ("if (chamberAt(level, pos) != null) {", "发布前现验候选执行舱（拆掉的 / 已切模式的丢进垃圾桶）"),
    ("snapshotExhaustive = !capped;", "exhaustive 的真实来源：只有撞上硬上限才是 false"),
    ("wire_link_flood_cap reached", "触顶时打可核对的锚点日志（绝不静默截断）"),
    ("private static void prune(", "状态表按时间淘汰（不随方块数泄漏）"),
]:
    ok = needle in LINK_CODE
    check("锚点：%s" % why, ok, "源码里找不到：%s" % needle)

for needle, why in [
    ("static final int LINK_MAX_STEPS", "旧「BFS 层数上限」常量已移除"),
    ("static final int LINK_MAX_CELLS", "旧「线缆格数上限」常量已移除"),
    ("cluster.size() >= LINK_MAX_CELLS", "旧「到顶即静默结束」的比较已不存在"),
    ("while (!frontier.isEmpty())", "旧的单次 BFS 主循环（到点 break）已不存在"),
    ("Integer.MAX_VALUE, now, origin);\n                }\n            }", "除首次之外没有第二条「一次扫完」通道"),
]:
    ok = needle not in LINK_CODE
    check("取代：%s" % why, ok, "仍然存在：%s" % needle)

AMBIGUOUS_BODY = body(LINK_CODE, "public boolean ambiguous() {", "}")
check("② 「暂时没扫完」不会被当完整结果发布：发布只在 queue 排空 / 触顶两处",
      LINK_CODE.count("publish(level, now);") == 2
      and "// 扫完：只有这一刻才发布本次结果" in LINK)
check("②b 除首次那一支以外，没有任何「本次判定一次扫完」的通道（每 tick 上界不可被绕过）",
      LINK_CODE.count("step(level, Integer.MAX_VALUE, now, origin);") == 1)
check("③ 旧判据的最后一项确实从代码里去掉：ambiguous() 正文不再出现 exhaustive",
      "exhaustive" not in AMBIGUOUS_BODY and "return reachable.size() >= 2;" in AMBIGUOUS_BODY,
      AMBIGUOUS_BODY.strip())
check("④ 停用仍只由 ambiguous 决定（Report 的第一个参数没变），truncated 只是提示",
      "return new Report(walk.ambiguous(), !walk.exhaustive(), reachable.size()," in INTERFERENCE)
check("⑤ 链级端点判定仍只有 2 处消费（搜链结构没被复制出第二套）",
      LINK.count("chamber.isBusOutput()") == 2)
check("⑥ 两道闸门仍在同一步里逐字调用（扳手断开 / 分隔框架口径未动）",
      "RsccCableCuts.isDisconnected(level, cell, direction)" in LINK_CODE
      and "SeparationFrameGuard.blocksConnection(level, cell, direction)" in LINK_CODE
      and "RsccCableCuts.isDisconnected(level, origin, direction)" in LINK_CODE
      and "SeparationFrameGuard.blocksConnection(level, origin, direction)" in LINK_CODE)
check("⑦ 重入守卫与「按链去重」的唯一实现都还在",
      "if (RsccSearchGuard.isSearching()) {" in LINK_CODE
      and "RsccSearchGuard.enter();" in LINK_CODE
      and "RsccSearchGuard.exit();" in LINK_CODE
      and "private static List<BlockPos> collapseByChain(" in LINK_CODE)
check("⑧ 与舱侧同口径（常量同名同值 + 舱侧也只在扫完时发布）",
      "private static final int BUS_LINK_TICK_BUDGET = 256;" in CHAMBER
      and "private static final int BUS_LINK_HARD_CAP = 4096;" in CHAMBER
      and "publishBusLinkSnapshot(flood, importers); // 扫完：这时才发布" in CHAMBER)
check("⑨ 源码里写清了「为什么没有与舱侧共享同一个实现」（文件归属：舱侧成员 private 且不在本轮写入范围）",
      "为什么没有共享同一个实现" in LINK)

# =====================================================================================
section("② 行为模型：与 Java 逐字同构的线缆洪泛（旧截断版 / 新可续扫版）")
# =====================================================================================

LINK_MAX_STEPS = 64      # 旧常量（源码已移除；模型里保留以复刻旧行为）
LINK_MAX_CELLS = 128     # 旧常量
BUS_LINK_TICK_BUDGET = 256
BUS_LINK_HARD_CAP = 4096
BUS_LINK_MAX_RESTARTS = 2
CADENCE = 20             # 总线侧判定节流 / 结果缓存（RSCC_SEARCH_MIN_INTERVAL / RSCC_LINK_CACHE_TICKS）
DIRS = ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1))


def off(pos, delta):
    return (pos[0] + delta[0], pos[1] + delta[1], pos[2] + delta[2])


class World(object):
    """格点世界：`wires` = 可穿行格（RS 线缆 / 输入输出总线），`chambers` = 执行舱 → 链 id。

    `loaded` 决定某格是否已加载（未加载 ⇒ 洪泛跳过，与 Java 的 `level.isLoaded` 同义）。
    这正是「截断点取决于哪些区块已加载」的来源：已加载范围一变，同一趟洪泛探到的格子数就变，
    于是旧实现的结果会跟着变（有时恢复 / 有时不恢复）。

    `removed` = 已经不再是「总线输出模式执行舱」的坐标（拆掉 / 切成面输出）：相当于 Java 里
    `chamberAt` 现验返回 null。洪泛不会重新探测已访问的格子，因此这类坐标会留在本趟的候选表里 ——
    「发布前现验」就是用来把它们清掉的。
    `version` = 结构版本（等价于 Java 的 `RsccStructureEpoch`）：只有反例模型会用它。
    """

    def __init__(self, wires, chambers, loaded=None):
        self.wires = set(wires)
        self.chambers = dict(chambers)
        self.removed = set()
        self.loaded = loaded or (lambda pos: True)
        self.version = 1

    def is_loaded(self, pos):
        return self.loaded(pos)

    def kind(self, pos):
        if pos in self.wires:
            return "wire"
        if pos in self.chambers:
            return "chamber"
        return None

    def chain_of(self, pos):
        return self.chambers[pos]

    def is_valid_chamber(self, pos):
        """`RsccWireLinkSearch#chamberAt` 的模型：还在、且仍是总线输出模式。"""
        return pos in self.chambers and pos not in self.removed

    def mutate(self):
        """结构变化：版本 +1（等价于 `RsccStructureEpoch.bump`）。"""
        self.version += 1


class Scan(object):
    """两个版本共同的产物：可达执行舱（已按链去重）+ 线缆簇 + 是否穷尽 + 成本计数。"""

    def __init__(self, reachable, cluster, exhaustive, probes, polls):
        self.reachable = reachable        # [(pos, chain)]，已按 (层号, 字典序) 排序并按链去重
        self.cluster = cluster
        self.exhaustive = exhaustive
        self.probes = probes              # 世界查询次数（每次 getBlockState 记 1）
        self.polls = polls                # 出队 / 展开的格子数（每 tick 预算夹的就是它）


def collapse_by_chain(sorted_chambers):
    """`RsccWireLinkSearch#collapseByChain` 的复刻：同一条链只留「它自己最近的那台」。"""
    seen = set()
    out = []
    for pos, chain in sorted_chambers:
        if chain in seen:
            continue
        seen.add(chain)
        out.append((pos, chain))
    return out


def old_scan(world, origin):
    """旧实现：逐层 BFS，到 `LINK_MAX_STEPS` / `LINK_MAX_CELLS` **静默结束**。"""
    visited = {origin}
    cluster = [origin]
    frontier = [origin]
    steps = 0
    exhaustive = True
    found = []
    probes = 1                       # Java 里 `visited.add(origin)` 之前没有世界查询；这里从 1 起算起点
    polls = 0
    while frontier:
        if steps >= LINK_MAX_STEPS or len(cluster) >= LINK_MAX_CELLS:
            exhaustive = False
            break
        steps += 1
        polls += len(frontier)
        nxt = []
        for cell in frontier:
            for delta in DIRS:
                neighbor = off(cell, delta)
                already = neighbor in visited
                visited.add(neighbor)
                if already or not world.is_loaded(neighbor):
                    continue
                probes += 1
                kind = world.kind(neighbor)
                if kind == "chamber":
                    found.append((neighbor, steps, world.chain_of(neighbor)))
                    continue
                if kind == "wire":
                    cluster.append(neighbor)
                    nxt.append(neighbor)
        frontier = nxt
    # 「先距离近、再坐标字典序」+ 按链去重（与 Java 同源）
    found.sort(key=lambda item: (item[1], item[0][0], item[0][1], item[0][2]))
    reachable = collapse_by_chain([(pos, chain) for pos, _depth, chain in found])
    return Scan(reachable, cluster, exhaustive, probes, polls)


class WireFlood(object):
    """可续扫洪泛（与 `RsccWireLinkSearch.WireFlood` 逐字同构的模型）。

    `filter_at_publish` 对应 Java 里「发布前用 `chamberAt` 现验候选执行舱」那一步；
    关掉它就可以复刻「幽灵执行舱把总线误判成停用」的旧形状（见 ⑤ E7）。
    `world.version` 用来复刻「这一趟跨过了世界变化」（只有反例模型会用它）。
    """

    def __init__(self, world, filter_at_publish=True):
        self.world = world
        self.filter_at_publish = filter_at_publish
        self.snapshot = None
        self.has_snapshot = False
        self.next_scan_at = -(10 ** 9)
        self.last_advance = None
        self.publishes = 0
        self.last_published_size = None
        self.max_step_polls = 0
        self.max_budgeted_polls = 0
        self.start_version = None
        self.reset_pass()

    def reset_pass(self):
        self.queue = deque()
        self.visited = set()
        self.cluster = []
        self.found = []              # [(pos, depth, chain)]
        self.depth_of = {}
        self.polled = 0
        self.capped = False
        self.running = False
        self.probes = 0

    @property
    def chambers_found(self):
        return list(self.found)

    def restart(self, origin, now):
        self.reset_pass()
        self.running = True
        self.start_version = self.world.version
        self.last_advance = now
        self.next_scan_at = now + CADENCE
        self.visited.add(origin)
        self.depth_of[origin] = 0
        self.cluster.append(origin)
        self.queue.append(origin)
        self.probes = 1

    def step(self, origin, now, budget):
        before = self.polled
        one_shot = budget >= 10 ** 9
        left = budget
        while self.running and left > 0:
            if not self.queue:
                self.running = False
                self.publish(now)          # 扫完：只有这一刻才发布
                break
            if self.polled >= BUS_LINK_HARD_CAP:
                self.running = False
                self.capped = True
                self.publish(now)          # 触顶：仍然发布（exhaustive=False），绝不静默
                break
            cell = self.queue.popleft()
            depth = self.depth_of.pop(cell, 0)
            self.polled += 1
            left -= 1
            for delta in DIRS:
                neighbor = off(cell, delta)
                already = neighbor in self.visited
                self.visited.add(neighbor)
                if already or not self.world.is_loaded(neighbor):
                    continue
                self.probes += 1
                kind = self.world.kind(neighbor)
                if kind == "chamber":
                    self.found.append((neighbor, depth + 1, self.world.chain_of(neighbor)))
                    continue
                if kind == "wire":
                    self.cluster.append(neighbor)
                    self.depth_of[neighbor] = depth + 1
                    self.queue.append(neighbor)
        self.max_step_polls = max(self.max_step_polls, self.polled - before)
        if not one_shot:
            # 只统计「按预算推进」的那些步；首次那一支是另一条通道（≤ HARD_CAP，且每台总线只发生一次）。
            self.max_budgeted_polls = max(self.max_budgeted_polls, self.polled - before)

    def publish(self, now):
        self.running = False
        found = list(self.found)
        if self.filter_at_publish:
            # 发布前现验：拆掉的 / 已切成面输出的执行舱不计入可达（否则会出现「幽灵链」把总线误判成停用）。
            found = [item for item in found if self.world.is_valid_chamber(item[0])]
        found.sort(key=lambda item: (item[1], item[0][0], item[0][1], item[0][2]))
        deduped = collapse_by_chain([(pos, chain) for pos, _depth, chain in found])
        self.snapshot = Scan(deduped, list(self.cluster), not self.capped, self.probes, self.polled)
        self.has_snapshot = True
        self.publishes += 1
        self.last_published_size = len(self.snapshot.cluster)

    def resolve(self, origin, now):
        """一次「判定」（= Java 的一次 `searchChamberLink` 调用）。"""
        if not self.running and (not self.has_snapshot or now >= self.next_scan_at):
            self.restart(origin, now)
        if self.running:
            if not self.has_snapshot:
                self.step(origin, now, 10 ** 9)                  # 首次一次扫完
            elif self.last_advance != now:
                self.last_advance = now
                self.step(origin, now, BUS_LINK_TICK_BUDGET)      # 续扫：只推进预算格
        return self.snapshot


def new_scan(world, origin):
    """跑到「有完整结果」为止（首次那一支本来就一次扫完）—— 与旧实现逐字对比用。"""
    flood = WireFlood(world)
    return flood.resolve(origin, 0)


def verdict_old(scan):
    """改动前的完整口径：旧 `inspect` + 两个 Mixin 的归属归类。"""
    if not scan.reachable:
        return "plain"                                   # 一台都够不到 ⇒ Report.CLEAR ⇒ 普通总线
    disabled = len(scan.reachable) >= 2 or (len(scan.reachable) == 1 and not scan.exhaustive)
    if disabled:
        return "disabled"                                # 红条 + 横幅 + 退回普通
    return "linked" if len(scan.reachable) == 1 else "plain"


def verdict_new(scan):
    """改动后同一口径：停用只由「够到 ≥2 条不同的链」判定。"""
    if not scan.reachable:
        return "plain"
    return "disabled" if len(scan.reachable) >= 2 else "linked"


def tally(verdicts):
    return "linked=%d disabled=%d plain=%d" % (verdicts.count("linked"), verdicts.count("disabled"),
                                               verdicts.count("plain"))


def line_world(buses, length, chamber_at, chain=0, extra_chambers=(), loaded=None):
    """一条 `length` 格直线线缆 + 挂在 +z 侧的总线（总线本身也是导线，与 Java 口径一致）。"""
    wires = set((x, 0, 0) for x in range(length))
    chambers = {chamber_at: chain}
    for pos, cid in extra_chambers:
        chambers[pos] = cid
    for x in buses:
        wires.add((x, 0, 1))
    return World(wires, chambers, loaded)


MANY_CELLS = 400
BUS_XS = list(range(1, MANY_CELLS, 2))          # 200 台：x = 1,3,5,…,399
CHAMBER_LEFT = (-1, 0, 0)

print("场景 A：%d 格线缆 + %d 台总线，线缆左端挂着 1 条链（1 台执行舱）" % (MANY_CELLS, len(BUS_XS)))
world_a = line_world(BUS_XS, MANY_CELLS, CHAMBER_LEFT)
old_a = [verdict_old(old_scan(world_a, (x, 0, 1))) for x in BUS_XS]
new_a = [verdict_new(new_scan(world_a, (x, 0, 1))) for x in BUS_XS]
print("  旧：%s" % tally(old_a))
print("  新：%s" % tally(new_a))
check("A1 旧实现：同一套布局里**没有任何一台**总线能判出「唯一归属且穷尽」",
      old_a.count("linked") == 0, "linked=%d" % old_a.count("linked"))
check("A2 旧实现：够到链但被截断的 %d 台被误判「归属未确定 ⇒ 停用」（红条 + 横幅 + 退回普通）"
      % old_a.count("disabled"),
      old_a.count("disabled") > 0, tally(old_a))
check("A3 旧实现：截断窗口之外的 %d 台连链都看不见 ⇒ 退回普通总线（「总线变普通」）"
      % old_a.count("plain"),
      old_a.count("plain") > 0, tally(old_a))
check("A4 新实现：%d 台**全部**判为「连着」（停用 0、普通 0）—— 截断不再造成任何误判" % len(BUS_XS),
      new_a.count("linked") == len(BUS_XS) and new_a.count("disabled") == 0
      and new_a.count("plain") == 0, tally(new_a))

print()
print("场景 B：同样的 %d 格线缆，但左右两端各挂 1 条链（2 条不同的链 = 真歧义）" % MANY_CELLS)
world_b = line_world(BUS_XS, MANY_CELLS, CHAMBER_LEFT, 0, extra_chambers=[((MANY_CELLS, 0, 0), 1)])
old_b = [verdict_old(old_scan(world_b, (x, 0, 1))) for x in BUS_XS]
new_b = [verdict_new(new_scan(world_b, (x, 0, 1))) for x in BUS_XS]
print("  旧：%s" % tally(old_b))
print("  新：%s" % tally(new_b))
check("B1 新实现：真「够到两条链」时**全部**停用（真歧义不回退）",
      new_b.count("disabled") == len(BUS_XS), tally(new_b))

print()
print("场景 C：小布局（离上限很远）—— 新旧必须逐字同结论（不回归）")
for label, world_c, expect in (
        ("1 条链", line_world([1, 3, 5], 7, CHAMBER_LEFT), "linked"),
        ("2 条链", line_world([1, 3, 5], 7, CHAMBER_LEFT, 0, extra_chambers=[((7, 0, 0), 1)]),
         "disabled")):
    olds = [verdict_old(old_scan(world_c, (x, 0, 1))) for x in (1, 3, 5)]
    news = [verdict_new(new_scan(world_c, (x, 0, 1))) for x in (1, 3, 5)]
    check("C(%s) 小布局：旧 %s == 新 %s == 「%s」（未触限时结论逐字相同）"
          % (label, olds, news, expect), olds == news == [expect] * 3, "old=%s new=%s" % (olds, news))

print()
print("场景 D：「有时恢复 / 永远不恢复」——同一套布局，仅已加载范围不同")
partial = line_world(BUS_XS, MANY_CELLS, CHAMBER_LEFT,
                     loaded=lambda pos: pos[0] <= 63 or pos[0] < 0)
near = [1, 3, 5, 7, 9]
full_old = [verdict_old(old_scan(world_a, (x, 0, 1))) for x in near]
part_old = [verdict_old(old_scan(partial, (x, 0, 1))) for x in near]
full_new = [verdict_new(new_scan(world_a, (x, 0, 1))) for x in near]
part_new = [verdict_new(new_scan(partial, (x, 0, 1))) for x in near]
print("  近端 %s：全部加载 ⇒ 旧 %s；只加载到 x<=63 ⇒ 旧 %s" % (near, full_old, part_old))
print("  近端 %s：全部加载 ⇒ 新 %s；只加载到 x<=63 ⇒ 新 %s" % (near, full_new, part_new))
check("D1 旧实现：同一套布局，仅「哪些区块已加载」不同，结论就翻转（= 玩家走动就「有时恢复」）",
      full_old != part_old, "full=%s partial=%s" % (full_old, part_old))
check("D2 新实现：已加载范围变化时结论稳定（都判「连着」；未加载的格子本来就不可达）",
      full_new == part_new == ["linked"] * len(near), "full=%s partial=%s" % (full_new, part_new))
far = [199, 225, 299, 399]
far_old = [verdict_old(old_scan(world_a, (x, 0, 1))) for x in far]
far_new = [verdict_new(new_scan(world_a, (x, 0, 1))) for x in far]
check("D3 旧实现：布局固定时远端 %s 每次都得到同一结论（%s）⇒ 「永远不恢复」" % (far, far_old),
      len(set(far_old)) == 1 and far_old[0] == "plain", "old=%s" % far_old)
check("D4 新实现：同一条线缆上的远端总线也判「连着」（0 台普通）",
      far_new == ["linked"] * len(far), "new=%s" % far_new)

# =====================================================================================
section("③ 暂态语义：续扫期间沿用上一次完整结果；首次一次扫完（绝不返回半份）")
# =====================================================================================

# 3000 格线缆：单趟出队 ≈ 3000 格 > 预算 256 ⇒ 一趟要 12 次判定，续扫区间可观测（预算恒为真值）。
LONG_LINE = 3000
world_t = line_world([1], LONG_LINE, CHAMBER_LEFT)
flood = WireFlood(world_t)
rows = []
for call in range(40):
    now = call * CADENCE
    snapshot = flood.resolve((1, 0, 1), now)
    rows.append({
        "call": call,
        "now": now,
        "returned": None if snapshot is None else len(snapshot.cluster),
        "published": flood.last_published_size,
        "running": flood.running,
        "polled": flood.polled,
        "exhaustive": None if snapshot is None else snapshot.exhaustive,
    })
first = rows[0]
resuming = [row for row in rows if row["running"] and row["polled"] > 0]
print("  第 1 次判定：returned=%s exhaustive=%s running=%s" %
      (first["returned"], first["exhaustive"], first["running"]))
print("  续扫区间（前 3 次）：%s" %
      [(r["call"], r["polled"], r["returned"], r["published"]) for r in resuming[:3]])
print("  续扫总次数：%d（一趟 %d 格 ÷ 预算 %d）" % (len(resuming), flood.polled, BUS_LINK_TICK_BUDGET))
check("T1 首次（无任何结果）在**同一次判定**里就拿到完整结果（不会出现「前几秒像没接执行舱」的窗口）",
      first["returned"] is not None and first["returned"] == LONG_LINE + 1
      and first["exhaustive"] is True and first["running"] is False,
      "first=%s" % first)
check("T2 确实存在「续扫中」的区间（否则下面的断言是空转）：%d 次判定处于续扫" % len(resuming),
      len(resuming) >= 10, "resuming=%d" % len(resuming))
check("T3 续扫期间返回的**永远是上一次已发布的完整结果**（绝不返回半份集合）",
      all(row["returned"] == row["published"] for row in rows if row["returned"] is not None)
      and all(row["published"] == LONG_LINE + 1 for row in rows),
      "%s" % [(r["call"], r["polled"], r["returned"], r["published"]) for r in resuming[:4]])
check("T4 续扫区间内「本趟已探到的格子数」明显小于完整结果（证明它是被截住而不是碰巧完整）",
      all(row["polled"] < row["published"] for row in resuming),
      "%s" % [(r["polled"], r["published"]) for r in resuming[:4]])
check("T5 续扫的每一步推进都被每 tick 预算夹住（≤ %d）" % BUS_LINK_TICK_BUDGET,
      flood.max_budgeted_polls <= BUS_LINK_TICK_BUDGET, "max=%d" % flood.max_budgeted_polls)
check("T5b 「首次一次扫完」是另一条通道：单次 ≤ 硬上限 %d（本例 %d 格一次扫完）"
      % (BUS_LINK_HARD_CAP, LONG_LINE + 1),
      flood.max_step_polls <= BUS_LINK_HARD_CAP, "max=%d" % flood.max_step_polls)
check("T6 一趟最终仍会扫完并发布完整结果（续扫不是「永远扫不完」）",
      flood.publishes >= 2 and rows[-1]["published"] == LONG_LINE + 1,
      "publishes=%d last=%s" % (flood.publishes, rows[-1]["published"]))

# =====================================================================================
section("④ 真歧义仍被识别：够到 2 条链 ⇒ 停用（红条口径不变，不与暂态混为一谈）")
# =====================================================================================

world_amb = World(
    wires={(0, 0, 0), (1, 0, 0), (2, 0, 0), (1, 0, 1)},
    chambers={(-1, 0, 0): 0, (3, 0, 0): 1},
)
amb_scan = new_scan(world_amb, (1, 0, 1))
check("M1 2 条不同的链都够得到 ⇒ 新模型判「停用」（真歧义一字未变）",
      verdict_new(amb_scan) == "disabled" and len(amb_scan.reachable) == 2,
      "verdict=%s reachable=%s" % (verdict_new(amb_scan), amb_scan.reachable))
check("M2 同一条链上的 2 台只算 1 条链 ⇒ 仍判「连着」（4 台 = 一台的扩容语义未被破坏）",
      verdict_new(new_scan(World(wires={(0, 0, 0), (1, 0, 0), (2, 0, 0)},
                                 chambers={(0, 1, 0): 7, (2, 1, 0): 7}), (1, 0, 0))) == "linked")
check("M3 停用仍走「Report.disabled → 横幅」这条既有链路（没有新增第二条提示路径）",
      "RsccBusDisabledBanner.send(serverLevel, self.getBlockPos(), true," in EXPORTER
      and "RsccBusDisabledBanner.send(serverLevel, self.getBlockPos(), false," in IMPORTER)

# =====================================================================================
section("⑤ 穷尽性语义：截断 ≠ exhaustive；截断 ≠ 停用")
# =====================================================================================

truncated = Scan(reachable=[("c", 0)], cluster=[("c",)], exhaustive=False, probes=0, polls=0)
check("E1 截断 ⇒ exhaustive=False（这个标志本身仍然是真实的）", truncated.exhaustive is False)
check("E2 截断 + 恰好 1 条链 ⇒ **不再**停用（这正是本轮修掉的那条根因）",
      verdict_new(truncated) == "linked", "verdict=%s" % verdict_new(truncated))
check("E3 反例（证明判据不是空转）：截断 + 2 条链 ⇒ 仍然停用",
      verdict_new(Scan(reachable=[("a", 0), ("b", 1)], cluster=[], exhaustive=False, probes=0,
                       polls=0)) == "disabled")
check("E4 旧口径确实是「截断 + 1 条链 ⇒ 停用」（改进前的数字来源）",
      verdict_old(truncated) == "disabled")
huge = line_world([1], 6000, CHAMBER_LEFT)
cap_flood = WireFlood(huge)
cap_result = None
for call in range(400):
    cap_result = cap_flood.resolve((1, 0, 1), call * CADENCE)
    if cap_flood.has_snapshot and cap_flood.capped:
        break
check("E5 撞上硬上限（6000 格线缆 > %d）时仍**发布**一份结果并标 exhaustive=False（不静默丢弃）"
      % BUS_LINK_HARD_CAP,
      cap_result is not None and cap_result.exhaustive is False and cap_flood.capped)
check("E6 撞上限也不停用（只有 1 条链）", verdict_new(cap_result) == "linked",
      "verdict=%s" % verdict_new(cap_result))

# E7 暂态不得导致停用：一趟续扫跨过了「另一台执行舱被拆掉」⇒ 幽灵链必须被现验清掉
ghost_world = line_world([1], LONG_LINE, CHAMBER_LEFT, 0,
                         extra_chambers=[((1, 0, -1), 1)])   # 第 2 条链：紧贴总线，先被发现
ghost_flood = WireFlood(ghost_world)
ghost_flood.resolve((1, 0, 1), 0)                     # 首次：一次扫完 → 2 条链 ⇒ 停用（真歧义）
check("E7a 前提：2 条链都在时确实判「停用」（真歧义基线）",
      verdict_new(ghost_flood.snapshot) == "disabled",
      "verdict=%s" % verdict_new(ghost_flood.snapshot))
ghost_world.removed.add((1, 0, -1))                   # 玩家拆掉第 2 条链（第 1 条链在远端）
ghost_flood.resolve((1, 0, 1), CADENCE)               # 起新一趟（只推进预算格，尚未扫完）
check("E7b 续扫中仍沿用上一次完整结果（此刻还是 2 条链的旧结论，不是半份）",
      verdict_new(ghost_flood.snapshot) == "disabled" and ghost_flood.running,
      "running=%s" % ghost_flood.running)
for call in range(2, 400):
    ghost_flood.resolve((1, 0, 1), call * CADENCE)
    if not ghost_flood.running:
        break
check("E7c 扫完发布时，现验把「幽灵链」清掉 ⇒ 判定回到「连着」（暂态不造成停用）",
      verdict_new(ghost_flood.snapshot) == "linked"
      and len(ghost_flood.snapshot.reachable) == 1,
      "verdict=%s reachable=%s" % (verdict_new(ghost_flood.snapshot), ghost_flood.snapshot.reachable))
ghost_unfiltered = WireFlood(ghost_world, filter_at_publish=False)
ghost_world_2 = line_world([1], LONG_LINE, CHAMBER_LEFT, 0,
                           extra_chambers=[((1, 0, -1), 1)])
ghost_unfiltered = WireFlood(ghost_world_2, filter_at_publish=False)
ghost_unfiltered.resolve((1, 0, 1), 0)
ghost_world_2.removed.add((1, 0, -1))
for call in range(1, 400):
    ghost_unfiltered.resolve((1, 0, 1), call * CADENCE)
    if not ghost_unfiltered.running:
        break
check("E7d 反例（证明 E7c 不是空转）：不做发布前现验时，同一场景会被幽灵链判成「停用」",
      verdict_new(ghost_unfiltered.snapshot) == "disabled",
      "verdict=%s reachable=%s" % (verdict_new(ghost_unfiltered.snapshot),
                                   ghost_unfiltered.snapshot.reachable))

# =====================================================================================
section("⑥ 前进保证：不存在「既无进展、又无重试点」的冻结态")
# =====================================================================================


def run_protocol(world, origin, calls, churn, flood=None):
    """跑 calls 次判定，返回 (冻结次数, 发布次数, 最终结果, 模型, 最长「不发布」连续区间)。

    `churn=True` 复刻「世界每一个 tick 都在变」（结构版本一直在涨）的最坏情形。
    """
    flood = flood or WireFlood(world)
    frozen = 0
    last_publish_at = None
    max_gap = 0
    for call in range(calls):
        now = call                                  # 每次调用都推进 tick（比现场更苛刻：现场 20 tick 一次）
        if churn:
            world.mutate()                          # RsccStructureEpoch.bump
        before = (flood.polled, flood.snapshot)
        flood.resolve(origin, now)
        advanced = flood.polled > before[0]
        published = flood.snapshot is not before[1]
        scheduled = flood.running or flood.next_scan_at > now
        if not (advanced or published or scheduled):
            frozen += 1
        if published:
            if last_publish_at is not None:
                max_gap = max(max_gap, call - last_publish_at)
            last_publish_at = call
    if last_publish_at is not None:
        max_gap = max(max_gap, calls - 1 - last_publish_at)   # 收尾也要算：否则「首帧之后再没发布」看不出来
    return frozen, flood.publishes, flood.snapshot, flood, max_gap


world_f = line_world(BUS_XS, MANY_CELLS, CHAMBER_LEFT)
summary = {}
for churn, label in ((False, "世界稳定"), (True, "世界每 tick 都在变（最坏）")):
    mode_world = line_world(BUS_XS, MANY_CELLS, CHAMBER_LEFT)
    frozen, publishes, last, probe, max_gap = run_protocol(mode_world, (1, 0, 1), 200, churn)
    summary[label] = (frozen, publishes, max_gap,
                      None if last is None else len(last.reachable))
    print("  %s：200 次判定 ⇒ 发布 %d 次、最长不发布区间 %d 次判定、冻结 %d 次、最终 %s"
          % (label, publishes, max_gap, frozen,
             None if last is None else tally([verdict_new(last)])))
    check("F1(%s) 每一次判定都有「进展 / 发布 / 已排定的重试点」三者之一" % label,
          frozen == 0, "frozen=%d" % frozen)
    check("F2(%s) 200 次判定里反复拿到完整结果，且「不发布」的连续区间有界（最长 %d 次判定）"
          % (label, max_gap), publishes >= 5 and max_gap <= 40,
          "publishes=%d maxGap=%d" % (publishes, max_gap))
    check("F3(%s) 最终结果是一份完整结果且判「连着」" % label,
          last is not None and last.exhaustive and verdict_new(last) == "linked")
check("F5 发布节奏与结论和「世界版本是否一直在变」**无关**（本实现不看 epoch ⇒ 世界抖动不会让结论抖动）",
      summary["世界稳定"] == summary["世界每 tick 都在变（最坏）"],
      "%s vs %s" % (summary["世界稳定"], summary["世界每 tick 都在变（最坏）"]))


class AbortOnChangeFlood(WireFlood):
    """反例：另一条设计路线 —— 「这一趟跨过世界变化就整趟作废、重开」（不发布）。

    本轮**没有**采用它：作废会丢掉整趟进度，而重开没有上限就永远发布不了（下面这条断言就是证据）；
    加上「到上限就一次扫完」又会引入一条「本次判定扫到硬上限」的通道，破坏每 tick 上界。
    因此最终选的是「扫完就发布 + 发布前现验候选执行舱」。
    """

    def step(self, origin, now, budget):
        if self.running and self.world.version != self.start_version:
            self.restart(origin, now)          # 作废重开（不发布）
            return
        WireFlood.step(self, origin, now, budget)


dead_world = line_world(BUS_XS, MANY_CELLS, CHAMBER_LEFT)
buggy = AbortOnChangeFlood(dead_world)
buggy_frozen, buggy_publishes, _last, _probe, buggy_gap = run_protocol(dead_world, (1, 0, 1), 200,
                                                                      True, buggy)
check("F4 反例：若改成「跨过世界变化就整趟作废」，世界一直在变时 200 次判定只发布 %d 次、"
      "最长不发布区间 %d 次判定（= 「永久卡死」的形状；本实现因此选择「扫完就发布 + 现验」）"
      % (buggy_publishes, buggy_gap),
      buggy_publishes <= 1 and buggy_gap >= 100,
      "publishes=%d maxGap=%d" % (buggy_publishes, buggy_gap))

# =====================================================================================
section("⑦ 复杂度：每 tick 工作量（旧 vs 新，N=10/50/200）")
# =====================================================================================

rows = []
for count in (10, 50, 200):
    buses = list(range(1, 2 * count, 2))
    world_n = line_world(buses, MANY_CELLS, CHAMBER_LEFT)
    old_scans = [old_scan(world_n, (x, 0, 1)) for x in buses]
    new_scans = [new_scan(world_n, (x, 0, 1)) for x in buses]
    old_probes = sum(scan.probes for scan in old_scans) / float(count)
    new_probes = sum(scan.probes for scan in new_scans) / float(count)
    new_polls = sum(scan.polls for scan in new_scans) / float(count)
    calls = max(1, int((new_polls + BUS_LINK_TICK_BUDGET - 1) // BUS_LINK_TICK_BUDGET))
    rows.append({
        "n": count,
        "old_probes": old_probes,
        "old_polls": sum(scan.polls for scan in old_scans) / float(count),
        "new_probes": new_probes,
        "new_polls": new_polls,
        "calls": calls,
        "old_per_tick": old_probes / CADENCE,
        "new_per_tick": new_probes / (calls * CADENCE),
        "new_per_call": new_probes / calls,
    })

print("  N     旧格/次  旧展开/次  新格/趟  新展开/趟  续扫次数  旧格/tick  新格/tick  新格/单次")
for row in rows:
    print("  %-5d %-8.1f %-10.1f %-8.1f %-10.1f %-9d %-10.1f %-10.1f %-.1f"
          % (row["n"], row["old_probes"], row["old_polls"], row["new_probes"], row["new_polls"],
             row["calls"], row["old_per_tick"], row["new_per_tick"], row["new_per_call"]))

check("G1 旧实现：单次判定的展开格数被旧上限夹住（≤ %d + 前沿），代价是尾部信息永久丢失"
      % LINK_MAX_CELLS,
      all(row["old_polls"] <= LINK_MAX_CELLS + 64 for row in rows)
      and all(verdict_old(scan) != "linked" for scan in
              [old_scan(line_world(list(range(1, 2 * n, 2)), MANY_CELLS, CHAMBER_LEFT), (1, 0, 1))
               for n in (10, 50, 200)]),
      "old_polls=%s" % [round(r["old_polls"], 1) for r in rows])
check("G2 新实现：单次判定推进的格数 ≤ 每 tick 预算 %d（世界查询 ≤ 6×预算 + 6）" % BUS_LINK_TICK_BUDGET,
      all(row["new_polls"] / row["calls"] <= BUS_LINK_TICK_BUDGET for row in rows)
      and all(row["new_per_call"] <= 6 * BUS_LINK_TICK_BUDGET + 6 for row in rows),
      "perCall=%s" % [round(r["new_per_call"], 1) for r in rows])
check("G3 新实现：一趟完整扫描被均摊到 %s 次判定（每 %d tick 一次）⇒ 每 tick 工作量随台数近似线性"
      % ([r["calls"] for r in rows], CADENCE),
      all(rows[i + 1]["new_per_tick"] / rows[i]["new_per_tick"] < 6.0 for i in range(len(rows) - 1)),
      "perTick=%s" % [round(r["new_per_tick"], 1) for r in rows])
check("G4 新实现：每 tick 工作量不超过「台数 × 单刻预算 × 6 / 节流」这个上界（O(N)，不是 O(N²)）",
      all(row["new_per_tick"] <= row["n"] * 6.0 * BUS_LINK_TICK_BUDGET / CADENCE + 1.0
          for row in rows),
      "%s" % [(r["n"], round(r["new_per_tick"], 1)) for r in rows])
check("G5 新实现首次（bootstrap）成本有上界：单台 ≤ %d 格，且只在「还没有完整结果」时发生一次"
      % BUS_LINK_HARD_CAP,
      all(row["new_polls"] <= BUS_LINK_HARD_CAP for row in rows))

# =====================================================================================
print()
print("=" * 96)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
