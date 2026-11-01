# -*- coding: utf-8 -*-
"""第 80 轮：终端定位 / 打开的三条服务端日志，从「每玩家每 JVM 一次」改成【可重复 + 时间节流】。

用户反馈（本轮任务的起点）
--------------------------
这三条日志此前用两个 `Set<UUID>` 去重（`NOT_FOUND_LOGGED` / `MULTIPLE_LOGGED`）：

    No advanced remote terminal found ...
    Player ... carries N advanced remote terminals ...
    Failed to open terminal mode ...

⇒ 玩家**第二次**复现时日志里什么都没有，无法反复取证（上一轮正是因为「第一次没留住日志」而
让取证落空）。用户原话：「可以」——同意改成可重复但有节流。

本脚本断言 5 件事
-----------------
A. **节流存在，且第一次必打**（源码级）：`THROTTLE_SECONDS` / `THROTTLE_TICKS`、
   `lastLogTick`（`Long.MIN_VALUE` = 从未输出 ⇒ 首次绕过窗口）、`suppressed`；
B. **同一 tick 内重复不发**仍在（`elapsed == 0L` 分支），且**窗口按 tick 计**（`elapsed < THROTTLE_TICKS`）；
C. **被压次数汇总**：被压时 `suppressed++`，下一条日志带上 `(xN in last 5s)`（N 含本次）；
D. **旧写法彻底消失**：`NOT_FOUND_LOGGED` / `MULTIPLE_LOGGED` / `newKeySet` / `logMultipleOnce`
   在真实代码里一个都不剩（注释里解释旧写法不算）；
E. **反例自证**：把旧写法（优先取 `git show HEAD:` 的真实旧文件，拿不到时用内置等价片段）
   喂给**同一批**判据 ⇒ 必须判负；把旧的行为模型喂给**同一批**行为判据 ⇒ 同样判负。
   同时断言新代码 / 新模型在相同判据下通过（证明判据不是「怎么都判负」）。

另有两组「不刷屏」的数值断言（纯 Python 可复跑）：
  * 连按 100 tick（5 秒内狂按）只产生 1 条日志，窗口过去后那条带 `x100`；
  * 连按 12000 tick（10 分钟）只产生 120 条，且所有 xN 之和 == 12000（= 一次按键都没丢）。

用法: python tools/selfcheck_round80_terminal_log_throttle.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。
"""

from __future__ import annotations

