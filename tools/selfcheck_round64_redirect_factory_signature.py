#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
round64 自检：@Redirect 工厂 handler 的签名一致性（返回类型必须等于被构造/被调用的目标类型）。

背景（为什么会有这个自检）
------------------------
1.1.0 让**专用服务端启动即崩**：AutocrafterManagerSlotMixin 把 @At 的 NEW 目标从
`net.minecraft.world.inventory.Slot` 改成 `...autocrafting.PatternSlot`（正确），却没有同步
把 handler 的返回类型改掉，仍是 `Slot`。Mixin 在**模组加载期**抛：

    InvalidInjectionException: @Redirect factory method ...::rscc$patternOnlyManagerServerSlot
    has an invalid signature. Found unexpected return type net.minecraft.world.inventory.Slot,
    expected com.refinedmods.refinedstorage.common.autocrafting.PatternSlot.

而当时的 `tools/verify_mixin_shadows.py` 只校验「被注入的目标方法是否存在」，
**完全没看 handler 自己的签名** ⇒ 它对这个必崩的代码报「0 问题」（假绿）。

本自检做四件事（全部离线，不需要启动游戏、不需要 gradle）：
  A.【反例自证】用崩溃日志里那一对签名（Slot vs PatternSlot）构造检查，证明：
       旧检查器（只查目标方法存在）→ **放行**
       新检查器（校验返回类型/形参/@At 字节码）→ **判负**
     反例不是手写字符串，而是**由当前源码现场还原**（加回 Slot import、把 handler 返回类型与
     被构造类型改回 Slot），因此复现的就是 1.1.0 真正发出去的那段代码。
  B.【全工程扫描】所有 @Redirect 都过新规则，并强制「≥1 处真的被深入校验」（防空跑假绿）。
  C.【javap 字节码核对】反汇编 RS 2.0.9 的
     AutocrafterManagerContainerMenu#addServerSideSlots，取出真实 `new PatternSlot` 调用点的
     构造器描述符，确认 handler 形参顺序与之一致；再确认 handler 返回类型 == PatternSlot。
  D.【通用性】对 INVOKE / GETSTATIC / PUTFIELD 等模式各构造一正一反两例，
     证明规则是通用的，不是对某一个 mixin 的特判。

