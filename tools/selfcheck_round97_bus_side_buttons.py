#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""round97 自检 A：**原生侧边按钮绝不被删除**（用户本轮最严重投诉，纯 Python 源码检查）。

======================================================================
一、RS 2.0.9 侧边按钮的「期望集合」（证据来自 sources jar，非猜测）
======================================================================
证据 jar：
  /d/gradle/caches/modules-2/files-2.1/com.refinedmods.refinedstorage/
  refinedstorage-neoforge/2.0.9/fdec378e535dd44163ce352f85c2fba0482137b3/
  refinedstorage-neoforge-2.0.9-sources.jar

  - common/importer/ImporterScreen.java
        :21  addSideButton(new FilterModeSideButtonWidget(...))   ← 过滤器模式（白 / 黑名单）
        :26  addSideButton(new FuzzyModeSideButtonWidget(...EXTRACTING_SOURCE))  ← 模糊模式
  - common/exporter/ExporterScreen.java
        :20  addSideButton(new FuzzyModeSideButtonWidget(...EXTRACTING_STORAGE_NETWORK))  ← 模糊模式
        :24  addSideButton(new SchedulingModeSideButtonWidget(...))  ← 调度模式
  - common/support/AbstractFilterScreen.java
        :39  addSideButton(new RedstoneModeSideButtonWidget(...))  ← 红石模式（基类统一挂）
  - common/support/AbstractBaseScreen.java
        :149 public void addSideButton(...)   ← 唯一的「挂侧边按钮」入口（声明在基类自身）
        :90  protected void init() { clearWidgets(); super.init(); ... }  ← 每次 init 都是「清空后重建」

于是界面上的完整集合是：
  **importer = FilterModeSideButtonWidget + FuzzyModeSideButtonWidget + RedstoneModeSideButtonWidget**
  **exporter = FuzzyModeSideButtonWidget + SchedulingModeSideButtonWidget + RedstoneModeSideButtonWidget**

======================================================================
二、本脚本钉住的不变式
======================================================================
A1 `mixin/client/AbstractBaseScreenMixin.java` 里**没有**任何针对 `addSideButton` 的注入
   （`@Inject` / `@Redirect` / `@ModifyVariable` / `@WrapOperation` / `@WrapWithCondition` 都不许），
   也不许出现 `addSideButton` 这个字面量（**连注释里也不写**：这样任何人 grep 一眼就能判真伪）。
A2 该文件里**没有** `FilterModeSideButtonWidget` / `FuzzyModeSideButtonWidget` /
   `SchedulingModeSideButtonWidget` 的 import 或类型引用，也**没有** `ImporterScreen` /
   `AbstractSideButtonWidget` 的残留 import（它们只服务于已被删除的那个注入）。
A3 全 `src/main/java` 中 `addSideButton` 字面量出现次数为 **0**。
A4 `mixin/client/ImporterScreenMixin.java` 不引用上述两个按钮类；它的注入只有
   `init @ TAIL`（**不可 cancel**），即「只追加自绘类别条」。
A5 `mixin/client/ExporterScreenMixin.java` 行为未被改动：注入只有 `init @ TAIL`（不可 cancel）与
   `renderTooltip @ HEAD`（cancellable，用于把自己的 tooltip 接力，属「追加」而非删控件），
   且不引用任何侧边按钮类。
A6 **全仓**没有任何注入把目标指向「加控件 / 删控件」的方法：
   `addSideButton` / `addRenderableWidget` / `addWidget` / `clearWidgets` / `removeWidget`。
   （这是本轮的根因形态：上一版的注入就是在 `addSideButton` 的 HEAD 处无条件 `ci.cancel()`。）
A7 `AbstractBaseScreenMixin` **仍然保留**两个合法注入（`mouseClicked` 抢点击、`renderTooltip` 接力）——
   防止「修删除时把该留的也删了」这种反向回归。
A8 两个文件的 javadoc 里必须有那条硬规则的中文表述（「原生侧边按钮一律保留」「绝不删除 RS 原生控件」），
   并且**不许**再出现「本轮移除了两个…控件」这类已删除行为的表述。

