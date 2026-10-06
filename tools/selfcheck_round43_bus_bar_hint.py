# -*- coding: utf-8 -*-
"""自检（第 43 轮 · 总线类别条上的「空条提示」：两段文字叠在一起 → 先算固定段再截断 + 分行）。

用法：python tools/selfcheck_round43_bus_bar_hint.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

上游证实的缺陷（本自检把它钉成断言，防止有人再改回「居中且不截断」）
-------------------------------------------------------------------
用户截图：**输入总线**面板上方那一条类别条上的提示「两段叠在一起、看不清楚」。
旧实现（`ExporterExecutorRowWidget#drawLockedBar` 的 `count == 0` 分支）是：

    guiGraphics.drawString(font, text, x + (w - font.width(text)) / 2, y + (h - 8) / 2, ...)

* 条内层只有 **162px** 宽，而这句中文（输入侧「还没勾选任何要收回的类别（点「详细配置…」开始勾选）」）
  约 **225px** ⇒ 居中画时左右各溢出约 32px；
* 右半截正好压进条内右端那颗**几何固定**的「普通」按钮（局部 132..162，按钮文字 y 3..12，
  提示文字 y 7..16 —— 垂直相交、水平相交）⇒ 两段字叠在同一片像素上；
* 左半截越出条内层（甚至面板左缘）。

修法（与「类别详细配置」子界面 `drawHint` 同一套策略，这里是同一条几何错误的第二处）：
**先把固定段（那颗「普通」按钮）的几何算死 → 由它推出提示文字的可用带宽 → 逐行截断后在带内居中**，
并把「状态」与「去哪改」拆成两行（可用带只有约 128px，一行两段会把两段都截成残句）。

本自检钉住五件事
----------------
G1 结构：`count == 0` 分支只调用 `drawEmptyHint`；旧的「居中且不截断」写法**已彻底消失**；
   `drawEmptyHint` 的可用带右界由 `FORCE_X − HINT_GAP`（固定段）推出，左界 = 条内层左缘 + `HINT_INSET`；
G2 `trimToWidth` 的返回值恒满足 `0 ≤ 宽度 ≤ maxWidth`（连省略号都放不下时返回空串）；
G3 **几何穷举**：对 0..400px 的各种文字宽度组合，断言两行的水平区间都落在可用带内、
   都不与「普通」按钮矩形相交、两行的垂直区间互不相交且都在条内层里（按钮画 / 不画两种情形都测）；
G4 文案：中英成对、键集一致、中文 ≤40 字、不含禁用措辞（「或」）；
   **且按保守宽度上界（非 ASCII 9px / 空格 4px / 其余 6px）四句都塞得进可用带**（无需省略号）；
G5 代价为零：提示只在空条这一帧画，类别条 / 控制带 / 面板几何一个像素都没动。
"""

from __future__ import annotations

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
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
WIDGET_PARTS = ("client", "widget", "ExporterExecutorRowWidget.java")
IMP_PARTS = ("client", "widget", "ImporterExecutorBarSource.java")
EXP_MIXIN_PARTS = ("mixin", "client", "ExporterScreenMixin.java")
IMP_MIXIN_PARTS = ("mixin", "client", "ImporterScreenMixin.java")

EXP = "gui.rs_create_compat.exporter_executor."
IMP = "gui.rs_create_compat.importer_executor."

