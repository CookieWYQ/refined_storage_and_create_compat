# -*- coding: utf-8 -*-
"""自检：全模组统一节流工具 RsccRateLimiter（round66）。

用法：python tools/selfcheck_round66_rate_limiter.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

依据：docs/REQUESTER_ORDER_AND_PACER_DESIGN.md §4.3（工具 API + 4 条不变量）、
      §4.4（7 张「改前 / 改后」数值对照表）、§4.6（既有自检锚点）、§4.7（R1/R3/R4/R5/R6）。

断言分四层
----------
A. **不变量 I1~I4**：把 Java 语义（int 32 位、`>>`、饱和移位、饱和乘法）在 Python 里<b>逐条镜像</b>，
   对 level 0..6 以及 64 / -1 / Integer.MIN_VALUE / Integer.MAX_VALUE 等异常输入扫描：
   `intervalTicks() >= 1`、`rateSaturating()/batchSaturating()` 不溢出不为负、`level()` 夹在 0..6。
   同时断言 `RsccRateLimiter.java` 的<b>源码形状</b>确实是这套实现（不是「文档写了但代码没写」）。
B. **逐台改前 vs 改后数值对照**：把 §4.4 的表 1~表 6 抄进来，对「数值不变」的机器断言<b>逐格相等</b>
   （且与文档给的「落地版」列逐格相符），对归流缓存仓扫描间隔断言<b>新曲线的具体数值</b>，
   并复核文档给出的「改前」列（改前模型也照 Java 掩码语义实现，溢出必须真的变负数）。
C. **回归锚点**：`getDeleteRatePerTick()` 的方法体字面量仍在（防后人误改），
   饰品槽固定节拍那一行字面量仍在（§4.6 明确要求不动）。
D. **R1 反例**：「一格 64 个速度升级」的输入下，旧模型放行（并因移位掩码反而变慢），
   新模型把计数夹到上限并把多余部分弹出；并断言源码里确实是按「个数」计数、槽上限已夹到 1。

判据来源一律是<b>可核对的具体数值</b>，不是「看起来对」。
"""

from __future__ import annotations

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
JAVA = os.path.join(ROOT, "src", "main", "java")

INT_MAX = 2 ** 31 - 1
INT_MIN = -(2 ** 31)
LONG_MAX = 2 ** 63 - 1
LONG_MIN = -(2 ** 63)

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, ("" if ok else ((" | " + detail) if detail else ""))))


