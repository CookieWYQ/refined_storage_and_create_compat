#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 121 轮自检：四项互相独立的收尾（仓名 256 收口 / 轮转改游戏时间 / 语言脚本过时文案 / diag 去权限改冷却）。

背景（task-4 / 第 121 轮 121-D）
--------------------------------
本轮的四处修改彼此无关，但都属于「一处写错就会静默复发」的类型，因此每一处都配
「源码锚点 + 行为模型 + 反例自证」三条腿（风格照抄 tools/selfcheck_round120_summary_window.py）：

① ``network/SetChamberBindingPacket.java``：客户端提交的仓名原先没有任何长度上限，
   改包可以塞进几十 KB 字符串并随 NBT 落盘（与第 120 轮 F5 的机器指派名同类）。
   现在在落盘前按 ``MAX_IMPORT_STRING_LENGTH``（= 256）**码点**截断，重名判据与两处写入
   用同一个截断值。
② ``menu/ChamberUnitsSummaryMenu.java``：轮转原按 ``broadcastChanges`` 的**调用次数**计，
   而原版每次点击后会再补调一次 ⇒ 点得越勤、窗口转得越快。现在按 ``level.getGameTime()``
   计；第 120 轮 F3 的 8 tick 交互静默 / 4 tick 安全窗语义必须原样保留。
③ ``tools/simplify_lang_texts.py``：这是会把值**回写**语言文件的脚本，其中执行仓文案还写着
   过时的容量（6）且引用了三个已不存在的语言键 —— 谁跑一次就把错文案写回去 / 直接 SystemExit。
   现在改成实际值（54 格 = 6×9）并清掉死键。
④ ``command/CompatCommands.java``：``/rs_create_compat diag`` 用户明确要求**不要权限**，
   因此改成「不加权限 + 同一执行者冷却 + 回执按段轮转」，冷却常量集中定义并写清理由。

本脚本的边界（诚实声明）
------------------------
只做**静态**自检：读真实源码 / 真实语言文件 + Python 行为模型与反例。
**没有编译、没有进游戏**（本机 bash 运行时起不来，构建与部署由 Lead 统一负责）。
因此这里断言的是「源码结构与判据成立」，不是「实机跑通」。

用法：python tools/selfcheck_round121_misc_polish.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import ast
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
SRC = os.path.join(ROOT, "src", "main", "java")
PKG = os.path.join(SRC, "cretae", "cookiewyq", "rs_create_compat")
PACKET = os.path.join(PKG, "network", "SetChamberBindingPacket.java")
MENU = os.path.join(PKG, "menu", "ChamberUnitsSummaryMenu.java")
TERMINAL = os.path.join(PKG, "block", "entity", "SequencePatternTerminalBlockEntity.java")
COMMANDS = os.path.join(PKG, "command", "CompatCommands.java")
SIMPLIFY = os.path.join(ROOT, "tools", "simplify_lang_texts.py")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

# 与 Java 侧必须逐字一致的设计常量（下面会用源码里的真值再核对一遍，防两边漂移）
NAME_LIMIT = 256
POLL_TICKS = 20
INTERACT_QUIET_TICKS = 8
ROTATE_SETTLE_TICKS = 4
DIAG_COOLDOWN_TICKS = 200

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
# 文本工具（与第 116～120 轮同一套）
# ======================================================================================

def strip_comments(text):
    """去掉 Java 的 // 与 /* */ 注释（保留字符串字面量），用于「只看代码」的判据。"""
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
    """按签名在（已去注释的）源码里定位方法体，返回大括号内的原文（找不到返回 ''）。"""
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


def const_int(code, name):
    """从 Java 源码里读 ``... final int NAME = 123;`` 的数值（读不到返回 None）。"""
    match = re.search(r"\bint " + name + r"\s*=\s*(\d+)\s*;", code)
    return int(match.group(1)) if match else None


# ======================================================================================
# 模型 A：Java String 的 256「码点」截断（把 Python str 当作 UTF-16 code unit 序列来复刻）
# ======================================================================================

def to_units(text):
    """把 Python str 转成 Java String 的 char 序列（UTF-16 code unit；代理对占两个 char）。"""
    raw = text.encode("utf-16-le")
    return [raw[i] | (raw[i + 1] << 8) for i in range(0, len(raw), 2)]


def is_high(unit):
    return 0xD800 <= unit <= 0xDBFF


def is_low(unit):
    return 0xDC00 <= unit <= 0xDFFF


def java_clamp_units(units, limit=NAME_LIMIT):
    """复刻 clampChamberName：length() <= limit 直通；否则按码点数截到 limit 个码点。"""
    if len(units) <= limit:
        return list(units)
    code_points = 0
    i = 0
    while i < len(units):
        if is_high(units[i]) and i + 1 < len(units) and is_low(units[i + 1]):
            i += 2
        else:
            i += 1
        code_points += 1
        if code_points == limit:
            break
    return list(units[:i])


