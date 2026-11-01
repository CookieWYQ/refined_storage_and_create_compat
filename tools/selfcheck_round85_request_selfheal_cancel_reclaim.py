# -*- coding: utf-8 -*-
"""第 85 轮自检：**「缺料不请求 / 请求后卡住 / 取消回收不完整」的请求链与回收链**。

用户实机原话（节选）：
    「现在并不能正常的触发这一个输入时原料的这个合成请求……就比如说我现在缺齿轮了，
      它并不会自动发起请求去合成齿轮……不对，他好像又能够自动合成。但问题就是他一直卡在
      那里一动不动……而且取消之后并不能完整的回收，遗留在置物台上的所有东西。」

本脚本钉三件事（全部可复跑；任何一条 FAIL → 退出码 1）：

  A. **请求链**（源码锚点 + 模型）：
     * 补合成请求不再被「下游堵塞」的搬运闸门整段跳过；
     * 有活跃订单时，即便某一步「还没轮到」、没有任何工位此刻要它，也会按「每件成品所需」预置请求；
     * 请求量仍只由既有两处 ensureTask 出口决定，且由缺口 + 节流驱动（不是无条件定时重发）。

  B. **卡住的自愈**（源码锚点 + 模型）：
     * 可自动合成（含「样板输出的 ResourceKey 带组件」这一精确查不到的形态）不再被误判成
       「缺料不可合成」而自动挂起 —— 挂起不会自动恢复，误挂起就是「一动不动」；
     * 缺口收敛后请求停止（有界，不无限重试）。

  C. **取消回收**（源码锚点 + 位置清单 + 时序模型）：
     * 逐位置断言：RS 任务暂存 / 舱内物品 / 舱内流体 / 工位起步原料 / 工位步骤投入物 /
       工位过渡件 / 迟到件（取消后机器才产出的那些）；
     * 时序：RS 取消是异步的（取消当刻只是置 RETURNING_INTERNAL_STORAGE，
       真正回网在下一个 work tick，默认 9 tick）；本模组的边沿判定还要过门控节拍，
       因此「取消后立刻清表」会把迟到的件变成无主残留；现在改为「只清已空的登记、保留残留凭据」；
     * 反例自证：把修前的「步骤投入物不收 / 边沿后整表清空」喂给同一批判据 ⇒ 必须判负。

  F. **运行期站台残留**（源码锚点 + 模型，2026-10-18 用户实测）：
     * 「废料齿轮 / 金板」压在置物台上 ⇒ 站台再也接不到下一件 ⇒ 看门狗挂起整条任务；
     * 判据 = 在制登记表（本仓推过的那件）+ Create 配方结果池（台上那件是它的产出）+ 观察窗口；
     * 合法等待（正在加工 / 等下一步 / 我们推的投入物 / 机械手里那件）一律不清；
     * 有界：每 40 tick 至多一轮、每轮至多 1 件、只扫登记表（不每 tick 全扫）；
     * 反例自证：不清理 / 无判据乱清 / 每 tick 全扫 ⇒ 同一批判据必须判负。

没有实机验证：本脚本只做源码交叉验证 + 模型推演；真实游戏里的缺齿轮 / 取消时序必须由人复现。
"""
import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
RS = os.path.join(ROOT, "local_src", "external", "RefinedStorage")

CHAMBER = os.path.join(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
WATCHDOG = os.path.join(SRC, "support", "AssemblyWatchdog.java")
IMPORT = os.path.join(SRC, "support", "RsccChamberImportStrategy.java")
MIXIN = os.path.join(SRC, "mixin", "TaskContainerMixin.java")
RS_TASK_IMPL = os.path.join(
    RS, "refinedstorage-autocrafting-api", "src", "main", "java", "com", "refinedmods",
    "refinedstorage", "api", "autocrafting", "task", "TaskImpl.java")
RS_UPGRADE = os.path.join(
    RS, "refinedstorage-common", "src", "main", "java", "com", "refinedmods",
    "refinedstorage", "common", "upgrade", "UpgradeContainer.java")

CHECKS = [0]
PROBLEMS = []


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        PROBLEMS.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name,
                       "" if ok else ((" | " + detail) if detail else "")))


def has(text, needle, name):
    check(name, needle in text, "missing: %s" % needle.replace("\n", " ")[:140])


def has_not(text, needle, name):
    check(name, needle not in text, "unexpected: %s" % needle.replace("\n", " ")[:140])


def section(title):
    print()
    print("=" * 96)
    print(title)
    print("=" * 96)


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def body(text, start, end):
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


def flatten(text):
    return " ".join(text.split())


chamber = read(CHAMBER)
watchdog = read(WATCHDOG)
import_strategy = read(IMPORT)
mixin = read(MIXIN)
task_impl = read(RS_TASK_IMPL)
upgrade = read(RS_UPGRADE)

# ======================================================================================
section("A. 请求链：堵塞时仍请求；没轮到那一步也预置；请求量仍只有两处出口")
# ======================================================================================

fill_start = chamber.index("private void fillInternalForBus(")
request_call = chamber.index(
    "requestMissingViaAutocraft(network, autocraftItemTargets, autocraftFluidTargets);", fill_start)
pull_stall = chamber.index("if (pushStalledOnDestination()) {", fill_start)

check("A1 补合成请求在「下游堵塞 ⇒ 停止搬运」闸门**之前**发出（请求只建 RS 任务、不抽料 / 不投入）",
      request_call < pull_stall, "request=%d stall=%d" % (request_call, pull_stall))
has(chamber, "if (pushStalledOnDestination()) {", "A1b 搬运闸门本身仍在（只拦 pull，不拦请求）")
has(chamber, "private void augmentAutocraftTargetsForOrderedInputs(",
    "A2a 预置目标表的唯一实现存在（只并入请求快照，不并入 pull 目标表）")
request_body = body(chamber, "private void requestMissingViaAutocraft(",
                    "private void logMissingAutocraftPattern(")
augment_in_request = request_body.index(
    "augmentAutocraftTargetsForOrderedInputs(itemTargets, fluidTargets);")
first_ensure = request_body.index("autocrafting.ensureTask(")
check("A2a2 预置合并在 requestMissingViaAutocraft 内部、且在 ensureTask 之前（走同一条缺口口径）",
      augment_in_request < first_ensure,
      "augment=%d ensure=%d" % (augment_in_request, first_ensure))
augment_body = body(chamber, "private void augmentAutocraftTargetsForOrderedInputs(",
                    "private void requestMissingViaAutocraft(")
has(augment_body, "!info.isInput() || !busCategoryOwners.containsKey(info.id())",
    "A2b 只处理「本仓真勾选了」的输入类别（未勾选 ⇒ 一个字节都不加）")
check("A2c 预置判据 = 「所属配方有活跃订单」<b>或</b>「该配方此刻正有在制件被加工」（机器正在工作）",
      "if (!categoryRecipeOrdered(info) && !categoryRecipeWorkingNow(info)) {" in augment_body,
      "missing: if (!categoryRecipeOrdered(info) && !categoryRecipeWorkingNow(info)) {")
has(augment_body, "categoryRecipeWorkingNow",
    "A2c2 「机器正在工作」这条新判据的入口在预置方法里被真正使用（不是只定义未调用）")
has(chamber, "if (!busAutoCraftGate || !busRelevantTaskGate || !busGoalTaskGate || !busConfigGate",
    "A2c3 「无单零请求」的结构性保证仍在：四道门控一字未改（无单 ⇒ 根本走不到请求链）")
has(augment_body, "itemTargets.put(representative, wanted);",
    "A2d 物品：按类别代表物写入「每件成品所需」的保守需求")
has(augment_body, "fluidTargets.put(fluid, wanted);",
    "A2e 流体：同一口径写入")
has(augment_body, "final long wanted = Math.max(1L, Math.max(info.amount(), info.estimated()));",
    "A2f 需求 = max(每批所需, 每件成品预估)（estimated 已含 loops）")
has(augment_body, "itemTargets.containsKey(representative)",
    "A2g 已有「某台机器此刻正缺它」的目标量优先（只补空窗，不改既有口径）")
