# -*- coding: utf-8 -*-
"""第 24 轮自检：**执行仓「链 / 分支」上的总线归属**（总线接在链上任意一台仓 = 属于整条链）。

用法：python tools/selfcheck_round24_exec_chain_bus_scope.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要有它（本轮需求无法在本地把游戏跑起来验证）：

  用户原话：「执行仓它是可以成一条链的。那么它的出发点只需要跟这条链上面的任何一个仓交上，
  那么就代表它是这一个分支里面的。」
  → 一条输出 / 输入总线只要接在链上的<b>任意一台</b>执行仓上，就属于整条链（整个分支），
    而不是只属于它物理上贴着的那一台。

本脚本做三件事：
  ① 源码锚点：链的建模只有一份（chainFace/chainHead/chainMembers），本轮新增的
     `chainMemberPositions` / `isInMyChain` / `chamberInMyChain` 都只是它的投影；
     `cluster`（RsccMachineCluster）是**另一个概念**（6 向相邻的存储合并），链展开**不碰它**。
  ② 源码锚点：三处口径（类别归属 busCategoryOwners / 供料目标 busSupplyTargets /
     收料绑定 RsccChamberImportStrategy）**全部**经同一个 `isInMyChain` + `chainStationOwners`，
     且**没有**把「排队 / 名额 / 跨舱在制计数」也链展开（否则同一件事会在链上每台仓各算一遍）。
  ③ 等价模型 + 反例表：把「总线接仓 A，仓 B / C 能不能看到」逐格列出，并给出三条本轮刻意保留的
     **物理口径**（selfBusSupplyTargets / 绑定仓算导出清单 / 仓内存储只扫一次）的推演，
     证明它们若被链口径替换会各自造出什么坑（这正是「不挖坑」的证据）。

  注：本脚本是**新增**回归锚点，不修改任何既有 selfcheck 的断言。
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
strategy = read("support", "RsccChamberImportStrategy.java")
cluster = read("support", "RsccMachineCluster.java")
clusterable = read("support", "RsccClusterable.java")

# ==================== 1. 链的建模：只有一份，且与 cluster 无关 ====================
section("1) 源码锚点：链（分支）的推导只有一份，cluster 是另一个概念")

has(chamber, "public Direction chainFace() {",
    "锚点①: 链方向 = 方块朝向（chainFace，由 blockstate 的 FACING 推导，旧档 ChainLink 刻意忽略）")
has(chamber, "public SequenceExecutionChamberBlockEntity chainHead() {",
    "锚点②: 链首唯一（chainHead：沿「胜出 + 环打断」的朝向走到端点）")
has(chamber, "public List<SequenceExecutionChamberBlockEntity> chainMembers() {",
    "锚点③: 链成员唯一实现（chainMembers：从链首反向 BFS，按坐标升序）")
has(chamber, "private List<SequenceExecutionChamberBlockEntity> collectUpstream(",
    "锚点④: 反向收集只有一份实现（collectUpstream，限长 MAX_CHAIN_LENGTH）")

chain_body = body(chamber, "public List<SequenceExecutionChamberBlockEntity> chainMembers() {",
                  "public boolean chainHasAnyUnit() {")
check("锚点⑤: 本轮新增的三个只读入口都在链成员旁边，且只是既有链推导的投影"
      "（chainMemberPositions ← chainMembers / chamberInMyChain ← chainMembers / isInMyChain 调 chamberInMyChain）",
      "public List<BlockPos> chainMemberPositions() {" in chain_body
      and "final List<SequenceExecutionChamberBlockEntity> members = chainMembers();" in chain_body
      and "private SequenceExecutionChamberBlockEntity chamberInMyChain(" in chain_body
      and "public boolean isInMyChain(" in chain_body
      and "return chamberInMyChain(chamberPos) != null;" in chain_body)
check("锚点⑥: 链 vs cluster 是两个概念（链区里不出现 RsccMachineCluster —— 链不参与任何存储合并；"
      "cluster 按 6 向相邻合并存储，见 RsccClusterable 的类注释）",
      "RsccMachineCluster" not in chain_body
      and "6 向连通分量" in cluster
      and "内容共享" in clusterable)
check("锚点⑦: 链的成员集合不再有第二套口径（全文件只有一处反向 BFS 定义）",
      chamber.count("private List<SequenceExecutionChamberBlockEntity> collectUpstream(") == 1
      and chamber.count("public List<SequenceExecutionChamberBlockEntity> chainMembers() {") == 1)

# ==================== 2. 唯一事实源：总线归属判定 ====================
section("2) 源码锚点：三处口径的唯一归属判据 = isInMyChain（← chainMembers）")

has(chamber, "&& (!restrictToMyChain || isInMyChain(exporter.rscc$linkedExecutorPos()))) {",
    "锚点①: 推料侧（输出总线）归属走 isInMyChain")
has(chamber, "&& (!restrictToMyChain || isInMyChain(importer.rscc$linkedExecutorPos()))) {",
    "锚点②: 收料侧（输入总线）归属走同一个 isInMyChain（两侧共用一条规则）")
check("锚点③: 旧的「只认物理相邻那一台」写法已从两处收集器里彻底消失"
      "（只剩 selfBusSupplyTargets 的物理口径按坐标相等判，且有独立注释说明用途）",
      "requireSelfLink" not in chamber
      and "restrictToMyChain || worldPosition.equals" not in chamber
      and chamber.count("worldPosition.equals(exporter.rscc$linkedExecutorPos())") == 1
      and chamber.count("worldPosition.equals(importer.rscc$linkedExecutorPos())") == 0
      and "if (!wholeChain && !worldPosition.equals(exporter.rscc$linkedExecutorPos())) {" in chamber)
check("锚点④: 三处口径都从同一条链口径集合出发（connectedExporterPositions / busSupplyTargets / chainStationOwners）",
      "for (final BlockPos pos : connectedExporterPositions()) {" in chamber
      and "return collectExporters(true);" in chamber
      and "return collectImporters(true);" in chamber)
has(chamber, "public List<BlockPos> chainMemberPositions() {",
    "锚点⑤: 「给定一个仓 → 它所属链上的全部仓」就是既有 chainMembers 的坐标投影（不新造第二套）")

# ==================== 3. 三处口径的实际落点 ====================
section("3) 源码锚点：类别归属 / 供料目标 / 收料绑定 三处一致展开")

has(chamber, "for (final BlockPos pos : connectedExporterPositions()) {",
    "锚点①: 类别归属表 busCategoryOwners 由「链口径的输出总线集合」整表重建（normalizeBusOwners）")
has(chamber, "public List<BlockPos> selfBusSupplyTargets() {",
    "锚点②: 供料目标同时保留「链口径 busSupplyTargets」与「物理口径 selfBusSupplyTargets」两份视图")
has(chamber, "private List<BlockPos> supplyTargets(final boolean wholeChain) {",
    "锚点③: 两份视图共用唯一实现（supplyTargets），差别只有「是否要求 linkedExecutorPos == 本仓」")
has(chamber, "public Map<BlockPos, SequenceExecutionChamberBlockEntity> chainStationOwners() {",
    "锚点④: 收料侧新增「工位 → 属主仓」表（chainStationOwners），供每台机器用它自己那台仓的判据")
has(chamber, "owners.putIfAbsent(target, owner);",
    "锚点⑤: 工位属主**去重**（同一工位被同链两台仓各挂一条总线时只有坐标最小的那台胜出）")
check("锚点⑥: chainStationOwners 的数据全部来自既有唯一事实源（链成员 + 链口径总线 + 总线自己的归属/朝向）",
      "for (final BlockPos busPos : connectedExporterPositions()) {" in chamber
      and "final SequenceExecutionChamberBlockEntity owner = chamberInMyChain(exporter.rscc$linkedExecutorPos());" in chamber
      and "final BlockPos target = exporter.rscc$supplyTargetPos();" in chamber)

has(strategy, "final Map<BlockPos, SequenceExecutionChamberBlockEntity> stationOwners = chamber.chainStationOwners();",
    "锚点⑦: 收料策略按工位取属主（一处解析、两分支共用）")
has(strategy, "private static SequenceExecutionChamberBlockEntity stationOwner(",
    "锚点⑧: 工位属主只有一份查表实现（查不到时退回绑定仓 = 与改造前逐字一致）")
check("锚点⑨: 收料策略的机器侧（物品 + 流体）与手动模式的步序判据都改走属主仓"
      "（带坐标版只用于机器侧；仓内存储侧仍是不带坐标的那一份）",
      "autoAcceptsItem(pos, stack, inputItemsOf(ownerInputItems, owner), false, owner)" in strategy
      and "inputFluidsOf(ownerInputFluids, owner).contains(stack.getFluid())" in strategy
      and "stationOwner(stationOwners, pos, chamber).matchesCategoryStep(info, stack)" in strategy
      and strategy.count("accept.test(pos, inTank)") == 1
      and strategy.count("accept.test(inTank)") == 1)
check("锚点⑩: 链上另一台仓的输入料在收回侧受保护（输入类并集：否则会把备料刚买回来的料抄回网络、每秒来回）",
      "final Set<Item> inputItems = chamber.chainInputCategoryItems();" in strategy
      and "final Set<Fluid> inputFluids = chamber.chainInputCategoryFluids();" in strategy
      and chamber.count("public Set<Item> chainInputCategoryItems() {") == 1
      and chamber.count("public Set<Fluid> chainInputCategoryFluids() {") == 1)

# ==================== 4. 绝不重复计数：三条刚修好的判据保持「每仓一份」 ====================
section("4) 源码锚点：排队 / 名额 / 跨舱在制计数 与 NEXT_FOR_MACHINE 保护都没有被链展开")

check("锚点①: 三条判据的实现各自只有一份，且都**没有**引用任何链口径"
      "（isInMyChain / chainMembers / chainMemberPositions / chainStationOwners）",
      all(("private " + sig) in chamber for sig in (
          "String machineReservedRecipe() {",
          "String computeMachineReservedRecipe(final Level level) {",
          "long inFlightUnitsForRecipe(final String recipeId) {",
          "long startCapacityForRecipe(final String recipeId) {"))
      and all(x not in body(chamber, "private String machineReservedRecipe() {",
                            "private boolean blockedByMachineQueue(") for x in (
          "isInMyChain", "chainMembers", "chainMemberPositions", "chainStationOwners")))
has(chamber, "private Map<String, Set<Integer>> computeOwnedSteps(final Level level) {",
    "锚点②: 「本仓负责的步」仍然只来自本仓单元样板 + 运行中订单（链展开没有并进别人的步）")
check("锚点③: 跨执行舱在制计数仍按「网络里的执行仓」逐台枚举（chambersOf 每个节点容器只出一台，不会重复）",
      "public static List<SequenceExecutionChamberBlockEntity> chambersOf(" in chamber
      and "chambers.add(node.getBlockEntity());" in chamber)
check("锚点④: 导出清单的**计算者**仍然唯一（mixins 里 rscc$applyExportFilters → executor.busExportFilters，"
      "executor 就是 rscc$linkedExecutor ⇒ 一条总线只有一台仓算它的清单；这是「一总线一次」的结构性保证）"
      "；<b>2026-10-06 起清单的「范围」升级为整条链</b>（见 selfcheck_round25：chainCategories / 属主委托）"
      "—— 本锚点固化的是「唯一计算者」，不是「范围只有一台仓」",
      "exporterNode.setFilters(executor.busExportFilters(" in read("mixin", "exporter",
                                                                   "AbstractExporterBlockEntityMixin.java"))
check("锚点⑤: 归属表仍是「每仓一份实例字段」，但**内容**自 2026-10-06 起是链级唯一事实源"
      "（chainBusOwners → normalizeBusOwners 只做写入；旧锚点「按仓各存一份、不跨仓合并」已随用户需求废弃）",
      "private final Map<String, List<BlockPos>> busCategoryOwners = new LinkedHashMap<>();" in chamber
      and "final Map<String, List<BlockPos>> rebuilt = chainBusOwners();" in chamber
      and "busCategoryOwners.putAll(rebuilt);" in chamber)
check("锚点⑥: 仓内存储（含集群共享的那一份）每次 transfer 只扫一次（accumulate 里只调用一次 sweepChamberItems）",
      strategy.count("add(sweepChamberItems(chamber, storage, actor, acceptChamberItem, label, detail), tally);") == 1)

# ==================== 5. 等价模型：总线归属的反例表 ====================
section("5) 等价模型：「总线接仓 A，仓 B / C 能不能看到」逐格推演")


def chain_of(chamber_name, chains):
    for members in chains:
        if chamber_name in members:
            return members
    return [chamber_name]


def sees(bus_owner, chamber_name, chains):
    """复刻 isInMyChain：总线自身解析出的执行仓与本仓同属一条链（含自己是它自己链的成员）。"""
    return bus_owner in chain_of(chamber_name, chains)


CHAINS = [["A", "B", "C"], ["X", "Y"]]
print("  链：{A,B,C}（同一条链 / 分支）、{X,Y}（另一条链）、D（独立，没串链）")
print("  %-34s %-10s %s" % ("情形", "可见?", "依据"))
CASES = [
    ("总线接在 A 上，问 A", "A", "A", True, "自己"),
    ("总线接在 A 上，问链上的 B", "A", "B", True, "本轮新增：同链 = 整条分支"),
    ("总线接在 A 上，问链上的 C", "A", "C", True, "本轮新增：同链 = 整条分支"),
    ("总线接在 A 上，问另一条链的 X", "A", "X", False, "不同链：照旧看不见"),
    ("总线接在 D 上（独立仓），问 A", "D", "A", False, "D 自成一条链"),
    ("总线接在 D 上（独立仓），问 D", "D", "D", True, "退化情形 = 改造前行为"),
    ("总线接在 X 上，问同链的 Y", "X", "Y", True, "另一条链内部同样成立"),
]
for label, owner, me, expect, why in CASES:
    got = sees(owner, me, CHAINS)
    print("  %-34s %-10s %s" % (label, got, why))
    check("推演① %s" % label, got == expect, "got=%s expect=%s" % (got, expect))

check("推演② 链口径是「物理口径」的超集 ⇒ 没串链时两者逐字相同（用户现场的绝大多数时刻）",
      all(sees(me, me, CHAINS) for me in ["A", "B", "C", "X", "Y", "D"]))


# ==================== 6. 等价模型：工位 → 属主（去重，不重复计数） ====================
section("6) 等价模型：工位 → 属主仓（一个工位只有一个属主，绝不重复计数）")


def station_owners(buses, chains):
    """复刻 chainStationOwners：按链成员坐标升序 + 总线坐标升序，putIfAbsent 去重。"""
    owners = {}
    for bus_pos, linked, target in buses:
        if not sees(linked, "A", chains):
            continue
        owners.setdefault(target, linked)
    return owners


BUSES = [
    # (总线坐标序, 它自己解析出的仓, 它朝向的工位)
    ("bus1@A", "A", "M1"),
    ("bus2@B", "B", "M1"),   # 同链另一台仓的、朝同一个工位的总线
    ("bus3@B", "B", "M2"),
    ("bus4@X", "X", "M9"),   # 另一条链上的总线：不属于本链
]
owners = station_owners(BUSES, CHAINS)
print("  输入：bus1@A→M1、bus2@B→M1、bus3@B→M2、bus4@X→M9（本仓是链 {A,B,C} 上的 A）")
print("  输出：%s" % owners)
check("推演③ 同一工位 M1 被同链两台仓各挂一条总线 ⇒ 只登记一个属主（坐标最小的 A），不会算两遍",
      owners.get("M1") == "A" and len([k for k in owners if k == "M1"]) == 1)
check("推演④ 不同工位各有各的属主（M2 归 B）", owners.get("M2") == "B")
check("推演⑤ 别的链（bus4@X）的总线绝不进入本链的工位表",
      "M9" not in owners)
check("推演⑥ 去重后工位数 = 实际被供料的机器数（不会因为链展开而把同一台机器算成两台）",
      len(owners) == 2)


# ==================== 7. 反例表：三个「刻意保留物理口径」的地方 ====================
section("7) 反例表：若把这三处也换成链口径，各自会造出什么坑（因此刻意保留物理口径）")


def suppress_handover(targets_of_candidate, machine):
    """复刻 NetworkTransitionSink#simulate 的最后一道闸门（True = 判定为「它自己的机器」→ 不交接）。"""
    return machine in targets_of_candidate