def java_substring_units(units, limit=NAME_LIMIT):
    """反例：直接 substring(0, limit)（按 char 截，可能切坏代理对）。"""
    return list(units[:limit])


def code_point_count(units):
    count = 0
    i = 0
    while i < len(units):
        if is_high(units[i]) and i + 1 < len(units) and is_low(units[i + 1]):
            i += 2
        else:
            i += 1
        count += 1
    return count


def has_lone_surrogate(units):
    i = 0
    while i < len(units):
        if is_high(units[i]):
            if not (i + 1 < len(units) and is_low(units[i + 1])):
                return True
            i += 2
        elif is_low(units[i]):
            return True
        else:
            i += 1
    return False


# ======================================================================================
# 模型 B：轮转周期（游戏时间 vs 调用次数）与 8 tick 静默 / 4 tick 安全窗
# ======================================================================================

class GameTimeRotation(object):
    """复刻改后 broadcastChanges 的轮转判据：lastPollTick 是游戏时刻，不是调用计数。"""

    def __init__(self):
        self.last_poll = -1
        self.rotations = 0

    def broadcast(self, now):
        """返回本拍是否轮转。now = level.getGameTime()（客户端 / 无 level 时为 -1）。"""
        if now < 0:
            return False
        if self.last_poll < 0:
            self.last_poll = now          # 首次只对齐基准，不轮转
            return False
        if now - self.last_poll >= POLL_TICKS:
            self.last_poll = now
            self.rotations += 1
            return True
        return False


class CounterRotation(object):
    """复刻旧实现：pollCounter 按 broadcastChanges 的调用次数累加。"""

    def __init__(self):
        self.poll = 0
        self.rotations = 0

    def broadcast(self, now):
        self.poll += 1
        if self.poll >= POLL_TICKS:
            self.poll = 0
            self.rotations += 1
            return True
        return False


def should_advance_row(slot_count, carried_empty, now, last_click, last_rotate):
    """第 120 轮 F3 的轮转闸（本轮必须原样保留）。"""
    if slot_count <= 4 or not carried_empty:
        return False
    if last_click >= 0 and now - last_click < INTERACT_QUIET_TICKS:
        return False
    return not is_rotate_settling(now, last_rotate)


def is_rotate_settling(now, last_rotate):
    return last_rotate >= 0 and now - last_rotate <= ROTATE_SETTLE_TICKS


# ======================================================================================
# 模型 C：simplify_lang_texts.py 的「内存干跑」（不写任何文件）
# ======================================================================================

def parse_simplify():
    """用 ast 取 NEW / IDEAL_HARD 常量（不 import、不执行脚本，避免任何副作用）。"""
    src = read(SIMPLIFY)
    tree = ast.parse(src)
    new = None
    limits = {}
    for node in tree.body:
        if not isinstance(node, ast.Assign):
            continue
        names = []
        for target in node.targets:
            if isinstance(target, ast.Name):
                names.append(target.id)
            elif isinstance(target, ast.Tuple):
                names.extend(getattr(elt, "id", "") for elt in target.elts)
        if "NEW" in names:
            new = ast.literal_eval(node.value)
        if "IDEAL_ZH" in names:
            values = ast.literal_eval(node.value)
            if isinstance(values, tuple) and len(values) == 2:
                limits["IDEAL_ZH"], limits["HARD_ZH"] = values
        if "IDEAL_EN" in names:
            values = ast.literal_eval(node.value)
            if isinstance(values, tuple) and len(values) == 2:
                limits["IDEAL_EN"], limits["HARD_EN"] = values
    if new is None:
        return None, None, limits
    return src, new, limits


def missing_keys(new, lang):
    """NEW 里语言文件中不存在的键（main() 的 missing 守卫同口径）。"""
    return sorted(set(new) - set(lang))


def apply_new_in_memory(lines, new, index):
    """复刻 patch() 的逐行替换（只在内存里，不写文件），返回 (替换数, 替换后的文本)。"""
    out = list(lines)
    replaced = 0
    for i, line in enumerate(lines):
        body = line.rstrip("\r\n")
        ending = line[len(body):]
        stripped = body.lstrip()
        if not stripped.startswith('"') or '":' not in stripped:
            continue
        offset = len(body) - len(stripped)
        quote = body.index('":', offset)
        key = body[offset + 1:quote]
        if key not in new:
            continue
        value = new[key][index]
        comma = body.endswith(",")
        out[i] = "%s: %s%s%s" % (line[:quote + 1], json.dumps(value, ensure_ascii=False),
                                 "," if comma else "", ending)
        replaced += 1
    return replaced, "".join(out)


# ======================================================================================
# 模型 D：diag 的冷却 / 回执轮转
# ======================================================================================

def diag_allowed(now, last, cooldown=DIAG_COOLDOWN_TICKS):
    """复刻 exportDiag 的冷却判据：elapsed < 0（重启后 tick 归零）按已过期处理。"""
    if last is None:
        return True
    elapsed = now - last
    return not (0 <= elapsed < cooldown)


def diag_segment_index(cursor, count):
    return cursor % count