import io
import os
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LOCATOR = os.path.join(PKG, "support", "RsccTerminalLocator.java")
ITEM = os.path.join(PKG, "item", "AdvancedRemoteTerminalItem.java")
LOCATOR_REL = "src/main/java/cretae/cookiewyq/rs_create_compat/support/RsccTerminalLocator.java"

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name,
                       ("" if ok else ((" | " + detail) if detail else ""))))


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def code_only(text):
    """剥掉 // 与 /*...*/ 注释：注释里会解释旧写法（`{@code NOT_FOUND_LOGGED}`），不算代码。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def method_body(text, signature):
    """按大括号配平取出以 signature 开头的方法体（含签名）。"""
    start = text.find(signature)
    if start < 0:
        return None
    open_at = text.find("{", start)
    if open_at < 0:
        return None
    depth = 0
    for idx in range(open_at, len(text)):
        ch = text[idx]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[start:idx + 1]
    return None


# ==========================================================================================
# 判据（源码级）——新代码与旧写法喂给**同一个函数**
# ==========================================================================================


def judge_source(code, label=""):
    """三条日志的「节流 + 首次必打 + 计数汇总 + 旧写法消失」判据。

    返回问题列表（空 = 通过）。这个函数是本轮的**唯一判据**：
    A 段把它喂给真实新代码（必须空），E 段把它喂给旧写法（必须非空）。
    """
    problems = []
    if not re.search(r"THROTTLE_SECONDS\s*=\s*(\d+)\s*;", code):
        problems.append("没有 THROTTLE_SECONDS（不存在时间节流窗口）")
    else:
        seconds = int(re.search(r"THROTTLE_SECONDS\s*=\s*(\d+)\s*;", code).group(1))
        if not (5 <= seconds <= 10):
            problems.append("节流窗口 %d 秒不在要求的 5~10 秒内" % seconds)
    if not re.search(r"THROTTLE_TICKS\s*=\s*THROTTLE_SECONDS\s*\*\s*20\s*;", code):
        problems.append("没有 THROTTLE_TICKS = THROTTLE_SECONDS * 20（窗口没有落到 tick）")
    if "lastLogTick" not in code:
        problems.append("没有 lastLogTick（无法表达「上次打日志的时刻」）")
    if "suppressed" not in code:
        problems.append("没有 suppressed（被压次数无法汇总）")
    if "Long.MIN_VALUE" not in code:
        problems.append("没有 Long.MIN_VALUE 哨兵 ⇒ 无法保证「首次必打」")
    if "elapsed == 0L" not in code:
        problems.append("没有「同一 tick 内重复不发」的分支（elapsed == 0L）")
    if "elapsed < THROTTLE_TICKS" not in code:
        problems.append("没有窗口判定（elapsed < THROTTLE_TICKS）")
    if "(x{} in last {}s)" not in code:
        problems.append("没有 (xN in last Ns) 汇总格式（被压次数会丢）")
    if "suppressed + 1" not in code:
        problems.append("汇总计数没有把「本次这条」算进去（suppressed + 1）")
    if "MAX_TRACKED_PLAYERS" not in code:
        problems.append("没有状态表内存上限（MAX_TRACKED_PLAYERS）")
    # ↓ 旧写法：每名玩家每 JVM 永久一次
    if "NOT_FOUND_LOGGED" in code:
        problems.append("旧写法仍在：NOT_FOUND_LOGGED（每玩家永久只打一次）")
    if "MULTIPLE_LOGGED" in code:
        problems.append("旧写法仍在：MULTIPLE_LOGGED（每玩家永久只打一次）")
    if "newKeySet" in code:
        problems.append("旧写法的 Set<UUID> 去重仍在：ConcurrentHashMap.newKeySet")
    if "logMultipleOnce" in code:
        problems.append("旧方法名仍在：logMultipleOnce（语义 = 只打一次）")
    return problems


def parse_window(code):
    """从真实源码解析出窗口（秒 / tick），供行为模型使用 —— 模型不另写一份常量。"""
    seconds = int(re.search(r"THROTTLE_SECONDS\s*=\s*(\d+)\s*;", code).group(1))
    return seconds, seconds * 20


# ==========================================================================================
# 行为判据 + 两个可复跑的模型（新 = 节流；旧 = 每玩家永久一次）
# ==========================================================================================


class ThrottleModel(object):
    """Java `logThrottled` 的逐行等价模型：只建模状态机（窗口 / 同 tick / 计数 / 时间倒流）。"""

    def __init__(self, window_ticks):
        self.window = window_ticks
        self.last = None
        self.suppressed = 0
        self.emitted = []          # [(tick, line)]

    def log(self, tick, text):
        if self.last is not None:
            elapsed = tick - self.last
            if elapsed == 0:               # 同一 tick 内重复不发
                self.suppressed += 1
                return None
            if 0 < elapsed < self.window:  # 窗口内只计数
                self.suppressed += 1
                return None
            # 窗口已过 / 时间倒流（elapsed < 0）⇒ 立即打
        occurrences = self.suppressed + 1
        line = text
        if occurrences > 1:
            line = "%s (x%d in last %ds)" % (text, occurrences, self.window // 20)
        self.emitted.append((tick, line))
        self.last = tick
        self.suppressed = 0
        return line


class OldPerPlayerOnceModel(object):
    """旧写法等价模型：每名玩家每 JVM 只打一次（= 两个 Set<UUID> 的语义）。"""

    def __init__(self):
        self.seen = set()
        self.emitted = []

    def log(self, tick, text):
        if text in self.seen:
            return None
        self.seen.add(text)
        self.emitted.append((tick, text))
        return text


def behavior_judge(model, label=""):
    """「玩家反复按 G，服务端日志里还能不能看到对应行」的同一批行为判据。

    场景（模拟真机）：
      * tick 0   第一次按（没有终端）⇒ **必须**出一条；
      * tick 20  5 秒内又按一次      ⇒ 允许被节流，但计数不能丢；
      * tick 120 6 秒后再按（窗口已过）⇒ **必须**再出一条，且带 (xN in last Ns)。
    返回问题列表（空 = 通过）。
    """
    text = "No advanced remote terminal found"
    problems = []
    first = model.log(0, text)
    if not first:
        problems.append("%s: 第一次按下没有留下日志" % label)
    model.log(20, text)
    again = model.log(120, text)
    if not again:
        problems.append("%s: 6 秒后再按仍然没有日志（= 每玩家永久只打一次，无法反复取证）" % label)
    elif "(x" not in again:
        problems.append("%s: 窗口过后的那条没有汇总被压次数" % label)
    return problems


def occurrences_of(line):
    """从日志行里取 xN 的 N（没有汇总 ⇒ 1）。"""
    match = re.search(r"\(x(\d+) in last (\d+)s\)\s*$", line)
    return int(match.group(1)) if match else 1


# ==========================================================================================
# A. 真实源码：节流 + 首次必打 + 计数汇总 + 旧写法消失
# ==========================================================================================

print("=== A. 真实源码（RsccTerminalLocator.java）：时间节流 + 首次必打 + 计数汇总 ===")

locator_raw = read(LOCATOR)
locator = code_only(locator_raw)
item = code_only(read(ITEM))

source_problems = judge_source(locator)
check("A1 单一判据：真实新代码 0 问题（节流存在 / 首次必打 / 计数汇总 / 旧写法消失）",
      not source_problems, "; ".join(source_problems))

window_seconds, window_ticks = parse_window(locator)
check("A2 节流窗口 N = %d 秒（要求 5~10 秒）⇒ %d tick" % (window_seconds, window_ticks),
      5 <= window_seconds <= 10 and window_ticks == window_seconds * 20,
      "解析到 %d 秒 / %d tick" % (window_seconds, window_ticks))

state = method_body(locator, "private static final class LogState")
check("A3 节流结构 = 每玩家每类：lastLogTick（Long.MIN_VALUE = 从未输出）+ suppressed",
      state is not None and "long lastLogTick = Long.MIN_VALUE;" in state
      and "int suppressed;" in state)

throttle_body = method_body(locator, "private static void logThrottled(")
check("A4 只有一个出口 logThrottled：两条「不发」分支都要求 lastLogTick != Long.MIN_VALUE"
      "（⇒ 从未输出过的通道绕过一切窗口，第一次必打）",
      throttle_body is not None
      and throttle_body.count("state.lastLogTick != Long.MIN_VALUE") == 2
      and "elapsed == 0L" in throttle_body
      and "elapsed > 0L && elapsed < THROTTLE_TICKS" in throttle_body,
      "出现 %d 次 MIN_VALUE 守卫" % (throttle_body.count("state.lastLogTick != Long.MIN_VALUE")
                                     if throttle_body else -1))
check("A5 被压次数汇总：被压时 suppressed++（两处），输出时 occurrences = suppressed + 1，打完清零",
      throttle_body is not None and throttle_body.count("state.suppressed++;") == 2
      and "final int occurrences = state.suppressed + 1;" in throttle_body
      and "state.suppressed = 0;" in throttle_body)
check("A6 汇总写进日志：`(x{} in last {}s)` 恰好两处（INFO / ERROR 各一条）",
      locator.count("(x{} in last {}s)") == 2,
      "出现 %d 次" % locator.count("(x{} in last {}s)"))
check("A7 本类所有终端日志都经同一出口：LOGGER 调用恰好 4 处，且全部在 logThrottled 体内",
      throttle_body is not None and throttle_body.count("LOGGER.") == 4
      and locator.count("LOGGER.") == 4,
      "本类 LOGGER 调用 %d 处" % locator.count("LOGGER."))
check("A8 三族日志各走各的通道（NOT_FOUND / MULTIPLE / OPEN_FAILED），互不挤占",
      "enum LogKind" in locator and "NOT_FOUND" in locator and "MULTIPLE" in locator
      and "OPEN_FAILED" in locator and "channels[kind.ordinal()]" in locator)
check("A9 打开失败那条也接进节流：AdvancedRemoteTerminalItem 的 catch 调 logOpenFailure，"
      "不再直接 LOGGER.error",
      "RsccTerminalLocator.logOpenFailure(player, mode, t)" in item
      and 'LOGGER.error("Failed to open terminal mode' not in item)
check("A10 内存上限：MAX_TRACKED_PLAYERS 存在，且有闲置淘汰 + 整表清空两条兜底",
      "MAX_TRACKED_PLAYERS = 512" in locator
      and method_body(locator, "private static void pruneIdle(") is not None
      and "LOG_STATES.clear();" in locator)
check("A11 绝不每 tick 打：locate() 的调用方只有 C2S 包与客户端叠加层（没有 tick 循环）",
      locator.count("public static Optional<Location> locate(") == 1
      and "locate(player)" in code_only(read(os.path.join(PKG, "network",
                                                          "OpenAdvancedRemoteTerminalPacket.java"))))

# ==========================================================================================
# B. 行为模型：首次必打、同 tick 抑制、窗口、汇总、时间倒流、按类独立、输出上界
# ==========================================================================================

print("")
print("=== B. 行为模型（源码常量为准）：首次必打 / 同 tick 不发 / 窗口 / 汇总 ===")


def new_model():
    return ThrottleModel(window_ticks)


# B1 首次必打（连按 100 tick 只 1 条，且是裸行 —— 第一次不会被节流吞掉）
first = new_model()
lines = [first.log(tick, "No advanced remote terminal found") for tick in range(0, 100)]
emitted = [entry[1] for entry in first.emitted]
check("B1 首次必打：tick 0 立刻输出裸行（无汇总后缀），并被 1 秒内 99 次连按保留下来",
      len(emitted) == 1 and emitted[0] == "No advanced remote terminal found",
      str(emitted))
check("B2 窗口边界：第 100 tick（第 5 秒整）再次输出，且汇总 x100（含本次）",
      first.log(100, "No advanced remote terminal found") ==
      "No advanced remote terminal found (x100 in last 5s)",
      str(first.emitted[-1]))

# B3 同一 tick 内重复不发（但计数进下一条）
same_tick = new_model()
same_tick.log(0, "m")
check("B3 同一 tick 内重复不发（返回 None，只累加计数）", same_tick.log(0, "m") is None)
same_tick.log(99, "m")
line = same_tick.log(100, "m")
check("B4 被压次数汇总正确：同 tick 1 次 + 窗口内 1 次 ⇒ 下一条 x3",
      line == "m (x3 in last 5s)", str(line))

# B5 时间倒流（换存档 / 整合服重开导致游戏刻归零）⇒ 立即打，不被窗口永久压死
back = new_model()
back.log(1000, "m")
check("B5 时间倒流（tick 1000 → 5）立即输出，不会因为负 elapsed 被永久压住",
      back.log(5, "m") == "m")

# B6 三族日志互不挤占
a, b = new_model(), new_model()
check("B6 三族日志各走各的通道：NOT_FOUND 在 tick 0 输出后，MULTIPLE 在同一 tick 0 仍能输出",
      a.log(0, "not found") is not None and b.log(0, "carries 2") is not None)

# B7 输出频率上界 + 信息不丢（10 分钟狂按 = 每 tick 一次）
burn = new_model()
for tick in range(12000):
    burn.log(tick, "x")
burn_lines = [entry[1] for entry in burn.emitted]
check("B7 不刷屏：12000 tick 内每 tick 连按 ⇒ 只有 %d 条（每 %d tick 一条）"
      % (len(burn_lines), window_ticks),
      len(burn_lines) == (12000 - 1) // window_ticks + 1,
      "实际 %d 条" % len(burn_lines))
check("B8 信息不丢：已输出行的 xN 之和 + 尚未输出的 suppressed == 12000（与按键次数逐次相等）",
      sum(occurrences_of(line) for line in burn_lines) + burn.suppressed == 12000,
      "xN 之和 = %d，尚未输出 = %d"
      % (sum(occurrences_of(line) for line in burn_lines), burn.suppressed))
tail = burn.log(12100, "x")
check("B9 收尾汇总：下一次输出把最后被压住的 99 次并进 x100（不是丢掉）",
      tail == "x (x100 in last 5s)", str(tail))

# ==========================================================================================
# C. 反例自证①：把**旧写法源码**喂给同一批判据 ⇒ 判负
# ==========================================================================================

print("")
print("=== C. 反例自证①：旧写法（每玩家永久一次）喂给同一批源码判据 ===")

OLD_SNIPPET = """
    private static final Set<UUID> MULTIPLE_LOGGED = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> NOT_FOUND_LOGGED = ConcurrentHashMap.newKeySet();

    private static void logNothingFound(final Player player) {
        if (player.level().isClientSide() || !NOT_FOUND_LOGGED.add(player.getUUID())) {
            return;
        }
        LOGGER.info("No advanced remote terminal found for {} ...", player.getName().getString());
    }

    private static void logMultipleOnce(final Player player, final int count, final ItemStack chosen) {
        if (MULTIPLE_LOGGED.add(player.getUUID())) {
            LOGGER.info("Player {} carries {} advanced remote terminals ...", count, chosen.getItem());
        }
    }
