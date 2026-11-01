# -*- coding: utf-8 -*-
"""round75 自检：**「可用机器」列表的新鲜度**（用户实机反馈：执行仓配置正确，列表里却一台都没有）。

用户原话（逐字，节选）：
    「你那一个检查可用的序列执行器的时候好像有点问题：我明明已经正确配置了一个序列执行器……
      然后我在序列样板终端查看它还是显示没有可用机器，然后我点开那个详细配置界面，它也是啥机器都没有。
      我不知道你是刷新机制是没做好还是什么样子的，这个也是个问题，
      要保证时时刻刻它都能正常检测同时也要性能。」

本脚本要钉死三件事（不是「看起来对」，而是**判据可复跑 + 反例必判负**）：

  A. **完整数据流**（源码锚点）：终端界面 → 请求包 → 服务端现算 → 客户端镜像 → 「无可用机器」的
     渲染条件。并证明「可用机器集合」这条链上**没有任何缓存**（包括 chainMembers 的每 tick 备忘
     也不参与它）⇒ 列表不可能「陈旧」，只可能被**入口条件**判空。

  B. **入口条件**（第 75 轮真正的根因）：无线「序列样板终端」的虚拟方块实体（不放在世界里、
     用反射 new 出来的那个）**漏绑 RS 网络** ⇒ `listChambers()` 第一行 `network == null` 直接返回
     空列表 ⇒ 终端行与机器选择子界面**永远**为空。这里断言「所有会打开按网络枚举界面的虚拟方块实体
     模式」都必须绑网络（4/4），并把「漏掉 sequence 那一处」的修前写法喂给同一批判据 ⇒ 必须判负。

  C. **写操作 × 失效对照表 + 模型**：
     * 所有会改变「哪些执行仓可用 / 链成员 / 机器名」的写操作，逐一断言源码里有对应的
       `RsccStructureEpoch.bump(...)` + `pushAvailabilityChanged(...)`（配置写入）或事件钩子（结构写入）；
     * 反例自证：把这些失效调用从方法体里删掉，同一批判据 ⇒ 判负；
     * 可复跑的小模型（与 Java 判据逐字同构）：证明「配置完成 → 下一次查询能看到」在**缓存存在时**
       仍然成立（因为可用机器判定不读那份备忘），同时证明缺 bump 时**链成员备忘**会在同一 tick 内陈旧
       —— 这是补 bump 的理由，也是「为什么它不是本次空列表的成因」的书面结论。

可重复执行：python tools/selfcheck_round75_machine_list_freshness.py
     → 全通过输出 `SELFCHECK OK (n checks)`；任一断言失败 → 退出码 1 并列出反例。

**没有实机验证**：本脚本只做源码交叉验证 + 模型推演；「在游戏里配置一台执行仓再打开无线终端」
必须由人在客户端里做（本会话无法实机操作 GUI）。
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
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

CHAMBER = os.path.join(PKG, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
TERMINAL = os.path.join(PKG, "block", "entity", "SequencePatternTerminalBlockEntity.java")
EPOCH = os.path.join(PKG, "support", "RsccStructureEpoch.java")
SYNC = os.path.join(PKG, "network", "SyncChamberListPacket.java")
REQUEST = os.path.join(PKG, "network", "RequestChamberListPacket.java")
CANDIDATES = os.path.join(PKG, "network", "AssemblyMachineCandidatesPacket.java")
ITEM = os.path.join(PKG, "item", "AdvancedRemoteTerminalItem.java")
SCREEN = os.path.join(PKG, "client", "screen", "SequencePatternTerminalScreen.java")
MENU = os.path.join(PKG, "menu", "SequencePatternTerminalMenu.java")
MOD = os.path.join(PKG, "RS_Create_Compat.java")

CHECKS = [0]
PROBLEMS = []


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        PROBLEMS.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name,
                       "" if ok else ((" | " + detail) if detail else "")))


def section(title):
    print()
    print("=" * 96)
    print(title)
    print("=" * 96)


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def code_only(src):
    """剥掉注释：只对真实代码做判定（注释里会引用旧写法 / 会出现被禁的词汇）。"""
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def java_body(src, signature):
    """取以 signature 开头的方法的花括号体（含花括号，按括号配平）。"""
    start = src.find(signature)
    if start < 0:
        return ""
    open_idx = src.find("{", start)
    if open_idx < 0:
        return ""
    depth = 0
    for i in range(open_idx, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                return src[open_idx:i + 1]
    return ""


# -------------------------------------------------------------------------------------
# 判据（可复用于真实源码 / 修前写法 / 植入式反例）
# -------------------------------------------------------------------------------------

# 「配置写入」必须做到的失效：结构版本 +1（链成员备忘立即作废）+ 事件驱动推送（开着的终端立刻重取）。
BINDING_INVALIDATION = (
    "RsccStructureEpoch.bump(",
    "pushAvailabilityChanged(",
)


def has_binding_invalidation(body):
    return all(token in body for token in BINDING_INVALIDATION)


def strip_binding_invalidation(body):
    """反例：把修前的写法（没有 bump / 没有推送）还原出来。"""
    kept = [line for line in body.splitlines()
            if "RsccStructureEpoch.bump(" not in line and "pushAvailabilityChanged(" not in line]
    return "\n".join(kept)


# 「结构写入」（方块 / 区块事件）必须推结构版本。
def has_structure_bump(body):
    return "epoch++;" in body or "bump(serverLevel);" in body or "RsccStructureEpoch.bump(" in body


def strip_structure_bump(body):
    kept = [line for line in body.splitlines()
            if "epoch++;" not in line and "bump(serverLevel);" not in line]
    return "\n".join(kept)


# 虚拟方块实体必须绑定网络（真网络或空网络兜底）—— 少一处就等于「界面开着但列表入口为空」。
VIRTUAL_BINDING = ("bindNetworkSafely(", "bindEmptyNetworkToVirtualBe(")


def has_virtual_binding(body):
    return all(token in body for token in VIRTUAL_BINDING)


def strip_virtual_binding(body):
    kept = [line for line in body.splitlines()
            if "bindNetworkSafely(" not in line and "bindEmptyNetworkToVirtualBe(" not in line]
    return "\n".join(kept)


# =====================================================================================
# A. 完整数据流（源码锚点）
# =====================================================================================
section("A. 数据流：终端界面 → 请求包 → 服务端现算 → 客户端镜像 → 「无可用机器」条件")

screen = code_only(read(SCREEN))
terminal = code_only(read(TERMINAL))
request = code_only(read(REQUEST))
sync = code_only(read(SYNC))
menu = code_only(read(MENU))
candidates = code_only(read(CANDIDATES))

check("A1 终端界面的「无可用机器」= 未指派 **且** 该步配方类型的候选为空（两者缺一不可）",
      "return !isMachineAssigned(globalStep) && chambersFor(recipeTypeOf(globalStep)).isEmpty();" in screen,
      "渲染条件变了 ⇒ 「空列表」与「请求没回来」必须重新区分")

check("A2 机器选择子界面（用户说的「详细配置界面」）与行内列表**同一份数据**：都用 chambersForType(该步类型)",
      "final List<SyncChamberListPacket.Entry> candidates = chambersForType(recipeType);" in screen
      and "for (final SyncChamberListPacket.Entry chamber : SyncChamberListPacket.getLastReceived()) {" in screen,
      "两条显示路径不同源的话，必须分别解释为什么都空")

check("A3 客户端只在 init() 发一次 RequestChamberListPacket（没有定时刷新，靠服务端事件推送）",
      screen.count("new RequestChamberListPacket(") == 1
      and "RequestChamberListPacket(\"\")" in screen,
      "出现第 2 处请求就要重新审「谁在刷新」")

check("A4 服务端按**当前打开的菜单**定位终端并**当场现算**（无服务端缓存）",
      "player.containerMenu instanceof SequencePatternTerminalMenu menu" in request
      and "terminal.listChambers()" in request
      and "terminal.listChambersFor(filter)" in request
      and "PacketDistributor.sendToPlayer(player, SyncChamberListPacket.from(chambers));" in request)

check("A5 服务端枚举候选机器的唯一实现：listChambers() 每次现读网络容器 + isBound()（不读任何备忘）",
      "network.getComponent(GraphNetworkComponent.class).getContainers()" in terminal
      and "if (chamber != null && chamber.isBound()) {" in terminal)

list_body = java_body(terminal, "public List<ChamberInfo> listChambers() {")
cache_tokens = ("chainMembersMemo", "chainMembersProbe", "RsccStructureEpoch", ".current()",
                "Memo", "memo", "Cache", "cache", "TTL")
check("A6 listChambers() 体内**没有**任何备忘 / 版本号 / TTL 记号（这条链上没有缓存可言）",
      list_body != "" and not any(token in list_body for token in cache_tokens),
      "命中=%s" % [t for t in cache_tokens if t in list_body])

null_guard = list_body.find("network == null")
first_empty = list_body.find("return List.of();")
check("A7 listChambers() 的**第一道门**是 network == null ⇒ 返回空列表（入口条件，不是陈旧数据）",
      null_guard >= 0 and first_empty > null_guard and first_empty - null_guard < 200,
      "入口条件改了 ⇒ 「永远空」的因果链必须重查")

check("A8 客户端镜像每次收包都换成新列表对象（派生缓存据身份失效，不可能拿旧对象冒充新快照）",
      "lastReceived = List.copyOf(packet.chambers())" in sync)

check("A9 「更换机器」（自动合成监视器）候选也是每次请求现算、客户端不缓存",
      "AssemblyWatchdog.machineCandidates(player, packet.taskId(), packet.stepIndex())" in code_only(read(
          os.path.join(PKG, "network", "AssemblyStepMachinePacket.java")))
      and "getContainers()" in code_only(read(os.path.join(PKG, "support", "AssemblyWatchdog.java")))
      and "第 75 轮：本包是「每次都现算」的" in read(CANDIDATES))

check("A10 客户端没有定时重发请求：整个界面里 RequestChamberListPacket 只出现 2 次（import + init 里那一次）",
      screen.count("RequestChamberListPacket") == 2
      and screen.count("sendToServer(new RequestChamberListPacket") == 1,
      "出现次数=%d（应为 2）" % screen.count("RequestChamberListPacket"))

# =====================================================================================
# B. 入口条件：无线「序列样板终端」的虚拟方块实体必须绑定网络（第 75 轮根因）
# =====================================================================================
section("B. 入口条件：所有「按网络枚举」的虚拟方块实体模式都必须绑定网络（4/4）")

item = code_only(read(ITEM))
# 打开界面时会按网络枚举数据的四个虚拟方块实体模式（样板终端 / 合成仓管理 / 序列样板终端 / 单元样板管理舱）
VIRTUAL_MODES = [
    ("样板终端（PatternGrid）", "private void openPatternGrid("),
    ("合成仓管理（AutocrafterManager）", "private void openAutocrafterManager("),
    ("序列样板终端（SequencePatternTerminal）", "private void openSequencePatternTerminal("),
    ("单元样板管理舱（UnitPatternManager）", "private void openUnitPatternManager("),
]
mode_bodies = {}
for label, signature in VIRTUAL_MODES:
    body = java_body(item, signature)
    mode_bodies[label] = body
    check("B1 虚拟方块实体模式「%s」绑定网络（真网络，绑不到则空网络兜底）" % label,
          has_virtual_binding(body),
          "缺 bindNetworkSafely/bindEmptyNetworkToVirtualBe ⇒ listChambers() 第一行就返回空")

check("B2 绑网络走的是**同一套** helper（不是各写一份）",
      "private static boolean bindNetworkSafely(" in item
      and "private static void bindEmptyNetworkToVirtualBe(" in item
      and "setVirtualBeNetwork(be, network, true);" in item)

check("B3 openModeScreen 的 MODE_SEQUENCE 分支确实指向被测方法（改映射也必须重查）",
      "case MODE_SEQUENCE -> openSequencePatternTerminal(player, stack, slotReference, title);" in item)

# 反例自证：把「漏绑」的修前写法喂回同一批判据 ⇒ 必须判负。
counterexample_ok = True
detail = []
for label, _ in VIRTUAL_MODES:
    broken = strip_virtual_binding(mode_bodies[label])
    if has_virtual_binding(broken):
        counterexample_ok = False
        detail.append(label)
check("B4 反例自证：删掉绑定语句（= 修前写法）后，同一批判据必须判负",
      counterexample_ok,
      "这些模式的判据在删掉绑定后仍然通过 = 判据是橡皮图章：%s" % (detail or "无"))

# =====================================================================================
# C. 写操作 × 失效 对照表（源码级）
# =====================================================================================
section("C. 写操作清单 × 失效调用（每个写操作都必须有自己的失效）")

chamber = code_only(read(CHAMBER))
epoch = code_only(read(EPOCH))
mod = code_only(read(MOD))

BINDING_WRITES = [
    ("配置：配方类型写入 setRecipeType", "public void setRecipeType(final String recipeType)"),
    ("配置：显示名写入 setChamberName", "public void setChamberName(final String name)"),
    ("链自愈：旧存档配置归位到新链首 sanitizeRayServer", "public void sanitizeRayServer()"),
    ("拆链：链首被拆时把配置移交给下家 handOverChainOnRemoval", "public void handOverChainOnRemoval()"),
]
write_bodies = {}
for label, signature in BINDING_WRITES:
    body = java_body(chamber, signature)
    write_bodies[label] = body
    check("C1 写操作「%s」同时做：结构版本 +1 与 事件驱动推送" % label,
          has_binding_invalidation(body),
          "缺 %s ⇒ 同一 tick 内链成员备忘 / 开着的终端看到旧值" %
          [t for t in BINDING_INVALIDATION if t not in body])

# 反例自证：删掉失效调用 ⇒ 判负。
bad = [label for label, _ in BINDING_WRITES
       if has_binding_invalidation(strip_binding_invalidation(write_bodies[label]))]
check("C2 反例自证：把 bump/push 从这 4 个写操作里删掉（= 修前写法）⇒ 同一批判据必须判负",
      not bad, "删掉后仍通过：%s" % (bad or "无"))

# 结构事件钩子（方块 / 区块）仍在
EVENT_HOOKS = [
    ("方块放置 / 破坏 / 被替换 / 扳手剪线 / 套壳", "public void onNeighborNotify(final BlockEvent.NeighborNotifyEvent event)"),
    ("区块卸载（卸载时方块没被拆 ⇒ 不会走 NeighborNotify）", "public void onChunkUnload(final ChunkEvent.Unload event)"),
    ("区块加载（那一段链重新可达）", "public void onChunkLoad(final ChunkEvent.Load event)"),
]
event_bodies = {}
for label, signature in EVENT_HOOKS:
    body = java_body(epoch, signature)
    event_bodies[label] = body
    check("C3 结构事件「%s」推结构版本" % label, has_structure_bump(body))
check("C4 反例自证：删掉事件钩子里的自增 ⇒ 判负",
      not [label for label, _ in EVENT_HOOKS
           if has_structure_bump(strip_structure_bump(event_bodies[label]))])

check("C5 结构任务总线已注册（否则版本号永远不动 = 死缓存）",
      "RsccStructureEpoch.register();" in mod and "public static void register()" in epoch)

check("C6 配置写入不经过任何方块事件 ⇒ 必须由写操作自己推版本（这条注释与实际调用都在）",
      "不经过任何方块事件" in read(EPOCH) and has_binding_invalidation(write_bodies[
          "配置：配方类型写入 setRecipeType"]))

# 推送只允许来自写入点（不是每 tick / 不是定时器）
push_sites = []
for name, path in (("chamber", CHAMBER), ("sync", SYNC)):
    push_sites.append((name, code_only(read(path)).count("pushAvailabilityChanged(")))
check("C7 pushAvailabilityChanged 只有「定义 1 处 + 四个写操作各 1 处」共 5 次出现（没有周期调用）",
      push_sites[1][1] == 1 and push_sites[0][1] == 4,
      "出现次数=%s" % push_sites)

check("C8 推送本身只发给「同网络 + 正开着终端」的玩家，且整条网络只现算一份列表",
      "player.containerMenu instanceof final SequencePatternTerminalMenu menu" in sync
      and "terminal.getNetworkOrNull() != network" in sync
      and "if (snapshot == null) {" in sync
      and "snapshot = List.copyOf(from(terminal.listChambers()).chambers());" in sync)

# =====================================================================================
# D. 可复跑的小模型（与 Java 判据逐字同构）
# =====================================================================================
section("D. 模型：缓存存在时「配置完成 → 下一次查询能看到」；链成员备忘缺 bump 才陈旧")

# ---- 判据同构：从 Java 正文抠出谓词，与模型逐字对照（防止「模型过了、实现改了」）----
compat_body = java_body(chamber, "private boolean chainRecipeCompatibleWith(")
is_bound_body = java_body(chamber, "public boolean isBound()")
check("D1 Java 侧「同配方才成链」判据仍是「一方为空也算兼容，双方非空且不等才拒绝」",
      "return mine.isEmpty() || theirs.isEmpty() || mine.equals(theirs);" in re.sub(r"\s+", " ", compat_body))
check("D2 Java 侧「可用」判据仍是「配方类型非空 且 名字非空」（两者都走链委托）",
      "final String type = getRecipeType();" in is_bound_body
      and "final String name = getChamberName();" in is_bound_body
      and "type != null && !type.isEmpty() && name != null && !name.isEmpty()" in is_bound_body)
check("D3 Java 侧链成员备忘的命中条件仍是「非空 && 有 tick && 同 tick && 同 epoch」",
      "if (memo != null && tick != Long.MIN_VALUE && tick == chainMembersProbeTick "
      "&& epoch == chainMembersProbeEpoch) {" in re.sub(r"\s+", " ", java_body(
          chamber, "public List<SequenceExecutionChamberBlockEntity> chainMembers()")))

NO_TICK = -(2 ** 63)


class Chamber(object):
    """镜像：own 字段 + 指向（faceTarget）+ 链成员备忘。"""

    def __init__(self, pos, points_to=None):
        self.pos = pos
        self.points_to = points_to   # 朝向那一格上的另一台执行仓（None = 指向空气 / 未加载）
        self.own_type = ""
        self.own_name = ""
        self.memo = None
        self.memo_tick = NO_TICK
        self.memo_epoch = -(2 ** 63)


class World(object):
    def __init__(self, chambers):
        self.chambers = list(chambers)
        self.tick = 1
        self.epoch = 1
        self.computations = 0

    # ---- 结构写入 ----
    def bump(self):
        self.epoch += 1

    def new_tick(self):
        self.tick += 1

    # ---- 与 Java 同构的链推导 ----
    @staticmethod
    def compatible(a, b):
        return (not a.own_type) or (not b.own_type) or a.own_type == b.own_type

    def effective_next(self, chamber):
        target = chamber.points_to
        if target is None or not self.compatible(chamber, target):
            return None
        if self.winning_predecessor(target) is not chamber:
            return None
        return target

    def winning_predecessor(self, target):
        winner = None
        for other in self.chambers:
            if other.points_to is target and self.compatible(other, target):
                if winner is None or other.pos < winner.pos:
                    winner = other
        return winner

    def chain_head(self, chamber):
        current = chamber
        visited = {current.pos}
        for _ in range(8):
            nxt = self.effective_next(current)
            if nxt is None or nxt.pos in visited:
                return current
            visited.add(nxt.pos)
            current = nxt
        return chamber

    def collect_upstream(self, start):
        members = []
        queue = [start]
        visited = {start.pos}
        while queue and len(members) < 8:
            current = queue.pop(0)
            members.append(current)
            predecessor = self.winning_predecessor(current)
            if predecessor is None or self.chain_head(predecessor) is not start:
                continue
            if predecessor.pos not in visited:
                visited.add(predecessor.pos)
                queue.append(predecessor)
        return members

    # ---- 被备忘的那条查询（链成员）----
    def chain_members(self, chamber):
        memo = chamber.memo
        if (memo is not None and self.tick != NO_TICK
                and self.tick == chamber.memo_tick and self.epoch == chamber.memo_epoch):
            return memo
        self.computations += 1
        members = self.collect_upstream(self.chain_head(chamber))
        chamber.memo_tick = self.tick
        chamber.memo_epoch = self.epoch
        chamber.memo = list(members)
        return chamber.memo

    # ---- 可用机器查询（Java 侧 listChambers：**不读任何备忘**）----
    def is_bound(self, chamber):
        head = self.chain_head(chamber)           # 每次现推导（对应 getRecipeType/getChamberName）
        return bool(head.own_type) and bool(head.own_name)

    def available(self, recipe_type):
        return [c for c in self.chambers if self.is_bound(c)
                and self.chain_head(c).own_type == recipe_type]

    # ---- 写入：与 setRecipeType / setChamberName 同构（写链首 + 可选失效）----
    def set_binding(self, chamber, recipe_type=None, name=None, invalidate=True):
        head = self.chain_head(chamber)
        if recipe_type is not None:
            head.own_type = recipe_type
        if name is not None:
            head.own_name = name
        if invalidate:
            self.bump()                           # RsccStructureEpoch.bump
        return head


# ---- D4：配置完成 → 下一次查询立刻能看到（单台仓；可用机器列表本身零缓存）----
single = Chamber(1)
world = World([single])
world.new_tick()
empty_before = world.available("create:deploying")          # 未配置：不在列表
world.set_binding(single, "create:deploying", "机械手一号")   # 玩家在界面上提交（类型 + 名字）
check("D4 配置完成（类型 + 名字）→ 同一 tick 的下一次查询就能列出这台执行仓",
      [c.pos for c in world.available("create:deploying")] == [1],
      "available=%r" % world.available("create:deploying"))
check("D5 配置之前那一刻列表里确实没有它（这条 case 真的在测「从无到有」）",
      empty_before == [])

# ---- D6：缓存存在时仍然看得到（可用机器判定不读那份备忘）----
world.new_tick()
world.chain_members(single)                                     # 先把链成员备忘建起来
world.set_binding(single, None, "机械手二号", invalidate=False)   # 故意**不**推版本
check("D6 即使链成员备忘在、且这次写入没推版本，可用机器列表（改名）也立刻看到新名字",
      world.is_bound(single) and world.chain_head(single).own_name == "机械手二号")
check("D7 ⇒ 结论：可用机器列表本身没有缓存，用户看到的「空列表」不可能是这里的陈旧值",
      "chainMembersMemo" not in list_body)


# ---- D8：缺 bump 的代价（链成员备忘会在同一 tick 内陈旧）----
# 场景：head 还没配置；member 是旧存档里「自己带过类型 + 名字」的成员，指向 head。
# 玩家在 member 界面提交一个新类型 ⇒ setRecipeType 按既有语义写到**链首**（head）。
# 两个类型都显式填过且不相等 ⇒ 不再成链（chainRecipeCompatibleWith），member 自成链首。
def split_case(with_bump):
    head = Chamber(1)
    member = Chamber(2, points_to=head)
    member.own_type = "create:pressing"
    member.own_name = "旧名字"
    w = World([head, member])
    w.new_tick()
    before = [c.pos for c in w.chain_members(member)]      # 先读一次 ⇒ 备忘就绪
    w.set_binding(member, "create:deploying", invalidate=with_bump)
    after = [c.pos for c in w.chain_members(member)]
    return before, after, w


before, stale, w_stale = split_case(False)
_, fresh, _w_fresh = split_case(True)
check("D8 缺 bump：改配方类型后**同一 tick** 的链成员仍是旧集合（%r）；补 bump 后立刻是新集合（%r）"
      % (stale, fresh),
      before == [1, 2] and stale == before and fresh == [2],
      "before=%r stale=%r fresh=%r" % (before, stale, fresh))
check("D9 但即使链成员备忘陈旧，可用机器列表仍按**当前**链首判定（member 已脱离链首 ⇒ 立刻可列出）",
      [c.pos for c in w_stale.available("create:pressing")] == [2],
      "available=%r" % [c.pos for c in w_stale.available("create:pressing")])

# ---- D10：改类型 / 改名都立即反映（列表从来不是「快照」）----
c3 = Chamber(3)
w3 = World([c3])
w3.new_tick()
w3.set_binding(c3, "create:pressing", "冲压一号")
first = (w3.available("create:pressing") != [] and w3.available("create:deploying") == [])
w3.set_binding(c3, "create:deploying")                      # 只改类型，名字保留
second = (w3.available("create:pressing") == [] and w3.available("create:deploying") != [])
check("D10 改类型 → 旧类型列表立刻不含它、新类型列表立刻含它（没有 TTL 等待）",
      first and second, "first=%s second=%s" % (first, second))

# ---- D11：终端「没有网络」时列表必为空（B 组的模型证据）----
def list_chambers_model(network, chambers):
    if network is None:
        return []                                              # 与 Java：listChambers() 第一行
    return [c for c in chambers if c.own_type and c.own_name]


check("D11 模型的入口条件与 Java 同构：network == None ⇒ 列表为空（这就是漏绑网络的后果）",
      list_chambers_model(None, [c3]) == []
      and list_chambers_model("net", [c3]) == [c3]
      and null_guard >= 0)

# ---- D12：备忘只影响「链成员」的算法次数，不影响可用机器结论 ----
w4 = World([Chamber(9)])
w4.new_tick()
w4.set_binding(w4.chambers[0], "create:filling", "灌注一号")
w4.new_tick()
memoized = [x.pos for x in w4.chain_members(w4.chambers[0])]
w4.chambers[0].memo = None                                   # 关掉缓存再算一次
plain = [x.pos for x in w4.chain_members(w4.chambers[0])]
check("D12 备忘开 / 关时链成员结果一致，且可用机器结论不受影响",
      memoized == plain == [9] and [x.pos for x in w4.available("create:filling")] == [9],
      "memoized=%r plain=%r" % (memoized, plain))

# ---- D13：同一 tick 内 50 次查询只真算 1 次（保留性能证据）----
w5 = World([Chamber(5)])
w5.new_tick()
for _ in range(50):
    w5.chain_members(w5.chambers[0])
check("D13 性能：同一 tick、无写入 ⇒ 50 次链成员查询只真算 1 次（备忘仍在，没被为了修 bug 而删掉）",
      w5.computations == 1, "computations=%d" % w5.computations)
w5.set_binding(w5.chambers[0], "create:mixing", "混合一号")
for _ in range(50):
    w5.chain_members(w5.chambers[0])
check("D14 性能：一次配置写入只让它多算 1 次（bump 的代价是「下一次读取重建一次」，不是持续开销）",
      w5.computations == 2, "computations=%d" % w5.computations)

# =====================================================================================
# E. 交付前摘要
# =====================================================================================
section("E. 摘要")
print("数据流：SequencePatternTerminalScreen#hasNoMachine/chambersFor")
print("        → RequestChamberListPacket → SequencePatternTerminalBlockEntity#listChambers")
print("        → network.getContainers() + chamber.isBound()（零缓存）")
print("        → SyncChamberListPacket → 客户端 lastReceived → 渲染「无可用机器」")
print()
print("入口条件（第 75 轮根因）：无线序列样板终端的虚拟方块实体曾漏绑网络")
print("        ⇒ listChambers() 第一行 network == null ⇒ 永远空列表。已在 AdvancedRemoteTerminalItem 修复。")
print()
print("写入侧失效（本轮补全）：4 个配置写入 → bump + pushAvailabilityChanged；3 个结构事件 → bump。")
print("性能：可用机器列表本来就不缓存；chainMembers 备忘保留（同 tick 只算 1 次），只是写入时多失效一次。")
print()

if PROBLEMS:
    print("=" * 96)
    print("SELFCHECK FAILED：%d 项不通过" % len(PROBLEMS))
    for problem in PROBLEMS:
        print("  - %s" % problem)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