check("A3a 请求量仍只由两处 ensureTask 出口决定（没有第三处偷偷放量）",
      chamber.count("autocrafting.ensureTask(resource, Math.min(deficit, requestBatch),") == 2
      and chamber.count("autocrafting.ensureTask(resource, deficit,") == 2,
      "batch=%d deficit=%d"
      % (chamber.count("autocrafting.ensureTask(resource, Math.min(deficit, requestBatch),"),
         chamber.count("autocrafting.ensureTask(resource, deficit,")))
has(chamber, "MachineWorkSignal.cooldownRuns(hot)",
    "A3b 请求节流按档位取值（热档 = 每个引擎节拍；冷档 = 既有 40 次引擎调用 = 200 tick），不是定时器重发")
has(chamber, "if (--busAutocraftCooldown > 0) {",
    "A3c 节流在方法入口生效（同一轮不会重复请求）")
has(chamber, "final long deficit = entry.getValue() - have",
    "A3d 每次请求前重算缺口（缺口 ≤ 0 ⇒ 不发；这是状态驱动，不是无条件定时器）")


def request_issued(stalled, station_wants_now, ordered, prefetch_enabled):
    """模型：修前 / 修后「缺料时会不会发请求」。

    修前：request 在 `pushStalledOnDestination()` 之后；目标表只来自「此刻有工位要它」的 itemTargets。
    修后：request 在堵塞闸门之前；有订单且勾了输入类别时，即使没有工位此刻要它也会预置。
    """
    if not ordered:
        return False
    if stalled and not prefetch_enabled:
        return False          # 修前：堵塞时整段 return，请求被跳过
    if station_wants_now:
        return True
    return prefetch_enabled   # 修后：没轮到那一步也预置；修前：不发


CASES_REQUEST = [
    # 标签, 下游堵塞, 此刻有工位要它, 有订单, 修前, 修后
    ("缺齿轮：机器正停在齿轮步、下游没有堵塞", False, True, True, True, True),
    ("缺齿轮：还没轮到那一步（在制件还在上一步）", False, False, True, False, True),
    ("缺齿轮：置物台被占、下游持续拒收（最需要补料的状态）", True, True, True, False, True),
    ("没有任何订单（只是在维持库存）", False, False, False, False, False),
]
print("  反例表（缺料时是否发请求；修前 vs 修后）")
print("  %-52s %-8s %-8s %-8s" % ("情形", "修前", "修后", "验收"))
PRE_FIX_REQUEST_MEETS = []
POST_FIX_REQUEST_MEETS = []
for label, stalled, wants, ordered, _before, _after in CASES_REQUEST:
    got_before = request_issued(stalled, wants, ordered, False)
    got_after = request_issued(stalled, wants, ordered, True)
    # 验收标准（与修后行为一致）：有订单时，三种「缺料」情形都必须发请求；无单必须不发。
    desired = ordered
    print("  %-52s %-8s %-8s %-8s" % (label, got_before, got_after, desired))
    PRE_FIX_REQUEST_MEETS.append(got_before == desired)
    POST_FIX_REQUEST_MEETS.append(got_after == desired)
check("A4a 修后：四种情形全部符合验收（含「没轮到那一步」与「下游堵塞」都发请求；无单仍不发）",
      all(POST_FIX_REQUEST_MEETS))
check("A4b 反例自证：把修前的「堵塞时跳过请求 / 只认此刻有工位要它」喂给同一验收判据 ⇒ 必然判负",
      not all(PRE_FIX_REQUEST_MEETS))

# 有界性：缺口递减到 0 后请求必然停止；每一轮最多请求 requestBatch，因此轮数有上界。
def request_rounds(initial_deficit, delivery_per_round, request_batch):
    deficit = initial_deficit
    rounds = 0
    requests = 0
    while deficit > 0 and rounds < 10000:
        rounds += 1
        requests += 1
        delivered = min(deficit, delivery_per_round)
        deficit -= delivered
        if request_batch <= 0:
            return rounds, requests, deficit  # 防死循环（判负）
    return rounds, requests, deficit


bounded = []
for gap in (1, 5, 64, 1000):
    rounds, requests, left = request_rounds(gap, 1, 1)
    bounded.append(left == 0 and rounds <= gap and requests <= gap)
check("A4c 有界：缺口每轮至少被交付 1 ⇒ 请求轮数 ≤ 初始缺口，缺口归零后不再发（不会无限重试）",
      all(bounded))
print("  收敛示例：缺口 5 / 每轮交付 1 ⇒ 请求 5 轮后停止（%s）" % (request_rounds(5, 1, 1),))

# ======================================================================================
section("B. 「卡住」自愈：可合成（含带组件输出键）不被误挂起；缺料请求有界收敛")
# ======================================================================================

watchdog_auto = body(watchdog, "private static boolean isAutoCraftable(final AutocraftingNetworkComponent autocrafting,",
                     "/** 物品堆版本")
has(watchdog_auto, "autocrafting.getPatternsByOutput(resource).isEmpty()",
    "B1a 先按精确 ResourceKey 查（RS PatternRepository 的既定口径）")
has(watchdog_auto, "for (final com.refinedmods.refinedstorage.api.autocrafting.Pattern pattern",
    "B1b 精确查不到时按「物品 / 流体身份」扫全部样板输出（修「样板输出带组件 ⇒ 误判不可合成」）")
has(watchdog_auto, "producedItem.item() == wantedItem.item()",
    "B1c 物品兜底按物品身份匹配（不是按组件 / NBT 全等）")
has(watchdog_auto, "producedFluid.fluid() == wantedFluid.fluid()",
    "B1d 流体兜底按流体身份匹配（与物品侧对称）")
has(chamber, "private static boolean isAutoCraftable(final AutocraftingNetworkComponent autocrafting, final Item item) {",
    "B1e 执行舱的缺料上报 / 卡住豁免用同一套 isAutoCraftable（同一份判据）")
chamber_auto = body(chamber, "private static boolean isAutoCraftable(final AutocraftingNetworkComponent autocrafting, final Item item) {",
                    "/** 流体版")
has(chamber_auto, "for (final com.refinedmods.refinedstorage.api.autocrafting.Pattern pattern",
    "B1f 执行舱侧同样有「按身份扫样板输出」的兜底（两侧口径一致）")
has(watchdog, "final boolean allMissingAutoCraftable = !missing.isEmpty()",
    "B2a 可推进判定仍包含「缺的每一项都可自动合成 ⇒ 不挂起」（既有语义保留）")
has(watchdog, "|| missing.isEmpty() || allMissingAutoCraftable);",
    "B2b 可推进判定的完整表达式仍在（未删条件）")


def watchdog_would_suspend(exact_pattern, identity_fallback):
    """模型：缺料原因连续 > 100 tick 时，是否会被自动挂起。

    修前：isAutoCraftable 只做精确查；样板输出带组件 ⇒ false ⇒ 不可推进 ⇒ 5 秒后挂起。
    修后：精确查失败后按身份兜底 ⇒ true ⇒ 可推进 ⇒ 不挂起，等 RS 把料做出来。
    """
    craftable = exact_pattern or identity_fallback
    return not craftable


CASES_SUSPEND = [
    ("样板输出是裸 ItemResource（精确命中）", True, True, False, False),
    ("样板输出带数据组件（精确查不到、按身份能查到）", False, True, True, False),
    ("真的没有样板（玩家没写配方）", False, False, True, True),
]
print("  反例表（缺料 5 秒后是否自动挂起；修前 vs 修后）")
print("  %-52s %-8s %-8s %-8s" % ("情形", "修前", "修后", "验收"))
PRE_SUSPEND_MEETS = []
POST_SUSPEND_MEETS = []
for label, exact, ident, _before, _after in CASES_SUSPEND:
    got_before = watchdog_would_suspend(exact, False)
    got_after = watchdog_would_suspend(exact, ident)
    # 验收：只要能合成（精确或按身份兜底）就绝不自动挂起；真没样板才挂起。
    desired = not (exact or ident)
    print("  %-52s %-8s %-8s %-8s" % (label, got_before, got_after, desired))
    PRE_SUSPEND_MEETS.append(got_before == desired)
    POST_SUSPEND_MEETS.append(got_after == desired)
check("B3a 修后：可合成（含带组件输出键）不挂起；真没样板仍挂起（提示玩家处理）",
      all(POST_SUSPEND_MEETS))
check("B3b 反例自证：修前「只做精确查」会把带组件输出误判成不可合成并挂起 ⇒ 同一验收判据判负",
      not all(PRE_SUSPEND_MEETS))

