# -*- coding: utf-8 -*-
"""round48（精确版）：`if (RsccAssemblyDebug.isEnabled()) { ... }` 块里**非日志**的语句。

用「上一条语句是否已结束」判定续行，避免把跨行拼接误报；只报真正的语句。
"""
import sys, os, re

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
ROOT = os.path.join("src", "main", "java")
GUARD = re.compile(r"if \(!?RsccAssemblyDebug\.isEnabled\(\)\)\s*\{")
PURE = re.compile(r"RsccAssemblyDebug\.|LOGGER\.|RsccDiag\.")


def blocks(text):
    for m in GUARD.finditer(text):
        start = m.end() - 1
        depth = 0
        for i in range(start, len(text)):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    yield text[:m.start()].count("\n") + 1, text[start + 1:i]
                    break


out = []
for base, _d, names in os.walk(ROOT):
    for n in names:
        if not n.endswith(".java"):
            continue
        path = os.path.join(base, n)
        rel = os.path.relpath(path, ROOT).replace("\\", "/")
        text = open(path, "r", encoding="utf-8", errors="replace").read()
        for line, body in blocks(text):
            prev_open = True          # 上一条语句是否已结束
            for raw in body.splitlines():
                s = raw.strip()
                if not s or s.startswith("//") or s.startswith("*") or s.startswith("/*"):
                    continue
                is_cont = not prev_open
                prev_open = s.endswith(";") or s.endswith("{") or s.endswith("}")
                if is_cont:
                    continue
                if PURE.search(s):
                    continue
                if re.match(r"^[\"'+.),]", s) or s.startswith("{") or s.startswith("}"):
                    continue
                if s in ("return;", "return"):
                    continue
                out.append((rel, line, s))

print("=== isEnabled() 块里的非日志语句（%d 条，按文件/块行号）===" % len(out))
for rel, line, s in out:
    print("%-66s 块首:%-6d %s" % (rel, line, s[:130]))
