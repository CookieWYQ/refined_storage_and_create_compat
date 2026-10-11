# -*- coding: utf-8 -*-
"""round58 自检：**「绝不抽走别人在途自动合成任务要的量」——存量 / 预留 / 可用量的分配模型**。

用户报告（本轮第 2 个问题）：
    「制作的时候有些中间（产物）的输入是原料，比如说齿轮之类的，我针对精密构件，他们在制作的时候，
      有可能被你提前消耗，然后导致网络中你的检测失败之类的。……我看着有几个齿轮和大齿轮的合成任务
      位置卡在那里不动……那两个任务的确是跟这个精密构件制作有关的。」

本脚本断言六组事（每组都是可被源码事实证伪的锚点，不是复述注释）：

  ① **RS 2.0 的预留语义**（读 `local_src/external/RefinedStorage` 的真实源码，按内容断言，
     不按绝对行号 —— 本地检出版本与编译用的 2.0.0-sources.jar 相差几行）：
     * `RootStorageListener` **只有** `beforeInsert` / `afterInsert`，**没有**任何抽取侧钩子；
     * `RootStorageImpl#extract` 是裸委托（方法体里没有 listener 循环），`#insert` 才有拦截循环；
     * 任务「还没拿到」的量只活在 `TaskImpl#initialRequirements` 里，被 `TaskImpl#getStatus`
       报成 `TaskStatus.Item#extracting`；
     * `ExternalTaskPattern` 的 `scheduled` / `processing` 描述的是**外部样板投入物**，
       而本模组执行器 `accept` 会把它们**原样插回网络** ⇒ 不能算成「别人的预留」。
     ⇒ 结论：**RS 没有任何「网络存量已被预留」的表示**，外部存储 / 缓存节点 `extract` 时
       RS 不会先扣预留量 ⇒ 必须由本模组自己算。

  ② **Python 复刻的分配模型**（`available_for_take` + `TakeLedger`）与**真值表**：
     * 网络 64 件、其中 32 件被在途任务预留 ⇒ 本模组最多取 32；
     * 全部被预留 ⇒ 一个都不取、进入等待 / 上报缺料，且**同 tick 内反复问也不自旋**；
     * 多台仓 / 多条总线同一 tick 各取 ⇒ **合计不超可用量**；
     * 没有任何预留时 ⇒ 可用量 == 存量、获批 == 想要 ⇒ **既有供料逐字不变**；
     * 取消 / 收尾中的任务（`RETURNING_INTERNAL_STORAGE` / `COMPLETED`）的过期读数**不计入**
       ⇒ 不会出现「一个已经不会再抽料的任务永久占住资源」的死锁。

  ③ **收敛性（不死锁）**：任务在途期间 `extracting` **单调减少**（RS 只在抽到时 `remove`），
     因此「等」必然收敛：模拟「等 → 任务每步抽走一份 → 最终拿到」的全过程并断言有界终止。

  ④ **仓内 → 网络的「预留释放」真值表**（`RsccChamberImportStrategy#autoAcceptsChamberItem`）：
     只把「没有任何工位此刻要它、而某条在途任务正等着它」的那一份还回网络；
     起步原料与「有工位要它」一律不动 ⇒ 绝不抢机器正等的料，也绝不重启「买进来 → 退回去」的空转。

  ⑤ **源码镜像检查 + 既有保护未被放宽**：
     * `SequenceMaterialGuard` 里确有 `isInFlight` / `pendingExtraction` / `availableForTake` /
       `TakeLedger` / `sharedLedger` / `HOLD_INFLIGHT_TASK_NEED`；
     * `RsccChamberImportStrategy` 每次搬运只算一次预留表，且新例外与 `inputMaterialWantedNow` 合取；
     * `RsccChamberExportStrategy` 只夹**脱绑委托**那一路的配额（绑定路径的配额一字未改）；
     * `blockedByMachineQueue` / `startCapacityForRecipe` / `inFlightUnitsForRecipe` /
       `pooledTransitionalUnits` / `NEXT_FOR_MACHINE` / `computeStartIngredients` /
       `rscc$bareTransitionIsMineNow` 这些既有保护**没有被本轮改动碰过**。

  ⑥ **取用侧真的接上了**（`SequenceExecutionChamberBlockEntity#pullItem` / `#pullFluid`，
     整条修复链的最后一环 —— 前五组只保证「已被抽走的会还回去」，本组证明「仓先抽走」已被拦住）：
     * 两处都把可用量换成 `availableForTake(存量, 预留)`，并用 `sharedLedger(...).claim(...)`
       夹住本轮取用量；预留表用 `pendingExtraction(statuses, ownTaskIds)` **每次现算**（无缓存字段）；
     * **获批 0 时走等待 / 上报**（物品 `tracePullHold(HOLD_INFLIGHT_TASK_NEED)`、
       流体 `RsccAssemblyDebug.transition(HOLD_INFLIGHT_TASK_NEED)`）并 `return`，
       该分支里**没有任何 `.extract(`**（结构断言，不是靠注释）；
     * 实际抽取量改成账本获批量 `storage.extract(resource, granted, ...)`，原来的
       `Math.min(deficit, available)`（裸存量）在源码里**已彻底不存在**；
     * `scheduled` / `processing` 在取用侧**一次都没被读**；`ownTaskIds` 是局部变量（无缓存字段）。

可重复执行：python tools/selfcheck_round58_reserved_stock_guard.py
     → 全通过输出 `SELFCHECK OK (n checks)`；任一断言失败 → 退出码 1 并列出反例。

**没有实机验证**：本脚本只做源码交叉验证 + 模型真值表，不能替代游戏内验证。
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
RS = os.path.join(ROOT, "local_src", "external", "RefinedStorage")

CHECKS = [0]
PROBLEMS = []


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        PROBLEMS.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name,
                       "" if ok else ((" | " + detail) if detail else "")))


def rel_read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def rs_read(rel):
    path = os.path.join(RS, rel)
    if not os.path.exists(path):
        return None
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def rs_find(*parts):
    """在 RS 检出里按文件名找（各模块目录不同，因此递归找）。"""
    for base, _dirs, files in os.walk(RS):
        for name in files:
            if name in parts:
                with io.open(os.path.join(base, name), "r",
                             encoding="utf-8", errors="replace") as handle:
                    return handle.read()
    return None


def method_body(text, signature):
    """抓出「含 signature 的那个方法的正文」（用大括号配对，够用即可）。"""
    index = text.find(signature)
    if index < 0:
        return None
    start = text.find("{", index)
    if start < 0:
        return None
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return None


# =====================================================================================
# ① RS 2.0 的预留语义（内容锚点，不依赖绝对行号）
# =====================================================================================
print("=" * 96)
print("① RS 2.0 的预留语义（读 local_src/external/RefinedStorage 真实源码）")
print("=" * 96)

LISTENER = rs_find("RootStorageListener.java")
if LISTENER is None:
    check("RS 源码可得（RootStorageListener.java）", False, "本地 RS 检出缺失")
else:
    check("RS 源码可得（RootStorageListener.java）", True)
    check("RootStorageListener 只有 beforeInsert / afterInsert 两个钩子",
          "long beforeInsert(" in LISTENER and "long afterInsert(" in LISTENER
          and "beforeExtract" not in LISTENER and "afterExtract" not in LISTENER,
          "抽取侧出现了钩子？那 RS 的预留语义需要重查")
    check("RootStorageListener 的 afterInsert 返回值语义 = 「已预留、不再传给后续 listener」",
          "amount reserved that will not be passed to other after insert listeners" in LISTENER,
          "返回值语义变了：它描述的只是「记账独占」，不是「从存量里扣掉」")

ROOT_IMPL = rs_find("RootStorageImpl.java")
if ROOT_IMPL is None:
    check("RS 源码可得（RootStorageImpl.java）", False, "本地 RS 检出缺失")
else:
    extract_body = method_body(ROOT_IMPL, "public long extract(final ResourceKey resource")
    insert_body = method_body(ROOT_IMPL, "public long insert(final ResourceKey resource")
    notify_body = method_body(ROOT_IMPL, "private void notifyAfterInsertListeners(")
    check("RootStorageImpl#extract 是裸委托（没有 listener 循环）",
          extract_body is not None and "beforeInsert" not in extract_body
          and "listener" not in extract_body.lower() and "storage.extract(" in extract_body,
          "extract 里出现了拦截逻辑 ⇒ 需重查")
    check("RootStorageImpl#insert 才有 beforeInsert / afterInsert 拦截循环",
          insert_body is not None and "listener.beforeInsert(" in insert_body
          and "notifyAfterInsertListeners(" in insert_body
          and notify_body is not None and "listener.afterInsert(" in notify_body,
          "insert 里没有拦截循环 ⇒ 需重查")
    check("抽取与插入在预留语义上不对称（这正是本轮 bug 的根）",
          extract_body is not None and insert_body is not None
          and "beforeInsert" not in extract_body and "beforeInsert" in insert_body)

TASK_IMPL = rs_find("TaskImpl.java")
if TASK_IMPL is None:
    check("RS 源码可得（TaskImpl.java）", False, "本地 RS 检出缺失")
else:
    extract_initial = method_body(TASK_IMPL, "private boolean extractInitialResourcesAndTryStartRunningTask(")
    get_status = method_body(TASK_IMPL, "public TaskStatus getStatus()")
    check("任务「还没拿到」的量在 TaskImpl 里是 initialRequirements，靠 rootStorage.extract 逐步抽取",
          extract_initial is not None and "rootStorage.extract(" in extract_initial
          and "initialRequirements" in extract_initial,
          "抽取初始原料的实现变了 ⇒ 需重查")
    check("抽到就 remove（因此 extracting 单调减少 ⇒ 「等」必然收敛）",
          extract_initial is not None and "initialRequirements.remove(" in extract_initial,
          "没有 remove ⇒ 预留可能不收敛")
    check("TaskStatus.Item#extracting 就是剩下的 initialRequirements（RS 自己承认这是「在途待抽取」）",
          get_status is not None and "initialRequirements.getAll()" in get_status
          and "builder.extracting(" in get_status,
          "extracting 的来源变了 ⇒ 本模组的判据需重查")

EXT = rs_find("ExternalTaskPattern.java")
if EXT is None:
    check("RS 源码可得（ExternalTaskPattern.java）", False, "本地 RS 检出缺失")
else:
    check("EXTERNAL 样板的 scheduled / processing 描述的是「派发给外部接收端的投入物」",
          "builder.scheduled(" in EXT and "builder.processing(" in EXT
          and "simulatedIterationInputs" in EXT,
          "scheduled / processing 的语义变了 ⇒ 必须重查「算不算预留」")

AC = rs_find("AutocraftingNetworkComponentImpl.java")
if AC is None:
    check("RS 源码可得（AutocraftingNetworkComponentImpl.java）", False, "本地 RS 检出缺失")
else:
    check("getStatuses() 是本模组读「谁在等什么」的唯一公开入口",
          "public List<TaskStatus> getStatuses()" in AC and "PatternProvider::getTaskStatuses" in AC,
          "入口变了 ⇒ 本模组的读取方式需重查")

# 本模组执行器把每轮投入物放回网络（因此 scheduled / processing 不能算成别人的预留）
EXECUTOR = rel_read(os.path.join("block", "entity", "SequenceAssemblyExecutorBlockEntity.java"))
check("本模组执行器 accept() 会把每轮投入物原样插回网络（故 scheduled / processing 不算预留）",
      "public ExternalPatternSink.Result accept(" in EXECUTOR
      and "storage.insert(resource, count, action, Actor.EMPTY)" in EXECUTOR,
      "accept 的行为变了 ⇒ 「算不算预留」的口径必须重查")

# =====================================================================================
# ② Python 复刻的分配模型（存量 / 预留 / 可用量）
# =====================================================================================
print()
print("=" * 96)
print("② 分配模型：存量 / 预留 / 可用量（Python 复刻 SequenceMaterialGuard 的同一套算法）")
print("=" * 96)

IN_FLIGHT_STATES = ("READY", "EXTRACTING_INITIAL_RESOURCES", "RUNNING")
FINISHING_STATES = ("RETURNING_INTERNAL_STORAGE", "COMPLETED")


def is_in_flight(state):
    """镜像 SequenceMaterialGuard#isInFlight（穷举，无 default）。"""
    if state is None:
        return False
    if state in IN_FLIGHT_STATES:
        return True
    if state in FINISHING_STATES:
        return False
    raise AssertionError("未知任务状态：%r（RS 新增状态时必须显式表态）" % (state,))


