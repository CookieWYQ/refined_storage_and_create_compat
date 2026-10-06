# -*- coding: utf-8 -*-
"""校验：Java 源码里引用的每一个本模组语言键，在 zh_cn / en_us 里都存在。

为什么需要它：改文案时很容易「删掉一个键、代码还在用」——MC 只会显示原始键名（玩家看到
`gui.rs_create_compat.xxx` 这种鬼东西），不会报错，于是要等截图才发现。本脚本把它变成一条命令。

识别两种写法：
  1) 字面量：`Component.translatable("gui.rs_create_compat.xxx")`
  2) 前缀常量拼接：`private static final String LANG = "gui.rs_create_compat.a.";` + `LANG + "b"`

注意：常量本身的值（以 `.` 结尾）是<b>前缀</b>，不是键，必须排除，否则会报一堆假缺键。
"""

import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src", "main", "java", "cretae", "cookiewyq", "rs_create_compat")
LANG_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "rs_create_compat", "lang")

LITERAL = re.compile(r'"(gui\.rs_create_compat\.[A-Za-z0-9_.]+)"')
CONST = re.compile(r'static final String\s+([A-Z_]+)\s*=\s*"(gui\.rs_create_compat\.[A-Za-z0-9_.]*)"')
CONCAT = re.compile(r'\b([A-Z_]+)\s*\+\s*"([A-Za-z0-9_.]*)"')


def main():
    zh = json.load(io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8"))
    en = json.load(io.open(os.path.join(LANG_DIR, "en_us.json"), encoding="utf-8"))

    used = set()
    for dirpath, _dirs, files in os.walk(SRC):
        for name in files:
            if not name.endswith(".java"):
                continue
            text = io.open(os.path.join(dirpath, name), encoding="utf-8", errors="replace").read()
            used.update(LITERAL.findall(text))
            constants = dict(CONST.findall(text))
            for const, suffix in CONCAT.findall(text):
                if const in constants:
                    used.add(constants[const] + suffix)
    # 前缀常量（以 `.` 结尾）不是键：它只是拼接的一部分，真正用到的键已由 CONCAT 展开
    used = {key for key in used if not key.endswith(".")}

    missing_zh = sorted(k for k in used if k not in zh)
    missing_en = sorted(k for k in used if k not in en)
    print("源码引用的本模组键：%d" % len(used))
    print("中文缺键：%d" % len(missing_zh))
    for key in missing_zh:
        print("  - %s" % key)
    print("英文缺键：%d" % len(missing_en))
    for key in missing_en:
        print("  - %s" % key)
    if missing_zh or missing_en:
        print("VERIFY LANG REFS FAILED")
        return 1
    print("VERIFY LANG REFS OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
