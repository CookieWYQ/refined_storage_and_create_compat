#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 55 轮：**序列装配样板库里的总样板常显「它合成什么」**（用户第 11 项 / 任务 B）的自检。

用户原话：<i>「位于序列（装配）样板库的这些总的样板应该时刻保持按下 shift 时候的样子，
就是和那个精致存储它的自动合成仓一样 —— 那里面的那些样板默认都是显示他们具体（合成）什么物品，
也就是按下 shift 之后的状态。」</i>

**先定位事实（本脚本用 RS 制品自身当证据，不靠猜）**：
    * `PatternRendering#canDisplayOutput` 里就有那条 Shift 分支（`Screen.hasShiftDown()`），
      与「当前界面实现 `PatternOutputRenderingScreen` 且它自己返回真」并列；
    * RS 的 `neoforge/mixin/AbstractGuiGraphicsMixin#renderItem` 在 `GuiGraphics#renderItem`
      的 HEAD 处拦一刀：能把产物显示出来时，就用<b>同一组 x/y/seed/guiOffset</b> 把产物画出来
      —— 所以「常显产物」根本不改 tooltip，也不会多占一个像素；
    * RS 自动合成仓 `AutocrafterScreen#canDisplayOutput` 恒返回 `getMenu().containsPattern(stack)`
      —— 这正是用户看到的「默认就显示合成什么」。
    因此本模组的做法 = 给样板库界面**实现同一个官方接口**并复用 RS 的实例判定（照抄
    `AutocrafterContainerMenu#containsPattern`），**一行绘制都不加**。

断言（任一 FAIL 即退出码 1）：
    ① 样板库界面实现 `PatternOutputRenderingScreen`，`canDisplayOutput` 委托给菜单的
       `containsPattern(stack)`（不在界面里另写判据）；
    ② 菜单判定是「本库槽位 + 同一实例」：public 只读、`slot instanceof PatternSlot`、
       `slot.getItem() == stack`；**不得**退化成按物品类型 / NBT 相等（那会污染背包与 JEI）；
    ③ **仅该界面**：全工程只有这一个类 `implements PatternOutputRenderingScreen`；
    ④ **其它位置不受影响**：没有任何工程代码自己引用 RS 的 `PatternRendering`（不复制钩子）；
       共用的 tooltip 构建器（`SequenceAssemblyPatternItem`）与分层实现（`RsccTooltipLayers`）
       都没有为这一处改动；本界面代码里不出现新的 Shift 读取；
    ⑤ **内容未溢出（几何 / 行数锚点）**：本界面新增绘制调用 = 0（背景 1 次 blit、标题 1 次
       drawString、tooltip 1 次 renderTooltip）；54 格仍是 18px 网格、每格最多 1 张
       （槽内物品自身不画数量角标）；本界面不新增任何文字行。

用法：python tools/selfcheck_round55_library_shift_state.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import glob
import io
import os
import re
import sys
import zipfile

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA_ROOT = os.path.join(ROOT, "src", "main", "java")
PKG = "cretae.cookiewyq.rs_create_compat"
PKG_PATH = PKG.replace(".", "/")

SCREEN = PKG_PATH + "/client/screen/SequenceAssemblyExecutorScreen.java"
MENU = PKG_PATH + "/menu/SequenceAssemblyExecutorMenu.java"
PATTERN_ITEM = PKG_PATH + "/item/SequenceAssemblyPatternItem.java"
TOOLTIP_LAYERS = PKG_PATH + "/client/tooltip/RsccTooltipLayers.java"

# 缓存根与「同族多版本取哪一个」统一交给 tools/_gradle_cache.py（跟随 GRADLE_USER_HOME，
# 按 gradle.properties 的 pin 精确选版本）。原先这里直接写死 ~/.gradle/caches 下的
# RS 目录，再 glob 全量扫它并取 bins[0]：缓存里并存 RS 2.0.0 与 2.0.9 时，
# 取到哪一版取决于目录枚举顺序 ⇒「用 RS 制品当证据」的断言结果不确定。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import _gradle_cache as gc  # noqa: E402  （必须在 sys.path 调整之后再导入）

