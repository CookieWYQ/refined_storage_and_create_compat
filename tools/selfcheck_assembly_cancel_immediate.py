# -*- coding: utf-8 -*-
"""「原生取消 → 立刻回收内部暂存 + 同步清理挂起记录」自检（源码锚点 + 等价模型推演）。

用法：python tools/selfcheck_assembly_cancel_immediate.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么需要它（无法在本地把游戏跑起来验证）
----------------------------------------
用户原话：「取消时，不管是怎么样的，你要立刻返回。」

RS 一条自动合成任务唯一的「推进 / 收尾」入口，就是 `TaskContainer` 每个 work tick 对容器里的
任务逐个调用 `Task#step(...)`（`TaskContainer.java:87-115`；work tick 速率见
`UpgradeContainer.DEFAULT_WORK_TICK_RATE = 9`，即默认每 9 游戏刻一次，装速度升级更快）。

- 玩家按 RS **原生「取消」**：`AutocraftingNetworkComponent#cancel`
  → `TaskContainer#cancel` → `TaskImpl#cancel()`（`TaskImpl.java:140-143`）
  **当刻**就把状态置为 `RETURNING_INTERNAL_STORAGE`；RS 再靠自己 `TaskImpl#step` 的
  `case RETURNING_INTERNAL_STORAGE`（`TaskImpl.java:134`）调用
  `returnInternalStorageAndTryCompleteTask`（`TaskImpl.java:276-298`）把内部暂存
  （已抽出的中间件 / 原料）`insert` 回网络。

- 旧缺陷：本模组对**已挂起**的任务在 `TaskContainerMixin` 里把 `step` 直接跳过
  （`cir.setReturnValue(false)`），于是「取消后 RS 自己那条回收分支」永远没机会跑 ——
  玩家的东西既不在网络里、也不再被推进，只能等本模组记录过期（默认约 10 分钟）才回收。

修法（判定仍然只有一处、且以 RS 源码为准）：
`TaskContainerMixin` 只在「仍在合成途中」的三个状态（`READY` /
`EXTRACTING_INITIAL_RESOURCES` / `RUNNING`）才允许挂起跳过；一旦进入
`RETURNING_INTERNAL_STORAGE` / `COMPLETED` 就**放行** RS 自己的回收，并当 tick 通知
`AssemblyWatchdog.onTaskTerminated(...)` 同步移除挂起记录 + 立刻重播告警快照（无幽灵行）。
"""
import io
import json
import datetime
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

MIXIN = os.path.join(SRC, "mixin", "TaskContainerMixin.java")
WATCHDOG = os.path.join(SRC, "support", "AssemblyWatchdog.java")
RS_TASK_IMPL = os.path.join(
    RS, "refinedstorage-autocrafting-api", "src", "main", "java", "com", "refinedmods",
    "refinedstorage", "api", "autocrafting", "task", "TaskImpl.java")
RS_TASK_STATE = os.path.join(
    RS, "refinedstorage-autocrafting-api", "src", "main", "java", "com", "refinedmods",
    "refinedstorage", "api", "autocrafting", "task", "TaskState.java")
RS_CONTAINER = os.path.join(
    RS, "refinedstorage-network", "src", "main", "java", "com", "refinedmods",
    "refinedstorage", "api", "network", "impl", "autocrafting", "TaskContainer.java")
RS_UPGRADE = os.path.join(
    RS, "refinedstorage-common", "src", "main", "java", "com", "refinedmods",
    "refinedstorage", "common", "upgrade", "UpgradeContainer.java")

FAILURES = []
CHECKS = [0]


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name,
                       (" | " + detail) if (detail and not ok) else ""))


def has(text, needle, name):
    check(name, needle in text, "" if needle in text else ("missing: %s" % needle))


def flatten(text):
    """折叠空白：锚点比对走「折叠空白后的子串」，避免被换行 / 缩进差异误伤。"""
    return " ".join(text.split())


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


mix = read(MIXIN)
wd = read(WATCHDOG)
task_impl = read(RS_TASK_IMPL)
task_state = read(RS_TASK_STATE)
container = read(RS_CONTAINER)
upgrade = read(RS_UPGRADE)

# ==================== 1. RS 判据锚点（放行点的依据） ====================
section("1) RS 源码判据：取消 → RETURNING_INTERNAL_STORAGE → 当 tick 回收到网络")

has(task_impl, "case RETURNING_INTERNAL_STORAGE -> returnInternalStorageAndTryCompleteTask(rootStorage);",
    "锚点: TaskImpl#step 对 RETURNING_INTERNAL_STORAGE 走" " returnInternalStorageAndTryCompleteTask（TaskImpl.java:134）")
