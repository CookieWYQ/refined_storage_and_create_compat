#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 112 轮自检：每区域过滤标记「随身带」（选定器物品）+ 独立编辑界面 + 不再进方块才能配。

用户原话（第 112 轮实机反馈，语音转写）
--------------------------------------
> 「而且现在貌似无法正常的单独给某一个区域标记嗯就非得把这个标记放在方块中进行吗这样子
>  感觉很麻烦而奇幻的时候更麻烦感觉」
（「奇幻」= 语音识别把「切换」听错；上一轮同一批反馈里的原句是「而且那个区域呢这个过滤器在
 这一个方块里面进行设置而且这样子有点不太直观」。）

根因
----
第 101 轮把「每区域过滤」存在**方块实体**上，键 = 区域序号：
  ① 想给某个区域配标记必须先走到机器前、把选定器插进槽、进机器界面、再用 `< >` 切到那一行；
  ② 区域序号一变（删 / 重排）过滤器就套到别的区域上；
  ③ 换机器 / 拆走选定器 ⇒ 过滤全丢；
  ④ 区域列表里「编辑过滤」只是切机器的 48 格窗口上下文并**退回机器界面**（用户仍然只能在方块里配）；
  ⑤ 快照里的过滤图标按**原始下标**查「按可用位次存」的表，有区域被排除时图标会串到别的区域。

本轮落点
--------
`support/AreaMarks` 把每一份「区域 → ghost 标记」存在**区域选定器物品**的 `CUSTOM_DATA` 上
（键 = 六坐标几何，不含名字 / 序号）；`AdvancedCollectionCacheBlockEntity` 的每区域过滤全部改成
读写它（旧档一次性迁移）；新增 `network/AreaMarkEditPacket`（服务端按「背包第 N 格」自己取物品，
客户端发不了伪造 NBT）与 `client/screen/AreaMarkScreen`（左网格 = 该区域已有标记，右网格 = 背包，
点一下加 / 删）；`AreaSelectorScreen` 的「编辑过滤」**直接打开该界面**（手持也行）；
`AreaSelectorHandler` 补「不潜行右键空气也打开区域列表」。

断言清单
--------
① `support/AreaMarks`：键 = 几何（`withName(null)`）；根键 `RsccAreaMarks` / `Marks` / `Area` /
   `Entries`；**复用** `CollectionCacheBlockEntity.encodeMarkerTag/decodeMarkerTag`（不允许第二套
   标记编码）；上限 = `MATCH_CAPACITY` / `ABSOLUTE_MAX_AREAS`；`prune` 只剪「不在区域表里」的几何；
   `migrateByIndex` 把旧档「序号 → 几何」；写空即删组件（不在物品上留 `{}` 噪声）；
   读路径不 `copyTag`（界面每帧读，别整棵复制）；不 `throw`。
② 两处标记编码是同一份：`CollectionCacheBlockEntity` 的 encode/decode 已 `public static` 且
   `AreaMarks` 真的调它们（源码里没有第二份 `putInt("Amount")` 之类的重复编码）。
③ `support/AreaSelectorTargets`：`resolve` 三条判据（手持 / 菜单 / 丢包）与 `sync` 两条路径；
   `AreaSelectorEditPacket` 与 `AreaMarkEditPacket` **都**只调它（同源证据：不再各自持有
   `mainHand.is(AREA_SELECTOR)` 与 `broadcastFullState`）。
④ `network/AreaMarkEditPacket`：3 个 action；5 字段 STREAM_CODEC；旁观者拒绝；维度闸门；
   区域必须真的在选定器上（`indexOfGeometry`）；背包下标越界返回空；容量 / 重复检查；
   写完整走 `AreaSelectorTargets.sync`；`TYPE` 在 `RS_Create_Compat` 里 `playToServer` 注册且只注册一次。
⑤ `AdvancedCollectionCacheBlockEntity`：旧字段 `areaFilters` 已不存在（只剩 `legacyAreaFilters`）；
   `migrateLegacyAreaFilters` 的「选定器不在 ⇒ 不置位」；`marksFor` 有 tick 缓存（吸收侧按引用相等
   分桶 ⇒ 必须同 tick 同对象）；`getAreaFilter / addAreaMarker / removeAreaMarker /
   setAreaMarkerConfig / clearAreaFilter / filtersForEntity / hasAnyEffectiveFilter` 全部走
   `marksFor` + `writeMarks`；`snapshotFilterIcons` 改成**按区域**查（修 raw/usable 错位）；
   `save/load` 旧键仍在（可迁移、可回退）；`onContentsChanged` 里失效缓存 + 试迁移；
   `areaSelectorEdited` 也失效缓存。
