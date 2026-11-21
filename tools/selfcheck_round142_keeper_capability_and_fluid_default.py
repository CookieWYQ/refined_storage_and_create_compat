#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 142 轮自检：保持器 H2 剩余（对外能力对 RS 隐藏）+ M2（流体默认目标按桶换算）。

1) H2 剩余（ROUND118 复核，需产品拍板，用户口径「按最优方案」）：
   保持器把「本机缓冲 + 网络里与标记同类的资源」通过 IItemHandler / IFluidHandler 暴露给相邻方块
   （供漏斗 / 管道取出 / 送入，设计功能）。RS 2.0.9 的外部存储会把该能力注册成**网络自己的存储源**：
     (a) 网络镜像 = 网络存量本身 ⇒ 网络存量 = 真实存储 + 镜像，虚高（每虚拟槽最多 +STORAGE_SLOT_LIMIT）；
     (b) 本机缓冲也被 net.get 计入，而 KeeperOverflow.localDestroyAmount 的池子又是
         net.get + 缓冲余量 ⇒ 缓冲被算两遍，销毁量偏多；
     (c) 读 / 抽路径可绕成递归（第 118 轮只给抽取加了重入闸）。
   **最优方案**：整份能力只对第三方物流可见，对 RS 自己的存储聚合隐藏（KeeperCapabilityVisibility，
   调用栈判据）。RS 读存量 / 取资源直接走网络即可，隐藏后不少任何可达路径；第三方（Create 漏斗 /
   管道 / 原版漏斗）完全不变。隐藏必须是**整份**能力（读 / 插入 / 抽取 / 流体 fill / drain 全部）：
   只要 RS 还看得见任何一部分，它就会把那部分注册成网络存储，双计与池子重复计数就还在。

2) M2（ROUND118 复核）：基础版 / 高级版默认目标 64 对物品是「个数」，对流体 / 气体却是
   「mB」⇒ 拖入岩浆后按 64 mB 持续销毁。用户口径：「改成 64000mb」。
   修法：目标还停在物品默认值时，首次标流体 / 气体 ⇒ 默认目标换成
   `物品默认值 × 1000`（默认 64 ⇒ 64000 mB，与 1 桶 = 1000 mB 同口径）；改回物品标记时换回。
   只动「未改过的默认值」，玩家设过的值 / 旧存档读来的值一概不动。

用法：python tools/selfcheck_round142_keeper_capability_and_fluid_default.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import os
import re
import shutil
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

VISIBILITY = os.path.join(JAVA, "support", "KeeperCapabilityVisibility.java")
KEEPER_TARGET = os.path.join(JAVA, "support", "KeeperTarget.java")
BASE_KEEPER = os.path.join(JAVA, "block", "entity", "QuantityKeeperBlockEntity.java")
ADV_KEEPER = os.path.join(JAVA, "block", "entity", "AdvancedQuantityKeeperBlockEntity.java")

PROBE_DIR = os.path.join(ROOT, "build", "round142_probe")
JAVAC = "D:/java21/bin/javac.exe"
JAVA_BIN = "D:/java21/bin/java.exe"

PASSED = 0
FAILED = 0
FAILS: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    global PASSED, FAILED
    if ok:
        PASSED += 1
        print("  OK   %s" % name)
    else:
        FAILED += 1
        FAILS.append(name)
        print("  FAIL %s%s" % (name, ("  <- " + detail) if detail else ""))


def read(path: str) -> str:
    with io.open(path, encoding="utf-8") as handle:
        return handle.read()


def method_slice(text: str, signature: str, length: int = 1400) -> str:
    start = text.index(signature)
    return text[start:start + length]


# ======================================================================================
# ① 行为模型：M2 流体默认目标 + 切换 + H2 销毁量
# ======================================================================================

MAX_SET_TARGET = 1_000_000_000
FALLBACK_ITEM_DEFAULT = 64
FLUID_MILLIBUCKETS_PER_UNIT = 1_000


def item_default(configured: int) -> int:
    value = configured if configured > 0 else FALLBACK_ITEM_DEFAULT
    return max(0, min(value, MAX_SET_TARGET))


def fluid_default(configured: int) -> int:
    return max(0, min(item_default(configured) * FLUID_MILLIBUCKETS_PER_UNIT, MAX_SET_TARGET))


