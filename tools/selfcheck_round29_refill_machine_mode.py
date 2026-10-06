# -*- coding: utf-8 -*-
"""第 29 轮自检：**「补合成请求量」新增第三档「按机器台数发」**，且默认档逐字未变。

用法：python tools/selfcheck_round29_refill_machine_mode.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要有它（用户原话）：

    金板不够了，处置方法只有两种：①「一个一个地发送请求」⇒ 产能不够；
    ②「一次性下单」⇒ 可能会过料，而且「我不确定它有没有正常发挥」。
    你帮我确认一下 REFILL 是不是这个……然后再加一种模式：① 一个一个的，② 一下子发；
    默认两种；**再加上一种就是它当前有多少台机子就发多少个**。

R1（现状确认，源码锚点）：本仓的「补料 / 自动合成请求量」只有一份实现 ——
`SequenceExecutionChamberBlockEntity#requestMissingViaAutocraft`，口径由 `RsccRefillPolicy` 两档决定：
  * 默认（off）：确定性配方一次一份（= 用户说的①）；概率性配方按「此刻允许在制的件数」分批递进；
  * `refill on`（GAP）：确定性配方按缺口一次要足（= 用户说的②）；概率性配方**仍**分批（要足会烧料）。

R2（本轮的改动）：把它扩成**三档**，第三档 MACHINES = 按机器台数发。本脚本钉住三件事：
  ① 三档都在源码里成立（档位 / 指令 / 执行舱请求量 / 语言键 / 诊断）；
  ② **默认档（off）的请求量公式与改动前逐字一致**（对整张取值网格做等价性反例）；
  ③ **MACHINES 档的请求量恒 ≤ 缺口、≤ 机器台数、≤ 在制名额**，因此它不可能让开件数超过名额上限
     （名额闸门 `startCapacityForRecipe` / `blockedByMachineQueue` 一个字都没改）。

「机器台数」的口径（本脚本同时钉住）：`supplyStations(level).size()` ——
把相连输出总线朝向的供料目标按**物理工位**归并后的台数（「置物台 + 对着它的机械手」算一台）。
不用裸 `busSupplyTargets()` 的条数，否则同一台机器会被数成两台、多发一份。
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
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

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
    check(name, ok, "" if ok else ("missing: %s" % needle.replace("\n", " ")[:120]))


def body(text, start, end):
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


def section(title):
    print()
    print("=" * 84)
    print(title)
    print("=" * 84)


chamber = read(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
commands = read(SRC, "command", "CompatCommands.java")
refill = read(SRC, "support", "RsccRefillPolicy.java")
diag = read(SRC, "support", "RsccDiag.java")
zh = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
en = json.load(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))

# ============================================================ ① 三档都成立
section("①) 源码锚点：新增第三档「按机器台数发」（档位 / 指令 / 执行舱 / 诊断）")

has(refill, "public final class RsccRefillPolicy extends SavedData {",
    "锚点: 档位仍是 SavedData（按存档持久化，读档仍生效）")
has(refill, "public enum Mode {", "锚点: 档位是枚举（第三档与既有两档同一份真源）")
has(refill, 'OFF("off"),', "锚点: 档位 id = off（默认档的 NBT / 日志标识与变更前一致）")
has(refill, 'GAP("gap"),', "锚点: 档位 id = gap（= 旧的「按缺口补发」）")
has(refill, 'MACHINES("machines");', "锚点: 档位 id = machines（新增第三档）")
has(refill, "private static final Mode DEFAULT_MODE = Mode.OFF;",
    "锚点: 默认档仍是 off（新增档是纯 opt-in）")
has(refill, "private static final boolean DEFAULT_ENABLED = false;",
    "锚点: 旧布尔默认值仍是 false（既有自检 round14 的锚点一字未改）")
has(refill, "public static Mode mode(final Level level) {",
    "锚点: 档位的只读读取（服务端权威，判不出来回落默认档）")
has(refill, "public static boolean gapRefill(final Level level) {",
    "锚点: 旧调用点保留（语义不变：true ⟺ GAP，于是新增档不会被误当成「按缺口要足」）")
has(refill, "return mode(level) == Mode.GAP;",
    "锚点: gapRefill 的口径 = 恰好是 GAP 档")
has(refill, "public static boolean machineRefill(final Level level) {",
    "锚点: 第三档的只读读取只有一份实现")
has(refill, "return mode(level) == Mode.MACHINES;",
    "锚点: machineRefill 的口径 = 恰好是 MACHINES 档")
has(refill, "public void setMode(final Mode newMode) {",
    "锚点: 档位写入端（指令用；变化时打带档位 id 的锚点行）")
has(refill, "public void setGapRefill(final boolean enabled) {",
    "锚点: 旧写入端保留（on/off 指令与旧语言键的语义不变）")
has(refill, "tag.putString(TAG_MODE, mode.id());", "锚点: 新键随存档落盘（读档后仍生效）")
has(refill, "tag.putBoolean(TAG_ENABLED, gapRefill);",
    "锚点: 旧布尔键仍然写（升级后再降级回旧版本时语义仍正确）")

has(commands, 'Commands.literal("refill")', "锚点: 指令树仍注册 refill 字面量（既有自检锚点）")
has(commands, "Commands.literal(RsccRefillPolicy.Mode.MACHINES.id())",
    "锚点: 第三档由 /rs_create_compat refill machines 显式选择（字面量取自档位自己的 id）")
has(commands, "RsccRefillPolicy.get(server).setMode(mode);", "锚点: 第三档写入持久化档位")
has(commands, "RsccRefillPolicy.get(server).setGapRefill(enable);",
    "锚点: 既有两档的写入端一字未改（既有自检锚点）")
has(commands, '"message.rs_create_compat.refill.machines"', "锚点: 第三档的语言键已引用")
has(commands, '"message.rs_create_compat.refill.usage.machines"',
    "锚点: 第三档的用法行已引用（与既有 usage 行并列显示）")

has(chamber, "final boolean machineRefill = RsccRefillPolicy.machineRefill(getLevel());",
    "锚点: 执行舱读取第三档（服务端权威）")
has(chamber, "final long machineRefillBatch = machineRefill ? machineSupplyBatch() : 0L;",
    "锚点: 未启用第三档时该分量为 0 ⇒ 旧口径完全不受影响")
has(chamber, "private long machineSupplyBatch() {",
    "锚点: 「按机器台数」的请求量只有一份实现")
has(chamber, "final int machines = supplyStations(level).size();",
    "锚点: 台数口径 = supplyStations（供料目标按物理工位归并）")
has(chamber, "return Math.max(1L, Math.min((long) machines, Math.max(1L, allowedConcurrentUnits())));",
    "锚点: 台数再夹一次在制名额（订单剩余件数；读不到时取在制下界，至少 1）")

machine_body = body(chamber, "private long machineSupplyBatch() {", "\n    /**\n     * <b>只读</b>：本仓产线里是否存在")
check("台数口径必须是「按工位归并」（不得用裸 busSupplyTargets 的条数："
      "「置物台 + 对着它的机械手」会被数成两台 ⇒ 多发一份）",
      "supplyStations(level)" in machine_body and "busSupplyTargets()" not in machine_body)
check("判不出来（没有供料目标 / 未进入世界 / 客户端）⇒ 1（与旧口径里确定性配方的「一次一份」逐字一致，绝不放量）",
      "return 1L;" in machine_body and "if (machines <= 0) {" in machine_body)

has(diag, 'LOGGER.info("{} refill={} (session default, source=save)",',
    "锚点: 会话启动锚点行仍在（既有自检锚点）")
has(diag, "RsccRefillPolicy.get(server).getMode().id()",
    "锚点: 启动锚点行打的是档位 id（off / gap / machines），不是旧布尔的 on/off")
has(diag, 'refill.put("mode", server.overworld() == null ? "off"',
    "锚点: 诊断快照新增 refill.mode（「我不确定它有没有正常发挥」可离线核对）")
has(chamber, 'RsccAssemblyDebug.machine("chamber", worldPosition) + " refill_batch mode="',
    "锚点: 执行舱按状态翻转打一行 refill_batch（档位 / 台数 / 本轮请求量三件事并排）")

# ============================================================ ② 请求量：默认档逐字未变
section("②) 请求量模型：默认档（off）与变更前逐字一致")

BUS_AUTOCRAFT_BATCH = 64


def allowed_units(order_remaining, readable, inflight):
    """复刻 allowedConcurrentUnits：读得到订单剩余就用它，读不到取下界（至少 1）。"""
    return order_remaining if (readable and order_remaining > 0) else max(1, inflight)


def legacy_off(deficit, chance, allowed):
    """**变更前**的请求量（旧源码逐字复刻）：
    requestBatch = 概率性 ? max(1, min(64, allowed)) : 1；请求量 = min(deficit, requestBatch)。"""
    if deficit <= 0:
        return 0
    batch = max(1, min(BUS_AUTOCRAFT_BATCH, allowed)) if chance else 1
    return min(deficit, batch)


def request_amount_14(deficit, chance, refill_on):
    """第 14 轮自检（tools/selfcheck_round14_feedback.py:90）里那版模型（当时还没夹 allowed）。"""
    if deficit <= 0:
        return 0
    if refill_on and not chance:
        return deficit
    batch = 64 if chance else 1
    return min(deficit, batch)


def machine_supply_batch(machines, allowed):
    """复刻 machineSupplyBatch：台数判不出来 ⇒ 1；否则 min(台数, max(1, 名额))，下限 1。"""
    if machines <= 0:
        return 1
    return max(1, min(machines, max(1, allowed)))


def request_amount(deficit, chance, mode, allowed, machines=0, gap_on=None):
    """复刻执行舱的请求量（本轮三条分支）。

    * off：min(deficit, 旧 requestBatch)；
    * gap（且确定性）：deficit（源码里是「再追加一次 ensureTask(deficit)」，与第一条取较大者等价）；
    * machines：min(deficit, machineSupplyBatch)。
    """
    if deficit <= 0:
        return 0
    if mode == "machines":
        return min(deficit, machine_supply_batch(machines, allowed))
    if mode == "gap" and not chance:
        return deficit
    return min(deficit, max(1, min(BUS_AUTOCRAFT_BATCH, allowed)) if chance else 1)


print("  等价性反例（整张取值网格：默认档 vs 变更前）")
print("  %-10s %-8s %-8s %-8s %-10s %-10s" % ("缺口", "概率性", "名额", "旧口径", "新默认档", "一致"))
GRID_MISMATCH = []
for gap in (0, 1, 2, 6, 63, 64, 65, 1000):
    for chance in (False, True):
        for allowed in (1, 2, 3, 64, 1000):
            old = legacy_off(gap, chance, allowed)
            new = request_amount(gap, chance, "off", allowed)
            if old != new:
                GRID_MISMATCH.append((gap, chance, allowed, old, new))
for row in [(0, False, 64), (1, False, 64), (6, False, 64), (64, True, 64), (1000, True, 1)]:
    gap, chance, allowed = row
    print("  %-10s %-8s %-8s %-8s %-10s %-10s" % (
        gap, "是" if chance else "否", allowed,
        legacy_off(gap, chance, allowed), request_amount(gap, chance, "off", allowed),
        "OK" if legacy_off(gap, chance, allowed) == request_amount(gap, chance, "off", allowed) else "DIFF"))
check("②a 默认档（off）的请求量在整张网格（缺口×概率性×名额）上与变更前**逐字一致**：%d 组"
      % (len([1 for g in (0, 1, 2, 6, 63, 64, 65, 1000) for c in (False, True) for a in (1, 2, 3, 64, 1000)]))
      + "（0 处不一致）", not GRID_MISMATCH, "mismatch=%s" % GRID_MISMATCH[:3])

check("②b 与第 14 轮自检公布的那版模型在「名额足够大」时完全吻合"
      "（确定性 6 → 1；概率性 64 → 64；缺口 0 → 0）",
      request_amount(6, False, "off", 64) == request_amount_14(6, False, False) == 1
      and request_amount(64, True, "off", 64) == request_amount_14(64, True, False) == 64
      and request_amount(0, False, "off", 64) == request_amount_14(0, False, False) == 0)
check("②c 默认档下「确定性配方一次只发一份」这条用户认识的旧行为一字未改（缺口 6 也只发 1）",
      request_amount(6, False, "off", 64) == 1)
check("②d gap 档（refill on）语义不变：确定性要足缺口；概率性**仍**分批（绝不按缺口要足 ⇒ 不烧料）",
      request_amount(6, False, "gap", 64) == 6
      and request_amount(1, False, "gap", 64) == 1
      and request_amount(64, True, "gap", 64) == request_amount(64, True, "off", 64) == 64)

# ============================================================ ③ 第三档：按机器台数
section("③) 第三档（machines）的请求量 = 机器台数（再夹缺口与在制名额）")

print("  反例表（第三档；缺口 = 目标 −(网络 + 本仓 + 机器侧 + 在途)）")
print("  %-44s %-8s %-8s %-8s %-10s" % ("情形", "台数", "名额", "缺口", "请求量"))
ROWS = [
    ("金板缺 6 / 本仓喂 3 台机器（订单还剩 64）", 3, 64, 6),
    ("金板缺 6 / 本仓喂 3 台机器（订单只剩 1）", 3, 1, 6),
    ("金板缺 1 / 本仓喂 8 台机器（订单还剩 64）", 8, 64, 1),
    ("金板缺 64 / 本仓喂 5 台机器（订单还剩 64）", 5, 64, 64),
    ("求不到机器台数（判不出来）", 0, 64, 6),
    ("没有缺口（已达标）", 3, 64, 0),
]
for label, machines, allowed, gap in ROWS:
    print("  %-44s %-8s %-8s %-8s %-10s" % (
        label, machines if machines else "判不出", allowed, gap,
        request_amount(gap, False, "machines", allowed, machines)))
check("③a 有几台就发几份：缺口 6 / 3 台机器 ⇒ 一次请求 3（三台可以同时开工，不必一个一个发）",
      request_amount(6, False, "machines", 64, 3) == 3)
check("③b 名额夹紧（用户硬要求：新模式不得让开件数超过名额）：下单 1 件 + 3 台机器 ⇒ 请求 1（不是 3），"
      "因为「本仓此刻还允许在制的件数」只有 1",
      request_amount(6, False, "machines", 1, 3) == 1)
check("③c 绝不超过缺口：缺口 1 / 8 台机器 ⇒ 请求 1（要多了就是用户说的「过料」）",
      request_amount(1, False, "machines", 64, 8) == 1)
check("③d 台数判不出来 ⇒ 请求 1（与旧口径里确定性配方的「一次一份」一致，绝不放量）",
      request_amount(6, False, "machines", 64, 0) == 1)
check("③e 缺口 ≤ 0 ⇒ 一条请求都不发（与两档既有口径一致，不会无限要）",
      request_amount(0, False, "machines", 64, 3) == 0)
check("③f 与档位开关的一致性：machines 档下**不会**再追加「按缺口要足」那一条"
      "（源码判据是 gapRefill ⟺ 恰好 GAP 档；模型里 machines 档取 min(缺口, 台数)）",
      "if (gapRefill && deterministicPipeline) {" in chamber
      and chamber.count("if (gapRefill && deterministicPipeline) {") == 2
      and request_amount(64, False, "machines", 64, 3) == 3)

BOUND = []
for gap in (0, 1, 3, 6, 64, 1000):
    for machines in (0, 1, 2, 3, 8, 64):
        for allowed in (1, 2, 3, 64, 1000):
            for chance in (False, True):
                amount = request_amount(gap, chance, "machines", allowed, machines)
                if amount > max(0, gap):
                    BOUND.append(("gap", gap, machines, allowed, chance, amount))
                if amount > max(1, machines):
                    BOUND.append(("machines", gap, machines, allowed, chance, amount))
                if amount > max(1, min(max(1, machines), max(1, allowed))):
                    BOUND.append(("cap", gap, machines, allowed, chance, amount))
check("③g 不变量（整张网格）：machines 档的请求量恒 ≤ 缺口、≤ max(1, 机器台数)、"
      "≤ max(1, min(台数, 名额)) —— 因此它既不会过料，也不可能顶破名额", not BOUND,
      "violations=%s" % BOUND[:3])

# ============================================================ ④ 名额闸门一字未改
section("④) 名额 / 共享机器排队闸门：一个字都没改（第三档不可能把它们弄坏）")

has(chamber, "private long startCapacityForRecipe(final String recipeId) {",
    "锚点: 开工名额的唯一实现仍在（必得严格 R − 在制；非必得 ceil(R/p) − 在制）")
has(chamber, "allowed = (long) Math.ceil(remaining / (double) p);",
    "锚点: 非必得的动态放大一字未改")
has(chamber, "capacity = allowed - inFlight;", "锚点: 名额 = 允许在制 − 跨舱在制件（口径未换）")
has(chamber, "private boolean blockedByMachineQueue(final BusCategoryInfo info) {",
    "锚点: 共享机器排队的唯一实现仍在")
check("锚点: 备料侧与推料侧仍然共用同一道排队闸门（调用点各一处）",
      chamber.count("if (blockedByMachineQueue(info)) {") == 2)
has(chamber, "private void fillInternalForBus(final StorageNetworkComponent storage, final Network network,",
    "锚点: 备料入口仍在（第三档只改「向 RS 请求多少」，不改备料 / 推料）")
check("锚点: 请求量只被用在两处 ensureTask 上（物品侧 / 流体侧），没有第三处偷偷放量",
      chamber.count("autocrafting.ensureTask(resource, Math.min(deficit, requestBatch),") == 2
      and chamber.count("autocrafting.ensureTask(resource, deficit,") == 2)


def start_capacity(remaining, guaranteed, p, inflight):
    """复刻 startCapacityForRecipe（第 26/27 轮修正后的源码，逐字对应）。"""
    if remaining == -2:
        return 0
    if remaining < 0:
        return 1 if inflight <= 0 else 0
    allowed = remaining
    if not guaranteed and isinstance(p, float) and 0.0 < p < 1.0:
        allowed = int(math.ceil(remaining / p))
    return max(0, allowed - inflight)


print("  反例表（第三档 vs 「按缺口要足」：同一份现场，谁会让开件数超过名额）")
print("  %-40s %-14s %-14s %-14s" % ("情形", "machines 请求", "gap 请求", "开工名额上限"))
CASES = [
    ("订单 1（必得）/ 3 台机器 / 缺口 64", 1, 64, 64, 1, True, 1.0, 0),
    ("订单 1（非必得 p=.8）/ 3 台机器 / 缺口 64", 1, 64, 64, 1, False, 0.8, 0),
    ("订单 64（必得）/ 3 台机器 / 缺口 6", 3, 6, 64, 64, True, 1.0, 0),
    ("订单 64（非必得 p=.8）/ 5 台机器 / 缺口 64", 5, 64, 64, 64, False, 0.8, 0),
]
for label, machines, gap, allowed, remaining, guaranteed, p, inflight in CASES:
    cap = start_capacity(remaining, guaranteed, p, inflight)
    mm = request_amount(gap, not guaranteed, "machines", allowed, machines)
    gp = request_amount(gap, not guaranteed, "gap", allowed, machines)
    print("  %-40s %-14s %-14s %-14s" % (label, mm, gp, cap))
check("④a 下单 1 件（必得配方）+ 3 台机器：第三档只请求 1 份（各开一件的上限被订单钉死在 1），"
      "而同一份现场下「按缺口要足」会请求 64 份 —— 这正是用户说的「过料」",
      request_amount(64, False, "machines", 1, 3) == 1
      and request_amount(64, False, "gap", 1, 3) == 64)
check("④b 第三档请求量 ≤ 在制名额上限（必得配方：≤ R − 在制；非必得：≤ ceil(R/p) − 在制）："
      "「订单 64 / 5 台 / 缺口 64」⇒ 请求 5，名额上限 80 ⇒ 5 ≤ 80",
      request_amount(64, False, "machines", 64, 5) == 5
      and start_capacity(64, False, 0.8, 0) == 80)
check("④c 名额读不出来（remaining=-1）时名额仍走原有保守下限（无在制 1 件 / 有在制 0 件）——第三档没有动它",
      start_capacity(-1, True, 1.0, 0) == 1 and start_capacity(-1, True, 1.0, 1) == 0)

# ============================================================ ⑤ 默认值未变（旧存档兼容模型）
section("⑤) 默认值未变的证据：旧存档读进来的档位与它保存那一刻一致（绝不会自己跳成 machines）")


def load_mode(gap_refill_tag, mode_tag):
    """复刻 RsccRefillPolicy#load 的档位还原（None = 存档里没有该键）。"""
    gap = bool(gap_refill_tag) if gap_refill_tag is not None else False
    if mode_tag in ("off", "gap", "machines"):
        return mode_tag
    return "gap" if gap else "off"


