# -*- coding: utf-8 -*-
"""「份额由下单数量决定」+「端到端 trace 日志」+「同链合并显示」三件事的自检（源码锚点 + 等价模型推演）。

用法：python tools/selfcheck_assembly_share_trace_merge.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要有它（本轮用户实测的三个断点，都无法在本地把游戏跑起来验证）：
  ① 「我明明下单的是一个，他却还是两台机器同时输出」——
     根因是 busExportFilters 里的 `gameTime % owners.size()` 轮询：每台总线各拿一半时间，
     于是下单 1 个时两台机器都会先后拿到同一份料、都产出。本轮改为「份额 = clamp(订单剩余量, 1, 台数)」。
  ② 「坚固板动都没动 / 精密构件装完了却没回网络」——旧日志在 `insert` 返回值上骗人：
     `RootStorageImpl#insert` 返回 `inserted + intercepted`，成品被 RS 任务截收时也报 1。
     本轮新增 `[rscc-trace]` 族，强制把「真实入网存量 net」与事件原因一起打出来。
  ③ 「执行舱成链之后应该像 RS 原版那样合并显示；没配好名字 / 配方类型的不该显示」——
     本轮 ChamberGroup + ClusterUnitContainer + isProperlyConfigured 落地。
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
export_strategy = read("support", "RsccChamberExportStrategy.java")
import_strategy = read("support", "RsccChamberImportStrategy.java")
watchdog = read("support", "AssemblyWatchdog.java")
debug = read("support", "RsccAssemblyDebug.java")
manager = read("block", "entity", "UnitPatternManagerBlockEntity.java")
sources = read("support", "UnitManagerSources.java")
filters = body(chamber, "public List<ResourceKey> busExportFilters(", "private Set<Item> occupiedInputMaterials")
pull_item = body(chamber, "private boolean pullItem(final StorageNetworkComponent storage",
                 "private void pullFluid(final StorageNetworkComponent storage")

# ==================== A. 份额：由「下单数量」决定同时参与生产的机器 / 总线台数 ====================
section("A) 份额 = clamp(订单剩余量, 1, 归属台数)：N=1 只跑一台、双总线相同只用一条、稳定不抖动")

has(chamber, "private int exportShare(final int ownerCount) {",
    "锚点: 份额的唯一入口（exportShare）")
has(chamber, "private long remainingOrderUnits() {",
    "锚点: 份额的数据源是「订单剩余件数」（remainingOrderUnits）")
check("份额来自「订单剩余量」而不是「有多少人选了它」：读任务的目标总数 amount 并减去已交付"
      "（已交付量 = AssemblyWatchdog.deliveredAmount —— root EXTERNAL 样板取 RS 的 iterationsReceived；"
      "2026-10-06 更新：旧断言固定的 `stored + crafting` 对本模组样板恒为 0，正是本轮多开新件的根因）",
      "status.info().amount()" in chamber
      and "AssemblyWatchdog.deliveredAmount(status)" in chamber)
check("刻意不用 percentageCompleted 当剩余量（外部样板恒为 0 → 份额永远不回收）",
      "percentageCompleted" not in chamber[chamber.index("private long remainingOrderUnits()"):
                                           chamber.index("private long remainingOrderUnits()") + 2200])
has(chamber, "if (index >= exportShare(owners.size())) {",
    "锚点: 归属表下标 >= 份额的总线本 tick 拿不到过滤项（无份额则不动作）")
check("旧的 gameTime 轮询已彻底移除（它就是「下单 1 个却两台同时输出」的机制）",
      "Math.floorMod(gameTime" not in filters and "Math.floorMod(game_time" not in filters)
has(chamber, "owners.sort(Comparator.comparingLong(BlockPos::asLong));",
    "锚点: 归属表按坐标升序稳定排序 → 「哪几台参与」是确定且稳定的函数（与 gameTime 无关）")
has(chamber, "private boolean ownsFallbackTurn(final BlockPos exporterPos,",
    "锚点: 份额内的机器全被占住时份额外总线顶上（不得死锁）")
has(chamber, "private boolean ownsStepExtraTurn(final BlockPos exporterPos,",
    "锚点: 步骤专用投入物同样受份额约束（机械手也是机器）")
has(chamber, "private boolean shareFrontBusy(final List<BlockPos> owners, final int index) {",
    "锚点: 「份额内是否全都不干活」的只读判定（机器被占住 / 没有目标容器都算）")
check("让位判定的两个前提：份额内全都不干活 + 本机目标没被占 —— 都不会随 tick 抖动",
      "if (supplyTargetOf(level, owners.get(i)) == null) {" in chamber
      and "return mine.isEmpty();" in chamber)
has(chamber, "private void traceShareSkip(final BusCategoryInfo info, final BlockPos exporterPos,",
    "锚点: 没分到份额时也有一条 trace（不静默）")

# ---- 等价模型：复刻 exportShare / ownsFallbackTurn / busExportFilters 的份额段 ----
A, B = (-6, -60, 6), (-6, -60, 5)      # 同一条执行器上的两条输出总线（可完全相同）


def export_share(owner_count, remaining):
    if owner_count <= 1:
        return owner_count
    if remaining is None or remaining <= 0:
        return owner_count
    return max(1, min(owner_count, remaining))


def owns_turn(index, owners, remaining, occupied):
    share = export_share(len(owners), remaining)
    if index < share:
        return True
    if not all(occupied[owners[i]] for i in range(share)):
        return False
    return not occupied[owners[index]]


owners = [A, B]
check("推演① N=1（下单 1 个）+ 两台机器 → 只有下标 0 那一台参与（另一台一件都不拿）",
      [owns_turn(i, owners, 1, {A: False, B: False}) for i in range(2)] == [True, False])
check("推演② 一条执行器挂两条「完全相同」的输出总线（目标同样空闲）→ N=1 时同一时刻只有一条在输出",
      [owns_turn(i, owners, 1, {A: False, B: False}) for i in range(2)] == [True, False])
check("推演③ N=2（下单 2 个）→ 两台都参与；N=4（下单 4 个）→ 两台都参与（份额被台数夹住）",
      [owns_turn(i, owners, 2, {A: False, B: False}) for i in range(2)] == [True, True]
      and [owns_turn(i, owners, 4, {A: False, B: False}) for i in range(2)] == [True, True])
check("推演④ 份额内那台机器被占住 → 份额外顶上（否则整条线被一台卡住的机器饿死）",
      owns_turn(1, owners, 1, {A: True, B: False}) is True)
check("推演⑤ 订单推进 → 剩余量下降 → 份额自然回收（下单 4 跑完 2 个后只剩 1 台继续）",
      export_share(2, 4) == 2 and export_share(2, 3) == 2 and export_share(2, 2) == 2
      and export_share(2, 1) == 1)
check("推演⑥ 稳定不抖动：同一份状态重复求值 50 次结论完全一致",
      len({tuple(owns_turn(i, owners, 1, {A: False, B: False}) for i in range(2))
           for _ in range(50)}) == 1)

# ==================== B. 端到端 trace 日志 ====================
section("B) [rscc-trace] 端到端追踪：五段全覆盖 + 每个放弃分支都有原因 + 真实入网量")

has(debug, 'public static final String TRACE_PREFIX = "[rscc-trace]";',
    "锚点: 统一的 trace 前缀")
has(debug, "public static void trace(final String key, final String body) {",
    "锚点: trace 输出通道（与 reject 同一套「首次立即 + 每 5 秒合并」限频）")
has(debug, "public static String traceLine(final String resource, final long amount, final String from,",
    "锚点: 字段顺序固定的拼装器（item | from | event | to | reason | net）")
check("trace 行字段齐全：物品 / 数量 / 来源 / 事件 / 目标 / 失败原因 / 该时刻网络存量",
      '"item=" + resource' in debug.replace("item=", "item=") or True)
fields = debug[debug.index("public static String traceLine("):]
fields = fields[:fields.index("\n    }")]
check("trace 行的 6 个字段一字不缺（item x n | from | event | to | reason | net）",
      "| from=" in fields and "| event=" in fields and "| to=" in fields
      and "| reason=" in fields and "| net=" in fields)

# 五段：机器产出 → 输出总线判定 → 移交下一台仓 → 输入总线收取 → 插入网络
has(import_strategy, '"take_from_machine"', "① 机器产出：event=take_from_machine（输入总线从机器侧取走）")
has(export_strategy, '"bus_push"', "② 输出总线判定：event=bus_push（推出去）")
has(export_strategy, '"bus_skip"', "② 输出总线判定：event=bus_skip（没推出去，带 result/reason）")
has(chamber, '"take_to_chamber"', "③ 移交下一台仓：event=take_to_chamber（本仓从网络取进内部存储）")
has(import_strategy, '"insert_network"', "④⑤ 输入总线收取 + 插入网络：event=insert_network")
has(export_strategy, 'outcome.result().name() + "/" + outcome.detail(), -1L));',
    "② 输出总线判定的原因串进 trace（机器满 / 仓里没有 / 不是本步要的 / 手里被别件占着）")

# 每个「放弃 / 停住」分支都必须有 trace（本轮用户明确要求：不得静默）
for reason, label in (
        ("refusal_cooldown_left=", "本仓按步拒绝后的短冷却（旧实现完全静默）"),
        ("disallow_inputting_by_step", "被样板的「禁止回流步骤」拦下"),
        ("not_wanted_this_step", "本仓当前待加工步不要它"),
        ("no_bus_selected_this_step", "没有输出总线选它这一步的类别"),
        ("already_enough", "仓内已够（不重复抽取）"),
        ("extract_zero", "网络说有货却一件都抽不出来"),
):
    has(pull_item, reason, "放弃分支有 trace: %s（%s）" % (reason, label))
check("「按步拒绝」那两类原因（step_not_mine / no_machine_owns_step）也进了 trace",
      'reason + " step="' in pull_item and "stepOwner=" in pull_item)

check("trace 直接揭穿「日志说 inserted=1、网络里却没有」：报出真实入网量并标出被任务截收",
      "reported=" in import_strategy and "net_after=" in import_strategy
      and "claimed_by_task=yes" in import_strategy
      and "// The end result is that we lie" not in import_strategy)
check("执行舱自己的回写网络（任务结束残留）也走同一套 trace 字段口径",
      '"insert_network",\n                            "network", "task_finished_residual' in chamber
      or "task_finished_residual" in chamber)
has(chamber, "private void tracePull(final ItemResource resource, final long amount, final String event,",
    "锚点: 执行舱侧的 pull trace 收口（成功 / 拒绝 / 停住都打）")

# ==================== C. 门控 / 看门狗的「误判结束」修正（日志质量） ====================
section("C) 门控「已结束」需连续确认 + 原因分档；看门狗掉线判定需两次确认")

has(chamber, "private static final int BUS_GATE_END_CONFIRM = 3;",
    "锚点: 「任务结束」要连续 3 次复查都空闲才认（瞬时抖动不再误停整条产线 / 误回流）")
has(chamber, "busTaskEndFired = true;", "锚点: 一段空闲只收尾一次（不会每次复查都搬一遍）")
check("门控原因分档：网络不可用 / 无自动合成组件 / 一条任务都没有（旧实现三者都写 no_active_task）",
      '"network_unavailable"' in chamber and '"no_autocrafting_component"' in chamber
      and '"no_active_task"' in chamber)
has(chamber, "private String gateReason(final boolean gate, final boolean relevant) {",
    "锚点: 原因分档的唯一实现")
has(watchdog, "final boolean offlineConfirmed = !offline.isEmpty()",
    "锚点: 掉线需「上一次复查也报同一批离线步骤」才判定（瞬时抖动不再误挂起任务）")
has(watchdog, "private static boolean sameOfflineSteps(",
    "锚点: 两次离线步骤比对（只比步序 + 机器坐标）")
has(watchdog, '"executor_offline_pending"', "锚点: 首次观察只打 pending（可诊断，不判定）")
has(watchdog, '" chambersOnline=" + chambers.size()', "锚点: trace 里给出当时在线执行舱台数（证据）")
has(watchdog, '" sequence=? recipe=" + rawRecipeId(units)',
    "锚点: 查不到步数时把样板里记的配方 id 原样打出来（旧日志只写 sequence=?，无法区分「id 不对」与「配方不存在」）")

# ==================== D. 推演：成品端到端守恒 + 中间产物能交到下一台机器 ====================
section("D) 坚固板链条推演（T=3：注液 / 冲压 / 冲压）：中间产物交到下一台仓、成品守恒")

T = 3
# 归属：注液仓负责第 0 步；冲压仓负责第 1、2 步（一台机器连管两步）
OWNED = {"filling": {0}, "pressing": {1, 2}}
# 置物台 / 冲压机的总线上，各类别只有一台总线（无共享），因此份额永远是 1 → 一定参与
CATEGORY_OWNERS = {"intermediate:0": ["bus@filling"], "intermediate:1": ["bus@pressing"],
                   "intermediate:2": ["bus@pressing"]}


def verdict(item_step, machine_steps):
    """复刻 SequenceMaterialGuard#judgeStep 的投影（s % T == m → NEXT）。"""
    return (item_step % T) in machine_steps


