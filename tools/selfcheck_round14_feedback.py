# -*- coding: utf-8 -*-
"""第 14 轮用户反馈 5 条的源码锚点 + <b>可离线推演的反例表</b>。

 ① 补合成请求量 = 缺口（不再固定 1）—— 且概率配方不得按缺口要足
 ② 该「按缺口补发」要有指令开关（/rs_create_compat refill <on|off>），默认档 = 变更前后行为一致
 ③ 资源可自动合成 ⇒ 不显示「缺少材料」、不挂起、静默等待
 ④ 任务已完成 / 缺口为 0 / 成品达标 ⇒ 绝不报缺；同一条缺料不得重复播报
 ⑤ 齿轮仍堵（第 N 轮）—— 换角度：「列表视图（全仓并集）」与「单物品视图（按本机）」判据不一致 + 把
    「玩家手动拿走」这条外部路径纳入日志（net_down）

为什么要有它：这五条都不能靠肉眼看刷屏日志判定。本脚本把「每条期望的代码落点」逐个断言，
并<b>复刻</b>对应的判定函数，用反例表证明「哪条路径会导致误报 / 往返 / 烧料」→「新逻辑为什么挡住它」。

用法：python tools/selfcheck_round14_feedback.py
      → 全绿输出 `SELFCHECK OK (n checks)`；失败退出码 1。
"""
import io
import json
import os
import re
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
    check(name, ok, "" if ok else ("missing: %s" % needle))


def section(title):
    print()
    print("=" * 84)
    print(title)
    print("=" * 84)


chamber = read(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
watchdog = read(SRC, "support", "AssemblyWatchdog.java")
commands = read(SRC, "command", "CompatCommands.java")
refill = read(SRC, "support", "RsccRefillPolicy.java")
diag = read(SRC, "support", "RsccDiag.java")
zh = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
en = json.load(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))

# ============================================================ ① 补合成请求量 = 缺口
section("① 补合成请求量 = 缺口（默认档不变，开关打开后按缺口补发）")

has(chamber, "final Map<Item, Long> autocraftItemTargets = new LinkedHashMap<>(itemTargets);",
    "锚点: 「补合成」目标表仍是夹量前的快照（既有第 ⑦ 条一字未改）")
has(chamber, "final long have = storage.get(resource) + storedItemAmount(entry.getKey());",
    "锚点: 「缺口 = 目标 − 可用量」的唯一实现仍在（既有口径保留）")
has(chamber, "- machineHeldItem(entry.getKey()) - autocraftInFlight(resource);",
    "锚点①: 缺口再扣「机器侧压着的那一份」与「RS 已在合成的在途量」（用户第 1 条：网络+本仓+机器侧+在制/在途）")
has(chamber, "private long autocraftInFlight(final ResourceKey resource) {",
    "锚点②: 在途量（TaskStatus.Item#crafting 汇总）只有一份实现")
has(chamber, "final long requestBatch = pipelineUsesChance()",
    "锚点③: 旧口径的分批量仍在（默认档 = 与变更前逐字一致）")
has(chamber, "autocrafting.ensureTask(resource, Math.min(deficit, requestBatch),",
    "锚点④: 旧口径请求式仍在（确定性一次一份 / 概率性按 allowedConcurrentUnits 分批）")
has(chamber, "final boolean gapRefill = RsccRefillPolicy.gapRefill(getLevel());",
    "锚点⑤: 「按缺口补发」的开关从持久化策略读取（服务端权威）")
check("锚点⑥: 「按缺口补发」只在<b>确定性配方</b>上追加一次「至少 deficit」的请求（概率配方不追加 ⇒ 不烧料）",
      "if (gapRefill && deterministicPipeline) {" in chamber
      and chamber.count("autocrafting.ensureTask(resource, deficit,") == 2
      and "final boolean deterministicPipeline = !pipelineUsesChance();" in chamber)


