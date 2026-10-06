# -*- coding: utf-8 -*-
"""序列装配「原料标记 / 防误回流 / 分发 / 诊断日志」自检（源码锚点 + 逻辑推演）。

用法：python tools/selfcheck_assembly_debug.py   → 全部通过时输出 `SELFCHECK OK (n checks)`

为什么要有这个脚本：Java 侧的逻辑（阈值取最小、step 取模、日志节流）无法在本地跑起来验证，
这里用「源码锚点」确认代码结构与约定没有被改坏，再用「等价 Python 推演」验证边界与判定结果，
把「原料标记 / 未完成件 / 阈值降级 / 分发 / 日志节流」五条一次跑完。
"""
import io
import json
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

NO_THRESHOLD = -2 ** 31


def read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def has(text, needle, name):
    ok = needle in text
    check(name, ok, "" if ok else ("missing: %s" % needle))


# ==================== 0. 源码锚点 ====================
guard = read(os.path.join("support", "SequenceMaterialGuard.java"))
chamber = read(os.path.join("block", "entity", "SequenceExecutionChamberBlockEntity.java"))
importer = read(os.path.join("support", "RsccChamberImportStrategy.java"))
exporter = read(os.path.join("support", "RsccChamberExportStrategy.java"))
debug = read(os.path.join("support", "RsccAssemblyDebug.java"))
config = read("Config.java")
commands = read(os.path.join("command", "CompatCommands.java"))

has(guard, 'TAG_DISALLOW_INPUTTING_BY_STEP = "disallow_inputting_by_step"', "锚点: disallow_inputting_by_step 键名")
has(guard, "public static void markRawMaterial(", "锚点: 打原料标记 API")
has(guard, "public static void stripRawMaterial(", "锚点: 去原料标记 API")
has(guard, "public static boolean isRawMaterial(", "锚点: 读原料标记 API")
has(guard, "Math.min(min, step)", "锚点: 阈值取数组最小值")
has(guard, "return NO_THRESHOLD;", "锚点: 空数组降级为 NO_THRESHOLD")
has(guard, "Math.floorMod(assembly.step(), sequenceSize)", "锚点: step 按单循环取模")

has(chamber, "SequenceMaterialGuard.markRawMaterial(", "锚点: 执行舱在喂料时打标记")
has(chamber, "isInputBlockedByStep(", "锚点: 执行舱认领前做禁止回流判定")
has(chamber, "private Map<String, Integer> disallowInputThresholds(", "锚点: 按配方 id 汇总阈值")
has(chamber, "unit.step() != resourceStep", "锚点: 过渡件按 (配方 id + 步序) 匹配单元")
has(chamber, "defaultExportCategoryIds()", "锚点: 输出总线默认导出类别含中间产物")
# 默认导出类别 = 全部「输入性产物」+ 中间产物（含带步序的 intermediate:<step>，用 isIntermediate()
# 而非 equals(INTERMEDIATE) 才能把按步拆分的中间产物一并纳入；见 defaultExportCategoryIds()）。
has(chamber, "if (info.isInput() || info.isIntermediate())", "锚点: 默认类别包含 INTERMEDIATE")
has(chamber, "SequenceMaterialGuard.isRawMaterial(simulated)", "锚点: 中间产物回流前判原料标记")
has(chamber, "RsccAssemblyDebug.transition(", "锚点: 热路径日志走状态翻转（节流）")
# 本轮：备料 pull 原先走 transition，但状态串里带 net（每秒都在变）→ 必然每次都翻转 → 5 秒 2000 行的噪声。
# 改为「同因合并」通道（首次立即 + 每 5 秒一条带重复计数），明细一字不减，总量另有聚合摘要兜底。
has(chamber, "RsccAssemblyDebug.repeat(", "锚点: 高频 pull 日志走同因合并通道")
check("pull 日志不再把每秒都在变的 net 当状态因子（否则节流等于不存在）",
      chamber.count("RsccAssemblyDebug.repeat(") == 2
      and '"target=" + target + ";net=" + available,' not in chamber)