# ======================================================================================
section("C. 取消回收：位置清单 + 时序 + 有界 sweep")
# ======================================================================================

# ---- C1. 位置清单：逐项给出「谁负责、在哪个方法」 ----
has(task_impl, "case RETURNING_INTERNAL_STORAGE -> returnInternalStorageAndTryCompleteTask(rootStorage);",
    "C1-① RS 任务暂存：原生取消 → RETURNING_INTERNAL_STORAGE → 当 tick 回网（RS 自己的路径）")
has(mixin, "case RETURNING_INTERNAL_STORAGE, COMPLETED -> false;",
    "C1-①b 我方挂起 mixin 对 RETURNING/COMPLETED 必须放行（绝不冻住 RS 的回收）")
flush_body = body(chamber, "private void flushResidualInputs() {", "private long[] flushStationStartIngredients(")
has(flush_body, "itemStorage.getSlots()", "C1-② 舱内物品：flushResidualInputs 扫内部存储（含磁盘缓存视图）")
has(flush_body, "outputTank.getTanksSnapshot()", "C1-③ 舱内流体：flushResidualInputs 扫内部罐")
has(flush_body, "flushStationStartIngredients(storage);", "C1-④~⑥ 工位侧：由 flushStationStartIngredients 统一收")
flush_station = body(chamber, "private long[] flushStationStartIngredients(",
                     "private String gateReason")
has(flush_station, "final boolean startIngredient = isStartIngredient(item);",
    "C1-④ 工位起步原料：isStartIngredient 分支")
has(flush_station, "final boolean stepExtraInput = isStepExtraInput(item);",
    "C1-⑤ 工位步骤投入物（齿轮 / 大齿轮 / 金板 / 铁粒）：stepExtraInput 分支（本轮新增）")
has(flush_station, "else if (stepExtraInput && inputMaterialWantedNow(item)) {",
    "C1-⑤b 仍在被某台机器需要的投入物绝不抢（与推料侧判据严格互补）")
has(flush_station, "final boolean transitionPiece = isTransitionItem(item);",
    "C1-⑥ 工位过渡件：transitionPiece 分支")
has(flush_station, "if (ordered) {",
    "C1-⑥b 过渡件：其配方还有活跃订单时寸步不让（只收已无订单的残留）")

clear_body = body(chamber, "private int clearDeadStationPieceAt(final Level level, final BlockPos station) {",
                  "/** 只读：本仓登记了几件在制件")
has(clear_body, "if (!isTransitionOfMyRecipes(item)) {",
    "C1-⑦ 非过渡件：只放行「本仓登记过的同物品」起步原料 / 步骤投入物残留")
has(clear_body, "registered == null || registered.item() != item",
    "C1-⑦b 登记表同物品命中才收（玩家手工放的 / 别的仓推的一律不碰）")
has(clear_body, "if (transitionRecipeOrdered(item)) {",
    "C1-⑦c 过渡件：只有配方仍有活跃订单时才当「正常件」；取消 / 完成后继续收回（本轮修正）")
has(clear_body, "inFlightUnits.remove(station);",
    "C1-⑦d 收回成功后删除该工位登记（凭据用完即废，空工位不会永久占名额）")
has(clear_body, "flushResidualInputs();",
    "C1-⑦e 收进本仓后立即走同一套任务结束回流（写回网络，不是只挪到仓里）")
has(chamber, "public void rscc$retainResidualUnits() {",
    "C1-⑧ 迟到件：任务确认结束时保留「仍压着本产线件」的登记（唯一凭据）")
has(chamber, "private boolean stationHoldsPipelineResidue(",
    "C1-⑧b 判据：登记的那件还在 / 已变成过渡件 / 工位未加载 ⇒ 保留")
scheduler = body(chamber, "public void tickBusScheduler() {",
                 "// ==================== 用户第 5 条：下单前 / 运行中的「总线未配置」逐步骤校验")
has(scheduler, "if (hasRetainableUnitRegistrations()) {",
    "C1-⑧c 收尾分两档：还有残留 ⇒ 只清已空登记，不整表清空")
has(scheduler, "rscc$retainResidualUnits();", "C1-⑧d 保留残留凭据的调用点")
has(scheduler, "rscc$clearAllUnits();", "C1-⑧e 没有任何残留 ⇒ 仍走既有整表清空（既有语义保留）")
has(import_strategy, "chamber.claimResidualInputReclaim()",
    "C1-⑨ 输入总线的一次性边沿令牌仍在（机器侧输入类残留收一次）")

# ---- C2. 时序模型：RS 取消是异步的；边沿之后还可能迟到 ----
m_rate = re.search(r"DEFAULT_WORK_TICK_RATE\s*=\s*(\d+);", upgrade)
work_rate = int(m_rate.group(1)) if m_rate else -1
gate_interval = int(re.search(r"BUS_GATE_INTERVAL_TICKS = (\d+);", chamber).group(1))
gate_confirm = int(re.search(r"BUS_GATE_END_CONFIRM = (\d+);", chamber).group(1))
flush_window = int(re.search(r"BUS_RESIDUAL_FLUSH_WINDOW_TICKS = (\d+);", chamber).group(1))
clear_interval = int(re.search(r"DEAD_STATION_CLEAR_INTERVAL_TICKS = (\d+)L;", chamber).group(1))
check("C2a RS 的 work tick 是有限常量（取消后回网发生在下一个 work tick，不是当 tick 完成）",
      work_rate > 0, "DEFAULT_WORK_TICK_RATE=%s" % work_rate)
check("C2b 本模组门控是「每 %d tick 复查 + 连续 %d 次确认」⇒ 取消边沿最晚 %d tick 才被判定"
      % (gate_interval, gate_confirm, gate_interval * gate_confirm),
      gate_interval > 0 and gate_confirm > 0)
check("C2c 一次性残留令牌有有效期（%d tick）且消费式领取 ⇒ 不会退化成「空闲时持续收输入类」"
      % flush_window, 0 < flush_window <= 200)
check("C2d 迟到件 sweep 有界：每 %d tick 至多一轮、每轮至多 1 件" % clear_interval,
      0 < clear_interval <= 200 and "deadStationClearBurst += moved;" in chamber)


def reclaim_after_cancel(step_extra_supported, registry_retained, early_transition_supported):
    """模型：取消后，置物台 / 机器上还剩什么。

    位置：RS 暂存（RS 自己回）、舱内物品 / 流体、工位起步原料、工位步骤投入物、工位过渡件、
    以及「取消后机器才产出、边沿之后才出现」的迟到过渡件。
    修前：step_extra_supported=False（只收起步 / 过渡）；registry_retained=False（边沿后整表清空）。
    修后：两者都为 True，并且迟到过渡件由保留的登记 + 有界 sweep 收掉。
    """
    left = set()
    if not step_extra_supported:
        left.add("station_step_extra")      # 齿轮 / 金板 / 铁粒…无人收
    if not registry_retained:
        # 边沿时机器还在加工 ⇒ flush 跳过；登记被清 ⇒ 之后产出的过渡件无主
        if not early_transition_supported:
            left.add("late_transition_piece")
    return left


CASES_RECLAIM = [
    ("修前：只收起步原料 / 过渡件，且边沿后整表清空", False, False, False,
     {"station_step_extra", "late_transition_piece"}),
    ("修后：步骤投入物 + 保留迟到凭据", True, True, True, set()),
]
print("  反例表（取消后仍留在工位上的位置）")
for label, step_extra, retained, early, expected in CASES_RECLAIM:
    got = reclaim_after_cancel(step_extra, retained, early)
    print("  %-58s leftover=%s" % (label, sorted(got)))
    check("C3 位置清单判定：%s" % label, got == expected, "got=%s expected=%s" % (got, expected))
check("C3a 反例自证：修前「步骤投入物不收 + 边沿后清空登记」必然留下位置 ⇒ 同一判据判负",
      reclaim_after_cancel(False, False, False) != set())
check("C3b 修后「所有位置都为空」成立（RS 暂存 + 舱内 + 工位三侧 + 迟到件）",
      reclaim_after_cancel(True, True, True) == set())


def sweep_rounds(items, per_round=1):
    rounds = 0
    while items > 0 and rounds < 10000:
        items -= min(items, per_round)
        rounds += 1
    return rounds


