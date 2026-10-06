# -*- coding: utf-8 -*-
"""第 32 轮自检：**「成链 = 一台逻辑执行仓，打开任意一台的总线配置要能读到整条链上全部成员的单元样板」**。

用法：python tools/selfcheck_round32_chain_unit_read.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要有它（本轮无法把游戏跑起来验证 UI）：

  用户原话：
    「这四个要被识别成【一台】。然后它应该是能够读取里面所有的单元样板的。」
    「就像是那个精致存储原版的自动合成仓一样，相当于一个扩容。」

  对照 RS 2.0 的真实模型（refinedstorage-neoforge-2.0.0-sources）：
    * `AutocrafterBlockEntity#getChainingRoot`（:172-186）沿朝向走到链首，上限
      `MAX_CHAINED_AUTOCRAFTERS = 8`（:73）；
    * 链**不共享样板**：每台各持 9 槽 `PatternInventory`（:83）与自己的
      `PatternProviderNetworkNode`（:102），界面只画本台那 9 格
      （`AutocrafterContainerMenu:87`）；
    * 「一处读全部」在 RS 里由 **Autocrafter Manager** 承担：
      `AutocrafterManagerBlockEntity#getAutocrafters`（:86-95）从网络
      `GraphNetworkComponent` 收集**全部** `Autocrafter` 容器，`getGroups`（:97-112）
      按名字分组，`AutocrafterManagerContainerMenu#addServerSideSlots`（:106-120）把每台的
      **真实容器**逐槽并进**同一个菜单**（不是把所有样板并成一份存储）；
    * 隔离概念 = 每台的 `visibleToTheAutocrafterManager`（:86 / :365-372，吃 NBT :266/:307-309），
      在 `isVisible`（:186-192）里决定「这台要不要进那份合并视图」。

  本模组的对应做法（本脚本固化的口径）：
    * 合并视图 = `chainCategories()`（唯一事实源，按 id 去重、每项带**唯一属主仓**）；
      `chainBusCategories()` 只是它的 id 投影（**不新增任何判定**），
      两个界面快照（输出 / 输入）与链级归属表都直接遍历 `chainCategories()`；
    * **绝不**把别台样板并进本台 `ownedSteps()`：属主判定（排队 / 名额 / 在制件 / 空转）
      必须留在定义该类别的那台仓身上，否则「一类别一属主」失效 → 同一件事每轮被链上 N 台
      各算一遍（重复计数），直接弄坏
      `blockedByMachineQueue` / `startCapacityForRecipe` / `inFlightUnitsForRecipe`。

本脚本做四件事：
  ① 链级读取的源码锚点（合并视图只有一份，界面三处同源）；
  ② 去重的源码锚点（类别 id / 总线坐标 / 每轮 handled 三处各只去重一次）；
  ③ 「刻意不展开 ownedSteps」的源码锚点（本台 unitSlots → 本台步表）；
  ④ 等价模型 + 反例表：4 台成链的并集 = 5 项且每项恰 1 属主；若按台各算一遍则
     ss:1 / track:2 各被算 4 次；若把别台样板并进 ownedSteps，track:2 的属主会从
     「真正定义它的那台（坐标 10）」翻成「坐标最小的那台（7）」—— 属主一旦换人，
     排队 / 名额判据就会在**错的机器**上求值。
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


def check(desc, ok):
    CHECKS[0] += 1
    if ok:
        print("  [OK]   %s" % desc)
    else:
        print("  [FAIL] %s" % desc)
        FAILURES.append(desc)


def section(title):
    print()
    print("-" * 72)
    print(title)
    print("-" * 72)


def body(text, marker, end="\n    }"):
    """从 marker 起取到**类成员缩进**的收尾大括号（含）——用于把「方法体」与 Javadoc 分开。"""
    start = text.find(marker)
    if start < 0:
        return ""
    stop = text.find(end, start)
    return text[start:stop if stop > 0 else len(text)]


CHAMBER = ("block", "entity", "SequenceExecutionChamberBlockEntity.java")
chamber = read(*CHAMBER)
importer = read("support", "RsccChamberImportStrategy.java")
unit_manager = read("support", "UnitManagerSources.java")

# ==================== 1. 链级读取：合并视图只有一份 ====================
section("1) 链级读取的源码锚点（打开任意一台的总线配置 = 整条链的单元样板）")

chain_categories = body(chamber, "public List<ChainBusCategory> chainCategories() {")
check("锚点①: 链级类别表 chainCategories() 只有一处实现，且成员集合取自唯一链推导 chainMembers()，"
      "逐成员并入它自己的 busCategories()（= 各台单元样板生成的类别）",
      chamber.count("public List<ChainBusCategory> chainCategories() {") == 1
      and "for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {" in chain_categories
      and "member.busCategories()" in chain_categories)

chain_projection = body(chamber, "public List<BusCategoryInfo> chainBusCategories() {")
check("锚点②: chainBusCategories() 只是 chainCategories() 的 id 投影（无第二套判定：体内不出现 "
      "unitSlots / busCategories() / chainMembers）",
      chain_projection != ""
      and "chainCategories()" in chain_projection
      and "unitSlots" not in chain_projection
      and "busCategories()" not in chain_projection
      and "chainMembers()" not in chain_projection)

out_snap = body(chamber, "public List<RsccBusCategory> busCategorySnapshot(")
in_snap = body(chamber,
               "public List<RsccBusCategory> busImportCategorySnapshot(\n"
               "        @org.jetbrains.annotations.Nullable final List<String> chosen, final boolean auto) {")
owners_body = body(chamber, "private Map<String, List<BlockPos>> chainBusOwners() {")
check("锚点③: 界面三处同源 —— 输出快照 / 输入快照 / 链级归属表都直接遍历 chainCategories()（共 3 处）",
      "for (final ChainBusCategory entry : chainCategories()) {" in out_snap
      and "for (final ChainBusCategory entry : chainCategories()) {" in in_snap
      and "final List<ChainBusCategory> categories = chainCategories();" in owners_body
      and chamber.count("for (final ChainBusCategory entry : chainCategories()) {") == 3)

check("锚点④: 输入总线的全自动收回也走同一份链级类别表（RsccChamberImportStrategy 里没有第二套类别来源）",
      "chamber.chainCategories()" in importer
      and "chamber.chainBusCategories()" not in importer)

check("锚点⑤: 单元样板管理器（RS Autocrafter Manager 对应物）同样按链 ∪ 相邻集群合并成员",
      "chainMembers()" in unit_manager
      and "RsccMachineCluster.clusterMembers" in unit_manager)

# ==================== 2. 去重：一类别一属主 / 一总线一次 ====================
section("2) 去重的源码锚点（重复计数会直接弄坏 5 组判据）")

check("锚点⑥: chainCategories() 用 LinkedHashSet 按 id 去重、先到者定义（⇒ 一个类别只有一个属主仓）",
      "final Set<String> seen = new LinkedHashSet<>();" in chain_categories
      and "seen.add(info.id())" in chain_categories)

exporters_body = body(chamber, "private List<BlockPos> chainExporterPositions() {")
check("锚点⑦: 链级总线集合由 LinkedHashSet 按坐标去重（一总线在归属表里只出现一次）",
      "final Set<BlockPos> found = new LinkedHashSet<>();" in exporters_body
      and "found.add(pos)" in exporters_body)

own_filters = body(chamber, "private List<ResourceKey> ownBusExportFilters(")
check("锚点⑧: 链级导出调度按 handled(skipIds) 让每个类别 id 每轮只被算一次",
      "skipIds.contains(info.id())" in own_filters
      and "private List<ResourceKey> ownBusExportFilters(" in chamber)

# ==================== 3. 刻意不展开 ownedSteps ====================
section("3) 「属主判定留在本台」的源码锚点（本轮的正面选择）")

owned = body(chamber, "private Map<String, Set<Integer>> computeOwnedSteps(final Level level) {")
cached_units = body(chamber, "private List<UnitData> cachedUnits() {")
collect_units = body(chamber, "private List<UnitData> collectUnits(final Level level) {")
check("锚点⑨: 本台「负责的步」只来自本台 unitSlots（computeOwnedSteps ← cachedUnits ← collectUnits），"
      "链上别台样板**不**并进来",
      "cachedUnits()" in owned and "chainMembers()" not in owned
      and "collectUnits(level)" in cached_units and "chainMembers()" not in cached_units
      and "unitSlots.getContainerSize()" in collect_units)

check("锚点⑩: 5 组判据的实现都在（本轮一字未改）",
      chamber.count("private boolean blockedByMachineQueue(") == 1
      and chamber.count("private String machineReservedRecipe()") == 1
      and chamber.count("private long startCapacityForRecipe(") == 1
      and chamber.count("private boolean unitCapacityLeftFor(") == 1
      and chamber.count("private long inFlightUnitsForRecipe(") == 1
      and chamber.count("private long[] pooledTransitionalUnits(") == 1
      and chamber.count("public boolean rscc$anyDestinationStuck()") == 1
      and chamber.count("public boolean pushStalledOnDestination()") == 1)

check("锚点⑪: 输入总线按「工位属主仓」逐台取判据（chainStationOwners / stationOwner），"
      "不是用绑定仓的步表去判别人的工位",
      "chamber.chainStationOwners()" in importer
      and "stationOwner(stationOwners, pos, chamber)" in importer)

# ==================== 4. 等价模型 + 反例 ====================
section("4) 等价模型：4 台成链的并集 / 属主 / 重复计数量化")

# 实测日志（run/logs/debug.log 14:30:09，x=-16 一排 4 台，cluster=4）里各台自己的类别表：
#   7 / 8 / 9 各 2 项，10 = 5 项（含前两台的 2 项）。
CHAIN = {
    7: ["intermediate:sturdy_sheet:1", "intermediate:track:2"],
    8: ["intermediate:sturdy_sheet:1", "intermediate:track:2"],
    9: ["intermediate:sturdy_sheet:1", "intermediate:track:2"],
    10: ["intermediate:sturdy_sheet:1", "intermediate:track:2",
         "intermediate:sturdy_sheet:2",
         "result:create:sturdy_sheet", "result:create:track"],
}


def chain_categories_model(chain):
    """复刻 chainCategories()：成员按坐标升序，同一 id 只留第一次出现（= 坐标最小的定义者）。"""
    entries = []
    seen = set()
    for pos in sorted(chain):
        for cid in chain[pos]:
            if cid not in seen:
                seen.add(cid)
                entries.append((cid, pos))
    return entries


entries = chain_categories_model(CHAIN)
ids = [cid for cid, _ in entries]
check("模型①: 链上任一台读到的并集逐字相同 = 5 项（含坐标 10 那台独有的 "
      "sturdy_sheet:2 / result:*）",
      len(ids) == 5 and len(set(ids)) == 5
      and ids == ["intermediate:sturdy_sheet:1", "intermediate:track:2",
                  "intermediate:sturdy_sheet:2",
                  "result:create:sturdy_sheet", "result:create:track"])
check("模型②: 每项恰一个属主；s:1 / track:2 由坐标最小的 7 定义，s:2 与 result:* 由唯一持有它们的 10 定义",
      dict(entries)["intermediate:sturdy_sheet:1"] == 7
      and dict(entries)["intermediate:track:2"] == 7
      and dict(entries)["intermediate:sturdy_sheet:2"] == 10
      and dict(entries)["result:create:sturdy_sheet"] == 10
      and dict(entries)["result:create:track"] == 10)

# 反例 A：不做链级去重（每台各回答一遍）→ 同一类别的判据被算 N 次。
def per_chamber_answers(chain):
    answers = []
    for pos in sorted(chain):
        for cid in chain[pos]:
            answers.append((cid, pos))
    return answers


answered = per_chamber_answers(CHAIN)
dup = {}
for cid, _ in answered:
    dup[cid] = dup.get(cid, 0) + 1
check("反例 A（量化）: 若按台各算一遍，intermediate:sturdy_sheet:1 与 intermediate:track:2 "
      "会被算 4 次、剩下 3 项各 1 次 —— 这正是「重复计数」的具体倍数",
      dup["intermediate:sturdy_sheet:1"] == 4
      and dup["intermediate:track:2"] == 4
      and dup["result:create:track"] == 1
      and sum(dup.values()) == 11 > len(entries) == 5)

# 反例 B：把别台样板并进本台 ownedSteps（= 让每台都「拥有」链上所有步）→ 属主换人。
def merged_owned_steps(chain):
    """每台的类别表都变成链级并集（模拟 ownedSteps 被链级展开后，各台的中间产物类别会一起扩出来）。"""
    union = []
    for pos in sorted(chain):
        for cid in chain[pos]:
            if cid not in union:
                union.append(cid)
    return {pos: list(union) for pos in chain}


merged = merged_owned_steps(CHAIN)
flipped = dict(chain_categories_model(merged))
check("反例 B（为什么刻意不做）: ownedSteps 一链级展开，intermediate:sturdy_sheet:2 的属主就从"
      "「真正定义它的 10」翻成「坐标最小的 7」（result:* 同理）；属主换人 ⇒ 排队 / 名额判据会在"
      "**另一台机器**上求值，startCapacityForRecipe / blockedByMachineQueue / inFlightUnitsForRecipe "
      "的口径随之改变",
      dict(entries)["intermediate:sturdy_sheet:2"] == 10
      and flipped["intermediate:sturdy_sheet:2"] == 7
      and dict(entries)["result:create:track"] == 10
      and flipped["result:create:track"] == 7
      and merged[7] != CHAIN[7]
      and len(merged[7]) == 5
      and all(merged[pos] == merged[7] for pos in merged))

check("模型③: 不串链（链 = 本台）时并集与投影都退化为本台类别表（行为零变化）",
      chain_categories_model({10: CHAIN[10]}) == [(cid, 10) for cid in CHAIN[10]]
      and [cid for cid, _ in chain_categories_model({10: CHAIN[10]})] == CHAIN[10])

# ==================== 结果 ====================
print()
print("=" * 72)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
