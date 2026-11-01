#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 122 轮自检：开发日志「关不掉」+ diag 导出「永不需要权限」。

================================================================================
用户原话
================================================================================
① 「现在服务端一直在那个日志中……关闭了那个开发日志的那个选项之后应该是不会再这样子一直输出了」
② 「那个开发日志导出这个日志它是永远不需要权限的」

================================================================================
一、根因（有用户服务器日志逐字可对，不是推断）
================================================================================
用户在 2026-10-24 跑的服务器日志（仓库内 diag/server-latest.log）里可以逐行对上：

    [rscc] diag logging ON (default) ... devLogs=off          <- 会话锚点：配置项是「关」的
    [rscc-assembly] devLogs=on (source=command)               <- 但有人用指令把它翻开了
    [rscc-diag] BEGIN tag=run ...                             <- 紧跟着就是 /rs_create_compat diag run
    [rscc-receiver-output] ...  x2956 行 / 连刷 12 分钟       <- 直到服务器重启才安静

也就是说：**配置项一直关着，`diag run` 把运行时开关强行打开后没有人翻回去**。
`RsccAssemblyDebug.isEnabled()` 是唯一判据入口，它一旦被 `diag run` 置为 true，
之后所有走判据的周期性诊断（接收器 `[rscc-receiver-output]`、执行舱、账本、充电器……）
就恢复输出 —— 用户「关掉那个选项」对当次会话完全无效。这正是「开关关不掉」。

修法（本轮）：
  1. `CompatCommands.exportDiag` 不再直接 `setEnabled(true)`，改走
     `RsccAssemblyDebug.withEnabledTemporarily(true, ...)`：作用域只有 `RsccDiag.snapshot` 那一次调用，
     返回（**含抛异常**）立刻恢复成调用前的开关状态。
  2. 本轮顺手把写域内（support/）还漏在开关外的几个诊断打印点接进唯一判据：
     `KeeperTarget` 的「生效目标被钳制」、`RsccGenericResourceTarget` 的两条 DEBUG、
     `RsccMachineCluster` 的集群成型 DEBUG。
  3. `Config.devLogs` 明确标注为「配置镜像值，禁止当判据」。

================================================================================
二、diag 权限复核（第 121 轮已加，本轮复查 + 用自检锁住）
================================================================================
- 根节点 `requires(source -> true)`；diag 节点上**再显式**写一次 `requires(source -> true)`。
- 全 `command/` 目录没有任何 `hasPermission` / `isOp` / `PermissionLevel` / `hasPermissions`。
- 全 `command/` 目录没有任何 `.suggests(...)`（没有 Tab 补全，也就没有补全侧权限过滤）。
- `RsccDiag`（导出核心）里没有任何权限判断；`RsccDiag.snapshot` 的唯一调用方是 diag 指令。

================================================================================
三、自检做什么
================================================================================
A. 唯一判据入口：`RsccAssemblyDebug` 只有一个 `enabled` 字段、默认 false，
   所有 `event/transition/reason/reject/repeat/trace/dedupe/count*` 通道内部都先判开关；
   `warn()` 是「必要日志」，明确不受开关控制；`withEnabledTemporarily` 必须 finally 恢复。
B. diag 全路径无权限 API、无 Tab 补全权限过滤、节点显式 requires(true)。
C. `diag run` 的开关作用域：全仓不允许出现裸的 `setEnabled(true)`。
D. 周期性打印点台账：每个「必须受开关控制」的打印点，逐一到真源码里断言它前面有判据。
E. 残余未走闸打印点基线：server 侧目录里仍不走闸的 INFO/DEBUG 点必须是**已分类**的
   （ONCE / STATE / THROTTLE / EVENT / DATA_RELOAD / CLIENT），每条都要有分类规则 + 代码证据 token；
   一旦出现**新的**未走闸打印点，判负（这就是「删掉某处开关判据 ⇒ 判负」的实际守卫）。
F. 反例（mutation）：在内存里删掉真实源码的判据/恢复/权限节点，自检必须判负 —— 证明断言有区分力。

用法：python tools/selfcheck_round122_devlog_gate.py [--verbose]
退出码：0 = 全过；1 = 有失败。
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src" / "main" / "java" / "cretae" / "cookiewyq" / "rs_create_compat"

GATE_REL = "support/RsccAssemblyDebug.java"
CMD_REL = "command/CompatCommands.java"
CONFIG_REL = "Config.java"
DIAG_REL = "support/RsccDiag.java"
RECEIVER_REL = "block/entity/NetworkResourceReceiverBlockEntity.java"

# 残余未走闸打印点的扫描范围：只扫「服务端会跑」的目录（client/ item/ menu/ 是界面/物品侧，
# 由按键或界面动作驱动，不在「服务端一直刷」的语境里，本轮写域也不含它们）。
SCAN_PREFIXES = ("block/entity", "network", "support", "compat", "mixin")

GATE_TOKEN = "RsccAssemblyDebug.isEnabled()"
GATE_ALTS = (GATE_TOKEN, "withEnabledTemporarily")


# ========================================================================
# 基础设施
# ========================================================================
class Files:
    """源码只读缓存（rel -> text）。mutation 时复制一份改一行，绝不写盘。"""

    def __init__(self, texts):
        self.texts = texts

    def get(self, rel: str) -> str:
        return self.texts.get(rel, "")

    def rels(self):
        return sorted(self.texts)


class Report:
    def __init__(self):
        self.items = []  # (id, name, ok, detail)

    def ok(self, cid, name, detail=""):
        self.items.append((cid, name, True, detail))

    def bad(self, cid, name, detail=""):
        self.items.append((cid, name, False, detail))

    def fail_ids(self):
        return {cid for cid, _, ok, _ in self.items if not ok}


def load_files():
    texts = {}
    for path in sorted(SRC.rglob("*.java")):
        rel = path.relative_to(SRC).as_posix()
        texts[rel] = path.read_text(encoding="utf-8", errors="replace")
    return Files(texts)


def norm(s: str) -> str:
    return re.sub(r"\s+", " ", s.strip())


def line_no_of(text: str, pos: int) -> int:
    return text.count("\n", 0, pos)


def find_all(text: str, needle: str):
    return [m.start() for m in re.finditer(re.escape(needle), text)]


