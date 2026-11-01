#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 124 轮自检（F1 · 集群电力）：FE 能量立方「输入输出按整簇结算、挖下只保留自身」的审计锚点。

本轮是**审计轮**：源码一字未改（见各组的「结论」行），交付 = 本自检 + 证据报告。
目的：把用户 beta.22 的 F1 口径「针对整个集群」钉死在源码上，防止以后回退。

用户原话（docs/ROUND122_USER_FEEDBACK.md §4 F1）
--------------------------------------------------
「摆了一面墙的立方，全在被充电，显示 580 多兆；旁边放一个范围充电器，它应该瞬间把整个集群
充满，而不是只充靠近的那一个；只有在被挖下来的时候才分那个方块自己的电量。」

六条不变量（每条都只认**代码行号证据**，不认注释）
--------------------------------------------------
① **输入按集群结算**：外部 FE 灌进任意一块 ⇒ 能填满整簇。
   `FEEnergyCubeBlockEntity.ClusterEnergyCapability#receiveEnergy`（L800-815）先算 **long** 空间
   `space = getClusterCapacity() - getClusterEnergyStored()`（L806），`request = min(收到量,
   feEnergyCubeMaxTransfer, space)`（L807），再 `receiveIntoCluster(request)`（L814）；后者
   `snapshot().receive(amount, worldPosition)`（L643）。`EnergyCubeCluster.Snapshot#receive`
   （L165-194）**先填 preferred（发起方自己）L168-175，再按坐标字典序填其它有空位的成员**
   L176-192（`if (pos.equals(preferred)) continue;` L180 防重复填自己）。
   → 结论：**已正确**。用户看到「只充靠近的那一个」不是分配口径错，而是**速率**：
   范围充电器给单个目标的预算 `transferBudget()` = `min(getChargeRate(), 电量/maxTargets)`
   （`RangeChargerBlockEntity#transferBudget` 现 L929-936，默认 500 FE/t、maxTargets=50）；立方单次能力上限
   `feEnergyCubeMaxTransfer` 默认 100,000。填满 20M 的缺口 = 20,000,000/500 = 40,000 tick
   ≈ 33 分钟。「容量」与「速率」是两件事，本自检 M-1x 组把这件事量化成断言。
   **第 127 轮已按用户两次口述改掉这条速率口径**：立方目标走「电池档」
   `cubeTransferBudget()`（= `min(本机缓存, feEnergyCubeMaxTransfer)`，默认 10 万 FE/t），
   不再吃 `transferBudget()` 的 500 FE/t 工具档。本文件 M-1 组的 `Charger` 模型仍按
   **工具档**跑（它现在只代表「玩家物品 / 掉落物 / 非立方方块」那一档，以及第 124 轮的历史口径）；
   立方档的现行行为断言在 `tools/selfcheck_round127_cluster_conductor.py`。

② **输出按集群结算且不双计**：`Snapshot#extract`（L139-157）按 `members` 的**坐标字典序**
   （`LEXICOGRAPHIC` L68-71、`found.sort(LEXICOGRAPHIC)` L305）逐个 `cube.extractOwn(remaining)`
   （L150），`extracted += got; remaining -= got;`（L152-153）——扣的是**各自 storage**，
   累计的是**实际扣掉量**，没有任何一处用「快照存量」再扣一遍 ⇒ 不会把同一度电算两次。
   RS 网络侧：节点只报本块（`FEEnergyCubeNetworkNode#getStored/#getCapacity` L96-104，网络组件
   逐块求和）；网络耗能走 `#extract`（L131-149）→ `dischargeToNetwork`（L623-628）→ 同一个
   `Snapshot#extract`，并「让位」给非立方 provider（L141-143）且自己抽自己返回 0（L136-138）。

③ **范围充电器覆盖任一块即可整簇充电**：`RangeChargerBlockEntity#chargeBlock`（现 L1251-1307）取的是
   `Capabilities.EnergyStorage.BLOCK`（现 L1252-1254，`direction = null`），而立方的能力注册
   （`FEEnergyCubeBlockEntity#registerCapabilities` L1035-1049）对**所有方向 + null** 都返回同一个
   `getExternalEnergyStorage()`（L1037-1041）⇒ 充电器拿到的是**集群视图**，真取电
   `receiveEnergy(transfer, false)`（现 L1300）落进①。
   **满电判定（第 124 轮 R-1 后）**：`transfer = transferBudget()`（L1291，纯函数）→ `transfer <= 0`
   早退（L1292）→ `if (storage.receiveEnergy(transfer, true) <= 0) { lastSkippedFullTargets++;
   return false; }`（L1295-1299，simulate 判满，**不扣电、不登记高亮**）→ 真取电 L1300 → 扣电
   `energyStorage.extractEnergy(accepted, false)` L1302 → 高亮 `tickChargedBlocks.add(pos)` L1303。
   判据原意（C-2）：**判满必须早于真取电，且判满失败不扣电、不登记高亮**（不锚旧字面量）。
   R-1 的前因：`IEnergyStorage` 只有 int，≥43 块时容量/存量都饱和到 `Integer.MAX_VALUE`
   （`FEEnergyCubeBlockEntity` L791-797 有据）⇒ 旧的「两个 int 比大小」会**提前判满**
   （124-D 的 M-3c 模型当时就复现了「真实还剩 2,516,352 FE 却被跳过」）。**124-C 已按此修掉**，
   本自检的 M-3c 现在断言的是「旧判据错、simulate 判满对」，C-2/C-5/C-6 三条把新写法钉死。

④ **挖下来只保留该方块自身电量**：`FEEnergyCubeBlock#getDrops`（L138-160）用的是
   `cube.getOwnEnergyStored()`（L149，判空）与 `cube.saveToItem(...)`（L155）——只把**本块**
   的电量写进掉落物（注释 L152 明确「同一集群里其它块的电量一格不动」）。放回去由
   `loadAdditional`（L1010-1021）→ `energyStorage.restoreEnergy(saved)`（L1015）写**本块**，
   期间没有 `receiveIntoCluster`、没有移交 ⇒ 不会把自己那一份又并进别人。
   物品形态读写**同一个** `TAG_ENERGY`（`FEEnergyCubeItem#getEnergy` L164-171 / `setEnergy` L185-197；
   方块侧 `saveAdditional` L1007）⇒ 「手上充的电 = 放下的电」。

⑤ **不复制 / 不丢失的边界**：
   * 快照只存**坐标**（`EnergyCubeCluster.Snapshot` 字段 L83-85 只有 level/members/lastValidated，
     全文件没有 `private final FEEnergyCubeBlockEntity` 字段）⇒ 区块卸载重载透明；
     `cubeAt` 用 `level.isLoaded(pos)` 守卫（L321-328），`stored()/capacity()` 对取不到的成员
     **两边都不计**（L111-132）⇒ `stored ≤ capacity` 恒成立；
   * `MAX_MEMBERS = 64`（L63）+ `collect` 的 `found.size() < MAX_MEMBERS`（L291）⇒ 超大结构截断；
   * 失效时机：`invalidateAround` = 坐标 + 6 邻（L219-231），由方块 `onPlace` L94-98 /
     `neighborChanged` L105-110 / `onRemove` L114-120 与方块实体 `setRemoved` L1024-1030、
     `loadAdditional` L1017 调用；
   * 客户端绝不解析集群：`resolve` 对非 `ServerLevel` 直接返回单元素快照（L246-249）；
     所有写路径另有 `level.isClientSide()` 早退（`extractFromCluster` L632、`receiveIntoCluster` L640、
     `dischargeToNetwork` L624、`onNetworkTick` L576、`tickNeighbourPush` L655）；方块 `getTicker`
     客户端返回 null（`FEEnergyCubeBlock` L69-71）。

⑥ **容量显示口径 = 整簇**：`getContainerData` 的 2/3 = 集群存量 lo/hi、4/5 = 集群容量 lo/hi
   （L937-940，来源 `stats()` L383-399）；菜单 `FEEnergyCubeMenu#getClusterEnergy` L86-88 =
   `join(data.get(2), data.get(3))`、`#getClusterCapacity` L91-93 = `join(data.get(4), data.get(5))`；
   界面电量条与「集群存量」行都用它（`FEEnergyCubeScreen#init` L208-211、`renderLabels` L217-225）。
   用户看到的 580 多兆 = 整簇，这条本来就对 —— 本轮钉死。

三条腿（与第 120 轮同一套路）
----------------------------
① 源码锚点：上面每个结论都对应一条「取方法体 → 断言关键语句」的判据（A/B/C/D/E/F 组）；
② 行为模型：用 python 复刻 `Snapshot#receive / #extract` + 能力视图三重夹 + 充电器 tick
   （M 组），把用户场景（12 块 × 50M、存量 580M、**充电器只覆盖 1 块**）跑出来，断言
   「整簇被填满、总量守恒、非覆盖成员也涨、掉落物只带自身」；
