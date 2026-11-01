#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 108 轮-A 自检：所有「纯电」GUI 能量条统一复用 RS 原版 ProgressWidget 那一套。

用户口径（第 108 条原话要点）
------------------------------
> 「你的那个能量条，就是所有那种纯电的能量条，比如说 FE 能量立方方块的、以及发电器的能量条，
> 全都不符合我的要求。你应该保持和那个范围充电器、以及那个控制器（精妙存储自己带的那个）一样。
> 你应该直接复用他们的代码，而不是自己画一坨那么丑的狗屎。」

参考实现（已反编译 RS 2.0.9 sources jar 确认，出处写进 `RsccEnergyBar` 的 javadoc）
------------------------------------------------------------------------------
* `com.refinedmods.refinedstorage.common.support.widget.ProgressWidget`
  —— sprite = `refinedstorage:widget/progress_bar`（16×70 PNG，横向 3 段分层 + 1px 纵向纹理）。
  它<b>只画亮起的部分</b>，空条区/外框由调用方的背景提供。
* 本模组 `RangeChargerScreen` 早就在用它（16×51 @ (26,20) + 自绘 18×53 黑框 + #2C2C2C 槽底）。
  第 108 轮把这套抽成 `client/widget/RsccEnergyBar`，三家共用。

断言清单
--------
① `RsccEnergyBar` 就是参考实现本身：`new ProgressWidget(x, y, WIDTH, height, ...)`、
   `WIDTH = 16`、`DEFAULT_HEIGHT = 51`、`COLOR_FRAME = 0xFF000000`、`COLOR_TRACK = 0xFF2C2C2C`、
   drawTrack 的两条 fill 与范围充电器逐像素同源、`ratio` 含 0 除/夹紧、
   `valueLine` 走 `RsccNumberField.grouped` + `" FE"`；**不新增任何贴图**（无 ResourceLocation / blit）。
② **参考实现自己必须走共享类**（防漂移）：`RangeChargerScreen` 里不得再出现 `new ProgressWidget(`
   或帧色字面量，必须调 `RsccEnergyBar.create/drawTrack/valueLine`。
③ FE 能量立方：几何 16×58 @ (14,19)（外框 18×60 @ (13,18) 整条盖住贴图旧 14×60 凹槽）、
   走共享类、**界面内零自绘**（无 drawEnergyBar / BAR_FILL / filledPixels / guiGraphics.fill /
   旧 RS 能量色字面量）、读数板 tooltip 先让位给条（同一帧不许两个框）。
④ 分解发电机（「发电器」）：**全界面只有一条能量条**（第 123 轮彻底重做）——
   恰好一次 `RsccEnergyBar.create` + 一次 `RsccEnergyBar.drawTrack`、几何取自
   `DecompositionGeneratorSpec`（ENERGY_X / ENERGY_Y / ENERGY_HEIGHT）、
   旧的 `COLOR_ENERGY`（0xFFE0B32A 琥珀平涂）与 `drawVerticalBar` 已随「进度条改熔炉箭头」一起删除、
   界面**零 `fill`**、悬浮句沿用本机既有 lang 键（「缓存：x / y FE」）且数字走 `grouped`
   （既有键不变成孤儿键）、`drawArrowTooltip` 里不再手画能量读数
   （否则 widget 的 tooltip + 手画的 tooltip 会叠）。
⑤ 飞行背包 HUD 的横向条**不纳入统一**（Lead 裁决 ⑤）：它不是 GUI 屏幕、无 hover/tooltip 语义、
   sprite 是竖向 16×70，硬套会变形；本脚本钉死「HUD 不得被改去调 RsccEnergyBar」。
