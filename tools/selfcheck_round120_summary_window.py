#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 120 轮自检：「执行仓单元样板汇总」窗口的「看着 A 点到 B」（第 119 轮复核 §F3）。

背景（docs/ROUND119_AUDIT_SEQUENCE_CHAMBER.md §F3）
----------------------------------------------------
* 汇总界面每行 4 格是该执行仓 54 格单元样板槽的**滑动窗口**，服务端每 ``POLL_TICKS = 20``
  tick 把每行的 ``rowStart[row]`` 前移一格；
* 旧实现**只在 ``getCarried()`` 非空时暂停轮转** ⇒ 「盯住某格看 1 秒再点」时，点击包到达服务端时
  服务端早已轮转过，而客户端还在画旧映射 ⇒ 点到的是**相邻那张样板**（取错 / 放错）；
* 旧实现 ``removeItem`` / ``setItem`` 都会当场 ``rebuildWindow()`` ⇒ **同一次点击内**的「取用」与
  「写回」可能落在两份不同映射上；跨两次点击（拿起 → 放回）时压缩表整体前移一位，
  放回去会和相邻那张**交换**（样板落到同一台仓的另一个槽）。

本轮的实现（方案 A + 安全窗，见 docs/ROUND120_SUMMARY_WINDOW.md）
----------------------------------------------------------------
1. ``clicked()`` 记「本行最近被点过」的 tick；``shouldAdvanceRow()`` 在本行最近
   ``INTERACT_QUIET_TICKS = 8`` tick 内被点过时**不轮转**（手上拿东西照旧不轮转）；
2. 本行刚轮转完的 ``ROTATE_SETTLE_TICKS = 4`` tick 内，该行窗口格上的点击**一律不执行**，
   改为 ``pendingFullResync`` ⇒ 下一次 ``broadcastChanges()`` 走 ``broadcastFullState()``
   ——**宁可不响应一次点击，也绝不把「A 格」点成相邻样板**；
3. 一次点击（含原版内部间接调用的 ``quickMoveStack``）共用一份 ``rowStart / rowSlotCount /
   rowSlotMap`` 快照 ⇒ 取用与写回永远指向同一张样板；
4. 被拿空的槽在本轮转周期内当作「洞」保留在压缩表里 ⇒ 其它格不位移，「拿起又放回」落回原槽；
5. 只有「真实样板张数 > 4」才轮转；样板不足 4 张时空位显示为空（旧实现会 4 格显示同一张）。

本脚本三条腿
------------
① **源码锚点**：正例必须全部命中，每条锚点配一个「坏写法」反例证明判据会判负；
② **行为模型**：Python 逐字复刻「轮转闸 + 一次点击一份快照 + 洞 + 安全窗」，端到端跑
   「服务端 tick / 客户端延迟 / 点击」三件套，断言：刚交互过不前进、无交互正常前进、
   **点击得到的就是当时看到的那张**、拿起又放回落回原槽、安全窗内点击被丢弃且状态不变；
③ **反例自证**：把旧逻辑（只在 ``getCarried`` 非空时暂停、点击内先 rebuild 再写）拼回源码副本 /
   行为模型，断言①的判据与②的断言**真的判负**。

用法：python tools/selfcheck_round120_summary_window.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
MENU = os.path.join(PKG, "menu", "ChamberUnitsSummaryMenu.java")
SCREEN = os.path.join(PKG, "client", "screen", "ChamberUnitsSummaryScreen.java")
DOC = os.path.join(ROOT, "docs", "ROUND120_SUMMARY_WINDOW.md")

# 与 Java 侧必须逐字一致的设计常量
POLL_TICKS = 20
INTERACT_QUIET_TICKS = 8
ROTATE_SETTLE_TICKS = 4
ROW_COLS = 4
VISIBLE_ROWS = 5
UNIT_SLOTS = 54

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
# 文本工具（与第 116～119 轮同一套）
# ======================================================================================

def strip_comments(text):
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


def brace_body(text, signature):
    """按签名在**去注释后的源码**里定位方法体，返回大括号内的原文（找不到返回 ''）。"""
    idx = text.find(signature)
    if idx < 0:
        return ""
    start = text.find("{", idx + len(signature))
    if start < 0:
        return ""
    depth = 0
    i = start
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start + 1:i]
        i += 1
    return ""


# ======================================================================================
# 判据（正例与反例共用同一份，避免「检查写两套」导致反例失真）
# ======================================================================================

def rotation_gated_by_recent_click(body):
    """轮转判据含「最近交互时间戳」：最近 INTERACT_QUIET_TICKS 内被点过就不前移。"""
    return ("if (lastClickTick >= 0 && now - lastClickTick < INTERACT_QUIET_TICKS) {"
            in body
            and "return false;" in body
            and "isRotateSettling(now, lastRotateTick)" in body)


def rotation_gated_by_settle(body):
    """轮转判据也挡住「自己上一次轮转还没出安全窗」。"""
    settle = body[body.find("return !isRotateSettling(now, lastRotateTick);"):]
    return "return !isRotateSettling(now, lastRotateTick);" in body and settle != ""


def should_advance_requires_more_than_row_cols(body):
    """只有「真实样板张数 > ROW_COLS」才轮转（旧实现是 > 1，2 张样板就会来回晃）。"""
    return "if (slotCount <= ROW_COLS || !carriedEmpty) {" in body


def click_records_interaction(body):
    """clicked() 必须在本行窗口格上记时间戳（否则「最近交互」判据永远拿不到值）。"""
    return "rowLastClickTick[row] = now;" in body


def click_settle_guard_drops(body):
    """安全窗内点击「不执行 + 待重同步」：跳过 super.clicked 并 return。"""
    guard = body.find("isRotateSettling(now, rowLastRotateTick[row])")
    if guard < 0:
        return False
    tail = body[guard:]
    drop = tail.find("pendingFullResync = true;")
    ret = tail.find("return;")
    sup = tail.find("super.clicked(")
    return drop > 0 and ret > drop and (sup < 0 or sup > ret)


