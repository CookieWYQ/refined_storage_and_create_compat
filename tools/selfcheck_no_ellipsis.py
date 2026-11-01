# -*- coding: utf-8 -*-
"""第 104 轮：界面文本「完整显示」验收（禁止省略号截断）。

用户口径（原话）：
「所有的这个界面的文本…尽量全部都不要出现…（省略号），所有都尽量保持全部显示完成，
  尽可能是更改他的那个排列方式而不是搞这个破（省略号）」

本脚本扫 `src/main/java/.../client/**/*.java`（剥掉注释后判定）：
  1. 禁止字符串字面量 "..."（省略号）；
  2. 禁止 while (...) 里用 font.width(...) 逐字符缩到放得下的「截断循环」；
  3. 禁止以截断为职责的私有方法（trimToWidth / truncate / cutToWidth / fitToWidth）；
  4. 必须存在唯一工具 `client/widget/RsccTextFit.java` 且提供 6 个 API、MIN_SCALE = 0.6F。

分组（写域见 task-37 / task-38；C 组等 task-31 完成后补）：
  A 组 = task-37（7 个文件）
  B 组 = task-38（9 个文件）
  C 组 = task-31 收尾后处理（CollectionCache / AdvancedCollectionCache / CollectionMarkerConfig）

退出码：0 = 全部通过；1 = 有 offender（逐文件打印行号与原文）。
"""
from __future__ import annotations

import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
CLIENT = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq",
                      "rs_create_compat", "client")
HELPER = os.path.join(CLIENT, "widget", "RsccTextFit.java")

GROUP_A = {
    "screen/AdvancedSchematicLoaderScreen.java",
    "screen/BusCategoryConfigScreen.java",
    "screen/ChamberUnitsSummaryScreen.java",
    "screen/ChargerFilterScreen.java",
    "screen/DecompositionGeneratorScreen.java",
    "screen/EnergyAuditScreen.java",
    "widget/ExporterExecutorRowWidget.java",
}
GROUP_B = {
    "screen/FEEnergyCubeScreen.java",
    "screen/NetworkResourceReceiverScreen.java",
    "screen/NetworkResourceRequesterScreen.java",
    "screen/RangeChargerScreen.java",
    "screen/SchematicLoaderScreen.java",
    "screen/SequenceExecutionChamberScreen.java",
    "screen/SequencePatternTerminalScreen.java",
    "widget/ScrollSelectWidget.java",
    "grid/ResourcePickerScreen.java",
}
GROUP_C = {
    "screen/CollectionCacheScreen.java",
    "screen/AdvancedCollectionCacheScreen.java",
    "screen/CollectionMarkerConfigScreen.java",
    "screen/AreaSelectorScreen.java",
}

FORBIDDEN_METHODS = ("trimToWidth", "truncate", "cutToWidth", "fitToWidth")
LITERAL_ELLIPSIS = re.compile(r'"\.\.\."')
TRUNC_WHILE = re.compile(r"\bwhile\s*\([^)]*font\s*\.\s*width\s*\(")
METHOD_DEF = re.compile(
    r"\b(?:private|public|protected)\s+(?:static\s+)?(?:final\s+)?[\w<>\[\], .?]+\s+"
    r"(" + "|".join(FORBIDDEN_METHODS) + r")\s*\(")
REQUIRED_API = ("fitsLine", "fitsBlock", "lineCount", "requiredScaleForLine",
                "drawFitLine", "drawFitBlock")


def strip_comments(src: str) -> str:
    """把 // 与 /* */ 注释替换成等长空白（保留换行与行号）。"""
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            while i < n and src[i] != "\n":
                out.append(" ")
                i += 1
        elif c == "/" and nxt == "*":
            while i < n and not (src[i] == "*" and i + 1 < n and src[i + 1] == "/"):
                out.append("\n" if src[i] == "\n" else " ")
                i += 1
            for _ in range(2):
                if i < n:
                    out.append(" ")
                    i += 1
        elif c == '"':
            # 字符串字面量整体保留（含转义），避免把 "//" 当注释
            out.append(c)
            i += 1
            while i < n:
                ch = src[i]
                out.append(ch)
                i += 1
                if ch == "\\" and i < n:
                    out.append(src[i])
                    i += 1
                elif ch == '"':
                    break
        else:
            out.append(c)
            i += 1
    return "".join(out)


def java_files(*roots: str) -> list[str]:
    found = []
    for root in roots:
        path = os.path.join(CLIENT, root) if root else CLIENT
        for dirpath, _dirnames, filenames in os.walk(path):
            for name in filenames:
                if name.endswith(".java"):
                    full = os.path.join(dirpath, name)
                    found.append(os.path.relpath(full, CLIENT).replace("\\", "/"))
    return sorted(set(found))


def group_of(rel: str) -> str:
    if rel in GROUP_A:
        return "A"
    if rel in GROUP_B:
        return "B"
    if rel in GROUP_C:
        return "C"
    return "-"


def main() -> int:
    checks = 0
    offenders: list[tuple[str, int, str, str]] = []

    files = java_files("")
    if not files:
        print("FAIL: 找不到 client 源码")
        return 1
    checks += 1

    for rel in files:
        full = os.path.join(CLIENT, rel)
        with open(full, "r", encoding="utf-8", errors="replace") as fh:
            raw = fh.read()
        code = strip_comments(raw)
        for lineno, line in enumerate(code.splitlines(), 1):
            if LITERAL_ELLIPSIS.search(line):
                offenders.append((rel, lineno, "省略号字面量", line.strip()))
            if TRUNC_WHILE.search(line):
                offenders.append((rel, lineno, "font.width 截断循环", line.strip()))
            m = METHOD_DEF.search(line)
            if m:
                offenders.append((rel, lineno, "截断方法定义", line.strip()))
        checks += 1

    # 唯一工具必须存在且 API 齐全
    if not os.path.isfile(HELPER):
        print("FAIL: 缺少 client/widget/RsccTextFit.java")
        return 1
    checks += 1
    with open(HELPER, "r", encoding="utf-8", errors="replace") as fh:
        helper_src = fh.read()
    for api in REQUIRED_API:
        checks += 1
        if not re.search(r"\b" + api + r"\s*\(", helper_src):
            print("FAIL: RsccTextFit 缺少 API %s" % api)
            return 1
    checks += 1
    if "MIN_SCALE = 0.6F" not in helper_src:
        print("FAIL: RsccTextFit.MIN_SCALE 必须钉死 0.6F（用户口径：不小于 0.6 倍）")
        return 1

    by_group: dict[str, int] = {}
    for rel, _lineno, _kind, _text in offenders:
        by_group[group_of(rel)] = by_group.get(group_of(rel), 0) + 1

    if offenders:
        current = None
        for rel, lineno, kind, text in sorted(offenders):
            if rel != current:
                print("\n[%s组] %s" % (group_of(rel), rel))
                current = rel
            print("  L%-5d %-18s %s" % (lineno, kind, text[:110]))
        print("")
        print("FAIL: 省略号/截断 offender 共 %d 处（A=%d，B=%d，C=%d，其它=%d）"
              % (len(offenders), by_group.get("A", 0), by_group.get("B", 0),
                 by_group.get("C", 0), by_group.get("-", 0)))
        print("规则见 docs/TEXT_NO_ELLIPSIS_RULES.md；工具见 client/widget/RsccTextFit.java")
        return 1

    print("SELFCHECK OK (%d checks)：client 源码 %d 个文件，零省略号、零截断循环、零截断方法"
          % (checks, len(files)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