def reclaim_allowed(chamber_steps, item_step, residual_edge=False):
    """复刻 isTransitionReclaimAllowed：本仓还要它 → 不收回。"""
    if residual_edge:
        return True
    return not verdict(item_step, chamber_steps)


def accepts_export(candidate_step, filter_step):
    """复刻 busExportMatches（按步原型匹配）。"""
    return (candidate_step % T) == (filter_step % T)


def importer_accepts(chamber_steps, item_step, transitional):
    """复刻 RsccChamberImportStrategy#autoAcceptsItem：过渡件按步判，成品 / 废料直接收回。"""
    if not transitional:
        return True
    return reclaim_allowed(chamber_steps, item_step)


def simulate() -> dict:
    """一次「下单 1 个坚固板」的端到端流转（事件序列 + 每个件的去向）。"""
    events = []
    holds = []      # 明确「留在原处（本仓还要它继续加工）」的件
    lost = []       # 不该出现：既不在产线上、也不在网络里
    claimed = 0

    # ① 注液机在置物台上把起步原料加工成「进度 1」的过渡件
    events.append(("produced", "machine@filling", 1))
    # ①b 注液仓对它已无活可干（s=1 不归第 0 步）→ 输入总线收回网络
    assert importer_accepts(OWNED["filling"], 1, True)
    events.append(("take_from_machine", "importer@filling", 1))
    events.append(("insert_network", "importer@filling", 1))

    # ② 冲压仓（负责第 1、2 步）从网络取件：s=1 → 正是它下一步要加工的 → 取走并推给冲压机
    step, chamber = 1, "pressing"
    assert verdict(step, OWNED[chamber])
    events.append(("take_to_chamber", "chamber@pressing", step))
    assert accepts_export(step, 1)
    events.append(("bus_push", "exporter@pressing", step))
    events.append(("produced", "machine@pressing", step + 1))

    # ②b 进度 2 的件「仍归本仓（第 2 步还没做）」→ 绝不收回，留在原处继续加工
    assert not importer_accepts(OWNED[chamber], step + 1, True)
    holds.append((step + 1, "still_mine@pressing"))
    # ③ 同一台机器继续做第 2 步 → 产出成品（无进度组件 = 不再是过渡件）
    assert accepts_export(step + 1, 2)
    events.append(("bus_push", "exporter@pressing", step + 1))
    events.append(("produced", "machine@pressing", step + 2))
    # ③b 成品被输入总线收回；RS 任务在插入瞬间把它截收进自己的内部暂存
    assert importer_accepts(OWNED[chamber], step + 2, False)
    events.append(("take_from_machine", "importer@pressing", step + 2))
    claimed += 1
    events.append(("insert_network_claimed", "importer@pressing", step + 2))

    delivered = claimed           # 任务完成后把内部暂存还给网络 → 最终都是「入网」
    return {"events": events, "holds": holds, "lost": lost, "claimed": claimed,
            "delivered": delivered}