⑥ 共享类不得被 network / support 的**代码**引用（纯客户端渲染；javadoc 里 `{@link RsccEnergyBar}`
   只是文档，不算调用 —— 第 123 轮 `DecompositionGeneratorSpec` 的注释里提到过类名，
   所以判据必须先剥注释再匹配，而不是「整份文件里出现这个词就判负」），
   且 `textures/gui/` 下不得新增 `*energy_bar*` / `*progress_bar*` 贴图
   （不新增自绘贴图、不复制 RS 的 PNG）。
⑦ 反例自证（11 条）：把「旧写法 / 错写法」拼进源码副本，断言判据真的判负。

用法：python tools/selfcheck_round108_energy_bar_unified.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
HELPER = os.path.join(JAVA, "client", "widget", "RsccEnergyBar.java")
CHARGER = os.path.join(JAVA, "client", "screen", "RangeChargerScreen.java")
CUBE = os.path.join(JAVA, "client", "screen", "FEEnergyCubeScreen.java")
GEN = os.path.join(JAVA, "client", "screen", "DecompositionGeneratorScreen.java")
HUD = os.path.join(JAVA, "client", "JetpackHudOverlay.java")
GUI_TEX_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat",
                           "textures", "gui")

CHECKS = [0]
COUNTER = [0]
FAILS = []


def read(path):
    with open(path, "r", encoding="utf-8") as handle:
        return handle.read()


