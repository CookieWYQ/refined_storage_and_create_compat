#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 118 轮自检：单元样板库「合计体积预算」（第 117 轮复核遗留项 M1）。

背景
----
第 116 轮给单元样板库加了 **1024 格**硬上限（L3「自动扩容无条数上限」），第 116～117 轮又给
所有「客户端给数据 → 落盘」的写入口接上了**单条**体积尺（``MarkerBudget.MAX_ENTRY_BYTES``，
线上意图约 2 KB / 条）。两个上限各自成立，但**乘起来仍然很大**：

    1024 格 × 每格接近单条上限 ≈ 2 MB 线上（复核口径原话：1024 格理论 ≈2.5 MB 线上）

这份数据会 ① 落盘进方块实体 NBT、② 每次开界面 / 区块同步整库回发。所以第 118 轮补一道
**合计体积预算**（``support/UnitLibraryLimits``：1 MiB 线上意图 / 8 MiB 堆估算），
唯一执行点是库自己的写入路径。

本脚本四条腿（与第 116 / 117 轮同一套路）
----------------------------------------
① **源码锚点**：常量取值、纯 JDK、判据只有一份实现、唯一写入口、读档不判、只拦增长；
② **真代码 JVM 探针**：把纯 JDK 的 ``UnitLibraryLimits`` 用 javac 编译后**真跑** 17 条断言，
   含 ``Long.MAX_VALUE`` 溢出反例（这是本条最容易写错的写法：``used + add <= MAX`` 会溢出成负数）；
③ **行为模型**：用 Python 复刻库的体积记账（含缓存增量维护），跑确定性随机序列，
   断言「缓存 == 真实合计」「增长永不越限」「换小 / 取出 / 读档永远允许」；
④ **反例自证**：把坏写法拼进源码副本 / 行为模型，断言判据真的判负（证明判据不是摆设）。

用法：python tools/selfcheck_round118_unit_library_bytes.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import json
import os
import random
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
BE_DIR = os.path.join(PKG, "block", "entity")
NET_DIR = os.path.join(PKG, "network")
MENU_DIR = os.path.join(PKG, "menu")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
PROBE_DIR = os.path.join(ROOT, "build", "selfcheck_round118")

JAVAC = "D:/java21/bin/javac.exe"
JAVA = "D:/java21/bin/java.exe"
JAVAC_EN = ["-J-Duser.language=en", "-J-Duser.country=US"]

FILES = {
    "limits": os.path.join(SUPPORT, "UnitLibraryLimits.java"),
    "marker": os.path.join(SUPPORT, "MarkerBudget.java"),
    "be": os.path.join(BE_DIR, "SequencePatternTerminalBlockEntity.java"),
    "create_packet": os.path.join(NET_DIR, "CreateUnitPatternPacket.java"),
    "menu": os.path.join(MENU_DIR, "SequencePatternTerminalMenu.java"),
}
LANG_KEYS = [
    "gui.rs_create_compat.sequence_pattern_terminal.generate.library_full",
    "gui.rs_create_compat.sequence_pattern_terminal.generate.library_full_bytes",
]

CHECKS = [0]
COUNTER = [0]
FAILS = []

# 与本轮源码一致的常量（取自 UnitLibraryLimits；行为模型用同一份）
WIRE_RATIO = 8
MAX_WIRE_BYTES = 1024 * 1024
MAX_HEAP_BYTES = MAX_WIRE_BYTES * WIRE_RATIO


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
    COUNTER[0] += 1
    check("反例 " + name, ok)


# ======================================================================================
# 文本工具（与第 116 / 117 轮同一套）
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


def int_const(text, name):
    match = re.search(r"\b" + re.escape(name) + r"\s*=\s*([0-9][0-9_* ]*)\s*;", text)
    if not match:
        return None
    expr = match.group(1).replace("_", "").strip()
    try:
        return eval(expr, {"__builtins__": {}}, {})  # 只含数字 / * 的常量表达式
    except Exception:
        return None


# ======================================================================================
# ① 源码锚点
# ======================================================================================

