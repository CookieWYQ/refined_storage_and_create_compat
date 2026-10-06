# -*- coding: utf-8 -*-
"""第 30 轮自检：**「4 台同配方执行仓成链 = 一台」到底成不成立 + 链级样板 / 类别可见性**。

用法：python tools/selfcheck_round30_exec_chain_symmetry_and_chain_categories.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要有它（本地跑不起游戏，但必须能判定验收项）：

  用户原话（本轮）：
    「它应该是有 **4 个执行仓**都会被连上 …… 你看他们的箭头方向。他们是成链了的，成了一条链的，
     所以说相当于是说这四台要被识别成【一台】。然后它应该是**能够读取里面所有的单元样板**的
     —— 这就是我之前一直强调的**成链要正常**，但显然现在并不正常。」

  本脚本做四件事：
   ① C1 源码锚点 + **等价模型**：把 chainFace / winningPredecessor / winnerNext / isCycleCut /
      effectiveNext / chainHead / collectUpstream / chainMembers 逐行搬成 Python，
      对「4 台同配方沿箭头相邻」等布局**逐台**算成员集合，证明集合**对称**（任意一台出发都得到同一组）。
      同时把两个**已知且刻意**的不对称（分叉落选者、>8 台超长链）写成显式断言：
      它们是文档化行为（与 RS 一致），不是缺陷 —— 真出问题（例如有人改了链推导）会在这里红。
   ② C2 判定锚点：界面拿到的类别快照**已经是链级**（chainCategories），而 `computeBusCategories` /
      `ownedSteps` **刻意仍是本台**（属主判定必须留在各自那台仓上，链级展开会把
      blockedByMachineQueue / startCapacityForRecipe / inFlightUnitsForRecipe 重复计数）。
      新增的 `chainBusCategories()` 只是 `chainCategories()` 的投影（单一事实源，不新造第二套）。
   ③ 去重反例表：同一类别 id 只出现一次、只有一个属主（坐标最小的定义者）；
      若按「每台仓各登记一遍」则不重复计数会各算 N 次（量化）。
   ④ 红线锚点：blockedByMachineQueue / machineReservedRecipe / startCapacityForRecipe /
      unitCapacityLeftFor / inFlightUnitsForRecipe / pooledTransitionalUnits /
      rscc$anyDestinationStuck / pushStalledOnDestination / NEXT_FOR_MACHINE 都仍在，
      且 `computeOwnedSteps`（属主唯一事实源）**没有**被并进链成员（= 没有重复计数）。
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


def section(title):
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


chamber = read("block", "entity", "SequenceExecutionChamberBlockEntity.java")
importer = read("support", "RsccChamberImportStrategy.java")
linksearch = read("support", "RsccWireLinkSearch.java")

MAX_CHAIN_LENGTH = int(chamber.split("MAX_CHAIN_LENGTH = ", 1)[1].split(";", 1)[0].strip())
print("链长上限（从源码读出，避免与实现漂移）：MAX_CHAIN_LENGTH = %d" % MAX_CHAIN_LENGTH)

# ==================== 1. C1 源码锚点：链推导的唯一入口 ====================
section("1) C1 源码锚点：链推导（朝向 → 胜出前辈 → 环打断 → 链首 → 反向 BFS）只有一份实现")

ANCHORS_C1 = [
    ("chainFace", "public Direction chainFace() {"),
    ("winningPredecessor", "private SequenceExecutionChamberBlockEntity winningPredecessor("),
    ("winnerNext", "private SequenceExecutionChamberBlockEntity winnerNext() {"),
    ("isCycleCut", "private boolean isCycleCut() {"),
    ("effectiveNext", "private SequenceExecutionChamberBlockEntity effectiveNext() {"),
    ("chainHead", "public SequenceExecutionChamberBlockEntity chainHead() {"),
    ("collectUpstream", "private List<SequenceExecutionChamberBlockEntity> collectUpstream("),
    ("chainMembers", "public List<SequenceExecutionChamberBlockEntity> chainMembers() {"),
]
for name, needle in ANCHORS_C1:
    check("锚点①-%s 只有一处实现" % name, chamber.count(needle) == 1)
check("锚点② chainMembers 以 chainHead() 为起点做反向 BFS，并按坐标升序排序（顺序确定 ⇒ 各台结论一致）",
      "final List<SequenceExecutionChamberBlockEntity> members = collectUpstream(chainHead());" in chamber
      and "members.sort(Comparator.comparingLong(member -> member.worldPosition.asLong()));" in chamber)
check("锚点③ 同配方校验是**对称**关系（一方为空也算兼容）：mine.isEmpty() || theirs.isEmpty() || equals",
      "return mine.isEmpty() || theirs.isEmpty() || mine.equals(theirs);" in chamber)
check("锚点④ 胜出前辈取「坐标最小」的指向者，方向遍历顺序无关（比较的是 asLong，不是遍历次序）",
      "&& (winner == null || neighbor.worldPosition.asLong() < winner.worldPosition.asLong())" in chamber)
check("锚点⑤ 反向 BFS 只收「自己也算出同一个链首」的台（chainMembers 与 chainHead 口径永远一致）",
      "if (predecessor == null || predecessor.isCycleCut() || predecessor.chainHead() != start) {" in chamber)

# ==================== 2. C1 等价模型：对称性 ====================
section("2) C1 等价模型：4 台同配方沿箭头相邻 ⇒ 从任意一台出发都得到同一组（用户验收项 C1）")

DIRS = [  # 顺序 = Direction.values()：DOWN, UP, NORTH, SOUTH, WEST, EAST
    ("DOWN", (0, -1, 0)),
    ("UP", (0, 1, 0)),
    ("NORTH", (0, 0, -1)),
    ("SOUTH", (0, 0, 1)),
    ("WEST", (-1, 0, 0)),
    ("EAST", (1, 0, 0)),
]
DIR_OFFSET = dict(DIRS)


def as_long(pos):
    """net.minecraft.core.BlockPos#asLong 的逐位实现（模组用它做「胜出 / 最小」裁决）。"""
    x, y, z = pos
    value = ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF)
    return value & ((1 << 64) - 1)