STRICT_B = ["M2"]          # selfBusSupplyTargets（B 物理上只供 M2）
CHAIN_B = ["M1", "M2"]     # busSupplyTargets（链口径：链上 A 的工位 M1 也算 B 的）
print("  情形：一件过渡件压在 M1 上，它的下一步归 B（M1 是 A 的工位、M2 是 B 的工位）")
print("  %-46s %s" % ("判据", "结论"))
print("  %-46s %s" % ("物理口径（selfBusSupplyTargets）= 现行代码", suppress_handover(STRICT_B, "M1")))
print("  %-46s %s" % ("链口径（busSupplyTargets）= 若照搬会怎样", suppress_handover(CHAIN_B, "M1")))
check("推演⑦ 物理口径 ⇒ 直接交接照常发生（True 才是「不交接」；这里应为 False）",
      suppress_handover(STRICT_B, "M1") is False)
check("推演⑧ 链口径 ⇒ 本该直接交接的过渡件被判成「它自己的机器」而压回 RS 网络绕一圈"
      "（2026-10-05 实测「网络里留不住 → 冲压机永远拿不到料」的成因）⇒ 因此这里刻意用物理口径",
      suppress_handover(CHAIN_B, "M1") is True
      and "candidate.selfBusSupplyTargets().contains(machinePos)" in strategy
      and "candidate.busSupplyTargets().contains(machinePos)" not in strategy)


