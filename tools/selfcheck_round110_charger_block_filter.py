#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 110 轮自检：范围充电器过滤新增「世界中的方块」类别（BLOCKS tab + 服务端过滤生效 + 快照）。

用户口径（原话）
----------------
> 你现在有几个类别玩家身上的（物品）以及掉落物，但是还有一个东西你忘了：世界中的方块，
> 就是放在地上的方块，比如控制器、或者说放在地上的能量立方。这个东西你并没有列出来，
> 这个类别也是很重要的。

落点（一句话）
--------------
方块充电功能本来就有（`Config.rangeChargerChargeBlocks` + `scanBlocks/scanBlocksInfinite/chargeBlock`），
缺的是**第四个类别**：`ChargingFilter` 多一份 `blocks` 名单 + 一个 `allowsBlock(Block)` 判定出口，
`chargeBlock` 真的调它；服务端顺手把「范围内检测到的可充方块」收集成有界候选池随快照下发，
界面多一个 BLOCKS 页签（候选 ∪ 已配置、图标用方块物品、名字走 PinyinSearch、id 走小写子串）。

断言清单
--------
① `ChargingFilter`：四类名单都在；`TAG_BLOCKS`；`allowsBlock` 与物品两条**共用同一个**私有
   `allows()`（`whitelist ? hit : !hit` 全类仍只有 2 次：`allows()` 与 `allowsPlayer()`）；
   `of(...)` 六参 + 五参重载（= 方块名单为空，既有调用方与第 99 轮探针不受影响）；
   `addBlocks` / `readBlocks` 都卡 `MAX_BLOCK_ENTRIES`；save/load 双向键名一致；
   `copyFrom` 复制方块名单；`sortedBlocks()` 供组包。
② `ChargerFilterLimits`：`MAX_BLOCK_ENTRIES` 存在且与物品上限同量级；仍**零 MC 依赖**
   （它被纯 JDK 探针 / 自检直接编译执行的前提）。
③ `RangeChargerBlockEntity`：`chargeBlock` 里 **先登记候选池、再判过滤、再满电跳过、再预算、再充电、
   最后才登记高亮**（顺序判据 —— 被拦住的方块「连目标都不算」）；候选池 `seenBlocks` 有界、
   每 tick 在 `doCharging` 里重建（且受方块充电总闸约束）；两类扫描仍调 `chargeBlock`；
   快照真的带上候选池；既有语义（满电跳过 / transferBudget / 只扣对方接受量 / 高亮）一字未动。
④ 网络：Sync 包 record 多 `blocks`/`seenBlocks`，编码 clamp、解码「读完声明条数、只保留前 N、
   不 break、不预分配」；Set 包复用同一份编解码（不允许两包各写一份）；`handle` 把 `blocks`
   交给 `ChargingFilter.of`；工厂 `of` 里带 `filter.sortedBlocks()`。
⑤ 界面：`Tab.BLOCKS` 存在；页签 4 个且**装得进面板**（单签压到 56）；`blocks` 勾选集合、
   isChecked 用**方块注册名**判定；行 = 候选池 ∪ 已配置（否则拉黑后取消不了）；图标用
   `new ItemStack(block.asItem())`、`asItem` 是空气则跳过；搜索**名字走 PinyinSearch**、
   id 走小写子串（与物品页同一口径）；发送 / tooltip / 空态文案 / 搜索提示齐全。
⑥ 语言键 6 条 zh/en 成对、非空、zh 有中文、en 无中日韩、`row.block.id` 带 `%s`。
⑦ 行为模型（纯 Python 孪生）：方块名单的黑/白名单真值表 4 组合 × 空/命中/未命中，且与物品 /
   玩家名单**互不串台**（同一 id 在物品名单里命中不影响方块判定）。
⑧ 反例自证（9 条）：删门控 / 把候选登记挪到过滤之后 / 去掉 NBT 方块键 / 解码加 break /
   Tab.BLOCKS 删掉 / 搜索不走 PinyinSearch / 方块行改用物品注册表 / en 语言键缺失 / 上限用错常量。

用法：python tools/selfcheck_round110_charger_block_filter.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import json
import os
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = os.path.dirname(os.path.abspath(__file__))
if TOOLS not in sys.path:
    sys.path.insert(0, TOOLS)

PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
SUPPORT = os.path.join(PKG, "support")
NET = os.path.join(PKG, "network")
CLIENT = os.path.join(PKG, "client")
BE = os.path.join(PKG, "block", "entity", "RangeChargerBlockEntity.java")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

FILES = {
    "filter": os.path.join(SUPPORT, "ChargingFilter.java"),
    "limits": os.path.join(SUPPORT, "ChargerFilterLimits.java"),
    "sync": os.path.join(NET, "SyncChargerFilterPacket.java"),
    "set": os.path.join(NET, "SetChargerFilterPacket.java"),
    "screen": os.path.join(CLIENT, "screen", "ChargerFilterScreen.java"),
    "be": BE,
}

# 本轮新增 / 改动语义的 6 条语言键（必须 zh/en 成对）
NEW_LANG_KEYS = [
    "gui.rs_create_compat.charger_filter.tab.blocks",
    "gui.rs_create_compat.charger_filter.tab.tip.blocks",
    "gui.rs_create_compat.charger_filter.list.empty.blocks",
    "gui.rs_create_compat.charger_filter.list.empty.blocks.search",
    "gui.rs_create_compat.charger_filter.row.block.id",
    "gui.rs_create_compat.charger_filter.search.blocks.hint",
]

