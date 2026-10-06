# -*- coding: utf-8 -*-
"""round48 自检：**开发日志总开关**（Config.devLogs，默认 false）。

用户原话：「这一版一直在后台拉日志，给配置加一个开关控制这个开发日志是否输出；必要的日志可以留着。」

本脚本断言（每条都是可以直接被源码事实证伪的锚点，不是复述注释）：
  1. 配置项 `devLogs` 存在且**默认 false**；旧的 `rsccAssemblyDebug` 不再作为独立配置项（统一成一个开关）；
  2. 总开关的运行时实现 `RsccAssemblyDebug.enabled` **默认 false**，且每个输出 API 都被它守卫；
  3. **每一个高频发出点都在开关内**（逐个源码锚点核对：锚点行往上 N 行内必须出现守卫）——
     这是本自检的核心：漏掉任何一个高频点，开关关掉后日志照样刷屏；
  4. **WARN / ERROR 不受开关控制**（全工程扫描：任何 WARN/ERROR 调用点上方 2 行内都不得出现
     `RsccAssemblyDebug.isEnabled()`）；
  5. 启动那一行版本信息（`[rscc-build]`）仍在，且只由 `RsccBuildInfo.logOnce()` 打一次；
  6. 指令三个写法（devlogs / assemblydebug / debug assembly）都接到同一个开关。

用法：python tools/selfcheck_round48_devlog_switch.py
      → 全通过输出 `SELFCHECK OK (n checks)`；任一断言失败 → 退出码 1 并列出反例。
"""
import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

FAILURES = []
CHECKS = [0]


def read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, ("" if ok else ((" | " + detail) if detail else ""))))


def lines_of(rel):
    return read(rel).splitlines()


# ==================== 0. 配置：唯一的开关，默认关闭 ====================

config = read("Config.java")
check("配置项 devLogs 存在且默认 false（发布版默认安静）",
      'define("devLogs", false)' in config,
      "缺少 .define(\"devLogs\", false)")
check("旧的 rsccAssemblyDebug 不再是独立配置项（不留语义重叠的两个开关）",
      'define("rsccAssemblyDebug"' not in config,
      "仍存在 define(\"rsccAssemblyDebug\", ...)")
check("Config 有 devLogs 静态字段", "public static boolean devLogs;" in config)
check("配置载入时把 devLogs 同步到运行时总开关",
      "devLogs = DEV_LOGS.get();" in config and "RsccAssemblyDebug.initFromConfig(" in config)

# ==================== 1. 运行时总开关默认关闭 ====================

debug = read("support/RsccAssemblyDebug.java")
check("运行时开关默认值 = false（这就是「没有任何指令也在刷屏」的根因）",
      "private static volatile boolean enabled = false;" in debug,
      "enabled 默认值不是 false")

GATED_API = ["event", "changed", "transition", "reason", "reject", "repeat", "trace", "dedupe",
             "countPull", "countPullFluid", "countFeed", "countCollect", "countReturn", "countReject"]


def method_body(text, signature_fragment):
    """按大括号配对取出以 signature_fragment 开头的方法体（含签名行）。"""
    start = text.find(signature_fragment)
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


for api in GATED_API:
    body = method_body(debug, "public static %s " % api) or method_body(debug, "public static boolean %s(" % api)
    if body is None:
        body = method_body(debug, "static void %s(" % api) or method_body(debug, "static boolean %s(" % api) \
            or method_body(debug, "static long %s(" % api) or method_body(debug, "private static void %s(" % api)
    if body is None:
        check("RsccAssemblyDebug.%s 被开关守卫" % api, False, "找不到方法体（签名变了？）")
        continue
    # 允许两种写法：① 本方法自己判 enabled；② 本方法只是转发到另一个已守卫的 API（repeat→reject、
    # transition→changed）。第二种同样是「关闭时零输出」，不能算漏。
    self_guarded = re.search(r"\benabled\b", body) is not None
    delegates = re.search(r"\b(changed|reject|event)\(|\breason\(|\btrace\(", body) is not None
    check("RsccAssemblyDebug.%s 被开关守卫（关闭时零输出）" % api, self_guarded or delegates,
          "既没判 enabled，也没有转发到已守卫的 API")

warn_body = method_body(debug, "public static void warn(")
check("RsccAssemblyDebug.warn 是必要日志：不受 devLogs 开关控制（WARN 不能少）",
      warn_body is not None and "!enabled" not in warn_body and "LOGGER.warn(" in debug,
      "warn() 仍被 enabled 守卫")

# ==================== 2. 核心：每个高频发出点都在开关内 ====================

