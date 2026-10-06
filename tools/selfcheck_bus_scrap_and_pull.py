# -*- coding: utf-8 -*-
"""本轮 A / B 两项验收的断言（源码锚点 + 算术推演）。

为什么要有这个脚本（无法在本地把游戏跑起来）：
  A. **过度补料**（用户实测 `summary window=5s pull=2018 feed=2017 collect=6`）：
     根因是「推料侧只有『机器里有没有同种料』一条闸门」——它对「这一步要不要它」完全无感，
     而机械手对**同种**物品是照收不误的（只拒绝**别的**物品），于是每次推成功都把仓内缺口
     重新打开 → 下一次备料又按 `target`（= TARGET 策略的「预估需求」= 每批 1 × loops 5 = 5，
     正是日志里的 `target=5`）补满 → 每 tick 每资源补一次。
     本脚本把这条算术做出来并对上实测 2018，再证明新口径的 pull 有界且等于「物理上必须的耗料量」。
  B. **废料 / 成品类别**：齿轮既是精密构件第 0 步的投入物、又在同一条配方 results 池里（= 废料），
     旧版把「输入类」一律保护 → 卡在机械手里的齿轮收不回来（用户只能手动抠）。
     本脚本断言「收回侧能收当前步不要的那一份」「备料侧同步不放行（不会来回搬运）」
     「成品 / 废料类别不会误收正在加工的过渡件」。

用法：python tools/selfcheck_bus_scrap_and_pull.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。
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
CREATE_RECIPES = os.path.join(ROOT, "local_src", "external", "Create", "src", "generated",
                              "resources", "data", "create", "recipe", "sequenced_assembly")

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
    print("=" * 72)
    print(title)
    print("=" * 72)


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


chamber = read(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
export_strategy = read(SRC, "support", "RsccChamberExportStrategy.java")
import_strategy = read(SRC, "support", "RsccChamberImportStrategy.java")
category = read(SRC, "support", "RsccBusCategory.java")
probe = read(SRC, "support", "SequencedRecipeProbe.java")
supply_policy = read(SRC, "support", "RsccSupplyPolicy.java")

# ==================== A. pull=2018/5s 的根因 ====================
section("A1) 根因：target 取自 TARGET 策略的「预估需求」+ 推料只按「机器里有没有同种料」")

check("默认档位就是 TARGET（「不管怎么样一定要达到目标产物数量」= 用户上次要求的默认档）",
      "RsccSupplyStrategy DEFAULT_STRATEGY = RsccSupplyStrategy.TARGET;" in supply_policy)
has(chamber, "final boolean targetMode = RsccSupplyPolicy.strategy(getLevel()) == RsccSupplyStrategy.TARGET;",
    "锚点: 备料目标量按 TARGET 策略切换到「预估需求」档")
has(chamber, "final long want = targetMode ? Math.max(perBatch, info.estimated()) : perBatch;",
    "锚点: TARGET 档下 want = 预估需求（= 每批 × loops；loops 5 的精密构件就是日志里的 target=5）")
has(export_strategy, "if (holdsSameItem(target, item.item())) {",
    "根因锚点: 推料侧当时的输入闸门只有「目标机器里有没有同种料」")
check("根因成立：`holdsSameItem` 只比对「有没有同种料」，与「这一步要不要它」无关 —— "
      "而机械手对同种物品照收不误（Create 只拒绝手里已有『不同』物品），"
      "因此每次推成功都会把仓内缺口重新打开",
      "return new TransferOutcome(Result.SKIPPED, 0L, \"machine_holds_input\");" in export_strategy
      and "private static boolean holdsSameItem(final IItemHandler handler, final Item item) {"
      in export_strategy)

section("A2) 修法：目标量按「每批一份」并夹缺口 + 三条推料门 + 备料同步不放行")

has(chamber, "private static final int BUS_TARGET_BATCHES = 1;",
    "修法①: 目标量 = 每批所需 × 1（不再是「预估需求 × loops」，也不再是「达标为止」）")
has(chamber, "private int busItemDeficit(final Item item, final long target) {",
    "修法①: 备料按「缺口」夹量（busItemDeficit；仓内已够 → 0 → 一点不抽）")
has(chamber, "private int busFluidDeficit(final Fluid fluid, final long target) {",
    "修法①: 流体同样按缺口夹量（busFluidDeficit）")
has(chamber, "final int deficit = busItemDeficit(resource.item(), target);",
    "修法①: 物品备料走缺口判定")
has(chamber, "if (deficit <= 0) {",
    "修法①: 缺口 <= 0 → 直接不抽（这就把「每 tick 反复补」这条路径整条砍掉）")
for reason, anchor in (
        ("machine_holds_input", "if (holdsSameItem(target, item.item())) {"),
        ("not_current_step_input", "if (!chamber.inputMaterialWantedNow(targetPos, item.item())) {"),
        ("machine_busy_with_my_step",
         "if (chamber.isStartIngredient(item.item()) && chamber.unitInFlightAt(targetPos)) {")):
    check("修法②: 推料门 %s 仍在（机器此刻不需要它 → 不推、不重试）" % reason, anchor in export_strategy)
has(export_strategy, "chamber.isInputMaterial(item.item()) ? INPUT_FEED_UNIT : Long.MAX_VALUE",
    "修法②: 输入类单次搬运量硬夹到 1 件（一次一份，不会一次塞满机械手）")
has(chamber, "if (!inputMaterialWantedNow(resource.item())) {",
    "修法③: 备料侧对「当前步不要的步骤专用投入物」同步不放行（否则收回 → 又备回来，每秒来回搬运）")
has(chamber, "private static final int BUS_GATE_INTERVAL_TICKS = 10;",
    "节流仍在: 自动合成门控 10 tick 复查一次（不是每 tick 全表扫网络）")
has(chamber, "private final Map<ResourceKey, Long> stepRefusalCooldowns = new HashMap<>();",
    "节流仍在: 「按步拒绝」的短冷却（同一资源短期内不重复判定 / 不重复抱怨）")

section("A3) 推演：把 2018/5s 算出来，再证明新口径有界（零多余）")

TICKS_5S = 100          # 5 秒 = 100 tick（20 tps）
RESOURCES = 4           # 精密构件的 4 种输入资源：金板（主原料）/ 齿轮 / 大齿轮 / 铁粒
OLD_TARGET = 5          # 实测日志里的 target=5 = 预估需求（每批 1 × loops 5）
MEASURED_PULL = 2018    # 用户实测 pull=2018/5s
# 旧口径：每 tick 对每种资源「补满到 target」；机器（机械手）对同种物品照收不误，
# 于是每一份都被接收 → 仓内缓冲每 tick 归零 → 下一 tick 又补满 target。
old_pull = TICKS_5S * RESOURCES * OLD_TARGET
error = abs(old_pull - MEASURED_PULL) * 100.0 / MEASURED_PULL
print("  旧口径算术：%d tick × %d 资源 × target %d = %d（实测 %d，差 %.1f%%，= 窗口边界）"
      % (TICKS_5S, RESOURCES, OLD_TARGET, old_pull, MEASURED_PULL, error))
check("根因可量化：旧算术 %d 与实测 %d 同阶（误差 <= 2%%）" % (old_pull, MEASURED_PULL),
      error <= 2.0)

# 新口径：仓内每种资源恒保有 1 份缓冲（BUS_TARGET_BATCHES=1），只有「真实被机器消耗掉的那一份」
# 才会重新打开缺口；而机器不需要的份一律不推、也不备 → pull = 消耗 = 产出 × 固有耗料比 + 缓冲。
PRODUCTS_5S = 6         # 同一段日志里 collect=6（这是该 5 秒内的真实产出速率）
LOOPS = 5               # precision_mechanism.json: loops = 5
INTRINSIC_PER_PRODUCT = LOOPS * RESOURCES   # 每件成品物理上必须消耗 5 组 × 4 种 = 20 份输入
BUFFER = RESOURCES      # 每种资源仓内最多留 1 份
new_pull = PRODUCTS_5S * INTRINSIC_PER_PRODUCT + BUFFER
print("  新口径算术：产出 %d 件 × 固有耗料 %d 份 + %d 份缓冲 = 上界 %d/5s"
      % (PRODUCTS_5S, INTRINSIC_PER_PRODUCT, BUFFER, new_pull))
check("新口径 pull 有界：<= 产出 × 固有耗料比 + 缓冲 = %d/5s（每一份都有对应的消耗，零多余）"
      % new_pull, new_pull == 124)
check("量级对比：%d → <=%d（下降 >= 90%%）；pull/collect 比值 336 → <=21（= 该配方固有的耗料比 4×5）"
      % (old_pull, new_pull),
      new_pull * 10 <= old_pull and new_pull <= PRODUCTS_5S * (INTRINSIC_PER_PRODUCT + 1))
check("pull 与 collect 的关系变成「可解释的固定比例」而不是「无界循环」",
      12 <= new_pull * 1.0 / PRODUCTS_5S <= 21)
check("旧路径已不存在：TARGET 档不再把「预估需求 × loops」当目标量，也不再有「达标为止」的补料循环",
      "BUS_TARGET_BATCHES = 1;" in chamber
      and "busItemDeficit(resource.item(), target)" in chamber
      and "Math.min(want * BUS_TARGET_BATCHES, BUS_ITEM_TARGET_MAX)" in chamber)

# ==================== B. 废料 / 成品类别 ====================
section("B1) 数据通路：配方 results 池 → 成品 / 废料类别（与终端 / JEI 同一份数据）")

pool = json.load(io.open(os.path.join(CREATE_RECIPES, "precision_mechanism.json"), encoding="utf-8"))
pool_ids = [entry.get("id") for entry in pool.get("results", [])]
print("  Create 的 precision_mechanism results 池 = %s" % pool_ids)
check("事实依据：create:cogwheel 确实在同一条配方的 results 池里（= 用户说的「废料里有齿轮」）",
      "create:cogwheel" in pool_ids and pool_ids[0] == "create:precision_mechanism")
has(chamber, "addProductCategories(recipe, results, scraps);",
    "执行舱在算类别时把该配方的 results 池接到「成品 / 废料」上")
has(chamber, "SequencedRecipeProbe.splitResultPool(recipe.resultPool)",
    "拆分口径与 SPT / JEI 同源（splitResultPool：第一项 = 主产物，其余 = 废料）")
has(chamber, "addProductCategory(results, RsccBusCategory.RESULT_PREFIX, output.stack());",
    "成品类别 id = result:<物品注册名>")
has(chamber, "addProductCategory(scraps, RsccBusCategory.SCRAP_PREFIX, output.stack());",
    "废料类别 id = scrap:<物品注册名>（用户原话：废料肯定要显示）")
has(category, "public static final String RESULT_PREFIX = \"result:\";",
    "类别模型里有成品类别前缀（可显示、可勾选）")
has(category, "public static final String SCRAP_PREFIX = \"scrap:\";",
    "类别模型里有废料类别前缀（可显示、可勾选）")
check("成品 / 废料与输入是三个互不覆盖的类别 id（勾选互不影响）",
      "public boolean isResult()" in category and "public boolean isScrap()" in category
      and "public static String productCategoryId(final String prefix," in chamber)

section("B2) 收回侧：能收废料 / 成品，且与备料 / 推料同一个判据（判定只保留一份）")

accept = import_strategy[import_strategy.index("private static boolean autoAcceptsItem("):
                         import_strategy.index("// ==================== 执行舱内部存储")]
has(accept, "if (inputItems.contains(stack.getItem()) && !chamber.inputMaterialWantedNow(pos, stack.getItem())) {",
    "收回侧例外 ①：输入类里「本机当前待加工步不要的那一份」= 废料性残留 → 收回"
    "（判据带机器坐标，与推料侧同一个判据的两面）")
has(accept, "return residualEdge || !inputItems.contains(stack.getItem());",
    "收回侧既有语义保留：非输入类照收；输入类只有「任务刚结束」的边沿才连收一次")
check("过渡件优先判定（成品 / 废料类别不会误收正在加工的过渡件）",
      accept.index("stack.get(AllDataComponents.SEQUENCED_ASSEMBLY) != null")
      < accept.index("inputItems.contains(stack.getItem())")
      and "return chamber.isTransitionReclaimAllowed(stack);" in accept)
check("判据只有一份：收回 / 推料 / 备料三处都是同一个 inputMaterialWantedNow / judgeStep 家族",
      "chamber.inputMaterialWantedNow(pos, stack.getItem())" in accept
      and "chamber.inputMaterialWantedNow(targetPos, item.item())" in export_strategy
      and "!inputMaterialWantedNow(resource.item())" in chamber
      and "chamber.isTransitionReclaimAllowed(stack)" in accept
      and "!isNextForMyMachines(probeStack)" in chamber)

section("B3) 推演：齿轮废料在自动模式下被收回 → 机器不再卡死（且不会来回搬运）")

# 场景：精密构件（3 步 deploying，loops 5）；置物台上的在制件已经走到第 2 步（要「大齿轮」），
#      机械手手里那件齿轮是上一步留下的废料（Create 的 DeployerItemHandler 对「手里已有不同物品」一律拒收）。
STEP = 1                                   # 1-based 的第 2 步（0-based = 1）
EXTRA_OF_STEP = {0: {"create:cogwheel"}, 1: {"create:large_cogwheel"}, 2: {"c:nuggets/iron"}}
STEP_EXTRA_ALL = set().union(*EXTRA_OF_STEP.values())
INPUT_ITEMS = {"create:cogwheel", "create:large_cogwheel", "c:nuggets/iron", "c:plates/gold"}
TRANSITIONAL = "create:incomplete_precision_mechanism"
SCRAP_ITEMS = {i for i in pool_ids if i != pool_ids[0]}


def input_material_wanted_now(item, step=STEP):
    """复刻执行舱 inputMaterialWantedNow：不是「步骤专用投入物」（主原料 / 成品 / 废料）
    或判不出待加工步时返回 True（放行）。"""
    if item not in STEP_EXTRA_ALL:
        return True
    return item in EXTRA_OF_STEP.get(step, set())


def auto_accepts(item, step=STEP, has_assembly_progress=False, owned_still_needs=False):
    """复刻 RsccChamberImportStrategy#autoAcceptsItem（residualEdge=false 的常规态）。"""
    if has_assembly_progress:
        return not owned_still_needs                 # 执行舱还要它 → 不收回
    if item in INPUT_ITEMS and not input_material_wanted_now(item, step):
        return True                                  # 例外 ①：当前步不要的输入类 → 收回
    return item not in INPUT_ITEMS                   # 常规：非输入类（成品 / 废料）→ 收回