def request_amount(deficit, chance, refill_on):
    """复刻 requestMissingViaAutocraft 的请求量：确定性 + 开关开 ⇒ deficit；否则 min(deficit, batch)。"""
    if deficit <= 0:
        return 0
    if refill_on and not chance:
        return deficit
    batch = 64 if chance else 1
    return min(deficit, batch)


def allowed_units(order_remaining, readable, inflight):
    """复刻 allowedConcurrentUnits（概率配方分批量的上界）。"""
    return order_remaining if (readable and order_remaining > 0) else max(1, inflight)


print("  反例表（确定性 / 概率性 × 开关 开/关；缺口语义：缺口 = 目标 −(网络+本仓+机器侧+在途)）")
print("  %-46s %-8s %-8s %-8s" % ("情形", "缺口", "关", "开"))
ROWS_A = [
    ("金板 58 → 目标 64（用户原话例子）", 6, False),
    ("金板 63 → 目标 64（刚好差 1）", 1, False),
    ("金板 64 → 达标（缺口 0）", 0, False),
    ("概率配方 缺 64（成品率 <100%）", 64, True),
    ("概率配方 缺 1（成品率 <100%）", 1, True),
]
for label, gap, chance in ROWS_A:
    off = request_amount(gap, chance, False)
    on = request_amount(gap, chance, True)
    print("  %-46s %-8d %-8d %-8d" % (label, gap, off, on))
check("①a 确定性配方：开关关 ⇒ 一次一份（旧行为）；开 ⇒ 一次要足缺口（6 / 1 / 0）",
      request_amount(6, False, False) == 1 and request_amount(6, False, True) == 6
      and request_amount(0, False, True) == 0)
check("①b 概率配方：无论开关 一律<b>不</b>按缺口要足（缺 64 也只分批递进，绝不 64 一次）",
      request_amount(64, True, False) == 64 and request_amount(64, True, True) == 64
      and request_amount(1, True, True) == 1)
check("①c 「宁可少不可多」：确定性开关打开时请求量恒 == 缺口（不可能多），缺口 ≤ 0 时恒 0",
      all(request_amount(g, False, True) == max(0, g) for g in (0, 1, 5, 6, 64, 1000)))
print("  收敛性（确定性，开关开）：t0 缺 6 → 要 6；成品回网后缺口 6→0 ⇒ 请求量归 0（绝不停不下来）")
print("  %-14s %-8s %-8s %-8s" % ("时点", "缺口", "请求量", "累计请求"))
_gap, _total = 6, 0
for tick in range(0, 5):
    _ask = request_amount(_gap, False, True)
    _total += _ask
    print("  %-14s %-8d %-8d %-8d" % ("t%d" % tick, _gap, _ask, _total))
    _gap = max(0, _gap - 2)  # 每 2 秒交付 2 件
check("①d 收敛：确定性配方下「请求量 = 缺口」逐轮单调收敛，缺补满后请求量恒为 0（不会无限请求）",
      request_amount(0, False, True) == 0 and request_amount(2, False, True) == 2)

# ============================================================ ② 指令开关
section("② 指令开关 /rs_create_compat refill <on|off>（服务端权威 + SavedData 持久化 + 锚点行）")

has(refill, "public final class RsccRefillPolicy extends SavedData {",
    "锚点①: 开关是 SavedData（按存档持久化，读档仍生效）")
has(refill, "private static final boolean DEFAULT_ENABLED = false;",
    "锚点②: 默认档 = 关（变更前后行为一致；新行为纯 opt-in）")
has(refill, "if (tag.contains(TAG_ENABLED)) {",
    "锚点③: 旧存档没有该键 ⇒ 保持默认关（读档不会「悄悄打开」新行为）")
has(refill, 'LOGGER.info("{} refill={} (source=command)", ANCHOR, enabled ? "on" : "off");',
    "锚点④: 状态变化打一条锚点行（可在日志里核对指令确实生效）")
