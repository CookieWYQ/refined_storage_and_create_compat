#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 111 轮自检：能耗监视界面「一片空白、只有 tooltip 和按钮」的渲染顺序回归。

用户第 111 条实机反馈（截图）：打开高级远程多功能终端的「能耗监视」页，面板里什么都没有，
但鼠标悬停能弹出行 tooltip（「输出总线 · 主世界 (391,110,99) · 当前 30 FE/t | 标称 30 FE/t」），
底部按钮（返回终端 / 刷新 / 清除高亮）与右侧滚动条也都在。用户原话：
  > 「这他妈什么鬼，怎么啥也不渲染，但是我觉得里面的内容是有的。你看那个 tooltip，
  >  但是他妈啥都没有是什么意思？」

## 根因
`EnergyAuditScreen extends ChildConfigScreen`。`ChildConfigScreen#renderBackground` 会
`blitSprite` 一整块**不透明**的原版九宫格子面板（`minecraft:recipe_book/overlay_recipe`）；
而 `Screen#render`（= `super.render`）的顺序是「renderBackground → 控件」。
旧实现把 `renderContent(...)`（标题 / 摘要 / 列表全部内容）放在 `super.render(...)` **之前**，
于是内容先画、随后被整块面板背景盖住；按钮 / 滚动条画在 super 之后、tooltip 画在最后，
所以只有它们可见 —— 与截图逐项对应（有 tooltip、有按钮，唯独没有列表）。

## 修法
渲染顺序改为：`super.render`（背景 + 控件）→ `renderContent`（内容）→ 滚动条**重画**
（内容会盖住列表右缘的滚动条，必须重画才能保持「滚动条压住列表」的旧观感）→ `renderTooltips`。

本脚本只读源码，只用 Python 标准库。

断言清单：
  ① `EnergyAuditScreen#render` 的四步顺序严格正确（super < content < scrollbar 重画 < tooltips）；
  ② 全工程所有 `render()` 里都不存在「renderContent 在 super.render 之前」的同类回归
     （扫 client/screen 下所有 Screen 子类）；
  ③ 滚动条在 renderContent 之后被重画（否则列表底/行会盖住它）；
  ④ 行为模型：把「面板背景」当成不透明覆盖层，旧顺序 ⇒ 内容不可见、新顺序 ⇒ 内容可见；
     反例：顺序退回旧写法 ⇒ 判负。
  反例自证 ≥4 条。

用法：python tools/selfcheck_round111_energy_audit_render_order.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # pragma: no cover
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCREENS = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq",
                       "rs_create_compat", "client", "screen")
AUDIT = os.path.join(SCREENS, "EnergyAuditScreen.java")

FAILS = []
CHECKS = [0]
COUNTER = [0]


def read(path):
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(name, ok):
    CHECKS[0] += 1
    print(("PASS " if ok else "FAIL ") + name)
    if not ok:
        FAILS.append(name)
    return ok


def counterexample(name, ok):
    COUNTER[0] += 1
    check("反例 %s" % name, ok)


def strip_comments(text):
    out = []
    i = 0
    n = len(text)
    quote = None
    while i < n:
        char = text[i]
        two = text[i:i + 2]
        if quote is None:
            if two == "//":
                end = text.find("\n", i)
                end = n if end < 0 else end
                out.append(" " * (end - i))
                i = end
                continue
            if two == "/*":
                end = text.find("*/", i + 2)
                end = n if end < 0 else end + 2
                out.append("".join("\n" if ch == "\n" else " " for ch in text[i:end]))
                i = end
                continue
            if char in ("\"", "'"):
                quote = char
                out.append(char)
                i += 1
                continue
            out.append(char)
            i += 1
            continue
        if char == "\\":
            out.append(text[i:i + 2])
            i += 2
            continue
        if char == quote:
            quote = None
        out.append(char)
        i += 1
    return "".join(out)