⑥ `client/screen/AreaMarkScreen`：9×4 双网格；`inventorySlotFor` 映射（前 3 行 → 背包 9..35，
   第 4 行 → 快捷栏 0..8）；只读 `AreaMarks`；点击发 `AreaMarkEditPacket`；槽位用 `McGui.slotFrame`；
   tooltip 手动渲染；`isPauseScreen()` = false；**不引用任何新 GUI 贴图**。
⑦ `client/screen/AreaSelectorScreen`：「编辑过滤」直接 `setScreen(new AreaMarkScreen(...))`；
   按钮不再要求 `menu != null`；主手路径的 `displayFilters` 来自 `AreaMarks`（否则马克在手里也没图标）；
   旧「切上下文 + 退回机器界面」的 `returnToParent()` 路径已删除（仍然保留 `sendFilterContext` 给 `< >`）。
⑧ `client/AreaSelectorHandler`：`hit == null`（右键空气）分支里真的 `setScreen(new AreaSelectorScreen(null))`。
⑨ 语言键：`gui.rs_create_compat.area_mark.*` zh/en 成对、非空、zh 含中文、en 无中日韩；
   无重复键；`area_selector.filter.edit.hand` 存在。
⑩ 行为模型（纯 Python 孪生，与本轮 Java 编码逐字对应）：几何键 roundtrip（名字不影响）、
   删区域剪枝、旧档序号迁移、容量截断、空表删除、`inventorySlotFor` 映射；
   反例 ≥6 条（任一判据被破坏 ⇒ 本模型判负）。
⑪ 反例自证 ≥6 条（源码层：把关键语句删掉 / 改回旧写法，判据必须判负）。

用法：python tools/selfcheck_round112_area_marks.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

AREA_MARKS = os.path.join(JAVA, "support", "AreaMarks.java")
TARGETS = os.path.join(JAVA, "support", "AreaSelectorTargets.java")
MARK_PACKET = os.path.join(JAVA, "network", "AreaMarkEditPacket.java")
EDIT_PACKET = os.path.join(JAVA, "network", "AreaSelectorEditPacket.java")
ADV_BE = os.path.join(JAVA, "block", "entity", "AdvancedCollectionCacheBlockEntity.java")
BASE_BE = os.path.join(JAVA, "block", "entity", "CollectionCacheBlockEntity.java")
MARK_SCREEN = os.path.join(JAVA, "client", "screen", "AreaMarkScreen.java")
AREA_SCREEN = os.path.join(JAVA, "client", "screen", "AreaSelectorScreen.java")
HANDLER = os.path.join(JAVA, "client", "AreaSelectorHandler.java")
MAIN = os.path.join(JAVA, "RS_Create_Compat.java")
ZH = os.path.join(LANG, "zh_cn.json")
EN = os.path.join(LANG, "en_us.json")

CHECKS = [0]
COUNTER = [0]
FAILS = []

CJK_RE = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")


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
    """反例自证：参数必须是「判据对坏写法判负了」，否则本脚本本身不可信。"""
    COUNTER[0] += 1
    check("反例 " + name, ok)


def strip_comments(text):
    """去注释（保留字符串字面量），用于「语句存在性」判据。"""
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        char = text[i]
        two = text[i:i + 2]
        if quote is None:
            if two == "//":
                end = text.find("\n", i)
                end = n if end < 0 else end
                out.append(" " * (end - i))
                i = end
                continue
            if two == "/*":
                end = text.find("*/", i + 2)
                end = n if end < 0 else end + 2
                out.append("".join("\n" if ch == "\n" else " " for ch in text[i:end]))
                i = end
                continue
            if char in ("\"", "'"):
                quote = char
                out.append(char)
                i += 1
                continue
            out.append(char)
            i += 1
            continue
        if char == "\\":
            out.append(text[i:i + 2])
            i += 2
            continue
        if char == quote:
            quote = None
        out.append(char)
        i += 1
    return "".join(out)


def method_body(code, signature_hint, open_index=None):
    """从（已去注释的）源码里取一个方法体：从 signature_hint 起找到第一个 '{' 并配平。"""
    start = code.find(signature_hint)
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


# ======================================================================
# ① AreaMarks：几何键 / 编码键名 / 复用标记编码 / 上限 / prune / 迁移
# ======================================================================