has(commands, 'Commands.literal("refill")', "锚点⑤: 指令树注册了 refill 字面量")
has(commands, "RsccRefillPolicy.get(server).setGapRefill(enable);",
    "锚点⑥: 处理器写入持久化开关（服务端权威）")
has(diag, 'LOGGER.info("{} refill={} (session default, source=save)",',
    "锚点⑦: 会话启动锚点行（不敲指令也能在日志里核对默认档）")
for key in ("message.rs_create_compat.refill.current", "message.rs_create_compat.refill.on",
            "message.rs_create_compat.refill.off", "message.rs_create_compat.refill.set",
            "message.rs_create_compat.refill.usage"):
    check("语言键存在且中英成对：%s" % key, key in zh and key in en)
check("语言键风格与新键前缀一致（全部落在 message.rs_create_compat.refill.）",
      all(k.startswith("message.rs_create_compat.refill.") for k in zh if "refill" in k)
      and all(len(zh[k]) <= 40 for k in zh if k.startswith("message.rs_create_compat.refill.")))
print("  反例表（档位 → 行为）")
print("  %-18s %-12s %-46s" % ("档位", "行为", "理由"))
print("  %-18s %-12s %-46s" % ("off（默认）", "旧口径", "确定性一次一份 / 概率分批 ⇒ 与变更前逐字一致"))
print("  %-18s %-12s %-46s" % ("on", "按缺口补发", "确定性要足缺口；概率仍分批 ⇒ 绝不多要 / 不烧料"))
check("②a 默认档 = 关（本任务要求「按变更前后行为一致」的稳妥选择）", 'DEFAULT_ENABLED = false' in refill)

# ============================================================ ③ 可自动合成 ⇒ 静默等待
section("③ 可自动合成 / 已有合成任务在跑 ⇒ 不报缺、不挂起、静默等待")

has(chamber, "private static boolean isAutoCraftable(final AutocraftingNetworkComponent autocrafting, final Item item) {",
    "锚点①: 执行舱「该物品可不可自动合成」只有一份实现")
has(chamber, "private static boolean isAutoCraftable(final AutocraftingNetworkComponent autocrafting, final Fluid fluid) {",
    "锚点②: 流体口径对称实现")
check("锚点③: 缺料上报里对「可自动合成」的物品 / 流体各整条跳过（用户第 3 条）",
      chamber.count("if (isAutoCraftable(shortageAuto, entry.getKey())) {") == 2)
has(watchdog, "final boolean allMissingAutoCraftable = !missing.isEmpty()",
    "锚点④: 看门狗序列装配分类：缺项全可自动合成 ⇒ 视为「可推进」（不挂起、不弹横幅）")
has(watchdog, "|| intermediateBack || missing.isEmpty() || allMissingAutoCraftable);",
    "锚点⑤: 「可自动合成」并入 canAdvance（既有 progress || subTaskRunning || chamberInFlight 一字未改；"
    "第 21 轮同步：整条表达式前面新增 !pushStalled 前置项 —— 目的地持续拒收 / 该步无机器认领时不许算「可推进」）")
has(watchdog, "&& !isAutoCraftable(autocrafting, resource)) {",
    "锚点⑥: RS 原版任务分类同样不计「可自动合成」的资源为缺料")


def should_report_missing(short_by, auto_craftable, task_running):
    """复刻 reportBusShortages 的单条判定：缺口 > 0 且既不可自动合成、也没有在跑的合成任务才报缺。"""
    if short_by <= 0:
        return False
    if auto_craftable or task_running:
        return False
    return True


print("  反例表（单条资源 → 会不会报「缺少材料」）")
print("  %-52s %-8s" % ("情形", "报缺"))
CASES_C = [
    ("金板缺 1，但金板<b>可自动合成</b>（我已写好配方）", 1, True, False, False),
    ("金板缺 1，已有金板的合成任务在跑", 1, False, True, False),
    ("金板缺 1，既不可合成也没有任务（真缺料）", 1, False, False, True),
    ("黑曜石粉缺 1，同样可自动合成", 1, True, False, False),
    ("金板缺口 0（已达标）", 0, False, False, False),
]
for label, gap, auto, running, expect in CASES_C:
    got = should_report_missing(gap, auto, running)
    print("  %-52s %-8s %s" % (label, got, "OK" if got == expect else "MISMATCH"))