has(task_impl, "private boolean returnInternalStorageAndTryCompleteTask(final RootStorage rootStorage) {",
    "锚点: 回收实现把内部暂存 insert 回网络（TaskImpl.java:276；rootStorage.insert + internalStorage.remove，不复制不销毁）")
check("锚点: TaskImpl#cancel() 只做两件事 —— 状态置 RETURNING_INTERNAL_STORAGE + cancelled=true"
      "（TaskImpl.java:140-143）",
      flatten("state = TaskState.RETURNING_INTERNAL_STORAGE;") in flatten(task_impl)
      and flatten("cancelled = true;") in flatten(task_impl),
      "取消路径的实现与预期不符")
has(container, "public void cancel(final TaskId id) {",
    "锚点: TaskContainer#cancel 找到任务并调用 task.cancel()（TaskContainer.java:61-69）")
has(container, "completed = task.getState() == TaskState.COMPLETED;",
    "锚点: TaskContainer#step 以「state == COMPLETED」判定收尾完成并把任务移出列表（TaskContainer.java:102）")
for state in ("READY", "EXTRACTING_INITIAL_RESOURCES", "RUNNING", "RETURNING_INTERNAL_STORAGE", "COMPLETED"):
    has(task_state, state, "锚点: TaskState 含状态 %s" % state)
m_rate = re.search(r"DEFAULT_WORK_TICK_RATE\s*=\s*(\d+);", upgrade)
check("锚点: 默认 work tick 速率 = 9 游戏刻（UpgradeContainer.DEFAULT_WORK_TICK_RATE）"
      " ⇒ 放行后回收最迟下一个 work tick（默认 ≤ 9 tick ≈ 0.45 秒，装速度升级更快）",
      m_rate is not None and int(m_rate.group(1)) == 9,
      "找不到 DEFAULT_WORK_TICK_RATE 或不是 9")

# ==================== 2. 放行点：挂起只作用于「合成途中」 ====================
section("2) 放行点（TaskContainerMixin）：挂起跳过只作用于进行中的 crafting 状态")

has(mix,
    "case READY, EXTRACTING_INITIAL_RESOURCES, RUNNING -> true;",
    "① 放行判据: READY / EXTRACTING_INITIAL_RESOURCES / RUNNING = 「仍在合成途中」（可被挂起跳过）")
has(mix,
    "case RETURNING_INTERNAL_STORAGE, COMPLETED -> false;",
    "① 放行判据: RETURNING_INTERNAL_STORAGE / COMPLETED = 「收尾 / 结束」→ 不放行跳过（必须放行 RS 回收）")
check("④ 挂起跳过被包在 rscc$isCrafting(state) 之内 —— 只有合成途中才可能返回 false",
      "if (rscc$isCrafting(task.getState())) {" in mix
      and "AssemblyWatchdog.isSuspended(task.getId().id())" in mix
      and "cir.setReturnValue(false);" in mix,
      "挂起跳过没有被状态判据包住")
check("① 收尾 / 结束态走 else 分支：调用 AssemblyWatchdog.onTaskTerminated(...)（同步清理挂起记录）",
      "AssemblyWatchdog.onTaskTerminated(task.getId().id());" in mix,
      "收尾态没有通知 watchdog 同步清理")
check("① 判据来自 RS 自己的 Task#getState()（穷举 switch、无 default ⇒ RS 新增状态时编译期即报错）",
      "task.getState()" in mix and "switch (state) {" in mix and "default" not in mix.split("rscc$isCrafting")[-1],
      "判据不是 RS 的状态枚举 / 用了 default 会静默吞掉新状态")
check("② 未被挂起的任务一字未被改动：只有 isSuspended 为真才 setReturnValue(false)，"
      "非挂起任务照常走 RS 的 step ⇒ 「运行中任务按原生取消立刻回收」这条路径不受影响",
      mix.count("cir.setReturnValue(false);") == 1
      and "AssemblyWatchdog.isSuspended" in mix,
      "挂起注入影响了非挂起任务的推进")

# ==================== 3. 同步清理：挂起记录 / 告警快照不残留 ====================
section("3) 同步清理（AssemblyWatchdog）：取消后挂起记录与监视器告警当帧消失")

has(wd, "public static void onTaskTerminated(final UUID taskId) {",
    "③ 锚点: onTaskTerminated 是「任务进入收尾 / 结束态」的即时清理入口")
has(wd, "if (record == null || record.suspendState == SuspendState.RUNNING) {",
    "③ 锚点: onTaskTerminated 只处理「确实被我们挂起过」的记录（未挂起 / 从未挂起 ⇒ 不动）")
has(wd, "entry.getValue().remove(taskId);",
    "③ 锚点: 命中即移除挂起记录（之后重复调用是空操作，幂等）")
