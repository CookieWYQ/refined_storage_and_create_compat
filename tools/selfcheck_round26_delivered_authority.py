# -*- coding: utf-8 -*-
"""第 26 轮自检：**「已交付量」必须对 root EXTERNAL 样板成立**（本轮根因修复）。

用法：python tools/selfcheck_round26_delivered_authority.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要有它（本轮同样无法在本地把游戏跑起来验证）：

  实测症状（用户）：订单 坚固板 10 + 列车轨道 10，实际**开件 19 + 14**，
  多出的 **9 件轨道半成品 + 3 件坚固板半成品**堆在中间产物缓存池里，而**交付数恰好 10/10**。
  ⇒ 不是少交付，是**多开新件**。

  根因（只读诊断，证据链闭合）：执行舱的「订单剩余量」= 订单量 − 已交付量，而「已交付量」取的是
  RS 任务状态里「目标资源那一项」的 `stored + crafting`：
    * `stored` 只由任务自己的 `internalStorage` 填（RS 2.0.0 `TaskImpl.java:162-164`），
      而 root EXTERNAL 样板的 `beforeInsert` 直接 `return 0`
      （`ExternalTaskPattern.java:96-102`）、认领只发生在 `afterInsert`
      （同文件 104-110 → `trySatisfy` 112-125），**只减 expectedOutputs、从不写 internalStorage**；
    * `crafting` 只由 `InternalTaskPattern#appendStatus` 填（`InternalTaskPattern.java:74-81`）；
    * `percentageCompleted` 对外部样板同样是 0（外部样板的权重是剩余派发次数，
      派发一空权重即为 0：`ExternalTaskPattern.java:174-177` + `TaskImpl.java:151-165`）。
  ⇒ 对本模组的样板，「已交付量」**恒为 0** ⇒ 每交付一件、在制数掉 1 ⇒ 名额立刻回到 1 ⇒ 又开一件。

  修法（本轮选定）：已交付量改用 RS **唯一被正确维护**的量 —— `ExternalTaskPattern#iterationsReceived`
  （字段 `:28`，每次认领都更新 `:127-141`，随任务快照读写 `:58` / `:252`）。
  它不暴露在任何公开 API 上，因此由 `RsccTaskDeliveredMixin` 注入 `TaskImpl#getStatus` 的 RETURN
  + 两个 `@Accessor`（`AbstractTaskPattern.root/pattern`、`ExternalTaskPattern.iterationsReceived`）
  只读镜像到 `AssemblyWatchdog`，执行舱三处与看门狗一处**全部**改调
  `AssemblyWatchdog.deliveredAmount(status)`（对 INTERNAL 样板内部仍退回 stored + crafting）。

本脚本做四件事：
  ① 源码锚点：权威读数只有一份实现，且**旧口径（恒为 0 的那份）已经从执行舱里彻底消失**；
  ② 源码锚点：4 处 `Math.max` 下限逐个给出处理结论（改 0 / 保留 1 并说明为什么保留）；
  ③ 源码锚点：非必得配方的动态放大（`ceil(R/p)`）**一字未改**，绝没有被这次修复收紧；
  ④ 等价模型 + 反例表：把「旧口径 delivered ≡ 0」与「新口径」在**用户现场的那组数**上对拍，
     并证明新口径下「开件总量 ≤ 订单量（非必得 ≤ ceil(R/p)）」。

第 27 轮补充（2026-10-06，本轮实测取证后的口径细化，见 tools/selfcheck_round27_parked_product_capacity.py）：
  `remaining < 0` 这一支只允许被「真判不出来」命中；执行舱新增了 `NO_ORDER_READABLE = -2`
  （任务表读得到、但没有需求）单独走 `cap = 0`。本脚本的 `start_capacity` 复刻与锚点已同步更新
  （旧复刻把 `-1` 与 `-2` 混为一谈 ⇒ 会固化「没有订单也放 1 件」这个被日志证实的错误行为）。
"""
import io
import json
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
WATCHDOG = os.path.join(SRC, "support", "AssemblyWatchdog.java")
MIXIN = os.path.join(SRC, "mixin", "RsccTaskDeliveredMixin.java")
ACCESSOR_ABSTRACT = os.path.join(SRC, "mixin", "accessor", "RsccTaskPatternAccessor.java")
ACCESSOR_EXTERNAL = os.path.join(SRC, "mixin", "accessor", "RsccExternalTaskPatternAccessor.java")
MIXIN_CONFIG = os.path.join(ROOT, "src", "main", "resources", "rs_create_compat.mixins.json")

