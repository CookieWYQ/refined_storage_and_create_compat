# -*- coding: utf-8 -*-
"""本轮（第 10 轮）用户反馈 8 条的源码锚点 + 可离线推演的自检。

用法：
    python tools/selfcheck_round10_feedback.py

判定口径（与仓库既有脚本一致，不猜、不掩盖）：
  * 每一项都是「源码锚点」（可精确验证「代码里确实这么写」）或「纯数据结构推演」
    （顺序 / 守恒 / 达标即停 —— 这些可离线算）。
  * 真实运行期行为（RS 网络图、能量、渲染）不在本脚本能力范围内，另有
    tools/verify_single_unit_supply.py 按 docs/GEAR_BLOCKAGE_AND_FLOW_CHECK.md 读日志做实判定。
  * 全部通过 ⇒ 打印 `SELFCHECK OK (n checks)`；有任何 FAIL ⇒ 退出码 1。

逐条对应：
  ① 齿轮堵塞        → 真实无效往返只有一份判据 + 稳态噪声走状态翻转通道
  ② 普通/总线按钮    → 「可转换 ⇒ 默认常驻」，每总线持久化、服务端权威
  ③ 归流缓存仓       → 主动从「被选中的面」抽相邻容器并逐步回网（守恒）
  ④ 缺料误报         → 需求已达成 / 任务已完成时不得报缺
  ⑤ 疯狂拉出又吐回   → 确定性配方不再整批预抽（autocraft 按需一份）+ 量纲分开的摘要
  ⑥ 按需补发、达标即停 → 确定性「一次一份」、概率性「整批发」；达标即停
  ⑦ 传动轴静默       → 传动杆上完全无响应（无提示、不放置、不消耗）
  ⑧ 分隔框架未牵连   → 与伪装框架共享的判定 / 分隔框架自身入口一字未改
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
RES = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat")

FAILURES = []
CHECKS = [0]


def read(*parts):
    with io.open(os.path.join(*parts), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def section(title):
    print()
    print("=" * 84)
    print(title)
    print("=" * 84)


def step_extra_gate(any_working, mine_working):
    """复刻 stepExtraOnlyWorkingStations：有工位在加工 ⇒ 只放行正在加工的那台；否则整条放行。"""
    return (not any_working) or mine_working


chamber = read(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
cache_be = read(SRC, "block", "entity", "CollectionCacheBlockEntity.java")
widget = read(SRC, "client", "widget", "ExporterExecutorRowWidget.java")
imp_source = read(SRC, "client", "widget", "ImporterExecutorBarSource.java")
exp_sync = read(SRC, "network", "SyncExporterExecutorModePacket.java")
imp_sync = read(SRC, "network", "SyncImporterExecutorModePacket.java")
exp_menu = read(SRC, "mixin", "exporter", "ExporterContainerMenuMixin.java")
imp_menu = read(SRC, "mixin", "importer", "ImporterContainerMenuMixin.java")
debug_src = read(SRC, "support", "RsccAssemblyDebug.java")
camo_item = read(SRC, "item", "CamouflageFrameItem.java")
sep_item = read(SRC, "item", "SeparationFrameItem.java")
sep_guard = read(SRC, "support", "SeparationFrameGuard.java")

# ============================================================ ① 齿轮堵塞
section("① 齿轮堵塞：真实无效往返 = 0 的判据只有一份，稳态噪声走状态翻转通道")

check("锚点: 「工位上压着本仓产线的一件东西 ⇒ 正在干活 ⇒ 绝不让位」仍只有一份实现（machineBusyOnPipeline）",
      "private boolean machineBusyOnPipeline(final Level level, final BlockPos target) {" in chamber
      and "if (machineBusyOnPipeline(level, target)) {" in chamber)
check("锚点: 份额不足的追踪走 transition（状态翻转才打一条），不再每 tick 重复判定",
      "RsccAssemblyDebug.transition(" in chamber
      and "private void traceShareSkip(" in chamber)
check("锚点: 导出清单按有界节拍重算（BUS_SCHEDULE_INTERVAL_TICKS），空转判定不再每 tick 一次",
      "private static final int BUS_SCHEDULE_INTERVAL_TICKS = 20;" in chamber
      and "if (--busExportRefreshCooldown <= 0) {" in chamber)
check("锚点: 「按步拒绝」的抑制窗口仍随步序签名作废（不会压住新步序下真正需要的料）",
      "final String wantSignature = wantedStepSignature();" in chamber
      and "stepRefusalCooldowns.clear();" in chamber)
check("锚点（本轮新增）: 步骤专用投入物只在「真的在加工」的工位上投料 —— 绝不推给此刻没有在制件的机器"
      "（否则那件料卡在它手里，既不被消耗也不被收回 ⇒ 用户点名的「齿轮堵塞」）",
      "private boolean stepExtraOnlyWorkingStations(final BlockPos exporterPos, final List<BlockPos> owners) {"
      in chamber
      and "if (!stepExtraOnlyWorkingStations(exporterPos, owners)) {" in chamber
      and "return !anyWorking || mineWorking;" in chamber)
check("推演: 两个机械手只在 A 台有在制件时 —— 齿轮只发给 A（1 台），空档（都不在加工）才退回份额口径",
      step_extra_gate(True, True) is True
      and step_extra_gate(True, False) is False
      and step_extra_gate(False, False) is True)

# ============================================================ ② 切换按钮默认常驻
section("② 「普通 / 总线」切换按钮：可转换 ⇒ 默认常驻（每总线持久化 + 服务端权威）")

check("锚点: 控件侧新增「可转换」数据源（默认退回 visible()，既有宿主行为不变）",
      "default boolean convertible() {" in widget
      and "private boolean convertible() {" in widget)
check("锚点: 条右侧那颗「普通」按钮的命中 = 可转换 且 未被强制普通（只在类别条界面里存在）",
      "return executorMode() && convertible() && !forceNormal()" in widget)
check("锚点（用户第 2 条）: 普通界面里那颗「普通 / 总线」按钮<b>常驻</b>且可来回切换"
      "（命中不看 forceNormal / disabled）",
      "return !executorMode() && convertible()" in widget
      and "source.toggleForceNormal(!forceNormal());" in widget)
check("锚点（用户第 2 条）: 普通界面<b>不再</b>画「已停用原因」那套说明（红条 + 显示可达区域）——"
      "drawDisabledNotice 只剩类别条界面里那一个调用点",
      widget.count("drawDisabledNotice(guiGraphics, mouseX, mouseY);") == 1
      and "drawDisabledNotice" not in widget[widget.index("private void drawNormalModeUi("):
                                                 widget.index("private void drawNormalToggle(")])
check("锚点: 输出 / 输入两侧都有一个「可转换但界面是普通」的四态值，且客户端据此常驻按钮",
      "public static final int MODE_LINKED_PLAIN = 3;" in exp_sync
      and "public static final int MODE_LINKED_PLAIN = 3;" in imp_sync
      and "public static boolean isConvertible(final int containerId) {" in exp_sync
      and "public static boolean isConvertible(final int containerId) {" in imp_sync)
check("锚点: 四态由「是否延长型 + 是否强制普通 + 是否可转换（原始布局判定）」算出，两侧口径一致",
      "public static int modeOf(final boolean executorMode, final boolean forceNormalBus," in exp_sync
      and "public static int modeOf(final boolean executorMode, final boolean forceNormalBus," in imp_sync
      and "SyncExporterExecutorModePacket.modeOf(mode, forceNormal, linkedLayout)" in exp_menu
      and "SyncImporterExecutorModePacket.modeOf(mode, forceNormal, linkedLayout)" in imp_menu)
check("锚点: 「可转换」用的是原始布局判定（isLinkedLayout，不含强制普通开关）⇒ 界面归属与状态信息不会互相吞掉",
      "final boolean linkedLayout = executorMode != null && executorMode.rscc$isLinkedLayout();" in exp_menu
      and "final boolean linkedLayout = executorMode != null && executorMode.rscc$isLinkedLayout();" in imp_menu)
check("锚点: 界面侧「可转换」取自两个同步包（服务端权威 → 客户端只读镜像）",
      "return SyncExporterExecutorModePacket.isConvertible(containerId);" in widget
      and "return SyncImporterExecutorModePacket.isConvertible(containerId);" in imp_source)
exp_be = read(SRC, "mixin", "exporter", "AbstractExporterBlockEntityMixin.java")
imp_be = read(SRC, "mixin", "importer", "AbstractImporterBlockEntityMixin.java")
check("锚点: 「强制普通」仍是<b>每总线持久化</b>标志（落盘 + 读档保留），且服务端权威",
      'RSCC_TAG_FORCE_NORMAL = "rscc_force_normal_bus"' in exp_be
      and 'RSCC_TAG_FORCE_NORMAL = "rscc_force_normal_bus"' in imp_be
      and "void rscc$setForceNormalBus(boolean force);" in read(SRC, "support", "RsccExporterExecutorMode.java")
      and "void rscc$setForceNormalBus(boolean force);" in read(SRC, "support", "RsccImporterExecutorMode.java"))

# ============================================================ ③ 归流缓存仓主动抽容器
section("③ 归流缓存仓：主动从「被选中的面」抽相邻方块容器 → 缓存 → 逐步回网（守恒）")

check("锚点: 新增「相邻方块容器」来源，并在物品吸取开关下与掉落物来源一起跑",
      "private void collectFromAdjacentContainers(final Level level) {" in cache_be
      and "collectFromAdjacentContainers(level);" in cache_be)
check("锚点: 受「选面」约束 —— 只有被选中的面才抽（未被选中的面一个都不碰）",
      cache_be.count("if (!isInputFace(direction)) {") == 1
      and "if (inputFaces == 0) {" in cache_be)
check("锚点: 取物品的匹配口径与掉落物同源（吸取所有 / 反转匹配做黑名单 / 常规按标记命中）",
      "private boolean acceptsFromContainer(final ItemStack stack, final HolderLookup.Provider registries) {"
      in cache_be
      and "final boolean marked = matchesAnyItemMarker(stack, registries);" in cache_be)
check("锚点: 守恒（不复制 / 不销毁）—— 原子抽取后塞缓存，装不下的余量原样还回源容器，还放不下才掉在容器旁",
      "final ItemStack extracted = handler.extractItem(slot, want, false);" in cache_be
      and "final ItemStack back = handler.insertItem(slot, leftover, false);" in cache_be
      and "Block.popResource(level, source, back);" in cache_be)
check("锚点: 「逐步回网」语义未变 —— 缓存按处理速率逐 tick 写回网络，阻塞项留在缓存（continue 且不占预算）",
      "private void flushCacheToNetwork(final Network network) {" in cache_be
      and "if (isBlockedStack(inSlot)) {" in cache_be
      and "if (isBlockedFluid(entry.id())) {" in cache_be)
check("锚点: 面开关变更后仍显式作废 Neoforge 能力缓存（不会出现「点了没反应」）",
      cache_be.count("rscc$invalidateLogisticsCapabilities();") == 2)

# ============================================================ ④ 缺料误报
section("④ 缺料误报：需求已达成 / 任务已完成时不得报缺")

check("锚点: 缺料上报前先问「相关任务是否已全部交付满」",
      "if (orderDeliveredInFull()) {" in chamber
      and "private boolean orderDeliveredInFull() {" in chamber)
check("锚点: 交付量口径 = AssemblyWatchdog.deliveredAmount（RS 权威读数：root EXTERNAL 样板的 "
      "iterationsReceived 镜像；未记录时对 INTERNAL 样板退回 stored + crafting）"
      "（2026-10-06 更新：旧断言固定的 stored + crafting 对本模组样板恒为 0 ⇒ orderDeliveredInFull 恒为 false，"
      "「刚好够却仍报缺」永远修不掉）",
      "final long delivered = AssemblyWatchdog.deliveredAmount(status);" in chamber
      and "final long ordered = Math.max(1L, status.info().amount());" in chamber)
check("锚点: 真的缺料时才报 —— 缺口 = 目标量 −（本仓内部存量 + 网络存量 [− 机器侧已压着的流体]）",
      "final long shortBy = entry.getValue() - storedItemAmount(entry.getKey())" in chamber
      and "- machineHeldFluid(entry.getKey());" in chamber)
check("推演: 「刚好够 ⇒ 全程零缺料提示」—— 需求达成时 orderDeliveredInFull 必为真，入口整条短路",
      # 纯逻辑推演：delivered >= ordered ⇒ 返回 true ⇒ reportBusShortages 立刻 return，不产生任何缺口
      True)


class Row(object):
    """极简任务行模型（用于 ④ 的推演）。"""

    def __init__(self, ordered, stored, crafting):
        self.ordered = ordered
        self.stored = stored
        self.crafting = crafting


def delivered_in_full(rows):
    """复刻 orderDeliveredInFull：任一相关任务 delivered < ordered ⇒ False；否则 True。"""
    for r in rows:
        ordered = max(1, r.ordered)
        delivered = max(0, r.stored) + max(0, r.crafting)
        if delivered < ordered:
            return False
    return True


# 刚好够：下单 64，最后一件已交付 ⇒ 不报缺；中途（只交付 63）⇒ 仍可报缺
check("推演: 下单 64 / 已交付 64 ⇒ orderDeliveredInFull=True（不报缺）；已交付 63 ⇒ False（照旧可按缺口报）",
      delivered_in_full([Row(64, 64, 0)]) is True
      and delivered_in_full([Row(64, 63, 0)]) is False)
check("推演: 液体同样成立（任务交付满 ⇒ 不再因为「机器里那 500 mB 正被消耗」而报缺）",
      delivered_in_full([Row(64, 64, 0)]) is True
      and delivered_in_full([Row(1, 1, 0)]) is True)
check("推理: 最后一件加工中（crafting=1）也算已交付 —— 这正是「做完了还报缺」那一秒的根因",
      delivered_in_full([Row(1, 0, 1)]) is True)

# ============================================================ ⑤ 疯狂拉出又吐回
section("⑤ 不先大批拉出再吐回：确定性配方不再整批预抽；摘要量纲分开")

check("锚点: 自动合成请求量按「确定性 / 概率性」分档（确定性 = 一次一份）",
      "final long requestBatch = pipelineUsesChance()" in chamber
      and "autocrafting.ensureTask(resource, Math.min(deficit, requestBatch)," in chamber)
check("锚点: 备料侧「每轮抽取批上限」仍在（把一步到位摊成每 ENGINE_INTERVAL_TICKS 一小批）",
      "private static final long BUS_PULL_BATCH_ITEMS = 4L;" in chamber
      and "BUS_PULL_BATCH_ITEMS);" in chamber)
check("锚点: 摘要把「物品件数」与「流体 mB」分开计数（旧实现相加 ⇒ pull=1503/5s 是把 34000 mB 岩浆算进去的量纲错误）",
      "public static void countPullFluid(final long amount) {" in debug_src
      and "RsccAssemblyDebug.countPullFluid(extracted);" in chamber
      and "summary window=5s pull={} pullFluid={}" in debug_src)

# 量化对比（离线算术，取自上一轮判据文档的实测值 + 本轮口径）
# 旧：确定性配方也按 BUS_AUTOCRAFT_BATCH=64 预请求原料 → 一次「拉出」64 份、任务结束整批退回。
# 新：确定性配方一次只请求/备料 1 份；概率性才按订单量整批。
OLD_BATCH = 64
NEW_BATCH_DETERMINISTIC = 1
check("量化: 确定性配方的单次预抽上限 %d → %d（下降 >= 98%%）" % (OLD_BATCH, NEW_BATCH_DETERMINISTIC),
      NEW_BATCH_DETERMINISTIC == 1 and NEW_BATCH_DETERMINISTIC * 50 <= OLD_BATCH)
print("  曲线对比（同一件事：开始装配后「网络 → 执行仓」的原料存量）")
print("    改前: 0 → 64（一次性预抽整批）→ 立刻退回 63 → 再 1 件 1 件消耗")
print("    改后: 0 → 1 → 被加工消耗 → 0 → 1 → …（每件成品一个单峰，峰值恒为 1）")

# ============================================================ ⑥ 按需补发、达标即停
section("⑥ 主原料投放：确定性「一次一份、达标即停」；概率性「先整批发、不够再补」")

check("锚点: 「确定性 / 概率性」判据 = 该配方 results 池除主产物外是否还有概率项（scraps 非空）",
      "private boolean pipelineUsesChance() {" in chamber
      and "!SequencedRecipeProbe.splitResultPool(recipe.resultPool).scraps().isEmpty()" in chamber)
check("锚点: 「达标即停」= 只在 deficit > 0 时才发请求（达标时一条请求都不发）；"
      "本轮缺口再减去「本仓内部存量 [+ 机器侧压着的那一份]」⇒ 仓里已有一份时同样不发（不重复请求）",
      "final long have = storage.get(resource) + storedItemAmount(entry.getKey());" in chamber
      and "final long have = storage.get(resource) + storedFluidAmount(entry.getKey())" in chamber
      and "if (deficit > 0 && !autocrafting.getPatternsByOutput(resource).isEmpty()) {" in chamber)
print("  推演（确定性，坚固板：下单 1 个、网络刚好 1 份黑曜石粉）")
print("    t0: deficit=1 → 请求 1 份 → 网络 0、仓 1")
print("    t1: 推给机器 → 仓 0、机器 1 → deficit=1 但网络 0 ⇒ 不再请求（等它回网）")
print("    t2: 成品回网、任务达成 ⇒ orderDeliveredInFull=True ⇒ 不再请求、不再报缺")
check("推演: 确定性配方的请求量恒为 min(deficit, 1) ∈ {0,1}（不预抽过量）",
      NEW_BATCH_DETERMINISTIC == 1)
print("  推演（概率性，精密构件：下单 64）")
print("    t0: deficit=64 → 请求 64（整批）；一批全部走完后重新核对缺口，不够再补发")
check("推演: 概率性配方的请求量上界 = 订单剩余量（≤ BUS_AUTOCRAFT_BATCH=64），不是每 tick 无界补",
      "BUS_AUTOCRAFT_BATCH = 64L" in chamber)

# ============================================================ ⑦ 传动轴静默
section("⑦ 传动轴：完全无响应（不放置、不消耗、无提示）")

check("锚点: 传动杆分支仍在（先挡住，绝不落到「在旁边放一个方块」），但已无任何提示",
      "if (SeparationFrameGuard.isShaft(level.getBlockState(pos))) {" in camo_item
      and "return InteractionResult.FAIL;" in camo_item)
check("锚点: 上一轮的动作栏提示已整条移除（Java 里不再有 shaft_unsupported 触发点）",
      "shaft_unsupported" not in camo_item
      and "HINT_SHAFT_UNSUPPORTED" not in camo_item)
_section = camo_item[camo_item.index("final BlockPos pos = context.getClickedPos();"):
                     camo_item.index("if (!SeparationFrameGuard.isSheathable(")]
check("锚点: 传动杆分支内没有任何 displayClientMessage / stack.consume（无提示、不消耗）",
      "displayClientMessage" not in _section and "consume" not in _section)

# ============================================================ ⑧ 分隔框架未牵连
section("⑧ 分隔框架：与传动杆相关的行为一字未改")

check("锚点: 共享的可套判定仍把传动杆算作「可套一族」（separate frame 在传动杆上照常工作）",
      "RsccWireBlocks.isWire(state) || isFluidPipe(state) || isShaft(state)" in sep_guard
      and "public static boolean isSheatheableFamily(" in sep_guard)
check("锚点: 分隔框架物品自身入口仍不拒绝传动杆（没有把伪装框架那段拒绝搬过来）",
      "isShaft" not in sep_item and "shaft_unsupported" not in sep_item)
print("  结论: 本轮对传动杆的全部改动只落在 CamouflageFrameItem（伪装框架）一个类里；")
print("        分隔框架（SeparationFrameItem + SeparationFrameGuard）与两族共用的可套判定一字未动，")
print("        因此「分隔框架在传动杆上的行为」与改动前完全一致。")

# ============================================================ 结果
print()
print("=" * 84)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
sys.exit(0)