JAVAC = "D:/java21/bin/javac.exe"
JAVA = "D:/java21/bin/java.exe"
# javac 默认用系统语言（Windows 上是 GBK 中文）；按 UTF-8 读会乱码，强制它说英文。
JAVAC_EN = ["-J-Duser.language=en", "-J-Duser.country=US"]
PROBE_DIR = os.path.join(ROOT, "build", "selfcheck_round110")

CJK_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")

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


def counterexample(name, ok):
    """反例自证：参数必须是「判据对坏写法判负了」，否则本脚本自身不可信。"""
    COUNTER[0] += 1
    check("反例 " + name, ok)


# ======================================================================================
# 文本工具：去注释 / 取方法体 / 读 int 常量
# ======================================================================================

def strip_comments(text):
    """去掉 // 与 /*...*/ 注释，保留字符串字面量（判「代码里有没有调用 X」时用）。"""
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        two = text[i:i + 2]
        if quote is None and two == "//":
            end = text.find("\n", i)
            end = n if end < 0 else end
            out.append(" " * (end - i))
            i = end
            continue
        if quote is None and two == "/*":
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out.append("".join("\n" if c == "\n" else " " for c in text[i:end]))
            i = end
            continue
        char = text[i]
        if quote is None and char in "\"'":
            quote = char
        elif quote is not None and char == "\\":
            out.append(text[i:i + 2])
            i += 2
            continue
        elif quote is not None and char == quote:
            quote = None
        out.append(char)
        i += 1
    return "".join(out)


def body_of(text, needle):
    """返回 `needle` 之后第一对花括号里的内容（找不到 ⇒ 空串）。"""
    start = text.find(needle)
    if start < 0:
        return ""
    brace = text.find("{", start + len(needle))
    if brace < 0:
        return ""
    depth = 0
    for i in range(brace, len(text)):
        char = text[i]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[brace + 1:i]
    return ""


def int_const(text, name):
    match = re.search(r"int\s+" + re.escape(name) + r"\s*=\s*([0-9_]+)\s*;", text)
    return None if not match else int(match.group(1).replace("_", ""))


# ======================================================================================
# 判据（返回问题码列表；反例自证靠「问题码出现」证明判负）
# ======================================================================================

def filter_problems(src):
    """① ChargingFilter：第四类名单 + 唯一判定出口 + NBT + 五参兼容。"""
    p = []
    filt = strip_comments(src["filter"])
    # 四类名单（掉落物 / 玩家身上物品 / 世界中的方块 / 玩家名字+UUID）
    for needle in ("private final Set<ResourceLocation> droppedItems",
                   "private final Set<ResourceLocation> carriedItems",
                   "private final Set<ResourceLocation> blocks",
                   "private final Set<String> playerNames",
                   "private final Set<UUID> playerIds"):
        if needle not in filt:
            p.append("filter-list-missing:" + needle)
    # NBT 键
    if 'private static final String TAG_BLOCKS = "blocks";' not in filt:
        p.append("filter-nbt-key-missing")
    # 判定出口：allowsBlock 必须委托给同一个私有 allows()（不是另写一套 whitelist 分支）
    if "public boolean allowsBlock(final Block block)" not in filt:
        p.append("filter-allowsblock-missing")
    if "return allows(blocks, idOfBlock(block));" not in filt:
        p.append("filter-allowsblock-not-shared")
    if "BuiltInRegistries.BLOCK.getKey(" not in body_of(filt, "private static ResourceLocation idOfBlock("):
        p.append("filter-idofblock-not-block-registry")
    if filt.count("return whitelist ? hit : !hit;") != 2:
        p.append("filter-whitelist-branch-not-shared")
    # of(...)：六参 + 五参重载
    ofb = body_of(filt, "public static ChargingFilter of(final boolean whitelist,")
    if "addBlocks(filter.blocks, blocks);" not in ofb:
        p.append("filter-of-no-blocks")
    if "return of(whitelist, droppedItems, carriedItems, null, playerNames, playerIds);" not in filt:
        p.append("filter-of5-overload-missing")
    add_blocks = body_of(filt, "private static void addBlocks(")
    if "ChargerFilterLimits.addBounded(target, id," not in add_blocks:
        p.append("filter-addblocks-not-bounded")
    if "MAX_BLOCK_ENTRIES" not in add_blocks:
        p.append("filter-addblocks-wrong-cap")
    # save / load
    if "tag.put(TAG_BLOCKS, idsToTag(blocks));" not in filt:
        p.append("filter-save-no-blocks")
    load_body = body_of(filt, "public static ChargingFilter load(")
    if "readBlocks(tag.getList(TAG_BLOCKS, Tag.TAG_STRING), filter.blocks);" not in load_body:
        p.append("filter-load-no-blocks")
    read_blocks = body_of(filt, "private static void readBlocks(")
    if "MAX_BLOCK_ENTRIES" not in read_blocks:
        p.append("filter-readblocks-wrong-cap")
    if "ChargerFilterLimits.addBounded(" not in read_blocks:
        p.append("filter-readblocks-not-bounded")
    # 缺字段 = 空 = 什么都不拦：读侧必须是 getList（缺键返回空列表），不许 getCompound / getString
    if "getCompound(TAG_BLOCKS)" in filt or "getString(TAG_BLOCKS)" in filt:
        p.append("filter-load-blocks-wrong-getter")
    # copyFrom / 只读视图 / 有序副本
    if "blocks.clear();\n        blocks.addAll(other.blocks);" not in filt:
        p.append("filter-copyfrom-no-blocks")
    if "public Set<ResourceLocation> blocks()" not in filt:
        p.append("filter-blocks-view-missing")
    if "public List<ResourceLocation> sortedBlocks()" not in filt:
        p.append("filter-sortedblocks-missing")
    return p