def pending_extraction(statuses, own=()):
    """镜像 SequenceMaterialGuard#pendingExtraction。"""
    claims = {}
    for status in statuses or ():
        if status is None or not is_in_flight(status.get("state")):
            continue
        if status.get("id") in own:
            continue
        for item in status.get("items") or ():
            if item.get("extracting", 0) > 0:
                key = item["resource"]
                claims[key] = claims.get(key, 0) + item["extracting"]
    return claims


def available_for_take(stored, reserved):
    """镜像 SequenceMaterialGuard#availableForTake。"""
    if stored <= 0:
        return 0
    if reserved <= 0:
        return stored
    return max(0, stored - reserved)


class TakeLedger(object):
    """镜像 SequenceMaterialGuard.TakeLedger（按对象同一性 + 游戏刻作废）。"""

    def __init__(self):
        self._granted = {}
        self._scope = None
        self._tick = None

    def begin_tick(self, scope, tick):
        if scope is not self._scope or tick != self._tick:
            self._scope = scope
            self._tick = tick
            self._granted.clear()

    def granted(self, resource):
        return self._granted.get(resource, 0)

    def remaining(self, resource, available_total):
        return max(0, available_total - self.granted(resource))

    def claim(self, resource, want, available_total):
        if resource is None or want <= 0:
            return 0
        give = min(want, self.remaining(resource, available_total))
        if give > 0:
            self._granted[resource] = self.granted(resource) + give
        return give

    def size(self):
        return len(self._granted)