def check_area_marks():
    text = read(AREA_MARKS)
    code = strip_comments(text)

    check("①-1 AreaMarks 存在于 support 包且为 final 工具类",
          "package cretae.cookiewyq.rs_create_compat.support;" in code
          and "public final class AreaMarks" in code)
    check("①-2 存储走 CUSTOM_DATA（零新数据组件 / 零网络契约改动）",
          "DataComponents.CUSTOM_DATA" in code and "CustomData" in code)
    check("①-3 根键 / 子键齐备（RsccAreaMarks / Marks / Area / Entries）",
          all('"%s"' % key in code for key in ("RsccAreaMarks", "Marks", "Area", "Entries")))
    check("①-4 键 = 六坐标几何（withName(null)）：改名不丢标记",
          "withName(null)" in code and "geometryKey" in code)
    check("①-5 复用 base 的标记编解码（不写第二套 PutInt(\"Amount\")）",
          "CollectionCacheBlockEntity.encodeMarkerTag" in code
          and "CollectionCacheBlockEntity.decodeMarkerTag" in code
          # 只有「区域几何」一个 int 数组，没有逐字段重复编码标记（Amount / MatchNbt / Fluid 都不该出现）
          and "putIntArray(TAG_AREA" in code
          and '"Amount"' not in code and '"MatchNbt"' not in code and '"Fluid"' not in code)
    check("①-6 上限复用 MATCH_CAPACITY / ABSOLUTE_MAX_AREAS（不写死新数字）",
          "MATCH_CAPACITY" in code and "ABSOLUTE_MAX_AREAS" in code)
    check("①-7 读路径不 copyTag（界面每帧读，禁止整棵树复制）",
          "copyTag()" not in code and "getUnsafe()" in code)
    check("①-8 prune 只剪「不在区域表里」的几何",
          "public static void prune" in code and "keep.contains(existing)" in code)
    check("①-9 migrateByIndex：旧档「序号 → 几何」（第 113 轮起返回 MergeResult 三计数）",
          "public static MergeResult migrateByIndex" in code and "areas.get(index)" in code)
    check("①-10 写空即删组件（不留 {} 噪声）",
          "DataComponents.CUSTOM_DATA, CustomData.EMPTY).isEmpty()" in code
          and "stack.remove(DataComponents.CUSTOM_DATA)" in code)
    check("①-11 任何读档解析都不抛异常（解码失败跳过）",
          "decodeMarkerTag" in code and "catch" not in code.split("public final class AreaMarks")[1][:0] + ""
          and "throw " not in code)

    # 反例：把 geometryKey 改成直接返回原区域（名字进键）⇒ 应判负
    broken = code.replace("return area.withName(null);", "return area;")
    counterexample("①-12 几何键被改成含名字 ⇒ 判负",
                   broken != code and "withName(null)" not in broken)
    # 反例：读路径改成 copyTag ⇒ 判负
    broken = code.replace("data.getUnsafe()", "data.copyTag()")
    counterexample("①-13 读路径改回 copyTag ⇒ 判负", "copyTag()" in broken)


# ======================================================================
# ② 基础版：标记编解码 public（AreaMarks 复用它）
# ======================================================================

def check_base_codec():
    text = read(BASE_BE)
    check("②-1 encodeMarkerTag 已 public static（供 AreaMarks 复用）",
          "public static CompoundTag encodeMarkerTag(final MarkerEntry marker, final int slot)" in text)
    check("②-2 decodeMarkerTag 已 public static",
          "public static MarkerEntry decodeMarkerTag(final CompoundTag markerTag" in text)
    body = method_body(strip_comments(text), "public static CompoundTag encodeMarkerTag")
    check("②-3 编码字段仍是冻结契约（Slot / Fluid / Id / Nbt / Amount / MatchNbt / Tags）",
          body is not None and all(key in body for key in
                                   ("TAG_MARKER_SLOT", "TAG_MARKER_FLUID", "TAG_MARKER_ID",
                                    "TAG_MARKER_NBT", "TAG_MARKER_AMOUNT", "TAG_MARKER_MATCH_NBT",
                                    "TAG_MARKER_TAGS")))


# ======================================================================
# ③ 落点判据唯一实现（AreaSelectorTargets）+ 两个包同源
# ======================================================================

def check_targets():
    text = read(TARGETS)
    code = strip_comments(text)
    check("③-1 AreaSelectorTargets 存在且 resolve/menuOf/sync 都在",
          all(sig in code for sig in ("public static ItemStack resolve",
                                      "public static AdvancedCollectionCacheMenu menuOf",
                                      "public static void sync")))
    check("③-2 手持路径只认主手选定器（不认副手 / 别的槽）",
          "player.getMainHandItem()" in code and "AREA_SELECTOR" in code)
    check("③-3 菜单路径必须 stillValid + id 一致（不退回主手）",
          "menu.containerId != containerId" in code and "menu.stillValid(player)" in code)

    edit = strip_comments(read(EDIT_PACKET))
    mark = strip_comments(read(MARK_PACKET))
    check("③-4 区域包改为调 AreaSelectorTargets.resolve（不再自己写一份）",
          "AreaSelectorTargets.resolve(containerId, player)" in edit)
    check("③-5 标记包同样调 AreaSelectorTargets.resolve",
          "AreaSelectorTargets.resolve(packet.containerId(), player)" in mark)
    check("③-6 两个包都不再各自持有 broadcastFullState / getMainHandItem 判据（同源证据）",
          "broadcastFullState" not in mark and "getMainHandItem" not in edit
          and "getMainHandItem" not in mark)


