# -*- coding: utf-8 -*-
"""第 27 轮自检：**「未交付成品件」必须计入在制件** + **名额绝不允许比门控更宽**。

用法：python tools/selfcheck_round27_parked_product_capacity.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要有它（本轮同样无法在本地把游戏跑起来验证）：

  实测现场（快照 `run/rscc_diag/20261006-115708/snapshot.json` + `run/logs/latest.log`）：
  多余中间产物已经只剩 **1 件** —— `cachePool.storedTotal = 1`，内容是
  `{create:incomplete_track: 1}`，而该轨道订单的交付数**恰好等于订单量**（10/10）。
  ⇒ 还是「多开了一件」，只是从 14 件降到了 1 件。

  根因 A（本轮日志取证，证据链闭合）——**成品在「做完」与「被 RS 认领」之间是计数盲区**：
    成品先压在链上工位（被本仓的堵塞自愈 `unblock_recovered` 收回本仓），随后才写回网络、
    才被 RS 认领（`iterationsReceived` ⇒ `delivered`）。在整段窗口里它
    **既不是过渡件**（不带 `create:sequenced_assembly` 进度组件 ⇒ 既有的 ①登记表 / ②仓内 / ③缓存池
    三个分量都不认它）**也还没被认领**（`delivered` 还没加）⇒ `delivered + inflight` 比真实开件数少 1
    ⇒ 名额凭空多出 1 ⇒ **多开一件**。日志原文（订单 10 条轨道）：

      11:56:16.711 track inflight=10(reg=2,store=0,pool=8) delivered=0   ← 10 件全部开出去（峰值）
      11:56:42.006 chamber@(-16,-60,10) unblock_recovered {item=create:track x1} from=(-16,-60,12)
      11:56:42.009 track remaining=8 delivered=2 inflight=7(reg=0,store=1,pool=6) allowed=8 cap=1  ← 多放 1 件
      11:56:42.210 chamber@(-5,-60,6) take_to_chamber {minecraft:stone_slab x1}                     ← 第 11 件
      11:56:42.259 track remaining=7 delivered=3 inflight=7(reg=1,store=0,pool=6) allowed=7 cap=0   ← 认领才追上

    同一形状在 11:56:46.208/.215/.266 又出现一次。第 11 件就是快照里那 1 件
    `create:incomplete_track`（订单计数器 `(-5,-60,6)|minecraft:stone_slab|pull = 11`，订单 10）。

  根因 B（同一份日志，两种完全不同的状态被混成 `-1`）——**「读得到、但没有需求」被当成「判不出来」**：
    名额函数对 `remaining < 0` 一律走「不确定 ⇒ 保守放 1 件」。而 `-1` 其实覆盖三种状态，
    其中「任务表读得到、但一条以本配方为目标的任务都没有」= **没有需求**（门控已经关闭：
    `gate autocrafting=false reason=no_active_task`）。日志原文：

      11:56:36.209 chamber@(-10,-60,10) remaining=-1 delivered=0 inflight=0 allowed=-1 cap=1
      11:56:36.209 take_to_chamber {create:powdered_obsidian x1}                        ← 真的去拉料
      11:56:37.409 gate autocrafting=false relevant=false reason=task_end_confirmed     ← 门控本来就关着
      11:56:36.409 return {item x1} to=network reason=task_finished                     ← 原样退回（纯抖动）
      11:57:34.009 chamber@(-5,-60,6) remaining=-1 delivered=0 inflight=0 allowed=-1 cap=1
      11:57:34.111 take_to_chamber {minecraft:stone_slab x1}                            ← 又白拉一份
      11:57:34.160 bus_share_skip ... reason=share_exhausted_order_remaining remaining=-1
      11:57:34.411 return {item x2} to=network reason=task_finished                     ← 又退回

    本次会话共 3 次（11:54:43.108 / 11:56:36.209 / 11:57:34.009）；坚固板那两次对应快照计数器
    `(-10,-60,10)|create:powdered_obsidian|pull 22 / push 20 / return 2`。

  修法（本轮，只改执行舱一个文件）：
    A. `inFlightBreakdownForRecipe` 的 ④ 分量 = 本配方**未交付成品件**（结果池物品，
       已剔除过渡件与起步原料 ⇒ 与 ①②③ 互斥），数在「链上工位（含机械手操作对象）+ 本仓内部存储」，
       跨仓按物理位置 / 存储身份去重。
    B. `remainingOrderUnitsFor` 新增 `NO_ORDER_READABLE = -2`（读得到、但没有需求）；
       `startCapacityForRecipe` 对它给 0 件，`-1`（真判不出来）**原样保留**「保守放 1 件」。

本脚本做三件事：
  ① 源码锚点：④ 分量的实现只有一份、与 ①②③ 互斥且逐位置去重；名额绝不允许比门控更宽；
  ② 反例表：用**日志原文的那几个数**对拍「旧口径 vs 新口径」，钉死两个多开时刻，
     并证明其余时刻（正常开工 / 已交付满 / 非必得放大）逐字未变；
  ③ 流水推演：把「成品有一段时间不可见」这件事显式建模，证明新口径下开件总量 ≤ 订单量。
"""
import io
import math
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
CHAMBER = os.path.join(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")

FAILURES = []
CHECKS = [0]


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
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
    check(name, needle in text, "" if needle in text else "missing: %s" % needle.replace("\n", " ")[:110])


def section(title):
    print()
    print("=" * 84)
    print(title)
    print("=" * 84)


chamber = read(CHAMBER)

# ============================================================ ① 源码锚点
section("①) 源码锚点：④ 未交付成品件（根因 A）")

has(chamber, "private long[] inFlightBreakdownForRecipe(final String recipeId) {",
    "锚点: 分量拆分仍只有一个实现（诊断与判定同源，绝无第二套口径）")
has(chamber, "final long[] parts = new long[4];",
    "锚点: 分量从三个扩成四个（④ = 未交付成品件）")
has(chamber, "Math.min(parts[0] + parts[1] + parts[2] + parts[3], BUS_ITEM_TARGET_MAX)",
    "锚点: 在制件总数 = ①登记表 + ②仓内 + ③缓存池 + ④未交付成品（仍夹 BUS_ITEM_TARGET_MAX）")
has(chamber, "parts[2] += pooledTransitionalUnits(level, recipeId)[0];",
    "锚点: ③（缓存池过渡件）逐字保留 —— 上一轮的修复没有被推翻")
has(chamber, "final Set<Item> settled = settledResultItems(recipeId);",
    "锚点: ④ 的作业集合只有一份来源（settledResultItems）")
has(chamber, "private Set<Item> settledResultItems(final String recipeId) {",
    "锚点: ④ 的归属判据（结果池物品）")
has(chamber, "results.removeAll(inFlightMarkerItems(recipeId));",
    "锚点: ④ 必须剔除「起步原料候选」，绝不把裸起步原料当成成品")
has(chamber, "private static long countSettledIn(",
    "锚点: ④ 的逐格读法只有一份实现（仓内 / 工位共用）")
has(chamber, "private static long countSettledAt(",
    "锚点: ④ 的逐工位读法（与 ① 共用已计数位置表 ⇒ 同一位置绝不数两次）")
check("锚点: ④ 与 ① 共用同一张已计数位置表（countedPositions），与 ② 各自独立去重"
      "（settledStorages 独立于 countedStorages —— ② 的存储去重挡不住 ④，否则成品仍会被漏掉）",
      "countSettledAt(level, station.getKey(), settled, recipeId, countedPositions)" in chamber
      and "countSettledAt(level, station.getValue(), settled, recipeId, countedPositions)" in chamber
      and "settledStorages.add(chamber.internalItemStorage())" in chamber
      and "countedStorages.add(chamber.internalItemStorage())" in chamber)
check("锚点: ④ 只读（不发任何 extract / insert / setChanged，绝不搬运 / 销毁任何资源）",
      ".extract(" not in body(chamber, "private long[] inFlightBreakdownForRecipe(",
                             "private long[] pooledTransitionalUnits(")
      and ".insertItem(" not in body(chamber, "private long[] inFlightBreakdownForRecipe(",
                                    "private long[] pooledTransitionalUnits(")
      and ".extractItem(" not in body(chamber, "private static long countSettledIn(",
                                     "private static long countSettledAt("))
has(chamber, ',settled=" + parts[3] + ")"',
    "锚点: 名额诊断行把 ④ 摊出来（下次可以一眼看到「是不是成品又没被算进去」）")
check("锚点: ④ 的判据绝不用「名字含 incomplete_/unprocessed_」这类启发式",
      "getDescriptionId()" not in chamber
      and 'contains("incomplete' not in body(chamber, "private Set<Item> settledResultItems(",
                                             "private static long countSettledIn("))

section("②) 源码锚点：名额绝不允许比门控更宽（根因 B）")

has(chamber, "private static final long NO_ORDER_READABLE = -2L;",
    "锚点: 「读得到、但没有需求」是一个独立的哨兵值（-2），不再与 -1（判不出来）混为一谈")
has(chamber, "return NO_ORDER_READABLE;",
    "锚点: 只有「任务表读得到 + 本仓整条产线确实没有订单」才升级成 -2")
has(chamber, "if (best < 0L && !hasGoalTask()) {",
    "锚点: 判据与既有的开闸判据 hasGoalTask() 同源（绝不新造第二套「有没有订单」口径）")
has(chamber, "if (networkForNoOrder != null",
    "锚点: 再断言一次「网络 / 自动合成组件读得到」—— 绝不把「判不出来」误判成「没需求」")
has(chamber, "if (remaining == NO_ORDER_READABLE) {",
    "锚点: 名额函数对 -2 单独给 0 件")
has(chamber, "capacity = inFlight <= 0L ? 1L : 0L;",
    "锚点: 对 -1（真判不出来）仍然「无在制 ⇒ 保守放 1 件」（老回归没有带回来）")
capacity_body = body(chamber, "private long startCapacityForRecipe(final String recipeId) {",
                     "private long[] inFlightBreakdownForRecipe(")
check("锚点: -2 的分支写在 -1 分支之前（否则抖动会从保守下限漏回来）",
      capacity_body.index("if (remaining == NO_ORDER_READABLE) {")
      < capacity_body.index("if (remaining < 0L) {"))
has(chamber, "allowed = (long) Math.ceil(remaining / (double) p);",
    "锚点: 非必得配方的 ceil(R/p) 放大逐字未改（绝没有借这次修复收紧概率产线）")


# ============================================================ ③ 等价模型 + 反例表
section("③) 反例表：用日志原文的数对拍「旧口径（三分量）vs 新口径（四分量）」")

NO_ORDER = -2


def capacity(remaining, guaranteed, p, inflight):
    """复刻 startCapacityForRecipe（本轮 2026-10-06 第 27 轮修正后的源码，逐字对应）。"""
    if remaining == NO_ORDER:
        return 0
    if remaining < 0:
        return 1 if inflight <= 0 else 0
    allowed = remaining
    if not guaranteed and isinstance(p, float) and 0.0 < p < 1.0:
        allowed = int(math.ceil(remaining / p))
    return max(0, allowed - inflight)


def cap_old(ordered, delivered, reg, store, pool):
    """旧口径：在制件 = ①+②+③（成品在「做完→认领」窗口里谁都不认它）。"""
    return capacity(max(0, ordered - delivered), True, 1.0, reg + store + pool)


def cap_new(ordered, delivered, reg, store, pool, settled):
    """新口径：在制件 = ①+②+③+④（④ = 未交付成品件）。"""
    return capacity(max(0, ordered - delivered), True, 1.0, reg + store + pool + settled)


# 每一行都来自 run/logs/latest.log 的 capacity 行原文（order=10 条轨道那一次订单）。
#   (时刻, 日志原文读数 R/D/reg/store/pool, 该时刻真实已经开出去的件数, ④ 能否数到那一件)
CASES = [
    ("11:56:16.711 峰值：10 件全开出去", 0, 2, 0, 8, 10, 0),
    ("11:56:38.111 首件成品刚被收回本仓", 0, 0, 1, 8, 10, 1),   # 10 件里 9 件可见 + 1 件成品
    ("11:56:42.009【多开那一刻】", 2, 0, 1, 6, 10, 1),
    ("11:56:46.215【同一形状再来一次】", 4, 0, 1, 4, 10, 1),
    ("11:56:50.119 正常推进（本来就不该开）", 6, 0, 1, 3, 11, 1),
    ("11:56:54.315 最后一件在制", 8, 0, 1, 1, 11, 1),
]
print("  %-42s %-8s %-8s %-8s" % ("现场（订单 10 条轨道）", "旧 cap", "新 cap", "真实开件"))
culprits = []
for label, delivered, reg, store, pool, started_true, settled in CASES:
    old = cap_old(10, delivered, reg, store, pool)
    new = cap_new(10, delivered, reg, store, pool, settled)
    print("  %-42s %-8s %-8s %-8s" % (label, old, new, started_true))
    if old > 0 and started_true >= 10:
        culprits.append((label, old, new))

check("反例①【本轮要修的那一格】11:56:42.009：真实开件数已达订单量 10，旧口径却给 cap=1 "
      "（日志紧随其后 11:56:42.210 就真的拉了第 11 份石头台阶）；新口径（+④ 那件刚被收回本仓的成品）给 0",
      cap_old(10, 2, 0, 1, 6) == 1 and cap_new(10, 2, 0, 1, 6, 1) == 0)
check("反例② 11:56:46.215：同一形状（成品又被收回本仓）旧口径 cap=1、新口径 0",
      cap_old(10, 4, 0, 1, 4) == 1 and cap_new(10, 4, 0, 1, 4, 1) == 0)
check("反例③ 11:56:38.111：旧口径 cap=1、新口径 0（该件成品压在工位上时 ④ 也能数到）",
      cap_old(10, 0, 0, 1, 8) == 1 and cap_new(10, 0, 0, 1, 8, 1) == 0)
check("反例④ 所有「真实开件 ≥ 订单量」的行，新口径必须一律 0（旧口径共 %d 行给了 >0）"
      % len(culprits),
      all(new == 0 for _, _, new in culprits) and len(culprits) >= 2)
check("反例⑤ 正常开工侧逐字未变：刚下单（D=0、无在制）时旧新都放满 10 件",
      cap_old(10, 0, 0, 0, 0) == 10 and cap_new(10, 0, 0, 0, 0, 0) == 10)
check("反例⑥ 已交付满（D=10）⇒ 旧新都是 0（上一轮那处修复没被推翻）",
      cap_old(10, 10, 0, 0, 0) == 0 and cap_new(10, 10, 0, 0, 0, 0) == 0)
check("反例⑦ 非必得（p=0.8）放大逐字未变：订单 10、在制 0 ⇒ 13 个名额；在制 3 ⇒ 10 个名额",
      capacity(10, False, 0.8, 0) == 13 and capacity(10, False, 0.8, 3) == 10
      and capacity(64, False, 0.8, 0) == 80 and capacity(1, False, 0.8, 0) == 2)

section("④) 反例表：根因 B —— 「没有需求」绝不放行、「判不出来」仍然保守放 1 件")

B_CASES = [
    ("11:57:34.009 任务一条不剩（门控已关）", NO_ORDER, 0, 0),
    ("11:56:36.209 同上（坚固板那一次）", NO_ORDER, 0, 0),
    ("11:54:43.108 同上（坚固板第一次）", NO_ORDER, 0, 0),
    ("真判不出来（无网络 / 配方解析不出来）、无在制", -1, 0, 1),
    ("真判不出来、已有在制", -1, 2, 0),
    ("已交付满 = 0", 0, 0, 0),
    ("还差 1 件、无在制", 1, 0, 1),
]
for label, remaining, inflight, expect in B_CASES:
    got = capacity(remaining, True, 1.0, inflight)
    check("B: %s（remaining=%d、inFlight=%d）⇒ cap=%d" % (label, remaining, inflight, expect),
          got == expect, "got=%d" % got)


# ============================================================ ⑤ 流水推演
section("⑤) 流水推演：成品在「做完 → 被认领」之间有 k tick 不可见时，开件总量是否 ≤ 订单量")


def total_started(ordered, capacity_fn, invis=3, work=3, ticks=400):
    """极简流水：每 tick 至多「做完并交付」1 件（与 round26 的模型同一口径）。

    * 一件从开工到交付需要 `work + invis` 个 tick；
    * 其中最后 `invis` 个 tick 是**本轮实测的那个窗口**：件已经做完（成了成品），
      但还没被 RS 认领 —— 旧口径下它既不算在制、也没进 delivered；
    * `capacity_fn(delivered, visible, invisible)` 返回这一 tick 还能开几件（> 0 就开 1 件）。
    """
    started = 0
    delivered = 0
    pipeline = []          # 每一件「还差几个 tick 交付」
    for _ in range(ticks):
        pipeline = [left - 1 for left in pipeline]
        if pipeline and pipeline[0] <= 0:      # 每 tick 至多交付 1 件（保守）
            pipeline.pop(0)
            delivered += 1
        if delivered >= ordered:
            break
        invisible = sum(1 for left in pipeline if left <= invis)
        visible = len(pipeline) - invisible
        if capacity_fn(ordered, delivered, visible, invisible) > 0:
            started += 1
            pipeline.append(work + invis)
    return started, delivered, len(pipeline)


def cap_ignoring_invisible(ordered, delivered, visible, invisible):
    """旧口径：在制件只看 ①②③ —— 不可见的那件成品没人认。"""
    return capacity(max(0, ordered - delivered), True, 1.0, visible)


def cap_counting_invisible(ordered, delivered, visible, invisible):
    """新口径：④ 把「做完但还没被认领」的成品也算进去。"""
    return capacity(max(0, ordered - delivered), True, 1.0, visible + invisible)


started_old, deliv_old, backlog_old = total_started(10, cap_ignoring_invisible)
started_new, deliv_new, backlog_new = total_started(10, cap_counting_invisible)
print()
print("  推演（订单 10、成品交付前有 3 tick 不可见）")
print("  %-34s %-10s %-10s %-10s" % ("口径", "开件总量", "交付", "残留"))
print("  %-34s %-10s %-10s %-10s" % ("旧（盲区不计）", started_old, deliv_old, backlog_old))
print("  %-34s %-10s %-10s %-10s" % ("新（④ 计入成品）", started_new, deliv_new, backlog_new))

check("反例⑧ 旧口径在同一流水下开件总量 > 订单量（= %d > 10，实测的「还剩 1 件中间产物」就是这条路径）"
      % started_old, started_old > 10)
check("反例⑨ 新口径下「开件总量 ≤ 订单量」：%d ≤ 10，且恒等式 开件 = 交付 + 残留 成立"
      % started_new, started_new <= 10 and started_new == deliv_new + backlog_new)

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