has(wd, "broadcast(level); // 立刻重播一次告警快照：幽灵行 / 按钮当帧消失",
    "③ 锚点: 移除后立刻重播告警快照（不等下一次 20 tick 扫描）")
has(wd, "LEVELS.put(level.dimension(), level);",
    "③ 锚点: 缓存维度实例（onTaskTerminated 那一刻手上只有 uuid，需要它才能即时广播）")
has(wd, "LEVELS.remove(level.dimension());",
    "③ 锚点: 维度卸载时清掉缓存（无泄漏）")
check("③ 扫描侧兜底（同一口径）: returning 分支里，记录若还挂着就就地移除并 continue"
      " —— 即使某次 step 没被调用到，下一次扫描也必然清干净",
      "if (record.returning) {" in wd
      and "records.remove(record.taskId());" in wd
      and "if (record.suspendState != SuspendState.RUNNING) {" in wd,
      "扫描侧没有 returning → 同步移除挂起记录的兜底分支")
# 本轮同步（旧口径 → 新口径）：actions() 把「收尾态不给任何按钮位」提到最前（`if (returning) {`），
# 于是**已挂起**的任务一旦被 RS 收尾也不会再广播「继续」（用户实测「一边继续、一边完成」）。
check("③ 服务端不再给收尾中的任务任何按钮位（actions: returning ⇒ ACTION_BIT_NONE，含已挂起态）",
      "private int actions() {" in wd and "if (returning) {" in wd
      and "return SyncAssemblyAlertsPacket.ACTION_BIT_NONE;" in wd,
      "收尾态仍可能渲染挂起 / 继续按钮")
check("③ 也不可能把「已取消」的任务再挂起（suspend 拒绝 returning）",
      "record.suspendState != SuspendState.RUNNING || record.returning" in wd,
      "suspend 没有拒绝「RS 正在回收」的任务")

# ==================== 4. 守恒：注入的代码里不搬运任何物品 / 流体 ====================
section("4) 守恒：放行注入本身不搬运 / 不销毁，回收全由 RS 自己的代码完成")

mix_code = re.sub(r"/\*.*?\*/", "", mix, flags=re.S)
mix_code = re.sub(r"//[^\n]*", "", mix_code)
for forbidden in ("extract(", "insert(", "setItem(", "internalStorage", "rootStorage", "sink.accept"):
    check("%s 不出现在注入代码里" % forbidden, forbidden not in mix_code,
          "注入代码里出现了搬运：%s（可能破坏守恒）" % forbidden)

# ==================== 5. 推演：与 Java 同构的极小模型 ====================
section("5) 推演：① 挂起任务被取消 ② 运行中任务被取消 ③ 记录同步消失 ④ 只跳过 crafting")

CRAFTING = {"READY", "EXTRACTING_INITIAL_RESOURCES", "RUNNING"}
TERMINAL = {"RETURNING_INTERNAL_STORAGE", "COMPLETED"}


def should_skip(state, suspended):
    """与 TaskContainerMixin 同构：只有「合成途中 且 被挂起」才跳过 step。"""
    return state in CRAFTING and suspended


class TaskModel(object):
    """与 TaskImpl + TaskContainerMixin 同构：内部暂存 / 网络 / 状态机。"""

    def __init__(self):
        self.state = "RUNNING"
        self.internal = 12          # 已抽出的中间件（任务自己的 internalStorage）
        self.network = 340          # 网络库存
        self.cancelled = False
        self.records = {"rec": {"suspended": False}}   # = AssemblyWatchdog.RECORDS

    def total(self):
        return self.internal + self.network

    # ---- AssemblyWatchdog.suspend / onTaskTerminated ----
    def suspend(self):
        rec = self.records["rec"]
        if rec["suspended"] or self.state == "RETURNING_INTERNAL_STORAGE":
            return False
        rec["suspended"] = True
        return True

    def on_terminated(self):
        """= AssemblyWatchdog.onTaskTerminated：只移除「确实挂起过」的记录，并重播快照。"""
        rec = self.records.get("rec")
        if rec is None or not rec["suspended"]:
            return False
        del self.records["rec"]
        self.broadcasts = getattr(self, "broadcasts", 0) + 1
        return True

    def alerts(self):
        """= AssemblyWatchdog.alerts：只有「有动作位」的记录才出现在监视器上。"""
        rec = self.records.get("rec")
        if rec is None:
            return []
        return [{"suspended": rec["suspended"]}]

    # ---- TaskContainer.cancel ----
    def cancel(self):
        self.state = "RETURNING_INTERNAL_STORAGE"   # = TaskImpl#cancel()
        self.cancelled = True

    # ---- TaskContainerMixin.step + TaskImpl.step ----
    def step(self):
        if should_skip(self.state, self.records.get("rec", {}).get("suspended", False)):
            return False                            # = cir.setReturnValue(false)
        if self.state in TERMINAL:
            self.on_terminated()                    # 放行 RS 回收 + 同步清理挂起记录
        if self.state == "RETURNING_INTERNAL_STORAGE":
            moved = self.internal
            self.network += moved                   # = returnInternalStorageAndTryCompleteTask
            self.internal = 0
            self.state = "COMPLETED"
            return moved > 0
        return False


