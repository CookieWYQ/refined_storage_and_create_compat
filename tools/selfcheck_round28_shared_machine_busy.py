# -*- coding: utf-8 -*-
"""第 28 轮自检：**两条配方共用一批工位时的「机器在忙」辨别**（修误挂起），且不放跑真堵。

用法：python tools/selfcheck_round28_shared_machine_busy.py
      → 全部通过时输出 `SELFCHECK OK (n checks)`，失败时退出码 1。

为什么要有它（本轮无法在本地把游戏跑起来验证）：

  用户实测：「我在测试精密构件和列车轨道**同时进行**……又出现了莫名其妙的暂停，但是点击继续
  还是正常。已经导出了两份快照。」—— 任务**真的**被挂起了（不是弹窗误报）。

  证据（本次核对）：
    * 快照 run/rscc_diag/20261006-125123/snapshot.json
        chambers[2].pos = -5,-60,6  name=USE  recipeType=create:deploying
        ownedSteps = create:sequenced_assembly/precision_mechanism#0,#1,#2
                     + create:sequenced_assembly/track#0,#1     ← **两条配方共用同一台机器**
        counters   = (-7,-60,5)|create:precision_mechanism|collect=5
                     (-7,-60,5)|create:incomplete_track|collect=12    ← 两台置物台都轮流做两条配方的件
                     (-7,-60,6)|create:precision_mechanism|collect=5
                     (-7,-60,6)|create:incomplete_track|collect=8
    * 日志 run/logs/latest.log 12:49:24.669/.671（**同一 tick** 两条任务一起被误判）：
        watchdog notice task=85f81e5c… step=0 cause=DESTINATION_REFUSED
                       resource=create:golden_sheet reason=OUTPUT_BLOCKED   （精密构件 x10）
        watchdog notice task=cf971bb8… step=0 cause=DESTINATION_REFUSED
                       resource=create:golden_sheet reason=OUTPUT_BLOCKED   （列车轨道 x10）
      而同一段时间这条线一直在出货：12:48:59.767 / 12:49:06.965 / 12:49:13.718
        take_from_machine create:precision_mechanism；
      12:48:59.771 / 12:49:06.967 / 12:49:14.170 / 12:49:23.614
        bus_push create:golden_sheet … EXPORTED/ok。
      两次拒收分别落在**不同的**置物台：12:49:21.364 → machine@(-7,-60,5)（工位上占着一件
        create:cogwheel，12:49:21.667 被本仓自愈收走）、12:49:22.711 → machine@(-7,-60,6)
        （那件 create:andesite_alloy 12:49:22.715 被取走）。

  根因：零进展判据只看「时间戳最新的那一格」（rscc$latestRefusedTarget），而两条总线的拒收
  轮流刷新时间戳 ⇒ 每次都比错格子 ⇒「另一格刚被取走」「另一格刚推成功」都被漏掉 ⇒
  把「机器正忙着做另一条配方的件」判成「堵死了」。

  本脚本锚定本轮的三件事：
    ① 逐格（rscc$refusedTargets + 逐格基线 + 逐格成功推送年龄）—— 机器在忙就不算堵；
    ② 真堵仍然秒级（60 tick）且不放宽 pushStalledOnDestination()（≥2 口径，填料的停手闸门）；
    ③ 既有的进入/恢复边沿、以及既有判据的第 ④ 分量 / blockedByMachineQueue / chainCategories
       / chainBusOwners / rscc$anyDestinationStuck / 点继续后仍堵只 1 条 —— 一条都没被动过。

  等价模型（含本次误报反例：两配方共机都有进展 ⇒ 0 条；以及真堵 ⇒ 1 条且 60 tick）见
  _audit_tmp/verify_push_notice_edges.py（场景 12/13/14/15/16）。
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
CHAMBER = os.path.join(SRC, "block", "entity", "SequenceExecutionChamberBlockEntity.java")
WATCHDOG = os.path.join(SRC, "support", "AssemblyWatchdog.java")
REPLICA = os.path.join(ROOT, "_audit_tmp", "verify_push_notice_edges.py")

FAILURES = []
CHECKS = [0]


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


def hasnt(text, needle, name):
    ok = needle not in text
    check(name, ok, "" if ok else ("unexpected: %s" % needle))


def body(text, start, end):
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


def section(title):
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


watchdog = read(WATCHDOG)
chamber = read(CHAMBER)

# --------------------------------------------------------------------------------------
section("① 执行舱侧：新增的只读入口 + 「整仓停手」闸门不许被动")
# --------------------------------------------------------------------------------------
has(chamber, "public java.util.List<BlockPos> rscc$refusedTargets() {",
    "rscc$refusedTargets() 存在且是 public（逐格交出「此刻仍在拒收」的全部工位）")
has(chamber, "alive.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));",
    "rscc$refusedTargets() 与 latestRefusedTarget 同取舍：时间戳最新在前")
has(chamber, "now - at > PUSH_STALL_WINDOW_TICKS",
    "rscc$refusedTargets() 与 PUSH_STALL_WINDOW_TICKS 同一窗口（过期拒收不算）")

refused_body = body(chamber, "public java.util.List<BlockPos> rscc$refusedTargets() {",
                    "\n    /**\n     * 兼容旧签名")
for forbidden, why in (("setChanged(", "写状态"), ("extractItem", "搬运物品"),
                       ("insertItem", "搬运物品"), ("refusedTargetAt.put", "写拒收表"),
                       ("refusedTargetAt.remove", "写拒收表")):
    hasnt(refused_body, forbidden, "rscc$refusedTargets() 是只读的：不含 %s（%s）" % (forbidden, why))

stalled = body(chamber, "public boolean pushStalledOnDestination() {",
               "\n    /**\n     * <b>只读</b>：窗口内被拒收过的目标是不是")
has(chamber, "return refusedTargetAt.size() >= 2;",
    "pushStalledOnDestination() 的「≥2 台同时拒收」口径未被放宽（fillInternalForBus 的停手闸门）")
has(stalled, "pushStalledOnDestination",
    "pushStalledOnDestination() 仍是提示层复用的那个入口（本轮没有改它的调用点）")

# --------------------------------------------------------------------------------------
section("② 看门狗侧：逐格判据（机器在忙 ⇒ 不算堵；件也不动 ⇒ 才算堵）")
# --------------------------------------------------------------------------------------
has(watchdog, "final List<BlockPos> refused = chamber.rscc$refusedTargets();",
    "stallProgressOf 用 rscc$refusedTargets() 取「全部拒收工位」")
has(watchdog, "final Set<BlockPos> watched = new LinkedHashSet<>(refused);",
    "观察集合 = 此刻仍拒收的工位 ∪ 本段窗口曾经拒收过我们的工位")
has(watchdog, "watched.addAll(record.pushNoticeStationBaselines.keySet());",
    "「刚刚收下过我们推料」的那一格必须继续被观察（否则一清就把证据删掉了）")
has(watchdog, "private final Map<BlockPos, String> pushNoticeStationBaselines = new LinkedHashMap<>();",
    "逐格内容基线（工位 → 窗口起点指纹）是 Map，不是「一台 + 上一 tick」两个字段")
has(watchdog, "private boolean pushNoticeRebaseline;",
    "窗口重算时「重取基线但保留键集」的标记存在")
has(watchdog, "final boolean rebaseline = record.pushNoticeRebaseline;",
    "stallProgressOf 会消费该标记（窗口刚重算 ⇒ 本 tick 整体重取基线）")
has(watchdog, "if (rebaseline || baseline == null) {",
    "逐格基线：窗口起点 / 刚重算时记为基线，否则与基线比对「变没变」")
has(watchdog, "OTHER_LINE_PROGRESS,",
    "StallProgress 新增 OTHER_LINE_PROGRESS（工位上的东西在动 = 机器在忙）")
has(watchdog, "if (progress == StallProgress.PROGRESS || progress == StallProgress.OTHER_LINE_PROGRESS) {",
    "两种「有进展」都只让窗口重新起算，绝不弹横幅")
has(watchdog, "record.pushNoticeRebaseline = true;",
    "窗口重新起算时置重取标记（不 clear 键集）")
has(watchdog, "final long pushOkAge = chamber.rscc$lastPushOkAge(target);",
    "成功推送年龄改成**逐格**取（不再只问 latestRefusedTarget 那一台）")
has(watchdog, '+ " stations=" + record.pushNoticeStationBaselines.size()',
    "诊断行带上 stations=（实机复核时能看出观察集合有几格）")
has(watchdog, '+ " proof=" + (record.pushNoticeStationAt == null',
    "诊断行带上 proof=（实机复核时能看出「是哪一格证明了还有进展」）")
has(watchdog, "if (!ours && holdsOurInFlightPiece(slots, chamber.rscc$registeredUnitAt(target))) {",
    "否决项「工位上是我们推进去的在制件」逐格判定，语义未放宽")
has(watchdog, "if (unit == null || unit.step() < 0 || unit.recipe().isEmpty()) {",
    "holdsOurInFlightPiece 仍然拒绝「无进度组件 / 步序 -1」的登记（不放宽否决项）")
has(watchdog, "private static void resetPushObservation(final Record record) {",
    "resetPushObservation 统一作废「窗口起点 + 逐格基线」")
has(watchdog, "PUSH_NOTICE_ZERO_PROGRESS_TICKS = 60;",
    "零进展窗口仍是 60 tick（3 秒）⇒ 真堵仍是秒级发现")
has(watchdog, "PUSH_NOTICE_NO_OWNER_TICKS = 10;",
    "「某步无人认领」的确定性信号窗口未变（10 tick）")
has(watchdog, "final boolean noticeOwned = record.pushNoticeHot && pushStallReason(record);",
    "老路抑制（noticeOwned）仍在：机器正常加工时老路不会补第二条误报")

# --------------------------------------------------------------------------------------
section("③ 边沿语义：进入只弹一次、恢复才重新武装、点继续不重置武装")
# --------------------------------------------------------------------------------------
has(watchdog, "record.pushNoticeArmed = false;\n        resetPushObservation(record);",
    "进入边沿：弹过就清 pushNoticeArmed（同一状态只弹一次）")
has(watchdog, "record.pushNoticeArmed = true;\n        sendPushClearedBanner(level, record);",
    "重新武装只由 trackPushStallRecovery 的正向探针（工位真的空出来）触发")
resume = body(watchdog, "private static void resumeRecord(final Record record, final long now) {",
              "\n    /** 状态与 {@code suspended} 一起写回")
hasnt(resume, "pushNoticeArmed",
      "resumeRecord（玩家点「继续」）**不**动 pushNoticeArmed（点继续后仍堵 ⇒ 不重复弹）")

# --------------------------------------------------------------------------------------
section("④ 既有语义一条都没被动（第 ④ 分量 / 机器排队 / 链级类别 / 整仓探针 / 横幅键）")
# --------------------------------------------------------------------------------------
has(chamber, "private long[] inFlightBreakdownForRecipe(final String recipeId) {",
    "startCapacityForRecipe 的四分量入口 inFlightBreakdownForRecipe 仍在")
has(chamber, "parts[3] += countSettledIn(chamber.internalItemStorage(), settled, recipeId);",
    "第 ④ 分量（本仓内部已做完未回网的成品件）仍在")
has(chamber, "private boolean blockedByMachineQueue(final BusCategoryInfo info) {",
    "blockedByMachineQueue（共享机器排队）仍在")
has(chamber, "private Map<String, List<BlockPos>> chainBusOwners() {",
    "chainBusOwners()（链级总线属主唯一真源）仍在")
has(chamber, "public List<ChainBusCategory> chainCategories() {",
    "chainCategories() 仍在")
has(chamber, "public boolean rscc$anyDestinationStuck() {",
    "rscc$anyDestinationStuck()（提示层「任意一个目的地拒收」）仍在")
has(watchdog, "RsccDiag.recordBanner(\"watchdog_push_cleared\", record.taskId.toString());",
    "恢复横幅的诊断键 watchdog_push_cleared 未变")
has(watchdog, "RsccDiag.recordBanner(\"watchdog_\" + record.reason.name().toLowerCase(java.util.Locale.ROOT),",
    "进入横幅的诊断键 watchdog_<reason>（watchdog_output_blocked）未变")

# --------------------------------------------------------------------------------------
section("⑤ 等价模型（_audit_tmp/verify_push_notice_edges.py）必须含本轮的反例与护栏")
# --------------------------------------------------------------------------------------
if os.path.isfile(REPLICA):
    replica = read(REPLICA)
    check("等价模型文件存在", True)
    has(replica, "OTHER_LINE_PROGRESS", "模型里有 OTHER_LINE_PROGRESS（机器在忙）")
    has(replica, "single_station=True", "模型能切换到「本轮修正前」的判据做对照（根因可复现）")
    has(replica, "场景 12：两条配方共用同一批工位", "模型含「两条配方共机、都有进展 ⇒ 0 条」")
    has(replica, "场景 13：事故现场复刻", "模型含本次事故现场的逐 tick 复刻（旧判据 1 条 / 新判据 0 条）")
    has(replica, "场景 14（护栏）", "模型含真堵形态一（工位空着还被拒收 ⇒ 仍 1 条）")
    has(replica, "场景 15（护栏）", "模型含真堵形态二（件长期不动且无人推成功 ⇒ 仍 1 条）")
    has(replica, "场景 16（护栏）", "模型含「共用机器但机器停了 ⇒ 不许免死」的护栏")
    has(replica, "problems", "模型自带失败汇总（退出码非 0 即失败）")
else:
    check("等价模型文件存在", False, "missing: %s" % REPLICA)

# --------------------------------------------------------------------------------------
print()
print("=" * 72)
if FAILURES:
    for line in FAILURES:
        print("FAIL " + line)
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