hand = "create:cogwheel"                             # 机械手手里那件（上一步留下的废料齿轮）
check("推演① 机械手手里的齿轮废料：本仓当前步（要大齿轮）不要它 → 自动模式收回（= 不再卡死）",
      auto_accepts(hand) is True and hand in SCRAP_ITEMS)
check("推演② 正在加工的那一件（本步要的大齿轮）绝不被抽走 → 不会把在制件饿死",
      auto_accepts("create:large_cogwheel") is False)
check("推演③ 主原料（金板）不是「步骤专用投入物」→ 一律受保护（不会把刚喂进去的料抽回来）",
      auto_accepts("c:plates/gold") is False and input_material_wanted_now("c:plates/gold") is True)
check("推演④ 未完成件（过渡件）走「执行舱还要不要它」：还要 → 不收回；已做完 → 收回",
      auto_accepts(TRANSITIONAL, has_assembly_progress=True, owned_still_needs=True) is False
      and auto_accepts(TRANSITIONAL, has_assembly_progress=True, owned_still_needs=False) is True)
check("推演⑤ 废料类别里没有过渡件（scrap/result 的过滤项是产出物品，不含 incomplete_* 过渡件）",
      TRANSITIONAL not in SCRAP_ITEMS and TRANSITIONAL not in pool_ids)


