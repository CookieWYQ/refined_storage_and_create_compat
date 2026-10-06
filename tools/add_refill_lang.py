# -*- coding: utf-8 -*-
"""为「按缺口补发自动合成」开关（用户第 1 / 2 条）追加 5 个语言键。

为什么用脚本而不是手改：语言文件是 JSON，手改容易漏逗号 / 破坏排序；
这里只做「在既有 shortage 段之后插入若干行」的最小文本插入，其余一个字节都不动。
注意：新键统一放在 `message.rs_create_compat.refill.` 前缀下 —— 刻意避开
`gui.rs_create_compat.assembly.shortage.`（verify_single_unit_supply.py 断言它<b>恰好 4 条</b>）
与 `*.assembly.*`（selfcheck_assembly_watchdog.py 断言其<b>恰好 39 条</b>），因此不会影响既有计数断言。
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

NEW_KEYS = {
    "zh_cn.json": [
        ('  "message.rs_create_compat.refill.current": "当前补合成口径：%s",\n'),
        ('  "message.rs_create_compat.refill.on": "按缺口补发（要足缺口，绝不多要）",\n'),
        ('  "message.rs_create_compat.refill.off": "一次一份 / 分批递进（默认，与旧行为一致）",\n'),
        ('  "message.rs_create_compat.refill.set": "补合成口径已设为：%s",\n'),
        ('  "message.rs_create_compat.refill.usage": "用法：/rs_create_compat refill <on|off>"\n'),
    ],
    "en_us.json": [
        ('  "message.rs_create_compat.refill.current": "Current refill mode: %s",\n'),
        ('  "message.rs_create_compat.refill.on": "refill the gap (request exactly the deficit, never more)",\n'),
        ('  "message.rs_create_compat.refill.off": "one at a time / batched (default, same as before)",\n'),
        ('  "message.rs_create_compat.refill.set": "Autocraft refill mode set to: %s",\n'),
        ('  "message.rs_create_compat.refill.usage": "Usage: /rs_create_compat refill <on|off>"\n'),
    ],
}

ANCHOR = '"message.rs_create_compat.shortage.usage"'


def patch(path, new_lines):
    with io.open(path, "r", encoding="utf-8", newline="") as handle:
        lines = handle.readlines()
    # 幂等：已经加过就跳过
    if any("message.rs_create_compat.refill.current" in line for line in lines):
        print("[skip] %s 已包含新键" % os.path.basename(path))
        return
    for index, line in enumerate(lines):
        if ANCHOR in line:
            # 给锚点行补逗号（它原本是最后一项、没有逗号）
            if not line.rstrip("\r\n").rstrip().endswith(","):
                stripped = line.rstrip("\r\n")
                eol = line[len(stripped):]
                lines[index] = stripped + "," + eol
            lines[index + 1:index + 1] = new_lines
            with io.open(path, "w", encoding="utf-8", newline="") as out:
                out.writelines(lines)
            print("[ok] %s 已插入 %d 个键" % (os.path.basename(path), len(new_lines)))
            return
    raise SystemExit("[fail] 未找到锚点: %s" % ANCHOR)


for name, keys in NEW_KEYS.items():
    patch(os.path.join(LANG, name), keys)