FAILURES = []
CHECKS = [0]


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def body(text, start, end):
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


def has(text, needle, name):
    check(name, needle in text, "missing: %s" % needle.replace("\n", " ")[:110])


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


chamber = read(CHAMBER)
watchdog = read(WATCHDOG)
mixin = read(MIXIN)
accessor_abstract = read(ACCESSOR_ABSTRACT)
accessor_external = read(ACCESSOR_EXTERNAL)
mixin_config = read(MIXIN_CONFIG)

# ============================================================ ① 权威读数只有一份
section("①) 源码锚点：已交付量只有一份实现（RS 权威读数），旧的恒为 0 的口径已彻底移除")

has(watchdog, "public static long deliveredAmount(final TaskStatus status) {",
    "锚点: 已交付量唯一实现 = AssemblyWatchdog.deliveredAmount（执行舱三处 + 看门狗一处全调它）")
has(watchdog, "publishedTaskDelivered(status.info().id().id(), status.info().resource())",
    "锚点: 它取「宿主 uuid + 目标资源 → RS 权威交付读数」的只读镜像")
has(watchdog, "public static void publishTaskDelivered(",
    "锚点: 镜像的写入端（只由注入调用）")
has(watchdog, "byResource.merge(resource, delivered, Math::max);",
    "锚点: 镜像按 (任务, 资源) 单调取大（交付量物理上不可能倒退）")

# 注入端：RS 唯一被正确维护的量 iterationsReceived
has(mixin, '@Inject(method = "getStatus", at = @At("RETURN"))',
    "锚点: 注入点是 TaskImpl#getStatus 的 RETURN（RS 重建任务状态的唯一入口 ⇒ 镜像天然保鲜）")
has(mixin, "@Shadow\n    @Final\n    private Map<?, ?> patterns;",
    "锚点: 用 @Shadow 取 TaskImpl 自身声明的 patterns（按本工程硬规则：不得 shadow 继承成员）")
has(mixin, "external.rscc$receivedIterations()",
    "锚点: 读的就是 ExternalTaskPattern#iterationsReceived（RS 唯一被正确维护的交付量）")
has(mixin, "meta.rscc$isRootPattern()",
    "锚点: 只镜像 root 样板（订单的直接产出），子样板不发布、退回既有口径")
has(mixin, "AssemblyWatchdog.publishTaskDelivered(status.info().id().id(), output.resource(),",
    "锚点: 按 (任务 uuid, 产出资源) 发布")

# 访问器：字段必须挂在「声明类」上（本工程硬规则，见 tools/verify_mixin_shadows.py）
has(accessor_abstract, '@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.task.AbstractTaskPattern")',
    "锚点: root / pattern 的 @Accessor 挂在声明类 AbstractTaskPattern 上（包私有 ⇒ 只能用 targets 字符串）")
has(accessor_abstract, '@Accessor("root")',
    "锚点: root 访问器（判断哪一个是订单的直接产出样板）")
has(accessor_external, '@Mixin(targets = "com.refinedmods.refinedstorage.api.autocrafting.task.ExternalTaskPattern")',
    "锚点: iterationsReceived 的 @Accessor 挂在声明类 ExternalTaskPattern 上")
has(accessor_external, '@Accessor("iterationsReceived")',
    "锚点: 交付量的来源字段（RS 2.0.0 ExternalTaskPattern.java:28）")

registered = json.loads(mixin_config)["mixins"]
check("锚点: 三个新 mixin 都已在 rs_create_compat.mixins.json 注册（未注册 = 注入不会生效，且不报错）",
      "RsccTaskDeliveredMixin" in registered
      and "accessor.RsccTaskPatternAccessor" in registered
      and "accessor.RsccExternalTaskPatternAccessor" in registered)