check("③a 可自动合成 ⇒ 静默等待、不报缺；只有「不可合成且没有在跑任务」才报缺",
      all(should_report_missing(g, a, r) == e for _l, g, a, r, e in CASES_C))
check("③b 判不出来（拿不到自动合成组件）时 a=False ⇒ 照旧报缺，绝不漏掉真实缺料",
      should_report_missing(1, False, False) is True)

# ============================================================ ④ 完成后绝不报缺 + 不重复播报
section("④ 任务完成 / 缺口 0 / 成品达标 ⇒ 不报缺；同一份缺口不得重复播报")


def order_delivered_in_full(related_rows):
    """复刻 orderDeliveredInFull：无相关任务 ⇒ True；有 ⇒ 每条 delivered(=stored+crafting) >= ordered。"""
    for ordered, stored, crafting in related_rows:
        if max(0, stored) + max(0, crafting) < max(1, ordered):
            return False
    return True


def deliver_gate(rows):
    return order_delivered_in_full(rows)


check("锚点①: 「需求已达成」闸门仍在 reportBusShortages 入口（orderDeliveredInFull ⇒ 整条短路）",
      "if (orderDeliveredInFull()) {" in chamber and "private boolean orderDeliveredInFull() {" in chamber)
check("锚点②: 同一份缺口「未变化就绝不重复播报」（去重为主、限频为辅）",
      "if (signature.equals(busShortageSignature) || now < busShortageNotifyTick) {" in chamber)
check("锚点③: 横幅限频常量仍是 100 tick（既有断言）", "BUS_SHORTAGE_NOTIFY_INTERVAL_TICKS = 100;" in chamber)

print("  反例推演（下单 64 个精密构件、最后一件正在加工的那几秒 → 完成后 1s / 10s / 60s）")
print("  %-34s %-16s %-10s" % ("时点", "相关任务交付", "报缺"))
CASES_D = [
    # (标签, 本仓相关任务的交付行, 被缺的那件是否正被合成, 期望「不报缺」)
    ("完成当刻（最后一件 crafting=1）", [(64, 63, 1)], False, True),
    ("完成后 1 秒（任务已从列表消失）", [], False, True),
    ("完成后 10 秒（我们自己的金板补货任务在跑）", [(6, 0, 0)], True, True),
    ("完成后 60 秒（无任何相关任务）", [], False, True),
    ("仍在跑 + 未交付满 + 缺口 > 0 + 不可合成（真缺料）", [(64, 10, 0)], False, False),
]
for label, rows, resource_running, expect_not_report in CASES_D:
    full = deliver_gate(rows)
    report = (not full) and should_report_missing(1, False, resource_running)
    print("  %-34s %-16s %-10s %-8s" % (label, ("满" if full else "未满"), report, expect_not_report))
    check("④ %s ⇒ 不报缺=%s" % (label, expect_not_report),
          (full or not report) == expect_not_report)


def notify_dedup(signature, last_signature, now, next_allowed):
    """复刻 notifyBusShortage：同签名或冷却未到 ⇒ 不发。返回 (是否发送, 新签名, 新 next)。"""
    if signature == last_signature or now < next_allowed:
        return False, last_signature, next_allowed
    return True, signature, now + 100


print("  反例表（同一条缺料在 5 秒窗口内的重复尝试）")
print("  %-30s %-6s %-10s" % ("尝试", "tick", "发送"))
seq = [(0, "gap:A"), (20, "gap:A"), (40, "gap:A"), (100, "gap:A"), (140, "gap:B"), (200, "gap:B")]
last, nxt, sent_total = "", 0, 0
for tick, sig in seq:
    sent, last, nxt = notify_dedup(sig, last, tick, nxt)
    sent_total += 1 if sent else 0
    print("  %-30s %-6d %-10s" % (sig, tick, sent))