class Chamber(object):
    def __init__(self, pos, facing, recipe_type="", cats=()):
        self.pos = pos
        self.facing = facing
        self.recipe_type = recipe_type
        self.cats = tuple(cats)
        self.world = None

    # ---- 与 Java 逐行对应 ----
    def face_target(self):
        off = DIR_OFFSET[self.facing]
        return self.world.get((self.pos[0] + off[0], self.pos[1] + off[1], self.pos[2] + off[2]))

    def compatible(self, other):
        if other is None or other is self:
            return False
        return self.recipe_type == "" or other.recipe_type == "" or self.recipe_type == other.recipe_type

    def winning_predecessor(self, target):
        winner = None
        for _name, off in DIRS:
            neighbor = self.world.get((target.pos[0] + off[0], target.pos[1] + off[1], target.pos[2] + off[2]))
            if neighbor is None:
                continue
            if not target.compatible(neighbor):
                continue
            if neighbor.face_target() is not target:
                continue
            if winner is None or as_long(neighbor.pos) < as_long(winner.pos):
                winner = neighbor
        return winner

    def winner_next(self):
        target = self.face_target()
        if target is None or not self.compatible(target):
            return None
        return target if self.winning_predecessor(target) is self else None

    def is_cycle_cut(self):
        if self.winner_next() is None:
            return False
        visited = {self.pos}
        max_pos = as_long(self.pos)
        current = self
        for _ in range(MAX_CHAIN_LENGTH):
            current = current.winner_next()
            if current is None:
                return False
            if current is self:
                return max_pos == as_long(self.pos)
            if current.pos in visited:
                return False
            visited.add(current.pos)
            max_pos = max(max_pos, as_long(current.pos))
        return False

    def effective_next(self):
        nxt = self.winner_next()
        return None if nxt is None or self.is_cycle_cut() else nxt

    def chain_head(self):
        current = self
        visited = {self.pos}
        for _ in range(MAX_CHAIN_LENGTH):
            nxt = current.effective_next()
            if nxt is None or nxt.pos in visited:
                return current
            visited.add(nxt.pos)
            current = nxt
        return self

    def collect_upstream(self, start):
        members = []
        visited = {start.pos}
        queue = [start]
        while queue and len(members) < MAX_CHAIN_LENGTH:
            current = queue.pop(0)
            members.append(current)
            predecessor = current.winning_predecessor(current)
            if predecessor is None or predecessor.is_cycle_cut() or predecessor.chain_head() is not start:
                continue
            if predecessor.pos not in visited:
                visited.add(predecessor.pos)
                queue.append(predecessor)
        return members

    def chain_members(self):
        members = self.collect_upstream(self.chain_head())
        return sorted(members, key=lambda m: as_long(m.pos))