def limits_problems(src):
    """② ChargerFilterLimits：方块上限 + 仍零 MC 依赖。"""
    p = []
    limits = strip_comments(src["limits"])
    cap = int_const(limits, "MAX_BLOCK_ENTRIES")
    item = int_const(limits, "MAX_ITEM_ENTRIES")
    if cap is None:
        p.append("limits-no-block-cap")
    else:
        if item is None or cap != item:
            # 两类名单的量级必须同级（否则「候选池里的方块永远勾不完」或反之）
            p.append("limits-block-cap-mismatch")
        if cap < 512 or cap > 65536:
            p.append("limits-block-cap-out-of-range")
    if re.search(r"^\s*import\s+(net\.minecraft|net\.neoforged|com\.mojang)", limits, re.M):
        p.append("limits-impure-import")
    for word in ("net.minecraft", "net.neoforged", "BuiltInRegistries", "ItemStack", "Level"):
        if word in limits:
            p.append("limits-impure-ref:" + word)
    return p


def be_problems(src):
    """③ 方块实体：chargeBlock 的过滤门控顺序 + 候选池 + 既有语义未改。"""
    p = []
    be = strip_comments(src["be"])
    sb = body_of(be, "private boolean chargeBlock(")
    if not sb:
        return ["be-chargeblock-missing"]
    gate = sb.find("chargingFilter.allowsBlock(")
    # 第 122 轮：候选登记收口到唯一入口 recordSeenCandidate（判据+黑名单在那里，
    # recordSeenBlock 只剩它一个调用点）；顺序语义不变：候选 → 过滤 → 满电 → 预算 → 充电 → 高亮。
    record = sb.find("recordSeenCandidate(")
    full = sb.find("lastSkippedFullTargets++")
    budget = sb.find("transferBudget()")
    recv = sb.find("storage.receiveEnergy(transfer, false)")
    highlight = sb.find("tickChargedBlocks.add(pos)")
    anchors = (gate, record, full, budget, recv, highlight)
    if min(anchors) < 0:
        p.append("be-chargeblock-anchor-missing")
    else:
        # 候选 → 过滤 → （预算=纯函数）→ 满电跳过（第 124 轮 R-1：simulate 判满需要 transfer，
        # 因此预算的「计算」排到判满之前；不扣电/不登记高亮的口径不变）→ 真充电 → 高亮
        # 注意 recv 的锚点必须是「真收」（false），不能是 R-1 里新增的 simulate（true）。
        if not (record < gate < budget < full < recv < highlight):
            p.append("be-chargeblock-order")
    # 「不许充的方块连目标都不算」：门控必须早于所有副作用（预算 / 取电 / 高亮 / targets++）
    if gate > 0 and "targets++" in sb and sb.find("targets++") < gate:
        p.append("be-chargeblock-gate-after-side-effect")
    for needle in ("Capabilities.EnergyStorage.BLOCK",
                   "energyStorage.extractEnergy(accepted, false)",
                   "lastSkippedFullTargets++",
                   "if (accepted > 0) {"):
        if needle not in sb:
            p.append("be-chargeblock-semantics-lost:" + needle)
    # 候选池：有界 + 每 tick 重建 + 受总闸约束
    if "private final Set<ResourceLocation> seenBlocks = new LinkedHashSet<>();" not in be:
        p.append("be-seenblocks-missing")
    rb = body_of(be, "private void recordSeenBlock(")
    if "ChargerFilterLimits.addBounded(seenBlocks, id," not in rb:
        p.append("be-seenblocks-not-bounded")
    if "ChargerFilterLimits.MAX_BLOCK_ENTRIES" not in rb:
        p.append("be-seenblocks-wrong-cap")
    if "BuiltInRegistries.BLOCK" not in rb or ".getKey(" not in rb:
        p.append("be-seenblocks-not-block-registry")
    df = body_of(be, "private void doCharging(")
    if "if (Config.rangeChargerChargeBlocks) {\n            seenBlocks.clear();" not in df:
        p.append("be-seenblocks-reset-not-gated")
    # 两类扫描仍真的走 chargeBlock（方块充电功能不许被这轮改动碰掉）
    if "chargeBlock(level, pos, entry.getValue())" not in body_of(be, "private int scanBlocks("):
        p.append("be-scanblocks-lost")
    if "chargeBlock(level, pos, entry.getValue())" not in body_of(be, "private int scanBlocksInfinite("):
        p.append("be-scanblocksinfinite-lost")
    # 扫描顺序仍是 玩家 → 掉落物 → 方块（第 102 轮定的语义，别在这轮被顺手改掉）
    if not (df.find("scanPlayers(level") < df.find("scanItems(level")
            < df.find("scanBlocks(level") or not (df.find("scanPlayers(level") >= 0)):
        p.append("be-charging-order-changed")
    # 快照带候选池 + 只读副本
    if "public List<ResourceLocation> getSeenBlocks()" not in be:
        p.append("be-getseenblocks-missing")
    if "getSeenBlocks()" not in body_of(be, "public void sendFilterSnapshot("):
        p.append("be-snapshot-no-seenblocks")
    if "ids.sort(ResourceLocation::compareTo);" not in body_of(be, "public List<ResourceLocation> getSeenBlocks()"):
        p.append("be-seenblocks-not-sorted-copy")
    return p