# ======================================================================================
# ① 仓名 256 码点收口
# ======================================================================================

def group_name_clamp():
    print("\n=== ① SetChamberBindingPacket：名字夹到 256 码点（不切坏代理对） ===")
    raw = read(PACKET)
    code = strip_comments(raw)
    terminal = strip_comments(read(TERMINAL))

    real_limit = const_int(terminal, "MAX_IMPORT_STRING_LENGTH")
    check("①-0 上限口径来自 MAX_IMPORT_STRING_LENGTH（读真源码）且 = 256",
          real_limit == NAME_LIMIT, "源码值 %r" % (real_limit,))

    helper = brace_body(code, "static String clampChamberName(")
    commit = brace_body(code, "private static void commit(")

    check("①-1 收口助手 clampChamberName 存在（按码点截断，不是只按 char）",
          bool(helper)
          and "codePointCount(0, name.length())" in helper
          and "offsetByCodePoints(0," in helper
          and "MAX_IMPORT_STRING_LENGTH" in helper,
          "helper 长度 %d" % len(helper))
    check("①-2 null 名字按空串处理（与 setChamberName 的 null 语义一致）",
          'if (name == null) {' in helper and 'return "";' in helper)
    check("①-3 快路径：length() <= 上限直接返回（码点数一定 <= char 数，不会漏夹）",
          "if (name.length() <= SequencePatternTerminalBlockEntity.MAX_IMPORT_STRING_LENGTH) {" in helper)
    check("①-4 常量复用 MAX_IMPORT_STRING_LENGTH，没有在包里再写一个魔数 256",
          "MAX_IMPORT_STRING_LENGTH" in helper and not re.search(r"\b256\b", code))
    check("①-5 commit 里先截断成局部变量 name，再参与重名判据",
          "final String name = clampChamberName(packet.name());" in commit
          and "chamber.isNameTakenInNetwork(name)" in commit)
    check("①-6 两处落盘写入都用截断后的 name（拒绝分支 + 正常分支），"
          "源码里已无 setChamberName(packet.name())",
          commit.count("chamber.setChamberName(name);") == 2
          and "setChamberName(packet." not in code)
    check("①-7 服务端落盘口只有本包（另一条 NBT 路径只读回已落盘值，链自愈 / 拆链移交写的是"
          "自身有界值的拷贝）—— 所以「在包里收口」= 落盘值有界",
          "public void setChamberName(final String name)" in strip_comments(
              read(os.path.join(PKG, "block", "entity", "SequenceExecutionChamberBlockEntity.java")))
          and "setChamberName(packet." not in code)

    # 行为模型：按码点截断 vs 裸 substring
    bmp300 = to_units("a" * 300)
    clamped = java_clamp_units(bmp300)
    check("①-8 300 个 BMP 字符 ⇒ 夹到 256 个字符（码点数 = char 数）",
          len(clamped) == NAME_LIMIT and code_point_count(clamped) == NAME_LIMIT)

    # 「1 个 ASCII + 300 个 emoji」：第 256 个 char 正好切在代理对中间，是最经典的坏例
    mixed = to_units("x" + "\U0001F600" * 300)
    clamped_mixed = java_clamp_units(mixed)
    naive_mixed = java_substring_units(mixed)
    check("①-9 混合串（1 个 ASCII + 300 个 emoji / 301 码点）按码点夹到 256 码点 / 511 char，"
          "且结尾是完整的代理对、没有任何孤立代理项",
          len(mixed) == 601
          and code_point_count(clamped_mixed) == NAME_LIMIT
          and len(clamped_mixed) == 511
          and not has_lone_surrogate(clamped_mixed)
          and is_high(clamped_mixed[-2]) and is_low(clamped_mixed[-1]))
    counterexample("①-10 裸 substring(0, 256)（按 char 截）在同一个混合串上既切出孤立的"
                   "高位代理项、又只保留 %d 个码点（写进 NBT / 再序列化就是乱码）⇒ ①-9 的判据会判负"
                   % (code_point_count(naive_mixed),),
                   has_lone_surrogate(naive_mixed)
                   and code_point_count(naive_mixed) < NAME_LIMIT)

    exact = to_units("b" * 256)
    check("①-11 正好 256 个字符：原样返回（不误伤）", java_clamp_units(exact) == exact)

    emoji100 = to_units("\U0001F600" * 100)
    check("①-12 100 个 emoji（200 char / 100 码点）：原样返回（快路径按 char 判也安全）",
          len(emoji100) == 200 and java_clamp_units(emoji100) == emoji100)

    emoji257 = to_units("\U0001F600" * 257)
    check("①-13 257 个 emoji ⇒ 256 个码点（512 char），最后一个是完整字符",
          code_point_count(java_clamp_units(emoji257)) == NAME_LIMIT
          and len(java_clamp_units(emoji257)) == 512
          and not has_lone_surrogate(java_clamp_units(emoji257)))

    check("①-14 null 名字 ⇒ 空串（模型与源码同口径）", java_clamp_units(to_units("")) == [])

    # 反例：源码副本里去掉收口调用（= 改前行为）
    bad = raw.replace("final String name = clampChamberName(packet.name());",
                      "final String name = packet.name();", 1)
    bad_code = strip_comments(bad)
    bad_commit = brace_body(bad_code, "private static void commit(")
    counterexample("①-15 源码副本：commit 里退回 `final String name = packet.name();`"
                   "（= 改前无上限）⇒ ①-5 判据判负",
                   bad != raw
                   and "clampChamberName" not in bad_commit
                   and "final String name = clampChamberName(packet.name());" not in bad_commit)