③ 反例自证：把坏写法拼回源码副本 / 模型，断言同一条判据真的判负（C 组，证明判据不是摆设）。

已知残留（如实写清）
------------------
R-1 ~~≥43 块时 `IEnergyStorage` int 饱和 ⇒ 范围充电器「满电跳过」提前判满~~ → **已由 124-C 修掉**
    （`chargeBlock` 改用 `receiveEnergy(transfer, true) <= 0` simulate 判满）。本轮把锚点跟进到新写法：
    C-2（判满早于真取电 + 判满失败不扣电/不登记高亮）、C-5（`transferBudget()` 只调用一次）、
    C-6（`transferBudget()` 只读）+ M-3c（饱和区间 simulate 仍能给出「还能收 N」）。
    反例 X-6a/X-6b/X-6c 分别抓「判满挪到真取电之后」「判满失败仍扣电」「判满失败仍登记高亮」；
    X-7a/X-7b 抓 `transferBudget()` 被调两次 / 被塞副作用（R-1 的纯函数前提）。
R-2 >64 块的结构被 `MAX_MEMBERS` 截断 ⇒ 不是「一个集群」（M-5d 组量化）。
R-3 「瞬间充满」不可能：受 `rangeChargerChargeRate`(500)/`rangeChargerNetworkPullPerTick`(100,000)/
    `rangeChargerEnergyCapacity`(50M) 与 `feEnergyCubeMaxTransfer`(100,000) 四层速率限制（M-1 组量化）。
R-4 「先填自己再填别人」会让**被覆盖的那一块**先满，玩家若只看单块读数会误以为「只充了靠近的那一个」
    （M-1e 组把顺序钉死，属设计口径，不是 bug）。