def net_problems(src):
    """④ 网络包：字段 / 编解码上限 / 两包共用 / handle 不丢字段。"""
    p = []
    sync = strip_comments(src["sync"])
    setp = strip_comments(src["set"])
    header = re.search(r"public record SyncChargerFilterPacket\((.*?)\)\s*implements", sync, re.S)
    if not header:
        return ["sync-record-missing"]
    fields = header.group(1)
    for field in ("List<ResourceLocation> blocks", "List<ResourceLocation> seenBlocks"):
        if field not in fields:
            p.append("sync-record-missing:" + field)
    enc = body_of(sync, "static void encodeBlocks(")
    if "ChargerFilterLimits.clamp(" not in enc or "MAX_BLOCK_ENTRIES" not in enc:
        p.append("sync-encodeblocks-uncapped")
    dec = body_of(sync, "static List<ResourceLocation> decodeBlocks(")
    if "MAX_BLOCK_ENTRIES" not in dec:
        p.append("sync-decodeblocks-uncapped")
    if "break;" in dec:
        # 读一半就停会让后续字段错位（方块名单被当成玩家名读），整包解析成随机数据
        p.append("sync-decodeblocks-breaks-stream")
    if "new ArrayList<>(declared)" in dec or "new ArrayList<>(count)" in dec:
        p.append("sync-decodeblocks-prealloc-by-input")
    if not re.search(r"for\s*\([^)]*i\s*<\s*declared", dec):
        p.append("sync-decodeblocks-count-var")
    for needle in ("encodeBlocks(buf, packet.blocks());",
                   "encodeBlocks(buf, packet.seenBlocks());",
                   "final List<ResourceLocation> blocks = decodeBlocks(buf);",
                   "final List<ResourceLocation> seenBlocks = decodeBlocks(buf);"):
        if needle not in sync:
            p.append("sync-codec-missing:" + needle)
    ofb = body_of(sync, "public static SyncChargerFilterPacket of(")
    if "filter.sortedBlocks()" not in ofb:
        p.append("sync-of-no-blocks")
    if "seenBlocks == null ? List.of() : List.copyOf(seenBlocks)" not in ofb:
        p.append("sync-of-no-seenblocks")
    # Set 包：字段 + 复用同一份编解码（不允许两包各写一份）
    header2 = re.search(r"public record SetChargerFilterPacket\((.*?)\)\s*implements", setp, re.S)
    if not header2:
        p.append("set-record-missing")
    elif "List<ResourceLocation> blocks" not in header2.group(1):
        p.append("set-record-missing-blocks")
    if "SyncChargerFilterPacket.encodeBlocks(buf, packet.blocks());" not in setp:
        p.append("set-encode-not-shared")
    if "SyncChargerFilterPacket.decodeBlocks(buf)" not in setp:
        p.append("set-decode-not-shared")
    handle = body_of(setp, "public static void handle(")
    if "packet.blocks()" not in handle:
        p.append("set-handle-drops-blocks")
    if "ChargingFilter.of(" not in handle:
        p.append("set-handle-no-filter")
    return p


def screen_problems(src):
    """⑤ 界面：BLOCKS 页签全链路（集合 / 行 / 勾选 / 渲染 / 搜索 / 文案）。"""
    p = []
    sc = strip_comments(src["screen"])
    if 'BLOCKS("tab.blocks", "tab.tip.blocks")' not in sc:
        p.append("screen-no-blocks-tab")
    if "private static final int TAB_COUNT = 4;" not in sc:
        p.append("screen-tab-count")
    tab_w = int_const(sc, "TAB_W")
    tab_x = int_const(sc, "TAB_X")
    gap = int_const(sc, "TAB_GAP")
    panel_w = int_const(sc, "PANEL_W")
    if None in (tab_w, tab_x, gap, panel_w):
        p.append("screen-tab-geometry-missing")
    else:
        right = tab_x + 3 * (tab_w + gap) + tab_w
        if right > panel_w - 10:
            p.append("screen-tabs-overflow")
        if tab_w * 4 + gap * 3 > panel_w - 20:
            p.append("screen-tabs-too-wide")
    if "private final Set<ResourceLocation> blocks = new LinkedHashSet<>();" not in sc:
        p.append("screen-no-blocks-set")
    if "copyIds(blocks, packet.blocks());" not in sc:
        p.append("screen-snapshot-no-blocks")
    if "packet.seenBlocks()" not in sc:
        p.append("screen-snapshot-no-seenblocks")
    isc = body_of(sc, "private boolean isChecked(final Row row)")
    if "return blocks.contains(row.blockId());" not in isc:
        p.append("screen-checked-not-by-blockid")
    tbs = body_of(sc, "private boolean toggleBlockSelection(")
    if "ChargerFilterLimits.toggleBounded(blocks" not in tbs:
        p.append("screen-toggle-block-no-bounded")
    if "MAX_BLOCK_ENTRIES" not in tbs:
        p.append("screen-toggle-block-wrong-cap")
    tog = body_of(sc, "private void toggleRow(")
    if "toggleBlockSelection(row.blockId())" not in tog:
        p.append("screen-togglerow-no-block-branch")
    if "fullStatus(ChargerFilterLimits.MAX_BLOCK_ENTRIES)" not in tog:
        p.append("screen-togglerow-block-cap-silent")
    rb = body_of(sc, "private void rebuildBlockRows(")
    if "for (final ResourceLocation id : seenBlocks)" not in rb:
        p.append("screen-rows-no-candidates")
    if "for (final ResourceLocation id : blocks)" not in rb:
        p.append("screen-rows-no-configured")
    if "!seen.contains(id)" not in rb:
        p.append("screen-rows-no-dedupe")
    ab = body_of(sc, "private boolean addBlockRow(")
    if "BuiltInRegistries.BLOCK.get(" not in ab:
        p.append("screen-block-not-from-block-registry")
    if "new ItemStack(block.asItem())" not in ab:
        p.append("screen-block-icon-not-itemstack")
    if "icon.getHoverName().getString()" not in ab:
        p.append("screen-block-name-not-localized")
    if "if (icon.isEmpty())" not in ab:
        p.append("screen-air-item-not-skipped")
    if "PinyinSearch.contains(name, needle)" not in ab:
        p.append("screen-search-name-no-pinyin")
    if "id.toString().toLowerCase(Locale.ROOT)" not in ab:
        p.append("screen-search-id-not-lowercase")
    sf = body_of(sc, "private void sendFilter()")
    if "new ArrayList<>(blocks)" not in sf:
        p.append("screen-send-no-blocks")
    if "renderItem(blockIconOf(" not in sc:
        p.append("screen-render-no-block-icon")
    if 'LANG + "row.block.id"' not in body_of(sc, "private void renderRowTooltip("):
        p.append("screen-tooltip-no-block-id")
    for needle in ('LANG + "list.empty.blocks"', 'LANG + "list.empty.blocks.search"',
                   'tab == Tab.BLOCKS ? "search.blocks.hint" : "search.hint"'):
        if needle not in sc:
            p.append("screen-lang-use-missing:" + needle)
    # 第 107 轮口径没被这一轮改坏：列表只显示名字，id 只进 tooltip
    if 'LANG + "row.id"' not in sc:
        p.append("screen-lost-item-id-tooltip")
    return p


