# -*- coding: utf-8 -*-
"""round59 自检：**「结构 / 目标」缓存的失效新鲜度** —— 用户提议的「先存好、到时候直接发」到底能不能做。

用户原话：
    「有没有种可能？我们可以使用缓存机制，缓存这些目标。就是设定好后自动保存，然后到时候就直接发送，
      而不是说要发送了再去找。这样的话应该会快一点吧，我不确定。而且关键是要是变更的话呢，该怎么更新，
      我也不知道。」

本脚本要回答的**不是**「缓存好不好」，而是「本轮新增的那一层结构缓存，在每一次结构变更之后，
下一次读取拿到的是不是新值」。因此断言分六组：

  ① **每一步查找的复杂度（源码锚点）**：确认「供料目标」本身就是一次
     `level.getChunkAt(pos).getBlockEntity(pos, CHECK)` + 一次朝向 `relative`（O(1)，无 BFS），
     且它外面已经包着「每 tick 一份」的备忘（`supplyTargets`）—— 所以「发给谁」不需要新缓存。

  ② **真值表：逐事件断言「事件 → epoch 增长 → 下一次读取必为重建后的新值」**。
     事件清单（每个都是独立 case）：方块放置 / 方块破坏 / 方块被替换 / 扳手剪线与套壳
     （走世界更新）/ 成链成员变化 / 网络节点增删 / 区块卸载 / 区块加载 / 同一 tick 内多次变更。

  ③ **反例：故意漏掉某个失效钩子时，epoch 兜不兜得住** —— 这是本轮的核心。
     模型里显式提供「不挂钩子」的对照组：证明「靠显式清理」会在漏一个钩子时**永久冻结**，
     而「靠 epoch」在同样情况下仍会在下一次读取重建。

  ④ **动态量没有被缓存**（源码锚点）：存货 / 机器忙闲 / 拒收 / 在途与名额计数都不在任何缓存结构里，
     并逐条给出「它们为什么不能被缓存」的源码依据（本工程「检测失败」类 bug 的成因）。

  ⑤ **TTL 兜底存在**（锚点）：`BUS_SCHEDULE_INTERVAL_TICKS = 20` 的整表重建仍在。

  ⑥ **卸载清理存在**（锚点）：`ChunkEvent.Unload` / `ChunkEvent.Load` 都推 epoch；
     备忘只活一个 tick 且不记进 NBT，因此不存在跨区块 / 跨存档的强引用滞留。

并且：**Python 模型必须与 Java 实现逐字同构** —— 模型里的判定谓词是从
`SequenceExecutionChamberBlockEntity#chainMembers()` 的正文里**抠出来**的，脚本会断言两者一致，
避免「模型过了、实现改了」这种自欺。

可重复执行：python tools/selfcheck_round59_binding_cache_freshness.py
     → 全通过输出 `SELFCHECK OK (n checks)`；任一断言失败 → 退出码 1 并列出反例。

**没有实机验证**：本脚本只做源码交叉验证 + 模型推演，不能替代游戏内验证。
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


def method_body(text, signature):
    """抓出「含 signature 的那个方法」的正文（大括号配对）。"""
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
# ① 查找复杂度：为什么「供料目标」不需要新缓存（源码锚点）
# =====================================================================================
print("=" * 96)
print("① 查找复杂度：供料目标解析是 O(1) 方块查询，且外层已有每 tick 备忘")
print("=" * 96)

BE = rel_read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))
EXPORTER_MIXIN = rel_read(os.path.join("mixin", "exporter", "AbstractExporterBlockEntityMixin.java"))
LINK_SEARCH = rel_read(os.path.join("support", "RsccWireLinkSearch.java"))
EPOCH = rel_read(os.path.join("support", "RsccStructureEpoch.java"))
MOD = rel_read("RS_Create_Compat.java")

supply_target = method_body(BE, "private static BlockPos supplyTargetOf(")
check("① 供料目标解析 = 「朝向 relative」+「getChunkAt().getBlockEntity(CHECK)」，O(1)、无 BFS",
      supply_target is not None
      and "level.getChunkAt(exporterPos).getBlockEntity(exporterPos, LevelChunk.EntityCreationType.CHECK)"
          in supply_target
      and "rscc$supplyTargetPos()" in supply_target
      and "BFS" not in supply_target,
      "供料目标解析的实现变了 ⇒ 复杂度结论必须重查")

check("① 总线侧的供料目标同样只是「朝向那一格」O(1)（不含搜索）",
      "public BlockPos rscc$supplyTargetPos()" in EXPORTER_MIXIN
      and "AbstractDirectionalBlock.tryExtractDirection(self.getBlockState())" in EXPORTER_MIXIN
      and "self.getBlockPos().relative(direction)" in EXPORTER_MIXIN)

supply_targets = method_body(BE, "private List<BlockPos> supplyTargets(final boolean wholeChain) {")
check("① 「本仓在给谁供料」已有每 tick 一份的备忘（supplyTargetsProbeTick / 两条口径惰性各算一次）",
      supply_targets is not None
      and "supplyTargetsProbeTick" in supply_targets
      and "supplyTargetsProbeChain" in supply_targets
      and "supplyTargetsProbeSelf" in supply_targets
      and "now != supplyTargetsProbeTick" in supply_targets,
      "备忘被移除了？那「按总线循环内部反复枚举」的平方成本会回来")

check("① 归属判定走 20 tick 缓存 + 事件驱动失效（BFS 不是每 tick 跑）",
      "RSCC_LINK_CACHE_TICKS = 20" in EXPORTER_MIXIN
      and "rscc$linkedCacheExpireAt" in EXPORTER_MIXIN
      and "rscc$invalidateLinkCache()" in EXPORTER_MIXIN
      and "RsccWireLinkSearch" in EXPORTER_MIXIN)

check("① 唯一的「结构性、且原先没有任何备忘」的热查询是 chainMembers（本轮就是给它补上的）",
      method_body(BE, "public List<SequenceExecutionChamberBlockEntity> chainMembers() {") is not None
      and "chainMembersProbeTick" in BE
      and "chainMembersProbeEpoch" in BE
      and "chainMembersMemo" in BE)

# =====================================================================================
# ② Python 模型：结构与 Java 实现逐字同构（谓词从源码里抠出来比对）
# =====================================================================================
print()
print("=" * 96)
print("② 模型与实现同构：从 chainMembers() 正文里抠出谓词，与 Python 模型逐字比对")
print("=" * 96)

chain_body = method_body(BE, "public List<SequenceExecutionChamberBlockEntity> chainMembers() {")
check("② 抠出了 chainMembers() 的正文", chain_body is not None)
if chain_body is not None:
    # 把 Java 谓词规范化成「可比较的记号」，避免空格 / 换行差异造成假失败
    normalized = re.sub(r"\s+", " ", chain_body)
    java_guard = ("if (memo != null && tick != Long.MIN_VALUE && tick == chainMembersProbeTick "
                  "&& epoch == chainMembersProbeEpoch) {")
    java_recompute = "final List<SequenceExecutionChamberBlockEntity> members = collectUpstream(chainHead());"
    java_store = ("chainMembersProbeTick = tick; chainMembersProbeEpoch = epoch; chainMembersMemo = members;")
    check("② Java 侧命中条件 = 非空 && 有 tick && 同 tick && 同 epoch",
          java_guard in normalized, "实际：%s" % normalized[:400])
    check("② Java 侧未命中时重算（collectUpstream(chainHead()) + 按坐标排序）",
          java_recompute in normalized
          and "members.sort(Comparator.comparingLong(member -> member.worldPosition.asLong()));" in normalized)
    check("② Java 侧把「本次 tick + 本次 epoch + 结果」三者一起写回（缺一项就会读到旧值）",
          java_store in normalized)
    check("② Java 侧无世界（tick == Long.MIN_VALUE）时不写备忘（卸载边界不缓存）",
          "if (tick != Long.MIN_VALUE) {" in normalized
          and "final Level level = getLevel();" in normalized)
else:
    check("② Java 侧命中条件", False, "正文缺失")

# ---- 模型：与上面抠出来的谓词一一对应 ----
# 两个哨兵刻意<b>不</b>用 None：None 是 Python「参数未传」的默认值，用它当哨兵会被
# `if tick is None: tick = tick_of()` 吃掉，从而把「无世界」误当成「取当前 tick」。
EPOCH_UNSET = -(2 ** 63)  # Java 里是 Long.MIN_VALUE（「从未写入」的哨兵）
NO_TICK = -(2 ** 63)      # Java 里是 tick == Long.MIN_VALUE（无世界）


class ChainMemo(object):
    """镜像 SequenceExecutionChamberBlockEntity#chainMembers 的备忘（逐字同构）。

    `tick_of()` / `epoch_of()` 对应 Java 正文里的 `level.getGameTime()` 与
    `RsccStructureEpoch.current()`：都必须在读取的那一刻求值。
    """

    def __init__(self, recompute, tick_of, epoch_of):
        self._recompute = recompute          # 相当于 collectUpstream(chainHead())
        self._tick_of = tick_of
        self._epoch_of = epoch_of
        self.memo = None
        self.memo_tick = NO_TICK
        self.memo_epoch = EPOCH_UNSET
        self.recomputes = 0                 # 统计真算次数（用来证明「同一份世界状态只算一次」）

    def read(self, tick=None, epoch=None):
        if tick is None:
            tick = self._tick_of()
        if epoch is None:
            epoch = self._epoch_of()
        memo = self.memo
        if (memo is not None and tick is not NO_TICK
                and tick == self.memo_tick and epoch == self.memo_epoch):
            return memo                      # 命中：直接复用
        members = self._recompute()          # 未命中：重算
        if tick is not NO_TICK:              # 无世界时不写备忘
            self.memo_tick = tick
            self.memo_epoch = epoch
            self.memo = members
            self.recomputes += 1
        return members


class World(object):
    """极简世界模型：只模拟「结构」与「结构版本」，不模拟任何动态量。"""

    def __init__(self, chain):
        self.chain = list(chain)             # 当前链成员（结构）
        self.epoch = 1                       # RsccStructureEpoch.current()
        self.tick = 0
        self.computations = 0                # 「真算链成员」的次数

    def structure_changed(self, mutate=None):
        """结构变化：改结构 + 推 epoch（这正是 RsccStructureEpoch.bump 的语义）。"""
        if mutate is not None:
            mutate(self.chain)
        self.epoch += 1

    def new_tick(self):
        self.tick += 1

    def memo(self):
        """把「读取时的当前 tick / 当前 epoch」交给备忘自己取 —— 与 Java 里
        `final Level level = getLevel(); ... RsccStructureEpoch.current()` 完全同构：
        参数必须在**读取的那一刻**求值，不能在调用点写死（写死就会掩盖失效 bug）。"""
        def recompute():
            self.computations += 1
            return list(self.chain)
        return ChainMemo(recompute, lambda: self.tick, lambda: self.epoch)


# =====================================================================================
# ③ 变更后立刻看到新目标：逐事件真值表（每个事件一个 case）
# =====================================================================================
print()
print("=" * 96)
print("③ 逐事件真值表：事件 → epoch 增长 → 下一次读取必为重建后的新值")
print("=" * 96)

BASE = ["m0", "m1", "m2"]


def case_place(chain):
    chain.append("m3")                       # 放置一台新执行仓（成链成员 +1）


def case_break(chain):
    chain.remove("m2")                       # 拆掉一台执行仓（成链成员 -1）


def case_replace(chain):
    chain.remove("m1")
    chain.append("mX")                       # 同一格被替换成别的东西


def case_wrench_cut(chain):
    chain.remove("m2")                       # 扳手剪线 / 套壳：链被切断


def case_chain_change(chain):
    chain.reverse()                          # 成链成员集合变了（顺序 / 归属变）


def case_network_split(chain):
    del chain[1:]                            # 网络拆分：只剩链首那一台


def case_network_merge(chain):
    chain.extend(["m8", "m9"])               # 网络合并：多了两台


def case_chunk_unload(chain):
    chain.remove("m2")                       # 区块卸载：那一段推不出来了


def case_chunk_load(chain):
    chain.append("m2")                       # 区块加载回来：那一段又能推出来了


def case_config_change(chain):
    chain.clear()                            # 配方类型改了：整条链不再兼容 ⇒ 只剩自己
    chain.append("m0")


EVENTS = [
    ("方块放置（新执行仓加入本链）", case_place, ["m0", "m1", "m2", "m3"]),
    ("方块破坏（执行仓被拆）", case_break, ["m0", "m1"]),
    ("方块被替换（同格换人）", case_replace, ["m0", "m2", "mX"]),
    ("扳手剪线 / 套壳（链被切断）", case_wrench_cut, ["m0", "m1"]),
    ("成链成员变化（顺序 / 归属变）", case_chain_change, ["m2", "m1", "m0"]),
    ("网络拆分（只剩链首）", case_network_split, ["m0"]),
    ("网络合并（多了两台）", case_network_merge, ["m0", "m1", "m2", "m8", "m9"]),
    ("区块卸载（那一段推不出来）", case_chunk_unload, ["m0", "m1"]),
    ("区块加载（那一段又可见）", case_chunk_load, ["m0", "m1", "m2", "m2"]),
    ("配置变更（配方类型改了 ⇒ 链不兼容）", case_config_change, ["m0"]),
]

for name, mutate, expected in EVENTS:
    world = World(BASE)
    memo = world.memo()
    # 先建缓存（上一 tick 读过一次，因此备忘已就绪）
    world.new_tick()
    before = memo.read()
    # 事件发生：结构变化 ⇒ epoch +1
    epoch_before = world.epoch
    world.structure_changed(mutate)
    # 同一 tick 内立刻读取：必须看到新值（epoch 不匹配 ⇒ 重建）
    after = memo.read()
    check("③ 事件「%s」：epoch 增长（%d → %d）" % (name, epoch_before, world.epoch),
          world.epoch == epoch_before + 1)
    check("③ 事件「%s」：变更后立刻读到新值（不是旧的 %r）" % (name, before),
          after == expected, "after=%r expect=%r" % (after, expected))
    check("③ 事件「%s」：新值与旧值确实不同（这条 case 真的在测「变更」）" % name,
          after != before, "before=%r after=%r" % (before, after))

# ---- 同一 tick 内多次变更：最后一次读到的必须是最新结构 ----
world = World(BASE)
memo = world.memo()
world.new_tick()
memo.read()
world.structure_changed(lambda c: c.append("m3"))
world.structure_changed(lambda c: c.append("m4"))
world.structure_changed(lambda c: c.remove("m0"))
check("③ 同一 tick 内连续 3 次结构变更 ⇒ 读取拿到的是「全部变更之后」的结构",
      memo.read() == ["m1", "m2", "m3", "m4"], "recomputes=%d" % memo.recomputes)
check("③ 同一 tick 内连续变更 ⇒ 变更之后的那一次读取必须重建（而不是命中变更之前那个版本的结果）",
      memo.recomputes == 2,
      "recomputes=%d（首次建 1 次 + 变更之后第 1 次读取重建 1 次；此后同版本继续命中）"
      % memo.recomputes)

# ---- 没有结构变化时：同一 tick 内只算一次（这正是「快一点」的来源） ----
world = World(BASE)
memo = world.memo()
world.new_tick()
for _ in range(50):                          # 模拟「按总线 × 按类别」反复问链成员
    memo.read()
check("③ 同一 tick、无结构变化 ⇒ 问 50 次只真算 1 次（这正是用户想要的「不用每次都去找」）",
      memo.recomputes == 1, "recomputes=%d" % memo.recomputes)
world.structure_changed()
memo.read()
world.new_tick()                             # 换 tick：tick 兜底也必须让它只算一次
for _ in range(50):
    memo.read()
check("③ 换 tick、无结构变化 ⇒ 也只真算 1 次（tick 兜底不会退化成每次都算）",
      memo.recomputes == 3, "recomputes=%d（建缓存 + 变更后 1 次 + 新 tick 1 次）" % memo.recomputes)

# ---- 无世界（区块卸载边界）：不写备忘、不抛 ----
world = World(BASE)
memo = world.memo()
check("③ 无世界（tick 哨兵）时不写备忘（卸载边界不缓存，且不抛异常）",
      memo.read(tick=NO_TICK, epoch=world.epoch) == BASE and memo.memo is None)

# =====================================================================================
# ④ 反例：故意漏掉某个失效钩子 —— 显式清理会永久冻结，epoch 兜得住
# =====================================================================================
print()
print("=" * 96)
print("④ 反例：漏掉失效钩子时，显式清理 vs epoch（本轮的核心安全性论证）")
print("=" * 96)


class ExplicitCleanupMemo(object):
    """反例模型：只靠「在事件里显式清缓存」的写法（本工程历史上被它坑过）。"""

    def __init__(self, recompute):
        self._recompute = recompute
        self.memo = None

    def read(self):
        if self.memo is None:
            self.memo = self._recompute()
        return self.memo

    def on_event_hook(self, mutate):
        """显式钩子：只有被调用的那一条钩子才会清缓存。"""
        mutate()
        self.memo = None


EVENT_HOOKS = ("block_place", "block_break", "wrench_cut", "chunk_unload")

# 对照：漏挂 wrench_cut
MISSING_HOOK = "wrench_cut"
world = World(BASE)
explicit = ExplicitCleanupMemo(lambda: list(world.chain))
first = explicit.read()
world.structure_changed(case_wrench_cut)      # 事件真的发生了
for hook in EVENT_HOOKS:
    if hook == MISSING_HOOK:
        continue                                  # ← 就是这里漏了
    # （其它钩子对这次事件无关，因此不会清）
    pass
stale = explicit.read()
check("④ 反例（显式清理 + 漏一个钩子）：读到的是**旧值** ⇒ 这就是永久冻结的成因",
      stale != world.chain and stale == first,
      "stale=%r 真值=%r" % (stale, world.chain))

world = World(BASE)
memoed = ChainMemo(lambda: list(world.chain), lambda: world.tick, lambda: world.epoch)
memoed.read()
world.structure_changed(case_wrench_cut)
check("④ epoch 版本号：**同一个漏掉的钩子**下，下一次读取照样重建出真值（兜住了）",
      memoed.read() == world.chain,
      "got=%r 真值=%r" % (memoed.read(), world.chain))

# 再给一个「连方块事件都没有」的极端反例：只有 tick 兜底
world = World(BASE)
memoed = ChainMemo(lambda: list(world.chain), lambda: world.tick, lambda: world.epoch)
world.new_tick()
first = memoed.read()
world.chain.append("silent")                  # 结构真的变了，但**没有**任何事件、epoch 没动
same_tick = memoed.read()
world.new_tick()
next_tick = memoed.read()
check("④ 极端反例（连版本号都没推）：同一 tick 内读到旧值（已知且可接受的上界 = 1 tick）",
      same_tick == first)
check("④ 极端反例（连版本号都没推）：下一个 tick 必然自愈（tick 兜底仍在 ⇒ 不会永久冻结）",
      next_tick == world.chain, "next=%r 真值=%r" % (next_tick, world.chain))

# =====================================================================================
# ⑤ 动态量没有被缓存（源码锚点 + 逐条理由）
# =====================================================================================
print()
print("=" * 96)
print("⑤ 动态量没有被缓存：存货 / 机器忙闲 / 拒收 / 在途名额")
print("=" * 96)

# 「机器里此刻压着什么」= 容器内容 ⇒ 一律现读（本工程「同一工位最多一份」不变式的唯一凭据）
occupied_items = method_body(BE, "private static Set<Item> occupiedItemsOf(")
check("⑤ 容器内容探针（occupiedItemsOf）现读：逐槽 getStackInSlot，不进任何缓存结构",
      occupied_items is not None
      and "handler.getSlots()" in occupied_items
      and "handler.getStackInSlot(i)" in occupied_items
      and "occupiedAtProbeCache" not in occupied_items
      and "chainMembersMemo" not in occupied_items)

machine_busy = method_body(BE, "private boolean machineBusyOnPipeline(")
check("⑤ 「机器忙闲」刻意现读（源码里写明了「看得见本 tick 自己刚推的那一份」）",
      machine_busy is not None
      and "occupiedItemsOf(level, target)" in machine_busy
      and "刻意现读" in BE,
      "机器忙闲被判据缓存了？那就会重现「份额外的总线把同一份推给第二台机器」")

holds_item = method_body(BE, "private boolean supplyTargetHoldsItem(")
check("⑤ 「工位上是否已握着这件料」的容器部分现读（只缓存结构性的工位归属）",
      holds_item is not None
      and "holdsItemAt(level, target, item)" in holds_item
      and "holdsItemAt(level, station, item)" in holds_item)

# 新增的备忘结构里只允许出现「链成员」（纯结构），不允许出现任何动态量
memo_fields = ["chainMembersMemo", "chainMembersProbeTick", "chainMembersProbeEpoch"]
dynamic_tokens = ("occupied", "busy", "inFlight", "inflight", "refus", "available", "stored",
                  "reserved", "amount", "capacity", "blocked")
for field in memo_fields:
    decl = re.search(r"[^\n]*\b%s\b[^\n]*;" % re.escape(field), BE)
    text = decl.group(0) if decl else ""
    check("⑤ 新备忘字段 %s 的声明里没有任何动态量记号" % field,
          decl is not None and not any(tok.lower() in text.lower() for tok in dynamic_tokens),
          "声明=%r" % text.strip())

check("⑤ 新增备忘的读取路径不触碰「存货 / 可用量 / 拒收 / 在途」（chainMembers 正文只有 epoch / tick / 链推导）",
      chain_body is not None
      and not any(tok in chain_body for tok in
                  ("storage", "getAmount", "availableForTake", "pendingExtraction",
                   "refus", "inFlight", "occupied", "busy")),
      "备忘里混进了动态量：那正是本工程「检测失败」类 bug 的成因")

# 现有的「动态量」判据必须仍然存在（没有被本轮顺手缓存掉）
for anchor, why in (
    ("occupiedAtProbeCache", "「本总线目标机器这一 tick 压着什么」的每 tick 探针必须仍在"),
    ("machineReservedRecipe", "共机排队判据必须仍在（它按 tick 只算一次）"),
    ("startCapacityForRecipe", "起步原料在制名额必须仍在"),
    ("inFlightUnitsForRecipe", "在途件计数必须仍在"),
    ("blockedByMachineQueue", "共机排队闸门必须仍在"),
):
    check("⑤ 既有动态判据仍在：%s" % why, anchor in BE)

# =====================================================================================
# ⑥ TTL 兜底 + 卸载清理（源码锚点）
# =====================================================================================
print()
print("=" * 96)
print("⑥ TTL 兜底与卸载清理")
print("=" * 96)

check("⑥ TTL 兜底仍在：BUS_SCHEDULE_INTERVAL_TICKS = 20 的整表重建",
      "BUS_SCHEDULE_INTERVAL_TICKS = 20" in BE
      and "busScheduleCooldown = BUS_SCHEDULE_INTERVAL_TICKS;" in BE
      and "unitsCache = collectUnits(level);" in BE
      and "busCategoriesCache = computeBusCategories();" in BE,
      "20 tick 整表重建被删了？那最外层保险就没了")

check("⑥ 本轮备忘的寿命比 TTL 更短（一个 tick）⇒ 不需要额外的 TTL",
      chain_body is not None and "tick == chainMembersProbeTick" in chain_body)

check("⑥ 区块卸载推 epoch（卸载时方块没有被拆 ⇒ NeighborNotify 不会触发，必须单独挂）",
      "public void onChunkUnload(final ChunkEvent.Unload event)" in EPOCH
      and "public void onChunkLoad(final ChunkEvent.Load event)" in EPOCH
      and EPOCH.count("bump(serverLevel);") == 3,
      "ChunkEvent 钩子少了？那「链上一台已被卸载」会被当成还在")

check("⑥ 邻块事件钩子存在且只做一次自增（不在事件回调里搜索 / 不取方块实体）",
      "public void onNeighborNotify(final BlockEvent.NeighborNotifyEvent event)" in EPOCH
      and "epoch++;" in EPOCH
      and "getBlockEntity" not in EPOCH
      and "getBlockState" not in EPOCH)

check("⑥ epoch 类已注册到事件总线（否则版本号永远不动 = 死缓存）",
      "RsccStructureEpoch.register();" in MOD
      and "public static void register()" in EPOCH)

check("⑥ 不泄漏：epoch 类不缓存任何东西（只有一个 long 计数器，没有按世界 / 按坐标的表）",
      "WeakHashMap" not in EPOCH and "static long epoch" in EPOCH
      and "new HashMap" not in EPOCH and "new ArrayList" not in EPOCH
      and re.search(r"\bMap<\s*(Level|BlockPos)", EPOCH) is None,
      "epoch 类里出现了容器 ⇒ 必须补卸载清理")

check("⑥ 备忘不进 NBT（不跨存档 / 不跨维度持久化）",
      "chainMembersMemo" not in method_body(BE, "protected void saveAdditional(")
      if method_body(BE, "protected void saveAdditional(") else True)

check("⑥ 备忘在无世界时不写（区块卸载边界不留下残留快照）",
      chain_body is not None and "if (tick != Long.MIN_VALUE) {" in re.sub(r"\s+", " ", chain_body))

# =====================================================================================
# 场景矩阵：结构版本 × tick → 命中 / 重建
# =====================================================================================
print()
print("=" * 96)
print("场景矩阵（同一份备忘：结构版本是否变 × tick 是否变 → 命中 / 重建）")
print("=" * 96)
print("%-14s %-10s %-10s %s" % ("场景", "epoch", "tick", "结果"))
print("-" * 96)
world = World(BASE)
memo = world.memo()
world.new_tick()
memo.read(world.tick, world.epoch)          # 建缓存
rows = [
    ("同一 tick / 版本未变", world.tick, world.epoch, "命中（复用）"),
    ("同一 tick / 版本 +1", world.tick, world.epoch + 1, "重建（立刻看到新值）"),
    ("新 tick / 版本未变", world.tick + 1, world.epoch, "重建（tick 兜底）"),
    ("新 tick / 版本 +1", world.tick + 1, world.epoch + 1, "重建"),
]
for name, tick, epoch, expect in rows:
    before = memo.recomputes
    memo.read(tick, epoch)
    got = "命中（复用）" if memo.recomputes == before else "重建（立刻看到新值）"
    ok = (got.startswith("命中") and expect.startswith("命中")) or \
         (got.startswith("重建") and expect.startswith("重建"))
    print("%-14s %-10s %-10s %s" % (name, epoch, tick, got))
    check("场景矩阵：%s ⇒ %s" % (name, expect), ok, "实际=%s" % got)
print()

if PROBLEMS:
    print("=" * 96)
    print("SELFCHECK FAILED：%d 项不通过" % len(PROBLEMS))
    for problem in PROBLEMS:
        print("  - %s" % problem)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
