#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 119 轮自检：缓存池读数求和改为「饱和加法」（`support/SaturatingMath`）。

背景
----
`RsccSharedCache#poolFreeSpace` 是 `RsccIntermediateFlow` 的「闸门 A」：池子还能收下多少件，
`> 0` 才允许把中间产物从 RS 网络抽出来。它的实现是**各盘剩余量逐个相加**，而
`remainingOf()` 对「无限容量盘」返回 `UNLIMITED_REMAINING = Long.MAX_VALUE / 4`。
旧代码是裸 `+=`：池子里同时插 **5 块以上**无限盘时和就回绕成**负数**，
闸门 A 于是把「几乎无限的空位」读成「池子满了」——中间产物一件都不搬，
不报错、不打日志，只表现为「机器无故不动」。同一文件的 `poolStored` / `poolContents`
有完全相同的问题。

修法：新增纯 JDK 的 `support/SaturatingMath#add`（减法判溢出，非负输入永不回绕），
三处求和全部改走它；`UNLIMITED_REMAINING` 那句「足够大又不会在别处做加法时溢出」的错误注释一并改正。

本脚本四条腿（与第 116～118 轮同一套路）
----------------------------------------
① 源码锚点：三处求和都走饱和加法，文件里不再有 `+= remainingOf` / `+= storedOf` / `Long::sum`；
   纯 JDK 类、判据只有一份、错误注释已改正；
② 真代码 JVM 探针：把 `SaturatingMath` 用 javac 编译后**真跑**（含 `Long.MAX_VALUE` 反例）；
③ 行为模型：用 Python 复刻「5 块无限盘 / 混合盘」的池子读数，断言闸门 A 不会翻转；
④ 反例自证：把旧的裸 `+=` 拼进模型，断言它真的会溢出成负数（证明判据不是摆设）。

用法：python tools/selfcheck_round119_pool_saturate.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import os
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
SUPPORT = os.path.join(PKG, "support")
PROBE_DIR = os.path.join(ROOT, "build", "selfcheck_round119")

JAVAC = "D:/java21/bin/javac.exe"
JAVA = "D:/java21/bin/java.exe"
JAVAC_EN = ["-J-Duser.language=en", "-J-Duser.country=US"]

MATH_FILE = os.path.join(SUPPORT, "SaturatingMath.java")
CACHE_FILE = os.path.join(SUPPORT, "RsccSharedCache.java")

_passed = 0
_failed = 0
_failures = []


def check(name, ok, detail=""):
    global _passed, _failed
    if ok:
        _passed += 1
        print("  OK   %s" % name)
    else:
        _failed += 1
        _failures.append((name, detail))
        print("  FAIL %s" % name)
        if detail:
            for line in str(detail).strip().splitlines()[:12]:
                print("       " + line)


def is_(actual, expected, label):
    check(label, actual == expected, "actual=%r expected=%r" % (actual, expected))


def read(path):
    with io.open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def method_body(text, signature):
    """取出包含 signature 的那个方法体（按花括号配平），找不到返回空串。"""
    index = text.find(signature)
    if index < 0:
        return ""
    start = text.find("{", index)
    if start < 0:
        return ""
    depth = 0
    for position in range(start, len(text)):
        char = text[position]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[start:position + 1]
    return ""


# ======================================================================================
# ① 源码锚点
# ======================================================================================