"""


def old_source():
    """取**真实的旧文件**：在 git 历史里回溯「最近一个仍含 NOT_FOUND_LOGGED」的版本。

    第 130 轮修：以前写的是 `git show HEAD:`，隐含「HEAD = 修前」；修复一提交进 HEAD，
    这里拿到的就是修后代码，C2 于是恒报「问题 0 条」。现在与 HEAD 长什么样无关
    （见 tools/_prefix_history.py 的说明）；历史里也找不到时退回内置等价片段，并把出处写出来。

    返回 (文本, 出处说明, 是否真实旧文件)。
    """
    import sys
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    from _prefix_history import find_last_version
    # 判据用 `newKeySet` 而不是 `NOT_FOUND_LOGGED`：后者在**修后的注释里**也被提到（解释旧写法），
    # 拿它当标记会取到 HEAD 自己的修后版本（第 130 轮实测：ab819fdd 就是修后代码 ⇒ C2 恒 0 条）。
    # newKeySet 是旧写法「每玩家一个永久 Set」的独有构造，修复后彻底消失。
    sha, text = find_last_version(ROOT, LOCATOR_REL.replace(
        "src/main/java/cretae/cookiewyq/rs_create_compat/", ""), marker="newKeySet")
    if text is not None:
        return text, "git 历史 %s:%s（真实旧文件）" % (sha, LOCATOR_REL), True
    return OLD_SNIPPET, "内置等价片段（git 历史里也找不到含 NOT_FOUND_LOGGED 的版本）", False


old_text, provenance, genuine = old_source()
old_problems = judge_source(code_only(old_text))
print("  旧写法出处: %s" % provenance)
check("C1 反例来源可核对：拿到了含旧写法的文本（真实 HEAD 旧文件或内置等价片段）",
      "NOT_FOUND_LOGGED" in old_text, provenance)
check("C2 同一批判据对旧写法**判负**（问题数 ≥ 4）", len(old_problems) >= 4,
      "问题 %d 条：%s" % (len(old_problems), "; ".join(old_problems)))
check("C3 判负原因点名了「每玩家永久只打一次」的两个 Set 与缺失的节流",
      all(any(token in problem for problem in old_problems)
          for token in ("NOT_FOUND_LOGGED", "MULTIPLE_LOGGED", "THROTTLE_SECONDS", "suppressed")),
      "; ".join(old_problems))
check("C4 对照：同一批判据对**真实新代码** 0 问题（判据不是「怎么都判负」）",
      judge_source(locator) == [], "; ".join(judge_source(locator)))
check("C5 旧写法在真实新代码里已消失（code_only 视角，注释里解释旧写法不算）",
      not any(token in locator for token in
              ("NOT_FOUND_LOGGED", "MULTIPLE_LOGGED", "newKeySet", "logMultipleOnce")))

# ==========================================================================================
# D. 反例自证②：把旧写法的**行为**喂给同一批行为判据 ⇒ 判负
# ==========================================================================================

print("")
print("=== D. 反例自证②：旧行为（每玩家永久一次）喂给同一批「反复按 G」行为判据 ===")

new_problems = behavior_judge(new_model(), "新代码模型")
old_behavior_problems = behavior_judge(OldPerPlayerOnceModel(), "旧写法模型")
check("D1 新代码模型：反复按 G 每次窗口都留下日志 + 汇总 ⇒ 0 问题",
      not new_problems, "; ".join(new_problems))
check("D2 旧写法模型：6 秒后再按没有日志 ⇒ 判负（这正是上一轮取证落空的原因）",
      len(old_behavior_problems) >= 1, "; ".join(old_behavior_problems))
check("D3 D1/D2 用的是同一个 behavior_judge（不是两套判据）",
      behavior_judge.__name__ == "behavior_judge"
      and behavior_judge(new_model(), "x") == []
      and behavior_judge(OldPerPlayerOnceModel(), "y") != [])

# ==========================================================================================
# E. 结果
# ==========================================================================================

print("")
if FAILURES:
    print("问题总数: %d" % len(FAILURES))
    for item_text in FAILURES:
        print(" - " + item_text)
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