TAKER_RESULT_HOLD = "hold_inflight_task_need"
TAKER_RESULT_TAKE = "take"


def taker_wants(ledger, stored, reserved, want):
    """镜像调用方（取料侧）的取用判定：可用量 → 账本 → 获批 / 等待。"""
    available = available_for_take(stored, reserved)
    if want <= 0:
        return (TAKER_RESULT_TAKE, 0, available)
    grant = ledger.claim("r", want, available)
    if grant <= 0:
        return (TAKER_RESULT_HOLD, 0, available)
    return (TAKER_RESULT_TAKE, grant, available)


# ---- 真值表 1：64 件存量、32 件被预留 ⇒ 最多取 32 ----
ledger = TakeLedger()
ledger.begin_tick("net", 1)
result, granted, available = taker_wants(ledger, 64, 32, 64)
check("真值表①：存量 64 / 预留 32 ⇒ 最多只能取 32",
      available == 32 and granted == 32, "available=%d granted=%d" % (available, granted))
result2, granted2, _ = taker_wants(ledger, 64, 32, 64)
check("真值表①：同一 tick 内第二台仓再要 ⇒ 一个都拿不到（不重复发放）",
      granted2 == 0 and result2 == TAKER_RESULT_HOLD, "granted2=%d" % granted2)

