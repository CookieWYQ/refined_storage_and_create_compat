# -*- coding: utf-8 -*-
"""round 76：单元样板的「机器」与「输入池原料」改常显（不再按 Shift）。

用户原话（逐字）：
    「另一个单元样板因为不可能像总的（序列装配样板）一样使用某一些物品作为它的那个图标，
     所以说呢它的那些机器和它的输入池原料应该始终保持显示，不要改成 shift 再显示，
     就更改这个地方。」

口径（只改单元样板，总样板一字不动）：
- 单元样板图标组件 `UnitPatternTooltipComponent`：机器行 / 输入池原料行 = **常显**；
  输入流体行仍按 Ctrl（它带数量 mB，属数值层，不是用户点名的两块之一）。
- 总序列装配样板（`SequenceAssemblyPatternItem` 的文字层 + `AssemblyInputsTooltipComponent`
  的图标行）**保持现状**：原料 / 步骤输入仍走 Shift，流体仍走 Ctrl；总样板的图标区仍按用户
  之前的要求关闭（`if (true) return Optional.empty();`）。
- 行数控制：机器 / 输入池原料**各固定 1 行**（多候选只在行内轮播），常显行数恒
  ≤ `MAX_ALWAYS_ROWS = 2`，不随候选数量增长。

本自检全部是源码级判据（不启动游戏），并带**反例自证**：把修前的写法喂给同一批判据必须判负。

用法：python tools/selfcheck_round76_unit_pattern_always_visible.py
退出码 0 = 全部通过。
"""
import io
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

UNIT_ITEM = os.path.join(PKG, "item", "SequenceUnitPatternItem.java")
UNIT_COMP = os.path.join(PKG, "client", "tooltip", "UnitPatternTooltipComponent.java")
ASSEMBLY_ITEM = os.path.join(PKG, "item", "SequenceAssemblyPatternItem.java")
ASSEMBLY_COMP = os.path.join(PKG, "client", "tooltip", "AssemblyInputsTooltipComponent.java")
CYCLE_COMP = os.path.join(PKG, "client", "tooltip", "ItemCycleTooltipComponent.java")
LAYERS = os.path.join(PKG, "client", "tooltip", "RsccTooltipLayers.java")

problems = []


def read(path):
    with io.open(path, encoding="utf-8", errors="replace") as handle:
        return handle.read()


def check(ok, label, detail=""):
    print(("  [OK]   " if ok else "  [FAIL] ") + label + (("  | " + detail) if detail else ""))
    if not ok:
        problems.append(label)


def section(title):
    print()
    print("=" * 78)
    print(title)
    print("=" * 78)