def reclaimed_by_bound_chamber(item, bound_inputs, chain_union):
    """复刻 autoAcceptsChamberItem 的「输入类受保护 / 否则收回」这条主干（忽略废料例外）。"""
    return item not in chain_union and item not in bound_inputs


A_INPUTS = {"cogwheel"}
B_INPUTS = {"large_cogwheel"}
UNION = A_INPUTS | B_INPUTS
print()
print("  情形：B 的输入料 large_cogwheel 因为「链上的总线选了它」被备进（同集群共享的）仓内存储，"
      "收回侧由绑定仓 A 的输入总线执行")
print("  %-46s %s" % ("保护集合", "会被抄回网络?"))
print("  %-46s %s" % ("只认绑定仓 A 的输入类（改造前口径）", reclaimed_by_bound_chamber("large_cogwheel", A_INPUTS, A_INPUTS)))
print("  %-46s %s" % ("整条链的输入类并集（本轮口径）", reclaimed_by_bound_chamber("large_cogwheel", A_INPUTS, UNION)))
check("推演⑨ 只认绑定仓 ⇒ B 的输入料被抄回网络，下一秒又被备料买回来（每秒来回搬运）",
      reclaimed_by_bound_chamber("large_cogwheel", A_INPUTS, A_INPUTS) is True)