FAILURES = []
CHECKS = [0]


def check(name, ok, detail="", hard=True):
    CHECKS[0] += 1
    if not ok and hard:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else ("FAIL" if hard else "INFO"), name,
                       (" | " + detail) if (detail and not ok) else ""))


def strip_comments_and_strings(text):
    out = []
    i = 0
    n = len(text)
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif c == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:j]))
            i = j
        elif c == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"' or text[j] == "\n":
                    j += 1
                    break
                j += 1
            out.append("".join("\n" if ch == "\n" else " " for ch in text[i:j]))
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


class Source:
    def __init__(self, rel, text):
        self.rel = rel
        self.text = text
        self.code = strip_comments_and_strings(text)


def load_sources():
    sources = {}
    for dirpath, _dirs, files in os.walk(JAVA_ROOT):
        for name in files:
            if not name.endswith(".java"):
                continue
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, JAVA_ROOT).replace(os.sep, "/")
            with io.open(full, encoding="utf-8") as handle:
                sources[rel] = Source(rel, handle.read())
    return sources


def method_body(code, signature_re):
    m = re.search(signature_re, code)
    if not m:
        return None
    brace = code.find("{", m.end() - 1)
    if brace < 0:
        return None
    depth = 0
    i = brace
    while i < len(code):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return code[m.start():i + 1]
        i += 1
    return None


def rs_artifacts():
    """返回 (二进制制品列表, sources 制品列表)：找不到就返回空（对应断言降级成 INFO）。

    只返回 **gradle.properties pin 的那一版**：缓存里可能并存多个 RS 版本（2.0.0 / 2.0.9），
    全部返回或取 glob 第一个都会让「拿 RS 制品当证据」的断言结果不可复现。
    """
    bin_jar, bin_notes = gc.find_jar("com.refinedmods.refinedstorage", "refinedstorage-neoforge")
    src_jar, src_notes = gc.find_jar("com.refinedmods.refinedstorage", "refinedstorage-neoforge",
                                     kind="sources")
    for line in bin_notes + src_notes:
        if "[警告]" in line or "[提示]" in line:
            print("  " + line.strip())
    return ([bin_jar] if bin_jar else []), ([src_jar] if src_jar else [])


