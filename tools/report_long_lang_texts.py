# -*- coding: utf-8 -*-
"""报告语言文件里过长的文本值（用于「大幅精简描述文本」的排查）。

只读，不改文件。按字数降序列出最长的 N 条，并给出所在语言键。
用法：python tools/report_long_lang_texts.py [N]
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat"
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

TOP = int(sys.argv[1]) if len(sys.argv) > 1 else 30

for name in ("zh_cn.json", "en_us.json"):
    path = os.path.join(LANG_DIR, name)
    with io.open(path, encoding="utf-8") as f:
        data = json.load(f)
    rows = sorted(((len(str(v)), k, str(v)) for k, v in data.items()), reverse=True)[:TOP]
    total = len(data)
    over = [1 for v in data.values() if len(str(v)) > 60]
    print("===== %s：共 %d 键，其中 >60 字的 %d 条 =====" % (name, total, len(over)))
    for length, key, value in rows:
        print("%4d  %s" % (length, key))
        print("       %s" % (value[:160].replace("\n", " ") + ("…" if len(value) > 160 else "")))
    print()