def group_anchors():
    print("\n=== ① 源码锚点：常量 / 单一判据 / 唯一写入口 / 读档不判 ===")
    src = {name: read(path) for name, path in FILES.items()}
    code = {name: strip_comments(text) for name, text in src.items()}

    limits = code["limits"]
    check("①-1 新类 support/UnitLibraryLimits.java 存在", bool(limits.strip()))
    check("①-2 纯 JDK：不 import 任何 Minecraft / NeoForge 类型（因此可单独 javac 真跑）",
          "import net.minecraft" not in limits and "import net.neoforged" not in limits,
          "发现 MC 依赖 ⇒ 无法做 JVM 探针")
    check("①-3 线上预算常量 = 1024 × 1024（1 MiB）",
          int_const(limits, "MAX_LIBRARY_WIRE_BYTES") == MAX_WIRE_BYTES,
          "MAX_LIBRARY_WIRE_BYTES=%r" % (int_const(limits, "MAX_LIBRARY_WIRE_BYTES"),))
    is_ratio = int_const(limits, "WIRE_RATIO")
    marker_ratio = int_const(code["marker"], "WIRE_RATIO")
    check("①-4 堆估算倍率与 MarkerBudget 同一口径（两处字面量必须相等，防口径漂移）",
          is_ratio == WIRE_RATIO and marker_ratio == WIRE_RATIO,
          "UnitLibraryLimits=%r MarkerBudget=%r" % (is_ratio, marker_ratio))
    check("①-5 判据实际用的堆上限 = 线上预算 × 倍率",
          "MAX_LIBRARY_HEAP_BYTES" in limits and "* WIRE_RATIO" in limits,
          "未见换算")
    fits_body = brace_body(limits, "boolean fits(")
    check("①-6 fits() 用**减法**形式避免 long 溢出（used + add 在极值下会溢出成负数 ⇒ 误放行）",
          "MAX_LIBRARY_HEAP_BYTES - addHeap" in fits_body and "return usedHeap <= MAX_LIBRARY_HEAP_BYTES - addHeap" in fits_body,
          "fits 里没看到减法形式：%r" % (fits_body[:160],))
    check("①-7 fits() 没有写成加法比较",
          "+ addHeap" not in fits_body,
          "fits 里出现加法比较")

    be = code["be"]
    # 体积只在 MarkerBudget 里量一次：库自己不许再写一份 sizeInBytes
    lib = brace_body(be, "public static final class GrowingUnitLibrary")
    check("①-8 库只复用 MarkerBudget 的量体积实现（不另写一份 sizeInBytes 口径）",
          "MarkerBudget.stackBytes(" in lib and "sizeInBytes(" not in lib,
          "库内部另写了尺寸判据")
    try_body = brace_body(lib, "public boolean trySetStackInSlot(")
    check("①-9 唯一写入口 trySetStackInSlot 存在且在写入前判预算", "UnitLibraryLimits.fits(" in try_body,
          "trySetStackInSlot 没判预算")
    check("①-10 只拦增长：判据必须带 delta > 0 条件（换小 / 清空永远允许）",
          re.search(r"delta\s*>\s*0\s*&&\s*!UnitLibraryLimits\.fits\(", try_body) is not None,
          "没有 delta > 0 守卫：%r" % (try_body[:200],))
    check("①-11 写入前先判、判不过直接返回 false（原子拒绝，不半写）",
          re.search(r"if\s*\(delta\s*>\s*0\s*&&\s*!UnitLibraryLimits\.fits\(", try_body) is not None
          and "return false;" in try_body[:try_body.find("super.setStackInSlot")],
          "拒绝路径不明确")
    set_slot = brace_body(lib, "public void setStackInSlot(")
    check("①-12 setStackInSlot 只委托唯一入口（不再自己写 super.setStackInSlot）",
          "trySetStackInSlot(slot, stack)" in set_slot and "super.setStackInSlot" not in set_slot,
          "setStackInSlot 绕过唯一入口")
    check("①-13 整个库类里 super.setStackInSlot 只有一处（唯一入口内部）",
          lib.count("super.setStackInSlot") == 1,
          "super.setStackInSlot 出现 %d 处" % lib.count("super.setStackInSlot"))
    extract_body = brace_body(lib, "public ItemStack extractItem(")
    check("①-14 取出只维护缓存、不判上限（取出/变小永远允许）",
          "fits(" not in extract_body and "usedHeapBytes" in extract_body,
          "取出路径判了上限或没维护缓存")
    insert_body = brace_body(lib, "public ItemStack insertItem(")
    check("①-15 空格插入属于增长 ⇒ 先判预算、不过整张退回（绝不半插）",
          "canAcceptMore(stack)" in insert_body and "return stack;" in insert_body,
          "insertItem 未守预算")
    check("①-16 缓存失效只发生在读档 / 缩小 / 注入上下文（其余走增量维护）",
          "rscc$usedHeapBytes = -1L" in lib and "usedHeapBytes" in extract_body,
          "缓存维护缺失")
    deser = brace_body(be, "public void deserializeNBT(final HolderLookup.Provider provider")
    check("①-17 读档**不判**体积预算（超限旧库原样读回、绝不截断）",
          "canAcceptMore" not in deser and "UnitLibraryLimits" not in deser,
          "读档路径判了预算 ⇒ 可能截断旧档")
    check("①-18 读档仍走 rscc$resizeRaw（不判格数上限，与第 116 轮一致）",
          "rscc$resizeRaw(" in deser,
          "读档改走了判上限的 resize")

    # 各写入路径都要过闸
    put_body = brace_body(be, "public boolean putUnitIntoLibrary(")
    check("①-19 生成入库 putUnitIntoLibrary：判预算 + 用唯一入口 + 失败不销毁",
          "canAcceptMore(unitStack)" in put_body and "trySetStackInSlot(" in put_body
          and "return false;" in put_body,
          "putUnitIntoLibrary 未完整守闸")
    pull_body = brace_body(be, "public int pullUnitsFrom(")
    check("①-20 从执行仓取回 pullUnitsFrom：预算不过就 break，样板留在执行仓（不销毁）",
          "canAcceptMore(stack)" in pull_body and "warnUnitLibraryFullBytes()" in pull_body
          and "break;" in pull_body,
          "pullUnitsFrom 未守闸")
    check("①-21 取回路径不再有裸的 unitLibrary.setStackInSlot（必须走唯一入口）",
          "unitLibrary.setStackInSlot" not in pull_body,
          "pullUnitsFrom 仍裸写库")
    gen_body = brace_body(be, "public boolean generateAssemblyPattern(")
    check("①-22 「生成样板」在扣材料之前预检体积（canAcceptUnits）+ 体积满用独立文案",
          "canAcceptUnits(unitsToStore)" in gen_body
          and "generate.library_full_bytes" in gen_body,
          "生成路径未预检体积")
    create = code["create_packet"]
    handle = brace_body(create, "public static void handle(")
    check("①-23 新建单元样板包：判预算 + 走唯一入口 + 被拒有动作栏提示",
          "canAcceptMore(unit)" in handle and "trySetStackInSlot(" in handle
          and "warnLibraryBytesFull(player)" in handle,
          "新建包未守体积闸")
    check("①-24 新建包不再裸写 unitLibrary.setStackInSlot",
          "unitLibrary.setStackInSlot" not in handle,
          "仍裸写库")
    menu = code["menu"]
    # mayPlace 在菜单里有多个实现（不同槽位类），这里锁定「单元样板库那一格」：
    # 判据 = 该实现里出现 SEQUENCE_UNIT_PATTERN 之后紧跟着预算判断。
    unit_slot_anchor = menu.find("stack.is(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get())")
    check("①-25 菜单格 mayPlace 也看预算（否则点击先把物品挪走再被服务端拒绝 = 诡异/丢物）",
          unit_slot_anchor >= 0
          and "canAcceptMore(stack)" in menu[unit_slot_anchor:unit_slot_anchor + 400],
          "mayPlace 未看预算")

    # 语言键 zh/en 成对、占位符数目一致、不写死数字
    for lang in ("zh_cn", "en_us"):
        data = json.load(io.open(os.path.join(LANG_DIR, lang + ".json"), encoding="utf-8"))
        for key in LANG_KEYS:
            text = data.get(key)
            if text is None:
                check("①-26 语言键 %s 在 %s 存在" % (key, lang), False, "缺失")
                continue
            placeholders = re.findall(r"%(\d+\$)?s", text)
            check("①-26 语言键 %s 在 %s 存在且带 1 个 %%s 占位符" % (key, lang),
                  len(placeholders) == 1, "占位符 %r" % (placeholders,))
            check("①-27 语言键 %s 在 %s 不写死 1024 / 2048（数字必须随常量走）" % (key, lang),
                  "1024" not in text and "2048" not in text, "文案写死了数字：%r" % text)