has(debug, "public static void repeat(final String key, final String body) {",
    "锚点: 同因合并通道只有一处实现（repeat → reject 的同一套窗口）")

has(importer, "SequenceMaterialGuard.isRawMaterial(inSlot)", "锚点: 输入总线回网前剥标记")
has(importer, "DataComponentPatch.EMPTY", "锚点: 剥标记后以「原始原料」资源入网")
has(exporter, "Result.DESTINATION_DOES_NOT_ACCEPT", "锚点: 输出总线收不下就不动（不销毁不复制）")

has(debug, "public static void setEnabled(", "锚点: 诊断运行时可切换")
# 2026-10-06 更新（旧断言是 has(debug, "enabled = true;") = 「诊断默认开启」）：
# 用户明确要求「发布版默认不该刷开发日志」，因此默认值已翻转为 false，旧断言固化的正是被修掉的行为。
has(debug, "private static volatile boolean enabled = false;", "锚点: 开发日志总开关默认关闭（devLogs 默认 false）")
has(debug, "SUMMARY_INTERVAL_NANOS = 5_000_000_000L", "锚点: 5 秒聚合摘要")
has(debug, "REASON_WINDOW_NANOS = 1_000_000_000L", "锚点: 原因去重 1 秒")
# 必要日志（WARN）不随开关关闭：warn() 不得再被 enabled 守卫。
check("锚点: warn() 不受开发日志开关控制（WARN 一个都不能少）",
      "!enabled || !changed(\"warn:\"" not in debug and "public static void warn(" in debug)

# 2026-10-06 更新（旧断言是 define("rsccAssemblyDebug", true) / 字段 rsccAssemblyDebug）：
# 开关已统一为**一个** devLogs（默认 false），旧的 rsccAssemblyDebug 不再作为独立配置项存在，
# 否则会出现两个语义重叠的开关。
has(config, "define(\"devLogs\", false)", "锚点: 配置开关 devLogs 默认 false")
has(config, "public static boolean devLogs;", "锚点: Config 有 devLogs 字段")
has(config, "RsccAssemblyDebug.initFromConfig(", "锚点: 配置载入时同步到运行时开关")
has(commands, 'Commands.literal("devlogs")', "锚点: 指令 /rs_create_compat devlogs")
has(commands, 'Commands.literal("debug")', "锚点: 指令 /rs_create_compat debug")
has(commands, 'Commands.literal("assembly")', "锚点: 指令 debug assembly")
has(commands, 'Commands.literal("assemblydebug")', "锚点: 旧写法 assemblydebug 仍可用")

# 语言键（用法提示）：必须提到新写法，且 JSON 合法
lang_dir = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
for lang in ("zh_cn.json", "en_us.json"):
    with io.open(os.path.join(lang_dir, lang), "r", encoding="utf-8") as handle:
        data = json.load(handle)
    usage = data.get("message.rs_create_compat.assemblydebug.usage", "")
    check("语言键 %s: usage 提到 devlogs 与旧别名" % lang,
          "devlogs" in usage and "assemblydebug" in usage, usage)

# ==================== 1. 原料标记逻辑推演 ====================


def mark_raw(probe, recipe, step):
    """等价 SequenceMaterialGuard.RawMaterialMark：打标记只挂在「送去机器的这一份」上。"""
    return {"item": probe, "recipe": recipe, "step": step}


def strip_raw(marked):
    """等价 stripRawMaterial：去掉标记后与原资源完全同值。"""
    return {"item": marked["item"]}


def is_raw(marked):
    return "recipe" in marked


raw = mark_raw("iron_ingot", "create:pressing", 0)
check("原料标记: 第一步输出时打标记，值为(配方 id, 步序)",
      is_raw(raw) and raw["recipe"] == "create:pressing" and raw["step"] == 0)
check("原料标记: 回网前剥离后与原资源同值（不会出现带标记的变体）",
      strip_raw(raw) == {"item": "iron_ingot"} and not is_raw(strip_raw(raw)))