# (文件, 锚点子串, 允许往上找守卫的行数, 人类可读的族名)
ANCHORS = [
    ("block/entity/RangeChargerBlockEntity.java",
     '"[rscc-range-charger] save @{},{},{} Energy={} capacity={}",', 6, "[rscc-range-charger] 每台每 60 秒一条"),
    ("block/entity/RangeChargerBlockEntity.java",
     '"[rscc-range-charger] load-enter @{},{},{} hasEnergyTag={} savedEnergy={}",', 6, "[rscc-range-charger] 每次区块加载"),
    ("block/entity/RangeChargerBlockEntity.java",
     '"[rscc-range-charger] load-done @{},{},{} savedEnergy={} loadedEnergy={} "', 6, "[rscc-range-charger] 每次区块加载"),
    ("network/IntermediateCacheNetworkNode.java",
     '"[rscc-cache-source] refreshSources disks={} added={} exposedStored={}",', 8, "[rscc-cache-source] 每次网络源刷新"),
    ("support/RsccFlowLedger.java",
     'LOGGER.info("{} {} balanced again (was {} sample(s) of imbalance, peak={} on {})",', 6, "[rscc-ledger] INFO 失衡恢复"),
    ("support/RsccFlowLedger.java",
     'LOGGER.info("{} {} unbalanced (first) peak={} on {} :: {}",', 6, "[rscc-ledger] INFO 首次失衡"),
    ("support/RsccFlowLedger.java",
     'LOGGER.info("{} {} unbalanced (same imbalance repeated {} times, peak={} on {}) :: {}",', 6, "[rscc-ledger] INFO 重复合并"),
    ("support/KeeperOverflow.java",
     'LOGGER.info("{} {} 开始销毁过量：{} 保留目标 {}，超出的部分将被销毁",', 6, "[rscc-keeper] 实测每秒最多 12 条（抖动）"),
    ("support/KeeperOverflow.java",
     'LOGGER.info("{} {} 过量已清完：{} 共销毁 {}（目标 {}）",', 6, "[rscc-keeper] 实测每秒最多 12 条（抖动）"),
    ("support/KeeperOverflow.java",
     'LOGGER.info("{} {} 停止销毁过量：{} 本段共销毁 {}（目标 {}）",', 6, "[rscc-keeper] 实测每秒最多 12 条（抖动）"),
    ("support/RsccIntermediateFlow.java",
     '"[rscc-intermediate-flow] moved={} poolFree(before)={} poolStored(after)={}"', 8, "[rscc-intermediate-flow] 每次存量迁移"),
]


def guard_near(rel, needle, window):
    """anchors: 锚点行往上 window 行内必须出现开关守卫；返回 (ok, 定位信息)。"""
    rows = lines_of(rel)
    hit = None
    for i, row in enumerate(rows):
        if needle in row:
            hit = i
            break
    if hit is None:
        return False, "找不到锚点: %s" % needle
    lo = max(0, hit - window)
    snippet = rows[lo:hit + 1]
    guarded = any(("RsccAssemblyDebug.isEnabled" in r) or ("devLogs" in r) for r in snippet)
    return guarded, "锚点在第 %d 行，守卫窗口 %d 行内%s" % (
        hit + 1, window, "有" if guarded else "**没有**任何开关判定")


def guard_in_method(rel, needle):
    """锚点所在**方法体**内必须出现开关守卫（用于「守卫在方法第一行、调用在后面」的写法）。"""
    text = read(rel)
    rows = text.splitlines()
    hit = None
    for i, row in enumerate(rows):
        if needle in row:
            hit = i
            break
    if hit is None:
        return False, "找不到锚点: %s" % needle
    # 向上找方法签名（4 空格缩进 + 修饰符）
    start = hit
    sig = re.compile(r"^    (?:public|private|protected|static|final|synchronized|@)")
    while start > 0 and not sig.match(rows[start]):
        start -= 1
    body = "\n".join(rows[start:])
    open_at = body.find("{")
    depth = 0
    end = len(body)
    for idx in range(open_at, len(body)):
        if body[idx] == "{":
            depth += 1
        elif body[idx] == "}":
            depth -= 1
            if depth == 0:
                end = idx
                break
    body = body[:end]
    ok = "RsccAssemblyDebug.isEnabled" in body
    return ok, "锚点在第 %d 行，方法 %s（第 %d 行起）内%s守卫" % (
        hit + 1, rows[start].strip()[:60], start + 1, "有" if ok else "**没有**")


for rel, needle, window, label in ANCHORS:
    ok, detail = guard_near(rel, needle, window)
    check("开关内: %s（%s）" % (label, rel), ok, detail)

# [loader] 的守卫写在 logState() 第一行、真正输出在方法末尾（中间是节流判定），
# 因此这里按「同一方法体内必须有守卫」核对 —— 比固定行窗更强（换行 / 加注释不会失效）。
ok, detail = guard_in_method("block/entity/SchematicLoaderBlockEntity.java",
                             'LOGGER.info("[loader {}] {}", key, message);')
check("开关内（方法级）: [loader] logState 的守卫在其方法体内", ok, detail)

# [rscc-cache-source] 的旧条件里有一半是「added > 0 就无条件打」——那等于开关关掉后每次网络重建仍会刷。
check("[rscc-cache-source] 不再有「added > 0 就无条件打日志」的旁路（否则开关关不掉它）",
      "added > 0 ||" not in read("network/IntermediateCacheNetworkNode.java"))