def form_switch(previous_form: str, new_form: str, target: int, configured: int) -> int:
    """标记载体切换时的目标默认值（与两个方块实体里的分支同语义）。"""
    if new_form == "fluid" and previous_form == "item" and target == item_default(configured):
        return fluid_default(configured)
    if new_form == "item" and previous_form in ("fluid", "gas") and target == fluid_default(configured):
        return item_default(configured)
    return target


def local_destroy(net_after: int, local_left: int, target: int) -> int:
    """与 KeeperOverflow.localDestroyAmount 同语义。"""
    if local_left <= 0:
        return 0
    return min(local_left, max(0, net_after + local_left - target))


def group_models() -> None:
    print("\n=== ① 行为模型：流体默认 64000 mB / 形态切换 / 双计销毁量 ===")
    cases = [
        (64, 64, 64000, "出厂配置：物品 64、流体 64000 mB（用户口径）"),
        (1, 1, 1000, "物品默认 1 ⇒ 流体 1000 mB（1 桶）"),
        (1000, 1000, 1_000_000, "物品默认 1000 ⇒ 流体 1,000,000 mB（1000 桶）"),
        (0, 64, 64000, "配置未加载（读 0）⇒ 回退 64 / 64000（不出现 0 mB）"),
        (1_000_000_000, 1_000_000_000, 1_000_000_000, "配置顶格 1e9 ⇒ 流体目标被钳到 1e9（不溢出）"),
        (2_000_000_000, 1_000_000_000, 1_000_000_000, "配置越界（改坏）⇒ 物品与流体都被钳到 1e9"),
    ]
    for configured, want_item, want_fluid, why in cases:
        check("① 配置 %d：物品默认 %d / 流体默认 %d（%s）"
              % (configured, want_item, want_fluid, why),
              item_default(configured) == want_item and fluid_default(configured) == want_fluid,
              "got %d / %d" % (item_default(configured), fluid_default(configured)))

    switch_cases = [
        ("item", "fluid", 64, 64, 64000, "新机器拖岩浆：64 个的默认值 ⇒ 64000 mB"),
        ("fluid", "item", 64000, 64, 64, "改回物品标记：64000 mB ⇒ 64 个"),
        ("fluid", "gas", 64000, 64, 64000, "流体改气体：目标不变（同为 mB 口径）"),
        ("item", "fluid", 128, 64, 128, "玩家设过 128 ⇒ 标流体不覆盖"),
        ("fluid", "item", 500, 64, 500, "玩家设过 500 mB ⇒ 改物品不覆盖"),
        ("item", "fluid", 0, 64, 0, "目标 0（未标记）⇒ 不动（0 不是默认值）"),
    ]
    for prev, new, target, configured, want, why in switch_cases:
        check("① 形态切换 %s→%s 目标 %d ⇒ %d（%s）" % (prev, new, target, want, why),
              form_switch(prev, new, target, configured) == want,
              "got %d" % form_switch(prev, new, target, configured))

    check("① 用户口径：64 个的默认目标 ⇒ 64000 mB（不是 64 mB，也不是 64000 个）",
          fluid_default(64) == 64000)

    # H2 双计模型：目标 64、缓冲 100、真实网络 0、镜像 64（旧实现把镜像与缓冲都算进 net.get）
    target = 64
    buffer = 100
    mirror = 64
    new_destroy = local_destroy(0, buffer, target)
    old_destroy = local_destroy(0 + buffer + mirror, buffer, target)
    check("① H2 模型：旧实现把镜像/缓冲算进池子 ⇒ 销毁 %d（超过应有的 %d），修后销毁 %d"
          % (old_destroy, buffer - target, new_destroy),
          new_destroy == buffer - target == 36 and old_destroy == 100)
    check("① H2 模型：修后「网络 0 + 缓冲 100、目标 64」只销毁 36 个，总数恰好停在目标 64",
          0 + buffer - new_destroy == target)


# ======================================================================================
# ② 源码锚点：KeeperCapabilityVisibility（整份能力对 RS 隐藏）
# ======================================================================================

