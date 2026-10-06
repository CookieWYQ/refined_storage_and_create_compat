# -*- coding: utf-8 -*-
"""自检（第 42 轮 · 总样板改绑入口改为「右键空气」，并补上「没有目标方块之后网络从哪来」）。

用法：python tools/selfcheck_round42_air_rebind_entry.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

上游证实的根因（本自检把它钉成断言，防止有人再把入口放回方块交互）
------------------------------------------------------------------
MC 1.21.1 `ServerPlayerGameMode#useItemOn` 的顺序是
`BlockState#useItemOn` → **`BlockState#useWithoutItem`** → `ItemStack#useOn`。
`SequenceExecutionChamberBlock` **没有重写 `useItemOn`**，于是默认实现直接进
`useWithoutItem`（那里**不检查手持物**，`openMenu` 后返回 `sidedSuccess`＝动作已被消费），
`ItemStack#useOn` **永远不被调用** ⇒ 上一版「手持总样板右键执行舱」的改绑分支是**死代码**。

用户拍板的新入口：**手持总样板 → 右键空气（不按 Shift）⇒ 打开「总样板机器绑定」界面**。
右键方块那条入口**不必保留**（且它本来就是死代码），已删除；Shift 的既有语义
（还原成空白 RS 样板）**逐字不变**。

本自检钉住六件事
----------------
T1 触发真值表：非 Shift 右键空气 ⇒ 打开界面；Shift（空气或方块）⇒ 还原空白样板；
   非 Shift 右键方块 ⇒ PASS（**不再**走改绑）；
T2 `SampleItemConversions` 不抢右键空气：非 Shift 时它直接 `pass`（因此新入口不与它冲突）；
T3 根因有据可查：执行舱方块没有 `useItemOn` 重写，`useWithoutItem` 不看手持物；
T4 界面打开前的**每一条拒绝路径**（空白样板 / 无步骤 ⇒ 不开；附近没有接入网络的执行舱 ⇒ 不开；
   网络里没有已配置执行舱 ⇒ 不开）都发生在发包之前，且都有明确提示；
T5 机器候选来自「本网络内的执行舱」，并按**链**去重（复用既有 `chainIdentity()`，
   不新造第二套链推导）；网络本身复用 `UnitPatternDedupe.networkOf`；
T6 文案键中英成对、中文 ≤40 字、不含禁用措辞。
"""