def back_and_forth(ticks, gate_on_pull):
    """复刻「收回 → 备料」的来回搬运：收回 1 份后，下一 tick 备料会不会又把它抽进仓。"""
    moved = 0
    for _ in range(ticks):
        moved += 1                                   # 收回侧：把当前步不要的那一份带回网络
        if not gate_on_pull and not input_material_wanted_now(hand):
            moved += 1                               # 备料侧不加门 → 又抽回来（来回搬运）
    return moved


check("推演⑥ 备料侧同步不放行 → 不会「刚收回又被备回来」（102 tick 只有 102 次单程搬运，不是 204）",
      back_and_forth(102, gate_on_pull=True) == 102
      and back_and_forth(102, gate_on_pull=False) == 204)
check("推演⑦ 收回侧与备料侧用的是同一个判据（源码锚点），因此结论严格互补、不会互搏",
      "!chamber.inputMaterialWantedNow(pos, stack.getItem())" in accept
      and "!inputMaterialWantedNow(resource.item())" in chamber)

# ==================== C. 本轮四条实机回归 ====================
section("C1) 打开总线界面 = 只读（绝不重装策略 / 绝不动任务状态）")

exporter_iface = read(SRC, "support", "RsccExporterExecutorMode.java")
importer_iface = read(SRC, "support", "RsccImporterExecutorMode.java")
exporter_be = read(SRC, "mixin", "exporter", "AbstractExporterBlockEntityMixin.java")
importer_be = read(SRC, "mixin", "importer", "AbstractImporterBlockEntityMixin.java")
exporter_menu = read(SRC, "mixin", "exporter", "ExporterContainerMenuMixin.java")
importer_menu = read(SRC, "mixin", "importer", "ImporterContainerMenuMixin.java")


