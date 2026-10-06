# -*- coding: utf-8 -*-
"""把「序列装配诊断日志」开关指令的语言键追加到 zh_cn.json / en_us.json。

规则（用户硬要求）：保持行序与缩进、只增删改对应行、写回后 json.load 校验。
做法：文本级插入 —— 在文件最后一行 `}` 之前追加新键；原来的最后一个键补上逗号。
用法：python tools/patch_lang_assembly_debug.py
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

KEYS_ZH = [
    ("message.rs_create_compat.assemblydebug.usage", "用法：/rs_create_compat assemblydebug <on|off>"),
    ("message.rs_create_compat.assemblydebug.current", "序列装配诊断日志：%s"),
    ("message.rs_create_compat.assemblydebug.set", "序列装配诊断日志已%s"),
    ("message.rs_create_compat.assemblydebug.on", "开启"),
    ("message.rs_create_compat.assemblydebug.off", "关闭"),
]
KEYS_EN = [
    ("message.rs_create_compat.assemblydebug.usage", "Usage: /rs_create_compat assemblydebug <on|off>"),
    ("message.rs_create_compat.assemblydebug.current", "Sequence assembly diagnostics: %s"),
    ("message.rs_create_compat.assemblydebug.set", "Sequence assembly diagnostics %s"),
    ("message.rs_create_compat.assemblydebug.on", "enabled"),
    ("message.rs_create_compat.assemblydebug.off", "disabled"),
]


def insert(path, items):
    with open(path, "r", encoding="utf-8") as f:
        text = f.read()
    before = json.loads(text)
    lines = text.split("\n")
    # 最后一个非空的 `}` 行 = 对象结尾
    close_idx = max(i for i, line in enumerate(lines) if line.strip() == "}")
    prev_idx = close_idx - 1
    prev = lines[prev_idx].rstrip()
    if prev.endswith(","):
        prev = prev[:-1]
    new_block = [prev + ","]
    for i, (key, value) in enumerate(items):
        line = "  %s: %s" % (json.dumps(key, ensure_ascii=False), json.dumps(value, ensure_ascii=False))
        if i != len(items) - 1:
            line += ","
        new_block.append(line)
    lines = lines[:prev_idx] + new_block + lines[close_idx:]
    out = "\n".join(lines)
    with open(path, "w", encoding="utf-8") as f:
        f.write(out)
    after = json.loads(open(path, "r", encoding="utf-8").read())  # 写回后必须能解析
    for key, value in items:
        if after.get(key) != value:
            print("[FAIL] %s 回写校验不一致: %s" % (path, key))
            return False
    if len(after) != len(before) + len(items):
        print("[FAIL] %s 键数异常: %d -> %d" % (path, len(before), len(after)))
        return False
    print("[OK] %s 已追加 %d 个键，键数 %d -> %d" % (path, len(items), len(before), len(after)))
    return True


def main():
    ok = insert(os.path.join(LANG_DIR, "zh_cn.json"), KEYS_ZH)
    ok = insert(os.path.join(LANG_DIR, "en_us.json"), KEYS_EN) and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
