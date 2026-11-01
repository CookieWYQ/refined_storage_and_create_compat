#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""第 70 轮：<b>分类不改写，策略只做附加标注</b>（「优先使用中间产物」的语义修正）。

<h2>用户原话（本轮的验收标准）</h2>
「补充一点就是这个<b>如果说开启优先使用中间产物，那么我指的是优先使用，并不是说原料就没有了</b>。
所以说呢这个中间产物呃，<b>就是这个原料，这个时候不要显示成中间产物，还是正常显示</b>，
但是呢<b>在旁边需要标注「优先使用中间产物」</b>这样」

⇒ 两条规则：
  ① <b>分类按「它本身是什么」来</b>：是原料就显示原料，
     <b>不许</b>因为「开了优先使用中间产物」就把它改名 / 改标成「中间产物」；
  ② <b>策略以【附加标注】表达</b>：在旁边加一句「优先使用中间产物」。
身份（分类）与策略（偏好）必须<b>分离</b>。

<h2>本脚本钉住的判据（全部是源码级 / 语言键级的静态判据，可回归）</h2>
  1) <b>开关状态不参与分类判定</b>：样板终端里唯一读
     {@code menu.isReuseIntermediates()} 的<b>分类 / 绘制</b>方法只有「附加标注」那一个；
     顶部输入原料格的绘制与标题<b>不许</b>出现任何规则开关 / 过渡件判据；
     更不许再出现「按开关在两条分类文案之间二选一」的写法（修前写法就叫分类改写）。
  2) <b>「优先使用中间产物」以独立标注存在</b>：专用文本键存在、中英非空且逐字是那句标注；
     旧的分类改写键（{@code input_slot.intermediate} / {@code op.intermediate}）必须已消失
     ——「中间产物」只能作为<b>类别名</b>存在，不能作为「原料格」的标签。
  3) <b>反例</b>：把修前写法（开关为开 ⇒ 这一格改画过渡件 + 换成「中间产物」标签）
     喂给本脚本<b>同一批判据</b>，必须被判 FAIL —— 证明这些判据真的抓得住旧语义。

用法：python tools/selfcheck_round70_intermediate_label.py
退出码：0 = 没有任何 FAIL。
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

SCREEN_REL = os.path.join("client", "screen", "SequencePatternTerminalScreen.java")
MENU_REL = os.path.join("menu", "SequencePatternTerminalMenu.java")
TERMINAL_LANG = "gui.rs_create_compat.sequence_pattern_terminal."
ANNOTATION_KEY = TERMINAL_LANG + "reuse.annotation"
PREVIEW_KEY = "gui.rs_create_compat.autocrafting_preview.intermediate_reuse"
ANNOTATION_TEXT = "优先使用中间产物"
# 修前的「分类改写键」：它们把「中间产物」这个词直接贴到原料格上。
RETIRED_KEYS = (TERMINAL_LANG + "input_slot.intermediate", TERMINAL_LANG + "op.intermediate")

FAILURES = []
CHECKS = [0]