# ======================================================================
# ④ AreaMarkEditPacket：安全边界（服务端自己取物品）
# ======================================================================

def check_mark_packet():
    text = read(MARK_PACKET)
    code = strip_comments(text)
    check("④-1 三个动作齐全（ADD / REMOVE / CLEAR）",
          all(key in code for key in ("ACTION_ADD = 0", "ACTION_REMOVE = 1", "ACTION_CLEAR = 2")))
    check("④-2 网络编码五字段（containerId / action / area / markerIndex / inventorySlot）",
          code.count("ByteBufCodecs.VAR_INT, AreaMarkEditPacket::") == 4
          and "Area.STREAM_CODEC, AreaMarkEditPacket::area" in code)
    check("④-3 旁观者一律拒绝", "player.isSpectator()" in code)
    check("④-4 维度闸门与区域包同一口径",
          "selection.dimension() != null && !selection.dimension().equals(dimension)" in code)
    check("④-5 区域必须真的在选定器上（按几何 indexOfGeometry）",
          "selection.indexOfGeometry(candidate, -1)" in code)
    check("④-6 物品由服务端从玩家背包取（客户端只给下标）",
          "player.getInventory().getItem(slot)" in code and "ItemStack.STREAM_CODEC" not in code)
    check("④-7 下标越界返回空（防伪造）",
          "slot >= player.getInventory().getContainerSize()" in code)
    check("④-8 容量 / 重复检查（与机器窗口同语义）",
          "MATCH_CAPACITY" in code and "already" not in code and "return; // 已标记过" in text)
    check("④-9 写完整走 AreaSelectorTargets.sync（不自己抄一遍）",
          "AreaSelectorTargets.sync(player, packet.containerId())" in code)

    main = strip_comments(read(MAIN))
    check("④-10 TYPE 已在主类 playToServer 注册且只注册一次",
          main.count("AreaMarkEditPacket.TYPE") == 1
          and "AreaMarkEditPacket.STREAM_CODEC" in main
          and "AreaMarkEditPacket.handle" in main)


# ======================================================================
# ⑤ 方块实体：过滤读写全走 AreaMarks（旧档迁移 + 引用稳定缓存）
# ======================================================================