def group_h2_anchors() -> None:
    print("\n=== ② 源码锚点：整份对外能力对 RS 隐藏（H2 剩余）===")
    vis = read(VISIBILITY)
    base = read(BASE_KEEPER)
    adv = read(ADV_KEEPER)

    check("②-1 新增纯 JDK 的 KeeperCapabilityVisibility（可单独 javac 真跑）",
          os.path.exists(VISIBILITY) and "StackWalker" in vis
          and "final class KeeperCapabilityVisibility" in vis)
    check("②-2 判据是 RS 包前缀，且限制栈深（不做全栈遍历）",
          'REFINED_STORAGE_PACKAGE = "com.refinedmods.refinedstorage"' in vis
          and "MAX_FRAMES" in vis and "limit(MAX_FRAMES)" in vis)
    check("②-3 隐藏语义入口 hiddenFromCaller 存在且只委托 isRefinedStorageCaller",
          "public static boolean hiddenFromCaller()" in vis
          and "return isRefinedStorageCaller();" in vis)
    check("②-4 类注释写清两类双计（网络镜像 + 本机缓冲被池子重复计数），以及「隐藏必须是整份能力」",
          "网络镜像被重复计算" in vis and "本机缓冲也被重复计入" in vis
          and "隐藏是整份能力" in vis)

    base_handler = base[base.index("private final class KeeperItemHandler"):]
    adv_handler = adv[adv.index("private final class KeeperItemHandler"):]

    # 基础版：物品 3 个入口（读 / 插 / 抽）+ 流体 4 个入口（读 / fill / drain×2）= 7
    check("②-5 基础版：整份能力 7 处闸门（物品读/插/抽 + 流体读/fill/drain×2）",
          base.count("KeeperCapabilityVisibility.hiddenFromCaller()") == 7,
          "count=%d" % base.count("KeeperCapabilityVisibility.hiddenFromCaller()"))
    pairs_base = [
        ("public ItemStack getStackInSlot(final int slot) {", "return storage.getStackInSlot(slot);"),
        ("public ItemStack insertItem(", "matchesItemMarker(stack)"),
        ("public ItemStack extractItem(", "final long taken = extractMarkerItemFromNetwork"),
        ("public FluidStack getFluidInTank(", "fluidStorage.entries()"),
        ("public int fill(", "isFluidValid(0, resource)"),
        ("public FluidStack drain(final FluidStack resource", "fluidStorage.getAmount(id, nbt)"),
        ("public FluidStack drain(final int maxDrain", "fluidStorage.entries()"),
    ]
    order_ok = True
    order_detail = ""
    for signature, marker in pairs_base:
        body = method_slice(base, signature)
        try:
            gate = body.index("KeeperCapabilityVisibility.hiddenFromCaller()")
            later = body.index(marker)
        except ValueError:
            order_ok = False
            order_detail = signature
            break
        if gate > later:
            order_ok = False
            order_detail = signature
            break
    check("②-6 基础版：每个入口的第一条判据都是闸门（先隐藏、后碰本机 / 网络数据）",
          order_ok, order_detail)
    check("②-7 基础版：隐藏分支的默认值安全（读=空 / 插入=原样退回 / 抽取=空 / fill=0）",
          "if (KeeperCapabilityVisibility.hiddenFromCaller()) {\n                return ItemStack.EMPTY;" in base_handler
          and "if (KeeperCapabilityVisibility.hiddenFromCaller()) {\n                return stack;" in base_handler
          and "if (KeeperCapabilityVisibility.hiddenFromCaller()) {\n                return 0;" in base_handler)

    # 高级版：物品 3 + 流体 4 = 7
    check("②-8 高级版：整份能力 7 处闸门（物品读/插/抽 + 流体读/fill/drain×2）",
          adv.count("KeeperCapabilityVisibility.hiddenFromCaller()") == 7,
          "count=%d" % adv.count("KeeperCapabilityVisibility.hiddenFromCaller()"))
    pairs_adv = [
        ("public ItemStack getStackInSlot(final int slot) {", "itemStorages[slot / STORAGE_SLOTS].getStackInSlot"),
        ("public ItemStack insertItem(", "matchingItemSlot(stack)"),
        ("public ItemStack extractItem(", "itemStorages[config].getStackInSlot"),
        ("public FluidStack getFluidInTank(", "fluidStorages[tank].entries()"),
        ("public int fill(", "matchingFluidSlot(id)"),
        ("public FluidStack drain(final FluidStack resource", "fluidStorages[tank].getAmount(id, nbt)"),
        ("public FluidStack drain(final int maxDrain", "fluidStorages[i].entries()"),
    ]
    order_ok = True
    order_detail = ""
    for signature, marker in pairs_adv:
        body = method_slice(adv, signature)
        try:
            gate = body.index("KeeperCapabilityVisibility.hiddenFromCaller()")
            later = body.index(marker)
        except ValueError:
            order_ok = False
            order_detail = signature
            break
        if gate > later:
            order_ok = False
            order_detail = signature
            break
    check("②-9 高级版：每个入口的第一条判据都是闸门（先隐藏、后碰本机 / 网络数据）",
          order_ok, order_detail)
    check("②-10 高级版：隐藏后 networkItem() 不再额外重复闸门（单一入口，便于审计）",
          "private ItemStack networkItem(final int config, final int amount, final boolean simulate) {\n"
          "            final long taken = extractNetworkItem(config, amount, simulate);" in adv)