def lang_problems(src):
    """⑥ 6 条新语言键：zh/en 成对、非空、zh 有中文、en 无中日韩。"""
    p = []
    try:
        zh = json.loads(src["zh"])
        en = json.loads(src["en"])
    except Exception as exc:  # pragma: no cover
        return ["lang-json-invalid:" + str(exc)]
    for key in NEW_LANG_KEYS:
        if key not in zh or key not in en:
            p.append("lang-missing:" + key)
            continue
        if not str(zh[key]).strip() or not str(en[key]).strip():
            p.append("lang-empty:" + key)
        if not CJK_RE.search(str(zh[key])):
            p.append("lang-zh-no-cjk:" + key)
        if CJK_RE.search(str(en[key])):
            p.append("lang-en-has-cjk:" + key)
    key_id = "gui.rs_create_compat.charger_filter.row.block.id"
    if key_id in zh and "%s" not in str(zh[key_id]):
        p.append("lang-block-id-no-placeholder")
    return p


# ======================================================================================
# ⑦ 行为模型：方块名单的黑/白名单真值表（纯 Python 孪生，与 round98 同一套写法）
# ======================================================================================

class BlockFilterTwin:
    """ChargingFilter 的方块那一路的纯 Python 孪生（同一语义：命中 ⇒ 黑名单拒 / 白名单放）。"""

    def __init__(self, whitelist=False, blocks=(), dropped=(), carried=()):
        self.whitelist = whitelist
        self.blocks = set(blocks)
        self.dropped = set(dropped)
        self.carried = set(carried)
        self.invert = False        # 反例开关：把语义取反
        self.ignore_lists = False  # 反例开关：空名单两态混同

    def _allows(self, hit):
        if self.ignore_lists:
            return True
        if self.invert:
            return (not hit) if self.whitelist else hit
        return hit if self.whitelist else not hit

    def allows_block(self, block_id):
        return self._allows(block_id in self.blocks)

    def allows_item(self, item_id, kind="dropped"):
        bucket = self.dropped if kind == "dropped" else self.carried
        return self._allows(item_id in bucket)


def behaviour_ok(model_cls=BlockFilterTwin):
    cases = [
        # 黑名单：空 ⇒ 全放；命中 ⇒ 拒；未命中 ⇒ 放
        (dict(whitelist=False), "rs_create_compat:fe_energy_cube", True),
        (dict(whitelist=False, blocks=["rs_create_compat:fe_energy_cube"]),
         "rs_create_compat:fe_energy_cube", False),
        (dict(whitelist=False, blocks=["rs_create_compat:fe_energy_cube"]),
         "refinedstorage:controller", True),
        # 白名单：空 ⇒ 全拒；命中 ⇒ 放；未命中 ⇒ 拒
        (dict(whitelist=True), "refinedstorage:controller", False),
        (dict(whitelist=True, blocks=["refinedstorage:controller"]),
         "refinedstorage:controller", True),
        (dict(whitelist=True, blocks=["refinedstorage:controller"]),
         "rs_create_compat:fe_energy_cube", False),
    ]
    for kwargs, block_id, expected in cases:
        if model_cls(**kwargs).allows_block(block_id) != expected:
            return False
    # 不串台：同一个 id 只在物品名单里 ⇒ 方块判定必须「未命中」
    twin = model_cls(whitelist=False, dropped=["minecraft:stone"],
                     carried=["minecraft:stone"], blocks=[])
    if twin.allows_block("minecraft:stone") is not True:
        return False
    # 方块名单命中不影响物品判定
    twin = model_cls(whitelist=False, blocks=["minecraft:stone"], dropped=[])
    if twin.allows_item("minecraft:stone", "dropped") is not True:
        return False
    return True


def semantics_variant(invert=False, ignore_lists=False):
    class Variant(BlockFilterTwin):
        def __init__(self, **kwargs):
            super().__init__(**kwargs)
            self.invert = invert
            self.ignore_lists = ignore_lists

    return Variant