def world_of(chambers):
    world = {}
    for chamber_obj in chambers:
        chamber_obj.world = world
        world[chamber_obj.pos] = chamber_obj
    return world


def members_by_pos(chambers):
    return dict((c.pos, frozenset(m.pos for m in c.chain_members())) for c in chambers)


BASE = (100, 64, 100)


def line(count, facing, recipe="create:pressing", base=BASE, axis=2, step=1):
    """沿 axis（默认 z）排 count 台，全部朝 facing（默认 SOUTH = +z 方向）。"""
    out = []
    for index in range(count):
        pos = list(base)
        pos[axis] += index * step
        out.append(Chamber(tuple(pos), facing, recipe))
    return out


# ---- 布局①：4 台同配方，箭头 +z 首尾相接（用户场景） ----
line4 = line(4, "SOUTH")
world_of(line4)
sets = members_by_pos(line4)
expected = frozenset(c.pos for c in line4)
check("C1-① 4 台同配方沿箭头（+z）相邻：**每一台**算出的链成员都是同一组 4 台（链集合对称）",
      all(s == expected for s in sets.values()),
      "from each = %s" % {p[2]: sorted(q[2] for q in s) for p, s in sets.items()})
check("C1-② 该链的链首唯一（4 台全部指向同一台）",
      len(set(id(c.chain_head()) for c in line4)) == 1,
      "heads = %s" % sorted(set(c.chain_head().pos[2] for c in line4)))
check("C1-③ 链首是箭头走到底的那一台（箭头 +z ⇒ 链首在最 +z 端）",
      line4[0].chain_head() is line4[3] and line4[3].chain_head() is line4[3])

# ---- 布局②：箭头方向与「摆放顺序」相反（玩家看到的箭头链反向） ----
line4r = line(4, "NORTH")
world_of(line4r)
sets_r = members_by_pos(line4r)
check("C1-④ 箭头反向（全部朝 -z）**仍然对称**：4 台从任意一台出发都得到同一组（链首换到另一端）",
      all(s == frozenset(c.pos for c in line4r) for s in sets_r.values())
      and line4r[3].chain_head() is line4r[0])

# ---- 布局③：8 台（上限内最长） ----
line8 = line(8, "SOUTH")
world_of(line8)
check("C1-⑤ 8 台（= MAX_CHAIN_LENGTH）仍对称，且成员集合 = 全部 8 台",
      all(s == frozenset(c.pos for c in line8) for s in members_by_pos(line8).values()))

# ---- 布局④：9 台（超长）：文档化的退化，不是缺陷 ----
line9 = line(9, "SOUTH")
world_of(line9)
sets9 = members_by_pos(line9)
check("C1-⑥ 9 台（超长链）：照 RS 的做法退回本台自己 ⇒ 集合**刻意不对称**（文档化行为），"
      "但每组仍 ≤ MAX_CHAIN_LENGTH（不会失控）",
      len(set(frozenset(s) for s in sets9.values())) > 1
      and all(len(s) <= MAX_CHAIN_LENGTH for s in sets9.values()))

# ---- 布局⑤：拐弯的链（L 形） ----
corner = [
    Chamber((BASE[0], BASE[1], BASE[2]), "SOUTH"),
    Chamber((BASE[0], BASE[1], BASE[2] + 1), "SOUTH"),
    Chamber((BASE[0], BASE[1], BASE[2] + 2), "EAST"),
    Chamber((BASE[0] + 1, BASE[1], BASE[2] + 2), "EAST"),
]
world_of(corner)
check("C1-⑦ L 形（拐弯）链同样对称：4 台从任意一台出发都是同一组",
      all(s == frozenset(c.pos for c in corner) for s in members_by_pos(corner).values()))

# ---- 布局⑥：环（2×2 的 4 台首尾相接：a→b→c→d→a） ----
ring = [
    Chamber((BASE[0], BASE[1], BASE[2]), "SOUTH"),          # a → b
    Chamber((BASE[0], BASE[1], BASE[2] + 1), "EAST"),       # b → c
    Chamber((BASE[0] + 1, BASE[1], BASE[2] + 1), "NORTH"),  # c → d
    Chamber((BASE[0] + 1, BASE[1], BASE[2]), "WEST"),       # d → a
]
world_of(ring)
check("C1-⑧ 前提：4 台确实成环（每台的朝向目标都指向下一台）",
      all(c.face_target() is ring[(i + 1) % 4] for i, c in enumerate(ring)))
