# -*- coding: utf-8 -*-
"""为「一键诊断快照」指令添加中英成对语言键（幂等；前后 JSON 校验 + 中文 ≤ 40 字）。

用法：python tools/add_diag_lang.py
退出码：0 = 成功 / 无改动；1 = 校验失败。
"""
import io
import json
import os
import sys
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

# 键 → (zh_cn, en_us)
KEYS = {
    "message.rs_create_compat.diag.exported": (
        "已导出诊断快照：%s（%s 行）",
        "Diagnostics exported: %s (%s lines)",
    ),
}
ZH_LIMIT = 40


def load(name):
    with io.open(os.path.join(LANG_DIR, name), encoding="utf-8") as handle:
        return json.load(handle)


def save(name, data):
    with io.open(os.path.join(LANG_DIR, name), "w", encoding="utf-8", newline="\n") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
        handle.write("\n")


def main():
    zh = load("zh_cn.json")
    en = load("en_us.json")
    problems = []
    for key, (zh_text, en_text) in KEYS.items():
        if len(zh_text) > ZH_LIMIT:
            problems.append("中文超过 %d 字：%s = %s" % (ZH_LIMIT, key, zh_text))
        if "%s" not in zh_text or "%s" not in en_text:
            problems.append("缺少占位符 %%s：%s" % key)
    if problems:
        for problem in problems:
            print("[FAIL] %s" % problem)
        return 1

    changed = 0
    for key, (zh_text, en_text) in KEYS.items():
        if zh.get(key) != zh_text:
            zh[key] = zh_text
            changed += 1
        if en.get(key) != en_text:
            en[key] = en_text
            changed += 1

    if changed:
        # 前后一致性校验（写盘前）：两个文件键集合不得出现单边键
        if set(zh) != set(en):
            print("[FAIL] zh / en 键集合不一致，拒绝写入")
            return 1
        save("zh_cn.json", zh)
        save("en_us.json", en)
    print("[OK] diag 语言键 %d 条；本次改动 %d 处；zh=%d / en=%d"
          % (len(KEYS), changed, len(zh), len(en)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