def read(rel):
    with io.open(os.path.join(SRC, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s %s" % (name, ("-> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


def body_of(text, signature):
    """取一个方法的方法体（花括号配平；找不到返回 None）。

    判据必须只看方法体，否则 `reuseAnnotation()` 的存在会让「开关读取点」这类断言失去分辨力。
    """
    at = text.find(signature)
    if at < 0:
        return None
    start = text.find("{", at)
    if start < 0:
        return None
    depth = 0
    for i in range(start, len(text)):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
    return None


# ======================================================================
# 判据实现（同一批函数也用于反例，见 §3）
# ======================================================================

# 策略开关的读取写法（界面侧的真读：客户端镜像 / 服务端存档级策略）。
POLICY_READ = re.compile(r"isReuseIntermediates\(|RsccIntermediateReusePolicy\s*\.|"
                         r"IntermediateReuseClient\.enabled\(\)")
# 过渡件的「事实」判据 = 拿它当绘制 / 分类的输入（修前正是它把原料改画 / 改标成中间产物）。
INTERMEDIATE_FACT = re.compile(r"reusableIntermediate\(|reuseIntermediate\(")
# 分类改写最露骨的特征：按开关在两条<b>分类文案</b>之间二选一。
CLASSIFICATION_SWITCH = re.compile(
    r"\?\s*\"[A-Za-z0-9_.]*\"\s*:\s*\"[A-Za-z0-9_.]*\"|"
    r"\?\s*[a-zA-Z]*[Ll]ang[A-Za-z]*\s*\+|"
    r"\?\s*\"[A-Za-z0-9_.]+\"\s*:\s*[A-Za-z]")


def classification_verdict(label, render_body, labels_body, annotation_body, policy_body):
    """一批静态判据（当前源码与反例走同一批）：返回 (ok, 原因列表)。"""
    problems = []
    if render_body is None:
        problems.append("找不到「顶部输入原料格绘制」方法体")
    else:
        # 「画什么」不许看开关：开关只影响取材优先级（服务端在备料闸门里读它）。
        if POLICY_READ.search(render_body):
            problems.append("输入原料格的绘制读了策略开关（开关不得影响画什么）")
        if INTERMEDIATE_FACT.search(render_body):
            problems.append("输入原料格的绘制拿了过渡件判据（修前正是它把原料改画成中间产物）")
    if labels_body is None:
        problems.append("找不到 renderLabels 方法体")
    else:
        if POLICY_READ.search(labels_body):
            problems.append("分类标题（renderLabels）读了策略开关")
        # <b>注意</b>：这里允许出现 {@code reuseAnnotation()}（它是「旁边加一句标注」的入口），
        # 但绝不允许把过渡件摆进分类标题（修前正是 `reuseIntermediate().isEmpty() ? "op.input" : "op.intermediate"`）。
        if INTERMEDIATE_FACT.search(labels_body):
            problems.append("分类标题拿了过渡件判据（修前正是它把标题换成「中间产物」）")
        if CLASSIFICATION_SWITCH.search(labels_body):
            problems.append("分类标题里出现「二选一」写法（分类改写特征写法）")
        if "reuseAnnotation(" in labels_body and "op.input" not in labels_body:
            problems.append("分类标题连自己那一份分类名都没有（只剩标注）")
    if annotation_body is not None and (POLICY_READ.search(annotation_body)
                                       or INTERMEDIATE_FACT.search(annotation_body)):
        problems.append("tooltip 侧仍在按开关 / 过渡件改写分类（标注必须独立成句）")
    if policy_body is None:
        problems.append("找不到策略开关的唯一读取点（reusableIntermediate）")
    elif not POLICY_READ.search(policy_body):
        problems.append("「优先使用中间产物」的判据没有读开关 ⇒ 标注与策略无关（说不清为什么出现）")
    return (not problems), problems


# ======================================================================
# 1) 分类判定链路：开关不参与
# ======================================================================
section("1) 分类判定链路：开关状态不参与分类判定（身份与策略分离）")

screen = read(SCREEN_REL)
menu = read(MENU_REL)
# 语言文件在中英两侧都要读（§1f 的模式名核对与 §2 的标注文案核对共用同一份）
zh = json.load(io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8"))
en = json.load(io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8"))

render_body = body_of(screen, "private void renderInputSlot(final GuiGraphics")
labels_body = body_of(screen, "protected void renderLabels(final GuiGraphics")
annotation_body = body_of(screen, "private Optional<Component> reuseAnnotation()")
policy_body = body_of(screen, "private ItemStack reusableIntermediate()")

ok, problems = classification_verdict("screen", render_body, labels_body, None, policy_body)
check("1a 分类 / 绘制 / 标题不许读策略开关；tooltip 侧也不许再出现「按开关改写分类」的二选一",
      ok, "; ".join(problems))

# 1b：整个界面里读「策略开关」的位置必须<b>只有</b>策略判据那一个方法（注释行不算读取点）
code_lines = []
for lineno, line in enumerate(screen.splitlines(), 1):
    stripped = line.strip()
    if stripped.startswith("*") or stripped.startswith("//") or stripped.startswith("/*"):
        continue  # 注释里的说明不算读取点（本轮大量注释在讲这条语义）
    if POLICY_READ.search(line):
        code_lines.append((lineno, stripped[:100]))
check("1b 界面代码里只有 1 处读策略开关（= 附加标注的判据），且它就在 reusableIntermediate() 里",
      len(code_lines) == 1 and "!menu.isReuseIntermediates()" in code_lines[0][1]
      and policy_body is not None and code_lines[0][1] in policy_body,
      "hits=%s" % code_lines)

# 1b2：那个方法的 javadoc 必须写明它只服务附加标注（防后人再拿它当绘制 / 分类输入）
check("1b2 过渡件事实的唯一解析方法明示「只服务附加标注、不参与分类判定」",
      policy_body is not None and "只服务附加标注" in screen[:screen.find("private ItemStack reusableIntermediate()")])

# 1c：菜单侧的开关 Javadoc 必须写明「不参与分类」（防止后人再拿它当分类开关）
check("1c 菜单开关的 Javadoc 明示「不参与任何分类判定」（防后人再用它改写身份）",
      "不参与任何分类判定" in menu)

# 1d：附加标注只在「开关为开 + 该配方确实有可优先取用的中间产物」时出现（两个条件缺一不可）
check("1d 附加标注 = 开关为开 AND 该配方有可优先取用的过渡件（缺任一条 → 无标注）",
      policy_body is not None
      and "if (!menu.isReuseIntermediates())" in policy_body
      and "recipe.getTransitionalItem()" in policy_body
      and "return ItemStack.EMPTY;" in policy_body)

# 1e：标注是「旁边加一句」，不是替换 —— tooltip 里它必须与物品自身行<b>并存</b>
tooltip_body = body_of(screen, "private void renderInputSlotTooltip(final GuiGraphics")
check("1e tooltip 里标注与「物品自身行」并存（附加，而不是替换分类）",
      tooltip_body is not None and "reuseAnnotation()" in tooltip_body
      and "itemTooltipLines(" in tooltip_body
      and "input_slot.intermediate" not in tooltip_body)

# 1f：其它「开关 → 文案」的地方不得改写分类：全工程只修这一处分类改写；
#     其余开关（自动收回 / 强制普通 / 目标策略）只切换<b>模式名</b>，不是分类名。
#     动态拼接的模式名（auto.on / auto.off / strategy.*）必须：① 语言键在中英两侧都存在；
#     ② 拼接它的宿主自己说出了该后缀（宿主 = 真正画那一行的那份源码）。
FORCE_LANG = "gui.rs_create_compat.bus_force_normal."
EXPORTER_LANG = "gui.rs_create_compat.exporter_executor."
IMPORTER_LANG = "gui.rs_create_compat.importer_executor."
WIDGET_REL = os.path.join("client", "widget", "ExporterExecutorRowWidget.java")
MODE_LINES = (
    # (宿主源码, 语言键前缀, 后缀, 宿主里拼接它的写法, 说明)
    (WIDGET_REL, EXPORTER_LANG, "strategy.target", '"strategy.target"', "供应策略：达标"),
    (WIDGET_REL, EXPORTER_LANG, "strategy.materials", '"strategy.materials"', "供应策略：只管输出原料"),
    (WIDGET_REL, FORCE_LANG, "restore", '"restore"', "强制普通总线：恢复"),
    (WIDGET_REL, FORCE_LANG, "button", '"button"', "强制普通总线：按钮"),
    (WIDGET_REL, FORCE_LANG, "restore.tip", '"restore.tip"', "强制普通总线：恢复提示"),
    (WIDGET_REL, FORCE_LANG, "button.tip", '"button.tip"', "强制普通总线：按钮提示"),
    (WIDGET_REL, IMPORTER_LANG, "auto.on", '"auto." + (auto ? "on" : "off")', "自动收回：开（输入总线专属）"),
    (WIDGET_REL, IMPORTER_LANG, "auto.off", '"auto." + (auto ? "on" : "off")', "自动收回：关（输入总线专属）"),
    (WIDGET_REL, IMPORTER_LANG, "auto.title", '"auto.title"', "自动收回：标题"),
    (WIDGET_REL, IMPORTER_LANG, "auto.tip", '"auto.tip"', "自动收回：说明"),
    (WIDGET_REL, IMPORTER_LANG, "auto.toggle.tip", '"auto.toggle.tip"', "自动收回：切换提示"),
    ("client/widget/ImporterExecutorBarSource.java", IMPORTER_LANG, "auto.skip.tip",
     '"auto.skip.tip"', "自动收回：输入类不收"),
    ("client/widget/ImporterExecutorBarSource.java", IMPORTER_LANG, "auto.collect.tip",
     '"auto.collect.tip"', "自动收回：这一类自动收"),
)
CLASSIFICATION_KEYS = ("group.materials", "group.feedstock", "group.products",
                       "group.scrap", "group.intermediates", "ingredients.name")
mode_missing = []
mode_bad_class = []
mode_sources = {rel: read(rel) for rel, _p, _s, _n, _w in MODE_LINES}
for rel, prefix, suffix, needle, _why in MODE_LINES:
    text = mode_sources[rel]
    if (prefix + suffix) not in zh or (prefix + suffix) not in en:
        mode_missing.append(prefix + suffix)
    if needle not in text:
        mode_missing.append("%s(宿主没说: %s)" % (suffix, needle))
    if any(key in text for key in CLASSIFICATION_KEYS):
        mode_bad_class.append(os.path.basename(rel))
check("1f 全部「开关 → 文案」都只切模式名（%d 条动态键中英齐全、宿主自证、无一提分类键）"
      % len(MODE_LINES),
      not mode_missing and not mode_bad_class,
      "missing=%s bad=%s" % (mode_missing, mode_bad_class))
# 1f2：全工程只允许「附加标注」那一处把策略说成一句标注；反向再扫一遍，
#      确认没有任何源码文件把策略开关与「分类沿用旧标签」绑在一起。
hits = []
for base, _dirs, files in os.walk(SRC):
    for name in files:
        if not name.endswith(".java"):
            continue
        with io.open(os.path.join(base, name), encoding="utf-8", errors="ignore") as handle:
            body = handle.read()
        for lineno, line in enumerate(body.splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith("*") or stripped.startswith("//"):
                continue
            if POLICY_READ.search(line):
                hits.append("%s:%d" % (name, lineno))
check("1f2 策略开关的代码读取点全工程 ≤8 处"
      "（界面附加标注 1 + 执行仓备料闸门 1 + 策略本体 1 + 命令 2 + 同步 2 + RS 预览 1）",
      len(hits) <= 8, "hits=%s" % hits)

# ======================================================================
# 2) 标注以独立文本存在
# ======================================================================
section("2) 「优先使用中间产物」以独立标注存在（专用键 + 逐字文案）")

check("2a zh/en 键集合一致", set(zh) == set(en))
check("2b 样板终端的附加标注键存在且中英非空",
      zh.get(ANNOTATION_KEY, "").strip() and en.get(ANNOTATION_KEY, "").strip(),
      "key=%s" % ANNOTATION_KEY)
check("2c 中文文案逐字 = 「%s」" % ANNOTATION_TEXT, zh.get(ANNOTATION_KEY) == ANNOTATION_TEXT,
      "zh=%r" % zh.get(ANNOTATION_KEY))
check("2d RS 预览界面那行同口径（也是这句附加标注，不是分类名）",
      zh.get(PREVIEW_KEY) == ANNOTATION_TEXT and en.get(PREVIEW_KEY, "").strip(),
      "preview=%r" % zh.get(PREVIEW_KEY))
check("2e 源码确实引用了这个专用键（不是只躺在语言文件里）",
      ('LANG + "reuse.annotation"' in screen) and (TERMINAL_LANG + "reuse.annotation" not in screen
                                                   or "reuse.annotation" in screen))
for key in RETIRED_KEYS:
    check("2f 修前的「分类改写键」已消失：%s" % key, key not in zh and key not in en)
    check("2g 源码里也没有再引用它：%s" % key,
          key not in screen and ('"input_slot.intermediate"' not in screen))
# 2h：「中间产物」这个词仍然只作为<b>类别名</b>（类别详细配置界面 + Jade 分节）存在 —— 本轮不删类别，
#     也不许把类别名与策略混为一谈（策略那句是 reuse.annotation）。
BUS_GROUP_KEY = "gui.rs_create_compat.bus_config.group.intermediates"
bus_screen = read(os.path.join("client", "screen", "BusCategoryConfigScreen.java"))
check("2h 「中间产物」仍作为类别名存在（类别详细配置界面 groupLang() + Jade 分节同源同键）",
      zh.get(BUS_GROUP_KEY, "").strip() and en.get(BUS_GROUP_KEY, "").strip()
      and 'return LANG + "group." + section;' in bus_screen
      and 'GROUP_LANG + groupId' in read(os.path.join("compat", "jade", "RsccBusJadePayload.java"))
      and "intermediates" in bus_screen,
      "zh=%r" % zh.get(BUS_GROUP_KEY))

# ======================================================================
# 3) 反例：修前写法必须被判 FAIL
# ======================================================================
section("3) 反例（负向自证）：把修前写法喂给同一批判据，必须 FAIL")

# 3a：真实的修前代码（本轮动手前 HEAD 里的那一版，逐字取自 git 历史；
#     它是「开关为开 ⇒ 本格改画过渡件 + tooltip 换成过渡件 + 末尾贴『中间产物（优先复用）』」）。
PREFIX_OLD_RENDER = """
    private void renderInputSlot(final GuiGraphics guiGraphics, final Slot slot) {
        final ItemStack intermediate = reuseIntermediate();
        if (!intermediate.isEmpty()) {
            guiGraphics.renderItem(intermediate, slot.x, slot.y);
            return;
        }
        final List<ItemStack> candidates = assemblyInputCandidates();
        if (candidates.size() > 1) {
            guiGraphics.renderItem(GhostMarkerRenderer.cycleCandidate(candidates), slot.x, slot.y);
            return;
        }
        super.renderSlot(guiGraphics, slot);
    }
"""
PREFIX_OLD_LABELS = """
    protected void renderLabels(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        guiGraphics.drawString(font, Component.translatable(LANG
            + (reuseIntermediate().isEmpty() ? "op.input" : "op.intermediate")),
            INPUT_LABEL_X, OP_LABEL_Y, COLOR_LABEL, false);
    }
"""
PREFIX_OLD_TOOLTIP = """
    private void renderInputSlotTooltip(final GuiGraphics guiGraphics, final int mouseX, final int mouseY) {
        final ItemStack intermediate = reuseIntermediate();
        if (!intermediate.isEmpty()) {
            always.addAll(itemTooltipLines(intermediate));
            always.add(Component.translatable(LANG + "input_slot.intermediate"));
            renderWithItemLines(guiGraphics, always, layered, mouseX, mouseY);
            return;
        }
    }
"""

old_ok, old_problems = classification_verdict(
    "prefix", PREFIX_OLD_RENDER, PREFIX_OLD_LABELS, PREFIX_OLD_TOOLTIP, None)
check("3a 修前写法被判 FAIL（改画过渡件 / 分类标题二选一 / tooltip 换掉物品行，三条全中）",
      not old_ok and len(old_problems) >= 3, "; ".join(old_problems))
check("3a2 命中的理由里明确含「过渡件判据」与「二选一」两类（就是旧语义的两个特征写法）",
      any("过渡件判据" in p for p in old_problems) and any("二选一" in p for p in old_problems))
check("3a3 旧 tooltip 的两个特征（换掉物品行 + 贴分类键）都在反例里被判据捕获",
      "input_slot.intermediate" in PREFIX_OLD_TOOLTIP
      and "itemTooltipLines(intermediate)" in PREFIX_OLD_TOOLTIP
      and not classification_verdict("p2", PREFIX_OLD_RENDER, PREFIX_OLD_LABELS, PREFIX_OLD_TOOLTIP, None)[0])

# 3b：判据<b>不是</b>恒假 —— 当前真实源码必须 PASS（同一批函数，见 §1a）
check("3b 同一批判据对当前源码判 PASS（上面的 FAIL 是真的抓到了旧语义，不是判据恒假）",
      classification_verdict("now", render_body, labels_body, None, policy_body)[0])

# 3c：把「分类标题二选一」单独注入当前源码，必须仍然被抓到
mutated = labels_body.replace('LANG + "op.input"', 'LANG + (reuseAnnotation().isPresent() ? "op.input" : "op.intermediate")')
check("3c 变异测试：把二选一写法注回 renderLabels ⇒ 判据立刻 FAIL",
      mutated != labels_body
      and not classification_verdict("mut", render_body, mutated, None, policy_body)[0])

# 3d：把「改画过渡件」注回绘制方法，必须仍然被抓到
mutated_render = render_body.replace("final List<ItemStack> candidates = assemblyInputCandidates();",
                                     "if (!reusableIntermediate().isEmpty()) { return; }\n        final List<ItemStack> candidates = assemblyInputCandidates();")
check("3d 变异测试：把「按开关改画过渡件」注回 renderInputSlot ⇒ 判据立刻 FAIL",
      mutated_render != render_body
      and not classification_verdict("mut2", mutated_render, labels_body, None, policy_body)[0])

print()
print("=" * 78)
if FAILURES:
    print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
    for item in FAILURES:
        print("  - %s" % item)
    sys.exit(1)
print("SELFCHECK OK (%d checks)" % CHECKS[0])
sys.exit(0)