check("C1-⑧ 成环：环上坐标最大者断开一次 ⇒ 其余各台仍看到同一组（链首唯一、不拉锯）",
      all(s == frozenset(c.pos for c in ring) for s in members_by_pos(ring).values()),
      "sizes = %s" % sorted(len(s) for s in members_by_pos(ring).values()))

# ---- 布局⑦：分叉（两台指向同一台）：文档化的不对称 ----
fork_target = Chamber((BASE[0], BASE[1], BASE[2]), "SOUTH")
fork_a = Chamber((BASE[0], BASE[1], BASE[2] - 1), "SOUTH")   # 指向 target
fork_b = Chamber((BASE[0] + 1, BASE[1], BASE[2]), "WEST")    # 也指向 target
fork_mid = [(BASE[0], BASE[1], BASE[2] + 1)]
world_of([fork_target, fork_a, fork_b])
loser = fork_b if fork_a.winning_predecessor(fork_target) is fork_a else fork_a
winner_side = fork_a if loser is fork_b else fork_b
check("C1-⑨ 分叉（两台指向同一台）：只有坐标最小的那台算「胜出前辈」，落选者自成一链"
      "（= 文档化行为，与 RS 的 isHeadOfChain 同源；用户场景是直链，不属于此项）",
      fork_target.chain_head() is fork_target
      and loser.chain_head() is loser
      and frozenset(m.pos for m in loser.chain_members()) == frozenset([loser.pos])
      and frozenset(m.pos for m in winner_side.chain_members()) == frozenset([winner_side.pos, fork_target.pos]))

# ---- 布局⑧：同配方校验（不同配方类型绝不合并；一方未配置仍兼容） ----
mixed = [
    Chamber((BASE[0], BASE[1], BASE[2]), "SOUTH", "create:pressing"),
    Chamber((BASE[0], BASE[1], BASE[2] + 1), "SOUTH", "create:deploying"),
]
world_of(mixed)
check("C1-⑩ 两台都显式配过、类型不同 ⇒ 不成链（各自成独立链，什么都不丢）",
      frozenset(m.pos for m in mixed[0].chain_members()) == frozenset([mixed[0].pos])
      and frozenset(m.pos for m in mixed[1].chain_members()) == frozenset([mixed[1].pos]))
half = [
    Chamber((BASE[0], BASE[1], BASE[2]), "SOUTH", "create:pressing"),
    Chamber((BASE[0], BASE[1], BASE[2] + 1), "SOUTH", ""),
]
world_of(half)
check("C1-⑪ 一方未配置（成员本来就该是空的：整链配置只存在链首）⇒ 仍成链，"
      "否则「只在链首填一次配方类型」就会把链断成几截（严重回归）",
      all(s == frozenset(c.pos for c in half) for s in members_by_pos(half).values()))

# ==================== 3. C2 判定：快照已链级、来源仍是本台 ====================
section("3) C2 判定锚点：界面类别快照**已经**链级展开；样板 → 类别的**来源**刻意仍是本台")

check("锚点① 两份界面快照遍历的都是**链级**类别表 chainCategories()（不是本台 busCategories()）",
      "for (final ChainBusCategory entry : chainCategories()) {" in body(
          chamber, "public List<RsccBusCategory> busCategorySnapshot(",
          "\n    /**")
      and "for (final ChainBusCategory entry : chainCategories()) {" in body(
          chamber, "public List<RsccBusCategory> busImportCategorySnapshot(\n        @org.jetbrains.annotations.Nullable final List<String> chosen, final boolean auto) {",
          "\n    /**"))
check("锚点② 输入总线快照同一口径（两侧共用同一份链级类别表）",
      "public List<RsccBusCategory> busImportCategorySnapshot(" in chamber)
check("锚点③ 链级类别表按类别 id 去重、每项带唯一属主仓（seen.add ⇒ 同一 id 只有一项）",
      "public List<ChainBusCategory> chainCategories() {" in chamber
      and "if (info.id() != null && seen.add(info.id())) {" in chamber
      and "result.add(new ChainBusCategory(info, member));" in chamber)
check("锚点④ **去重即一类别一属主**：chainCategories 只产出 (info, owner)，不带任何「本台编号」"
      "⇒ 依赖「一个类别只算一次」的闸门不会被链上台数放大",
      "public record ChainBusCategory(BusCategoryInfo info, SequenceExecutionChamberBlockEntity owner)" in chamber)
check("锚点⑤ 新增的 chainBusCategories() 只有一处实现，且**只是** chainCategories() 的投影"
      "（不新增判定、不新造第二套链级表）",
      chamber.count("public List<BusCategoryInfo> chainBusCategories() {") == 1
      and "final List<ChainBusCategory> entries = chainCategories();" in chamber)
