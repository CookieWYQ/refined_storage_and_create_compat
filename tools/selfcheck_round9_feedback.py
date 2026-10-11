# -*- coding: utf-8 -*-
"""第 9 轮用户反馈（8 条）的源码锚点自检。

用法：
    python tools/selfcheck_round9_feedback.py

本脚本只做「源码锚点 + 可判定的口径」检查，不猜、不掩盖：
* HARD 项失败 ⇒ 退出码 1；
* INFO 项只打印取证结论（例如「本条本轮未交付」，见报告），不算失败。

逐条对应：
  ① 岩浆误报「缺少 500 mB」        → 缺口口径必须含网络存量 / 机器侧已压着的量
  ② 输入输出总线被自动标记          → 记录现状（本轮未交付，见报告）
  ③ 执行器/输入器的「显示」一闪就没  → 叠加层必须是手动切换、不随 GUI 关闭复位
  ④ 齿轮阻塞 / 多余中间产物         → 见 tools/verify_single_unit_supply.py（本脚本只做存在性锚点）
  ⑤ 归流缓存仓「选择面输入」没做好   → 面开关改动后必须作废 Neoforge 能力缓存
  ⑥ 归流缓存仓「阻塞功能」失效       → 阻塞判定必须落在「写回网络」的两条路径上
  ⑦ 没有下单却自动发料              → 必须有「有人为本产线下单」门控
  ⑧ 传动杆套伪装不显示              → 见报告（需要另一次实机取证）
"""
import json
import os
import re
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

problems = []


def report(name, ok, detail="", hard=True):
    print("  [%s] %s" % ("OK" if ok else ("X" if hard else "INFO"), name))
    if detail:
        print("       " + detail)
    if not ok and hard:
        problems.append(name)


def info(name, detail=""):
    report(name, True, detail, hard=False)


def read(relative):
    with open(os.path.join(JAVA, relative), "r", encoding="utf-8") as handle:
        return handle.read()


chamber = read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))
cache_be = read(os.path.join("block", "entity", "CollectionCacheBlockEntity.java"))
menu_cache = read(os.path.join("menu", "CollectionCacheMenu.java"))
overlay = read(os.path.join("client", "BusInterferenceOverlay.java"))
exp_mixin = read(os.path.join("mixin", "exporter", "AbstractExporterBlockEntityMixin.java"))
imp_mixin = read(os.path.join("mixin", "importer", "AbstractImporterBlockEntityMixin.java"))

print("=" * 78)
print("① 岩浆 / 物品缺料不再误报（缺口口径含网络存量；物品与流体都再含机器侧已压着的量）")
print("=" * 78)
report("锚点: 物品缺口 = 目标量 − 本仓内部存量 − 网络存量 − 供料目标（机器侧）已压着的量"
       "（第 15 轮补齐：机器上正压着的那一份必须算进可用量，否则原料刚被推进机器就误报缺料；"
       "与流体侧 machineHeldFluid 严格对称）",
       "- netItems.getOrDefault(entry.getKey(), 0L)" in chamber
       and "- machineHeldItem(entry.getKey());" in chamber)
report("锚点: 流体缺口 = 目标量 − 本仓罐内 − 网络存量 − 供料目标（机器侧）已压着的量",
       "- netFluids.getOrDefault(entry.getKey(), 0L) - machineHeldFluid(entry.getKey());" in chamber)
_report_start = chamber.index("private void reportBusShortages(")
report("锚点: 网络存量表（netItems / netFluids）的构建落在缺料判定之前（否则读到空表）",
       chamber.index("final Map<Item, Long> netItems = new LinkedHashMap<>();", _report_start)
       < chamber.index("final long shortBy = entry.getValue() - storedItemAmount", _report_start))
report("锚点: 缺口仍按「纯中间产物」整条跳过（既有硬要求，不许回退）",
       "if (intermediateOnly.contains(entry.getKey())) {" in chamber)

