# -*- coding: utf-8 -*-
"""自检（第 40 轮 · 总样板重建不得吞掉主原料候选组）。

用法：python tools/selfcheck_round40_assembly_candidates.py
末行固定为 `SELFCHECK OK (n checks)` 或 `SELFCHECK FAILED (m/n)`。

缺陷（上游发现，本次修）
------------------------
`AssemblyWatchdog.changeStepMachine`（监视器里「更换机器」那条路）用 **5 参**
`AssemblyData` 构造器重建总样板。5 参构造器把第 6 参 `ingredientCandidates` 缺省成空表
⇒ 之后 `candidatesOrRepresentative()` 退回「代表物一件」⇒ 主原料的整组候选
（列车轨道的 `create:sleepers` = 石头台阶 / 平滑石台阶 / 安山岩台阶）**静默消失**：
玩家换过一次机器后，主原料只认一个候选。它与该文件**自己已经修过**的
`unit.inputCandidates()` 是同一类坑，只是一个在总样板层、一个在步骤层。

本自检钉住五件事
----------------
R1 两个构造器的差异有据可查：record 是 6 分量，第 6 个就是主原料候选组（`List<ItemStack>`）；
   5 参便捷构造器**已删除** ⇒ 重建总样板只能显式给出第 6 参（编译期不可再犯）。
R2 **全工程** `AssemblyData` 构造点逐个枚举（按实参个数），任何实参 < 6 的点都算「仍在丢候选组」。
R3 `changeStepMachine` 的既有语义一字未动：taskId + 产物找样板、只对正在跑的任务、
   步号越界即安全失败、原地改写同一张（不复制 / 不销毁物品）。
R4 与 `inputCandidates` **同一口径**：两层都显式透传「候选组」这一分量，不新造第二套传递方式。
R5 反例表可证伪：给出「实参个数 ⇒ 是否丢候选组」的判定规则，并用**修复前的 5 参写法**
   作为负样本验证该规则真的会报警。
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
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

FAILURES = []
CHECKS = [0]

# 修复前的那一行（负样本）：5 参写法 —— 规则必须判定它「丢候选组」。
LEGACY_FIVE_ARG = (
    "final SequencePatternData.AssemblyData updated = new SequencePatternData.AssemblyData(\n"
    "                assembly.ingredient(), assembly.loops(), units, assembly.results(), assembly.scraps());\n"
)


def check(name, ok, detail=""):
    CHECKS[0] += 1
    if not ok:
        FAILURES.append("%s%s" % (name, (" -> " + detail) if detail else ""))
    print("%s %s%s" % ("PASS" if ok else "FAIL", name, (" | " + detail) if detail else ""))


def read(*parts):
    with io.open(os.path.join(*parts), "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def strip_comments(source):
    """去注释但保留换行（行号/strip 位置仍可用）：断言「代码里没有」时不被注释字面量干扰。"""
    out = []
    i = 0
    n = len(source)
    while i < n:
        char = source[i]
        if char == "/" and i + 1 < n and source[i + 1] == "/":
            while i < n and source[i] != "\n":
                out.append(" ")
                i += 1
        elif char == "/" and i + 1 < n and source[i + 1] == "*":
            while i < n and not (source[i] == "*" and i + 1 < n and source[i + 1] == "/"):
                out.append("\n" if source[i] == "\n" else " ")
                i += 1
            out.append("  ")
            i += 2
        elif char == '"':
            out.append(char)
            i += 1
            while i < n:
                if source[i] == "\\":
                    out.append("  ")
                    i += 2
                    continue
                out.append(source[i])
                if source[i] == '"':
                    i += 1
                    break
                i += 1
        else:
            out.append(char)
            i += 1
    return "".join(out)


def iter_calls(source):
    """产出 (行号, 实参个数, 实参文本)。只认 `new ...AssemblyData(` 这一种构造调用。"""
    pattern = re.compile(r"new\s+(?:SequencePatternData\s*\.\s*)?AssemblyData\s*\(")
    for match in pattern.finditer(source):
        i = match.end()
        depth = 1
        args = []
        current = []
        while i < len(source) and depth > 0:
            char = source[i]
            if char in "([{":
                depth += 1
            elif char in ")]}":
                depth -= 1
                if depth == 0:
                    break
            if char == "," and depth == 1:
                args.append("".join(current))
                current = []
            else:
                current.append(char)
            i += 1
        args.append("".join(current))
        lineno = source.count("\n", 0, match.start()) + 1
        yield lineno, len([a for a in args if a.strip()]), [a.strip() for a in args]


def assembly_calls():
    """全工程扫描 src/main/java 下所有 .java，返回构造点列表。"""
    found = []
    for base, _dirs, files in os.walk(SRC):
        for name in sorted(files):
            if not name.endswith(".java"):
                continue
            path = os.path.join(base, name)
            rel = os.path.relpath(path, ROOT).replace("\\", "/")
            code = strip_comments(read(path))
            for lineno, argc, args in iter_calls(code):
                found.append((rel, lineno, argc, args))
    return found


def drops_candidates(argc):
    """判定规则：AssemblyData 第 6 个分量就是主原料候选组 ⇒ 实参 < 6 即「丢候选组」。"""
    return argc < 6


def main():
    data = read(SRC, "data", "SequencePatternData.java")
    watchdog = read(SRC, "support", "AssemblyWatchdog.java")
    rebind = read(SRC, "item", "AssemblyPatternRebind.java")
    terminal = read(SRC, "block", "entity", "SequencePatternTerminalBlockEntity.java")
    old_selfcheck = read(ROOT, "tools", "selfcheck_candidate_pipeline.py")

    print("=" * 78)
    print("R1 AssemblyData 的两个构造器（差异逐项）")
    print("=" * 78)
    check("锚点① record 是 6 分量，第 6 个 = 主原料候选组 ingredientCandidates",
          "public record AssemblyData(ItemStack ingredient, int loops," in data
          and "List<PatternOutput> scraps," in data
          and "List<ItemStack> ingredientCandidates) {" in data)
    check("锚点② 归一化：null 视为空表（老样板语义，缺键 ⇒ 空 ⇒ 退回代表物）",
          "ingredientCandidates = normalizeCandidates(ingredientCandidates);" in data)
    check("锚点③ 会吞候选组的 5 参便捷构造器已删除（编译期杜绝再犯）",
          "public AssemblyData(final ItemStack ingredient, final int loops, final List<UnitEntry> units,"
          not in data)
    check("锚点④ 删除只影响「重建」入口，不碰 NBT 读回：readAssembly 用 6 参显式读候选",
          "readOutputs(data, TAG_SCRAPS, registries),\n            readCandidates(data, registries));"
          in data)
    check("锚点⑤ 回落逻辑仍在（老样板 / 空候选 ⇒ 代表物一件，与改动前一字不差）",
          "public List<ItemStack> candidatesOrRepresentative() {" in data
          and "return ingredient == null || ingredient.isEmpty() ? List.of() : List.of(ingredient);" in data)

    print()
    print("=" * 78)
    print("R2 全工程 AssemblyData 构造点（实参个数逐个枚举）")
    print("=" * 78)
    calls = assembly_calls()
    for rel, lineno, argc, _args in calls:
        verdict = "丢候选组(实参<6)" if drops_candidates(argc) else "保住候选组(实参=6)"
        print("  %-72s:%-5d argc=%d  %s" % (rel, lineno, argc, verdict))
    check("锚点① 构造点非空且全部实参 >= 6（没有任何点在丢主原料候选组）",
          bool(calls) and all(not drops_candidates(argc) for _rel, _line, argc, _args in calls),
          str([(rel, line, argc) for rel, line, argc, _a in calls if drops_candidates(argc)]))
    check("锚点② 构造点全集 = 读取端 1 + 终端生成 1 + 改绑 1 + 监视器更换机器 1（多出来的点必须一并审）",
          sorted(os.path.basename(rel) for rel, _l, _a, _x in calls)
          == sorted(["SequencePatternData.java", "SequencePatternTerminalBlockEntity.java",
                     "AssemblyPatternRebind.java", "AssemblyWatchdog.java"]),
          str(sorted(os.path.basename(rel) for rel, _l, _a, _x in calls)))

    print()
    print("=" * 78)
    print("R3 changeStepMachine 既有语义不得被破坏")
    print("=" * 78)
    body = watchdog[watchdog.find("public static boolean changeStepMachine("):]
    body = body[:body.find("\n    /** 该总样板的产物里是否包含这条任务的产物资源")]
    check("锚点① 仍按 taskId 找记录（只对正在跑的任务有效）",
          "final Record record = findRecord(player, taskId);" in body
          and "if (record == null) {" in body)
    check("锚点② 仍按「产物」在样板库里找那张总样板",
          "!produces(assembly, record.product)" in body)
    check("锚点③ 步号越界 / 样板库消失 ⇒ 安全失败，不写任何东西",
          "if (stepIndex < 0 || stepIndex >= assembly.units().size()) {" in body
          and "return false; // 样板库已消失 → 安全失败" in body)
    check("锚点④ 原地改写同一张（数量不变，绝不复制 / 不销毁物品）",
          "final ItemStack replacement = stack.copyWithCount(stack.getCount());" in body
          and "inventory.setItem(slot, replacement);" in body)
    check("锚点⑤ 只改该步的机器指派字段，其余 10 个分量原样透传",
          "pos, name == null ? \"\" : name,\n                unit.recipeType(), unit.inputFluid(),"
          " unit.inputCandidates()));" in body)
    check("锚点⑥ UnitPatternDedupe 未被牵动（本轮只碰重建的候选组）",
          "UnitPatternDedupe" not in body)

    print()
    print("=" * 78)
    print("R4 与 inputCandidates 同一口径（两层都显式透传候选组）")
    print("=" * 78)
    check("锚点① 步骤层：重建 UnitEntry 时显式带 unit.inputCandidates()（既有已修路径）",
          "unit.recipeType(), unit.inputFluid(), unit.inputCandidates()));" in body)
    check("锚点② 总样板层：重建 AssemblyData 时显式带 assembly.ingredientCandidates()",
          "assembly.results(), assembly.scraps(),\n                assembly.ingredientCandidates());" in body)
    check("锚点③ 同一口径的另一处已修路径（改绑）也是 6 参，未新造第二套传递方式",
          "assembly.results(), assembly.scraps(),\n            assembly.ingredientCandidates());" in rebind)
    check("锚点④ 终端生成总样板同样是 6 参（候选来自界面算出的整组）",
          "ingredientCandidates()\n        );" in terminal)
    check("锚点⑤ 没有第二套「候选组传递」API：全工程只有 ingredientCandidates / inputCandidates / readCandidates",
          "writeCandidateGroup" not in watchdog and "setIngredientCandidateList" not in watchdog
          and data.count("public static List<ItemStack> readCandidates(") == 1)
    # 旧自检原本有一条 `check("保留旧 5 参构造器（老代码 / 老 NBT 不受影响）", ... "in data")`，
    # 那等于固化「5 参构造器可接受」；现在必须换成「它已不存在」的负向断言。
    check("锚点⑥ 旧自检不再固化「5 参可接受」：原正向锚点已删、改为断言该构造器不存在",
          "check(\"保留旧 5 参构造器（老代码 / 老 NBT 不受影响）\"" not in old_selfcheck
          and "已删除会吞主原料候选组的 5 参便捷构造器" in old_selfcheck
          and "not in data)" in old_selfcheck)

    print()
    print("=" * 78)
    print("R5 反例表（实参个数 ⇒ 是否丢候选组；含修复前写法的负样本）")
    print("=" * 78)
    print("  %-10s %-14s %s" % ("实参个数", "判定", "说明"))
    for argc, note in ((5, "修复前 changeStepMachine 的写法（主原料候选组被吞）"),
                       (6, "修复后 / 已修路径的写法（候选组显式透传）")):
        print("  %-10d %-14s %s" % (argc, "丢候选组" if drops_candidates(argc) else "保住候选组", note))
    check("反例① 规则判定 5 参 = 丢候选组（负样本能报警，规则不是恒真）",
          drops_candidates(5) is True)
    check("反例② 规则判定 6 参 = 保住候选组",
          drops_candidates(6) is False)
    legacy_args = next(iter(iter_calls(strip_comments(LEGACY_FIVE_ARG))))[1]
    check("反例③ 把**修复前那一行原文**喂给规则 ⇒ 报警（证明该规则能抓到本次这个坑）",
          drops_candidates(legacy_args) is True, "argc=%d" % legacy_args)
    fixed_args = list(iter_calls(strip_comments(body)))[0]
    check("反例④ 把**修复后的实现**喂给规则 ⇒ 不报警",
          drops_candidates(fixed_args[1]) is False and fixed_args[1] == 6,
          "argc=%d" % fixed_args[1])

    print()
    print("=" * 78)
    if FAILURES:
        print("SELFCHECK FAILED (%d/%d)" % (len(FAILURES), CHECKS[0]))
        for item in FAILURES:
            print("  - %s" % item)
        return 1
    print("SELFCHECK OK (%d checks)" % CHECKS[0])
    return 0


if __name__ == "__main__":
    sys.exit(main())