# ① 挂起任务被原生取消 → 内部暂存当 tick 立刻回网（≤ 数个 tick，不等记录过期）
m = TaskModel()
check("① 前置: 任务已挂起", m.suspend() is True and m.records["rec"]["suspended"] is True)
before = m.total()
# 挂起期间：step 被跳过（旧缺陷的现场 —— 600 tick 后内部暂存纹丝不动）
for _ in range(600):
    m.step()
check("① 前置: 挂起期间 step 全被跳过（内部暂存 12 原地冻结，回收分支永远不会被调用）",
      m.internal == 12 and m.total() == before)
m.cancel()                                      # = 玩家按 RS 原生「取消」
check("① 取消后状态变为 RETURNING_INTERNAL_STORAGE（crafting 之外）⇒ step 必然被放行"
      "（旧实现会继续跳过，回收永远不跑，只能等记录过期）",
      should_skip(m.state, m.records["rec"]["suspended"]) is False)
ticks_used = 0
for _ in range(10_000):                         # 上限远大于记录过期（12000）
    m.step()
    ticks_used += 1
    if m.state == "COMPLETED" and m.internal == 0:
        break
check("① 放行后第 1 个 work tick 就把内部暂存还回网络"
      "（= 默认 ≤ 9 游戏刻 ≈ 0.45 秒，与记录过期 12000 tick 完全无关）",
      ticks_used == 1, "实际用了 %d 个 work tick" % ticks_used)
check("① 守恒: 内部暂存 12 全部回网，取消前后总量 %d == %d（不复制 / 不销毁）"
      % (before, m.total()),
      m.internal == 0 and m.network == before and m.total() == before)
check("③ 回收完成当刻，挂起记录已被同步移除（监视器上不再有「已挂起」行 / 按钮）",
      m.records == {})
check("③ 告警快照同步为空（无幽灵记录）", m.alerts() == [])

# ② 运行中（未挂起）任务被原生取消 → 立刻回收，且未被我们影响
m2 = TaskModel()
check("② 前置: 任务正在运行、从未被挂起", m2.records["rec"]["suspended"] is False)
before2 = m2.total()
m2.step()                                       # 正常运行：照常走 RS 的 step
check("② 前置: 运行中任务被正常推进，未受挂起注入影响",
      m2.state == "RUNNING" and m2.total() == before2)
m2.cancel()
ticks2 = 0
for _ in range(10_000):
    m2.step()
    ticks2 += 1
    if m2.state == "COMPLETED" and m2.internal == 0:
        break
check("② 运行中任务按原生取消: 同样当 tick 回收完、总量守恒（这条路径我们一字未改）",
      ticks2 == 1 and m2.network == before2 and m2.total() == before2)
check("② 取消不会凭空给我方记录加上挂起标记（是否清理只由扫描按「任务是否还在」决定）",
      m2.records.get("rec", {}).get("suspended", False) is False)

# ③ 幂等 / 不刷包：回收跨越多个 work tick（网络塞满）时，记录移除只有一次
m3 = TaskModel()
m3.suspend()
m3.internal = 12
m3.cancel()
m3.on_terminated()                              # 第 1 次：移除 + 广播
m3.on_terminated()                              # 之后重复调用：空操作
check("③ 幂等: 记录移除 + 即时广播只发生一次（不会每 tick 重复刷包）",
      getattr(m3, "broadcasts", 0) == 1 and m3.records == {})

# ④ 挂起跳过只作用于「进行中的 crafting 状态」
for state in sorted(CRAFTING):
    check("④ %s + 已挂起 ⇒ 跳过 step（正常挂起语义不变）" % state,
          should_skip(state, True) is True)
for state in sorted(TERMINAL):
    check("④ %s + 已挂起 ⇒ 绝不跳过（放行 RS 自己的回收 / 收尾）" % state,
          should_skip(state, True) is False)
for state in sorted(CRAFTING | TERMINAL):
    check("④ %s + 未挂起 ⇒ 永不跳过（正常运行一字未改）" % state,
          should_skip(state, False) is False)

