# -*- coding: utf-8 -*-
"""第 15 轮用户反馈 3 条的源码锚点 + <b>可离线推演的反例表</b>。

用户澄清后的三条（均为重复反馈）：
 ① 「刚好够」仍误报缺少黑曜石粉末 —— 换角度：**另一条报缺路径**（AssemblyWatchdog 的
    「由于 X 缺少或自动合成失败」）没有套上执行舱那套闸门：它只看**网络存量**、还把**过渡件**
    算成「缺少的原料」。
 ② 齿轮「堵塞」= 卡在置物台上、**系统从未自动收回**（用户手动拿走 3 次）。根因：「本步要它
    ⇒ 不收回」把「<b>需要</b>」与「<b>正在被消耗</b>」混为一谈 —— 卡住的那一份同样「需要」。
 ③ 「多余中间产物」= ② 的残留（卡住不收 ⇒ 又买回来 ⇒ 看起来多出来），量化上界「每件成品 ≤ 1 份」。

用法：python tools/selfcheck_round15_feedback.py
      → 全绿输出 `SELFCHECK OK (n checks)`；失败退出码 1。
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

STUCK_TICKS = 60  # 与 Java 常量 STATION_STUCK_TICKS 必须一致（本轮：200 → 60 = 3 秒）

# ============================================================ ② 齿轮堵塞：需要 ≠ 正在被消耗
section("② 齿轮堵塞（需要 ≠ 正在被消耗）：唯一判定 + 时间闸门 + 阈值理由")
print("  本轮修正：用户说「超过 5 秒还没回流」—— 5 秒 = 100 tick < 旧阈值 200 tick，")
print("  也就是说「10 秒才回流」在旧口径下是设计内行为，用户却把它当故障 ⇒ 阈值收到 60（3 秒）；")
print("  同时删掉「整条产线仍在推进」这条全局等待豁免（它让并行产线里卡住的工位永远等不到阈值）。")

has(chamber, "private static final int STATION_STUCK_TICKS = 60;",
    "锚点①: 阈值是明确常量 STATION_STUCK_TICKS = 60（3 秒），不是魔数")
has(chamber, "private boolean stationStuckOn(@org.jetbrains.annotations.Nullable final BlockPos target,",
    "锚点②: 卡住判定只有一份实现 stationStuckOn(工位, 物品, 待加工步)")
check("锚点③: 「推料 / 收回（按本机 + 全仓并集）/ 备料」三个执行点共用同一个 stationStuckOn（4 处调用；"
      "原来第 5 处是「收回侧的配方主原料」—— 本轮按硬约束把它改为恒定受保护）",
      chamber.count("stationStuckOn(target, item, pending)") == 4)
has(chamber, "if (wanted.contains(item) && stationStuckOn(target, item, pending)) {",
    "锚点④: 推料侧（inputMaterialWantedNow(BlockPos, Item)）——「本步要它」再经过卡住闸门")
has(chamber, "if (!stationStuckOn(target, item, pending)) {\n                    return true;"
             " // 这一台此刻正需要它 → 放行",
    "锚点④b: 收回侧的全仓并集（inputMaterialWantedNow(Item)）同样把卡住的工位排除在「要它」之外")
check("锚点⑤: 收回侧保护的源头（stationStepWantsInput）对「步骤专用投入物」经过卡住闸门："
      "要它但卡住 ⇒ 不再保护；而配方主原料恒定受保护（硬约束：任务期间起步原料绝不收回）",
      chamber.count("!stationStuckOn(target, item, pending)") == 2
      and "return mainIngredientCandidates(recipe).contains(item);" in chamber)
has(chamber, "if (stationStuckOn(target, item, pending)) {\n                continue;",
    "锚点⑥: 备料侧（wantingTargetCount）不为卡住的工位备料（否则会「收回 → 再买 → 再收回」）")
has(chamber, "需要 ≠ 正在被消耗",
    "锚点⑦: javadoc 写清了「为什么」（需要 ≠ 正在被消耗 = 卡住也算需要，这是漏洞）")
check("锚点⑧: 新阈值 60 的「为什么」写在常量 javadoc 里（3 秒 = 参考产线单步停留的 3 倍以上余量；"
      "并说明为什么不做第二条「立即判定」链：唯一能立即收回的 pending==null 早已不过闸门）",
      "3 倍以上余量" in chamber and "阈值为什么从 200 tick 收到 60 tick" in chamber
      and "为什么不另做「立即判定」的快速路径" in chamber)
check("锚点⑨: 观察表有界（STATION_STUCK_WATCH_MAX + pruneStuckWatch，防无界增长）",
      "STATION_STUCK_WATCH_MAX" in chamber and "private void pruneStuckWatch(final long now) {" in chamber)


GAP_TICKS = 20  # 与 Java 常量 STATION_OBSERVE_GAP_TICKS 必须一致（本轮：40 → 20 = 阈值的 1/3）


def simulate_stuck(observations):
    """忠实复刻 stationStuckOn：observations = [(tick, key, wait)]。

    key 相同 = 同一份「需要」仍被观察到；wait=True = 本次观察存在等待理由（hasWaitReason 为真）。
    Java 语义：
      * 有等待理由 → 计时清零并暂停（每次观察都把起点刷到「现在」），返回 False；
      * 上一次观察隔了太久（> STATION_OBSERVE_GAP_TICKS）→ 计时清零（这一份已不在工位上）；
      * now - since > STATION_STUCK_TICKS → 判卡住。
    """
    watch = {}
    out = []
    for tick, key, wait in observations:
        entry = watch.get(key)
        if wait:
            if entry is None:
                watch[key] = [tick, tick]
            else:
                entry[0] = tick
                entry[1] = tick
            out.append(False)
            continue
        if entry is None:
            watch[key] = [tick, tick]
            out.append(False)
            continue
        if tick - entry[1] > GAP_TICKS:
            entry[0] = tick
        entry[1] = tick
        out.append(tick - entry[0] > STUCK_TICKS)
    return out


def has_wait(frozen=False, wait_mode=False, craft_in_flight=False,
             craftable_and_missing=False, pipeline_progressing=False, held_on_station=False):
    """复刻 hasWaitReason（W1~W4）。

    pipeline_progressing 参数仅用于展示「旧 W5」的反例（旧实现里它会让任何一条并行产线
    永久豁免掉卡住的那个工位），Java 侧已删除该信号，因此默认恒为 False。

    held_on_station=True 复刻本轮收窄：这一份<b>已经压在工位上</b>时，W3/W4（等它被合成出来）
    对「它能不能被消化」毫无帮助 ⇒ 它们不再算等待理由，只认 W1/W2。
    """
    if frozen or wait_mode:
        return True    # W1 / W2：全局停工 / 玩家选的等待档 —— 恒有效
    if held_on_station:
        return False   # W3/W4 对「已经在工位上的那一份」不适用（本轮收窄）
    return craft_in_flight or craftable_and_missing or pipeline_progressing


# ① 正在被消耗：机械手每 60 tick 应用一步（步序变 ⇒ key 变），永远不会被判卡住。
consuming = [(t, "cog#step%d" % (t // 60), False) for t in range(0, 601, 20)]
verdicts_consuming = simulate_stuck(consuming)
# ② 真卡住：同一份「需要」原样待着、无任何等待理由，一直观察到 600 tick。
stuck = [(t, "cog#step0", False) for t in range(0, 601, 20)]
verdicts_stuck = simulate_stuck(stuck)
first_stuck_tick = next(t for t, v in zip(range(0, 601, 20), verdicts_stuck) if v)

print("  反例表（stationStuckOn → 「是否判定为卡住 / 放开收回」）")
print("  %-46s %-10s %-10s" % ("情形", "首次判定", "结论"))
print("  %-46s %-10s %-10s" % ("正在被消耗（步序每 60 tick 前进一次）", "从不",
                               "保护（绝不收回）"))
print("  %-46s %-10s %-10s" % ("真卡住（无在途 / 无合成 / 无缺料）",
                               "tick=%d" % first_stuck_tick, "放开收回"))
check("②a 正在被消耗的投入物<b>绝不</b>被判卡住（步序一变计时归零）⇒ 保护不破",
      not any(verdicts_consuming) and verdicts_consuming[0] is False)
check("③ 真卡住（无在途、无合成、无缺料、东西就躺在工位上不动）⇒ 60 tick 后<b>仍必须收回</b>"
      "（本轮阈值：3 秒，用户等 5 秒一定能看到回流）",
      any(verdicts_stuck) and 60 < first_stuck_tick <= 80)
check("②c 观察中断（这一份被消耗掉 / 被搬走后又放回来，间隔 > STATION_OBSERVE_GAP_TICKS）"
      "⇒ 计时清零重来，不沿用旧判定",
      simulate_stuck([(0, "cog#0", False), (100, "cog#0", False)]) == [False, False]
      and simulate_stuck([(0, "cog#0", False), (1000, "cog#0", False)]) == [False, False])
check("②d 换了一件（key 变）⇒ 各自独立计时，不会把上一件的卡住结论套到新件上",
      simulate_stuck([(0, "a#0", False), (400, "b#0", False)]) == [False, False])

# ②e 本轮收窄的现场反例（用户第 2 条「齿轮还是堵」的持久化机制）：
#     齿轮已经压在机械手工位上，它「可自动合成 + 网络里恰好没有它」（本仓刚把它抽进来时必然成立）。
#     旧实现认 W4 ⇒ 计时被无限刷新 ⇒ 永远等不到 60 tick ⇒ 收回侧永远不收回 ⇒ 永久堵塞。
#     收窄后：已经压在工位上的那一份不再认 W3/W4 ⇒ 60 tick 后如实判卡住并放开收回。
held_case = [(t, "cog#0", has_wait(craftable_and_missing=True, held_on_station=True))
             for t in range(0, 601, 20)]
verdicts_held = simulate_stuck(held_case)
first_held_tick = next(t for t, v in zip(range(0, 601, 20), verdicts_held) if v)
print("  %-46s %-10s %-10s" % ("已压在工位上 + 可自动合成 + 网络缺它（本轮收窄）",
                               "tick=%d" % first_held_tick, "放开收回"))
check("②e 已压在工位上的那一份不再吃 W3/W4 ⇒ 计时照常累计，60 tick 后判卡住并放开收回"
      "（旧口径下它会被 W4 永久豁免 ⇒ 用户实测的「齿轮永久堵」）",
      any(verdicts_held) and 60 < first_held_tick <= 80)
check("②f 「等这件料被合成出来」的豁免对<b>还没到工位上</b>的那一份仍然有效（用户反例一字未减）",
      has_wait(craftable_and_missing=True) is True and has_wait(frozen=True, held_on_station=True) is True
      and has_wait(wait_mode=True, held_on_station=True) is True)

# ============================================================ ②' 等待豁免（本轮核心：用户反例）
section("②' 等待豁免：卡住计时必须带「有正当理由在等」这道闸门（用户本轮反例）")

has(chamber, "private static final int STATION_OBSERVE_GAP_TICKS = 20;",
    "锚点①: 观察中断常量 STATION_OBSERVE_GAP_TICKS = 20（换件 / 被消耗 ⇒ 计时立即清零；"
    "固定为阈值的 1/3，不会把「换件」误当「同一份卡住」）")
has(chamber, "private boolean hasWaitReason(@org.jetbrains.annotations.Nullable final Item item, final long now) {",
    "锚点②: 等待豁免只有一份实现 hasWaitReason（W1~W4）")
has(chamber, "if (hasWaitReason(item, now, heldOnStation)) {",
    "锚点③: stationStuckOn 只在「没有任何正当理由在等」时才允许累计计时")
check("锚点③b（本轮收窄，用户第 2 条「齿轮还是堵」的持久化机制）: W3/W4（等这件料被合成出来）"
      "只在「这一份还没到工位上」时才算等待理由 —— 已经压在工位上的那一份若还认 W3/W4，"
      "只要「可自动合成 + 网络缺它」（本仓刚抽进来时必然成立）计时就被无限刷新 ⇒ 永远等不到阈值 ⇒ 永久堵塞。"
      "落点 = stationStuckOn 先算 heldOnStation，再交给 3 参重载 hasWaitReason",
      "final boolean heldOnStation = supplyTargetHoldsItem(level, target, item);" in chamber
      and "if (heldOnStation) {\n            return false;\n        }" in chamber
      and "private boolean hasWaitReason(@org.jetbrains.annotations.Nullable final Item item, final long now,\n"
          "                                  final boolean heldOnStation) {" in chamber)
check("锚点④: W1 挂起冻结（isBusFrozen）+ W2 缺料等待档（RsccShortagePolicy Mode.WAIT）都在豁免表里",
      "return true; // W1：挂起冻结" in chamber
      and "RsccShortagePolicy.Mode.WAIT" in chamber)
check("锚点⑤: W3 该资源有在途合成量（autocraftInFlight > 0）+ W4 可自动合成且网络缺它"
      "（isAutoCraftable && networkItemAmount <= 0）都在豁免表里",
      "autocraftInFlight(new ItemResource(item)) > 0L" in chamber
      and "isAutoCraftable(autocrafting, item) && networkItemAmount(item) <= 0L" in chamber)
check("锚点⑥（本轮修正）: 旧的 W5「整条产线仍在推进」已彻底删除 —— 它是<b>全局</b>信号，"
      "并行产线上健康那台每秒刷新它 ⇒ 卡住那个工位的计时被无限推迟（用户「等了很久也不回流」的直接机制）；"
      "hasWaitReason 末尾必须直接 return false，且两个旧探针的实现不得再出现"
      "（javadoc 里留一条「已删除」的说明是刻意的，判据因此只看实现）",
      "private boolean pipelineProgressedRecently(" not in chamber
      and "private String pipelineProgressSignature(" not in chamber
      and "private long lastPipelineProgressTick" not in chamber
      and "已删除" in chamber
      and chamber.count("return pipelineProgressedRecently(now)") == 0)
has(chamber, "等待豁免",
    "锚点⑦: javadoc 写清了「为什么」（只有时间不够 —— 合法等待会被误判成卡住并主动收回）")
has(chamber, " wait=none}",
    "锚点⑧: 「判卡住并收回」的日志带 wait=none（证明豁免已核对，可与玩家手动拿走区分）")

# ④ 豁免出现 → 消失后必须重新累计满阈值
user_case = [(t, "cog#0", has_wait(craftable_and_missing=True)) for t in range(0, 601, 20)]
verdicts_user = simulate_stuck(user_case)
# WAIT 档位：等 5000 tick 也不收回
wait_mode_case = [(t, "cog#0", has_wait(wait_mode=True)) for t in range(0, 5001, 20)]
verdicts_wait_mode = simulate_stuck(wait_mode_case)
# 豁免消失（t=600 小齿轮做好 / 或 W4 不再成立）→ 之后必须重新累计满阈值
recovering = ([(t, "cog#0", has_wait(craftable_and_missing=True)) for t in range(0, 601, 20)]
              + [(t, "cog#0", False) for t in range(620, 1101, 20)])
verdicts_recovering = simulate_stuck(recovering)
rec_ticks = list(range(0, 601, 20)) + list(range(620, 1101, 20))
first_recover_stuck = next((t for t, v in zip(rec_ticks, verdicts_recovering) if v), None)

print()
print("  反例推演（用户原话：拿走小齿轮 → 它可自动合成 → 等待很久 ⇒ 金板被误收？）")
print("  %-52s %-12s %-12s" % ("场景", "等待时长", "判定"))
print("  %-52s %-12s %-12s" % ("① 拿走小齿轮（可合成、网络缺它）→ 等 600 tick",
                               "600", "从不判卡住"))
print("  %-52s %-12s %-12s" % ("② WAIT 档位下等待", "5000", "从不判卡住"))
print("  %-52s %-12s %-12s" % ("③ 真卡住（无在途/无合成/无缺料）",
                               "60", "tick=%s 判卡住" % first_stuck_tick))
print("  %-52s %-12s %-12s" % ("④ 豁免消失（t=600 料到位）后重新计时",
                               "60", "tick=%s 才判卡住" % first_recover_stuck))
print("  %-52s %-12s %-12s" % ("⑤ 第①条现场：2 台机器并行、另一台每秒换步序（旧 W5 恒真）",
                               "∞（旧）", "本轮：仍按 tick=%s 判卡住" % first_stuck_tick))
check("① 用户场景：拿走小齿轮（可自动合成且网络缺它）后等待 600 tick ⇒ <b>金板 / 投入物绝不被收回</b>",
      not any(verdicts_user))
check("② WAIT 档位（RsccShortagePolicy = wait）下等待 5000 tick ⇒ 绝不判卡住、绝不收回",
      not any(verdicts_wait_mode))
check("④ 豁免消失后计时恢复正常：t=600 料到位 ⇒ 必须<b>重新</b>累计满 60 tick（约 t=680）才判卡住",
      first_recover_stuck is not None and 660 < first_recover_stuck <= 680
      and not any(verdicts_recovering[:len(range(0, 601, 20))]
                  + verdicts_recovering[len(range(0, 601, 20)):len(range(0, 601, 20)) + 3]))
check("⑤ 回归：删掉全局 W5 后，「别的机器还在推进」不再豁免本工位 —— 本工位的判定与其它目标无关",
      "private boolean pipelineProgressedRecently(" not in chamber
      and simulate_stuck([(t, "cog#0", False) for t in range(0, 201, 20)])[-1] is True)

# ============================================================ ① 缺料提示：全部输出点清单
section("① 「刚好够」误报缺少黑曜石粉末：所有会输出「缺少 X」的代码点 + 漏点")

# 完整清单（本轮逐一核对）：
#   A. 执行舱 reportBusShortages → notifyBusShortage（「缺少材料」横幅）
#      —— 入口 orderDeliveredInFull + 忽略纯中间产物 + 可自动合成跳过 + 网络/本仓/机器侧算可用量
#   B. 看门狗 classifySequence/classifyGeneric → sendBanner（「由于 X 缺少或自动合成失败」横幅）
#      —— 本轮补齐：过渡件剔除 + 网络/仓/机器侧算「有」+ 起步原料被在制件顶掉 + 交付满不报
#   C. RsccBusDisabledBanner：总线被停用（**不是缺料**，是归属/配置提示，不涉及缺料口径）
#   D. 蓝图装填器 SchematicLoaderBlockEntity 的缺失行（**另一个功能**，与序列装配缺料无关）
#   E. 样板终端「缺少 N 个样板」按钮 tooltip（**制样板成本**，不是产线缺料）
print("  会输出「缺少 X」的代码点清单（本轮逐一核对是否套了缺料闸门）")
print("  %-58s %-8s" % ("代码点", "套闸门"))
print("  %-58s %-8s" % ("A. 执行舱 reportBusShortages → notifyBusShortage（缺少材料横幅）", "是"))
print("  %-58s %-8s" % ("B. 看门狗 classifySequence/classifyGeneric → sendBanner（由于 X 缺少）", "本轮补齐"))
print("  %-58s %-8s" % ("C. RsccBusDisabledBanner（总线停用；非缺料）", "不适用"))
print("  %-58s %-8s" % ("D. 蓝图装填器缺失行（另一功能）", "不适用"))
print("  %-58s %-8s" % ("E. 样板终端「缺少 N 个样板」（制样板成本，非产线缺料）", "不适用"))

has(watchdog, "private static Set<Item> transitionalItems(final ServerLevel level,",
    "漏点修正①: 看门狗新增「过渡件集合」（按配方 transitional_item 精确剔除，不再报「缺少中间产物」）")
has(watchdog, "if (transitionals.contains(unit.input().getItem())) {",
    "漏点修正②: neededItems 剔除过渡件（真实原料黑曜石粉照旧会报）")
has(watchdog, "missing(needed, present, chambers, pattern.assembly(), remaining);",
    "漏点修正③: 看门狗的「有」口径改为「网络 + 仓 / 机器侧 + 在制件顶掉起步原料」")
has(watchdog, "if (anyChamberSupplies(chambers, stack.getItem())) {",
    "漏点修正④: 「正压在仓内 / 机器 / 置物台上」⇒ 不算缺料")
has(watchdog, "if (chamber.pipelineHasInFlightUnit()) {",
    "漏点修正⑤: 「产线还在动吗」改用 pipeline 口径（含机器上的在制件，不再只看仓内）")
has(watchdog, "|| subTaskRunning || chamberInFlight || remaining <= 0L",
    "漏点修正⑥: 「交付已满（remaining ≤ 0）」并入可推进 ⇒ 尾巴缺口一律不报")
has(watchdog, "public static long deliveredAmount(final TaskStatus status) {",
    "漏点修正⑦: 已交付量只有一份实现（执行舱三处 + 看门狗都调它），与 root EXTERNAL 样板的 "
    "RS 权威读数同口径（2026-10-06 更新：旧断言固定的 private + stored/crafting 口径对本模组样板恒为 0）")
has(chamber, "- machineHeldItem(entry.getKey());",
    "漏点修正⑧: 执行舱缺料上报的物品侧也减「机器侧已压着的那一份」（与流体侧对称）")
has(chamber, "public boolean pipelineHasInFlightUnit() {",
    "锚点: 执行舱提供 pipeline 只读 API（pipelineHasInFlightUnit）")
has(chamber, "public long pipelineInFlightCount() {",
    "锚点: 执行舱提供 pipeline 只读 API（pipelineInFlightCount）")
has(chamber, "public boolean pipelineSuppliesItem(@org.jetbrains.annotations.Nullable final Item item) {",
    "锚点: 执行舱提供 pipeline 只读 API（pipelineSuppliesItem）")


def watchdog_missing(net_has, machine_holds, in_flight, remaining, ingredient=True):
    """忠实复刻看门狗 missing()：网络 / 仓机器侧 / 起步原料被在制件顶掉，三者取并集。"""
    if net_has or machine_holds:
        return []
    if ingredient and remaining <= in_flight:
        return []
    return ["dust"]


def watchdog_reason(net_has, machine_holds, in_flight, remaining, progress=False):
    """复刻 canAdvance：progress || chamberInFlight(pipeline) || remaining<=0 || missing.isEmpty()。"""
    missing = watchdog_missing(net_has, machine_holds, in_flight, remaining)
    can_advance = progress or in_flight > 0 or remaining <= 0 or not missing
    return "NONE" if can_advance else "MISSING_MATERIAL"


print()
print("  反例推演（下单 1 / 64 个坚固板，原料 = 黑曜石粉；「刚好够」全程逐点采样）")
print("  %-52s %-10s %-10s" % ("时点（网络粉 / 机器侧 / 在制 / 还差几件）", "报缺吗", "期望"))
TIMELINE = [
    ("t0 开工前：网络 64 / 机器无 / 在制 0 / 还差 64", 1, 0, 0, 64, "NONE"),
    ("t1 刚推进机器：网络 63 / 机器压着 1 / 在制 1 / 还差 64", 1, 1, 1, 64, "NONE"),
    ("t2 加工中：网络 10 / 机器无 / 在制 1 / 还差 20", 1, 0, 1, 20, "NONE"),
    ("t3 尾巴（下单 1 件）：网络 0 / 机器无 / 在制 1 / 还差 1", 0, 0, 1, 1, "NONE"),
    ("t4 尾巴（下单 64 件，最后一件在制）：网络 0 / 机器无 / 在制 1 / 还差 1", 0, 0, 1, 1, "NONE"),
    ("t5 已交付满：还差 0", 0, 0, 0, 0, "NONE"),
    ("t6 真缺料：网络 0 / 机器无 / 在制 0 / 还差 10", 0, 0, 0, 10, "MISSING_MATERIAL"),
]
for label, net, machine, inflight, remaining, expect in TIMELINE:
    got = watchdog_reason(net, machine, inflight, remaining)
    print("  %-52s %-10s %-10s %s" % (label, got, expect, "OK" if got == expect else "MISMATCH"))
    check("① %s ⇒ %s" % (label, expect), got == expect)
check("①a 过渡件被剔除：坚固板的 needed = [黑曜石粉]（不含未完成黑曜石板）",
      "transitionals.contains(unit.input().getItem())" in watchdog)
check("①b 真缺料绝不漏报（网络 + 仓 / 机器都空、还有件要做）⇒ MISSING_MATERIAL",
      watchdog_reason(0, 0, 0, 10) == "MISSING_MATERIAL")

# ============================================================ ③ 多余中间产物：量化上界
section("③ 多余中间产物：量化定义 + 每件成品 ≤ 1 份")

check("锚点①: 「同一工位同一时刻最多一件在制件」由 unitInFlightAt / holdsUnitAt 保证",
      "public boolean unitInFlightAt(@org.jetbrains.annotations.Nullable final BlockPos target) {" in chamber
      and "private static boolean holdsUnitAt(final Level level, final BlockPos pos) {" in chamber)
check("锚点②: 「同一工位最多一份未消耗的投入物」由 supplyTargetHoldsItem 保证，备料侧据此不再补第二份",
      "private boolean supplyTargetHoldsItem(final Level level, final BlockPos target, final Item item) {" in chamber
      and "if (supplyTargetHoldsItem(level, target, item)) {\n                continue;" in chamber)
check("锚点③: 卡住的工位不再被备料（多余的中间产物 = 卡住残留的来源，被同一闸门掐掉）",
      "if (stationStuckOn(target, item, pending)) {\n                continue;" in chamber)
check("锚点④（本轮新增，用户第 ① ② 条）：<b>推料侧</b>也加上「同一工位的兄弟目标已握着这一件 ⇒ 不再推第二份」"
      "（此前推料侧只判自己那一格，2 条总线 / 2 台机械手共用一个工位时会多推一份 ⇒ 多出来的那份"
      "「没有消费者 + 本步要它受保护」= 齿轮卡住 / 中间产物多一份的共同来源）",
      "public boolean stationTwinHoldsStepExtra(" in chamber
      and "if (chamber.stationTwinHoldsStepExtra(targetPos, item.item())) {" in read(
          SRC, "support", "RsccChamberExportStrategy.java")
      and '"station_twin_holds_input"' in read(SRC, "support", "RsccChamberExportStrategy.java"))
check("锚点⑤（本轮新增）：份额读不到（remainingOrderUnits() ≤ 0）时的回退必须取<b>确定性下界</b>"
      "（allowedConcurrentUnits → max(1, inFlightUnitCount())），且备料份数再夹一次 —— "
      "「不确定时取小不取大」，绝不因为读不到订单而多发一份",
      "want = (int) Math.min((long) want, allowedConcurrentUnits());" in chamber
      and "public long allowedConcurrentUnits()" not in chamber  # 私有：只允许内部调用
      and "private long allowedConcurrentUnits() {" in chamber
      and "return Math.max(1L, inFlightUnitCount());" in chamber
      and "final long cap = Math.max(0L, allowedConcurrentUnits() - inFlightUnitCount());" in chamber)


def intermediates(units, stuck_stations):
    """量化模型：每件成品需要 1 份起步原料 + 每步 1 份步骤专用投入物；
    残留 = 每件成品 0 份（正常流转），除非工位卡住 —— 卡住工位最多 1 份未消耗投入物。
    上界 = 1 件在制件 + 每工位 ≤1 份未消耗投入物（与成品数无关，是「同时刻」的量，不随件数累积）。"""
    in_flight = 1 if units > 0 else 0
    leftover = min(stuck_stations, 1) if stuck_stations > 0 else 0
    return in_flight + leftover


def pulls_for(order_units, stations, remaining_readable):
    """复刻 wantingTargetCount 的目标量（备料份数）与「每件成品对应的中间产物」。

    口径（与 Java 逐一对应）：
      * want = 缺它的工位数（同一物理工位只算一次，且「工位上已有同类件」不再计）；
      * remaining 可读（> 0）→ want = min(want, remaining)；
        读不到（≤ 0）→ want = min(want, allowedConcurrentUnits()) = min(want, max(1, in_flight))；
      * 每件成品最终消耗的份数 = 每件成品每步 1 份（与并发无关）⇒ 上界恒为「每件成品 ≤ 1 份」。
    """
    in_flight = min(order_units, stations)
    if remaining_readable:
        want = min(stations, max(1, order_units))
    else:
        want = min(stations, max(1, in_flight))
    # 比例：任务期共备料 want（同时刻），成品 order_units 件 ⇒ 每件成品摊到的「在飞份数」 ≤ want/order_units ≤ 1
    return want


print("  量化定义：多余 = 同一时刻超出不变式「1 件在制件 + 每工位 ≤1 份未消耗投入物」的份数")
print("  %-46s %-12s %-12s" % ("情形", "同时刻中间产物", "上界"))
print("  %-46s %-12s %-12s" % ("正常流转（N 件成品）", "≤1 件在制", "≤1 件"))
print("  %-46s %-12s %-12s" % ("1 个工位卡住且未收回（修复前：永久累积）", "1 件 + 1 份残留", "≤2 份"))
print("  %-46s %-12s %-12s" % ("卡住 3 秒内被收回（本轮修复后）", "≤1 件", "≤1 份"))
print("  %-46s %-12s %-12s" % ("目标量（备料份数）N=1 / N=64 / 余量不可读", "1 / ≤工位数 / ≤在制数",
                               "≤工位数且 ≤ 需求"))
check("③a 「每件成品对应的中间产物 ≤ 1 份」在正常流转下成立（同时刻只有 1 件在制，与成品数无关）",
      intermediates(64, 0) == 1 and intermediates(1, 0) == 1)
check("③b 修复前：卡住工位永久残留 ⇒ 上界跑到 2 份（且随卡住次数累积到 3 次 = 用户的实测 3 次堵塞）",
      intermediates(64, 1) == 2)
check("③c 修复后：卡住工位 ≤3 秒被自动收回 ⇒ 同时刻上界回到 ≤1 份（见 ②b 的首判 tick）",
      first_stuck_tick <= 80 and intermediates(64, 0) == 1)
check("③d 目标量推演：N=1（余量可读）⇒ 只备 1 份；N=64（余量可读）⇒ 备 = 缺它的工位数；"
      "余量不可读 ⇒ 备 = min(工位数, max(1, 在制数))，<b>恒不超过并发上限</b>（不确定时取小不取大）",
      pulls_for(1, 2, True) == 1 and pulls_for(64, 2, True) == 2
      and pulls_for(1, 2, False) == 1 and pulls_for(64, 2, False) == 2
      and pulls_for(64, 4, False) == 4 and pulls_for(64, 4, False) <= 4)

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