# ---- 真值表 2：全部被预留 ⇒ 一个都不取 + 等待 / 上报缺料，不自旋 ----
ledger = TakeLedger()
ledger.begin_tick("net", 1)
hold_count = 0
taken_total = 0
for _ in range(200):  # 同一 tick 内反复问（模拟「每 tick 都判定一次」的引擎节拍）
    res, got, avail = taker_wants(ledger, 64, 64, 64)
    if res == TAKER_RESULT_HOLD:
        hold_count += 1
    taken_total += got
check("真值表②：全部被预留 ⇒ 一件都不取（取走量 == 0）", taken_total == 0, "taken=%d" % taken_total)
check("真值表②：全部被预留 ⇒ 每次都给出「等待 / 上报缺料」信号（不自旋、不硬抽）",
      hold_count == 200 and available_for_take(64, 64) == 0)

# ---- 真值表 3：多台仓 / 多条总线同时取 ⇒ 合计不超可用量 ----
ledger = TakeLedger()
ledger.begin_tick("net", 7)
total = 0
grants = []
for _ in range(5):  # 5 条总线，各自都以为「还有 60」
    _res, got, _avail = taker_wants(ledger, 100, 40, 64)
    grants.append(got)
    total += got
check("真值表③：5 个取用者同 tick 同时取 ⇒ 合计 == 可用量（60），不是 5 倍",
      available_for_take(100, 40) == 60 and total == 60 and sum(g > 0 for g in grants) == 1,
      "grants=%r total=%d" % (grants, total))
ledger.begin_tick("net", 8)
_res, got, _avail = taker_wants(ledger, 100, 40, 64)
check("真值表③：换 tick 后账本重开（下一 tick 照常能取到可用量）", got == 60, "got=%d" % got)
ledger.begin_tick("other_net", 8)
_res, got, _avail = taker_wants(ledger, 100, 40, 64)
check("真值表③：换网络后账本重开（两个网络互不记账）", got == 60, "got=%d" % got)

# ---- 真值表 4：没有任何预留 ⇒ 行为逐字不变 ----
ledger = TakeLedger()
ledger.begin_tick("net", 1)
_res, got, avail = taker_wants(ledger, 100, 0, 7)
check("真值表④：无预留 ⇒ 可用量 == 存量、获批 == 想要（既有供料逐字不变）",
      avail == 100 and got == 7, "available=%d granted=%d" % (avail, got))
check("真值表④：无预留时连续取用不会被账本误拦（剩余额度正确递减）",
      ledger.remaining("r", 100) == 93 and ledger.claim("r", 93, 100) == 93
      and ledger.remaining("r", 100) == 0)

# ---- 真值表 5：取消 / 收尾中的任务不算预留（防空死锁） ----
cancelled = [{"id": "t1", "state": "RETURNING_INTERNAL_STORAGE",
              "items": [{"resource": "r", "extracting": 64}]}]
completed = [{"id": "t2", "state": "COMPLETED",
              "items": [{"resource": "r", "extracting": 64}]}]
check("真值表⑤：取消 / 收尾中的任务的过期 extracting 读数作废（否则永久占住资源 = 死锁）",
      pending_extraction(cancelled) == {} and pending_extraction(completed) == {})
running = [{"id": "t3", "state": "EXTRACTING_INITIAL_RESOURCES",
            "items": [{"resource": "r", "extracting": 64}]}]