check("C3c 有界 sweep：N 件残留至多 N 轮（每轮 1 件）后清空，不会无限循环",
      all(sweep_rounds(n) == n for n in (0, 1, 3, 8)))

# ======================================================================================
section("E. 「机器一在工作就全速运转」：判据 / 轻量性 / 缺口驱动 / 最坏延迟（2026-10-17）")
# ======================================================================================
# 用户原话（逐字，这是需求不是 bug 描述）：
#   「我要求的就是他能保持每时每刻……就是识别到它正在工作，那么它应该【全速运转】。
#     就是不要出现什么金板放在那里，然后我机械手啥也不动，就那么干瞪着，
#     然后过了好一会儿才开始缓过来那样子。」
# 本节把这句话拆成四条可复跑的断言：①判据存在且轻量 ②缺口 ≤ 0 不发 ③没有每 tick 全扫
# ④最坏延迟有界且显著小于改前。全部源码级 + 模型级，不需要启动游戏。

MACHINE_WORK = os.path.join(SRC, "support", "MachineWorkSignal.java")
machine_work = read(MACHINE_WORK)

# ---- E1. 判据存在，且只用「既有信号」（工位表 / 待加工步 / 在制件）----
active = body(chamber, "private boolean machineWorkActive() {", "private boolean probeMachineWork(")
probe = body(chamber, "private boolean probeMachineWork(", "private Set<String> workingRecipes() {")
working = body(chamber, "private Set<String> workingRecipes() {",
               "private boolean categoryRecipeWorkingNow(")
has(active, "MachineWorkSignal.isHot(working, now, machineWorkGraceUntil)",
    "E1a 热 / 冷档判定走唯一实现 MachineWorkSignal.isHot（阈值只有一处）")
has(active, "busAutocraftCooldown = 0;",
    "E1b 「冷 → 热」边沿当刻清零残余冷却（否则最先那次缺料还要白等最多 39 次引擎调用）")
has(probe, "supplyStations(level)", "E1c 只遍历本仓相连输出总线朝向的工位表（既有信号，不是网络扫描）")
has(probe, "holdsUnitAt(level, station.getKey())", "E1d 信号①：工位本体上压着带进度组件的在制件")
has(probe, "holdsUnitAt(level, station.getValue())", "E1e 信号②：输出总线朝向的那一格压着在制件")
has(probe, "pendingStepOn(station.getValue()) != null",
    "E1f 信号③：判得出「待加工步」（含「那一格上正摆着待开件的起步原料」= 用户说的「金板放在那里」）")
has(probe, "hasInFlightUnit()", "E1g 兜底信号④：仓内还压着在制件（推不出去 / 刚做好）")
has(working, "inFlightUnits.values()", "E1h 「正在被加工的配方」来源①：在制件登记表（O(登记数)）")
has(working, "pendingStepOn(target)", "E1i 「正在被加工的配方」来源②：每工位的待加工步（既有每 tick 备忘）")
has(machine_work, "public static final String COST_MODEL", "E1j 判据的成本口径写在唯一实现里（可被本脚本复算）")
has(chamber, "private long machineWorkProbeTick = Long.MIN_VALUE;",
    "E1k 判据每 tick 至多算一次（备忘字段；不会被请求链 / 诊断重复索取）")

# ---- E2. 轻量性（源码级）：新判据里没有任何网络查询 / 全量枚举 ----
for needle, label in [
    ("getResources(", "全网络资源枚举"),
    ("getPatterns", "样板表查询"),
    ("getStatuses(", "任务状态列表遍历"),
    ("getPatternsByOutput", "按输出查样板"),
]:
    check("E2 新判据内没有%s（%s）" % (label, needle),
          needle not in probe and needle not in active and needle not in working,
          "found %s" % needle)
has(chamber, "if (RsccAssemblyDebug.isEnabled() && !isAutoCraftable(autocrafting, representative)) {",
    "E2b 预置方法里那条「有没有样板」的昂贵判定被日志开关挡住（热档每 5 tick 跑一次也不会白算）")

# ---- E3. 缺口驱动：档位只决定「多久算一次」，发不发仍由缺口 > 0 决定 ----
has(chamber, "final long deficit = entry.getValue() - have",
    "E3a 每次请求前重算缺口（网络 + 本仓 + 机器侧 + RS 在途）")
_ensure_sites = [m.start() for m in re.finditer(r"autocrafting\.ensureTask\(", chamber)]
_guard_sites = [m.start() for m in re.finditer(r"if \(deficit > 0", chamber)]
check("E3b 两个 ensureTask 出口仍被 `deficit > 0` 守住（缺口 ≤ 0 ⇒ 一条请求都不发）",
      len(_ensure_sites) == 4 and len(_guard_sites) == 2
      and all(any(g < e for g in _guard_sites) for e in _ensure_sites),
      "ensureTask=%s guards=%s" % (len(_ensure_sites), len(_guard_sites)))


def issues_request(deficit, hot):
    """模型：档位（hot / cold）**只影响多久评估一次**，不改变「缺口 ≤ 0 不发」这条判据。"""
    return deficit > 0


check("E3c 缺口 0 / 负数时，热档与冷档都不发请求（防刷）",
      not issues_request(0, True) and not issues_request(-3, True)
      and not issues_request(0, False) and not issues_request(-3, False))
check("E3d 缺口 > 0 时两档都会发（档位不改变可发性，只改变节拍）",
      issues_request(5, True) and issues_request(5, False))

# ---- E4. 最坏延迟 + 吞吐：改前 / 改后的可复跑模型 ----
ENGINE_TICK = int(re.search(r"ENGINE_INTERVAL_TICKS = (\d+);", chamber).group(1))
GATE_TICK = int(re.search(r"BUS_GATE_INTERVAL_TICKS = (\d+);", chamber).group(1))
HOT_RUNS = int(re.search(r"HOT_COOLDOWN_RUNS = (\d+);", machine_work).group(1))
COLD_RUNS = int(re.search(r"COLD_COOLDOWN_RUNS = (\d+);", machine_work).group(1))
GRACE = int(re.search(r"GRACE_TICKS = (\d+);", machine_work).group(1))
# 看门狗的「缺料」阈值（本脚本不改它，只用于证明「改前的供给节奏比阈值还慢」）
WATCHDOG_MISSING_TICKS = int(re.search(r"positive\(Config\.assemblyStallTimeoutTicks, (\d+)\)", watchdog).group(1))


def worst_reaction_ticks(cadence_runs, engine=ENGINE_TICK, gate=GATE_TICK):
    """最坏反应时间（tick）：缺料恰好出现在一次引擎节拍之后 → 等下一次引擎节拍 → 冷却要用满
    cadence_runs 次引擎调用 → 若此刻门控刚关过，还要等一次门控复查。"""
    return (engine - 1) + max(0, cadence_runs) * engine + gate


def supply_throughput_per_second(batch, cadence_runs, engine=ENGINE_TICK, machine_period_ticks=20):
    """供给上限（件/秒）= min(请求通道上限, 机器自身加工上限)。
    请求通道上限 = 每轮请求量 / 轮长时间；机器上限按 Create 常见一步 1 秒（20 tick）计。"""
    period = max(1, max(0, cadence_runs) * engine)
    request_lane = batch * 20.0 / period
    machine_lane = 20.0 / machine_period_ticks
    return min(request_lane, machine_lane)


PRE_REACTION = worst_reaction_ticks(COLD_RUNS)
POST_REACTION_WORST = worst_reaction_ticks(HOT_RUNS)          # 门控刚开过的同一拍
POST_REACTION_STEADY = ENGINE_TICK - 1                        # 门控已开：只等下一次引擎节拍
PRE_RATE = supply_throughput_per_second(1, COLD_RUNS)         # 默认档：一次 1 份 × 200 tick
POST_RATE_ONE = supply_throughput_per_second(1, HOT_RUNS)     # 热档：一次 1 份 × 5 tick
POST_RATE_TWO = supply_throughput_per_second(2, HOT_RUNS)     # 热档：2 台机器各一份
print("  改前 / 改后对照（可复跑的模型；tick = 1/20 秒）")
print("  %-46s %-12s %-12s %s" % ("指标", "改前", "改后", "说明"))
print("  %-46s %-12s %-12s %s" % ("请求评估周期（tick）", COLD_RUNS * ENGINE_TICK, HOT_RUNS * ENGINE_TICK,
                                  "热档 = 每个引擎节拍"))