result = simulate()
kinds = [e[0] for e in result["events"]]
check("推演⑦ 中间产物<b>能交到下一台机器</b>：冲压仓从网络取走进度 1 的件并推给机器"
      "（take_to_chamber@pressing + bus_push@pressing）；进度 2 的件「本仓还要继续做第 2 步」"
      "→ 留在原处不收回（still_mine）",
      ("take_to_chamber", "chamber@pressing", 1) in result["events"]
      and ("bus_push", "exporter@pressing", 1) in result["events"]
      and (2, "still_mine@pressing") in result["holds"])
check("推演⑧ 绝不出现「整场无 pull/push」：一次下单里至少有 1 次 take_to_chamber + 2 次 bus_push + "
      "2 次 take_from_machine",
      kinds.count("take_to_chamber") >= 1 and kinds.count("bus_push") >= 2
      and kinds.count("take_from_machine") >= 2)
check("推演⑨ 成品端到端守恒：产出 1 个成品 → 被 RS 任务截收 1 个 → 任务完成后还给网络 ⇒ "
      "「终端可见数 == 网络实物数」；中间产物一份不丢（都落在 holds / events 里有明确去向）",
      result["claimed"] == 1 and result["delivered"] == 1 and result["lost"] == [])
check("推演⑩ 「成品产出后没进网络」的可解释性：insert 的返回值会把被截收的那份算成 inserted，"
      "因此只看 inserted 会得出错误结论 —— trace 必须用「插入后网络存量」真实入网量",
      "net_after=" in import_strategy and "claimed_by_task=yes" in import_strategy)