======================================================================
三、为什么这个脚本能真的抓到回归
======================================================================
所有断言都是「行级正则 + 方法块扫描」：

  * `injections()` 用**括号配平**解析每个 `@Inject(...)` 的完整参数（不是 `[^)]*`，
    否则 `@At("HEAD")` 里的右括号会把 `cancellable = true` 截断 —— 这正是最容易写错的地方）；
  * `method_block()` 按大括号配平取出注入方法体，于是「方法名叫什么」不是唯一判据，
    `ci.cancel()` 出现的位置同样被检查；
  * **反例自证写在脚本内部**（第 4 节）：把上一版真实的 `rscc$hideImporterFilterButtons`
    注入拼进一份虚拟源码，同一套 `audit()` 必须判负 —— 这条自带证明不依赖人工实验。

把 `rscc$hideImporterFilterButtons` 还原回 `AbstractBaseScreenMixin.java` 后，本脚本必须 FAIL
（A1/A2/A3 三条会同时红）。Lead 可实测。

用法：python tools/selfcheck_round97_bus_side_buttons.py
退出码：0 = 全部通过。
"""

from __future__ import annotations

import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = os.path.join(ROOT, "src", "main", "java")
PKG = os.path.join(JAVA, "cretae", "cookiewyq", "rs_create_compat")
MIXIN_CLIENT = os.path.join(PKG, "mixin", "client")

ABS_PATH = os.path.join(MIXIN_CLIENT, "AbstractBaseScreenMixin.java")
IMP_PATH = os.path.join(MIXIN_CLIENT, "ImporterScreenMixin.java")
EXP_PATH = os.path.join(MIXIN_CLIENT, "ExporterScreenMixin.java")

NEWLINE = chr(10)
CHECKS = [0]
PROBLEMS = []

# 注入注解全集（任何一个把目标指到「加控件」方法上都是回归）
INJECT_ANNS = ("Inject", "Redirect", "ModifyVariable", "WrapOperation", "WrapWithCondition",
               "ModifyExpressionValue")
# 「加控件 / 删控件」的方法名黑名单
WIDGET_METHODS = ("addSideButton", "addRenderableWidget", "addWidget", "clearWidgets", "removeWidget")
# 被删掉的那两个侧边按钮类（+ 同族的调度模式，一并禁止在本模组的 mixin 里出现）
FORBIDDEN_BUTTON_CLASSES = ("FilterModeSideButtonWidget", "FuzzyModeSideButtonWidget",
                            "SchedulingModeSideButtonWidget")


# ==================== 基础设施 ====================

def check(desc, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS  %02d  %s" % (CHECKS[0], desc))
    else:
        PROBLEMS.append("%s%s" % (desc, ("  <- " + detail) if detail else ""))
        print("FAIL  %02d  %s%s" % (CHECKS[0], desc, ("  <- " + detail) if detail else ""))
    return ok


def read(path):
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(text):
    """去掉 // 与 /* */ 注释。这些文件里没有把 '//' 放进字符串字面量的写法。"""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    out = []
    for line in text.split(NEWLINE):
        cut = line.find("//")
        out.append(line if cut < 0 else line[:cut])
    return NEWLINE.join(out)


def balanced(text, open_index):
    """从 `(` 出发做括号配平，返回 (括号内正文, 右括号下标)。"""
    depth = 0
    index = open_index
    while index < len(text):
        char = text[index]
        if char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return text[open_index + 1:index], index
        index += 1
    return "", len(text)


def injections(text):
    """扫描源码里的注入注解，返回 [dict]。

    每个 dict：annotation / args / method（method= 目标）/ at（@At 的位置）/ cancellable / line。
    **为什么要括号配平**：`@Inject(method = "x", at = @At("HEAD"), cancellable = true)` 里
    `@At("HEAD")` 自带右括号，用 `[^)]*` 会把 `cancellable` 吞掉 —— 那样「取消型注入」就永远查不出来。
    """
    code = strip_comments(text)
    found = []
    pattern = re.compile(r"@(" + "|".join(INJECT_ANNS) + r")\s*\(")
    for match in pattern.finditer(code):
        start = code.find("(", match.start())
        args, _end = balanced(code, start)
        target = re.search(r'method\s*=\s*"([^"]+)"', args)
        at = re.search(r'at\s*=\s*@At\(\s*(?:value\s*=\s*)?"([A-Z_]+)"', args)
        found.append({
            "annotation": match.group(1),
            "args": args,
            "method": target.group(1) if target else "",
            "at": at.group(1) if at else "",
            "cancellable": "cancellable = true" in args,
            "line": code.count(NEWLINE, 0, match.start()) + 1,
        })
    return found