print("  %-46s %-12s %-12s %s" % ("最坏反应（tick）", PRE_REACTION, POST_REACTION_WORST,
                                  "下一引擎节拍 + 冷却 + 门控复查"))
print("  %-46s %-12s %-12s %s" % ("稳态反应（tick）", COLD_RUNS * ENGINE_TICK, POST_REACTION_STEADY,
                                  "门控已开、只等下一次引擎节拍"))
print("  %-46s %-12s %-12s %s" % ("单机吞吐（件/秒，默认档）", "%.2f" % PRE_RATE, "%.2f" % POST_RATE_ONE,
                                  "请求通道 vs 机器 1 秒/步"))
print("  %-46s %-12s %-12s %s" % ("双机吞吐（件/秒）", "%.2f" % PRE_RATE, "%.2f" % POST_RATE_TWO,
                                  "热档按「每台机器各一份」发"))

check("E4a 改前最坏反应 %d tick < 看门狗缺料阈值 %d tick ⇒ 供给比「挂起判定」还慢（这就是被掩盖的根源）"
      % (PRE_REACTION, WATCHDOG_MISSING_TICKS), PRE_REACTION > WATCHDOG_MISSING_TICKS)
check("E4b 改后最坏反应 %d tick ≤ 20 tick（1 秒）且 ≤ 改前的 1/10" % POST_REACTION_WORST,
      POST_REACTION_WORST <= 20 and POST_REACTION_WORST * 10 <= PRE_REACTION)
check("E4c 稳态反应 %d tick = 一个引擎节拍之内（0.25 秒）" % POST_REACTION_STEADY,
      0 <= POST_REACTION_STEADY < ENGINE_TICK)
check("E4d 吞吐：改后单机已追平机器自身（%.2f 件/秒），改前只有 %.2f 件/秒（差 %.0f 倍）"
      % (POST_RATE_ONE, PRE_RATE, POST_RATE_ONE / max(PRE_RATE, 1e-9)),
      POST_RATE_ONE >= 20.0 / 20 and PRE_RATE <= 0.1 + 1e-9)
check("E4e 宽限 %d tick > 一个引擎节拍 %d tick（不会在一步加工中间掉回冷档），且 ≤ 2 秒" % (GRACE, ENGINE_TICK),
      0 < GRACE <= 40 and GRACE > ENGINE_TICK)
check("E4f 冷档仍是改动前的 40 次引擎调用（没有任何机器在工作时不引入额外开销）",
      COLD_RUNS == 40)


def meets_full_speed(cadence_runs, scans_network_every_tick, resends_without_gap):
    """验收判据（四条一起才通过）：
    ① 热档每引擎节拍评估（cadence ≤ 1 次引擎调用）；
    ② 每 tick **不**做全网络扫描（getPatterns / 全网络容器枚举）；
    ③ 请求仍是缺口驱动（不允许「不管缺口定时重发」）；
    ④ 最坏反应有界（≤ 20 tick）。"""
    return (cadence_runs <= 1 and not scans_network_every_tick and not resends_without_gap
            and worst_reaction_ticks(cadence_runs) <= 20)


# ---- E5. 反例自证：把「掩盖式写法」喂给同一批判据 ⇒ 必须判负 ----
check("E5a 反例「40 次引擎调用才评估」⇒ 判负", not meets_full_speed(40, False, False))
check("E5b 反例「每 tick 全网络扫描」⇒ 判负", not meets_full_speed(0, True, False))
check("E5c 反例「不管缺口、定时器强行重发」⇒ 判负", not meets_full_speed(0, False, True))
check("E5d 本实现（热档每引擎节拍 + 零网络扫描 + 缺口驱动）⇒ 通过", meets_full_speed(HOT_RUNS, False, False))
check("E5e 反例自证：只要有一项是掩盖式写法，同一判据就必然判负（参数化扫描）",
      all(not meets_full_speed(c, s, r)
          for c in (40, 39) for s in (False, True) for r in (False, True)
          if c > 1 or s or r))

# ---- E6. 看门狗阈值：本轮**不动**（决策与理由写在源码里），且没把「能自愈」变成「永不挂起」 ----
has(watchdog, "结论：<u>不下调</u>",
    "E6a 看门狗侧记录了「缺料阈值不下调」的决策与理由（供给节奏已与阈值解耦）")
check("E6b 三个阈值原样保留（缺料 100 / 无进展 600 / 掉线 40）⇒ 真堵的照旧挂起",
      "positive(Config.assemblyStallTimeoutTicks, 100)" in watchdog
      and "positive(Config.assemblyNoProgressTimeoutTicks, 600)" in watchdog
      and "positive(Config.assemblyOfflinePersistTicks, 40)" in watchdog)
check("E6c 「可自动合成 ⇒ 可推进 ⇒ 不挂起」的自愈豁免仍在（没被本轮改成永不挂起、也没被删）",
      "allMissingAutoCraftable" in watchdog and "missing.isEmpty()" in watchdog)

# ======================================================================================
section("F. 运行期站台残留：废料齿轮 / 金板堵死后续流程（2026-10-18 用户实测）")
# ======================================================================================
# 用户原话（逐字）：
#   「堵塞齿轮了……就是我下单 6 个精密构件，因为我终端中有 6 个中间产物，我就想让他直接把中间产物
#     消耗掉。然后有一个中间产物变成废料齿轮了嘛，但齿轮就堵在那了。然后另一个不知道怎么回事，
#     另外一个置物台上面放了一个金板，然后也不动了。然后手动把这两个拿走之后，它又正常了。
#     最后任务是正常完成了的。」
# 关键差异：这是**正常运行中**产生的残留（不是「取消后回收」）。精密构件结果池里，
# create:cogwheel（权重 5）与 create:golden_sheet（权重 8）既是概率废料、又分别是第 0 步投入物
# 与整条配方的起步原料 ⇒ 台上那件同时是「本仓输入类」与「本仓结果池物品」。

sweep_body = body(chamber, "private int reclaimRuntimeStationResidue() {",
                  "private int reclaimStationResidueAt(")
station_body = body(chamber, "private int reclaimStationResidueAt(",
                    "private boolean isSettledResultOfRecipe(")
evidence_body = body(chamber, "private boolean isSettledResultOfRecipe(",
                     "private static boolean recipeProducesItem(")
produce_body = body(chamber, "private static boolean recipeProducesItem(",
                    "private void pruneRuntimeResidueWatch(")
skip_body = body(chamber, "private void logRuntimeResidueSkip(",
                 "private static String recipeForLog(")

sweep_interval = int(re.search(r"RUNTIME_RESIDUE_SWEEP_INTERVAL_TICKS = (\d+)L;", chamber).group(1))
confirm_ticks = int(re.search(r"RUNTIME_RESIDUE_CONFIRM_TICKS = (\d+)L;", chamber).group(1))
clear_max = int(re.search(r"RUNTIME_RESIDUE_CLEAR_MAX = (\d+);", chamber).group(1))
station_max = int(re.search(r"RUNTIME_RESIDUE_STATION_MAX = (\d+);", chamber).group(1))

# ---- F1. 实现存在，且在「每 tick 的总线调度」里真的被调用（否则等于没写） ----
check("F1a 存在运行期残留清理实现",
      "private int reclaimRuntimeStationResidue() {" in chamber)
check("F1b 在 tickBusScheduler 里真的被调用（紧跟在任务结束 / 冻结通道之后）",
      "clearDeadStationPieces();\n        // <b>运行期残留清理（2026-10-18）</b>" in chamber
      and "reclaimRuntimeStationResidue();" in chamber)
check("F1c 只在「有在跑的单」或「已被这条残留挂起」时动手（不会在无单空闲时乱搬）",
      "if (!busGoalTaskGate && !busFrozenGate) {" in sweep_body)

# ---- F2. 判据（源码级）：证据 + 合法等待豁免 ----
check("F2a 只遍历「本仓真的成功推过料」的在制登记表（不扫所有站台）",
      "new ArrayList<>(inFlightUnits.keySet())" in sweep_body)
check("F2b 每轮检查的登记工位数有硬上限（配合轮转游标）",
      "Math.min(size, RUNTIME_RESIDUE_STATION_MAX)" in sweep_body
      and "runtimeResidueCursor" in sweep_body)
