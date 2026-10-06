# -*- coding: utf-8 -*-
"""为「分隔框架 · FTB Ultimine 连锁套壳」写入中英文语言键。

要求（本仓库规则）：有格式要求的文件用 python 脚本生成/改写，不手写。
本脚本只做「在指定锚点行后插入若干行」，并顺带把 JSON 的尾逗号规范化
（最后一条键不带逗号、其余键行带逗号）——锚点恰好是最后一条键时，
「插在它后面」必须先把它的逗号补上、再让新的最后一条不带逗号，否则整个语言文件会失效。
写回前先用 json.load 校验内容合法，写回后再校验一次；已存在的键会跳过（可重复执行）。
"""
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = r"d:\MODS\refined_storage_and_create_compat"
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

# 锚点：插到「取下框架」那条之后（这几条都是分隔框架的 actionbar 提示，保持相邻）
ANCHOR = '"message.rs_create_compat.frame_unsheathed"'

ZH = [
    ("message.rs_create_compat.frame_chain_sheathed", "已连锁套壳 %s 格"),
]

EN = [
    ("message.rs_create_compat.frame_chain_sheathed", "Chained framing: %s cells"),
]


def key_line_indexes(lines):
    return [i for i, line in enumerate(lines) if line.lstrip().startswith('"')]


def normalize_trailing_commas(lines):
    """规范化尾逗号：最后一条键不带逗号，其余键行都带逗号（只动这几行，其余行原样）。"""
    indexes = key_line_indexes(lines)
    if not indexes:
        return
    last = indexes[-1]
    lines[last] = lines[last].rstrip().rstrip(",")
    for i in indexes[:-1]:
        if not lines[i].rstrip().endswith(","):
            lines[i] = lines[i].rstrip() + ","


def patch(filename, entries):
    path = os.path.join(LANG_DIR, filename)
    with open(path, "rb") as handle:
        raw = handle.read()
    terminator = "\r\n" if b"\r\n" in raw else "\n"
    text = raw.decode("utf-8")
    lines = text.replace("\r\n", "\n").split("\n")
    normalize_trailing_commas(lines)

    # 写回前先校验（内容非法就直接退出，绝不写坏文件）
    data = json.loads("\n".join(lines))

    anchor_index = -1
    for i, line in enumerate(lines):
        if ANCHOR in line:
            anchor_index = i
            break
    if anchor_index < 0:
        print("[X] %s 找不到锚点: %s" % (filename, ANCHOR))
        return False

    insert = []
    rewritten = 0
    for key, value in entries:
        # 中文单条 ≤ 40 字（本仓库硬要求）；en 放宽到 60
        if len(value) > (60 if filename.startswith("en") else 40):
            print("[X] %s 文案过长（%d）: %s" % (key, len(value), value))
            return False
        if key in data:
            if data[key] == value:
                print("    [skip] %s 已存在且一致" % key)
                continue
            quoted = '"%s": ' % key
            for i, line in enumerate(lines):
                if line.lstrip().startswith(quoted):
                    indent = line[:len(line) - len(line.lstrip())]
                    lines[i] = '%s"%s": %s,' % (indent, key, json.dumps(value, ensure_ascii=False))
                    rewritten += 1
                    break
            continue
        insert.append('  "%s": %s,' % (key, json.dumps(value, ensure_ascii=False)))

    lines[anchor_index + 1:anchor_index + 1] = insert
    normalize_trailing_commas(lines)  # 锚点原本是最后一条时，这一步会把尾逗号放到正确的位置
    new_text = "\n".join(lines)
    check = json.loads(new_text)  # 写回前再校验一次

    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(new_text.replace("\n", terminator))

    for key, value in entries:
        if check.get(key) != value:
            print("[X] %s %s 写入不匹配: %r" % (filename, key, check.get(key)))
            return False
    print("  [OK] %s 插入 %d 行 / 改写 %d 行，json.load 校验通过（共 %d 键）"
          % (filename, len(insert), rewritten, len(check)))
    return True


if __name__ == "__main__":
    ok = patch("zh_cn.json", ZH) and patch("en_us.json", EN)
    sys.exit(0 if ok else 1)