print()
print("=" * 78)
print("② 输入/输出总线被强制当成序列装配总线（本轮交付：条上「普通」按钮 + 持久化标志）")
print("=" * 78)
report("锚点: 两侧「界面归属判定」都被「强制普通总线」开关与「过滤槽有东西」收口"
       "（任一为真 ⇒ 一律回到普通总线界面）",
       # 2026-10-10（用户第 9 条）：收口条件多了一条 —— 过滤槽里有东西时也优先按普通总线处理
       # （判据与理由见 support/RsccBusConfig 与两个 mixin 的 rscc$hasFilterEntries）。
       # 本锚点因此升级为「两个收口条件都在」，而不是放宽成任意写法。
       "return rscc$isLinkedLayout() && !rscc$forceNormalBus && !rscc$hasFilterEntries();" in exp_mixin
       and "return rscc$isLinkedLayout() && !rscc$forceNormalBus && !rscc$hasFilterEntries();" in imp_mixin)
report("锚点: 「强制普通」是<b>每总线持久化</b>标志（落盘 + 读档保留，旧存档缺键 → false）",
       'RSCC_TAG_FORCE_NORMAL = "rscc_force_normal_bus"' in exp_mixin
       and 'RSCC_TAG_FORCE_NORMAL = "rscc_force_normal_bus"' in imp_mixin
       and "tag.putBoolean(RSCC_TAG_FORCE_NORMAL, rscc$forceNormalBus);" in exp_mixin
       and "rscc$forceNormalBus = tag.getBoolean(RSCC_TAG_FORCE_NORMAL);" in exp_mixin
       and "tag.putBoolean(RSCC_TAG_FORCE_NORMAL, rscc$forceNormalBus);" in imp_mixin
       and "rscc$forceNormalBus = tag.getBoolean(RSCC_TAG_FORCE_NORMAL);" in imp_mixin)
report("锚点: 强制普通时「归属」恒为未确定 ⇒ 真的走回 RS 原版路径（不丢不复制）",
       "rscc$linkedPosCache = rscc$forceNormalBus || report.disabled()"
       " || report.reachableCount() != 1" in exp_mixin
       and "rscc$linkedPosCache = rscc$forceNormalBus || report.disabled()"
           " || report.reachableCount() != 1" in imp_mixin)
report("锚点: <b>状态信息不丢</b>（用户硬要求）—— 「已停用红条 + 显示可达区域按钮」的依据是"
       "「原始布局判定」（不含强制普通开关），界面同步链路也改用它",
       "public boolean rscc$isLinkedLayout()" in exp_mixin
       and "public boolean rscc$isLinkedLayout()" in imp_mixin
       and "rscc$pushInterference(executorMode != null && executorMode.rscc$isLinkedLayout());"
       in read(os.path.join("mixin", "exporter", "ExporterContainerMenuMixin.java"))
       and "rscc$pushInterference(executorMode != null && executorMode.rscc$isLinkedLayout());"
       in read(os.path.join("mixin", "importer", "ImporterContainerMenuMixin.java")))
widget = read(os.path.join("client", "widget", "ExporterExecutorRowWidget.java"))
report("锚点: 条上有「普通」按钮（绘制 + 命中 + 点击同源），点它写的是 C2S 包（服务端权威）",
       "private static final int FORCE_X = 132;" in widget
       and "private boolean inBarForce(final double mouseX, final double mouseY)" in widget
       and "source.toggleForceNormal(true);" in widget
       and "source.toggleForceNormal(!forceNormal());" in widget)
report("锚点: 普通界面里那颗「普通 / 总线」按钮<b>常驻</b>（可转换即画），且在普通与总线之间来回切换",
       "private static final int RESTORE_Y = BAND_Y;" in widget
       and "private boolean inNormalToggle(final double mouseX, final double mouseY)" in widget
       and "source.toggleForceNormal(!forceNormal());" in widget)
main_src = read("RS_Create_Compat.java")
report("锚点: 两个 C2S 包（输出 / 输入）都已注册进 registerPayloads",
       "SetExporterForceNormalPacket.STREAM_CODEC" in main_src
       and "SetImporterForceNormalPacket.STREAM_CODEC" in main_src)

print()
print("=" * 78)
print("③ 执行器/输入器的「显示」一闪就没（= 可达区域半透明叠加层）")
print("=" * 78)
report("锚点: 换容器 id 时不再复位「显示」（关掉再打开仍是开的）",
       "旧界面上的「显示」状态不该跟到新界面上" not in overlay)
report("锚点: 渲染阶段不再「界面一关就自动收口」",
       "界面已关闭：自动收口" not in overlay and "打开了别的界面：自动收口" not in overlay)
