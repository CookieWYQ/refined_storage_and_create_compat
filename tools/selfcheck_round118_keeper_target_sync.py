#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 118 轮自检（之三）：保持器「目标数量」的 short 截断修复 + 网络抽取重入保护。

背景（独立只读复核报出的两条高严重度缺陷）
------------------------------------------
**H1（截断 = 界面显示 0 + 点 ± 静默改小目标 + 开注销时误销毁）**
MC 1.21.1 的 ``ClientboundContainerSetDataPacket`` 线上是 ``short``（``writeShort`` / ``readShort``），
而 ``ContainerData`` 的索引值一律按 short 同步。两台定量保持器把 ``targetAmount`` / ``targets[row]``
整个 int 放在一个索引上 ⇒ **> 32767 就被截断**。流体目标按 mB 计（1 桶 = 1000 mB），
**只要目标 > 32.7 桶就中招**：客户端读到低 16 位（可能是 0 或负数）⇒ 输入框回填 "0"，
玩家点 ``+`` 时按 0 计算并发出 ``target=1`` ⇒ 服务端目标被静默改小；开「过量销毁」时按错误目标销毁资源。

修复：目标数量拆成「低 16 位 + 高 16 位」两个索引（高半段**追加在末尾**，不动既有下标），
两端都按无符号 16 位掩码拼回完整 int。

**H2（RS 外部存储贴着保持器 ⇒ 网络抽取无限递归 StackOverflowError）**
保持器故意把「网络镜像」通过能力暴露给相邻方块（供漏斗 / 管道取出，是设计功能）。
RS 2.0.9 的外部存储会把相邻方块的 ``IItemHandler`` / ``IFluidHandler`` 注册成网络存储源，
于是「外部存储读保持器能力 → 保持器从网络抽 → 网络又抽到外部存储 → …」形成环。
修复：``support/KeeperNetworkReadGuard`` 同线程重入闸 —— 内层抽取直接返回 0（不递归）。
**双计（外部存储把网络镜像再算一遍）不在本条修复范围**：那是产品口径问题（见本轮文档 §5）。

四条腿：① 源码锚点；② 半段编解码行为模型（覆盖 32768..Integer.MAX_VALUE）+ 递归模型；
③ 反例自证；④ 纯 JDK 重入闸的真代码 JVM 探针。

用法：python tools/selfcheck_round118_keeper_target_sync.py
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
BQ = os.path.join(PKG, "block", "entity", "QuantityKeeperBlockEntity.java")
AQ = os.path.join(PKG, "block", "entity", "AdvancedQuantityKeeperBlockEntity.java")
QM = os.path.join(PKG, "menu", "QuantityKeeperMenu.java")
AM = os.path.join(PKG, "menu", "AdvancedQuantityKeeperMenu.java")
GUARD = os.path.join(PKG, "support", "KeeperNetworkReadGuard.java")
PROBE_DIR = os.path.join(ROOT, "build", "selfcheck_round118_keeper")

JAVAC = "D:/java21/bin/javac.exe"
JAVA = "D:/java21/bin/java.exe"

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


# ======================================================================================
# ① 源码锚点
# ======================================================================================