# ==================== 3. WARN / ERROR 不受开关控制 ====================

WARN_CALL = re.compile(r"\b(?:LOGGER|LOG|ORG_SLF4J)\.(warn|error)\s*\(")
bad_warn = []
for base, _dirs, names in os.walk(SRC):
    for name in names:
        if not name.endswith(".java"):
            continue
        path = os.path.join(base, name)
        rel = os.path.relpath(path, SRC)
        rows = io.open(path, "r", encoding="utf-8").read().splitlines()
        for i, row in enumerate(rows):
            if not WARN_CALL.search(row):
                continue
            above = rows[max(0, i - 2):i + 1]
            if any("RsccAssemblyDebug.isEnabled" in r for r in above):
                bad_warn.append("%s:%d" % (rel.replace("\\", "/"), i + 1))
check("WARN / ERROR 调用点不受 devLogs 开关控制（全工程扫描，一个都不能少）",
      not bad_warn, "被开关吞掉的告警: %s" % ", ".join(bad_warn))

rows_ledger = lines_of("support/RsccFlowLedger.java")
sustained = [i for i, r in enumerate(rows_ledger) if "unbalanced (sustained" in r]
check("哨兵断言：[rscc-ledger] 的 sustained 告警行仍存在且仍是 WARN",
      bool(sustained) and any("LOGGER.warn" in rows_ledger[i] or "LOGGER.warn" in rows_ledger[i - 1]
                              for i in sustained))

# ==================== 4. 启动那一行仍在（且只一行） ====================

build = read("support/RsccBuildInfo.java")
diag = read("support/RsccDiag.java")
check("启动版本行仍在：[rscc-build] 前缀 + 由 RsccBuildInfo.logOnce() 输出",
      'PREFIX = "[rscc-build]"' in build and "public static void logOnce()" in build
      and "RsccBuildInfo.logOnce();" in diag)
check("版本行幂等（服务器反复启停也只一行）", "private static volatile boolean logged;" in build
      and "if (logged)" in build)
check("版本行不受 devLogs 开关控制", "isEnabled" not in build)

# ==================== 5. 指令：三个写法都接同一个开关 ====================

commands = read(os.path.join("command", "CompatCommands.java"))
check("指令 /rs_create_compat devlogs 存在", 'Commands.literal("devlogs")' in commands)
check("旧写法 assemblydebug 仍可用", 'Commands.literal("assemblydebug")' in commands)
check("debug assembly 仍可用", 'Commands.literal("assembly")' in commands)
check("三个写法都调用同一个 RsccAssemblyDebug.setEnabled（不产生第二套语义）",
      commands.count("setAssemblyDebug(context") >= 3
      and "RsccAssemblyDebug.setEnabled(" in commands
      and "RsccAssemblyDebug.initFromConfig(" not in commands)

# ==================== 6. 关掉开关后仍在输出的「必要日志」必须是低频 ====================

# 开关关闭后仍会输出的每一族，都必须能指出它的节流锚点（一次性 / 同键只一次 / 冷却窗口）。
THROTTLE_ANCHORS = [
    ("support/KeeperTarget.java", "CLAMP_LOG_COOLDOWN_TICKS = 200",
     "保持器「目标被钳制」：200 tick + 同设定值去重（历史全量仅 370 行）"),
    ("block/entity/CollectionCacheBlockEntity.java", "experienceOffWarnCooldown = 200",
     "归流缓存仓「经验开关没开」：10 秒一次且只在附近真有经验球时"),
    ("block/entity/CollectionCacheBlockEntity.java", "experienceEmptyStreak < 20",
     "归流缓存仓「经验空转」：连续 20 轮空转才提示，且 1200 tick 一条"),
    ("support/RsccTerminalLocator.java", "NOT_FOUND_LOGGED.add(player.getUUID())",
     "终端定位失败：每名玩家只一条"),
    ("support/RsccCuriosTerminalSlot.java", "FOUND_LOGGED.add(slotId",
     "饰品槽找到终端：同槽位+下标+物品只一条"),
    ("support/RsccBuildInfo.java", "private static volatile boolean logged;",
     "启动版本行：进程内幂等，只一条"),
    ("network/QuantityKeeperNetworkNode.java", "yielded.equals(lastYielded)",
     "保持器让位/恢复：只在状态翻转时各一条"),
    ("network/AdvancedQuantityKeeperNetworkNode.java", "yielded.equals(lastYielded)",
     "保持器让位/恢复（高级）：只在状态翻转时各一条"),
    ("block/entity/CollectionCacheBlockEntity.java", "fluidCollectLogged = true",
     "流体收集生效：首次成功一条（之后不再打）"),
]
for rel, needle, label in THROTTLE_ANCHORS:
    ok = needle in read(rel)
    check("必要日志低频锚点: %s（%s）" % (label, rel), ok, "缺少节流锚点: %s" % needle)

# ==================== 结果 ====================

print("")
if FAILURES:
    print("问题总数: %d" % len(FAILURES))
    for item in FAILURES:
        print(" - " + item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