def click_uses_one_snapshot(body):
    """一次点击只用一份映射快照：begin 在 super.clicked 之前、end 在 finally 里。"""
    begin = body.find("beginClickSnapshot();")
    if begin < 0:
        return False
    sup = body.find("super.clicked(", begin)       # clicked 里第一处 super 是客户端直通分支，要跳过后找
    fin = body.find("} finally {", begin)
    end = body.find("endClickSnapshot();", begin)
    return sup > begin and fin > sup and end > fin


def snapshot_is_nested_safe(begin_body, end_body):
    """嵌套调用（clicked → quickMoveStack）只快照一次，且最外层结束时才复位。"""
    return ("if (terminal == null || clickDepth++ > 0) {" in begin_body
            and "clickSnapshotActive = true;" in begin_body
            and "if (terminal == null || clickDepth <= 0) {" in end_body
            and "if (--clickDepth == 0) {" in end_body
            and "clickSnapshotActive = false;" in end_body)


def quick_move_records_interaction(body):
    """Shift 点击（同样是一次交互）也要让轮转让路。"""
    return "rowLastClickTick[row] = nowTick();" in body


def broadcast_resyncs_on_pending(body):
    """丢弃过点击后必须全量重同步（否则客户端会一直画旧内容 / 光标错到底）。"""
    pend = body.find("if (pendingFullResync) {")
    if pend < 0:
        return False
    tail = body[pend:]
    return ("broadcastFullState();" in tail
            and tail.find("broadcastFullState();") < tail.find("return;"))


def settle_window_skips_diff_sync(body):
    """重同步之后不再走 super.broadcastChanges()（否则 remoteSlots 已被客户端预测污染）。"""
    pend = body.find("if (pendingFullResync) {")
    if pend < 0:
        return False
    tail = body[pend:]
    ret = tail.find("return;")
    sup = tail.find("super.broadcastChanges();")
    return ret > 0 and (sup < 0 or sup > ret)


def mapping_reads_snapshot(body):
    """mapToUnitSlot 在点击期间读快照（取用 / 写回同一张样板）。"""
    return ("final boolean snapshot = clickSnapshotActive;" in body
            and "snapshot ? rowSlotCount[row] : rowSlotCount[row]" not in body
            and "snapshot ? snapCount[row] : rowSlotCount[row]" in body
            and "snapshot ? snapStart[row] : rowStart[row]" in body
            and "snapshot ? snapMap[row] : rowSlotMap[row]" in body)


def snapshot_copies_mapping(body):
    """快照必须是逐行深拷贝（rowSlotMap 是原地复用的数组，拷引用等于没拷）。"""
    return ("snapStart[row] = rowStart[row];" in body
            and "snapCount[row] = rowSlotCount[row];" in body
            and "System.arraycopy(rowSlotMap[row], 0, snapMap[row], 0, snapMap[row].length);"
            in body)


def remove_keeps_hole(body):
    """拿空的槽留「洞」（本轮转周期内保留空位），其它格不位移。"""
    return ("if (chamber.unitSlots.getItem(unitSlot).isEmpty()) {" in body
            and "rowHoles[index / ROW_COLS].set(unitSlot);" in body)


def rebuild_includes_holes(body):
    """重建压缩表时把「洞」当作一个空位条目（否则洞一重建就消失）。"""
    return ("} else if (holes.get(i)) {" in body
            and "map[count++] = i;" in body
            and "holes.clear(i);" in body)


def holes_cleared_on_rotation(body):
    """轮转是洞周期的边界：轮转时必须清洞（否则永久留空位）。"""
    return "rowHoles[row].clear();" in body


def holes_cleared_on_page_change(body):
    """换页 / 行换了执行仓时清洞（旧洞的下标对新行无意义）。"""
    return "clearAllHoles();" in body


def window_index_empty_beyond_count(body):
    """样板不足 4 张时，超出的格返回 -1（空位），不是 floorMod 循环重复同一张。"""
    return ("if (count <= 0 || col < 0 || col >= count || col >= ROW_COLS) {" in body
            and "return -1;" in body
            and "Math.floorMod(start + col, count)" in body)


def advance_start_bounded(body):
    """起点前移必须取模（旧实现 rowStart++ 会无限增长）。"""
    return "Math.floorMod(start + 1, count)" in body


def rotation_clears_holes_in_loop(broadcast_body):
    """broadcastChanges 的轮转分支里同时更新 start / tick / 洞。"""
    return ("rowStart[row] = advanceStart(rowStart[row], rowPatternCount[row]);"
            in broadcast_body
            and "rowLastRotateTick[row] = now;" in broadcast_body
            and "rowHoles[row].clear();" in broadcast_body)


def pattern_count_drives_rotation(broadcast_body):
    """轮转判据用「真实样板张数」（洞不算），避免 4 张样板的行被一个洞带得开始晃。"""
    return ("shouldAdvanceRow(rowPatternCount[row], carriedEmpty, now,"
            in broadcast_body
            and "shouldAdvanceRow(rowSlotCount[row]" not in broadcast_body)


def keeps_clear_content_noop(source):
    """回归：Container#clearContent() 仍必须是空操作（死亡掉落不许抹掉执行仓样板）。"""
    return "public void clearContent() {" in source


def keeps_slot_max_one(source):
    """回归：窗口格仍然一格一张（getMaxStackSize == 1）。"""
    return source.count("return 1;") >= 2


def keeps_accepts_unit_gate(source):
    """回归：放进去仍走 acceptsUnit（类型 + 已绑定 + 配方匹配），没有旁路。"""
    return ("if (!SequenceUnitPatternItem.isUnitPattern(stack)) {" in source
            and "return chamber != null && chamber.acceptsUnit(stack);" in source)


# ======================================================================================
# ① 源码锚点（正例）
# ======================================================================================