print("  %-46s %-14s %-14s" % ("旧存档内容", "还原档位", "行为"))
LEGACY = [
    ("没有任何键（第三档之前的默认存档）", None, None),
    ("gap_refill=false（旧版默认档）", False, None),
    ("gap_refill=true（旧版敲过 refill on）", True, None),
    ("refill_mode=machines（新版第三档）", False, "machines"),
    ("refill_mode=不认识的字符串（手改坏）", True, "???"),
]
for label, gap_tag, mode_tag in LEGACY:
    restored = load_mode(gap_tag, mode_tag)
    print("  %-46s %-14s %-14s" % (label, restored,
                                   {"off": "一次一份 / 分批递进", "gap": "按缺口要足"}.get(restored, "回退布尔键")))
check("⑤a 旧存档（没有新键）还原后绝不会变成 machines："
      "无键 ⇒ off；gap_refill=false ⇒ off；gap_refill=true ⇒ gap",
      load_mode(None, None) == "off" and load_mode(False, None) == "off" and load_mode(True, None) == "gap")
check("⑤b 认不出的新键值 ⇒ 回退旧布尔键（绝不因为 NBT 被改坏而悄悄换档）",
      load_mode(True, "???") == "gap" and load_mode(False, "???") == "off")
check("⑤c 源码里这条回退就是唯一实现（读档路径没有第二套判据）",
      "data.mode = parsed == null ? (data.gapRefill ? Mode.GAP : Mode.OFF) : parsed;" in refill
      and "public static Mode byId(final String id) {" in refill)