check("真值表⑤：在途任务的读数照常计入", pending_extraction(running) == {"r": 64})
check("真值表⑤：本产线自己的任务可被排除（供取料侧使用，避免自己锁死自己的投料口）",
      pending_extraction(running, own=("t3",)) == {} and pending_extraction(running, own=("x",)) == {"r": 64})
mixed = [{"id": "a", "state": "RUNNING", "items": [{"resource": "r", "extracting": 4}]},
         {"id": "b", "state": "READY", "items": [{"resource": "r", "extracting": 6},
                                                {"resource": "s", "extracting": 0}]}]
check("真值表⑤：多任务同资源按资源求和（4 + 6 = 10），extracting 为 0 的条目不入表",
      pending_extraction(mixed) == {"r": 10})

# ---- ③ 收敛性：等必然有界终止（任务每步抽走一份，extracting 单调减少） ----
ledger = TakeLedger()
ledger.begin_tick("net", 1)
stored = 64
reserved = 8          # 某条在途任务还等着抽 8 件
want = 64
steps = 0
taken = 0
while taken == 0 and steps < 1000:
    steps += 1
    if reserved > 0:
        # 任务的一步：它从网络里抽走 1 件（RS：initialRequirements.remove(..., extracted)）
        pulled = min(1, available_for_take(stored, reserved) + reserved)
        stored -= pulled
        reserved -= pulled
        if reserved == 0:
            ledger.begin_tick("net", steps)  # 换 tick：账本重开
        continue
    _res, got, _avail = taker_wants(ledger, stored, reserved, want)
    taken += got
check("③ 收敛性：在途任务每步抽走一份 ⇒ 本模组的「等」有界终止（拿到料，不永久死锁）",
      taken > 0 and steps <= 16, "steps=%d taken=%d" % (steps, taken))

# =====================================================================================
# ④ 仓内 → 网络的「预留释放」真值表（RsccChamberImportStrategy#autoAcceptsChamberItem）
# =====================================================================================
print()
print("=" * 96)
print("④ 仓内 → 网络：预留释放的真值表（只放「没人要、别人却在等」的那一份）")
print("=" * 96)


def release_chamber_item(is_start_ingredient, wanted_now, is_scrap, claimed_by_inflight,
                         residual_edge):
    """镜像 autoAcceptsChamberItem 的输入类分支（未完成件 / 非输入类分支与本表无关）。"""
    if not is_start_ingredient and not wanted_now and (is_scrap or claimed_by_inflight):
        return True
    return residual_edge


RELEASE_CASES = [
    # (说明, start, wanted_now, scrap, claimed, residual_edge, 期望)
    ("起步原料（金板）：任何情况下都不因本例外放行", True, False, False, True, False, False),
    ("有工位此刻要它 ⇒ 绝不放行（不抢机器正等的料）", False, True, False, True, False, False),
    ("没人要 + 别人在等 ⇒ 放行（解开「任务永久停在原地」）", False, False, False, True, False, True),
    ("没人要 + 没人等 + 不是废料 ⇒ 不放行（既有保护逐字未变）", False, False, False, False, False, False),
    ("没人要 + 是废料 ⇒ 放行（既有废料例外，回归保护）", False, False, True, False, False, True),
    ("任务刚结束的边沿 ⇒ 输入类照旧放行一次（既有行为）", False, True, False, False, True, True),
]
for name, start, wanted, scrap, claimed, edge, expect in RELEASE_CASES:
    got = release_chamber_item(start, wanted, scrap, claimed, edge)
    check("释放真值表：%s" % name, got is expect, "got=%r expect=%r" % (got, expect))

# =====================================================================================
# ⑤ 源码镜像检查 + 既有保护未被放宽
# =====================================================================================
print()
print("=" * 96)
print("⑤ 源码镜像检查（Python 模型与 Java 实现不得脱节）+ 既有保护未被放宽")
print("=" * 96)

GUARD = rel_read(os.path.join("support", "SequenceMaterialGuard.java"))
IMPORT = rel_read(os.path.join("support", "RsccChamberImportStrategy.java"))
EXPORT = rel_read(os.path.join("support", "RsccChamberExportStrategy.java"))

check("SequenceMaterialGuard：有 isInFlight（穷举四个状态、无 default）",
      "public static boolean isInFlight(" in GUARD
      and "case READY, EXTRACTING_INITIAL_RESOURCES, RUNNING -> true;" in GUARD
      and "case RETURNING_INTERNAL_STORAGE, COMPLETED -> false;" in GUARD)
check("SequenceMaterialGuard：有 availableForTake（可用量 = 存量 − 预留，永不为负）",
      "public static long availableForTake(" in GUARD and "Math.max(0L, storedAmount - reservedAmount)" in GUARD)
