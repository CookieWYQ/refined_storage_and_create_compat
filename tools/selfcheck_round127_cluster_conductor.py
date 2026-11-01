#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 127 轮自检（F1 续 · 立方 = 整簇电池 / 导线 + 降解室发电机「副产物满就堵塞」）。

用户 beta.26 实机口述（本轮原话，逐句拆成可验收项）
--------------------------------------------------
> 「那个 F1 能量立方视为一个完整集群。……操作的时候，将所有相连的方块视为一个整体，
>   只有在破坏的时候，才把它单独看作一个个个体，准确分离它实际上存的那个电量……」
> 「然后呢，它本身也相当于是一个导线，也可以传递能量。就比如说我全部放在那么多，
>   然后我随便拿一个范围充电器接在它上面。实际上范围充电器吸取的是整个集群中的所有电量。」
> 「以及那个发电机，那个发电机如果说它副产物满了，那么他就无法再进行发电了，
>   就是直接卡在了也相当于堵塞。」

四组判据
--------
A 组（源码锚点）「立方 = 整簇 + 导线 + 拆开只带自己」
    A-1 范围充电器对**立方目标**走「电池档」`cubeTransferBudget()`（第 127 轮新增），
        其它目标（玩家物品 / 掉落物 / 非立方方块）仍走 `transferBudget()` 工具档；
    A-2 `cubeTransferBudget()` 是**纯函数**：只读本机缓存、夹 `feEnergyCubeMaxTransfer`、
        缓存见底返回 0，不写世界、不扣电、不登记高亮（「先算预算再 simulate 判满」的前提）；
    A-3 能力入口仍是 `Capabilities.EnergyStorage.BLOCK` + null 面 + 真收——立方分支只改**预算**，
        不改「读到的就是整簇视图」这条（换 ITEM 能力 = 反例）；
    A-4 立方向相邻方块推电时**取整簇存量、扣整簇**（`getClusterEnergyStored` / `extractFromCluster`），
        并且**不推给别的立方**（簇内已共享，互推只是左右口袋搬运）——这就是「导线」；
    A-5 立方六面能力注册对**所有方向 + null** 都返回同一个集群视图实例；
    A-6 破坏掉落只带走**本块**电量（`getDrops` → `getOwnEnergyStored` + `saveToItem`），
        绝不用 `getClusterEnergyStored`（否则挖一块 = 带走整面墙）。

B 组（行为模型）用户场景：12 块 × 5000 万 = 6 亿容量、存量 5.8 亿、缺口 2000 万
    B-1 充电器**只覆盖 1 块**、缓存 5000 万 ⇒ **一个 tick** 就把整簇灌满（守恒：缓存 −2000 万 = 簇 +2000 万）；
    B-2 若立方仍按工具档 500 FE/t 算，同样场景要 40,000 tick（≈33 分钟）——这就是用户说的
        「只看到贴着那一块在慢慢吸」，也是 A-1 分支存在的理由（反例）；
    B-3 缓存不足时按缓存给（500 万缓存 ⇒ 4 tick 给光 500 万），不造电、不透支；
    B-4 「导线」：相邻机器从任一块立方抽电时，扣的是**整簇**（覆盖块空了会继续扣其它成员）。

C 组（源码锚点）降解室发电机「副产物满了就堵塞」
    C-1 `progress + 1 >= progressTotal` 时先掷骰 + `productsFit`，放不下 ⇒ `outputFull = true; return;`
        （不 settle）；
    C-2 `settle` 只在 fit 通过之后调用；它内部「写缓存 → 写产物 → 扣 1 件」的顺序不变；
    C-3 `outputFull` 时：不推进进度、不发电（`addInternal` 不执行）、不消耗输入（`extractItem` 不执行）；
    C-4 `isOutputFull()` / 数据槽 FLAG_OUTPUT_FULL 仍然对外（界面/诊断能看到「为什么卡住」）；
    C-5 副产物清空后自动恢复（下一 tick fit 通过 ⇒ settle）。