def strip_comments(text):
    """去掉 // 与 /* */ 注释（判据只看真代码；javadoc 里提旧写法不算）。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def method_body(text, token):
    """取「签名里含 token 的那个方法」的方法体（token 例如 'drawTrack('）。

    这样写是为了避开多行签名：直接按 token 找到签名起点，再从它后面第一个 `{` 起配花括号。
    """
    index = text.find(token)
    if index < 0:
        return ""
    brace = text.find("{", index + len(token))
    if brace < 0:
        return ""
    depth = 0
    for position in range(brace, len(text)):
        char = text[position]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[brace + 1:position]
    return ""


def check(label, ok):
    CHECKS[0] += 1
    if not ok:
        FAILS.append(label)
        print("FAIL %s" % label)
    else:
        print("ok   %s" % label)
    return ok


def counterexample(label, mutated_fails):
    COUNTER[0] += 1
    if not mutated_fails:
        FAILS.append("反例未判负: " + label)
        print("FAIL 反例 %s（可变体居然通过了判据）" % label)
    else:
        print("ok   反例 %s 已被判负" % label)


# ==================== 判据（吃源码文本，便于反例复用） ====================

def helper_ok(raw_helper):
    """① 共享类 = 参考实现；且不新增贴图。"""
    body = strip_comments(raw_helper)
    draw = method_body(body, "drawTrack(")
    create = method_body(body, "ProgressWidget create(")
    ratio = method_body(body, "double ratio(")
    value = method_body(body, "Component valueLine(")
    ok = (
        "package cretae.cookiewyq.rs_create_compat.client.widget;" in body
        and "import com.refinedmods.refinedstorage.common.support.widget.ProgressWidget;" in body
        and "public static final int WIDTH = 16;" in body
        and "public static final int DEFAULT_HEIGHT = 51;" in body
        and "public static final int COLOR_FRAME = 0xFF000000;" in body
        and "public static final int COLOR_TRACK = 0xFF2C2C2C;" in body
    )
    # 参考实现的原文：外框 18 宽、内底 16 宽（与范围充电器第 98 轮以来逐像素相同）
    ok = ok and "fill(x - 1, y - 1, x + WIDTH + 1, y + height + 1, COLOR_FRAME)" in draw
    ok = ok and "fill(x, y, x + WIDTH, y + height, COLOR_TRACK)" in draw
    # widget 必须是 RS 原版，且宽 = sprite 宽 16
    ok = ok and "new ProgressWidget(x, y, WIDTH, height," in create
    ok = ok and "ratio(stored.getAsLong(), capacity.getAsLong())" in create
    # 比例算法唯一：0 除 + 超 100% 夹紧
    ok = ok and "if (stored <= 0L || capacity <= 0L)" in ratio
    ok = ok and "raw >= 1D ? 1D : raw" in ratio
    # 数值句：千分位 + FE（与工程既有 RsccNumberField.grouped 同一口径）
    ok = ok and "RsccNumberField.grouped(stored)" in value
    ok = ok and '" / "' in value and '" FE"' in value
    # 文档锚点：sprite 全名来自 RS 的 jar（我们只引用、不复制）
    ok = ok and "refinedstorage:widget/progress_bar" in raw_helper
    # 不新增贴图：共享类里不得出现自有贴图/资源定位
    ok = ok and "ResourceLocation" not in body and "blit(" not in body
    # 「HUD 不纳入」的依据必须写在这里（Lead 裁决 ⑤）
    ok = ok and "JetpackHudOverlay" in raw_helper and "HUD" in raw_helper
    return ok


def charger_ok(raw_charger):
    """② 参考实现自己必须走共享类（防「参考实现不跟队」的漂移）。"""
    body = strip_comments(raw_charger)
    ok = (
        "new ProgressWidget(" not in body
        and "com.refinedmods.refinedstorage" not in body
        and "0xFF2C2C2C" not in body
        and "0xFF000000" not in body
        and "private static final int ENERGY_BAR_X = 26;" in body
        and "private static final int ENERGY_BAR_Y = 20;" in body
    )
    ok = ok and "RsccEnergyBar.create(" in body
    ok = ok and "RsccEnergyBar.drawTrack(" in body
    ok = ok and "RsccEnergyBar.DEFAULT_HEIGHT" in body
    ok = ok and "RsccEnergyBar.valueLine(" in body
    return ok


def cube_ok(raw_cube):
    """③ FE 能量立方：共享实现 + 几何 + 零自绘 + tooltip 不叠 + 旧凹槽覆盖契约。"""
    body = strip_comments(raw_cube)
    ok = (
        "private static final int ENERGY_BAR_X = 14;" in body
        and "private static final int ENERGY_BAR_Y = 19;" in body
        and "private static final int ENERGY_BAR_HEIGHT = 58;" in body
        and "this.imageWidth = 176;" in body
        and "this.imageHeight = 166;" in body
    )
    ok = ok and "RsccEnergyBar.drawTrack(" in body
    ok = ok and "RsccEnergyBar.create(" in body
    ok = ok and "RsccEnergyBar.valueLine(" in body
    ok = ok and "menu.getClusterEnergy()" in body and "menu.getClusterCapacity()" in body
    # 旧自绘实现（第 99~107 轮）的特征一个都不许留
    for dead in ("drawEnergyBar", "BAR_FILL", "BAR_INSET", "filledPixels",
                 "0xFF0D8FA5", "0xFF18B6D1", "guiGraphics.fill(", "ProgressWidget"):
        ok = ok and dead not in body
    # 贴图旧凹槽 (14,18) 14×60 与外框 (13,18) 18×60 的覆盖契约必须写在 javadoc（本轮不动 PNG）
    ok = ok and "14×60" in raw_cube and "(14,18)" in raw_cube and "(13,18)" in raw_cube
    # 两条 tooltip 不许叠：读数板判定先让位给条
    readout = method_body(body, "boolean isOverReadout(")
    ok = ok and "isOverEnergyBar(mouseX, mouseY)" in readout and "return false;" in readout
    ok = ok and "boolean isOverEnergyBar(" in body
    return ok


def generator_ok(raw_gen):
    """④ 分解发电机：**整个界面只有一条能量条**；进度用原版熔炉箭头；无任何自绘能量分支。"""
    body = strip_comments(raw_gen)
    # 旧设计的痕迹一个都不许留：琥珀平涂色 + 自绘竖直条
    ok = "COLOR_ENERGY" not in body and "0xFFE0B32A" not in body and "drawVerticalBar" not in body
    # 「只有一条」的正向判据：create / drawTrack 各恰好一次（多一条就会画两条）
    ok = ok and body.count("RsccEnergyBar.create(") == 1
    ok = ok and body.count("RsccEnergyBar.drawTrack(") == 1
    # 比例由共享类自己算（界面不许再算一遍能量比例，也不许自己调 ratio）
    ok = ok and "RsccEnergyBar.ratio(" not in body
    # 界面零 fill：面板 / 槽框全在贴图里（第 122 轮用户骂的就是「fill 拼出来的丑面板」）
    ok = ok and body.count(".fill(") == 0
    # 几何唯一来源：Spec（贴图生成脚本与 Menu 用的是同一份常量）
    ok = ok and "DecompositionGeneratorSpec.ENERGY_X" in body
    ok = ok and "DecompositionGeneratorSpec.ENERGY_Y" in body
    ok = ok and "DecompositionGeneratorSpec.ENERGY_HEIGHT" in body
    # 悬浮数值句沿用本机既有 lang 键（「缓存：x / y FE」），数字走 grouped 千分位
    # —— 这样既有键不会变成孤儿键（audit_lang_keys 会红），且与其余界面同一数字口径。
    ok = ok and "gui.rs_create_compat.decomposition_generator.energy" in body
    ok = ok and "RsccNumberField.grouped(menu.getEnergyStored())" in body
    ok = ok and "RsccNumberField.grouped(menu.getEnergyCapacity())" in body
    # 能量 tooltip 由 RS widget 自己画：手动画 tooltip 的那段里不许再出现能量读数
    tooltips = method_body(body, "void drawArrowTooltip(")
    ok = ok and "getEnergyStored" not in tooltips and "getEnergyCapacity" not in tooltips
    ok = ok and "progress_short" in tooltips
    # 进度改原版熔炉精灵：空箭头 (79,34) / 亮起 (176,14)，各 24×16，恰好两次 blit
    ok = ok and "ARROW_EMPTY_U = 79;" in body and "ARROW_EMPTY_V = 34;" in body
    ok = ok and "ARROW_LIT_U = 176;" in body and "ARROW_LIT_V = 14;" in body
    ok = ok and body.count("guiGraphics.blit(FURNACE_TEXTURE,") == 2
    return ok


def hud_untouched(raw_hud):
    """⑤ 背包 HUD 的横向条不纳入统一（不得被改去调共享类）。"""
    body = strip_comments(raw_hud)
    return (
        "RsccEnergyBar" not in body
        and "COLOR_BAR_FILL = 0xFF4FC3F7" in body
        and "private static final int BAR_HEIGHT = 8;" in body
    )


def not_referenced_by_server_sides(extra_sources=None):
    """⑥-a 共享类不得被 network / support 的**代码**引用（纯客户端渲染）。

    注意只看真代码：剥掉注释后 `{@link RsccEnergyBar}` 这类 javadoc 引用不算调用。
    第 123 轮 `support/DecompositionGeneratorSpec` 的注释里写过「能量条宽固定 16，见 RsccEnergyBar.WIDTH」——
    那是文档，不是服务端依赖；若按「整份文件出现该词就判负」会误伤（把文档写成理由也不允许，
    那就等于逼着人不写注释）。

    `extra_sources` = {相对 JAVA 的路径: 文本}，反例注入用（默认读磁盘）。
    """
    extra_sources = extra_sources or {}
    for folder in ("network", "support"):
        base = os.path.join(JAVA, folder)
        if not os.path.isdir(base):
            continue
        for dirpath, _dirnames, filenames in os.walk(base):
            for name in filenames:
                if not name.endswith(".java"):
                    continue
                full = os.path.join(dirpath, name)
                key = os.path.relpath(full, JAVA).replace(os.sep, "/")
                text = extra_sources.get(key)
                if text is None:
                    text = read(full)
                if "RsccEnergyBar" in strip_comments(text):
                    return False
    return True


def no_new_bar_texture(names):
    """⑥ textures/gui/ 下不得新增 energy_bar / progress_bar 贴图（不复制 RS 的 PNG）。"""
    lowered = [name.lower() for name in names]
    bad = [name for name in lowered if "energy_bar" in name or "progress_bar" in name]
    return not bad and "fe_energy_cube.png" in lowered and "range_charger.png" in lowered


# ==================== 主流程 ====================

def main():
    helper_text = read(HELPER)
    charger_text = read(CHARGER)
    cube_text = read(CUBE)
    gen_text = read(GEN)
    hud_text = read(HUD)
    gui_names = sorted(os.listdir(GUI_TEX_DIR))

    print("=== ① 共享实现 RsccEnergyBar = RS 原版 ProgressWidget + 同款黑框（不新增贴图） ===")
    check("① 共享类含 RS ProgressWidget / WIDTH=16 / 默认高 51 / 外框 0xFF000000 / 槽底 0xFF2C2C2C / "
          "drawTrack 两条 fill 与范围充电器逐像素同源 / ratio 夹紧 / valueLine 走 grouped / 无自有贴图",
          helper_ok(helper_text))

    print("\n=== ② 参考实现（范围充电器）自己也走共享类（防漂移） ===")
    check("② RangeChargerScreen 无 new ProgressWidget / 无帧色字面量；调 create+drawTrack+valueLine，几何 26/20",
          charger_ok(charger_text))

    print("\n=== ③ FE 能量立方：16×58 @ (14,19)、外框 18×60 @ (13,18) 盖住旧凹槽、零自绘、tooltip 不叠 ===")
    check("③-a 立方界面走共享类、几何 14/19/58、无 drawEnergyBar/BAR_FILL/filledPixels/guiGraphics.fill()、"
          "旧凹槽 (14,18) 14×60 与外框 (13,18) 18×60 契约在案",
          cube_ok(cube_text))
    check("③-b 立方读数板 tooltip 先让位给条（isOverReadout 里 isOverEnergyBar ⇒ return false）",
          cube_ok(cube_text))

    print("\n=== ④ 分解发电机（发电器）：全界面只有一条能量条，进度改原版熔炉箭头 ===")
    check("④ 发电机全界面只有一条 RsccEnergyBar（create/drawTrack 各恰 1 处）、零 fill、几何取自 Spec、"
          "无 COLOR_ENERGY/0xFFE0B32A/drawVerticalBar；悬浮句走既有 lang 键且数字 grouped；"
          "箭头 tooltip 无能量读数；进度走原版熔炉箭头（(79,34) 与 (176,14) 两次 blit）",
          generator_ok(gen_text))

    print("\n=== ⑤ 飞行背包 HUD 横向条不纳入统一（Lead 裁决 ⑤） ===")
    check("⑤ HUD 未被改去调 RsccEnergyBar（仍是自绘横向 96×8 条：不是 GUI、无 hover、sprite 竖向 16×70 硬套会变形）",
          hud_untouched(hud_text))

    print("\n=== ⑥ 共享类不得被 network/support 引用；textures/gui 不得新增能量条贴图 ===")
    check("⑥-a network/support 的代码里 0 处 RsccEnergyBar 引用（javadoc 提及不算）",
          not_referenced_by_server_sides())
    check("⑥-b textures/gui 下无 energy_bar/progress_bar 新贴图（fe_energy_cube.png / range_charger.png 仍在）",
          no_new_bar_texture(gui_names))

    print("\n=== ⑦ 反例自证（11 条） ===")
    counterexample("共享类 WIDTH 改 14（裁掉 sprite 右侧、观感不再像 RS）⇒ ① 判负",
                   helper_ok(helper_text.replace(
                       "public static final int WIDTH = 16;",
                       "public static final int WIDTH = 14;")) is False)
    counterexample("共享类改用自有贴图（ResourceLocation）⇒ ① 判负（不许新增 PNG）",
                   helper_ok(helper_text.replace(
                       "    private RsccEnergyBar() {",
                       "    private static final Object OWN_TEXTURE = ResourceLocation.parse(\"x:y\");\n"
                       "    private RsccEnergyBar() {")) is False)
    counterexample("共享类外框色改掉（0xFF000000 ⇒ 0xFF101010）⇒ ① 判负（与充电器像素不再同源）",
                   helper_ok(helper_text.replace(
                       "public static final int COLOR_FRAME = 0xFF000000;",
                       "public static final int COLOR_FRAME = 0xFF101010;")) is False)
    counterexample("范围充电器又自己 new ProgressWidget（参考实现不跟队）⇒ ② 判负",
                   charger_ok(charger_text.replace(
                       "        addRenderableWidget(RsccEnergyBar.create(",
                       "        addRenderableWidget(new com.refinedmods.refinedstorage.common.support.widget.ProgressWidget("))
                   is False)
    counterexample("立方又自绘 guiGraphics.fill 能量条 ⇒ ③ 判负",
                   cube_ok(cube_text.replace(
                       "        RsccEnergyBar.drawTrack(guiGraphics,",
                       "        guiGraphics.fill(0, 0, 1, 1, 0xFF0D8FA5);\n"
                       "        RsccEnergyBar.drawTrack(guiGraphics,")) is False)
    counterexample("立方条高改回 60（外框不再与旧凹槽契约一致）⇒ ③ 判负",
                   cube_ok(cube_text.replace(
                       "private static final int ENERGY_BAR_HEIGHT = 58;",
                       "private static final int ENERGY_BAR_HEIGHT = 60;")) is False)
    counterexample("立方读数板 tooltip 不再让位（同一帧两个框）⇒ ③ 判负",
                   cube_ok(cube_text.replace(
                       "if (isOverEnergyBar(mouseX, mouseY)) {", "if (false) {")) is False)
    counterexample("发电机又加回琥珀色自绘能量分支 ⇒ ④ 判负",
                   generator_ok(gen_text.replace(
                       "RsccEnergyBar.drawTrack(guiGraphics,",
                       "drawVerticalBar(guiGraphics, 0, 0, energyFraction(), 0xFFE0B32A, 0xFF2B2B2B);\n"
                       "        RsccEnergyBar.drawTrack(guiGraphics,")) is False)
    counterexample("发电机又画第二条能量条（同一帧两个条）⇒ ④ 判负",
                   generator_ok(gen_text.replace(
                       "        addRenderableWidget(RsccEnergyBar.create(",
                       "        addRenderableWidget(RsccEnergyBar.create(leftPos, topPos, 1, () -> 0, "
                       "() -> 1, java.util.List::of));\n"
                       "        addRenderableWidget(RsccEnergyBar.create(")) is False)
    counterexample("support 的代码里真的引用共享类（服务端引客户端渲染）⇒ ⑥-a 判负",
                   not_referenced_by_server_sides({"support/DecompositionProfile.java":
                       read(os.path.join(JAVA, "support", "DecompositionProfile.java"))
                       .replace("public record DecompositionProfile(",
                                "    private static final int BAR_W = RsccEnergyBar.WIDTH;\n"
                                "    public record DecompositionProfile(")})
                   is False)
    counterexample("textures/gui 下新增 energy_bar.png ⇒ ⑥-b 判负（不许新增自绘贴图）",
                   no_new_bar_texture(gui_names + ["energy_bar.png"]) is False)

    print("")
    if FAILS:
        print("SELFCHECK FAILED (%d checks, %d counterexamples, %d failures)"
              % (CHECKS[0], COUNTER[0], len(FAILS)))
        for label in FAILS:
            print("  - " + label)
        return 1
    print("断言总数 %d（含反例自证 %d 条，SKIP 0 条）" % (CHECKS[0], COUNTER[0]))
    print("SELFCHECK OK (%d checks, %d counterexamples)" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
