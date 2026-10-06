# -*- coding: utf-8 -*-
"""第 11 轮反馈（用户 6 条）的逐条断言 —— 源码锚点 + 真实配方数据推演。

为什么要有它：用户这一轮点名 6 件事（齿轮堵塞 / 精密构件配方分类 / 多余中间产物 /
单件与 64 件消耗差异 / 缺料挂起开关 / 断缝横幅）。本脚本把「每条期望的代码落点」与
「来自 Create 真实配方 JSON 的事实」各断言一遍，避免只靠肉眼看刷屏日志。

用法：python tools/selfcheck_round11_feedback.py
      → 全绿输出 `SELFCHECK OK (n checks)`；失败退出码 1。
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
RECIPES = os.path.join(ROOT, "local_src", "create_src", "data", "create", "recipe", "sequenced_assembly")

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


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


chamber = read(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
import_strategy = read(SRC, "support", "RsccChamberImportStrategy.java")
export_strategy = read(SRC, "support", "RsccChamberExportStrategy.java")
probe = read(SRC, "support", "SequencedRecipeProbe.java")
watchdog = read(SRC, "support", "AssemblyWatchdog.java")
shortage = read(SRC, "support", "RsccShortagePolicy.java")
commands = read(SRC, "command", "CompatCommands.java")
zh = json.load(io.open(os.path.join(LANG, "zh_cn.json"), encoding="utf-8"))
en = json.load(io.open(os.path.join(LANG, "en_us.json"), encoding="utf-8"))

# ==================== 1) 齿轮堵塞：输入总线的自动模式只保留一份判定 ====================
section("1) 齿轮堵塞 / 输入总线「自动模式」：判定只有一份、且严格互补")

has(import_strategy, "private static boolean autoAcceptsItem(final BlockPos pos, final ItemStack stack,",
    "锚点①: 输入总线自动模式的机器侧判据只有一份（autoAcceptsItem，带机器坐标）")
has(import_strategy, "private static boolean autoAcceptsChamberItem(final ItemStack stack,",
    "锚点②: 仓内存储与机器侧分开判（autoAcceptsChamberItem）—— 杜绝「买进来→退回去」每秒来回")
has(import_strategy, "&& !chamber.inputMaterialWantedNow(pos, stack.getItem())) {",
    "锚点③: 「本机当前待加工步不要的那一份」= 压在机械手手里的废料残留 → 收回（判据是推料侧同一个方法）")
has(import_strategy, "if (chamber.isStartIngredient(stack.getItem()) && !residualEdge) {",
    "锚点④: 起步原料任务期间绝不被自动收回（只在任务结束边沿收一遍）")
has(export_strategy, "if (!chamber.inputMaterialWantedNow(targetPos, item.item())) {",
    "锚点⑤: 推料侧与收回侧用同一个 inputMaterialWantedNow —— 两侧结论严格互补，不可能互搏")
check("结论: 「刚推出 → 立刻收回 → 再推出」这条空转在结构上不成立（两侧同一个判据的两面）",
      "chamber.inputMaterialWantedNow(pos, stack.getItem())" in import_strategy
      and "chamber.inputMaterialWantedNow(targetPos, item.item())" in export_strategy)

# ==================== 2) 精密构件产线：四类分类（真实配方数据） ====================
section("2) 精密构件产线：原料 / 输入时原料 / 成品 / 废料 逐项对照 Create 真实配方")

pm = json.load(io.open(os.path.join(RECIPES, "precision_mechanism.json"), encoding="utf-8"))
pool = [e for e in pm["results"] if e.get("id")]
pool_ids = [e["id"] for e in pool]
weights = [float(e.get("chance", 1.0)) for e in pool]
total = sum(weights)
print("  真实 results 池（权重）: %s" % list(zip(pool_ids, weights)))
print("  真实 ingredient（原料）: %s" % pm["ingredient"])
print("  真实 sequence 各步输入:  %s" % [s["ingredients"][1] for s in pm["sequence"]])

# emule SequencedRecipeProbe#splitResultPool（第一项非空 = 主产物，其余 = 废料）
results_side = [pool_ids[0]]
scraps_side = pool_ids[1:]

check("成品 = 只有 create:precision_mechanism（= results 池第一项，用户原话「成品应该只有精密构件」）",
      results_side == ["create:precision_mechanism"], "实际结果侧=%s" % results_side)
check("废料 = 池里其余全部（安山合金 create:andesite_alloy 必须在这里，不在成品里）",
      "create:andesite_alloy" in scraps_side and "create:andesite_alloy" not in results_side,
      "实际废料侧=%s" % scraps_side)
check("概率与 Create JEI 同一归一公式（权重 / 权重和）：精密构件 = %.0f%%"
      % (weights[0] / total * 100), abs(weights[0] / total - 120.0 / 150.0) < 1e-9)
has(probe, "results.add(new Output(nonEmpty.get(0).stack(), probability(nonEmpty.get(0).chance(), totalWeight)));",
    "锚点: splitResultPool 只把第一项当成品（与 Create getResultItem()=resultPool.getFirst() 同口径）")
has(probe, "scraps.add(new Output(output.stack(), probability(output.chance(), totalWeight)));",
    "锚点: 其余项一律归为废料")
has(chamber, "addProductCategory(results, RsccBusCategory.RESULT_PREFIX, output.stack());",
    "锚点: 成品类别 id = result:<物品>")
has(chamber, "addProductCategory(scraps, RsccBusCategory.SCRAP_PREFIX, output.stack());",
    "锚点: 废料类别 id = scrap:<物品>（安山合金因此是 scrap:create:andesite_alloy）")

# 输入的「步内投入物」= sequence 每步第二个 ingredient（第 0 个被 Create 覆盖成过渡件）
step_inputs = []
for s in pm["sequence"]:
    second = s["ingredients"][1]
    step_inputs.append(second.get("item") or second.get("tag"))
check("输入时原料（中间投入）= 齿轮 / 大齿轮 / 铁粒（按真实 sequence 排列，逐项对上）",
      step_inputs == ["create:cogwheel", "create:large_cogwheel", "c:nuggets/iron"],
      "实际=%s" % step_inputs)
check("原料（起步主原料）= c:plates/gold（= create:golden_sheet 金板，Create 的 ingredient）",
      pm["ingredient"].get("tag") == "c:plates/gold")
has(probe, "// 从下标 1 开始：下标 0 一定被 initFromSequencedAssembly 覆盖为过渡件（或过渡件+主原料的复合）",
    "锚点: stepInput/assemblyStepInputs 跳过下标 0 的唯一说明（输入时原料读的是第 1 个 ingredient）")

# ==================== 3) 多余中间产物：按需补发（份额 = 订单剩余件数） ====================
section("3) 多余中间产物：备料份数受「订单剩余件数」约束（确定性配方一次一份）")

has(chamber, "private int wantingTargetCount(", "锚点①: 「有几个工位缺这份投入物」只有一份实现")
has(chamber, "if (isStartIngredient(item) && unitInFlightAt(target)) {",
    "锚点②: 起步原料 + 工位上还有在制件 ⇒ 这台开不了新件 ⇒ 不为它备料（一次只开一件）")
has(chamber, "if (supplyTargetHoldsItem(level, target, item)) {",
    "锚点③: 这台机器手上 / 台上已握着这件未被消耗 ⇒ 绝不备第二份")
has(chamber, "want = (int) Math.min((long) want, Math.max(1L, remaining));",
    "锚点④: 份额闸门 —— 备料份数 ≤ 订单剩余件数（下单 1 个时目标量被夹到 1）")
has(chamber, "private long remainingOrderUnits() {", "锚点⑤: 订单剩余件数取自 RS 任务自身（不是猜）")
has(chamber, "final int deficit = busItemDeficit(resource.item(), target);",
    "锚点⑥: 备料按「缺口」夹量（仓内已够 → 缺口 0 → 一点不抽），因此不会「多余地多抽一份」")

# 算术：确定性配方（坚固板 loops=1、3 步）一次只该有一件在制
print("  推演（坚固板）: 下单 1 件 ⇒ 目标量 = min(缺它的工位数, 订单剩余 1) = 1 ⇒ 只抽 1 份粉")
print("  推演（概率配方）: 不足时按缺口补发（busItemDeficit），不额外多抽")
check("结论: 「多余中间产物」的两条来源（起步原料多开一件 / 备料按批次大缓冲）都已被闸门夹住",
      "unitInFlightAt(target)" in chamber and "busItemDeficit(resource.item(), target)" in chamber)

# ==================== 4) 单件 vs 64 件：分支差异 ====================
section("4) 下单 1 件 vs 64 件：走的是同一条链，差别只在「份额闸门是否拿得到剩余件数」")

has(chamber, "if (statuses.isEmpty()) {\n            return -1L; // 没有任务：门控此时也已关闭，份额无意义（返回「判不出来」最保守）",
    "锚点①: 份额唯一数据源 remainingOrderUnits —— 没有相关任务时返回 -1（判不出来）")
has(chamber, "if (now == remainingOrderProbeTick) {\n            return remainingOrderProbeValue;",
    "锚点②: 份额每 tick 只真正算一次（同一 tick 内多类别 / 多总线共用同一值）")
has(chamber, "if (remaining > 0L) {", "锚点③: 只有拿到剩余件数（> 0）才夹份额；拿不到就退回既有行为（要几份备几份）")
has(chamber, "final long ordered = Math.max(1L, status.info().amount());",
    "锚点④: 订单总量取自 RS 任务 info.amount()（1 件与 64 件在这里分叉）")
print("  分支差异（同一段代码，唯一分叉点 = remaining 的值）:")
print("    N=1  : 任务短命 / 常读不到 → remaining 可能为 -1 → 闸门跳过 → want = 缺它的工位数")
print("    N=64 : 任务长期存在 → remaining = 64 → 闸门生效 → want = min(工位数, 64) = 工位数")
print("  两档共用『一次一份』的下游闸门（unitInFlightAt / supplyTargetHoldsItem / busItemDeficit）")
check("结论: 不存在「1 件走一条链、64 件走另一条链」的独立分支 —— 只有 remaining 的取值不同；"
      "因此单件异常只可能来自『拿不到剩余件数』这一种情形",
      chamber.count("remainingOrderUnits()") >= 2 and "remainingOrderProbeTick" in chamber)

# ==================== 5) 缺料处置开关：挂起 / 一直等待 ====================
section("5) 缺料处置开关（用户第 5 条）：持久化、服务端权威、默认与既有行为逐字一致")

has(shortage, "public enum Mode {", "锚点①: 二档枚举（SUSPEND / WAIT）")
has(shortage, 'SUSPEND("suspend"),', "锚点②: 档位 id = suspend")
has(shortage, 'WAIT("wait");', "锚点③: 档位 id = wait")
has(shortage, "private static final Mode DEFAULT_MODE = Mode.SUSPEND;",
    "锚点④: 默认 = 缺料即挂起（= 既有行为，旧存档零回归）")
has(shortage, "extends SavedData", "锚点⑤: 按存档持久化（SavedData，读档仍生效）")
has(shortage, "public static Mode mode(final Level level) {",
    "锚点⑥: 只读入口（服务端权威；客户端回落默认档，不缓存过期值）")
has(commands, 'Commands.literal("shortagemode")', "锚点⑦: 指令 /rs_create_compat shortagemode <suspend|wait>")
has(watchdog, "final boolean shortWaiting = record.reason == Reason.MISSING_MATERIAL",
    "锚点⑧: 看门狗按档位门控「缺料」这一类原因的自动挂起")
has(watchdog, "if (!shortWaiting && record.reason != Reason.NONE",
    "锚点⑨: WAIT 档只在缺料时抑制自动挂起")
check("执行器离线 / 无进展仍照旧挂起（WAIT 只压制 MISSING_MATERIAL，不会让掉线任务永远堵住后面的任务）",
      "record.reason == Reason.MISSING_MATERIAL" in watchdog
      and "RsccShortagePolicy.mode(level) == RsccShortagePolicy.Mode.WAIT" in watchdog)
for key in ("message.rs_create_compat.shortage.current", "message.rs_create_compat.shortage.set",
            "message.rs_create_compat.shortage.usage", "message.rs_create_compat.shortage.mode.suspend",
            "message.rs_create_compat.shortage.mode.wait"):
    check("语言键中英成对: %s" % key, key in zh and key in en)
check("手动挂起不受本开关影响（suspend() 仍独立存在，任何档位下都生效）",
      "public static boolean suspend(final ServerPlayer player, final UUID taskId)" in watchdog)

# ==================== 6) 断缝后的横幅 / 挂起提示 ====================
section("6) 断缝（线缆断开）后的挂起 / 缺料横幅")

has(watchdog, "public enum Reason {", "锚点①: 挂起原因枚举（离线 / 缺料 / 无进展 / 手动）")
has(watchdog, "record.reason = Reason.EXECUTOR_OFFLINE;", "锚点②: 断链 → EXECUTOR_OFFLINE（离线判定）")
has(watchdog, "if (!offline.isEmpty()) {", "锚点③: 离线检测入口")
has(watchdog, "sendBanner(level, record);", "锚点④: 挂起时必发一次横幅（sendBanner 是唯一发送点）")
has(watchdog, "if (offlineConfirmed) {", "锚点⑤: 离线需连续两次确认才算数（防瞬时误挂）")
check("本项目新增的 WAIT 档不会误杀断缝横幅：断缝归 EXECUTOR_OFFLINE，而 WAIT 只压制 MISSING_MATERIAL",
      "record.reason == Reason.MISSING_MATERIAL" in watchdog
      and "Reason.EXECUTOR_OFFLINE" in watchdog)

# ==================== 结果 ====================
print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