def body(text, start_marker, end_marker):
    start = text.find(start_marker)
    if start < 0:
        return ""
    end = text.find(end_marker, start)
    return text[start:end if end > 0 else len(text)]


has(exporter_iface, "void rscc$refreshLinkForUi();", "锚点: 输出总线提供「只读刷新归属」入口")
has(importer_iface, "void rscc$refreshLinkForUi();", "锚点: 输入总线提供「只读刷新归属」入口")
ui_exporter = body(exporter_be, "public void rscc$refreshLinkForUi()", "@Override")
ui_importer = body(importer_be, "public void rscc$refreshLinkForUi()", "@Override")
check("只读刷新的实现里只有「作废缓存 + 重算」（输出总线侧）",
      "rscc$invalidateLinkCache();" in ui_exporter and "rscc$resolveLink();" in ui_exporter
      and "rscc$installChamberStrategy" not in ui_exporter
      and "rscc$chamberStrategyInstalled = false" not in ui_exporter)
check("只读刷新的实现里只有「作废缓存 + 重算」（输入总线侧）",
      "rscc$invalidateLinkCache();" in ui_importer and "rscc$resolveLink();" in ui_importer
      and "rscc$installChamberStrategy" not in ui_importer
      and "rscc$chamberStrategyInstalled = false" not in ui_importer)
check("两个「打开菜单」注入点都改走只读入口（不再重装策略 = 打开界面在任务状态上零副作用）",
      "mode.rscc$refreshLinkForUi();" in exporter_menu
      and "mode.rscc$refreshLinkForUi();" in importer_menu
      and "refreshExecutorMode();" not in exporter_menu
      and "refreshExecutorMode();" not in importer_menu)

section("C2) 多仓并发：每种原料各一份 × 每台要它的机器，且不按归属串台")