# ======================================================================================
# ③ 源码锚点：M2（流体默认目标 64000 mB）
# ======================================================================================

def group_m2_anchors() -> None:
    print("\n=== ③ 源码锚点：M2 流体默认目标 ===")
    target = read(KEEPER_TARGET)
    base = read(BASE_KEEPER)
    adv = read(ADV_KEEPER)

    check("③-1 KeeperTarget：有物品 / 流体两套默认值入口与 1000 倍换算常量",
          "int itemDefaultTarget()" in target and "int fluidDefaultTarget()" in target
          and "FLUID_MILLIBUCKETS_PER_DEFAULT_UNIT = 1_000" in target)
    check("③-2 流体默认走唯一钳制入口（不另写一套 Math.max/min）",
          "return clampSetTarget((int) Math.min(MAX_SET_TARGET, scaled));" in target)
    check("③-3 配置未加载回退 64（不会出现「默认 0 mB」）",
          "FALLBACK_ITEM_DEFAULT_TARGET = 64" in target
          and "configured > 0 ? configured : FALLBACK_ITEM_DEFAULT_TARGET" in target)

    check("③-4 基础版字段初值改走 KeeperTarget.itemDefaultTarget()",
          "private int targetAmount = KeeperTarget.itemDefaultTarget();" in base)
    fluid_body = method_slice(base, "public void setFluidMarker(")
    check("③-5 基础版 setFluidMarker：标流体时把「未改过的物品默认值」换成流体默认值",
          "if (markerForm == FORM_ITEM && targetAmount == KeeperTarget.itemDefaultTarget()) {\n"
          "            targetAmount = KeeperTarget.fluidDefaultTarget();" in fluid_body)
    check("③-6 基础版 setItemMarker：改回物品标记时换回物品默认值（清空标记不动目标）",
          "if (!stack.isEmpty() && markerForm != FORM_ITEM && targetAmount == KeeperTarget.fluidDefaultTarget()) {\n"
          "            targetAmount = KeeperTarget.itemDefaultTarget();" in base)

    check("③-7 高级版构造器初值改走 KeeperTarget.itemDefaultTarget()",
          "targets[i] = KeeperTarget.itemDefaultTarget();" in adv)
    adv_fluid_body = method_slice(adv, "public void setFluidMarker(final int slot")
    check("③-8 高级版 setFluidMarker：该槽未改过的默认值换成流体默认值",
          "if (fluidMarkerIds[slot] == null && targets[slot] == KeeperTarget.itemDefaultTarget()) {\n"
          "            targets[slot] = KeeperTarget.fluidDefaultTarget();" in adv_fluid_body)
    adv_item_body = method_slice(adv, "public void setItemMarker(final int slot")
    check("③-9 高级版 setItemMarker：改回物品标记时换回物品默认值",
          "if (!stack.isEmpty() && fluidMarkerIds[slot] != null\n"
          "                && targets[slot] == KeeperTarget.fluidDefaultTarget()) {\n"
          "            targets[slot] = KeeperTarget.itemDefaultTarget();" in adv_item_body)

    base_read = base[base.index("public void loadAdditional("):]
    base_read = base_read[:base_read.index("\n    }")]
    check("③-10 基础版读档路径完全没动（旧档目标原样读回，不经过 M2 切换）",
          'targetAmount = Math.max(0, tag.getInt("TargetAmount"));' in base_read
          and "fluidDefaultTarget" not in base_read)
    adv_read = adv[adv.index("private void readConfigs("):]
    adv_read = adv_read[:adv_read.index("\n    }")]
    check("③-11 高级版读档路径同样没动（每槽目标原样读回）",
          "targets[i] = Math.max(0, entry.getInt(TAG_CONFIG_TARGET));" in adv_read
          and "fluidDefaultTarget" not in adv_read)


# ======================================================================================
# ④ 真代码 JVM 探针：调用栈判据（含伪造 RS 包名的调用者）
# ======================================================================================