def method_body(text: str, name: str, occurrence: int = 0):
    """取**方法声明**的方法体（花括号内文本）。只认带 public/private/protected 的声明行，
    避免被 javadoc 里的 {@link #name()} 或调用点干扰。字符串 / 注释里的大括号不计。"""
    hits = [m.start() for m in re.finditer(r"(?<![.\w])" + re.escape(name) + r"\s*\(", text)]
    lines = text.splitlines()
    decls = []
    for pos in hits:
        ln = line_no_of(text, pos)
        if re.search(r"\b(public|private|protected)\b", lines[ln]):
            decls.append(pos)
    if occurrence >= len(decls):
        return None
    pos = decls[occurrence]
    # 找到参数表的右括号
    i = text.find("(", pos)
    depth = 0
    while i < len(text):
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                break
        i += 1
    brace = text.find("{", i)
    if brace < 0:
        return None
    depth = 0
    j = brace
    in_str = in_chr = in_lc = in_bc = False
    while j < len(text):
        c = text[j]
        if in_lc:
            if c == "\n":
                in_lc = False
        elif in_bc:
            if c == "*" and j + 1 < len(text) and text[j + 1] == "/":
                in_bc = False
                j += 1
        elif in_str:
            if c == "\\":
                j += 1
            elif c == '"':
                in_str = False
        elif in_chr:
            if c == "\\":
                j += 1
            elif c == "'":
                in_chr = False
        else:
            if c == '"':
                in_str = True
            elif c == "'":
                in_chr = True
            elif c == "/" and j + 1 < len(text) and text[j + 1] == "/":
                in_lc = True
                j += 1
            elif c == "/" and j + 1 < len(text) and text[j + 1] == "*":
                in_bc = True
                j += 1
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return text[brace + 1:j]
        j += 1
    return None


def gated_before(text: str, pos: int, window: int) -> bool:
    """打印点前 window 行内是否有**代码层**判据。
    （先去掉注释：注释里提到 `RsccAssemblyDebug.isEnabled()` 不算判据 —— 否则删掉真判据也能蒙混过关。）"""
    lines = text.splitlines()
    ln = line_no_of(text, pos)
    lo = max(0, ln - window)
    # 丢掉纯注释行（含 javadoc 续行），再剥行内/块注释
    code_lines = [l for l in lines[lo: ln + 1] if not re.match(r"\s*(//|/\*|\*)", l)]
    ctx = strip_java_comments("\n".join(code_lines))
    if any(tok in ctx for tok in GATE_ALTS):
        return True
    # AssemblyWatchdog / 执行舱里有 `final boolean devLogs = RsccAssemblyDebug.isEnabled();` 再 `if (devLogs)`
    if re.search(r"if\s*\(\s*devLogs\s*\)", ctx):
        return True
    return False