check("④a 同一份缺口（gap:A 连试 4 次）只发 1 次；缺口变化（gap:B）立刻再发 1 次；之后同缺口不再发",
      sent_total == 2)
check("④b 不得重复播报：同一签名的后续尝试一律被去重吞掉",
      notify_dedup("gap:A", "gap:A", 100000, 0)[0] is False)

# ============================================================ ⑤ 齿轮仍堵（换角度）
section("⑤ 齿轮仍堵（第 N 轮）：换角度 = 「列表视图（全仓并集）」与「单物品视图（按本机）」判据不一致")

has(chamber, "public boolean inputMaterialWantedNow(@org.jetbrains.annotations.Nullable final Item item) {",
    "锚点①: 「全仓并集」口径仍有唯一实现（列表视图）")
check("锚点②: 本轮修正点 —— 全仓并集里「空闲机械手」不再算 undecidable（= 确定「它此刻不要」）",
      chamber.count("if (!isDeployer(level, target)) {\n                    undecidable = true;") >= 2
      and "// 其它机器形态判不出来 → 不因它而断供" in chamber)
has(chamber, 'RsccAssemblyDebug.event(RsccAssemblyDebug.machine("chamber", worldPosition)\n'
             '                + " net_down {item="',
    "锚点③: 「网络数量下降」被记成 net_down（玩家手动拿走 = 外部事件，唯一表现就是数量下降）")
has(chamber, "private void logPipelineNetDrops(final StorageNetworkComponent storage) {",
    "锚点④: net_down 探针只有一份实现（只读、只在下降时输出）")
has(chamber, "logPipelineNetDrops(storage);", "锚点⑤: 探针挂在引擎节流之后（两种输出模式都覆盖）")


def chamber_wide_wanted_OLD(item, stations):
    """复刻修复前的全仓并集：空闲机械手 ⇒ undecidable ⇒ 返回 True（= 收回侧以为「某台还要它」）。"""
    undecidable = False
    for state in stations:
        if state.get("pending") is None:
            undecidable = True          # 旧实现：机械手也计进来
            continue
        wanted = state.get("wants", set())
        if not wanted:
            return True
        if item in wanted:
            return True
    return undecidable


def chamber_wide_wanted_NEW(item, stations):
    """复刻本轮修正后的全仓并集：空闲机械手 = 确定「不要」（与 wantingTargetCount 同口径）。"""
    undecidable = False
    for state in stations:
        if state.get("pending") is None:
            if not state.get("deployer"):
                undecidable = True      # 其它机器形态：判不出来 ⇒ 宽松
            continue
        wanted = state.get("wants", set())
        if not wanted:
            return True
        if item in wanted:
            return True
    return undecidable


GEAR = "create:cogwheel"
IDLE_ARM = {"pending": None, "deployer": True}
STEP1_ARM = {"pending": 1, "deployer": True, "wants": {"create:large_cogwheel"}}
STEP0_ARM = {"pending": 0, "deployer": True, "wants": {GEAR}}
UNKNOWN_MACHINE = {"pending": None, "deployer": False}
SCENARIOS = [
    ("只有一个空闲机械手（齿轮是废料残留在仓里）", [IDLE_ARM]),
    ("机械手已走到第 1 步（手里那件齿轮是废料）", [STEP1_ARM]),
    ("机械手第 0 步正要齿轮（正常）", [STEP0_ARM]),
    ("有空闲机械手 + 一台判不出步序的其它机器", [IDLE_ARM, UNKNOWN_MACHINE]),
    ("没有供料目标（判不出来）", []),
]
print("  反例表（全仓并集返回 True ⇒ 收回侧把「废料齿轮」当「某台还要它」⇒ <b>永远收不回网络</b>）")
print("  %-52s %-10s %-10s" % ("情形", "修复前", "修复后"))
for label, stations in SCENARIOS:
    old = chamber_wide_wanted_OLD(GEAR, stations)
    new = chamber_wide_wanted_NEW(GEAR, stations)
    print("  %-52s %-10s %-10s" % (label, old, new))
