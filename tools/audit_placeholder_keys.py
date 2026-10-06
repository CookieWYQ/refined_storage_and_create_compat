# -*- coding: utf-8 -*-
"""审计：语言文件里带 %s 的键，是否都真的被「带参数」调用过。

为什么需要（2026-10-05 用户实测）：
用户按 Shift 看到 `输入原料：%s` —— 因为某个键还带占位符，但代码已经改成不带参数地调它
（或干脆不再调它、键却留着）。MC 不会报错，只会把 `%s` 原样画出来。

判定：
  1. 扫描 Java 源码里所有 `LANG + "key"` / 字面量键的调用；
  2. 对每个「带 %s」的键，看它的调用点是否**至少有一处传了参数**；
  3. 一个调用点都没有 ⇒ 报告为「死键（带占位符）」；有调用点但全部不带参数 ⇒ 报告为「缺参数」。
"""

from __future__ import annotations

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

CONST = re.compile(r'static final String\s+([A-Z_]+)\s*=\s*"(gui\.rs_create_compat\.[A-Za-z0-9_.]*|item\.rs_create_compat\.[A-Za-z0-9_.]*)"')


def main():
    zh = json.load(io.open(os.path.join(LANG_DIR, "zh_cn.json"), encoding="utf-8"))
    with_pct = {k: v for k, v in zh.items() if "%s" in v}

    # 收集所有调用点：常量前缀 + 后缀，并看括号内是否还有别的实参
    calls = []  # (key, has_args)
    for dirpath, _dirs, files in os.walk(SRC):
        for name in files:
            if not name.endswith(".java"):
                continue
            text = io.open(os.path.join(dirpath, name), encoding="utf-8", errors="replace").read()
            constants = dict(CONST.findall(text))
            for const, prefix in constants.items():
                # 找 `const + "suffix"`，再往后看到「配对的右括号」为止，判断有没有逗号实参
                for m in re.finditer(re.escape(const) + r'\s*\+\s*"([A-Za-z0-9_.]*)"', text):
                    key = prefix + m.group(1)
                    # 该调用是否带参数：从字符串之后一直看到配对的右括号为止（跨行也算）。
                    # ⚠ 不能只看本行：真实代码大量是
                    #     CompletionBannerPayload.localized(KEY_MATERIAL_MORE,
                    #         record.materialTotal - record.materialIcons.size()));
                    # 参数在下一行，只看行尾会把正确的调用误报成「缺参数」。
                    tail = text[m.end():m.end() + 400]
                    depth = 0
                    args = ""
                    for ch in tail:
                        if ch == "(":
                            depth += 1
                        elif ch == ")":
                            if depth == 0:
                                break
                            depth -= 1
                        args += ch
                    calls.append((key, "," in args, name))

    dead = []
    noargs = []
    for key in sorted(with_pct):
        hits = [c for c in calls if c[0] == key]
        if not hits:
            dead.append(key)
        elif not any(c[1] for c in hits):
            noargs.append((key, hits[0][2]))

    print("带 %%s 的键：%d" % len(with_pct))
    print()
    print("【死键】带占位符但代码里没有任何调用点（%d）—— 应删除或改成不带占位符：" % len(dead))
    for key in dead:
        print("   %-64s %r" % (key, with_pct[key]))
    print()
    print("【缺参数】有调用点但全部没传参（%d）—— 界面上会原样显示 %%s：" % len(noargs))
    for key, where in noargs:
        print("   %-64s %r  (首个调用点: %s)" % (key, with_pct[key], where))
    print()
    print("AUDIT %s" % ("FAILED" if (dead or noargs) else "OK"))
    return 1 if (dead or noargs) else 0


if __name__ == "__main__":
    sys.exit(main())