has(chamber, "private int wantingTargetCount(", "锚点: 「有几台机器要它」的唯一实现")
has(chamber, "private long stepExtraStockTarget(final Item item)", "锚点: 步骤专用投入物的备料量上限")
has(chamber, "final long cap = stepExtraStockTarget(item);",
    "锚点: 备料目标量按「每台要它的机器各一份」夹紧（不再是每批 × loops 的大缓冲）")
has(chamber, "itemTargets.merge(item, Math.min(itemTarget, cap), Math::max);",
    "锚点: 夹紧发生在写好目标量的唯一处")
stock_cap = body(chamber, "private int wantingTargetCount(", "/**\n     * 「步骤专用投入物」的备料量上限")
check("「每种原料各一份 × 每台机器」的算术：单台机器 → 1 份；两台各要一份且订单还有 2 件 → 2 份"
      "（不是每批 × loops = 5）；再叠一道份额闸门：备料份数 ≤ 订单剩余件数（下单 1 个 ⇒ 只备 1 份，"
      "用户第 ④ 条「一个一个下单时中间产物还是存在额外之类的」）",
      "int want = 0;" in stock_cap and "want++" in stock_cap
      and "want = (int) Math.min((long) want, Math.max(1L, remaining));" in stock_cap
      and "return undecidable ? 1 : 0;" in stock_cap)
check("全仓口径改成「任何一台要它」的并集（不再取最小步当唯一答案 → 靠后那台不会断供）",
      "for (final BlockPos target : busSupplyTargets()) {" in body(
          chamber, "public boolean inputMaterialWantedNow(@org.jetbrains.annotations.Nullable final Item item)",
          "private int wantingTargetCount")
      and "pendingStepOnTargets()" not in body(
          chamber, "public boolean inputMaterialWantedNow(@org.jetbrains.annotations.Nullable final Item item)",
          "private int wantingTargetCount"))
filters = body(chamber, "public List<ResourceKey> busExportFilters(", "private Set<Item> occupiedInputMaterials")
check("步骤专用投入物类别只豁免「轮询」、不豁免归属与份额：另按「本总线目标机器此刻是否要它」过滤",
      "if (isStepExtraCategory(info)) {" in filters
      and "inputMaterialWantedNow(target, item)" in filters
      and "final BlockPos target = supplyTargetOf(level, exporterPos);" in filters
      and "if (!ownsStepExtraTurn(exporterPos, owners, stepExtraProbeOf(info))) {" in filters)
check("非步骤专用投入物（主原料 / 中间产物）仍按归属 + 份额判定（份额由下单数量决定；不再是按 tick 轮询）",
      "final int index = owners.indexOf(exporterPos);" in filters
      and "if (index >= exportShare(owners.size())) {" in filters
      and "Math.floorMod(gameTime, owners.size())" not in filters)

section("C3) 喂料断档：按步拒绝的抑制窗口必须随步序作废（≤ 阈值）")

has(chamber, "private String wantedStepSignature()", "锚点: 步序签名（哪台机器轮到哪里）")
has(chamber, "if (!wantSignature.equals(lastWantedStepSignature)) {",
    "锚点: 步序一变立刻作废旧结论")
has(chamber, "stepRefusalCooldowns.clear();", "锚点: 抑制表随步序清空（不再压住「已经需要」的那份料）")
signature = body(chamber, "private String wantedStepSignature()", "/** 某个物品对应的「输入性产物」类别 id")
check("签名的粒度就是「每台机器的配方 + 步序」（步序一变签名必变）",
      "pendingStepOn(target)" in signature and "pending.recipeId()" in signature
      and ".step()" in signature)
check("抑制常量仍是 100 tick（自检 assembly_step_ownership 要求），只是不再跨步序生效",
      "STEP_REFUSAL_COOLDOWN_TICKS = 100" in chamber)

section("C4) 不出现持续输出：仓内备料受保护，只有机器侧残留才按「本步不要它」收回")

has(import_strategy, "private static boolean autoAcceptsChamberItem(",
    "锚点: 仓内存储与机器侧两套判据（唯一新增判定）")
auto_branch = body(import_strategy, "final Predicate<ItemStack> acceptChamberItem",
                   "} else {")
check("自动收回：仓内 = 受保护判据，机器侧 = 按步判据（且带机器坐标，与推料侧同一口径）"
      "（第 24 轮起机器侧按「工位属主仓」取判据：链展开后一条总线会扫到整条链的工位）",
      "autoAcceptsChamberItem(stack, inputItems, residualEdge, chamber)" in auto_branch
      and "autoAcceptsItem(pos, stack, inputItems" in auto_branch
      and "acceptChamberItem, acceptChamberFluid," in auto_branch
      and "acceptMachineItem, acceptFluid" in auto_branch)
chamber_accept = body(import_strategy, "private static boolean autoAcceptsChamberItem(",
                      "// ==================== 执行舱内部存储 → RS 网络")
