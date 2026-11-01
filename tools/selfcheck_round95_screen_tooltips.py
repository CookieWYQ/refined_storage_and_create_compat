#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""round95 自检：**所有界面都必须手动绘制 tooltip**（用户第 7/8 条）。

用户原话：
    「增加一个新的自检文件检测所有界面的 tooltip 是否手动进行绘制……你自己去读取一下之前
      所有的那些界面你就会发现其实我已经要求过不止一遍说这一个 tooltip 它 MC 是不会自动化的，
      你必须要手动调用一遍它才会绘制。这个容器代理器其实没有绘制的。」

事实（不是意见）：`AbstractContainerScreen#render` **不会**替子类调
`renderTooltip(GuiGraphics, mouseX, mouseY)` —— 那一行是原版特意留给子类的。
不写它，槽位高亮照常出现（`super.render` 会画），但**悬停不显示物品提示**。
round95 实测：全工程 14 个 `AbstractContainerScreen` 子类里，`InventoryProxyScreen` 是唯一
0 次 tooltip 调用的 —— 正是用户说的「容器代理器没有绘制」。

本脚本钉住的不变式（只读源码，不启动游戏）：

  ① 覆盖面：`client/screen/` 下所有 `extends AbstractContainerScreen` 的类都要被扫到
    （目录扫描，不是手写名单 —— 手写名单一定会漏掉将来新增的界面）。
  ② 可达性：每个容器界面必须存在一条从 `render(...)` / `renderLabels(...)` 出发、
    经 0~2 层本类方法、最终到达 tooltip 调用的路径。只写在从不被调用的方法里不算。
  ③ 常显：tooltip 调用不得被 `if (hasShiftDown())` / `if (isShiftKeyDown())` 包住 ——
    用户第 4 条明确说「按 Shift 显示详情 Jade 已经实现了」，本模组机器界面必须常显。
  ④ 其它界面：`extends Screen` / `extends ChildConfigScreen` 的界面，只要自己画了物品图标
    （`renderItem(`），就必须有 tooltip 路径（原版 `renderTooltip` / `renderComponentTooltip` /
    本工程自己的 `RsccTooltipLayers.render` / `renderAttached`，后两者内部就是 `renderTooltip`）。
  ⑤ 反例自证 ×5：把某个界面的 tooltip 调用删掉 / 挪进死方法 / 套上 Shift 条件 /
    新加一个不画 tooltip 的界面 / 删掉非容器界面的 tooltip ⇒ 对应断言必须判负。
    任何一条反例不再被抓住 ⇒ 对应断言是空转的。

用法：python tools/selfcheck_round95_screen_tooltips.py
退出码：0 = 全部 PASS。
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
SCREEN_DIR = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq",
                          "rs_create_compat", "client", "screen")

NEWLINE = chr(10)
TAB = chr(9)

TOOLTIP_CALL = re.compile(r"(?:render(?:Component)?Tooltip|renderAttached)")
# 「一行以内的 tooltip 调用语句」：给反例替换用；不跨行，所以注释里写 renderTooltip(GuiGraphics,int,int)
# 不会被误吞（这正是第一版自检的假红原因）。
SIMPLE_CALL_STMT = re.compile("(?:render(?:Component)?Tooltip)[ " + TAB + "]*[(][^;" + NEWLINE + "]*[)][ " + TAB + "]*;")
SHIFT_GUARD = re.compile(r"(?:hasShiftDown|isShiftKeyDown)[ \t]*[(][ \t]*[)]")

PROBLEMS = []
CHECKS = [0]


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if ok:
        print("PASS %s" % name)
    else:
        PROBLEMS.append(name)
        print("FAIL %s%s" % (name, ("  <- " + detail) if detail else ""))