def group_source_anchors():
    print("\n=== ① 源码锚点 ===")
    check("①-1 support/SaturatingMath.java 存在", os.path.isfile(MATH_FILE))
    if not os.path.isfile(MATH_FILE):
        return
    math_src = read(MATH_FILE)
    cache_src = read(CACHE_FILE)

    check("①-2 饱和加法是纯 JDK（可单独 javac 真跑，不依赖 Minecraft / NeoForge）",
          "import net.minecraft" not in math_src and "import net.neoforged" not in math_src
          and "import com.refinedmods" not in math_src)
    check("①-3 public final class + 私有构造（工具类不可实例化）",
          "public final class SaturatingMath" in math_src and "private SaturatingMath()" in math_src)
    check("①-4 判据用减法形式判溢出（加法形式会先自己回绕）",
          "Long.MAX_VALUE - right" in math_src and "left + right" in math_src)
    check("①-5 负输入按 0 处理（读数只可能是非负量，负只来自上游错误）",
          "Math.max(0L, a)" in math_src and "Math.max(0L, b)" in math_src)

    free_body = method_body(cache_src, "public static long poolFreeSpace(")
    stored_body = method_body(cache_src, "public static long poolStored(")
    contents_body = method_body(cache_src, "public static Map<ResourceKey, Long> poolContents(")
    check("①-6 三个读数方法都能定位到方法体", bool(free_body) and bool(stored_body) and bool(contents_body))
    check("①-7 poolFreeSpace 走 SaturatingMath.add",
          "SaturatingMath.add(free, remainingOf(" in free_body)
    check("①-8 poolStored 走 SaturatingMath.add",
          "SaturatingMath.add(stored, storedOf(" in stored_body)
    check("①-9 poolContents 的合并函数是 SaturatingMath::add",
          "SaturatingMath::add" in contents_body)
    check("①-10 poolContents 不再用 Long::sum（裸求和同样会回绕）",
          "Long::sum" not in contents_body)

    # 整个文件里不许再出现对这两类读数的裸累加（防止以后又加一处旧的写法）
    check("①-11 文件里不存在 `+= remainingOf`", not re.search(r"\+=\s*remainingOf\(", cache_src))
    check("①-12 文件里不存在 `+= storedOf`", not re.search(r"\+=\s*storedOf\(", cache_src))

    # 错误注释必须改掉：旧注释声称这个常量「不会在别处做加法时溢出」，而事实是 5 块盘就溢出
    stale_claim = "足够大又不会在别处做加法时溢出"
    check("①-13 `UNLIMITED_REMAINING` 的错误注释已改正（不再声称加法不会溢出）",
          stale_claim not in cache_src)
    check("①-14 新注释明确写出「5 块以上无限盘会回绕」这个触发条件与修法",
          "5 块以上" in cache_src and "SaturatingMath" in cache_src)
    check("①-15 UNLIMITED_REMAINING 的取值没变（改的是求和，不是读数口径）",
          "UNLIMITED_REMAINING = Long.MAX_VALUE / 4" in cache_src)


# ======================================================================================
# ② 真代码 JVM 探针
# ======================================================================================

JAVA_PROBE = r"""
public final class SaturatingMathProbe {
    private static int passed = 0;
    private static int failed = 0;

    private static void is(boolean actual, boolean expected, String label) {
        if (actual == expected) {
            passed++;
        } else {
            failed++;
            System.out.println("PROBE-FAIL " + label + " actual=" + actual + " expected=" + expected);
        }
    }

    private static void is(long actual, long expected, String label) {
        if (actual == expected) {
            passed++;
        } else {
            failed++;
            System.out.println("PROBE-FAIL " + label + " actual=" + actual + " expected=" + expected);
        }
    }

    public static void main(String[] args) {
        final long max = Long.MAX_VALUE;
        final long unlimited = Long.MAX_VALUE / 4;
        // 基本语义
        is(SaturatingMath.add(0L, 0L), 0L, "空 + 空 = 0");
        is(SaturatingMath.add(5L, 7L), 12L, "普通加法");
        is(SaturatingMath.add(0L, 5L), 5L, "空 + 5 = 5");
        // 边界不减料
        is(SaturatingMath.add(max - 1L, 1L), max, "MAX-1 + 1 = MAX");
        is(SaturatingMath.add(max, 0L), max, "MAX + 0 = MAX");
        // 溢出反例（本条的真正目的）：绝不许变成负数
        is(SaturatingMath.add(max, 1L), max, "MAX + 1 饱和到 MAX（不许回绕）");
        is(SaturatingMath.add(max, max), max, "MAX + MAX 饱和到 MAX");
        is(SaturatingMath.add(unlimited, unlimited) < 0L, false, "两块无限盘不许为负");
        long five = 0L;
        for (int i = 0; i < 5; i++) {
            five = SaturatingMath.add(five, unlimited);
        }
        is(five < 0L, false, "5 块无限盘不许为负（旧写法在这里回绕）");
        is(five, max, "5 块无限盘饱和到 MAX");
        long many = 0L;
        for (int i = 0; i < 64; i++) {
            many = SaturatingMath.add(many, unlimited);
        }
        is(many < 0L, false, "64 块无限盘不许为负");
        is(many, max, "64 块无限盘饱和到 MAX");
        // 负输入按 0（读数出现负数只可能是上游出错，当 0 比传播更安全）
        is(SaturatingMath.add(-5L, 3L), 3L, "负 + 3 = 3");
        is(SaturatingMath.add(3L, -5L), 3L, "3 + 负 = 3");
        is(SaturatingMath.add(Long.MIN_VALUE, Long.MIN_VALUE), 0L, "双 MIN 按 0");
        // 单调性：结果 >= 两个非负输入，且不会为负
        long seed = 123456789L;
        boolean monotone = true;
        boolean nonNegative = true;
        for (int i = 0; i < 20000; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            final long a = Math.abs(seed) % (max / 2);
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            final long b = Math.abs(seed) % (max / 2);
            final long sum = SaturatingMath.add(a, b);
            if (sum < a || sum < b) {
                monotone = false;
            }
            if (sum < 0L) {
                nonNegative = false;
            }
        }
        is(monotone, true, "随机 2 万组：结果不小于任一加数（单调）");
        is(nonNegative, true, "随机 2 万组：结果永不为负");
        System.out.println("PROBE passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
"""