# ④ 正常挂起语义未被破坏：挂起后长时间不抽料、不投料、总量冻结
m4 = TaskModel()
m4.suspend()
frozen = m4.total()
for _ in range(600):
    m4.step()
check("④ 挂起期间 600 tick 内既不推进也不搬运（内部暂存 %d 原地冻结，总量 %d 不变）"
      % (m4.internal, m4.total()),
      m4.total() == frozen and m4.internal == 12 and m4.state == "RUNNING")

# ==================== 6. 「处理中：N」的出处 / 挂起不自相矛盾 / 金板守恒 ====================
section("6) 监视器「还有 N 个金板正在处理」：字段出处 + 挂起不自相矛盾 + 金板守恒")

RS_EXTERNAL = os.path.join(
    RS, "refinedstorage-autocrafting-api", "src", "main", "java", "com", "refinedmods",
    "refinedstorage", "api", "autocrafting", "task", "ExternalTaskPattern.java")
RS_CALC_LISTENER = os.path.join(
    RS, "refinedstorage-autocrafting-api", "src", "main", "java", "com", "refinedmods",
    "refinedstorage", "api", "autocrafting", "task", "TaskPlanCraftingCalculatorListener.java")
RS_MONITOR_SCREEN = os.path.join(
    RS, "refinedstorage-common", "src", "main", "java", "com", "refinedmods",
    "refinedstorage", "common", "autocrafting", "monitor", "AutocraftingMonitorScreen.java")
RS_LANG_ZH = os.path.join(ROOT, "local_src", "rs_assets", "assets", "refinedstorage", "lang", "zh_cn.json")
PATTERN_ITEM = os.path.join(SRC, "item", "SequenceAssemblyPatternItem.java")
MONITOR_MIXIN = os.path.join(SRC, "mixin", "client", "AutocraftingMonitorScreenMixin.java")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
LOG = os.path.join(ROOT, "run", "logs", "latest.log")

external = read(RS_EXTERNAL)
calc_listener = read(RS_CALC_LISTENER)
monitor_screen = read(RS_MONITOR_SCREEN)
pattern_item = read(PATTERN_ITEM)
monitor_mixin = read(MONITOR_MIXIN)

# ---- 6.1 该数字取自 RS 的哪个字段（贴源码行） ----
has(external, "final long iterationsProcessing = iterationsSentToSink - iterationsReceived;",
    "锚点: 「处理中」= iterationsProcessing = iterationsSentToSink − iterationsReceived"
    "（ExternalTaskPattern#appendStatus；sent 只由 step 递增、received 只由产物回收递增）")
has(external, "builder.processing(",
    "锚点: 该数写进 TaskStatus 的 processing 项（ExternalTaskPattern#appendStatus → builder.processing(...)，"
    "值 = simulatedIterationInputs.get(input) × iterationsProcessing）")
has(external, "this.simulatedIterationInputs = calculateIterationInputs(Action.SIMULATE);",
    "锚点: 参与该行的资源 = 样板的**每次迭代输入**（simulatedIterationInputs），"
    "因此金板（主原料）会有这一行、且每轮计 1")
check("锚点: 挂起期间该数字必然冻结 —— 发送端只在 step 里递减、收回端只在 trySatisfy 里递增，"
      "而挂起正好把 step 跳过（TaskContainerMixin），两者都不发生",
      "iterationsToSendToSink--;" in external
      and "private long trySatisfy(final ResourceKey resource, final long amount) {" in external
      and "updateIterationsReceived()" in external,
      "找不到 sent/received 的唯一递增点")
has(calc_listener, "task.addToExtract(resource, amount);",
    "锚点: 主原料同时以 initialRequirements 形式出现（→ 显示为 Extracting，随抽取递减到 0）；"
    "与 processing 是两条不同的行，别混看")
has(monitor_screen, 'renderItemText(graphics, "processing", rendering, x, yy, item.processing());',
    "锚点: 监视器把 processing 渲染成一行小字（AutocraftingMonitorScreen#renderItemText）")
rs_lang = json.load(io.open(RS_LANG_ZH, encoding="utf-8"))
check("锚点: 该行的中文文案 = 「处理中：%s」（RS 自己的语言键，用户看到的「正在处理」就是它）",
      rs_lang.get("gui.refinedstorage.autocrafting_monitor.processing") == "处理中：%s",
      "RS 语言键缺失或被改动")

# ---- 6.2 我们只读不改：绝不伪造 RS 的任务计数 ----
for forbidden in ("TaskStatusBuilder", "builder.processing", "updateState("):
    hits = []
    for root_dir, _dirs, files in os.walk(SRC):
        for name in files:
            if name.endswith(".java") and forbidden in read(os.path.join(root_dir, name)):
                hits.append(name)
    check("「%s」不出现在本模组源码里（不改写 RS 的任务计数 / 状态机；只读 getStatuses()）"
          % forbidden, not hits, "出现于: %s" % hits)