from __future__ import annotations

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
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
PREFIX = "gui.rs_create_compat.assembly_pattern_rebind."

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def read(*parts):
    with io.open(os.path.join(*parts), "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(source):
    """去掉 /* */ 与 // 注释：断言「代码里没有某样东西」时不被注释字面量干扰。"""
    out = []
    index = 0
    length = len(source)
    while index < length:
        if source.startswith("/*", index):
            stop = source.find("*/", index + 2)
            index = length if stop < 0 else stop + 2
            continue
        if source.startswith("//", index):
            stop = source.find("\n", index)
            index = length if stop < 0 else stop
            continue
        out.append(source[index])
        index += 1
    return "".join(out)


def body(source, anchor, end=None):
    """取 anchor 之后的片段（到 end 或文件末尾）：用于「某方法体内」的断言。"""
    index = source.find(anchor)
    if index < 0:
        return ""
    index += len(anchor)
    rest = source[index:]
    if end is not None:
        stop = rest.find(end)
        if stop >= 0:
            return rest[:stop]
    return rest


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


# ======================================================================
# 与 Java 逐字同构的两个决策模型（真值表用）
# ======================================================================
def entry_decision(shift, air):
    """`SequenceAssemblyPatternItem#use / #useOn` 的分支复刻。

    返回 'revert'（还原成空白 RS 样板）/ 'open_rebind'（打开改绑界面）/ 'pass'（不管）。
    """
    if shift:
        return "revert"                       # use 与 useOn 的 Shift 分支都走 SampleItemConversions
    return "open_rebind" if air else "pass"   # 只有「右键空气」是非 Shift 下的改绑入口


def open_decision(has_pattern, blank_or_no_step, network, candidates):
    """`AssemblyPatternRebind#openFor` 的决策复刻（先判后发，每一步都拒绝 + 提示）。"""
    if not has_pattern:
        return "ignore"                       # 手上不是总样板：什么都不做
    if blank_or_no_step:
        return "refuse:empty_pattern"         # 空白样板 / 没有任何步
    if network is None:
        return "refuse:no_chamber_near"       # 附近一台执行舱都没有 / 都没接入网络
    if candidates <= 0:
        return "refuse:no_chamber"            # 网络里没有「配好名字 + 配方类型」的执行舱
    return "open"


def chamber_entries(chambers):
    """`AssemblyPatternRebind#chamberEntries` 的复刻（按链去重）。

    入参 `chambers`：按坐标升序的 `(chain_identity, configured, recipe_type)` 三元组
    （与 `UnitManagerSources.chambers` 的升序一致）；返回保留下来的下标表。
    """
    seen = set()
    kept = []
    for index, (identity, configured, recipe_type) in enumerate(chambers):
        if not configured or not recipe_type:
            continue                          # 没配好的机器接不了任何样板
        if identity in seen:
            continue                          # 同一条链的别的成员：同一个逻辑执行仓
        seen.add(identity)
        kept.append(index)
    return kept


def main():
    item = read(SRC, "item", "SequenceAssemblyPatternItem.java")
    rebind = read(SRC, "item", "AssemblyPatternRebind.java")
    conversions = read(SRC, "item", "SampleItemConversions.java")
    chamber_block = read(SRC, "block", "SequenceExecutionChamberBlock.java")
    open_packet = read(SRC, "network", "AssemblyPatternRebindOpenPacket.java")

    use_air = body(item, "public net.minecraft.world.InteractionResultHolder<ItemStack> use(",
                   "public net.minecraft.world.InteractionResult useOn(")
    use_on = body(item, "public net.minecraft.world.InteractionResult useOn(",
                  "// ---------- RS PatternProviderItem")

    # ---------------- T1 触发真值表 ----------------
    section("T1 触发：非 Shift 右键空气 ⇒ 打开界面；Shift ⇒ 还原；非 Shift 右键方块 ⇒ 不再改绑")
    check("锚点① use() 里：Shift 走 SampleItemConversions.use，非 Shift 只在服务端调 openFor(player, hand)",
          "if (player.isShiftKeyDown()) {" in use_air
          and "return SampleItemConversions.use(level, player, hand);" in use_air
          and "AssemblyPatternRebind.openFor(serverPlayer, hand);" in use_air
          and "AssemblyPatternRebind.openFor(serverPlayer, context.getHand()" not in item)
    check("锚点② 打开界面不带任何方块参数（没有目标方块也能打开）",
          "openFor(serverPlayer, hand);" in use_air
          and "getClickedPos()" not in use_air)
    check("锚点③ 客户端也返回 sidedSuccess（手会挥一下；界面由服务端 S2C 打开）",
          "net.minecraft.world.InteractionResultHolder.sidedSuccess(" in use_air
          and "player.getItemInHand(hand), level.isClientSide());" in use_air)
    check("锚点④ useOn 只剩 Shift 还原 + PASS：不再有「目标方块是执行舱」的分支",
          "if (player.isShiftKeyDown()) {" in use_on
          and "return SampleItemConversions.useOn(context.getLevel(), player, context.getHand());" in use_on
          and "AssemblyPatternRebind" not in use_on
          and "SequenceExecutionChamberBlock" not in use_on
          and "return net.minecraft.world.InteractionResult.PASS;" in use_on)
    check("锚点⑤ Shift 的两条路径共用同一份实现（语义一字不变）",
          "public static InteractionResultHolder<ItemStack> use(" in conversions
          and "public static InteractionResult useOn(" in conversions)

    section("真值表：输入（Shift？ 空气？）⇒ 行为")
    table = [
        ((False, True), "open_rebind", "非潜行 · 右键空气 ⇒ 开界面"),
        ((True, True), "revert", "潜行 · 右键空气 ⇒ 还原空白样板（现状）"),
        ((True, False), "revert", "潜行 · 右键方块 ⇒ 还原空白样板（现状）"),
        ((False, False), "pass", "非潜行 · 右键方块 ⇒ PASS（不再走改绑）"),
    ]
    for (shift, air), expected, title in table:
        got = entry_decision(shift, air)
        check("① %s" % title, got == expected, "got=%s" % got)

    # ---------------- T2 不与 SampleItemConversions 抢 ----------------
    section("T2 SampleItemConversions 不抢右键空气（非 Shift 时直接 pass，不消费动作）")
    check("锚点① 非 Shift ⇒ InteractionResultHolder.pass(held)（因此新入口拿得到这次右键）",
          "if (!player.isShiftKeyDown()) {" in conversions
          and "return InteractionResultHolder.pass(held);" in conversions)

    # ---------------- T3 根因有据可查 ----------------
    section("T3 根因：执行舱方块没有 useItemOn 重写 ⇒ useWithoutItem 先消费掉动作")
    check("锚点① 执行舱方块里没有 useItemOn 重写（默认实现会先进 useWithoutItem）",
          "public InteractionResult useItemOn(" not in chamber_block
          and "InteractionResult useItemOn(" not in chamber_block)
    check("锚点② useWithoutItem 不看手持物（只 openMenu + sidedSuccess）",
          "player.openMenu(state.getMenuProvider(level, pos), pos);" in chamber_block
          and "getItemInHand" not in body(chamber_block, "protected InteractionResult useWithoutItem(",
                                           "@Nullable\n    @Override\n    protected net.minecraft.world.MenuProvider"))
    check("锚点③ 触发文案里说明了这条根因（后人不会再把入口放回方块交互）",
          "useWithoutItem" in item and "1.21.1" in item)

    # ---------------- T4 先判后发 ----------------
    section("T4 每一条拒绝路径都发生在发包之前，且都有明确提示")
    open_body = body(rebind, "public static void openFor(final ServerPlayer player, final InteractionHand hand,\n"
                             "                               @Nullable final SequenceExecutionChamberBlockEntity anchor)",
                     "// ==================== 写入：界面确认后的一次性改绑 ====================")
    send_at = open_body.find("PacketDistributor.sendToPlayer(")
    check("锚点① 空白样板 / 没有任何步 ⇒ empty_pattern，不发包",
          "assembly == null || assembly.units().isEmpty()" in open_body
          and "LANG + \"empty_pattern\"" in open_body
          and 0 <= open_body.find("LANG + \"empty_pattern\"") < send_at)
    check("锚点② 取不到网络 ⇒ no_chamber_near，不发包",
          "LANG + \"no_chamber_near\"" in open_body
          and 0 <= open_body.find("LANG + \"no_chamber_near\"") < send_at)
    check("锚点③ 网络里没有候选执行舱 ⇒ no_chamber，不发包",
          "chambers.isEmpty()" in open_body
          and "LANG + \"no_chamber\"" in open_body
          and 0 <= open_body.find("LANG + \"no_chamber\"") < send_at)
    check("锚点④ 全部通过后才发包（sendToPlayer 在三条拒绝之后）",
          send_at > 0
          and send_at > open_body.find("LANG + \"empty_pattern\"")
          and send_at > open_body.find("LANG + \"no_chamber_near\"")
          and send_at > open_body.find("LANG + \"no_chamber\""))
    check("锚点⑤ 上一轮的边界仍然保持：找不到该步单元样板 / 槽位不足 / 指纹不符都还拒绝",
          "LANG + \"no_unit\"" in rebind and "LANG + \"no_slot\"" in rebind
          and "LANG + \"stale\"" in rebind and "!current.equals(packet.patternId())" in rebind)

    section("真值表：openFor 的决策（全部拒绝都不发界面包）")
    decisions = [
        ((False, False, None, 0), "ignore", "手上不是总样板 ⇒ 不动作"),
        ((True, True, None, 0), "refuse:empty_pattern", "空白样板 / 无步骤 ⇒ 拒绝"),
        ((True, False, None, 0), "refuse:no_chamber_near", "附近没有接入网络的执行舱 ⇒ 拒绝"),
        ((True, False, "net", 0), "refuse:no_chamber", "网络里没有已配置执行舱 ⇒ 拒绝"),
        ((True, False, "net", 2), "open", "网络里有候选 ⇒ 打开界面"),
    ]
    for args, expected, title in decisions:
        got = open_decision(*args)
        check("② %s" % title, got == expected, "got=%s" % got)

    # ---------------- T5 网络从哪来 + 链级去重 ----------------
    section("T5 没有目标方块后：网络取自「玩家身边最近的、已接入网络的执行舱」")
    nearby = body(rebind, "private static Network nearbyNetwork(final ServerPlayer player) {",
                  "/** 两坐标之间的欧氏距离平方")
    check("锚点① 只读扫描已加载区块，绝不因取网络而加载区块",
          "level.isLoaded(pos)" in nearby
          and "player.serverLevel()" in nearby
          and "private static final int NEARBY_RADIUS = 8;" in rebind)
    check("锚点② 结果确定：先按距离平方、再按坐标字典序排序",
          "distanceSq(center, chamber.getBlockPos())" in nearby
          and ".thenComparingLong(chamber -> chamber.getBlockPos().asLong())" in nearby)
    check("锚点③ 网络解析复用既有口径 UnitPatternDedupe.networkOf（不自己走网络图）",
          "UnitPatternDedupe.networkOf(chamber)" in nearby
          and "GraphNetworkComponent" not in strip_comments(rebind))
    check("锚点④ 显式给定执行舱时（写入后的权威刷新）用它的网络，不再按玩家位置猜",
          "anchor == null ? nearbyNetwork(player) : UnitPatternDedupe.networkOf(anchor)" in open_body
          and "openFor(player, hand, target);" in rebind)
    check("锚点⑤ 候选机器 = 本网络内的执行舱（复用 UnitManagerSources.chambers）",
          "UnitManagerSources.chambers(network)" in strip_comments(rebind))

    section("T5·2 候选按链去重（同一条链 = 同一个逻辑执行仓）")
    entries_body = body(rebind, "private static List<SyncChamberListPacket.Entry> chamberEntries(",
                        "// ==================== 只读小工具 ====================")
    check("锚点① 去重键是既有 chainIdentity()（链首坐标），不新造第二套链推导",
          "chamber.chainIdentity()" in entries_body
          and "chainHead(" not in strip_comments(rebind)
          and "winningPredecessor" not in strip_comments(rebind)
          and "collectUpstream" not in strip_comments(rebind))
    check("锚点② 只保留每一条链的第一台（seenChains.add 失败即跳过）",
          "final Set<BlockPos> seenChains = new LinkedHashSet<>();" in entries_body
          and "if (!seenChains.add(chamber.chainIdentity())) {" in entries_body)
    check("锚点③ 未配置（没名字 / 没配方类型）的机器仍不给它当候选",
          "!chamber.isProperlyConfigured()" in entries_body and "type.isEmpty()" in entries_body)

    section("真值表：候选按链去重（输入按坐标升序，元素 = 链身份 / 是否配好 / 配方类型）")
    chains = [
        ("A", True, "create:pressing"),
        ("A", True, "create:pressing"),
        ("A", False, ""),
        ("B", True, "create:pressing"),
        ("A", True, "create:pressing"),
        ("C", True, ""),
    ]
    check("① 同链 4 台 + 另一条链 1 台 ⇒ 只出 2 个条目（台数 → 逻辑执行仓数）",
          chamber_entries(chains) == [0, 3],
          str(chamber_entries(chains)))
    check("② 未配置的机器（空配方类型）一个都不进候选",
          all(chains[i][1] and chains[i][2] for i in chamber_entries(chains)))
    check("③ 同链成员被另一条链的成员隔开时，只保留先遇到的那一台（结果与顺序确定）",
          chamber_entries([("A", True, "t"), ("B", True, "t"), ("A", True, "t")]) == [0, 1])
    check("④ 空网络 ⇒ 空候选（调用方据此拒绝，不发空界面）",
          chamber_entries([]) == [])

    # ---------------- T6 文案 ----------------
    section("T6 文案键中英成对、中文 ≤40 字、无禁用措辞")
    with io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8") as handle:
        zh = json.load(handle)
    with io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8") as handle:
        en = json.load(handle)
    zh_keys = set(key for key in zh if key.startswith(PREFIX))
    en_keys = set(key for key in en if key.startswith(PREFIX))
    check("锚点① 中英键集合完全一致（且新增的两个键都在）",
          zh_keys == en_keys and (PREFIX + "no_chamber") in zh_keys
          and (PREFIX + "no_chamber_near") in zh_keys,
          "zh=%d en=%d" % (len(zh_keys), len(en_keys)))
    check("锚点② 中文文案 ≤40 字",
          all(len(zh[key]) <= 40 for key in zh_keys),
          str([key for key in zh_keys if len(zh[key]) > 40]))
    forbidden = ("\u6216", "\u7b49 %s \u79cd", "\u56fe\u6807\u8f6e\u6362")
    check("锚点③ 中文文案不含禁用措辞（「或」/「等 N 种」/「图标轮换」）",
          not any(word in zh[key] for key in zh_keys for word in forbidden))
    check("锚点④ 服务端新提示确实引用了这两个键（不是只加进语言文件）",
          "LANG + \"no_chamber_near\"" in rebind and "LANG + \"no_chamber\"" in rebind)

    # ---------------- 界面复用（只换入口，不重做界面） ----------------
    section("T7 只换入口：界面与 S2C/C2S 结构未重做")
    check("锚点① S2C 快照结构不变（步表 + 机器表）",
          "List<Step> steps," in open_packet and "List<SyncChamberListPacket.Entry> chambers)" in open_packet)
    check("锚点② 服务端写入路径仍是唯一一处（apply 由 C2S 处理体委托）",
          "cretae.cookiewyq.rs_create_compat.item.AssemblyPatternRebind.apply(player, packet);"
          in read(SRC, "network", "AssemblyPatternStepMachinePacket.java"))

    print()
    print("=" * 78)
    if FAILURES:
        print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
        for entry in FAILURES:
            print("  - %s" % entry)
        return 1
    print("SELFCHECK OK (%d checks)" % CHECKS[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