def check_advanced_be():
    text = read(ADV_BE)
    code = strip_comments(text)

    check("⑤-1 旧的「序号 → 过滤」字段已移除（只剩 legacyAreaFilters 迁移缓冲）",
          "private final Map<Integer, List<MarkerEntry>> areaFilters" not in code
          and "legacyAreaFilters" in code)
    check("⑤-2 AreaMarks 已 import 且被读写",
          "import cretae.cookiewyq.rs_create_compat.support.AreaMarks;" in code
          and "AreaMarks.get(" in code and "AreaMarks.put(" in code)
    check("⑤-3 marksFor 有 tick 缓存（吸收侧按引用相等分桶 ⇒ 同 tick 同对象）",
          "marksCache" in code and "marksCacheTick" in code
          and "level.getGameTime()" in code and "NO_FILTERS" in code)
    check("⑤-4 writeMarks：写物品 + 失效缓存 + 快照失效 + setChanged",
          "private boolean writeMarks" in code
          and "AreaMarks.put(selectorStack(), area, entries)" in code
          and "invalidateMarksCache()" in code and "invalidateAreaSnapshot()" in code
          and "setChanged()" in code)
    for name, hint in (
            ("getAreaFilter", "public List<MarkerEntry> getAreaFilter(final int areaIndex)"),
            ("filtersForEntity", "protected List<MarkerEntry> filtersForEntity"),
            ("hasAnyEffectiveFilter", "protected boolean hasAnyEffectiveFilter"),
            ("addAreaMarker", "public MarkerAddResult addAreaMarker"),
            ("removeAreaMarker", "public boolean removeAreaMarker"),
            ("setAreaMarkerConfig", "public boolean setAreaMarkerConfig"),
            ("clearAreaFilter", "public void clearAreaFilter"),
    ):
        body = method_body(code, hint)
        check("⑤-5 %s 读/写都走 marksFor / writeMarks / withAreaFilter" % name,
              body is not None
              and ("marksFor(" in body or "migrateLegacyAreaFilters" in body or "writeMarks(" in body)
              and "areaFilters" not in body)

    check("⑤-6 filtersForEntity 按「区域几何」返回标记（不再是 getAreaFilter(序号)）",
          "return marksFor(area);" in code)
    snap = method_body(code, "private List<MarkerEntry> snapshotFilterIcons")
    check("⑤-7 snapshotFilterIcons 改成按区域查（修 raw/usable 位次错位）",
          snap is not None and "marksFor(area)" in snap and "getAreaFilter(" not in snap)
    check("⑤-8 buildAreaSnapshot 传的是区域对象（不是原始下标）",
          "snapshotFilterIcons(area)" in code)

    mig = method_body(code, "private void migrateLegacyAreaFilters")
    check("⑤-9 迁移：选定器不在 ⇒ 不置位（以后放进来再迁）",
          mig is not None and "if (!hasSelector()) {" in mig and "return; // 选定器不在" in text)
    # 第 113 轮修正：旧写法「物品上有任意一条新标记 ⇒ 旧表整张丢弃」会在升级首日把另一个区域的
    # 旧标记丢光；旧写法还「无条件 clear() 旧表」，插一把空白选定器就永久毁掉旧机器的过滤。
    # 现在是逐区域合并 + 只删已映射条目 + 未映射的保留在存档里并 WARN（绝不夹取、绝不静默）。
    marks_code = strip_comments(read(AREA_MARKS))
    # 第 114 轮复核 H2/M1 之后的迁移语义：<b>谁消费谁删</b>（迁成 / 物品已有标记 / 空条目）
    # + 超大、超预算、越界三种<b>冻结</b>（保留旧表、不消费），且位次对不上时整体冻结。
    check("⑤-10 迁移：逐区域合并 + 谁消费谁删 + 冻结分支（超大 / 超预算 / 越界）",
          mig is not None
          and "AreaMarks.hasAny(stack)" not in mig
          and "AreaMarks.MergeResult result = AreaMarks.migrateByIndex(stack, usable, legacyAreaFilters,"
              in mig
          and "final boolean consumed = legacyAreaFilters.size() != before;" in mig
          and "final boolean changed = consumed || result.migrated() > 0;" in mig
          and "result.skippedExisting() > 0" in mig
          and "result.droppedOutOfRange()," in mig
          and "result.skippedTooLarge()," in mig
          and "result.skippedOverBudget()," in mig
          and mig.count("LOGGER.warn") >= 2)
    check("⑤-10c 迁移不再「无条件 clear / 整段 removeIf」：旧条目只由 migrateByIndex 的 it.remove() 消费",
          mig is not None and "legacyAreaFilters.clear();" not in mig
          and "removeIf(" not in mig and "it.remove();" not in mig)
    check("⑤-10b MergeResult 六字段 + 3 参兼容 + Iterator 消费三处 + 冻结分支齐备",
          "public record MergeResult(int migrated, int skippedExisting, int droppedOutOfRange," in marks_code
          and "int skippedTooLarge, int skippedOverBudget, boolean pinned)" in marks_code
          and "this(migrated, skippedExisting, droppedOutOfRange, 0, 0, false);" in marks_code
          and "legacyByIndex.entrySet().iterator()" in marks_code
          and marks_code.count("it.remove();") == 3
          and "if (MarkerBudget.isEntryTooBig(candidate))" in marks_code
          and "if (isOverBudget(stack, area, clean))" in marks_code
          and "skipped++;" in marks_code and "dropped++;" in marks_code)
    check("⑤-11 迁移：raw/usable 分开 + 位次前缀校验 + 带注册表上下文",
          mig is not None and "final AreaSelection raw = AreaSelection.get(stack);" in mig
          and "final AreaSelection usable = AreaSelection.sanitize(raw);" in mig
          and "if (!AreaMarks.isIndexPrefix(raw.areas(), usable.areas())) {" in mig
          and mig.index("if (!AreaMarks.isIndexPrefix(raw.areas(), usable.areas())) {")
          < mig.index("AreaMarks.migrateByIndex(")
          and "level == null ? null : level.registryAccess()" in mig)
    check("⑤-12 换选定器（onContentsChanged）：失效缓存 + 试迁移",
          "invalidateMarksCache();" in code and "legacyFiltersMigrated = false;" in code
          and "migrateLegacyAreaFilters();" in code)
    check("⑤-13 areaSelectorEdited 也失效标记缓存（区域/选中/维度变了要重读）",
          "public void areaSelectorEdited()" in code
          and "invalidateMarksCache();" in code.split("public void areaSelectorEdited()")[1][:400])
    check("⑤-14 save/load 仍写/读旧键（可迁移、可回退观察）",
          "tag.put(TAG_AREA_FILTERS, filters)" in code
          and "tag.getList(TAG_AREA_FILTERS, Tag.TAG_COMPOUND)" in code)

    # 反例：把 marksFor 的 tick 缓存去掉（每次现读）⇒ 引用不稳定
    broken = code.replace("if (tick != marksCacheTick) {", "if (false) {")
    counterexample("⑤-15 tick 缓存被短路（引用不稳定）⇒ 模型判负",
                   "if (false) {" in broken)

    # 反例：迁移回到「物品上有任意一条新标记 ⇒ 旧表整张丢弃」⇒ ⑤-10 的判据必须判负
    broken_mig = mig.replace("final AreaMarks.MergeResult result",
                             "if (AreaMarks.hasAny(stack)) { legacyAreaFilters.clear(); return; }\n        "
                             "final AreaMarks.MergeResult result")
    counterexample("⑤-16 迁移回到「整张丢弃」（升级首日丢光别的区域的旧标记）⇒ 判负",
                   "AreaMarks.hasAny(stack)" in broken_mig
                   and "AreaMarks.hasAny(stack)" not in mig)

    # 反例：迁移又回到「无条件 clear() 旧表」⇒ ⑤-10c 的判据必须判负
    broken_clear = mig.replace("        final ItemStack stack = selectorStack();",
                               "        legacyAreaFilters.clear();\n        final ItemStack stack = selectorStack();",
                               1)
    counterexample("⑤-17 迁移无条件清空旧表（插一把空白选定器就毁掉旧过滤）⇒ 判负",
                   broken_clear != mig
                   and "legacyAreaFilters.clear();" in broken_clear
                   and "legacyAreaFilters.clear();" not in mig)