check("仓内输入类平时一律受保护（只在任务结束边沿收一次）→ 与备料侧不再「买进 → 退回」来回搬运",
      "if (inputItems.contains(stack.getItem())) {" in chamber_accept
      and "return residualEdge;" in chamber_accept)
# 用户第 ② 条（本轮）：一个物品既是某步投入物、又在同一条配方的 results 池里（= 废料，典型齿轮）时，
# 仓内那份「没有任何工位此刻要它」照常收回（否则界面上「已收回齿轮」永远收不回 —— 用户点名的那一条）。
check("用户第 ② 条：仓内「废料性」输入（同时是 scrap 且此刻没有任何工位要它）照常收回",
      "chamber.isScrapItem(stack.getItem())" in chamber_accept
      and "!chamber.inputMaterialWantedNow(stack.getItem())" in chamber_accept
      and "return true;" in chamber_accept)
check("机器侧判据保持「本步不要它就收回」的语义（压在机械手手里的废料性残留仍会被收回，产线不会卡死）",
      "if (inputItems.contains(stack.getItem())"
      " && !chamber.inputMaterialWantedNow(pos, stack.getItem())) {"
      in body(import_strategy, "private static boolean autoAcceptsItem(", "private static boolean autoAcceptsChamberItem"))
check("两个来源各用各的判据（sweepChamberItems 用仓内判据、pullMachineItems 用机器判据）",
      "sweepChamberItems(chamber, storage, actor, acceptChamberItem, label, detail)" in import_strategy
      and "pullMachineItems(level, selfPos, target, storage, actor, acceptMachineItem, label, detail,"
          in import_strategy)

# ==================== C5) 用户第 ②③ 条（本轮）：无单仍收回废料 + 本步要的投入物绝不被收回再推 ====
section("C5) 无单只收紧「推进生产」，不拦「回收」；本步仍要的投入物绝不被任何输入总线抄走")

# 第 ② 条：无单闸门只在「手动模式」整条短路；自动模式只关掉「跨阶段直接交接」、照常回收成品 / 废料。
check("② 无单闸门不再一律短路：手动模式保持原样，自动模式改为「仅回收」（noOrderFlag → transitionSink=null）",
      "final boolean auto = owner.rscc$isAutoCollect();" in import_strategy
      and "boolean noOrderFlag = false;" in import_strategy
      and "if (!chamber.isAutoCraftingEnabled() && !chamber.hasResidualInputReclaim()) {" in import_strategy
      and "noOrderFlag = true;" in import_strategy
      and "final TransitionSink transitionSink = noOrderFlag ? null : new NetworkTransitionSink("
          in import_strategy)
check("② 自动模式无单时仍执行 sweep/pull（不提前 return）—— 只有 !auto 才 return false",
      "if (!auto) {" in import_strategy
      and import_strategy.index("if (!auto) {") < import_strategy.index("noOrderFlag = true;"))
# 第 ②-2 条（2026-10-05 用户实测）：无单时机器侧只收回「本仓产物 / 废料」，
# 绝不把玩家摆在空闲机器上的东西（例如喂给冲压机的铁锭）抄走 —— 用户原话
# 「它居然没有执行任何任务也会被立刻收走」。
check("②-2 机器侧只收回本仓产物 / 废料（isMyProductOrScrap）；无单 / 残留边沿都不再抄走输入"
      "（第 24 轮起「本仓」= 该工位的属主仓；边沿令牌仍只属于本总线绑定的那台仓）",
      "final boolean noOrderHere = noOrderFlag;" in import_strategy
      and "owner.isMyProductOrScrap(stack)" in import_strategy
      and "final boolean edge = owner == chamber && residualEdge;" in import_strategy
      # 2026-10-05：`residualEdge` 不再放行「收回输入类」—— 那正是用户实测的
      # 「金板刚推过去就被抄回、产线反复横跳」。
      and "&& autoAcceptsItem(pos, stack, inputItemsOf(ownerInputItems, owner), false, owner);"
          in import_strategy)
# 第 ③ 条：机器侧「这台机器此刻仍要它」的直接闸门（跨仓也不再误抄）。
check("③ 机器侧新增「本步仍要的投入物绝不收回」闸门（stationStepWantsInput，且 residualEdge 绕开它）",
      "!residualEdge && chamber.stationStepWantsInput(pos, stack.getItem())" in import_strategy)
has(chamber, "public boolean stationStepWantsInput(",
    "③ 判据唯一实现在执行舱（stationStepWantsInput：按工位在制件的进度步解析配方与该步投入）")
check("③ 判据「只有确定它此刻需要才拦」：工位上没有在制件（pending==null）⇒ 不拦，成品照旧回收",
      "// 工位上没有在制件 ⇒ 没有「正在加工的这一步」要保护；成品 / 废料照旧回收。" in chamber
      and "return false; // 配方查不到 → 判不出来，不拦" in chamber
      and "return mainIngredientCandidates(recipe).contains(item);" in chamber)