def main():
    sources = load_sources()
    for rel in (SCREEN, MENU):
        check("① 源文件存在：%s" % os.path.basename(rel), rel in sources)

    screen = sources.get(SCREEN)
    menu = sources.get(MENU)
    if screen is None or menu is None:
        return report()

    # ---------------- ① 用 RS 官方扩展点，判据只有一份 ----------------
    check("①a 样板库界面 implements PatternOutputRenderingScreen（RS 官方扩展点）",
          re.search(r"implements[^{]*PatternOutputRenderingScreen", screen.code) is not None)
    can_display = method_body(screen.code, r"public\s+boolean\s+canDisplayOutput\s*\(")
    check("①b 界面覆写 canDisplayOutput(ItemStack)", can_display is not None)
    if can_display is not None:
        check("①c 界面把判据委托给菜单（menu.containsPattern(stack)），不自己另写一份",
              re.search(r"menu\.containsPattern\s*\(\s*stack\s*\)", can_display) is not None)
        check("①d 界面里不出现按物品类型 / NBT 的自判（不复制 RS 的语义）",
              not re.search(r"isAssemblyPattern|isSameItemSameComponents|areItemStacksEqual", can_display))

    # ---------------- ② 菜单判定 = 本库槽位 + 同一实例 ----------------
    contains = method_body(menu.code, r"public\s+boolean\s+containsPattern\s*\(")
    check("②a 菜单提供 public 只读 containsPattern(ItemStack)", contains is not None)
    if contains is not None:
        check("②b 范围限定在样板槽（slot instanceof PatternSlot）",
              re.search(r"instanceof\s+PatternSlot", contains) is not None)
        check("②c 按实例比对（slot.getItem() == stack），与 RS 的 containsPattern 同口径",
              re.search(r"getItem\s*\(\s*\)\s*==\s*stack", contains) is not None)
        check("②d 不按物品类型 / NBT 相等（否则背包与 JEI 里同内容的样板也会被改）",
              not re.search(r"isAssemblyPattern|isSameItemSameComponents|areItemStacksEqual", contains))
        check("②e 空栈先短路（绝不 NPE）",
              re.search(r"stack\s*==\s*null\s*\|\|\s*stack\.isEmpty\(\)", contains) is not None)

    # ---------------- ③ 仅该界面实现（别处一行都不变） ----------------
    implementors = []
    for rel, src in sources.items():
        if re.search(r"implements[^{]*PatternOutputRenderingScreen", src.code):
            implementors.append(rel)
    check("③ 全工程只有样板库界面 implements PatternOutputRenderingScreen（1 个）",
          implementors == [SCREEN], str(implementors))

    # ---------------- ④ 其它位置不受影响 ----------------
    hand_rolled = [rel for rel, src in sources.items()
                   if re.search(r"\bPatternRendering\b", src.code)]
    check("④a 没有任何工程代码自己引用 RS 的 PatternRendering（不复制渲染钩子）",
          not hand_rolled, "; ".join(hand_rolled))
    overrides = []
    for rel, src in sources.items():
        if re.search(r"boolean\s+canDisplayOutput\s*\(", src.code):
            overrides.append(rel)
    check("④b canDisplayOutput 只出现在样板库界面（%d 处）" % len(overrides),
          overrides == [SCREEN], str(overrides))

    item = sources.get(PATTERN_ITEM)
    if item is None:
        check("④c 找到共用样板 tooltip 构建器 SequenceAssemblyPatternItem", False)
    else:
        hover = method_body(item.code, r"public\s+void\s+appendHoverText\s*\(")
        check("④c 共用 tooltip 构建器没有被改成常显（仍走 RsccTooltipLayers.append）",
              hover is not None
              and re.search(r"RsccTooltipLayers\.append\s*\(\s*tooltip\s*,\s*layered\s*\)", hover) is not None
              and "PatternOutputRenderingScreen" not in item.code)
    layers = sources.get(TOOLTIP_LAYERS)
    if layers is None:
        check("④d 找到分层实现 RsccTooltipLayers", False)
    else:
        check("④d 分层实现里没有为这一处新增常显 / 强制展开开关",
              "PatternOutputRenderingScreen" not in layers.code
              and not re.search(r"\bforce\s*\(", layers.code)
              and "SequenceAssemblyExecutor" not in layers.code)
    check("④e 样板库界面没有再读一次 Shift（常显与按键无关）",
          "hasShiftDown" not in screen.code)

    # ---------------- ⑤ 未溢出：绘制调用 / 几何 / 行数锚点 ----------------
    blits = len(re.findall(r"\.blit\s*\(", screen.code))
    draws = len(re.findall(r"\.drawString\s*\(", screen.code))
    tooltips = len(re.findall(r"renderTooltip\s*\(", screen.code))
    check("⑤a 本界面绘制调用与改动前逐字相同（blit 1 / drawString 1 / renderTooltip 1），"
          "常显产物由 RS 画在同一槽位、本类一次都没加",
          blits == 1 and draws == 1 and tooltips == 1,
          "blit=%d drawString=%d renderTooltip=%d" % (blits, draws, tooltips))
    check("⑤b 本界面不新增任何文字行（没有 Component.translatable）",
          "Component.translatable" not in screen.code)
    check("⑤c 54 格仍是 18px 网格（8 + col*18 / 18 + row*18）",
          re.search(r"8\s*\+\s*col\s*\*\s*18", menu.code) is not None
          and re.search(r"18\s*\+\s*row\s*\*\s*18", menu.code) is not None)
    check("⑤d 样板库容量 54 格且 PLAYER_START = PATTERN_SLOTS（槽位区间未被改动）",
          re.search(r"PATTERN_SLOTS\s*=\s*SequenceAssemblyExecutorBlockEntity\.PATTERN_SLOTS", menu.code)
          is not None
          and re.search(r"PLAYER_START\s*=\s*PATTERN_SLOTS", menu.code) is not None)
    check("⑤e 每格最多 1 张（槽内物品自身不画数量角标）",
          re.search(r"getMaxStackSize\s*\(\s*\)\s*\{\s*return\s+1\s*;", menu.code) is not None)

    # ---------------- ⑥ RS 官方依据（用制品自身当证据；缺制品则降级为 INFO） ----------------
    bins, srcs = rs_artifacts()
    if not bins:
        check("⑥ 找到 RS 制品（跟随 GRADLE_USER_HOME 的缓存）；找不到时本节只作 INFO", True, "", hard=False)
    else:
        with zipfile.ZipFile(bins[0]) as jar:
            names = set(jar.namelist())
            rendering = jar.read("com/refinedmods/refinedstorage/common/autocrafting/PatternRendering.class") \
                if "com/refinedmods/refinedstorage/common/autocrafting/PatternRendering.class" in names else b""
        check("⑥a RS 制品里有 PatternOutputRenderingScreen（我们实现的接口）",
              "com/refinedmods/refinedstorage/common/api/autocrafting/PatternOutputRenderingScreen.class"
              in names)
        check("⑥b RS 制品里有 GuiGraphics 钩子 AbstractGuiGraphicsMixin（产物就是被它画出来的）",
              "com/refinedmods/refinedstorage/neoforge/mixin/AbstractGuiGraphicsMixin.class" in names)
        check("⑥c RS 的 PatternRendering 字节码里确有 Shift 分支（Screen.hasShiftDown）",
              b"hasShiftDown" in rendering)
    if not srcs:
        check("⑥d 找到 RS sources 制品；找不到时下面两条只作 INFO", True, "", hard=False)
    else:
        with zipfile.ZipFile(srcs[0]) as jar:
            text = jar.read("com/refinedmods/refinedstorage/common/autocrafting/PatternRendering.java") \
                .decode("utf-8", "replace")
            hook = jar.read("com/refinedmods/refinedstorage/neoforge/mixin/AbstractGuiGraphicsMixin.java") \
                .decode("utf-8", "replace")
            autocrafter = jar.read("com/refinedmods/refinedstorage/common/autocrafting/autocrafter/AutocrafterScreen.java") \
                .decode("utf-8", "replace")
        check("⑥d PatternRendering#canDisplayOutput 的 Shift 分支 = Screen.hasShiftDown()",
              "Screen.hasShiftDown()" in text and "PatternOutputRenderingScreen" in text)
        check("⑥e RS 钩子用同一组 x/y/seed/guiOffset 画产物（因此不会溢出槽位）",
              re.search(r"self\.renderItem\(entity,\s*level,\s*output,\s*x,\s*y,\s*seed,\s*guiOffset\)",
                        hook) is not None)
        check("⑥f RS 自动合成仓常显产物 = getMenu().containsPattern(stack)（我们照抄的判据）",
              re.search(r"canDisplayOutput\(final ItemStack stack\)\s*\{\s*return getMenu\(\)\.containsPattern\(stack\)",
                        autocrafter) is not None)

    return report()


def report():
    print("")
    if FAILURES:
        print("[FAIL] 共 %d 项断言失败（检查 %d 项）：" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("   - %s" % item)
        return 1
    print("[OK] 样板库总样板常显产物：%d 项断言全部通过" % CHECKS[0])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