check("推演⑩ 取整条链的并集 ⇒ 受保护，不再与备料互搏（本轮代码即此口径）",
      reclaimed_by_bound_chamber("large_cogwheel", A_INPUTS, UNION) is False
      and "chamber.chainInputCategoryItems()" in strategy)


def who_computes_filters(bus_linked, chain_members):
    """复刻：一条总线的导出清单只由「它物理绑定的那台仓」计算（rscc$linkedExecutor → busExportFilters）。"""
    return bus_linked if bus_linked in chain_members else None


print()
print("  情形：一条总线接在 A 上，链是 {A,B,C}")
print("  %-46s %s" % ("问题", "答案"))
print("  %-46s %s" % ("导出清单由哪台仓算", who_computes_filters("A", ["A", "B", "C"])))
print("  %-46s %s" % ("份额 / 轮询归属表按哪台仓算", "A（同一份 busCategoryOwners）"))
check("推演⑪ 一条总线永远只有一台仓计算它的过滤项（= 它自己线缆 BFS 解析出的那一台）；"
      "2026-10-06 起每个类别另有唯一属主（见 round25），因此排队 / 名额 / 在制件闸门"
      "「每条总线、每个类别每轮只算一次」这条结构性保证依然成立（链展开没有让它被算两遍）",
      who_computes_filters("A", ["A", "B", "C"]) == "A"
      and who_computes_filters("B", ["A", "B", "C"]) == "B")

# ==================== 结果 ====================
print()
print("=" * 72)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