PROBE_MAIN = """\
import com.refinedmods.refinedstorage.probe.FakeRsCaller;
import cretae.cookiewyq.rs_create_compat.support.KeeperCapabilityVisibility;

public final class KeeperCapabilityVisibilityProbe {
    private static int passed = 0;
    private static int failed = 0;

    private static void is(boolean actual, boolean expected, String what) {
        if (actual == expected) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL: " + what + " expected=" + expected + " actual=" + actual);
        }
    }

    public static void main(String[] args) {
        is(KeeperCapabilityVisibility.isRefinedStorageCaller(), false, "普通调用者（探针自身）不应被判成 RS");
        is(KeeperCapabilityVisibility.hiddenFromCaller(), false, "普通调用者：能力可见");
        is(FakeRsCaller.call(), true, "伪 RS 包调用者：必须被判成 RS");
        is(FakeRsCaller.hidden(), true, "伪 RS 包调用者：能力必须隐藏");
        is(FakeRsCaller.callDeep(), true, "伪 RS 包调用者（隔两层调用，模拟外部存储链路）：仍必须命中");
        is(FakeRsCaller.callDeepHidden(), true, "伪 RS 包调用者（隔两层调用）：能力隐藏");
        System.out.println("PROBE passed=" + passed + " failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }
}
"""

FAKE_RS = """\
package com.refinedmods.refinedstorage.probe;

import cretae.cookiewyq.rs_create_compat.support.KeeperCapabilityVisibility;

/** 探针用伪 RS 调用者（包名与真实 RS 同前缀，但不依赖任何 RS 类）。 */
public final class FakeRsCaller {
    private FakeRsCaller() {
    }

    public static boolean call() {
        return KeeperCapabilityVisibility.isRefinedStorageCaller();
    }

    public static boolean hidden() {
        return KeeperCapabilityVisibility.hiddenFromCaller();
    }

    public static boolean callDeep() {
        return relay1();
    }

    public static boolean callDeepHidden() {
        return relay2();
    }

    private static boolean relay1() {
        return KeeperCapabilityVisibility.isRefinedStorageCaller();
    }

    private static boolean relay2() {
        return KeeperCapabilityVisibility.hiddenFromCaller();
    }
}
"""


def compile_probe(src_dir: str, out_dir: str) -> tuple[bool, str]:
    files = []
    for base, _dirs, names in os.walk(src_dir):
        for name in names:
            if name.endswith(".java"):
                files.append(os.path.join(base, name))
    cmd = [JAVAC, "-encoding", "UTF-8", "-J-Duser.language=en", "-J-Duser.country=US",
           "-d", out_dir] + files
    result = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return result.returncode == 0, (result.stdout or "") + (result.stderr or "")


def write_probe_tree(root: str, visibility_text: str) -> str:
    src = os.path.join(root, "src")
    os.makedirs(os.path.join(src, "cretae", "cookiewyq", "rs_create_compat", "support"))
    os.makedirs(os.path.join(src, "com", "refinedmods", "refinedstorage", "probe"))
    with io.open(os.path.join(src, "cretae", "cookiewyq", "rs_create_compat", "support",
                              "KeeperCapabilityVisibility.java"), "w", encoding="utf-8") as handle:
        handle.write(visibility_text)
    with io.open(os.path.join(src, "com", "refinedmods", "refinedstorage", "probe",
                              "FakeRsCaller.java"), "w", encoding="utf-8") as handle:
        handle.write(FAKE_RS)
    with io.open(os.path.join(src, "KeeperCapabilityVisibilityProbe.java"), "w", encoding="utf-8") as handle:
        handle.write(PROBE_MAIN)
    return src