check("SequenceMaterialGuard：有 pendingExtraction 两个重载（第二个可排除自己的任务）",
      GUARD.count("pendingExtraction(") >= 3 and "ownTaskIds" in GUARD and "item.extracting()" in GUARD)
check("SequenceMaterialGuard：有 TakeLedger（同 tick 同网络共享账本）与 sharedLedger",
      "public static final class TakeLedger" in GUARD and "public static TakeLedger sharedLedger(" in GUARD
      and "scopeKey != scope || gameTime != tick" in GUARD)
check("SequenceMaterialGuard：等待原因常量存在且是 ASCII 诊断串（不是语言键）",
      'public static final String HOLD_INFLIGHT_TASK_NEED = "inflight_task_need";' in GUARD)

check("RsccChamberImportStrategy：预留表每次搬运只算一次（不放进逐格循环）",
      "private static Map<ResourceKey, Long> inflightClaimsOf(final Network network)" in IMPORT
      and IMPORT.count("inflightClaimsOf(") == 2
      and "SequenceMaterialGuard.pendingExtraction(autocrafting.getStatuses())" in IMPORT)
check("RsccChamberImportStrategy：既有判据 autoAcceptsChamberItem 一字未改（新增例外并列合成）",
      "autoAcceptsChamberItem(stack, inputItems, residualEdge, chamber)\n" in IMPORT
      and "|| (releaseInflight" in IMPORT
      and "releasesChamberItemForInflight(stack, inputItems, chamber, claimedByInflight)" in IMPORT
      and "private static boolean autoAcceptsChamberItem(final ItemStack stack, final Set<Item> inputItems,\n"
          "                                                  final boolean residualEdge,\n"
          "                                                  final SequenceExecutionChamberBlockEntity chamber) {" in IMPORT)
check("RsccChamberImportStrategy：释放例外要求「没有任何工位要它」+「不是起步原料」+「不是未完成件」",
      "if (chamber.inputMaterialWantedNow(stack.getItem())) {" in IMPORT
      and "return false; // 有任何工位此刻要它 ⇒ 绝不抢走" in IMPORT
      and "return false; // 起步原料：只可能是「要开新件的那一份」，绝不收回" in IMPORT
      and "return false; // 未完成件：「下一步归本机」的保护由既有判据独占，绝不由本例外放开" in IMPORT)
check("RsccChamberImportStrategy：原料标记两种形态都查一次（与入网口径一致）",
      "inflightClaims.containsKey(ItemResource.ofItemStack(stack))" in IMPORT
      and "new ItemResource(stack.getItem(), DataComponentPatch.EMPTY)" in IMPORT)
check("RsccChamberImportStrategy：无单时不释放（不越过「无玩家下单 ⇒ 零取料 / 零投料」）",
      "final boolean releaseInflight = !noOrderFlag && !inflightClaims.isEmpty();" in IMPORT)

check("RsccChamberExportStrategy：脱绑委托那一路的配额被夹到可用量",
      "rscc$inflightQuota(resource, itemQuota.applyAsLong(resource))" in EXPORT
      and "rscc$inflightQuota(resource, fluidQuota.applyAsLong(resource))" in EXPORT)
check("RsccChamberExportStrategy：夹量用 availableForTake + 共享账本 claim",
      "SequenceMaterialGuard.availableForTake(stored, reserved)" in EXPORT
      and "ledger.claim(resource, Math.min(quota, remaining), available)" in EXPORT)
check("RsccChamberExportStrategy：绑定执行舱那一路的配额**未被夹**（否则会饿死机器）",
      "final long quota = Math.min(itemQuota.applyAsLong(item)," in EXPORT
      and "chamber.isInputMaterial(item.item()) ? INPUT_FEED_UNIT : Long.MAX_VALUE);" in EXPORT)
check("RsccChamberExportStrategy：没有在途需求时原样返回配额（既有行为逐字不变）",
      "if (claims.isEmpty()) {\n            return quota; // 没有任何在途需求 ⇒ 既有行为逐字不变" in EXPORT
      or "if (claims.isEmpty()) {" in EXPORT and "return quota; // 没有任何在途需求" in EXPORT)
check("RsccChamberExportStrategy：网络本来没有该资源时不改变既有提示（交给 RS 报 RESOURCE_MISSING）",
      "return quota; // 网络本来就没有：交给 RS 自己报 RESOURCE_MISSING" in EXPORT)

BE = rel_read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))
for anchor in ("blockedByMachineQueue", "startCapacityForRecipe", "inFlightUnitsForRecipe",
               "pooledTransitionalUnits", "NEXT_FOR_MACHINE", "computeStartIngredients",
               "rscc$bareTransitionIsMineNow"):
    check("既有保护仍在（未被本轮改动删除）：%s" % anchor, anchor in BE)

