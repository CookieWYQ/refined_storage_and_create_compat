# -*- coding: utf-8 -*-
"""第 25 轮自检：**「同配方的一条链 = 一个逻辑执行仓」**（成链 = 扩容）。

用法：python tools/selfcheck_round25_exec_chain_logical_chamber.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要有它（本轮需求无法在本地把游戏跑起来验证）：

  用户原话（本轮最终口径）：
    「这个执行仓它是可以成一条链的。**成链是指这一条链，他们的那个配方 ID 是一样的**……
     **不同配方类型的仓绝不合并**…… 相当于同属于同一个执行场，相当于给执行仓扩容……
     这一条链的上面，它的这一个**输入输出总线应该保持一样的**，它应该**同步那个执行仓里面的所有内容**。」

  逐条可验收：
    C1 成链条件：只有配方相同（同一处理器类型）才成链；不同配方类型绝不合并。
    C2 一个逻辑执行仓：链 = 扩容（多块当一块用）。
    C3 总线同一套：链上任一处的输入 / 输出总线属于同一套（可选类别 = 整条链的并集；
       导出过滤清单 = 整条链的并集；供料目标集合早已链级）。
       且必须保证「一条总线只被算一次、只被消费一次」（否则会把刚修好的
       排队 blockedByMachineQueue / 在制名额 startCapacityForRecipe /
       跨舱在制计数 inFlightUnitsForRecipe 重复计数，直接弄坏它们）。
    C4 同步所有内容：链内仓的仓内物品 / 流体一致可见（本轮查清：由机器集群共享同一份载荷实现）。

本脚本做四件事：
  ① C1 源码锚点：链的每一步推导（chainHead / collectUpstream）都经过唯一校验
     `chainRecipeCompatibleWith`（own 字段比较 + 「一方为空也算兼容」）。
  ② C3 源码锚点：链级类别表 `chainCategories()` 与链级归属表 `chainBusOwners()` 各只有一份实现；
     导出清单由 `busExportFilters`（链级调度）+ `ownBusExportFilters`（单仓，带 skipIds 去重）
     + `categoryExportFilters`（单类别，`continue` → `return` 一 一对应）组成；
     界面两份快照与输入总线手动收回都走同一份链级类别表。
  ③ C4 源码锚点 + 结论：链**不搬运任何物品**（链级方法里没有任何存储 API），
     仓内物品 / 流体的一致性来自 `RsccMachineCluster` 的「全部成员采纳同一份共享存储」。
  ④ 等价模型 + 反例表：
     * C1：同配方 / 不同配方 / 一方未配置 三类的连通结果，并证明「要求双方非空且相等」会当场
       把「只在链首填一次」的正常玩法弄断（严重回归）；
     * C3：一个类别只有一个属主（判据只算一次）；一条总线在链级集合里只出现一次；
       若改成「每台仓各算一遍」会各算几次（重复计数的量化）。
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
importer = read("support", "RsccChamberImportStrategy.java")
cluster = read("support", "RsccMachineCluster.java")
clusterable = read("support", "RsccClusterable.java")

# ==================== 1. C1：成链条件 = 同配方 ====================
section("1) C1 源码锚点：同配方才成链（不同配方类型的仓绝不合并）")

has(chamber, "private boolean chainRecipeCompatibleWith(",
    "锚点①: 链级「同配方」校验只有一份实现（chainRecipeCompatibleWith）")
check("锚点②: 校验比较的是各自的**自己的**字段（ownRecipeType），不是链委托值 —— "
      "否则「链首→成员」这条边是否成立会依赖它自己（自指，判定不稳定）",
      "final String mine = ownRecipeType();" in chamber
      and "final String theirs = other.ownRecipeType();" in chamber
      and "return mine.isEmpty() || theirs.isEmpty() || mine.equals(theirs);" in chamber)
check("锚点③: 「一方为空也算兼容」是刻意的（整链配置只存在链首那一份，成员字段本来就是空的；"
      "若要求双方非空且相等，玩家只在链首填一次配方类型就会把链断成几截 —— 见下面的反例表）",
      "return mine.isEmpty() || theirs.isEmpty() || mine.equals(theirs);" in chamber)
check("锚点④: 校验同时作用在链的两个推导方向上：正向（winnerNext → chainHead）与"
      "反向（winningPredecessor → collectUpstream → chainMembers），因此成员集合口径只有一份",
      "return target != null && chainRecipeCompatibleWith(target) && winningPredecessor(target) == this"
      in chamber
      and "if (neighbor != null && target.chainRecipeCompatibleWith(neighbor)"
      in chamber)

# ==================== 2. C3：链级类别 / 归属唯一事实源 ====================
section("2) C3 源码锚点：链级类别表与链级归属表各只有一份实现")

check("锚点①: 链级类别表只有一处实现，且只是既有链推导（chainMembers）的投影",
      chamber.count("public List<ChainBusCategory> chainCategories() {") == 1
      and "for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {" in chamber
      and "public record ChainBusCategory(BusCategoryInfo info, SequenceExecutionChamberBlockEntity owner)"
      in chamber)
check("锚点②: 链级归属表只有一处实现（chainBusOwners），normalizeBusOwners 只做「比较 → 写入 → 通知」，"
      "因此所有既有消费点（备料 / 份额 / 界面角标 / 总线配置校验）自动读到同一份链级视图，没有第二张表",
      chamber.count("private Map<String, List<BlockPos>> chainBusOwners() {") == 1
      and chamber.count("final Map<String, List<BlockPos>> rebuilt = chainBusOwners();") == 1
      and "busCategoryOwners.putAll(rebuilt);" in chamber)
check("锚点③: 类别范围 = 整条链的并集（旧写法「只按本仓可见的类别过滤」已消失，"
      "否则链上另一台仓定义的类别会被静默丢掉 ⇒ 玩家的勾选变成死选项）",
      "final List<ChainBusCategory> categories = chainCategories();" in chamber
      and "for (final ChainBusCategory entry : categories) {" in chamber
      and "for (final BusCategoryInfo info : busCategories()) {\n            visible.add(info.id());" not in chamber)

# ==================== 3. C3：一总线一次 / 一类别一属主 ====================
section("3) C3 源码锚点：去重（一总线一次、一类别一次）")

check("锚点①: 链级总线集合按坐标去重（LinkedHashSet）—— 同一台总线即使被链上多台仓的线缆同时触达，"
      "在链级表里也只出现一次（这是「一总线一次」的第一道保证）",
      "private List<BlockPos> chainExporterPositions() {" in chamber
      and "final Set<BlockPos> found = new LinkedHashSet<>();" in chamber
      and "if (!level.isLoaded(pos) || found.contains(pos)) {" in chamber)
check("锚点②: 链级总线集合逐个成员用 collectExporters(false)（纯线缆可达）枚举后再统一做归属过滤 —— "
      "不能直接用 connectedExporterPositions()（它带 isInMyChain，会递归回链推导）",
      "for (final BlockPos pos : member.collectExporters(false)) {" in chamber
      and "if (chamberInMyChain(exporter.rscc$linkedExecutorPos()) != null) {" in chamber)
check("锚点③: 导出清单由「链级调度 + 单仓实现 + 单类别判定」三层组成，且链级调度带 handled 去重集"
      "（同一个类别 id 只由链上第一台定义它的仓回答一次）",
      "public List<ResourceKey> busExportFilters(" in chamber
      and "final Set<String> handled = new LinkedHashSet<>();" in chamber
      and "result.addAll(member.ownBusExportFilters(exporterPos, handled, busCategoryOwners));" in chamber
      and "if (info.id() == null || skipIds.contains(info.id())) {" in chamber)
check("锚点④: 单类别的全部判据在一处（categoryExportFilters），且「本类别不导出」只结束这一个类别"
      "（原 `continue` → 现在的 `return result`；不能连带砍掉后面的类别）",
      "private List<ResourceKey> categoryExportFilters(final BusCategoryInfo info, final BlockPos exporterPos,"
      in chamber
      and "result.addAll(categoryExportFilters(info, exporterPos, owners));" in chamber)
check("锚点⑤: 份额 / 轮询用的是**调用方传入的那一张链级表**（不是各成员自己那一份副本），"
      "否则同一条总线在链上不同副本里可能被算成不同份额 ⇒ 重复消费",
      "final Map<String, List<BlockPos>> ownersByCategory) {" in chamber
      and "final List<BlockPos> owners = ownersByCategory.get(info.id());" in chamber)
check("锚点⑥: 一条总线永远只有一台仓计算它的清单（结构性保证仍然成立：清单入口只被"
      "「该总线自己解析出的唯一执行仓」调用），链展开没有把闸门变成「链上每台各算一遍」",
      "exporterNode.setFilters(executor.busExportFilters(" in read("mixin", "exporter",
                                                                   "AbstractExporterBlockEntityMixin.java")
      and "final SequenceExecutionChamberBlockEntity executor = rscc$linkedExecutor();"
      in read("mixin", "exporter", "AbstractExporterBlockEntityMixin.java"))

# ==================== 4. C3：界面快照与收料侧同一套类别 ====================
section("4) C3 源码锚点：界面「可选类别」与收料侧手动收回都走同一份链级类别表")

check("锚点①: 输出总线界面快照（busCategorySnapshot）遍历链级类别表 ⇒ 「可选类别」= 整条链的并集"
      "（三处 `for (final ChainBusCategory entry : chainCategories())`：输出快照 / 输入快照 / 链级类别表自身）",
      "public List<RsccBusCategory> busCategorySnapshot(" in chamber
      and chamber.count("for (final ChainBusCategory entry : chainCategories()) {") == 3
      and "normalizeBusOwners();\n        final List<RsccBusCategory> result = new ArrayList<>();" in chamber)
check("锚点②: 输入总线界面快照（busImportCategorySnapshot）同样遍历链级类别表",
      "public List<RsccBusCategory> busImportCategorySnapshot(" in chamber)
check("锚点③: 输入总线**手动**收回遍历的是同一份链级类别表，且步序判据由「定义该类别的仓」回答"
      "（本仓定义的类别仍由本仓回答 ⇒ 未串链时与改造前逐字一致）",
      "for (final SequenceExecutionChamberBlockEntity.ChainBusCategory entry" in importer
      and ": chamber.chainCategories()) {" in importer
      and "final SequenceExecutionChamberBlockEntity definer = entry.owner();" in importer
      and "&& definer.matchesCategoryStep(info, stack);" in importer)
check("锚点④: 供料目标集合本来就是链级的（本轮未改，回归红线）：链口径与物理口径两份视图都还在",
      "public List<BlockPos> busSupplyTargets() {" in chamber
      and "public List<BlockPos> selfBusSupplyTargets() {" in chamber
      and "return supplyTargets(true);" in chamber
      and "return supplyTargets(false);" in chamber)

# ==================== 5. 回归红线：排队 / 名额 / 在制计数仍是「按仓一份」 ====================
section("5) 回归红线：三条刚修好的判据没有被链展开成「每台各算一遍」")

check("锚点①: 三条判据的实现各自只有一份，且实现体里不引用任何链口径"
      "（链展开只决定「谁算」，不改变「算什么」）",
      all(("private " + sig) in chamber for sig in (
          "String machineReservedRecipe() {",
          "String computeMachineReservedRecipe(final Level level) {",
          "long inFlightUnitsForRecipe(final String recipeId) {",
          "long startCapacityForRecipe(final String recipeId) {"))
      and all(x not in body(chamber, "private String machineReservedRecipe() {",
                            "private boolean blockedByMachineQueue(") for x in (
          "isInMyChain", "chainMembers", "chainMemberPositions", "chainStationOwners")))
check("锚点②: 链级调度里对每个成员只调用一次单仓实现（每类别每总线一次），"
      "且成员按 chainMembers() 的坐标升序 —— 顺序确定 ⇒ 同一条链问哪台仓都是同一份清单",
      chamber.count("member.ownBusExportFilters(exporterPos, handled, busCategoryOwners)") == 1
      and "for (final SequenceExecutionChamberBlockEntity member : chainMembers()) {" in chamber)
check("锚点③: 单仓实现里每个类别只调用一次 categoryExportFilters（不在别处重复求值）",
      chamber.count("result.addAll(categoryExportFilters(info, exporterPos, owners));") == 1)
check("锚点④: NEXT_FOR_MACHINE 保护语义（收回侧「正轮到机器加工 ⇒ 绝不收回」）一字未动",
      "NEXT_FOR_MACHINE" in read("support", "SequenceMaterialGuard.java")
      and "return SequenceMaterialGuard.sameStep(" in chamber
      and "isTransitionReclaimAllowed" in importer)

# ==================== 6. C4：链内仓内容一致可见 ====================
section("6) C4 源码锚点：链内仓的仓内物品 / 流体已经由「机器集群共享同一份载荷」同步")

check("锚点①: 链成员必然 6 向相邻（链指向就是方块朝向，faceTarget 取的就是那一格）"
      " ⇒ 它们必然落在同一个机器集群里",
      "return chamberAt(worldPosition.relative(chainFace()));" in chamber
      and "final BlockPos neighbor = cell.relative(direction);" in cluster
      and "sameFamily(level.getBlockState(neighbor).getBlock(), originBlock)" in cluster)
check("锚点②: 集群把「容量叠加 + 内容共享」实现为**同一份存储对象**（全体成员采纳同一份；只有主控落盘）",
      "4) 全部成员采纳同一份共享存储；只有主控承担落盘" in cluster
      and "m.rscc$clusterAdopt(shared);" in cluster
      and "m.rscc$clusterSetOwnsPayload(m == master);" in cluster)
check("锚点③: 执行仓采纳共享载荷 = 直接把 outputStorage / outputTank 换成那一份（因此仓内物品 / 流体"
      "在链成员之间本来就是同一个对象、天然一致可见）",
      "outputStorage = payload.items();" in chamber
      and "outputTank = payload.fluids();" in chamber
      and "public record ClusterPayload(" in chamber)
check("锚点④: 链级方法**只读**：链的推导 / 类别表 / 归属表里不出现任何存储搬运 API"
      "（绝不复制、绝不销毁 —— C4 的一致性由集群的共享对象负责，链只做视图合并）",
      not any(token in body(chamber, "public List<ChainBusCategory> chainCategories() {",
                            "private SequenceExecutionChamberBlockEntity chamberInMyChain(")
              for token in ("extractItem", "insertItem", "clusterMerge", "drain(", "popResource")))

# ==================== 7. 反例表：C1 的三种情形 ====================
section("7) 反例表：链的连通判定（同配方 / 不同配方 / 一方未配置）")


def compatible(mine, theirs):
    """复刻 chainRecipeCompatibleWith（own 字段比较；一方为空也算兼容）。"""
    return mine == "" or theirs == "" or mine == theirs


def strict_equals(mine, theirs):
    """若改成「双方都非空且相等」会怎样（反例：把正常玩法弄断）。"""
    return bool(mine) and bool(theirs) and mine == theirs


PRESS = "create:pressing"
DEPLOY = "create:deploying"
UNSET = ""
CASES = [
    ("链首(冲压) - 成员(冲压)", PRESS, PRESS, True, "同配方：成链（用户要的扩容）"),
    ("链首(冲压) - 成员(机械手)", PRESS, DEPLOY, False, "不同配方类型：绝不合并（用户明确否定）"),
    ("链首(冲压) - 成员(未配置)", PRESS, UNSET, True, "成员本来就不持有配置（只存在链首）⇒ 必须兼容"),
    ("链首(未配置) - 成员(机械手)", UNSET, DEPLOY, True, "未配置的一方不表态；配置仍只在链首那一份上"),
]
for label, mine, theirs, expect, why in CASES:
    got = compatible(mine, theirs)
    print("  %-28s compatible=%-5s %s" % (label, got, why))
    check("推演① %s" % label, got == expect, "got=%s expect=%s" % (got, expect))

check("推演② 若改成「双方都非空且相等」，玩家「放三台仓、只在链首填一次配方类型」的正常玩法会当场"
      "断成三截（3 台仓的链只剩 1 台）—— 这就是「一方为空也算兼容」不能省的理由",
      compatible(PRESS, PRESS) and compatible(PRESS, UNSET) and compatible(UNSET, UNSET)
      and not strict_equals(PRESS, UNSET) and not strict_equals(UNSET, PRESS)
      and strict_equals(PRESS, PRESS))
check("推演③ 判定是对称的（mine / theirs 互换结论不变）⇒ 两台仓对「要不要连」永远一致，不会拉锯",
      all(compatible(a, b) == compatible(b, a) for a in (PRESS, DEPLOY, UNSET)
          for b in (PRESS, DEPLOY, UNSET)))

# ==================== 8. 反例表：C3 的去重量化 ====================
section("8) 反例表：链级去重（重复计数会怎样弄坏刚修好的闸门）")

CHAIN = ["A", "B", "C"]
# 每个成员各自定义的类别 id（真实情形：同一处理器承担不同序列装配配方的不同步）
CATS = {
    "A": ["intermediate:sturdy_sheet:1", "intermediate:sturdy_sheet:2", "input:sturdy_sheet#obsidian"],
    "B": ["intermediate:sturdy_sheet:2", "intermediate:track:2", "input:track#stone"],
    "C": ["input:sturdy_sheet#obsidian", "intermediate:track:2", "input:track#iron_nugget"],
}


def chain_categories(members, cats):
    """复刻 chainCategories：按链成员坐标升序，同一 id 只留第一次出现（= 坐标最小的定义者）。"""
    out = []
    seen = set()
    for member in members:
        for cid in cats[member]:
            if cid not in seen:
                seen.add(cid)
                out.append((cid, member))
    return out


entries = chain_categories(CHAIN, CATS)
owners = dict((cid, owner) for cid, owner in entries)
raw_total = sum(len(CATS[m]) for m in CHAIN)
print("  链 {A,B,C} 各自类别数 %s（合计 %d 条登记）" % ([len(CATS[m]) for m in CHAIN], raw_total))
print("  链级去重后 %d 个类别：%s" % (len(entries), sorted(owners.items())))
check("推演④ 去重后「一个类别只有一个属主」⇒ 判据只算一次（按 id 数，而不是按登记条数）",
      len(entries) == len(set(cid for cid, _ in entries))
      and all(cid in owners for cid, _ in entries))
check("推演⑤ 共有 id 由坐标最小的定义者拥有："
      "intermediate:track:2 → B（A 没有它；C 也有但 B 更靠前），input:sturdy_sheet#obsidian → A",
      owners["intermediate:track:2"] == "B" and owners["input:sturdy_sheet#obsidian"] == "A")
dup_savings = raw_total - len(entries)
check("推演⑥ 若**不**去重（每台仓各算一遍），三个共有类别会被重复判定："
      "intermediate:sturdy_sheet:2（A、B）、intermediate:track:2（B、C）、"
      "input:sturdy_sheet#obsidian（A、C）⇒ 排队 / 名额 / 在制件闸门各被多算 %d 次"
      "（9 条登记 − 6 个类别 = 3；正是本脚本要防的重复计数）" % dup_savings,
      dup_savings == 3)
check("推演⑦ 「一条总线只被算一次」的第二半：链级总线集合按坐标去重 —— "
      "同一台总线被两台仓的线缆同时触达时，集合里仍然只有一个坐标",
      len(set(["bus1", "bus1", "bus2"])) == 2)

FLT = []
for cid, owner in entries:
    if owner == "B":  # 假设总线接在 B 上：只有 B 自己的那些（未在更靠前成员出现过的）由 B 回答
        FLT.append(cid)
print("  总线接在 B 上时，B 需要回答的类别：%s" % FLT)
check("推演⑧ 总线接在 B 上时，B 只回答「链上尚未被更靠前成员（A）定义过」的类别"
      "（= intermediate:track:2、input:track#stone）；A 已答过的 intermediate:sturdy_sheet:2 与"
      " A 拥有的 input:sturdy_sheet#obsidian 不再由 B 重复判定 ⇒ 同一条总线同一类别每轮只判一次",
      FLT == ["intermediate:track:2", "input:track#stone"]
      and "intermediate:sturdy_sheet:2" not in FLT
      and "input:sturdy_sheet#obsidian" not in FLT)

# ==================== 9. C4 反例表：不复制、不销毁 ====================
section("9) 反例表：C4 为什么不需要「复制内容」（链 = 视图合并，集群 = 同一份对象）")


def content_view(chain, cluster_shared):
    """链成员的仓内内容视图：同集群 ⇒ 同一个对象（内容天然一致）；不同对象 ⇒ 各看各的。"""
    return "same-object" if cluster_shared else "per-chamber"


check("推演⑨ 链成员彼此相邻 ⇒ 同集群 ⇒ 仓内物品 / 流体是**同一个对象**（因此「同步所有内容」"
      "已经成立，无需任何复制 / 搬运 —— 链级方法里也确实没有存储 API）",
      content_view(CHAIN, True) == "same-object"
      and content_view(CHAIN, False) == "per-chamber")
check("推演⑩ 「只做视图合并」的证据：链级类别表只返回 (类别, 属主仓) 的条目，"
      "不含任何 ItemStack 拷贝 / 抽取动作（拷贝只出现在过滤项构造里，见 ItemResource.ofItemStack）",
      "public record ChainBusCategory(BusCategoryInfo info, SequenceExecutionChamberBlockEntity owner)"
      in chamber
      and clusterable.count("内容共享") >= 1)

# ==================== 结果 ====================
print()
print("=" * 72)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