def method_block(text, start_index):
    """从 `{` 开始按大括号配平取出方法体（用于检查 `ci.cancel()` 落在哪个方法里）。"""
    brace = text.find("{", start_index)
    if brace < 0:
        return ""
    depth = 0
    index = brace
    while index < len(text):
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return text[brace + 1:index]
        index += 1
    return ""


def count_literal(files, needle):
    """全仓统计某个字面量出现的次数（含注释：本轮的硬口径就是「连注释里都不写」）。"""
    hits = []
    for path in files:
        text = read(path)
        for line_no, line in enumerate(text.split(NEWLINE), start=1):
            if needle in line:
                hits.append("%s:%d" % (os.path.relpath(path, ROOT).replace("\\", "/"), line_no))
    return hits


def java_files():
    out = []
    for base, _dirs, files in os.walk(JAVA):
        for name in files:
            if name.endswith(".java"):
                out.append(os.path.join(base, name))
    return sorted(out)


def referencing(sources, class_name):
    """返回 [文件] 中提到该类型的文件（按 `import` 或裸类名引用）。"""
    hits = []
    for path, text in sources.items():
        if re.search(r"\b%s\b" % re.escape(class_name), text):
            hits.append(path)
    return hits


# ==================== 审计主体 ====================

def by_name(repo, name):
    """按文件名从 {相对路径: 源码} 里取源码（找不到返回空串）。"""
    for path, text in repo.items():
        if os.path.basename(path) == name:
            return text
    return ""