# ---- 旧口径必须已经消失（否则 delivered 仍会恒为 0） ----
OLD_DELIVERED = "delivered += Math.max(0L, item.stored()) + Math.max(0L, item.crafting());"
check("反例检测器: 执行舱里再不存在旧的「stored + crafting」交付量循环（那份对本模组样板恒为 0）",
      OLD_DELIVERED not in chamber)
check("锚点: 执行舱三处（整仓份额 / 按配方剩余量 / 收尾判满）全部改调 AssemblyWatchdog.deliveredAmount",
      chamber.count("AssemblyWatchdog.deliveredAmount(status)") >= 3,
      "count=%d" % chamber.count("AssemblyWatchdog.deliveredAmount(status)"))

# ============================================================ ② 4 处下限逐个结论
section("②) 源码锚点：4 处 Math.max 下限逐个给出处理结论")

check("下限①（整仓份额 computeRemainingOrderUnits）: 剩余量下限 1 → 0（语义上必须能表示「已交付满」）；"
      "旧式子只允许作为注释里的说明存在，绝不允许再出现在代码语句里",
      "best = Math.max(best, Math.max(0L, ordered - delivered));" in chamber
      and "Math.max(best, Math.max(1L, ordered - delivered))" not in chamber)
check("下限②（按配方剩余量 remainingOrderUnitsFor）: 同上 → 0",
      chamber.count("Math.max(0L, ordered - delivered)") == 2,
      "count=%d" % chamber.count("Math.max(0L, ordered - delivered)"))

capacity_body = body(chamber, "private long startCapacityForRecipe(final String recipeId) {",
                     "private long[] inFlightBreakdownForRecipe(")
check("下限③（名额分支）: 判据 `remaining <= 0L` → `remaining < 0L`："
      "只有「判不出来」才走保守下限 1，「已交付满(0)」⇒ 一件都不许再开",
      "if (remaining < 0L) {" in capacity_body
      and "if (remaining <= 0L) {" not in capacity_body
      and "capacity = inFlight <= 0L ? 1L : 0L;" in capacity_body)
check("下限③的反面必须仍被保住（不许把「判不出来就断供」的老回归带回来）: "
      "remaining < 0 时无在制 ⇒ 仍允许 1 件",
      "if (remaining < 0L) {" in capacity_body
      and capacity_body.index("capacity = inFlight <= 0L ? 1L : 0L;")
      > capacity_body.index("if (remaining < 0L) {"))
check("下限③b（2026-10-06 第 27 轮修正）: `remaining < 0` 这一支只允许被「真判不出来」命中 —— "
      "「读得到、但没有需求」(NO_ORDER_READABLE = -2) 必须单独走 `cap = 0`，"
      "且该分支写在 `remaining < 0L` 之前（否则老抖动会从「保守放 1 件」漏回来）",
      "if (remaining == NO_ORDER_READABLE) {" in capacity_body
      and capacity_body.index("if (remaining == NO_ORDER_READABLE) {")
      < capacity_body.index("if (remaining < 0L) {")
      and "private static final long NO_ORDER_READABLE = -2L;" in chamber
      and "return NO_ORDER_READABLE;" in chamber)

check("下限④（订单量下界 Math.max(1L, amount)）: 有意保留 —— RS 自己保证订单量 > 0"
      "（AutocraftingNetworkComponentImpl 里 ResourceAmount.validate 校验），"
      "它不是「剩余量下限」，不会让 delivered 被抹平",
      "final long ordered = Math.max(1L, status.info().amount());" in chamber)
check("下限④b（看门狗 remaining）: 外层 max(0) + 订单量下界（同一个有意的写法），"
      "delivered 换成权威读数后它的「刚好够」判定才可能成立",
      "final long remaining = Math.max(0L, Math.max(1L, status.info().amount()) - delivered);" in watchdog)

# ============================================================ ③ 非必得放大不能被收紧
section("③) 源码锚点：非必得配方（精密构件）的动态放大一字未改")

has(chamber, "allowed = (long) Math.ceil(remaining / (double) p);",
    "锚点: 非必得放大 = ceil(R / p)（推导：X = 第 R 次成功的投料次数，E[X] = R/p）")
has(chamber, "if (!guaranteedResult(recipeId)) {",
    "锚点: 只有非必得配方才放大（必得严格 R）")