# ======================================================================================
# ⑧ 真代码探针（JVM）：of / save / load 的方块那一路真的跑一遍
# ======================================================================================

PROBE_SOURCE = r"""
import cretae.cookiewyq.rs_create_compat.support.ChargerFilterLimits;
import cretae.cookiewyq.rs_create_compat.support.ChargingFilter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 真代码探针：ChargingFilter 的「世界中的方块」那一路（of / save / load / copyFrom / allowsBlock）。
 *
 * 不使用任何注册表：Block 实例需要完整 Bootstrap（依赖数据包 / 标签），在离线 javac 探针里不成立，
 * 因此「注册表取不到注册名」这条真实分支用 allowsBlock(null) 覆盖（正是源码注释承诺的降级语义）。
 */
public final class ChargerBlockFilterProbe {
    private static int cases;
    private static int failures;

    public static void main(String[] args) {
        final int over = ChargerFilterLimits.MAX_BLOCK_ENTRIES + 300;
        final List<ResourceLocation> many = ids(over);

        // of() 六参：方块名单超限截断，且不污染其它三份名单
        final ChargingFilter big = ChargingFilter.of(false, List.of(), List.of(), many,
            List.of(), List.of());
        expectTrue("of-blocks-capped",
            big.blocks().size() == ChargerFilterLimits.MAX_BLOCK_ENTRIES);
        expectTrue("of-blocks-first-kept", big.blocks().contains(id(0)));
        expectTrue("of-blocks-no-last", !big.blocks().contains(id(over - 1)));
        expectTrue("of-others-empty", big.droppedItems().isEmpty() && big.carriedItems().isEmpty()
            && big.playerNames().isEmpty() && big.playerIds().isEmpty());

        // 五参重载 = 方块名单为空（第 98～109 轮的调用形态必须继续可用）
        final ChargingFilter legacy = ChargingFilter.of(false, List.of(id(1)), List.of(),
            List.of("Steve"), List.of());
        expectTrue("of5-blocks-empty", legacy.blocks().isEmpty());
        expectTrue("of5-dropped-kept", legacy.droppedItems().size() == 1);

        // save → load 往返（方块名单保序/保内容）
        final ChargingFilter src = ChargingFilter.of(true, List.of(id(2)), List.of(),
            List.of(id(3), id(4)), List.of(), List.of());
        final CompoundTag tag = new CompoundTag();
        src.save(tag);
        expectTrue("save-has-blocks-key", tag.contains("blocks"));
        expectTrue("save-blocks-size", tag.getList("blocks", Tag.TAG_STRING).size() == 2);
        final ChargingFilter back = ChargingFilter.load(tag);
        expectTrue("roundtrip-blocks", back.blocks().size() == 2
            && back.blocks().contains(id(3)) && back.blocks().contains(id(4)));
        expectTrue("roundtrip-whitelist", back.whitelist());
        expectTrue("roundtrip-dropped", back.droppedItems().contains(id(2)));

        // 旧存档：完全没有 blocks 键 ⇒ 读完不炸、方块名单为空、其它字段照旧
        final CompoundTag oldTag = new CompoundTag();
        oldTag.putInt("v", ChargingFilter.NBT_VERSION);
        oldTag.putBoolean("whitelist", false);
        final ListTag oldDropped = new ListTag();
        oldDropped.add(StringTag.valueOf(id(5).toString()));
        oldTag.put("dropped", oldDropped);
        final ChargingFilter old = ChargingFilter.load(oldTag);
        expectTrue("legacy-no-blocks-key", old.blocks().isEmpty());
        expectTrue("legacy-other-fields", old.droppedItems().contains(id(5)) && !old.whitelist());

        // 坏类型：blocks 被手改成 STRING ⇒ 按空列表处理，绝不抛
        final CompoundTag badType = new CompoundTag();
        badType.putInt("v", ChargingFilter.NBT_VERSION);
        badType.putString("blocks", "not-a-list");
        final ChargingFilter bad = ChargingFilter.load(badType);
        expectTrue("wrong-type-blocks-empty", bad.blocks().isEmpty());

        // 版本闸没被这轮改动弄坏：v=99 一律回落默认（含方块名单）
        final CompoundTag future = new CompoundTag();
        future.putInt("v", 99);
        future.put("blocks", tag.getList("blocks", Tag.TAG_STRING));
        final ChargingFilter fallback = ChargingFilter.load(future);
        expectTrue("version-gate", fallback.blocks().isEmpty() && !fallback.whitelist());

        // copyFrom：方块名单必须一起被覆盖（方块实体里是 final 字段，只有这一条写入路径）
        final ChargingFilter copy = new ChargingFilter();
        copy.copyFrom(src);
        expectTrue("copyfrom-blocks", copy.blocks().size() == 2);
        expectTrue("copyfrom-is-copy", copy.blocks() != src.blocks());

        // allowsBlock 的降级语义：注册表取不到注册名 ⇒ 按「未命中」⇒ 黑名单放行 / 白名单拒绝
        expectTrue("allows-block-null-blacklist",
            ChargingFilter.of(false, List.of(), List.of(), List.of(), List.of(), List.of())
                .allowsBlock(null));
        expectTrue("allows-block-null-whitelist",
            !ChargingFilter.of(true, List.of(), List.of(), List.of(), List.of(), List.of())
                .allowsBlock(null));

        // 常量：方块上限与物品同级；NBT 版本仍是 1（旧档按 v1 读）
        expectTrue("cap-block-equals-item",
            ChargerFilterLimits.MAX_BLOCK_ENTRIES == ChargerFilterLimits.MAX_ITEM_ENTRIES);
        expectTrue("nbt-version-1", ChargingFilter.NBT_VERSION == 1);

        if (failures > 0) {
            System.out.println("BLOCK_FILTER_FAIL failures=" + failures + " cases=" + cases);
            System.exit(1);
        }
        System.out.println("BLOCK_FILTER_OK cases=" + cases);
    }

    private static List<ResourceLocation> ids(int count) {
        final List<ResourceLocation> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(id(i));
        }
        return out;
    }

    private static ResourceLocation id(int index) {
        return ResourceLocation.fromNamespaceAndPath("probe110", "block_" + index);
    }

    private static void expectTrue(String name, boolean ok) {
        cases++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name);
        }
    }
}
"""