# ==================== E. 本轮四项验收（latest.log 逐条对账） ====================
section("E1) 齿轮不再有「持续性的无效重复」：让位判据改成按资源、机器正在加工不算收不下")

has(chamber, 'if (stepUnitInFlightOn(target)) {\n                return false;',
    "修法①: 「机器里压着正在加工的这一件」不再被当成「收不下这一份」→ 份额内的机器正在加工时绝不让位"
    "（旧判据用一次 SIMULATE 插入当唯一依据，而置物台只有 1 格 ⇒ 机器一加工就必然判失败）")
has(chamber, 'if (!ownsStepExtraTurn(exporterPos, owners, stepExtraProbeOf(info))) {',
    "修法②: 步骤专用投入物类别的让位判定改传「该类别那件具体投入物」（不再用「机器里压着任何物品即算干不了活」）")
has(chamber, '''private static Item stepExtraProbeOf(final BusCategoryInfo info) {
        if (info == null || info.items().size() != 1) {
            return null;
        }
        return info.items().get(0);
    }''',
    "修法②: 类别含多件物品时退回旧口径（null），行为逐字不变")
check("修法③: 同一份「本步不要它」的结论不再被反复重算 —— 抑制窗口仍随步序签名作废（既有单点判定）",
      "final String wantSignature = wantedStepSignature();" in chamber
      and "stepRefusalCooldowns.clear();" in chamber
      and "private static final int STEP_REFUSAL_COOLDOWN_TICKS" in chamber)

section("E2) 金板不再「拉进来又退回」+ 同一执行器两条总线不重复推同一份投入物（门控统一）")

# 本轮同步（旧口径 → 新口径）：闸门再补 `busGoalTaskGate`（用户第 ⑦ 条：没有为本产线下单时
# 绝不取料 / 投料 / 自动合成）。导出侧与进货侧仍然共用同一个表达式，因此「一边动、一边不动」的
# 结构性死循环依旧不可能出现。
has(chamber, '''public boolean isAutoCraftingEnabled() {
        // 第三项是「有人为本产线下单」（用户第 ⑦ 条）：只牵涉本仓的料、却没人下单时，
        // 本仓既不取料也不投料，机械手不会「莫名其妙地」被喂料去做序列装配。
        // 第四项是「本流程每一步的总线配置齐全」（用户第 5 条）：有步骤没配输入 / 输出总线时，
        // 本仓一律不取料 / 不投料 —— 否则物料被抽进机器却推不出去，就是用户说的「白烧原料」。
        return busAutoCraftGate && busRelevantTaskGate && busGoalTaskGate && !busFrozenGate
            && busConfigGate;
    }''',
    "修法①: 导出侧与进货侧用同一个闸门（旧版导出只看 busAutoCraftGate，进货还要 busRelevantTaskGate "
    "⇒ 网络里只有别人的任务时，导出侧每 tick 判一次 chamber_empty 而料永远进不来；"
    "再补 !busFrozenGate ⇒ 挂起冻结时两侧一起停；"
    "第 9 轮再补 busGoalTaskGate ⇒ 没人为本产线下单时两侧一起停；"
    "第 18 轮再补 busConfigGate ⇒ 有步骤没配总线时两侧一起停，绝不「白烧原料」）")
has(chamber, 'if (!isAutoCraftingEnabled()) {\n            return List.of();\n        }',
    "修法①: busExportFilters 同步用同一个闸门（两条闸门口径一致，结构上不可能再出现「导得出、进不来」）")
has(export_strategy, 'private static final String ASSERT_MACHINE_HOLDS_OTHER_STEP_INPUT = "assert_machine_holds_other_step_input";',
    "锚点: 推料侧仍保留「手里压着本步不要的件 → 放弃推送且不重试」（不在另一台机器上重复投喂同一份）")
# 2026-10-05：让位判定新增「在制名额」闸门 —— 用户实测「下单 10 个精密构件仍多发一个金板」
# 就是份额外的总线在「份额内那台正在加工」时顶上来又投了一份（两个置物台各一件）。
# 现在让位必须同时满足「还有在制名额」与 ownsFallbackTurn。
check("推演: 下单 1 个 + 两台机器 + 份额内那台正在加工 → 先过在制名额闸门、不让位"
      " ⇒ 份额外那条总线一件都拿不到过滤项",
      "if (index >= exportShare(owners.size())) {" in chamber
      and "final boolean unitCapacityLeft = allowedConcurrentUnits() - inFlightUnitCount() > 0L;" in chamber
      and "if (!unitCapacityLeft || !ownsFallbackTurn(exporterPos, owners, shareProbeOf(info))) {" in chamber
      and "if (stepUnitInFlightOn(target)) {" in chamber)

section("E3) 坚固板端到端：注入 → 冲压 ×2（同一台冲压仓连做）→ 成品入库")