def read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def code_only(text):
    """剥掉 // 与 /*...*/ 注释，只对真实代码做锚点判定（javadoc 里会解释「为什么」）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def method_body(text, signature):
    """按大括号配平取出以 signature 开头的方法体（含签名行）。"""
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


# ======================================================================================
# A-0. Java 语义镜像（与 RsccRateLimiter.java 一一对应；下面 A-2 会断言源码确实长这样）
# ======================================================================================


def j_int32(v):
    """Java int 回绕。"""
    v &= 0xFFFFFFFF
    return v - 2 ** 32 if v >= 2 ** 31 else v


def j_shl_masked(base, n):
    """Java 的裸 `<<`：移位量按 32 取模（旧代码的坑就在这里）。"""
    return j_int32(j_int32(base) << (n & 31))


MAX_LEVEL = 6
MAX_POWER = 24
STACK_AS_SPEED_WEIGHT = 3
UNLIMITED = INT_MAX


def j_level(raw):
    return max(0, min(raw, MAX_LEVEL))


def j_power(speed, stack):
    return min(j_level(speed) + j_level(stack) * STACK_AS_SPEED_WEIGHT, MAX_POWER)


def j_shift_saturating(base, power):
    result = j_int32(base)
    for _ in range(max(0, power)):
        if result > INT_MAX >> 1:
            return INT_MAX
        result = j_int32(result << 1)
    return result


def j_rate_saturating(base, speed, stack):
    return j_shift_saturating(max(1, base), j_power(speed, stack))


def j_batch_saturating(base, stack):
    a = max(1, base)
    b = 1 + j_level(stack)
    product = a * b
    return LONG_MAX if not (LONG_MIN <= product <= LONG_MAX) else product


def j_interval_ticks(base, speed):
    return max(1, max(1, base) >> j_level(speed))


def j_is_maxed(raw):
    return j_level(raw) >= MAX_LEVEL


limiter = read("support/RsccRateLimiter.java")
limiter_code = code_only(limiter)

# ======================================================================================
# A-1. 工具的 API 与不变量实现形状（源码级）
# ======================================================================================

print("=== A. RsccRateLimiter：API + 4 条不变量 ===")

check("文件在 support/ 包下且类名正确",
      "package cretae.cookiewyq.rs_create_compat.support;" in limiter
      and "public final class RsccRateLimiter" in limiter)
check("私有构造器（纯静态工具，不允许实例化）",
      "private RsccRateLimiter()" in limiter_code)
check("I4 单点：MAX_LEVEL 直接取 UpgradeSlot.MAX_PER_UPGRADE（不另写 6）",
      "public static final int MAX_LEVEL = UpgradeSlot.MAX_PER_UPGRADE;" in limiter_code
      and 'import cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot;' in limiter_code)
check("堆叠权重保留工程既有口径（1 堆叠 = 3 速度）",
      "public static final int STACK_AS_SPEED_WEIGHT = 3;" in limiter_code)
check("MAX_POWER = MAX_LEVEL + MAX_LEVEL * STACK_AS_SPEED_WEIGHT（推导式，不是字面量 24）",
      "public static final int MAX_POWER = MAX_LEVEL + MAX_LEVEL * STACK_AS_SPEED_WEIGHT;" in limiter_code)
check("满级哨兵 UNLIMITED = Integer.MAX_VALUE",
      "public static final int UNLIMITED = Integer.MAX_VALUE;" in limiter_code)

level_body = method_body(limiter_code, "public static int level(")
check("I4：level() 把任何输入夹到 [0, MAX_LEVEL] 且这是唯一收敛点",
      level_body is not None and "Math.clamp(rawUpgrades, 0, MAX_LEVEL)" in level_body)
power_body = method_body(limiter_code, "public static int power(")
check("power() 先过 level() 再乘权重、再夹 MAX_POWER",
      power_body is not None
      and "level(speedUpgrades)" in power_body and "level(stackUpgrades) * STACK_AS_SPEED_WEIGHT" in power_body
      and "Math.min(" in power_body)
rate_body = method_body(limiter_code, "public static int rateSaturating(")
check("I1：rateSaturating() 先把 base 夹到 >= 1，再走饱和移位（形状①）",
      rate_body is not None
      and "Math.max(1, basePerTick)" in rate_body
      and "shiftSaturating(" in rate_body)
batch_body = method_body(limiter_code, "public static long batchSaturating(")
check("I1/I3：batchSaturating() 走饱和乘法（形状②）",
      batch_body is not None and "multiplySaturating(" in batch_body
      and "Math.max(1L, baseBatch)" in batch_body and "1L + level(stackUpgrades)" in batch_body)
interval_body = method_body(limiter_code, "public static int intervalTicks(")
check("I1/I2：intervalTicks() = max(1, base >> level(n))，无除法（形状③）",
      interval_body is not None
      and "Math.max(1, Math.max(1, baseInterval) >> level(speedUpgrades))" in interval_body)
shift_body = method_body(limiter_code, "public static int shiftSaturating(")
check("I3：shiftSaturating() 每次移位前判 `result > Integer.MAX_VALUE >> 1`（越界即封顶）",
      shift_body is not None
      and "result > Integer.MAX_VALUE >> 1" in shift_body
      and "return Integer.MAX_VALUE;" in shift_body)
mult_body = method_body(limiter_code, "public static long multiplySaturating(")
check("I3：multiplySaturating() 走 Math.multiplyExact，溢出返回 Long.MAX_VALUE（绝不为负）",
      mult_body is not None
      and "Math.multiplyExact(a, b)" in mult_body and "return Long.MAX_VALUE;" in mult_body
      and "ArithmeticException" in mult_body)
maxed_body = method_body(limiter_code, "public static boolean isMaxed(")
check("isMaxed() 是「满级 = 不限」的唯一判定（≥ MAX_LEVEL，漏斗塞 64 也算满级）",
      maxed_body is not None and "level(rawSpeedUpgrades) >= MAX_LEVEL" in maxed_body)
check("I2：整个工具类里一条除法都没有（间隔型走 >>，批量型走 *）",
      "/" not in limiter_code.replace("//", ""),
      "源码里出现了 '/'")

# 不变量扫描：输入集合刻意包含 64 / 负数 / 极值
bases = [INT_MIN, -7, -1, 0, 1, 2, 500, 8192, 1000000, INT_MAX]
ups = [INT_MIN, -1, 0, 1, 5, 6, 7, 64, INT_MAX]
violations = []
for base in bases:
    for s in ups:
        for st in ups:
            rate = j_rate_saturating(base, s, st)
            if rate < 1 or rate > INT_MAX:
                violations.append("rate(%d,%d,%d)=%d" % (base, s, st, rate))
            batch = j_batch_saturating(base, st)
            if batch < 1 or batch > LONG_MAX:
                violations.append("batch(%d,%d)=%d" % (base, st, batch))
        interval = j_interval_ticks(base, s)
        if interval < 1:
            violations.append("interval(%d,%d)=%d" % (base, s, interval))
check("I1/I3：%d 组异常输入（含 64 / 负数 / 极值）下速率与批量恒 >= 1 且不溢出"
      % (len(bases) * len(ups) * len(ups)),
      not violations, "; ".join(violations[:5]))

check("I4：level() 对 -1 / Integer.MIN_VALUE / 64 / Integer.MAX_VALUE 的夹取",
      j_level(-1) == 0 and j_level(INT_MIN) == 0 and j_level(64) == 6
      and j_level(7) == 6 and j_level(6) == 6 and j_level(INT_MAX) == 6)
check("I4：漏斗塞 64 个速度升级 ⇒ power 仍是 6（不是 64，也不是 64&31=0）",
      j_power(64, 0) == 6 and j_power(64, 64) == MAX_POWER)
check("I3：饱和移位在 real 溢出点封顶（8192<<18 不再变负数）",
      j_rate_saturating(8192, 0, 6) == INT_MAX
      and j_rate_saturating(1000000, 3, 3) == INT_MAX
      and j_rate_saturating(1000000, 0, 6) == INT_MAX)
check("I1：intervalTicks 对 0 / 负数 / 极大 base 都 >= 1",
      j_interval_ticks(0, 6) == 1 and j_interval_ticks(-5, 3) == 1
      and j_interval_ticks(INT_MIN, 6) == 1 and j_interval_ticks(1, 6) == 1
      and j_interval_ticks(5, 999) == 1)
check("I1：batchSaturating 对极大 base 饱和到 Long.MAX_VALUE（不会回绕成负数）",
      j_batch_saturating(LONG_MAX, 6) == LONG_MAX and j_batch_saturating(-5, 6) == 7)

# ======================================================================================
# B. 逐台「改前 / 改后」数值对照（§4.4 表 1~表 6 抄进自检）
# ======================================================================================

print("")
print("=== B. 逐台改前 / 改后数值对照（§4.4 表 1~表 6）===")

collection = read("block/entity/CollectionCacheBlockEntity.java")
collection_code = code_only(collection)
charger = read("block/entity/RangeChargerBlockEntity.java")
charger_code = code_only(charger)
keeper = read("block/entity/QuantityKeeperBlockEntity.java")
keeper_code = code_only(keeper)
adv_keeper = read("block/entity/AdvancedQuantityKeeperBlockEntity.java")
adv_keeper_code = code_only(adv_keeper)
loader = read("block/entity/SchematicLoaderBlockEntity.java")
loader_code = code_only(loader)
config = read("Config.java")

# ---- 表 1：范围充电器 getChargeRate()（base 默认 500） ----
old_charger = lambda s, st, base=500: j_shl_masked(base, s + st * 3)
doc_table1 = [
    # (速度, 堆叠, 文档「改前」, 文档「落地版」)
    (0, 0, 500, 500),
    (1, 0, 1000, 1000),
    (2, 0, 2000, 2000),
    (3, 0, 4000, 4000),
    (6, 0, 32000, 32000),
    (0, 1, 4000, 4000),
    (3, 3, 2048000, 2048000),
    (0, 6, 131072000, 131072000),
]
t1_ok = []
for speed, stack, before_doc, after_doc in doc_table1:
    before = old_charger(speed, stack)
    after = j_rate_saturating(500, speed, stack)
    if before != before_doc or after != after_doc or before != after:
        t1_ok.append("s=%d st=%d 改前=%d(文档 %d) 改后=%d(文档 %d)"
                     % (speed, stack, before, before_doc, after, after_doc))
check("表 1：范围充电器合法配置逐格不变，且与文档「改前/落地版」两列逐格相符",
      not t1_ok, "; ".join(t1_ok))
check("表 1：配置上限下的溢出（8192+6 堆叠 / 1e6+3速3堆）改前为负数、改后封顶 Integer.MAX_VALUE",
      old_charger(0, 6, 8192) < 0 and old_charger(3, 3, 1000000) < 0
      and j_rate_saturating(8192, 0, 6) == INT_MAX
      and j_rate_saturating(1000000, 3, 3) == INT_MAX)
check("表 1：漏斗往一格塞 64 个速度升级 —— 改前 `500 << 64` 被掩码成 500（反而更慢），改后 32000",
      old_charger(64, 0) == 500 and j_rate_saturating(500, 64, 0) == 32000)
check("表 1 源码：getChargeRate() 走 rateSaturating 且读的是速度/堆叠两个计数",
      "RsccRateLimiter.rateSaturating(Config.rangeChargerChargeRate" in charger_code
      and "countUpgrades(SPEED_UPGRADE), countUpgrades(STACK_UPGRADE)" in charger_code)
check("表 1 源码：getUpgradeCount() 收敛到 power()（界面与速率同源，I4）",
      "RsccRateLimiter.power(countUpgrades(SPEED_UPGRADE), countUpgrades(STACK_UPGRADE))" in charger_code
      and "Config.rangeChargerChargeRate << getUpgradeCount()" not in charger_code)

# ---- 表 2：定量保持器 / 高级定量保持器 getDestroyRate()（base 默认 1） ----
old_keeper = lambda s, st, base=1: j_int32(base << min(min(s, 6) + min(st, 6) * 3, 24))
doc_table2 = [
    (0, 0, 1, 1),
    (1, 0, 2, 2),
    (3, 0, 8, 8),
    (6, 0, 64, 64),
    (0, 1, 8, 8),
    (0, 3, 512, 512),
    (0, 6, 262144, 262144),
]
t2_ok = []
for speed, stack, before_doc, after_doc in doc_table2:
    before = old_keeper(speed, stack)
    after = j_rate_saturating(1, speed, stack)
    if before != before_doc or after != after_doc or before != after:
        t2_ok.append("s=%d st=%d 改前=%d(文档 %d) 改后=%d(文档 %d)"
                     % (speed, stack, before, before_doc, after, after_doc))
check("表 2：两个保持器合法配置逐格不变，且与文档「改前/落地版」两列逐格相符",
      not t2_ok, "; ".join(t2_ok))
check("表 2：quantityKeeperDestroyRate=1000000 + 3速3堆 —— 改前为负数、改后封顶",
      old_keeper(3, 3, 1000000) < 0 and j_rate_saturating(1000000, 3, 3) == INT_MAX)
check("表 2 源码：基础版与高级版逐字符同源，都走 rateSaturating(Config.quantityKeeperDestroyRate)",
      "RsccRateLimiter.rateSaturating(Config.quantityKeeperDestroyRate" in keeper_code
      and "RsccRateLimiter.rateSaturating(Config.quantityKeeperDestroyRate" in adv_keeper_code
      and "Config.quantityKeeperDestroyRate << power" not in keeper_code
      and "Config.quantityKeeperDestroyRate << power" not in adv_keeper_code)

# ---- 表 3：归流缓存仓 getScanInterval()（base 默认 20，tick 越小越快） ----
scan_body = method_body(collection_code, "public int getScanInterval(")
old_scan = lambda n, radius_factor: max(1, 20 // ((1 + n) * radius_factor))
new_scan = lambda n, radius_factor: j_interval_ticks(20 // radius_factor, n)
doc_table3_r1_before = [20, 10, 6, 5, 4, 3, 2]
doc_table3_r1_after = [20, 10, 5, 2, 1, 1, 1]
doc_table3_r5_before = [6, 3, 2, 1, 1, 1, 1]
doc_table3_r5_after = [6, 3, 1, 1, 1, 1, 1]
check("表 3 复核：文档半径 1 的「改前」列确实是 20,10,6,5,4,3,2",
      [old_scan(n, 1) for n in range(7)] == doc_table3_r1_before,
      str([old_scan(n, 1) for n in range(7)]))
check("表 3 复核：文档半径 5（档位 3）的「改前」列确实是 6,3,2,1,1,1,1",
      [old_scan(n, 3) for n in range(7)] == doc_table3_r5_before,
      str([old_scan(n, 3) for n in range(7)]))
check("表 3：半径 1 改后 = 20,10,5,2,1,1,1（文档「落地版」逐格相符）",
      [new_scan(n, 1) for n in range(7)] == doc_table3_r1_after,
      str([new_scan(n, 1) for n in range(7)]))
check("表 3：半径 5 改后 = 6,3,1,1,1,1,1（文档「落地版」逐格相符）",
      [new_scan(n, 3) for n in range(7)] == doc_table3_r5_after,
      str([new_scan(n, 3) for n in range(7)]))
check("表 3：满级必须到得了 1 tick（旧倒数曲线永远停在 2）",
      new_scan(6, 1) == 1 and old_scan(6, 1) == 2)
check("表 3 源码：getScanInterval() 走 intervalTicks，且旧倒数式已消失；分母显式夹到 >= 1",
      scan_body is not None
      and "RsccRateLimiter.intervalTicks(" in scan_body
      and "Math.max(1, base / radiusFactor)" in scan_body
      and "Math.max(1, 1 + (getCollectRadius() - 1) / 2)" in scan_body
      and "base / (speedFactor * radiusFactor)" not in scan_body)

# ---- 表 4：归流缓存仓 getProcessRate() / getTransferBatch() / getDeleteRatePerTick() ----
check("表 4：处理组数 1,2,3,4,5,6,7 逐格不变（走 level() 而非裸计数）",
      [1 + j_level(n) for n in range(7)] == [1, 2, 3, 4, 5, 6, 7]
      and "return 1 + RsccRateLimiter.level(countUpgrades(SPEED_UPGRADE));" in collection_code)
check("表 4：单次吞吐 64,128,…,448 逐格不变（走 batchSaturating）",
      [j_batch_saturating(64, n) for n in range(7)] == [64, 128, 192, 256, 320, 384, 448]
      and "RsccRateLimiter.batchSaturating(64L, countUpgrades(STACK_UPGRADE))" in collection_code)
check("表 4：漏斗塞 64 个升级时不再把组数/吞吐抬到 65 / 4160，而是夹到 7 / 448",
      (1 + j_level(64)) == 7 and j_batch_saturating(64, 64) == 448)
check("表 4：满级删除速率 7 × 448 = 3,136 不变",
      (1 + j_level(6)) * j_batch_saturating(64, 6) == 3136)

# ---- 表 5：装填器（SchematicLoaderBlockEntity） ----
def loader_limit_before(n):
    s = min(n, 6)
    return UNLIMITED if s >= 6 else (8 << s)


def loader_limit_after(n):
    return UNLIMITED if j_is_maxed(n) else j_shift_saturating(8, j_level(n))


def loader_rate_before(n):
    s = min(n, 6)
    return float("inf") if s >= 6 else 0.5 * (1 << s)


def loader_rate_after(n):
    return float("inf") if j_is_maxed(n) else 0.5 * (1 << j_level(n))


check("表 5：每 tick 拉取上限 8,16,32,64,128,256,UNLIMITED 逐格不变",
      [loader_limit_before(n) for n in range(7)] == [8, 16, 32, 64, 128, 256, UNLIMITED]
      and [loader_limit_after(n) for n in range(7)] == [8, 16, 32, 64, 128, 256, UNLIMITED])
check("表 5：每 tick 拉取速率 0.5,1,2,4,8,16,∞ 逐格不变",
      [loader_rate_before(n) for n in range(7)] == [0.5, 1, 2, 4, 8, 16, float("inf")]
      and [loader_rate_after(n) for n in range(7)] == [0.5, 1, 2, 4, 8, 16, float("inf")])
check("表 5：漏斗塞 64 个速度升级时前后都是「不限」（满级判定认 >= 6）",
      loader_limit_before(64) == loader_limit_after(64) == UNLIMITED
      and loader_rate_before(64) == loader_rate_after(64) == float("inf"))
check("表 5 源码：满级哨兵收敛为 isMaxed() + UNLIMITED，指数段走饱和移位",
      "RsccRateLimiter.isMaxed(speed)" in loader_code
      and "RsccRateLimiter.UNLIMITED" in loader_code
      and "RsccRateLimiter.shiftSaturating(8, RsccRateLimiter.level(speed))" in loader_code
      and "speed >= 6" not in loader_code)
check("表 5 源码：拉取速率 / 预算两处的裸 `speed >= 6` 都已换成 isMaxed（数值不变）",
      loader_code.count("RsccRateLimiter.isMaxed(speed)") == 3
      and "Double.MAX_VALUE" in loader_code)

# ---- 表 6：固定节拍（饰品槽扫描间隔，不吃升级） ----
next_pass = method_body(charger_code, "private boolean nextCuriosPass(")
check("表 6：固定节拍数值不变（intervalTicks(5, 0) 与 5 恒等）",
      j_interval_ticks(5, 0) == 5)
check("表 6：这一行按 §4.6 刻意不动（selfcheck_round52 锚定了它的字面量）",
      next_pass is not None
      and "curiosScanCooldown = Math.max(0, Config.rangeChargerCuriosScanInterval - 1);" in next_pass)

# ======================================================================================
# C. 回归锚点（防后人误改）
# ======================================================================================

print("")
print("=== C. 回归锚点 ===")

delete_rate_body = method_body(collection_code, "public long getDeleteRatePerTick(")
check("回归：getDeleteRatePerTick() 方法体字面量仍在（派生量，无溢出风险，本次不动）",
      delete_rate_body is not None
      and "Math.max(1, getProcessRate()) * Math.max(1, getTransferBatch())" in delete_rate_body)
check("回归：删除路径仍用 getProcessRate()/getTransferBatch() 作为组数与吞吐（自检锚点不变）",
      "final int itemBudget = Math.max(1, getProcessRate());" in collection_code
      and "final int transfer = Math.max(1, getTransferBatch());" in collection_code
      # 第 95 轮第 12 条：回写路径的局部变量 budget 改名为 groups（物品 / 流体各一份预算），
      # 但「每 tick 处理组数 = getProcessRate()」的取法没变 —— 这里钉住新形状。
      and "final int groups = Math.max(1, getProcessRate());" in collection_code)
check("回归：自检锚定的饰品槽节拍赋值行仍在（见 tools/selfcheck_round52_charger_curios.py:184）",
      "curiosScanCooldown = Math.max(0, Config.rangeChargerCuriosScanInterval - 1);" in charger_code)

# ======================================================================================
# D. R1 反例：一格 64 个速度升级
# ======================================================================================

print("")
print("=== D. R1：漏斗「一格 64 个速度升级」===")


def old_cap_model(slots):
    """旧实现：按槽位数计数（counts[kind]++），一格算一个 ⇒ 一格 64 个也只算 1 个。"""
    counts = {}
    for kind, _count in slots:
        if kind is None:
            continue
        counts[kind] = counts.get(kind, 0) + 1
    return counts


def new_cap_model(slots, max_per_kind=6, creative_max=1):
    """新实现：按物品个数计数，逐格保留前 allowed 个、其余弹出；返回 (保留计数, 弹出个数)。"""
    kept, dropped = {}, 0
    for kind in range(4):
        limit = creative_max if kind == 3 else max_per_kind
        already = 0
        for k, count in slots:
            if k != kind:
                continue
            allowed = max(0, limit - already)
            dropped += max(0, count - allowed)
            already += min(count, allowed)
        kept[kind] = already
    return kept, dropped


funnel = [(0, 64)]  # 0 = speed，一格 64 个
old_counts = old_cap_model(funnel)
check("R1 反例·旧模型：`counts[kind]++` 数的是槽位 —— 一格 64 个只算 1 个 ⇒ 上限被绕过",
      old_counts.get(0) == 1)
check("R1 反例·旧模型后果：countUpgrades 拿到 64 ⇒ `500 << 64` 被掩码成 500（比插 6 个还慢）",
      old_charger(64, 0) == 500 and old_charger(6, 0) == 32000)

kept_new, dropped_new = new_cap_model(funnel)
check("R1 反例·新模型：计数按「个数」算并被夹到上限 6（不再是 64）",
      kept_new[0] == 6 and dropped_new == 58)
check("R1 反例·新模型后果：速率回到「6 个速度升级」的正确值 32000",
      j_rate_saturating(500, kept_new[0], 0) == 32000)

validate_body = method_body(charger_code, "private void validateUpgradeSlots(")
check("R1 源码：validateUpgradeSlots 已按「物品个数」计数（`counts[kind]++` 已消失）",
      validate_body is not None
      and "counts[kind]++" not in validate_body
      and "final int inSlotCount = inSlot.getCount();" in validate_body
      and "Math.min(inSlotCount, allowed)" in validate_body)
check("R1 源码：超限/非法一律「弹出到世界」而不是销毁（dropOne 走 addFreshEntity）",
      validate_body is not None
      and "dropOne(i)" in validate_body
      and "ItemStack.EMPTY" not in validate_body
      and "public long destroy" not in validate_body)
drop_one_body = method_body(charger_code, "private void dropOne(")
check("R1 源码：dropOne 每次只抽 1 个并 addFreshEntity 到世界；且没有服务端世界时不删东西",
      drop_one_body is not None
      and "extractItem(slot, 1, false)" in drop_one_body
      and "level.addFreshEntity(entity)" in drop_one_body
      and "level == null || level.isClientSide()" in validate_body)
check("R1 源码：ItemStackHandler 的槽上限已夹到 1（每槽 1 个，照 UpgradeSlot 的既有口径）",
      "protected int getStackLimit(final int slot, final ItemStack stack)" in charger_code
      and "Math.min(1, stack.getMaxStackSize())" in charger_code
      and "public int getSlotLimit(final int slot)" in charger_code)
check("R1 源码：计数种类常量与创造范围上限仍与工程既有约定一致（6 / 1）",
      "private static final int UPGRADE_KINDS = 4;" in charger_code
      and "private static final int KIND_CREATIVE_RANGE = 3;" in charger_code
      and "? 1 : cretae.cookiewyq.rs_create_compat.menu.UpgradeSlot.MAX_PER_UPGRADE" in charger_code)

# ======================================================================================
# E. R3 / R4 / R5：三处既有缺陷
# ======================================================================================

print("")
print("=== E. R3 / R4 / R5 ===")

# R3：死配置 quantityKeeperDestroyRatePerSpeed 已删除（选②：接上会改动手感）
dead_hits = []
for dirpath, _dirnames, filenames in os.walk(JAVA):
    for name in filenames:
        if not name.endswith(".java"):
            continue
        path = os.path.join(dirpath, name)
        with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
            text = handle.read()
        if "quantityKeeperDestroyRatePerSpeed" in text or "QUANTITY_KEEPER_DESTROY_RATE_PER_SPEED" in text:
            dead_hits.append(os.path.relpath(path, ROOT))
check("R3：死配置 quantityKeeperDestroyRatePerSpeed 已从全工程删除（定义/字段/赋值三处一起走）",
      not dead_hits, str(dead_hits))
check("R3：两个保持器的销毁速率都只由 quantityKeeperDestroyRate + 升级曲线决定（没有第二个来源）",
      keeper_code.count("Config.quantityKeeperDestroyRate") == 1
      and adv_keeper_code.count("Config.quantityKeeperDestroyRate") == 1)

# R5：口径注释 —— 行为是「每秒」
check("R5：基础保持器注释明确写「每秒」，并指出消费端 ratePerSecond / 20 tick 换算",
      "本机销毁速率（<b>每秒</b>，不是每 tick）" in keeper
      and "ratePerSecond" in keeper)
check("R5：配置项注释已从「每 tick」改正为「每秒」（只改注释，不改数值/默认值/范围）",
      "定量保持器每秒基础销毁的过量数量" in config
      and 'defineInRange("quantityKeeperDestroyRate", 1, 1, 1000000)' in config
      and "定量保持器每 tick 基础销毁的过量数量" not in config)

# R4：死注册（sequence_assembly_executor 没有升级槽）
destinations = read("RegisterUpgradeDestinations.java")
check("R4：sequence_assembly_executor 不再登记任何升级（该方块确实没有升级槽）",
      'register("sequence_assembly_executor"' not in destinations
      and "sequence_assembly_executor" not in code_only(destinations))
check("R4：登记表仍保留其它真正有升级槽的机器（本表的不变式：表里每一台都必须真的有槽）",
      all(('register("%s"' % m) in destinations for m in
           ["schematic_loader", "advanced_schematic_loader", "quantity_keeper",
            "range_charger", "collection_cache"]))

# ======================================================================================
# 结果
# ======================================================================================

print("")
if FAILURES:
    print("问题总数: %d" % len(FAILURES))
    for item in FAILURES:
        print(" - " + item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