# ---- 6.3 金板为什么会有这一行：我们的样板把主原料登记为「每次迭代 1 个」 ----
# 说明（2026-10-04 第三轮）：旧锚点钉的是 `items.merge(data.ingredient().getItem(), …)`。
# §7.1 把 collectInputs 从「按单个物品聚合」改成「按候选组聚合」（Map<InputGroupKey, Long>）
# 之后，这一行已经不存在，锚点因此长期 FAIL（= 自检永久红，而不是「代码坏了」）。
# 这里改成钉住**当前**实现：主原料经 mergeInput 以「单候选组」并入，数量仍是
# max(1, getCount()) 且不随 loops 放大 —— 与旧锚点想守住的语义完全一致。
# 2026-10-05：主原料改为「整组候选」登记（用户要求原料一律轮播，禁止拼「A 或 B」），
# 因此锚点从 List.of(...) 改为 candidatesOrRepresentative()；数量口径未变（仍不随 loops 放大）。
has(pattern_item, "mergeInput(items, data.candidatesOrRepresentative(),",
    "锚点: 样板把**主原料**（金板 = c:plates/gold）登记为每次迭代 1 个（collectInputs → mergeInput，"
    "候选组、不随 loops 放大），因此「处理中：N」的 N = 在途件数（每件含 1 个金板），"
    "不是「仓里存着 N 个金板」")
# 追加一条：候选组聚合口径本身也在（防止有人把「按候选组聚合」改回「按单个物品聚合」）
has(pattern_item, "private record InputTotals(Map<InputGroupKey, Long> items, Map<FluidResource, Long> fluids) {",
    "锚点: 输入聚合按**候选组**（InputGroupKey）而不是按单个物品，"
    "「铁粒 或 锌粒」才会成为同一项 ingredient 的两个候选")

# ---- 6.4 挂起时显示不自相矛盾：我们自己写「已挂起」+ 解释在途计数 ----
for key in ("suspended.offline", "suspended.missing", "suspended.noprog", "suspended.manual"):
    has(monitor_mixin, 'case SyncAssemblyAlertsPacket.REASON_' + {
        "suspended.offline": "EXECUTOR_OFFLINE",
        "suspended.missing": "MISSING_MATERIAL",
        "suspended.noprog": "NO_PROGRESS",
        "suspended.manual": "MANUAL"}[key] + ' -> RSCC_LANG + "%s";' % key,
        "锚点: 挂起态显示我们自己的一行原因（%s）—— 所以在途计数旁边一定有「已挂起」" % key)
check("锚点: 挂起标记的 tooltip 有**两行**：第一行「已挂起 + 如何恢复」，第二行解释「处理中」是在途件数"
      "（避免把「已经不动了却还写着 N 个在处理」误读成还在跑 / 料被吞）",
      'lines.add(Component.translatable(RSCC_LANG + "suspended.tip").getVisualOrderText());' in monitor_mixin
      and 'lines.add(Component.translatable(RSCC_LANG + "suspended.inflight_tip").getVisualOrderText());'
          in monitor_mixin)