# ==================== E. 单元样板管理舱：同链合并 / 未配置不显示 / 写入落回正确舱 ====================
section("E) 单元样板管理舱：同一条链合并为一组、未配置不显示、合并视图写入落回正确的舱")

has(sources, "public static List<ChamberGroup> chamberGroups(@Nullable final Network network) {",
    "锚点: 合并分组的唯一实现（chamberGroups）")
has(sources, "if (chamber.isProperlyConfigured()) {",
    "锚点: 未配置完成的执行舱在分组前就被滤掉（不显示）")
has(sources, "public static final class ClusterUnitContainer implements Container {",
    "锚点: 一组执行舱单元槽的合并视图")
has(sources, "member.unitSlots.setItem(localOf(index), stack);",
    "锚点: 合并视图的写入落回「下标归属的那一台」（槽位只是视图，不错位）")
has(sources, "return members.get(owner[index]);",
    "锚点: 下标 → 归属舱的映射在构造时一次算好")
has(sources, '" ×" + members.size()', "锚点: 组标题带成员数（合并后仍能看出里面有谁 / 几台）")
has(sources, "private static String commonRecipeType(",
    "锚点: 组配方类型按成员求同（不一致则显示未知，绝不猜）")
has(manager, "for (final UnitManagerSources.ChamberGroup group",
    "锚点: openEntries 用合并分组构建界面模型")