report("锚点: 仍只在「玩家按下按钮 + 服务端权威判定为已停用」时渲染",
       "if (!shown || !disabled) {" in overlay)
info("语义说明: 本开关与伪装显形 K 键完全无关（一个是线缆归属，一个是伪装套壳），不存在两套语义打架")

print()
print("=" * 78)
print("④ 齿轮阻塞 / 多余中间产物")
print("=" * 78)
report("锚点: 「能不能搬运」仍由 相关任务 + 本仓真的有单 + 未冻结 三闸门共同决定（⑦ 的落点）",
       "busAutoCraftGate && busRelevantTaskGate && busGoalTaskGate && !busFrozenGate" in chamber)
info("量化判定: 由 tools/verify_single_unit_supply.py 按 docs/GEAR_BLOCKAGE_AND_FLOW_CHECK.md "
     "的判据 A~D 输出分布与结论（本脚本不重复实现）")

print()
print("=" * 78)
print("⑤ 归流缓存仓「选择面输入」没做好（面开关不生效）")
print("=" * 78)
report("锚点: 两个物流视图都按「该面是否允许输入」逐面开放（未选中的面返回 null）",
       cache_be.count("if (direction != null && !isInputFace(direction)) {") == 2)
report("锚点: 改面开关后显式作废 Neoforge 能力缓存（否则缓存仍是旧的 null，开关点了没反应）",
       cache_be.count("rscc$invalidateLogisticsCapabilities();") == 2
       and "private void rscc$invalidateLogisticsCapabilities() {" in cache_be)
report("锚点: 作废点在 setInputFace（服务端权威写入）与 applyInputFacesMask（读档 / 同步）两处",
       "inputFaces = next;\n        setChanged();\n        rscc$invalidateLogisticsCapabilities();" in cache_be)

print()
print("=" * 78)
print("⑥ 归流缓存仓「阻塞功能」（本轮补：<b>按标签</b>阻塞 —— 用户「注册了所有板子，放金板却瞬间回流」）")
print("=" * 78)
report("锚点: 物品写回网络前查阻塞名单（含按标签阻塞）",
       "if (isBlockedStack(inSlot)) {" in cache_be
       and "return itemMatchesAnyTag(item, blockedItemTags);" in cache_be)
report("锚点: 流体写回网络前查阻塞名单（含按标签阻塞）",
       "if (isBlockedFluid(entry.id())) {" in cache_be)
report("锚点: 阻塞名单落盘（方块实体 NBT 读写）+ 经同步包回传客户端（服务端权威，客户端只读）",
       "writeBlocked(tag);" in cache_be and "loadBlocked(tag);" in cache_be)
report("锚点: 「按标签阻塞」两个名单必须落盘（旧存档缺键 → 空集，行为与改动前一致）",
       'TAG_BLOCKED_ITEM_TAGS = "BlockedItemTags"' in cache_be
       and 'TAG_BLOCKED_FLUID_TAGS = "BlockedFluidTags"' in cache_be
       and "readBlockedList(tag.getList(TAG_BLOCKED_ITEM_TAGS, Tag.TAG_STRING), blockedItemTags);" in cache_be)
report("锚点: 服务端写入入口把「条目的标签集合」一起写进按标签阻塞名单",
       "block.setBlockedTags(fluid, tags, blocked);" in menu_cache)
report("锚点: 界面把条目的标签一起下发（主界面 Ctrl+左键 / 子界面开关同一条协议）",
       "sendBlockedToggle(entry.fluid(), entry.id(), entry.tags());" in
       read(os.path.join("client", "screen", "CollectionCacheScreen.java"))
       and "tagMode ? effectiveTags() : List.of()" in
       read(os.path.join("client", "screen", "CollectionMarkerConfigScreen.java")))
report("锚点: 「未阻塞的其它资源逐步回网、阻塞的留在缓存」—— 每 tick 受处理速率 / 吞吐升级约束，"
       "阻塞项直接 continue 且不占预算",
       "budget--;" in cache_be and "continue; // 阻塞（含按标签阻塞）：流体同样只留在本机缓存，不写回网络" in cache_be)

print()
print("=" * 78)
print("⑦ 没有下单却自动发料做序列装配")
print("=" * 78)
report("锚点: 「本仓真的有单」= 存在以本仓产物/在制件为目标的活跃任务（只有一份实现）",
       "private boolean hasGoalTask() {" in chamber)
