# -*- coding: utf-8 -*-
"""修复 add_refill_lang.py 的插入位置问题（锚点已不是 JSON 最后一项时，最后一条新键少了逗号）。

只做一件事：若 `message.rs_create_compat.refill.usage` 那一行以 `"` 结尾（缺逗号），给它补一个逗号。
幂等；其余一个字节都不动。
"""
import io
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")
NEEDLE = '"message.rs_create_compat.refill.usage"'

for name in ("zh_cn.json", "en_us.json"):
    path = os.path.join(LANG, name)
    with io.open(path, "r", encoding="utf-8", newline="") as handle:
        lines = handle.readlines()
    changed = False
    for index, line in enumerate(lines):
        if NEEDLE in line:
            stripped = line.rstrip("\r\n")
            if not stripped.rstrip().endswith(","):
                eol = line[len(stripped):]
                lines[index] = stripped + "," + eol
                changed = True
            break
    if changed:
        with io.open(path, "w", encoding="utf-8", newline="") as out:
            out.writelines(lines)
        print("[ok] %s 已补逗号" % name)
    else:
        print("[skip] %s 无需修改" % name)