def group_anchors():
    print("\n=== ① 源码锚点（当前实现必须命中；每条都配反例） ===")
    raw = read(MENU)
    code = strip_comments(raw)
    clicked = brace_body(code, "public void clicked(")
    broadcast = brace_body(code, "public void broadcastChanges()")
    should = brace_body(code, "static boolean shouldAdvanceRow(")
    window_idx = brace_body(code, "static int windowIndex(")
    advance = brace_body(code, "static int advanceStart(")
    map_to = brace_body(code, "int mapToUnitSlot(")
    begin_snap = brace_body(code, "private void beginClickSnapshot(")
    end_snap = brace_body(code, "private void endClickSnapshot(")
    rebuild = brace_body(code, "void rebuildWindow()")
    remove_item = brace_body(code, "public ItemStack removeItem(final int index, final int amount)")
    quick = brace_body(code, "public ItemStack quickMoveStack(")

    check("①-1 clicked / broadcastChanges / rebuildWindow / mapToUnitSlot 四个方法都能定位到方法体",
          bool(clicked) and bool(broadcast) and bool(rebuild) and bool(map_to),
          "clicked=%d broadcast=%d rebuild=%d map=%d" % (
              len(clicked), len(broadcast), len(rebuild), len(map_to)))
    check("①-2 轮转判据含「最近交互时间戳」（最近 8 tick 内被点过 ⇒ 本行不轮转）",
          rotation_gated_by_recent_click(should),
          "shouldAdvanceRow 里没有 lastClickTick / INTERACT_QUIET_TICKS 判据")
    check("①-3 轮转判据同时挡住「上一次轮转还没出安全窗」",
          rotation_gated_by_settle(should),
          "没有 isRotateSettling(now, lastRotateTick) 这一条")
    check("①-4 只有「真实样板张数 > 4」才轮转（旧实现 > 1：2 张样板也会来回晃）",
          should_advance_requires_more_than_row_cols(should),
          "判据不是 slotCount <= ROW_COLS")
    check("①-5 clicked() 在本行窗口格上记交互时间戳（「最近交互」判据的数据来源）",
          click_records_interaction(clicked),
          "没记 rowLastClickTick[row]")
    check("①-6 clicked() 有「轮转安全窗 ⇒ 不执行 + 待全量重同步 + return」的闸",
          click_settle_guard_drops(clicked),
          "安全窗内仍会执行 super.clicked")
    check("①-7 一次点击只用一份映射快照：beginClickSnapshot 在 super.clicked 之前、"
          "endClickSnapshot 在 finally 里",
          click_uses_one_snapshot(clicked),
          "快照边界缺失 / 位置不对")
    check("①-8 Shift 快速移动也记一次交互（窗口 → 背包同样是「动过这一行」）",
          quick_move_records_interaction(quick),
          "quickMoveStack 没记时间戳")
    check("①-9 丢弃过点击 ⇒ broadcastChanges 里走 broadcastFullState() 并直接返回",
          broadcast_resyncs_on_pending(broadcast)
          and settle_window_skips_diff_sync(broadcast),
          "pendingFullResync 没有被消费 / 没有全量重同步")
    check("①-10 mapToUnitSlot 点击期间读快照（取用与写回同一张样板）",
          mapping_reads_snapshot(map_to),
          "mapToUnitSlot 仍直接读活映射")
    check("①-11 快照是逐行深拷贝（rowSlotMap 原地复用，拷引用等于没拷）",
          snapshot_copies_mapping(begin_snap),
          "快照没有 arraycopy")
    check("①-12 快照可嵌套、最外层结束才复位（clicked → quickMoveStack 只快照一次）",
          snapshot_is_nested_safe(begin_snap, end_snap),
          "clickDepth 逻辑缺失")
    check("①-13 拿空的槽留「洞」（本轮转周期内保留空位，「拿起又放回」落回原槽）",
          remove_keeps_hole(remove_item),
          "removeItem 没有设洞")
    check("①-14 重建压缩表时把「洞」当一个空位条目（并且洞被填上就作废）",
          rebuild_includes_holes(rebuild),
          "rebuildWindow 没处理 holes")
    check("①-15 轮转清洞 / 换页清洞 / 行换了执行仓清洞",
          holes_cleared_on_rotation(broadcast)
          and holes_cleared_on_page_change(code)
          and "clearAllHoles();" in brace_body(code, "private void refreshChambers()"),
          "洞的生命周期不完整")
    check("①-16 轮转分支用「真实样板张数」，并且 start / tick / 洞三者同源更新",
          rotation_clears_holes_in_loop(broadcast) and pattern_count_drives_rotation(broadcast),
          "轮转分支的字段口径不一致")
    check("①-17 样板不足 4 张时空位显示为空（windowIndex 返回 -1，不再 floorMod 重复同一张）",
          window_index_empty_beyond_count(window_idx),
          "windowIndex 仍是直接取模")
    check("①-18 起点前移取模（旧实现 rowStart++ 无限增长）",
          advance_start_bounded(advance),
          "advanceStart 没有 floorMod")

    # 回归保护：本轮不许顺手改坏的既有硬规则
    check("①-19 回归：WindowContainer#clearContent() 仍是刻意空操作（死亡掉落不抹仓）",
          keeps_clear_content_noop(code),
          "clearContent 有实现体")
    check("①-20 回归：窗口格仍一格一张（两处 getMaxStackSize 都返回 1）",
          keeps_slot_max_one(code),
          "getMaxStackSize 不再是 1")
    check("①-21 回归：放入仍走 acceptsUnit 统一判定（类型 + 已绑定 + 配方匹配）",
          keeps_accepts_unit_gate(code),
          "acceptsUnit 判据被绕开")

    # 常量：自检里用的数字必须与 Java 源码一致（防止两边漂移）
    consts = {
        "POLL_TICKS": POLL_TICKS,
        "INTERACT_QUIET_TICKS": INTERACT_QUIET_TICKS,
        "ROTATE_SETTLE_TICKS": ROTATE_SETTLE_TICKS,
        "ROW_COLS": ROW_COLS,
        "VISIBLE_ROWS": VISIBLE_ROWS,
    }
    bad = []
    for name, value in consts.items():
        m = re.search(r"public static final int " + name + r" = (\d+);", code)
        if not m or int(m.group(1)) != value:
            bad.append("%s: java=%s model=%d" % (name, m.group(1) if m else "?", value))
    check("①-22 设计常量与自检模型逐字一致（POLL=20 / QUIET=8 / SETTLE=4 / 4 格 × 5 行）",
          not bad, "; ".join(bad))

    check("①-23 客户端界面没有被本轮改动（本修复全在服务端菜单；外观一个像素都没动）",
          "ChamberUnitsSummaryMenu.isRotateSettling" not in read(SCREEN)
          and "pendingFullResync" not in read(SCREEN),
          "屏幕端出现了只能服务端有的状态")