退出码：0 = 全部通过；1 = 有问题。
"""

from __future__ import annotations

import os
import re
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import verify_mixin_shadows as V  # noqa: E402  （复用被测校验器本身，避免两份实现走偏）
import _gradle_cache as gc  # noqa: E402

MIXIN_REL = os.path.join("src", "main", "java", "cretae", "cookiewyq",
                         "rs_create_compat", "mixin", "AutocrafterManagerSlotMixin.java")
MIXIN_PATH = os.path.join(ROOT, MIXIN_REL)
MENU_BIN = ("com.refinedmods.refinedstorage.common.autocrafting.autocraftermanager."
            "AutocrafterManagerContainerMenu")
PATTERN_SLOT_BIN = "com.refinedmods.refinedstorage.common.autocrafting.PatternSlot"
PATTERN_SLOT_DESC = "Lcom/refinedmods/refinedstorage/common/autocrafting/PatternSlot;"
SLOT_DESC = "Lnet/minecraft/world/inventory/Slot;"

FAILS = []
NOTES = []


def check(cond, ok_msg, bad_msg):
    if cond:
        print("  [OK]   %s" % ok_msg)
    else:
        print("  [FAIL] %s" % bad_msg)
        FAILS.append(bad_msg)
    return bool(cond)


def find_redirect(src, injections):
    reds = [i for i in injections if i.annotation == "Redirect"]
    return reds


def build_index():
    """与 verify_mixin_shadows.py 完全相同的 classpath 判据（跟随 GRADLE_USER_HOME + pin 版本）。"""
    cache, source = gc.cache_root()
    mc_jar, mc_notes = V.find_mc_jar(cache)
    rs_jar, rs_notes = V.find_rs_jar(cache)
    create_jar, create_notes = V.find_create_jar(cache)
    cp = os.pathsep.join([mc_jar, rs_jar, create_jar])
    print("  Gradle 缓存 : %s  [%s]" % (cache, source))
    for coord, (ver, why) in sorted(gc.pinned_versions().items()):
        print("  pin         : %s = %s  (%s)" % (coord, ver, why))
    for line in (mc_notes + rs_notes + create_notes):
        print("  " + line.strip())
    print("  RS jar      : %s" % rs_jar)
    return V.JarIndex(cp), rs_jar


def reconstruct_broken_source(text):
    """
    由「已修好的源码」现场还原 1.1.0 真正发出去的那段崩溃代码：
      * 加回 `import net.minecraft.world.inventory.Slot;`
      * handler 返回类型 PatternSlot → Slot
      * 被构造类型 `new PatternSlot(container, index, x, y, level)` → `new Slot(container, index, x, y)`
    @At 的 target 保持 PatternSlot 不变（崩溃就是这么来的）。
    三处替换都要求**恰好命中一次**，否则直接失败，避免反例被改坏后静默变成别的检查。
    """
    replacements = [
        ("import com.refinedmods.refinedstorage.common.autocrafting.PatternSlot;",
         "import com.refinedmods.refinedstorage.common.autocrafting.PatternSlot;\n"
         "import net.minecraft.world.inventory.Slot;"),
        ("private static PatternSlot rscc$patternOnlyManagerServerSlot(",
         "private static Slot rscc$patternOnlyManagerServerSlot("),
        ("return new PatternSlot(container, index, x, y, level) {",
         "return new Slot(container, index, x, y) {"),
    ]
    out = text
    for old, new in replacements:
        n = out.count(old)
        if n != 1:
            raise AssertionError("反例还原失败：期望命中 1 次，实际 %d 次 → %r" % (n, old))
        out = out.replace(old, new, 1)
    return out


def main():
    print("=" * 100)
    print("round64 自检：@Redirect 工厂 handler 签名一致（返回类型 == 被构造/被调用目标类型）")
    print("=" * 100)

    if not os.path.isfile(MIXIN_PATH):
        print("[FATAL] 找不到 %s" % MIXIN_PATH)
        return 1
    index, rs_jar = build_index()

    # ---------------------------------------------------------------- 1. 现状
    print("")
    print("[1] 解析当前 mixin，取出 @Redirect 与 handler 签名")
    live_text = open(MIXIN_PATH, encoding="utf-8").read()
    src, _shadows, live_injs = V.parse_source(MIXIN_PATH)
    reds = find_redirect(src, live_injs)
    if not check(len(reds) == 1, "找到 1 个 @Redirect（AutocrafterManagerSlotMixin）",
                 "期望恰好 1 个 @Redirect，实际 %d 个" % len(reds)):
        return 1
    inj = reds[0]
    print("      @At value   : %s" % inj.at_value)
    print("      @At target  : %s" % inj.at_target)
    print("      handler     : %s %s(%s)"
          % (inj.handler_ret_src, inj.handler_name, inj.handler_params_src))
    print("      handler 描述符: (%s)%s" % ("".join(inj.handler_params or []), inj.handler_ret))
    check((inj.at_value or "").upper() == "NEW",
          "@At(value) 解析为 NEW（旧实现只读位置参数，这里恒为 None ⇒ 等于没校验）",
          "@At(value) 解析失败：%r" % inj.at_value)
    check((inj.at_target or "") == PATTERN_SLOT_DESC,
          "@At(target) 解析为 %s" % PATTERN_SLOT_DESC,
          "@At(target) 解析异常：%r" % inj.at_target)
    check(inj.handler_ret == PATTERN_SLOT_DESC,
          "handler 返回类型 = %s（与 NEW 目标精确相等）" % PATTERN_SLOT_DESC,
          "handler 返回类型 %r != NEW 目标 %r（这正是 1.1.0 崩启动的原因）"
          % (inj.handler_ret, PATTERN_SLOT_DESC))

    # ------------------------------------------- 2. 修复后的源码：新旧检查器一致通过
    print("")
    print("[2] 修复后的源码")
    out_old, out_new = [], []
    iso_old = V.check_injection(inj, src.target, index, out_old)
    iso_new = V.check_redirect_handler(inj, src.target, index, out_new)
    for line in out_old + out_new:
        print("      " + line)
    check(iso_old == 0, "旧规则（只查目标方法存在）：0 问题", "旧规则对已修代码报 %d 问题" % iso_old)
    check(iso_new == 0, "新规则（返回类型/形参/@At 字节码）：0 问题",
          "新规则对已修代码报 %d 问题（误报！）" % iso_new)

    # ------------------------------------------- 3. 反例自证：旧放行 / 新判负
    print("")
    print("[3] 反例自证：由当前源码现场还原 1.1.0 的崩溃代码（handler 返回 Slot）")
    broken_text = reconstruct_broken_source(live_text)
    tmp_dir = os.path.join(ROOT, "build", "round64_counterexample")
    os.makedirs(tmp_dir, exist_ok=True)
    broken_path = os.path.join(tmp_dir, "AutocrafterManagerSlotMixin_BROKEN_1_1_0.java")
    with open(broken_path, "w", encoding="utf-8") as fh:
        fh.write(broken_text)
    print("      反例源码：%s" % broken_path)

    _bsrc, _bshadows, binjs = V.parse_source(broken_path)
    breds = find_redirect(_bsrc, binjs)
    if not check(len(breds) == 1, "反例解析出 1 个 @Redirect", "反例解析出 %d 个 @Redirect" % len(breds)):
        return 1
    binj = breds[0]
    print("      反例 handler 返回类型 : %s" % binj.handler_ret)
    print("      反例 @At target       : %s" % binj.at_target)
    check(binj.handler_ret == SLOT_DESC,
          "反例 handler 返回类型已还原为 %s" % SLOT_DESC,
          "反例 handler 返回类型不是 Slot：%r" % binj.handler_ret)

    bout_old, bout_new = [], []
    biso_old = V.check_injection(binj, _bsrc.target, index, bout_old)
    biso_new = V.check_redirect_handler(binj, _bsrc.target, index, bout_new)
    print("      —— 旧检查器输出 ——")
    for line in bout_old:
        print("      " + line)
    print("      —— 新检查器输出 ——")
    for line in bout_new:
        print("      " + line)
    check(biso_old == 0,
          "旧检查器【放行】崩溃代码（0 问题）—— 与 1.1.0 实机日志一致（这就是盲区）",
          "旧检查器居然报了 %d 问题，反例不成立" % biso_old)
    check(biso_new > 0,
          "新检查器【判负】崩溃代码（%d 问题）" % biso_new,
          "新检查器仍然放行了崩溃代码（0 问题）—— 修复无效")
    new_text = "\n".join(bout_new)
    check(("返回类型不符" in new_text) and (SLOT_DESC in new_text) and (PATTERN_SLOT_DESC in new_text),
          "新检查器明确指出「返回类型不符：%s vs %s」" % (SLOT_DESC, PATTERN_SLOT_DESC),
          "新检查器没有指出返回类型不符（输出里缺少关键词）")

    # ------------------------------------------- 4. javap 字节码核对
    print("")
    print("[4] javap 字节码核对：真实 new PatternSlot 调用点的构造器描述符 vs handler 形参")
    proc = subprocess.run([V.JAVAP, "-p", "-c", "-cp", rs_jar, MENU_BIN],
                          capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=300)
    dis = proc.stdout or ""
    m = re.search(r'PatternSlot\."<init>":(\([^)]*\))V', dis)
    if not check(m is not None, "在 addServerSideSlots 的字节码里找到 PatternSlot.<init> 调用点",
                 "javap 输出里找不到 PatternSlot.<init> 调用点（RS jar 变了？）"):
        return 1
    ctor_params = V.split_descriptor_params(m.group(1))
    print("      真实构造器描述符 : %sV" % m.group(1))
    print("      真实构造器形参   : %s" % V._fmt_params(ctor_params))
    print("      handler 形参     : %s" % V._fmt_params(inj.handler_params))
    check(ctor_params == list(inj.handler_params),
          "handler 形参顺序/类型 == 真实字节码里构造器压栈的形参顺序",
          "handler 形参 %s != 真实构造器形参 %s" % (V._fmt_params(inj.handler_params),
                                                  V._fmt_params(ctor_params)))
    # 构造器必须也能从 PatternSlot 自身字节码里读到（同一个类，供规则 a 使用）
    ps = index.get(PATTERN_SLOT_BIN)
    ctors = [md for mn, md, _st in (ps.methods if ps else []) if mn == "<init>"]
    check(ctor_params and ("(" + "".join(ctor_params) + ")V") in ctors,
          "PatternSlot 真实构造器集合包含 %sV" % m.group(1),
          "PatternSlot 里没有这个构造器：%s" % ctors)

    # ------------------------------------------- 5. 通用性（非特判）
    print("")
    print("[5] 通用性：同一套规则对 INVOKE / GETSTATIC / PUTFIELD 各来一正一反")
    ITEMSTACK = "Lnet/minecraft/world/item/ItemStack;"
    CONTAINER = "Lnet/minecraft/world/Container;"
    cases = [
        ("INVOKE 实例方法（含 owner 前置）",
         dict(at_value="INVOKE", at_target="Lnet/minecraft/world/Container;getItem(I)" + ITEMSTACK,
              handler_ret=ITEMSTACK, handler_params=[CONTAINER, "I"]),
         dict(real={"invoke_static": False}, positive=0,
              negative=dict(handler_ret="V"))),
        ("INVOKE 静态方法（无 owner 前置）",
         dict(at_value="INVOKE", at_target="Lnet/minecraft/world/item/ItemStack;isEmpty(" + ITEMSTACK + ")Z",
              handler_ret="Z", handler_params=[ITEMSTACK]),
         dict(real={"invoke_static": True}, positive=0, negative=dict(handler_ret=ITEMSTACK))),
        ("GETSTATIC",
         dict(at_value="GETSTATIC", at_target="Lnet/minecraft/world/item/Items;AIR:" + ITEMSTACK,
              handler_ret=ITEMSTACK, handler_params=[]),
         dict(real={"field_static": True}, positive=0, negative=dict(handler_ret="V"))),
        ("PUTFIELD",
         dict(at_value="PUTFIELD", at_target="Lnet/minecraft/world/Container;x:I",
              handler_ret="V", handler_params=[CONTAINER, "I"]),
         dict(real={"field_static": False}, positive=0, negative=dict(handler_ret="I"))),
    ]
    for title, ok_case, extra in cases:
        p_ok = V.validate_redirect_signature(ok_case["at_value"], ok_case["at_target"],
                                             ok_case["handler_ret"], ok_case["handler_params"],
                                             None, extra["real"])
        check(len(p_ok) == 0, "%s：正确签名通过" % title,
              "%s：正确签名被误报 %s" % (title, p_ok))
        bad = dict(ok_case)
        bad.update(extra["negative"])
        p_bad = V.validate_redirect_signature(bad["at_value"], bad["at_target"],
                                              bad["handler_ret"], bad["handler_params"],
                                              None, extra["real"])
        check(len(p_bad) > 0, "%s：返回类型写错被判负" % title,
              "%s：返回类型写错却未被判负" % title)

    # ------------------------------------------- 6. 全工程扫描
    print("")
    print("[6] 全工程扫描：所有 mixin 的 @Redirect 都过新规则")
    n_red = n_checked = n_issues = 0
    for path in sorted(_iter_mixins()):
        msrc, _sh, minjs = V.parse_source(path)
        for minj in minjs:
            if minj.annotation != "Redirect":
                continue
            n_red += 1
            if (minj.at_value or "").upper() in V.REDIRECT_CHECKED_MODES:
                n_checked += 1
            _o = []
            n_issues += V.check_redirect_handler(minj, msrc.target, index, _o)
            for line in _o:
                print("      %s | %s" % (msrc.rel_name(), line))
    check(n_red >= 1, "全工程共有 %d 处 @Redirect" % n_red, "全工程没扫到任何 @Redirect（扫描失效？）")
    check(n_checked == n_red, "全部 %d 处都按返回类型/形参/@At 字节码深入校验过（无跳过）" % n_checked,
          "有 %d 处 @Redirect 没被深入校验（@At 解析退化）" % (n_red - n_checked))
    check(n_issues == 0, "全工程 @Redirect 签名问题数：0", "全工程 @Redirect 签名问题数：%d" % n_issues)

    print("")
    print("=" * 100)
    if FAILS:
        print("round64 自检失败：%d 项" % len(FAILS))
        for f in FAILS:
            print("  - %s" % f)
        print("退出码：1")
        return 1
    print("round64 自检通过：@Redirect handler 返回类型/形参/@At 字节码三条规则全部成立，"
          "且反例自证「旧放行 / 新判负」成立")
    print("退出码：0")
    return 0


def _iter_mixins():
    for dirpath, _dirnames, filenames in os.walk(V.MIXIN_DIR):
        for fn in filenames:
            if fn.endswith(".java"):
                yield os.path.join(dirpath, fn)


if __name__ == "__main__":
    sys.exit(main())