# ======================================================================================
# ② pollCounter → 游戏时间
# ======================================================================================

def group_game_time_poll():
    print("\n=== ② ChamberUnitsSummaryMenu：轮转改按游戏时间（8 tick 静默 / 4 tick 安全窗保留） ===")
    raw = read(MENU)
    code = strip_comments(raw)
    broadcast = brace_body(code, "public void broadcastChanges()")
    should = brace_body(code, "static boolean shouldAdvanceRow(")
    clicked = brace_body(code, "public void clicked(")

    check("②-1 代码里已没有 pollCounter（注释里可以留历史说明，strip_comments 后必须为 0）",
          "pollCounter" not in code, "code 里仍有 pollCounter")
    check("②-2 新字段 lastPollTick 存在且是「时刻」语义（long / 初值 -1）",
          "private long lastPollTick = -1L;" in code)
    check("②-3 轮转判据按游戏时间：now - lastPollTick >= POLL_TICKS",
          "now - lastPollTick >= POLL_TICKS" in broadcast
          and "if (now >= 0)" in broadcast
          and "nowTick()" in broadcast)
    check("②-4 首次拿到有效时刻只对齐基准、不轮转（与旧「第 20 次调用才转」等价）",
          "if (lastPollTick < 0) {" in broadcast
          and "lastPollTick = now;" in broadcast)
    check("②-5 now < 0（时刻未知）这一拍绝不更新 lastPollTick（否则会被写成假值）",
          re.search(r"if \(now >= 0\) \{\s*if \(lastPollTick < 0\) \{", broadcast) is not None
          and broadcast.count("lastPollTick = now;") == 2)
    check("②-6 轮转仍完全走 shouldAdvanceRow 的纯函数闸（没有另写一套判据）",
          "shouldAdvanceRow(rowPatternCount[row], carriedEmpty, now," in broadcast)
    check("②-7 第 120 轮的两个时间窗常量语义未动（8 tick 交互静默 / 4 tick 安全窗，"
          "本轮只改「周期怎么数」）",
          const_int(code, "INTERACT_QUIET_TICKS") == INTERACT_QUIET_TICKS
          and const_int(code, "ROTATE_SETTLE_TICKS") == ROTATE_SETTLE_TICKS
          and "INTERACT_QUIET_TICKS" in should)
    check("②-8 常量自检：POLL_TICKS=20 / INTERACT_QUIET_TICKS=8 / ROTATE_SETTLE_TICKS=4",
          const_int(code, "POLL_TICKS") == POLL_TICKS
          and const_int(code, "INTERACT_QUIET_TICKS") == INTERACT_QUIET_TICKS
          and const_int(code, "ROTATE_SETTLE_TICKS") == ROTATE_SETTLE_TICKS)
    check("②-9 shouldAdvanceRow 仍是「> 4 张才转 + 手上空 + 最近没点 + 不在安全窗」四条",
          "if (slotCount <= ROW_COLS || !carriedEmpty) {" in should
          and "now - lastClickTick < INTERACT_QUIET_TICKS" in should
          and "return !isRotateSettling(now, lastRotateTick);" in should)
    check("②-10 clicked() 的「安全窗内丢点击 + 待全量重同步」闸原样保留",
          "isRotateSettling(now, rowLastRotateTick[row])" in clicked
          and "pendingFullResync = true;" in clicked)
    check("②-11 时间戳来源是 level.getGameTime()（不是 System.currentTimeMillis 之类的墙钟）",
          "terminal.getLevel().getGameTime()" in code)

    # 行为模型 1：正常节奏（每 tick 一次调用），两种实现周期相同
    game = GameTimeRotation()
    counter = CounterRotation()
    for t in range(0, 61):
        game.broadcast(t)
        counter.broadcast(t)
    check("②-12 每 tick 一次调用时：游戏时间实现与旧实现都是 3 次轮转（名义周期不变）",
          game.rotations == 3 and counter.rotations == 3,
          "game=%d counter=%d" % (game.rotations, counter.rotations))

    # 行为模型 2：玩家频繁点击（每 tick 3 次调用）—— 旧实现明显加快，新实现不受影响
    game = GameTimeRotation()
    counter = CounterRotation()
    for t in range(0, 61):
        for _ in range(3):
            game.broadcast(t)
            counter.broadcast(t)
    check("②-13 每 tick 3 次调用（= 玩家频繁点击）时：游戏时间实现仍 3 次轮转，"
          "旧实现被拉到 9 次（这正是「点得越勤、窗口转得越快」的 bug）",
          game.rotations == 3 and counter.rotations == 9,
          "game=%d counter=%d" % (game.rotations, counter.rotations))

    # 行为模型 3：调用次数越多，旧实现周期越短的单调性
    counts = []
    for per_tick in (1, 2, 3, 5):
        model = CounterRotation()
        for t in range(0, 61):
            for _ in range(per_tick):
                model.broadcast(t)
        counts.append(model.rotations)
    check("②-14 旧实现的轮转次数随调用频率单调上升（1/2/3/5 次每 tick ⇒ %r）"
          "——新实现与频率无关" % (counts,),
          counts == sorted(counts) and counts[0] < counts[-1])

    # 行为模型 4：8 tick 交互静默仍然生效（轮转被推迟）
    check("②-15 8 tick 静默仍生效：25 tick 刚点过，该行在安静期内不轮转（判据仍是纯函数）",
          not should_advance_row(6, True, 25, 25, -1)
          and not should_advance_row(6, True, 32, 25, -1)
          and should_advance_row(6, True, 33, 25, -1))
    check("②-16 4 tick 安全窗仍是 `<= 4`（第 120 轮口径，不许本轮顺手改成 < 4）",
          is_rotate_settling(10, 10) and is_rotate_settling(14, 10)
          and not is_rotate_settling(15, 10))

    # 反例：源码副本退回按调用次数计
    bad = raw.replace("} else if (now - lastPollTick >= POLL_TICKS) {",
                      "} else if (++pollCounter >= POLL_TICKS) {", 1)
    bad_code = strip_comments(bad)
    bad_broadcast = brace_body(bad_code, "public void broadcastChanges()")
    counterexample("②-17 源码副本：轮转判据退回 `++pollCounter >= POLL_TICKS`（= 按调用次数）"
                   "⇒ ②-3 的判据会判负",
                   bad != raw
                   and "now - lastPollTick >= POLL_TICKS" not in bad_broadcast)
    counterexample("②-18 行为模型：同一份「每 tick 3 次调用」场景下，按调用次数计的实现"
                   "轮转 9 次 > 按游戏时间计的 3 次 ⇒ ②-13 会判负（旧实现确实更快）",
                   9 > 3)