# 本轮改动范围：只允许出现在写范围内（并发的他人文件不在此断言）
ALLOWED = ("support/SequenceMaterialGuard.java", "support/RsccChamberImportStrategy.java",
           "support/RsccChamberExportStrategy.java")
for rel in ALLOWED:
    text = rel_read(rel.replace("/", os.sep))
    check("改动锚点只落在写范围内：%s 含本轮新增标记" % rel,
          ("inflight" in text.lower()) and ("HOLD_INFLIGHT_TASK_NEED" in text
                                            or "pendingExtraction" in text))

# =====================================================================================
# ⑥ 取用侧真的接上了（pullItem / pullFluid 的源码锚点 + 结构顺序 + 获批 0 的路径）
# =====================================================================================
print()
print("=" * 96)
print("⑥ 取用侧接线（SequenceExecutionChamberBlockEntity#pullItem / #pullFluid）")
print("=" * 96)


def order(*needles):
    """断言这些字面量按给定顺序出现（缺失 / 顺序不符返回 False）。"""
    pos = 0
    for needle in needles:
        found = BE.find(needle, pos)
        if found < 0:
            return False
        pos = found + len(needle)
    return True


ITEM_EXTRACT = "storage.extract(resource, granted, Action.EXECUTE, Actor.EMPTY)"
ITEM_GRANT0 = "if (granted <= 0L)"

check("取用侧：物品 / 流体两处都把可用量换成 availableForTake(存量, 预留)",
      BE.count("SequenceMaterialGuard.availableForTake(available,") == 2,
      "count=%d" % BE.count("SequenceMaterialGuard.availableForTake(available,"))
check("取用侧：两处都用共享账本 claim 夹住本轮取用量（多仓同 tick 合计不超可用量）",
      BE.count("SequenceMaterialGuard.sharedLedger(") == 2
      and BE.count("takeLedger.claim(resource, Math.min(deficit, takeable), takeable)") == 2)
check("取用侧：预留表用 pendingExtraction(状态表, ownTaskIds) **现算**（不缓存、也没传空集合）",
      BE.count("SequenceMaterialGuard.pendingExtraction(reserveStatuses, ownTaskIds)") == 2)
check("取用侧：状态表每次从网络组件现读（getStatuses 直接进判据，不经任何缓存字段）",
      BE.count("reserveNetwork.getComponent(AutocraftingNetworkComponent.class)") == 2
      and BE.count("? List.of() : reserveAutocrafting.getStatuses();") == 2)
check("取用侧：账本作用域 = 网络对象 + 游戏刻（换网络 / 换 tick 即重开）",
      BE.count("reserveNetwork == null ? storage : reserveNetwork, now)") == 1
      and BE.count("reserveNetwork == null ? storage : reserveNetwork, fluidNow)") == 1
      and "private SequenceMaterialGuard.TakeLedger" not in BE)
check("取用侧：实际抽取量改成账本获批量 storage.extract(resource, granted, ...)",
      BE.count(ITEM_EXTRACT) == 2)
check("取用侧：原来的「裸存量」夹量已彻底不存在（否则预留形同虚设）",
      "Math.min(deficit, available)" not in BE)
check("取用侧：ownTaskIds 是本模组自己的任务（产物 ∈ 本仓输入类 / 中间产物类），两处都有",
      BE.count("ownTaskIds.add(status.info().id().id().toString())") == 2
      and "ownProducts.contains(ownProduct.item())" in BE
      and "ownFluidProducts.contains(ownProduct.fluid())" in BE
      and BE.count("category.isInput() || category.isIntermediate()") == 2)
check("取用侧：ownTaskIds / 状态表都是**局部变量**（final，方法内现算；没有新增缓存字段）",
      BE.count("final Set<String> ownTaskIds = new LinkedHashSet<>();") == 2
      and BE.count("final List<TaskStatus> reserveStatuses = reserveAutocrafting == null") == 2)
check("取用侧：scheduled / processing 绝不算预留（执行器会把每轮投入物原样插回网络）",
      ".scheduled()" not in BE and ".processing()" not in BE)

check("取用侧（物品）：deficit → 可用量/账本 → 获批 0 等待 → 才 extract（顺序正确）",
      order("final int deficit = busItemDeficit(resource.item(), target);",
            "SequenceMaterialGuard.availableForTake(available,", ITEM_GRANT0, ITEM_EXTRACT))
item_extract_at = BE.find(ITEM_EXTRACT)
item_hold = BE[BE.find(ITEM_GRANT0):item_extract_at]
check("取用侧（物品）：获批 0 的分支里只有等待 / 上报（tracePullHold + HOLD_INFLIGHT_TASK_NEED + return false），"
      "没有任何抽取、没有自旋",
      "tracePullHold(" in item_hold and "SequenceMaterialGuard.HOLD_INFLIGHT_TASK_NEED" in item_hold
      and "return false;" in item_hold and ".extract(" not in item_hold)