def lead_classpath():
    """与 tools/_lead_compile.ps1 同一套 classpath（跟随 GRADLE_USER_HOME + 同族去重）。"""
    import _gradle_cache as gc

    parts = []
    mc_jar, _ = gc.find_mc_jar()
    if mc_jar:
        parts.append(mc_jar)
    parts.extend(ref.path for ref in gc.dedupe(gc.module_jars(), gc.pinned_versions()).kept)
    libs_dir = os.path.join(ROOT, "libs")
    if os.path.isdir(libs_dir):
        parts.extend(os.path.join(libs_dir, name) for name in sorted(os.listdir(libs_dir))
                     if name.endswith(".jar"))
    seen = set()
    unique = []
    for part in parts:
        if part and part not in seen:
            seen.add(part)
            unique.append(part)
    return ";".join(unique)


def run_probe():
    """编译「当前源码里的 ChargingFilter + ChargerFilterLimits」并真跑一遍。

    刻意从 src 现编译这两份源码（而不是用 build/lead_compile 的旧产物）：
    探针要验证的是<b>这次改动后的源码</b>，不是上一次编译缓存。
    """
    for tool in (JAVAC, JAVA):
        if not os.path.isfile(tool):
            return False, "找不到 " + tool
    os.makedirs(PROBE_DIR, exist_ok=True)
    probe = os.path.join(PROBE_DIR, "ChargerBlockFilterProbe.java")
    with io.open(probe, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(PROBE_SOURCE)
    classpath = lead_classpath()
    proc = subprocess.run(
        [JAVAC, *JAVAC_EN, "-proc:none", "-encoding", "UTF-8", "-cp", classpath,
         "-d", PROBE_DIR,
         os.path.join(SUPPORT, "ChargingFilter.java"),
         os.path.join(SUPPORT, "ChargerFilterLimits.java"),
         probe],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=300)
    if proc.returncode != 0:
        return False, "探针编译失败：" + (proc.stdout + proc.stderr)[-1500:]
    proc = subprocess.run([JAVA, "-cp", PROBE_DIR + ";" + classpath, "ChargerBlockFilterProbe"],
                          capture_output=True, text=True, encoding="utf-8", errors="replace",
                          timeout=300)
    out = (proc.stdout or "") + (proc.stderr or "")
    if proc.returncode != 0 or "BLOCK_FILTER_OK" not in out:
        return False, "探针失败：" + out[-1500:]
    tail = [line for line in out.splitlines() if line.startswith("BLOCK_FILTER_OK")]
    return True, "；".join(tail)


# ======================================================================================
# 主流程
# ======================================================================================

def main():
    sources = {name: read(path) for name, path in FILES.items()}
    sources["zh"] = read(os.path.join(LANG_DIR, "zh_cn.json"))
    sources["en"] = read(os.path.join(LANG_DIR, "en_us.json"))

    print("=" * 78)
    print("round110：范围充电器过滤新增「世界中的方块」类别（BLOCKS tab）")
    print("=" * 78)

    p1 = filter_problems(sources)
    check("① ChargingFilter：四类名单 + allowsBlock（共用 allows）+ NBT blocks + 五参兼容（%d 项）" % 12,
          not p1, ", ".join(p1[:6]))
    p2 = limits_problems(sources)
    check("② ChargerFilterLimits：MAX_BLOCK_ENTRIES 与物品同级、仍零 MC 依赖", not p2,
          ", ".join(p2[:6]))
    p3 = be_problems(sources)
    check("③ 方块实体：chargeBlock 先判过滤（连目标都不算）+ 有界候选池 + 既有语义未改",
          not p3, ", ".join(p3[:6]))
    p4 = net_problems(sources)
    check("④ 网络：Sync 多两字段、编解码双向卡上限、Set 复用同一份、handle 不丢字段",
          not p4, ", ".join(p4[:6]))
    p5 = screen_problems(sources)
    check("⑤ 界面：BLOCKS 页签全链路（集合 / 行 = 候选 ∪ 已配置 / 勾选 / 图标 / 拼音搜索 / 文案）",
          not p5, ", ".join(p5[:6]))
    p6 = lang_problems(sources)
    check("⑥ 语言键 6 条：zh/en 成对、非空、zh 有中文、en 无中日韩、row.block.id 带 %s",
          not p6, ", ".join(p6[:6]))
    check("⑦ 行为模型：方块黑/白名单 4 组合 × 空/命中/未命中，且与物品名单互不串台",
          behaviour_ok())

    ok, detail = run_probe()
    check("⑧ 真代码探针：of / save / load / copyFrom 的方块那一路真的跑通（含旧存档缺键）",
          ok, detail)
    if ok:
        print("[probe] " + detail)

    # ---------------- 反例自证 ----------------
    def mutate(name, old, new, count=1):
        if old not in sources[name]:
            raise SystemExit("反例自证失败：源码里找不到要替换的片段（%s）：%r" % (name, old))
        copy = dict(sources)
        copy[name] = sources[name].replace(old, new, count)
        return copy

    # 1. 删掉 chargeBlock 的方块门控（等于「第三类根本没有判定出口」——本轮要修的那个洞）
    case = mutate("be",
                  "if (!chargingFilter.allowsBlock(blockEntity.getBlockState().getBlock())) {\n"
                  "            return false;\n"
                  "        }",
                  "")
    counterexample("删掉 chargeBlock 的 allowsBlock 门控 ⇒ be-chargeblock", bool(be_problems(case)))

    # 2. 把「候选登记」挪到过滤之后（拉黑过的方块从候选池消失 ⇒ 再也取消不了勾选）
    # 第 122 轮：登记入口改为 recordSeenCandidate（携带已取到的能力），锚点同步更新，
    # 判据原意不变 —— 仍然要求「登记发生在过滤判定之前」。
    case = mutate("be", "        recordSeenCandidate(blockEntity.getBlockState(), storage);\n", "")
    GATE_BLOCK = ("        if (!chargingFilter.allowsBlock(blockEntity.getBlockState().getBlock())) {\n"
                  "            return false;\n"
                  "        }")
    case["be"] = case["be"].replace(
        GATE_BLOCK, GATE_BLOCK + "\n        recordSeenCandidate(blockEntity.getBlockState(), storage);", 1)
    counterexample("候选登记挪到过滤之后（拉黑的方块从列表消失）⇒ be-chargeblock-order",
                   "be-chargeblock-order" in be_problems(case))

    # 3. NBT 不写方块键（存档里丢了这一类的配置）
    case = mutate("filter", "        tag.put(TAG_BLOCKS, idsToTag(blocks));\n", "")
    counterexample("save 不写 blocks 键 ⇒ filter-save-no-blocks",
                   "filter-save-no-blocks" in filter_problems(case))

    # 4. 解码「读够上限就 break」（后续字段错位）
    OLD_DEC = ("            if (kept.size() < ChargerFilterLimits.MAX_BLOCK_ENTRIES) {\n"
               "                kept.add(id);\n"
               "            }")
    NEW_DEC = ("            if (kept.size() < ChargerFilterLimits.MAX_BLOCK_ENTRIES) {\n"
               "                kept.add(id);\n"
               "            } else {\n"
               "                break;\n"
               "            }")
    case = mutate("sync", OLD_DEC, NEW_DEC)
    counterexample("decodeBlocks 读够上限就 break ⇒ sync-decodeblocks-breaks-stream",
                   "sync-decodeblocks-breaks-stream" in net_problems(case))

    # 5. 删掉 BLOCKS 页签（等于「方块这一类没列出来」）
    case = mutate("screen", '        BLOCKS("tab.blocks", "tab.tip.blocks"),\n', "")
    counterexample("删掉 Tab.BLOCKS ⇒ screen-no-blocks-tab",
                   "screen-no-blocks-tab" in screen_problems(case))

    # 6. 方块搜索不走 PinyinSearch（退回「只能打汉字」）
    case = mutate("screen", "final boolean nameHit = PinyinSearch.contains(name, needle);",
                  "final boolean nameHit = name.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));")
    counterexample("方块搜索不用 PinyinSearch ⇒ screen-search-name-no-pinyin",
                   "screen-search-name-no-pinyin" in screen_problems(case))

    # 7. 方块行改用「物品注册表」取 id（判定对象变成方块物品 —— 与方块注册名不是一回事）
    case = mutate("screen", "net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id)",
                  "net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id)")
    counterexample("方块行从物品注册表取 ⇒ screen-block-not-from-block-registry",
                   "screen-block-not-from-block-registry" in screen_problems(case))

    # 8. 语言文件少一条新键
    en_data = json.loads(sources["en"])
    en_data.pop(NEW_LANG_KEYS[0], None)
    case = dict(sources)
    case["en"] = json.dumps(en_data, ensure_ascii=False)
    counterexample("en_us 少一条方块页签键 ⇒ lang-missing",
                   bool(lang_problems(case)))

    # 9. 上限用错常量（方块名单去卡物品上限 = 两套口径）
    case = mutate("filter",
                  "if (!ChargerFilterLimits.addBounded(target, id,\n"
                  "                ChargerFilterLimits.MAX_BLOCK_ENTRIES)) {",
                  "if (!ChargerFilterLimits.addBounded(target, id,\n"
                  "                ChargerFilterLimits.MAX_ITEM_ENTRIES)) {")
    counterexample("addBlocks 用错上限常量 ⇒ filter-addblocks-wrong-cap",
                   "filter-addblocks-wrong-cap" in filter_problems(case))

    # 10. 语义取反 / 空名单两态混同（行为模型自证）
    counterexample("黑/白名单语义取反 ⇒ 方块真值表判负",
                   behaviour_ok(semantics_variant(invert=True)) is False)
    counterexample("空名单两态混同（空名单一律放行）⇒ 方块真值表判负",
                   behaviour_ok(semantics_variant(ignore_lists=True)) is False)

    print("")
    print("断言总数 %d（含反例自证 %d 条）" % (CHECKS[0], COUNTER[0]))
    if FAILS:
        print("问题总数: %d" % len(FAILS))
        for item in FAILS:
            print(" - " + item)
        return 1
    print("SELFCHECK OK (%d checks, %d counterexamples)" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