report("锚点: 目标物品集合 = 各单元样板的配方产物 ∪ 过渡件（判不了时返回 null ⇒ 保守放行）",
       "private Set<Item> computeGoalItems(final Level level) {" in chamber
       and "private Set<Item> goalItemsCache;" in chamber)
report("锚点: 取料/投料入口（fillInternalForBus）在没单时整条短路",
       "if (!busAutoCraftGate || !busRelevantTaskGate || !busGoalTaskGate || !busConfigGate" in chamber)
report("锚点: 推送闸门（isAutoCraftingEnabled）同样要求「有单」",
       "busAutoCraftGate && busRelevantTaskGate && busGoalTaskGate && !busFrozenGate" in chamber)
report("锚点: 自动合成请求（requestMissingViaAutocraft）仍在同一入口（fillInternalForBus）之内 ⇒ 无单时零请求；"
       "本轮该请求改用「在制名额夹量之前」的目标表（autocraft*），因此「已有一件在制」时也不会漏掉补合成"
       "（用户第 ④ 条：金板配方必须能被正常调用）",
       "requestMissingViaAutocraft(network, autocraftItemTargets, autocraftFluidTargets);" in chamber
       and "final Map<Item, Long> autocraftItemTargets = new LinkedHashMap<>(itemTargets);" in chamber)
report("锚点: 目标物品集合与相关性集合同一节拍重建（数据包重载可自愈）",
       "goalItemsCache = computeGoalItems(level);" in chamber)

print()
print("=" * 78)
print("⑧ 传动杆套上伪装后不显示（取证结论 + 本轮交付：显式拒绝，不消耗）")
print("=" * 78)
info("取证结论: Create 的 jar 内嵌 Flywheel（META-INF/jarjar/flywheel-neoforge-1.21.1-1.0.6.jar），"
     "启动日志里 Flywheel 已加载；Create 用 kinetic.base.ShaftVisual /"
     " SingleAxisRotatingVisual.shaft(...)（AllPartialModels.SHAFT）在 Flywheel 里画传动杆，"
     "而 KineticBlockEntityRenderer 在 VisualizationManager.supportsVisualization(...) 为真时提前返回 ⇒"
     " 本模组「替换烘焙模型」的外壳链路接不到 Flywheel 的实例化渲染 ⇒ 该方案不可修")
camouflage_item = read(os.path.join("item", "CamouflageFrameItem.java"))
report("交付锚点（本轮改为「静默」）: 伪装框架点在传动杆上<b>完全不响应</b> —— 不放置、不消耗、"
       "<b>无动作栏提示</b>（用户第 7 条：「不要显示『套不上』，直接无反应就行了」）",
       "SeparationFrameGuard.isShaft(level.getBlockState(pos))" in camouflage_item
       and "HINT_SHAFT_UNSUPPORTED" not in camouflage_item
       and "shaft_unsupported" not in camouflage_item
       and "return InteractionResult.FAIL;" in camouflage_item)
report("交付锚点: 共享的可套判定与分隔框架<b>一字未改</b>（传动杆上分隔框架照常工作，不牵连既有正确行为）",
       "RsccWireBlocks.isWire(state) || isFluidPipe(state) || isShaft(state)"
       in read(os.path.join("support", "SeparationFrameGuard.java")))
with open(os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                       "lang", "zh_cn.json"), "r", encoding="utf-8") as handle:
    _zh = json.load(handle)
with open(os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                       "lang", "en_us.json"), "r", encoding="utf-8") as handle:
    _en = json.load(handle)
_key = "block.rs_create_compat.camouflage_frame.hint.shaft_unsupported"
# 第 11 轮（全量文本自检）：该拒绝提示早已改为「静默」，语言键也作为孤儿键一并删除，
# 因此本锚点改为断言「中英两处都不再留这条键」（原来是断言它存在且中英成对）。
report("交付锚点: 拒绝提示已整条移除（语言里不再留孤儿键）",
       _key not in _zh and _key not in _en,
       "zh 含该键=%s / en 含该键=%s" % (_key in _zh, _key in _en))

print()
print("=" * 78)
if problems:
    print("自检问题总数: %d" % len(problems))
    for item in problems:
        print("  - %s" % item)
    sys.exit(1)
print("自检问题总数: 0")
sys.exit(0)