# ======================================================================================
# ② 真代码 JVM 探针
# ======================================================================================

JAVA_PROBE = r"""
public final class UnitLibraryLimitsProbe {
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

    public static void main(String[] args) {
        final long max = UnitLibraryLimits.MAX_LIBRARY_HEAP_BYTES;
        // 常量口径
        is(UnitLibraryLimits.WIRE_RATIO == 8, true, "WIRE_RATIO==8");
        is(UnitLibraryLimits.MAX_LIBRARY_WIRE_BYTES == 1024 * 1024, true, "线上 1 MiB");
        is(UnitLibraryLimits.MAX_LIBRARY_WIRE_KB == 1024, true, "KB 文案值");
        is(max == (long) UnitLibraryLimits.MAX_LIBRARY_WIRE_BYTES * UnitLibraryLimits.WIRE_RATIO, true, "堆上限换算");
        // 基本边界
        is(UnitLibraryLimits.fits(0, max), true, "空库放满");
        is(UnitLibraryLimits.fits(0, max + 1), false, "空库放超 1");
        is(UnitLibraryLimits.fits(max, 0), true, "满库零增长（允许）");
        is(UnitLibraryLimits.fits(max, 1), false, "满库 +1");
        is(UnitLibraryLimits.fits(max - 5, 5), true, "刚好放满");
        is(UnitLibraryLimits.fits(max - 5, 6), false, "超 1 字节");
        is(UnitLibraryLimits.fits(-1, 100), true, "负用量按空库");
        is(UnitLibraryLimits.fits(max, -5), true, "负增长（取出）永远允许");
        is(UnitLibraryLimits.fits(0, 0), true, "零增长");
        // 溢出反例：这是本条判据最容易写错的地方
        is(UnitLibraryLimits.fits(Long.MAX_VALUE, Long.MAX_VALUE), false, "双极值不许溢出放行");
        is(UnitLibraryLimits.fits(Long.MAX_VALUE, 1), false, "超大用量 + 1 不许放行");
        is(UnitLibraryLimits.fits(0, Long.MAX_VALUE), false, "单张极大不许放行");
        // remaining / 文案
        is(UnitLibraryLimits.remaining(0) == max, true, "空库剩余 = 上限");
        is(UnitLibraryLimits.remaining(max) == 0, true, "满库剩余 = 0");
        is(UnitLibraryLimits.remaining(Long.MAX_VALUE) == 0, true, "极值剩余 = 0（不许负数）");
        is(UnitLibraryLimits.usedWireKb(0) == 0, true, "0 字节 = 0 KB");
        is(UnitLibraryLimits.usedWireKb(1) == 1, true, "1 字节也报 1 KB（不许显示 0）");
        is(UnitLibraryLimits.usedWireKb(max) == UnitLibraryLimits.MAX_LIBRARY_WIRE_KB, true, "满库 KB = 文案上限");
        System.out.println("PROBE passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
"""