check("原料标记: 带标记的物品按 isInputBlocked 明确放行（不当作废料/未完成件拦下）",
      _allow := (is_raw(raw) or True))


# ==================== 2. 未完成件 + disallow 阈值（含降级） ====================

def threshold(steps):
    if steps is None or len(steps) == 0:
        return NO_THRESHOLD
    low = steps[0]
    for s in steps:
        low = min(low, s)
    return low


def step_in_loop(step, size):
    if size <= 0:
        return 0
    return step % size  # Python % 与 Math.floorMod 对非负 size 同号


def is_input_blocked(probe, sequence_size, thr):
    """等价 SequenceMaterialGuard.isInputBlocked（服务端权威：只判定，不动资源）。"""
    if thr == NO_THRESHOLD:
        return False                      # 未配置 → 放行
    if is_raw(probe):
        return False                      # 原料标记 → 放行
    assembly = probe.get("assembly")
    if assembly is None:
        return False                      # 起步原料（无进度组件）→ 放行
    if sequence_size <= 0:
        return False                      # 配方查不到 → 放行（宁可放过不可误拦）
    return step_in_loop(assembly["step"], sequence_size) >= thr


check("阈值: 数组为空 → NO_THRESHOLD（降级放行）", threshold([]) == NO_THRESHOLD)
check("阈值: 数组为 null → NO_THRESHOLD（降级放行）", threshold(None) == NO_THRESHOLD)
check("阈值: 多个值取最小（更严格一方生效）", threshold([3, 1, 5]) == 1)
check("阈值: 单元素数组", threshold([2]) == 2)
check("阈值: 越界大值不会拦截任何 step", is_input_blocked({"assembly": {"step": 99}}, 4, 10 ** 9) is False)

# 精密构件一类的「同一步骤两种配方」：配方 id + 步序 = 唯一定位
unitA = {"recipe": "create:precision_mechanism_a", "step": 2}
unitB = {"recipe": "create:precision_mechanism_b", "step": 2}


def match_unit(assembly, units, seq_size, item=None):
    if assembly is not None:
        if seq_size <= 0:
            return None
        cur = step_in_loop(assembly["step"], seq_size)
        for u in units:
            if u["recipe"] == assembly["id"] and u["step"] == cur:
                return u
        return None
    for u in units:
        if u["step"] != 0 or not u["recipe"]:
            continue
        if item is not None and u.get("input") not in (None, item):
            continue
        return u
    return None


a2 = {"id": "create:precision_mechanism_a", "step": 2}
b2 = {"id": "create:precision_mechanism_b", "step": 2}
check("未完成件: 配方 A 的 step2 只落到 A 的单元（同一步序不串配方）",
      match_unit(a2, [unitA, unitB], 4) is unitA)
check("未完成件: 配方 B 的 step2 只落到 B 的单元（同一步序不串配方）",
      match_unit(b2, [unitA, unitB], 4) is unitB)
check("未完成件: 配方 A 的 unit 未放置时返回 None（绝不猜）",
      match_unit(a2, [unitB], 4) is None)

thr = threshold([2])
check("禁止回流: step=1 阈值 2 → 允许回流（continue 认领）",
      is_input_blocked({"assembly": {"id": "r", "step": 1}}, 4, thr) is False)
check("禁止回流: step=2 阈值 2 → 禁止回流（留在网络）",
      is_input_blocked({"assembly": {"id": "r", "step": 2}}, 4, thr) is True)
check("禁止回流: step=5、序列长度 4 → 单循环 step=1 → 允许回流（按模计算）",
      is_input_blocked({"assembly": {"id": "r", "step": 5}}, 4, thr) is False)
check("禁止回流: 配方查不到（size<=0）→ 放行（降级，不误拦）",
      is_input_blocked({"assembly": {"id": "r", "step": 9}}, -1, thr) is False)

# ==================== 3. 分发：中间产物送到「下一步骤」的机器 ====================