def group_jvm_probe():
    print("\n=== ② 真代码 JVM 探针（javac 编译 SaturatingMath 后真跑）===")
    os.makedirs(PROBE_DIR, exist_ok=True)
    math_src = os.path.join(PROBE_DIR, "SaturatingMath.java")
    text = read(MATH_FILE)
    # 去掉 package 声明再编译到默认包：否则 class 落在 cretae/... 子目录，默认包的探针类引用不到它。
    text = re.sub(r"^\s*package\s+[\w.]+\s*;\s*$", "", text, count=1, flags=re.M)
    with io.open(math_src, "w", encoding="utf-8") as handle:
        handle.write(text)
    probe_src = os.path.join(PROBE_DIR, "SaturatingMathProbe.java")
    with io.open(probe_src, "w", encoding="utf-8") as handle:
        handle.write(JAVA_PROBE)
    compile_cmd = [JAVAC, "-encoding", "UTF-8"] + JAVAC_EN + ["-d", PROBE_DIR, math_src, probe_src]
    result = subprocess.run(compile_cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    check("②-1 纯 JDK 类可单独 javac 编译（真代码，不是模型）", result.returncode == 0,
          (result.stdout or "") + (result.stderr or ""))
    if result.returncode != 0:
        return
    run = subprocess.run([JAVA, "-cp", PROBE_DIR, "SaturatingMathProbe"],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    check("②-2 JVM 探针全过（含 MAX 反例、5/64 块无限盘、随机 2 万组单调性）",
          run.returncode == 0, (run.stdout or "") + (run.stderr or ""))
    if run.stdout:
        for line in run.stdout.strip().splitlines():
            print("    " + line)


# ======================================================================================
# ③ 行为模型 + ④ 反例自证
# ======================================================================================

MAX = (1 << 63) - 1
UNLIMITED = MAX // 4


def naive_sum(values):
    """旧实现：裸 `+=`（Python 的 int 不会溢出，所以显式模拟 64 位有符号回绕）。"""
    total = 0
    for value in values:
        total = (total + value) & 0xFFFFFFFFFFFFFFFF
        if total >= 1 << 63:
            total -= 1 << 64
    return total


def saturated_sum(values):
    shared = {"total": 0}

    def add(a, b):
        left = a if a > 0 else 0
        right = b if b > 0 else 0
        if left > MAX - right:
            return MAX
        return left + right

    for value in values:
        shared["total"] = add(shared["total"], value)
    return shared["total"]


def group_model():
    print("\n=== ③ 行为模型：闸门 A 不会被无限盘翻转 ===")
    for disks in (1, 2, 4, 5, 8, 16):
        values = [UNLIMITED] * disks
        used = saturated_sum(values)
        check("③ 块数 %2d：饱和和 >= 0（闸门 A 可用）" % disks, used >= 0, "used=%d" % used)
    mixed = [UNLIMITED, UNLIMITED, 1024, UNLIMITED, 7, UNLIMITED, UNLIMITED]
    check("③ 混合盘：饱和和 >= 0", saturated_sum(mixed) >= 0, "used=%d" % saturated_sum(mixed))
    check("③ 混合盘：饱和和 >= 任一单盘读数",
          saturated_sum(mixed) >= max(mixed), "used=%d" % saturated_sum(mixed))
    # 闸门 A 的判定本身：`poolFree > 0` 才抽
    check("③ 5 块无限盘：闸门 A 判定「有空位」（旧实现这里是 false）",
          saturated_sum([UNLIMITED] * 5) > 0)
    check("③ 真满了（全是 0）：闸门 A 判定「满」", not (saturated_sum([0, 0, 0]) > 0))

    print("\n=== ④ 反例自证：旧写法真的会溢出（判据不是摆设）===")
    naive_five = naive_sum([UNLIMITED] * 5)
    check("④-1 旧裸 `+=` 在 5 块无限盘时溢出为负（证明这条修复有真实触发面）",
          naive_five < 0, "naive=%d" % naive_five)
    check("④-2 旧写法下闸门 A 会误判「池子满了」", not (naive_five > 0))
    naive_four = naive_sum([UNLIMITED] * 4)
    check("④-3 4 块盘时旧写法还不溢出（解释为什么这个 bug 只在多盘时出现）",
          naive_four > 0, "naive=%d" % naive_four)


def main():
    print("第 119 轮自检：缓存池读数饱和加法（support/SaturatingMath）")
    print("仓库：%s" % ROOT)
    group_source_anchors()
    group_jvm_probe()
    group_model()
    print("\n断言 %d 条，失败 %d 条" % (_passed + _failed, _failed))
    if _failed:
        print("\n失败明细：")
        for name, detail in _failures:
            print("  - %s" % name)
            if detail:
                print("      " + str(detail).splitlines()[0])
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