def strip_java_comments(text: str) -> str:
    """去掉注释再扫（注释里的 `setEnabled(true)`、`.suggests(` 之类不能算代码）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def is_print_site(text: str, pos: int, back: int = 4) -> bool:
    """该位置是不是日志调用的实参（而不是 javadoc / 普通注释里提到的同一段文字）。"""
    lines = text.splitlines()
    ln = line_no_of(text, pos)
    lo = max(0, ln - back)
    for i in range(lo, ln + 1):
        line = lines[i]
        if "LOGGER." in line or "LoggerFactory" in line or re.search(r"\.(info|debug)\s*\(", line):
            return True
    return False


# ========================================================================
# A. 唯一判据入口
# ========================================================================
CHANNEL_GATED = ("event", "transition", "reason", "reject", "trace", "dedupe")
CHANNEL_COUNTED = ("countPull", "countPullFluid", "countFeed", "countCollect", "countReturn", "countReject")


def check_A(files: Files, rep: Report):
    gate = files.get(GATE_REL)
    if not gate:
        rep.bad("A0", "gate 类存在", GATE_REL + " 读不到")
        return

    # A1：只有一个开关字段，且默认 false（发布版默认安静）
    n_field = len(re.findall(r"private\s+static\s+volatile\s+boolean\s+enabled\s*=\s*false\s*;", gate))
    if n_field == 1:
        rep.ok("A1", "唯一开关字段 enabled 默认 false")
    else:
        rep.bad("A1", "唯一开关字段 enabled 默认 false", "匹配到 %d 处" % n_field)

    # A2：isEnabled() 只返回该字段
    body = method_body(gate, "isEnabled")
    if body is not None and norm(body) == "return enabled;":
        rep.ok("A2", "isEnabled() 是唯一读取入口（return enabled）")
    else:
        rep.bad("A2", "isEnabled() 是唯一读取入口", repr(norm(body) if body else None))

    # A3：enabled 只在 initFromConfig / setEnabled 里写（withEnabledTemporarily 只转调 setEnabled）
    init_body = method_body(gate, "initFromConfig") or ""
    set_body = method_body(gate, "setEnabled") or ""
    init_start = gate.find("public static void initFromConfig")
    set_start = gate.find("public static void setEnabled")
    bad_writes = []
    for m in re.finditer(r"\benabled\s*=\s*", gate):
        ln = line_no_of(gate, m.start())
        line = gate.splitlines()[ln]
        if "volatile boolean enabled" in line:
            continue  # 字段声明
        ok_write = False
        for body_text, start in ((init_body, init_start), (set_body, set_start)):
            if body_text and start >= 0 and start <= m.start() <= start + len(body_text) + 400:
                ok_write = True
        if not ok_write:
            bad_writes.append(norm(line)[:100])
    if bad_writes:
        rep.bad("A3", "enabled 只在 initFromConfig/setEnabled 内写", "额外写点: %s" % bad_writes[:3])
    else:
        rep.ok("A3", "enabled 只在 initFromConfig/setEnabled 内写")

    # A4：所有开发日志通道内部都先判开关
    missing = []
    for name in CHANNEL_GATED:
        body = method_body(gate, name)
        if body is None:
            missing.append(name + "(找不到)")
            continue
        if name == "repeat":
            if "reject(" not in body:
                missing.append("repeat(未委托 reject)")
            continue
        gate_pat = r"if\s*\(\s*enabled\s*\)" if name == "event" else r"if\s*\(\s*!\s*enabled\s*\)"
        if not re.search(gate_pat, body):
            missing.append("%s(缺开关判据)" % name)
    for name in CHANNEL_COUNTED:
        body = method_body(gate, name)
        if body is None:
            missing.append(name + "(找不到)")
            continue
        if not re.search(r"if\s*\(\s*enabled\b", body) or "flushIfDue()" not in body:
            missing.append(name + "(缺 if (enabled) 或 flushIfDue)")
    if missing:
        rep.bad("A4", "所有通道内部先判开关", "; ".join(missing))
    else:
        rep.ok("A4", "所有通道（%d 个）内部先判开关" % (len(CHANNEL_GATED) + len(CHANNEL_COUNTED)))

    # A5：warn() 是必要日志，明确不受开关控制
    warn_body = method_body(gate, "warn") or ""
    if warn_body and not re.search(r"\benabled\b", warn_body) and "changed(" in warn_body:
        if "必要日志不受本开关控制" in gate:
            rep.ok("A5", "warn() 为必要日志，刻意不受开关控制（已在类注释写明）")
        else:
            rep.bad("A5", "warn() 必要日志注释缺失")
    else:
        rep.bad("A5", "warn() 被判据挡住或未走 changed()（必要日志不能丢）")

    # A6：withEnabledTemporarily 必须 finally 恢复
    wbody = method_body(gate, "withEnabledTemporarily") or ""
    need = ["final boolean previous = enabled", "setEnabled(value)", "action.get()", "finally", "setEnabled(previous)"]
    miss = [tok for tok in need if tok not in wbody]
    if miss:
        rep.bad("A6", "withEnabledTemporarily 作用域化并 finally 恢复", "缺: %s" % miss)
    else:
        i_try = wbody.find("try")
        i_set_on = wbody.find("setEnabled(value)")
        i_action = wbody.find("action.get()")
        i_finally = wbody.find("finally")
        i_restore = wbody.find("setEnabled(previous)")
        if 0 <= i_try < i_set_on < i_action < i_finally < i_restore:
            rep.ok("A6", "withEnabledTemporarily 作用域化并按「try→开→导出→finally→恢复」")
        else:
            rep.bad("A6", "withEnabledTemporarily 顺序不对", "%s" % [i_try, i_set_on, i_action, i_finally, i_restore])

    # A7：全仓没有第二套开发日志开关（Config.devLogs 只能当镜像值，禁止当判据）
    bad = []
    for rel in files.rels():
        if rel == CONFIG_REL:
            continue
        for m in re.finditer(r"\bboolean\s+devLogs\b", files.get(rel)):
            ln = line_no_of(files.get(rel), m.start())
            line = files.get(rel).splitlines()[ln]
            if GATE_TOKEN not in line:
                bad.append("%s:%d %s" % (rel, ln + 1, norm(line)[:90]))
    for rel in files.rels():
        if re.search(r"if\s*\(\s*Config\.devLogs\b", files.get(rel)):
            bad.append(rel + " 用 Config.devLogs 当判据")
    if bad:
        rep.bad("A7", "没有第二套开关 / 禁止用 Config.devLogs 当判据", "; ".join(bad[:4]))
    else:
        rep.ok("A7", "没有第二套开关（devLogs 局部变量必须来自 isEnabled()）")


# ========================================================================
# B. diag 全路径永不需要权限
# ========================================================================
PERM_RE = re.compile(r"hasPermission|hasPermissions|\bisOp\b|PermissionLevel|getPermission|permissionLevel")


def check_B(files: Files, rep: Report):
    # B1：command/ 目录没有任何权限 API
    hits = []
    for rel in files.rels():
        if not rel.startswith("command/"):
            continue
        for m in PERM_RE.finditer(files.get(rel)):
            ln = line_no_of(files.get(rel), m.start())
            hits.append("%s:%d" % (rel, ln + 1))
    if hits:
        rep.bad("B1", "command/ 无任何权限 API", ", ".join(hits[:6]))
    else:
        rep.ok("B1", "command/ 无任何权限 API（hasPermission/isOp/PermissionLevel…）")

    cmd = files.get(CMD_REL)

    # B2：没有 Tab 补全（也就没有补全侧权限过滤）
    if ".suggests(" in cmd:
        rep.bad("B2", "diag 路径没有 Tab 补全（不引入补全侧权限过滤）", "发现 .suggests(")
    else:
        rep.ok("B2", "全指令树没有 .suggests（无补全侧权限过滤）")

    # B3：diag 节点显式 requires(source -> true)
    diag_start = cmd.find('Commands.literal("diag")')
    diag_end = cmd.find('Commands.literal("chain-debug")', diag_start)
    if diag_start < 0 or diag_end < 0:
        rep.bad("B3", "diag 节点显式 requires(source -> true)", "找不到 diag 子树")
    else:
        subtree = cmd[diag_start:diag_end]
        if ".requires(source -> true)" in subtree:
            rep.ok("B3", "diag 节点显式 requires(source -> true)")
        else:
            rep.bad("B3", "diag 节点显式 requires(source -> true)", "子树里没有 requires(true)")

    # B4：根节点也是 requires(true)
    lit = cmd.find('.literal("rs_create_compat")')
    root_start = cmd.rfind("Commands", 0, lit) if lit >= 0 else -1
    first_then = cmd.find("root.then(", lit if lit >= 0 else 0)
    if root_start >= 0 and first_then > root_start and ".requires(source -> true)" in cmd[root_start:first_then]:
        rep.ok("B4", "根节点 requires(source -> true)")
    else:
        rep.bad("B4", "根节点 requires(source -> true)")

    # B5：导出核心 RsccDiag 里没有任何权限判断
    if PERM_RE.search(files.get(DIAG_REL)):
        rep.bad("B5", "RsccDiag 导出核心无权限判断")
    else:
        rep.ok("B5", "RsccDiag 导出核心无权限判断")

    # B7：导出快照的唯一调用方就是 diag 指令
    callers = []
    for rel in files.rels():
        if rel == DIAG_REL:
            continue
        if "RsccDiag.snapshot(" in files.get(rel):
            callers.append(rel)
    if callers == [CMD_REL]:
        rep.ok("B7", "快照唯一入口 = diag 指令（无第二个需要权限的入口）")
    else:
        rep.bad("B7", "快照唯一入口 = diag 指令", "实际调用方: %s" % callers)


# ========================================================================
# C. diag run 的开关作用域
# ========================================================================
def check_C(files: Files, rep: Report):
    gate = files.get(GATE_REL)
    cmd = files.get(CMD_REL)

    # C1：全仓不允许出现裸的 setEnabled(true)（只能用 withEnabledTemporarily）
    bare = []
    for rel in files.rels():
        if rel == GATE_REL:
            continue
        if ".setEnabled(true)" in strip_java_comments(files.get(rel)):
            bare.append(rel)
    if bare:
        rep.bad("C1", "全仓无裸 setEnabled(true)（否则又会「忘翻回去」）", ", ".join(bare))
    else:
        rep.ok("C1", "全仓无裸 setEnabled(true)")

    # C2：exportDiag 用作用域化开关（去注释后再判，注释里提到旧写法不算数）
    body = strip_java_comments(method_body(cmd, "exportDiag") or "")
    if "withEnabledTemporarily" in body and ".setEnabled(" not in body:
        rep.ok("C2", "exportDiag 走 withEnabledTemporarily，不直接 setEnabled")
    else:
        rep.bad("C2", "exportDiag 走 withEnabledTemporarily，不直接 setEnabled",
                "withEnabledTemporarily=%s / setEnabled=%s" % (
                    "withEnabledTemporarily" in body, ".setEnabled(" in body))

    # C3：exportDiag 里快照调用出现在 withEnabledTemporarily 的 lambda 里（作用域只包住快照）
    if re.search(r"withEnabledTemporarily\(true,\s*\(\)\s*->[^;]*snapshot", body):
        rep.ok("C3", "diag run 的开关作用域只包住 RsccDiag.snapshot 调用")
    else:
        rep.bad("C3", "diag run 的开关作用域只包住 RsccDiag.snapshot 调用")

    # C4：exportDiag 不检查权限（导出永远不需要权限）
    if PERM_RE.search(body):
        rep.bad("C4", "exportDiag 无权限检查（导出永远不需要权限）")
    else:
        rep.ok("C4", "exportDiag 无权限检查（导出永远不需要权限）")

    # C5：开关状态变化本身必须留可核对回执（source=command 那两条）
    if '(source=command)' in gate and 'devLogs={}' in gate:
        rep.ok("C5", "开关切换留 devLogs=on/off (source=command) 回执")
    else:
        rep.bad("C5", "开关切换回执缺失")


# ========================================================================
# D. 周期性打印点台账（逐条到真源码断言判据）
# ========================================================================
# (id, 文件, 必须受开关控制的打印行特征串, 判据允许出现在前面多少行内, 说明)
GATE_INVENTORY = [
    ("D1", RECEIVER_REL, 'LOGGER.info(LOG_PREFIX + " " + worldPosition.toShortString() + " | " + format, args);',
     12, "接收器输出诊断 [rscc-receiver-output]（用户实测单会话 2956 行的那个打印点）"),
    ("D2", "support/KeeperTarget.java", "生效目标被钳制", 10,
     "定量保持器「生效目标被钳制」（每 tick 调用的观测器）"),
    ("D3", "support/RsccGenericResourceTarget.java", "外部存储提供器拒绝了资源", 10,
     "外部存储提供器拒绝（逐写入轮次可反复发生）"),
    ("D4", "support/RsccGenericResourceTarget.java", "外部存储提供器工厂", 10,
     "外部存储提供器工厂创建失败（解析轮次可反复发生）"),
    ("D5", "support/RsccMachineCluster.java", "机器集群成型", 10,
     "机器集群成型（方块增删反复发生）"),
    ("D6", "support/KeeperOverflow.java", "开始销毁过量", 10, "过量销毁开始"),
    ("D7", "support/KeeperOverflow.java", "过量已清完", 10, "过量销毁结束"),
    ("D8", "support/RsccFlowLedger.java", "balanced again", 10, "账本恢复对平"),
    ("D9", "support/RsccFlowLedger.java", "unbalanced (first)", 10, "账本首次失衡"),
    ("D10", "support/RsccFlowLedger.java", "same imbalance repeated", 10, "账本持续失衡合并"),
    ("D11", "block/entity/RangeChargerBlockEntity.java", "[rscc-range-charger] save", 10,
     "范围充电器电量落盘（每台每 60 秒）"),
    ("D12", "block/entity/RangeChargerBlockEntity.java", "[rscc-range-charger] load-enter", 10,
     "范围充电器读档（每次区块加载）"),
    ("D13", "block/entity/RangeChargerBlockEntity.java", "[rscc-range-charger] load-done", 10,
     "范围充电器读档完成（每次区块加载）"),
    ("D14", "block/entity/SchematicLoaderBlockEntity.java", "[loader {}] {}", 16,
     "蓝图加载器诊断（单会话 3.9 万行的那个族）"),
    ("D15", "block/entity/SequenceExecutionChamberBlockEntity.java", "fed {} item(s) in the last", 15,
     "执行舱投料摘要（周期性）"),
    ("D16", "network/IntermediateCacheNetworkNode.java", "[rscc-cache-source]", 12,
     "中间产物缓存源（每次网络变动）"),
]


def check_D(files: Files, rep: Report):
    for cid, rel, needle, window, desc in GATE_INVENTORY:
        text = files.get(rel)
        if not text:
            rep.bad(cid, desc, "文件读不到: " + rel)
            continue
        # 只认真正的 LOGGER 实参位置（javadoc / 注释里提到同一段文字不算打印点）
        positions = [p for p in find_all(text, needle) if is_print_site(text, p)]
        if not positions:
            rep.bad(cid, desc, "源码里找不到打印点: " + needle)
            continue
        ungated = [line_no_of(text, p) + 1 for p in positions if not gated_before(text, p, window)]
        if ungated:
            rep.bad(cid, desc, "%s 第 %s 行缺开关判据" % (rel, ungated[:4]))
        else:
            rep.ok(cid, desc, "%s（%d 处，全部有判据）" % (rel, len(positions)))

    # D16b：账本 sustained WARN 属「必要日志」，必须**保留**（不能被开关吞掉）
    text = files.get("support/RsccFlowLedger.java")
    pos = find_all(text, "unbalanced (sustained")
    if pos and not gated_before(text, pos[0], 6):
        rep.ok("D16b", "账本 sustained 是 WARN 必要日志，刻意不受开关控制")
    else:
        rep.bad("D16b", "账本 sustained WARN 被开关挡住（必要日志丢失）")

    # D17：用户实测刷屏的那个前缀在接收器里只能有一个 INFO 出口，且必须受控
    recv = files.get(RECEIVER_REL)
    info_hits = re.findall(r"LOGGER\.info\(\s*LOG_PREFIX", recv)
    err_hits = re.findall(r"LOGGER\.error\(\s*LOG_PREFIX", recv)
    dbg = method_body(recv, "debug") or ""
    if len(info_hits) == 1 and len(err_hits) == 4 and GATE_TOKEN in dbg[:200]:
        rep.ok("D17", "[rscc-receiver-output]：1 个 INFO 出口（受控）+ 4 个 ERROR 出口（必要日志）")
    else:
        rep.bad("D17", "[rscc-receiver-output] 出口数/门禁不符",
                "info=%d error=%d debug_gate=%s" % (len(info_hits), len(err_hits), GATE_TOKEN in dbg[:200]))


# ========================================================================
# E. 残余未走闸打印点基线（新增未分类点 = 判负）
# ========================================================================
# 明细：server 侧目录中仍不走 devLogs 判据的 INFO/DEBUG 打印点（每条都已分类 + 有代码证据）。
# 基线是「当前已知且已证明不会周期性无界输出」的集合；**新增**未走闸点会让 E1 判负。
BASE_RESIDUAL = [
    ('block/entity/AdvancedCollectionCacheBlockEntity.java', 'LOGGER.info("[rs_create_compat] 高级归流缓存仓：旧档每区域过滤已迁到选定器物品（{} 个区域，位置 {}）",'),
    ('block/entity/AdvancedCollectionCacheBlockEntity.java', 'LOGGER.info( "[rs_create_compat] 高级归流缓存仓：区域选定器绑定的维度与当前维度不符 ⇒ 本机不吸收"'),
    ('block/entity/AdvancedQuantityKeeperBlockEntity.java', 'LOGGER.info("[{}] 插件槽异常状态被保留原样（一格多个 / 非法物品 / 超上限）：oversized={} invalid={}"'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.debug("[rs_create_compat] 归流缓存仓：丢弃代表物 {} 不自带的匹配标签 {}（否则标签过滤器永远无法命中）",'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.info("[rs_create_compat] 归流缓存仓 @{}：节点已重新激活，恢复吸收 / 回写"'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.info("[rs_create_compat] 归流缓存仓 @{}：节点连续 {} tick 未激活 ⇒ 既不会吸收世界里的资源、"'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.info("[rscc-collect] @{} {} {} 标记={} 开关[物品={} 流体={} 气体={} 经验={}] "'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.info("[rs_create_compat] 归流缓存仓 @{} 检测到半径内有经验球，但「经验」吸取开关是关闭的"'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.debug("[rs_create_compat] 归流缓存仓标签过滤器本轮未收集：上下文={} tags={} 数量门槛={} "'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.info("[rs_create_compat] 归流缓存仓流体收集生效：slot={} fluid={} 半径({},{},{}) "'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.debug("[rs_create_compat] 归流缓存仓流体本轮未收集：流体标记={} 首个标记={} 数量上限={}mB "'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.info("[rs_create_compat] 归流缓存仓经验收集生效：形态={} 液态目标={} "'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.info("[rs_create_compat] 归流缓存仓经验本轮未收集：形态={} 液态目标={} "'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.info("[rs_create_compat] 经验球余数不足一个颗粒，已移除（不再滞留可被重复吸取）："'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.info("[rs_create_compat] 经验球未删除（余数留置）：原始值={} 本轮收走={} 剩余={} "'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.info("[rs_create_compat] 归流缓存仓 @{}：回写网络已恢复（此前连续 {} tick 一个字节都写不进去，"'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.info("[rs_create_compat] 归流缓存仓 @{}：已接入网络节点，但网络没有存储组件（未接入 RS 网络？），"'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.info("[rs_create_compat] 归流缓存仓 @{}：缓存里有 {} 件（{} 堆）物品 / {} mB 流体想写回网络，"'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.info("[rs_create_compat] 归流缓存仓：玩家确认后销毁缓存区内容，共 {}（物品件 + 流体 mB）",'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.info("[rs_create_compat] 归流缓存仓删除模式 = {}（标记为删除的命中资源{}被销毁；"'),
    ('block/entity/CollectionCacheBlockEntity.java', 'LOGGER.info("[rs_create_compat] 归流缓存仓 @{}：检测到 {} 条旧版标记带有「凑够 N 件才吸」"'),
    ('block/entity/DecompositionGeneratorBlockEntity.java', 'LOGGER.info("[rs_create_compat] 降解室发电机 @{} 就绪：降解表 items={} tags={} 错误={}",'),
    ('block/entity/InventoryProxyBlockEntity.java', 'LOGGER.info("[rscc-proxy] 容器代理器 @{}：代理对象 {} 只读到一路内容物"'),
    ('block/entity/NetworkResourceRequesterBlockEntity.java', 'LOGGER.debug("[rs_create_compat] 请求器@{} 的目标「{}」所在区块未加载，本轮跳过",'),
    ('block/entity/NetworkResourceRequesterBlockEntity.java', 'LOGGER.debug("[rs_create_compat] 请求器@{} 投递失败 reason={} args={}",'),
    ('block/entity/QuantityKeeperBlockEntity.java', 'LOGGER.info("[{}] 插件槽异常状态被保留原样（非法物品 / 超上限）：invalid={} speed={} stack={}"'),
    ('block/entity/SequenceExecutionChamberBlockEntity.java', 'LOGGER.info("SequenceExecutionChamber at {} removed; {} takes over the chain binding {} / '),
    ('block/entity/SequenceExecutionChamberBlockEntity.java', 'LOGGER.debug("SequenceExecutionChamber at {} adopted the chain binding {} / {} from {}",'),
    ('block/entity/SequenceExecutionChamberBlockEntity.java', 'LOGGER.info("[rs_create_compat] 序列执行仓旧存档迁移：把执行舱磁盘槽里的磁盘退回世界"'),
    ('block/entity/SequencePatternTerminalBlockEntity.java', 'LOGGER.debug("[rs_create_compat] 样板终端读档：老档的空编排（{} 行全空）已归一化为 0 步",'),
    ('block/entity/SequencePatternTerminalBlockEntity.java', 'LOGGER.debug("[rs_create_compat] 单元样板库读档：成功 {} 条（新格式 {} / 旧格式迁移 {}，最大槽位 {}）",'),
    ('compat/crafter/CreateCraftLog.java', 'LOGGER.info("[rscc-createcraft] " + message, args);'),
    ('network/AdvancedQuantityKeeperNetworkNode.java', 'LOGGER.info("{} {} 恢复自主控制：{}", KeeperOverflow.LOG_PREFIX, label, lastYielded);'),
    ('network/AdvancedQuantityKeeperNetworkNode.java', 'LOGGER.info("{} {} 让位给同网络更权威的保持器：{}（本机只搬运，不销毁 / 不合成）",'),
    ('network/QuantityKeeperNetworkNode.java', 'LOGGER.info("{} {} 恢复自主控制：{}", KeeperOverflow.LOG_PREFIX, label, lastYielded);'),
    ('network/QuantityKeeperNetworkNode.java', 'LOGGER.info("{} {} 让位给同网络更权威的保持器：{}（本机只搬运，不销毁 / 不合成）",'),
    ('support/BlockContentReleaser.java', 'LOGGER.debug("Block content release skipped a ghost/marker container ({}): its contents ar'),
    ('support/DecompositionTable.java', 'LOGGER.info("[rs_create_compat] 降解表已重载：files={} applied={} skipped={} itemErrors={} entryE'),
    ('support/RsccBuildInfo.java', 'LOGGER.info("{} mod=rs_create_compat version={} revision={}{} branch={} built={} sources={'),
    ('support/RsccBusClipboard.java', 'LOGGER.info("[rscc-ultimine] 保底整簇粘贴（手持剪贴板 + 潜行 + 右键）已{}", enable ? "开启" : "关闭");'),
    ('support/RsccBusClipboard.java', 'LOGGER.info("[rscc-ultimine] 保底整簇粘贴生效（潜行+右键，不经过 FTB）：写入 {} 台，玩家={}，"'),
    ('support/RsccBusClipboard.java', 'LOGGER.info("[rscc-chain] 左键·{}：事件已收到（hand={} 事件报的物品={}） 真实拿着剪贴板的手={} "'),
    ('support/RsccBusClipboard.java', 'LOGGER.info("[rscc-chain] 剪贴板连锁首次判定（不受 devLogs 控制）：hand={} 手持={} 潜行={} "'),
    ('support/RsccCuriosTerminalSlot.java', 'LOGGER.info("Curios is not loaded: terminal shortcut falls back to the player inventory on'),
    ('support/RsccCuriosTerminalSlot.java', 'LOGGER.info("Registered terminal slot reference provider for Curios slots {}",'),
    ('support/RsccCuriosTerminalSlot.java', 'LOGGER.info("Found terminal {} in Curios slot {} index {}",'),
    ('support/RsccCuriosTerminalSlot.java', 'LOGGER.info("Curios slot {} is not present for the player; skipping it when locating the t'),
    ('support/RsccDiag.java', 'LOGGER.info("{} diag logging ON (default) session={} exportDir={} devLogs={}",'),
    ('support/RsccDiag.java', 'LOGGER.info("{} BEGIN tag={} chambers={} buses={} machines={} containers={} keepers={} "'),
    ('support/RsccDiag.java', 'LOGGER.info("{} groups=chambers,buses,machines,containers,universalStorage,playerInventory'),
    ('support/RsccDiag.java', 'LOGGER.info("{} END tag={}", PREFIX, tag);'),
    ('support/RsccIntermediateReusePolicy.java', 'LOGGER.info("{} intermediate_reuse={} (source=command)", ANCHOR, enabled ? "on" : "off");'),
    ('support/RsccRefillPolicy.java', 'LOGGER.info("{} refill={} (source=command)", ANCHOR, enabled ? "on" : "off");'),
    ('support/RsccRefillPolicy.java', 'LOGGER.info("{} refill={} (source=command)", ANCHOR, newMode.id());'),
    ('support/RsccTerminalLocator.java', 'LOGGER.info("{} (x{} in last {}s)", message, occurrences, THROTTLE_SECONDS);'),
    ('support/RsccTerminalLocator.java', 'LOGGER.info(message);'),
    ('support/RsccUltimineIntegration.java', 'LOGGER.info("FTB Ultimine is not loaded: frames stay single-cell "'),
    ('support/RsccUltimineIntegration.java', 'LOGGER.info("FTB Ultimine integration: listener attached to {} (waiting for its register "'),
    ('support/RsccUltimineIntegration.java', 'LOGGER.info("[rscc-ultimine] {}：state={}（{}），FTB Ultimine 已加载={}；"'),
    ('support/RsccUltimineIntegration.java', 'LOGGER.info("FTB Ultimine register event observed: right-click handler registered with {} '),
]

# 分类规则：(文件, 基线行包含的子串, 类别, 代码证据 token（必须在该文件中真实出现）)
EXEMPT_RULES = [
    ("block/entity/AdvancedCollectionCacheBlockEntity.java", "旧档每区域过滤已迁", "ONCE-读档迁移", "migrateLegacyAreaFilters"),
    ("block/entity/AdvancedCollectionCacheBlockEntity.java", "区域选定器绑定的维度与当前维度不符", "THROTTLE-200t", "dimensionWarnCooldown"),
    ("block/entity/AdvancedQuantityKeeperBlockEntity.java", "插件槽异常状态被保留原样", "STATE-签名变化", "lastUpgradeAnomaly"),
    ("block/entity/QuantityKeeperBlockEntity.java", "插件槽异常状态被保留原样", "STATE-签名变化", "lastUpgradeAnomaly"),
    ("block/entity/CollectionCacheBlockEntity.java", "丢弃代表物", "EVENT-写标记时", "sanitizeTags"),
    ("block/entity/CollectionCacheBlockEntity.java", "节点已重新激活", "STATE-激活翻转", "inactiveWarnKind"),
    ("block/entity/CollectionCacheBlockEntity.java", "节点连续 {} tick 未激活", "THROTTLE-5min", "FLUSH_WARN_INTERVAL_TICKS"),
    ("block/entity/CollectionCacheBlockEntity.java", "[rscc-collect]", "THROTTLE-5s窗口+集合变化", "COLLECT_DIAG_INTERVAL_TICKS"),
    ("block/entity/CollectionCacheBlockEntity.java", "检测到半径内有经验球", "THROTTLE-5min", "EXPERIENCE_OFF_WARN_INTERVAL_TICKS"),
    ("block/entity/CollectionCacheBlockEntity.java", "标签过滤器本轮未收集", "THROTTLE-60t", "tagDebugCooldown"),
    ("block/entity/CollectionCacheBlockEntity.java", "流体收集生效", "ONCE-首次", "fluidCollectLogged"),
    ("block/entity/CollectionCacheBlockEntity.java", "流体本轮未收集", "THROTTLE-60t", "fluidDebugCooldown"),
    ("block/entity/CollectionCacheBlockEntity.java", "经验收集生效", "ONCE-首次", "experienceCollectLogged"),
    ("block/entity/CollectionCacheBlockEntity.java", "经验本轮未收集", "THROTTLE-1200t+连续空转", "experienceDebugCooldown"),
    ("block/entity/CollectionCacheBlockEntity.java", "经验球余数不足一个颗粒", "EVENT-余数条件", "pointsPerNugget"),
    ("block/entity/CollectionCacheBlockEntity.java", "经验球未删除（余数留置）", "EVENT-部分收取条件", "orb.getValue() < value"),
    ("block/entity/CollectionCacheBlockEntity.java", "回写网络已恢复", "STATE-恢复翻转", "flushWarnedAt"),
    ("block/entity/CollectionCacheBlockEntity.java", "已接入网络节点，但网络没有存储组件", "THROTTLE-5min", "FLUSH_WARN_INTERVAL_TICKS"),
    ("block/entity/CollectionCacheBlockEntity.java", "缓存里有 {} 件（{} 堆）物品", "THROTTLE-5min", "FLUSH_WARN_INTERVAL_TICKS"),
    ("block/entity/CollectionCacheBlockEntity.java", "玩家确认后销毁缓存区内容", "USER_ACTION-确认销毁", "destroyAllCacheContent"),
    ("block/entity/CollectionCacheBlockEntity.java", "删除模式 = {}", "STATE-开关设置", "setDeleteMode"),
    ("block/entity/CollectionCacheBlockEntity.java", "检测到 {} 条旧版标记", "ONCE-读档迁移", "legacyMarkerAmountsNormalized"),
    ("block/entity/DecompositionGeneratorBlockEntity.java", "降解室发电机", "ONCE-首tick", "loggedTableState"),
    ("block/entity/InventoryProxyBlockEntity.java", "只读到一路内容物", "ONCE-首次", "innerWarned"),
    ("block/entity/NetworkResourceRequesterBlockEntity.java", "所在区块未加载", "DEBUG-逐tick但仅 DEBUG 级", "isRememberedTargetChunkUnloaded"),
    ("block/entity/NetworkResourceRequesterBlockEntity.java", "投递失败 reason=", "THROTTLE-hint冷却", "HINT_COOLDOWN_TICKS"),
    ("block/entity/SequenceExecutionChamberBlockEntity.java", "takes over the chain binding", "EVENT-方块拆除", "onRemove"),
    ("block/entity/SequenceExecutionChamberBlockEntity.java", "adopted the chain binding", "EVENT-链自愈", "isChainHead"),
    ("block/entity/SequenceExecutionChamberBlockEntity.java", "序列执行仓旧存档迁移", "ONCE-读档迁移", "legacyDiskMigrated"),
    ("block/entity/SequencePatternTerminalBlockEntity.java", "样板终端读档", "ONCE-读档", "loadAdditional"),
    ("block/entity/SequencePatternTerminalBlockEntity.java", "单元样板库读档", "ONCE-读档", "loadAdditional"),
    ("compat/crafter/CreateCraftLog.java", "[rscc-createcraft]", "THROTTLE-200t/键（残余风险：本文件不在本轮写域）", "THROTTLE_TICKS"),
    ("network/AdvancedQuantityKeeperNetworkNode.java", "恢复自主控制", "STATE-让位集合变化", "lastYielded"),
    ("network/AdvancedQuantityKeeperNetworkNode.java", "让位给同网络更权威", "STATE-让位集合变化", "lastYielded"),
    ("network/QuantityKeeperNetworkNode.java", "恢复自主控制", "STATE-让位集合变化", "lastYielded"),
    ("network/QuantityKeeperNetworkNode.java", "让位给同网络更权威", "STATE-让位集合变化", "lastYielded"),
    ("support/BlockContentReleaser.java", "ghost/marker container", "ONCE-CAS", "GHOST_SKIP_LOGGED"),
    ("support/DecompositionTable.java", "降解表已重载", "DATA_RELOAD-数据包重载", "lastReport"),
    ("support/RsccBuildInfo.java", "mod=rs_create_compat", "ONCE-启动指纹", "logOnce"),
    ("support/RsccBusClipboard.java", "保底整簇粘贴（手持剪贴板 + 潜行 + 右键）已", "STATE-开关设置", "setSneakFallbackEnabled"),
    ("support/RsccBusClipboard.java", "保底整簇粘贴生效（潜行+右键", "ONCE-CAS", "SNEAK_FALLBACK_USED"),
    ("support/RsccBusClipboard.java", "左键·{}：事件已收到", "ONCE-CAS-每侧一次", "LEFT_CLIENT_DECISION"),
    ("support/RsccBusClipboard.java", "剪贴板连锁首次判定", "ONCE-CAS", "CHAIN_FIRST_DECISION"),
    ("support/RsccCuriosTerminalSlot.java", "Curios is not loaded", "CLIENT-启动一次", "MISSING_SLOT_LOGGED"),
    ("support/RsccCuriosTerminalSlot.java", "Registered terminal slot reference provider", "CLIENT-启动一次", "Registered terminal slot reference provider"),
    ("support/RsccCuriosTerminalSlot.java", "Found terminal", "CLIENT-Set 去重", "FOUND_LOGGED"),
    ("support/RsccCuriosTerminalSlot.java", "Curios slot {} is not present", "CLIENT-Set 去重", "MISSING_SLOT_LOGGED"),
    ("support/RsccDiag.java", "diag logging ON", "ONCE-启动锚点", "onServerStarted"),
    ("support/RsccDiag.java", "BEGIN tag=", "ONCE-导出标记", "snapshot"),
    ("support/RsccDiag.java", "groups=chambers", "ONCE-导出标记", "snapshot"),
    ("support/RsccDiag.java", "END tag=", "ONCE-导出标记", "snapshot"),
    ("support/RsccIntermediateReusePolicy.java", "intermediate_reuse", "STATE-指令设置", "setReuseIntermediates"),
    ("support/RsccRefillPolicy.java", "refill=", "STATE-指令设置", "setMode"),
    ("support/RsccTerminalLocator.java", "in last {}s", "THROTTLE-每玩家5s", "THROTTLE_TICKS"),
    ("support/RsccTerminalLocator.java", "LOGGER.info(message);", "THROTTLE-每玩家5s", "THROTTLE_TICKS"),
    ("support/RsccUltimineIntegration.java", "FTB Ultimine is not loaded", "ONCE-启动", "logInitState"),
    ("support/RsccUltimineIntegration.java", "listener attached", "ONCE-启动", "logInitState"),
    ("support/RsccUltimineIntegration.java", "[rscc-ultimine] {}：state=", "ONCE-启动", "logInitState"),
    ("support/RsccUltimineIntegration.java", "register event observed", "ONCE-CAS", "HANDLER_CALL_SEEN"),
]


def scan_residual(files: Files):
    """扫 server 侧目录里「不走 devLogs 判据的 INFO/DEBUG 打印点」。WARN/ERROR 是必要日志，不在此列。"""
    pat = re.compile(r"LOGGER\.(trace|debug|info|warn|error)\s*\(")
    out = []
    for rel in files.rels():
        if not rel.startswith(SCAN_PREFIXES):
            continue
        if rel == GATE_REL:
            continue  # 判据类自身由 A4/A5 断言
        text = files.get(rel)
        lines = text.splitlines()
        for i, line in enumerate(lines):
            m = pat.search(line)
            if not m:
                continue
            if m.group(1) in ("warn", "error", "trace"):
                continue
            if gated_before(text, sum(len(x) + 1 for x in lines[:i]), 120):
                continue
            raw = line.strip()
            if raw.endswith("(") and i + 1 < len(lines):
                raw = raw + " " + lines[i + 1].strip()
            out.append((rel, norm(raw)[:90]))
    return out


def check_E(files: Files, rep: Report):
    current = scan_residual(files)
    base = list(BASE_RESIDUAL)
    # 多重集比较：新出现的未走闸点 = 判负；被接进开关的（变少）= 进步，不算失败。
    remaining = list(base)
    unexpected = []
    for item in current:
        if item in remaining:
            remaining.remove(item)
        else:
            unexpected.append(item)
    if unexpected:
        rep.bad("E1", "无新增的未走闸周期性打印点", "; ".join("%s :: %s" % x for x in unexpected[:5]))
    else:
        rep.ok("E1", "无新增的未走闸打印点（基线 %d 条；当前仍不走闸 %d 条，均已在基线内分类）"
               % (len(base), len(current)))

    # E2：每条基线点都必须能匹配到分类规则，且规则必须有真实代码证据 token
    unmatched = []
    for rel, snippet in BASE_RESIDUAL:
        hit = [r for r in EXEMPT_RULES if r[0] == rel and r[1] in snippet]
        if not hit:
            unmatched.append("%s :: %s" % (rel, snippet[:60]))
    if unmatched:
        rep.bad("E2", "每条残余点都有分类规则", "; ".join(unmatched[:4]))
    else:
        rep.ok("E2a", "每条残余点都有分类规则（%d 条 / %d 条规则）" % (len(BASE_RESIDUAL), len(EXEMPT_RULES)))

    used = set()
    bad_rules = []
    for rel, sub, cat, token in EXEMPT_RULES:
        if token not in files.get(rel):
            bad_rules.append("%s 缺证据 token %r" % (rel, token))
            continue
        matched = [e for e in BASE_RESIDUAL if e[0] == rel and sub in e[1]]
        if matched:
            used.update(matched)
        else:
            bad_rules.append("%s 规则 %r 没有匹配任何基线点（死规则）" % (rel, sub))
    if bad_rules:
        rep.bad("E2b", "分类规则都有代码证据且不悬空", "; ".join(bad_rules[:4]))
    else:
        rep.ok("E2b", "分类规则全部有代码证据 token，且没有悬空规则")

    # E3：配置默认关 + 接线到唯一判据
    cfg = files.get(CONFIG_REL)
    if 'define("devLogs", false)' in cfg and "RsccAssemblyDebug.initFromConfig(devLogs)" in cfg:
        rep.ok("E3", "配置 devLogs 默认 false，并由 Config 接线到 RsccAssemblyDebug.initFromConfig")
    else:
        rep.bad("E3", "配置 devLogs 默认 false 且接线到唯一判据")

    # E4：启动锚点行必须带 devLogs 实际档位（用户可在日志里核对「开关到底开没开」）
    diag = files.get(DIAG_REL)
    if "diag logging ON" in diag and "devLogs={}" in diag:
        rep.ok("E4", "启动锚点行带 devLogs 实际档位，可直接核对开关状态")
    else:
        rep.bad("E4", "启动锚点行缺 devLogs 档位")


# ========================================================================
# 汇总 & 反例（mutation）
# ========================================================================
def checks_all(files: Files, rep: Report):
    check_A(files, rep)
    check_B(files, rep)
    check_C(files, rep)
    check_D(files, rep)
    check_E(files, rep)


# (名字, 文件, 原文片段, 改后片段, 期望失败的检查 id)
MUTATIONS = [
    ("删掉 event() 的开关判据",
     GATE_REL,
     "        if (enabled) {\n            // 默认采集",
     "        if (true) {\n            // 默认采集",
     "A4"),
    ("删掉接收器 debug() 的开关判据",
     RECEIVER_REL,
     "        if (!RsccAssemblyDebug.isEnabled()) {\n            return;\n        }\n        final int hash =",
     "        final int hash =",
     "D17"),
    ("删掉 withEnabledTemporarily 的 finally 恢复",
     GATE_REL,
     "            setEnabled(previous);",
     "",
     "A6"),
    ("删掉 diag 节点的 requires(source -> true)",
     CMD_REL,
     '        root.then(Commands.literal("diag")\n            .requires(source -> true)',
     '        root.then(Commands.literal("diag")',
     "B3"),
    ("删掉 KeeperTarget 的开关判据",
     "support/KeeperTarget.java",
     '            if (RsccAssemblyDebug.isEnabled()) {\n                LOGGER.info("{} {} 生效目标被钳制',
     '            {\n                LOGGER.info("{} {} 生效目标被钳制',
     "D2"),
    ("在接收器里插入一个未走闸的 INFO 打印点",
     RECEIVER_REL,
     '    private static final String LOG_PREFIX = "[rscc-receiver-output]";',
     '    private static final String LOG_PREFIX = "[rscc-receiver-output]";\n\n'
     '    private void rscc$mt122Fake() {\n'
     '        LOGGER.info("[rscc-receiver-output] mutation fake");\n'
     '    }',
     "E1"),
    ("把作用域化开关退回裸 setEnabled(true)",
     CMD_REL,
     "    /** 挂载「开发日志总开关」的三种写法（同一处理器，行为完全一致；不产生第二套语义）。 */",
     "    /** mutation：裸 setEnabled(true) 必须被判负。 */\n"
     "    static void rscc$mt122Fake() {\n"
     "        RsccAssemblyDebug.setEnabled(true);\n"
     "    }\n\n"
     "    /** 挂载「开发日志总开关」的三种写法（同一处理器，行为完全一致；不产生第二套语义）。 */",
     "C1"),
]


def run_mutations(files: Files, verbose: bool) -> int:
    passed = 0
    for idx, (name, rel, old, new, expect_id) in enumerate(MUTATIONS, 1):
        text = files.get(rel)
        if old not in text:
            print("[FAIL] 反例 %d 无法构造（源码片段已变）: %s" % (idx, name))
            continue
        mutant = dict(files.texts)
        mutant[rel] = text.replace(old, new, 1)
        rep = Report()
        checks_all(Files(mutant), rep)
        fired = expect_id in rep.fail_ids()
        if fired:
            passed += 1
            if verbose:
                print("[PASS] 反例 %d 判负成功: %s -> %s" % (idx, name, expect_id))
        else:
            print("[FAIL] 反例 %d 未判负（断言没有区分力）: %s（期望 %s 失败，实际失败集 %s）"
                  % (idx, name, expect_id, sorted(rep.fail_ids())))
    print("反例（mutation）: %d/%d 判负成功" % (passed, len(MUTATIONS)))
    return passed


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass
    verbose = "--verbose" in sys.argv

    files = load_files()
    if not files.texts:
        print("[FAIL] 读不到源码目录: %s" % SRC)
        return 1

    rep = Report()
    checks_all(files, rep)

    failures = [it for it in rep.items if not it[2]]
    print("=" * 78)
    print("第 122 轮自检：开发日志「关不掉」+ diag 导出「永不需要权限」")
    print("=" * 78)
    if verbose:
        for cid, name, ok, detail in rep.items:
            print("[%s] %-4s %s%s" % ("PASS" if ok else "FAIL", cid, name,
                                      ("  | " + detail) if detail and not ok else ""))
    else:
        for cid, name, ok, detail in failures:
            print("[FAIL] %-4s %s  | %s" % (cid, name, detail))

    passed = len(rep.items) - len(failures)
    print("-" * 78)
    print("断言: %d 项，通过 %d，失败 %d" % (len(rep.items), passed, len(failures)))

    mut_ok = run_mutations(files, verbose)

    # 残余风险（固定列出，免得被当成"已全部修好"）
    print("-" * 78)
    print("残余风险（不在本轮写域，已分类但仍是风险）:")
    print("  * block/entity/… 里仍有 %d 条未走 devLogs 判据的 INFO/DEBUG 打印点；"
          % len(BASE_RESIDUAL))
    print("    每条都有节流/状态变化/一次性证据（见 E2 分类规则），但它们不是「单入口」的，")
    print("    其中 compat/crafter/CreateCraftLog 的 [rscc-createcraft] 在开关关掉时仍会打印（已 200t/键 节流）。")
    print("  * 托管服面板直接改配置文件时，是否触发 NeoForge 配置重载由平台决定；")
    print("    日志里的 `[rscc] ... devLogs=off` 锚点可用于核对运行期实际档位。")
    print("=" * 78)

    if failures:
        return 1
    if mut_ok != len(MUTATIONS):
        return 1
    print("结论: 0 失败；唯一判据入口成立；diag 全路径无权限；反例全部判负。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