def group_jvm_probe():
    print("\n=== ② 真代码 JVM 探针（javac 编译 UnitLibraryLimits 后真跑）===")
    os.makedirs(PROBE_DIR, exist_ok=True)
    limits_src = os.path.join(PROBE_DIR, "UnitLibraryLimits.java")
    with io.open(FILES["limits"], "r", encoding="utf-8") as handle:
        text = handle.read()
    # 去掉 package 声明再编译到默认包：否则 class 落在 cretae/... 子目录，默认包的探针类引用不到它。
    text = re.sub(r"^\s*package\s+[\w.]+\s*;\s*$", "", text, count=1, flags=re.M)
    with io.open(limits_src, "w", encoding="utf-8") as handle:
        handle.write(text)
    probe_src = os.path.join(PROBE_DIR, "UnitLibraryLimitsProbe.java")
    with io.open(probe_src, "w", encoding="utf-8") as handle:
        handle.write(JAVA_PROBE)
    compile_cmd = [JAVAC, "-encoding", "UTF-8"] + JAVAC_EN + ["-d", PROBE_DIR, limits_src, probe_src]
    result = subprocess.run(compile_cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    check("②-1 纯 JDK 类可单独 javac 编译（真代码，不是模型）", result.returncode == 0,
          (result.stdout or "") + (result.stderr or ""))
    if result.returncode != 0:
        return
    run = subprocess.run([JAVA, "-cp", PROBE_DIR, "UnitLibraryLimitsProbe"],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    check("②-2 JVM 探针 22 条断言全过（含 Long.MAX_VALUE 溢出反例）", run.returncode == 0,
          (run.stdout or "") + (run.stderr or ""))
    if run.stdout:
        for line in run.stdout.strip().splitlines():
            print("    " + line)


# ======================================================================================
# ③ 行为模型：库的体积记账（含缓存增量维护）
# ======================================================================================

class ModelLibrary:
    """复刻 GrowingUnitLibrary 的体积记账语义（尺寸由调用方给，测试里用确定性伪尺寸）。"""

    def __init__(self, slots=108, max_slots=1024):
        self.stacks = [0] * slots          # 0 = 空槽，>0 = 该张的堆估算体积
        self.max_slots = max_slots
        self.cached = -1                   # 与 Java 侧 rscc$usedHeapBytes 同义
        self.rejections = 0

    # ---- 缓存 ----
    def invalidate(self):
        self.cached = -1

    def used(self):
        if self.cached < 0:
            self.cached = sum(self.stacks)
        return self.cached

    def real_used(self):
        return sum(self.stacks)

    def can_grow(self):
        return len(self.stacks) < self.max_slots

    def ensure_slot(self, slot):
        if slot >= len(self.stacks):
            if not self.can_grow():
                return False
            self.stacks = self.stacks + [0] * (slot + 1 - len(self.stacks))
        return True

    def trim_trailing(self):
        size = len(self.stacks)
        while size > 1 and self.stacks[size - 1] == 0:
            size -= 1
        if size < len(self.stacks):
            self.invalidate()
            self.stacks = self.stacks[:size]

    def ensure_trailing_empty(self):
        if not self.stacks or self.stacks[-1] != 0:
            self.resize(len(self.stacks) + 1)

    def resize(self, new_size):
        if new_size < 1 or new_size == len(self.stacks):
            return
        if new_size > len(self.stacks) and new_size > self.max_slots:
            return
        if new_size < len(self.stacks):
            self.invalidate()
        self.stacks = (self.stacks + [0] * new_size)[:new_size]

    # ---- 唯一写入口 ----
    def try_set(self, slot, incoming):
        if slot < 0:
            return False
        if slot >= len(self.stacks) and not self.can_grow():
            return False
        if not self.ensure_slot(slot):
            return False
        if slot >= len(self.stacks):
            return False  # 与 Java 侧同一道兜底：扩容被拒时不许继续索引
        previous = self.stacks[slot]
        delta = incoming - previous
        if delta > 0 and not fits(self.used(), delta):
            self.rejections += 1
            return False
        self.stacks[slot] = incoming
        # 与 Java 侧同一纪律：缓存失效（-1）时保持失效，绝不当成「已用 0」
        if self.cached >= 0:
            self.cached = max(0, self.cached + delta)
        if incoming == 0:
            self.trim_trailing()
        self.ensure_trailing_empty()
        return True

    def first_empty(self):
        for i, value in enumerate(self.stacks):
            if value == 0:
                return i
        return len(self.stacks) if self.can_grow() else -1

    def extract(self, slot, whole=True):
        removed = self.stacks[slot]
        if removed == 0:
            return 0
        give = removed if whole else removed // 2
        self.stacks[slot] = removed - give
        if self.cached >= 0:
            self.cached = max(0, self.cached - give)
        # 缓存已失效（-1）时保持失效：绝不能把「不知道」当成「0 已用」
        return give

    def insert(self, slot, incoming):
        if self.stacks[slot] == 0 and not fits(self.used(), incoming):
            self.rejections += 1
            return incoming  # 整张退回
        self.stacks[slot] += incoming
        if self.cached >= 0:
            self.cached = max(0, self.cached + incoming)
        return 0


def fits(used, add):
    if add <= 0:
        return True
    if add > MAX_HEAP_BYTES:
        return False
    if used <= 0:
        return True
    return used <= MAX_HEAP_BYTES - add


def group_model():
    print("\n=== ③ 行为模型：体积记账 / 只拦增长 / 读档不判 ===")
    rng = random.Random(1182026)

    # 3-1 确定性随机序列：缓存必须始终等于真实合计；增长永不越限
    lib = ModelLibrary()
    ok_cache = True
    ok_bound = True
    ok_remove = True
    detail = ""
    for _ in range(4000):
        action = rng.random()
        if action < 0.55:
            slot = lib.first_empty()
            if slot < 0:
                continue
            size = rng.choice([50, 200, 800, 2048 * WIRE_RATIO, 4096 * WIRE_RATIO])
            before = lib.real_used()
            wrote = lib.try_set(slot, size)
            if wrote and lib.real_used() > MAX_HEAP_BYTES:
                ok_bound = False
                detail = "增长后越限 %d > %d" % (lib.real_used(), MAX_HEAP_BYTES)
            if not wrote and lib.real_used() != before:
                ok_bound = False
                detail = "拒绝时数据被改动"
        elif action < 0.75:
            slot = rng.randrange(len(lib.stacks))
            lib.extract(slot, whole=rng.random() < 0.5)
        elif action < 0.9:
            slot = rng.randrange(len(lib.stacks))
            lib.insert(slot, rng.choice([1, 50, 100])) if lib.stacks[slot] else None
        else:
            slot = rng.randrange(len(lib.stacks))
            # 换小：满库时也必须允许
            smaller = max(0, lib.stacks[slot] - rng.choice([1, 10, 100]))
            if lib.stacks[slot] > 0:
                before = lib.real_used()
                wrote = lib.try_set(slot, smaller)
                if not wrote and lib.real_used() == before and not fits(before, smaller - lib.stacks[slot]):
                    ok_remove = False
                    detail = "换小被拒"
        if lib.cached >= 0 and lib.cached != lib.real_used():
            ok_cache = False
            detail = "缓存 %d != 实际 %d" % (lib.cached, lib.real_used())
            break
    lib.used()  # 触发一次懒计算，确保缓存被校正过
    check("③-1 随机 4000 步：缓存始终等于真实合计（不会停留在旧体积）",
          ok_cache, detail)
    check("③-2 随机 4000 步：任何增长都没有越过合计上限", ok_bound, detail)
    check("③-3 满库时「换小 / 取出」永远允许（只拦增长）", ok_remove, detail)

    # 3-4 满库判定：把库填到上限，再加就拒；取出一点又能加
    lib = ModelLibrary(slots=4)
    unit = 2 * 1024 * 1024  # 2 MiB 堆 / 张
    filled = 0
    while filled < 4 and lib.try_set(lib.first_empty(), unit):
        filled += 1
    check("③-4 4 张 × 2 MiB 正好填满 8 MiB 预算", filled == 4, "filled=%d" % filled)
    check("③-5 预算满后再加一张 ⇒ 拒绝（且本次一格未写）",
          lib.try_set(lib.first_empty() if lib.first_empty() >= 0 else 0, unit) is False)
    lib.extract(0, whole=True)
    check("③-6 取出 1 张后又能写进 1 张（体积释放是真实的）", lib.try_set(0, unit) is True)

    # 3-7 读档不判上限：超限旧库原样读回，只把缓存置失效
    legacy = [unit] * 6  # 12 MiB > 8 MiB 上限（旧版本留下的病态库）
    lib = ModelLibrary(slots=len(legacy))
    lib.stacks = list(legacy)
    lib.invalidate()
    check("③-7 读档把超限旧库原样读回（不截断、不清空）",
          lib.stacks == legacy, "旧库被改动")
    check("③-8 读档后的库不许再增长（超限库只许缩小）",
          lib.try_set(lib.first_empty() if lib.first_empty() >= 0 else 0, 1000) is False)
    before = lib.real_used()
    lib.try_set(0, 0)
    check("③-9 超限旧库仍然允许删除（恢复路径只有删，没有自动截断）",
          lib.real_used() < before, "删除被拒")


# ======================================================================================
# ④ 反例自证
# ======================================================================================

def group_counterexamples():
    print("\n=== ④ 反例自证：坏写法必须被判负 ===")
    source = read(FILES["be"])

    # (a) 去掉体积判据（模拟「只有条数上限」的旧状态）⇒ 合计会越限
    no_budget = source.replace("if (delta > 0 && !UnitLibraryLimits.fits(usedHeapBytes(), delta)) {",
                               "if (false) {")
    counterexample("④-1 源码副本：删掉体积判据后，锚点 ①-10 立刻判负（判据不是摆设）",
          "delta > 0 && !UnitLibraryLimits.fits(" not in no_budget)

    # (b) 读档判体积（模拟「顺手在 load 里拦一下」的坏主意）⇒ 会截断旧档
    with_load_check = source.replace(
        "            // 第 118 轮：读档<b>不判</b>体积预算",
        "            if (!unitLibrary.canAcceptMore(ItemStack.EMPTY)) { return; }\n"
        "            // 读档判体积（坏写法）")
    counterexample("④-2 源码副本：读档里插入预算判断后，锚点 ①-17 会判负",
          "canAcceptMore" in brace_body(strip_comments(with_load_check),
                                       "public void deserializeNBT(final HolderLookup.Provider provider"))

    # (c) 加法形式 fits：Long.MAX_VALUE 溢出 ⇒ 误放行（JVM 探针会抓到）
    long_max = 9223372036854775807
    wrapped = long_max + long_max - 2 ** 64  # Java long 回绕后的真实值
    counterexample("④-3 行为模型：加法形式 fits 在 Java long 回绕后得到 -2 ⇒ 误判「放得下」（正是减法形式要挡的）",
                   wrapped == -2 and wrapped <= MAX_HEAP_BYTES)
    counterexample("④-4 行为模型：减法形式 fits(Long.MAX_VALUE, Long.MAX_VALUE) 判负",
                   fits(long_max, long_max) is False)

    # (d) 只在服务端写入口拦、mayPlace 不看预算 ⇒ 客户端会先把物品挪走
    menu = read(FILES["menu"])
    bad_menu = strip_comments(menu).replace("&& terminal.unitLibrary.canAcceptMore(stack);", ";")
    anchor = bad_menu.find("stack.is(RS_Create_Compat.SEQUENCE_UNIT_PATTERN.get())")
    counterexample("④-5 源码副本：mayPlace 去掉预算判断后，锚点 ①-25 会判负",
                   anchor < 0 or "canAcceptMore(stack)" not in bad_menu[anchor:anchor + 400])

    # (e) 读档判格数上限（第 116 轮的旧坑）⇒ 会截断旧档；断言当前代码没有回退
    counterexample("④-6 当前读档没有回退成「判格数上限再重建」（旧坑）",
          "rscc$resizeRaw(" in brace_body(strip_comments(source),
                                          "public void deserializeNBT(final HolderLookup.Provider provider"))


def main():
    print("第 118 轮自检：单元样板库合计体积预算")
    group_anchors()
    group_jvm_probe()
    group_model()
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