纪律：不跑 gradle / javac、不 git、不改 docs/CHANGELOG/README/gradle.properties、不碰其它 agent 的文件。
用法：python tools/selfcheck_round124_energy_cluster.py
退出码：0 = 全部通过；最后一行：ALL_PASS。
"""

from __future__ import annotations

import io
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_REL = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
JAVA = os.path.join(ROOT, JAVA_REL)

CLUSTER = os.path.join(JAVA, "support", "EnergyCubeCluster.java")
CUBE_BE = os.path.join(JAVA, "block", "entity", "FEEnergyCubeBlockEntity.java")
CUBE_BLOCK = os.path.join(JAVA, "block", "FEEnergyCubeBlock.java")
CUBE_ITEM = os.path.join(JAVA, "item", "FEEnergyCubeItem.java")
CUBE_SCREEN = os.path.join(JAVA, "client", "screen", "FEEnergyCubeScreen.java")
CUBE_MENU = os.path.join(JAVA, "menu", "FEEnergyCubeMenu.java")
NET_NODE = os.path.join(JAVA, "network", "FEEnergyCubeNetworkNode.java")
CHARGER = os.path.join(JAVA, "block", "entity", "RangeChargerBlockEntity.java")
CONFIG = os.path.join(JAVA, "Config.java")

#: 单块容量 / 传输上限 / 充电器速率 / 充电器目标数上界（默认值，A-0 组从 Config 源码锚定）。
SINGLE_CAP = 50_000_000
MAX_TRANSFER = 100_000
CHARGER_RATE = 500
CHARGER_MAX_TARGETS = 50
#: `EnergyCubeCluster.MAX_MEMBERS`（E-2 组锚定）。
MAX_MEMBERS = 64
INT_MAX = 2 ** 31 - 1

CHECKS = [0]
COUNTER = [0]
FAILS = []


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS " + name)
    else:
        FAILS.append(name)
        print("FAIL " + name + (("  <- " + detail) if detail else ""))


def counterexample(name, ok, detail=""):
    COUNTER[0] += 1
    check("反例 " + name, ok, detail)


# ======================================================================================
# 源码解析小工具（与第 119 / 120 轮同一套口径，避免再造一份「去注释 / 取方法体」）
# ======================================================================================

def strip_comments(text):
    """去注释（保留字符串字面量），用于「语句存在性」判据。"""
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        two = text[i:i + 2]
        if quote is None and two == "//":
            end = text.find("\n", i)
            if end < 0:
                break
            i = end
            continue
        if quote is None and two == "/*":
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        ch = text[i]
        if quote is None and ch in "\"'":
            quote = ch
        elif quote == ch and text[i - 1] != "\\":
            quote = None
        out.append(ch)
        i += 1
    return "".join(out)


def method_body(code, hint):
    """从（已去注释的）源码里取一个方法体：从 hint 起找到第一个 '{' 并配平。"""
    start = code.find(hint)
    if start < 0:
        return None
    brace = code.find("{", start)
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[start:i + 1]
    return None


def brace_block(text, header):
    """取以 header 开头的那个花括号块（含嵌套）。找不到返回 None。"""
    if text is None:
        return None
    start = text.find(header)
    if start < 0:
        return None
    brace = text.find("{", start + len(header) - 1)
    if brace < 0:
        return None
    depth = 0
    for i in range(brace, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return None


def line_no(raw, needle):
    """needle 在原始源码里的行号（1 基）；找不到返回 -1。用于把所有证据写成「带行号的断言名」。"""
    idx = raw.find(needle)
    if idx < 0:
        return -1
    return raw.count("\n", 0, idx) + 1


def where(raw, needle):
    line = line_no(raw, needle)
    return "L%d" % line if line > 0 else "L?"


def where_in(raw, hint, needle):
    """在 `hint`（方法签名）之后找 `needle` 的行号 —— 同一个语句在文件里出现多次时用它定位到本方法那一次。"""
    start = raw.find(hint)
    if start < 0:
        return "L?"
    idx = raw.find(needle, start)
    if idx < 0:
        return "L?"
    return "L%d" % (raw.count("\n", 0, idx) + 1)


# ======================================================================================
# 行为模型：复刻 EnergyCubeCluster.Snapshot + 能力视图 + 范围充电器 tick
# ======================================================================================

class Cube:
    """复刻 `FEEnergyCubeBlockEntity.OwnEnergyStorage`：容量恒为 cap，收/发都被自身夹量。"""

    __slots__ = ("x", "y", "z", "cap", "energy")

    def __init__(self, x, y, z, energy=0, cap=SINGLE_CAP):
        self.x, self.y, self.z = x, y, z
        self.cap = cap
        self.energy = max(0, min(energy, cap))

    @property
    def pos(self):
        return (self.x, self.y, self.z)

    def receive_own(self, amount):
        """= `receiveOwn`（L308-317）：被自身剩余空间夹住。"""
        got = max(0, min(int(amount), self.cap - self.energy))
        self.energy += got
        return got

    def extract_own(self, amount):
        """= `extractOwn`（L292-301）：被自身存量夹住。"""
        got = max(0, min(int(amount), self.energy))
        self.energy -= got
        return got


class Snapshot:
    """复刻 `EnergyCubeCluster.Snapshot`：只存成员坐标 + 按坐标字典序的确定性顺序。"""

    def __init__(self, cubes, loaded=None):
        self.members = sorted(cubes, key=lambda c: (c.x, c.y, c.z))   # = found.sort(LEXICOGRAPHIC)
        self.by_pos = {c.pos: c for c in self.members}
        self.loaded = set(c.pos for c in cubes) if loaded is None else set(loaded)

    def live(self):
        """`cubeAt` 取不到（区块未加载）的成员 == null ⇒ stored/capacity 两边都不计。"""
        return [c for c in self.members if c.pos in self.loaded]

    def stored(self):
        return sum(c.energy for c in self.live())

    def capacity(self):
        return sum(c.cap for c in self.live())

    def extract(self, amount):
        """= L139-157：字典序逐个扣，累计**实际扣掉量**（不是请求量）。"""
        remaining = max(0, int(amount))
        extracted = 0
        for c in self.members:
            if remaining <= 0:
                break
            if c.pos not in self.loaded:
                continue
            got = c.extract_own(remaining)
            if got > 0:
                extracted += got
                remaining -= got
        return extracted

    def receive(self, amount, preferred=None):
        """= L165-194：先填 preferred，再按字典序填其它成员。"""
        remaining = max(0, int(amount))
        received = 0
        if preferred is not None and preferred in self.by_pos and preferred in self.loaded:
            got = self.by_pos[preferred].receive_own(remaining)
            received += got
            remaining -= got
        for c in self.members:
            if remaining <= 0:
                break
            if preferred is not None and c.pos == preferred:
                continue
            if c.pos not in self.loaded:
                continue
            got = c.receive_own(remaining)
            if got > 0:
                received += got
                remaining -= got
        return received


def capability_receive(snap, to_receive, preferred, limit=MAX_TRANSFER):
    """= `ClusterEnergyCapability#receiveEnergy`（L800-815）：三重夹后用 long 空间，再灌整簇。"""
    space = snap.capacity() - snap.stored()          # long，绝不含 int 饱和
    request = min(int(to_receive), limit, max(0, space))
    if request <= 0:
        return 0
    return snap.receive(request, preferred)


def capability_receive_simulate(snap, to_receive, limit=MAX_TRANSFER):
    """= `receiveEnergy(..., simulate = true)`：**不写世界**，只回答「还能收多少」。
    第 124 轮 R-1 起，充电器的「满电跳过」用的就是它（内部是 long 空间，不受 int 饱和影响）。"""
    space = snap.capacity() - snap.stored()
    return max(0, min(int(to_receive), limit, max(0, space)))


def capability_extract(snap, to_extract, limit=MAX_TRANSFER):
    """= `ClusterEnergyCapability#extractEnergy`（L817-831）。"""
    available = min(int(to_extract), limit, snap.stored())
    if available <= 0:
        return 0
    return snap.extract(available)


def clamp_int(value):
    """= `clampToInt`（L861-864）：long → int 饱和。"""
    return int(max(0, min(int(value), INT_MAX)))


def old_int_full_check(snap):
    """R-1 **之前**的旧判据：`storage.getEnergyStored() >= storage.getMaxEnergyStored()`（两个 int 比大小）。"""
    return clamp_int(snap.stored()) >= clamp_int(snap.capacity())


class Charger:
    """复刻 `RangeChargerBlockEntity` 的方块充电路径（**只读**该文件，不在此判据之外改它）。"""

    def __init__(self, energy):
        self.energy = energy

    def tick(self, snap, covered, rate=CHARGER_RATE, max_targets=CHARGER_MAX_TARGETS,
             limit=MAX_TRANSFER):
        """= `doCharging → scanBlocks → chargeBlock`（现 L1251-1307）+ `transferBudget`（现 L929-936）。

        第 124 轮 R-1 后的顺序（与源码逐句对应）：`transferBudget()` → `transfer <= 0` 早退
        → `receiveEnergy(transfer, true) <= 0` 判满（不扣电、不计高亮）→ `receiveEnergy(transfer, false)`。
        """
        if self.energy <= 0:
            return 0
        total = 0
        for pos in covered:
            if self.energy <= 0:
                break
            share = self.energy // max(1, max_targets)          # = transferBudget()
            budget = max(1, min(rate, share))
            if budget <= 0:
                break
            # 满电跳过（R-1 后）：simulate 问能力「还收得下吗」—— 不依赖两个 int 的饱和关系。
            if capability_receive_simulate(snap, budget, limit) <= 0:
                break                                           # 判满失败：不扣电、不登记高亮
            accepted = capability_receive(snap, budget, pos, limit)
            if accepted <= 0:
                continue
            self.energy -= accepted
            total += accepted
        return total


def user_scenario():
    """用户实机场景：12 块 × 50M = 600M 容量，存量 580M（8 块满 + 4 块 45M）。"""
    cubes = [Cube(i, 0, 0, SINGLE_CAP if i < 8 else 45_000_000) for i in range(12)]
    return Snapshot(cubes)


# ======================================================================================
# 判据（写成纯函数，反例自证时直接喂「改坏的源码副本」/ 改坏的模型）
# ======================================================================================

# ---------- ① 输入按集群结算 ----------

def capability_receive_cluster_wide(src):
    """`ClusterEnergyCapability#receiveEnergy`：long 空间 + 限速 + `receiveIntoCluster`（不是只写自己）。"""
    body = method_body(strip_comments(src), "public int receiveEnergy(")
    if body is None:
        return False
    return ("final long space = getClusterCapacity() - getClusterEnergyStored();" in body
            and "Math.min(Math.min((long) toReceive, transferLimit()), Math.max(0L, space))" in body
            and "return clampToInt(receiveIntoCluster(request));" in body
            and "energyStorage.receiveEnergy(" not in body)


def receive_into_cluster_uses_snapshot(src):
    """`receiveIntoCluster` 把量交给**集群**快照（preferred = 自己）。"""
    body = method_body(strip_comments(src), "private long receiveIntoCluster(")
    if body is None:
        return False
    return ("return snapshot().receive(amount, worldPosition);" in body
            and "level.isClientSide()" in body)


def snapshot_receive_fills_whole_cluster(src):
    """`Snapshot#receive`：先填 preferred，再遍历**所有**成员。"""
    code = strip_comments(src)
    body = method_body(code, "public long receive(final long amount")
    if body is None:
        return False
    return ("if (preferred != null && members.contains(preferred))" in body
            and "for (final BlockPos pos : members)" in body
            and "if (pos.equals(preferred))" in body
            and "cube.receiveOwn(remaining)" in body)


def snapshot_receive_preferred_first(src):
    """先填自己再填别人：`preferred` 那一段出现在遍历成员那段**之前**。"""
    code = strip_comments(src)
    body = method_body(code, "public long receive(final long amount")
    if body is None:
        return False
    first = body.find("if (preferred != null && members.contains(preferred))")
    second = body.find("for (final BlockPos pos : members)")
    return 0 <= first < second


def own_storage_receive_is_capacity_clamped(src):
    """`receiveOwn` 只受自身空间夹量（不按速率截断 —— 否则网络抽取的差额会变成纯损耗）。"""
    body = method_body(strip_comments(src), "public long receiveOwn(")
    if body is None:
        return False
    return ("energyStorage.receiveEnergy((int) Math.min(amount, Integer.MAX_VALUE), false)" in body
            and "transferLimit" not in body)


# ---------- ② 输出按集群结算 ----------

def snapshot_extract_is_lexicographic(src):
    """`Snapshot#extract`：按 members 顺序（= 字典序）扣到够为止，累计实际扣掉量。"""
    code = strip_comments(src)
    body = method_body(code, "public long extract(final long amount)")
    if body is None:
        return False
    return ("for (final BlockPos pos : members)" in body
            and "cube.extractOwn(remaining)" in body
            and "extracted += got;" in body
            and "remaining -= got;" in body
            # 累计的必须是「实际扣掉量」：写成 `extracted += remaining` 就会把同一度电
            # 在多个成员上各算一次（X-4 反例正是这条）。
            and "extracted += remaining" not in body)


def members_are_sorted_lexicographically(src):
    """成员排序口径 = x → y → z，且 `collect` 真的排了序（确定性，与遍历顺序无关）。"""
    code = strip_comments(src)
    comparator = method_body(code, "private static final Comparator<BlockPos> LEXICOGRAPHIC")
    collect = method_body(code, "private static List<BlockPos> collect(")
    if comparator is None or collect is None:
        return False
    return ("comparingInt(BlockPos::getX)" in comparator
            and "thenComparingInt(BlockPos::getY)" in comparator
            and "thenComparingInt(BlockPos::getZ)" in comparator
            and "found.sort(LEXICOGRAPHIC);" in collect)


def extract_from_cluster_is_whole_cluster(src):
    """`extractFromCluster` / `dischargeToNetwork` 都走集群快照（不是只扣自己）。"""
    code = strip_comments(src)
    extract = method_body(code, "private long extractFromCluster(")
    discharge = method_body(code, "public long dischargeToNetwork(")
    if extract is None or discharge is None:
        return False
    return ("return snapshot().extract(amount);" in extract
            and "return snapshot().extract(amount);" in discharge)


def node_reports_own_only(src):
    """RS 网络节点只报本块（网络组件逐块求和 ⇒ 报集群值会平方级重复计数）。"""
    code = strip_comments(src)
    stored = method_body(code, "public long getStored()")
    capacity = method_body(code, "public long getCapacity()")
    if stored is None or capacity is None:
        return False
    return ("blockEntity.getOwnEnergyStored()" in stored
            and "blockEntity.getOwnCapacity()" in capacity
            and "getCluster" not in stored and "getCluster" not in capacity)


def node_extract_defers_to_non_cube(src):
    """网络耗能：非立方 provider 先出，自己抽自己返回 0（不左右口袋搬运）。"""
    body = method_body(strip_comments(src), "public long extract(final long amount)")
    if body is None:
        return False
    return ("blockEntity.isPullingFromNetwork()" in body
            and "want = Math.max(0L, amount - nonCubeStored);" in body
            and "blockEntity.dischargeToNetwork(want)" in body)


# ---------- ③ 充电器覆盖任一即可整簇 ----------

def charger_uses_block_capability(src):
    """`chargeBlock` 取 `Capabilities.EnergyStorage.BLOCK`（null 面）+ 真收（不是模拟）。

    第 127 轮更新（**判据原意未放宽**）：立方目标多了一条「电池档」预算分支
    （`blockEntity instanceof FEEnergyCubeBlockEntity ? cubeTransferBudget() : transferBudget()`），
    但**能力入口仍然必须是 BLOCK + null 面**（不能换成 ITEM、不能直接读方块实体内部字段），
    真取电也仍然必须是 `receiveEnergy(transfer, false)`。允许出现 `FEEnergyCube` 这个类名
    （只是预算选择，不改变「覆盖任一块 ⇒ 读到的就是整簇视图」这一条）。
    反例 X-5（改走 ITEM 能力）依旧判负。
    """
    body = method_body(strip_comments(src), "private boolean chargeBlock(")
    if body is None:
        return False
    return ("Capabilities.EnergyStorage.BLOCK" in body
            and "storage.receiveEnergy(transfer, false)" in body)


def full_check_precedes_real_receive(src):
    """**满电判定必须早于真取电，且判满失败不扣电、不登记高亮**（第 124 轮 R-1 后的判据原意）。

    2026-10-31 124-C 落地 R-1 后的 `chargeBlock` 形状：
    `transfer = transferBudget()` → `transfer <= 0` 早退
    → `if (storage.receiveEnergy(transfer, true) <= 0) { lastSkippedFullTargets++; return false; }`
    → `final int accepted = storage.receiveEnergy(transfer, false);` → `energyStorage.extractEnergy(accepted, false)`。

    这条判据**不锚旧字面量**、也不放宽成「只要出现 simulate 就行」，它要求四件事同时成立：
    ① simulate 判满锚点存在且**唯一**（`receiveEnergy(transfer, true) <= 0`）；
    ② 跳过计数 `lastSkippedFullTargets++` 早于真取电（判满失败才算跳过）；
    ③ simulate 判满早于真取电 `receiveEnergy(transfer, false)`；
    ④ 判满失败的那一段（simulate → 真取电之间）**不扣电**（无 `energyStorage.extractEnergy`）、
       **不登记高亮**（无 `tickChargedBlocks.add`），且扣电只出现在真取电之后。
    """
    body = method_body(strip_comments(src), "private boolean chargeBlock(")
    if body is None:
        return False
    sim_anchor = "storage.receiveEnergy(transfer, true) <= 0"
    real_recv = "storage.receiveEnergy(transfer, false)"
    if body.count(sim_anchor) != 1 or body.count(real_recv) != 1:
        return False
    skip = body.find("lastSkippedFullTargets++")
    sim = body.find(sim_anchor)
    real = body.find(real_recv)
    if skip < 0 or sim < 0 or real < 0:
        return False
    if not (skip < real and sim < real):        # ① 判满（含跳过计数）必须在真取电之前
        return False
    failed_segment = body[sim:real]             # ② 判满失败的那一段：不扣电、不登记高亮
    if "energyStorage.extractEnergy" in failed_segment:
        return False
    if "tickChargedBlocks.add" in failed_segment:
        return False
    extract = body.find("energyStorage.extractEnergy(accepted, false)")
    return extract > real                        # ③ 扣电只能在真取电成功之后


def transfer_budget_called_once(src):
    """`transferBudget()` 在 `chargeBlock` 里**只被调用一次**（R-1 的实现依赖它纯函数、可提前算）。"""
    body = method_body(strip_comments(src), "private boolean chargeBlock(")
    if body is None:
        return False
    return body.count("transferBudget()") == 1


def transfer_budget_is_pure(src):
    """`transferBudget()` 的方法体**只读**：无 `energyStorage` 写、无 `setChanged`、无高亮登记。

    R-1 允许「先算预算再判满」，前提正是它没有副作用；一旦有人往里面塞 `extractEnergy` /
    `receiveEnergy` / `setChanged`，「判满失败不扣电」就会被绕过（预算计算阶段就把电扣了）。
    """
    body = method_body(strip_comments(src), "private int transferBudget()")
    if body is None:
        return False
    forbidden = ("extractEnergy(", "receiveEnergy(", "restoreEnergy(", "setChanged(",
                 "tickChargedBlocks", "lastChargedTargets", "lastSkippedFullTargets")
    if any(word in body for word in forbidden):
        return False
    # 只允许这两种能量读法
    return "energyStorage.getEnergyStored()" in body


def capability_simulate_is_read_only(src):
    """`receiveEnergy(..., true)` 的 simulate 分支**只回答、不写世界**（R-1 的判满就建立在这一点上）。

    R-1 把「满电跳过」从「比两个 int」换成 `receiveEnergy(transfer, true) <= 0`，
    前提是 simulate **绝不能真灌进集群**：否则「判满失败不扣电」这条不变量直接被绕过
    （探测一下就把电充进去了）。因此单独钉死 simulate 分支的内容。
    """
    body = method_body(strip_comments(src), "public int receiveEnergy(")
    if body is None:
        return False
    block = brace_block(body, "if (simulate)")
    if block is None:
        return False
    return ("return clampToInt(request);" in block
            and "receiveOwn" not in block
            and "receiveIntoCluster" not in block
            and "setChanged" not in block)


def cube_capability_registered_all_sides(src):
    """能力注册：所有方向 + null 都返回同一个集群视图（`direction` 未被用于分支）。"""
    body = method_body(strip_comments(src), "public static void registerCapabilities(")
    if body is None:
        return False
    return ("Capabilities.EnergyStorage.BLOCK" in body
            and "(blockEntity, direction) -> blockEntity.getExternalEnergyStorage()" in body
            and "if (direction" not in body)


def external_receive_ignores_gate(src):
    """外部 FE 注入**不看门控**（门控只拦「从 RS 网络抽电」）；canReceive 恒 true。"""
    code = strip_comments(src)
    receive = method_body(code, "public int receiveEnergy(")
    can_receive = method_body(code, "public boolean canReceive()")
    if receive is None or can_receive is None:
        return False
    return ("Gate" not in receive and "computeGate" not in receive
            and "return true;" in can_receive)


# ---------- ④ 挖下只保留自身 ----------

def drops_carry_own_energy_only(src):
    """`getDrops` 只写本块电量（出现 `getClusterEnergyStored` 就是 bug）。"""
    body = method_body(strip_comments(src), "protected List<ItemStack> getDrops(")
    if body is None:
        return False
    return ("if (cube.getOwnEnergyStored() <= 0)" in body
            and "cube.saveToItem(stack, params.getLevel().registryAccess());" in body
            and "getClusterEnergyStored" not in body)


def load_restores_own_energy_without_merging(src):
    """读档是「设定值」语义写进**本块** storage，期间没有集群写入（不会并进别人）。"""
    body = method_body(strip_comments(src), "public void loadAdditional(")
    if body is None:
        return False
    return ("energyStorage.restoreEnergy(saved);" in body
            and "receiveOwn" not in body
            and "receiveIntoCluster" not in body
            and "EnergyCubeCluster.invalidateAround(level, worldPosition);" in body)


def item_and_block_share_same_tag(src_be, src_item):
    """物品电量与方块电量是**同一个** `TAG_ENERGY` 键（挖下来的电 = 放下去的电）。"""
    save = method_body(strip_comments(src_be), "public void saveAdditional(")
    get = method_body(strip_comments(src_item), "public static int getEnergy(")
    put = method_body(strip_comments(src_item), "public static void setEnergy(")
    if save is None or get is None or put is None:
        return False
    return ("tag.putInt(TAG_ENERGY, energyStorage.getEnergyStored());" in save
            and "FEEnergyCubeBlockEntity.TAG_ENERGY" in get
            and "FEEnergyCubeBlockEntity.TAG_ENERGY" in put
            and "BlockItem.setBlockEntityData(" in put)


# ---------- ⑤ 不复制 / 不丢失的边界 ----------

def snapshot_holds_coordinates_not_block_entities(src):
    """快照只存坐标（不缓存方块实体引用 ⇒ 区块卸载重载透明）。"""
    raw = src
    code = strip_comments(raw)
    body = brace_block(code, "public static final class Snapshot")
    if body is None:
        return False
    return ("private final List<BlockPos> members;" in body
            and "private final Level level;" in body
            and "private final FEEnergyCubeBlockEntity" not in body)


def stored_and_capacity_skip_unloaded(src):
    """取不到成员（区块未加载）时 stored/capacity **两边都不计**（保证 stored ≤ capacity）。"""
    code = strip_comments(src)
    stored = method_body(code, "public long stored()")
    capacity = method_body(code, "public long capacity()")
    if stored is None or capacity is None:
        return False
    return ("if (cube != null)" in stored and "if (cube != null)" in capacity
            and "getOwnEnergyStored()" in stored and "getOwnCapacity()" in capacity)


def max_members_limit(src):
    """`MAX_MEMBERS = 64` 且 `collect` 用它截断。"""
    code = strip_comments(src)
    collect = method_body(code, "private static List<BlockPos> collect(")
    if collect is None:
        return False
    return ("public static final int MAX_MEMBERS = 64;" in code
            and "found.size() < MAX_MEMBERS" in collect)


def invalidate_timings(src_cluster, src_block, src_be):
    """失效时机：坐标 + 6 邻；放置 / 邻块变化 / 破坏 / 移除 / 读档都调它。"""
    invalidate = method_body(strip_comments(src_cluster), "public static void invalidateAround(")
    block = strip_comments(src_block)
    be = strip_comments(src_be)
    if invalidate is None:
        return False
    return ("invalidateAt(map, pos);" in invalidate
            and "invalidateAt(map, pos.relative(direction));" in invalidate
            and "EnergyCubeCluster.invalidateAround(level, pos);"
                in (method_body(block, "protected void onPlace(") or "")
            and "EnergyCubeCluster.invalidateAround(level, pos);"
                in (method_body(block, "protected void neighborChanged(") or "")
            and "EnergyCubeCluster.invalidateAround(level, pos);"
                in (method_body(block, "protected void onRemove(") or "")
            and "EnergyCubeCluster.invalidateAround(level, worldPosition);"
                in (method_body(be, "public void setRemoved(") or ""))


def client_never_resolves_cluster(src_cluster, src_be):
    """客户端不解析集群 + 所有写路径 `isClientSide` 早退。"""
    resolve = method_body(strip_comments(src_cluster), "public static Snapshot resolve(")
    be = strip_comments(src_be)
    if resolve is None:
        return False
    write_paths = ("private long extractFromCluster(", "private long receiveIntoCluster(",
                   "public long dischargeToNetwork(", "public void onNetworkTick(",
                   "public void tickNeighbourPush(")
    for hint in write_paths:
        body = method_body(be, hint)
        if body is None or "level.isClientSide()" not in body:
            return False
    return ("if (!(level instanceof ServerLevel server))" in resolve
            and "return new Snapshot(level, List.of(pos));" in resolve)


def cube_ticker_null_on_client(src_block):
    """方块 ticker 在客户端为 null（客户端根本不 tick 立方）。"""
    body = method_body(strip_comments(src_block), "public <T extends BlockEntity> BlockEntityTicker<T> getTicker(")
    if body is None:
        return False
    return "if (level.isClientSide())" in body and "return null;" in body


# ---------- ⑥ 显示口径 ----------

def menu_display_uses_cluster_slots(src):
    """菜单的集群存量/容量 = 数据槽 2/3 与 4/5 的 long 拼装。"""
    code = strip_comments(src)
    stored = method_body(code, "public long getClusterEnergy()")
    capacity = method_body(code, "public long getClusterCapacity()")
    if stored is None or capacity is None:
        return False
    return ("FEEnergyCubeBlockEntity.join(data.get(2), data.get(3))" in stored
            and "FEEnergyCubeBlockEntity.join(data.get(4), data.get(5))" in capacity)


def container_data_exposes_cluster(src):
    """数据槽 2..5 = 集群存量/容量 lo/hi；`stats()` 一次性算出整簇数值。"""
    code = strip_comments(src)
    return ("case 2 -> lo(clusterStored);" in code
            and "case 3 -> hi(clusterStored);" in code
            and "case 4 -> lo(clusterCapacity);" in code
            and "case 5 -> hi(clusterCapacity);" in code
            and "clusterStored = stats.clusterStored();" in code
            and "clusterCapacity = stats.clusterCapacity();" in code)


def screen_display_uses_cluster(src):
    """界面电量条与「集群存量」行都用集群值（单块精确值单列一行）。"""
    code = strip_comments(src)
    init = method_body(code, "protected void init()")
    labels = method_body(code, "protected void renderLabels(")
    if init is None or labels is None:
        return False
    return ("RsccEnergyBar.create(" in init
            and "() -> menu.getClusterEnergy(), () -> menu.getClusterCapacity()," in init
            and "menu.getClusterEnergy()" in labels
            and "menu.getOwnEnergy()" in labels)


def get_cluster_energy_falls_back_to_own_on_client(src):
    """`getClusterEnergyStored / Capacity`：客户端 / 未放置退回本块值（绝不在客户端解析集群）。"""
    code = strip_comments(src)
    stored = method_body(code, "public long getClusterEnergyStored()")
    capacity = method_body(code, "public long getClusterCapacity()")
    if stored is None or capacity is None:
        return False
    return ("if (level == null || level.isClientSide())" in stored
            and "return getOwnEnergyStored();" in stored
            and "return snapshot().stored();" in stored
            and "if (level == null || level.isClientSide())" in capacity
            and "return getOwnCapacity();" in capacity
            and "return snapshot().capacity();" in capacity)


# ======================================================================================
# 分组执行
# ======================================================================================

def group_anchors():
    cluster_raw = read(CLUSTER)
    be_raw = read(CUBE_BE)
    block_raw = read(CUBE_BLOCK)
    item_raw = read(CUBE_ITEM)
    screen_raw = read(CUBE_SCREEN)
    menu_raw = read(CUBE_MENU)
    node_raw = read(NET_NODE)
    charger_raw = read(CHARGER)
    config_raw = read(CONFIG)

    print("\n=== A 组：① 输入按集群结算（外部 FE 灌任意一块 ⇒ 整簇可填满） ===")
    check("A-0 Config 默认值：单块容量 %d / 单次传输上限 %d / 充电器速率 %d / 目标上界 %d"
          % (SINGLE_CAP, MAX_TRANSFER, CHARGER_RATE, CHARGER_MAX_TARGETS),
          '"feEnergyCubeCapacity", 50000000' in config_raw
          and '"feEnergyCubeMaxTransfer", 100000' in config_raw
          and '"rangeChargerChargeRate", 500' in config_raw
          and '"rangeChargerMaxTargets", 50' in config_raw)

    check("A-1 ClusterEnergyCapability#receiveEnergy 用 long 空间 (%s) + 限速 + 灌整簇 (%s)"
          % (where(be_raw, "final long space = getClusterCapacity() - getClusterEnergyStored();"),
             where(be_raw, "return clampToInt(receiveIntoCluster(request));")),
          capability_receive_cluster_wide(be_raw))

    check("A-2 receiveIntoCluster → snapshot().receive(amount, worldPosition) (%s)"
          % where(be_raw, "return snapshot().receive(amount, worldPosition);"),
          receive_into_cluster_uses_snapshot(be_raw))

    check("A-3 Snapshot#receive 先 preferred 后遍历全部成员 (%s)"
          % where(cluster_raw, "public long receive(final long amount"),
          snapshot_receive_fills_whole_cluster(cluster_raw))

    check("A-4 先填自己再填别人（preferred 段在 members 遍历段之前）",
          snapshot_receive_preferred_first(cluster_raw))

    check("A-5 receiveOwn 只受自身空间夹量、不按速率截断 (%s)"
          % where(be_raw, "public long receiveOwn("),
          own_storage_receive_is_capacity_clamped(be_raw))

    print("\n=== B 组：② 输出按集群结算 + 不双计 ===")
    check("B-1 Snapshot#extract 字典序逐个扣、累计实际扣掉量 (%s)"
          % where(cluster_raw, "public long extract(final long amount)"),
          snapshot_extract_is_lexicographic(cluster_raw))

    check("B-2 成员排序 x→y→z 且 collect 真的 sort (%s)"
          % where(cluster_raw, "found.sort(LEXICOGRAPHIC);"),
          members_are_sorted_lexicographically(cluster_raw))

    check("B-3 extractFromCluster / dischargeToNetwork 都走集群快照 (%s)"
          % where(be_raw, "private long extractFromCluster("),
          extract_from_cluster_is_whole_cluster(be_raw))

    check("B-4 网络节点只报本块（getStored/getCapacity），不报集群 (%s)"
          % where(node_raw, "public long getStored()"),
          node_reports_own_only(node_raw))

    check("B-5 网络耗能先非立方 provider、自己抽自己 = 0 (%s)"
          % where(node_raw, "want = Math.max(0L, amount - nonCubeStored);"),
          node_extract_defers_to_non_cube(node_raw))

    print("\n=== C 组：③ 范围充电器覆盖任一块即可整簇充电 ===")
    check("C-1 chargeBlock 取 Capabilities.EnergyStorage.BLOCK 并真收 (%s)"
          % where(charger_raw, "Capabilities.EnergyStorage.BLOCK, pos, blockEntity.getBlockState()"),
          charger_uses_block_capability(charger_raw))

    check("C-2 满电判定（simulate，%s）早于真取电（%s）且判满失败不扣电/不登记高亮"
          % (where_in(charger_raw, "private boolean chargeBlock(",
                      "storage.receiveEnergy(transfer, true) <= 0"),
             where_in(charger_raw, "private boolean chargeBlock(",
                      "storage.receiveEnergy(transfer, false)")),
          full_check_precedes_real_receive(charger_raw))

    # 第 124 轮 R-1 新增不变量：`transferBudget()` 纯函数 + 只调用一次（「先算预算再判满」的前提）
    check("C-5 transferBudget() 在 chargeBlock 里只被调用一次 (%s)"
          % where_in(charger_raw, "private boolean chargeBlock(", "transferBudget()"),
          transfer_budget_called_once(charger_raw))

    check("C-6 transferBudget() 只读（无 energyStorage 写 / 无 setChanged / 无高亮登记）(%s)"
          % where(charger_raw, "private int transferBudget()"),
          transfer_budget_is_pure(charger_raw))

    check("C-7 立方能力视图的 simulate 分支只读（R-1 判满不写世界的前提）(%s)"
          % where_in(be_raw, "public int receiveEnergy(", "if (simulate) {"),
          capability_simulate_is_read_only(be_raw))

    check("C-3 立方能力对全部方向 + null 都返回集群视图 (%s)"
          % where(be_raw, "(blockEntity, direction) -> blockEntity.getExternalEnergyStorage()"),
          cube_capability_registered_all_sides(be_raw))

    check("C-4 外部 FE 注入不看门控、canReceive 恒 true (%s)"
          % where(be_raw, "public boolean canReceive()"),
          external_receive_ignores_gate(be_raw))

    print("\n=== D 组：④ 挖下只保留该方块自身电量 ===")
    check("D-1 getDrops 只写本块 storage（getOwnEnergyStored + saveToItem）(%s)"
          % where(block_raw, "cube.saveToItem(stack, params.getLevel().registryAccess());"),
          drops_carry_own_energy_only(block_raw))

    check("D-2 loadAdditional 设定值写本块、不并进别人 (%s)"
          % where(be_raw, "energyStorage.restoreEnergy(saved);"),
          load_restores_own_energy_without_merging(be_raw))

    check("D-3 物品与方块共用同一个 TAG_ENERGY（挖下=放下）(%s / %s)"
          % (where(be_raw, "tag.putInt(TAG_ENERGY, energyStorage.getEnergyStored());"),
             where(item_raw, "BlockItem.setBlockEntityData(")),
          item_and_block_share_same_tag(be_raw, item_raw))

    print("\n=== E 组：⑤ 不复制 / 不丢失的边界 ===")
    check("E-1 快照只存坐标、不缓存方块实体引用 (%s)"
          % where(cluster_raw, "private final List<BlockPos> members;"),
          snapshot_holds_coordinates_not_block_entities(cluster_raw))

    check("E-2 stored/capacity 对取不到的成员两边都不计 (%s)"
          % where(cluster_raw, "public long stored()"),
          stored_and_capacity_skip_unloaded(cluster_raw))

    check("E-3 MAX_MEMBERS = 64 且 collect 用它截断 (%s)"
          % where(cluster_raw, "public static final int MAX_MEMBERS = 64;"),
          max_members_limit(cluster_raw))

    check("E-4 invalidateAround = 坐标 + 6 邻，放置/邻块/破坏/移除/读档都调 (%s)"
          % where(cluster_raw, "public static void invalidateAround("),
          invalidate_timings(cluster_raw, block_raw, be_raw))

    check("E-5 客户端不解析集群 + 所有写路径 isClientSide 早退 (%s)"
          % where(cluster_raw, "public static Snapshot resolve("),
          client_never_resolves_cluster(cluster_raw, be_raw))

    check("E-6 方块 ticker 客户端返回 null (%s)"
          % where(block_raw, "if (level.isClientSide())"),
          cube_ticker_null_on_client(block_raw))

    print("\n=== F 组：⑥ 容量显示口径 = 整簇 ===")
    check("F-1 菜单 getClusterEnergy/Capacity = 数据槽 2/3、4/5 (%s)"
          % where(menu_raw, "FEEnergyCubeBlockEntity.join(data.get(2), data.get(3))"),
          menu_display_uses_cluster_slots(menu_raw))

    check("F-2 数据槽 2..5 = 集群存量/容量 lo/hi (%s)"
          % where(be_raw, "case 2 -> lo(clusterStored);"),
          container_data_exposes_cluster(be_raw))

    check("F-3 界面电量条 + 集群存量行用集群值、单块值单列 (%s)"
          % where(screen_raw, "() -> menu.getClusterEnergy(), () -> menu.getClusterCapacity(),"),
          screen_display_uses_cluster(screen_raw))

    check("F-4 客户端 getClusterEnergyStored/Capacity 退回本块值 (%s)"
          % where(be_raw, "public long getClusterEnergyStored()"),
          get_cluster_energy_falls_back_to_own_on_client(be_raw))


def group_models():
    print("\n=== M 组：行为模型（用户场景：12 块 × 50M = 600M 容量、存量 580M、只覆盖 1 块） ===")

    # ---- ① 单块被覆盖 ⇒ 整簇被填满（含非覆盖成员） ----
    snap = user_scenario()
    charger = Charger(50_000_000)
    covered = [(9, 0, 0)]           # 充电器只盖住第 10 块（存量 45M，缺口 5M）
    start_total = snap.stored()
    start_charger = charger.energy
    ticks = 12_000
    for _ in range(ticks):
        charger.tick(snap, covered)
    cell = snap.by_pos[(9, 0, 0)]
    neighbor_free = snap.by_pos[(8, 0, 0)]
    check("M-1a 只覆盖 1 块，%d tick 后：覆盖块已满(%d) 且**未被覆盖**的块也涨了(%d > 45000000)"
          % (ticks, cell.energy, neighbor_free.energy),
          cell.energy == SINGLE_CAP and neighbor_free.energy > 45_000_000)
    check("M-1b 总量守恒：集群 +%d FE == 充电器 -%d FE（零损耗）"
          % (snap.stored() - start_total, start_charger - charger.energy),
          (snap.stored() - start_total) == (start_charger - charger.energy) == ticks * CHARGER_RATE)

    # ---- 填满整簇：需要 20M / 500 = 40,000 tick ----
    snap2 = user_scenario()
    charger2 = Charger(50_000_000)
    need = snap2.capacity() - snap2.stored()
    fill_ticks = need // CHARGER_RATE
    for _ in range(fill_ticks):
        charger2.tick(snap2, covered)
    filled = all(c.energy == c.cap for c in snap2.members)
    check("M-1c 填满 20M 缺口需要 %d tick（≈%.1f 分钟，@%d FE/t × %d 块覆盖）：整簇最终全满=%s"
          % (fill_ticks, fill_ticks / 20.0 / 60.0, CHARGER_RATE, len(covered), filled),
          filled and snap2.stored() == snap2.capacity() == 12 * SINGLE_CAP)
    check("M-1d 填满口径：任何成员都不超过自身容量（无溢出）+ 缺口 = 20M 与用户「580 多兆」一致",
          all(0 <= c.energy <= c.cap for c in snap2.members) and need == 20_000_000)

    # ---- 顺序：preferred 先满，其它成员一 tick 都没涨（解释用户「只充靠近的那一个」的观感） ----
    snap3 = user_scenario()
    charger3 = Charger(50_000_000)
    for _ in range(10_000):         # 5M / 500 = 10,000 tick
        charger3.tick(snap3, covered)
    others_untouched = all(c.energy == (SINGLE_CAP if c.x < 8 else 45_000_000)
                           for c in snap3.members if c.x != 9)
    check("M-1e 前 10,000 tick 只填被覆盖的那一块（其余 11 块一格没动=%s）—— 设计口径，非 bug"
          % others_untouched,
          others_untouched and snap3.by_pos[(9, 0, 0)].energy == SINGLE_CAP)

    # ---- ② 跨成员扣电 + 不双计 ----
    snap4 = user_scenario()
    got = snap4.extract(60_000_000)
    check("M-2a 从整簇抽 60M = 恰好 60M（x0 扣 50M→0、x1 扣 50M→40M，其余不动）",
          got == 60_000_000
          and snap4.by_pos[(0, 0, 0)].energy == 0
          and snap4.by_pos[(1, 0, 0)].energy == 40_000_000
          and snap4.by_pos[(2, 0, 0)].energy == SINGLE_CAP
          and snap4.stored() == 580_000_000 - 60_000_000)
    snap5 = user_scenario()
    got = snap5.extract(10_000_000_000)
    check("M-2b 抽超量：返回 = 整簇存量 580M、全簇清零（不返回请求量）",
          got == 580_000_000 and snap5.stored() == 0)
    snap6 = user_scenario()
    start = snap6.stored()
    snap6.extract(60_000_000)
    check("M-2c 不双计：扣掉的总额 = 60M（不是每人各扣 60M）",
          start - snap6.stored() == 60_000_000)

    # ---- ③ 充电器满电判定 = 整簇满 ----
    snap7 = user_scenario()
    charger7 = Charger(50_000_000)
    while snap7.stored() < snap7.capacity():
        charger7.tick(snap7, covered)
    moved_after_full = charger7.tick(snap7, covered)
    check("M-3a 整簇满后充电器一 tick 也送不出（满电跳过 = 整簇口径）", moved_after_full == 0)
    snap8 = user_scenario()
    snap8.by_pos[(11, 0, 0)].energy = 0          # 单块留 50M 缺口
    charger8 = Charger(50_000_000)
    first = charger8.tick(snap8, covered)
    check("M-3b 未满就不跳过：某块空着（缺 50M）⇒ 首 tick 收下 %d = 预算 %d" % (first, CHARGER_RATE),
          first == CHARGER_RATE)

    # ---- ③ R-1（124-C 已修）：≥43 块 int 饱和区间，simulate 判满必须仍然给出「还能收 N」 ----
    big = Snapshot([Cube(i, 0, 0, SINGLE_CAP) for i in range(43)])
    big.members[42].energy = 47_483_648              # 42×50M + 47,483,648 = 2,147,483,648 > INT_MAX
    real_space = big.capacity() - big.stored()
    stale = old_int_full_check(big)                  # R-1 之前的 int 比大小（错）
    fresh = capability_receive_simulate(big, CHARGER_RATE) <= 0   # R-1 之后（对）
    check("M-3c R-1 已修：43 块 int 饱和时旧判据「满」=%s（错），simulate 判满「满」=%s（对，"
          "真实还剩 %d FE 能收）" % (stale, fresh, real_space),
          stale and not fresh and real_space > 0 and clamp_int(big.capacity()) == INT_MAX)

    # ---- ④ 掉落只带自身 + 放回不复制 ----
    snap9 = user_scenario()
    dropped_own = snap9.by_pos[(9, 0, 0)].energy          # = getOwnEnergyStored()
    del snap9.by_pos[(9, 0, 0)]
    snap9.members = [c for c in snap9.members if c.x != 9]
    snap9.loaded.discard((9, 0, 0))
    remaining = snap9.stored()
    check("M-4a 挖下第 10 块：掉落物带自身 %d FE（不是整簇 %d），剩余 %d 块存量 %d、容量 %d"
          % (dropped_own, 580_000_000, 11, remaining, snap9.capacity()),
          dropped_own == 45_000_000 and remaining == 535_000_000 and snap9.capacity() == 550_000_000)
    placed = Cube(9, 0, 0, dropped_own)
    snap9.members.append(placed)
    snap9.members.sort(key=lambda c: (c.x, c.y, c.z))
    snap9.by_pos[placed.pos] = placed
    snap9.loaded.add(placed.pos)
    check("M-4b 放回去：整簇回到 %d FE / %d 容量（总量守恒、无复制）"
          % (snap9.stored(), snap9.capacity()),
          snap9.stored() == 580_000_000 and snap9.capacity() == 12 * SINGLE_CAP)

    # ---- ⑤ 卸载 / 重载 / 截断 ----
    snap10 = user_scenario()
    for x in (0, 1, 2):
        snap10.loaded.discard((x, 0, 0))
    check("M-5a 卸载 3 块：stored/capacity 两边都变小（%d/%d），stored ≤ capacity 恒成立"
          % (snap10.stored(), snap10.capacity()),
          snap10.stored() == 580_000_000 - 150_000_000
          and snap10.capacity() == 9 * SINGLE_CAP
          and snap10.stored() <= snap10.capacity())
    got = snap10.extract(30_000_000)
    check("M-5b 部分卸载时抽电只从已加载成员扣（30M 全来自 x3）", 
          got == 30_000_000 and snap10.by_pos[(3, 0, 0)].energy == 20_000_000)
    for x in (0, 1, 2):
        snap10.loaded.add((x, 0, 0))
    check("M-5c 重载后总量回来（无丢失 / 无复制）",
          snap10.stored() == 580_000_000 - 30_000_000
          and snap10.capacity() == 12 * SINGLE_CAP)

    wall = Snapshot([Cube(i, 0, 0, SINGLE_CAP) for i in range(100)][:MAX_MEMBERS])
    check("M-5d 已知上限 R-2：>%d 块的结构被截断（100 块的墙 ⇒ 视图只含 %d 块、容量 %d）"
          % (MAX_MEMBERS, len(wall.members), wall.capacity()),
          len(wall.members) == MAX_MEMBERS and wall.capacity() == MAX_MEMBERS * SINGLE_CAP)

    # ---- ⑥ 客户端视图 = 本块 ----
    snap11 = user_scenario()
    client_view = Snapshot([snap11.by_pos[(9, 0, 0)]])
    check("M-6a 客户端 resolve 返回单元素快照 ⇒ 存/容 = 本块 %d/%d"
          % (client_view.stored(), client_view.capacity()),
          client_view.stored() == 45_000_000 and client_view.capacity() == SINGLE_CAP)

    # ---- 「瞬间充满」不可能：四层速率上限（R-3） ----
    snap12 = Snapshot([Cube(i, 0, 0, 0) for i in range(12)])
    charger12 = Charger(50_000_000)
    all_covered = [(i, 0, 0) for i in range(12)]
    one_tick = charger12.tick(snap12, all_covered)
    check("M-7 R-3 上限：12 块全被覆盖时单 tick 最多 %d FE（= 12 × 充电器速率），"
          "相对 600M 容量需要 %d tick 才满" 
          % (one_tick, (12 * SINGLE_CAP) // max(1, one_tick)),
          one_tick <= 12 * CHARGER_RATE)


def group_counterexamples():
    cluster_code = strip_comments(read(CLUSTER))
    be_code = strip_comments(read(CUBE_BE))
    be_raw = read(CUBE_BE)                      # 判据 X-2 要往「真代码」里插语句，不能用去注释副本
    block_code = strip_comments(read(CUBE_BLOCK))
    menu_code = strip_comments(read(CUBE_MENU))
    charger_code = strip_comments(read(CHARGER))

    print("\n=== C 组：反例自证（把坏写法拼回源码副本 / 模型，断言判据真的判负） ===")

    # A 组：receiveIntoCluster 改成「只写自己」
    broken = be_code.replace(
        "        return snapshot().receive(amount, worldPosition);",
        "        return energyStorage.receiveEnergy((int) Math.min(amount, Integer.MAX_VALUE), false);", 1)
    counterexample("X-1 receiveIntoCluster 改成只写自己 ⇒ A-2 判负（外部 FE 只充一块）",
                   broken != be_code and not receive_into_cluster_uses_snapshot(broken))

    # A 组：能力视图先看门控（第 106 轮修掉的旧写法）
    broken = be_raw.replace(
        "            if (toReceive <= 0) {\n                return 0;\n            }",
        "            if (toReceive <= 0) {\n                return 0;\n            }\n"
        "            if (getGateState() != Gate.ALLOW) {\n                return 0;\n            }", 1)
    counterexample("X-2 能力视图加回门控 ⇒ C-4 判负（外部 FE 注入不该看网络状态）",
                   broken != be_raw and not external_receive_ignores_gate(broken))

    # A 组：Snapshot#receive 不再遍历其它成员（`pos.equals(preferred)` 是 receive 独有的上下文）
    broken = cluster_code.replace(
        "            for (final BlockPos pos : members) {\n"
        "                if (remaining <= 0L) {\n                    break;\n                }\n"
        "                if (pos.equals(preferred)) {",
        "            for (final BlockPos pos : java.util.List.<BlockPos>of()) {\n"
        "                if (remaining <= 0L) {\n                    break;\n                }\n"
        "                if (pos.equals(preferred)) {", 1)
    counterexample("X-3 Snapshot#receive 不再遍历其它成员 ⇒ A-3 判负（整簇共享容量变假话）",
                   broken != cluster_code and not snapshot_receive_fills_whole_cluster(broken))

    # B 组：extract 用「请求量」而不是「实际扣掉量」累计（同一度电算两次的典型写法）
    broken = cluster_code.replace(
        "                final long got = cube.extractOwn(remaining);",
        "                final long got = cube.extractOwn(remaining);\n                extracted += remaining;", 1)
    counterexample("X-4 extract 累计请求量而非实际量 ⇒ B-1 判负（多算）",
                   broken != cluster_code and not snapshot_extract_is_lexicographic(broken))

    # C 组：充电器改用 ITEM 能力（拿不到方块集群视图）
    broken = charger_code.replace(
        "            Capabilities.EnergyStorage.BLOCK, pos, blockEntity.getBlockState(), blockEntity, null",
        "            Capabilities.EnergyStorage.ITEM, pos, blockEntity.getBlockState(), blockEntity, null", 1)
    counterexample("X-5 chargeBlock 改用 ITEM 能力 ⇒ C-1 判负（覆盖任一块不再整簇结算）",
                   broken != charger_code and not charger_uses_block_capability(broken))

    # === C-2 的反例：只在 chargeBlock 的方法体里动刀 ===
    # 源码副本已去注释，行内可能留下「只剩缩进」的空行，因此**不硬编码整段空白**，
    # 而是按行索引定位（simulate 判满行 / 真取电行 / transferBudget 行）再重排 / 插句。
    cb_body = method_body(charger_code, "private boolean chargeBlock(")
    cb_lines = cb_body.split("\n") if cb_body is not None else []

    def cb_rebuild(lines):
        """把改过的行拼回 chargeBlock 副本，其余源码原样保留。"""
        if cb_body is None:
            return charger_code
        return charger_code.replace(cb_body, "\n".join(lines), 1)

    def cb_index_of(needle):
        for index, line in enumerate(cb_lines):
            if needle in line:
                return index
        return -1

    i_sim = cb_index_of("storage.receiveEnergy(transfer, true) <= 0")
    i_real = cb_index_of("storage.receiveEnergy(transfer, false)")
    i_budget = cb_index_of("transferBudget()")
    located = 0 <= i_sim < i_real and i_budget >= 0

    # X-6a：把 simulate 判满**挪到真取电之后**（真扣电发生在判满之前）
    broken = charger_code
    if located:
        skip_lines = cb_lines[i_sim:i_real]            # simulate 判满的那个 if 块（含右花括号）
        broken = cb_rebuild(cb_lines[:i_sim] + [cb_lines[i_real]] + skip_lines + cb_lines[i_real + 1:])
    counterexample("X-6a 把 simulate 判满挪到真取电之后 ⇒ C-2 判负（真扣电发生在判满之前）",
                   located and broken != charger_code
                   and not full_check_precedes_real_receive(broken))

    # X-6b：判满失败**仍扣电**（在跳过分支里 extractEnergy）
    broken = charger_code
    if located:
        broken = cb_rebuild(cb_lines[:i_sim + 1]
                            + ["            energyStorage.extractEnergy(transfer, false);"]
                            + cb_lines[i_sim + 1:])
    counterexample("X-6b 判满失败仍扣电 ⇒ C-2 判负（「满电跳过不扣电量」被破坏）",
                   located and broken != charger_code
                   and not full_check_precedes_real_receive(broken))

    # X-6c：判满失败**仍登记高亮**（满电目标被画成「正在充电」）
    broken = charger_code
    if located:
        broken = cb_rebuild(cb_lines[:i_sim + 1]
                            + ["            tickChargedBlocks.add(pos);"]
                            + cb_lines[i_sim + 1:])
    counterexample("X-6c 判满失败仍登记高亮 ⇒ C-2 判负（满电目标不该出现在高亮里）",
                   located and broken != charger_code
                   and not full_check_precedes_real_receive(broken))

    # X-17：chargeBlock 里把 transferBudget() 调两次（R-1 的「纯函数、只算一次」前提被破坏）
    broken = charger_code
    if located:
        broken = cb_rebuild(cb_lines[:i_budget + 1]
                            + ["        final int budgetAgain = transferBudget();"]
                            + cb_lines[i_budget + 1:])
    counterexample("X-17 chargeBlock 里把 transferBudget() 调两次 ⇒ C-5 判负",
                   located and broken != charger_code
                   and not transfer_budget_called_once(broken))

    # X-18：给 transferBudget() 塞一句 energyStorage 写（纯函数前提被破坏）
    budget_body = method_body(charger_code, "private int transferBudget()")
    broken = charger_code
    if budget_body is not None:
        broken_body = budget_body.replace(
            "        final int stored = energyStorage.getEnergyStored();",
            "        energyStorage.extractEnergy(1, false);\n"
            "        final int stored = energyStorage.getEnergyStored();", 1)
        broken = charger_code.replace(budget_body, broken_body, 1)
    counterexample("X-18 transferBudget() 里加了 energyStorage 写 ⇒ C-6 判负（判满失败不扣电会被绕过）",
                   budget_body is not None and broken != charger_code
                   and not transfer_budget_is_pure(broken))

    # X-19：simulate 分支改成「真灌进集群」（R-1 的判满从此会写世界）
    broken = be_code.replace("                return clampToInt(request);",
                             "                return clampToInt(receiveIntoCluster(request));", 1)
    counterexample("X-19 能力视图的 simulate 分支改成真灌 ⇒ C-7 判负（判满探测会写世界）",
                   broken != be_code and not capability_simulate_is_read_only(broken))

    # D 组：掉落物写集群电量
    broken = block_code.replace("if (cube.getOwnEnergyStored() <= 0) {",
                                "if (cube.getClusterEnergyStored() <= 0) {", 1)
    counterexample("X-7 getDrops 改用 getClusterEnergyStored ⇒ D-1 判负（拆一块带走整簇电量）",
                   broken != block_code and not drops_carry_own_energy_only(broken))

    # D 组：读档走 receiveEnergy（被 maxReceive 截断 ⇒ 「存满了重进又空了」）
    broken = be_code.replace("        energyStorage.restoreEnergy(saved);",
                             "        energyStorage.receiveEnergy(saved, false);", 1)
    counterexample("X-8 loadAdditional 改回 receiveEnergy ⇒ D-2 判负（设定值语义被速率截断）",
                   broken != be_code and not load_restores_own_energy_without_merging(broken))

    # E 组：去掉客户端早退
    broken = be_code.replace(
        "        if (level == null || level.isClientSide() || amount <= 0L) {\n            return 0L;\n        }\n"
        "        return snapshot().extract(amount);",
        "        if (level == null || amount <= 0L) {\n            return 0L;\n        }\n"
        "        return snapshot().extract(amount);", 1)
    counterexample("X-9 去掉 extractFromCluster 的 isClientSide 早退 ⇒ E-5 判负（客户端写世界）",
                   broken != be_code and not client_never_resolves_cluster(cluster_code, broken))

    # E 组：MAX_MEMBERS 改成 1
    broken = cluster_code.replace("public static final int MAX_MEMBERS = 64;",
                                  "public static final int MAX_MEMBERS = 1;", 1)
    counterexample("X-10 MAX_MEMBERS 改成 1 ⇒ E-3 判负（相邻立方不再并池）",
                   broken != cluster_code and not max_members_limit(broken))

    # E 组：去掉排序（成员顺序变成遍历顺序 ⇒ 与坐标无关、不确定性）
    broken = cluster_code.replace("        found.sort(LEXICOGRAPHIC);", "", 1)
    counterexample("X-11 去掉 found.sort(LEXICOGRAPHIC) ⇒ B-2 判负（顺序不再确定）",
                   broken != cluster_code and not members_are_sorted_lexicographically(broken))

    # F 组：菜单显示改用本块槽位
    broken = menu_code.replace("return FEEnergyCubeBlockEntity.join(data.get(2), data.get(3));",
                               "return FEEnergyCubeBlockEntity.join(data.get(0), data.get(1));", 1)
    counterexample("X-12 菜单集群存量改用本块槽位 0/1 ⇒ F-1 判负（界面不再显示整簇）",
                   broken != menu_code and not menu_display_uses_cluster_slots(broken))

    # 模型反例：extract 每个成员都按 amount 扣（同一度电算两次）
    def buggy_extract_double(snap, amount):
        total = 0
        for cube in snap.members:
            total += cube.extract_own(amount)
        return total

    snap = user_scenario()
    start = snap.stored()
    buggy_extract_double(snap, 60_000_000)
    counterexample("X-13 模型反例：每人各扣 60M ⇒ 扣掉总额 %d ≠ 60M（M-2c 判据不是摆设）"
                   % (start - snap.stored()),
                   start - snap.stored() != 60_000_000)

    # 模型反例：掉落物带走整簇电量 ⇒ 放回去时总量变成 1,115M > 600M 容量
    snap = user_scenario()
    buggy_drop = snap.stored()                     # 580M（错）
    remaining = 535_000_000
    counterexample("X-14 模型反例：掉落物带整簇 580M ⇒ 放回去 %d + 535M = %d > 600M 容量（M-4b 判据不是摆设）"
                   % (remaining, remaining + buggy_drop),
                   remaining + buggy_drop > 12 * SINGLE_CAP)

    # 模型反例：receive 只填 preferred ⇒ 整簇填充判负
    snap = user_scenario()
    for _ in range(40_000):
        snap.by_pos[(9, 0, 0)].receive_own(CHARGER_RATE)
    counterexample("X-15 模型反例：只填 preferred ⇒ 40,000 tick 后整簇仍只有 585M（填满判负）",
                   snap.stored() != snap.capacity())

    # 模型反例：客户端解析整簇 ⇒ 客户端读数 != 本块读数
    snap = user_scenario()
    counterexample("X-16 模型反例：客户端若解析整簇 ⇒ 读数 580M ≠ 本块 45M（M-6a 判据不是摆设）",
                   snap.stored() != snap.by_pos[(9, 0, 0)].energy)


def main():
    print("第 124 轮自检（F1 集群电力）：FE 能量立方「输入输出按整簇结算、挖下只保留自身」")
    print("本轮 = 审计轮：源码未改；断言 = 源码锚点 + 行为模型 + 反例自证。")
    group_anchors()
    group_models()
    group_counterexamples()
    print("\n断言 %d 条（其中反例 %d 条），失败 %d 条"
          % (CHECKS[0], COUNTER[0], len(FAILS)))
    print("已知残留：R-1 已由 124-C 修（本自检 C-2/C-5/C-6 + M-3c 跟进新写法）/ R-2 >64 块截断 / "
          "R-3 速率上限（需实机）/ R-4 先填自己被误读为「只充近的那一块」")
    if FAILS:
        print("FAILED:")
        for name in FAILS:
            print("  - " + name)
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