zh = json.load(io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8"))
en = json.load(io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8"))
inflight_key = "gui.rs_create_compat.assembly.monitor.suspended.inflight_tip"
check("锚点: 新 tooltip 行中英成对且中文 ≤ 40 字",
      inflight_key in zh and inflight_key in en and len(zh[inflight_key]) <= 40,
      "键缺失或中文过长")
check("锚点: 挂起时按钮位只有「继续」（与 returning 的「无动作位」互斥）—— 界面同一时刻不会既说已挂起又给挂起",
      "int actions = SyncAssemblyAlertsPacket.ACTION_BIT_RESUME;" in wd
      and "if (returning) {" in wd)

# ---- 6.5 「那 7 个金板」去向守恒（日志核对；无日志 / 无相关行时跳过） ----
def log_lines():
    if not os.path.exists(LOG):
        return None
    with io.open(LOG, encoding="utf-8", errors="replace") as handle:
        return handle.readlines()


lines = log_lines()
if lines is None:
    print("[SKIP] 找不到日志 %s —— 金板守恒的日志断言跳过" % LOG)
else:
    trace = re.compile(r"item=(\S+) x(\S+) \| from=(\S+) \| event=(\S+) \| to=(\S+) \| reason=([^|]*)\| net=(\S*)")
    repeat = re.compile(r"\(same cause repeated (\d+) times in 5s\)")

    gold = {}
    # <b>日志比源码旧 ⇒ 归因跳过（与 selfcheck_flow_conservation 同款处理）。</b>
    #
    # 本断言的证据全部来自 run/logs/latest.log。用户每次「改代码 → 重编 → 重启游戏」之间，
    # 日志里保留的是<b>上一版构建</b>产生的流水，此时拿它判定新代码的守恒关系会得到假红
    # （实测：金板守恒② 报「入网 1 vs 废料 0 + 残留 0」，而那次日志来自修复前的构建）。
    # 判定方式与 flow_conservation 一致：日志 mtime 早于执行舱源码 mtime ⇒ 标记归因并跳过。
    _chamber_src = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq",
                                "rs_create_compat", "block", "entity",
                                "SequenceExecutionChamberBlockEntity.java")
    _stale_log = False
    if os.path.exists(LOG) and os.path.exists(_chamber_src):
        # <b>用「日志里的构建戳」判定，而不是 mtime。</b>
        # 为什么：日志文件 mtime 会因为「游戏仍在写日志」而一直变新，而它<b>内容</b>里那段流水
        # 可能来自上一版构建（实测踩过：日志 mtime 14:28 > 源码 mtime 13:47，却拿的是 13:48 构建的流水）。
        # 只有 `[rscc-build] built=<时间戳>` 才真正说明「这段流水是哪一版代码产生的」。
        try:
            with open(LOG, "r", encoding="utf-8", errors="replace") as _handle:
                _text = _handle.read()
            _stamps = re.findall(r"\[rscc-build\][^\n]*?built=([0-9T:+\-]+)", _text)
            _log_build = _stamps[-1] if _stamps else None
        except OSError:
            _log_build = None
        _src_mtime = datetime.datetime.fromtimestamp(os.path.getmtime(_chamber_src))
        if _log_build:
            try:
                _stale_log = datetime.datetime.fromisoformat(_log_build).replace(
                    tzinfo=None) < _src_mtime.replace(tzinfo=None)
            except ValueError:
                _stale_log = False
        else:
            # 日志里没有构建戳（旧日志 / 刚清空）⇒ 退回 mtime 判定
            _stale_log = os.path.getmtime(LOG) < os.path.getmtime(_chamber_src)
    if _stale_log:
        print("[SKIP] 日志里的构建戳早于执行舱源码（本段流水来自上一版构建）—— 守恒断言归因跳过 [FIXED-OLD-LOG 归因]")

    def weight(line):
        m = repeat.search(line)
        return int(m.group(1)) if m else 1

    gold = {}
    for line in lines:
        m = trace.search(line)
        if m is None or m.group(1) != "create:golden_sheet":
            continue
        ev = m.group(4)
        why = m.group(6).strip()
        bucket = ev + ("/residual" if "task_finished_residual" in why else "")
        gold[bucket] = gold.get(bucket, 0) + weight(line)
    pulled = gold.get("take_to_chamber", 0)
    pushed = gold.get("bus_push", 0)
    scrap = gold.get("take_from_machine", 0)
    returned = gold.get("insert_network/residual", 0)
    if pulled == 0 and pushed == 0:
        print("[SKIP] 日志里没有金板的搬运行（本次没跑过精密构件）—— 守恒断言跳过")
    else:
        print("  金板流水（加权）：拉入执行仓=%d；推给机器=%d；抽奖废料入网=%d；任务结束残留回流=%d"
              % (pulled, pushed, scrap, returned))
        # 与守恒②同款归因：`pulled`（网络侧 take_to_chamber）与 `pushed`（推送侧 bus_push）
        # 来自<b>两个不同的日志段</b>，日志轮转只截断一侧时差值会被放大成假红。
        # 因此差值超限时先看两侧是否都非空；任一侧为 0 ⇒ 证据缺失、归因跳过。
        if pulled == 0 or pushed == 0:
            print("  [SKIP] 金板守恒①：一侧计数为 0（拉入 %d / 推给机器 %d）"
                  "—— 日志轮转截断了其中一段，归因跳过 [FIXED-OLD-LOG 归因]" % (pulled, pushed))
        else:
            # <b>2026-10-05 口径修正：`pushed` 侧本就可大于 `pulled`，不是「凭空多出」。</b>
            #
            #   pulled = {@code event=take_to_chamber}：只有「网络 → 仓」那一路
            #   pushed = {@code event=bus_push}：仓把料推给机器的<b>全部</b>来源，
            #            其中还包含<b>跨阶段直传</b>（{@code take_from_machine} +
            #            {@code handover_to_chamber}，见 chamber 的「跨阶段中间产物直接交接」）
            #            以及「机器退回后再推一次」等<b>不经过网络</b>的路径。
            #
            # 因此 `pushed > pulled` 是<b>设计内</b>的正常形状（直传不记账为入网）。
            # 真正要守的底线是反方向：<b>不能「拉进来却推不出去/不知去向」</b>，
            # 即 `pulled` 不能显著大于 `pushed`。所以只在这一侧断言。
            check("金板守恒①: 拉进执行仓的量不允许显著大于推给机器的量"
                  "（pushed ≥ pulled − 在途容差；pushed 偏大是跨阶段直传的正常形状）",
                  pulled - pushed <= 2, "拉入 %d vs 推给机器 %d" % (pulled, pushed))
        inserts = gold.get("insert_network", 0) + gold.get("insert_network/residual", 0)
        # <b>2026-10-05 口径修正：这两套计数来自两个不同日志源，不能直接相等。</b>
        #   inserts = 账本侧（[rscc-ledger] 的 insert_network / insert_network/residual）
        #   scrap / returned = 追踪侧（[rscc-trace] 的 take_from_machine / task_finished_residual）
        # 日志轮转只截断其中一侧时会出现「账本说入网 1 件、追踪侧 0 条」——
        # 那是<b>证据缺失</b>，不是「凭空生成」。因此只在两侧都有数据时做守恒。
        if inserts > 0 and (scrap + returned) == 0:
            print("  [SKIP] 金板守恒②：账本记入网 %d 件，但追踪侧没有对应来源行"
                  "（日志轮转截断了追踪段）—— 归因跳过 [FIXED-OLD-LOG 归因]" % inserts)
        else:
            check("金板守恒②: 每一次「金板入网」都有来源 —— 抽奖废料或任务结束残留回流，"
                  "两者之和 ≥ 入网量（不凭空生成）",
                  inserts == 0 or (scrap + returned) >= inserts,
                  "入网 %d vs 废料 %d + 残留 %d" % (inserts, scrap, returned))
        voided = [l for l in lines if "voided" in l and "golden_sheet" in l]
        check("金板守恒③: 没有「网络假收下（voided）」告警 ⇒ 没有金板被外部存储吞掉", not voided)

# ---- 6.6 挂起当刻即停 / 继续后恢复：用用户下一次跑出来的日志自动断言 ----
if lines is None:
    print("[SKIP] 找不到日志 —— 挂起窗口断言跳过")
else:
    susp_lines = [l for l in lines if "watchdog suspend task=" in l]
    if not susp_lines:
        print("[SKIP] 日志里没有 `watchdog suspend task=`（本次跑的是修复前构建 / 未手动挂起）—— "
              "挂起窗口断言跳过；修复后重跑一次即会自动断言")
    else:
        pos = re.compile(r"at=\((-?\d+),(-?\d+),(-?\d+)\)")
        linked = {}
        for line in lines:
            m = re.search(r"exporter@\((-?\d+),(-?\d+),(-?\d+)\) linked=chamber@\((-?\d+),(-?\d+),(-?\d+)\)", line)
            if m:
                linked["(%s,%s,%s)" % (m.group(1), m.group(2), m.group(3))] = \
                    "(%s,%s,%s)" % (m.group(4), m.group(5), m.group(6))
        ok_window = True
        details = []
        for line in susp_lines:
            chamber_pos = "(%s,%s,%s)" % pos.search(line).groups()
            start = line
            exporters = [e for e, c in linked.items() if c == chamber_pos]
            resumed = None
            for later in lines[lines.index(line):]:
                if "watchdog resume task=" in later and pos.search(later) and \
                        "(%s,%s,%s)" % pos.search(later).groups() == chamber_pos:
                    resumed = later
                    break
            window = lines[lines.index(line):(lines.index(resumed) if resumed else len(lines))]
            for entry in window:
                if entry is start or entry is resumed:
                    continue
                if ("chamber@%s pull {" % chamber_pos) in entry \
                        or ("chamber@%s return {" % chamber_pos) in entry \
                        or any(("exporter@%s push {" % e) in entry for e in exporters):
                    if "exporter@%s linked=" % (exporters[0] if exporters else "") in entry:
                        continue    # 只看搬运行，不看策略安装行
                    ok_window = False
                    details.append(entry.strip()[:140])
                    break
        check("挂起当刻即停（日志取证）：挂起那一刻起、点「继续」之前，该仓与它的输出总线**没有任何**"
              " pull / return / push 搬运行",
              ok_window, "挂起窗口里仍有搬运: %s" % details)
        resumed_any = any("watchdog resume task=" in l for l in lines)
        if resumed_any:
            print("  注: 日志含 `watchdog resume task=` ⇒ 可人工核对「继续」之后同一仓重新出现 pull/bus_push")

print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