# ======================================================================================
# ② 行为模型：逐字复刻「轮转闸 + 一次点击一份快照 + 洞 + 安全窗」
# ======================================================================================

def window_index(start, col, count):
    if count <= 0 or col < 0 or col >= count or col >= ROW_COLS:
        return -1
    return (start + col) % count


def advance_start(start, count):
    return 0 if count <= 0 else (start + 1) % count


def is_rotate_settling(now, last_rotate):
    return last_rotate >= 0 and now - last_rotate <= ROTATE_SETTLE_TICKS


def should_advance_row(slot_count, carried_empty, now, last_click, last_rotate):
    if slot_count <= ROW_COLS or not carried_empty:
        return False
    if last_click >= 0 and now - last_click < INTERACT_QUIET_TICKS:
        return False
    return not is_rotate_settling(now, last_rotate)


class Row(object):
    def __init__(self, slots):
        """slots: 长度 54 的 list，元素 = 样板名（str）或 None。"""
        self.slots = list(slots)
        self.start = 0
        self.last_click = -1
        self.last_rotate = -1
        self.holes = set()
        self.map = []
        self.count = 0
        self.patterns = 0
        self.rebuild()

    def rebuild(self):
        self.map = []
        patterns = 0
        for i, item in enumerate(self.slots):
            if item is not None:
                self.holes.discard(i)
                self.map.append(i)
                patterns += 1
            elif i in self.holes:
                self.map.append(i)
        self.count = len(self.map)
        self.patterns = patterns

    def snapshot(self):
        return (self.start, self.count, list(self.map))


class MenuModel(object):
    """复刻 ChamberUnitsSummaryMenu 的服务端窗口语义（只保留与本 bug 相关的部分）。"""

    def __init__(self, rows, carried=None, old_rotation_gate=False):
        self.rows = rows
        self.carried = carried
        self.poll = 0
        self.depth = 0
        self.snapshot = None
        self.pending_resync = False
        self.old_rotation_gate = old_rotation_gate
        self.resync_count = 0

    # ---- 映射 ----
    def map_to_unit_slot(self, row, col):
        snap = self.snapshot
        if snap is not None:
            start, count, smap = snap[row]
            idx = window_index(start, col, count)
            return -1 if idx < 0 else smap[idx]
        r = self.rows[row]
        idx = window_index(r.start, col, r.count)
        return -1 if idx < 0 else r.map[idx]

    def item_at(self, row, col):
        unit = self.map_to_unit_slot(row, col)
        return None if unit < 0 else self.rows[row].slots[unit]

    def view(self):
        return [[self.item_at(r, c) for c in range(ROW_COLS)] for r in range(len(self.rows))]

    # ---- 服务端 tick ----
    def broadcast_changes(self, now):
        self.poll += 1
        if self.poll >= POLL_TICKS:
            self.poll = 0
            carried_empty = self.carried is None
            for r in self.rows:
                ok = False
                if self.old_rotation_gate:
                    ok = carried_empty and r.patterns > 1
                else:
                    ok = should_advance_row(r.patterns, carried_empty, now,
                                            r.last_click, r.last_rotate)
                if ok:
                    r.start = advance_start(r.start, r.patterns)
                    r.last_rotate = now
                    r.holes.clear()
        for r in self.rows:
            r.rebuild()
        if self.pending_resync:
            self.pending_resync = False
            self.resync_count += 1
            return "full"
        return "diff"

    # ---- 点击 ----
    def begin_snapshot(self):
        self.depth += 1
        if self.depth == 1:
            self.snapshot = [r.snapshot() for r in self.rows]

    def end_snapshot(self):
        self.depth -= 1
        if self.depth == 0:
            self.snapshot = None

    def click(self, slot_id, now, dropped_ok=True):
        row = slot_id // ROW_COLS if 0 <= slot_id < ROW_COLS * len(self.rows) else -1
        if row >= 0:
            self.rows[row].last_click = now
            if is_rotate_settling(now, self.rows[row].last_rotate):
                self.pending_resync = True
                return "dropped"
        self.begin_snapshot()
        try:
            return self.do_pickup(slot_id)
        finally:
            self.end_snapshot()

    def do_pickup(self, slot_id):
        """原版 ClickType.PICKUP 的最小子集：空手 ⇒ 拿走；手上有 ⇒ 放下 / 交换。"""
        row, col = slot_id // ROW_COLS, slot_id % ROW_COLS
        r = self.rows[row]
        unit = self.map_to_unit_slot(row, col)
        if self.carried is None:
            if unit < 0 or r.slots[unit] is None:
                return "noop"
            item = r.slots[unit]
            r.slots[unit] = None
            r.holes.add(unit)          # 拿空的槽留洞（本轮转周期内）
            self.carried = item
            r.rebuild()
            return "took"
        target = unit
        if target < 0:
            target = next((i for i, it in enumerate(r.slots) if it is None), -1)
            if target < 0:
                return "full"
        old = r.slots[target]
        r.slots[target] = self.carried
        self.carried = old
        r.rebuild()
        return "placed"


def patterns(*names):
    """构造一个「样板稀疏分布」的执行仓槽位表：names 依次放进 3,7,12,20,31,45,..."""
    slots = [None] * UNIT_SLOTS
    spread = [3, 7, 12, 20, 31, 45, 2, 9, 14, 27, 33, 50]
    for idx, name in enumerate(names):
        slots[spread[idx % len(spread)] if idx < len(spread) else idx] = name
    return slots


def run_to(model, tick):
    """把服务端跑到 tick（含）。返回最后一次 broadcastChanges 的返回码。"""
    last = "diff"
    for t in range(tick + 1):
        last = model.broadcast_changes(t)
    return last