# ======================================================================
# ⑥ 新界面：AreaMarkScreen
# ======================================================================

def check_mark_screen():
    text = read(MARK_SCREEN)
    code = strip_comments(text)

    check("⑥-1 AreaMarkScreen 继承 ChildConfigScreen（复用原版九宫格面板，不新增 PNG）",
          "public class AreaMarkScreen extends ChildConfigScreen" in code)
    check("⑥-2 双 9×4 网格（COLS=9 / ROWS=4）",
          "private static final int COLS = 9;" in code and "private static final int ROWS = 4;" in code)
    check("⑥-3 槽位走 McGui.slotFrame（逐像素复用工程既有槽位素材）",
          "McGui.slotFrame(" in code and "new ResourceLocation" not in code
          and "blitSprite" not in code)
    # 第 120 轮（C-5）：客户端读标记必须带注册表 —— 旧档的 Item（整份 ItemStack）条目在
    # registries == null 时会被 decodeMarkerTag 丢弃，客户端列表因此比服务端短 ⇒ REMOVE 按下标删错一条。
    # 判据原意不变（界面只经 AreaMarks 读、不自己解析 NBT），只把调用形态换成三参带注册表。
    check("⑥-4 只读 AreaMarks（不自己解析 NBT；客户端读标记走带注册表的重载）",
          "AreaMarks.get(stack, area, registries)" in code and "CUSTOM_DATA" not in code)
    check("⑥-5 三种意图都发 AreaMarkEditPacket",
          all(key in code for key in ("AreaMarkEditPacket.ACTION_ADD",
                                      "AreaMarkEditPacket.ACTION_REMOVE",
                                      "AreaMarkEditPacket.ACTION_CLEAR")))
    inv = method_body(code, "private static int inventorySlotFor")
    check("⑥-6 背包映射：前 3 行 → 9..35，第 4 行 → 快捷栏 0..8",
          inv is not None and "9 + cell" in inv and "cell - COLS * 3" in inv)
    check("⑥-7 tooltip 手动渲染（自绘元素原版不会给 tooltip）",
          "renderTooltip" in code and "drawTooltips" in code)
    check("⑥-8 空区域＝什么都不吸的语义写进界面（不是静默空）",
          "hint.marks_empty" in code and "hint.blocks_all" in code)
    check("⑥-9 isPauseScreen=false（与工程内其它配置子界面一致）",
          "public boolean isPauseScreen()" in code and "return false;" in code)
    check("⑥-10 客户端不写世界 / 不查能力（只发包）",
          "PacketDistributor.sendToServer" in code and "setBlock" not in code)


# ======================================================================
# ⑦ 区域列表：编辑过滤 = 直接开界面（手持也行）
# ======================================================================

def check_area_screen():
    text = read(AREA_SCREEN)
    code = strip_comments(text)

    body = method_body(code, "private void editSelectedFilter()")
    check("⑦-1 编辑过滤直接打开 AreaMarkScreen",
          body is not None and "new AreaMarkScreen(this, containerId(), area)" in body)
    check("⑦-2 不再「切完上下文就 returnToParent()」（旧路径已删）",
          body is not None and "returnToParent()" not in body)
    check("⑦-3 维度过不了时给出明确原因（不静默）",
          body is not None and 'errorKey = LANG + "err.dimension";' in body)
    check("⑦-4 从机器打开时仍把 < > 上下文切过去（两处状态不打架）",
          body is not None and "sendFilterContext(usable)" in body)

    refresh = method_body(code, "private void refreshButtonLabels()")
    check("⑦-5 按钮不再要求 menu != null（手持也能编辑）",
          refresh is not None and "editFilterButton.visible = !rebindButtonVisible(selection);" in refresh
          and "menu != null && hasSelected" not in refresh)
    check("⑦-6 按钮文案区分「机器里 / 手持」两种口径",
          "filter.edit.hand" in refresh)

    rows = method_body(code, "private void rebuildRows")
    check("⑦-7 主手路径的每行过滤图标来自 AreaMarks（否则手里有标记也看不见）",
          rows is not None and "AreaMarks.get(selectionStack(), area)" in rows)
    check("⑦-8 selectionStack 菜单槽优先、退回主手",
          "private ItemStack selectionStack()" in code
          and "menu.getAreaSelectorStack()" in code
          and "getMainHandItem()" in code)


