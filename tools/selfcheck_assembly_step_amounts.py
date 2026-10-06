# -*- coding: utf-8 -*-
"""③ 「按步的原料数量」断言（源码锚点 + 等价模型推演）。

用法：python tools/selfcheck_assembly_step_amounts.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要这个脚本（无法在本地把游戏跑起来验证）：
  用户原话：「输出原料的数量不同」—— 不同步骤所需原料数量不同，怀疑输出 / 补料的数量口径
  没按「步」区分。

  事实：输出总线的一个「输入原料」类别是**按物品种类**唯一的（id = `input:<物品注册名>`），
  但同一个物品可以在多个步骤里被消耗，而且**各步数量可以不同**（附属模组 / 多步配方里
  1 件与 3 件并存；流体同理：500 mB 与 1000 mB 并存）。

  旧实现用 `putIfAbsent`（首次出现的那一步赢了）写这一类别的「每批所需量」，于是：
    * 靠前那一步只要 1 件、靠后那一步要 3 件时，类别里记的是 1；
    * 备料目标量 = `perBatch × BUS_TARGET_BATCHES` = 1 ⇒ 靠后那一步永远缺料
      （而「预估需求」那一路已经取的是 max，两路口径互相矛盾）。

  本轮把类别里的「每批所需量」改成**同一个物品 / 流体在多步里出现的最大值**，
  于是同一物品「一步都不缺」，且与 `estimated` 的取 max 口径一致（不再是两套口径）。
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
    with io.open(os.path.join(ROOT, *parts), "r", encoding="utf-8") as handle:
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


chamber = read("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat", "block", "entity",
               "SequenceExecutionChamberBlockEntity.java")

# ==================== 1. 锚点：类别里记的「每批所需量」取同物品多步的最大值 ====================
section("1) 锚点：addInputCategory / addFluidCategory 取「多步里的最大需求」")

has(chamber,
    "inputs.merge(id, stack.copy(),\n                (existing, candidate) -> candidate.getCount() > existing.getCount() ? candidate : existing);",
    "物品：同一物品被多步使用时，类别里记的是**件数最大**的那一步所需量")
has(chamber,
    "fluids.merge(id, stack.copy(),\n                (existing, candidate) -> candidate.getAmount() > existing.getAmount() ? candidate : existing);",
    "流体：同一流体被多步使用时，类别里记的是**mB 最大**的那一步所需量")
def body_of(text, signature, end_marker):
    start = text.index(signature)
    end = text.index(end_marker, start)
    return text[start:end]


item_body = body_of(chamber, "private static void addInputCategory(",
                    "private static void addFluidCategory(")
fluid_body = body_of(chamber, "private static void addFluidCategory(",
                     "/**\n     * 服务端：把「每台相连输出总线当前选择的类别」整表重建为权威归属表")
check("旧口径（首次出现的那一步赢了）已不存在：两处 add*Category 的方法体里都没有 putIfAbsent"
      "（成品 / 废料类别仍用 putIfAbsent 是刻意的：同一物品只出一种成品类别）",
      "putIfAbsent" not in item_body and "putIfAbsent" not in fluid_body
      and "target.putIfAbsent(id, stack.copy());" in chamber)
check("「预估需求」那一路仍然是 max（两路口径现在一致，不再是「类别取首条、预估取最大」的互相矛盾）",
      "estimated.merge(id, value, Math::max);" in chamber)
check("备料目标量取自该类别的「每批所需量」：perBatch = max(1, info.amount())，"
      "因此上面的修正会直接作用到 pull 的目标量",
      "final long perBatch = Math.max(1L, info.amount());" in chamber)
check("单次搬运上限仍是 BUS_TARGET_BATCHES = 1（一次一份，不因修正而变成大批量塞入）",
      "private static final int BUS_TARGET_BATCHES = 1;" in chamber)

# ==================== 2. 等价模型：把「首条 / 最大」两种口径的差额算出来 ====================
section("2) 推演：多步共用同一原料、各步数量不同时的每批需求")

LOOPS = 1
STEPS = [
    # (步序, 该步消耗的 (物品种类, 件数))
    (0, [("create:golden_sheet", 1)]),
    (1, [("create:golden_sheet", 3), ("create:golden_sheet", 0)]),  # 同一步里的两种 ingredient 也归并
    (2, [("create:cogwheel", 2)]),
]


def collect(mode):
    """复刻 computeBusCategories 里对 inputs 的写入（mode = 'first' 旧口径 / 'max' 新口径）。"""
    merged = {}
    for _step, stacks in STEPS:
        for item, count in stacks:
            if count <= 0:
                continue
            if item not in merged:
                merged[item] = count
            elif mode == "max":
                merged[item] = max(merged[item], count)
    return merged


first = collect("first")
newest = collect("max")
print("  旧口径（首条）每批需求 = %s" % first)
print("  新口径（最大）每批需求 = %s" % newest)
check("旧口径把金板的每批需求记成 1 件（首条那个步只要 1）—— 靠后那一步要 3 件，永远补不齐",
      first["create:golden_sheet"] == 1)
check("新口径把金板的每批需求记成 3 件（多步里的最大值）—— 两步都不缺料",
      newest["create:golden_sheet"] == 3)
check("齿轮（只在一步出现）两种口径一致 ⇒ 修正不会改变单步配方（如精密构件）的行为",
      first["create:cogwheel"] == newest["create:cogwheel"] == 2)

# ==================== 3. 推演：目标量与缺口（备料 = 目标量 − 仓内已有） ====================
section("3) 推演：缺口口径（备料 = 目标量 − 仓内已有，目标量 = 每批需求 × 1）")


def deficit(per_batch, have, capacity=10 ** 9):
    # BUS_TARGET_BATCHES=1 / BUS_ITEM_TARGET_MAX=64 / BUS_PULL_BATCH_ITEMS=4（本轮新增的逐步抽取批上限）
    target = min(max(1, per_batch) * 1, 64)
    return max(0, min(target - have, capacity, 4))


WOOD_STEP1, WOOD_STEP2 = 0, 1


def first_step_wins_capacity():
    """旧口径：机器正在第 2 步（要 3 件），但类别里只记了第 1 步的 1 件。"""
    return deficit(collect("first")["create:golden_sheet"], have=0)


def max_wins_capacity():
    return deficit(collect("max")["create:golden_sheet"], have=0)


check("旧口径下第 2 步（要 3 件）只能拿到 %d 件的备料 ⇒ 永远差 2 件" % first_step_wins_capacity(),
      first_step_wins_capacity() == 1)
check("新口径下同一步能拿到 %d 件的备料 ⇒ 一步到位、不再缺料" % max_wins_capacity(),
      max_wins_capacity() == 3)
check("缺口仍是「仓内已够就不再抽」：仓内已有 3 件时目标量 3 → 一份都不抽（不会无限累积）",
      deficit(3, have=3) == 0 and deficit(3, have=1) == 2)
check("目标量上限 64 仍生效，但「一轮最多抽一批 4 件」⇒ 逐步消耗（用户第 ③ 条，"
      "不再出现与加工进度不匹配的一次性大幅跳变）",
      deficit(9999, have=0) == 4)

# ==================== 4. 单步配方（用户现场的精密构件）行为不变 ====================
section("4) 回归：单步配方（每个输入只被一个步骤使用）行为与既有逐字一致")

PRECISION = [
    (0, [("create:cogwheel", 1)]),
    (1, [("create:large_cogwheel", 1)]),
    (2, [("c:nuggets/iron", 1)]),
]


def collect_of(steps, mode):
    merged = {}
    for _step, stacks in steps:
        for item, count in stacks:
            if item not in merged:
                merged[item] = count
            elif mode == "max":
                merged[item] = max(merged[item], count)
    return merged


check("精密构件的三个输入各只在一个步骤里出现 ⇒ 新旧口径完全相同",
      collect_of(PRECISION, "first") == collect_of(PRECISION, "max")
      == {"create:cogwheel": 1, "create:large_cogwheel": 1, "c:nuggets/iron": 1})

# ==================== 5. 推演：定额下单（1 个 / 64 个）⇒ 主原料精确消耗 ====================
section("5) 推演：只放刚好够的量时，下单 N 个 ⇒ 精确消耗 N 份主原料（用户第 ① 条「精确消耗」）")

# 工位状态机（把 Create「一台机器一次只加工一件」与推料 / 收回两侧的闸门原样搬过来）：
#   free      = 工位空着（可以开一件新在制件）
#   holding   = 起步原料刚推上去、还没被 Create 换成过渡件（旧口径「多耗一份」的漏点就在这段窗口）
#   in_flight = 已是带进度组件的在制件 ⇒ unitInFlightAt=true ⇒ 闸门必拦
PUSH_TO_CONVERT = 2   # holding 持续 tick（机器接管那一刻的窗口）
IN_FLIGHT = 8         # 在制件占用工位的 tick
RECLAIM_AFTER = 1     # 旧口径：holding 满 1 tick 就被输入总线抄回网络（本轮：任务期间绝不收回）
TICKS = 4000


def simulate(order, protect=True, ticks=TICKS):
    """protect=True = 本轮口径（任务期间不收回起步原料 + 工位非空即拦）。"""
    stock = order
    pushes = 0
    state = "free"
    timer = 0
    holding_age = 0
    for _t in range(ticks):
        # ① 收回侧：旧口径在 holding 窗口里把「还没被消耗的起步原料」抄回网络（守恒：原样 +1）。
        #    本轮：起步原料在任务期间一律不收回，因此这一步不存在。
        if state == "holding" and not protect and holding_age >= RECLAIM_AFTER:
            state = "free"
            stock += 1
            holding_age = 0
        # ② 推料闸门（本轮：工位非空 ⇒ 不推；旧口径：只认带进度组件的在制件 ⇒ holding 里还能再推）
        if state == "free" and stock > 0:
            stock -= 1
            pushes += 1
            state = "holding"
            timer = PUSH_TO_CONVERT
            holding_age = 0
            continue
        # ③ 工位自身推进
        if state == "holding":
            holding_age += 1
            timer -= 1
            if timer <= 0:
                state = "in_flight"
                timer = IN_FLIGHT
        elif state == "in_flight":
            timer -= 1
            if timer <= 0:
                state = "free"
        if stock <= 0 and state == "free":
            break
    return pushes, stock


for order in (1, 64):
    pushes_new, left_new = simulate(order)
    pushes_old, left_old = simulate(order, protect=False)
    print("   下单 %-3d 个：本轮推送 %-3d 次（网络剩 %d）；旧口径推送 %-5d 次（网络剩 %d）" %
          (order, pushes_new, left_new, pushes_old, left_old))
    check("下单 %d 个 ⇒ 恰好推送 %d 份主原料、网络一份不剩（旧口径会多推 / 一直推）" % (order, order),
          pushes_new == order and left_new == 0 and pushes_old > order)

check("闸门判据是「工位空着」而不是「有没有带进度组件的在制件」：holding 窗口不再被二次投料 / 反复回收",
      simulate(64)[0] == 64 and simulate(1)[0] == 1
      and simulate(1, protect=False)[0] > 1)

# ==================== 结果 ====================
print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