def audit(repo, repo_files):
    """对给定源码仓库执行全部判据，返回问题列表（空 = 通过）。

    参数化是为了「反例自证」：把上一版的注入拼进虚拟源码，同一套判据必须判负。
    """
    problems = []

    # ---------- A1 / A2 / A7：AbstractBaseScreenMixin ----------
    abs_src = by_name(repo, "AbstractBaseScreenMixin.java")
    if "addSideButton" in abs_src:
        problems.append("A1 AbstractBaseScreenMixin 仍出现 addSideButton 字面量（注入或注释）")
    for info in injections(abs_src):
        if info["method"] in WIDGET_METHODS:
            problems.append("A1 AbstractBaseScreenMixin 存在以 %s 为目标的注入（第 %d 行）"
                            % (info["method"], info["line"]))
    for cls in FORBIDDEN_BUTTON_CLASSES:
        if re.search(r"\b%s\b" % cls, abs_src):
            problems.append("A2 AbstractBaseScreenMixin 仍引用按钮类 %s" % cls)
    for leftover in ("ImporterScreen", "AbstractSideButtonWidget"):
        # 只查 **import 行**：这两个名字在 javadoc 里作为说明文字出现是正常的
        # （例如「输入总线界面（{@code ImporterScreen}）自己声明了 renderTooltip」），
        # 但「还留着一条没人用的 import」就说明删除不干净。
        stale = [line.strip() for line in abs_src.split(NEWLINE)
                 if line.strip().startswith("import ")
                 and re.search(r"\b%s\b" % leftover, line)]
        if stale:
            problems.append("A2 AbstractBaseScreenMixin 仍残留只服务于已删注入的 import：%s" % leftover)
    targets = [info["method"] for info in injections(abs_src)]
    if "mouseClicked" not in targets:
        problems.append("A7 丢掉了合法注入 mouseClicked（抢点击）")
    if "renderTooltip" not in targets:
        problems.append("A7 丢掉了合法注入 renderTooltip（tooltip 接力）")

    # ---------- A3：全仓 addSideButton 字面量 0 次 ----------
    hits = count_literal(repo_files, "addSideButton")
    if hits:
        problems.append("A3 全仓仍出现 addSideButton 字面量 %d 处：%s"
                        % (len(hits), ", ".join(hits[:6])))

    # ---------- A4：ImporterScreenMixin ----------
    imp_src = by_name(repo, "ImporterScreenMixin.java")
    for cls in ("FilterModeSideButtonWidget", "FuzzyModeSideButtonWidget"):
        if re.search(r"\b%s\b" % cls, imp_src):
            problems.append("A4 ImporterScreenMixin 引用按钮类 %s" % cls)
    imp_inj = injections(imp_src)
    if not imp_inj:
        problems.append("A4 ImporterScreenMixin 找不到 init 注入（类别条没挂上去）")
    for info in imp_inj:
        if info["method"] != "init":
            problems.append("A4 ImporterScreenMixin 出现非 init 注入：%s" % info["method"])
        if info["at"] != "TAIL":
            problems.append("A4 ImporterScreenMixin 的 init 注入不在 TAIL（可能影响原生 init）")
        if info["cancellable"]:
            problems.append("A4 ImporterScreenMixin 的 init 注入是 cancellable（可能吞掉原生 init）")

    # ---------- A5：ExporterScreenMixin 行为未变 ----------
    exp_src = by_name(repo, "ExporterScreenMixin.java")
    for cls in FORBIDDEN_BUTTON_CLASSES:
        if re.search(r"\b%s\b" % cls, exp_src):
            problems.append("A5 ExporterScreenMixin 引用按钮类 %s（本轮不许动输出总线）" % cls)
    exp_inj = injections(exp_src)
    exp_targets = sorted(info["method"] for info in exp_inj)
    if exp_targets != ["init", "renderTooltip"]:
        problems.append("A5 ExporterScreenMixin 注入集合变了：%s（本轮应为 init + renderTooltip）"
                        % exp_targets)
    for info in exp_inj:
        if info["method"] == "init" and (info["at"] != "TAIL" or info["cancellable"]):
            problems.append("A5 ExporterScreenMixin 的 init 注入必须 TAIL 且不可 cancel")
        if info["method"] == "renderTooltip" and not info["cancellable"]:
            problems.append("A5 ExporterScreenMixin 的 renderTooltip 接力必须 cancellable")

    # ---------- A6：全仓没有任何「加/删控件」型注入 ----------
    for path, text in repo.items():
        if not path.endswith(".java"):
            continue
        for info in injections(text):
            if info["method"] in WIDGET_METHODS:
                problems.append("A6 %s 第 %d 行的注入目标是 %s（删除原生控件的形态）"
                                % (path, info["line"], info["method"]))

    # ---------- A8：javadoc 的硬规则表述 ----------
    if "绝不删除 RS 原生控件" not in abs_src:
        problems.append("A8 AbstractBaseScreenMixin 的 javadoc 缺少「绝不删除 RS 原生控件」")
    if "原生侧边按钮一律保留" not in imp_src:
        problems.append("A8 ImporterScreenMixin 的 javadoc 缺少「原生侧边按钮一律保留」")
    if "绝不删除 RS 原生控件" not in imp_src:
        problems.append("A8 ImporterScreenMixin 的 javadoc 缺少「绝不删除 RS 原生控件」")
    for stale in ("本轮同时移除了两个", "不再挂上", "不再挂"):
        if stale in imp_src or stale in abs_src:
            problems.append("A8 仍有「删掉原生控件」的过期表述：%r" % stale)

    return problems


# ==================== 载入真实源码 ====================

def load_sources():
    sources = {}
    for path in java_files():
        sources[os.path.relpath(path, JAVA).replace("\\", "/")] = read(path)
    return sources


ALL_FILES = java_files()
SOURCES = load_sources()

if not os.path.exists(ABS_PATH) or not os.path.exists(IMP_PATH) or not os.path.exists(EXP_PATH):
    print("[result] 自检失败：找不到要检查的 mixin 文件")
    sys.exit(1)


# ==================== 1. 三个关键文件的「注入清单」（可读输出，便于人工复核） ====================

print("---- 1. 三个关键 mixin 的注入清单（目标方法 / @At / cancellable）----")
for label, path in (("AbstractBaseScreenMixin", ABS_PATH),
                    ("ImporterScreenMixin", IMP_PATH),
                    ("ExporterScreenMixin", EXP_PATH)):
    listing = injections(read(path))
    summary = ", ".join("%s@%s%s" % (i["method"], i["at"] or "?", "(cancel)" if i["cancellable"] else "")
                        for i in listing) or "（无注入）"
    print("      %-26s %s" % (label, summary))