check("锚点⑥ 类别**来源**仍是本台的单元样板：computeBusCategories 读 unitsForExport()，"
      "而 unitsForExport() 只遍历本台 unitSlots（属主判定必须留在各自那台仓上）",
      "final List<UnitData> units = unitsForExport();" in chamber
      and "for (int i = 0; i < unitSlots.getContainerSize(); i++) {" in body(
          chamber, "private List<UnitData> unitsForExport() {", "\n    }"))
check("锚点⑦ ownedSteps()（属主唯一事实源）**没有**被并进链成员 —— "
      "链级展开会把 blockedByMachineQueue / startCapacityForRecipe / inFlightUnitsForRecipe 重复计数",
      "chainMembers()" not in body(chamber, "private Map<String, Set<Integer>> computeOwnedSteps(final Level level) {",
                                   "\n    /**"))
check("锚点⑧ 诊断出口（rscc$diagReport）给出链级视图：链台数 / 成员 / **全部成员的单元样板** / 类别并集",
      'chainInfo.put("size", chainMemberList.size());' in chamber
      and 'chainInfo.put("units", chainUnitRows);' in chamber
      and 'chainInfo.put("categoryIds", chainCategoryIds);' in chamber
      and 'out.put("chain", chainInfo);' in chamber)
check("锚点⑨ cats@ 诊断日志已是链级签名（categorySignature 走 chainBusCategories），"
      "并在同一行打出链上台数，避免「4 台各打一小份」被误读",
      "for (final BusCategoryInfo info : chainBusCategories()) {" in body(
          chamber, "private String categorySignature() {", "\n    }")
      and '+ " chain=" + chainMembers().size()' in chamber)

# ---- 去重反例表：用实测日志里的真实类别分布算一遍 ----
section("4) 去重反例表：链级并集 vs「每台各登记一遍」（实测形态：3 台各 2 项 + 1 台 5 项）")

S = "create:sequenced_assembly/"
CATS = {
    "chamber7": ["intermediate:%ssturdy_sheet:1" % S, "intermediate:%strack:2" % S],
    "chamber8": ["intermediate:%ssturdy_sheet:1" % S, "intermediate:%strack:2" % S],
    "chamber9": ["intermediate:%ssturdy_sheet:1" % S, "intermediate:%strack:2" % S],
    "chamber10": ["intermediate:%ssturdy_sheet:1" % S, "intermediate:%ssturdy_sheet:2" % S,
                  "intermediate:%strack:2" % S, "result:create:sturdy_sheet", "result:create:track"],
}
CHAIN_ORDER = ["chamber7", "chamber8", "chamber9", "chamber10"]  # chainMembers 按坐标升序
seen = []
owners = {}
for member in CHAIN_ORDER:
    for cid in CATS[member]:
        if cid not in owners:
            owners[cid] = member
            seen.append(cid)
raw_total = sum(len(CATS[m]) for m in CHAIN_ORDER)
print("  4 台各自类别数 %s（合计 %d 条登记）" % ([len(CATS[m]) for m in CHAIN_ORDER], raw_total))
print("  链级去重后 %d 个类别，属主 %s" % (len(seen), owners))
check("推演① 链级并集**覆盖链上每一台的单元样板所生成的类别**（用户验收项 C2）："
      "7/8/9 台只有 2 项，但任意一台算出的并集都含 10 台的 sturdy_sheet:2 与两个成品类别",
      set(seen) == set(c for m in CHAIN_ORDER for c in CATS[m])
      and "intermediate:%ssturdy_sheet:2" % S in seen
      and "result:create:track" in seen)
check("推演② 同一个类别 id 只出现一次、只有一个属主（坐标最小的定义者赢）",
      len(seen) == len(set(seen)) and owners["intermediate:%ssturdy_sheet:2" % S] == "chamber10"
      and owners["intermediate:%strack:2" % S] == "chamber7")
dup = raw_total - len(seen)
check("推演③ 若**不**去重（每台仓各算一遍）：11 条登记里只有 5 个不同类别 ⇒ 共有类别被重复判定 %d 次"
      "（= 排队 blockedByMachineQueue / 名额 startCapacityForRecipe / 在制计数 inFlightUnitsForRecipe "
      "各被多算的倍数），这正是本轮改动刻意避开的形态" % dup,
      dup == 6)