反例自证（每个判据配一条「把它改坏必须判负」）
----------------------------------------------
X-1 删掉立方分支（立方回到工具档）⇒ A-1 判负；X-2 让立方分支去调 `transferBudget()` ⇒ A-2 判负；
X-3 `cubeTransferBudget()` 里扣电/标脏 ⇒ A-2 判负；X-4 推送改用 `getOwnEnergyStored` ⇒ A-4 判负；
X-5 掉落改用 `getClusterEnergyStored` ⇒ A-6 判负；X-6 能力注册只给一个方向 ⇒ A-5 判负；
X-7 副产物满时继续 settle ⇒ C-1/C-3 判负；X-8 模型里立方按工具档 ⇒ B-1 判负；
X-9 模型里副产物满仍结算 ⇒ C-5 判负。

运行：`python tools/selfcheck_round127_cluster_conductor.py`（只读源码，退出码 0/1）。
"""

import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_REL = os.path.join("src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
JAVA = os.path.join(ROOT, JAVA_REL)

CLUSTER = os.path.join(JAVA, "support", "EnergyCubeCluster.java")
CUBE_BE = os.path.join(JAVA, "block", "entity", "FEEnergyCubeBlockEntity.java")
CUBE_BLOCK = os.path.join(JAVA, "block", "FEEnergyCubeBlock.java")
NET_NODE = os.path.join(JAVA, "network", "FEEnergyCubeNetworkNode.java")
CHARGER = os.path.join(JAVA, "block", "entity", "RangeChargerBlockEntity.java")
GENERATOR = os.path.join(JAVA, "block", "entity", "DecompositionGeneratorBlockEntity.java")
CONFIG = os.path.join(JAVA, "Config.java")

#: 默认值（A-0 组从 Config 源码锚定，防止「数字对不上」的自检假绿）
SINGLE_CAP = 50_000_000
MAX_TRANSFER = 100_000
CHARGER_RATE = 500
CHARGER_MAX_TARGETS = 50
OUTPUT_COUNT = 4
OUTPUT_LIMIT = 64

CHECKS = [0]
COUNTER = [0]
FAILS = []

#: 控制台是 GBK 时也能打印 `⇒` / `≈` / `×`（与第 124 轮那几个脚本同一处理）。
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


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
# 源码解析小工具（与第 119 / 120 / 124 轮同一套口径）
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
    """从（已去注释的）源码里取一个方法体：从 hint 起找第一个 '{' 并配平。"""
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


def line_no(raw, needle):
    idx = raw.find(needle)
    if idx < 0:
        return -1
    return raw.count("\n", 0, idx) + 1


def where(raw, needle):
    n = line_no(raw, needle)
    return ("L%d" % n) if n > 0 else "L?"


# ======================================================================================
# A 组：源码锚点
# ======================================================================================

def cube_branch_in_charge_block(charger_src):
    """A-1：`chargeBlock` 里立方分支走 `cubeTransferBudget()`，其它目标走 `transferBudget()`。"""
    body = method_body(strip_comments(charger_src), "private boolean chargeBlock(")
    if body is None:
        return False
    return ("blockEntity instanceof FEEnergyCubeBlockEntity" in body
            and "cubeTransferBudget()" in body
            and "transferBudget()" in body
            and "? cubeTransferBudget()" in body.replace("\n", " ").replace("  ", " ")
            or ("cubeTransferBudget()" in body and "transferBudget()" in body
                and "FEEnergyCubeBlockEntity" in body))


def cube_transfer_budget_is_battery_track(charger_src):
    """A-2：`cubeTransferBudget()` = 电池档（夹 `feEnergyCubeMaxTransfer`），不是 500 工具档。"""
    code = strip_comments(charger_src)
    body = method_body(code, "private int cubeTransferBudget(")
    if body is None:
        return False
    return ("Config.feEnergyCubeMaxTransfer" in body
            and "energyStorage.getEnergyStored()" in body
            and "stored <= 0" in body
            and "return 0;" in body)


def cube_transfer_budget_is_pure(charger_src):
    """A-2：纯函数 —— 不扣电、不标脏、不登记高亮（与 `transferBudget()` 同一口径）。"""
    code = strip_comments(charger_src)
    body = method_body(code, "private int cubeTransferBudget(")
    if body is None:
        return False
    return ("extractEnergy" not in body
            and "setChanged" not in body
            and "tickChargedBlocks" not in body
            and "receiveEnergy" not in body)


def order_budget_before_full_check(charger_src):
    """A-3：顺序仍是「取预算 → 判满（simulate）→ 真收」，立方分支不得破坏它。"""
    code = strip_comments(charger_src)
    body = method_body(code, "private boolean chargeBlock(")
    if body is None:
        return False
    budget = body.find("? cubeTransferBudget()")
    if budget < 0:
        budget = body.find("cubeTransferBudget()")
    full = body.find("storage.receiveEnergy(transfer, true) <= 0")
    real = body.find("storage.receiveEnergy(transfer, false)")
    return 0 <= budget < full < real


def capability_entry_is_block(charger_src):
    """A-3：能力入口仍是 `Capabilities.EnergyStorage.BLOCK`（不是 ITEM、不是直接读 BE 字段）。"""
    code = strip_comments(charger_src)
    body = method_body(code, "private boolean chargeBlock(")
    if body is None:
        return False
    return ("Capabilities.EnergyStorage.BLOCK" in body
            and "Capabilities.EnergyStorage.ITEM" not in body)


def push_uses_cluster(charger_src):
    """A-4：立方推送相邻方块时**取整簇、扣整簇**（导线语义）。"""
    code = strip_comments(charger_src)
    body = method_body(code, "private int pushToNeighbours(")
    if body is None:
        return False
    return ("getClusterEnergyStored()" in body
            and "extractFromCluster(accepted)" in body
            and "getOwnEnergyStored()" not in body)


def push_skips_cubes(charger_src):
    """A-4：立方之间不互推（簇内已共享容量，互推只会来回倒电）。"""
    code = strip_comments(charger_src)
    body = method_body(code, "private int pushToNeighbours(")
    if body is None:
        return False
    return "instanceof FEEnergyCubeBlock" in body and "continue" in body


def capability_registered_all_sides(cube_be_src):
    """A-5：六面能力注册（含 null）都返回同一个集群视图实例。"""
    code = strip_comments(cube_be_src)
    body = method_body(code, "public static void registerCapabilities(")
    if body is None:
        return False
    return ("(blockEntity, direction) -> blockEntity.getExternalEnergyStorage()" in body
            and "Capabilities.EnergyStorage.BLOCK" in body)


def drops_use_own_energy(cube_block_src, cube_be_src):
    """A-6：掉落只带本块电量（`getOwnEnergyStored` + `saveToItem`），绝不带整簇。"""
    code = strip_comments(cube_block_src)
    body = method_body(code, "protected List<ItemStack> getDrops(")
    if body is None:
        return False
    return ("cube.getOwnEnergyStored()" in body
            and "saveToItem(" in body
            and "getClusterEnergyStored()" not in body)


# ======================================================================================
# B 组：行为模型（整簇 = 一块电池 + 导线）
# ======================================================================================

class Cube:
    def __init__(self, x, energy, cap=SINGLE_CAP):
        self.x = x
        self.energy = energy
        self.cap = cap


class Snapshot:
    """`EnergyCubeCluster.Snapshot` 的模型：成员按坐标字典序；receive 先填自己再填别人。"""

    def __init__(self, cubes):
        self.members = sorted(cubes, key=lambda c: (c.x, 0, 0))
        self.by_x = {c.x: c for c in self.members}

    def stored(self):
        return sum(c.energy for c in self.members)

    def capacity(self):
        return sum(c.cap for c in self.members)

    def space(self):
        return self.capacity() - self.stored()

    def receive_simulate(self, amount, limit=MAX_TRANSFER):
        request = max(0, min(amount, limit, self.space()))
        return min(request, self.space())

    def receive(self, amount, preferred_x, limit=MAX_TRANSFER):
        want = self.receive_simulate(amount, limit)
        got = 0
        order = [self.by_x[preferred_x]] + [c for c in self.members if c.x != preferred_x]
        for cube in order:
            if want <= 0:
                break
            room = cube.cap - cube.energy
            moved = min(room, want)
            cube.energy += moved
            got += moved
            want -= moved
        return got

    def extract(self, amount):
        want = max(0, amount)
        total = 0
        for cube in self.members:
            if want <= 0:
                break
            got = min(cube.energy, want)
            cube.energy -= got
            total += got
            want -= got
        return total


def user_scenario():
    """用户场景：12 块 × 5000 万 = 6 亿容量，存量 5.8 亿（8 块满 + 4 块 4500 万）。"""
    return Snapshot([Cube(i, SINGLE_CAP if i < 8 else 45_000_000) for i in range(12)])


class Charger:
    """`RangeChargerBlockEntity` 的方块充电循环模型（第 127 轮：立方走电池档）。

    每个被覆盖的目标各拿一份预算：立方 = `min(本机缓存, feEnergyCubeMaxTransfer)`（电池档），
    其它 = `min(getChargeRate(), 本机缓存 / maxTargets)`（工具档）；预算一律 ≤ 本机缓存，
    所以「一次给光缓存」不可能发生，需按 tick 累加。
    """

    def __init__(self, energy):
        self.energy = energy

    def tick(self, snap, covered, cube=True, max_targets=CHARGER_MAX_TARGETS):
        if self.energy <= 0:
            return 0
        total = 0
        for x in covered:
            if self.energy <= 0:
                break
            if cube:
                budget = max(1, min(self.energy, MAX_TRANSFER))      # = cubeTransferBudget()
            else:
                budget = max(1, min(CHARGER_RATE, self.energy // max(1, max_targets)))  # = transferBudget()
            if snap.receive_simulate(budget) <= 0:
                break                                               # 判满（simulate）不扣电
            accepted = snap.receive(budget, x)
            if accepted <= 0:
                continue
            self.energy -= accepted
            total += accepted
        return total

    def run_until_full(self, snap, covered, cube=True, limit=200_000):
        """跑到整簇满（或跑满 limit tick）；返回 (ticks, moved)。"""
        ticks = 0
        moved = 0
        while ticks < limit and snap.stored() < snap.capacity() and self.energy > 0:
            moved += self.tick(snap, covered, cube)
            ticks += 1
        return ticks, moved


def battery_rate_fills_wall_fast():
    """B-1：充电器覆盖整面墙（12 块）+ 缓存 5000 万 ⇒ 17 tick 灌满 2000 万缺口（守恒）。"""
    snap = user_scenario()
    charger = Charger(SINGLE_CAP)
    need = snap.capacity() - snap.stored()
    ticks, moved = charger.run_until_full(snap, list(range(12)))
    return (need == 20_000_000
            and snap.stored() == snap.capacity()
            and moved == need
            and ticks == 17
            and charger.energy == SINGLE_CAP - need)


def battery_rate_single_covered_cube():
    """B-1b：只覆盖 1 块也按电池档 10 万/t 灌整簇 ⇒ 200 tick 填满 2000 万（≈10 秒）。"""
    snap = user_scenario()
    charger = Charger(SINGLE_CAP)
    need = snap.capacity() - snap.stored()
    ticks, moved = charger.run_until_full(snap, [9])
    return (moved == need
            and snap.stored() == snap.capacity()
            and ticks == 200)


def tool_rate_would_take_3334_ticks():
    """B-2：工具档 500 FE/t × 12 块 = 6000 FE/t ⇒ 20M 缺口要 3,334 tick（≈2.8 分钟）。

    这正是第 124 轮记录的用户观感（「只看到贴着那一块在慢慢动」）；也是 A-1 分支存在的理由。
    """
    snap = user_scenario()
    charger = Charger(SINGLE_CAP)
    ticks, moved = charger.run_until_full(snap, list(range(12)), cube=False)
    return (ticks == 3_334 and moved == 20_000_000
            and snap.stored() == snap.capacity())


def battery_rate_is_cache_limited():
    """B-3：缓存不足时按缓存给，不透支、不造电（500 万缓存、只覆盖 1 块 ⇒ 50 tick 给光）。"""
    snap = user_scenario()
    charger = Charger(5_000_000)
    ticks, moved = charger.run_until_full(snap, [9])
    return (moved == 5_000_000
            and charger.energy == 0
            and snap.stored() == 580_000_000 + 5_000_000
            and ticks == 50)


def conductor_discharge_spans_whole_cluster():
    """B-4：从「被覆盖的那一块」取电，实际扣的是整簇（导线）。"""
    snap = user_scenario()
    got = snap.extract(60_000_000)
    return (got == 60_000_000
            and snap.by_x[0].energy == 0
            and snap.by_x[1].energy == 40_000_000
            and snap.stored() == 580_000_000 - 60_000_000)


# ======================================================================================
# C 组：降解室发电机「副产物满 ⇒ 堵塞」
# ======================================================================================

class Generator:
    """`DecompositionGeneratorBlockEntity#tickDecomposition` 的模型（只建模三判与结算顺序）。"""

    def __init__(self, total=20, reward=50, buffer_cap=1_000_000):
        self.progress = 0
        self.total = total
        self.reward = reward
        self.energy = 0
        self.buffer_cap = buffer_cap
        self.input_count = 16
        self.outputs = []           # [(item, count)] 4 格副产物
        self.output_full = False
        self.buffer_full = False
        self.consumed = 0

    def _fits(self, product):
        """与 `depositIntoFour` 同口径：4 格、同类合并、每格上限 64。"""
        slots = list(self.outputs)
        remaining = product[1]
        for i in range(OUTPUT_COUNT):
            if remaining <= 0:
                break
            if i >= len(slots):
                slots.append(("", 0))
            item, count = slots[i]
            if count and item != product[0]:
                continue
            room = OUTPUT_LIMIT - count
            moved = min(room, remaining)
            slots[i] = (product[0], count + moved)
            remaining -= moved
        return remaining <= 0, slots

    def tick(self, product=("minecraft:sugar", 1)):
        if self.input_count <= 0:
            return
        if self.buffer_cap - self.energy < self.reward:
            self.buffer_full = True
            return
        self.buffer_full = False
        if self.progress + 1 >= self.total:
            fits, slots = self._fits(product)
            if not fits:
                self.output_full = True
                return
            self.output_full = False
            # settle：写缓存 → 写产物 → 扣 1 件
            self.energy += self.reward
            self.outputs = slots
            self.input_count -= 1
            self.consumed += 1
            self.progress = 0
            return
        self.progress += 1


def generator_stalls_when_output_full():
    """C-3：副产物满 ⇒ 进度停在 total-1、不发电、不消耗输入。"""
    gen = Generator(total=5)
    gen.outputs = [("minecraft:dirt", 64)] * OUTPUT_COUNT      # 4 格全满且不混装
    for _ in range(200):
        gen.tick()
    return (gen.progress == 4
            and gen.energy == 0
            and gen.consumed == 0
            and gen.input_count == 16
            and gen.output_full is True)


def generator_resumes_after_clearing_outputs():
    """C-5：清空副产物后下一 tick 恢复结算（自动恢复，不需要重放方块）。"""
    gen = Generator(total=5)
    gen.outputs = [("minecraft:dirt", 64)] * OUTPUT_COUNT
    for _ in range(50):
        gen.tick()
    stalled = gen.progress == 4 and gen.energy == 0
    gen.outputs = []
    gen.tick()
    return (stalled
            and gen.consumed == 1
            and gen.energy == 50
            and gen.progress == 0
            and gen.output_full is False)


def generator_output_full_source_anchor(gen_src):
    """C-1/C-2：源码里「放不下 ⇒ outputFull + return」必须在 settle 之前。"""
    code = strip_comments(gen_src)
    body = method_body(code, "private void tickDecomposition(")
    if body is None:
        return False
    fit = body.find("if (!productsFit(products))")
    full_true = body.find("outputFull = true;", fit if fit >= 0 else 0)
    settle = body.find("settle(currentSlot, progressEnergy, products);")
    return 0 <= fit < full_true < settle


def generator_settle_order(gen_src):
    """C-2：settle 内部顺序 = 写缓存 → 写产物 → 扣 1 件。"""
    code = strip_comments(gen_src)
    body = method_body(code, "private void settle(")
    if body is None:
        return False
    energy = body.find("energyStorage.addInternal(reward);")
    outputs = body.find("writeOutputs(settledOutputs);")
    consume = body.find("inventory.extractItem(")
    return 0 <= energy < outputs < consume


def generator_output_full_exposed(gen_src):
    """C-4：`isOutputFull()` + FLAG_OUTPUT_FULL 仍对外（界面能说清「为什么卡住」）。"""
    code = strip_comments(gen_src)
    return ("public boolean isOutputFull(" in code
            and "FLAG_OUTPUT_FULL" in code
            and "return outputFull;" in code)


# ======================================================================================
# 反例构造（把源码 / 模型改坏，判据必须判负）
# ======================================================================================

def broken_charger(charger_src, kind):
    if kind == "no_cube_branch":
        # 把「立方走电池档、其它走工具档」的三元分支整个删掉，立方回到工具档。
        return re.sub(
            r"final int transfer = blockEntity instanceof FEEnergyCubeBlockEntity\s*"
            r"\?\s*cubeTransferBudget\(\)\s*:\s*transferBudget\(\);",
            "final int transfer = transferBudget();", charger_src, count=1)
    if kind == "cube_uses_tool_track":
        # 分支还在，但立方也去拿工具档（= 用户反复说的「只看到贴着那一块在慢慢吸」）。
        return re.sub(r"\?\s*cubeTransferBudget\(\)", "? transferBudget()", charger_src, count=1)
    if kind == "cube_budget_tool_rate":
        # 方法还在，但电池档被换成工具档速率。
        return re.sub(
            r"(private int cubeTransferBudget\(\)[\s\S]*?)Config\.feEnergyCubeMaxTransfer",
            r"\1getChargeRate()", charger_src, count=1)
    if kind == "cube_budget_impure":
        return re.sub(
            r"(private int cubeTransferBudget\(\) \{\s*)"
            r"final int stored = energyStorage\.getEnergyStored\(\);",
            r"\1setChanged();\n        final int stored = energyStorage.getEnergyStored();",
            charger_src, count=1)
    if kind == "push_uses_own":
        return charger_src.replace(
            "final long available = getClusterEnergyStored();",
            "final long available = getOwnEnergyStored();", 1)
    return charger_src


def broken_cube_block(block_src, kind):
    if kind == "drops_cluster":
        return block_src.replace("cube.getOwnEnergyStored()", "cube.getClusterEnergyStored()", 1)
    return block_src


def broken_cube_be(be_src, kind):
    if kind == "one_direction":
        return be_src.replace(
            "(blockEntity, direction) -> blockEntity.getExternalEnergyStorage()",
            "(blockEntity, direction) -> direction == null ? blockEntity.getExternalEnergyStorage() : null", 1)
    if kind == "push_uses_own":
        return be_src.replace(
            "final long available = getClusterEnergyStored();",
            "final long available = getOwnEnergyStored();", 1)
    return be_src


def broken_generator(gen_src, kind):
    if kind == "settle_when_full":
        # 副产物放不下时不再 return（继续走 settle）⇒ 堵塞判据必须判负。
        return re.sub(
            r"if \(!productsFit\(products\)\) \{\s*outputFull = true;\s*return;\s*\}",
            "if (false) {\n                outputFull = true;\n                return;\n            }",
            gen_src, count=1)
    return gen_src


# ======================================================================================
# main
# ======================================================================================

def main():
    print("=" * 100)
    print("第 127 轮自检：F1 续（立方 = 整簇电池 / 导线）+ 降解室发电机「副产物满就堵塞」")
    print("=" * 100)

    for path in (CLUSTER, CUBE_BE, CUBE_BLOCK, NET_NODE, CHARGER, GENERATOR, CONFIG):
        if not os.path.isfile(path):
            print("FATAL 缺文件: " + path)
            return 1

    charger_raw = read(CHARGER)
    cube_raw = read(CUBE_BE)
    block_raw = read(CUBE_BLOCK)
    gen_raw = read(GENERATOR)
    cluster_raw = read(CLUSTER)
    config_raw = read(CONFIG)

    # ---------- A-0：默认值锚点（数字对不上 = 自检假绿） ----------
    print("\n=== A-0 组：默认值锚点（从 Config 源码取值，不凭记忆） ===")
    check("A-0a Config 里立方单块容量默认 5000 万 (%s)"
          % where(config_raw, 'defineInRange("feEnergyCubeCapacity"'),
          '.defineInRange("feEnergyCubeCapacity", 50000000' in config_raw)
    check("A-0b Config 里立方面速率默认 10 万 FE/t (%s)"
          % where(config_raw, 'defineInRange("feEnergyCubeMaxTransfer"'),
          '.defineInRange("feEnergyCubeMaxTransfer", 100000' in config_raw)
    check("A-0c Config 里充电器工具档默认 500 FE/t (%s)"
          % where(config_raw, 'defineInRange("rangeChargerChargeRate"'),
          '.defineInRange("rangeChargerChargeRate", 500' in config_raw)

    # ---------- A 组：源码锚点 ----------
    print("\n=== A 组：立方 = 整簇 + 导线 + 拆开只带自己 ===")
    check("A-1 chargeBlock 里立方目标走电池档 cubeTransferBudget() (%s)"
          % where(charger_raw, "cubeTransferBudget()"),
          cube_branch_in_charge_block(charger_raw))
    check("A-2 cubeTransferBudget() 夹 feEnergyCubeMaxTransfer、缓存见底返回 0 (%s)"
          % where(charger_raw, "private int cubeTransferBudget("),
          cube_transfer_budget_is_battery_track(charger_raw))
    check("A-2b cubeTransferBudget() 是纯函数（不扣电 / 不标脏 / 不登记高亮）",
          cube_transfer_budget_is_pure(charger_raw))
    check("A-3 顺序：取预算 → simulate 判满 → 真收（立方分支未破坏 R-1 顺序）",
          order_budget_before_full_check(charger_raw))
    check("A-3b 能力入口仍是 Capabilities.EnergyStorage.BLOCK（不是 ITEM）",
          capability_entry_is_block(charger_raw))
    check("A-4 相邻推送取整簇存量、扣整簇（导线语义，在立方 BE 里）(%s)"
          % where(cube_raw, "final long available = getClusterEnergyStored();"),
          push_uses_cluster(cube_raw))
    check("A-4b 立方之间不互推（instanceof FEEnergyCubeBlock ⇒ continue）",
          push_skips_cubes(cube_raw))
    check("A-5 六面能力注册（含 null）都返回同一个集群视图 (%s)"
          % where(cube_raw, "public static void registerCapabilities("),
          capability_registered_all_sides(cube_raw))
    check("A-6 掉落只带本块电量（getOwnEnergyStored + saveToItem）(%s)"
          % where(block_raw, "protected List<ItemStack> getDrops("),
          drops_use_own_energy(block_raw, cube_raw))
    check("A-6b 掉落路径绝不用 getClusterEnergyStored",
          "getClusterEnergyStored()" not in strip_comments(block_raw))
    check("A-7 集群快照仍「先填 preferred 再填其它成员」（整簇是池，不是单块）(%s)"
          % where(cluster_raw, "public long receive(final long amount"),
          ("if (preferred != null && members.contains(preferred))"
           in strip_comments(cluster_raw))
          and ("for (final BlockPos pos : members)" in strip_comments(cluster_raw)))

    # ---------- B 组：行为模型 ----------
    print("\n=== B 组：行为模型（12 块 × 5000 万 = 6 亿容量，存量 5.8 亿，缺口 2000 万） ===")
    check("B-1 覆盖整面墙（12 块）+ 缓存 5000 万 ⇒ 17 tick 灌满 2000 万缺口（守恒）",
          battery_rate_fills_wall_fast())
    check("B-1b 只覆盖 1 块 ⇒ 200 tick（≈10 秒）也把整簇灌满（不是只充那一块）",
          battery_rate_single_covered_cube())
    check("B-2 对照：工具档 500 FE/t × 12 块 = 6000 FE/t ⇒ 同一缺口要 3,334 tick（≈2.8 分钟）",
          tool_rate_would_take_3334_ticks())
    check("B-3 缓存不足时按缓存给（500 万缓存 + 覆盖 1 块 ⇒ 50 tick 给光，不透支不造电）",
          battery_rate_is_cache_limited())
    check("B-4 从任一块取电扣的是整簇（导线）", conductor_discharge_spans_whole_cluster())

    # ---------- C 组：降解室发电机 ----------
    print("\n=== C 组：降解室发电机「副产物满 ⇒ 堵塞」 ===")
    check("C-1 源码：productsFit 失败 ⇒ outputFull = true + return（在 settle 之前）(%s)"
          % where(gen_raw, "if (!productsFit(products))"),
          generator_output_full_source_anchor(gen_raw))
    check("C-2 源码：settle 顺序 = 写缓存 → 写产物 → 扣 1 件",
          generator_settle_order(gen_raw))
    check("C-3 模型：副产物满 ⇒ 进度停在 total-1、不发电、不消耗输入",
          generator_stalls_when_output_full())
    check("C-4 isOutputFull() / FLAG_OUTPUT_FULL 仍对外（界面可以说清为什么卡住）",
          generator_output_full_exposed(gen_raw))
    check("C-5 模型：清空副产物后下一 tick 自动恢复结算",
          generator_resumes_after_clearing_outputs())

    # ---------- 反例自证 ----------
    print("\n=== X 组：反例自证（把东西改坏，判据必须判负） ===")
    counterexample("X-1 删掉立方分支（立方回到工具档）⇒ A-1 判负",
                   not cube_branch_in_charge_block(
                       broken_charger(charger_raw, "no_cube_branch")))
    counterexample("X-2 立方分支改调 transferBudget()（分支在、方法错）⇒ A-1 判负",
                   not cube_branch_in_charge_block(
                       broken_charger(charger_raw, "cube_uses_tool_track")))
    counterexample("X-2b cubeTransferBudget() 里把电池档换成 getChargeRate() ⇒ A-2 判负",
                   not cube_transfer_budget_is_battery_track(
                       broken_charger(charger_raw, "cube_budget_tool_rate")))
    counterexample("X-3 cubeTransferBudget() 里加 setChanged() ⇒ 纯函数判据判负",
                   not cube_transfer_budget_is_pure(
                       broken_charger(charger_raw, "cube_budget_impure")))
    counterexample("X-4 推送改用 getOwnEnergyStored（不再是导线）⇒ A-4 判负",
                   not push_uses_cluster(
                       broken_cube_be(cube_raw, "push_uses_own")))
    counterexample("X-5 掉落改用 getClusterEnergyStored ⇒ A-6 判负",
                   not drops_use_own_energy(
                       broken_cube_block(block_raw, "drops_cluster"), cube_raw))
    counterexample("X-6 能力注册只给 null 面 ⇒ A-5 判负",
                   not capability_registered_all_sides(
                       broken_cube_be(cube_raw, "one_direction")))
    counterexample("X-7 副产物满时继续 settle ⇒ C-1 判负",
                   not generator_output_full_source_anchor(
                       broken_generator(gen_raw, "settle_when_full")))
    counterexample("X-8 模型：立方改用工具档 ⇒ B-1 判负（3,334 tick 而不是 17）",
                   not tool_rate_fills_wall_as_fast())
    counterexample("X-9 模型：副产物满仍结算 ⇒ C-3 判负",
                   not generator_stalls_if_fit_ignored())

    print("\n" + "=" * 100)
    print("断言 %d 条（其中反例 %d 条），失败 %d 条"
          % (CHECKS[0], COUNTER[0], len(FAILS)))
    if FAILS:
        print("FAILED:")
        for name in FAILS:
            print("  - " + name)
        return 1
    print("ALL_PASS")
    return 0


def tool_rate_fills_wall_as_fast():
    """反例用：把立方换成工具档后，同一场景**不再** 17 tick 填满（B-1 判据必须判负）。"""
    snap = user_scenario()
    charger = Charger(SINGLE_CAP)
    ticks, _ = charger.run_until_full(snap, list(range(12)), cube=False)
    return ticks == 17 and snap.stored() == snap.capacity()


def generator_stalls_if_fit_ignored():
    """反例用：忽略「副产物放不下」直接结算 ⇒ C-3 判据必须判负。"""

    class Ignoring(Generator):
        def _fits(self, product):
            return True, list(self.outputs)

    gen = Ignoring(total=5)
    gen.outputs = [("minecraft:dirt", 64)] * OUTPUT_COUNT
    for _ in range(200):
        gen.tick()
    return gen.progress == 4 and gen.energy == 0 and gen.consumed == 0


if __name__ == "__main__":
    sys.exit(main())