def group_model():
    print("\n=== ② 行为模型：轮转闸 / 一次点击一份快照 / 洞 / 安全窗 ===")

    # 2-1 无交互 ⇒ 正常前进（每 20 tick 一格）
    model = MenuModel([Row(patterns("P0", "P1", "P2", "P3", "P4", "P5"))])
    starts = []
    for t in range(0, 61):
        model.broadcast_changes(t)
        if t in (19, 39, 59):
            starts.append(model.rows[0].start)
    check("②-1 无交互：行窗口每 20 tick 前移一格（start = 1,2,3）",
          starts == [1, 2, 3], "starts=%r" % (starts,))

    # 2-2 刚交互过 ⇒ 那一次轮转被推迟（拿起 → 放回 = 两次交互，光标最后是空的）
    model = MenuModel([Row(patterns("P0", "P1", "P2", "P3", "P4", "P5"))])
    run_to(model, 90)
    start_before = model.rows[0].start
    for t in range(91, 95):
        model.broadcast_changes(t)
    model.click(1, 95)
    model.click(1, 96)                       # 放回原格：光标回到空、样板张数回到 6
    click_tick = 96
    next_rotation = None
    for t in range(97, 200):
        before = model.rows[0].last_rotate
        model.broadcast_changes(t)
        if model.rows[0].last_rotate != before:
            next_rotation = model.rows[0].last_rotate
            break
    check("②-2 刚交互过 ⇒ 下一次轮转被推到「安静 ≥ %d tick」之后（本轮实际在 +%s tick 才转），"
          "而不是照旧每 20 tick 硬转" % (INTERACT_QUIET_TICKS,
                                    "?" if next_rotation is None else next_rotation - click_tick),
          next_rotation is not None
          and next_rotation - click_tick >= INTERACT_QUIET_TICKS
          and model.rows[0].start == advance_start(start_before, model.rows[0].patterns),
          "start_before=%d next_rotation=%r start=%d" % (
              start_before, next_rotation, model.rows[0].start))

    # 2-3 持续点击 ⇒ 轮转一直不前进（这正是玩家「正在操作」时该有的样子）
    model = MenuModel([Row(patterns("P0", "P1", "P2", "P3", "P4", "P5"))])
    start = model.rows[0].start
    for t in range(0, 200):
        if t % 5 == 0 and not is_rotate_settling(t, model.rows[0].last_rotate):
            model.click(0, t)                # 每 5 tick 点一下
        model.broadcast_changes(t)
    check("②-3 玩家持续点同一行（每 5 tick 一下）⇒ 200 tick 内该行一次都没轮转",
          model.rows[0].start == start and model.rows[0].last_rotate == -1,
          "start=%d last_rotate=%d" % (model.rows[0].start, model.rows[0].last_rotate))

    # 2-4 端到端「点击得到的就是当时看到的那张」：客户端单程延迟 LAT = 0..6 tick
    #     * 2-4a：正常联机带宽（单程 ≤ 2 tick，即 RTT ≤ 200 ms）——一次错样板都不许有；
    #     * 2-4b：全带宽——任何一次「点错」都只可能发生在**已出安全窗**的到达时刻
    #       （证明闸门机制与文档写的完全一致，不会在安全窗内点错）；
    #     * 2-4c：安全窗内被丢弃的点击**绝不改变物品/光标**（丢的是点击，不是物品）。
    bad_fast = []        # 单程 ≤ 2 tick（正常联机）里的点错案例：一条都不许有
    bad_inside = []      # 到达时仍在 4 tick 安全窗内却点错：一条都不许有（闸门必须真生效）
    dropped_moved = []   # 被丢弃的点击却改动了光标：一条都不许有
    slow_cases = 0       # 单程 > 2 tick 的点错案例（已声明的残余风险，仅计数）
    for lat in range(0, 7):
        for click_tick in range(0, 80):
            model = MenuModel([Row(patterns("P0", "P1", "P2", "P3", "P4", "P5"))])
            views = []
            server_tick = click_tick + lat          # 点击包到达服务端的那一拍
            for t in range(0, server_tick):         # 到达之前服务端跑过的 tick
                model.broadcast_changes(t)
                views.append(model.view())
            # 客户端在 click_tick 这一帧看到的是「已送达」里最新的那一份
            delivered = [s for s in range(len(views)) if s + lat <= click_tick]
            if not delivered:
                continue
            seen = views[delivered[-1]]
            carried_before = model.carried
            rotate_before = model.rows[0].last_rotate
            result = model.click(1, server_tick)
            if result == "dropped":
                if model.carried is not carried_before:
                    dropped_moved.append((lat, click_tick))
                continue
            if result != "took" or model.carried == seen[0][1]:
                continue
            pair = (lat, click_tick, seen[0][1], model.carried)
            if lat <= 2:
                bad_fast.append(pair)
            if rotate_before >= 0 and server_tick - rotate_before <= ROTATE_SETTLE_TICKS:
                bad_inside.append(pair)
            if lat > 2:
                slow_cases += 1
    check("②-4a 正常延迟（单程 ≤ 2 tick / RTT ≤ 200 ms，共 1400 组场景）："
          "点击拿到的一定是当时看到的那张，没有任何例外", not bad_fast,
          "首批异常 %r" % (bad_fast[:4],))
    check("②-4b 任何一次「点错」都不可能发生在到达时刻仍在 4 tick 安全窗内"
          "（闸门真的生效）；单程 > 2 tick 的残余窗口共 %d 例，属已声明的残余风险"
          % slow_cases, not bad_inside, "安全窗内点错 %r" % (bad_inside[:4],))
    check("②-4c 安全窗内被丢弃的点击从不改变光标内容（只丢一次点击，不丢 / 不错拿物品）",
          not dropped_moved, "异常 %r" % (dropped_moved[:4],))

    # 2-5 拿起又放回 ⇒ 落回原槽、其它格一个都不动
    model = MenuModel([Row(patterns("P0", "P1", "P2", "P3", "P4", "P5"))])
    run_to(model, 0)
    values_before = model.view()
    unit_before = model.map_to_unit_slot(0, 1)
    took = model.click(1, 1)
    view_after_take = model.view()
    back = model.click(1, 2)
    check("②-5 拿起（拿到当时看到的那张）→ 其它格不位移（洞在位）→ 放回落回原槽",
          took == "took"
          and model.carried is None
          and back == "placed"
          and model.rows[0].slots[unit_before] == values_before[0][1]
          and model.view() == values_before,
          "took=%s back=%s unit=%d view=%r vs %r" % (
              took, back, unit_before, model.view(), values_before))
    check("②-6 拿走那一拍：该格显示为空、其它格内容与单位置完全不变",
          view_after_take[0][1] is None
          and view_after_take[0][0] == values_before[0][0]
          and view_after_take[0][2] == values_before[0][2]
          and view_after_take[0][3] == values_before[0][3],
          "taking 后 view=%r" % (view_after_take,))

    # 2-7 洞不跨轮转周期（否则永久留空位）：玩家拿走一张后揣进背包，下一拍轮转就把洞清掉
    model = MenuModel([Row(patterns("P0", "P1", "P2", "P3", "P4", "P5"))])
    run_to(model, 0)
    model.click(1, 1)                        # 拿走 P1
    model.carried = None                     # 玩家把它塞进背包（不再拿在光标上）
    for t in range(2, 41):
        model.broadcast_changes(t)
    check("②-7 「拿走的洞」只活到该行下一次轮转：轮转后洞清零、显示恢复成纯样板（不留永久空位）",
          model.rows[0].holes == set() and model.rows[0].count == 5
          and model.rows[0].last_rotate >= 0,
          "holes=%r count=%d last_rotate=%d" % (
              sorted(model.rows[0].holes), model.rows[0].count, model.rows[0].last_rotate))

    # 2-8 只有「样板多于 4 张」才轮转
    never = True
    detail = ""
    for n in (1, 2, 3, 4):
        model = MenuModel([Row(patterns(*["P%d" % i for i in range(n)]))])
        for t in range(0, 120):
            model.broadcast_changes(t)
        if model.rows[0].start != 0 or model.rows[0].last_rotate != -1:
            never = False
            detail = "n=%d start=%d last_rotate=%d" % (n, model.rows[0].start,
                                                       model.rows[0].last_rotate)
            break
    model5 = MenuModel([Row(patterns(*["P%d" % i for i in range(5)]))])
    run_to(model5, 25)
    check("②-8 样板 1..4 张：永不轮转（都看得见，不该晃）；5 张：正常轮转",
          never and model5.rows[0].start == 1,
          detail or "5 张时 start=%d" % model5.rows[0].start)

    # 2-9 样板不足 4 张 ⇒ 空位显示为空，不是同一张重复 4 次（旧实现的显示 bug）
    model = MenuModel([Row(patterns("A", "B"))])
    run_to(model, 0)
    view = model.view()[0]
    check("②-9 只有 2 张样板：窗口显示 [A, B, 空, 空]（旧实现会显示 [A,B,A,B]）",
          view == ["A", "B", None, None], "view=%r" % (view,))

    # 2-10 安全窗：轮转后 SETTLE_TICKS 内的点击被丢弃且状态完全不变
    model = MenuModel([Row(patterns(*["P%d" % i for i in range(6)]))])
    for t in range(0, 20):                    # 第 20 次 broadcastChanges 触发轮转（t = 19）
        model.broadcast_changes(t)
    rotate_tick = model.rows[0].last_rotate
    slots_before = list(model.rows[0].slots)
    within = [model.click(1, rotate_tick + d) for d in range(1, ROTATE_SETTLE_TICKS + 1)]
    state_kept = model.rows[0].slots == slots_before and model.carried is None
    pending = model.pending_resync
    sync = model.broadcast_changes(rotate_tick + 1)
    after_window = model.click(1, rotate_tick + ROTATE_SETTLE_TICKS + 1)
    check("②-10 轮转后 4 tick 内的点击全部被丢弃（仅置 pendingFullResync、状态一点不动），"
          "下一次 broadcastChanges 走全量重同步，第 5 tick 起点击恢复正常",
          rotate_tick == 19
          and all(res == "dropped" for res in within)
          and state_kept
          and pending
          and sync == "full"
          and model.resync_count == 1
          and after_window == "took",
          "rotate=%d within=%r state_kept=%s pending=%s sync=%s after=%s" % (
              rotate_tick, within, state_kept, pending, sync, after_window))

    # 2-11 被丢弃的点击不会改变任何样板（守恒）
    model = MenuModel([Row(patterns(*["P%d" % i for i in range(6)]))])
    before = sorted(x for x in model.rows[0].slots if x)
    for t in range(0, 25):
        model.broadcast_changes(t)
    model.click(1, 22)                        # 落在安全窗内（轮转在 t=19）
    after = sorted(x for x in model.rows[0].slots if x)
    check("②-11 安全窗内点击被丢弃后：执行仓里的样板一张不多一张不少（只丢了一次点击，不丢物品）",
          before == after and model.carried is None, "before=%r after=%r" % (before, after))

    # 2-12 空手/手上拿东西两种暂停条件都还在
    model = MenuModel([Row(patterns(*["P%d" % i for i in range(6)]))], carried="外来的样板")
    for t in range(0, 60):
        model.broadcast_changes(t)
    check("②-12 手上拿着东西 ⇒ 照旧不轮转（原有行为保留）",
          model.rows[0].start == 0 and model.rows[0].last_rotate == -1,
          "start=%d" % model.rows[0].start)

    # 2-13 映射唯一性：同一行不会有两格指向同一个真实槽（洞只会让格子变空）
    dup = []
    for count in range(0, 9):
        for start in range(0, max(1, count)):
            used = []
            for col in range(ROW_COLS):
                idx = window_index(start, col, count)
                if idx >= 0:
                    used.append(idx)
            if len(used) != len(set(used)):
                dup.append((start, count, used))
    check("②-13 windowIndex：同一行四格永不指向同一个压缩下标（旧实现 count<4 时会重复）",
          not dup, "重复样例 %r" % (dup[:3],))

    # 2-14 「一次点击内只算一次映射」：点击途中压缩表被重建，本次点击的目标不许变
    model = MenuModel([Row(patterns("P0", "P1", "P2", "P3", "P4", "P5"))])
    run_to(model, 0)
    target_before = model.map_to_unit_slot(0, 1)
    model.begin_snapshot()
    model.rows[0].slots[3] = None             # 外部变化（别的来源挖走了第 1 个压缩位）
    model.rows[0].rebuild()
    inside = model.map_to_unit_slot(0, 1)
    model.end_snapshot()
    live_after = model.map_to_unit_slot(0, 1)
    check("②-14 一次点击内映射被冻结：点击途中压缩表重建也不改变本次点击的目标"
          "（旧实现会按新映射写 ⇒ 取用与写回指向两张不同样板）",
          inside == target_before and live_after != target_before,
          "before=%d inside=%d live=%d" % (target_before, inside, live_after))