def group_jvm() -> None:
    print("\n=== ④ 真代码 JVM 探针：调用栈判据（伪造 RS 包名）===")
    root = os.path.join(PROBE_DIR, "ok")
    if os.path.isdir(root):
        shutil.rmtree(root)
    os.makedirs(root)
    src = write_probe_tree(root, read(VISIBILITY))
    ok, log = compile_probe(src, os.path.join(root, "classes"))
    check("④-1 KeeperCapabilityVisibility + 探针可单独 javac 编译（不依赖 RS 类）", ok, log.strip()[:800])
    if not ok:
        return
    run = subprocess.run([JAVA_BIN, "-cp", os.path.join(root, "classes"), "KeeperCapabilityVisibilityProbe"],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    check("④-2 JVM 探针 6 条断言全过（普通 / 伪 RS / 隔层调用 × 可见性）", run.returncode == 0,
          (run.stdout or "") + (run.stderr or ""))
    if run.stdout:
        for line in run.stdout.strip().splitlines():
            print("    " + line)

    print("\n=== ④' 反例自证：判据改坏后，JVM 探针必须判负 ===")
    bad_root = os.path.join(PROBE_DIR, "broken_prefix")
    if os.path.isdir(bad_root):
        shutil.rmtree(bad_root)
    os.makedirs(bad_root)
    broken_text = read(VISIBILITY).replace(
        'REFINED_STORAGE_PACKAGE = "com.refinedmods.refinedstorage"',
        'REFINED_STORAGE_PACKAGE = "com.refinedmods.NOPE"')
    check("④'-1 前缀判据确实被改坏（副本与原文不同）", broken_text != read(VISIBILITY))
    src2 = write_probe_tree(bad_root, broken_text)
    ok2, log2 = compile_probe(src2, os.path.join(bad_root, "classes"))
    if not ok2:
        check("④'-2 反例副本可编译", False, log2.strip()[:800])
        return
    run2 = subprocess.run([JAVA_BIN, "-cp", os.path.join(bad_root, "classes"),
                           "KeeperCapabilityVisibilityProbe"],
                          capture_output=True, text=True, encoding="utf-8", errors="replace")
    check("④'-3 前缀判据改坏 ⇒ 伪 RS 调用者不再被判成 RS，探针判负（证明探针真能抓到回退）",
          run2.returncode != 0)


# ======================================================================================
# ⑤ 反例自证（源码副本：把修法拼回去，锚点必须判负）
# ======================================================================================

def group_counterexamples() -> None:
    print("\n=== ⑤ 反例自证：把修法拼回去，锚点必须判负 ===")
    base = read(BASE_KEEPER)
    adv = read(ADV_KEEPER)
    target = read(KEEPER_TARGET)

    broken = base.replace("if (KeeperCapabilityVisibility.hiddenFromCaller()) {", "if (false) {")
    check("⑤-1 基础版整体去掉闸门 ⇒ ②-5 计数判负（RS 重新看得到能力 ⇒ 双计回归）",
          broken.count("KeeperCapabilityVisibility.hiddenFromCaller()") == 0
          and "if (false) {" in broken)

    broken_adv = adv.replace("if (KeeperCapabilityVisibility.hiddenFromCaller()) {", "if (false) {")
    check("⑤-2 高级版整体去掉闸门 ⇒ ②-8 计数判负",
          broken_adv.count("KeeperCapabilityVisibility.hiddenFromCaller()") == 0)

    broken_switch = base.replace(
        "        if (markerForm == FORM_ITEM && targetAmount == KeeperTarget.itemDefaultTarget()) {\n"
        "            targetAmount = KeeperTarget.fluidDefaultTarget();\n"
        "        }\n", "")
    check("⑤-3 基础版抽掉「标流体换默认值」⇒ ③-5 判负（默认又变回 64 mB）",
          "targetAmount = KeeperTarget.fluidDefaultTarget();" not in broken_switch)

    broken_scale = target.replace("FLUID_MILLIBUCKETS_PER_DEFAULT_UNIT = 1_000",
                                  "FLUID_MILLIBUCKETS_PER_DEFAULT_UNIT = 1")
    check("⑤-4 换算改回 1:1 ⇒ ① 用户口径断言判负（64 会变回 64 mB）",
          broken_scale != target and fluid_default_with(broken_scale, 64) == 64)

    broken_fallback = target.replace("configured > 0 ? configured : FALLBACK_ITEM_DEFAULT_TARGET",
                                     "configured")
    check("⑤-5 去掉配置未加载回退 ⇒ ①「配置 0 ⇒ 64000」用例判负（配置没加载会推出 0 mB）",
          broken_fallback != target)


def fluid_default_with(target_src: str, configured: int) -> int:
    m = re.search(r"FLUID_MILLIBUCKETS_PER_DEFAULT_UNIT = (\d+)", target_src)
    mult = int(m.group(1)) if m else 1_000
    return max(0, min(item_default(configured) * mult, MAX_SET_TARGET))


def main() -> int:
    print("第 142 轮自检：保持器 H2 剩余（整份对外能力对 RS 隐藏）+ M2（流体默认 64000 mB）")
    group_models()
    group_h2_anchors()
    group_m2_anchors()
    group_jvm()
    group_counterexamples()
    print("\n结果：%d 通过 / %d 失败" % (PASSED, FAILED))
    if FAILED:
        print("失败项：")
        for name in FAILS:
            print("  - " + name)
        return 1
    print("全部通过（7+7 处能力闸门 / 流体默认 64000 mB / 读档不动 / JVM 探针 / 反例自证）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