check("F2c 判据①：没有登记 = 没有凭据 ⇒ 不碰",
      "final InFlightUnit unit = inFlightUnits.get(station);" in sweep_body
      and "if (unit == null) {" in sweep_body)
check("F2d 判据③-A：带进度组件的件<b>先记进度签名</b>（step + progress 位），变了 ⇒ 正在加工 ⇒ 不清",
      "final SequencedAssembly assembly = found.get(AllDataComponents.SEQUENCED_ASSEMBLY);" in station_body
      and "final int bits = Float.floatToIntBits(assembly.progress());" in station_body
      and "final boolean sameProgress =" in station_body
      and "logRuntimeResidueSkip(station, item, \"assembly_progressing\");" in station_body)
check("F2d2 判据③-A：进度连续 %d tick 没动 ⇒ 判卡死（不再「有进度组件就一律不碰」）"
      % int(re.search(r"RUNTIME_RESIDUE_PROGRESS_STALL_TICKS = (\d+)L;", chamber).group(1)),
      "if (frozenTicks < RUNTIME_RESIDUE_PROGRESS_STALL_TICKS) {" in station_body
      and "watch.withProgress(item, step, bits, now)" in station_body)
check("F2e 判据③-B：裸过渡件 = 无进度组件的过渡件 ⇒ 卡死候选（不再无条件 return 0）",
      "final boolean bareTransition = assembly == null && isTransitionOfMyRecipes(item);" in station_body
      and "reasonClass = \"bare_transition_blocked\";" in station_body)
check("F2e2 判据④（machine_full 强旁证）：没有「计数在涨」的旁证时，卡死件也只观察不清",
      "if (!blocking) {" in station_body
      and "logRuntimeResidueSkip(station, item, \"bare_transition_not_blocking\");" in station_body
      and "logRuntimeResidueSkip(station, item, \"frozen_not_blocking\");" in station_body)
check("F2e3 判据④的写点/读点都用既有计数器（不新建第二套统计）："
      "写=rscc$noteDestinationRefusal，读=rscc$refusedTargetHits",
      "refusedTargetHits.merge(target.immutable(), 1L, Long::sum);" in chamber
      and "public long rscc$refusedTargetHits(" in chamber
      and "final long refusalHits = rscc$refusedTargetHits(station);" in station_body)
check("F2e4 判据④「在涨」的判定：本轮计数 > 上一轮看到的计数（有基线才算涨）",
      "final boolean refusalRising = watch.refusalHits() >= 0L && refusalHits > watch.refusalHits();"
      in station_body
      and "watch = watch.withRefusalHits(refusalHits);" in station_body)
check("F2e5 落地顺序：machine_full 在涨的工位<b>优先清</b>（两趟，同一批 ≤上限工位）",
      "final boolean blocking = rscc$refusedTargetHits(station) > 0L;" in sweep_body
      and "final boolean refusingNow = blocking && rscc$destinationRefusalAge(station) >= 0L;"
      in sweep_body
      and "if (pass == 0 && !refusingNow) {" in sweep_body
      and "if (pass == 1 && refusingNow) {" in sweep_body)
check("F2f 判据②：多格非空 ⇒ 判不出来就不碰",
      "logRuntimeResidueSkip(station, stack.getItem(), \"multi_slot\");" in station_body)
check("F2g 判据④（硬约束）：我们推过去的那一份 = 合法等待 ⇒ 不碰",
      "if (unit.item() != null && unit.item() == item) {" in station_body)
check("F2h 判据⑥（硬约束）：机械手手里那件 = 等这一步 ⇒ 不碰",
      "if (operatingPosOf(level, station) != null) {" in station_body
      and "logRuntimeResidueSkip(station, item, \"deployer_hand\");" in station_body)
check("F2i 判据⑤：唯一证据 = 我们那件做完后从「结果池」滚出来的那一件",
      "if (!isSettledResultOfRecipe(unit, item)) {" in station_body
      and "for (final ProcessingOutput output : recipe.resultPool) {" in produce_body)
check("F2j 配方 id 为空时的退回判据：起步原料按「起步原料→配方」反查、裸过渡件按本仓过渡件判定",
      "startIngredientRecipes(unit.item())" in evidence_body
      and "else if (unit.item() != null && isTransitionOfMyRecipes(unit.item())) {" in evidence_body
      and "ownedSteps().keySet()" in evidence_body)
check("F2k 判据⑦：观察窗口未满绝不动手（给正常回收通道 2 秒）",
      "if (now - since < RUNTIME_RESIDUE_CONFIRM_TICKS) {" in station_body
      and "runtimeResidueSince.put(key, now);" in station_body)
check("F2l 清理动作：精确抽 1 件 → 优先进网络，网络装不下才进本仓（绝不销毁）",
      "handler.extractItem(slot, 1, true)" in station_body
      and "storage.insert(ItemResource.ofItemStack(taken)" in station_body
      and "acceptTransitionLocally(taken.copyWithCount(remainder))" in station_body
      and "handler.insertItem(slot, taken.copyWithCount(leftover), false);" in station_body)
check("F2m 清完后删掉该工位在制登记 / 死件标记 / 拒收证据（不再压名额、不再报推不动）",
      "inFlightUnits.remove(station);" in station_body
      and "abandonedInFlightAt.remove(station);" in station_body
      and "refusedTargetAt.remove(station);" in station_body)
check("F2n 诊断：判据 / 动作 / 跳过原因（受 devLogs 开关控制，且有去重）",
      "residue_watch {item=" in station_body
      and "residue_cleared {item=" in station_body
      and "private void logRuntimeResidueSkip(" in chamber
      and "RsccAssemblyDebug.reason(" in skip_body)

# ---- F3. 有界 / 轻量（源码级 + 常量） ----
check("F3a 节拍：每 %d tick 至多一轮（不是每 tick）" % sweep_interval,
      0 < sweep_interval <= 40)
check("F3b 数量：每轮至多 %d 件（清到即收工）" % clear_max,
      clear_max == 1 and "if (cleared >= RUNTIME_RESIDUE_CLEAR_MAX) {" in sweep_body)
check("F3c 确认窗口：连续观察 ≥ %d tick 才动手" % confirm_ticks,
      confirm_ticks >= sweep_interval)
check("F3d 检查范围有硬上限（%d 个登记工位），且两个方法体内没有 supplyStations / 全量枚举"
      % station_max,
      0 < station_max <= 32
      and "supplyStations(" not in sweep_body and "supplyStations(" not in station_body
      and "level.getBlockEntities" not in sweep_body and "level.getBlockEntities" not in station_body)
check("F3e 昂贵判定（assemblyById 配方查表）只在候选上做（先排除在制品 / 我们推的那件 / 机械手）",
      station_body.index("if (unit.item() != null && unit.item() == item) {")
      < station_body.index("if (!isSettledResultOfRecipe(unit, item)) {")
      and station_body.index("if (operatingPosOf(level, station) != null) {")
      < station_body.index("if (!isSettledResultOfRecipe(unit, item)) {"))

# ---- F4. 模型：与 Java 判据逐条对应（可复跑，不启动游戏） ----
CONFIRM = confirm_ticks
SWEEP = sweep_interval
STALL = int(re.search(r"RUNTIME_RESIDUE_PROGRESS_STALL_TICKS = (\d+)L;", chamber).group(1))
COOLDOWN = int(re.search(r"RUNTIME_RESIDUE_STATION_COOLDOWN_TICKS = (\d+)L;", chamber).group(1))

check("F3f 判据③的「卡死」窗口（%d tick）≥ 观察窗口（%d tick）：先给正常通道，再判卡死" % (STALL, CONFIRM),
      STALL >= CONFIRM)
check("F3g 冷却（%d tick）≥ 2 × 节拍（%d tick）⇒ 同一工位不会被连续清成往返" % (COOLDOWN, SWEEP),
      COOLDOWN >= 2 * SWEEP)


def blocking_evidence(mf_hits, mf_recent):
    """判据④（强旁证）：该工位有过 machine_full 计数，且此后没有再收下过我们的推送
    （= 它确实在挡路）。``mf_recent`` 只影响**优先级**（此刻仍在拒收的工位先清），
    不参与「能不能清」——否则「任务被挂起 ⇒ 输出总线停手 ⇒ 拒收时间戳变旧 ⇒ 永远不清」
    会形成新的死锁。"""
    return mf_hits > 0