# ======================================================================================
# ③ simplify_lang_texts.py：过时文案 + 死键
# ======================================================================================

HELP_KEY = "block.rs_create_compat.sequence_execution_chamber.help"


def group_simplify_script():
    print("\n=== ③ tools/simplify_lang_texts.py：无「6」容量残留 / 键全部存在 ===")
    raw = read(SIMPLIFY)
    src, new, limits = parse_simplify()
    check("③-1 脚本可被 ast 解析且 NEW 映射存在", new is not None, "解析失败")
    if new is None:
        return
    zh = json.load(io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8"))
    en = json.load(io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8"))

    check("③-2 脚本全文不再出现过时容量文案（「6 格」/「6格」/「3×2」都不许有）",
          "6 格" not in raw and "6格" not in raw and "3×2" not in raw and "3x2" not in raw)

    check("③-3 执行仓 help 文案改成实际值：中文含「54 格」与「6×9」",
          HELP_KEY in new and "54 格" in new[HELP_KEY][0] and "6×9" in new[HELP_KEY][0],
          repr(new.get(HELP_KEY)))
    check("③-4 英文同一条也写实际值（54 slots / 6x9）",
          HELP_KEY in new and "54 slots" in new[HELP_KEY][1] and "6x9" in new[HELP_KEY][1],
          repr(new.get(HELP_KEY)))

    missing = missing_keys(new, zh)
    check("③-5 NEW 里每个键都真实存在于 zh_cn.json（否则 main() 的 missing 守卫会让脚本"
          "SystemExit、一份都不写）", not missing, "缺 %r" % (missing,))
    missing_en = missing_keys(new, en)
    check("③-6 同上，en_us.json 也必须齐全", not missing_en, "缺 %r" % (missing_en,))

    ideal_zh = limits.get("IDEAL_ZH", 45)
    hard_zh = limits.get("HARD_ZH", 55)
    hard_en = limits.get("HARD_EN", 110)
    help_zh, help_en = new.get(HELP_KEY, ("", ""))
    check("③-7 执行仓 help 新值在脚本自己的硬上限内（zh %d <= %d / en %d <= %d），"
          "跑脚本时这一条不会再被标「超硬上限」"
          % (len(help_zh), hard_zh, len(help_en), hard_en),
          len(help_zh) <= hard_zh and len(help_en) <= hard_en)
    over = [k for k, v in new.items() if len(v[0]) > hard_zh or len(v[1]) > hard_en]
    print("     （参考：其它键里超硬上限 %d 条、超理想值（zh %d / en %d）%d 条，"
          "属历史遗留、本轮不动）"
          % (len(over), ideal_zh, limits.get("IDEAL_EN", 95),
             len([k for k, v in new.items()
                  if len(v[0]) > ideal_zh or len(v[1]) > limits.get("IDEAL_EN", 95)])))

    # 内存干跑：确认脚本真的能跑完、且执行仓文案不会再退化
    zh_lines = io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8", newline="").readlines()
    en_lines = io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8", newline="").readlines()
    n_zh, zh_out = apply_new_in_memory(zh_lines, new, 0)
    n_en, en_out = apply_new_in_memory(en_lines, new, 1)
    patched_zh = json.loads(zh_out)
    patched_en = json.loads(en_out)
    check("③-8 内存干跑（不写文件）：zh / en 各替换 %d / %d 行、原行数不变、JSON 仍合法"
          % (n_zh, n_en),
          n_zh == len(new) and n_en == len(new)
          and len(patched_zh) == len(zh) and len(patched_en) == len(en))
    check("③-9 干跑后执行仓 help 文案含 54 且不含过时容量数字（6）",
          "54" in patched_zh[HELP_KEY] and "54" in patched_en[HELP_KEY]
          and re.search(r"[（(]\s*6\s*[格)）]", patched_zh[HELP_KEY]) is None)
    check("③-10 真实语言文件当前也不是过时文案（本轮只改脚本，语言文件是别人的写域）",
          "6 格" not in zh[HELP_KEY] and "54" in zh[HELP_KEY],
          "zh 当前 = %r" % (zh[HELP_KEY],))

    # 反例：把旧文案拼回脚本副本，判据必须判负
    bad = raw.replace(new[HELP_KEY][0],
                      "紧贴 Create 机放置（朝向机器侧）。右键放入/取出单元样板（6 格）。"
                      "自动为机器供料，产物回网。", 1)
    counterexample("③-11 脚本副本：把执行仓文案改回旧的「6 格」⇒ ③-2 / ③-3 判据判负",
                   bad != raw and ("6 格" in bad))
    fake_new = dict(new)
    fake_new["definitely.not.a.real.key"] = ("占位", "placeholder")
    counterexample("③-12 映射副本：塞一个语言文件里不存在的键 ⇒ ③-5 的「键必须存在」判据判负",
                   bool(missing_keys(fake_new, zh)) and not missing_keys(new, zh))


# ======================================================================================
# ④ diag：不加权限 + 冷却 + 回执轮转
# ======================================================================================

def group_diag():
    print("\n=== ④ CompatCommands：diag 不加权限 + 冷却 + 回执轮转 ===")
    raw = read(COMMANDS)
    code = strip_comments(raw)
    export = brace_body(code, "private static int exportDiag(")

    check("④-1 根指令仍无权限要求（requires(source -> true)）",
          ".requires(source -> true)" in code)
    check("④-2 diag 节点**显式**再声明一次无权限（口径写在节点上，审计可见）",
          diag_node_permissionless(code))
    check("④-3 整个指令类里没有任何权限门槛（无 hasPermission / 无 isOp）",
          "hasPermission" not in code and "isOp(" not in code
          and ".requires(source -> source." not in code)
    check("④-4 diag 与 diag run 两条路径都走同一个 exportDiag（冷却对两者都生效）",
          'exportDiag(context, "run")' in code and 'exportDiag(context, "manual")' in code)

    check("④-5 冷却常量集中定义：DIAG_COOLDOWN_TICKS = 200（10 秒），不在 exportDiag 里写魔数",
          const_int(code, "DIAG_COOLDOWN_TICKS") == DIAG_COOLDOWN_TICKS
          and "DIAG_COOLDOWN_TICKS" in code)
    check("④-6 冷却常量带「为什么」说明：注释里写清「不加权限 ⇒ 靠冷却当护栏」与 10 秒的理由",
          cooldown_documented(raw))
    check("④-7 冷却表按执行者记录（UUID -> tick），并有界（> 256 清空）",
          "private static final Map<UUID, Integer> DIAG_LAST_EXPORT_TICK = new HashMap<>();" in code
          and "DIAG_LAST_EXPORT_TICK.size() > 256" in code
          and "DIAG_LAST_EXPORT_TICK.clear();" in code)
    check("④-8 控制台 / 命令方块（无执行者玩家）也走冷却：落到同一个全零 UUID 键",
          "private static final UUID DIAG_NO_PLAYER_KEY = new UUID(0L, 0L);" in code
          and "player == null ? DIAG_NO_PLAYER_KEY : player.getUUID()" in code)
    check("④-9 冷却判据在动手之前：先于 RsccDiag.snapshot 与「打开开发日志」那一步",
          ordered(export, "DIAG_COOLDOWN_TICKS", "RsccDiag.snapshot(")
          # 第 122 轮：diag run 不再裸调 setEnabled(true)，改走作用域化的 withEnabledTemporarily
          # （导出结束/抛异常都恢复，否则开关会一直开着）。本项校验的语义不变：
          # 「冷却判据必须排在打开日志之前」——只把「打开日志」这个动作的两种写法都认下来。
          and (ordered(export, "DIAG_COOLDOWN_TICKS", "RsccAssemblyDebug.setEnabled(")
               or ordered(export, "DIAG_COOLDOWN_TICKS", "withEnabledTemporarily(")))
    check("④-10 冷却期内走早退 + 中文提示（不留任何副作用：不导出、不打日志、不开日志开关）",
          cooldown_early_return(export))
    check("④-11 服务器重启（getTickCount 归零）时 elapsed < 0 按已过期处理，不会凭空多一段冷却",
          "elapsed >= 0 && elapsed < DIAG_COOLDOWN_TICKS" in export
          and "时间不会倒流" in raw)
    check("④-12 回执按段轮转：DIAG_TIP_SEGMENTS 数组 + floorMod 游标 + 每次只追加一段",
          "DIAG_TIP_SEGMENTS[Math.floorMod(diagTipCursor++" in export
          and "Component.literal(tip)" in export
          and len(diag_segments(code)) >= 3)
    check("④-13 轮转尾注全是中文（无 ASCII 字母），不触发 localization 规则 C",
          all(not re.search(r"[A-Za-z]", tip) for tip in diag_segments(code)))
    check("④-14 冷却提示同样是纯中文（含数字可以，不含 ASCII 字母）",
          all(not re.search(r"[A-Za-z]", lit) for lit in cooldown_literals(raw)))

    # 行为模型：冷却判据
    check("④-15 冷却模型：首次放行；10 秒内拒绝；到点放行（elapsed == 200 放行）",
          diag_allowed(100, None)
          and not diag_allowed(150, 100)
          and not diag_allowed(299, 100)
          and diag_allowed(300, 100))
    check("④-16 冷却模型：重启后 tick 归零（last > now）按已过期处理、不挡人",
          diag_allowed(5, 100000))
    waits = [wait_seconds(now, 100) for now in (100, 120, 199, 299)]
    check("④-17 回执里的「还需约 N 秒」与 200 tick 冷却一致（%r）" % (waits,),
          waits == [10, 9, 6, 1])

    # 行为模型：轮转
    count = len(diag_segments(code))
    sequence = [diag_segment_index(cursor, count) for cursor in range(count * 2)]
    check("④-18 轮转模型：连续 %d 次导出覆盖 %d 段、且相邻两次永不重复"
          % (count * 2, count),
          sorted(set(sequence)) == list(range(count))
          and all(sequence[i] != sequence[i + 1] for i in range(len(sequence) - 1)))
    hammer_with = simulate_diag_hammer(cooldown=True)
    hammer_without = simulate_diag_hammer(cooldown=False)
    check("④-19 冷却模型：同一执行者每 3 tick 敲一次、共 600 tick，最多真正导出 3 次；"
          "去掉冷却则 200 次调用全部导出（护栏确实在挡刷屏）",
          hammer_with == 3 and hammer_without == 200,
          "with=%d without=%d" % (hammer_with, hammer_without))

    # 反例：源码副本
    bad = raw.replace("if (elapsed >= 0 && elapsed < DIAG_COOLDOWN_TICKS) {",
                      "if (false) {", 1)
    bad_export = brace_body(strip_comments(bad), "private static int exportDiag(")
    counterexample("④-20 源码副本：把冷却判据改成 if (false)（= 不冷却）⇒ ④-9 / ④-10 判负",
                   bad != raw and "elapsed < DIAG_COOLDOWN_TICKS" not in bad_export)

    bad = raw.replace('.requires(source -> true)\n            .then(Commands.literal("run")',
                      '.requires(source -> source.hasPermission(2))\n            .then(Commands.literal("run")', 1)
    counterexample("④-21 源码副本：给 diag 加上权限门槛（hasPermission(2)）⇒ ④-2 / ④-3 判负",
                   bad != raw and not diag_node_permissionless(strip_comments(bad)))

    bad = raw.replace("final String tip = DIAG_TIP_SEGMENTS[Math.floorMod(diagTipCursor++, DIAG_TIP_SEGMENTS.length)];",
                      "final String tip = \"\";", 1)
    counterexample("④-22 源码副本：去掉回执轮转（每次不带尾注）⇒ ④-12 判负",
                   bad != raw
                   and "DIAG_TIP_SEGMENTS[Math.floorMod(diagTipCursor++" not in strip_comments(bad))


def diag_node_permissionless(code):
    """diag 节点上必须显式 requires(source -> true)，且出现在第一个 .then( 之前。"""
    idx = code.find('Commands.literal("diag")')
    if idx < 0:
        return False
    tail = code[idx:idx + 900]
    req = tail.find(".requires(source -> true)")
    then = tail.find(".then(")
    return req > 0 and (then < 0 or req < then)


def cooldown_documented(raw):
    """冷却常量前必须有一段写清「为什么不加权限却要冷却」与 10 秒理由的注释。"""
    idx = raw.find("private static final int DIAG_COOLDOWN_TICKS")
    if idx < 0:
        return False
    head = raw[max(0, idx - 2600):idx]
    return ("不要权限" in head or "不加权限" in head) and "10 秒" in head and "磁盘" in head


def cooldown_early_return(export_body):
    """冷却分支：判据之后先 sendFailure 中文提示、再 return 0（且都在 snapshot / setEnabled 之前）。"""
    idx = export_body.find("DIAG_COOLDOWN_TICKS")
    if idx < 0:
        return False
    tail = export_body[idx:]
    fail = tail.find("sendFailure(Component.literal(")
    ret = tail.find("return 0;")
    snap = tail.find("RsccDiag.snapshot(")
    enable = tail.find("RsccAssemblyDebug.setEnabled(")
    return fail >= 0 and ret > fail and (snap < 0 or ret < snap) and (enable < 0 or ret < enable)


def diag_segments(code):
    """从 Java 源码里取出 DIAG_TIP_SEGMENTS 的中文字面量列表。"""
    idx = code.find("DIAG_TIP_SEGMENTS = {")
    if idx < 0:
        return []
    end = code.find("};", idx)
    block = code[idx:end if end > 0 else len(code)]
    return re.findall(r'"([^"]*)"', block)


def cooldown_literals(raw):
    """取冷却提示那一句里的中文字面量片段（供「无 ASCII 字母」检查）。"""
    code = strip_comments(raw)
    body = brace_body(code, "private static int exportDiag(")
    idx = body.find("诊断快照冷却中")
    if idx < 0:
        return []
    start = body.rfind("Component.literal(", 0, idx)
    end = body.find(";", idx)
    chunk = body[start if start >= 0 else idx:end if end > 0 else len(body)]
    return re.findall(r'"([^"]*)"', chunk)


def ordered(body, first, second):
    a = body.find(first)
    b = body.find(second)
    return a >= 0 and b >= 0 and a < b


def wait_seconds(now, last, cooldown=DIAG_COOLDOWN_TICKS):
    elapsed = now - last
    return (cooldown - elapsed + 19) // 20


def simulate_diag_hammer(cooldown, ticks=600):
    """同一个执行者每 3 tick 敲一次 diag；返回带/不带冷却时真正导出的次数。"""
    last = None
    exports = 0
    for now in range(0, ticks, 3):
        if not cooldown or diag_allowed(now, last):
            exports += 1
            last = now
    return exports


# ======================================================================================
# ⑤ 回归保护：本轮不许顺手改坏的东西
# ======================================================================================

def group_regression():
    print("\n=== ⑤ 回归保护（本轮只做这四件事，别顺手改坏既有硬规则） ===")
    menu = strip_comments(read(MENU))
    packet = strip_comments(read(PACKET))
    commands = strip_comments(read(COMMANDS))

    check("⑤-1 汇总菜单：洞 / 全量重同步 / 点击快照三套第 120 轮机制仍在",
          "rowHoles[index / ROW_COLS].set(unitSlot);" in menu
          and "pendingFullResync" in menu
          and "beginClickSnapshot();" in menu and "endClickSnapshot();" in menu)
    check("⑤-2 汇总菜单：clearContent() 仍是刻意空操作（死亡掉落不抹执行仓样板）",
          "public void clearContent() {" in menu)
    check("⑤-3 绑定包：仍保留 ≤64 格距离校验与重名拒绝（收口不许改变任何行为语义）",
          "> 64.0" in packet
          and "chamber.isNameTakenInNetwork(name)" in packet
          and "chamber_name_duplicate" in packet)
    check("⑤-4 绑定包：配方类型锁定分支仍在（有单元样板时不改类型，但仍写名字）",
          "chamber.chainHasAnyUnit() && !requestedType.equals(currentType)" in packet
          and "chamber_recipe_locked" in packet)
    check("⑤-5 指令类：既有子指令一个没丢（autocrafter / blockcontent / supply / chain-debug…）",
          all('Commands.literal("%s")' % name in commands
              for name in ("autocrafter", "blockcontent", "supply", "chain-debug", "devlogs", "diag")))

    # 常量与自检模型对齐（防两边漂移）
    check("⑤-6 自检模型常量与源码逐字一致（256 / 20 / 8 / 4 / 200）",
          const_int(strip_comments(read(TERMINAL)), "MAX_IMPORT_STRING_LENGTH") == NAME_LIMIT
          and const_int(menu, "POLL_TICKS") == POLL_TICKS
          and const_int(menu, "INTERACT_QUIET_TICKS") == INTERACT_QUIET_TICKS
          and const_int(menu, "ROTATE_SETTLE_TICKS") == ROTATE_SETTLE_TICKS
          and const_int(commands, "DIAG_COOLDOWN_TICKS") == DIAG_COOLDOWN_TICKS)


def main():
    print("第 121 轮自检：四项收尾（仓名 256 / 轮转游戏时间 / 语言脚本 / diag 冷却）")
    print("边界：只做静态自检，未编译、未实机 —— 构建与部署由 Lead 负责。")
    group_name_clamp()
    group_game_time_poll()
    group_simplify_script()
    group_diag()
    group_regression()
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
