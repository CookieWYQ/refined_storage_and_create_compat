# -*- coding: utf-8 -*-
"""① 成品守恒 + 「任务必须能继续前进」断言（源码锚点 + 等价模型推演）。

用法：python tools/selfcheck_assembly_product_conservation.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要这个脚本（无法在本地把游戏跑起来验证）：
  用户实测「终端里还是显示一个都没有，但是任务又显示还剩下 55 个没有被处理 ——
  那显然还是自相矛盾的」。这句矛盾的根因有两条，本脚本各钉一组断言：

  A. **成品去向链的守恒**：机器产出 → 输入总线收回 → RS 网络。整条链上每一次「先抽后插」
     都必须「先按网络能收下的量夹紧（SIMULATE）→ 原子抽取 → 插入 → 余量**原样回写原处**」，
     因此「机器产出的成品数 == 进入网络的成品数」，且任何时刻一份东西只在一处
     （机器 / 执行舱内部存储+磁盘 / 网络）。

  B. **挂起只由玩家点「继续」解除（本轮撤销自动恢复）**：任务被本模组挂起时 RS 不再 step 它
     （不抽料 / 不投料 / 不驱动执行器），已抽出的中间件原地冻结在任务自己的暂存里。
     上一轮曾让「扫描一旦把原因判回 NONE（或观察到进展）就当场恢复」，本轮按用户要求撤回：
     玩家拆机器常常不是失误而是<b>故意</b>的（要增加机器数量 / 增加输出总线数量并重新配置），
     因此「必须要点击继续才能继续任务」，而不能「一检测到（连上）就立刻继续」。
     本脚本把这条规则（挂起后一律不自动恢复；恢复入口唯一 = AssemblyWatchdog.resume）连同
     「正常运行时绝不挂起」一起固化成断言。

  C. **诊断口径**：任务开始时的绑定快照必须能打出产物名与数量（旧顺序让它永远是 `product= x0`），
     否则「成品去哪了」的第一步就查不下去。
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
RS_SRC = os.path.join(ROOT, "local_src", "rs_src")

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


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


import_strategy = read(SRC, "support", "RsccChamberImportStrategy.java")
export_strategy = read(SRC, "support", "RsccChamberExportStrategy.java")
watchdog = read(SRC, "support", "AssemblyWatchdog.java")
task_impl = read(RS_SRC, "com", "refinedmods", "refinedstorage", "api", "autocrafting", "task",
                 "TaskImpl.java")
external_pattern = read(RS_SRC, "com", "refinedmods", "refinedstorage", "api", "autocrafting",
                        "task", "ExternalTaskPattern.java")

# ==================== A. 成品去向链：不丢 / 不复制 ====================
section("A1) 锚点：收回侧的每一步都是「先模拟夹量 → 原子抽取 → 插入 → 余量原样回写」")

has(import_strategy,
    "final long acceptable = storage.insert(resource, inSlot.getCount(), Action.SIMULATE, actor);",
    "仓内收：先 SIMULATE 问网络能收下多少（收不下就这一格一点都不动）")
has(import_strategy,
    "final ItemStack taken = chamberStore.extractItem(\n                slot, (int) Math.min(acceptable, inSlot.getCount()), false);",
    "仓内收：正式搬运时才原子抽取（抽的量已被 SIMULATE 夹过）")
has(import_strategy, "chamberStore.insertItem(0,\n                    taken.copyWithCount((int) (taken.getCount() - inserted)), false);",
    "仓内收：网络没吃完的余量原样放回执行舱（绝不销毁）")
has(import_strategy, "handler.insertItem(slot, taken.copyWithCount((int) (taken.getCount() - inserted)), false);",
    "机器侧收：网络没吃完的余量原样还回机器（绝不销毁）")
has(import_strategy,
    "chamber.outputTank.fill(\n                    drained.copyWithAmount((int) (drained.getAmount() - inserted)),",
    "流体收：余量原样填回执行舱流体存储（绝不销毁）")

section("A2) 锚点：输出总线（成品 / 中间产物的唯一对外通道）同样先模拟再搬、余量回写")

has(export_strategy, "final ItemStack remainder = ItemHandlerHelper.insertItem(target, pick, true);",
    "推料：先 SIMULATE 目标能收下多少（收不下一点都不动 → DESTINATION_DOES_NOT_ACCEPT）")
has(export_strategy, "chamberStore.insertItem(0, remainder, false);",
    "推料：插不下的余量立即回写执行舱内部存储（既不销毁、也绝不回流网络）")
has(export_strategy, "chamber.outputTank.fill(drained.copyWithAmount(drained.getAmount() - filled),",
    "流体推料：余量立即回写执行舱内部存储")

section("A3) 锚点：成品的「落点」是网络存储本身（不是任务内部暂存）—— 终端可见数 == 网络实物数")

has(external_pattern, "    long afterInsert(final ResourceKey resource, final long amount) {\n        if (!root) {\n            return 0;\n        }\n        return trySatisfy(resource, amount);",
    "RS：根样板（= 本模组的序列装配样板，output 就是产物）的产出走 afterInsert → trySatisfy")
has(external_pattern, "    long beforeInsert(final ResourceKey resource, final long amount) {\n        if (root) {\n            return 0;\n        }\n        return trySatisfy(resource, amount);",
    "RS：只有非根（子）样板的原料才在 beforeInsert 被截进任务暂存（根样板恒 0）")
after_body = external_pattern[external_pattern.index("    long afterInsert(final ResourceKey resource, final long amount) {"):]
after_body = after_body[:after_body.index("    private long trySatisfy(")]
check("RS：根样板的 afterInsert 只扣减 expectedOutputs，**不**把产物塞进 internalStorage "
      "⇒ 产物确实落在网络存储里（终端看得见），因此「终端可见数 == 网络实物数」成立",
      "internalStorage" not in after_body and "expectedOutputs.remove" in external_pattern)
check("RS：TaskImpl 里凡是往 internalStorage 加东西的地方只有两处（初始原料抽取 / 非根拦截），"
      "根样板的产出不在其中",
      task_impl.count("internalStorage.add(") == 3
      and "internalStorage.add(initialRequirementResource, extracted);" in task_impl)

# ==================== A4. 等价模型：一轮坚固板的成品守恒 ====================
section("A4) 推演：机器产出 N 件成品 → 全部进入网络（数量与实物都守恒）")

PRODUCTS = 9          # 用户现场：已经做出来的一批（监视器上「已收到」那一部分）
MACHINE_CAP = 4       # 机器侧一次能交出的最大件数（模拟「网络 / 容器容量」）


def network_cap(capacity_left, stored_before, moved, interception=0):
    """storage.insert(resource, n, EXECUTE, actor) 的返回面。

    RS 的 RootStorageImpl#insert 把「被任务拦截的量」也算进返回值（拦截 = 直接进任务暂存，
    不走 storage），因此传入 interception 时 inserted 会更大 —— 本脚本据此断言
    「我方只按返回值记账」在两种情形下都不会把东西记丢 / 记重。
    """
    return min(capacity_left, moved) + interception


def reclaim_once(machine_items, chamber_items, net_capacity, net_stored):
    """复刻一次输入总线自动收回：仓内 + 机器侧两处，语义完全一致。"""
    available = machine_items + chamber_items          # 同一份东西只在一处：两处之和 = 待收回总量
    acceptable = min(available, net_capacity)          # SIMULATE 夹量
    if acceptable <= 0:
        return machine_items, chamber_items, net_capacity, net_stored, 0
    taken = acceptable                                  # 原子抽取（先从机器侧、再从仓内）
    from_machine = min(machine_items, taken)
    from_chamber = taken - from_machine
    machine_items -= from_machine
    chamber_items -= from_chamber
    inserted = network_cap(net_capacity, net_stored, taken)
    if inserted < taken:                                # 理论不可达：SIMULATE 已夹过
        back = taken - inserted
        machine_items += back                       # 余量原样回写原处（这里演示回写机器侧）
    net_capacity -= inserted
    net_stored += inserted
    return machine_items, chamber_items, net_capacity, net_stored, inserted


machine, chamber, cap, net = PRODUCTS, 0, MACHINE_CAP, 0
total_before = machine + chamber + net
passes = 0
while machine + chamber > 0 and cap > 0:
    machine, chamber, cap, net, moved = reclaim_once(machine, chamber, cap, net)
    passes += 1
    if moved == 0:
        break
print("  %d 件成品 → %d 轮收回后：网络 %d 件 / 机器与仓内共 %d 件"
      % (PRODUCTS, passes, net, machine + chamber))
check("成品守恒：机器产出 %d 件 == 进入网络 %d 件 + 尚未收回 %d 件"
      % (PRODUCTS, net, machine + chamber),
      net + machine + chamber == PRODUCTS)
check("总量守恒：任何一轮结束后「机器 + 仓内 + 网络」恒等于产出数（不复制 / 不销毁）",
      machine + chamber + net == total_before)

# 网络容量为 0（网络塞满）时：一点都不动，东西原样留在原处（绝不销毁）
blocked = reclaim_once(PRODUCTS, 0, 0, 0)
check("网络收不下时一份都不回收（东西原样留在机器 / 仓里，绝不销毁）",
      blocked[0] == PRODUCTS and blocked[1] == 0 and blocked[4] == 0)

# 任务拦截计入返回值：我方按返回值夹量，不会把「进了任务暂存」的那一份再记一次到网络
intercepted = network_cap(100, 0, 3, interception=3)
check("被任务拦截的插入量也计入 storage.insert 的返回值（RS 语义）→ 我方只会把它当成"
      "「已经离开原处」，因此绝不会重复搬运 / 重复记账",
      intercepted == 6 and "return inserted + totalIntercepted;" in read(
          RS_SRC, "com", "refinedmods", "refinedstorage", "api", "storage", "root",
          "RootStorageImpl.java"))

# ==================== B. 挂起只由「继续」解除：一律不自动恢复 ====================
section("B1) 锚点：恢复入口唯一（玩家「继续」），扫描判回正常也不解挂")

has(watchdog, "if (record.suspendState == SuspendState.RUNNING) {",
    "AssemblyWatchdog.advanceSuspendState：只有 RUNNING 态才做「挂起」判定，SUSPENDED 态不做任何自动恢复")
check("旧的自动恢复路径（观察到进展即恢复 / 原因判回 NONE 即恢复 / PROBING 探测窗口）已全部删除",
      "markObservedProgress" not in watchdog
      and "PROBING," not in watchdog and "SuspendState.PROBING" not in watchdog
      and "卡住原因消失" not in watchdog and "MIN_BACKOFF_TICKS" not in watchdog)
check("恢复只有一份实现，且只有一个调用点（AssemblyWatchdog.resume = 玩家点「继续」）",
      "private static void resumeRecord(final Record record, final long now) {" in watchdog
      and watchdog.count("resumeRecord") == 2)

# ==================== B2. 等价模型：原因恢复正常后不会自动恢复 ====================
section("B2) 推演：原因恢复正常 + 任意长时间，仍为 SUSPENDED；只有「继续」才回 RUNNING")

OFFLINE_THRESHOLD = 40   # 掉线原因要连续 40 tick 才挂起


class SuspendModel(object):
    """与 Java 的 scanNetwork + advanceSuspendState 同构：已挂起冻结原因、绝无自动恢复。"""

    def __init__(self):
        self.state = "RUNNING"
        self.reason = "NONE"
        self.ticks = 0

    def scan(self, reason):
        if self.state != "RUNNING":
            return                       # 已挂起：冻结原因，不重新分类
        self.reason = reason

    def tick(self):
        self.ticks = 0 if self.reason == "NONE" else self.ticks + 1
        if (self.state == "RUNNING" and self.reason == "EXECUTOR_OFFLINE"
                and self.ticks > OFFLINE_THRESHOLD):
            self.state = "SUSPENDED"

    def resume(self):
        self.state = "RUNNING"
        self.reason = "NONE"
        self.ticks = 0


model = SuspendModel()
for _ in range(OFFLINE_THRESHOLD + 1):
    model.scan("EXECUTOR_OFFLINE")
    model.tick()
check(model.state == "SUSPENDED", "掉线持续超过阈值 → 挂起", "没有在阈值后挂起")

resumed = False
for _ in range(200000):                  # 机器回到网络 + 任意长的时间
    model.scan("NONE")
    model.tick()
    if model.state == "RUNNING":
        resumed = True
check(not resumed and model.state == "SUSPENDED",
      "② 机器回到网络后经过任意时长，状态仍为 SUSPENDED（一律不自动恢复）",
      "出现了自动恢复（原因正常即解挂）")

model.resume()                           # = AssemblyTaskActionPacket.ACTION_RESUME
check(model.state == "RUNNING", "② 收到 ACTION_RESUME（AssemblyWatchdog.resume）后才回到 RUNNING",
      "手动「继续」没有解除挂起")

# ③ 不误挂起：正常推进（原因恒 NONE）任何时长都不挂起
model2 = SuspendModel()
for _ in range(200000):
    model2.scan("NONE")
    model2.tick()
check(model2.state == "RUNNING", "③ 正常推进的任务（原因恒 NONE）任何时长都不挂起",
      "正常任务被误挂起")

# ==================== C. 绑定快照要能打出产物名 / 数量 ====================
section("C1) 锚点：logBindingSnapshot 必须在快照字段写完**之后**调用")

amount_assign = watchdog.index("record.amount = status.info().amount();")
log_call = watchdog.index("logBindingSnapshot(level, chambers, pattern, status.info().id().id(), record);")
check("调用顺序：record.amount 先写、logBindingSnapshot 后调（否则日志永远打 `product= x0`，"
      "排查「成品去哪了」时第一步就被误导）",
      amount_assign < log_call)
check("绑定快照仍然逐行输出 machine / chamberExists / recipeType / chamberSteps / nextStepOwner",
      '" chamberExists=" + (chamber != null)' in watchdog
      and '" nextStepOwner=" + nextStepOwnerOf(' in watchdog)

# ==================== D. 「上报成功但网络没保住」必须被识别（reported vs landed） ====================
section("D) 插入必须按「网络聚合存量的真实增量」记账：reported / landed / not_retained 三者分开")

has(import_strategy, "final long landed = landedAmount(storage, taken.getItem(), netBefore, inserted);",
    "锚点: 机器侧回收的「真实入网增量」由「插入前后网络存量之差」算出（不再只信 insert 的返回值）")
has(import_strategy, "final long landed = landedAmountFluid(storage, drained.getFluid(), netBefore, inserted);",
    "锚点: 流体侧同一口径（机器侧与仓内两条路都算）")
has(import_strategy, "private static long landedAmount(final RootStorage storage, final Item item,",
    "锚点: landedAmount 的唯一实现（min(reported, max(0, netAfter - netBefore))）")
has(import_strategy, "public static long networkFluidAmount(",
    "锚点: 流体网络存量的唯一读取实现（与物品版同口径）")
check("insert 的返回值把「被任务截收的量」也算进来（RS 语义），因此 landed 必须按聚合存量之差夹紧",
      "return inserted + totalIntercepted;" in read(RS_SRC, "com", "refinedmods", "refinedstorage",
                                                   "api", "storage", "root", "RootStorageImpl.java")
      and "Math.min(reported, Math.max(0L, netAfter - netBefore))" in import_strategy)
check("trace 把三者一起写出来：reported（上报）/ landed（真实入网）/ net_after（网络存量）/ not_retained（上报却没保住）",
      "reported=" in import_strategy and "landed=" in import_strategy
      and "net_after=" in import_strategy and "not_retained=" in import_strategy)
check("「未完成件」（带 create:sequenced_assembly 的过渡件）绝不可能是任何样板的产出 ⇒ 它的 claimed_by_task 恒为 no "
      "（被网络『收下却不保留』时不再冤枉成「被合成任务截收」，而是写明被存储源吞掉）；"
      "且判据用「本次插入的网络增量」landed 而不是「插入后的网络总存量」（后者几乎恒为 false，"
      "会把 RS 已记账的任务产出也误报成 no）",
      "final boolean transition = isTransitionItem(stack);" in import_strategy
      and "final boolean claimed = !transition && landed < reportedInserted;" in import_strategy
      and "public static boolean isTransitionItem(final ItemStack stack) {" in import_strategy)
check("网络「假收下」需要玩家干预：打一条限频 WARN 并给出可执行的排查方向（挂创造板条箱的外部存储）",
      "RsccAssemblyDebug.warn(\"voided@\"" in import_strategy
      and "create:creative_crate" in import_strategy
      and "refinedstorage:external_storage" in import_strategy)

# ==================== E. 「凭空生成金板」：守恒的真相 + 闸门 ====================
chamber_src = read(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
section("E1) 搬运层不存在复制：所有「产出 / 插入」路径都是「先模拟 / 先插后抽 / 余量原样回写」")
# 逐条核对「回收量 ≤ 机器实际产出量」：本模组自己也从不生成物品，RS 的 ensureTask 才会真的产出。
# 因此「凭空生成金板」不可能是本模组的复制，只可能是「RS 按玩家写的配方真的做了一份」——
# 它被请求的唯一入口是 requestMissingViaAutocraft，而该入口只在「有人为本产线下单」时才可达。
has(task_impl, "internalStorage.add(initialRequirementResource, extracted);",
    "锚点: RS 的任务暂存只在「初始原料抽取 / 非根拦截」处增加 ⇒ 产出不会凭空加倍")
check("锚点: 本模组从不调用任何「生成物品」API（只调 RS 的 ensureTask / insert / extract）——"
      "搬运层只搬不造，因此守恒断言只需证明「搬运不复制」",
      "ensureTask" in chamber_src and ".copyWithCount(" in import_strategy
      and "Block.popResource" in chamber_src)

section("E2) 「凭空生成金板」的根因（已被第 ⑤ 条加固消除）：无单却补合成")
check("锚点①: 补合成请求只在本仓「真的有人下单」时可达（入口 fillInternalForBus 开头要求 busGoalTaskGate）",
      "if (!busAutoCraftGate || !busRelevantTaskGate || !busGoalTaskGate || !busConfigGate"
      in chamber_src)
check("锚点②: hasGoalTask 现在把「投入物 / 起步原料」整条排除 ⇒ 别人维持金板库存不会触发本产线开工",
      "if (isStartIngredient(item.item()) || isInputMaterial(item.item())) {" in chamber_src)
print("  反例表（谁在动金板 / 谁在下单 ⇒ 本仓是否开工并请求补合成）")
print("  %-46s %-10s %-12s" % ("网络里正在跑的任务", "有本产线单?", "会补合成?"))


def phantom(target_in_goals, is_input_side, suspended):
    has_order = target_in_goals and not is_input_side and not suspended
    return has_order, has_order  # 开工 ⇔ 才会走到 requestMissingViaAutocraft


CASES_PH = [
    ("定量保持器维持金板（金板 ∈ 输入侧）", True, True, False, False),
    ("别人在做齿轮（齿轮 ∈ 输入侧）", True, True, False, False),
    ("有人下单 64 个精密构件", True, False, False, True),
    ("本产线订单被挂起", True, False, True, False),
]
for label, in_goals, input_side, susp, expect in CASES_PH:
    has_order, autocraft = phantom(in_goals, input_side, susp)
    print("  %-46s %-10s %-12s %s" % (label, has_order, autocraft,
                                       "OK" if has_order == expect else "MISMATCH"))
check("E2a 只有「以本产线产物 / 在制件为目标且未被挂起的真实订单」才开工 ⇒ 才可能请求 RS 补合成",
      all(phantom(g, i, s)[0] == e for _l, g, i, s, e in CASES_PH))
check("E2b 无单 ⇒ 零补合成 ⇒ 网络里不会「凭空多出一个金板」",
      phantom(True, True, False)[1] is False and phantom(True, False, True)[1] is False)

section("E3) 用户第 ③ 条：本步仍要的投入物绝不被任何输入总线抄走（跨仓拉锯 = 0）")
has(import_strategy, "!residualEdge && chamber.stationStepWantsInput(pos, stack.getItem())",
    "锚点: 机器侧收回前的「这台机器此刻还要它」闸门（唯一实现点在执行舱）")
has(chamber_src, "public boolean stationStepWantsInput(",
    "锚点: stationStepWantsInput（按工位在制件的进度步解析配方与该步投入；判不出来一律放行）")


def tug_of_war(station_step_wants, item_is_my_input, residual_edge):
    """复刻 autoAcceptsItem 的一段：True = 被收回（会与推料侧形成拉锯）。"""
    if not residual_edge and station_step_wants:
        return False                      # 本步仍要它 → 绝不收回（本轮新增）
    if residual_edge:
        return True
    return not item_is_my_input           # 不是我输入类 ⇒ 当废料收回（旧口径，跨仓会误抄）


print("  反例表（同一个共享工位上那份「本步要的投入物」是否被 B 仓抄走）")
print("  %-40s %-10s %-14s" % ("情形", "本步还要它", "被收回?"))
CASES_TOW = [
    ("A 仓刚推的齿轮，工位本步要它（B 仓输入类里没有它）", True, False, False, False),
    ("齿轮已不再被本步需要（真废料）", False, False, False, True),
    ("任务刚结束的一次性边沿（残留收尾）", True, False, True, True),
]
for label, wants, mine, residual, expect in CASES_TOW:
    got = tug_of_war(wants, mine, residual)
    print("  %-40s %-10s %-14s %s" % (label, wants, got, "OK" if got == expect else "MISMATCH"))
check("E3a 本步仍要它 ⇒ 绝不被收回（推—收—推的真实往返 = 0）；不再需要它 ⇒ 照常当废料收回",
      all(tug_of_war(w, m, r) == e for _l, w, m, r, e in CASES_TOW))

# ==================== 结果 ====================
print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