FAILURES = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def read(*parts):
    with io.open(os.path.join(*parts), "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def java_int(source, name):
    match = re.search(r"\b(?:static final int|final int)\s+%s\s*=\s*(-?\d+)\s*;" % re.escape(name), source)
    if not match:
        raise AssertionError("源码里找不到 int 常量 %s" % name)
    return int(match.group(1))


def body(source, start, end):
    return source[source.index(start):source.index(end)]


def trim_width(widths, max_w, ellipsis_w):
    """复刻 Java trimToWidth 返回串的宽度（逐字符收，末尾再验一次）。"""
    if not widths or max_w <= 0:
        return 0
    total = sum(widths)
    if total <= max_w:
        return total
    if max_w <= ellipsis_w:
        return 0
    kept = list(widths)
    while len(kept) > 1 and sum(kept) + ellipsis_w > max_w:
        kept.pop()
    result = sum(kept) + ellipsis_w
    return result if result <= max_w else 0


def char_widths(text_w):
    """把「文字总宽 text_w」摊成一串字符宽度（只为让逐字符截断可复刻；总宽恒等于 text_w）。"""
    if text_w <= 0:
        return []
    return [9] * (text_w // 9) + ([text_w % 9] if text_w % 9 else [])


def rect(x, y, w, h):
    return (x, y, x + w, y + h)


def overlap(a, b):
    return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]


def conservative_width(text):
    """保守宽度上界：空格 4px、其余 ASCII 6px、非 ASCII 9px（默认字体不会更宽）。"""
    total = 0
    for ch in text:
        if ch == " ":
            total += 4
        elif ord(ch) < 128:
            total += 6
        else:
            total += 9
    return total


def main():
    widget = read(SRC, *WIDGET_PARTS)
    imp_source = read(SRC, *IMP_PARTS)
    exp_mixin = read(SRC, *EXP_MIXIN_PARTS)
    imp_mixin = read(SRC, *IMP_MIXIN_PARTS)

    line_h = java_int(widget, "HINT_LINE_H")
    line1_y = java_int(widget, "HINT_LINE1_Y")
    line2_y = java_int(widget, "HINT_LINE2_Y")
    inset = java_int(widget, "HINT_INSET")
    gap = java_int(widget, "HINT_GAP")
    force_x = java_int(widget, "FORCE_X")
    force_w = java_int(widget, "FORCE_W")
    force_h = java_int(widget, "FORCE_H")
    bar_w = java_int(imp_mixin, "RSCC_ROW_W")
    bar_h = java_int(imp_mixin, "RSCC_ROW_H")
    exp_bar_w = java_int(exp_mixin, "RSCC_ROW_W")
    exp_bar_h = java_int(exp_mixin, "RSCC_ROW_H")

    section("G1 结构：count == 0 只走 drawEmptyHint；「居中且不截断」的旧写法已消失")
    locked_bar = body(widget, "private void drawLockedBar(", "private void drawInnerBorder(")
    check("空条分支改走 drawEmptyHint（分支里不再自己 drawString）",
          "drawEmptyHint(guiGraphics);" in locked_bar
          and "drawString" not in locked_bar)
    check("旧的「居中且不截断」整句写法在整个控件里都找不到了",
          "y + (h - 8) / 2" not in widget,
          str([line.strip() for line in widget.splitlines() if "y + (h - 8) / 2" in line][:2]))
    check("drawEmptyHint 只有一个调用点（就是空条那一帧）",
          widget.count("drawEmptyHint(guiGraphics);") == 1)
    check("两种「空」仍然分开说（没有候选 → emptyTextKey；有候选没勾 → selectedEmptyTextKey）",
          "? source.selectedEmptyTextKey() : source.emptyTextKey()).getString(), left, bandW, headY);"
          in widget
          and "default String selectedEmptyTextKey()" in widget)
    check("第二行（去哪改）走可替换的数据源键（输入总线用自己的前缀覆写）",
          "source.selectedEmptyActionTextKey()" in widget
          and 'return LANG + "no_selected_action";' in widget
          and 'return LANG + "no_selected_action";' in imp_source)
    check("可用带右界由固定段推出：FORCE_X − HINT_GAP（按钮画） / 条内层右缘 − HINT_INSET（按钮不画）",
          "? getX() + FORCE_X - HINT_GAP" in widget
          and ": getX() + getWidth() - HINT_INSET;" in widget
          and "final int bandW = Math.max(0, right - left);" in widget)
    check("固定段的「画不画」判定与绘制同源（convertible() && !forceNormal()）",
          "final int right = (convertible() && !forceNormal())" in widget
          and "if (convertible() && !forceNormal()) {\n            drawForceButton(guiGraphics, mouseX, mouseY);"
          in locked_bar)
    check("逐行绘制 = 先 trimToWidth 再在带内居中（0 ≤ 文字宽 ≤ 带宽 ⇒ 左界 ≥ 带左、右界 ≤ 带右）",
          "final String text = trimToWidth(font, raw, bandW);" in widget
          and "guiGraphics.drawString(font, text, left + (bandW - font.width(text)) / 2, y, COLOR_TEXT, false);"
          in widget)

    section("G2 trimToWidth：返回值恒满足 0 <= 宽度 <= maxWidth")
    check("空串 / 非正宽度 / 放不下省略号 → 返回空串（宁可不画，也不画超出可用宽的一行）",
          "if (text == null || text.isEmpty() || maxWidth <= 0) {" in widget
          and "if (maxWidth <= ellipsisWidth) {" in widget
          and "final String candidate = trimmed + HINT_ELLIPSIS;" in widget
          and 'return font.width(candidate) <= maxWidth ? candidate : "";' in widget)

    section("G3 几何穷举：任意文字宽（0..400）下两行都不重叠、不压按钮、不出条、不出带")
    ellipsis_w = 3 * 6  # "..." 的保守上界（默认字体里 '.' 远窄于此）
    problems = []
    cases = 0
    for current_bar_w, current_bar_h in ((bar_w, bar_h), (exp_bar_w, exp_bar_h)):
        for force_drawn in (True, False):
            left = inset
            right = (force_x - gap) if force_drawn else (current_bar_w - inset)
            band_w = max(0, right - left)
            force_rect = rect(force_x, 0, force_w, force_h)
            for head_w in range(0, 401):
                for tail_w in (0, 1, 5, 17, 18, 45, 63, 90, 127, 128, 129, 200, 401):
                    for two_lines in (False, True):
                        cases += 1
                        if two_lines:
                            ys = (line1_y, line2_y)
                            widths = (head_w, tail_w)
                        else:
                            ys = ((current_bar_h - line_h) // 2,)
                            widths = (head_w,)
                        rects = []
                        for text_w, y in zip(widths, ys):
                            drawn = trim_width(char_widths(text_w), band_w, ellipsis_w)
                            if drawn > band_w:
                                problems.append("宽度超出可用带：band=%d drawn=%d" % (band_w, drawn))
                                continue
                            x = left + (band_w - drawn) // 2
                            r = rect(x, y, drawn, line_h)
                            rects.append(r)
                            if x < left or x + drawn > right:
                                problems.append("水平越出可用带：x=%d w=%d 带=[%d,%d]"
                                                % (x, drawn, left, right))
                            if force_drawn and overlap(r, force_rect):
                                problems.append("压到「普通」按钮：%s vs %s" % (r, force_rect))
                            if r[1] < 0 or r[3] > current_bar_h:
                                problems.append("垂直越出类别条：%s（条高 %d）" % (r, current_bar_h))
                        for i in range(len(rects)):
                            for j in range(i + 1, len(rects)):
                                if overlap(rects[i], rects[j]):
                                    problems.append("两行重叠：%s vs %s" % (rects[i], rects[j]))
    check("穷举 %d 组（条宽 %d/%d × 按钮画/不画 × 文字宽 0..400 × 两行/一行）无重叠 / 无越界"
          % (cases, bar_w, exp_bar_w), not problems, str(problems[:3]))
    check("两行的垂直区间本来就不相交（%d..%d 与 %d..%d，行高 %d）"
          % (line1_y, line1_y + line_h - 1, line2_y, line2_y + line_h - 1, line_h),
          line1_y + line_h <= line2_y and line2_y + line_h <= bar_h)
    check("可用带右界 ≤ 固定段左缘（FORCE_X − HINT_GAP ≤ FORCE_X）",
          force_x - gap <= force_x and bar_w - inset <= bar_w)
    check("两侧条几何逐字一致（提示的可用带宽因此也一致）",
          (bar_w, bar_h) == (exp_bar_w, exp_bar_h))

    section("G4 文案：中英成对、键集一致、中文 ≤40、无禁用措辞、且都塞得进可用带")
    with io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8") as handle:
        zh = json.load(handle)
    with io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8") as handle:
        en = json.load(handle)
    check("两个语言文件整体键集一致", set(zh) == set(en), str(sorted(set(zh) ^ set(en))[:4]))
    keys = (EXP + "no_selected_categories", EXP + "no_selected_action",
            IMP + "no_selected_categories", IMP + "no_selected_action")
    check("本轮四个键（两条总线各两份）中英齐备", all(k in zh and k in en for k in keys),
          str([k for k in keys if k not in zh or k not in en]))
    check("中文 ≤40 字", all(len(zh[k]) <= 40 for k in keys),
          str([(k, len(zh[k])) for k in keys if len(zh[k]) > 40]))
    check("文案不含禁用措辞（「或」）", all("或" not in zh[k] and " or " not in en[k] for k in keys))
    band = force_x - gap - inset
    fitting = [(k, conservative_width(zh[k]), conservative_width(en[k])) for k in keys]
    check("四句在保守宽度上界下都塞得进可用带（%dpx，无需省略号）" % band,
          all(zw <= band and ew <= band for _k, zw, ew in fitting),
          str([(k, zw, ew) for k, zw, ew in fitting if zw > band or ew > band]))
    check("「去哪改」已从状态句里拆出来（状态句不再提「详细配置」）",
          all("详细配置" in zh[k] for k in (EXP + "no_selected_action", IMP + "no_selected_action"))
          and all("详细配置" not in zh[k] for k in (EXP + "no_selected_categories",
                                                    IMP + "no_selected_categories")))

    section("G5 代价为零：提示只在空条这一帧画，条 / 控制带 / 面板几何一个像素没动")
    check("类别条几何 = 162×23 @ Menu (8,16)（两侧一致）",
          (bar_w, bar_h) == (162, 23) and (exp_bar_w, exp_bar_h) == (162, 23))
    check("控制带常量仍在（BAND_Y = 24 / CONFIG_X = TOGGLE_X + TOGGLE_W + 3）",
          "private static final int BAND_Y = 24;" in widget
          and "private static final int CONFIG_X = TOGGLE_X + TOGGLE_W + 3;" in widget)
    check("两行提示都落在类别条内层里（空条帧内没有单元格，不抢图标位置）",
          line2_y + line_h <= bar_h and "final List<RsccBusCategory> list = visibleCategories();" in locked_bar)

    print()
    print("=" * 78)
    if FAILURES:
        print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
        for entry in FAILURES:
            print("  - %s" % entry)
        return 1
    print("SELFCHECK OK (%d checks)" % CHECKS[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