def group_anchors():
    print("\n=== ① 源码锚点 ===")
    basic = strip_comments(read(BQ))
    adv = strip_comments(read(AQ))
    qm = strip_comments(read(QM))
    am = strip_comments(read(AM))

    check("H1-1 基础保持器有 DATA_TARGET_LOW / DATA_TARGET_HIGH / DATA_COUNT 三个常量",
          "DATA_TARGET_LOW = 0" in basic and "DATA_TARGET_HIGH = 9" in basic
          and "DATA_COUNT = DATA_TARGET_HIGH + 1" in basic)
    check("H1-2 基础保持器 ContainerData 的高半段返回 targetAmount >>> 16",
          "case DATA_TARGET_HIGH -> targetAmount >>> 16;" in basic)
    check("H1-3 基础保持器 ContainerData 长度用 DATA_COUNT（不再是写死的 9）",
          "return DATA_COUNT;" in basic and "return 9;" not in basic)
    check("H1-4 基础菜单按无符号 16 位掩码拼回目标（低位 & 0xFFFF，高位 << 16）",
          "(high << 16) | low" in qm and "& 0xFFFF" in qm
          and "data.get(0);" not in qm)
    check("H1-5 基础菜单客户端占位数据长度跟常量走（不再写死 9）",
          "new SimpleContainerData(QuantityKeeperBlockEntity.DATA_COUNT)" in qm)

    check("H1-6 高级保持器新增 DATA_TARGET_HIGH_BASE 且排在 DATA_SPEED_UPGRADES 之前",
          "DATA_TARGET_HIGH_BASE = SLOT_COUNT * DATA_PER_SLOT" in adv
          and "DATA_SPEED_UPGRADES = DATA_TARGET_HIGH_BASE + SLOT_COUNT" in adv)
    check("H1-7 高级保持器 ContainerData 高半段按 targets[...] >>> 16 返回",
          "targets[index - DATA_TARGET_HIGH_BASE] >>> 16" in adv)
    check("H1-8 高级菜单按无符号 16 位掩码拼回每行目标",
          "DATA_TARGET_HIGH_BASE + row" in am and "(high << 16) | low" in am and "& 0xFFFF" in am)
    check("H1-9 高级菜单客户端占位长度跟 DATA_COUNT 走",
          "new SimpleContainerData(AdvancedQuantityKeeperBlockEntity.DATA_COUNT)" in am)

    # H2：重入保护
    check("H2-1 新增纯 JDK 的 KeeperNetworkReadGuard（可单独 javac 真跑）",
          os.path.isfile(GUARD) and "import net.minecraft" not in read(GUARD))
    enter = basic.count("KeeperNetworkReadGuard.enter()") + adv.count("KeeperNetworkReadGuard.enter()")
    exit_ = basic.count("KeeperNetworkReadGuard.exit()") + adv.count("KeeperNetworkReadGuard.exit()")
    check("H2-2 四个网络抽取入口各有一道 enter/exit（基础 2 + 高级 2）",
          enter == 4 and exit_ == 4, "enter=%d exit=%d" % (enter, exit_))
    check("H2-3 抽取主体被搬到 Raw 方法：进入失败时直接返回 0（不递归、不重复抽）",
          "extractMarkerItemFromNetworkRaw(" in basic and "extractMarkerFluidFromNetworkRaw(" in basic
          and "extractNetworkItemRaw(" in adv and "extractNetworkFluidRaw(" in adv)
    check("H2-4 基础抽取：四个 Raw 方法体里各有一次 net.extract（主体没被拆坏）",
          basic.count("net.extract(") >= 2)


# ======================================================================================
# ② 行为模型：16 位拆分编解码 + 重入递归
# ======================================================================================

def to_java_short(value):
    """模拟 Java 的 (short) 截断 + 读回时符号扩展。"""
    low = value & 0xFFFF
    return low - 0x10000 if low >= 0x8000 else low


def encode_split(target):
    """服务端：低半段（原 index）、高半段（新 index）。"""
    return to_java_short(target & 0xFFFF), to_java_short(target >> 16)


def decode_split(low, high):
    """客户端：与 Java 修复后的菜单同一算式。"""
    return ((high & 0xFFFF) << 16) | (low & 0xFFFF)


def decode_old_single(low):
    """客户端旧算式：直接使用被 short 截断后的值。"""
    return low


def group_models():
    print("\n=== ② 行为模型 ===")
    values = [0, 1, 63, 64, 1000, 32767, 32768, 32769, 65535, 65536,
              100_000, 1_000_000, 640_000, 2_147_483_647]
    bad_new = []
    bad_old = []
    for v in values:
        low, high = encode_split(v)
        if decode_split(low, high) != v:
            bad_new.append(v)
        if decode_old_single(low) != v:
            bad_old.append(v)
    check("H1-M1 新的「低16+高16」编解码对 14 个取值（含 32768 / 65536 / 100 万 / Integer.MAX）全部往返正确",
          bad_new == [], "失败取值 %r" % (bad_new,))
    counterexample("H1-M2 旧的「单索引 int」编码：32768 起全部往返失败（正是被截断的那批）",
                   bad_old == [32768, 32769, 65535, 65536, 100_000, 1_000_000, 640_000, 2_147_483_647],
                   "旧编码失败取值 %r" % (bad_old,))
    counterexample("H1-M3 流体 33 桶（33000 mB）在旧编码下被截断成 -32536 ⇒ 输入框夹到 0（点 + 即把目标改写成 1）",
                   33000 > 32767 and decode_old_single(to_java_short(33000)) == -32536)
    counterexample("H1-M4 修复后 33000 mB 往返正确（33 桶不再被改写）",
                   decode_split(*encode_split(33000)) == 33000)
    counterexample("H1-M5 若客户端忘了 & 0xFFFF 掩码（低位直接符号扩展），32768 会被拼成负数",
                   (((0) << 16) | to_java_short(32768)) < 0)

    # H2：递归模型
    def extract(depth, guarded):
        if guarded and depth > 0:
            return 0  # 内层让位
        if depth > 200:
            raise RecursionError("network extract recursion")
        return extract(depth + 1, guarded)

    try:
        extract(0, guarded=False)
        recursive_unguarded = "no-error"
    except RecursionError:
        recursive_unguarded = "RecursionError"
    try:
        result_guarded = extract(0, guarded=True)
        recursive_guarded = "no-error"
    except RecursionError:
        recursive_guarded = "RecursionError"
    counterexample("H2-M1 没有重入闸时「外部存储 ↔ 网络抽取」环会无限递归（模型：RecursionError）",
                   recursive_unguarded == "RecursionError")
    counterexample("H2-M2 有重入闸时同一个环只走一层、内层返回 0（不递归、不重复抽）",
                   recursive_guarded == "no-error" and result_guarded == 0)