# ======================================================================================
# ③ 反例自证：旧逻辑 / 坏写法必须被判负
# ======================================================================================

def group_counterexamples():
    print("\n=== ③ 反例自证：旧逻辑 / 坏写法必须判负 ===")
    raw = read(MENU)
    code = strip_comments(raw)

    def body_of(text, signature):
        return brace_body(strip_comments(text), signature)

    # (a) 轮转判据退回「只看手上有没有拿东西」
    bad = raw.replace(
        "if (lastClickTick >= 0 && now - lastClickTick < INTERACT_QUIET_TICKS) {",
        "if (false) {", 1)
    counterexample("③-1 源码副本：把「最近交互」判据改成 if (false)（= 旧实现只在 getCarried 非空暂停）"
                   "⇒ ①-2 判据判负",
                   bad != raw and not rotation_gated_by_recent_click(
                       body_of(bad, "static boolean shouldAdvanceRow(")))

    # (b) 起点不取模（旧 rowStart++）
    bad = raw.replace("return count <= 0 ? 0 : Math.floorMod(start + 1, count);",
                      "return start + 1;", 1)
    counterexample("③-2 源码副本：advanceStart 改成 start + 1（旧实现的 rowStart++）⇒ ①-18 判负",
                   bad != raw and not advance_start_bounded(
                       body_of(bad, "static int advanceStart(")))

    # (c) 安全窗闸去掉（= 旧实现：直接执行点击）
    bad = raw.replace(
        "if (now >= 0 && isRotateSettling(now, rowLastRotateTick[row])) {",
        "if (false) {", 1)
    counterexample("③-3 源码副本：去掉「轮转安全窗 ⇒ 不执行点击」这道闸（正是 F3 的旧行为）"
                   "⇒ ①-6 判负",
                   bad != raw and not click_settle_guard_drops(
                       body_of(bad, "public void clicked(")))

    # (d) setItem 里先 rebuild 再按新映射写（= 旧实现：一次点击两份映射）
    bad = raw.replace(
        "final boolean snapshot = clickSnapshotActive;",
        "final boolean snapshot = false;", 1)
    counterexample("③-4 源码副本：mapToUnitSlot 不再读快照（等于「取用/写回各用一份映射」）"
                   "⇒ ①-10 判负",
                   bad != raw and not mapping_reads_snapshot(
                       body_of(bad, "int mapToUnitSlot(")))

    # (e) 快照拷引用而不是深拷贝
    bad = raw.replace(
        "System.arraycopy(rowSlotMap[row], 0, snapMap[row], 0, snapMap[row].length);",
        "snapMap[row] = rowSlotMap[row];", 1)
    counterexample("③-5 源码副本：快照改成 snapMap[row] = rowSlotMap[row]（拷引用）⇒ ①-11 判负",
                   bad != raw and not snapshot_copies_mapping(
                       body_of(bad, "private void beginClickSnapshot(")))

    # (f) 不留洞（拿走就压缩表前移 = 旧行为，放回去会串位）
    bad = raw.replace(
        "rowHoles[index / ROW_COLS].set(unitSlot);",
        "// removed", 1)
    counterexample("③-6 源码副本：removeItem 不再留洞（旧行为：压缩表立即前移）⇒ ①-13 判负",
                   bad != raw and not remove_keeps_hole(
                       body_of(bad, "public ItemStack removeItem(final int index, final int amount)")))

    # (g) rebuildWindow 不再把洞计入压缩表
    bad = raw.replace("} else if (holes.get(i)) {", "} else if (false) {", 1)
    counterexample("③-7 源码副本：rebuildWindow 不再把「洞」计入压缩表 ⇒ ①-14 判负",
                   bad != raw and not rebuild_includes_holes(
                       body_of(bad, "void rebuildWindow()")))

    # (h) 丢弃点击后不做全量重同步
    bad = raw.replace("                broadcastFullState();", "                // 不同步", 1)
    counterexample("③-8 源码副本：丢弃点击后不给客户端全量重同步（客户端会一直画旧内容）"
                   "⇒ ①-9 判负",
                   bad != raw and not broadcast_resyncs_on_pending(
                       body_of(bad, "public void broadcastChanges()")))

    # (i) clicked 不记交互时间戳
    bad = raw.replace("            rowLastClickTick[row] = now;", "            // 不记", 1)
    counterexample("③-9 源码副本：clicked 不记交互时间戳 ⇒ ①-5 判负",
                   bad != raw and not click_records_interaction(
                       body_of(bad, "public void clicked(")))

    # (j) 行为模型反例：旧逻辑「只在 getCarried 非空时暂停」必须重现「看到 A 点到 B」
    bad_cases = []
    for lat in range(0, 7):
        for click_tick in range(0, 80):
            model = MenuModel([Row(patterns("P0", "P1", "P2", "P3", "P4", "P5"))],
                              old_rotation_gate=True)
            views = []
            server_tick = click_tick + lat
            for t in range(0, server_tick):
                model.broadcast_changes(t)
                views.append(model.view())
            delivered = [s for s in range(len(views)) if s + lat <= click_tick]
            if not delivered:
                continue
            seen = views[delivered[-1]]
            result = model.click(1, server_tick)
            if result == "took" and model.carried != seen[0][1]:
                bad_cases.append((lat, click_tick, seen[0][1], model.carried))
    counterexample("③-10 行为模型：旧逻辑（只在 getCarried 非空时暂停）在同样的端到端场景里"
                   "必然出现「看到 A 点到 B」（首个错例：延迟 %d tick、第 %d tick 点击时"
                   "看到 %r 却拿到 %r）⇒ ②-4 的断言会判负"
                   % (bad_cases[0] if bad_cases else (0, 0, "?", "?")),
                   len(bad_cases) > 0)

    # (k) 行为模型反例：去掉快照 ⇒ 点击途中映射会跟着压缩表变（取用与写回指向两张样板）
    class NoSnapshotMenu(MenuModel):
        def begin_snapshot(self):
            pass

        def end_snapshot(self):
            pass

    model = NoSnapshotMenu([Row(patterns("P0", "P1", "P2", "P3", "P4", "P5"))])
    run_to(model, 0)
    target_before = model.map_to_unit_slot(0, 1)
    model.begin_snapshot()
    model.rows[0].slots[3] = None
    model.rows[0].rebuild()
    inside = model.map_to_unit_slot(0, 1)
    model.end_snapshot()
    counterexample("③-11 行为模型：去掉「一次点击一份映射」后，点击途中的压缩表变化会改变本次点击"
                   "的目标（%d → %d），即「取用与写回各用一份映射」⇒ ②-14 的断言会判负"
                   % (target_before, inside),
                   inside != target_before)

    # (l) 行为模型反例：不留洞 ⇒ 拿走一格后整行前移（其它格内容全变、放回去串位）
    class NoHoleMenu(MenuModel):
        def do_pickup(self, slot_id):
            row, col = slot_id // ROW_COLS, slot_id % ROW_COLS
            r = self.rows[row]
            unit = self.map_to_unit_slot(row, col)
            if self.carried is None:
                if unit < 0 or r.slots[unit] is None:
                    return "noop"
                item = r.slots[unit]
                r.slots[unit] = None
                self.carried = item
                r.rebuild()
                return "took"
            return MenuModel.do_pickup(self, slot_id)

    model = NoHoleMenu([Row(patterns("P0", "P1", "P2", "P3", "P4", "P5"))])
    run_to(model, 0)
    values_before = model.view()
    model.click(1, 1)
    shifted = model.view()
    counterexample("③-12 行为模型：不留洞（旧行为）时，拿走一格会让整行前移、"
                   "其它格内容全变 ⇒ ②-6 的断言会判负",
                   shifted[0][0] != values_before[0][0]
                   or shifted[0][2] != values_before[0][2]
                   or shifted[0][3] != values_before[0][3],
                   "shifted=%r before=%r" % (shifted, values_before))

    # (m) 行为模型反例：不留洞 ⇒ 「拿起又放回」落到别的槽（②-5 判负）
    model = NoHoleMenu([Row(patterns("P0", "P1", "P2", "P3", "P4", "P5"))])
    run_to(model, 0)
    values_before = model.view()
    unit_before = model.map_to_unit_slot(0, 1)
    model.click(1, 1)                          # 拿起
    model.click(1, 2)                          # 放回同一格
    moved = model.rows[0].slots[unit_before]
    counterexample("③-13 行为模型：不留洞时「拿起→放回」不会落回原槽（原槽变成 %r、"
                   "手里多了一张别的样板 %r）⇒ ②-5 的断言会判负"
                   % (moved, model.carried),
                   moved != values_before[0][1] or model.carried is not None)

    # (n) 行为模型反例：窗口映射直接用 floorMod（旧实现 count<4 时 4 格重复）
    def old_window_index(start, col, count):
        if count <= 0:
            return -1
        return (start + col) % count

    model = MenuModel([Row(patterns("A", "B"))])
    run_to(model, 0)
    row = model.rows[0]
    old_view = []
    for c in range(ROW_COLS):
        idx = old_window_index(row.start, c, row.count)
        old_view.append(None if idx < 0 else row.slots[row.map[idx]])
    counterexample("③-14 行为模型：windowIndex 退回 floorMod（旧实现）时 2 张样板会显示成 [A,B,A,B]"
                   " ⇒ ②-9 的断言会判负（old_view=%r vs 新实现 %r）" % (old_view, model.view()[0]),
                   old_view == ["A", "B", "A", "B"] and model.view()[0] == ["A", "B", None, None])

    # (n) 文档缺口的自证：本轮文档必须存在且写明选型（不实机验证不许声称已实机验证）
    doc = read(DOC) if os.path.exists(DOC) else ""
    counterexample("③-15 文档副本：把「未实机验证」的诚实声明删掉后，文档检查会判负",
                   bool(doc) and "未实机" in doc,
                   "文档缺失 / 没有未验证声明")