check("⑤d 默认档 = off（新增档是纯 opt-in；既有存档的网络存量曲线不会被这次改动影响）",
      "private static final Mode DEFAULT_MODE = Mode.OFF;" in refill
      and "private static final boolean DEFAULT_ENABLED = false;" in refill)

# ============================================================ ⑥ 语言键
section("⑥) 语言键：中英成对、字符数受限、第三档文案不含被禁说法")

refill_keys = sorted(k for k in zh if k.startswith("message.rs_create_compat.refill."))
check("⑥a 语言键集合中英完全一致（全局）", set(zh) == set(en))
print("  refill 段语言键：%s" % ", ".join(refill_keys))
check("⑥b 三档标签 + 两行用法都在（on / off / machines / usage / usage.machines）",
      all(("message.rs_create_compat.refill." + suffix) in zh
          and ("message.rs_create_compat.refill." + suffix) in en
          for suffix in ("on", "off", "machines", "usage", "usage.machines")))
check("⑥c 中文长度 ≤ 40（既有自检 round14 的同一条约束）",
      all(len(zh[k]) <= 40 for k in refill_keys),
      "%s" % [(k, len(zh[k])) for k in refill_keys if len(zh[k]) > 40])
NEW = ("message.rs_create_compat.refill.machines", "message.rs_create_compat.refill.usage.machines")
BANNED = ("或", "等 ", "图标轮换")
check("⑥d 新增文案不含被禁说法（「或」/「等 N 种」/「图标轮换」）且不枚举种类数",
      all(not any(word in zh[k] for word in BANNED) for k in NEW)
      and all("种" not in zh[k] for k in NEW))
check("⑥e 新增文案里给出了第三档的确切字面量 machines（玩家不必猜）",
      "machines" in zh["message.rs_create_compat.refill.usage.machines"]
      and "machines" in en["message.rs_create_compat.refill.usage.machines"])
check("⑥f 既有 usage 行未被改动（仍是 <on|off>，第三档另起一行给出）",
      zh["message.rs_create_compat.refill.usage"] == "用法：/rs_create_compat refill <on|off>"
      and en["message.rs_create_compat.refill.usage"] == "Usage: /rs_create_compat refill <on|off>")

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