has(chamber, "private boolean guaranteedResult(final String recipeId) {",
    "锚点: 必得判据仍在（Create 轮盘口径，判不出来一律按非必得 ⇒ 绝不误卡概率产线）")
has(chamber, "private float mainResultChance(final String recipeId) {",
    "锚点: 主产物归一概率 p 的唯一天平仍在")
has(chamber, "capacity = allowed - inFlight;",
    "锚点: 名额仍然 = 允许在制数 − 跨舱在制件数（放大口径未换、未收紧）")


def start_capacity(remaining, guaranteed, p, inflight):
    """复刻 startCapacityForRecipe（2026-10-06 第 26/27 轮修正后的源码，逐字对应）。

    第 27 轮新增：`remaining == -2`（NO_ORDER_READABLE = 任务表读得到、但没有需求）⇒ 0 件。
    这与 `-1`（判不出来）必须分开：`-1` 仍走「保守放 1 件」，绝不把老回归带回来。
    """
    if remaining == -2:
        return 0
    if remaining < 0:
        return 1 if inflight <= 0 else 0
    allowed = remaining
    if not guaranteed and isinstance(p, float) and 0.0 < p < 1.0:
        allowed = int(math.ceil(remaining / p))
    return max(0, allowed - inflight)


check("推演① 必得：订单 10、已交付 4、在制 2 ⇒ 名额 4（还差 6 − 2）",
      start_capacity(10 - 4, True, 1.0, 2) == 4)
check("推演② 非必得（p=0.8）：订单 64、已交付 0、在制 0 ⇒ 名额 80（动态放大，与修复前逐字一致）",
      start_capacity(64, False, 0.8, 0) == 80)
check("推演③ 非必得（p=0.8）：订单 1、已交付 0、在制 0 ⇒ 名额 2（下单 1 个精密构件仍允许 2 件在制）",
      start_capacity(1, False, 0.8, 0) == 2)
check("推演④ 已交付满（remaining=0，必有 / 非必得都一样）⇒ 名额 0（一件都不许再开）",
      start_capacity(0, True, 1.0, 0) == 0 and start_capacity(0, False, 0.8, 0) == 0)
check("推演⑤ 判不出来（remaining=-1）⇒ 保守下限仍在（无在制 1 件 / 有在制 0 件）—— 老回归没有带回来",
      start_capacity(-1, True, 1.0, 0) == 1 and start_capacity(-1, True, 1.0, 1) == 0)
check("推演⑥【第 27 轮】读得到、但没有需求（remaining=NO_ORDER_READABLE=-2）⇒ 0 件（不是保守的 1 件）",
      start_capacity(-2, True, 1.0, 0) == 0 and start_capacity(-2, True, 1.0, 3) == 0)

# ============================================================ ④ 反例表：旧口径 vs 新口径
section("④) 反例表：把「旧口径 delivered ≡ 0」与「新口径（RS 权威交付量）」在用户现场的数上对拍")


def capacity_new(ordered, delivered, inflight):
    """新口径：remaining = 订单量 − 已交付量（RS 权威读数），再减去跨舱在制件数。"""
    return start_capacity(max(0, ordered - delivered), True, 1.0, inflight)


def capacity_old(ordered, delivered, inflight):
    """旧口径：delivered 恒为 0（stored + crafting 对本模组 root EXTERNAL 样板恒为 0），
    且剩余量下限被 `Math.max(1L, …)` 钉在 1。"""
    remaining = max(1, ordered - 0)
    if remaining <= 0:
        return 1 if inflight <= 0 else 0
    return max(0, remaining - inflight)


print("  %-56s %-8s %-8s" % ("现场（订单 / 已交付 / 在制）", "旧口径", "新口径"))
CASES = [
    # 用户现场（快照口径）：订单 10、已交付 10、缓存池压着 9 件半成品
    ("订单10 已交付10 在制9（= 实测「开件 19 = 10 + 9」的那一刻）", 10, 10, 9),
    ("订单10 已交付10 在制0（管道已清空）", 10, 10, 0),
    ("订单10 已交付5 在制3", 10, 5, 3),
    ("订单10 已交付0 在制0（刚下单：两种口径必须一致）", 10, 0, 0),
    ("订单10 已交付9 在制1（最后一件在制）", 10, 9, 1),
]
rows = []
for label, ordered, delivered, inflight in CASES:
    old = capacity_old(ordered, delivered, inflight)
    new = capacity_new(ordered, delivered, inflight)
    rows.append((label, ordered, delivered, inflight, old, new))
    print("  %-56s %-8s %-8s" % (label, old, new))