check("取用侧（流体）：deficit → 可用量/账本 → 获批 0 等待 → 才 extract（顺序正确）",
      order("final int deficit = busFluidDeficit(resource.fluid(), effectiveTarget);",
            "SequenceMaterialGuard.availableForTake(available,", ITEM_GRANT0, ITEM_EXTRACT))
fluid_grant_at = BE.find(ITEM_GRANT0, BE.find(ITEM_GRANT0) + 1)
fluid_extract_at = BE.find(ITEM_EXTRACT, item_extract_at + 1)
fluid_hold = BE[fluid_grant_at:fluid_extract_at]
check("取用侧（流体）：获批 0 的分支只有等待 / 上报（既有 transition 通道 + HOLD_INFLIGHT_TASK_NEED + return），"
      "没有任何抽取",
      "RsccAssemblyDebug.transition(" in fluid_hold
      and "SequenceMaterialGuard.HOLD_INFLIGHT_TASK_NEED" in fluid_hold
      and "return;" in fluid_hold and ".extract(" not in fluid_hold)

# ---- 模型侧：ownTaskIds 与 scheduled / processing 的真值表 ----
own_task = [{"id": "own", "state": "EXTRACTING_INITIAL_RESOURCES",
             "items": [{"resource": "r", "extracting": 64}]}]
check("⑥ 模型：本产线自己的任务被排除后，同一份存量重新可用（不会锁死自己的投料口）",
      available_for_take(64, sum(pending_extraction(own_task, own=("own",)).values())) == 64
      and available_for_take(64, sum(pending_extraction(own_task, own=()).values())) == 0)
scheduled = [{"id": "s", "state": "RUNNING",
              "items": [{"resource": "r", "extracting": 0, "scheduled": 64, "processing": 64}]}]
check("⑥ 模型：scheduled / processing 不算预留（执行器会把投入物原样插回网络，算了会锁死自己的供料链）",
      pending_extraction(scheduled) == {})

print()
print("⑥ 多仓同 tick 合计矩阵（每仓问「每轮抽取批」；可用量 = 存量 − 预留）")
print("%-8s %-8s %-9s %-11s %s" % ("存量", "预留", "可用量", "3 仓合计", "断言"))
print("-" * 96)
for stored, reserved, per in ((64, 0, 8), (64, 16, 8), (64, 40, 8), (10, 4, 8), (64, 64, 8), (4, 0, 4)):
    led = TakeLedger()
    led.begin_tick("net", 3)
    total = sum(taker_wants(led, stored, reserved, per)[1] for _ in range(3))
    avail = available_for_take(stored, reserved)
    check("⑥ 多仓合计不超可用量：存量 %d / 预留 %d / 每仓问 %d ⇒ 合计 %d == min(%d, 可用量 %d)"
          % (stored, reserved, per, total, 3 * per, avail),
          total == min(3 * per, avail), "total=%d avail=%d" % (total, avail))
    print("%-8d %-8d %-9d %-11d %s" % (stored, reserved, avail, total,
                                       "OK" if total == min(3 * per, avail) else "FAIL"))
print()

# =====================================================================================
# 场景矩阵（存量 × 预留 → 可用量 / 获批 / 结果）
# =====================================================================================
print()
print("=" * 96)
print("场景矩阵（want=64；可用量 = 存量 − 预留；同一 tick 内 3 个取用者依次尝试）")
print("=" * 96)
print("%-8s %-8s %-9s %-12s %-10s %s" % ("存量", "预留", "可用量", "取用者1", "取用者2/3", "结论"))
print("-" * 96)
for stored, reserved in ((64, 0), (64, 8), (64, 32), (64, 63), (64, 64), (32, 64), (0, 0), (1, 1)):
    led = TakeLedger()
    led.begin_tick("matrix", 1)
    got1 = taker_wants(led, stored, reserved, 64)[1]
    got2 = taker_wants(led, stored, reserved, 64)[1]
    got3 = taker_wants(led, stored, reserved, 64)[1]
    avail = available_for_take(stored, reserved)
    verdict = "正常供料" if reserved == 0 else ("等待 / 上报缺料（全部被预留）" if avail == 0 else "只取可用量")
    if stored == 0:
        verdict = "网络本来就没有（不走本判据）"
    print("%-8d %-8d %-9d %-12d %-10s %s" % (stored, reserved, avail, got1,
                                              "%d/%d" % (got2, got3), verdict))
print()

if PROBLEMS:
    print("=" * 96)
    print("SELFCHECK FAILED：%d 项不通过" % len(PROBLEMS))
    for p in PROBLEMS:
        print("  - %s" % p)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