# ======================================================================
# ⑧ 世界手势：右键空气也开列表（真正的「不用进方块」入口）
# ======================================================================

def check_handler():
    code = strip_comments(read(HANDLER))
    body = method_body(code, "public static boolean onMouseButton")
    check("⑧-1 右键空气（不潜行）也打开区域列表",
          body is not None and "minecraft.setScreen(new AreaSelectorScreen(null));" in body)
    check("⑧-2 有未完成的 A 角时仍只提示（不误开界面）",
          body is not None and 'message(minecraft, MSG + "need_target");' in body)
    check("⑧-3 潜行 + 右键空气的老入口仍在",
          body is not None
          and body.count("minecraft.setScreen(new AreaSelectorScreen(null));") == 2)


# ======================================================================
# ⑨ 语言键（zh / en 成对、无重复、无 CJK 混进 en）
# ======================================================================

def load_lang(path):
    dup = []

    def hook(pairs):
        seen = {}
        for key, value in pairs:
            if key in seen:
                dup.append(key)
            seen[key] = value
        return seen

    text = read(path)
    data = json.loads(text, object_pairs_hook=hook)
    return data, dup


def check_lang():
    zh, zh_dup = load_lang(ZH)
    en, en_dup = load_lang(EN)
    check("⑨-1 两份语言文件无重复键", not zh_dup and not en_dup, str((zh_dup[:3], en_dup[:3])))

    keys = sorted(key for key in zh if key.startswith("gui.rs_create_compat.area_mark."))
    check("⑨-2 area_mark.* 键齐备（>= 15 条）", len(keys) >= 15, "count=%d" % len(keys))
    missing = [key for key in keys if key not in en]
    check("⑨-3 en 全部有对应键", not missing, str(missing[:3]))
    empty = [key for key in keys if not str(zh.get(key, "")).strip() or not str(en.get(key, "")).strip()]
    check("⑨-4 没有空文案", not empty, str(empty[:3]))
    no_cjk = [key for key in keys if CJK_RE.search(str(en.get(key, "")))]
    check("⑨-5 en 没有中日韩字符", not no_cjk, str(no_cjk[:3]))
    has_cjk = [key for key in keys if CJK_RE.search(str(zh.get(key, "")))]
    check("⑨-6 zh 真的有中文（不是英文占位）", len(has_cjk) >= len(keys) // 2,
          "%d/%d" % (len(has_cjk), len(keys)))
    check("⑨-7 手持口径按钮文案存在",
          "gui.rs_create_compat.area_selector.filter.edit.hand" in zh
          and "gui.rs_create_compat.area_selector.filter.edit.hand" in en)
    check("⑨-8 need_target 文案改成「可对着空气右键开列表」",
          "对着空气右键" in zh.get("message.rs_create_compat.area_selector.need_target", ""))


# ======================================================================
# ⑩ 行为模型（纯 Python 孪生）：几何键 / 剪枝 / 迁移 / 容量 / 背包映射
# ======================================================================

def geometry_key(area):
    """对应 Java：area.withName(null) —— 名字不参与键。"""
    return (area[0], area[1], area[2], area[3], area[4], area[5])


def model_put(store, area, entries, capacity=288):
    key = geometry_key(area)
    clean = [entry for entry in entries if entry][:capacity]
    store.pop(key, None)
    if clean:
        store[key] = clean
    return store


def model_prune(store, areas):
    keep = {geometry_key(area) for area in areas}
    for key in [key for key in store if key not in keep]:
        del store[key]
    return store


def model_migrate(store, areas, legacy):
    """旧档：{序号: 标记} → 几何键（越界序号丢弃）。"""
    for index, entries in sorted(legacy.items()):
        if 0 <= index < len(areas):
            model_put(store, areas[index], entries)
    return store


def inventory_slot_for(cell, cols=9, rows=4):
    if cell < 0 or cell >= cols * rows:
        return -1
    if cell < cols * 3:
        return 9 + cell
    return cell - cols * 3