# =====================================================================
# 工具：抽取 Java 方法体（按 `{` / `}` 配平）
# =====================================================================
def method_body(source, name):
    """返回方法（含花括号）的源码；找不到返回 None。

    只匹配**声明**（前面必须有 private/protected/public + 返回类型），因此 `getHeight()` 里对
    `machineVisible()` 的调用不会被误当成方法本体。
    """
    if source is None:
        return None
    pattern = re.compile(
        r"(?:private|protected|public)\s+(?:static\s+)?(?:final\s+)?[\w<>\[\],.?\s]+\s+"
        + re.escape(name) + r"\s*\([^;{]*\)\s*\{")
    match = pattern.search(source)
    if not match:
        return None
    brace = source.find("{", match.end() - 1)
    depth = 0
    index = brace
    while index < len(source):
        char = source[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                break
        index += 1
    return source[brace:index + 1]


# =====================================================================
# 判据 ①：两块内容「常显」= 可见性判定里不允许出现任何按键层判据
# =====================================================================
KEY_TOKENS = ("componentVisible", "Layer.SHIFT", "Layer.CTRL", "Layer.ALT",
              "hasShiftDown", "hasControlDown", "hasAltDown", "isShiftKeyDown",
              "held(")


def visibility_failures(source):
    """机器 / 输入池原料的可见性判定里若还残留按键判据 ⇒ 返回失败原因列表。"""
    fails = []
    for name in ("machineVisible", "inputVisible"):
        body = method_body(source, name)
        if body is None:
            fails.append("缺少 %s()（判据无从核对）" % name)
            continue
        for token in KEY_TOKENS:
            if token in body:
                fails.append("%s() 仍受按键 / 分层控制（含 %s）" % (name, token))
        if not re.search(r"return\s+!", body):
            fails.append("%s() 不是「有内容就显示」（没有 return !... 形式）" % name)
    return fails


# =====================================================================
# 判据 ②：常显行数上限（可验证的收敛规则）
# =====================================================================
def cap_failures(source):
    """常显行数上限必须真的写在源码里、且被高度计算与绘制共同遵守。"""
    fails = []
    if "MAX_ALWAYS_ROWS = 2" not in source:
        fails.append("缺少常显行数上限常量 MAX_ALWAYS_ROWS = 2")
    rows = method_body(source, "visibleRows")
    height = method_body(source, "getHeight")
    render = method_body(source, "renderImage")
    if rows is None:
        fails.append("缺少 visibleRows()（高度没有唯一来源）")
    else:
        if "Math.min(" not in rows or "MAX_ALWAYS_ROWS" not in rows:
            fails.append("visibleRows() 没有按 MAX_ALWAYS_ROWS 截断")
        for flag in ("machineVisible()", "inputVisible()"):
            if flag not in rows:
                fails.append("visibleRows() 没有计入 %s" % flag)
    if height is None or "visibleRows() * ICON_SIZE" not in height:
        fails.append("getHeight() 不是 visibleRows() * ICON_SIZE（高度与绘制会分叉）")
    if render is None:
        fails.append("缺少 renderImage()")
    else:
        # 一旦按候选循环加行，常显行数就不再是常数 ⇒ 上限不可验证。
        if "for (" in render:
            fails.append("renderImage() 里按候选循环加行（行数会随候选增长）")
        for flag, label in (("if (machineVisible())", "机器行"),
                            ("if (inputVisible())", "输入池原料行")):
            count = render.count(flag)
            if count != 1:
                fails.append("%s 在 renderImage() 里出现 %d 次（应恰好 1 行）" % (label, count))
    return fails


# =====================================================================
# 判据 ③：总样板（序列装配样板）的 SHIFT 结构未被改动
# =====================================================================
def assembly_failures(assembly_item, assembly_comp):
    fails = []
    # 总样板文字层：步数 / 循环 / 概率仍进 Ctrl 层，并经唯一出口追加。
    if "RsccTooltipLayers.append(tooltip, layered);" not in assembly_item:
        fails.append("总样板文字层不再经 RsccTooltipLayers.append（分层被绕过）")
    if "RsccTooltipLayers.ctrl(" not in assembly_item:
        fails.append("总样板的 Ctrl 层（步数 / 概率）被改动")
    # 总样板图标区仍按用户此前要求关闭（本次绝不重新打开）。
    if "if (true) {\n            return java.util.Optional.empty();" not in assembly_item:
        fails.append("总样板图标区被重新打开（用户此前明确要求砍掉）")
    # 总样板图标组件：主原料 / 步骤输入仍 = Shift，流体仍 = Ctrl。
    expected = {
        "ingredientVisible": "RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.SHIFT)",
        "itemsVisible": "RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.SHIFT)",
        "fluidsVisible": "RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.CTRL)",
    }
    for name, token in expected.items():
        body = method_body(assembly_comp, name)
        if body is None:
            fails.append("总样板缺少 %s()（SHIFT 结构被破坏）" % name)
        elif token not in body:
            fails.append("总样板 %s() 不再使用 %s" % (name, token))
    return fails


def head_source(rel_path):
    """取 git HEAD 版本的源码（无 git / 不在工作树 ⇒ None）。"""
    import subprocess
    try:
        proc = subprocess.run(
            ["git", "show", "HEAD:src/main/java/cretae/cookiewyq/rs_create_compat/" + rel_path],
            cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
    except Exception:
        return None
    return proc.stdout if proc.returncode == 0 else None


# =====================================================================
# 反例自证：修前写法必须被同一批判据判负
# =====================================================================
BEFORE_VISIBILITY = """
    /** 机器行是否显示：有候选机器 且 按住 Shift。 */
    private boolean machineVisible() {
        return !machines.isEmpty()
            && RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.SHIFT);
    }

    /** 输入原料行是否显示：有输入物（代表物或候选）且 按住 Shift。 */
    private boolean inputVisible() {
        return !input.isEmpty() || !inputCandidates.isEmpty()
            ? RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.SHIFT)
            : false;
    }
"""

BEFORE_CAP = """
    @Override
    public int getHeight() {
        return (machineVisible() ? ICON_SIZE : 0) + (inputVisible() ? ICON_SIZE : 0)
            + (fluidVisible() ? ICON_SIZE : 0);
    }
"""


def section_counterexample():
    print("=== 反例自证：修前写法喂给同一批判据 ⇒ 必须判负 ===")
    fails = visibility_failures(BEFORE_VISIBILITY)
    check(len(fails) >= 2, "反例 a（机器 / 输入原料仍按 Shift）被可见性判据抓到", str(fails[:3]))
    check(any("machineVisible" in f for f in fails) and any("inputVisible" in f for f in fails),
          "反例 a 同时点名 machineVisible 与 inputVisible")
    cap = cap_failures(BEFORE_CAP)
    check(len(cap) >= 1, "反例 b（没有 MAX_ALWAYS_ROWS / visibleRows 收缩）被行数判据抓到", str(cap[:3]))
    # 真·反例：git HEAD = 本轮改动前的同一文件。
    head = head_source("client/tooltip/UnitPatternTooltipComponent.java")
    if head is None:
        print("       （无 git 工作树 / HEAD 取不到 ⇒ 跳过真源码反例；合成反例仍已判负）")
        return
    head_fails = visibility_failures(head)
    check(len(head_fails) >= 2,
          "反例 c（git HEAD = 修前）的机器 / 输入原料仍按 Shift ⇒ 同批判据判负",
          str(head_fails[:3]))


# =====================================================================
# 主流程
# =====================================================================
def main():
    unit_item = read(UNIT_ITEM)
    unit_comp = read(UNIT_COMP)
    assembly_item = read(ASSEMBLY_ITEM)
    assembly_comp = read(ASSEMBLY_COMP)
    cycle_comp = read(CYCLE_COMP)

    section("① 单元样板：机器 / 输入池原料常显（源码级判据）")
    fails = visibility_failures(unit_comp)
    check(not fails, "机器行 / 输入池原料行的可见性判定里没有任何按键层判据", str(fails[:4]))
    check("MAX_ALWAYS_ROWS = 2" in unit_comp,
          "常显块被显式建模为「2 行上限」（机器 1 + 原料 1）")
    check(method_body(unit_comp, "machineVisible") is not None
          and "return !machines.isEmpty();" in method_body(unit_comp, "machineVisible"),
          "机器行判据 = 有候选机器就显示（常显）")
    input_body = method_body(unit_comp, "inputVisible") or ""
    check("return !input.isEmpty() || !inputCandidates.isEmpty();" in input_body,
          "输入池原料行判据 = 有代表物或候选就显示（常显）")
    fluid_body = method_body(unit_comp, "fluidVisible") or ""
    check("RsccTooltipLayers.componentVisible(RsccTooltipLayers.Layer.CTRL)" in fluid_body,
          "输入流体行仍按 Ctrl（本次只改用户点名的两块）")

    section("② 常显后行数上限（用户此前抱怨过 tooltip 太挤）")
    cap = cap_failures(unit_comp)
    check(not cap, "常显行数被 MAX_ALWAYS_ROWS 截断、且高度与绘制同源", str(cap[:4]))
    icon_match = re.search(r"ICON_SIZE = (\d+)", cycle_comp)
    icon = int(icon_match.group(1)) if icon_match else -1
    max_rows = int(re.search(r"MAX_ALWAYS_ROWS = (\d+)", unit_comp).group(1))
    check(icon > 0, "图标行高 ICON_SIZE 可解析", str(icon))
    check(max_rows * icon <= 40,
          "常显块像素高度 ≤ 40px（%d 行 × %dpx = %dpx）" % (max_rows, icon, max_rows * icon))

    section("③ 总序列装配样板：SHIFT / Ctrl 结构未被改动（未误伤）")
    assembly = assembly_failures(assembly_item, assembly_comp)
    check(not assembly, "总样板的文字层 / 图标行 / 图标区开关全部保持原样", str(assembly[:4]))

    section("④ 单元样板其余信息仍在分层（没有把整份 tooltip 都改成常显）")
    check("RsccTooltipLayers.append(tooltip, layered);" in unit_item,
          "单元样板文字层仍经统一出口（Shift = 配方类型名，Alt = 内部 id）")
    check("RsccTooltipLayers.shift(" in unit_item and "RsccTooltipLayers.alt(" in unit_item,
          "配方类型名 / 内部 id 仍分别属 Shift / Alt")
    check(unit_item.count("tooltip.add(") == 1,
          "单元样板仍只有 1 处常显文字 add（空白样板的结论行）",
          str(unit_item.count("tooltip.add(")))
    hover_body = method_body(unit_item, "appendHoverText") or ""
    check('LANG + "machine"' not in hover_body and 'LANG + "input"' not in hover_body,
          "机器 / 输入原料没有被复制成文字行（仍在图标组件里，避免文字与图标重复占行）")

    section("⑤ 反例自证")
    section_counterexample()

    print()
    print("=" * 78)
    if problems:
        print("SELFCHECK FAILED (%d 个问题)" % len(problems))
        for item in problems:
            print("  - %s" % item)
        return 1
    print("SELFCHECK OK：单元样板机器 / 输入池原料常显；总样板结构未动。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
