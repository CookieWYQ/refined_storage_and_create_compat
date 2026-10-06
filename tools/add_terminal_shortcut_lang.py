# -*- coding: utf-8 -*-
"""为「快捷键找不到终端」的短提示补语言键（中英双语），并按行插入、保持原文件行序与缩进。

用法: python tools/add_terminal_shortcut_lang.py
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

# 锚点行：插在它后面（与终端相关键放在一起）
ANCHOR = '"item.rs_create_compat.advanced_remote_terminal.mode_unavailable"'

# 文件 -> (键, 文案)
ENTRIES = {
    "zh_cn.json": (
        "item.rs_create_compat.advanced_remote_terminal.not_found",
        "没有找到高级远程多功能终端（主手/背包/饰品槽）",
    ),
    "en_us.json": (
        "item.rs_create_compat.advanced_remote_terminal.not_found",
        "No advanced remote terminal found (hand/inventory/curios)",
    ),
}


def patch(file_name, key, value):
    path = os.path.join(LANG_DIR, file_name)
    with open(path, "r", encoding="utf-8") as f:
        lines = f.read().split("\n")

    for i, line in enumerate(lines):
        if key in line:
            raise SystemExit("key already present in %s: %s" % (file_name, key))

    for i, line in enumerate(lines):
        if line.strip().startswith(ANCHOR):
            if not line.rstrip().endswith(","):
                raise SystemExit("anchor line is not comma-terminated in %s" % file_name)
            lines.insert(i + 1, '  "%s": "%s",' % (key, value))
            break
    else:
        raise SystemExit("anchor not found in %s" % file_name)

    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    return path


def main():
    for file_name, (key, value) in ENTRIES.items():
        path = patch(file_name, key, value)
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        assert data[key] == value, "round-trip mismatch for %s" % key
        print("OK  %-12s %s = %s" % (file_name, key, data[key]))

    # 双语键集合一致性
    keys = []
    for file_name in ENTRIES:
        with open(os.path.join(LANG_DIR, file_name), "r", encoding="utf-8") as f:
            keys.append(set(json.load(f).keys()))
    if keys[0] != keys[1]:
        print("WARN zh/en key sets differ: %s" % sorted(keys[0] ^ keys[1]))
        return 1
    print("OK  zh_cn / en_us key sets identical (%d keys)" % len(keys[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