# ==================== 2. 正例：当前源码必须全部通过 ====================

print("---- 2. 当前源码审计 ----")
issues = audit(SOURCES, ALL_FILES)
check("A1+A2 AbstractBaseScreenMixin 不再拦截挂侧边按钮 / 不再引用两个按钮类、且两个合法注入仍在",
      not [i for i in issues if i.startswith(("A1", "A2", "A7"))],
      "; ".join([i for i in issues if i.startswith(("A1", "A2", "A7"))]))
check("A3 全仓 src/main/java 的 addSideButton 字面量为 0 次",
      not [i for i in issues if i.startswith("A3")],
      "; ".join([i for i in issues if i.startswith("A3")]))
check("A4 ImporterScreenMixin 只引用自绘控件，注入只有 init@TAIL 且不可 cancel",
      not [i for i in issues if i.startswith("A4")],
      "; ".join([i for i in issues if i.startswith("A4")]))
check("A5 ExporterScreenMixin 行为未变（init@TAIL + renderTooltip@HEAD 接力，不引用按钮类）",
      not [i for i in issues if i.startswith("A5")],
      "; ".join([i for i in issues if i.startswith("A5")]))
check("A6 全仓没有任何注入把目标指向 addSideButton / addRenderableWidget / addWidget / clearWidgets / removeWidget",
      not [i for i in issues if i.startswith("A6")],
      "; ".join([i for i in issues if i.startswith("A6")]))
check("A8 两个文件的 javadoc 已改写为「只追加、绝不删除原生控件」且无过期表述",
      not [i for i in issues if i.startswith("A8")],
      "; ".join([i for i in issues if i.startswith("A8")]))

# ==================== 3. 附加：RS 期望集合的登记（注释级自我说明） ====================

rs_expected = [
    "importer = FilterModeSideButtonWidget + FuzzyModeSideButtonWidget + RedstoneModeSideButtonWidget",
    "exporter = FuzzyModeSideButtonWidget + SchedulingModeSideButtonWidget + RedstoneModeSideButtonWidget",
]
header = __doc__ or ""
check("B1 文件头注释记录了 RS 2.0.9 的期望按钮集合（importer / exporter 两行都在）",
      all(item in header for item in rs_expected),
      "缺：%s" % [item for item in rs_expected if item not in header])
check("B2 文件头注释记录了证据来源（refinedstorage-neoforge-2.0.9-sources.jar + 具体行号）",
      "refinedstorage-neoforge-2.0.9-sources.jar" in header
      and "ImporterScreen.java" in header and "AbstractFilterScreen.java" in header)

# 这些按钮类在**本模组源码里**应当完全不再被引用（它们只属于 RS 原生）
repo_sources = {os.path.relpath(p, JAVA).replace("\\", "/"): read(p) for p in ALL_FILES}
for cls in FORBIDDEN_BUTTON_CLASSES:
    users = [p for p in referencing(repo_sources, cls) if not p.startswith("mixin/client/")]
    check("B3 侧边按钮类 %s 未被任何非 mixin/client 的本模组源码引用" % cls, not users, str(users))

# ==================== 4. 反例自证：把上一版真实的注入拼回去，同一套判据必须判负 ====================

print("---- 4. 反例自证（上一版的注入拼回虚拟源码，audit() 必须判负）----")

OLD_METHOD = """
    /**
     * 「输入总线」上两个语义不再适用的侧边按钮：不再挂上「过滤器模式（白 / 黑名单）」与「模糊模式」。
     */
    @Inject(method = "addSideButton", at = @At("HEAD"), cancellable = true)
    private void rscc$hideImporterFilterButtons(final AbstractSideButtonWidget button,
                                                final CallbackInfo ci) {
        if ((Object) this instanceof ImporterScreen
            && (button instanceof FilterModeSideButtonWidget || button instanceof FuzzyModeSideButtonWidget)) {
            ci.cancel();
        }
    }
"""

