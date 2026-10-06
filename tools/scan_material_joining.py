# -*- coding: utf-8 -*-
"""扫描：还有没有别处把「多种原料」拼成一行文字（用户禁止「A 或 B / C」这种形式）。

命中判据：
  1. 字面量分隔符拼接：Component.literal(" / ") / literal("、") / literal("或")
  2. 集合聚合：String.join / Collectors.joining / joinToString
只作报告，不改代码 —— 由人逐条判断是否属于「原料候选」语境。
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
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")

PATTERNS = [
    re.compile(r'literal\(\s*"(?:/|、|或| \| |, )"'),
    re.compile(r'Component\.literal\(\s*"[/、]'),
    re.compile(r'String\.join'),
    re.compile(r'Collectors\.joining'),
    re.compile(r'joining\('),
]


def main():
    hits = 0
    for dirpath, _dirs, files in os.walk(SRC):
        for name in files:
            if not name.endswith(".java"):
                continue
            path = os.path.join(dirpath, name)
            lines = io.open(path, encoding="utf-8", errors="replace").read().splitlines()
            for index, line in enumerate(lines):
                if any(p.search(line) for p in PATTERNS):
                    hits += 1
                    print("%s:%d  %s" % (name, index + 1, line.strip()[:130]))
    print()
    print("命中 %d 处（逐条判断是否属于「原料候选」语境）" % hits)
    return 0


if __name__ == "__main__":
    sys.exit(main())