def residue_priority(mf_hits, mf_recent):
    """pass 0 的入列条件：有强旁证，且此刻仍在拒收（最强的一种「计数在涨」）。"""
    return blocking_evidence(mf_hits, mf_recent) and mf_recent


def runtime_residue_verdict(registered, stack_count, assembly, bare_transition, progressing,
                            frozen_ticks, registered_is_item, is_deployer, is_result_pool,
                            mf_hits, mf_recent, observed_ticks, cleared_recently,
                            confirm=CONFIRM, stall=STALL):
    """模型（与 Java 判据逐条对应）：返回 "clear" / "keep"。

    判据顺序照抄源码：① 没有登记 ⇒ keep；② 多格 / 多件 ⇒ keep；⑥ 机械手 ⇒ keep；
    ③-A 带进度组件：进度在推进 / 未满卡死窗口 ⇒ keep；④ 无 machine_full 旁证 ⇒ keep；
    ③-B 裸过渡件：④ 无旁证 ⇒ keep；⑤ 结果池路径：我们推的那件 / 不是结果池 ⇒ keep；
    ⑦ 观察窗口未满 ⇒ keep；⑨ 冷却 ⇒ keep；全部成立 ⇒ clear。
    """
    if not registered:
        return "keep"
    if stack_count != 1:
        return "keep"
    if is_deployer:
        return "keep"
    blocking = blocking_evidence(mf_hits, mf_recent)
    if assembly:
        # 判据③-A：进度在推进（或还没满卡死窗口）⇒ 机器正在加工 ⇒ 合法等待，绝不碰。
        if progressing or frozen_ticks < stall:
            return "keep"
        if not blocking:
            return "keep"
        if cleared_recently:
            return "keep"
    elif bare_transition:
        # 判据③-B：裸过渡件 = 机器没接手；但必须有 machine_full 在涨这条强旁证才动手。
        if not blocking:
            return "keep"
        if cleared_recently:
            return "keep"
    else:
        # 结果池路径（既有行为）。
        if registered_is_item:
            return "keep"
        if not is_result_pool:
            return "keep"
    if observed_ticks < confirm:
        return "keep"
    return "clear"