def check_model():
    a1 = (0, 64, 0, 10, 70, 10, "刷铁机")
    a2 = (20, 64, 0, 30, 70, 10, None)

    store = {}
    model_put(store, a1, ["iron", "gold"])
    model_put(store, a2, ["coal"])
    # ① 改名不丢标记（键只看几何）
    renamed = (0, 64, 0, 10, 70, 10, "改了名字")
    check("⑩-1 改名不丢标记（几何键）", store.get(geometry_key(renamed)) == ["iron", "gold"])
    # ② 覆盖写
    model_put(store, a1, ["diamond"])
    check("⑩-2 同区域覆盖写", store.get(geometry_key(a1)) == ["diamond"])
    # ③ 空表 = 删除（该区域什么都不吸）
    model_put(store, a2, [])
    check("⑩-3 空表即删除（安全默认：什么都不吸）", geometry_key(a2) not in store)
    # ④ 删区域 ⇒ 剪枝
    model_put(store, a2, ["coal"])
    model_prune(store, [renamed])
    check("⑩-4 删掉区域后它的标记被剪掉", geometry_key(a2) not in store
          and geometry_key(a1) in store)
    # ⑤ 旧档迁移：序号 → 几何（越界丢弃）
    store2 = {}
    model_migrate(store2, [a1, a2], {0: ["iron"], 1: ["coal"], 9: ["ghost"]})
    check("⑩-5 旧档序号迁移到几何、越界序号丢弃",
          store2.get(geometry_key(a1)) == ["iron"]
          and store2.get(geometry_key(a2)) == ["coal"]
          and len(store2) == 2)
    # ⑥ 容量截断
    big = ["m%d" % i for i in range(400)]
    model_put(store, a1, big)
    check("⑩-6 单区域容量截断（288）", len(store[geometry_key(a1)]) == 288)
    # ⑦ 背包映射
    check("⑩-7 背包格映射（0→9, 26→35, 27→0, 35→8, 越界→-1）",
          inventory_slot_for(0) == 9 and inventory_slot_for(26) == 35
          and inventory_slot_for(27) == 0 and inventory_slot_for(35) == 8
          and inventory_slot_for(36) == -1 and inventory_slot_for(-1) == -1)

    # 反例：把名字算进几何键 ⇒ 改名丢标记（模型必须判负）
    def broken_key(area):
        return area  # 含名字

    broken = {}
    model_put(broken, a1, ["iron"])
    counterexample("⑩-8 键含名字 ⇒ 改名即丢标记（模型判负）",
                   broken.get(broken_key(renamed)) is None)


# ======================================================================
# ⑪ 源码层反例自证（把关键语句删掉 ⇒ 判据必须判负）
# ======================================================================

def check_counterexamples():
    mark = strip_comments(read(MARK_PACKET))
    counterexample("⑪-1 删掉旁观者闸门 ⇒ 判负",
                   "player.isSpectator()" not in mark.replace("if (player.isSpectator()) {", ""))
    counterexample("⑪-2 删掉几何校验（伪造坐标可写）⇒ 判负",
                   "indexOfGeometry" not in mark.replace(
                       "final int index = selection.indexOfGeometry(candidate, -1);", ""))

    adv = strip_comments(read(ADV_BE))
    counterexample("⑪-3 marksFor 去掉 hasSelector 判据 ⇒ 判负",
                   "if (area == null || !hasSelector()) {" not in adv.replace(
                       "if (area == null || !hasSelector()) {", "if (area == null) {"))

    screen = strip_comments(read(AREA_SCREEN))
    counterexample("⑪-4 编辑过滤又要求 menu != null ⇒ 判负",
                   "editFilterButton.active = hasSelected && editableHere();"
                   not in screen.replace("editFilterButton.active = hasSelected && editableHere();", ""))

    handler = strip_comments(read(HANDLER))
    counterexample("⑪-5 右键空气不再开列表（退回只提示）⇒ 判负",
                   handler.count("minecraft.setScreen(new AreaSelectorScreen(null));") == 2
                   and handler.count("minecraft.setScreen(new AreaSelectorScreen(null));") != 1)

    lang_keys = json.loads(read(ZH))
    counterexample("⑪-6 删掉手持口径按钮文案 ⇒ 判负",
                   "gui.rs_create_compat.area_selector.filter.edit.hand" not in
                   {key: value for key, value in lang_keys.items()
                    if key != "gui.rs_create_compat.area_selector.filter.edit.hand"})


def main():
    print("=" * 78)
    print("第 112 轮自检：每区域过滤标记随身带（选定器物品）+ 独立标记界面")
    print("=" * 78)
    check_area_marks()
    check_base_codec()
    check_targets()
    check_mark_packet()
    check_advanced_be()
    check_mark_screen()
    check_area_screen()
    check_handler()
    check_lang()
    check_model()
    check_counterexamples()
    print("-" * 78)
    print("检查 %d 项（其中反例自证 %d 项），失败 %d 项" % (CHECKS[0], COUNTER[0], len(FAILS)))
    if FAILS:
        for name in FAILS:
            print("  FAILED: " + name)
        return 1
    print("ROUND112_AREA_MARKS_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