def render_body(text):
    """取 EnergyAuditScreen 的 render 方法体（括号配平）。"""
    code = strip_comments(text)
    match = re.search(r"public void render\(final GuiGraphics guiGraphics, final int mouseX, final int mouseY,\s*"
                      r"final float partialTick\)", code)
    if not match:
        return None
    brace = code.find("{", match.end() - 1)
    depth = 0
    for index in range(brace, len(code)):
        char = code[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return code[match.start():index + 1]
    return None


# ======================================================================================
# ① 四步顺序
# ======================================================================================

def order_ok(text):
    body = render_body(text)
    if body is None:
        return False
    # 只保留调用行，按出现顺序比较；scrollbar.render 必须是「重画」那一次
    super_at = body.find("super.render(guiGraphics, mouseX, mouseY, partialTick);")
    content_at = body.find("renderContent(guiGraphics, mouseX, mouseY);")
    scroll_at = body.find("scrollbar.render(guiGraphics, mouseX, mouseY, partialTick);")
    tip_at = body.find("renderTooltips(guiGraphics, mouseX, mouseY);")
    return 0 <= super_at < content_at < scroll_at < tip_at


def scrollbar_redrawn_ok(text):
    body = render_body(text)
    if body is None:
        return False
    return ("if (scrollbar != null) {" in body
            and "scrollbar.render(guiGraphics, mouseX, mouseY, partialTick);" in body)


# ======================================================================================
# ② 全工程同类回归扫描
# ======================================================================================

def screens_with_content_before_super():
    offenders = []
    for name in sorted(os.listdir(SCREENS)):
        if not name.endswith(".java"):
            continue
        path = os.path.join(SCREENS, name)
        try:
            body = render_body_text(read(path))
        except OSError:
            continue
        if body is None:
            continue
        if "renderContent" in body and "super.render" in body:
            if body.find("renderContent") < body.find("super.render"):
                offenders.append(name)
    return offenders


def has_content_before_super(text):
    """单文件版判据：它的 render() 里 renderContent 是否出现在 super.render 之前。"""
    body = render_body_text(text)
    if body is None:
        return False
    if "renderContent" not in body or "super.render" not in body:
        return False
    return body.find("renderContent") < body.find("super.render")


def render_body_text(text):
    """通用版：任何 render(...) 方法体（用于扫描其它界面）。"""
    code = strip_comments(text)
    match = re.search(r"public void render\(final GuiGraphics", code)
    if not match:
        return None
    brace = code.find("{", match.end() - 1)
    depth = 0
    for index in range(brace, len(code)):
        char = code[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return code[match.start():index + 1]
    return None


# ======================================================================================
# ④ 行为模型：不透明面板覆盖 + 内容/控件的绘制先后
# ======================================================================================

def content_visible(order):
    """order = 绘制顺序列表（如 ["content", "panel", "widgets", "tooltip"]）。

    模型：`panel`（ChildConfigScreen 的子面板背景）是**不透明**的，后画者覆盖先画者；
    返回内容（title/summary/list）在最终画面上是否可见（= 内容之后没有画过 panel）。
    """
    final = []
    for layer in order:
        if layer == "panel":
            final.append("panel")  # 不透明背景 = 清空之前所有内容
        elif layer != "widgets" and layer != "tooltip":
            final.append(layer)
        else:
            final.append(layer)
    # 内容可见 ⇔ 内容在列表里、且它之后没有再出现 panel
    for index, layer in enumerate(final):
        if layer == "content":
            return "panel" not in final[index + 1:]
    return False


def content_visible_transparent(order):
    """反例模型：把子面板当透明（不覆盖先前绘制）——旧顺序会被误判为可见。"""
    return "content" in order


def model_ok():
    old_order = ["content", "panel", "widgets", "tooltip"]  # 旧：内容先画，面板盖住
    new_order = ["panel", "content", "widgets", "tooltip"]  # 新：背景先画，内容后画
    return content_visible(old_order) is False and content_visible(new_order) is True


# ======================================================================================
# 主流程
# ======================================================================================

def main():
    audit = read(AUDIT)

    print("=== ① EnergyAuditScreen#render 顺序：super.render → renderContent → 滚动条重画 → tooltips ===")
    check("①-a 四步顺序严格正确", order_ok(audit))
    counterexample("把 renderContent 挪回 super.render 之前（用户截图里的原始 bug）⇒ 判负",
                   order_ok(audit.replace(
                       "        super.render(guiGraphics, mouseX, mouseY, partialTick);\n"
                       "        renderContent(guiGraphics, mouseX, mouseY);",
                       "        renderContent(guiGraphics, mouseX, mouseY);\n"
                       "        super.render(guiGraphics, mouseX, mouseY, partialTick);")) is False)
    check("②-a 滚动条在内容之后被重画（否则列表底 / 行会盖住列表右缘的滚动条）",
          scrollbar_redrawn_ok(audit))
    counterexample("删掉滚动条重画 ⇒ 判负",
                   scrollbar_redrawn_ok(audit.replace(
                       "        if (scrollbar != null) {\n"
                       "            scrollbar.render(guiGraphics, mouseX, mouseY, partialTick);\n"
                       "        }\n", "")) is False)

    print("=== ② 全工程扫描：不存在第二处「renderContent 在 super.render 之前」的同类回归 ===")
    offenders = screens_with_content_before_super()
    check("②-b client/screen 下所有界面都不再有同类顺序回归", not offenders)
    if offenders:
        for name in offenders:
            print("       offender: " + name)
    counterexample("把 EnergyAuditScreen 的 render 换回旧顺序后扫描器能重新抓到它 ⇒ 判负",
                   has_content_before_super(audit) is False
                   and has_content_before_super(audit.replace(
                       "        super.render(guiGraphics, mouseX, mouseY, partialTick);\n"
                       "        renderContent(guiGraphics, mouseX, mouseY);",
                       "        renderContent(guiGraphics, mouseX, mouseY);\n"
                       "        super.render(guiGraphics, mouseX, mouseY, partialTick);")) is True)

    print("=== ③ 行为模型：不透明子面板会盖住先画的内容（旧顺序 ⇒ 内容不可见、新顺序 ⇒ 可见） ===")
    check("③-a 模型：旧顺序内容不可见、新顺序可见", model_ok())
    old_order = ["content", "panel", "widgets", "tooltip"]
    new_order = ["panel", "content", "widgets", "tooltip"]
    counterexample("模型把面板当透明（旧顺序也判可见）⇒ 判负",
                   content_visible_transparent(old_order) is True
                   and content_visible(old_order) is False)
    counterexample("模型把内容放在面板之后却判不可见 ⇒ 判负",
                   content_visible(new_order) is True)

    print("")
    print("断言总数 %d（含反例自证 %d 条）" % (CHECKS[0], COUNTER[0]))
    if FAILS:
        print("问题总数: %d" % len(FAILS))
        for item in FAILS:
            print(" - " + item)
        return 1
    print("SELFCHECK OK (%d checks, %d counterexamples)" % (CHECKS[0], COUNTER[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