def read(path):
    with io.open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments_and_strings(text):
    """注释 + 字符串字面量换成等长空白（行号不变）。判「代码里有没有调用」时用。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            j = text.find(NEWLINE, i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif ch == "/" and nxt == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(NEWLINE if c == NEWLINE else " " for c in text[i:j]))
            i = j
        elif ch == '"':
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == '"':
                    j += 1
                    break
                j += 1
            out.append(" " * (j - i))
            i = j
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def block_at(text, open_index):
    depth = 0
    for j in range(open_index, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return text[open_index + 1:j]
    return ""


def method_bodies(code):
    """{方法名: 方法体}。手写扫描（不用复杂正则，避免回溯卡死）。"""
    result = {}
    n = len(code)
    for index in range(n):
        if code[index] != "{":
            continue
        j = index - 1
        while j >= 0 and code[j].isspace():
            j -= 1
        if j < 0 or code[j] != ")":
            continue
        depth = 0
        k = j
        while k >= 0:
            if code[k] == ")":
                depth += 1
            elif code[k] == "(":
                depth -= 1
                if depth == 0:
                    break
            k -= 1
        if k <= 0:
            continue
        m = k - 1
        while m >= 0 and (code[m].isalnum() or code[m] in "_$"):
            m -= 1
        name = code[m + 1:k]
        if not name or not (name[0].isalpha() or name[0] in "_$"):
            continue
        body = block_at(code, index)
        if name not in result or len(body) > len(result[name]):
            result[name] = body
    return result


def class_decl(code):
    match = re.search(r"class[ \t]+(\w+)[ \t]+extends[ \t]+(\w+)", code)
    if not match:
        return "?", ""
    return match.group(1), match.group(2)


def reachable_tooltip(methods):
    """从 render / renderLabels / renderBg 出发，0~2 跳能否到达 tooltip 调用。"""
    roots = ("render", "renderLabels", "renderBg")
    if not any(TOOLTIP_CALL.search(body) for body in methods.values()):
        return False, "没有任何方法调用 tooltip"
    for root in roots:
        body = methods.get(root)
        if body is None:
            continue
        if TOOLTIP_CALL.search(body):
            return True, root
        for mid, mid_body in methods.items():
            if mid == root:
                continue
            if not re.search("(?:^|[^A-Za-z0-9_$])" + re.escape(mid) + "[ \t]*[(]", body):
                continue
            if TOOLTIP_CALL.search(mid_body):
                return True, root + " -> " + mid
            for leaf, leaf_body in methods.items():
                if leaf in (root, mid):
                    continue
                if not re.search("(?:^|[^A-Za-z0-9_$])" + re.escape(leaf) + "[ \t]*[(]", mid_body):
                    continue
                if TOOLTIP_CALL.search(leaf_body):
                    return True, root + " -> " + mid + " -> " + leaf
    return False, "render/renderLabels 里没有可达的 tooltip 调用"


def shift_guarded_tooltip(methods):
    offenders = []
    for name, body in methods.items():
        for match in SHIFT_GUARD.finditer(body):
            brace = body.find("{", match.end())
            if brace < 0 or brace - match.end() > 40:
                continue
            if TOOLTIP_CALL.search(block_at(body, brace)):
                offenders.append(name)
                break
    return offenders


def audit(container_code_by_name, generic_code_by_name=None):
    """返回判负的界面名列表（②③ + ④ 的口径）。"""
    bad = []
    for filename, code in container_code_by_name.items():
        methods = method_bodies(code)
        ok, _ = reachable_tooltip(methods)
        if not ok or shift_guarded_tooltip(methods):
            bad.append(filename)
    for filename, code in (generic_code_by_name or {}).items():
        methods = method_bodies(code)
        if not any(TOOLTIP_CALL.search(body) for body in methods.values()):
            bad.append(filename)
    return bad


# ==================================================================== 扫描
files = sorted(os.path.join(SCREEN_DIR, name) for name in os.listdir(SCREEN_DIR)
               if name.endswith(".java"))
raw = {path: read(path) for path in files}
container_paths = []
generic_icon_paths = []
for path in files:
    code = strip_comments_and_strings(raw[path])
    name, base = class_decl(code)
    if base == "AbstractContainerScreen":
        container_paths.append(path)
    elif base in ("Screen", "ChildConfigScreen") and "renderItem(" in code:
        generic_icon_paths.append(path)

print("=" * 78)
print("round95：所有界面必须手动绘制 tooltip（用户第 7/8 条）")
print("=" * 78)
print("[info] AbstractContainerScreen 子类：%d 个" % len(container_paths))
for path in container_paths:
    print("[info]   - " + os.path.basename(path))
print("[info] 画物品图标的非容器界面：%d 个" % len(generic_icon_paths))
for path in generic_icon_paths:
    print("[info]   - " + os.path.basename(path))

check("① 覆盖面：扫到 >= 10 个容器界面（少于这个数说明目录结构变了 / 扫描坏了）",
      len(container_paths) >= 10, "found=%d" % len(container_paths))

container_code = {os.path.basename(p): strip_comments_and_strings(raw[p]) for p in container_paths}
generic_code = {os.path.basename(p): strip_comments_and_strings(raw[p]) for p in generic_icon_paths}

# ② + ③
fail_reach = []
fail_shift = []
for name, code in container_code.items():
    methods = method_bodies(code)
    ok, route = reachable_tooltip(methods)
    if not ok:
        fail_reach.append("%s（%s）" % (name, route))
    bad_shift = shift_guarded_tooltip(methods)
    if bad_shift:
        fail_shift.append("%s: %s" % (name, ",".join(bad_shift)))
check("② 可达性：每个容器界面都存在 render/renderLabels → tooltip 的调用路径",
      not fail_reach, "; ".join(fail_reach))
check("③ 常显：tooltip 调用不得被 `if (hasShiftDown())` 这类条件包住（Shift 详情归 Jade）",
      not fail_shift, "; ".join(fail_shift))

# ④
fail_generic = [name for name, code in generic_code.items()
                if not any(TOOLTIP_CALL.search(body) for body in method_bodies(code).values())]
check("④ 其它界面：只要画了物品图标，就必须有 tooltip 路径",
      bool(generic_icon_paths) and not fail_generic,
      "没有扫到任何画图标的非容器界面（判据可能失效）" if not generic_icon_paths
      else "; ".join(fail_generic))

# ==================================================================== ⑤ 反例自证
victim = os.path.join(SCREEN_DIR, "InventoryProxyScreen.java")
assert os.path.isfile(victim), "找不到反例样本 InventoryProxyScreen.java"
proxy_name = os.path.basename(victim)
proxy_code = container_code[proxy_name]

case_code = SIMPLE_CALL_STMT.sub("", proxy_code)
check("⑤a 反例（删掉全部 tooltip 调用 ⇒ 判负）",
      len(audit({proxy_name: case_code})) == 1)

# 第 97 轮：InventoryProxyScreen 的 render 里现在有**两处** tooltip 调用（槽位物品提示 +
# 悬停六面按钮的 face_tip 提示），所以「只删第一处」已经不能制造「界面上没有任何可达 tooltip」
# 的反例了（删掉 face_tip 那处，槽位那处还在 ⇒ 断言应当仍然 PASS）。这里改成「全部 tooltip 调用
# 都挪进一个从不被调用的方法」—— 反例语义没变（仍然是「不画提示的界面必须判负」），只是把
# 注入强度提高到覆盖全部调用点。
moved = SIMPLE_CALL_STMT.sub("", proxy_code)
cut = moved.rfind("}")
moved = moved[:cut] + (
    NEWLINE + "    private void deadTooltip(GuiGraphics g, int x, int y) {" + NEWLINE +
    "        renderTooltip(g, x, y);" + NEWLINE + "    }" + NEWLINE + "}" + NEWLINE)
check("⑤b 反例（全部 tooltip 调用挪进从不被调用的方法 ⇒ 判负）",
      len(audit({proxy_name: moved})) == 1)

shift_wrapped = SIMPLE_CALL_STMT.sub(
    lambda m: "if (hasShiftDown()) {" + NEWLINE + "    " + m.group(0) + NEWLINE + "}", proxy_code, count=1)
check("⑤c 反例（tooltip 套上 if (hasShiftDown()) ⇒ 判负）",
      len(audit({proxy_name: shift_wrapped})) == 1)

fake = (
    "package x;" + NEWLINE +
    "public class FakeNoTooltipScreen extends AbstractContainerScreen<FakeMenu> {" + NEWLINE +
    "    @Override" + NEWLINE +
    "    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {" + NEWLINE +
    "        super.render(g, mouseX, mouseY, partialTick);" + NEWLINE +
    "    }" + NEWLINE + "}" + NEWLINE)
check("⑤d 反例（新界面有槽位却不画 tooltip ⇒ 判负）",
      len(audit({"FakeNoTooltipScreen.java": fake})) == 1)

fake_icon = (
    "package x;" + NEWLINE +
    "public class FakeIconScreen extends Screen {" + NEWLINE +
    "    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {" + NEWLINE +
    "        g.renderItem(ItemStack.EMPTY, 0, 0);" + NEWLINE +
    "    }" + NEWLINE + "}" + NEWLINE)
check("⑤e 反例（非容器界面画了图标却不画提示 ⇒ 判负）",
      len(audit({}, {"FakeIconScreen.java": fake_icon})) == 1)

print("=" * 78)
if PROBLEMS:
    print("[result] 自检失败：%d 个问题（断言 %d 项）" % (len(PROBLEMS), CHECKS[0]))
    for item in PROBLEMS:
        print("  - %s" % item)
    sys.exit(1)
print("[result] 自检通过：%d 项断言（含 5 个反例自证）；所有界面都手动绘制 tooltip 且常显" % CHECKS[0])
sys.exit(0)