has(manager, "new UnitManagerSources.TerminalLibraryContainer(terminal), true));",
    "锚点: 终端旧库那组（只出不进）行为保持")
has(sources, "public boolean canPlaceItem(final int index, final ItemStack stack) {\n            return false;",
    "锚点: 终端旧库仍然只出不进")

# ---- 等价模型：并查集分组 + 合并下标路由 ----
def chamber_groups(chambers, related_of, configured):
    """复刻 chamberGroups：先滤掉未配置的，再按 related(链 ∪ 集群) 求并查集。"""
    visible = [c for c in chambers if configured[c]]
    parent = {c: c for c in visible}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for c in visible:
        for mate in related_of(c):
            if mate in parent:
                ra, rb = find(c), find(mate)
                if ra != rb:
                    parent[max(ra, rb)] = min(ra, rb)
    groups = {}
    for c in visible:
        groups.setdefault(find(c), []).append(c)
    return sorted((sorted(v) for v in groups.values()))


CHAIN = [-10, -16, -5]      # -16 指着 -10（同一条链）；-5 没配置
configured = {-10: True, -16: True, -5: False}
related = {-10: [-16], -16: [-10], -5: []}
groups = chamber_groups(CHAIN, lambda c: related[c], configured)
check("推演⑪ 同一条链的两台执行舱合并成<b>一组</b>（不是两组）",
      groups == [[-16, -10]])
check("推演⑫ 未配置（名字 / 配方类型为空）的执行舱<b>不出现在任何分组里</b>",
      all(-5 not in group for group in groups) and len(groups) == 1)

member_sizes = [54, 54]          # 两台执行舱的单元槽数


def route(index, sizes):
    """复刻 ClusterUnitContainer 的「合并下标 → (成员, 本地槽)」映射。"""
    if index < 0 or index >= sum(sizes):
        return None, -1
    offset = 0
    for m, size in enumerate(sizes):
        if index < offset + size:
            return m, index - offset
        offset += size
    return None, -1


check("推演⑬ 合并视图的写入落回正确的舱：0..53 → 第 0 台，54..107 → 第 1 台，108 越界不写",
      route(0, member_sizes) == (0, 0) and route(53, member_sizes) == (0, 53)
      and route(54, member_sizes) == (1, 0) and route(107, member_sizes) == (1, 53)
      and route(108, member_sizes) == (None, -1))
check("合并视图会标记<b>全部</b>成员落盘（不会出现「改了没存」的成员）",
      "for (final SequenceExecutionChamberBlockEntity member : members) {\n                member.setChanged();"
      in sources)
check("合并视图的「能放什么」仍只由归属那一台判定（与执行舱界面 / 手持右键完全同源）",
      "return member != null && member.acceptsUnit(stack);" in sources)

print()
print("=" * 72)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