check("推演④ 「一台总线只被算一次」的另外两半：链级总线集合按坐标去重（LinkedHashSet）、"
      "类别 id 去重（handled）—— 两者都在既有链级实现里",
      "final Set<BlockPos> found = new LinkedHashSet<>();" in chamber
      and "final Set<String> handled = new LinkedHashSet<>();" in chamber)

# ==================== 5. C3 锚点：接在链上任意一处 = 属于整条链 ====================
section("5) C3 锚点：总线端点判定按链（4 台 = 一台），归属 / 工位 / 总线集合全部链级")

check("锚点① 输出总线找归属时问的是**链级**端点判定：isBusOutput() = 本台设过 或 本链任一台设过",
      "public boolean isBusOutput() {" in chamber
      and "return outputMode == OutputMode.BUS || chainHasBusOutputMember();" in chamber)
check("锚点② 该链级判定只经唯一事实源 chainMembers()；不串链时链 = 本台 ⇒ 单台仓行为逐字不变",
      "for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {" in body(
          chamber, "private boolean chainHasBusOutputMember() {", "\n    }"))
check("锚点③ 该判定**只**被线缆搜链消费（2 处），且各判据自己的「输出模式」闸门仍是**原始字段**比较 —— "
      "链级端点判定没有渗进排队 / 名额 / 在制 / 备料 / 推料判据",
      linksearch.count("chamber.isBusOutput()") == 2
      and chamber.count("outputMode != OutputMode.BUS") == 2
      and chamber.count("outputMode == OutputMode.BUS") >= 1)
check("锚点④ 总线归属判定链级：chamberInMyChain / isInMyChain（← chainMembers）是唯一判据",
      "private SequenceExecutionChamberBlockEntity chamberInMyChain(" in chamber
      and "public boolean isInMyChain(" in chamber)
check("锚点⑤ 供料工位归属链级且一工位一属主（putIfAbsent + 成员坐标升序）",
      "owners.putIfAbsent(target, owner);" in chamber)
check("锚点⑥ 链级总线集合从每个成员各自线缆可达求并集，再按坐标去重（同一台总线只出现一次）",
      "for (final BlockPos pos : member.collectExporters(false)) {" in chamber
      and "if (!level.isLoaded(pos) || found.contains(pos)) {" in chamber)

# ==================== 6. 红线锚点：五组判据未被破坏 ====================
section("6) 红线锚点：五组既有判据仍在，且属主判定未被链级展开（无重复计数）")

RED_LINES = [
    ("共享机器排队 blockedByMachineQueue", "private boolean blockedByMachineQueue("),
    ("machineReservedRecipe", "machineReservedRecipe"),
    ("在制名额 startCapacityForRecipe", "startCapacityForRecipe"),
    ("unitCapacityLeftFor", "unitCapacityLeftFor"),
    ("跨舱在制计数 inFlightUnitsForRecipe", "inFlightUnitsForRecipe"),
    ("pooledTransitionalUnits", "pooledTransitionalUnits"),
    ("rscc$anyDestinationStuck", "rscc$anyDestinationStuck"),
    ("pushStalledOnDestination", "pushStalledOnDestination"),
]
for label, needle in RED_LINES:
    check("红线①-%s 仍在（链级展开没有动它）" % label, chamber.count(needle) >= 1)
check("红线② NEXT_FOR_MACHINE 保护仍在（收回侧「正轮到机器加工 ⇒ 绝不收回」）",
      "NEXT_FOR_MACHINE" in read("support", "SequenceMaterialGuard.java")
      and "if (verdict == StepVerdict.NEXT_FOR_MACHINE) {" in chamber)
check("红线③ 导出侧「一类别一属主、一总线一次」的调度仍在：busExportFilters 按链成员坐标升序并入，"
      "并把手答过的 id 记进 handled（同 id 只回答一次）",
      "for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {" in body(
          chamber, "public List<ResourceKey> busExportFilters(", "\n    /**")
      and "handled.add(info.id());" in chamber)
check("红线④ 备料侧仍是「本台类别 ∩ 链级归属表」，且按 (配方, 步序) 再夹一次 categoryOwned —— "
      "链级类别可见性不会让本台替别台备料（属主 = 自己的单元样板 + 运行中订单）",
      "for (final BusCategoryInfo info : busCategories()) {" in body(
          chamber, "private void fillInternalForBus(", "\n    /**")
      and "if (!categoryOwned(info)) {" in chamber)

# ==================== 结果 ====================
print()
print("=" * 72)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