def group_doc():
    print("\n=== ④ 文档（≤200 行，位置 / 旧行为 / 新行为 / 选型理由 / 自检 / 未验证清单） ===")
    if not os.path.exists(DOC):
        check("④-1 docs/ROUND120_SUMMARY_WINDOW.md 存在", False, "文件不存在")
        return
    doc = read(DOC)
    lines = doc.count("\n") + 1
    check("④-1 docs/ROUND120_SUMMARY_WINDOW.md 存在且 ≤ 200 行", lines <= 200, "行数 %d" % lines)
    needed = ["位置", "旧行为", "新行为", "选型", "自检", "未验证"]
    missing = [n for n in needed if n not in doc]
    check("④-2 文档含 位置 / 旧行为 / 新行为 / 选型理由 / 自检覆盖 / 未验证清单 六段",
          not missing, "缺 %r" % (missing,))
    check("④-3 文档写清「宁可不响应一次点击，也不换错样板」的取舍与残余风险（延迟 > 安全窗）",
          "残余" in doc or "延迟" in doc, "没有残余风险说明")
    check("④-4 文档明确「未实机点过界面」（不许声称实机验证过）",
          "实机" in doc, "没有实机相关声明")


def main():
    print("第 120 轮自检：汇总窗口「看着 A 点到 B」（第 119 轮复核 F3）")
    group_anchors()
    group_model()
    group_counterexamples()
    group_doc()
    print("\n断言 %d 条（其中反例 %d 条），失败 %d 条" % (CHECKS[0], COUNTER[0], len(FAILS)))
    if FAILS:
        print("FAILED:")
        for name in FAILS:
            print("  - " + name)
        return 1
    print("ALL_PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