ABS_KEY = [k for k in SOURCES if os.path.basename(k) == "AbstractBaseScreenMixin.java"][0]
IMP_KEY = [k for k in SOURCES if os.path.basename(k) == "ImporterScreenMixin.java"][0]
CASE_ABS = SOURCES[ABS_KEY].replace(
    "\n    @Inject(method = \"renderTooltip\"", OLD_METHOD + "\n    @Inject(method = \"renderTooltip\"")
case_sources = dict(SOURCES)
case_sources[ABS_KEY] = CASE_ABS
# 反例要把「只服务于该注入的 import」也加回去，才等同于上一版的真实文件
CASE_ABS = (CASE_ABS
            .replace("import com.refinedmods.refinedstorage.common.support.AbstractBaseScreen;",
                     "import com.refinedmods.refinedstorage.common.importer.ImporterScreen;\n"
                     "import com.refinedmods.refinedstorage.common.storage.FilterModeSideButtonWidget;\n"
                     "import com.refinedmods.refinedstorage.common.support.AbstractBaseScreen;\n"
                     "import com.refinedmods.refinedstorage.common.support.widget.AbstractSideButtonWidget;\n"
                     "import com.refinedmods.refinedstorage.common.support.widget.FuzzyModeSideButtonWidget;")
            .replace(" * <p><b>为什么不遮挡 RS 原生控件</b>", " * <p><b>为什么不遮挡 RS 原生控件</b>") )
case_sources[ABS_KEY] = CASE_ABS

# 反例里的全仓扫描也要能命中 → 用一个包含该字面量的文件名集合模拟
case_repo = list(ALL_FILES)
case_issues = audit(case_sources, case_repo)
case_kinds = sorted({item.split(" ", 1)[0] for item in case_issues})
check("C1 反例：把 rscc$hideImporterFilterButtons 拼回去 ⇒ A1（注入）判负",
      any(item.startswith("A1") for item in case_issues),
      "只抓到：%s" % case_issues)
check("C2 反例：同一份源码 ⇒ A2（按钮类 import / 引用）判负",
      any(item.startswith("A2") for item in case_issues))
check("C3 反例：同一份源码 ⇒ A8（过期表述「不再挂上」）判负",
      any(item.startswith("A8") for item in case_issues))
check("C4 反例整体至少触发 2 类判据（说明判据不是空转的）", len(case_kinds) >= 2,
      "只触发：%s" % case_kinds)

# 第二个反例：只把「注释里的字面量」加回去 —— A1 的「连注释里都不写」也要判负
case2 = dict(SOURCES)
case2[ABS_KEY] = SOURCES[ABS_KEY].replace(
    " * 更不做 {@code clearWidgets()} 之后不重建。",
    " * 更不做 {@code clearWidgets()} 之后不重建（也不注入 addSideButton）。")
issues2 = audit(case2, case_repo)
check("C5 反例：只在注释里写回 addSideButton 也必须判负（grep 一眼可判的口径）",
      any("addSideButton 字面量" in item for item in issues2), "只抓到：%s" % issues2)

# 第三个反例：把 init 注入改成 cancellable —— A4 必须抓到
case3 = dict(SOURCES)
case3[IMP_KEY] = SOURCES[IMP_KEY].replace(
    '@Inject(method = "init", at = @At("TAIL"))',
    '@Inject(method = "init", at = @At("TAIL"), cancellable = true)')
issues3 = audit(case3, case_repo)
check("C6 反例：把 ImporterScreenMixin 的 init 注入改成 cancellable 必须判负（会吞原生 init）",
      any(item.startswith("A4") for item in issues3), "只抓到：%s" % issues3)


# ==================== 汇总 ====================
print("=" * 78)
if PROBLEMS:
    print("[result] 自检失败：%d 个问题（断言 %d 项）" % (len(PROBLEMS), CHECKS[0]))
    for item in PROBLEMS:
        print("  - %s" % item)
    sys.exit(1)
print("[result] 自检通过：%d 项断言（含 6 条反例自证）；"
      "输入总线原生侧边按钮（白/黑名单 + 模糊 + 红石）输出总线（模糊 + 调度 + 红石）全部保留，"
      "本模组对 RS 界面只追加、不删除" % CHECKS[0])
sys.exit(0)