check("反例①【本轮要修的那一格】订单10 / 已交付10 / 在制9：旧口径仍给 1 个名额（⇒ 又开一件 = 实测的第 20 件），"
      "新口径给 0（⇒ 停）",
      capacity_old(10, 10, 9) == 1 and capacity_new(10, 10, 9) == 0)
check("反例② 管道清空且已交付满：旧口径仍给 10（把已交付忘得一干二净），新口径 0",
      capacity_old(10, 10, 0) == 10 and capacity_new(10, 10, 0) == 0)
check("反例③ 刚下单（已交付 0）时两种口径完全一致 —— 修复绝不会改变「正常开工」这一侧",
      all(capacity_old(10, 0, 0) == capacity_new(10, 0, 0) for _ in [0]))


def total_started(ordered, capacity_fn, delay=9, ticks=120):
    """极简流水模型：**先交付，再按名额开工**。

    * `delay` = 一件从开工到交付要几个 tick（= 序列装配的固有耗时；期间它就是「在制件」）；
      `delay=9` 对应实测现场「缓存池里压着 9 件半成品、同时已交付 10 件」那一刻；
    * 每 tick 至多交付 1 件（多台机器时更快，但结论不变：只要「交付滞后于开工」，
      旧口径就会用「订单量 − 在制」反复放行新件）；
    * `capacity_fn` 返回这一 tick 还能开几件（> 0 就开 1 件）。
    """
    started = 0
    delivered = 0
    pipeline = []
    for _ in range(ticks):
        if len(pipeline) >= delay:          # 最早开工的那一件做完并交付
            pipeline.pop(0)
            delivered += 1
        if delivered >= ordered:            # 订单交付满 ⇒ RS 任务完成、从状态列表消失
            break
        if capacity_fn(ordered, delivered, len(pipeline)) > 0:
            started += 1
            pipeline.append(1)
    return started, delivered, len(pipeline)


started_old, deliv_old, backlog_old = total_started(10, capacity_old)
started_new, deliv_new, backlog_new = total_started(10, capacity_new)
print()
print("  流水推演（开工到交付 delay=9 tick、每 tick 至多交付 1 件；订单 10）")
print("  %-24s %-10s %-10s %-10s %-10s" % ("口径", "开件总量", "交付", "残留半成品", "上界"))
print("  %-24s %-10s %-10s %-10s %-10s" % ("旧（delivered≡0）", started_old, deliv_old, backlog_old,
                                          "订单量+残留"))
print("  %-24s %-10s %-10s %-10s %-10s" % ("新（RS 权威读数）", started_new, deliv_new, backlog_new,
                                          "订单量"))

check("反例④ 新口径下「开件总量 ≤ 订单量」：%d 件（= 已交付 %d + 残留 %d，恒等式 started = delivered + inflight 成立）"
      % (started_new, deliv_new, backlog_new),
      started_new <= 10 and started_new == deliv_new + backlog_new)
check("反例⑤ 旧口径在同一流水下「每交付一件就又开一件」⇒ 开件总量 = 已交付 + 残留 = %d + %d = %d > 订单量 10"
      "（用户实测的 19 正是这一格：已交付 10 + 缓存池 9 件半成品）；新口径 %d ≤ 10"
      % (deliv_old, backlog_old, started_old, started_new),
      started_old > 10 and started_old == deliv_old + backlog_old and started_new <= 10)

# 非必得：开件上界 = ceil(R/p)，绝不被这次修复收紧
budget_800 = start_capacity(10, False, 0.8, 0)
check("反例⑥ 非必得配方未被收紧：订单 10、p=0.8、在制 0 ⇒ 仍有 13 个名额（= ceil(10/0.8)），"
      "且随交付推进自然收敛（交付 10 后 remaining=0 ⇒ 0）",
      budget_800 == 13 and start_capacity(0, False, 0.8, 0) == 0)

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