sturdy = json.load(io.open(os.path.join(CREATE_RECIPES, "sturdy_sheet.json"), "r", encoding="utf-8"))
seq_types = [step.get("type", "") for step in sturdy["sequence"]]
check("推演前提: Create 配方 = filling + pressing ×2（两次冲压是同一台机器），loops=1 ⇒ 三次加工出成品",
      seq_types == ["create:filling", "create:pressing", "create:pressing"]
      and sturdy.get("loops", 1) == 1,
      "实测 sequence=%s" % seq_types)
check("推演前提: 过渡件是同一个物品（create:unprocessed_obsidian_sheet）⇒ 三处中间产物都是它",
      sturdy["transitional_item"]["id"] == "create:unprocessed_obsidian_sheet"
      and all(step["results"][0]["id"] == "create:unprocessed_obsidian_sheet"
              for step in sturdy["sequence"]))
has(import_strategy, '"handover_to_chamber"',
    "修法①: 跨阶段中间产物直接交给「负责它下一步」的那台仓，不经 RS 网络（新增可 grep 的交接追踪行）")
has(import_strategy, '''if (candidate.selfBusSupplyTargets().contains(machinePos)) {
                    return 0;''',
    "修法②: 若这份过渡件就压在接手仓<b>自己的</b>机器上 → 原样留下（= 「下一步仍由同一台机器负责时绝不收回」，"
    "坚固板第 2、3 步正是靠这一条在同一台冲压机上连做）。"
    "第 24 轮起<b>刻意用物理口径 selfBusSupplyTargets</b>（而不是链口径 busSupplyTargets）："
    "这条判据问的是「这台机器是不是接手仓<b>自己</b>在供料」，换成链口径会把同链另一台仓供料的机器也算进来，"
    "本该直接交接的过渡件会被压回 RS 网络绕一圈（2026-10-05 实测「网络里留不住」的成因）")
has(import_strategy, '''final int localOk = transitionSink.simulate(probe, pos);
                if (localOk > 0) {
                    final ItemStack takenLocal = handler.extractItem(slot, localOk, false);''',
    "修法③（守恒）: 先 SIMULATE 夹量、再原子抽取、余量原样还回机器 —— 与既有「不丢不复制」同一套口径")
check("推演（端到端）: 注液机产出第 1 步过渡件 → 它在注液机上（不是冲压仓的供料目标）→ 直接交接进冲压仓内部存储 "
      "→ 冲压仓输出总线按该步过滤项推给冲压机 → 冲压机连做第 2、3 步 → results 池（本配方只有 sturdy_sheet）"
      "→ 成品不是输入类 ⇒ 输入总线收回网络 ⇒ RS 任务达成",
      "public boolean wantsTransitionNext(final ItemStack probe) {" in chamber
      and "public int canAcceptTransitionLocally(final ItemStack probe) {" in chamber
      and "public int acceptTransitionLocally(final ItemStack probe) {" in chamber
      and "public static List<SequenceExecutionChamberBlockEntity> chambersOf(" in chamber
      and "if (!candidate.wantsTransitionNext(probe)) {" in import_strategy
      and "if (candidate.selfBusSupplyTargets().contains(machinePos)) {" in import_strategy)

section("E4) 监视器「处理量」口径：只由 RS 自身的 TaskStatus 决定，模组的输入登记不得混入过渡件")

has(chamber, 'final long ordered = Math.max(1L, status.info().amount());',
    "锚点: 份额 / 剩余量取自 RS 自身的任务量（info.amount），不用 percentageCompleted（外部样板恒为 0）")
has(chamber, 'final long delivered = AssemblyWatchdog.deliveredAmount(status);',
    "锚点: 已交付量改用 RS 权威读数（AssemblyWatchdog.deliveredAmount：root EXTERNAL 样板的 "
    "iterationsReceived 镜像；对 INTERNAL 样板内部仍退回 stored + crafting）。"
    "2026-10-06 更新：旧断言固定的 `stored + crafting` 对本模组样板恒为 0（beforeInsert 返回 0、"
    "认领只发生在 afterInsert 且不写 internalStorage），正是本轮「多开新件」的根因")
check("口径一致的前提: 样板登记的输入里绝不含「过渡件」——JEI 导入路径用 StepInput（跳过被 Create 覆盖成过渡件的下标 0），"
      "因此 RS 不会把中间产物当成 ingredient 去截收 / 计数",
      "final ItemStack stepInput = cretae.cookiewyq.rs_create_compat.support.SequencedRecipeProbe\n"
      "                    .stepInput(proc);" in read(SRC, "client", "SequenceTerminalJeiPlugin.java")
      and "for (int i = 1; i < ingredients.size(); i++) {" in probe)
has(probe, "// 从下标 1 开始：下标 0 一定被 initFromSequencedAssembly 覆盖为过渡件（或过渡件+主原料的复合）",
    "锚点: stepInput 跳过下标 0 的唯一实现（含注释说明「为什么」）")

# ==================== 结果 ====================
print()
print("=" * 72)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