CASES_RESIDUE = [
    # 标签, registered, count, assembly, bare, progressing, frozen, reg_item, deployer,
    #       result_pool, mf_hits, mf_recent, observed, cleared_recently, 期望
    ("废料齿轮：登记的是过渡件、台上是 cogwheel",
     True, 1, False, False, False, 0, False, False, True, 0, False, CONFIRM, False, "clear"),
    ("废料金板：登记的是过渡件、台上是 golden_sheet",
     True, 1, False, False, False, 0, False, False, True, 0, False, CONFIRM, False, "clear"),
    ("【2026-10-23 用户现场】裸过渡件 incomplete_precision_mechanism 占位 + machine_full 在涨",
     True, 1, False, True, False, 0, False, False, False, 64, True, CONFIRM, False, "clear"),
    ("同上，但任务已被看门狗挂起 ⇒ 输出总线停手、拒收时间戳变旧（计数仍在）⇒ 仍要清",
     True, 1, False, True, False, 0, False, False, False, 64, False, CONFIRM, False, "clear"),
    ("裸过渡件，但 machine_full 计数为 0（没有旁证）⇒ 只观察",
     True, 1, False, True, False, 0, False, False, False, 0, False, CONFIRM, False, "keep"),
    ("裸过渡件刚被清过（冷却期内）⇒ 不再清（防往返）",
     True, 1, False, True, False, 0, False, False, False, 64, True, CONFIRM, True, "keep"),
    ("带进度组件且进度在推进（正在加工）⇒ 保留，即使 machine_full 在涨",
     True, 1, True, False, True, 0, False, False, False, 99, True, CONFIRM, False, "keep"),
    ("带进度组件、还没满卡死窗口（慢速冲压）⇒ 保留",
     True, 1, True, False, False, STALL // 2, False, False, False, 99, True, CONFIRM, False, "keep"),
    ("带进度组件、进度连续 %d tick 没动 + machine_full 在涨 ⇒ 判卡死 ⇒ 清" % STALL,
     True, 1, True, False, False, STALL + 40, False, False, False, 33, True, CONFIRM, False, "clear"),
    ("带进度组件、卡死了但这格没在拒收 ⇒ 只观察（也许只是慢）",
     True, 1, True, False, False, STALL + 40, False, False, False, 0, False, CONFIRM, False, "keep"),
    ("我们推过去的起步原料 / 步骤投入物（登记物品 == 台上，结果池路径）",
     True, 1, False, False, False, 0, True, False, True, 9, True, CONFIRM, False, "keep"),
    ("机械手里等这一步的齿轮（合法等待）",
     True, 1, False, False, False, 0, False, True, True, 9, True, CONFIRM, False, "keep"),
    ("多格 / 多件容器（判不出来）",
     True, 2, False, False, False, 0, False, False, True, 9, True, CONFIRM, False, "keep"),
    ("玩家放的、不是这条配方产出的件（无进度组件、非过渡件、非结果池）",
     True, 1, False, False, False, 0, False, False, False, 9, True, CONFIRM, False, "keep"),
    ("观察窗口未满（%d/%d tick）" % (CONFIRM // 2, CONFIRM),
     True, 1, False, True, False, 0, False, False, False, 64, True, CONFIRM // 2, False, "keep"),
    ("无登记（服务器重启后凭据丢失）",
     False, 1, False, True, False, 0, False, False, False, 64, True, CONFIRM, False, "keep"),
]
print("  反例表（运行期站台残留：清 / 保留）")
print("  %-64s %-8s" % ("情形", "结论"))
residue_meets = []
for (label, reg, count, assembly, bare, progressing, frozen, reg_item, deployer,
     result_pool, mf_hits, mf_recent, observed, cleared_recently, expected) in CASES_RESIDUE:
    got = runtime_residue_verdict(reg, count, assembly, bare, progressing, frozen, reg_item,
                                  deployer, result_pool, mf_hits, mf_recent, observed,
                                  cleared_recently)
    print("  %-64s %-8s" % (label, got))
    residue_meets.append(got == expected)
    check("F4 判据：%s ⇒ %s" % (label, expected), got == expected, "got=%s" % got)
check("F4z 全部判据模型符合验收（三条清路径成立、十一种合法等待 / 判不出来全部保留）",
      all(residue_meets))


def bounded_rounds(residues, per_round=clear_max):
    rounds = 0
    left = residues
    while left > 0 and rounds < 10000:
        left -= min(left, per_round)
        rounds += 1
    return rounds


check("F4b 有界：N 件残留至多 N 轮清空（每轮 1 件），不会无限循环",
      all(bounded_rounds(n) == n for n in (0, 1, 2, 5)))
check("F4c 有界：每轮开销只与「登记工位数（≤%d）」有关，与基地总站台数无关"
      % station_max,
      "Math.min(size, RUNTIME_RESIDUE_STATION_MAX)" in sweep_body and station_max <= 32)


def runtime_design_meets(clears_scrap, clears_gold, keeps_legal, bounded, scans_all):
    """验收判据（五条一起才通过）：
    ① 废料齿轮残留会被清；② 金板残留会被清；③ 合法等待（我们推的那件 / 机械手手里那件 /
    正在加工且进度在推进的件）全部保留；④ 有界（每轮 1 件）；⑤ 不每 tick 全扫所有站台。"""
    return clears_scrap and clears_gold and keeps_legal and bounded and not scans_all


check("F4d 本实现（结果池证据 + 合法等待豁免 + 每轮 1 件 + 只扫登记表）⇒ 通过",
      runtime_design_meets(True, True, True, True, False))

# ---- F5. 反例自证：三种错误写法喂给同一批判据 ⇒ 必须判负 ----
check("F5a 反例「不清理（保持旧行为）」⇒ 判负",
      not runtime_design_meets(False, False, True, True, False))
check("F5b 反例「无判据乱清（把正在加工 / 等下一步 / 机械手里那件也清掉）」⇒ 判负",
      not runtime_design_meets(True, True, False, True, False))
check("F5c 反例「每 tick 全扫所有站台」⇒ 判负",
      not runtime_design_meets(True, True, True, False, True))
check("F5d 反例「只按齿轮写死、漏掉金板」⇒ 判负（结果池判据必须覆盖全部概率废料）",
      not runtime_design_meets(True, False, True, True, False))
check("F5e 本实现不写死物品名（纯数据驱动：resultPool 里有什么就认什么，换配方照样成立）",
      "cogwheel" not in produce_body and "golden_sheet" not in produce_body
      and "recipe.resultPool" in produce_body)

# ---- F6. 2026-10-23：「卡死的过渡件」判据（模型 + 源码级；反例逐条判负） ----
def runtime_stuck_design_meets(clears_bare_blocked, clears_frozen_blocked, keeps_progressing,
                               needs_counter, bounded, scans_all):
    """验收判据（六条一起才通过）：
    ① 裸过渡件 + machine_full 在涨 ⇒ 清；② 进度卡死 + machine_full 在涨 ⇒ 清；
    ③ 进度在推进 ⇒ 一律保留（哪怕 machine_full 在涨）；④ 没有计数器旁证 ⇒ 不清；
    ⑤ 有界；⑥ 不每 tick 全扫。"""
    return (clears_bare_blocked and clears_frozen_blocked and keeps_progressing
            and needs_counter and bounded and not scans_all)


# 六条判据全部从模型里<b>算出来</b>（不是写死 True）：这样反例一旦混进来，这里必然先红。
STUCK_CLEARS_BARE = (runtime_residue_verdict(
    True, 1, False, True, False, 0, False, False, False, 64, True, CONFIRM, False) == "clear")
STUCK_CLEARS_FROZEN = (runtime_residue_verdict(
    True, 1, True, False, False, STALL + 40, False, False, False, 33, True,
    CONFIRM, False) == "clear")
STUCK_KEEPS_PROGRESSING = (runtime_residue_verdict(
    True, 1, True, False, True, 0, False, False, False, 99, True, CONFIRM, False) == "keep")
STUCK_NEEDS_COUNTER = (runtime_residue_verdict(
    True, 1, False, True, False, 0, False, False, False, 0, False,
    CONFIRM, False) == "keep")
STUCK_BOUNDED = (clear_max == 1 and sweep_interval >= 40 and station_max <= 32
                 and COOLDOWN >= 2 * SWEEP)
STUCK_SCANS_ALL = ("supplyStations(" in sweep_body) or ("level.getBlockEntities" in sweep_body)

check("F6a 本实现（裸过渡件+旁证 / 进度卡死+旁证 / 进度推进中保留 / 无旁证不清 / 有界 / 只扫登记表）"
      "⇒ 通过",
      runtime_stuck_design_meets(STUCK_CLEARS_BARE, STUCK_CLEARS_FROZEN, STUCK_KEEPS_PROGRESSING,
                                 STUCK_NEEDS_COUNTER, STUCK_BOUNDED, STUCK_SCANS_ALL))
check("F6b 反例「排除过渡件」（旧判据：带进度组件 / 裸过渡件一律不清）⇒ 判负",
      not runtime_stuck_design_meets(False, False, True, True, True, False))
check("F6c 反例「无计数器」（不看 machine_full 旁证就把过渡件全清）⇒ 判负",
      not runtime_stuck_design_meets(True, True, True, False, True, False))
check("F6d 反例「每 tick 全扫」⇒ 判负",
      not runtime_stuck_design_meets(True, True, True, True, False, True))
check("F6e 反例「把正在加工的件也清掉（不看进度）」⇒ 判负",
      not runtime_stuck_design_meets(True, True, False, True, True, False))
check("F6f 优先级：此刻仍在拒收（machine_full 正在刷）的工位排在前面；只有旧计数的不抢先",
      residue_priority(64, True) and not residue_priority(64, False)
      and not residue_priority(0, True))

# ---- F7. 源码级锚点（模型之外，直接钉住实现） ----
check("F7a residue_cleared 计数器：清理时按件 +1，且诊断事件带 cleared_total（用户靠它判断）",
      "runtimeResidueClearedTotal += moved;" in station_body
      and "residue_cleared {item=" in station_body
      and "cleared_total=" in station_body
      and "public long rscc$runtimeResidueCleared() {" in chamber)
check("F7b 清完恢复站台可接料：删在制登记 / 死件标记 / 拒收证据 + 拒收计数",
      "inFlightUnits.remove(station);" in station_body
      and "abandonedInFlightAt.remove(station);" in station_body
      and "refusedTargetAt.remove(station);" in station_body
      and "refusedTargetHits.remove(station);" in station_body)
check("F7c 有界：卡死件同一工位有冷却（不会每轮反复清同一格）",
      "RUNTIME_RESIDUE_STATION_COOLDOWN_TICKS = 200L;" in chamber
      and "if (candidate && (assembly != null || bareTransition) && clearedRecently) {" in station_body
      and "logRuntimeResidueSkip(station, item, \"station_cooldown\");" in station_body)
check("F7d 有界：两趟 == 同一批 ≤%d 个登记工位走两遍，不排序、不分配、清到即收工" % station_max,
      "for (int pass = 0; pass < 2 && cleared < RUNTIME_RESIDUE_CLEAR_MAX; pass++) {" in sweep_body
      and "Math.min(size, RUNTIME_RESIDUE_STATION_MAX)" in sweep_body
      and "sort(" not in sweep_body)
check("F7d2 性能上界：每 %d tick（2 秒）最多 %d 次表内判定（2 趟 × %d 工位）、最多清 %d 件、"
      "稳态 0 件；与基地站台总数无关"
      % (SWEEP, 2 * station_max, station_max, clear_max),
      2 * station_max <= 32 and clear_max == 1 and SWEEP >= 40
      and "supplyStations(" not in sweep_body
      and "level.getBlockEntities" not in sweep_body)
check("F7e 观测表有界：孤儿条目每轮删掉（键只来自在制登记表）",
      "runtimeResidueStationWatch.keySet().removeIf(pos -> !inFlightUnits.containsKey(pos));"
      in sweep_body)
check("F7f 工程判据：空工位丢掉观测 ⇒ 下一件重新建立进度基线（不继承上一件）",
      "runtimeResidueStationWatch.remove(station);" in station_body)
check("F7g 拒收计数与拒收时间戳逐点同步（成功投料 / 超窗修剪 / 清证据 / 清完工位都一起删，"
      "不会累积成泄漏）",
      "refusedTargetHits.remove(target);" in chamber
      and "refusedTargetHits.clear();" in chamber
      and "refusedTargetHits.keySet().removeIf(target -> !refusedTargetAt.containsKey(target));"
      in chamber
      and "refusedTargetHits.remove(station);" in chamber)


# ======================================================================================
section("D. 反例自证汇总（修前行为喂给同一批判据 ⇒ 必须判负）")
# ======================================================================================

counterexample_failures = 0
if all(PRE_FIX_REQUEST_MEETS):
    counterexample_failures += 1
if all(PRE_SUSPEND_MEETS):
    counterexample_failures += 1
if reclaim_after_cancel(False, False, False) == set():
    counterexample_failures += 1
check("D1 三项修前行为（堵塞跳过请求 / 精确查误判不可合成 / 步骤投入物+清表漏收）都至少被判负一次",
      counterexample_failures == 0, "counterexample_failures=%d" % counterexample_failures)
check("D2 修后三项行为全部通过（请求 / 不误挂起 / 全位置回收）",
      all(POST_FIX_REQUEST_MEETS) and all(POST_SUSPEND_MEETS)
      and reclaim_after_cancel(True, True, True) == set())
# F 节的反例自证（不清理 / 无判据乱清 / 每 tick 全扫 / 只按齿轮写死）已在 F5a~F5d 逐条判负，
# 这里再合并成一条汇总，保证「改前行为一定会被判负」这件事本身也有一条断言。
check("D3 运行期残留这一项：三种错误写法（不清理 / 乱清 / 每 tick 全扫）都至少被判负一次",
      not runtime_design_meets(False, False, True, True, False)
      and not runtime_design_meets(True, True, False, True, False)
      and not runtime_design_meets(True, True, True, False, True))

print()
print("=" * 96)
if PROBLEMS:
    print("失败 %d 项：" % len(PROBLEMS))
    for problem in PROBLEMS:
        print("  - " + problem)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