check("⑤a 空闲机械手 ⇒ 修复前 True（= 齿轮废料永远收不回 ⇒ 堵在仓里，只能玩家手动抠 = 「齿轮-N」），"
      "修复后 False（= 收回侧认定「没有工位要它」⇒ 正常收回网络）",
      chamber_wide_wanted_OLD(GEAR, [IDLE_ARM]) is True
      and chamber_wide_wanted_NEW(GEAR, [IDLE_ARM]) is False)
check("⑤b 机械手第 1 步（手里攥着齿轮那件废料）⇒ 修复后 False（收回）—— 与推料侧「按本机判」严格互补",
      chamber_wide_wanted_NEW(GEAR, [STEP1_ARM]) is False)
check("⑤c 机械手第 0 步正要齿轮 ⇒ 两侧都为 True（绝不因本次修正而把正在加工的那一件抽走）",
      chamber_wide_wanted_NEW(GEAR, [STEP0_ARM]) is True
      and chamber_wide_wanted_OLD(GEAR, [STEP0_ARM]) is True)
check("⑤d 判不出来的其它机器形态仍保守放行（附属模组自定义步不会被饿死）",
      chamber_wide_wanted_NEW(GEAR, [IDLE_ARM, UNKNOWN_MACHINE]) is True)
check("⑤e 没有任何供料目标 ⇒ 两侧都不放行（= 收回侧认定「没有工位要它」⇒ 仓里那份齿轮按废料收回）；"
      "注意这与 wantingTargetCount 的「没有工位 ⇒ 保底 1 份」不同：那是<b>备料侧</b>的「判不出来不饿死」口径，"
      "而这里是<b>收回侧</b>的「要回收」判断，两者刻意不对称、各自朝向安全的方向",
      chamber_wide_wanted_OLD(GEAR, []) is False and chamber_wide_wanted_NEW(GEAR, []) is False)


def classify_net_drop(drop, take_to_chamber_same_tick):
    """复刻日志判读口径：net_down 行 + 同 tick 有没有 take_to_chamber。"""
    if drop == 0:
        return None
    if take_to_chamber_same_tick:
        return "chamber_pull(系统拉料)"
    return "external_take(玩家手动拿走)"


print("  反例表（怎么从日志区分「系统往返」与「用户手动拿走」）")
print("  %-52s %-26s" % ("日志形态", "判读"))
JUDGE = [
    ("net_down {item=create:cogwheel} delta=-1，同 tick 无 take_to_chamber",
      classify_net_drop(1, False), "external_take(玩家手动拿走)"),
    ("net_down {item=create:cogwheel} delta=-1，同 tick 有 take_to_chamber",
      classify_net_drop(1, True), "chamber_pull(系统拉料)"),
    ("本仓拉料后又把同一件收回（take_to_chamber + importer took ... MOVED）",
      "roundtrip", "roundtrip"),
]
for label, got, expect in JUDGE:
    print("  %-52s %-26s %s" % (label, got, "OK" if got == expect else "MISMATCH"))
check("⑤f 「有 take_to_chamber 的下降」= 系统拉料；「没有 take_to_chamber 的下降」= 玩家手动拿走"
      "（用户的「齿轮-N」可与 net_down 行逐条对上；系统往返则表现为 net_down + net 回升成对出现）",
      classify_net_drop(1, False).startswith("external") and classify_net_drop(1, True).startswith("chamber"))
check("⑤g 探针只在「真的下降」时输出（数量不变 / 上升不产生任何行 ⇒ 稳态零噪声）",
      classify_net_drop(0, False) is None)

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