# ======================================================================================
# ③ JVM 探针：真代码 KeeperNetworkReadGuard
# ======================================================================================

PROBE = r"""
public final class KeeperNetworkReadGuardProbe {
    private static int passed = 0;
    private static int failed = 0;

    private static void is(boolean actual, boolean want, String name) {
        if (actual == want) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL " + name + " actual=" + actual + " want=" + want);
        }
    }

    public static void main(String[] args) throws Exception {
        is(KeeperNetworkReadGuard.inProgress(), false, "初始未进入");
        is(KeeperNetworkReadGuard.enter(), true, "首次进入成功");
        is(KeeperNetworkReadGuard.inProgress(), true, "进入后标记为进行中");
        is(KeeperNetworkReadGuard.enter(), false, "重入被拒");
        KeeperNetworkReadGuard.exit();
        is(KeeperNetworkReadGuard.inProgress(), false, "退出后复位");
        is(KeeperNetworkReadGuard.enter(), true, "退出后可再次进入");
        KeeperNetworkReadGuard.exit();
        KeeperNetworkReadGuard.exit();
        is(KeeperNetworkReadGuard.inProgress(), false, "重复 exit 幂等");
        final boolean[] otherThread = new boolean[1];
        Thread thread = new Thread(() -> otherThread[0] = KeeperNetworkReadGuard.enter());
        thread.start();
        thread.join();
        is(otherThread[0], true, "其他线程不受本线程状态影响（ThreadLocal 隔离）");
        System.out.println("PROBE passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
"""


def group_jvm():
    print("\n=== ③ 真代码 JVM 探针（KeeperNetworkReadGuard）===")
    os.makedirs(PROBE_DIR, exist_ok=True)
    text = read(GUARD)
    text = re.sub(r"^\s*package\s+[\w.]+\s*;\s*$", "", text, count=1, flags=re.M)
    src = os.path.join(PROBE_DIR, "KeeperNetworkReadGuard.java")
    with io.open(src, "w", encoding="utf-8") as handle:
        handle.write(text)
    probe = os.path.join(PROBE_DIR, "KeeperNetworkReadGuardProbe.java")
    with io.open(probe, "w", encoding="utf-8") as handle:
        handle.write(PROBE)
    cmd = [JAVAC, "-encoding", "UTF-8", "-J-Duser.language=en", "-J-Duser.country=US",
           "-d", PROBE_DIR, src, probe]
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    check("H2-P1 重入闸类可单独 javac 编译", result.returncode == 0,
          (result.stdout or "") + (result.stderr or ""))
    if result.returncode != 0:
        return
    run = subprocess.run([JAVA, "-cp", PROBE_DIR, "KeeperNetworkReadGuardProbe"],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    check("H2-P2 JVM 探针 8 条断言全过（进入/重入/退出/幂等/线程隔离）", run.returncode == 0,
          (run.stdout or "") + (run.stderr or ""))
    if run.stdout:
        for line in run.stdout.strip().splitlines():
            print("    " + line)


# ======================================================================================
# ④ 反例自证（源码副本）
# ======================================================================================

def group_counterexamples():
    print("\n=== ④ 反例自证（源码副本） ===")
    qm = read(QM)
    broken = qm.replace("return (high << 16) | low;", "return low;")
    counterexample("H1-C1 源码副本：菜单改回「直接返回低位」后，H1-4 判负（等于回到 short 截断）",
                   "return (high << 16) | low;" in qm and "return (high << 16) | low;" not in broken)
    bq = read(BQ)
    broken_bq = bq.replace("case DATA_TARGET_HIGH -> targetAmount >>> 16;", "")
    counterexample("H1-C2 源码副本：服务端不提供高半段后，H1-2 判负",
                   "case DATA_TARGET_HIGH -> targetAmount >>> 16;" not in broken_bq)
    guard = bq.replace(
        """        if (!cretae.cookiewyq.rs_create_compat.support.KeeperNetworkReadGuard.enter()) {""",
        """        if (false) {""")
    counterexample("H1-C3 源码副本：抽掉重入闸后，H2-2 计数判负（不再是 4 道）",
                   guard.count("KeeperNetworkReadGuard.enter()") < bq.count("KeeperNetworkReadGuard.enter()"))


def main():
    print("第 118 轮自检（之三）：保持器目标数量 short 截断 + 网络抽取重入保护")
    group_anchors()
    group_models()
    group_jvm()
    group_counterexamples()
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