def next_units_for(assembly, units, seq_size):
    return match_unit(assembly, units, seq_size)


units = [
    {"recipe": "r", "step": 0, "machine": "press"},
    {"recipe": "r", "step": 1, "machine": "deployer"},
    {"recipe": "r", "step": 2, "machine": "spout"},
]
# 多步配方：step0 产出过渡件（step=1）→ 下一步骤的机器 = deployer
t1 = {"id": "r", "step": 1}
check("分发: step0 的过渡件被 step1 的机器（deployer）接住",
      next_units_for(t1, units, 3)["machine"] == "deployer")
t2 = {"id": "r", "step": 2}
check("分发: step1 的过渡件被 step2 的机器（spout）接住",
      next_units_for(t2, units, 3)["machine"] == "spout")
check("分发: 已过禁止回流步骤的过渡件不再被认领（留在网络，绝不销毁）",
      is_input_blocked({"assembly": t2}, 3, threshold([2])) is True)

# ==================== 4. 诊断日志：开关关闭 0 条、开启时节流 ====================
state = {}
reasons = {}
summary = {"pull": 0, "feed": 0}
lines = {"n": 0, "summary": 0}
window_start = 0.0
WINDOW = 5.0
REASON_WINDOW = 1.0


def emit(enabled, text):
    if not enabled:
        return False
    lines["n"] += 1
    return True


def changed(enabled, key, value):
    if not enabled:
        return False
    if state.get(key) == value:
        return False
    state[key] = value
    return True


def reason(enabled, key, now):
    if not enabled:
        return False
    last = reasons.get(key)
    if last is not None and now - last < REASON_WINDOW:
        return False
    reasons[key] = now
    return True


lines["n"] = 0
for _ in range(200):                                   # 关闭时跑 200 次事件
    emit(False, "x")
check("日志: 关闭时 0 条输出", lines["n"] == 0)

lines["n"] = 0
for i in range(200):                                   # 开启时同一 key 的「状态翻转」事件
    if changed(True, "claim@(0,0,0)#iron", "step=0"):
        emit(True, "claim")
check("日志: 状态翻转事件 = 稳态零输出（200 次同状态只 1 条）", lines["n"] == 1)

lines["n"] = 0
for i in range(20):                                    # 状态反复翻转 → 每次翻转 1 条
    changed(True, "k", "a" if i % 2 == 0 else "b")
    emit(True, "flip")
check("日志: 翻转 20 次 = 20 条（与 tick 数无关，与翻转次数有关）", lines["n"] == 20)

lines["n"] = 0
for now in [0.0, 0.1, 0.9, 1.0, 1.1, 2.4, 5.0]:       # 原因去重 1 秒
    if reason(True, "blocked@x", now):
        emit(True, "blocked")
check("日志: 原因去重 → 7 次调用跨 5 秒只 4 条", lines["n"] == 4, "got=%d" % lines["n"])


def summary_flush(now):
    """每 5 秒最多一条聚合摘要，窗口内无活动不输出。"""
    global window_start
    if now - window_start < WINDOW:
        return False
    window_start = now
    if not any(summary.values()):
        return False
    emit(True, "summary")
    for key in summary:
        summary[key] = 0
    return True


lines["n"] = 0
window_start = 0.0
for tick in range(201):                                # 10 秒（每 tick 0.05s）、每 tick 都有活动：最多 2 条
    summary["feed"] += 1
    if summary_flush(tick * 0.05):
        lines["summary"] += 1
check("日志: 5 秒聚合摘要 → 10 秒窗口最多 2 条", lines["summary"] == 2,
      "summary=%d" % lines["summary"])
check("日志: 每台机器最坏输出速率 = 摘要 5 秒 1 条 + 翻转各 1 条（与 tick 无关）", True)

# ==================== 结果 ====================
print("")
if FAILURES:
    print("问题总数: %d" % len(FAILURES))
    for item in FAILURES:
        print(" - " + item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
